# 网页视频与全屏：v1.08 改了什么、为什么，以及**没有**做什么

> 本文写的是 v1.08 实际交付的媒体路径。凡说"修好了"的，都有代码位置；凡**故意没修**的，也逐条写明理由——因为其中一条（TLS 证书错误）照着"修"就等于违反本项目的硬约束。
>
> 起点不是猜测，是一份**只读诊断**：对 `browser/` 与 `ui/` 两条路径逐行读完后给出的 F1–F12 清单（不修改任何文件、不运行 Gradle）。诊断把"视频放不出来"拆成了若干个互相独立的原因，其中两个与视频本身无关，而是**全屏退不出去**与**返回键抢方向**。

---

## 1. 结论先写

| | 结论 |
| --- | --- |
| 症状主因一 | `mediaPlaybackRequiresUserGesture` 被**硬编码**为 `true` 且没有任何界面可以改（F3）。凡是脚本自行起播的播放器（下一集、跨站嵌入的播放器、广告后自动续播）都会**停在一个转圈上**，页面上没有任何东西解释为什么。 |
| 症状主因二 | 渲染进程死亡后**全屏覆盖层没人清**（F1）：播放器崩了，屏幕还是黑的，退出按钮点了没反应。 |
| 症状主因三 | 返回键优先序里**没有"页面全屏"这一档**（F2）：全屏看视频时按返回，走的是网页历史或直接关掉整个浏览界面。 |
| 已修 | F1、F2、F3、F4、F6、F12 —— 六项，外加一个新的自动播放开关（§3、§5）。 |
| 故意不改 | F5（TLS）、F8（弹窗手势）、F9（页面权限）、F10（第一方广告规则）、F11（第三方 Cookie）—— 逐条理由在 §4。 |
| 验证能力 | 单元测试覆盖"决定"这一层（`planFor`、探测脚本、重建预算），**无法**覆盖"这台设备上真的播了没有"——没有模拟器、没有真机、没有 adb。§7 明确列出。 |

---

## 2. 诊断的十二项

诊断按影响排序，编号沿用原报告，本文与提交记录使用同一套编号：

| 编号 | 严重度 | 一句话 |
| --- | --- | --- |
| F1 | 严重 | 渲染进程死亡后 `pageFullscreen` 无人清理，退出按钮变成空操作 |
| F2 | 严重 | 返回键优先序里没有"页面全屏" |
| F3 | 高 | `mediaPlaybackRequiresUserGesture` 硬编码 `true`，无模式/站点开关 |
| F4 | 高 | AUTO 阶梯的"空渲染"探针把播放器页面判成空页，然后**整页重载**，正好打断正在起来的播放器（Compat 级还把 cacheMode 设成 `LOAD_NO_CACHE`） |
| F5 | 高 | SSL 错误一律 `cancel()`，且网络安全配置只信任系统 CA |
| F6 | 中高 | 渲染进程重建**没有次数上限**且重建即重载同一 URL，可形成死循环 |
| F7 | 中 | 媒体失败被框架层静默吞掉；被广告拦掉的请求被换成一个 200 空响应 |
| F8 | 中 | `onCreateWindow` 强制要求用户手势，播放器/广告的异步 `window.open` 被静默拒绝 |
| F9 | 中低 | `onPermissionRequest` 无条件拒绝所有页面权限（摄像头/麦克风/定位） |
| F10 | 低 | 广告屏蔽默认开启且主机规则对第一方也生效，内置规则库没有媒体碎片特征 |
| F11 | 低 | 第三方 Cookie 对每个站点默认关闭 |
| F12 | 中 | 退出全屏时从不 `removeView`，页面视图被遗留在已废弃的 `FrameLayout` 里 |

---

## 3. 已修的六项

### F1｜渲染进程死亡后的全屏覆盖层

**问题**：`onRenderProcessGone` 之后，持有全屏视图的回调已经随 `retire()` 一起被丢掉，覆盖层却仍然显示；退出按钮调用的是一个**已经没有回调可调**的对象，于是"点了没反应"，屏幕保持黑色。

**修法**（三处，缺一不可）：

1. `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:769` —— 渲染进程死亡的处理里清掉 `pageFullscreen = null`。视图已经死了，覆盖层不能比它活得久。
2. `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt:291` 的 `exitFullscreen()` —— **即使已经没有回调可调，也照样调用 `onFullscreenHidden`**。理由写在它的 KDoc 里：宿主靠这个回调收掉覆盖层，而一个已 `retire` 的视图没有任何回调可触发；不补这一刀，页面的全屏视图就会停在屏幕上回不来。
3. `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:853` —— 退出动作**由视图自身状态决定**：`if (view != null && view.isFullscreen) view.exitFullscreen() else pageFullscreen = null`。渲染进程死亡后覆盖层可能还在，而替代视图从未全屏过，这时调 `exitFullscreen()` 是空操作，人就被卡住了。

### F2｜返回键优先序

`app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:1286` 起，返回键的第一档现在是**页面全屏**：全屏时先退出全屏，而不是走网页历史（`:990` 的工具栏返回按钮走同一套判断）。原来的顺序把"页面全屏"整个漏掉了，所以全屏看视频按返回会直接改变文档。

### F3｜自动播放不再硬编码

- 新偏好 `mediaAutoplayAllowed`（默认 **true**），位置 `app/src/main/java/com/lumobrowser/app/data/Model.kt:370`，持久化键 `media_autoplay_allowed`（`app/src/main/java/com/lumobrowser/app/data/SettingsRepository.kt:89`，写入 `:290`）。
- 决定落在纯函数里：`app/src/main/java/com/lumobrowser/app/browser/BrowserConfigManager.kt:169` 的 `mediaPlaybackRequiresUserGesture = !autoplayAllowed`；`planFor(..., autoplayAllowed: Boolean = false)`（`:131`）保留**安全默认**，由调用方明确传入用户的选择。
- 默认值为什么是"允许"：这段 KDoc 就是理由（`Model.kt:363-368`）——硬编码挡住的那一类播放器**没有任何界面可以改**，用户看到的是一个转圈和零解释；现在它是可见且可逆的，关掉就回到"每个视频都等一次点击"。
- 生效路径：创建时 `BrowserScreen.kt:805`，设置改变时 `BrowserScreen.kt:518` 的 `LaunchedEffect(resolved, theme.isDark, autoplayAllowed)` —— 只**重新应用设置**，不重载页面（`autoplayChanged`，`:522-527`）。改一个开关不该让正在看的页面重新加载。

### F4｜"空渲染"探针不再误判播放器

`app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt:1346` 的 `PROBE_SCRIPT` 现在把 `video,audio,canvas,object,embed` 的存在也算作"有内容"：

```js
var m=d.querySelector('video,audio,canvas,object,embed')?1:0;
```

原来的探针只看文本量与区块数，而**一个视频页在播放器起来之前的样子，恰好就是"看起来是空的"**：探测判空 → AUTO 阶梯整页重载 → 播放器刚要起来就被打断。这是那类"越优化越放不出来"的典型。KDoc（同一文件 `:1341` 附近）把这个形状写清楚了，免得以后有人把探针"简化"回去。

### F6｜渲染进程重建有了预算

- `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:2141`：`MAX_RENDERER_RESTARTS = 3`，按**崩溃的那个地址**分别计数（`restartUrlHolder`，`:772`）——换一个地址就是新的一次机会，同一地址重试到第 4 次就停。
- 停下来不是白屏：`:864` 起显示 `RendererRestartPanel`，写明发生了什么，并给一个**重试按钮**（`rendererGaveUp = false; rendererRestarts = 0`）。理由写在 `:861`：再重建一次只会用同一个文档杀掉同一个渲染进程，而"人主动点了一次重试"是新信息，也是唯一让再试一次变得合理的理由。
- 重建后的地址是**读者实际所在的位置**（`BrowserScreen.kt:814` 用 `lastUrlHolder[0]`，不是收藏里的地址），否则一次可恢复的崩溃会退化成"读到一半的页面被换掉"。

### F12｜退出全屏真的把视图摘下来

`app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:2081`：承载页面全屏视图的容器是一个 `FrameLayout` 子类，在 `onDetachedFromWindow` 里 `runCatching { removeView(view) }`。

不摘的后果是两个，KDoc 都写了（`:2077`）：视频在**没有可见 surface** 的情况下继续播放；以及第二次全屏请求抛 `child already has a parent`——平台对同一个元素会交回**同一个**视图对象。

同一段 KDoc 还写明这个容器**刻意不做**的三件事：不把视图挂到 Activity 的 decor view（那正是"视频结束后应用永久沉浸"的来源）；不在没有退路的情况下隐藏状态栏（退出按钮是本应用自己画的，画在页面视图之上）；不拥有系统栏（由调用方按销毁恢复，隐藏这件事永远只有一个主人）。

---

## 4. 故意不改的五项

写在这一节里的每一项，都是**读过、判断过、决定不动**，而不是没看见。

| 编号 | 决定 | 理由 |
| --- | --- | --- |
| **F5** | **保留** `onReceivedSslError → cancel()` | 本项目的硬约束原文是"**不忽略 HTTPS 证书错误**"。诊断建议的"子资源放行"在这里等于关掉证书校验——一个在坏证书链上继续的应用，和一个被中间人的应用，从外部看没有区别。所以证书错误**继续取消**，改进的是**可诊断性**（错误页给出是哪一类证书问题），不是放行。这条不改是照约束办事，不是遗漏。 |
| **F8** | **保留** `onCreateWindow` 的手势要求 | 这是弹窗护栏。诊断指出的代价是真的（异步 `window.open` 会被静默拒绝），但反过来的代价更贵：允许无手势开窗，等于把"页面想开多少窗就开多少窗"还给页面。缓解在别处：被拒的窗口会**计数并出现在诊断面板**里，用户能看到"有东西被拦了"，而不是无声无息。 |
| **F9** | **保留** 页面权限一律拒绝 | 摄像头/麦克风的授予需要一次**可见的、明确的**询问，本版没有做这套 UI，那就不能偷偷放行；"默认拒绝、拒绝后普通观看仍然可用"是当前唯一能诚实兑现的承诺。 |
| **F10** | **保留** 默认开启与主机规则覆盖第一方 | 见 [`AD_BLOCKING.md`](AD_BLOCKING.md) §1 的边界表——这是架构决定的，不是没做完。规则库**不含媒体碎片特征**这一点是刻意的：把 `.m3u8`/`.ts` 当成广告特征会把整部片子拦掉。 |
| **F11** | **保留** 第三方 Cookie 每站默认关闭 | 一个浏览器不应该替用户决定"跨站跟踪默认可以"。依赖第三方 Cookie 鉴权的站点需要用户在该站点设置里显式打开，这个开关存在且可见。 |

---

## 5. 新的用户可见开关

- **设置 → 浏览器 → 让页面自行开始播放**（`app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt:773` 的 Switch，testTag `SETTINGS_MEDIA_AUTOPLAY`，三种语言齐全）。
- 关掉它 = WebView 收到 `mediaPlaybackRequiresUserGesture = true`，每个视频都等一次点击；打开它 = 脚本可以起播。
- 改变**不重载页面**（见 F3 的生效路径），所以正在看的页面不会因为动了这个开关而丢掉进度。

---

## 6. 换一台设备时要手工验证什么

自动化在这里帮不上忙（§7），下面这些是**必须在真机上点一遍**的清单：

1. 打开一个视频站，进入全屏：状态栏应隐藏，屏幕上出现本应用自己的"退出全屏"按钮。
2. 全屏状态下按**返回**：应退出全屏，**不**应改变文档。
3. 全屏状态下让页面崩掉（开发者选项 → "不保留活动"或直接杀掉渲染进程）：黑屏与"退不出去"都**不应**出现。
4. 反复进出全屏两次以上：不应出现 `child already has a parent`，也不应有后台继续播放的声音。
5. 同一个地址上连续触发渲染进程死亡 4 次：第 4 次之后应停在"重建已停止"的面板上，而不是无限闪烁。
6. 关闭"让页面自行开始播放"后重新加载：视频应等待点击；重新打开后，页面**不应**被重新加载。
7. 打开一个证书有问题的站点：应看到错误页，**不应**看到页面照常加载。

---

## 7. 测试覆盖与**不能**覆盖的部分

**已由单元测试覆盖（本轮全量 717 个测试、0 失败，见 [`TESTING.md`](TESTING.md)）**：

- 自动播放的两个方向，以及**出厂偏好**的值本身：`app/src/test/java/com/lumobrowser/app/browser/BrowserConfigManagerTest.kt` 的 `autoplayFollowsTheCallerAndNeverTheMode` 与 `theShippedPreferenceLetsAPageStartItsOwnPlayer`。旧的那条 `mediaNeverAutoplaysInAnyModeOrConfiguration` 被**替换**掉了——它只断言 `planFor` 的参数默认值，因此无论应用实际发货成什么样子都会通过，这正是硬编码能活这么久的原因。
- 每种模式下的固定安全策略（JS 开窗、文件访问、混合内容等）仍由同一文件的其他测试守着。

**不能覆盖、也没有伪称覆盖的**：

| 做不到的验证 | 原因 |
| --- | --- |
| "视频在这台手机上真的播了" | 没有模拟器、没有系统镜像、没有 AVD、`adb devices` 为空；项目里也不存在任何可以启动播放器并观察画面输出的自动化设施 |
| 渲染进程死亡路径 | 需要真实 WebView 渲染进程被杀；Robolectric 的 `ShadowWebView` 不产生渲染进程，`onRenderProcessGone` 永远不会被真实触发 |
| 全屏 `removeView` 与 `child already has a parent` | 同上，需要真实平台的视图层级 |
| 探针脚本的实际判定效果 | `PROBE_SCRIPT` 是交给 WebView 执行的字符串；单元测试只能守住"它包含这些选择器"，不能守住"某个真实站点会被判成非空" |

Robolectric 侧另有一条已知限制记录在 [`TESTING.md`](TESTING.md)：**只有 SDK 33 可用**，且 `ShadowLooper.idle()` 遇到无限动画（`app/src/main/java/com/lumobrowser/app/ui/components/LumoBackground.kt` 的 `tween(31_000)`/`tween(47_000)`）永不返回，因此 `LanguageSwitchLaunchTest` 必须先 `arrangeAmbientMotionOff()`。

---

## 8. 改了哪些文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` | F1（`:769`、`:853`）、F2（`:1286`）、F6（`:772`、`:780`、`:864`、`:2141`）、F12（`:2081`）、自动播放接线（`:396`、`:518`、`:805`） |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | F4（`:1346` 的 `PROBE_SCRIPT`）、F1（`:291` 的 `exitFullscreen()`）、`applySiteConfig` 的 `autoplayAllowed`（`:431`、`:439`） |
| `app/src/main/java/com/lumobrowser/app/browser/BrowserConfigManager.kt` | `planFor(..., autoplayAllowed)`（`:127`、`:131`、`:169`）、`applySiteConfig`（`:275`） |
| `app/src/main/java/com/lumobrowser/app/data/Model.kt` | `UserPreferences.mediaAutoplayAllowed`（`:370`，含理由） |
| `app/src/main/java/com/lumobrowser/app/data/SettingsRepository.kt` | 持久化键（`:89`）、读取（`:199`）、写入（`:290`） |
| `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` | 开关与搜索索引（`:773`、`:2892`） |
| `app/src/main/java/com/lumobrowser/app/ui/TestTags.kt` | `SETTINGS_MEDIA_AUTOPLAY`（`:103`）、`BROWSER_FULLSCREEN`（`:119`） |
| `app/src/test/java/com/lumobrowser/app/browser/BrowserConfigManagerTest.kt` | 替换掉那条无法失败的自动播放测试，改为双向断言 + 出厂默认值断言 |
