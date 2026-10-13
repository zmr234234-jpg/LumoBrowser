# LumoBrowser 开发记录（1.0.0 → 1.08）

> 这份文档是仓库早期交付记录的原文（原本就是 `README.md`），按时间顺序记录了每一轮开发：
> 真机事故、缺陷根因、被否掉的实现，以及每一轮**没有验证什么**。
>
> - 想知道**当前版本做了什么**：先读 [`../README.md`](../README.md)。
> - 想按版本查改动：读 [`../CHANGELOG.md`](../CHANGELOG.md)——它是本文件按版本收敛后的续篇，包含 1.09 及之后的内容。
> - 文中出现的 `dist/` 是**当年交付时的产物目录**（APK / 源码包），不在本仓库里；构建结果保留在 [`build-logs/`](build-logs/) 与 [`BUILD.md`](BUILD.md) §8。
> - 文中出现的 `D:\deepseek\Website\LumoBrowser` 等绝对路径来自编写它的那台机器，换成你自己的克隆路径即可。

---
# LumoBrowser

一个 Android 原生「网址收藏 + 内置浏览器」。收藏以卡片形式呈现，点击即在应用内的 WebView 中打开——不跳转系统浏览器、不依赖任何服务端。

- **包名**：`com.lumobrowser.app`（Debug 构建为 `com.lumobrowser.app.debug`）
- **技术栈**：Kotlin · Jetpack Compose · Material 3 · Room · DataStore · Android WebView · AndroidX WebKit
- **最低版本**：Android 8.0（API 26） · **目标版本**：Android 14（API 34）
- **界面语言**：简体中文 / 繁体中文 / English

> ## 🆕 1.08（`versionCode=10`）：网页视频与全屏六项修复 · 广告屏蔽真正生效 · 导航安全新模块
>
> `versionCode` **9 → 10**，`versionName` **`1.07` → `1.08`**（Debug 变体实际显示 `1.08-debug`）。`applicationId` 未动、签名密钥未动、用户数据（收藏 / 历史 / 设置）未动。Release 包用**与 1.07 同一把密钥**签名，可以直接**覆盖**手机上已安装的 1.07，不用先卸载。
>
> **先说这一版没有解决什么**：「在手机上安装了，打不开」的**真因仍然没有找到**——找它需要那台手机的 logcat，而它至今没有拿到；本机也没有模拟器、没有系统镜像、没有可连的真机（`adb devices` 为空）。所以 1.08 **不声称修好了启动崩溃**。这一版做的是：把已经写进代码、却没真正生效或没真正接上的东西补完，并把每一项能力和它的边界一起写进文档。
>
> ### 一、网页视频与全屏：六项修复（起因是一份只读诊断）
>
> 那份诊断逐行读了浏览器与界面两侧的代码、不改任何文件，列出 F1–F12。这一版修了六项：
>
> 1. **F3 —— 最要命的一条：`mediaPlaybackRequiresUserGesture` 是硬编码 `true`**，而且界面里没有任何地方能改。凡是脚本自己起播的播放器（下一集、跨站嵌入、广告之后的续播）全都停在转圈上，页面上**没有任何解释**。现在它是一个真实偏好（默认**开**），落在纯函数里，改动**不会重载页面**。顺带修掉一条**说假话的测试**：旧断言只检查 `planFor` 的参数默认值，所以无论应用实际发货成什么样都会通过——**那正是这个硬编码能一直活到现在的原因**。
> 2. **F1 / F12 —— 全屏的两处"退出不了"**：渲染进程死亡后全屏覆盖层永不退出（退出按钮变成空操作）；退出全屏从不 `removeView`（第二次进全屏会抛 `child already has a parent`）。现在退出动作**由视图自身是否全屏决定**，全屏容器在被分离时主动摘下页面视图。
> 3. **F2 —— 返回键优先序里补上"先退出页面全屏"**，全屏看视频按返回不再顺手改变文档。
> 4. **F6 —— 渲染进程重建加了次数上限**（按地址 3 次）：用尽后停在说明面板上，并给一个**用户主动点**的重试，不再可能陷入"重建 → 崩 → 再重建"的死循环。
> 5. **F4 —— AUTO 阶梯的"空渲染"探针**会把播放器页面判成空页并整页重载，**正好打断正在起来的播放器**（Compat 级还会顺手把 cacheMode 设成 `LOAD_NO_CACHE`）。探针现在把 `video/audio/canvas/object/embed` 也算作"有内容"；另外补上一条守卫：**用户主动停止一次加载，是对这次加载的决定，不是关于这个站点的证据**，所以取消后不会再悄悄把站点爬升一档。
>
> **五项刻意不改，理由逐条写在 [`docs/VIDEO_PLAYBACK.md`](docs/VIDEO_PLAYBACK.md) §4**：SSL 证书错误仍然一律取消（本项目的硬约束就是**不忽略 HTTPS 证书错误**，"修"它等于违反约束，改为把诊断信息呈现清楚）、弹窗仍要求用户手势、页面权限仍一律拒绝、主机规则仍覆盖第一方、第三方 Cookie 仍每站默认关闭。
>
> ### 二、广告屏蔽：从"写好了"到"真的生效"
>
> 1. **媒体豁免**：`.m3u8` / `.mpd` / `.m4s` / `.ts` 不吃**主机**规则（扩展名**只从路径读**，`?file=stream.m3u8` 不算），路径规则与 `@@` 例外不受影响，同一主机上的普通文件照旧被拦。视频被"拦广告拦到播不出来"是最常见的一类误伤。
> 2. **站点级暂停**：浏览器溢出菜单里直接暂停 / 恢复本站（**涵盖子域**；恢复时**逐条复原实际存下来的条目**，然后重载本页），设置页也列出所有已暂停站点。
> 3. **规则更新**：**用户手动触发**，来源是随包常量（没有"规则地址"输入框）；**只走 HTTPS**，重定向前后各查一次，声明长度与实际读取都有上限，**解析成功前一个字节都不写**；结果是**合并**（随包列表 + 上游新增）而不是替换。
> 4. **不伪造"已是最新"**：更新结果带 `changed`——下载到的是**同一份正在用的清单**时，界面说的是"与当前规则相同"，而不是一次成功更新。
> 5. **拦截记录**：最近 60 条（有界），设置页可清空；**清空记录不会重置累计拦截数**。
> 6. **设置页回答三个问题**：现在用的是哪份规则（随包 / 已下载）、本页检查了多少请求、上次下载的时间与版本。细节与全部数字见 [`docs/AD_BLOCKING.md`](docs/AD_BLOCKING.md) 附录 §12。
>
> ### 三、导航安全：一个新的独立模块
>
> 新增 `browser/security/`（决策层 + 状态层），四条规则：**重定向环**（同一地址在一次导航里出现到第 3 次）、**弹窗泛滥**（10 秒内第 4 个窗口请求）、**不可信 scheme**、**Safe Browsing 命中**。三个值得写下来的决定：
>
> 1. **它不是第二个"要不要在应用内打开"的判断者**——那个问题仍由既有的 `WebNavigationPolicy.classifyNavigation` 回答；本模块只回答"已经决定要导航之后，这一次导航是否安全"。代价是两个同名 `NavigationKind` 并存，所以接线处必须显式 import（注释就在 import 区）。
> 2. **已接线与未接线的入口分成两张表写清楚**（[`docs/NAVIGATION_SECURITY.md`](docs/NAVIGATION_SECURITY.md) §5、§6）。未接线的四个方法各有理由，其中 `noteSafeBrowsingUnavailable()` **没有平台信号可以接**，因此设置页那句"不可用"在实务上不可达——文案据此改写成**只声称可验证的事**："应用把它开着，并且从不覆盖平台给出的警告"。
> 3. **清空记录不等于重置状态**：`clearLog()` 只清记录；`reset()`（连重定向链与弹窗时间窗一起清）刻意**不**被设置页调用——**清掉读者正在看的记录，不应该改变浏览器正在做的事**。
>
> ### 四、本机实测（不是预估）
>
> | 项目 | 结果 |
> | --- | --- |
> | 全量单元测试 `:app:testDebugUnitTest` | ✅ **718 例 / 44 个类，0 失败 0 错误 0 跳过**，`BUILD SUCCESSFUL in 38s`（上一版 628 例 / 42 类） |
> | └ 导航安全（新增） | ✅ **81 例**：`NavigationSecurityTest` 52 · `NavigationSecurityManagerTest` 29 |
> | └ 广告屏蔽（补强） | ✅ **71 例**：`AdBlockPolicyTest` 26 · `AdBlockRulesTest` 20 · `AdBlockManagerTest` 13 · `AdBlockResponsesTest` 12 |
> | Debug + Release 构建 | ✅ `BUILD SUCCESSFUL in 1m 44s`，**0 个 `e:`、0 个 `w:`**；Debug **19,535,475 字节**、Release **13,182,384 字节** |
> | 签名与清单校验 | ✅ `apksigner verify` 两个包都 exit 0；Release 无 `application-debuggable`、四种 ABI 齐全、`zipalign -c -p -v 4` 通过；两个 64 位 ABI 的 `PT_LOAD p_align` 均为 `0x4000`（16 KB） |
> | 出货包内容抽查 | ✅ 直接从 release APK 里读出三语 `security_*` 文案；`classes2.dex` 中 `NavigationSecurityManager` 命中 14 处 |
>
> ### 五、诚实边界
>
> 需要真机或真实网络才能回答的 7 条（能不能播放、全屏退出、真实站点上的误拦、站点暂停的实际行为、导航安全四入口在真实网页上的表现、崩溃自述页端到端、以及**为什么打不开**）**全部未测试**，逐条列在 [`docs/TESTING.md`](docs/TESTING.md) §0G.7 与 §4，并标明"未测试"而不是"通过"。本轮**没有**做 `--no-build-cache` 冷构建，所以"零警告"的正确读法是"**本轮改动过的文件零警告**"（边界写在同文档 §7.0）。
>
> ## 🆕 1.07（`versionCode=9`）：崩溃不再静默 · 安全模式 · 广告屏蔽 · 已签名的 Release 包
>
> `versionCode` **8 → 9**，`versionName` **`1.06` → `1.07`**（Debug 变体实际显示 `1.07-debug`）。`applicationId` 未动；用户数据（收藏 / 历史 / 设置）未动，升级不会失败。
>
> **起点是用户的一句话：「在手机上安装了，打不开，在优化一下。」** 装了 v1.06 的手机是 Android 14/15，症状是**闪一下就退回桌面**，而报告问题的人**拿不到 logcat**（「不方便，先按你的判断修」）。这个包是对那句「打不开」的回应。
>
> ### 一、先把能排除的排除掉（这一步没有找到凶手，但它把范围压小了）
>
> 本机**没有设备也没有模拟器**（`adb devices` 为空、SDK 里没有 system image、Robolectric 只有 API 33 的运行时），所以无法复现。能做的只有三件事，结论都是「**代码里没有冒烟枪**」：
>
> 1. **与用户装的那颗包逐字比对**：把 `dist/LumoBrowser-v1.06-source.zip` 解出来与当前源码比，`MainActivity.kt`（320 行）与 `AndroidManifest.xml`（204 行）**完全相同**；差异只有广告屏蔽接线的 `LumoApplication.kt`、`StartupAnimation.kt`、`ui/LumoApp.kt` 与一处 `windowSplashScreenAnimationDuration`（400 → 0）。**没有任何一项能解释启动崩溃。**
> 2. **启动路径审计**：全 `main` 源码里没有 `!!`、没有无兜底的枚举 `valueOf`（三处都是 `entries.firstOrNull { … } ?: Default`）、没有 `checkNotNull`/`require`、没有向系统设置**写入**（`WindowUtil` 只读亮度与动画缩放）；三个 `error("… read outside …")` 守门都包在正确的 provider 里，`StartupSmokeTest` 在 API 34 上真的启动 `MainActivity` 并全部通过。
> 3. **两条「装置专属」假设被否证**：**16 KB 内存页**——自解析 APK 里四个 `.so` 的 ELF program header，`PT_LOAD` 的 `p_align` 全部是 `0x4000`（16 KB），**已经符合 Android 15 的要求**，不是它；**损坏的持久值**——`AssistTargetCodec.decode` 逐行 `mapNotNull`、`split` 不抛、格式错就丢，不可能因为旧版写入的数据崩掉。
>
> ### 二、于是换一条路：让崩溃无法静默
>
> 没有 logcat，就没有诊断；那就让**应用自己记录并说明自己的死亡**。四个新文件（细节见 [`docs/CRASH_RECOVERY.md`](docs/CRASH_RECOVERY.md)）：
>
> 1. **`crash/CrashGuard.kt`** —— `Application.onCreate` 里紧跟 `super.onCreate()` 装上未捕获异常处理器。三条刻意的选择：**一定调用前一个处理器**（记录完把进程交还平台，让它照常死，绝不做吞掉异常的半死进程）、**用 `SharedPreferences` 的 `commit()` 而不是 DataStore**（写入发生在一个马上要被杀掉的线程上，同步落盘才是对的形状）、**一切有界且包在 `runCatching` 里**（会抛异常的崩溃处理器会把用户的 bug 报告换成更糟的一份）。
> 2. **`crash/CrashRecord.kt`** —— 报告是**一段人类可读的文字**，磁盘上的文件、屏幕上的文本、分享出去的内容是同一个东西。有界：每个 throwable 最多 48 帧、一条 cause 链最多 6 层、正文 12 000 字元、自指 cause 视为环并终止。`parse` 遇到不认识的内容返回 `null` 而不抛异常。
> 3. **`crash/CrashRecoveryActivity.kt`** —— 恢复页**只用平台 View**，不用 Compose、不用 WebView、不读 Compose 主题（如果崩溃出在 Compose 侧，这一页必须不碰它）。报告全文可选、可复制、可分享。
> 4. **`crash/SafeMode.kt`** —— 安全模式把动画强度设为 `0f`、动画预设设为 `Minimal`、关掉环境光斑与简化效果、关掉广告屏蔽，**其余偏好一字不动，且永不写回 DataStore**。
>
> **下次启动会先说明发生了什么。** `MainActivity.onCreate` 在 `setContent` 之前检查「待处理崩溃」：有就转到恢复页并 `finish()`，**这条路径上永远不会构建 Compose 树**。恢复页给两条路：**安全模式启动**（把环境动效全关掉再进）与**正常启动**；报告里的 `page size:`、`webview:`、`debuggable` 三行通常就能定位是哪一类问题。
>
> ### 三、另外交付一个**已签名的 Release 包**
>
> 部分 OEM ROM 会拦截或杀掉 **debuggable** 的侧载应用——那也是「闪一下就退回桌面」，而且不是代码问题。所以这一版多了一个包：`dist/LumoBrowser-v1.07-release.apk`，用工程内附带的密钥签名（`CN=LumoBrowser`，证书 SHA-256 `4beb5163…75a9`），**不可调试**；包名不带 `.debug` 后缀，**可以和 Debug 包同时装在一台手机上**。签名密钥是**个人侧载用的一次性密钥**（口令就写在 `keystore/keystore.properties` 与 [`docs/BUILD.md`](docs/BUILD.md) §6，因为它不值得保护），要上架请换成自己的。Release 变体**故意不混淆**（`isMinifyEnabled = false`）：R8 从未在真机上验证过，而 `addJavascriptInterface` 出来的桥方法是反射绑定的——一个 R8 引入的问题会和正在诊断的启动崩溃长得一模一样。
>
> ### 四、这一版还包含
>
> - **广告屏蔽**（1.07 主线功能）：请求拦截层，主机规则 + 路径片段规则 + 例外、`DecisionCache`、每 2 秒合并写一次计数，设置页可关。出厂的规则文件在 `app/src/main/assets/adblock/rules.txt`。**只拦第三方请求、只拦子资源、绝不拦主框架**；HTTPS 证书错误一个都不忽略。
> - **一个真 bug 的修复**：`LanguageSwitchLaunchTest` 曾在 Robolectric 下**永不返回**（裸 `ActivityScenario.launch` 内部的 `ShadowLooper.idle()` + 主屏漂移极光的无限动画 ⇒ 测试 JVM 自旋 20 分钟以上）。修法是在启动前把环境动效关掉（这是用户本来就有的开关，与语言判定无关）。修好之后全量套件从「跑不完」变成 **27 秒**。
>
> ### 五、测试与构建（本机实测）
>
> | 项目 | 结果 |
> | --- | --- |
> | 全量单元测试 `:app:testDebugUnitTest` | ✅ **628 例 / 42 个类，0 失败 0 错误 0 跳过**，`BUILD SUCCESSFUL in 27s`（上一版 527 例 / 33 类） |
> | └ 崩溃自述与安全模式（新增） | ✅ **38 例**：`CrashGuardTest` 14 · `CrashRecordTest` 10 · `CrashRecoveryActivityTest` 8 · `SafeModeTest` 5 · `CrashRoutingTest` 1 |
> | └ 广告屏蔽（新增） | ✅ **63 例**：`AdBlockPolicyTest` 22 · `AdBlockRulesTest` 20 · `AdBlockResponsesTest` 12 · `AdBlockManagerTest` 9 |
> | Debug + Release 构建 | ✅ `BUILD SUCCESSFUL in 2m 26s`，**0 个 `e:`、0 个 `w:`**；Debug **19,416,196 字节**、Release **13,099,148 字节**（Release 更小是因为 Debug 变体额外带着 `ui-tooling` 与 `ui-test-manifest`） |
> | 签名与清单校验 | ✅ `apksigner`：Debug = `CN=Android Debug`，Release = `CN=LumoBrowser`（v2 方案）；`aapt2 dump badging`：Release **无 `application-debuggable`**、四种 ABI 齐全 |
>
> ### 六、诚实边界
>
> **我不能保证这次一定打得开**——本机没有能复现那台手机的设备，也没有它的 logcat。能做的是：把「装上了却打不开」从一个**只能靠猜**的问题，变成一个**下次启动会自己说明**的问题；再给一个不是 debuggable 的包，用来排除 ROM 拦截这一类非代码原因。如果 1.07 仍然一进就退，**请再点开一次**：第二次启动会先显示崩溃自述页，而不是直接退出；在那一页选「安全模式启动」，并把报告分享出来。
>
> ## 🆕 1.06（`versionCode=8`）：网页跳 App 没反应的两条根因 · 跳转开关真的生效 · 设置页清理
>
> `versionCode` **7 → 8**，`versionName` **`1.05` → `1.06`**（Debug 变体实际显示 `1.06-debug`）。`applicationId`（`com.lumobrowser.app`）、签名配置、用户数据（收藏 / 历史 / 设置）一律未动。
>
> 起点是用户的一句话：**「网页跳转 App 没有反应」**，外加三个点名要修的地方。查下去发现**症状有四种，病只有一种**：**用一次看不见全貌的查询，去替一次本来会成功的尝试下结论。**
>
> 1. **根因 A：网页地址在意图建立之前就被否掉了。** `ExternalLauncher.build()` 从 `externalLinkOffer()` 开始，而那个函数只对策略判定为 `ConfirmExternal` 的地址回答——`http`/`https` **恰恰不是**那一种，它是 `InApp`。于是「网站跳转」开关交出去的**每一个**网页地址都在意图还不存在的时候撞上 `?: return null`，回来是 `Unusable`，界面说「这台设备上没有其他浏览器可以打开该地址」，而设备上明明装着浏览器。**修法是把网页地址当作它本来的样子处理**：`ACTION_VIEW` + 地址，不需要任何「offer」批准。回归测试 `webAddress_isBuiltEvenThoughTheLinkPolicyDeclinesToOfferIt` 把这两件事分开钉住：同一个地址，`externalLinkOffer(url)` 必须是 `null`，而 `ExternalLauncher.build(url)` 必须非空。
> 2. **根因 B：启动前先查一次包管理器，查不到就不启动。** `queryIntentActivities` 在 Android 11+ 会被 manifest 的 `<queries>` 过滤，而 **`startActivity` 不被过滤**。一个真实存在、真能启动的浏览器可以被一次看不见它的查询否掉；而且 `resolveActivity` 只回**偏好**处理器，会让用户看不到自己其实能选的别的应用。**修法是：查询只决定「值不值得弹选择器」，启动一律尝试，`ActivityNotFoundException` 才是「没有处理器」。** 回归测试 `invisibleHandler_isStillHandedTheAddress` 先用一次真实查询断言「这里一个处理器都看不见」，再断言 `launch()` 仍然返回 `Opened`。
> 3. **跳转开关真的生效。** 开：`alwaysChoose = true`，走**系统自己的选择器**（清单由系统产生，所以恰好是这台设备上实际装的浏览器，没有条目被凭空造出来）；关：http/https 全部留在内置 WebView。**开关状态只有一个来源**——菜单显示的勾与 `LumoWebView.externalizeCrossSite` 读的是同一个偏好，翻转在调用端做（`!preferences.browserCrossSiteExternal`），所以不存在「菜单说开了、页面还是老样子」。偏好存在 DataStore，**立即生效、重启恢复**，新增字段有默认值，升级不会失败。**取消选择器不会闪退，也不会留下一张空白页**：移交失败时**回落到内置 WebView 打开**并给出提示——那正是简报要求的「返回内置浏览器的选项」。连点去重由 `HandoffGate` 负责，因为「同一个地址的重复请求是同一次点击到了两次，不是第二次请求」。
> 4. **跳转判定补上两个条件。** `leavesForCrossSite` 从三个参数变成五个，新增 `isForMainFrame` 与 `isRedirect`：**子框架（iframe）的导航不移交**（一个嵌入的广告不该把用户从页面里带走），**服务器发的 `30x` 也不移交**（用户点的是他看到的那个链接，跟着重定向走出应用等于在一次**已经做完的**点击底下把页面换掉，再把它报告成用户从没选过的浏览器切换；而且重定向链里 `from` 描述的是正在重定向的文档，跨站比较此时没有可靠基准）。**普通页面重定向、跨域跳转、站内导航都不构成「打开 App」**，只有用户自己点下的、有把握的跨站链接才会出去。两条各有一个测试钉住：`a sub-frame navigation never leaves the app`、`a server redirect never leaves the app`。
> 5. **面板不再宣称「这台设备上没有应用能处理该地址」。** 那句结论 `PackageManager` **得不出**：包可见性会把真实存在的处理器藏起来。更糟的是它正下方的确认按钮**故意保持可按**——面板一边说没有、一边允许按下，两者互相打脸。所以 `ExternalLauncher.canHandle` **整个删掉**（`canHandle` 在仓库里 0 命中），面板只说「打开方式：某个具名应用」或者诚实的「由你选择的应用」，真正的答案由**尝试本身**在事后给出。**删掉一个判断，比让它继续说话安全。**
> 6. **四条「开到别处」的路合流为一条。** 开关移交、错误页的「用其他浏览器打开」、工具栏动作、重试条——用户眼里是同一个请求：**这里读不了，去别处读**。它们以前有**两份各自实现**，于是一个应用可以一边拒绝交出一个它明明有浏览器可用的地址，一边在真没有浏览器时安静地什么都不启动。现在全部经过 `ui/BrowserScreen.kt` 的 `openElsewhere(...)`，`util/FileIo.kt` 里那个只会发一条 `ACTION_VIEW`、什么都不回报的 `openExternally` 已删除。
> 7. **设置页删掉「权限与隐私」分类**及其中读不出结果、点了不知道发生什么的入口，保留底层权限检查与安全校验（详见更新日志）。
> 8. **「菜单点了没反应」的真因是工具栏在用户开口之前就把自己收起来了。** 导航一开始就会隐藏 chrome，而**页面自己发起的重定向或 `history.pushState` 会在同一次点击的同一帧里做到这件事**——用户看到的是「菜单弹不出来」。修法是把隐藏条件接上 `menuExpanded` / `diagnosticsOpen`：**用户正在读的东西不从他手里抽走**（`LaunchedEffect(state.url) { if (!menuExpanded && !diagnosticsOpen) chromeVisible = false }`）。

>
> **测试**：**527 例 / 33 个类**（上一版 505 例 / 32 类），全部通过，**0 失败 0 错误 0 跳过**。新增 `ExternalLauncherTest`（18 例）——上一版 505 个测试里**没有一个调用过 `ExternalLauncher.build`**，这正是这个开关能连着两个版本坏着出厂的原因；`CrossSiteLinkPolicyTest` 25 → 27 例，`SettingsRepositoryTest` 10 → 12 例。三语言字符串 **431 → 428** 条（删掉「权限与隐私」的 3 条）。`assembleDebug` 与 `testDebugUnitTest` 全程 **0 个 `e:`、0 个 `w:`**。
>
> ⚠️ **诚实边界**：这台环境**没有设备也没有模拟器**（`adb` 不存在，SDK 里没有 system image），所以下面这些只做到「编译通过 + 单元测试 + 静态审查」，测试报告里逐项标为**未测试**：开着「网站跳转」点一个真实的跨站链接，系统选择器是否真的出现、取消后页面是否还在；`taobao:` / `weixin:` 这类自定义协议在真机上是否真的落到对应 App（本环境没有 `startActivity` 可跑，只有一次被系统否掉的调用被断言过）；取消选择器后「回落内置 WebView」的提示是否如期出现；「菜单点了没反应」是否真的不再发生（需要一个会自己重定向的页面才看得到）。
>
> ## 🆕 1.05（`versionCode=7`）：按钮的阴影与涟漪 · 震动模式下整个应用无声 · 启动先黑屏 · 跨站链接开关
>
> `versionCode` **6 → 7**，`versionName` **`1.04` → `1.05`**（Debug 变体实际显示 `1.05-debug`）。
>
> 上一版处理的是「装了打不开」，起点是一句暴击。这一版是五个**看得见但不致命**的毛病，加一个点名要的新开关。五个里有**两个**查到最后是同一个病：**同一件事在两个地方各记了一份，两份各自漂移。**
>
> 1. **按钮底下是一块方的暗色块。** 真因不在颜色，在**修饰符顺序**：`.shadow()` 和 `.clip(shape)` 挂在同一个节点上，修饰符由左至右嵌套，所以写在后面的裁剪把前面那个 4dp 的模糊**切在了圆角框边上**——柔和的投影变成硬边的深色方块。规则只有一条：**`.shadow()` 写在 `.clip()` 之前**，阴影就由裁剪之外的图层绘制。为此把 `lumoButtonSurface` 拆成 `Modifier.lumoButtonShadow(style, shape, enabled)`（只管投影，`clip = false`）加剩下的裁剪/填充/边框/高光，四个调用点全部改成「阴影先、裁剪后」。**涟漪改用 `Modifier.indication` 挂在视觉节点上**，外层触控节点的 `indication = null`——涟漪由它自己那个**已经裁剪过**的节点绘制，而不是由 48dp 的触控目标绘制；`LumoPrimaryButton` 原本**完全没有裁剪**，是同一张报告的另一半。顺带修掉禁用态：填充淡到 45% 而**描边仍是满强度**，一圈清晰的边围着褪色的形状，读起来像「启用着但是空的」——`borderFor(enabled)` / `quietBorderFor(enabled)` 让填充、描边、文字一起淡，**渐变边框不动**（它没有单一 alpha 可调，半淡化等于发明第二个渐变）。
> 2. **震动模式下整个应用没有声音——不是小，是完全没有。** 又是「两份各记一份」：`SoundSynth` 用 `USAGE_ASSISTANCE_SONIFICATION` 建 `SoundPool`，AOSP 把它映射到 **`STREAM_SYSTEM`**（`AudioAttributes.java:1776-1783`），而自检 `isAudible()` 读的是 **`STREAM_MUSIC`**。`STREAM_SYSTEM` 在铃声为**静音或震动**时都被强制静音——`AudioService.java:6168` 逐字 `final boolean ringerModeMute = ringerMode == RINGER_MODE_VIBRATE || ringerMode == RINGER_MODE_SILENT;`，`:7536-7540` 的默认遮罩明确含它。于是手机只要在震动模式（极常见），**整个应用一点声音都没有，而自检仍回 `true`、设置页仍显示音效开启、没有任何日志**；`STREAM_SYSTEM` 的音量还被别名到 `STREAM_RING` 且默认满格，样本根本没被衰减，**任何读音量索引的判断都不可能发现**（Stack Overflow 60303150 是同一个坑的已知案例，配置逐项相同）。修法：换到 **`USAGE_MEDIA` → `STREAM_MUSIC`**（正是音量键控制的那条流；SoundPool **从不申请音频焦点**，不申请就不触发 ducking，不会打断用户正在放的音乐）；`playbackStream` **从同一份 `attributes` 推导**（`attributes.volumeControlStream`），杜绝「同一件事写两份」；`RINGER_MODE_VIBRATE` 与 `SILENT` 同等看待；判定逻辑抽进纯 JVM 的 `SoundGate`，`play()` / `preview()` / `status()` 走同一个门并返回被拦下的**原因**（`SoundBlock`）。
> 3. **启动先黑屏，再出现启动动画。** 那段空窗期是真实的：窗口背景从进程 fork 就存在，第一个 Compose 帧要等 `onCreate` → `setContent` → 组合 → 布局 → 绘制走完，这段完全由系统展示；1.04 可见属性只有一条纯色 `windowBackground`，系统能画出来的就是一个空矩形，应用内动画再叠 **2340 ms** 把开场拉成两秒多。引入 `androidx.core:core-splashscreen`，新增启动主题 `Theme.LumoBrowser.Starting`（父主题 `Theme.SplashScreen`）让**系统启动窗口**在 `onCreate` 之前就画出品牌标记，背景与 Compose 启动遮罩取**同一个** `@color/lumo_backdrop`（`#FF06070E`）所以交接是同一块面；`installSplashScreen()` 是 `onCreate` 第一句（**必须在 `super.onCreate` 之前**）；三段动画从 960/1720/2000 **收到 `260/400/480 = 1140 ms`**，顺序比例保持。**刻意没做**：不用 `setKeepOnScreenCondition`（它的语义是「把第一帧挡在后面」，等于把黑屏风险从「一开始」搬到「条件不满足时」）；不靠延长动画掩盖空窗。`values-night/themes.xml` 新建且夜里**不翻浅色**——默认外观由用户偏好在 Compose 里决定而不是系统 `uiMode`，翻浅色只会与要交接的遮罩对不上；这个文件买的是「不碰巧继承」。
> 4. **跨站链接开关（新增，默认关）。** 右上角三点菜单新增第四项「网站跳转」。**关**（默认）：所有网页链接都在 LUMO 内打开，与 1.04 完全一致；**开**：跳到**其他站点**的链接交给系统浏览器，同站链接（含二、三级页）仍在应用内。跨站判定是**刻意的近似**（用「后两段标签」当站点 key，没有 public suffix list），所以 `a.co.uk` 与 `b.co.uk` 会被判为同站——**这个错误方向是选过的**：判成同站的代价是一条本可以交出去的链接留在应用里，用户仍在读他想读的东西；判成跨站的代价是在用户读到一半时把他踢出去，**低报可以承受，高报不行**。这条已知错误被写成断言（`a two-label key merges co uk domains and that is the safe direction`），免得下一个人把它「修好」而把方向翻过来；**任何解析不出来的地址一律视为同站**。`hostOf` 手写解析（`java.net.URI` 会对路径含空格或主机名非 ASCII 的正常地址抛 `URISyntaxException`；`android.net.Uri` 在这个要跑无 Android 类测试的文件里不可用），userinfo 按**最后一个** `@` 切——按第一个切会把 `https://example.com@evil.com/x` 读成 `example.com`，等于把一个指向别处的链接留在应用里。**这个开关只在两个浏览器之间移动网页，不改变「打开 App」的方式**：`tel:` / `mailto:` / `market:` / `intent:` 两种状态下完全一样，都走既有的确认面板。
> 5. **设置页的音效状态与试听反馈。** 音效区新增一行**真实状态**：被拦下时说明原因（已关闭／这类音效已关闭／上面的音量为零／手机处于静音或振动／媒体音量为零／设备没有可用音频输出／音效仍在载入／设备拒绝播放），放行时说「正常」，下面一行显示实际读到的媒体音量。**试听按钮也改成说实话**：`previewSound` 返回 `SoundBlock?`，失败显示原因。这里有个只有试听才看得见的坑——`SoundBlock.PlayRejected` 只存在于「尝试启动的那一刻」，**事后查询看不到它**，所以试听必须汇报它自己那次尝试的答案，而不是回头再查一遍状态。试听刻意**绕过**「音效总开关」与「这类音效开关」（它存在的意义就是回答「这声音什么样」，那是开启之前的问题），但**不绕过**其余条件。
>
> **测试**：**505 例 / 32 个类**（上一版 460 例 / 29 类），新增 `CrossSiteLinkPolicyTest`（25 例）、`SoundGateTest`（14 例）、`StringResourceParityTest`（6 例）。三语言字符串 **416 → 431** 条。
>
> **一次差点被绕过去的编译错误。** 修音效时写了 `attributes.legacyStreamType`，编译报 `Unresolved reference: legacyStreamType`——`AudioAttributes.getLegacyStreamType()` **不在公开 SDK 表面上**（公开版是 `getVolumeControlStream()`，`toLegacyStreamType()` 是 `@hide` 且标了 `@UnsupportedAppUsage`）。**「改个版本号或换个写法让它编过」在这里会写出一条只在部分设备上可用的代码**；正确做法是去找公开 API 里语义相同的那一个。
>
> ⚠️ **诚实边界**：这台环境**没有设备也没有模拟器**，所以下面这些只做到「编译通过 + 单元测试通过 + 静态审查」，测试报告里逐项标注为**未测试**：按钮阴影在真机上是否真的不再是方块（本环境无法渲染）；**震动模式下是否真的有声音（本轮最需要真机确认的一条）**；冷启动黑屏是否真的消失，且 **API 26–30 与 API 31+ 要各看一次**（两条实现路径不同）；跨站开关拨动后系统选择器的实际样子、取消后页面是否原样保留；菜单项与状态行在小屏 + 大字体下的换行。
>
> ## 🆕 1.04（`versionCode=6`）：闪退的两个根因 · 外部跳转确认面板 · 按钮规范 · 设置页重构
>
> `versionCode` **5 → 6**，`versionName` **`1.03` → `1.04`**（Debug 变体实际显示 `1.04-debug`）。起点是用户的一句话：**「在手机上安装了，打不开，在优化一下」**。
>
> 这一版没有把「打不开」当成一个 bug 去猜，而是拆成两条**各自独立、各自可复现**的崩溃路径，分别定位根因再修。**只修任何一条，另一条都还会崩。**
>
> 1. **根因之一（更贴症状的那条）：弹窗复用了已经导航过的 WebView。** Chromium 的 `how-does-on-create-window-work.md` 有一节标题逐字就是 **"New WebView for popup window must not have been previously navigated"**，而旧代码 `transport.webView = this` 交出去的正是那个刚导航过无数次的主 WebView；项目里 `supportMultipleWindows = true`，所以**每次点 `target="_blank"` 都会走进去**。第三、四层页面正是站内 `_blank` 链接最密的地方——「二级页正常、三级页闪退」的位置就此对上。失败是 **native SIGSEGV，不是 Java 异常，`runCatching` 拦不住**。改为每次弹窗新建 scratch WebView：不挂视图树、取消一切导航，因此永远停在 `about:blank`，任何时刻都能安全销毁。同时把三路分派抽成 `dispatchNavigation(url, inApp)`，让「点链接」和「弹窗要打开的地址」走同一份判定，不会各自长出一份副本。
> 2. **根因之二：渲染进程死后回调了尸体。** `WebView` 比渲染进程活得久，进程没了之后它的每个方法**仍然会正常返回**，但每一次调用都跨进一个已经不存在的进程。新增退役状态机（`retired` / `destroyed` 两个独立布尔量、13 个跨 renderer 入口加守卫），修正 `emit()` 的求值顺序（原来是 `onState?.invoke(BrowserState(...))`，Kotlin 会先把整个引数清单求值完才发现没人可通知——等于对一具尸体照做了一整套跨进程调用），并把 `AndroidView(onRelease = …)` 定为**全 App 唯一的销毁点**——旧代码在一条崩溃路径上 destroy 了三次。
> 3. **外部软件跳转：先问，再交出去。** 建立不变量「**`LumoWebView` 永不启动 activity、永不自行解析 intent**；全 App 只有一处能造成外部效果，而那一处会先问用户」。`tel:` / `mailto:` / `market:` / `intent://` 现在从底部滑出 Material 3 确认面板，写清「要打开什么」和「由谁打开」。三个决定值得记：**`intent://` 由本项目重建，绝不用 `Intent.parseUri`**（平台解析器会吃 `component=` / `launchFlags=`，那正是 intent redirection 的入口，而本项目的解析器显式忽略这两个字段）；**每次启动都排除 LUMO 自己**（它注册了 http/https 的 `VIEW`，不排除就会「解析到自己」，用户按了确认什么也没发生）；**确认按钮的可用性不依赖包可见性预检**（`canHandle` 是一次 `PackageManager` 查询，Android 11+ 只看得到 `<queries>` 放行的包，拿它当 `enabled` 会让一次预检失误把一个本来能用的链接**永久按不动**——所以预检只影响文案，真正的答案由点击后的 `HandoffOutcome` 回报）。
> 4. **按钮规范。** 报告是「按下去的反馈和按钮的形状对不上」。根因不是某条修饰符写错，而是同一件事在三个按钮里各写了一遍、三份副本各自漂移。修掉三处：图标按钮的**涟漪溢出形状**（`clickable` 挂在 48dp 触控区上，而视觉形状是里面 44dp 的圆角容器，外层没有 `clip`——加 `clip` 即可，**`clip` 只影响绘制不影响指针输入**，48dp 触控区原样保留）；**禁用态只淡填色不淡描边**（填色降到 45%、轮廓保持全强度，围着一个变暗的形状，读起来像「能按，只是内容空了」）；**色票涟漪硬编码白色**（八个自定义色里三个近白，白涟漪画在白底上等于没有反馈）。另有一条**刻意的例外**：渐变描边在禁用时**原样不动**——它没有单一 alpha 可调，淡化等于替用户发明第二个渐变。见新增的 [`docs/BUTTON_SPEC.md`](docs/BUTTON_SPEC.md)。
> 5. **设置页重构与功能审查。** 设置页从一条 2021 行的单层长滚动列表改成**系统设置风格**：首页分类卡片 + 二级页面 + 设置搜索直达，八大分类，重要操作统一走一个二次确认对话框。配套的**功能审查**逐字段追了 58 个偏好的读取者，产出「保留 / 合并 / 修复 / 删除 / 暂缓」清单——其中 **3 个假开关被删掉**（`defaultBrowserMode`、`checkNetworkBeforeOpening`、`rememberPerSiteNetworkAssist`：有字段、有存储键、有界面开关，**但没有任何代码路径读它**），另有 **2 条死字符串资源**一并清除。见 [`docs/SETTINGS_STRUCTURE.md`](docs/SETTINGS_STRUCTURE.md) 与 [`docs/FEATURE_CLEANUP.md`](docs/FEATURE_CLEANUP.md)。
>
> **测试**：最终 **460 例 / 29 个类**（上一版 372 例 / 25 类），见下方「一次被自己抓到的假绿」。新增 `WebNavigationPolicyTest`（34 例）、`ButtonStateTest`（8 例）。
>
> **一次被自己抓到的假绿（如实记录）。** 我在设置页重构落地后跑了一次全量测试，得到 **429 例 / 0 失败**，一度当成「重构后的设置页没问题」的证据写进了这一节。**那是错的。** 那次运行在 23:39 启动、Gradle 在 23:43 重构落盘之前就已经编译完了旧版 `SettingsScreen.kt`——也就是说，它测的是**重构前的界面**。真正针对新代码跑出来的结果是 **460 例 / 5 失败**：4 条是老的冒烟测试还按「设置页是一整条平铺长列表」的老结构去点控件（新结构要先进分类卡片），1 条是新增的搜索测试自己的夹具写错了。5 条全部修好后才有了最终那个绿。**这一条留在这里而不是删掉，是因为「测试通过了」这句话在什么代码上通过，比通过本身更重要**——尤其当整个版本就是在处理一个「安装成功却打不开」的报告时。
>
>
> ⚠️ **诚实边界**：**真机端到端仍未验证。** 本机 `adb devices` 是空清单、没有 `emulator.exe`、没有 `system-images`，拿不到真机 Logcat，**因此无法证明弹窗 SIGSEGV 在真机上确实不再发生**。两条崩溃路径都是「按官方文档与 Chromium 的设计约束定位到根因、按根因修复」，**不是**「在设备上复现后确认修复」——这两件事在验收表里分开记，没有当成同一件。
>
> ### 🔧 1.03（`versionCode=5`）：无障碍辅助服务：从「明确不做」到「已实现」
>
> 版本号从 `2.2` 回到 `1.03`——这个包不是 v2.2 专项任务的收尾，而是接在 1.02 之后的第三个交付版本；中间那轮 v2.2 的序号把版本序列拉离了发布节奏，本次拉回来。`versionCode` **4 → 5**，`versionName` **`2.2` → `1.03`**（Debug 变体实际显示 `1.03-debug`）。
>
> 本轮只有一件事，但它是那件一直挂着的：**规格 2.3 的无障碍辅助服务落地了。**
>
> 1. **它做什么。** 用户在 LUMO 里指定某个 App 里的某一个按钮，LUMO 可以替他**按一次**。典型场景是 VPN 客户端——系统授权对话框已经同意过，但每次都要去另一个 App 点一下「连接」。刻意**不是**自动化脚本引擎、不是「帮我在所有 App 里找按钮」：目标必须逐条由用户声明，一次尝试只针对一个目标，按下一次就结束。
> 2. **权限很大，行为很小。** 一次只有一个尝试且 **12 秒过期**（`WINDOW_MS`）、**最多按一次**（`MAX_PRESSES = 1`）；**超时检查在包名比对之前**，过期尝试连「这个包是不是目标」都不会去问。六个平台套件 + LUMO 自己**永远返回 `Forbidden`**——因为用户最可能顺手声明成目标的，恰恰是同意对话框上的那个「确定」。服务配置只留 `flagReportViewIds`，`flagRetrieveInteractiveWindows` / `flagRequestFilterKeyEvents` 等五项**逐条写明为什么不要**。不读输入框（跳过 `isEditable` 与 `EditText`）、不落盘、不记录。
> 3. **五个真实缺陷。前三个是新测试第一次运行就抓出来的**（都是产品代码的 bug，不是测试写错）：`isForbiddenAssistPackage` 比对裸常量，**漏掉了 debug 变体自己的包**——「开发者手机上实际安装的那个变体」成了唯一漏网的，而失败是静默的；`AssistTargetCodec.unescape` 吃掉反斜杠，把 `C:\vpn` 变成 `C:vpn`，而同段注释声称「原样保留」；`isEnabledViaSecureSetting` 用的是 `ComponentName.unflattenFromString` 已经展开过的名字，导致**用户已在 Android 设置里打开的服务被报告成关闭**（false negative 让用户损失一个他明明已经打开的功能）。**后两个是编译器抓出来的，测试从来没红过**：`notePressed(attempt)` 收下参数却从不读它（"一次回调里不可能换掉 attempt" 这个假设从未被检查）；`AccessibilityTextField` 的 `onSubmit` 从未被调用（**键盘上的「完成」键按下去什么都不会发生**）。它们以两条 `Parameter 'x' is never used` 警告的形式在前几次构建里一直存在、一直被当作噪音——**一条总是出现、又从不影响结果的警告，会自然退化成没人读的装饰**。修掉之后，构建日志里 Kotlin 警告数为 **0**。详见 [第 16.3 节](#163-五个真实缺陷前三个由新测试抓出后两个由编译器抓出)。
> 4. **顺带关掉一个旧缺口**：2.2 遗留清单第 ⑧ 项——`startupTimeline()` 的纯函数测试，12 例。
>
> **测试**：无障碍 **59 例**（`t13.txt`）+ 启动动画 **12 例**（`t15.txt`），全部通过；全量 **372 例 / 0 失败**，干净重建 `BUILD SUCCESSFUL in 16m 37s`。完整说明见新增的 [`docs/ACCESSIBILITY_ASSIST.md`](docs/ACCESSIBILITY_ASSIST.md)。
>
> ⚠️ **诚实边界**：**真机端到端未验证**——本机没有可用的模拟器或真机，节点树遍历与 `performAction(ACTION_CLICK)` 的实际效果未被实际运行验证过。验收表里这一条按「实现完成、真机未验」记，不记全绿。
>
> ### 🔧 v2.2（1.03 之前的上一轮，`versionCode=4`）：VPN 集成重写 · 音效系统 · 按钮视觉 · 启动动画
>
> 这一轮是**专项升级**，四个大项，每一件都先说清楚"能做到什么、做不到什么"：
>
> 1. **VPN 辅助重写。** 上一版的状态面板有两个结构性错误：`Connected` 只要设备上有 VPN 承载默认路由就报「已连接」（那是关于**设备**的观测，不是关于本应用的结论），而 `Connecting` / `Failed` 两态由一个**全程零写入者的旗标**驱动——**六态里有两态永远到不了**，界面会一直等下去。现在状态由 `resolveVpnReport(...)` 从三个独立来源合成（隧道真实状态、网络快照、能力），并新增第七态 `DetectedNetwork`：**只检测到系统 VPN 网络时标记为「检测到 VPN 网络」，绝不冒充已连接。** 详见 [第 15 节](#15-v22第六轮vpn-集成音效按钮视觉启动动画) 与 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md)。
> 2. **音效系统重写。** 76 个 WAV 素材（19 种事件 × 4 种风格）**全部程序化合成、零第三方素材**，改用 `SoundPool` 预载异步解码，并补齐设置页与试听。**19 个事件此前只有 12 个真的会响**，现在全部接上线。详见 [`docs/AUDIO_ASSETS.md`](docs/AUDIO_ASSETS.md)。
> 3. **按钮阴影的根因修复。** 全库只有两处 `Modifier.shadow`，两处都用 `Color.Black` 且都写在 `.clip(shape)` **之前**（所以裁不到），另有 12 组父子重复投影；浅色主题的"脏边"来自 `hairline()` 在浅色下返回**黑 @ 0.495**。逐条定位后修掉，**没有对按钮统一设 `shadow = 0`**。
> 4. **2～3 秒三阶段冷启动动画。** 全程无 blur、单一驱动源；**是覆盖层不是闸门**——`content` 从第一帧就在下面组合与布局，动画最坏的失败模式（自己变成打不开的原因）在结构上不可表达。详见 [`docs/STARTUP_ANIMATION.md`](docs/STARTUP_ANIMATION.md)。
>
> **顺带修掉一个真 bug**：`UserPreferences.reduceMotion`（设置页那个"减少动效"开关）是一个**死偏好**——它有字段、有存储键、有读写、有 UI 开关，但**从未传进主题构建**，唯一生效的是系统级旗标。
>
> **回归**：单元测试 **301 用例 · 0 失败 · 0 错误 · 0 跳过**（21 个类；上一版 263）。**VPN 集成测试第一次运行就抓出三个真实产品缺陷**（都是产品代码的 bug，不是测试写错），详见 [`docs/TESTING.md`](docs/TESTING.md) §3.14。
>
> ⚠️ **关于 VPN 能力，这里先把话说清楚**：**本版本不能建立 VPN 连接。** `TunnelBackends` 注册表是空的——没有协议实现、没有服务器配置界面。做到的是：授权流程、服务生命周期、前台通知、状态合成、端点持久化、已安装 VPN 应用的发现与打开。**这不是"暂时为空"，出到一个协议就等于出一个对真实服务器跑过的实现，而两者都不存在。** 完整的"有什么 / 缺什么"对照表在 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md) 第一节。
>
> ⚠️ **无障碍服务**：**v2.2 时本版不做；1.03 已实现。** 完整说明见 [`docs/ACCESSIBILITY_ASSIST.md`](docs/ACCESSIBILITY_ASSIST.md)。v2.2 当时记录的五条「将来实现时的既定约束」在 1.03 实现时**逐条照做，没有一条被放宽**，当时的理由（「为一个不存在的服务做授权状态检查，只能检查出一个恒为 false 的值，那不是功能，是装饰」）也保留在 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md) 第四节。
>
> ### 🔧 第四轮（1.02）：浏览模式、浏览器兼容性、设置即时反馈
>
> 这一轮解决四个真机反馈的问题，并**把每一条都落到可验证的代码上**（不是"加了颜色和动画"）：
>
> 1. **设置页的背景效果、按钮质感点了没反应。** 背景与按钮质感的解析逻辑此前散落在三个不同的 `when (buttonTexture)` 里，且**完全没有为自订颜色 / 圆角 / 阴影 / 边缘高光预留通道**。现在收敛成 `LumoTheme.backgroundBase/backgroundLayerAlpha/backgroundGlow/backgroundAnimates/buttonRadius` 与单一的 `@Composable fun lumoButtonStyle(): LumoButtonStyle`，七个质感各自有独立的 fill / border / shadow / highlight 表；设置页的背景与按钮区块下方各放了**即时预览**（读的是"刚点中的那一项"而非持久化值，所以不必等 DataStore 往返），并新增"恢复默认外观"（只重置外观键，不动语言、收藏、音效）。
> 2. **内置浏览器兼容性不足。** 新增 `BrowserConfigManager`（纯函数 `planFor(...)` 产出 `BrowserSettingsPlan`，`applySettings(...)` 机械落盘），打开 `setSupportMultipleWindows`（不开的话 `target="_blank"` / `window.open` 会被**静默丢弃**），补齐 `onShowFileChooser` / `onPermissionRequest`（一律拒绝）/ `setDownloadListener` / 渲染进程崩溃恢复 / 网页诊断面板，并指派了此前**从未被赋值**因而按钮是死的 `onRetryRequested` / `onExternalRequested`。
> 3. **添加网址页遮住系统状态栏。** 根因不是内边距——`EditorScreen` 的 `statusBarsPadding()` 一直是对的。真正原因是 `themes.xml` 把 `android:windowLightStatusBar` **写死为 `false`**（永远浅色图标），而 `enableEdgeToEdge()` 不带参数时图标明暗跟随**系统**深浅色而非 **App** 主题，于是"App 浅色 + 白图标"必定看不见图标，看起来就像顶部布局坏了。
> 4. **新增 AUTO / 手机版 / 桌面版 / 兼容模式四种浏览模式，且每个网址可独立设置。** 新增 `BrowserMode` 枚举（持久化走 `storageName`，重命名常量不会静默作废旧值）、`SiteConfig` / `BrowserModeProfile` / `ResolvedSiteConfig`，Room `version` 1 → 2 配 `MIGRATION_1_2`，编辑器里可选模式与自定义 UA / JS / DOM 存储 / 第三方 Cookie，浏览器右上角菜单可**当场切换**，AUTO 是一个**有界**的升级阶梯（`Mobile → Compat → Desktop`，`exhausted` 后闩锁，永不回环）。
>
> **顺带修掉两个"装得上、打得开，但一用就废"的真实缺陷**（都由本轮新增的测试抓出，不是测试写错）：
>
> - **`WebViewFeature` 宣称支持却抛异常**：`isFeatureSupported(ALGORITHMIC_DARKENING)` 为 true 之后 `setAlgorithmicDarkeningAllowed(...)` 抛 `UnsupportedOperationException`，而它跑在 `AndroidView` 的 `factory` 里——**整个浏览器页直接崩掉**。现在所有能力查询都过 `featureGated(...)`（catch `RuntimeException`，**刻意不 catch `Throwable`**）。
> - **每网址"请求桌面版"开关是空操作**：`planFor(...)` 多写了一个 `&& profile.desktopUserAgent`，而 `resolve()` 早已把模式的含意并进该标志，于是开关在手机版 / 兼容 / 自动三种模式下**打开也没用**，诊断面板还会报告 `desktop-ua`。现在只判断 `config.desktopSiteEnabled` 一个标志。
>
> **版本号**：`versionCode` **2 → 3**、`versionName` **1.0.1 → 1.02**（Debug 变体实际显示 `1.02-debug`）。
>
> **回归**：`clean` 冷构建（禁用构建缓存）`BUILD SUCCESSFUL in 4m 15s`、**零 error、零 warning**，单元测试 **223 用例 · 0 失败 · 0 错误 · 0 跳过**（上一版 147，本轮新增 76）。细节见 [第 10 节](#10-第四轮102浏览模式--浏览器兼容性--设置即时反馈)、[第 11 节](#11-第四轮102实际验证了什么) 与 [`CHANGELOG.md`](CHANGELOG.md)。
>
> ### 第三轮修复（历史）：设置里切换语言时卡死
>
> 真机上**一打开设置切换语言就卡住**，而且此后每次启动都会卡。这不是偶发，是结构性死循环：
>
> - `MainActivity` 用 `collectAsStateWithLifecycle(initialValue = UserPreferences.Default)` 收集偏好，而 `UserPreferences.Default.appLanguage` 是 `AppLanguage.System`——**占位值永远是"跟随系统"**。
> - 判断"要不要重建 Activity"时，代码拿**存储的语言**和**组合内 `remember` 出来的值**比。后者每个新实例都从那个占位值种下，所以恒为 `System`。
> - 于是只要存过任何非"跟随系统"的语言，每个新 Activity 都在真实偏好到达后判定"过时了" → `recreate()` → 新实例又从 `System` 重新开始 → **永不收敛**。用户从未改过语言时存储值恰好等于占位值，所以第一、二轮从没触发过。
>
> 修复分三处：把"本实例实际套用的语言"提升为 Activity 字段，在 `attachBaseContext`（任何视图存在之前）从镜像读取并记录；偏好改为 `initialValue = null` 收集，好让决策能区分"**尚未加载**"与"已加载且相符"；判断收敛为纯函数 `shouldRecreateForLanguage(applied, loaded)`，并且**先同步写语言镜像再重建**——`attachBaseContext` 不是协程，下一次建立时必须已经能读到新值。
>
> 顺带修掉回环的另一半：`LanguageMirror` 原来存 `Locale`，而 `System` 经 `toLocale()` 映射为 `null`，导致镜像**无法区分"跟随系统"和"从未写入"**。现在改存 `AppLanguage` 枚举名。
>
> **版本号**：`versionCode` → **2**、`versionName` → **1.0.1**（Debug 变体带后缀，实际显示 `1.0.1-debug`）。同时把构建时间编译进 `BuildConfig.BUILD_TIME`，设置页"关于"面板新增**构建时间**一行——Debug 包是手动侧载的，装了哪个包必须能自己说出来。
>
> **回归**：`clean` 冷构建 + 全量单元测试 **147 用例 0 失败**（原 140 + 本次新增 7），零 error、零 warning。细节见 [第 7 节](#7-第三轮设置里切换语言卡死) 与 [`docs/TESTING.md`](docs/TESTING.md) §3.11。
>
> ### 🔧 第二轮修复：卡顿 / 按键没反馈 / 点击不灵敏
>
> 真机能启动之后，反馈的问题变成"感觉卡卡的，按键没反馈不灵敏"。这一次做的是**全库性能与交互审查**：我先把整个代码库按 10 个检查项（组合期读动画状态、每帧对象分配、只定义不调用的公开函数、缺 indication / 触摸目标、主线程阻塞、缺 key 导致动画重播、O(n²) 扫描、WebView 卡顿、滚动时发声振动）过了一遍，**共确认 11 个真实缺陷并逐条修掉**，清单与每条的成因见 [第 8 节](#8-第二轮性能与交互修复卡顿--按键没反馈--不灵敏)。
>
> 其中最关键的三条，恰好各自对应一个症状：
>
> - **卡顿**：`LumoBackground` 在**组合阶段**读取无限动画的相位，于是**每一帧都重组整个主屏**并把新的绘制 lambda 写回去，全屏三个巨大径向渐变每帧重新光栅化（每帧还有 10+ 次对象分配）。而且这个读取与用户选的背景样式无关——选"纯色"照样每帧重组。现在相位只在 `graphicsLayer` 的 lambda 里读（只改 RenderNode 变换，不重组、不重绘），静止背景根本不创建动画。
> - **按键没反馈**：`MainViewModel` 里的 `playTap/playToggle/playConfirm/playError` 四个方法**全工程零调用点**——音效和触觉的接线从来没有接上；同时四个共用按钮把 `indication` 显式设成了 `null`，连水波纹都没有。现在新增 `LumoFeedback` 并作为 CompositionLocal 提供，所有共用控件统一发反馈，设置页的滑杆**只在松手时**发（拖动过程中每帧发正是需求明令禁止的）。
> - **不灵敏**：卡片上的"更多"按钮视觉尺寸 32dp、命中区也只有 32dp，浏览器工具列的唤出把手更是 56×22dp 的细条——都远低于 48dp。现在视觉尺寸不变，但外层统一套 `LumoMinTouchTarget`（48dp）的命中盒。
>
> **回归**：`clean` 冷构建 + 全量单元测试 **140 用例 0 失败**（与改动前同数，无回归），其中 18 例是真正启动 `MainActivity` 的冒烟测试。
>
> ### 📌 第一轮修复（历史）：真机「安装成功但打不开」
>
> 上一版 APK 装到手机上点图标立刻闪退。根因：`ui/components/Surfaces.kt` 里的 `LocalLumoExtra` 是一个**读取即抛错**的 CompositionLocal，而它的提供者 `ProvideLumoExtras` 在全工程里从未被调用过——于是**首次组合必然抛 `IllegalStateException: LumoExtraColors read outside LumoTheme`，每次启动都崩**。修复方式是把 `ProvideLumoExtras` 绑进 `LumoTheme`（见 `ui/theme/LumoTheme.kt`），让"漏提供"这个错误在结构上不可表达。
>
> **为什么第一轮之前没发现**：原有的 122 个单元测试**没有一个执行过 `setContent`**——没有 Activity、没有组合、没有 Compose 运行时，所以一个"每次启动必崩"的缺陷可以完全隐身。为此新增了两个**真正启动 `MainActivity` 并点击按钮**的冒烟测试类（`StartupSmokeTest` 7 例 + `AppFlowSmokeTest` 11 例）。
>
> ⚠️ **这是 Debug 签名的测试包，安装前请先卸载旧版本**（旧包 `com.lumobrowser.app.debug` 的签名与本次相同，可直接覆盖安装；若提示签名冲突，卸载后重装即可）。

---

## 1. 本次交付物

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| Debug APK | [`dist/LumoBrowser-v1.08-debug.apk`](dist/LumoBrowser-v1.08-debug.apk) | **1.08**，可直接安装测试，**Debug 签名，非正式发布签名**（19,535,475 字节，SHA-256 `833FEE7E…C911E`） |
| Release APK（已签名） | [`dist/LumoBrowser-v1.08-release.apk`](dist/LumoBrowser-v1.08-release.apk) | **1.08**，用工程内附带的密钥签名、**不可调试**、包名不带 `.debug` 后缀（13,182,384 字节，SHA-256 `876BAFA2…6E42`），可与 Debug 包共存；**与 1.07 同一把密钥**，所以它能直接覆盖手机上已装的 1.07 Release。存在的理由见 [第 2.2 节](#22-如果-107-还是打不开)：部分 ROM 会拦截 debuggable 的侧载应用 |
| 完整源码 | [`dist/LumoBrowser-v1.08-source.zip`](dist/LumoBrowser-v1.08-source.zip) | 可导入 Android Studio 的完整 Gradle 工程（**见下方源码包实测信息**，含 76 个音效素材、`docs/` 全部条目（其中含各版构建日志）；附 `keystore/`，便于自行重建可覆盖安装的 Release 包） |
| 构建日志 | [`docs/build-logs/`](docs/build-logs/README.md) | **21 份原始 Gradle 日志副本** + 索引（1.04 起收录；1.08 新增六份，含**失败**的那一次编译 `v108-compile-failed-safebrowsing.txt`，1.07 的两份在本版补齐）。验收要求交付构建日志，而此前这些日志**从没进过源码包**——文档按名字引用 `c30.txt`、`t13.txt`，收件人手上却没有这些文件 |
| 无障碍辅助说明 | [`docs/ACCESSIBILITY_ASSIST.md`](docs/ACCESSIBILITY_ASSIST.md) | **本版新增**：它做什么、七种结果、安全边界逐条、五个真实缺陷、59 例测试、这一版没做什么 |
| VPN 集成说明 | [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md) | **能不能真实建立连接、支持哪些外部服务、是否依赖用户自备服务器**、无障碍功能的状态（第四节已在 1.03 改写） |
| 音效素材说明 | [`docs/AUDIO_ASSETS.md`](docs/AUDIO_ASSETS.md) | 76 个素材的来源/作者/许可、合成参数、19 种事件表、播放引擎与规格逐条对照 |
| 启动动画说明 | [`docs/STARTUP_ANIMATION.md`](docs/STARTUP_ANIMATION.md) | 三阶段时间表、两个结构性决定、何时播/何时不播、与系统 SplashScreen 的关系 |
| 构建说明 | [`docs/BUILD.md`](docs/BUILD.md) | 环境要求、命令行/Android Studio 构建、离线测试配置、Release 签名、**§8 各版建置记录（含 1.08）** |
| 测试记录 | [`docs/TESTING.md`](docs/TESTING.md) | **§0.0 当前交付（1.08）** 的实测数字表（**718 例 / 44 类全绿**、两个 APK 的字节数与 SHA-256、签名与 badging 校验）与 **§0G 1.08 验收标准覆盖表**（A 广告屏蔽 / B F1–F12 影片与全屏 / C 导航安全 / D 回归 / E 真实建置 / F 交付物，逐项标「通过 / 静态审查 / 未测试」，G 组是必须由真机与真实网络回答的 7 条）、**§7.0 1.08 构建与测试记录**；历史留档：**§0.0a 1.07**、**§0E 1.06 验收标准覆盖表**、**§7.0a 1.07 构建与测试记录**、**§0A 1.03 验收标准更新**、**§0B v2.2 二十条验收标准结果表**（9 通过 / 10 部分通过 / 1 未通过）、**§0C 1.04**、**§0D 1.05**（并含 §0D.7「校验脚本自己造出来的两次假警报」）、**§7.1 1.05**（505 例 / 32 类、`--no-build-cache` 冷构建）、**§7.2 1.04**（460 例 / 29 类）；以及实际跑过的测试、无法执行的测试与原因 |
| 浏览模式说明 | [`docs/BROWSER_MODES.md`](docs/BROWSER_MODES.md) | 四种浏览模式的行为差异、AUTO 的升级阶梯与边界、每网址设置 |
| 1.02 交付记录 | [`docs/RELEASE-1.02.md`](docs/RELEASE-1.02.md) | 上一版的构建日志与冷构建自足性验证（留档） |
| 变更历史 | [`CHANGELOG.md`](CHANGELOG.md) | 1.0 → 1.0.1 → 1.02 → 2.2 → 1.03 → 1.04 → 1.05 → 1.06 → 1.07 → **1.08** 的逐条变更 |
| 校验和 | [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt) | `dist/` 下全部 20 个文件（含本版 3 个新产物）的 SHA-256（放在压缩包之外，避免自我指涉） |

APK 实测信息（`aapt2 dump badging` / `apksigner verify`）：

```
package: name='com.lumobrowser.app.debug' versionCode='7' versionName='1.05-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE
               + FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
                 （声明给 LumoVpnService——本版没有协议实现，服务会在 startForeground() 之前
                  就以 NoBackend 自停，所以这三条实际从未被请求）
service: com.lumobrowser.app.vpn.LumoVpnService
         android:permission="android.permission.BIND_VPN_SERVICE"
         android:foregroundServiceType="specialUse"
         <intent-filter> android.net.VpnService
service: com.lumobrowser.app.accessibility.LumoAccessibilityService
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
         <intent-filter> android.accessibilityservice.AccessibilityService
         <meta-data> android.accessibilityservice → @xml/lumo_accessibility_service
大小 18.44 MB（19,340,939 字节）
SHA-256 d7894d077d3af692989a131ebcd99428efe8313e4f473f42d9ad5ba74f009bcb
签名 APK Signature Scheme v2 通过，证书 CN=Android Debug（Debug 签名，非发布签名）
资源表 res/raw/ 下实测 76 个 .wav；classes.dex 实测含 accessibility/LumoAccessibilityService、
  accessibility/AccessibilityAssistRuntime、accessibility/AccessibilityAssistKt、
  accessibility/AssistTarget、accessibility/AssistOutcome、accessibility/AccessibilityStatus、
  accessibility/AssistSettings、accessibility/AssistTargetCodec，
  以及 vpn/LumoVpnService、vpn/VpnController、vpn/VpnAppDirectory、
  以及 1.04 新增的 browser/WebNavigationPolicyKt、browser/IntentUrlKt、browser/ExternalLinkOffer、
  util/ExternalLauncher、ui/ExternalLinkSheetKt
  ui/components/StartupAnimationKt、feedback/SoundSynth、feedback/SoundLibrary
  以及 1.05 新增的 feedback/SoundGate、feedback/SoundBlock、feedback/SoundStatus、
  browser/CrossSiteLinkPolicyKt、ui/HomeScreen、ui/BrowserScreen、ui/components/Surfaces
```

> 最后两行不是装饰：76 个生成素材正是那种「悄悄没打进包、编译照样通过、装上也照样能开，只是全程静音」的东西。发布前的校验脚本会在**源码树、源码压缩包、APK 资源表**三处各数一遍 76 这个数，数不上就拒绝发布；`classes.dex` 里那一串类名同理——它们证明这个 APK 确实含有 1.03、1.04、1.05 与 v2.2 的工作，而不只是声称含有。
>
> **1.03 的校验脚本还多问了三件事**，每一件都来自本版自己的事故：三份 `strings.xml` 是不是各有 371 条字符串、名称集合是否完全一致（它们在 1.03 期间被写坏过一次，见 [`CHANGELOG.md`](CHANGELOG.md)）；以及 **APK 的 manifest 是否真的声明了那个服务**——一个存在于 Kotlin 却从没进 manifest 的服务对 Android 的无障碍设置是不可见的，用户会在列表里找不到 LUMO，然后没有任何东西可以打开。第一次跑这条检查时报了「未声明」，而 `aapt2 dump xmltree` 证明 manifest 其实是对的：**是我自己的检查用 UTF-8 字节搜一个 UTF-16 的字符串池**。这个误报本身就是本版的主题——一个看起来在检查、其实永远为假的判断。
>
> > **本版 APK 是本机构建的产物**（`app/build/outputs/apk/debug/app-debug.apk` → 复制为 `dist/LumoBrowser-v1.05-debug.apk`），交付装配用 `clean :app:assembleDebug --no-build-cache` 得到 `BUILD SUCCESSFUL in 36s`（`41 actionable tasks` 里 **40 个真的执行**、没有一个任务的输出来自构建缓存，所以这一版的「Kotlin 警告 0 条」不需要任何限定语），单元测试 `:app:testDebugUnitTest --no-build-cache` 在最终源码上得到 `BUILD SUCCESSFUL in 22m 51s`、505 例全绿。**与 1.02 不同，2.2、1.03、1.04 与本版都没有做"源码包解开重新冷构建"的二次验证**——这一条明确记在 [`docs/TESTING.md`](docs/TESTING.md) 的未执行清单里，没有当成做过。
>
> Debug APK 是**逐次重新签名**的，每次构建的 SHA-256 都不同（DEX 里的 `BuildConfig.BUILD_TIME`、zip 条目时间戳与签名块都会变）。上面这个哈希对应的是 `dist/` 里那一个文件，换台机器重新构建得到的是**功能等价但哈希不同**的包——想校验手上的文件是否就是本次交付的包，请比对 [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt)。

1.08 两个包的实测信息（`aapt dump badging` / `apksigner verify` / `aapt dump xmltree`，全部在本机跑过）：

```
--- dist/LumoBrowser-v1.08-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='10' versionName='1.08-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
大小 18.63 MB（19,535,475 字节）
SHA-256 833fee7ea51cd2507def2e09a8e0eec9911aac1c85665fbd2854b04c7c5c911e
签名 APK Signature Scheme v2 通过，证书 C=US, O=Android, CN=Android Debug（Debug 签名，非发布签名）
     证书 SHA-256 ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934
调试标记 aapt dump xmltree 命中 android:debuggable(0x0101000f)=(type 0x12)0xffffffff

--- dist/LumoBrowser-v1.08-release.apk ---
package: name='com.lumobrowser.app' versionCode='10' versionName='1.08'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'          ← 无 application-debuggable（xmltree 里 0 命中）
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
大小 12.57 MB（13,182,384 字节）
SHA-256 876bafa2160af51cb41ecc783972a3340d111c103ebf1aa97a272249835a6e42
签名 APK Signature Scheme v2 通过，v1/v3/v3.1/v4 为 false（与 1.07 一致），1 个签名者
     证书 DN: CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN
     证书 SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9
     ← 与 1.07 Release 同一把密钥 ⇒ 可直接覆盖手机上已装的 1.07
16 KB 页面对齐：从包内取 libdatastore_shared_counter.so 自解析 ELF 程序头，
     arm64-v8a 三个 PT_LOAD 的 p_align 均为 0x4000、x86_64 两个均为 0x4000
     （build-tools 34.0.0 的 zipalign 没有 -P 选项，所以只能这样查）
zipalign -c -p -v 4 两个包均 Verification succesful
出货运载物抽查：aapt dump strings 在 Release 包里读到本版新增的
     「安全浏览已开启：由系统按自己的清单检查每个地址，本浏览器不会覆盖它给出的警告」；
     classes.dex/classes2.dex 中 NavigationSecurityManager 与 pausedEntriesFor 命中 14 处
```

> **1.08 也是本机构建的产物**（`app/build/outputs/apk/{debug,release}/` → 复制为 `dist/LumoBrowser-v1.08-*.apk`）。本版用的是**分层构建**：`assembleDebug` 与 `assembleRelease` 合并成一次调用，`BUILD SUCCESSFUL in 1m 44s`、0 条 `e:` 与 0 条 `w:`；单元测试 `:app:testDebugUnitTest` 在最终源码上得到 `BUILD SUCCESSFUL in 38s`、**718 例 / 44 类全绿**。构建日志原文收在 [`docs/build-logs/`](docs/build-logs/README.md)（`v108-assemble.txt`、`v108-tests.txt`、`v108-compile.txt`）。**没有做「解开源码包重新冷构建」的二次验证**，理由与边界记在 [`docs/TESTING.md`](docs/TESTING.md) §7.0；因此「0 警告」的准确含义是**本轮改动的文件 0 警告**，不是全树冷构建 0 警告。

1.07 两个包的实测信息（`aapt2 dump badging` / `apksigner verify`）：

```
--- dist/LumoBrowser-v1.07-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='9' versionName='1.07-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
大小 18.52 MB（19,416,196 字节）
SHA-256 39b472d87c0832652c9246ced158d27d594f43568619bf22ed4b9fb9a7b276c2
签名 APK Signature Scheme v2 通过，证书 CN=Android Debug（Debug 签名，非发布签名）

--- dist/LumoBrowser-v1.07-release.apk ---
package: name='com.lumobrowser.app' versionCode='9' versionName='1.07'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'          ← 无 application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
大小 12.49 MB（13,099,148 字节）
SHA-256 4ad270c838863baa2ee72c00073d7a5e230adbaf3e53355d1079fd49057424e
签名 APK Signature Scheme v2 通过，证书 CN=LumoBrowser
     证书 SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9
```

> **Release 包比 Debug 包小 6.3 MB，不是混淆的功劳**：两个变体都**没有**开启 R8（Release 的 `isMinifyEnabled = false` 是刻意的，理由见上文 1.07 公告第三节），差出来的是 Debug 变体额外带进包的 `ui-tooling` 与 `ui-test-manifest` 两个调试依赖。
>
> **两个包能同时装在一台手机上**：Debug 的包名带 `.debug` 后缀（`applicationIdSuffix`），Release 不带。这也意味着**从 Debug 换到 Release 不会继承数据**（不同的包 = 不同的应用），想保留收藏请先用应用内的「备份」导出。

源码包实测信息：

```
LumoBrowser-v1.08-source.zip  287 个条目（1.07 是 267 档；含 docs/ 下 20 份文档、22 份构建日志、
                              76 个音效素材、assets/adblock/rules.txt、keystore 2 份）
内容：Gradle 工程 + 全部 Kotlin/XML 源码 + 测试源集 + README/docs/CHANGELOG + Room schema JSON
      + docs/ACCESSIBILITY_ASSIST.md + 无障碍服务配置 xml + 六个 accessibility 源文件
      + docs/SOUND_FEEDBACK.md + docs/STARTUP_OPTIMIZATION.md + docs/EXTERNAL_BROWSER_POLICY.md
      + docs/AD_BLOCKING.md（含 §12 1.08 广告屏蔽）/ docs/VIDEO_PLAYBACK.md / docs/NAVIGATION_SECURITY.md
      + docs/ANIMATION_SYSTEM.md + docs/CRASH_RECOVERY.md
      + app/src/main/assets/adblock/rules.txt（出厂广告屏蔽规则）
      + app/src/main/java/.../crash/ 下 4 个源文件与 app/src/test/.../crash/ 下 4 个测试类
      + app/src/main/java/.../browser/security/ 下的 NavigationSecurity 核心与测试
      + tools/generate_sfx.py + tools/verify_sfx.py + tools/package_source.ps1
      + app/src/main/res/raw/ 下 76 个音效素材（19 事件 × 4 风格）
      + keystore/lumobrowser-release.jks 与 keystore/keystore.properties（一次性侧载密钥，口令见 docs/BUILD.md §6）
不含：build/、.gradle/、.idea/、.kotlin/、dist/、local.properties（含本机绝对路径，需自行创建）、
      .apk/.aar/.zip
```

> 压缩包的**精确字节数与 SHA-256 不写在本文件里**——本文件本身就在压缩包内，写进去会自我指涉、永远对不上。交付物的精确哈希统一公布在压缩包之外的 [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt)。

> 上一版（2.2 / `versionCode=4`）与更早的 1.02 的 APK 与源码包仍在 `dist/` 顶层留档，1.0.1 在 `dist/previous-1.0.1/`。**本版与 1.02、2.2 都用同一个 Debug 证书签名，可以互相覆盖安装**（包名同为 `com.lumobrowser.app.debug`）。`dist/SHA256SUMS.txt` 登记 `dist/` 下全部 **20** 个文件（v1.08 的 Debug / Release / 源码包三条是本版新增），每条都实测校验通过。
>
> 1.02 源码包的**冷构建自足性经过实测**：解压到空目录、只补一个 `local.properties`，跑 `clean :app:assembleDebug :app:testDebugUnitTest --no-build-cache`，独立构建通过并产出 APK（`D:\LumoBrowser-102-coldbuild3`，18 个测试类 263 例 0 失败）。**2.2 与 1.03 都没有重做这一步**，见上文说明。

---

## 2. 快速安装

1. 把 APK 传到手机（或 `adb install dist/LumoBrowser-v1.08-debug.apk`）。**两个包，选一个装**：

   | 文件 | 什么时候选它 |
   | --- | --- |
   | `dist/LumoBrowser-v1.08-debug.apk` | 常规选择。**Debug 签名**，可调试，包名 `com.lumobrowser.app.debug` |
   | `dist/LumoBrowser-v1.08-release.apk` | **装上了却闪退**时优先试这个。真密钥签名、**不可调试**，包名 `com.lumobrowser.app`（不带后缀）。部分 ROM 会拦 debuggable 的侧载应用——那是 ROM 的策略，不是代码问题，换这个包就能排除它。**已装 1.07 Release 的可直接覆盖升级**（同一把密钥、同一包名、versionCode 9 → 10） |

2. 首次安装需在系统设置中允许「安装未知来源应用」。
3. 两个包名不同，**可以同时装在一台手机上**（也因此**数据不互通**：想保留收藏请先在应用内导出备份）。
4. **从 1.02 或更早版本升上来，可以直接覆盖安装**（同一个 Debug 证书、同一个包名）。数据库版本仍是 3，本轮没有 schema 变更，**收藏、分类、浏览模式与原有设置全部保留**。

要求 Android 8.0 及以上。应用不申请存储权限，也不在后台联网。

> **关于「VPN 辅助」这个开关**：本版本**不能建立 VPN 连接**，它的实际用途是——读取系统的网络/VPN 状态、在网页加载失败时判断这算不算网络问题、打开你**已经安装的** VPN 应用或系统的 VPN 设置页、以及在你连上之后重试网页。状态面板会明确区分「已连接」（本应用建立的隧道）与「检测到 VPN 网络」（设备上有 VPN，但本应用无法确认它通向你要的网站）。完整说明见 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md)。

### 2.1 如果装上了却打不开

**应用启动时发生的崩溃，界面上什么都看不到**——没有报错框，没有闪退提示，点图标像是"没反应"。这种崩溃的完整堆栈只会出现在系统日志里，所以取证方式只有一条：用 `adb logcat` 抓。

手机开启「开发者选项 → USB 调试」并用数据线连上电脑后：

```bash
adb logcat -c                                    # 先清空缓冲区，免得看到无关的旧日志
adb logcat -b crash                              # 然后点应用图标，崩溃堆栈会打在这里
```

抓不到 `crash` 缓冲区（部分定制 ROM 会关掉它）时，改用按包名过滤的全量日志：

```bash
adb logcat -c
adb logcat | findstr com.lumobrowser              # Windows
adb logcat | grep com.lumobrowser                 # macOS / Linux
```

日志里的第一段 `FATAL EXCEPTION: main` 和紧随其后的 `Caused by:` 就是根因，把这一段发出来即可。

**没有电脑、只有手机**的话：装一个能读 `logcat` 的应用（需要 Shizuku 或 root 权限），或直接在手机的「开发者选项 → 错误报告」里生成一份错误报告，崩溃记录包含在其中。

### 2.2 如果 1.07 还是打不开

**先再点一次图标。** 1.07 起，应用会记住自己的死亡：第一次崩溃之后，**下一次启动不会直接退出，而是先显示一张「上次崩溃」的说明页**——上面有异常类型与消息、设备事实（Android 版本、ABI、**内存页大小**、**WebView 提供方版本**）、以及完整堆栈的全文。那一页有两个入口：

- **安全模式启动**：进入应用前把环境动效与广告屏蔽全部关掉（动画强度 `0`、预设 `Minimal`、光斑与简化效果关闭、广告屏蔽关闭），**不写回你的设置**——用来判断问题是不是出在某个可关掉的特性上。
- **正常启动**：照常进入应用。

上面那些内容也能**复制**或**分享**出来，所以即使这台手机不方便连电脑，也能把报告发出去。细节见 [`docs/CRASH_RECOVERY.md`](docs/CRASH_RECOVERY.md)。

**如果连说明页都没出现**（即崩溃发生在 `Application.onCreate` 装处理器之前、或进程被系统直接杀掉、或是原生库崩溃），那么这一版能提供的就只有：换用 `dist/LumoBrowser-v1.08-release.apk` 排除「ROM 拦截 debuggable 应用」这一条，以及上面这条 `logcat` 路径。**我不会声称这次一定修好了**——本机没有能复现那台手机的设备，也没有它的日志（[`docs/TESTING.md`](docs/TESTING.md) §0G.7 把它列为「必须由真机回答」的一条）。历史上逐条排查过的「装得上但打不开」成因（启动即崩的 `LocalLumoExtra`、数据库迁移缺失、ABI 缺失、原生库未对齐、签名无效、launcher Activity 未导出）见 [第 14 节](#14-装上了却打不开逐条排除记录)，构建侧证据见 [`docs/RELEASE-1.02.md`](docs/RELEASE-1.02.md) 第八、九节。

---

## 3. 功能一览

### 收藏管理

- 主屏以卡片网格展示收藏，卡片显示 **favicon · 网站名 · 域名 · 可选描述 · 分类 · 置顶徽标**。
- **新增 / 编辑 / 删除**：右上角 `+` 进入编辑器；每张卡片的菜单提供打开、编辑、置顶、复制一份、分享、复制链接、删除。
- **搜索**：按标题、网址、描述三字段匹配，输入时 180 ms 防抖，避免每敲一个字都查库。
- **分类**：编辑器里可自由填写或点选已有分类；主屏分类条可筛选，并含「全部」与「未分类」。
- **置顶**：置顶项排到最前，卡片右上角显示徽标。
- **拖动排序**：**长按**卡片拿起（轻点仍是打开、轻扫仍是滚动列表），拖动时其余卡片让位，松手才写库；靠近上下边缘会自动滚动；网格与列表两种布局都支持。
- **删除可撤销**：删除后底部出现撤销条，撤销会按原 id、原顺序、原时间戳整体还原。

### 内置浏览器

- 点击卡片在应用内 `WebView` 打开，网页占据主要区域。
- 完整导航：**后退 / 前进 / 刷新 / 停止 / 回主页**，按钮按历史栈状态自动可用或禁用。
- **页面历史**：使用 WebView 自身历史栈；`goBack()` 优先于关闭页面。
- **加载进度**：顶部 2 dp 进度条，颜色为主题强调色。
- **错误处理**：主机解析失败、连接失败、超时、证书错误、重定向过多、不支持的协议、代理错误、404、进程被杀等分类提示，并提供**重试 / 关闭 / 用外部浏览器打开**——但**证书错误不提供"继续访问"**。
- **站内链接**：正常在应用内跳转。
- **`target="_blank"` 与 `window.open`**：通过 `WebChromeClient.onCreateWindow` 把新窗口接管到同一个 WebView 中打开，不跳系统浏览器、不丢失浏览上下文。
- **沉浸式工具栏**：工具栏默认隐藏，只留顶部一个 56×22 dp 的细抓手；点抓手展开，2.6 秒无操作自动收起，切换页面后自动收起。**收起状态下不覆盖任何透明点击层**，所以网页的滚动、选中、链接点击手势完全不受影响。
- **收藏当前页**：工具栏上的心形按钮，已在收藏中则显示为勾选态。

### 外观与动效

- **主题**：深色科技风（默认）/ 浅色 / 跟随系统；风格可选科技经典、玻璃拟态、自定义。
- **主题色**：6 个内置色板 + 0–360 色相自定义滑杆；切换时颜色**平滑过渡**而非突跳。
- **背景**：极光光斑 / 网格 / 纯色，强度随主题自动调整。
- **卡片样式**：玻璃 / 抬升 / 描边；**按钮质感**：实心 / 柔和 / 玻璃。
- **圆角** 0–32 dp、**卡片透明度** 0.35–1.0 可调。
- **动画预设**：沉浸 / 柔和 / 极简三种，外加 0–100% 全局动画强度滑杆（调到 0 时所有动画塌缩为瞬时）。
- **降低动效**：当系统开启「移除动画」（`TRANSITION_ANIMATION_SCALE` 或 `ANIMATOR_DURATION_SCALE` 为 0）时，动画强度被压到 35%，极光背景动画直接关闭，但短促的点击反馈保留——是**降低**非必要动效，而不是把反馈全部删掉。

十项动效的实现位置：

| # | 要求 | 实现 |
| --- | --- | --- |
| 1 | 卡片淡入 + 轻微上移 | `HomeScreen.kt` `BookmarkCard`：`alpha` 0→1 与 `translationY` 位移同步，逐项错峰（上限 8 项，避免长列表等太久） |
| 2 | 按钮按下缩小、释放回弹 | `Surfaces.kt` `Modifier.lumoPress`：按下 `tween`，释放 `spring` |
| 3 | 页面平滑转场 | `LumoApp.kt` `NavHost` 的 enter/exit/popEnter/popExit：淡入淡出 + 1/12 视口位移 |
| 4 | 主题色平滑过渡 | `LumoTheme.kt`：对强调色做 `animateColorAsState`，Material 3 的整套色阶由该输入派生，因此整屏配色一起过渡 |
| 5 | 收藏成功反馈 | `MainViewModel.addBookmark` → `SoundCue.Favorite` 三音琶音 + `HapticCue.Success` + Snackbar |
| 6 | 置顶 / 删除 / 撤销反馈 | 置顶与删除各有一对音效与触觉；删除/整表导入后弹出撤销条，撤销有独立文案 |
| 7 | 加载进度动画 | `BrowserScreen.kt`：进度值经 `animateFloatAsState` 平滑到 2 dp 进度条 |
| 8 | 光斑与玻璃质感 | `LumoBackground.kt` 单个 Canvas 画 3 个径向光斑；`lumoSurface()` 提供玻璃填充 + 发丝边 + 高光 |
| 9 | 全局动画等级 | 设置 → 动效：预设 + 强度滑杆，作用于全部动画时长/位移/缩放 |
| 10 | 系统减少动效 | `util/WindowUtil.kt:isSystemReduceMotionEnabled` → `MotionPresets.config` 降级处理 |

背景动效的性能约束（见 `LumoBackground.kt` 注释）：整个背景只用**一个 Canvas** 和**两个 float 动画**（24 s / 37 s 线性），刻意**不使用 `RenderEffect` 模糊**（中端 GPU 上会拖累滚动帧率）；动画关闭时相位冻结为常量，Canvas 不再触发重绘。

### 声音与触觉

- 音效**运行时合成**（`feedback/SoundSynth.kt`）：正弦/三角波 PCM 经 `AudioTrack` 播放，无第三方素材、无版权问题、APK 内不含音频文件。音高：点击 880→660 Hz、确认 659+988 Hz、收藏 523+784+1046 Hz 琶音、开关 520 Hz、错误下行音；每个音都带 ADSR 包络，**≤150 ms 且无爆音**。
- 触觉（`feedback/Haptics.kt`）：轻点用系统 `EFFECT_TICK`，确认/成功/警告用**振幅 ≤190** 的短波形。
- **去抖**：音效 45 ms、触觉 40 ms 内的重复同类触发视为同一次意图。
- **不会在滚动或重绘时触发**：两者都不挂在滚动、页面加载或重组上，只由明确的用户操作调用。
- 设置里可**全局静音**、调**音量**、关**触觉**；关掉触觉时连 `Vibrator` 都不查询。

### 数据与隐私

- 收藏存 Room（`lumobrowser.db`），设置存 DataStore（`lumo_settings`），**重启后全部恢复**。
- **备份 / 恢复**：导出为 JSON（`CreateDocument` 选保存位置），导入时可选**替换**或**追加**；整表替换同样可撤销。文件含 schema 与 version，**拒绝读取更高版本的备份**；单条坏记录只计入 `skipped`，不会拖垮整个文件。
- **不默认上传任何数据**：无账号、无统计、无广告、无自建后端。应用只有 `INTERNET` / `ACCESS_NETWORK_STATE` / `VIBRATE` 三个权限。
- `android:allowBackup="false"`：收藏与设置不进入系统云备份，也不参与设备迁移（想迁移请用应用内的导出功能，由用户自己决定数据去向）。
- 唯一的外发请求是**卡片 favicon**：`https://icons.duckduckgo.com/ip3/<host>.ico`，只带主机名，由 Coil 缓存在磁盘。不加载 favicon 也不影响任何功能。

### 设置项

外观（主题模式、主题风格、主题色 + 自定义色相、背景、卡片样式、按钮质感、圆角、卡片透明度）、动效（预设、强度、跟随系统降低动效）、反馈（音效开关、音量、触觉开关）、布局（网格/列表、置顶优先、亮度）、语言（跟随系统 / 简体中文 / 繁体中文 / English）、数据（导出、导入、清除浏览数据、重置全部设置）、关于（版本、收藏数、隐私说明）。

- **亮度**优先通过**当前 Activity 窗口**设置（`window.attributes.screenBrightness`），只有用户主动选择「跟随系统」时才回落到系统亮度，**全程不修改系统全局亮度**。
- **语言切换**：`AppLanguage` 枚举 + Activity 重建 + 一个用于 `attachBaseContext` 的同步 SharedPreferences 镜像（DataStore 是异步的，而 `attachBaseContext` 不是协程作用域）。选「跟随系统」则交还系统语言。

---

## 4. 工程结构

```
LumoBrowser/
├── app/
│   ├── build.gradle.kts            # 单模块；debug 加 .debug 后缀，release 开启混淆与资源压缩
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/lumobrowser/app/
│       │   │   ├── LumoApplication.kt      # 手写 service locator（只有 5 个对象，不引 DI 框架）
│       │   │   ├── MainActivity.kt         # edge-to-edge、窗口亮度、发送/查看意图入口
│       │   │   ├── data/                   # Model / Database(Room) / 三个 Repository / UrlTools / BackupManager
│       │   │   ├── browser/                # LumoWebView / BrowserState / LumoJsBridge / LumoErrorPage
│       │   │   ├── ui/                     # LumoApp 导航、四个 Screen、MainViewModel、Icons、TestTags
│       │   │   │   ├── components/         # LumoBackground / Surfaces / Reorder（自研拖动排序）
│       │   │   │   └── theme/              # LumoTheme / Color / Motion / Shape / Type
│       │   │   ├── feedback/               # LumoFeedback（CompositionLocal 接线）/ SoundSynth / Haptics
│       │   │   └── util/                   # JsonUtil / FormatUtil / WindowUtil / FileIo / LumoStrings
│       │   └── res/                        # 三套 strings、主题、纯 vector 图标（无 PNG 二进制）
│       ├── test/                           # 223 个 JVM 单测（纯逻辑 + Robolectric + 19 个真的启动 Activity 的冒烟用例）
│       └── androidTest/                    # 5 个 Compose UI 测试（本机无设备，仅通过编译）
├── docs/BUILD.md · docs/TESTING.md
├── dist/                                   # 交付的 APK 与源码 zip
├── gradle/libs.versions.toml               # 版本目录
└── gradlew / gradlew.bat / gradle/wrapper/
```

分层原则：`data` 不认识 Compose，`browser` 不认识 Room，`ui` 通过 `MainViewModel` 单点访问两者。整个应用**只用一个 Activity 作用域的 ViewModel**——Home、Editor、Browser 改的是同一份收藏数据，单真相源比三个 ViewModel 互相通知更不容易出错，撤销快照也才能跨页面存活。

---

## 5. 安全设计

WebView 是这类应用最大的攻击面，本项目的取值都偏向保守：

| 项 | 取值 | 原因 |
| --- | --- | --- |
| JavaScript | 开启 | 现代网页必需 |
| DOM Storage / Database | 开启 | 登录态与站点数据依赖它 |
| 文件访问（`allowFileAccess` / `allowContentAccess`） | **关闭** | 网页无需读取本机文件 |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` | **关闭** | 阻断 `file://` 页面的跨源读取 |
| 混合内容 | `MIXED_CONTENT_NEVER_ALLOW` | HTTPS 页面不得拉取明文子资源 |
| `onReceivedSslError` | **只 `cancel()`，从不 `proceed()`** | **不忽略任何证书错误**；错误页明确写出这一立场 |
| 协议白名单 | 仅 `http` / `https` | `javascript:`、`data:`、`file:`、`intent:`、`content:` 一律拦截，且不交给系统处理 |
| 权限请求（相机/麦克风/定位） | 不覆写 `onPermissionRequest` | 默认拒绝 |
| JS 桥 | 仅 2 个方法：`vibrate()`、`hostVersion()` | 最小权限；无 getter、不碰文件、不暴露特权回调；触觉仍受用户开关约束 |
| 媒体自动播放 | `mediaPlaybackRequiresUserGesture = true` | 避免打开页面就出声 |
| 第三方 Cookie | 默认策略，未放宽 | — |
| 应用间数据 | `allowBackup="false"` + `dataExtractionRules` 全排除 | 收藏与设置不出设备 |
| FileProvider | 仅暴露 `cache/share/` | 私有数据库与设置不可达 |

错误页（`browser/LumoErrorPage.kt`）是注入到当前文档里的自包含覆盖层：**不请求任何外部字体、图片、脚本**，重试/关闭/外部打开三个按钮通过 `lumo-error://` 自定义协议回报，因此不需要为它额外扩大 JS 桥的暴露面。

---

## 6. 已知限制

- **仅 Debug 签名**：没有配置 Release 签名密钥，因此只交付了 Debug APK。Debug 签名不能用于正式分发，Release 签名的配置步骤见 [`docs/BUILD.md`](docs/BUILD.md)。
- **未在真机/模拟器上跑过自动化测试**：本机没有 Android 模拟器（`emulator` 与 `system-images` 均未安装）也没有连接设备，所以 `connectedDebugAndroidTest` 与 `adb install` 未执行。为了不让这条盲区继续掩盖问题，本轮新增了两个**在 JVM 上真正启动 `MainActivity`、真正点击按钮、真正读写 Room/DataStore** 的冒烟测试类（共 18 例），启动路径与五条核心流程现在都有自动化覆盖。详见 [`docs/TESTING.md`](docs/TESTING.md) 第 2.2 与 4.3 节。
- **无多标签页**：`target="_blank"` 的新窗口在同一个 WebView 中打开，没有标签管理器。
- **无广告拦截、无阅读模式、无下载管理**：不在本次需求范围内。
- **favicon 依赖第三方**：`icons.duckduckgo.com`，离线或被墙时回落到按域名生成的稳定首字母占位色块（不会出现空白框）。
- **不支持无点主机名**：`http://localhost` 这类地址会被判为无效网址（与"必须有可解析域名"的校验规则一致）。

---

## 7. 第三轮：设置里切换语言卡死

用户报告（真机侧载）：**在设置里切换语言时直接卡住**，而且此后每次启动都卡。这不是偶发，是结构性死循环。

### 7.1 根因

三个环节叠在一起才成了死循环，缺任何一个都不会发生：

| 环节 | 位置 | 事实 |
| --- | --- | --- |
| 偏好收集用了非空占位值 | `MainActivity.kt` | `collectAsStateWithLifecycle(initialValue = UserPreferences.Default)` |
| 占位值的语言恒为"跟随系统" | `data/Model.kt` | `val appLanguage: AppLanguage = AppLanguage.System` / `val Default = UserPreferences()` |
| 决策拿"存储值"和"组合内 remember 的值"相比 | `MainActivity.kt` | `var appliedLanguage by remember { mutableStateOf(prefs.appLanguage) }` |

因为每个新 Activity 的**首次组合只看到占位值**，`remember` 种下的 `appliedLanguage` 恒为 `System`；等真实偏好（例如 English）到达时判定不符 → `recreate()` → 新实例又从 `System` 重新开始。**只要存过任何非"跟随系统"的语言就永不收敛**。用户此前没改过语言，存储值恰好等于占位值，所以前两轮从未触发——这正是它躲过 140 个单元测试的原因：那些测试从不跨越"真实偏好到达"这一刻。

### 7.2 修复

1. `appliedLanguage` 从"组合内的 `remember`"提升为 **Activity 字段**，在 `attachBaseContext` 里（任何视图存在之前）从 `LanguageMirror` 读取并记录；`null` 表示"用系统 locale"。
2. 偏好收集改为 `initialValue = null`（`loaded == null` 表示**尚未加载**），决策因此能区分"还没加载"和"已加载且相符"——**这正是原来不可能做出的区分**。
3. 决策收敛成一个纯函数，可以被单元测试直接钉住：
   ```kotlin
   internal fun shouldRecreateForLanguage(applied: AppLanguage?, loaded: UserPreferences?): Boolean {
       val target = loaded?.appLanguage ?: return false
       return applied != target
   }
   ```
4. **先同步写镜像再重建**：`LanguageMirror.write(context, target.appLanguage)` 在 `recreateActivity()` 之前执行。`attachBaseContext` 不是协程，下一次实例建立时必须已经能读到新值。
5. `LanguageMirror` 改存 `AppLanguage` 枚举名而非 `Locale`。原来 `System` 经 `toLocale()` 映射为 `null`，镜像**无法区分"跟随系统"与"从未写入"**——这是回环的另一半。

### 7.3 为什么现在不可能再回环

| 情境 | `appliedLanguage` | 加载后的偏好 | 重建次数 |
| --- | --- | --- | --- |
| 冷启动，已存 English 且镜像已写 | `English` | `English` | **0** |
| 从"跟随系统"切到 English | `null` | `English` | 1（写镜像后重建，新实例读到 English ⇒ 收敛） |
| 从 English 切回"跟随系统" | `English` | `System` | 1（同理收敛） |
| 冷启动时镜像尚未写入（竞态） | `null` | 任意 | 1（重建后镜像已写 ⇒ 收敛） |

### 7.4 版本号

`versionCode` 1 → **2**，`versionName` `1.0.0` → **1.0.1**（Debug 变体为 `1.0.1-debug`）。新增 `BuildConfig.BUILD_TIME`（在 `app/build.gradle.kts` 里用 `SimpleDateFormat` 生成），设置页"关于"面板多出一行**构建时间**——侧载的 Debug 包必须能自己说清是哪一个。

### 7.5 本轮回归结果

```
BUILD SUCCESSFUL in 3m 35s                          (clean + --no-build-cache)
Kotlin 编译 0 error、0 warning
单元测试 11 个类 · 147 用例 · 0 失败 · 0 错误 · 0 跳过   （原 140 + 本次新增 7）
```

新增的 `LanguageSwitchTest.kt` 共 7 例：`LanguageSwitchDecisionTest` 6 例（纯函数——含"占位值绝不触发重建"即原始缺陷本身、"对每一种存储语言都恰好收敛于 1 次重建"、"收敛之后连算 50 帧都不再重建"、"镜像对每种语言都能往返，包括跟随系统"），`LanguageSwitchLaunchTest` 1 例（真启动 `MainActivity`，断言全过程**只创建了 1 个 Activity 实例**，且运行中的 Activity 资源 locale 确实是 `en`）。

### 7.6 写这个回归测试时踩到的两个坑（供维护参考）

- **`ShadowLooper.idle()` 在有无限动画时永不返回**：主屏背景光斑是无限动画，每跑一帧就重排下一帧，队列永远不为空，`idle()` 于是自旋到 `OutOfMemoryError`。Compose 自己的测试规则知道"判断空闲时要忽略无限动画"，裸的 looper pump 不知道。改用 `idleFor(Duration)` 按有界虚拟时间推进。
- **`Activity.recreate()` 在 Robolectric 下会把 Compose Activity 撑爆**：本想加一个"强制重建一次、确认计数器真的看得见"的正向对照测试，结果测试 JVM 直接 OOM。该对照已删除；因此本测试**只声称**基线启动恰好创建 1 个 Activity、且任何后续创建都会被同一个 `onActivityCreated` 回调计到，**不声称**已观察到一次强制重建。同一说明也写在 [`docs/TESTING.md`](docs/TESTING.md) 里。

## 8. 第二轮：性能与交互修复（卡顿 / 按键没反馈 / 不灵敏）

审查方式：先把全部 Kotlin 源码按 10 个检查项逐文件过一遍（组合期读动画状态、每帧对象分配、只定义不调用的公开函数、缺 `indication` / 触摸目标 <48dp、主线程阻塞 I/O、缺稳定 key 导致动画重播、O(n²) 扫描、WebView 卡顿、滚动时发声振动、Compose 正确性），**确认 11 个真实缺陷**，逐条修复后重新冷构建 + 全量回归。

### 8.1 卡顿（5 项）

| # | 位置 | 成因 | 修法 |
| --- | --- | --- | --- |
| 1 | `ui/components/LumoBackground.kt` | **组合阶段**读取 `rememberInfiniteTransition` 的相位（`val a = if (animate) phase else 0.15f`），于是**每帧重组整个主屏**并写回新的绘制 lambda；`drawAurora` 每帧还新建 3 个 `Brush.radialGradient` + 一个 `List` + 3 个 `Triple`（10+ 次分配）。且该读取**与背景样式无关**——选"纯色/网格"照样每帧重组。文件自己的注释还写着"不每帧分配 brush"，实测违反了这条承诺 | 相位改成 `State<Float>`（**故意不用 `by` 委托**），唯一读取点放进 `Modifier.graphicsLayer { }`——只重写 RenderNode 变换，不重组、不重绘；动画关闭时**根本不创建** `InfiniteTransition`，改用冻结相位走静态路径 |
| 2 | `ui/BrowserScreen.kt` | 加载进度用 `val progress by animateFloatAsState(...)` 且喂给 **layout** modifier（`.fillMaxWidth(progress)`）⇒ 网页正在加载时整屏每帧重组**并重新 measure**（最忙的时候最贵） | 进度条改为 `Modifier.drawBehind { }`，在绘制 lambda 里读 `progress.value`；只重绘，不重组、不重新布局 |
| 3 | `ui/HomeScreen.kt` | 卡片入场动效用 `remember(bookmark.id)` 记状态，而 `LazyVerticalGrid` 滚出视口会销毁 item ⇒ **每次滚回来都重播淡入+上移+索引延迟**（最长约 360ms）。卡片 `alpha=0` 期间**仍然可点**，等于让人去点看不见的卡片 | 新增 `EntryAnimationTracker`（`HashSet<Long>`）按 `selectedCategory` 记忆"这个 id 是否已入场过"，只有**首次**才播动画；已入场过的卡片直接以 `appeared=true` 起步 |
| 4 | `ui/components/Reorder.kt` | 拖动排序里 `state.autoScrollDelta(change.position.y, size.height)` 把**卡片局部坐标**当成**视口坐标**传给一个按视口算边距的函数 ⇒ 手指刚过卡片上缘三分之一就被判成"到屏幕边"而触发自动滚动；且超出卡片边界后返回值没有上限 | 新增 `requestAutoScroll(key, itemLocalY)`：从 `listState.layoutInfo.visibleItemsInfo` 取该 item 的 `offset.y`，换算出**真正的视口坐标**再算边距；`autoScrollDelta` 一律 `coerceIn(-MAX_STEP, MAX_STEP)`（26f） |
| 5 | `ui/HomeScreen.kt` | 拖动时每个 item 每帧都执行 `bookmarks.indexOfFirst { it.id == draggingKey }` 与 `bookmarks.indexOf(bookmark)` ⇒ O(n²) | 提升为 grid 层一次计算（`draggingKey` / `previewIndex` / `draggingFrom`），列表改 `itemsIndexed` 直接用下标 |

### 8.2 按键没反馈（3 项）

| # | 位置 | 成因 | 修法 |
| --- | --- | --- | --- |
| 6 | `ui/MainViewModel.kt` | `playTap` / `playToggle` / `playConfirm` / `playError` 四个方法**全工程零调用点**——音效与触觉的接线从未接上，所以"按钮有声音有振动"这件事一直只是代码里的意图 | 新增 `feedback/LumoFeedback.kt`（`tap/toggle/confirm/error`），在 `LumoApplication` 里实现并作为 `LocalLumoFeedback` 提供；四个 `playXxx` 改为委派，单一真相源。**默认值是 no-op 而不是 `error()`**——缺 provider 只能降级成"没声音"，绝不能让首次组合崩（那正是第一轮 `ProvideLumoExtras` 的教训） |
| 7 | `ui/components/Surfaces.kt` | 四个共用按钮把 `indication` 显式设成 `null` ⇒ 连水波纹都没有，按下没有任何视觉回应 | 新增 `lumoRipple(color)`（`rememberRipple(bounded = true)`），四处统一带上按键色；按钮的 `onClick` 统一先发 `feedback.tap()`。`DropdownMenuItem` 也包成 `LumoMenuItem` 一并接线 |
| 8 | `ui/SettingsScreen.kt` | 开关与滑杆完全没有反馈 | `SwitchRow` 发 `toggle()`，`ActionRow` 发 `tap()`；**滑杆只在 `onValueChangeFinished` 发**——`onValueChange` 每帧都触发，拖动时逐帧发声振动正是需求明令禁止的 |

### 8.3 点击不灵敏（3 项）

| # | 位置 | 成因 | 修法 |
| --- | --- | --- | --- |
| 9 | `ui/components/Surfaces.kt` | `LumoIconButton` 的命中区就是 `containerSize`，而卡片上的"更多"按钮传的是 32dp；`LumoTextButton` 高约 38dp、`LumoChip` 约 33dp——都低于 48dp | 新增 `LumoMinTouchTarget = 48.dp`；`LumoIconButton` 拆成**外层命中盒**（`maxOf(touchTarget, containerSize)`）+ **内层视觉盒**（尺寸不变），`LumoTextButton` / `LumoPrimaryButton` 加 `.defaultMinSize(minHeight = 48.dp)`，`LumoChip` 外层扩到 48dp 高（只扩高不扩宽，避免相邻命中区重叠）。同时该按钮加回了 `.lumoPress()` |
| 10 | `ui/BrowserScreen.kt` | 浏览器工具栏默认隐藏（沉浸式），唯一能唤出它的是顶部 56×**22dp** 的细把手，还没有水波纹——失手就穿透到网页 | 手柄外层改为 `.width(56.dp).height(LumoMinTouchTarget)` + `lumoRipple`，视觉仍是那条 22dp 药丸（内层居中、`padding(top = 6.dp)`），观感不变但命中区翻倍 |
| 11 | `ui/SettingsScreen.kt` | 十个主题色预设是裸 30dp 圆形，**可见圆就是命中区**，间隔仅 10dp | 每颗外层套 48dp 的 `Box` + `lumoRipple`，30dp 圆变成内层视觉 |

### 8.4 顺带修掉的相关问题（同一轮审查发现，非用户直接报告）

| # | 位置 | 问题 | 修法 |
| --- | --- | --- | --- |
| 12 | `ui/SettingsScreen.kt` → `SettingsRepository` | 圆角/卡片透明度滑杆**每个拖曳帧都做一次 DataStore 原子写盘**，值再经 Flow 回流 ⇒ 整棵主题树每帧重组，滑杆拇指还会落后于手指 | `SliderRow` 改用本地 draft（`mutableFloatStateOf` + `dragging` 标志 + `LaunchedEffect` 同步外部值），拖动只改本地，**松手才持久化**。签名由 `valueText: String` 改为 `textFor: (Float) -> String`，5 个调用点全部跟上 |
| 13 | `ui/HomeScreen.kt` | 每张卡片每次重组都重新构造 `ImageRequest` + crossfade transformer，并重新 `URI(url).host` 解析 | `remember(url)` / `remember(favicon, context)` 缓存 |
| 14 | `ui/HomeScreen.kt` | 浅色模式下"新增收藏"按钮用 `onPrimary`（白）作 tint，但三种按钮质感**都没有画实心强调色**（Solid 其实是 `surfaceContainerHighest`，Soft 只有 12–14% 透明度，Glass 是 45% 容器色）⇒ 白字压浅底，对比度约 1.1:1，主操作几乎看不见（深色模式正常，所以一直没暴露） | tint 改用 `LumoTheme.state.accent`——但第一次改成 `theme.accent` 时编译报 `Unresolved reference: theme`（该 lambda 内 `theme` 不在作用域），已改为从 `LumoTheme.state` 取 |
| 15 | `ui/LumoApp.kt` | 备份导出/导入的 **JSON 序列化 + SAF 读写全在主线程**（`rememberLauncherForActivityResult` 的回调就在主线程）⇒ 收藏多时冻结数百毫秒且没有任何进度 | 搬进 `rememberCoroutineScope().launch(Dispatchers.IO)`；结果经 `MainViewModel`（其内部本就是 `viewModelScope.launch`）回主线程 |
| 16 | `ui/LumoApp.kt` | 撤销条只有一个"撤销"按钮，`onDismiss` 只能**经由**撤销才能抵达 ⇒ 不想要的删除操作会永远压在主屏底部，且与同样 `BottomCenter` 的 snackbar 打架 | 补上该组件注释里本来就承诺的 dismiss 半区：`LumoIconButton(LumoIcons.Clear)`，新增 testTag `undo-dismiss` |
| 17 | `ui/LumoApp.kt` | 主题化 snackbar `lumoSnackbar(text)` 从未被引用——`SnackbarHost` 没有 content lambda，实际渲染的是默认 Material 样式（**能编译、但不生效**的死代码） | `SnackbarHost(..., snackbar = { data -> lumoSnackbar(data.visuals.message) })` |
| 18 | `ui/BrowserScreen.kt` | `onVibrateRequest = { }`——网页 `navigator.vibrate()` 的整条路径接成空 lambda，而 JS 桥仍然对外宣称支持它 | 接到应用自己的 `Haptics`（它持有用户的触觉开关与去抖），不直接碰 `Vibrator` |
| 19 | `ui/BrowserScreen.kt` | 同一组合内两次调用 `isBookmarked(state.url)`，而它的实现是对全部收藏做 `any { it.url == ... }` 线性扫描 ⇒ WebView 每次状态发射都跑两趟全表扫描 | 提为单个 `val bookmarked` |
| 20 | `ui/components/LumoBackground.kt` | 我重写后留下一个从未使用的 `theme` 参数，编译报 `Parameter 'theme' is never used` | 从 `StaticBackdrop` 去掉该参数，三个调用点改为在 lambda 里闭包捕获 `theme`；**编译恢复零 warning** |

### 8.5 明确排查过、确认无问题的部分（不是遗漏）

| 检查项 | 结论 |
| --- | --- |
| WebView 是否被重复创建 | 否。只在 `AndroidView` 的 `factory` 里建一次，`update` 只重指派 holder；`onPause` / `pauseTimers` / `destroy` 都有处理；`hardwareAccelerated="true"` |
| 滚动或重绘时是否发声音/振动 | 否。音效合成在 `Dispatchers.Default`（`Thread.sleep` 不在主线程），触觉有 40ms 去抖，声音有 45ms 去抖，都不挂在滚动/绘制回调上 |
| 列表 key 是否稳定 | 是。grid 与分类行都用稳定 key，没有"因缺 key 而丢状态"的问题 |
| 触摸目标 | 修复后所有主要可点元素 ≥48dp（卡片本身就是整块） |

### 8.6 本轮回归结果

```
clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon --no-build-cache
BUILD SUCCESSFUL in 3m 35s        Kotlin 编译 0 error、0 warning
单元测试 147 用例 · 0 失败 · 0 错误 · 0 跳过   （原 140 + 本次新增 7）
```

> 这 147 例里只有 19 例真的启动了 `MainActivity`。**第 1 项（背景每帧重组）和第 9 项（触摸目标）这类缺陷，单元测试是抓不到的**——它们不是逻辑错误，而是"能跑但代价高"和"能点但点不中"。这正是第 9 节把"动效流畅度"与"触摸命中率"列进**未验证**清单的原因：本机没有设备，我无法诚实地声称这两项已经过实测。

---

## 9. 第一至三轮实际验证了什么（历史记录）

下面每一行都来自本机的真实运行输出，不是设计意图，也不是推断。

| 验证项 | 怎么验证的 | 结果 |
| --- | --- | --- |
| 冷构建（清空 `build/` 与 `.gradle/`，禁用构建缓存） | `clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-build-cache` | ✅ `BUILD SUCCESSFUL in 3m 35s`，**Kotlin 编译 0 error、0 warning** |
| 应用能否启动 | `StartupSmokeTest`：真的创建 `MainActivity`、真的跑 Compose 组合 | ✅ **7 / 7 通过**（第一轮修复前是 7 / 7 全部以同一异常失败） |
| 五条核心流程能否走通 | `AppFlowSmokeTest`：点真实按钮，断言 Room / DataStore 里的真实数据 | ✅ **11 / 11 通过** |
| 单元测试总量 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` | ✅ **147 用例，0 失败，0 错误，0 跳过**（第三轮新增 7 例：6 个纯函数决策用例 + 1 个真启动 Activity 的用例） |
| 交付的源码包是否自足 | 把 `dist/LumoBrowser-source.zip` 解压到一个**空目录**、只补一个 `local.properties`，重新跑上面那条冷构建命令 | ✅ 独立构建通过，147 用例全绿——证明包里确实不缺文件 |
| APK 是不是一个真实可安装的包 | `aapt2 dump badging` 读清单 | ✅ 包名 `com.lumobrowser.app.debug`、可启动 Activity `com.lumobrowser.app.MainActivity`、`minSdk 26`、`targetSdk 34`、**三个权限**（INTERNET / ACCESS_NETWORK_STATE / VIBRATE）、四种 ABI |
| APK 的签名是否有效 | `apksigner verify --verbose --print-certs` | ✅ `Verifies`；**APK Signature Scheme v2** 通过，签名者 `CN=Android Debug`（**Debug 签名，不是发布签名**） |
| 交付物哈希是否与实际文件一致 | `Get-FileHash -Algorithm SHA256` 重新计算并与 [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt) 比对 | ✅ 一致（APK `e9a0eee2…`） |

**没有验证的（不要当成已验证）**：`connectedDebugAndroidTest`（需要设备）、真机安装与手动走查、WebView 的真实网页加载与渲染、**动效流畅度与帧率**、**触摸命中率**、滚动时确实不出声不振动。

本轮修的 20 个问题里，第 12–20 项（写盘频率、对象复用、主线程 I/O、接线缺失）是**代码层面可判定的**；但第 1–11 项改善的是"每帧要做多少工作"和"手指能不能点中"，这类改动**只能靠设备上的帧率与命中实测来确认收益**，我这边没有设备，所以只能说：**成因是确定的（代码就在那里），修复方向是标准的 Compose 做法，但实际提升了多少帧、命中率变成多少，本轮没有测量**。本机既没有模拟器也没有连接设备，这些**一律没有执行**，[`docs/TESTING.md`](docs/TESTING.md) 第 4 节逐条列出了原因与建议的补充验证清单。

---

## 10. 第四轮（1.02）：浏览模式 + 浏览器兼容性 + 设置即时反馈

这一轮对应四个真机反馈。下面每一节都写清楚**根因是什么、为什么原来的做法必然无效**，因为"加个颜色和动画让它看起来有反应"正是本任务明令禁止的做法。

### 10.1 设置页的背景与按钮质感点了没反应

**根因是结构性的。** 按钮质感原先在 `ui/components/Surfaces.kt` 里被**三份各自独立的 `when (theme.buttonTexture)`** 分别翻译成填充色（`LumoIconButton` 一份、`LumoPrimaryButton` 两份），而每一份只覆盖当时存在的三个枚举值。于是：

- 同一个质感在不同按钮上翻译出的**语义并不一致**；
- 自订颜色、圆角、阴影、边缘高光**根本没有通道**——枚举加得再多，也只有 `container` 一个值会变；
- 背景样式只有三档，且没有任何一项能改变"背景到底长什么样"以外的东西（亮度、透明度、光效都无从设置）。

**改法是把"质感"从三个 `when` 收敛成一个类型。** 新增 `@Immutable data class LumoButtonStyle(texture, brand, fill, fillStrong, fillQuiet, content, contentStrong, contentQuiet, border, quietBorder, shadowElevation, highlightTop, specular, pressScale, pressAnimated)`，由单一 `@Composable fun lumoButtonStyle(): LumoButtonStyle` 从主题与偏好解析；四个共用控件（图标按钮 / 主按钮 / 文字按钮 / chip）全部改走 `.lumoButtonSurface(style, shape, fill, border, enabled)`。七个质感各自有一行独立的 fill / border / shadow / highlight 定义：

| 质感 | fill | border | 阴影 | 边缘高光 | 斜向反光 |
| --- | --- | --- | --- | --- | --- |
| Solid 实心 | `brand` | – | 0 | ✗ | ✗ |
| Soft 柔和立体 | `brand` 16%/14% 叠 surfaceContainer | – | 跟随设置 | ✓ | ✗ |
| Glass 玻璃 | surfaceContainer 45% 叠玻璃底 | highlight 16% | 设置值 ÷ 2 | ✓ | ✓ |
| Flat 极简扁平 | surfaceContainerHighest 72% | – | **0**（扁平就该没有阴影） | ✗ | ✗ |
| Frosted 磨砂玻璃 | surfaceContainer 62% 叠玻璃底 | highlight 22% | 跟随设置 | ✓ | ✓ |
| Floating 半透明悬浮 | surfaceContainerHigh 88% | highlight 18% | **设置值 × 2** | ✓ | ✗ |
| Highlight 细腻边缘高光 | surfaceContainerHighest 85% | highlight 30% | 设置值 ÷ 2 | ✓ | ✓ |

内容色一律走 `contrastContent(fill, brand, fallback)`（WCAG 对比度 ≥ 3:1，实测不足则退回 `readableOn(fill)`）——这就是为什么"自订按钮颜色"不会把文字变成看不见的白底白字。

**文字按钮与 chip 也被接进来了**，理由是：不接的话，质感设置会**静默跳过全 App 每一个"取消 / 撤销 / 对话框"按钮**；而 chip 的静息填充就是按质感的填充，所以改质感能在**改它的那一行设置项当場**看见效果——这正是质感选项原本缺的即时反馈。

背景侧新增五个可调项（自订底色、亮度 0.4–1.6、透明度、光效开关、六种样式）与 `LumoBackdropPreview`。**亮度用 RGB 标量乘而不是 alpha 混合**：混 alpha 会让半透明主题**露出视窗后面的东西**，把亮度调低反而看到桌面。预览与 App 背景**共用 `LumoBackdrop` 同一份实作**（会走样的预览比没有预览更糟），但预览刻意使用**冻结相位的静态极光**——在滚动清单里跑六个无限动画正是本档要避免的成本。

### 10.2 浏览器兼容性

新增 `browser/BrowserConfigManager.kt`，刻意拆成两半：

- `planFor(config: ResolvedSiteConfig, defaultUserAgent: String, darkTheme: Boolean): BrowserSettingsPlan` —— **纯函数，不含任何 Android 型别**，所以"哪个模式打开哪个开关"是 JVM 单元测试而不是注解；
- `applySettings(webView, plan, defaultUserAgent)` —— 只做机械落盘，不含任何判断，因此没有测试需要触及的东西。

原先的 `configureSettings()` 只在 WebView 构造子里跑一次，这使得**任何按网址的设置都不可能生效**（等 App 知道这是哪个网站时，WebView 早就建好了），也让"套用新模式"只能靠销毁重建 WebView——正是任务里说的内存抖动。

补齐的能力：`setSupportMultipleWindows`（不开的话 `onCreateWindow` **根本不会被调用**，`target="_blank"` 与 `window.open` 会被静默丢弃，用户看到的是"点了没反应"）、`onShowFileChooser`（`FileChooserParams.parseResult`，**取消也一定回调**，否则页面的 file input 会永远卡住）、`onPermissionRequest`（镜头／麦克风／定位**一律拒绝**）、`setDownloadListener`、渲染进程崩溃恢复、网页诊断面板。**`onRetryRequested` 与 `onExternalRequested` 此前从未被赋值**，所以网页内错误覆盖层的按钮是死的——现在都接上了。

### 10.3 添加网址页遮住系统状态栏

根因**不是内边距**：`EditorScreen` 的 `statusBarsPadding()` 从第一版起就是对的，而且在 `verticalScroll` 之前，滚动视口已经内缩。

真正的原因在 `app/src/main/res/values/themes.xml:9`：`android:windowLightStatusBar` **写死为 `false`**（永远浅色图标），而 `MainActivity` 的 `enableEdgeToEdge()` 不带参数时，图标明暗跟随**系统的深浅色**而不是 **App 主题**。于是这两种常见组合都会得到看不见的图标：

- App 浅色主题 + 白色状态栏图标；或
- 系统浅色 + App 深色主题。

图标看不见，用户看到的就是"顶部布局坏了"。修法是让 App 解析后的 `isDark` 驱动 `WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars`，并让非主屏页面也挂上 `LumoBackground`，使状态栏区域显示的是主题背景而不是一块突兀的色带。

### 10.4 四种浏览模式 + 每网址独立设置

新增 `BrowserMode` 枚举与 `data/BrowserConfig.kt`：

| 模式 | 桌面 UA | 宽视口 | 总览模式 | 宽松混合内容 | 绕过缓存 | 钉住文字缩放 |
| --- | --- | --- | --- | --- | --- | --- |
| `Auto` 自动 | ✗ | ✓ | ✗ | ✗ | ✗ | – |
| `Mobile` 手机版 | ✗ | ✓ | ✗ | ✗ | ✗ | – |
| `Desktop` 桌面版 | **✓** | ✓ | ✓ | ✗ | ✗ | – |
| `Compat` 兼容 | ✗ | ✓ | ✓ | **✓** | **✓** | **100%** |

**`Compat` 与 `Mobile` 的差异是被测试钉住的**（`BrowserModeTest.everyModeHasADistinctProfile` / `compatIsNotAnAliasForMobile`）：一个"能选、能存、能翻译成另一个枚举常量，但渲染结果完全一样"的选项就是在骗人，而这种漂移光看下拉菜单永远看不出来。

持久化走 `storageName`（`"auto"` / `"mobile"` / `"desktop"` / `"compat"`）而非 `enum.name`，所以**重命名常量不会静默作废数据库里已有的值**；`fromStorage` 对无法识别的值（含 `"Desktop"` 这种 enum 名）一律回落 `Auto`。

Room `version` **1 → 2**，配 `MIGRATION_1_2`（逐列 `ALTER TABLE ADD COLUMN` + 默认值）。**刻意不提供 `fallbackToDestructiveMigration()`**——那会在用户看不懂的时机静默清空收藏，与"不删数据库结构、不清空数据"的硬约束直接冲突。迁移只加列，不改名、不删除，覆盖安装不丢任何收藏。

自动模式是一个**有界**的升级阶梯：`AUTO_LADDER = [Mobile, Compat, Desktop]`，`classifyFailure(kind, httpStatus, documentIsEmpty)` 只有三种判决（`Keep` / `Switch` / `NotConfigurable`）。**HTTP 4xx/5xx 与证书失败一律 `NotConfigurable`**——服务器说 404，换成桌面 UA 不会变成 200；证书被拒时切换 UA 更是在教用户绕过安全警告。`AutoModeState` 在阶梯尽头置 `exhausted` 并**闩锁**，之后任何判决都是空操作，所以永远不会回环。`AutoModeTest` 里有一条用例连喂 50 次 `Switch` 来证明这一点。

### 10.5 这一轮由测试抓出的两个真实缺陷

写 `BrowserConfigManagerTest` 与 `BrowserModeTest` 时抓到两个**生产代码**缺陷，都不是测试写错：

1. **`WebViewFeature` 宣称支持却抛异常 → 整个浏览器页崩掉。** `androidx.webkit` 的 `WebViewFeature.isFeatureSupported(ALGORITHMIC_DARKENING)` 回 true，紧接着 `WebSettingsCompat.setAlgorithmicDarkeningAllowed(...)` 抛 `UnsupportedOperationException`。而它跑在 `AndroidView` 的 `factory` 里，所以**页面连第一次组合都活不过去**。部分 OEM 的 WebView 与单元测试用的 Robolectric stub 都会这样。现在所有能力查询都过 `featureGated(feature) { ... }`：保留能力查询，另外 catch `RuntimeException`——**刻意不 catch `Throwable`**，`OutOfMemoryError` 是真实状况，不能吞。这是继第一轮 `ProvideLumoExtras` 之后**第二个"装得上、打得开，但一进某个画面就炸"的缺陷**。
2. **每网址"请求桌面版"开关在非桌面版模式下是空操作。** `planFor(...)` 多写了一个 `&& profile.desktopUserAgent`，而 `SiteConfig.resolve()` 早已把模式的含意并进 `desktopSiteEnabled`。于是用户在编辑器里打开开关、保存、重开，发出去的仍然是手机 UA，**诊断面板还报告 `desktop-ua`**。现在只判断 `config.desktopSiteEnabled`：桌面版模式经由 `resolve()` 的合并照旧拿到桌面 UA，其余模式下用户显式打开时也真的会发桌面 UA——**显式的每网址选择优先于模式的默认值**。三条回归用例锁住，含一条"默认仍为关"的反向保护。

---

## 11. 第四轮（1.02）实际验证了什么

下面每一行都来自本机的真实运行输出。

| 验证项 | 怎么验证的 | 结果 |
| --- | --- | --- |
| 冷构建（清空 `build/`，禁用构建缓存） | `clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon --no-build-cache` | ✅ `BUILD SUCCESSFUL in 4m 15s`，**Kotlin 编译 0 error、0 warning** |
| 单元测试总量 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐类求和 | ✅ **223 用例 · 0 失败 · 0 错误 · 0 跳过**（上一版 147，本轮 +76） |
| 应用能否启动（回归） | `StartupSmokeTest`：真的创建 `MainActivity`、真的跑 Compose 组合 | ✅ **7 / 7 通过** |
| 核心流程能否走通（回归） | `AppFlowSmokeTest`：点真实按钮，断言 Room / DataStore 里的真实数据 | ✅ **11 / 11 通过**（含三个曾因 `WebSettingsCompat` 崩溃的浏览器用例） |
| 四种模式是否真的不同 | `BrowserModeTest`（18 例） | ✅ 四个 profile 互不相同；`Auto` 解析到 `Mobile` 的 profile；`storageName` 稳定；未知值回落 |
| AUTO 是否真的有界 | `AutoModeTest`（28 例） | ✅ 连喂 50 次 `Switch` 后 `exhausted == true` 且 `attempt` 停在阶梯末端；闩锁后所有判决皆空操作 |
| 浏览器配置是否守住了安全底线 | `BrowserConfigManagerTest`（30 例） | ✅ `allowFileAccess` / `allowContentAccess` 四种模式全为 false、`javaScriptCanOpenWindowsAutomatically` false、`mediaPlaybackRequiresUserGesture` true、`safeBrowsingEnabled` true |
| 交付的源码包是否自足 | 把 `dist/LumoBrowser-v2-source.zip` 解压到**空目录** `D:\LumoBrowser-v2-zip-verify`、只补一个 `local.properties`，重跑冷构建 | ✅ `BUILD SUCCESSFUL in 5m 29s`、零 error 零 warning、**223 用例全绿**、产出的 APK 与工作树构建的**字节数完全相同**（18,298,055） |
| APK 是不是一个真实可安装的包 | `aapt2 dump badging` 读清单 | ✅ 包名 `com.lumobrowser.app.debug`、`versionCode='3'` / `versionName='1.02-debug'`、可启动 Activity `com.lumobrowser.app.MainActivity`、`minSdk 26` / `targetSdk 34`、**三个权限**、四种 ABI |
| APK 的签名是否有效 | `apksigner verify --verbose --print-certs` | ✅ v2 方案通过（v1/v3/v3.1/v4 均 false），签名者 `CN=Android Debug`（**Debug 签名，不是发布签名**） |
| 交付物哈希是否与实际文件一致 | `Get-FileHash -Algorithm SHA256` 重新计算并与 [`dist/SHA256SUMS.txt`](dist/SHA256SUMS.txt) 比对 | ✅ 一致 |

**没有验证的（不要当成已验证）**：`connectedDebugAndroidTest`（本机无模拟器、无真机）、真机安装与手动走查、WebView 的真实网页加载与渲染、**动效流畅度与帧率**、**触摸命中率**、滚动时确实不出声不振动、四种浏览模式在真实网站上的实际效果差异。

这一轮新增的 76 个用例全部是**纯 JVM 逻辑测试**（模式解析、AUTO 状态机、配置计划）。它们能证明"COMPAT 与 MOBILE 的配置确实不同" "AUTO 不会无限升级" "安全开关在四种模式下都是关的"——**但它们证明不了某个具体网站在兼容模式下会变好**。那需要一台设备加真实的失败网站，本机没有。`BrowserMode` 的四档差异是**设计并测试过**的，不是**实测过的**。

---

## 12. 第五轮：状态栏遮挡、性能开关、网络与 VPN 辅助、数据库 v3

版本号**不变**（仍是 1.02 / `versionCode 3`）——这一轮修的是同一个版本里的缺陷，不是新特性。逐条细节、行号与取舍理由见 [`docs/RELEASE-1.02.md`](docs/RELEASE-1.02.md)。

### 12.1 状态栏遮挡：两层根因，缺一层都修不好

真机反馈"顶部布局坏了 / 打不开"，实际是**两种独立的缺陷叠在一起**，所以单独修任何一层都看不出来：

1. **图标与背景同色。** `app/src/main/res/values/themes.xml:9` 把 `android:windowLightStatusBar` **写死为 `false`**（永远浅色图标），而 `MainActivity` 调用的 `enableEdgeToEdge()` 不带参数时，图标明暗跟的是**系统的深浅色**，不是 **App 主题**。于是"App 浅色主题 + 白色图标"和"系统浅色 + App 深色主题"这两种常见组合都会得到**看不见的图标**。修法是让 App 解析后的 `isDark` 驱动 `WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars`。
2. **画面根本没消费 inset。** 首页根 `Box` 只有 `fillMaxSize()`，没有任何 inset 修饰符，所以内容从屏幕最顶端开始画，**第一排收藏卡片直接被状态栏压住**。原先各屏各写各的 `statusBarsPadding()` / `navigationBarsPadding()` / `imePadding()`，漏一处就是一处遮挡。

现在全应用只有**一个** inset 来源 `app/src/main/java/com/lumobrowser/app/ui/components/Insets.kt`：`LumoInsets`（`safeDrawing`）、`LumoTopInsets`、`LumoBottomInsets`、`LumoSystemBars`（**只给浏览器**，因为网页要画到状态栏后面）与五个 `Modifier` 扩展（`lumoScreenInsets` / `lumoTopInsets` / `lumoBottomInsets` / `lumoVerticalInsets` / `lumoStatusBarInset`）。四个屏幕全部接入，全库已无 `statusBarsPadding` / `navigationBarsPadding` / `imePadding` 的调用。

这里有一个**看起来对、实际会把整条背景铺满状态栏的坑**：加载进度条是一条 2 dp 的线，若直接对它套 `lumoTopInsets()`，它自己的盒高会变成「2 dp ＋ 状态栏高度」，于是那 2 dp 的背景色被拉伸成覆盖整个状态栏区域色带。正确做法是把 2 dp 的线**包在一层套了 inset 的 `Box` 里**。

### 12.2 "性能"开关此前是假开关

设置页有一个"简化效果"开关，**存下来了、也持久化了，但没有任何一行代码读它**——点了没有任何变化。这类"能选、能存、能进数据库，但完全不影响渲染"的开关，和空按钮是同一类问题。

现在它是真的（`LumoThemeState.simplifiedEffects`）：

| 开关 | 它到底关什么 | 是否无障碍相关 |
| --- | --- | --- |
| **简化效果**（性能） | 持续成本：动态背景、极光光斑、每个 surface 的**离屏阴影模糊** | ✗ 这是"耗电/掉帧"开关 |
| **减少动态效果**（跟随系统） | 系统级无障碍设置，用户可能在系统里为晕动症打开 | ✓ |
| **动画强度 / 极简预设** | 口味偏好，用户就是不喜欢动画 | ✗ |

三者语义不同，所以**互不替代**：打开"简化效果"不会去改你的主题、配色、圆角或动画设置。三者的差异由 `SimplifiedEffectsTest`（10 例）逐条钉住。

定位到两处真正的**持续**成本（全库 grep 实测）：

- `rememberInfiniteTransition` **全库只有一处**——`app/src/main/java/com/lumobrowser/app/ui/components/LumoBackground.kt:184` 的极光漂移（`"lumo-aurora"`）；关掉动态背景后它整个不再进入组合。
- `Modifier.shadow(` **两处**——`Surfaces.kt:200`（卡片、面板、栏）与 `:482-483`（按钮）。**阴影是每个 surface 一次离屏模糊，首页每一张卡片都有一个**，这正是滚动卡顿的来源。所以阴影规则被提取成纯函数 `lumoShadowElevation(style, elevated, simplified): Dp`——它原本内联在 `@Composable` 里，**唯一能检查的方法就是渲染整个 App 用眼睛看，这正是它能一直是死开关的原因**；提取后 `SurfaceElevationTest`（4 例）可以直接断言。

### 12.3 网络状态与 VPN 辅助：解释层，不含隧道

应用**永远不会自己建立 VPN 隧道**，也**没有新增任何权限**（仍是 INTERNET / ACCESS_NETWORK_STATE / VIBRATE 三条）。这一层做的是"把失败说清楚，并在用户已经配好 VPN 时能一键配合"：

- `PageFailure` 把一次失败分成可解释的类别，`assistOfferFor(...)` 在**恰好该解释**的时候给出建议（连接类问题才提 VPN；证书问题**从不**建议换 UA 或绕过）；
- 浏览器「更多」菜单里新增**网络面板**：当前网络、VPN 状态、失败原因、每网址的联网偏好、有界重试；
- **每网址偏好有两个入口**（第六轮补齐）：浏览器网络面板里可以改，收藏的**编辑器**里也可以改。两处写的是同一个字段（`bookmarks.networkAssist`），所以不会出现"面板里改了、编辑器里看着还是旧值"的分叉。默认是「跟随全局设置」，所以**升级不会改变任何已有收藏的行为**——它只是不再有意见而已。
- 设置页新增「网络与 VPN」区：全局模式（自动/手动/静默）＋ 10 项开关，其中"管理 VPN"与"打开网络设置"直接跳到 Android 自己的系统页面（某些定制 ROM 没有该页面，所以这条路包了 `ActivityNotFoundException`——**缺一个系统画面不能让设置页崩掉**）；
- **有界重试**：重试次数有上限，用尽后按钮禁用并说明原因。唯一不计数的一次是"连接 VPN 后重试"——**用户明确要求，且它等的条件（VPN 起来了）是新信息，不是把同一个请求重发一遍**。两种结果都会清掉等待旗标，所以不可能变成重试循环。

**一个刻意的设计选择**：失败类别**只在失败发生的那一刻分类一次**，不随实时网络状态重算。页面失败之后才发生的断网**不是那次失败的原因**，跟着实时状态走会用当前连不上的事实去给一次早已过去的失败贴标签，然后永远贴错。

### 12.4 数据库 v1 → v2 → v3

新增 `networkAssist` 列。两个迁移**都必须注册**：一台装了 1.00 的设备升上来，磁盘上的库还是 version 1，Room 要走 **1 → 2 → 3** 两步——只留 `MIGRATION_2_3` 会让这类设备抛 `A migration from 1 to 3 was required but not found`，**不是升级失败，是启动就崩**。

`DatabaseMigrationTest`（11 例）在**真 SQLite**（`@SQLiteMode(NATIVE)`，不是 Robolectric 的内存假实现，否则 DDL 语法根本没被验证过）上走完 v1→v2→v3，并用**手写 DDL 造出真数据库文件**。它覆盖了三个"应用不会报错但确实坏了"的陷阱：不注册旧迁移、默认值漏写引号、以及**重建表的迁移会连索引一起丢掉而应用毫无提示**（三个索引从 schema 1 起就存在，两个迁移都没提到它们——这正是要测的）。

写这批测试时踩到的两个坑本身也值得记下来：`Room.databaseBuilder(...).build()` **只构造包装器**，不触碰数据库的迁移测试会**假通过**；`BookmarkDao` 返回的是**实体**（字段是存储字符串 `"auto"`/`"global"`，不是枚举），而 `assertEquals` 的签名是 `(Any?, Any?)`，所以**拿枚举比字符串编译期零警告、运行期必错**。

---

## 13. 交付的 APK 是怎么来的（源码包冷构建）

`dist/LumoBrowser-1.02-debug.apk` **不是**从开发目录里增量构建出来的包，而是：

```powershell
Expand-Archive dist\LumoBrowser-1.02-source.zip -DestinationPath D:\LumoBrowser-102-coldbuild3
# 只补一个 local.properties（写 SDK 路径；机器相关，故意不打包）
Set-Content D:\LumoBrowser-102-coldbuild3\LumoBrowser\local.properties "sdk.dir=D\:\\AndroidDev\\sdk"

Set-Location D:\LumoBrowser-102-coldbuild3\LumoBrowser
gradle clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest `
       --no-daemon --console=plain --no-build-cache
```

实测：`exit=0`、`BUILD SUCCESSFUL in 17m 4s`（墙钟 1,025.3 秒）、零 `e:`、**18 个测试类 263 例 0 失败 0 跳过**，产出 `app-debug.apk` = **18,377,227 B**。这个文件被原样复制进 `dist/`，字节数与哈希都没改。

> 冷构建的墙钟时间在这台机器上波动很大：同一批源码、同一条命令，之前一次（248 例、尚未给测试 JVM 设堆）跑完用了 **4m 6s**。差的不是工作量，是当时构建机还有多少空闲内存——`gradle.properties` 里 Gradle daemon 自己就要 2.5 GB，Kotlin daemon 再来一份，测试 worker 现在还要 2 GB。**下面第 13.1 节解释了这 2 GB 是怎么来的。**

所以能说的最强命题是：**交付目录里的 APK，就是交付的源码包建出来的那一个**——拿这份 zip 的人不需要这台机器上的任何隐藏状态（构建缓存、`local.properties`、已下载产物）。`--no-build-cache` 是关键的一条：开着缓存，这个验证就退化成「证明缓存里有东西」。

### 13.1 这次冷构建先失败过一次，失败的原因值得单独说

第一次冷构建（解压到另一个空目录）以 **`GRADLE_EXIT=1`** 结束：

```
com.lumobrowser.app.LanguageSwitchLaunchTest > launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate FAILED
    java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49
263 tests completed, 1 failed
```

**挂掉的不是被测代码。** 单元测试跑在**独立于 Gradle daemon 的另一个 JVM** 里，`org.gradle.jvmargs` 管不到它，Gradle 给它的默认堆是 **512 MB**。`LanguageSwitchLaunchTest` 会在 Robolectric 里把 Activity 和整棵 Compose 树真的撑起来，单跑就要 136 秒——512 MB 够不够，取决于那一刻构建机还在并行做什么。源码树里跑同一批测试时它过了，冷构建时并行任务更多，它就死了。

修的是**资源不足的 worker，不是症状**：`app/build.gradle.kts` 的 `testOptions.unitTests.all { }` 里显式设了 `it.maxHeapSize = "2g"`。**没有削弱这个断言，也没有把它排除出测试集。**

这条的教训比修法本身重要：**一个会因为构建机负载而随机变红的测试集，它证明的东西比报告上写的少。** 报告写「263 例 0 失败」，但那份绿如果是「当时机器比较闲」的绿，它就没有看上去那么值钱。

完整实测表、与源码树那次构建的**逐条目差异追溯**（同为 491 条目，只有 `classes7.dex` 差 428 B，差额来自 `BuildConfig.BUILD_TIME`），以及"文档改动没有破坏这次验证"的**全树逐文件哈希比对**（排除 `.md` 后差异为 **0** 个文件），都记在 [`docs/RELEASE-1.02.md`](docs/RELEASE-1.02.md) 第八节。

**这次验证没有覆盖什么**：没有在设备上安装过（本机 `adb devices` 为空，无真机/模拟器/AVD/系统映像），`app-debug-androidTest.apk` 只编译未运行，未验证 release 变体（无 keystore）。

---

## 14. 「装上了却打不开」：逐条排除记录

这条来自真实反馈（用户的手机上「安装了，打不开」）。**本机没有任何设备，所以我没有复现过、也没有验证过任何修复**——这一节能提供的是「已经排除了哪些成因」和「怎么把现场变成一段可读的文字」，而不是「已经修好了」。

> **1.07 的回答。** 表格里的成因在用户那台 Android 14/15 手机上**一条都不成立**，而 1.07 也没能找出新的根因（排查过程见文首 1.07 公告第一节）。所以这一版换了一条路：不再试图猜中那个崩溃，而是让**崩溃自己说话**——异常被记录下来，**下一次启动先显示报告**（含内存页大小、WebView 提供方版本、是否 debuggable），并给出「安全模式启动」与「正常启动」两个入口，另外提供一个**不可调试的 Release 包**来排除 ROM 拦截。三条入口见 [第 2.2 节](#22-如果-107-还是打不开)，完整设计见 [`docs/CRASH_RECOVERY.md`](docs/CRASH_RECOVERY.md)。

取证命令（`adb logcat -b crash` 那几步、以及只有手机时怎么办）在 [第 2.1 节](#21-如果装上了却打不开)，不在这里重复。下面是**逐条排查过的成因**，每一条都写了怎么查的——这些是查过的，不是在推测：

| 「装得上但打不开」的常见成因 | 本包的情况 | 怎么查的 |
| --- | --- | --- |
| 桌面图标指向的 Activity 没有导出 | ✅ 不成立：`MainActivity` 有 `android:exported="true"` + `MAIN`/`LAUNCHER` | `aapt2 dump badging` 能列出 `launchable-activity` |
| Android 7 及以下装不上 | ✅ 不成立（这是「装不上」，不是「打不开」）：`minSdk 26` | `aapt2 dump badging` |
| 缺少本机 CPU 架构的原生库 | ✅ 不成立：四种 ABI 全在（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`），且四个 `.so` 的数据偏移都是 **4096 字节对齐**（`extractNativeLibs=false` 要求如此，没对齐会 `UnsatisfiedLinkError`） | 直接解析 APK 的 `lib/` 条目与 zip 本地标头 |
| 16 KB 内存页设备（Android 15+）加载不了旧对齐的 `.so` | ✅ 不成立：四个 `.so` 的 ELF `PT_LOAD` 段 `p_align` **全是 `16384`** | 解析 `.so` 的 ELF 程序头 |
| 签名无效被系统拒绝 | ✅ 不成立：`apksigner verify` 退出码 0，v2 方案通过 | `apksigner verify --verbose --print-certs` |
| 启动时 `CompositionLocal` 没提供 | ✅ 第一轮修过，修复在出货码里，且新版结构上不可能再漏 | `docs/TESTING.md` §3.7 |
| 系统 WebView 被禁用/缺失导致启动崩 | ✅ 不成立：**启动路径完全不碰 WebView**。全工程只有一处 `WebView(...)` 构造（`BrowserScreen.kt:419`，在 `AndroidView` 工厂里），只有一处读 WebView 版本（`BrowserScreen.kt:293` → `webViewVersion()`）——两者都在浏览器屏，首页只用到 Compose + Room + DataStore | 全库 grep `WebView(` / `WebViewCompat` |
| 跨版本升级时数据库迁移缺失 → 启动崩 | ✅ 不成立：`MIGRATION_1_2` 与 `MIGRATION_2_3` **都**注册了，11 例真 SQLite 测试覆盖 | `DatabaseMigrationTest` |
| 旧版本留下的应用数据与新 schema 冲突 | ⚠️ **只有这一条要你动手** —— 见 14.1 | — |

**这些只排除了已知的崩溃路径，不等于证明它一定能打开。** 剩下的要靠那台手机上的日志。

### 14.1 最省事的一步：先卸载再装

如果手机上装过更早的包，**先卸载，再装这个 APK**。

理由不是「玄学」：调试包名是 `com.lumobrowser.app.debug`，和正式包名共存时会留下两份互不相识的应用数据；而如果之前那个包**从未成功启动过**，卸载不会丢任何东西——它根本没来得及写进去。这一步能一次性排除「旧数据 + 新 schema」的全部组合。

### 14.2 抓不到崩溃栈时怎么判断

- **`adb logcat -b crash` 和 `AndroidRuntime:E` 都为空** → 不是崩溃，是「起来了但什么都不显示」。这时改用下面这条，它会告诉你 Activity 到底有没有被创建：
  ```powershell
  adb shell am start -n com.lumobrowser.app.debug/com.lumobrowser.app.MainActivity
  ```
  如果它回 `Error type 3` 说明组件找不到；如果回 `Starting: Intent {...}` 而屏幕仍然没反应，那就是渲染/主题层的问题，而不是启动流程。
- **`adb devices -l` 里没有设备** → 是数据线/驱动/授权问题，不是应用问题（状态是 `unauthorized` 时要在手机上点「允许 USB 调试」）。
- **LumoBrowser 自己不写 logcat、不上传任何东西**（见第 12.3 节），所以日志里看到的就是系统自己记的那一份。

### 14.3 这里查不到的

- **无法验证上面任何一条修复在你的手机上是否生效**——本机没有设备。
- **无法给出帧率、触摸命中率的实测数字**（第 12.2 节说明过）：那需要同一台设备上「关掉简化效果 / 打开简化效果」各跑一次 `adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset`。
- **没有 release 签名包**：`dist/` 里的是 Debug 签名包，只适合自己装来测。

---

## 15. v2.2（第六轮）：VPN 集成、音效、按钮视觉、启动动画

这一版是四个专项一起做。每一节的写法是**先写"做不到什么"，再写"做到了什么"**——因为这个版本里最容易出的错，不是功能没做完，而是**在一个应用没有能力回答的问题上给出一个听起来很确定的答案**。

### 15.1 VPN：先回答"能不能真实建立连接"

**不能。** 这不是"暂时还没做"，而是一个可以从代码上断言的事实：`TunnelBackends` 注册表是空的，测试 `thisBuildShipsNoProtocolBackendAndSaysSo` 钉住了这一条——**将来有人注册一个协议，这个测试就会变红，而那正是应该发生的事**。

出到一个协议，就等于出一个**对真实服务器跑过的**实现。没有服务器、没有认证流程、没有封包泵、没有 DNS 与路由策略，写出来的只会是一个在用户手机上静默失败的类，而它失败的样子和"应用坏了"一模一样。规格明确禁止"仅创建一个 `VpnService` 类就声称 VPN 可用"，这就是那条禁令的落点。

**上一版真正的结构性问题**（这一轮的主要收益就是把它拆掉）：

| 问题 | 后果 |
| --- | --- |
| `Connected` 只要设备上有 VPN 承载默认路由就返回 | 那是关于**设备**的观测，被当成了关于**本应用**的结论——应用从没建立过任何东西，却报"已连接" |
| `Connecting` / `Failed` 由一个**全程零写入者**的旗标驱动 | 六态里有两态**永远到不了**；`waitingForVpn` 唯一的逃逸路径是那个不可达的 `Failed`，**点下去等于掉进真空** |
| `checkNetworkBeforeOpening` / `rememberPerSiteNetworkAssist` | 两个**死开关**：有存储键、有 UI、有读写，但从未出现在任何条件式里 |
| "有 VPN" 是用户在设置页自己勾的 | 这个勾决定面板建议"连接 VPN"还是"去配置 VPN"；**没有任何 profile 被探测过** |

现在状态由 `resolveVpnReport(...)` 从三个独立来源合成，并新增第七态：

| 状态 | 含义 |
| --- | --- |
| `NotConfigured` | 这个构建没有协议实现，或没有可用的端点 |
| `Disconnected` | 有后端、有端点，但隧道是空闲的 |
| `Connecting` | 已经发起，还没结果 |
| `Connected` | **本应用建立的隧道确实起来了**——唯一够格说"已连接"的状态 |
| `DetectedNetwork` | **设备上有 VPN 网络**。这是上一版被报成"已连接"的东西，两者不是同一个主张 |
| `Failed` | 有具体原因（无后端 / 未配置 / 授权被拒 / 后端自己的失败文字） |
| `Unknown` | 网络读数本身不可知——**不是"离线"** |

`DetectedNetwork` 是独立枚举值而不是一个布尔旗标，理由很具体：**布尔可以和"已连接"同时为真，枚举不行。**

**方案 A（对接已安装的 VPN 应用）做到哪一步**：发现（`queryIntentServices(Intent("android.net.VpnService"))`——所有 `VpnService` 都必须在 manifest 声明这个 action，所以能列出**真实已安装**的 VPN 应用，且不需要 `QUERY_ALL_PACKAGES`）和**打开**（`getLaunchIntentForPackage`）。**"控制"刻意未实现**，`VpnAppControlApis` 是一张空表：查证第三方控制接口时没拿到可引用的规格原文，而凭记忆捏一条 action 失败时，在用户眼里是"LumoBrowser 坏了"而不是"这个应用不支持"。规格 2.2 明文禁止猜测包名/接口/内部实现。

**自动连接与重试是有界的**：`VPN_WAIT_TIMEOUT_MS = 30_000L`（旧代码**完全没有逾时**）、`MAX_ATTEMPTS = 2`、`COOLDOWN_MS = 15_000L`，"连接后重试"那颗按钮要同时满足三个条件（用户允许自动连接 + 这个构建真有后端 + 真有可拨的端点）才出现——缺任何一条，它就成了应用守不住的承诺。

### 15.2 音效：19 种事件 × 4 种风格，全部程序化生成

素材是**自己合成的**：`tools/generate_sfx.py` 用纯标准库产出 76 个 WAV（19 事件 × 4 风格），22050 Hz / 16-bit / 单声道，无第三方素材、无网络下载。**种子用 FNV-1a 而不是 Python 的 `hash()`**——后者被 `PYTHONHASHSEED` 加盐，"重新生成"会得到内容不同、文件名相同的文件，素材库就有两个互相矛盾的版本而且没有任何东西会报警。

播放引擎改用 `SoundPool`：启动后异步解码、未就绪时**静音而不是报错**、同 cue 去抖、`setMaxStreams(4)`、**刻意不请求 audio focus**（30–450 ms 的提示音去抢焦点，会把用户的音乐压下去，比它修的问题更糟）。

**修掉的最大缺口**：19 种事件此前**只有 12 种真的会响**——`LoadStart` / `LoadSuccess` / `LoadFailure` / `VpnConnecting` / `VpnConnected` / `VpnFailed` / `LaunchEnd` / `Navigate` / `DialogOpen` / `DialogClose` / `Press` / `Release` 全都没有调用点。现在全部接上。两处刻意的例外：

- **`Press` 响、`Release` 只在按下被取消时响**。一次提交的点击会在几十毫秒后产生自己的结果音，再补一个"松开"音就是一次操作三声——那正是规格 3.5 禁止的堆叠。而被取消的按下恰恰是唯一"不会有好结果音"的时刻，也正是用户需要听到"没发生"的时刻。
- **`SettingsSaved` 是唯一没有调用点的事件，且决定不补**。本应用设置**即时生效**、根本没有"保存"按钮；每个控件的提交都已经产生更具体的音（开关是 `Toggle`、动作列是 `Click`、恢复默认是 `Confirm`），再叠一个就是一次操作两声。素材和 cue 都在（设置页可以试听），缺的只是一个不重复的表达时机。**这一条列在 [`docs/AUDIO_ASSETS.md`](docs/AUDIO_ASSETS.md) 与 [`docs/TESTING.md`](docs/TESTING.md) 的未实现清单里。**

### 15.3 按钮：根因逐条定位，没有"统一设 shadow = 0"

规格四明确要求**依实际源码定位原因**。全库只有两处 `Modifier.shadow`，两处的问题一模一样：

- 都用 `Color.Black.copy(alpha = 0.5f/0.6f)` 当 ambient / spot 颜色（深色主题下就是大块黑投影）；
- **都写在 `.clip(shape)` 之前**，所以组件自己的圆角裁不到阴影，阴影按矩形铺开。

另外 12 组父子元素**同时**带阴影（最严重的是 `BookmarkCard` 同一条 modifier 链上三层：拖拽 `graphicsLayer{shadowElevation=22f}` + `surfaceTreatment` + 嵌套图标按钮）。

**浅色主题的"脏边"是另一回事**：`hairline(alpha)` 在浅色下返回 `Color.Black.copy(alpha = alpha * 0.55f)`，而卡片边框传的是 `hairline(0.9f)`——**黑 @ 0.495**。这不是阴影问题，是一个颜色 token 的问题，所以也没有靠调阴影解决。

修的方式是：深色主题改用低对比柔和边界而非黑色大投影、把阴影移到 `.clip()` 之后、压平重复投影、浅色主题的 hairline 改为基于 accent 的低 alpha 描边。**没有对按钮统一设 `shadow = 0`。**

### 15.4 启动动画：它是覆盖层，不是闸门

三阶段 480 / 860 / 1000 ms（合计 2340 ms，随动效强度整体缩放）。两个结构性决定：

1. **它是覆盖层，不是闸门。** `timeline == null` 时直接渲染 `content`；否则 `content` **从第一帧起就在下面组合与布局**，动画只是盖在上面。这是针对本项目上一个真实故障设计的——**一个启动动画最坏的失败模式，就是它自己变成打不开的原因。**
2. **它不拥有任何指针输入**（没有 `clickable`、没有 `pointerInput`）。播到一半就想操作的用户，仍然能碰到他瞄准的那个控件。

**不会和系统启动窗口连播两次品牌动画**：本工程**没有接入 `androidx.core:core-splashscreen`**，启动窗口只由 `themes.xml` 的纯色 `windowBackground` 驱动。真实序列是"系统画纯色窗 → Compose 首帧到达 → 三阶段动画"。**返回主界面不重播**是免费得到的——返回是导航，不经过 `onCreate`；旋屏与语言切换则由 `savedInstanceState == null` 和 process 级的 `startupAnimationShown` 分别挡住。

### 15.5 顺带修掉的真 bug：`reduceMotion` 是死偏好

设置页那个"减少动效"开关有字段、有存储键、有读写、有 UI——**但从未传进主题构建**。`buildLumoTheme(...)` 只接收 `animationPreset` / `animationStrength` / 系统旗标，所以这个开关从加上那天起就没有任何作用。现在已接上并有回归测试。

### 15.6 这一轮由测试抓出的三个真实缺陷

VPN 集成测试**第一次运行就红了 3 例，三例全是产品代码的 bug**，没有一例是测试写错：

1. **值的分隔符变成了栏位分隔符**——`dnsServers` / `routes` 用 `joinToString(" ")` 直接接起来，条目本身没转义。一条含 `|` 的 DNS 会让 8 栏变 9 栏，整个 profile 读回成空。
2. **`decode` 吞掉坏值，与它自己的文档矛盾**——坏掉的 port 被 `?: 0` 吞掉，其余栏位照收，返回一个"半套"profile。八栏是一次 `encode` 描述一个端点，救回七栏等于发明一个用户没打过的值。
3. **两个能力问题被塌缩成一个**——`hasProfile = connectability != NoProfile`，而 `VpnConnectability` 只报告**第一个**失败的检查。于是对每一个用"没有协议实现的构建"的人，面板都会说"你还没配置端点"：**它把"我们做不到"说成了"你没做"。**

> 三个缺陷是同一个形状：**应用在一个它没有能力回答的问题上，给出了一个听起来很确定的答案。** 第一个说"你没配置"（其实记录读坏了），第二个说"配置好了"（其实只读了一半），第三个说"你没配置"（其实是应用没这功能）。
>
> 规格禁止伪造连接状态。而伪造往往不是从"假装连上了"开始的——**是从"假装知道"开始的。**

### 15.7 实际验证了什么

- **单元测试：301 例 · 0 失败 · 0 错误 · 0 跳过（21 个类）**，上一版 263。新增 `VpnIntegrationTest` 22 例、`SoundCueTaxonomyTest` 10 例、`SoundLibraryTest` 5 例。
- **APK 实际构建成功**：`BUILD SUCCESSFUL in 41s` → `dist/LumoBrowser-2.2-debug.apk`，19,154,932 字节，`versionCode=4` / `versionName=2.2-debug`；`apksigner verify` v2 方案通过（`CN=Android Debug`）；`aapt2 dump badging` 确认四种 ABI 齐全。
- **APK 内容实测**：资源表 `res/raw/` 下 76 个 `.wav`；`classes.dex` 里含 `vpn/LumoVpnService`、`vpn/VpnController`、`vpn/VpnProfileCodec`、`vpn/VpnAppDirectory`、`ui/components/StartupAnimationKt`、`feedback/SoundSynth`、`feedback/SoundLibrary`——**证明这个包确实含有本轮的四项工作**，而不只是声称含有。
- **音效素材验证 `problems: 0`**：76 档 / 668,268 字节 / 峰值 0.4315–0.7200，四种风格的泛音内容确有可量测的区分度（`hf_ratio` 均值 deep 0.137 < soft 0.244 < crisp 0.335 < glass 0.650）。
- **二十条验收标准的逐条判定**在 [`docs/TESTING.md`](docs/TESTING.md) §0B：**9 条已通过、10 条部分通过、1 条未通过（无障碍，本版明确不做）、0 条被谎报为通过**。

### 15.8 明确没有做的

| 项目 | 为什么 |
| --- | --- |
| **真实 VPN 连接** | 没有协议实现与服务器配置。写一个连不上的隧道类，比诚实地没有更糟 |
| **第三方 VPN 应用的"控制"** | 没拿到可引用的公开接口规格；猜测包名/action 违反规格 2.2 |
| ~~**无障碍服务**~~ | ~~规格允许"无法安全实现时如实说明"~~ → **1.03 已实现**，见第 16 节。当时写下的五条约束逐条照做 |
| **按钮的"载入中 / 成功 / 失败"三态视觉** | 目前只有 `enabled` / `disabled` 两态 |
| **页面切换残影、滚动位置保留、加载可取消** | 规格第六节的细节打磨项，本轮未做完 |
| **源码包冷构建二次验证** | 1.02 做过（`D:\LumoBrowser-102-coldbuild3`），**2.2 与 1.03 都没有重做**。这一条明确记在 [`docs/TESTING.md`](docs/TESTING.md) 里，没有当成做过 |
| **真机安装与启动验证** | 本机无设备、无模拟器、无 AVD。`adb devices -l` 只有表头 |

**「部分通过」的 10 条全部是同一个原因：需要一台设备。** 它们不是"大概可以"，而是"机制已实现且有测试钉住，最后一段只能由设备证实"——各自需要什么写在 [`docs/TESTING.md`](docs/TESTING.md) 第 4 节。

---

## 16. 1.03（第七轮）：无障碍辅助服务落地

这一轮只做了一件事，而且是上一轮明确记下"不做"的那一件。版本号从 `2.2` 回到 `1.03`——这个包不是 v2.2 专项任务的收尾，而是接在 1.02 之后的第三个交付版本。

### 16.1 为什么上一轮说"不做"，这一轮又说"做"

上一轮的理由是：规格 2.3 要的三个入口（打开系统设置 / 检查授权状态 / 关闭辅助功能）依赖一个**真实存在、可被授权**的服务，而当时没有服务——**"为一个不存在的服务做授权状态检查，只能检查出一个恒为 false 的值，那不是功能，是装饰。"**

那个判断现在依然成立。变的只是服务真的存在了。这也是为什么这一轮的第一件事是把服务、它的授权状态识别、它的运行时状态机**全部写完**，而不是先把设置页那三个入口画出来。

### 16.2 权限很大，行为很小

| 约束 | 值 | 为什么 |
| --- | --- | --- |
| 尝试窗口 | `WINDOW_MS = 12_000L` | 授权一旦武装就自动过期 |
| 按下次数 | `MAX_PRESSES = 1` | 一次尝试只按一次 |
| 过期检查位置 | **在包名比对之前** | 过期尝试连"这个包是不是目标"都不会去问（有测试钉住） |
| 永远禁止的目标 | 六个平台套件 + LUMO 自己 | 用户最可能顺手声明成目标的，恰恰是同意对话框上的那个「确定」 |
| 服务配置 flag | 只留 `flagReportViewIds` | 其余五项（`flagRetrieveInteractiveWindows` / `flagRequestFilterKeyEvents` / …）逐条写明为什么不要 |
| 不读输入框 | 跳过 `isEditable` 与 `EditText` | 这个功能从来不需要知道任何人打了什么字 |
| 默认状态 | `Boolean?` = `null`（从未表态） | 把"没问过"渲染成"已拒绝"是另一种不诚实 |

### 16.3 五个真实缺陷（前三个由新测试抓出，后两个由编译器抓出）

1. **`isForbiddenAssistPackage` 漏掉了自己。** 比对裸常量 `com.lumobrowser.app`，但 debug 变体的 applicationId 是 `com.lumobrowser.app.debug`——**"开发者手机上实际安装的那个变体"成了唯一漏网的包**，失败还是静默的：该抓它的检查本身才是错的。
2. **`AssistTargetCodec.unescape` 吃掉反斜杠。** 未知转义分支把 `C:\vpn` 变成 `C:vpn`，而同段注释声称"原样保留"。
3. **`isEnabledViaSecureSetting` 永远认不出相对拼法。** `ComponentName.unflattenFromString` **自己就会把前导 `.` 展开**，展开后比真正存在的类名多一段——**用户已经在 Android 设置里打开的服务被报告成关闭**。这是该文件 KDoc 写明的失败模式：false negative 让用户损失一个他明明已经打开的功能。
4. **`notePressed(attempt)` 收下参数却从不读它。** 这个参数的存在就是为了让调用方说明"我按的是哪一次尝试"，但从来没有被核对过。真出问题时失败形态是**静默且张冠李戴**的：一个属于已作废尝试的按下，会被报告成**当前**这次尝试成功了。现在核对，对不上就丢弃。
5. **`AccessibilityTextField` 的 `onSubmit` 从未被调用。** 调用方传的 lambda 能正常保存目标，`imeAction` 也正确设成了 `ImeAction.Done`——**但键盘上的"完成"键按下去什么都不会发生**，因为缺的是 `KeyboardActions(onDone = ...)`。功能没坏（下面那个独立的"添加"按钮是真的），坏的是一条**看起来通、其实断的捷径**。

> 第 4、5 两条的**发现方式**值得单独记：它们是构建日志里那两条 `Parameter 'x' is never used` 警告。它们在前几次构建中一直存在，一直被当作背景噪音。**一条总是出现、又从不影响结果的警告，会自然退化成没人读的装饰**——和这一版反复遇到的是同一种东西。修掉之后，构建日志里 Kotlin 警告数为 **0**。

### 16.4 这一版自己的事故（一并记下）

- **三份 `strings.xml` 被写坏过一次。** 为修一个裸撇号用了 `Get-Content -Raw` + `WriteAllText` 往返，而 `Get-Content -Raw` 对无 BOM 的 UTF-8 文件用了 ANSI 码页（GBK），把每个"多位元组字符紧邻 ASCII"的位置解成两个错字并吃掉后面那个 ASCII 字符。11 条字符串 + 5 条注释受损，全部从**打包在事发之前**的 `dist/LumoBrowser-2.2-source.zip` 逐条重写修复。三份文件最终都是 `open=371 close=371 names=371 suspect=0`，`values/strings.xml` 对 zip 原版 `changed vs zip=0`。**教训：永远不要用 PowerShell 的 `Get-Content -Raw` 往返这个仓库里的 UTF-8 文件，改文件一律用 Python 并明写 `encoding="utf-8"`。**
- **发布校验脚本自己误报过。** `_verify103.py` 第一次跑报"manifest 未声明无障碍服务"，而 `aapt2 dump xmltree` 证明 manifest 是对的——**是检查用 UTF-8 字节搜一个 UTF-16 的字符串池**。修法是同时搜两种编码。这个误报本身就是本版的主题：一个看起来在检查、其实永远为假的判断。

### 16.5 实际验证了什么

- **单元测试 372 例 · 0 失败 · 0 错误 · 0 跳过（25 个类）**，上一版 301 例 / 21 类。干净重建 `clean :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL in 16m 37s`，**并且构建日志里 Kotlin 警告数为 0**（前几次构建里那两条 `Parameter 'x' is never used` 就是 16.3 的第 4、5 条）。
- **APK**：`dist/LumoBrowser-1.03-debug.apk`，19,218,591 字节，SHA-256 `0b5a1fc4bab84282a95144b8413dd04f7c35447626f61445170e9a3db00b5c60`，`versionCode=5` / `versionName=1.03-debug`，`apksigner` v2 通过（`CN=Android Debug`）。
- **源码包**：`dist/LumoBrowser-1.03-source.zip`，**209 个文件**（v2.2 是 189 档；含 `docs/build-logs/` 下的 1 份索引与 7 份原始 Gradle 日志）。精确字节数与 SHA-256 不写在本文件里——本文件自己就在压缩包内，写进去会自我指涉、永远对不上；统一公布在 `dist/SHA256SUMS.txt`。
- **交付校验 `_verify103.py` → `VERDICT: OK`**：10 个 dist 文件哈希全对；源包 209 档、76 个 wav、12 个必需新档 + 8 份构建日志全在；三份 `strings.xml` 各 371 条且名称集合一致；**APK 的 `classes.dex` 里 14 个类名全部找到**（8 个 1.03 + 6 个 v2.2）；**APK manifest 确实声明了无障碍服务、要求了 `BIND_ACCESSIBILITY_SERVICE`、有 intent-filter、指向 `@xml/lumo_accessibility_service`**。

### 16.6 明确没有做的

| 项目 | 为什么 |
| --- | --- |
| **无障碍服务的真机端到端验证** | 本机无设备、无模拟器。**节点树遍历与 `performAction(ACTION_CLICK)` 的实际效果从未被实际运行验证过**——59 例测的是纯逻辑与授权状态识别。验收表里这一条按「实现完成、真机未验」记，**不记全绿** |
| **按钮的"载入中 / 成功 / 失败"三态视觉** | 2.2 遗留的第 ⑦ 项，本版**没有推进** |
| **页面切换残影/闪白、滚动位置保留、加载可取消可重试、无障碍描述与字体缩放** | 2.2 遗留的第 ⑨ 项，本版**没有推进** |
| **Gradle 构建日志尚未纳入交付物** | 第 ⑩ 项，本版仍未纳入（日志留在工作目录的 `.txt` 里，源码包的 `SKIP_EXT` 排除了 `.txt`） |
| **VpnProfile 编辑界面** | 2.2 时就没有做，本版未涉及 |
| **源码包冷构建二次验证** | 1.02 做过，2.2 与 1.03 都没有重做 |
