# 导航安全：独立的 `NavigationSecurityManager`（v1.08）

> 本文写 v1.08 实际交付的导航安全模块。**已接线的入口**与**没有接线的入口**分成两张表写清楚（§5、§6）——一个只在一半入口上生效的检查器，如果不说明白，就会被当成在每个入口上都生效。安全面板里那句关于 Safe Browsing 的文案为什么那样写，理由在 §7。

---

## 1. 它是什么，不是什么

**是**：一个**纯 Kotlin、无 Android 依赖**的决策器（`app/src/main/java/com/lumobrowser/app/browser/security/NavigationSecurity.kt`），加一层持有状态与记录的管理器（`NavigationSecurityManager.kt`）。四条规则：重定向环、弹窗泛滥、不可信 scheme、Safe Browsing 命中。

**不是**：

- 不是第二个"要不要在应用内打开"的判断者。那个问题由既有的 `app/src/main/java/com/lumobrowser/app/browser/WebNavigationPolicy.kt:143` 的 `classifyNavigation(url: String?)` 回答（应用内 / 询问后交给系统 / 拦下）。本模块只在**已经决定要导航**之后，回答"这一次导航是否安全"。
- 不做 HTTPS 证书校验（那是 `onReceivedSslError` 的事，见 [`VIDEO_PLAYBACK.md`](VIDEO_PLAYBACK.md) §4 的 F5 条目）。
- 不读用户的收藏、历史或设置；不联网；不上传任何东西（本项目的硬约束）。
- 不声称"拦截了危险网站"。Safe Browsing 的清单是平台的，本模块只在平台**已经判定**之后记录原因并让页面看到该看到的警告（§7）。

---

## 2. 两个权威的边界，以及一个同名陷阱

`WebNavigationPolicy.kt:20` 早就有一个 `enum class NavigationKind`（`InApp` / `ConfirmExternal` / `Blocked`）。新模块也叫 `NavigationKind`（`NavigationSecurity.kt:55`，十个取值）。两者**不同名不同义**，这是刻意的取舍：

- 合并成一个枚举会把"用户想不想在这个应用里打开"和"这次导航安不安全"两个正交的问题压成一维。
- 代价写在 `LumoWebView.kt` 的 import 区：`LumoWebView.kt` 与两者同属 `com.lumobrowser.app.browser` 父套件，按 Kotlin 的就近解析规则，**不写 import 就会解析到同套件的旧枚举**。所以 `NavigationSecurityManager` 与 `NavigationReason` 都是显式 import 进来的，并在注释里写明原因。
- 兼容性：`NavigationKind.fromId` 接受旧的 `InApp` / `ConfirmExternal` / `Blocked` 名字，所以已经存过盘的旧值仍然读得回来。

---

## 3. 决策的数据模型

| 类型 | 位置 | 说明 |
| --- | --- | --- |
| `NavigationKind` | `NavigationSecurity.kt:55` | 十种导航：`MainFrameTyped`("typed")、`MainFrameRedirect`("redirect")、`MainFrameLink`("link")、`SubFrame`、`NewWindow`、`Download`、`IntentScheme`、`CustomScheme`、`ExternalApp`、`InternalPage`。每个都有 `id` 与 `label`（`label` 只用于英文技术细节行，界面文案走本地化字符串）。 |
| `NavigationReason` | `NavigationSecurity.kt:133` | 六个理由：`RedirectLoop`("redirect_loop")、`PopupFlood`("popup_flood")、`UnparsableTarget`("unparsable")、`UntrustedScheme`("untrusted_scheme")、`SafeBrowsingHit`("safe_browsing")、`UserRule`("user_rule")。 |
| `NavigationVerdict` | `NavigationSecurity.kt:171` | 只有 `Allow` 与 `Block` 两档。**没有"警告但放行"**——一个会返回"也许"的 API 会被调用方忽略掉。 |
| `NavigationDecision` | `NavigationSecurity.kt` | `(verdict, kind, reason, detail)`，带 `val allowed: Boolean get() = verdict == NavigationVerdict.Allow`，调用方只读这一个属性。 |
| `NavigationSecurityState` | `NavigationSecurityManager.kt:22` | 对外事实：`blockedCount`、`lastBlockedUrl`、`lastReason`、`safeBrowsingAvailable`、`log`。 |
| `BlockedNavigation` | `NavigationSecurityManager.kt:37` | 一条记录：`atMillis`、`url`、`kind`、`reason`、`detail`。 |
| `MAX_SECURITY_LOG_ENTRIES` | `NavigationSecurityManager.kt:52` | `40`。记录是**有界**的：一个被灌满的日志列表就是一段缓慢的内存泄漏，而且没人会看第 41 条。 |

---

## 4. 四条规则

### 重定向环（`RedirectChain(maxEntries = 12, maxRepeats = 2)`，`NavigationSecurity.kt:501`）

同一个地址在一次导航里出现到第 **3** 次就是环。两个数字的理由都写在源码 KDoc 里（`:488-494`）：`maxEntries` 限定内存，`maxRepeats` 是一个页面在"下一次出现就算环"之前得到的配额。

`onMainFrameRequest`（`NavigationSecurityManager.kt:130`）在**用户主动发起**导航时清空链（`:147`）——这是让"返回再点一次链接"不被上一次尝试留下的环挡住的原因（KDoc `:144-146`）；点击与手输都算主动。

### 弹窗泛滥（`PopupWindowGuard(windowMs = 10_000, maxWindows = 3)`，`NavigationSecurity.kt:565`）

十秒内超过 3 个窗口请求即 `PopupFlood`。窗口是**半开**的 `(now - windowMs, now]`（`:573`）；恰好 10 秒前的那一次不算数。3 这个数字是权衡后的选择：一个分享对话框加两个支付页正好是 3，而十几个窗口的突发仍然会被拒。**这是一个会误伤真实用量的限速**，源码里明说了（`NavigationSecurityManager.kt:196-201`）：宁可放过一次突发，也不要把一个正在进行的会话打断；代价是没有手势信息的调用方需要自己判断要不要把它当建议而非硬拦。

窗口里只允许 `http`、`https`、`intent`、`about` 开窗（`:189`）。`about:blank` 特意放行——`window.open()` 合法地先产生它，拒掉会把一个正常弹窗变成一次假报错。

### 不可信 scheme

`onSchemeRequest`（`NavigationSecurityManager.kt:280`）**没有接线**，理由见 §6：scheme 的处置已经是 `WebNavigationPolicy.classifyNavigation` 的职责，两处都做决定会互相矛盾。

### Safe Browsing 命中

`onSafeBrowsingHit(url, threatId)`（`NavigationSecurityManager.kt:339`）无条件 `Block` 并记录 `SafeBrowsingHit`。它**不做**威胁判定——判定是平台的（见 §7）。

---

## 5. 已接线的入口（四条，全部有代码位置）

| 入口 | 位置 | 行为 |
| --- | --- | --- |
| 用户发起的导航 | `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt:459`（`load(url, extraHeaders)` 内） | `navigationSecurity?.onMainFrameRequest(url, isUserGesture = true, typedByUser = true)`。**返回值刻意丢弃**：这里一定会清空链，所以唯一可能的答案是 `Allow`；接一个永远为真的分支是给读者看的噪音。 |
| 页面发起的导航 | `LumoWebView.kt:820` 起（`LumoClient.shouldOverrideUrlLoading`，`return dispatchNavigation(...)` 之前） | 只有当 `request.isForMainFrame` 且 scheme 是 `http`/`https` 时才问：`onMainFrameRequest(url, isUserGesture = request.hasGesture(), typedByUser = false)`。被拒时用既有的错误页机制报告，并 `return true`（吞掉这次导航）。 |
| 页面要求的新窗口 | `LumoWebView.kt:599`（`newPopupScratch()` 的 `deliver` lambda 内） | `onNewWindow(url, isUserGesture = false)`；被拒则 `deniedPopups++` 并结束，允许则照旧 `dispatchNavigation(url, from = currentUrl, inApp = { load(url) })`。**刻意放在这里而不是 `onCreateWindow`**：那个回调只拿到一个传输消息、没有地址，在那里计数会把"开了但没去任何地方"的窗口也计入速率限制，从而误拦一个其实没开窗的页面（注释 `:595-598`）。 |
| Safe Browsing 命中 | `LumoWebView.kt:1108`（`LumoClient.onSafeBrowsingHit`，`super` 之前） | 记录原因，然后照常 `super.onSafeBrowsingHit(...)`。**本层不做判断**：平台的警告必须照原样走到用户面前。 |

被拦的三种情况都用**既有的**错误页渲染，映射关系（`LumoWebView.kt:820` 附近）：

```kotlin
code = when (reason) {
    NavigationReason.RedirectLoop -> WebViewClient.ERROR_REDIRECT_LOOP
    NavigationReason.SafeBrowsingHit -> WebViewClient.ERROR_UNSAFE_RESOURCE
    else -> WebViewClient.ERROR_UNSUPPORTED_SCHEME
}
```

选这三个 code 不是随意的：`LumoErrorPage.messageKey` 已经有它们的本地化文案，英文技术细节走 `LumoErrorPage.kt:212` 的 detail 行。新增一套错误码会得到"英文技术串出现在中文界面上"。

---

## 6. **没有**接线的入口（诚实清单）

| 方法 | 为什么没有调用者 |
| --- | --- |
| `noteSafeBrowsingUnavailable()`（`NavigationSecurityManager.kt:355`） | 平台**没有**"清单确实没在生效"的信号。`WebView.startSafeBrowsing` 自 API 28 起已废弃（默认开启）且不报告当前状态。没有信号就不编一个：默认 `safeBrowsingAvailable = true`，因此设置页那句"不可用"在实務上**不可达**（§7 说明文案为何这样写）。 |
| `onSchemeRequest()`（`:280`） | scheme 决策已经由 `WebNavigationPolicy.classifyNavigation` 负责（应用内 / 询问 / 拦下 + 既有的确认弹层与测试）。再接一个权威进来，同一件事会有两个答案。保留在模块里并带有测试，是为了将来若把 scheme 决策整体搬进来时它是可用的。 |
| `noteUserRule()`（`:370`） | 用户自定义拦截规则的功能本版没有做。不做就不假装有。 |
| `forgetWindowRequest()`（`:247`） | 用于"已经计过数的窗口其实没去任何地方"时退回计数。本版接线在**知道目的地之后**才计数（`LumoWebView.kt:599`），所以不存在这个情形。 |
| `reset()`（`:412`） | 刻意不被设置页的"清空记录"调用：`reset()` 会同时清掉**重定向链**与**弹窗时间窗**，那是"正在进行的导航"的活状态。清掉读者正在看的记录，不应该改变浏览器正在做的事——所以 `MainViewModel.clearNavigationSecurityLog()` 只调 `clearLog()`（`:399`）。 |

---

## 7. 设置页上那块面板说的是什么

位置：`app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` 的浏览器页，在广告屏蔽面板之后。三行事实：

1. **已拦截**（`securityBlockedLabel` / `securityBlockedValue(count)`）：计数来自 `blockedCount`，大于 0 时高亮。
2. **最近一次**（`securityLastLabel`）：有原因时显示"理由 + 地址"，没有时显示 `securityLastEmpty`。理由走本地化字符串 `security_reason_*`（六个理由 × 三种语言）。
3. **Safe Browsing**（`securitySafeBrowsingOn` / `securitySafeBrowsingOff`）：见下。

### 那句文案为什么是那个写法

英文原文：*"Safe Browsing is switched on: the platform checks each address against its own list, and this browser never overrides a warning it raises"*（zh-Hans：安全浏览已开启：由系统按自己的清单检查每个地址，本浏览器不会覆盖它给出的警告）。

早先写的是"此设备上安全浏览可用"——**那句话本应用证明不了**：没有任何平台信号能说明清单正在生效，而 `startSafeBrowsing` 已经废弃。现在的写法只声称两件可以被代码验证的事：应用把它开着（`app/src/main/java/com/lumobrowser/app/browser/BrowserConfigManager.kt:234` 的 Safe Browsing 开关），以及应用**从不覆盖**平台给出的警告（`LumoClient.onSafeBrowsingHit` 只记录原因、照常调 `super`，见 §5 第四行）。

"不可用"那一行同理：它只在有人明确告诉模块时才会出现，而本版没有这样的调用者。

### 记录面板

`if (navigationSecurity.log.isNotEmpty())` 才显示；每条一行，时间用 `android.text.format.DateFormat.getTimeFormat(context)` 格式化（跟随系统 12/24 小时制，不写死），宽度 58.dp，右侧是"理由 · 地址"。底部是清空按钮（testTag `SETTINGS_SECURITY_LOG_CLEAR`）与该列表是有界的说明（`securityLogNote(MAX_SECURITY_LOG_ENTRIES)`）。清空**只清记录**，见 §6 最后一行的理由。

---

## 8. 测试

**81 个测试，全绿**（`:app:testDebugUnitTest`，本轮全量 717 个测试、0 失败）：

| 文件 | 测试数 | 覆盖 |
| --- | --- | --- |
| `app/src/test/java/com/lumobrowser/app/browser/security/NavigationSecurityTest.kt` | 52 | 纯决策层：`NavigationKind`/`NavigationReason` 的 `id` 往返、`RedirectChain` 的窗口与配额边界、`PopupWindowGuard` 的半开时间窗、`UntrustedScheme` 的白名单、`NavigationDecision.allowed`。 |
| `app/src/test/java/com/lumobrowser/app/browser/security/NavigationSecurityManagerTest.kt` | 29 | 状态层：计数、记录有界、`clearLog()` 与 `reset()` 的**区别**、主动导航清链、被拒后状态发布。 |

**测不到的**（与 `VIDEO_PLAYBACK.md` §7 同一类问题）：真实 WebView 是否真的把一次导航交到 `shouldOverrideUrlLoading`、Safe Browsing 在真机上是否触发、`onSafeBrowsingHit` 的 `threatType` 实际取值。这些都需要真机或模拟器，而本环境两者都没有（`adb devices` 为空）。**没有**把这些说成"已验证"。

---

## 9. 改了哪些文件

| 文件 | 角色 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/browser/security/NavigationSecurity.kt`（887 行） | 纯决策层，无 Android 依赖 |
| `app/src/main/java/com/lumobrowser/app/browser/security/NavigationSecurityManager.kt`（479 行） | 状态、记录、`StateFlow` |
| `app/src/main/java/com/lumobrowser/app/LumoApplication.kt` | `val navigationSecurity: NavigationSecurityManager by lazy { … }`——**全进程一份**：重定向链属于一次导航、弹窗时间窗属于一个页面，两份实例会对同一个会话给出两种意见，设置页也只能看到一半记录 |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | `var navigationSecurity` 属性（`:129`，可为 null 是受支持的状态：测试构造的视图没有 application，此时**没有规则生效**而不是崩溃）＋ §5 的四个接线点 |
| `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` | `val navigationSecurity = remember(vibrateApp) { vibrateApp.navigationSecurity }`，并在 `AndroidView` 的 `update` 里赋给 WebView |
| `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt` | `navigationSecurityState`、`clearNavigationSecurityLog()` |
| `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` | §7 的面板与四条搜索索引（`browser.security`、`-blocked`、`-log`、`-safe-browsing`） |
| `app/src/main/java/com/lumobrowser/app/util/LumoStrings.kt`、`ui/LumoStringsSnapshot.kt`、`res/values*/strings.xml` | 20 条 `security_*` × 三种语言 |
| `app/src/main/java/com/lumobrowser/app/ui/TestTags.kt` | `SETTINGS_SECURITY_LOG_CLEAR` |
