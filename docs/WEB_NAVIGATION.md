# 网页导航与外部跳转

本文覆盖 v1.04 的两项核心改动：**网页连续跳转闪退的修复**，以及**外部软件跳转确认面板**。两者
共用同一处导航分类逻辑，因此放在一起说明。

**v1.06 又在此之上改了四处**，全部落在第二节：「网页跳 App 没反应」的两条根因（地址在意图建立
之前就被否掉、启动前先用一次看不见全貌的查询当闸门）、面板不再宣称「没有应用能处理该地址」、
`ShareUtil.openExternally` 删除后四条「开到别处」的路合流为一条、以及一次真实尝试的回执取代一次
查询的猜测。跨站开关自身的策略（含 1.06 新增的「重定向与子框架不移交」）见
[`EXTERNAL_BROWSER_POLICY.md`](EXTERNAL_BROWSER_POLICY.md)。

全文只描述已经落在代码里的行为。**没有一条结论来自真机运行**——本轮开发环境没有可用设备
（`adb devices` 为空、SDK 内无 `emulator.exe`、无 `system-images`），能够执行的和不能执行的验证
在第四节逐条列出，不做含糊表述。

---

## 一、导航分类：一处决定，可离线断言

### 1.1 为什么需要它

改造之前，一个导航请求的含义被三个地方分别判断，而且三者互相矛盾：

| 现象 | 旧行为 | 问题 |
| --- | --- | --- |
| `target="_blank"` 链接 | 当作弹窗走 `onCreateWindow` | 普通新标签被当成特殊路径 |
| `window.open()` 产生的 `about:blank` | 报「已拦截非网页协议」 | 正常弹窗变成假错误 |
| `tel:` / `mailto:` | 一律报错拒绝 | 本该交给拨号器/邮件客户端 |
| 未知协议 | 一律报错拒绝 | 这一点是对的，但和白名单混在一起 |

规则要能只写一遍、只测一遍，就必须有一个纯函数。`app/src/main/java/com/lumobrowser/app/browser/WebNavigationPolicy.kt`
就是这个函数。

### 1.2 三类判定

```kotlin
enum class NavigationKind { InApp, ConfirmExternal, Blocked }
fun classifyNavigation(url: String?): NavigationKind
```

判定顺序本身就是策略：

1. **空地址或没有协议** → `InApp`。它是相对导航或空文档，是 WebView 的事，不是系统的事。
2. **黑名单优先于一切**，包括白名单。这样往 `EXTERNAL_SCHEMES` 里加条目**永远不可能**重新打开一个
   文件系统协议。
3. `http` / `https` / `about` / `javascript` → `InApp`。
4. 已知的应用协议 → `ConfirmExternal`。
5. 谁也没听说过的协议 → `Blocked`。**网页没有资格发明一次跳转。**

`InApp` 的返回值是「交还给 WebView」（`shouldOverrideUrlLoading` 返回 `false`），不是「App 自己加载」。
这是官方推荐的写法，也是 history、重定向、POST body 保持正确的唯一方式。

### 1.3 三张表

| 表 | 内容 | 位置 |
| --- | --- | --- |
| `WEBVIEW_SCHEMES` | `http` `https` `about` `javascript` | `WebNavigationPolicy.kt:58` |
| `EXTERNAL_SCHEMES` | `tel` `sms` `mailto` `geo` `market` `intent` 及 40 余个应用协议 | `WebNavigationPolicy.kt:68` |
| `BLOCKED_SCHEMES` | `file` `content` `chrome` `resource` `jar` `data` `blob` `filesystem` | `WebNavigationPolicy.kt:100` |

三处刻意的不对称：

- **`about:` 在允许列表里。** `window.open()` 合法地产生 `about:blank` 作为中间文档，拒绝它会把每个
  普通弹窗都变成假的「协议被拦截」错误。
- **`javascript:` 在允许列表里。** 拒绝它并不会移除这个能力（页面可以直接从 `<script>` 调用同一个函数），
  只会打断 `javascript:void(0)` 这类占位链接，而那是真实网页里很大的一部分。
- **`data:` 不在允许列表里。** 顶层 `data:` URL 是一个地址栏文字与内容无关的文档——地址栏欺骗的标准
  载体，所以主框架导航到它一律拒绝。`data:` 图片和字体是子资源，根本不经过这个分类器。

### 1.4 `schemeOf` 里的一个坑

`example.com:8080/x` 里的 `example.com` 看起来像协议名，因为 RFC 3986 允许协议名里带 `.`，而带点的
协议是真实存在的（`google.navigation:`、`vnd.youtube:`）。语法本身分辨不了，所以判别依据是**冒号后面
跟的是什么**：端口是一串数字并且结束 authority。没有这条，用户手打的每个 `host:port` 都会被当成应用
协议而拒绝（`WebNavigationPolicy.kt:121-127`）。

### 1.5 书签比导航更严

```kotlin
fun isBookmarkable(url: String?): Boolean = UrlTools.isBrowsable(url)
```

比 `classifyNavigation` 窄，是故意的：书签是 App **自己**去打开的，中间没有网页、没有用户点击，所以
必须是一个普通网址。这条检查挡住的是「从备份恢复时把一条 `tel:` 或 `intent:` 种进收藏库」。

---

## 二、外部跳转：先问，再交出去

### 2.1 不变量

> **`LumoWebView` 永不启动 activity、永不自行解析 intent。全 App 只有一处能让页面造成外部效果，
> 而那一处会先问使用者。**

这条不是风格偏好，而是这类功能的唯一安全边界。改之前 `ShareUtil.openExternally()`
（当时在 `app/src/main/java/com/lumobrowser/app/util/FileIo.kt:33`）直接
`Intent(ACTION_VIEW, Uri.parse(url))` 就 `startActivity`，没有解析确认、没有排除自己。

**1.06 起那个函数已删除。** 它不只没有确认，还会**什么都不回报**：调用方无从知道地址有没有被接走，
于是同一个请求在两处各有一份实现，可以互相矛盾——一处拒绝交出一个明明有浏览器可用的地址，另一处
在真的没有浏览器时安静地什么都不启动。现在「开到别处」只有一条路，见
[`EXTERNAL_BROWSER_POLICY.md`](EXTERNAL_BROWSER_POLICY.md) 第 4.2 节。

### 2.2 数据流

```
网页请求 tel:/mailto:/market:/intent://...
        ↓
classifyNavigation(url) == ConfirmExternal          WebNavigationPolicy.kt:143
        ↓
LumoWebView.LumoClient.shouldOverrideUrlLoading
        ↓  return true（吞掉，什么都不启动）
onExternalNavigationRequested?.invoke(url)          LumoWebView.kt
        ↓
externalLinkOffer(url) → ExternalLinkOffer?         ExternalLinkOffer.kt
        ↓
ExternalLauncher.handlerLabel(ctx, url)             ExternalLauncher.kt:99
        ↓
ExternalLinkSheet（底部确认面板）                    ExternalLinkSheet.kt
        ↓  用户按下「打开」
ExternalLauncher.launch(context, url)               ExternalLauncher.kt:115
```

`ExternalLinkOffer` 是纯数据，`externalLinkOffer()` 是纯函数。只有 `ExternalLauncher` 碰
`PackageManager`，只有 `launch()` 启动 activity。

### 2.3 面板说清楚「你要打开什么」

面板不显示一个笼统的「此链接将离开浏览器」，而是把实际会被使用的地址显示出来：

- `ExternalLinkOffer.summary` —— 跳转的**主体**：`tel:+8613800000000` 显示号码，
  `market://details?id=com.x` 显示包名，`intent://` 显示它命中的包/数据/动作。
- `ExternalLinkOffer.url` —— **原样**的地址，等宽字体、最多 3 行、超出省略。

`subjectOf()`（`ExternalLinkOffer.kt`）只剥掉协议和查询串，不做百分号解码：面板显示的就应该是平台
将收到的字节，解码会让这个 App 的行为和别的浏览器不一样。

### 2.4 `intent://` 为什么不用 `Intent.parseUri`

这是本模块最重要的一个决定。

平台的 `Intent.parseUri()` 会接受 `component=`、`launchFlags=`、`identifier=` 这些参数，而它们正好是
**intent redirection** 的入口：一个网页可以让浏览器去拉起任意一个导出组件，甚至带上
`FLAG_GRANT_READ_URI_PERMISSION`。所以：

- `app/src/main/java/com/lumobrowser/app/browser/IntentUrl.kt` 里的 `parseIntentUrl()` 自己按
  `#Intent;` / `;` / `S.` `i.` `l.` `f.` `b.` 前缀解析，**`component` / `launchFlags` / `identifier`
  一律忽略**（`IntentUrl.kt:172`）。
- `ExternalLauncher.buildFromIntentUrl()` 从解析结果**重建** `Intent`，绝不把原始字符串交给平台。
- 数据 URI 落在 `file` `content` `javascript` `data` `blob` `chrome` `resource` `jar` `filesystem`
  上的一律判为不可用（`FORBIDDEN_DATA_SCHEMES`，`IntentUrl.kt:187`）。
- 附加项有条数上限 `MAX_EXTRAS = 24`、键长 `MAX_EXTRA_KEY_LENGTH = 128`、值长
  `MAX_EXTRA_VALUE_LENGTH = 4096`。
- **解析失败就显示为「无法开启」，而不是丢给平台去宽松解读**——平台的宽松解读正是上面那些向量的入口。
- `parseIntentUrl` 在最后一道 `takeIf { it.isActionable }`（`IntentUrl.kt:202`）会丢掉「语法合法但
  无法变成 Intent」的地址：没有 action、没有 type、没有 package，数据又是一个没有协议可套的
  `//host/path`。这种情况交给平台就是递过去一个半成品。

### 2.5 为什么每次都排除自己

**LumoBrowser 自己的 `MainActivity` 今天并没有声明任何 `VIEW` 过滤器**（`AndroidManifest.xml`
里没有），所以一次网页地址的解析不会回到自己身上。排除仍然保留：它买的是「以后加了过滤器也不会
把『用别的浏览器打开』变成『再打开 LUMO 一次』」。

`ExternalLauncher.candidates()`（`ExternalLauncher.kt`）先自己查一遍候选，
`selfComponents()` 取出本包会命中的组件，再 `putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, self)` 排除掉。

### 2.6 handler 名字：不确定就说不确定

| 情况 | 面板显示 |
| --- | --- |
| 地址不可用（如 `intent://` 解析失败） | 「此地址无法转换为可在其他应用中打开的链接」 |
| 只有一个可见候选 | 该应用的名字 |
| 有多个候选 | 「由你选择的应用」（`externalLinkAppChooser`），**不编造赢家** |
| **一个候选都看不见** | **也是「由你选择的应用」** |

`handlerLabel()` 在候选数 `!= 1` 时返回 `null`（`ExternalLauncher.kt:99`），这是刻意的。

**最后一行是 1.06 改掉的。** 以前这里显示「此设备上没有可处理该地址的应用」，而那句话是
`PackageManager` 查询**得不出**的结论：Android 11+ 的包可见性会把真实存在的处理器藏起来，而
`startActivity` 不受该过滤影响。更糟的是它正下方的确认按钮**故意保持可按**——面板一边说没有，
一边允许按下，两者互相打脸。**「没有」只能由尝试本身回答**，所以它现在只在点击之后以
`HandoffOutcome.NoHandler` 的形式出现（「此设备上没有可处理该地址的应用。」）。

### 2.7 确认按钮不等同于预检结果

```kotlin
enabled = current.actionable        // 与「有没有应用能处理」无关
```

任何「有没有应用能处理」的预检都会受包可见性影响，而 `startActivity` 不受它影响。所以点击始终
允许，「这个地址本身是否可用」是唯一能挡住按钮的条件（`intent://` 解析失败之类）。真正的答案在
点击之后由 `HandoffOutcome` 报告：

**1.06 删除了 `ExternalLauncher.canHandle`。** 它在 1.04 只是个「预览」，可到 1.06 才发现：
只要是预览，就一定会被读成结论。删掉一个判断，比让它继续说话安全。

```kotlin
sealed interface HandoffOutcome {
    data class Opened(val appLabel: String?)
    data object NoHandler
    data object Unusable
    data class Failed(val message: String)
}
```

### 2.8 包可见性：`<queries>`

`app/src/main/AndroidManifest.xml` 里新增了 `<queries>`。从 Android 11 起，一个应用只能解析
`<queries>` 放行的包，其余对 `queryIntentActivities()` / `resolveActivity()` 不可见。

声明内容是**本 App 愿意交出去的地址**，分两部分：

- 具名的标准协议：`http` `https` `tel` `sms` `mailto` `geo` `market`。
- 一条**刻意宽泛**的 `<intent><action android:name="android.intent.action.VIEW" /></intent>`。

第二条是必需的，不是图省事。网页的「在 App 中打开」横幅会指名一个应用专属协议，而这类协议没有固定
清单——`whatsapp:`、`taobao:`、`bilibili:`、`snssdk1128:` 都是写网页的人自己挑的，任何枚举都覆盖不了。
没有这一条，这个功能在最需要它的场景下就是坏的。

**它不授予任何能力。** 包可见性只是一次「装了什么的读取」，它只决定确认面板能不能说出将要打开的应用
的名字，别的什么都不决定。面板仍然先问再启动，App 从不枚举已安装应用、从不自己弹选择器、从不持有
`QUERY_ALL_PACKAGES`。

### 2.9 fallback url

`intent://...#Intent;S.browser_fallback_url=...;end` 是网页给出的「如果 App 打不开就去这里」。它：

- 由 `parseIntentUrl` 单独取出，**不放进 `stringExtras`**（`IntentUrl.kt:150`，且在条数上限之前处理，
  免得被 `MAX_EXTRAS` 挤掉），也因此不会传给目标应用。
- 只有在 `UrlTools.isBrowsable()` 通过时才保留，所以必然是 http/https。
- 在面板上是一个**独立按钮**，点了才在应用内加载。**绝不自动跟随**——自动跟随等于让网页替用户决定
  最终落到哪个地址，而那正是「确认一次跳转」要防止的事。

---

## 三、连续跳转闪退：渲染进程死亡与退役状态机

### 3.1 症状

二级页面正常，进到三级、四级页面闪退。

### 3.2 根因

崩的不是「渲染进程死掉」这件事本身，而是**死掉之后的修复路径去回调了尸体**。

`WebView` 是一个 Java 对象，它比渲染进程活得久。渲染进程被系统杀掉之后，`WebView` 上的每个方法
**仍然会正常返回**，但每一次调用都要跨进一个已经不存在的进程。这种失败是 **native abort，不是 Java
异常，所以 `runCatching` 拦不住**。唯一的防御是「不要调用」。

触发链条：同一个 `WebView` 每导航一次就累积 history / DOM / JS 堆，超过平台内存上限后系统杀掉渲染
进程 → `onRenderProcessGone` → **旧的修复码反而在这里崩**。

旧代码在 `onRenderProcessGone` 里做了三件危险的事：

1. 调 `reportError()` 构造 `BrowserState`，而 `BrowserState` 要读
   `url` / `title` / `canGoBack()` / `canGoForward()` / `copyBackForwardList()`——只有最后一个包了
   `runCatching`。
2. 回调宿主的 `onRendererGone`，宿主又去 `(dead.parent as? ViewGroup)?.removeView(dead)`。
3. `destroy()`——而宿主那边也在 `destroy()`，Compose 之后再释放第三次。

### 3.3 更贴症状的根因：`target="_blank"` 弹窗复用了同一个 WebView

上面那条解释了「渲染进程死后为什么还会崩」，但解释不了「为什么偏偏是三级页面」。把两件事连起来的是弹窗路径。

Chromium 的 `android_webview/docs/how-does-on-create-window-work.md` 有一节标题逐字就是
**"New WebView for popup window must not have been previously navigated"**；AOSP 的
`WebViewContentsClientAdapter.java` 注释亦为「createWindow 的 WebView 必须没有加载过任何 url。
不然就不能作为 createWindow 的 webview 使用。」旧代码把主 WebView 自己交了出去：

```kotlin
transport.webView = this      // this 就是刚刚导航过无数次的那个 WebView
result.sendToTarget()
```

而 `BrowserConfigManager` 里 `supportMultipleWindows = true`，所以**每一次点 `target="_blank"`
都会走进 `onCreateWindow`**。三级、四级页面正是站内 `target="_blank"` 链接最密的地方——症状的位置
就此对上，而且失败是 native SIGSEGV，`runCatching` 拦不住。

修法：**每次弹窗请求都新建一个 scratch WebView**（`newPopupScratch()`），它不挂在任何视图树上，
也从不被允许提交任何文档：

- `settings` 全部关掉：JS、图片、`blockNetworkLoads`、DOM storage、`setSupportMultipleWindows(false)`。
- `shouldOverrideUrlLoading` 的两个重载和 `onPageStarted` 都只做一件事——把地址交给一次性闸锁
  `deliver`，然后**回 `true`（取消这次导航）**。所以它永远停在 `about:blank`，因此任何时刻都可以
  安全 `destroy()`。
- 新的请求到来时先 `popupScratch?.destroy()` 再替换；`retire()` 里也一并销毁。

**两个入口共用一条分派。** 弹窗收到的地址和用户点链接得到的地址，走的是同一个
`dispatchNavigation(rawUrl, inApp)`：

```kotlin
when (classifyNavigation(rawUrl)) {
    NavigationKind.InApp           -> { inApp?.invoke(); return false }   // 交还给 WebView
    NavigationKind.ConfirmExternal -> onExternalNavigationRequested?.invoke(rawUrl) ?: 上报不支持
    NavigationKind.Blocked         -> 上报被拦截
}
return true
```

`inApp` 在两个入口下语义不同，这个差别是有意的：**点击链接时留空**（把导航交还给 WebView 才能保住
history、重定向和 POST body）；**弹窗时传 `load(url)`**（弹窗没有「正在飞的导航」可以交还，只能自己
发起）。如果只把分类逻辑写在 `shouldOverrideUrlLoading` 里，弹窗路径就会自己长出一份副本，两份迟早
会对「哪些协议留在应用内」给出不同答案。

### 3.4 退役状态机

`app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` 新增两个独立的布尔量：

| 字段 | 含义 |
| --- | --- |
| `retired` | 这个 view 已经不能再和渲染进程说话。崩溃时由崩溃本身设置。 |
| `destroyed` | `destroy()` 已经调用过。 |

**两者必须分开**：崩溃的 view 由崩溃退役，但它之后仍然必须被 `destroy()`，所以 `retired` 不能当
`destroy` 的守卫。真正必须「恰好一次」的是 `destroy`。

- `fun retire()` —— `if (retired) return`，置位后**清空全部宿主回调**（`onState`、`onRendererGone`、
  `onEmptyRender`、`onDownloadEnqueued` 等 13 个），并把 `customView` / `customViewCallback` /
  `pendingRestore` 置空。理由：这些回调指回 Compose 层，渲染进程死后仍可能有已排队的消息投递进来。
- `fun retireAndDestroy()` —— `retire()` 之后 `if (destroyed) return`，然后 `runCatching { destroy() }`。
  **唯一的拆毁路径。**

每个会跨进渲染进程的入口都加了 `if (retired) return`：
`load()`、`reloadPage()`、`goHome()`、`stopLoadingPage()`、`clearBrowsingData()`、`adoptPopup()`、
`applySiteConfig()`、`restorePendingScroll()`、`probeRenderedContent()`。

两处容易被忽略但很关键的细节：

- **`emit()` 的求值顺序。** 原来是 `onState?.invoke(BrowserState(...))`。Kotlin 求值整个引数清单之后
  才会发现 `onState` 是 null——也就是说，**对已经退役的 view 照样做完了每一次跨进程调用**。改成先
  `val callback = onState ?: return`，再构造。
- **`applySiteConfig()` 仍然推导并记录计划**，只跳过写入 `settings` 的那一半。因为诊断面板和空渲染
  闸门都要读这份计划，而它们都不碰渲染进程。

```kotlin
override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
    Log.w(TAG, "Render process gone (crashed=${detail.didCrash()})")
    val hostCallback = onRendererGone   // 必须先读成区域变量：retire() 会清掉它
    retire()
    hostCallback?.invoke()
    return true
}
```

**删掉的部分就是修复**：这里不再构造 `BrowserState`，不再 `destroy()`。返回 `true` 表示「我自己处理
了这个崩溃」，这样系统不会连带把 App 一起杀掉。

### 3.5 Compose 侧的拆除路径

`app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` 只保留**一个**销毁点：

```kotlin
onRelease = { webView -> webView.retireAndDestroy() },   // BrowserScreen.kt:672
```

`AndroidView` 的 `onReset` / `onRelease` 多载在 Compose UI 1.4+ 存在（本项目 BOM 2024.05.00 → ui
1.6.7），已编译验证可用。

- `onRendererGone`（`BrowserScreen.kt:613`）只剩三行：`webViewHolder[0] = null`、设置提示语、
  `rendererGeneration++`。`key(rendererGeneration)`（`BrowserScreen.kt:534`）让整个 browser 子树重建。
- `DisposableEffect` 的 `onDispose`（`BrowserScreen.kt:1077`）同样只调 `retireAndDestroy()`。
  `retireAndDestroy()` 是幂等的，所以谁先到谁做，重复调用无害。
- 新建 view 时加载 `lastUrlHolder[0] ?: initialUrl`（`BrowserScreen.kt:656`）而不是 `initialUrl`：
  工厂第二次执行只可能是渲染进程在三、四级深处死掉，此时把读者丢回书签的原始地址，等于弄丢他们正在
  进行的导航。

### 3.6 恢复后的行为

渲染进程死亡 → 旧 view 立即退役（不再有任何跨进程调用）→ Compose 按新 key 重建子树 → 新 `WebView`
带着 `lastUrlHolder[0]` 重新加载 → 用户看到一句「页面渲染进程已重启」。

**代价是诚实的**：页面内部状态（表单内容、JS 变量、滚动位置）丢失，这是渲染进程死亡本身造成的，
不是恢复路径造成的。能保住的是地址。

---

## 四、验证状态

### 4.1 已执行

| 项目 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| 主源码编译 | `:app:compileDebugKotlin` | `BUILD SUCCESSFUL`，错误 0、警告 0 |
| 导航与跳转单元测试 | `:app:testDebugUnitTest --tests "*WebNavigationPolicyTest"` | **34 例，失败 0、错误 0** |
| `onRelease` 多载可用性 | 同上编译 | 通过 |
| 三份 `strings.xml` | `_verify103.py` | 各 387 条，开闭标签平衡 |

`app/src/test/java/com/lumobrowser/app/browser/WebNavigationPolicyTest.kt` 覆盖：

- http/https/`about:blank`/`javascript:` 留在应用内；`data:` 顶层被拒。
- `tel:` `mailto:` `market:` `geo:` 等被交给外部；`file:` `content:` 被拒。
- 黑名单优先于白名单；`host:port` 不被误判为协议；协议名大小写不敏感。
- `intent://` 的正规例子、附加项类型解析、坏附加项被丢弃而不连累整个 intent、
  「语法合法但无法变成 Intent」被整体丢弃、`browser_fallback_url` 的分离与百分号编码不被解码、
  附加项条数上限、超长值丢弃。
- `isExternalNavigation` 与 `classifyNavigation` 一致。

### 4.2 未执行（**没有设备，无法执行**）

| 项目 | 为什么不能执行 |
| --- | --- |
| 真机安装并验证三级/四级跳转不再闪退 | `adb devices` 空清单；SDK 内无 `emulator.exe`；无 `system-images` |
| `onRenderProcessGone` 的真实触发 | 需要真机内存压力，无法在单元测试中构造 |
| 外部跳转面板的真机端到端 | 需要真实安装了拨号器/邮件/应用商店的设备 |
| `<queries>` 是否真的放行了目标应用 | 需要真机 `PackageManager` |
| 各厂商 ROM 上的 scheme 行为差异 | 需要多台设备 |

**因此本文第二、三节描述的是代码路径与静态推理，不是运行结果。** 崩溃修复的逻辑是「不再调用」，
它可以在源码层被逐条核对；但「闪退是否真的消失」只能由真机回答，本轮没有设备，不做任何声称。

---

## 五、相关文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/browser/WebNavigationPolicy.kt` | 导航分类，纯函数 |
| `app/src/main/java/com/lumobrowser/app/browser/IntentUrl.kt` | `intent://` 严格解析器 |
| `app/src/main/java/com/lumobrowser/app/browser/ExternalLinkOffer.kt` | 面板要显示的数据，纯函数 |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | 退役状态机、`shouldOverrideUrlLoading` 三路分派 |
| `app/src/main/java/com/lumobrowser/app/util/ExternalLauncher.kt` | 唯一能启动外部 activity 的地方 |
| `app/src/main/java/com/lumobrowser/app/ui/ExternalLinkSheet.kt` | 底部确认面板 |
| `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` | 接线、单一销毁点、`key(rendererGeneration)` |
| `app/src/main/AndroidManifest.xml` | `<queries>` |
| `app/src/test/java/com/lumobrowser/app/browser/WebNavigationPolicyTest.kt` | 34 例单元测试 |
