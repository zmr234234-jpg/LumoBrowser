# 返回键与 WebView 历史恢复（1.09）

本文说明 1.09 里返回键做了什么。核心是一句话：**返回走 WebView 自己的历史，不重新加载那个地址。**

---

## 一、三个不同的东西

「收藏的地址」「显示的地址」「WebView 的历史」是三件事，本文后面全部靠这个区分。

| 概念 | 是什么 | 存在哪 | 谁改它 |
| --- | --- | --- | --- |
| 收藏里存的 URL | 数据库 `bookmarks` 表 `url` 列里的字符串，写入前过 `UrlTools.normalize(url)` | `lumobrowser.db` 的一行 | 只有 `BookmarkRepository`（见 [`BOOKMARK_DATA_INTEGRITY.md`](BOOKMARK_DATA_INTEGRITY.md)） |
| 当前显示的地址 | 这一次导航请求的地址，以及 WebView 提交后报告的当前地址 | `LumoWebView` 的 `requestedUrl` / `currentUrl` | 每次导航 |
| WebView 的历史 | 引擎自己持有的 `WebBackForwardList`：每条 entry 有地址、标题，以及引擎为它保存的文档与表单状态 | WebView 内部，`copyBackForwardList()` 只读得到 | 引擎，在每次导航时 |

收藏里的 URL 与 WebView 的历史**没有任何连接**：收藏是一个字符串，历史是引擎里的一串 entry。**返回键只动第三个。**

---

## 二、返回路径的实现

### 2.1 两个入口

工具栏（`ui/BrowserScreen.kt`）：

```kotlin
onBack = {
    val view = webViewHolder[0]
    if (view != null && view.isFullscreen) {
        view.exitFullscreen()
    } else {
        view?.goBackKeepingPosition()
    }
},
onForward = { webViewHolder[0]?.goForwardKeepingPosition() },
```

系统返回键（`ui/BrowserScreen.kt`，`BackHandler(enabled = true)` 的最后一个分支）：

```kotlin
else -> {
    val view = webViewHolder[0]
    if (view == null || !view.goBackKeepingPosition()) {
        onClose()
    }
}
```

在它前面还有四个优先级更高的分支，按顺序吃掉返回：待处理的跳转提示（`pendingHandoff`）、展开的菜单（`menuExpanded`）、诊断面板（`diagnosticsOpen`）、页面全屏（`pageFullscreen`，先退出全屏）。

### 2.2 真正的动作

`app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt`：

```kotlin
fun goBackKeepingPosition(): Boolean {
    if (retired) return false
    val history = runCatching { copyBackForwardList() }.getOrNull() ?: return false
    val target = history.currentIndex - 1
    if (target < 0) return false
    pendingRestore = history.getItemAtIndex(target)?.url?.let { address ->
        scrollMemory?.recall(address)?.takeIf { it > 0 }?.let { PendingRestore(address, it) }
    }
    goBack()
    pollHistoryRestore()
    return true
}
```

`goForwardKeepingPosition()` 完全对称，只是 `val target = history.currentIndex + 1`、越界判断是 `if (target >= history.size) return false`，动作是 `goForward()`。

要点：

- 真正移动历史的是 **`goBack()` / `goForward()`**，也就是 WebView 自己的历史游标。
- 恢复滚动位置用的一次性记录 `pendingRestore` 是**按目标 entry 的地址**取的（`history.getItemAtIndex(target)?.url`），不是按「上一页」这种模糊说法取的。
- 返回值只表达「这一步动没动」：`retired`、`copyBackForwardList()` 抛异常、`target` 越界，三种情况都返回 `false`。

### 2.3 只有「无处可去」才关页面

系统返回键那一段的判定是 `if (view == null || !view.goBackKeepingPosition()) { onClose() }`。

也就是说，**关页面这件事由「历史移动报告没有地方可去」决定**，而不是由一次单独的 `canGoBack()` 检查决定。这样做的理由是：`canGoBack()` 与 `goBack()` 之间存在的状态差需要被维护两次，而返回值本身就是同一次判断的结果。

工具栏上按钮的可用性仍然读 `BrowserState` 的 `state.canGoBack` / `state.canGoForward`（由 `canGoBack()` / `canGoForward()` 构建），那只是「按钮灰不灰」，它不参与「按下去之后关不关页面」的决定。

---

## 三、这条路径刻意不做什么

`goBackKeepingPosition()` 的文档注释把这条边界写成了正文：

> What this deliberately is NOT: a `loadUrl(previousAddress)` and a `reload()`. Issuing the address again would be a fresh navigation … `goBack()` on the other hand moves the cursor the engine already holds: the entry, its document and its form state are the ones from the last visit.

翻译过来：

- **不调用 `loadUrl(previousAddress)`。** 把上一个地址再请求一次是一次**全新导航**：新的文档、新的表单状态、新的滚动位置，历史里还会多出一条。
- **不做预防性的 `reload()`。** 返回不需要任何「先刷新一下」的动作。
- 滚动位置的恢复是**叠加在历史恢复之上**的一层，不是它的替代品。

### 全量清单：`app/src/main` 里所有 `loadUrl(` 与 `reload(`

为了能自己核对「返回路径上没有这两个调用」，下面是整个 `app/src/main` 的完整命中列表：

| 文件:行 | 命中内容 | 它是什么 |
| --- | --- | --- |
| `browser/LumoWebView.kt:466` | `loadUrl(url)` | `fun load(url, extraHeaders)` 里的落点：地址栏、收藏、卡片启动的**新导航** |
| `browser/LumoWebView.kt:468` | `loadUrl(url, HashMap(extraHeaders))` | 同一个 `load(...)` 的带请求头分支 |
| `browser/LumoWebView.kt:475` | `loadUrl(previousAddress)` | **注释里的反例**，说明本条路径刻意不这么做；不是调用 |
| `browser/LumoWebView.kt:597` | `loadUrl(url)` | `fun goHome(url: String)`：回主页，是一次新导航 |
| `browser/LumoWebView.kt:475` | `reload()` | 同一句注释里的反例 |
| `browser/LumoWebView.kt:569` | `reload()` | `fun reloadPage()`：AUTO 模式升级、模式/主题重新下发、工具栏刷新按钮走的路径 |
| `adblock/AdBlockManager.kt:276` | `fun reload()` | 广告规则的**声明**，与 WebView 无关 |
| `ui/LumoStringsSnapshot.kt:77` | `val browserReload get() = …` | 字符串取用，与导航无关 |
| `ui/MainViewModel.kt:458-461` | 注释 + `app.adBlocker.reload()` | 过滤文本真的变了时重载**规则** |
| `util/LumoStrings.kt:76` | `fun browserReload(c: Context) = …` | 字符串取用，与导航无关 |

四条 `loadUrl(` 命中里，两条在 `load(...)`（新导航入口）、一条在 `goHome(...)`、一条是注释；`reload(` 的命中里只有 `reloadPage()` 一处真的会刷网页，而它由刷新按钮、AUTO 模式升级和模式/主题变更调用。**没有任何一条在返回路径上。**

---

## 四、滚动位置怎么恢复

### 4.1 记录

位置存在 `browser/ScrollMemory.kt`（纯 Kotlin，不出现 Android 类型，因此驱逐规则与「没有记录 ≠ 位置 0」这两条可以不带设备做单元测试）：

```kotlin
class ScrollMemory(private val capacity: Int = DEFAULT_CAPACITY) {
    private val offsets = LinkedHashMap<String, Int>(capacity, 0.75f, true)

    fun remember(url: String, offset: Int) {
        if (url.isBlank() || offset < 0) return
        offsets[url] = offset
        evictOverflow()
    }

    fun recall(url: String): Int? = if (url.isBlank()) null else offsets[url]

    companion object { const val DEFAULT_CAPACITY: Int = 24 }
}
```

- 按**地址**记录，`accessOrder = true`：读也算使用，所以「读了很久的长文」不会因为中间路过几个页面就被挤掉。
- 上限 24 条，超出后先丢最久没用过的。
- 空地址与负偏移被忽略（前者是首次提交前的 `WebView.url`，后者是橡皮筋回弹报出来的值）。
- `recall` 返回 `null` 与返回 `0` 是**两个不同的答案**：「没见过这个地址」与「本来就在顶部」不能混为一谈，只有前者才该跳过恢复。
- 只在浏览器页面活着的时候有效：关掉页面就全丢。它保证的是「一次重新加载不会丢掉你的位置」，不是「应用记得你昨天读到哪」。

写入它的地方是 WebView 的滚动回调：

```kotlin
override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
    …
    scrollMemory?.remember(url.orEmpty(), t)
}
```

### 4.2 重新应用

`goBackKeepingPosition()` 在调用 `goBack()` 之前，把目标 entry 的地址对应的偏移放进 `pendingRestore`，然后调用 `pollHistoryRestore()`。后者是一个有界的轮询：

- 每 150 ms 查一次（`HISTORY_RESTORE_POLL_MS = 150L`），最多 8 次（`HISTORY_RESTORE_POLLS = 8`）。
- 每次先看 `retired`，再看 `pendingRestore`；只有当 `currentUrl == pending.address` 时才 `applyPendingScroll()`。
- 8 次之后仍未匹配，就只是 `pendingRestore = null`——**放弃的是这个偏移量，不是这次导航**。

为什么不直接挂在 `onPageFinished` 上：一次由引擎已持有的文档作答的返回，**根本不会加载任何东西**，`onPageFinished` 可能永远不来。轮询与页面事件两条路都读同一个 `pendingRestore`，谁先到谁应用。

`applyPendingScroll()` 本身是 post 出去的，并且在调用原生 `scrollTo` **之前**先检查 `retired`——渲染进程已经死掉时，原生调用会中断。

---

## 五、实测记录（Android 模拟器）

设备：Android 模拟器，安装包 `com.lumobrowser.app.debug`。被测页面由本机夹具（fixture）站点提供。

### 5.1 走查步骤

1. 从收藏打开一级页；该页自己报出 `nav=navigate`，即这一次是**取回来的**。
2. 向下滚动。
3. 点页内链接跳到二级页。
4. 按返回。

### 5.2 结果

| 观察项 | 结果 |
| --- | --- |
| 一级页重新出现时的 `nav` 值 | `nav=back_forward`——文档是**从历史恢复**的，不是作为一份全新文档重新取回的 |
| 阅读位置 | 仍在链接附近，**没有**回到顶部 |
| 夹具站点的访问日志 | 该 origin 发送 `Cache-Control: no-store`；日志显示在返回这一步的**同一秒**里出现了一条 `GET /long.html` |

前两行与第三行放在一起看就是本次走查的全部事实：**在这一台 origin 上，文档确实被重新请求了一次，而历史 entry 的状态同时被恢复。** 这两件事同时发生。

本文**不**为这个组合推断机制。`no-store` 明确禁止复用缓存副本，因此重新请求是它预期内的行为；但「重新请求」与「历史状态恢复」在日志与页面自述里各自可见，至于引擎内部两者的先后与因果关系，本轮没有测量，所以不写。

### 5.2a 同一轮走查里的另一次记录（带时间戳）

这棵树（1.08 基线 + 上面的实现）上又走了一次同样四步，夹具站点日志与本机时钟对齐后是：

| 站点时间 | 请求 | 对应的动作 |
| --- | --- | --- |
| `1791825702.250` | `GET /long.html` | 从收藏打开一级页 |
| `1791825726.830` | `GET /long2.html` | 点页内链接进二级页 |
| `1791825731.496` | `GET /long.html` | **按返回** |

返回这一步与上一次请求之间隔了约 4.7 秒，中间只有用户的一次按键，期间站点没有收到别的请求。

同一时刻页面自身报出的读数：

| 观察项 | 读数 |
| --- | --- |
| 一级页 `nav` 值 | `nav=back_forward` |
| 打开时（第 1 步）的 `nav` 值 | `nav=navigate` |
| 返回后的阅读位置 | 停在链接下方、`Section 126` 附近，**不是**页面顶部 |

第 1 步与第 4 步的两个 `nav` 值放在一起，才说明「返回」这件事在一次访问里是可以被区分出来的：新开是 `navigate`，回来是 `back_forward`。位置这一行说明 `pendingRestore` 这条路径确实生效——按返回之后页面没有停在顶部。

### 5.3 对照：更早的一次等价走查

在**另一次更早的**走查里，同一个夹具 origin 以 `public, max-age=600` 提供页面。那次走查中：

| 观察项 | 结果 |
| --- | --- |
| 返回时站点收到的请求 | **一条也没有** |
| `nav` 值 | `nav=back_forward` |
| 阅读位置 | 保留 |

这是上面 5.2 的对照项，来自另一次走查，不是同一次运行的第二个条件。

### 5.4 已知未做的事

- 本轮**没有**测量 `pollHistoryRestore()` 实际用掉了几次轮询。150 ms × 8 次是代码里的上限，不是设备上的观测值。
- 本轮**没有**在返回后继续滚动再前进/后退多次来验证 `ScrollMemory` 的 24 条上限。
- 表单状态（输入框内容）是否随历史恢复，本轮**没有**专门构造用例验证。
- 本轮**没有**构造 `target="_blank"`、`window.open()`、HTTP→HTTPS 跳转或 SPA 内导航这几种特例来单独核对返回行为；走的是一条普通的同域 `<a href>` 链接。
- 本轮**没有**在返回之后继续前进（`goForwardKeepingPosition()`），前进路径只有单元测试与代码审阅覆盖，没有设备读数。
- §5.2 与 §5.3 的读数是在**返工之前**的构建上取的。返工只动了收藏页的布局分支与两个默认值（见 [`BOOKMARK_LAYOUT.md`](BOOKMARK_LAYOUT.md) §7.0a），浏览器与历史路径没有改，但**没有重跑**这两组观察来确认。

---

## 六、相关文档

- [`BOOKMARK_DATA_INTEGRITY.md`](BOOKMARK_DATA_INTEGRITY.md) —— 收藏行的写入清单，以及「返回不会写收藏」
- [`BOOKMARK_LAYOUT.md`](BOOKMARK_LAYOUT.md) —— 收藏页的两种分类栏排布
- [`WEB_NAVIGATION.md`](WEB_NAVIGATION.md) —— 导航策略与外部浏览器分流
- [`BROWSER_MODES.md`](BROWSER_MODES.md) —— AUTO 模式升级与重新下发，即 `reloadPage()` 的主要调用方
- [`TESTING.md`](TESTING.md) —— 本轮的测试数字与走查表
- [`../CHANGELOG.md`](../CHANGELOG.md) —— 1.09 变更条目
