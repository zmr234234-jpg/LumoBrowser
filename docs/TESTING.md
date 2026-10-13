# LumoBrowser 测试记录

本文档记录**实际执行过的测试**、真实结果，以及**未能执行的测试及其原因**。所有数字都来自本机真实运行，不是预估。

---

## 0. 结论速览

### 0.0 当前交付：1.09（`versionCode=11`）

这一版做四件事：收藏分类栏的两种位置与它的设置项、位置切换的动画、复用既有音效与触觉、返回键改成从 WebView 自己的历史恢复。**与 1.08 最大的不同是这一轮第一次有了设备**——本机已装上 Android 模拟器与系统镜像，因此「走查」这一栏第一次是 ✅。

| 项目 | 状态 |
| --- | --- |
| 单元测试（`gradle :app:testDebugUnitTest`，全量） | ✅ **45 个测试类 / 729 个测试 / 0 失败 / 0 错误 / 0 跳过**，`BUILD SUCCESSFUL in 46s`（与 `assembleDebug`/`assembleRelease` 同一次调用，exit 0，`docs/build-logs/v109r7-build.txt`）。数字来自 `app/build/test-results/testDebugUnitTest` 下 45 个 XML 的合计——Gradle 控制台本身不打印用例数（1.08 为 718 例 / 44 类；本版 +11 例、+1 类，其中 `CategoryRailTest` 10 例、`MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` 1 例） |
| ⚠️ 一次真实的不稳定用例（**未修好，已定性**） | `StartupSmokeTest.localizedStrings_resolveToRealText` 在全量运行与独立冷构建里各失败一次：`UncaughtExceptionsBeforeTest` → 被抑制的 `Illegal connection pointer`（Robolectric `ShadowLegacySQLiteConnection`）。形状已定性（Room 的 JVM 级 `arch_disk_io` 池跨过 Robolectric 每用例重置的连接表，落在下一个用例开始前抛野异常）。**两条修法都实测失败、都没有留下**（重试规则不可能生效；单线程钉死反而让 8 个用例一起超时），删掉后连续 4 次 `--rerun-tasks` 全量全绿。详见 §0H.5 / §0H.5a |
| Debug APK 构建（`gradle :app:assembleDebug :app:assembleRelease`） | ✅ 成功，`app/build/outputs/apk/debug/app-debug.apk` **20,067,566 字节**，`versionCode=11` / `versionName=1.09-debug`，`BUILD SUCCESSFUL in 46s`，0 个 `e:` |
| Release APK 构建 | ✅ 成功，`app/build/outputs/apk/release/app-release.apk` **13,199,620 字节**，`versionCode=11` / `versionName=1.09`；`dist/LumoBrowser-v1.09-release.apk` 同为 13,199,620 字节 |
| ├ 交付件与哈希 | `dist/LumoBrowser-v1.09-debug.apk` **20,067,566 字节**、`dist/LumoBrowser-v1.09-release.apk` **13,199,620 字节**、`dist/LumoBrowser-v1.09-source.zip`（**299 个文件**；字节数与哈希见 `dist/SHA256SUMS.txt` 与 `dist/pack-report.txt`）。release 包与 1.08 同一个签名证书（`apksigner verify --print-certs` → `SHA-256 digest: 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9`）。**以 `SHA256SUMS.txt` 里的哈希为准**——重新打包会让压缩流大小有 1–2 字节的浮动，内容是一致的 |
| ├ debug 包的字节数在几次构建之间会变 | 同一份源码里，`app-debug.apk` 量到过 **20,067,098**、**20,067,017**、**20,067,046**、**20,067,562** 与 **20,067,566** 五个值（release 包与源码包逐字节稳定：13,199,620）。debug 包会被重新打包与 zipalign 填充影响，**字节数不是判定代码是否一致的依据**，下面的 dex 标记检查才是 |
| 源码包自足（交付前独立冷构建） | ✅ 源码包解到工作树之外、只补 `local.properties` → `BUILD SUCCESSFUL in 3m 1s`，**45 / 728 / 0 / 0 / 0**，release APK 字节数与交付件相同（该次 debug 19,552,711 字节）。见 §0H.7 |
| 出货 APK 里确实有本版的代码 | ✅ 四个包（build 输出 debug/release + `dist/` debug/release）逐个解包搜索 `classes*.dex`：`goBackKeepingPosition`、`goForwardKeepingPosition`、`category_rail_placement`、`setting_category_rail`、`HISTORY_RESTORE_POLL_MS` 五个标记**全部命中** |
| 三语言字符串一致性 | ✅ 三份 `strings.xml` 各 **519 条** `<string>`，新增 `setting_category_rail` / `setting_category_rail_hint` / `setting_category_rail_on` / `setting_category_rail_off` 四键，中/繁/英三语齐全 |
| 模拟器安装与手动走查 | ✅ **已执行** —— Android 模拟器，安装包 `com.lumobrowser.app.debug`，逐项标记见 **§0H** |
| 返回路径的设备证据 | ✅ 已执行：返回后一级页报 `nav=back_forward` 且阅读位置保留（停在链接下方 `Section 126` 附近）；打开时报 `nav=navigate`，两个值成对出现。夹具 origin 为 `Cache-Control: no-store` 时，返回的同一秒里出现过一条 `GET /long.html`，两次观察并列记录在 §0H.6 |
| Logcat 检查 | ✅ 已执行 —— 走查期间按 `FATAL EXCEPTION` / `AndroidRuntime` 过滤，本版运行**没有崩溃记录**（§0H.8） |
| 验收标准逐项覆盖 | ✅ 见 **§0H.1**（本轮被要求的检查逐条标「已执行 / 未执行」）；历史各版见 §0G（1.08）、§0F（1.07）与 §0E、§0A–§0D |

### 0.0a 上一版交付：1.08（`versionCode=10`，保留原文）

先说这一版**没有解决什么**：**「安装在手机上打不开」的真因仍然没有找到**。找它需要那台手机的 Logcat，而它至今没有拿到；本机也没有模拟器、没有系统镜像、没有可连的真机。所以 1.08 **不声称修好了启动崩溃**——它做的是把已经写进代码、但没真正生效或没真正接上的功能补完，并且把每一项能力和它的边界一起写进文档。

| 项目 | 状态 |
| --- | --- |
| Debug APK 构建（`gradle :app:assembleDebug :app:assembleRelease`） | ✅ 成功，**19,535,475 字节**，`versionCode=10` / `versionName=1.08-debug`，同一次调用 `BUILD SUCCESSFUL in 1m 44s`，**0 个 `e:`、0 个 `w:`**（校验见 §7.0） |
| Release APK 构建 | ✅ 成功，**13,182,384 字节**，`versionCode=10` / `versionName=1.08`，工程内密钥签名（`CN=LumoBrowser`，v2 方案，证书 SHA-256 `4beb5163…75a9` 与 1.07 **同一把**，因此可覆盖手机上已装的 1.07 release），`aapt2 dump badging` 确认**无 `application-debuggable`**、四 ABI 齐全（校验见 §7.0） |
| 单元测试（`gradle :app:testDebugUnitTest`，全量） | ✅ **718 个用例，0 失败，0 错误，0 跳过（44 个测试类）**，`BUILD SUCCESSFUL in 38s`，**0 个 `e:`、0 个 `w:`**（上一版 628 例 / 42 类；本版 +90） |
| ├ **导航安全（1.08 新增模块）** | ✅ **81 个**（`NavigationSecurityTest` 52 · `NavigationSecurityManagerTest` 29）——重定向环、弹窗泛滥、不可信 scheme、Safe Browsing 命中四条规则与它们的记录 |
| ├ **广告屏蔽（1.08 补强）** | ✅ **71 个**（`AdBlockPolicyTest` 26 · `AdBlockRulesTest` 20 · `AdBlockManagerTest` 13 · `AdBlockResponsesTest` 12；上一版 63）。新增覆盖：媒体分片（`.m3u8`/`.mpd`/`.m4s`/`.ts`）不被主机规则误杀、扩展名只从路径读、站点暂停的条目计算与复原 |
| ├ **自动播放偏好（1.08 补强）** | ✅ **31 个**（`BrowserConfigManagerTest`）。删掉一条**永远通过**的旧测试（只断言 `planFor` 的参数默认值），换成双向断言 + 出厂默认值断言 |
| 广告屏蔽是否真的生效 | ✅ 决策层由 71 例覆盖（含媒体豁免与暂停），**❌ 真实站点上的误拦/漏拦未测试**——没有网络测试环境，也没有设备 |
| 网页视频与全屏是否真的修好了 | ✅ 「决定」这一层由测试覆盖，**❌ 端到端未测试**：视频能不能播、全屏能不能退出，只有真机能回答（手工清单见 [`VIDEO_PLAYBACK.md`](VIDEO_PLAYBACK.md) §6） |
| 导航安全是否真的接上了 | ✅ 已接线的四个入口（用户导航 / 页面导航 / 弹窗投递 / Safe Browsing 命中）在代码里可逐行核对，规则层 81 例全绿；**❌ 未接线的入口与理由写在 [`NAVIGATION_SECURITY.md`](NAVIGATION_SECURITY.md) §6**，不是"忘了接" |
| 三语言字符串一致性 | ✅ 三份 `strings.xml` 各新增 **20 条 `security_*`**，`StringResourceParityTest` 通过（名称集合三方对称差 0） |
| 出货 APK 里确实有本版的代码与文案 | ✅ 实测：`aapt dump strings` 能从 APK 里读出 `安全浏览已开启：由系统按自己的清单检查每个地址…` 与英文原文；`classes2.dex` 中 `NavigationSecurityManager` / `pausedEntriesFor` 命中 14 处 |
| 真机安装与手动走查 | ❌ **未执行** —— 本机无模拟器、无真机（原因见 §4） |
| Logcat 检查 | ❌ **未执行** —— 同上；**这仍然是 1.08 无法回答"为什么打不开"的唯一原因** |
| 验收标准逐项覆盖 | ✅ 见 **§0G**（1.08 的 A–F 六组覆盖表，逐条标「通过 / 静态审查 / 未测试」）；历史各版见 §0F（1.07）与 §0E、§0A–§0D |

### 0.0b 上一版交付：1.07（`versionCode=9`，保留原文）

| 项目 | 状态 |
| --- | --- |
| Debug APK 构建（`gradle :app:assembleDebug`） | ✅ 成功，**19,416,196 字节**，`versionCode=9` / `versionName=1.07-debug`，`BUILD SUCCESSFUL`（Debug + Release 同一次调用 `2m 26s`），**0 个 `e:`、0 个 `w:`**（产物校验见 §7.0） |
| Release APK 构建（`gradle :app:assembleRelease`，**1.07 新增**） | ✅ 成功，**13,099,148 字节**，`versionCode=9` / `versionName=1.07`，用工程内附带密钥签名（`CN=LumoBrowser`，v2 方案），`aapt2 dump badging` 确认**无 `application-debuggable`**、四 ABI 齐全（校验见 §7.0） |
| 单元测试（`gradle :app:testDebugUnitTest`，全量） | ✅ **628 个用例，0 失败，0 错误，0 跳过（42 个测试类）**，`BUILD SUCCESSFUL in 27s`，**0 个 `e:`、0 个 `w:`**（上一版 527 例 / 33 类） |
| ├ **崩溃自述与安全模式（1.07 新增）** | ✅ **38 个**（`CrashGuardTest` 14 · `CrashRecordTest` 10 · `CrashRecoveryActivityTest` 8 · `SafeModeTest` 5 · `CrashRoutingTest` 1） |
| ├ **广告屏蔽（1.07 新增）** | ✅ **63 个**（`AdBlockPolicyTest` 22 · `AdBlockRulesTest` 20 · `AdBlockResponsesTest` 12 · `AdBlockManagerTest` 9） |
| ├ **语言切换启动测试（1.07 修复卡死）** | ✅ **1 个**，`LanguageSwitchLaunchTest` 从「**永不返回**（20 分钟零进度，主线程自旋）」变为 **16 秒通过**；修法见 §3.12 |
| 真实崩溃 → 第二次启动自我说明 | ✅ 逻辑层已由测试覆盖（`CrashGuardTest` 串接/幂等、`CrashRoutingTest` 路由、`CrashRecoveryActivityTest` 五个按钮），❌ **真机未验证**——需要一次真实崩溃才能证明端到端表现（见 §0F.8） |
| 安全模式真的关掉了动效 | ✅ 以**后果**断言：`MotionPresets.config(safe.animationPreset, safe.animationStrength, safe.reduceMotion).animationsEnabled == false` 且 `startupTimeline(motion) == null`（`SafeModeTest`） |
| Release 签名与清单校验 | ✅ `apksigner verify` 两个包都通过（Debug = `CN=Android Debug`，Release = `CN=LumoBrowser`）；`aapt2 dump badging` 与 `zipalign`（`-c -p -v`）逐项核对 |
| 三语言字符串一致性 | ✅ 三份 `strings.xml` 各新增 **17 条 `crash_recovery_*`**，`StringResourceParityTest` 6 例通过（名称集合三方对称差 0） |
| 真机安装与手动走查 | ❌ **未执行** —— 本机无模拟器、无真机（原因见 §4）。**1.07 的核心功能（崩溃自述页）恰恰只有在真机上发生真实崩溃时才会被看到**，这一项比上一版更依赖设备 |
| Logcat 检查 | ❌ **未执行** —— 同上。**这正是 1.07 存在的理由**：拿不到 Logcat，所以让应用自己写一份可复制、可分享的崩溃报告 |
| 验收标准逐项覆盖 | ✅ 见 **§0F**（1.07 的 8 组覆盖表）；历史各版见 §0E（1.06）与 §0A–§0D |

### 0.0c 上一版交付：1.06（`versionCode=8`，保留原文）

| 项目 | 状态 |
| --- | --- |
| Debug APK 构建（`gradle :app:assembleDebug`） | ✅ 成功，**19,356,507 字节**，`versionCode=8` / `versionName=1.06-debug`，`BUILD SUCCESSFUL in 17s`，**Kotlin 警告 0 条**——⚠️ 本轮是增量构建（`9 executed, 31 up-to-date`），这一条的强度低于 1.05 的冷构建，**边界写在 §7.0**（产物校验见 §7.0） |
| 单元测试（`gradle :app:testDebugUnitTest`，全量） | ✅ **527 个用例，0 失败，0 错误，0 跳过（33 个测试类）**，`BUILD SUCCESSFUL in 10m 46s`，**0 个 `e:`、0 个 `w:`**（上一版为 505 例 / 32 类） |
| ├ **外部移交（1.06 新增）** | ✅ **18 个**（`ExternalLauncherTest` —— 相对 1.05 的源码包，它是本轮**唯一新增的源码文件**，18 例全是新写的；上一版的 505 个用例里**没有一个调用过 `ExternalLauncher.build`**） |
| ├ **跨站链接判定（1.06 补强）** | ✅ **27 个**（`CrossSiteLinkPolicyTest`，上一版 25：新增「服务器发的 30x 不算离开」「子框架导航不算离开」） |
| ├ **设置持久化（1.06 补强）** | ✅ **12 个**（`SettingsRepositoryTest`，上一版 10） |
| 验收标准逐项覆盖 | ✅ 见 **§0E**：A 根因 A 7 项、B 根因 B 6 项、C 跳转判定 4 项、D 查询细节 4 项、E 无障碍预检 3 项、F 设置页与字符串 4 项、G 回归与构建 6 项、H 必须由真机回答的 6 条 |
| 三语言字符串一致性 | ✅ 各 **428 条**（上一版 431，删去 3 条「权限与隐私」相关），名称集合三方对称差 **0**，格式参数不匹配 **0**，全树 **426 个** `R.string.` 引用全部可解析 |
| 真机安装与手动走查 | ❌ **未执行** —— 本机无模拟器、无真机（原因见 §4）。**1.06 删掉了三处「查询当闸门」，判断被推迟到尝试的那一刻，因此这一项比任何时候都更需要真机回答**（清单见 §0E.8） |
| Logcat 检查 | ❌ **未执行** —— 同上。没有设备就没有 Logcat |

### 0.1 历史结论速览（1.03 轮，保留原文）

下面这张表记录的是 **1.03 那一轮**的数字。它没有被更新，因为它是**历史记录**；1.02 / 2.2 / 1.03 / 1.04 / 1.05 各自的产物校验分别在第 7 节。

| 项目 | 状态 |
| --- | --- |
| Debug APK 构建（干净重建，`clean testDebugUnitTest assembleDebug`） | ✅ 成功，**19,218,591 字节，`versionCode=5` / `versionName=1.03-debug`**，`BUILD SUCCESSFUL in 16m 37s`，**Kotlin 警告 0 条**（1.03；1.02 / 2.2 的产物见第 7 节） |
| 单元测试（`testDebugUnitTest`，1.03 轮） | ✅ **372 个用例，0 失败，0 错误，0 跳过（25 个测试类）** |
| ├ **无障碍辅助（1.03 新增）** | ✅ **59 个**（`AccessibilityAssistTest` 20 + `AssistTargetCodecTest` 22 + `AccessibilityRuntimeTest` 17） |
| ├ **启动时间轴（1.03 新增）** | ✅ **12 个**（`StartupTimelineTest`） |
| ├ **VPN 集成（2.2 新增）** | ✅ **22 个**（`VpnIntegrationTest`） |
| ├ **音效分类与素材（2.2 新增）** | ✅ **15 个**（`SoundCueTaxonomyTest` 10 + `SoundLibraryTest` 5） |
| ├ 不启动 Activity 的单测（数据层 / 工具层 / 主题 / 模式 / 迁移） | ✅ **254 个**（2.2 轮计数） |
| ├ 真的启动 `MainActivity` 的用例 | ✅ **19 个**（`StartupSmokeTest` 7 + `AppFlowSmokeTest` 11 + `LanguageSwitchLaunchTest` 1） |
| ├ **浏览模式配置（纯函数）** | ✅ **76 个**（`BrowserModeTest` 18 + `AutoModeTest` 28 + `BrowserConfigManagerTest` 30） |
| ├ **性能开关语义（纯函数）** | ✅ **10 个**（`SimplifiedEffectsTest`） |
| ├ **阴影高度规则（纯函数）** | ✅ **4 个**（`SurfaceElevationTest`） |
| ├ **数据库迁移 v1→v2→v3（真 SQLite）** | ✅ **11 个**（`DatabaseMigrationTest`） |
| ├ **核心流程冒烟测试（真的点按钮）** | ✅ **11 个**（`AppFlowSmokeTest`） |
| ├ **语言切换决策测试（纯函数）** | ✅ **6 个**（`LanguageSwitchDecisionTest`） |
| ├ **动效与主题（纯函数）** | ✅ **28 个**（`MotionAndThemeTest`，含 `reduceMotion` 回归） |
| └ **语言切换启动测试（真的启动 `MainActivity`）** | ✅ **1 个**（`LanguageSwitchLaunchTest`） |
| 交付校验脚本（`_verify103.py`） | ✅ **`VERDICT: OK`** —— 10 个 dist 文件哈希全对、源包 209 档（含 8 份构建日志）、APK 里 14 个类名全部找到、manifest 确实声明了无障碍服务（见第 7 节） |
| 交付源码包冷构建自足性（1.02 轮实测） | ✅ 见第 1 节末与 [`RELEASE-1.02.md`](RELEASE-1.02.md) 第 9 节。**1.03 与 2.2 没有重做这一步** |
| APK 签名校验（`apksigner verify`） | ✅ v2 方案通过，签名者 `CN=Android Debug`（**Debug 签名，非发布签名**） |
| 交付物哈希自校验 | ✅ 与实际文件一致（见第 7 节） |
| UI 测试执行（`connectedDebugAndroidTest`） | ❌ **未执行** —— 本机无模拟器、无真机（原因见第 4 节） |
| 真机安装与手动走查 | ❌ **未执行** —— 同上 |
| **无障碍服务的真机端到端验证** | ❌ **未执行** —— 同上。节点树遍历与 `performAction(ACTION_CLICK)` 的实际效果**从未被实际运行验证过** |
| 帧率 / 触摸命中率实测 | ❌ **未执行** —— 需要设备（原因见第 4 节） |
| 四种浏览模式在真实网站上的效果差异 | ❌ **未执行** —— 需要设备与真实的失败网站（见第 4.4 节） |
| 受限网络下的 VPN 辅助流程 | ❌ **未执行** —— 需要设备、真实受限网络与一个已配好的 VPN |
| 覆盖安装（1.00/1.0.1/1.02/2.2 → 1.03）是否保住收藏 | ⚠️ **迁移逻辑已用真 SQLite 测过**，但**真机覆盖安装未执行**。数据库版本仍是 3，本轮无 schema 变更 |
| Lint | ⚠️ 配置为 `abortOnError = false`，未作为质量门禁 |

**测试过程中发现并修复了 6 个生产代码缺陷**（不是测试自身的问题），其中 **3.7 节那个会让应用每次启动都崩溃**——那是真机上"安装成功但打不开"的直接原因。这是这轮测试最有价值的部分。

**第七轮（1.03：无障碍辅助服务 + 启动时间轴测试）**：这一轮有**五个**真实缺陷。前三个**是新测试第一次运行就抓出来的**，而且是同一种形状——**一个看起来在检查、其实永远为假的判断**：

- `isForbiddenAssistPackage` 比对裸常量包名，而 debug 变体的 applicationId 带 `.debug` 后缀，于是**"开发者手机上实际安装的那个变体"成了唯一漏网的包**。失败是静默的，因为**该抓它的检查本身才是错的**。
- `isEnabledViaSecureSetting` 假定可以自己补全相对拼法，但 `ComponentName.unflattenFromString` **早就替它展开了**——**用户已经在 Android 设置里打开的服务被报告成关闭**。这是该文件 KDoc 自己写明的失败模式：false negative 让用户损失一个他明明已经打开的功能，而他只会以为是 LUMO 坏了。
- `AssistTargetCodec.unescape` 的未知转义分支吃掉反斜杠，把 `C:\vpn` 变成 `C:vpn`，而同段注释声称"原样保留"。

**后两个是编译器抓出来的，测试从来没红过**——它们是构建日志里两条 `Parameter 'x' is never used` 警告：

- `AccessibilityAssistRuntime.notePressed(attempt)` 收下参数却从不读它，于是"一次回调里不可能换掉 attempt"这个假设从未被检查。真出问题时失败形态是**静默且张冠李戴**的：属于已作废尝试的按下会被报告成**当前**这次成功了。
- `AccessibilityTextField(..., onSubmit = ...)` 的 `onSubmit` 从未被调用——**键盘上的「完成」键按下去什么都不会发生**，因为缺的是 `KeyboardActions(onDone = ...)`。功能没坏（旁边那个独立按钮是真的），坏的是一条**看起来通、其实断的捷径**。

**这一轮还多了一条别人给的教训**：发布校验脚本 `_verify103.py` 第一次跑时报"manifest 未声明无障碍服务"，而 `aapt2 dump xmltree` 证明 manifest 完全正确——**是检查自己在用 UTF-8 字节搜一个 UTF-16 的字符串池**。一个永远返回 false 的检查看起来和"一切正常"没有任何区别，直到有人真的去问它到底在查什么。

**这五条与那一条误报的共同点**：它们都不会让任何测试变红，也不会让任何构建失败。前三个只让一个功能对某些用户静默失效，后两个只让一个用户按下去没有反应——而**一条总是出现、又从不影响结果的警告，会自然退化成没人读的装饰**。修掉这五条之后，构建日志里 Kotlin 警告数为 **0**。

**第五轮（1.02 第二阶段：状态栏遮挡、性能开关、网络与 VPN 辅助、数据库 v3）**：这一轮的教训与前四轮**都不一样**——三个新测试类里有**两个是"为了让一条规则可测而先改生产代码"**。阴影高度规则原先内联在 `@Composable` 里，唯一能检查它的方法就是渲染整个 App 用眼睛看；把它提取成纯函数 `lumoShadowElevation(style, elevated, simplified)` 之后，4 条断言就能钉住它。**一个只能靠肉眼检查的规则，迟早会退化成没人检查的规则**——这正是"简化效果"开关能一直是假开关的原因。

这一轮也是**第一次为数据库迁移写测试**。此前 `MIGRATION_1_2` 存在于代码里、也进过交付，但**从来没有任何东西验证过它真的能跑**。用真 SQLite 补上 11 例之后，一次运行就暴露出**测试自己的三处断言写错了**（拿枚举比存储字符串，`assertEquals` 的 `(Any?, Any?)` 签名让它编译期毫无警告），以及**一处会让测试假通过的结构问题**（`Room.databaseBuilder(...).build()` 只构造包装器，不触碰数据库就根本不会执行迁移）。详见第 3.13 节。

**另有第二轮的性能与交互审查**（真机能启动之后，反馈是"卡卡的、按键没反馈、不灵敏"）：那一次不是"跑测试发现问题"，而是**逐文件静态审查**确认真实缺陷，共 20 项，记在第 3.10 节。**其中最关键的教训是：这类缺陷单元测试根本抓不到**——它们不是逻辑错误，而是"能跑但每帧代价高""能点但点不中""接上了但零调用点"。

**第三轮**（用户反馈"打开设置切换语言时就卡住了"）：这次是**真的跑测试之前就先定位到了根因**——`MainActivity` 用 `UserPreferences.Default` 当偏好收集的占位初值，而占位值的语言恒为"跟随系统"，于是"存储的偏好"和"组合内 `remember` 的值"相比永远不等 ⇒ 无限 `recreate()`。详见第 3.11 节。这一轮的教训与第二轮相反：**它不是"测试抓不到"，而是"此前根本没有测试跨越真实偏好到达的那一刻"**——新增的 7 个用例正是为了钉住那个时刻。

**第四轮（1.02：浏览模式 + 浏览器兼容性 + 设置即时反馈）**：这一轮**又是"测试抓到真问题"**，而且是两个都会让用户直接撞上的缺陷，详见第 3.12 节——`WebViewFeature` 宣称支持却抛异常（整页崩掉）、以及每网址"请求桌面版"开关是空操作。两个都不是测试写错，都是生产代码里的真实缺陷。本轮的教训是：**给新功能补的测试，往往会顺手抓住旧功能里从没人验证过的那一半**——"请求桌面版"开关从 1.02 起就存在于 UI 上，但直到有人为 `planFor(...)` 写了断言，才发现它在三种模式下什么都不做。

新增的三个测试类全部是**纯 JVM 逻辑测试**（模式配置、AUTO 状态机、WebView 设置计划）。它们能证明"COMPAT 与 MOBILE 的配置确实不同"「AUTO 不会无限升级」「四种模式下文件访问都是关的」——但**证明不了某个具体网站在兼容模式下会真的变好**。那需要一台设备加真实的失败网站。

> **同样重要的一条**：本轮新增的 76 个用例里有 28 个专测 AUTO 的**有界性**。"自动重试"这类功能最容易写成没有上限的循环，而它在开发机上永远看起来是对的（因为开发时页面总能加载成功）。把"连喂 50 次切换判决后必须停在阶梯末端并闩锁"写成断言，才把这个性质从"我以为"变成"机器检查过"。

> **最重要的一条教训**：此前"122 个单元测试全部通过 + 冷构建零 error 零 warning"曾被当作"应用可用"的证据，但那 122 个用例**没有任何一个执行过 `setContent`**——没有 Activity、没有组合、没有 Compose 运行时。于是一个"每次启动必崩"的缺陷可以完全隐身。**对 UI 交付物而言，"能编译 + 数据层单测通过"不等于能启动。** 第 2.2 节的两个冒烟测试类就是为堵住这个盲区而写的。

---

## 0A. 1.03 对二十条验收标准的更新

1.03（`versionCode=5`）只做了一件事：**规格 2.3 的无障碍辅助服务从「明确不做」变成「已实现」**，外加关掉 2.2 遗留清单里的第 ⑧ 项（`startupTimeline()` 纯函数测试）。下面只列**状态发生变化**的那几条；未列出的条目状态与 2.2 时完全一致，依据见紧随其后的 v2.2 表。

| # | 验收标准 | 2.2 时 | **1.03** | 依据 |
| --- | --- | --- | --- | --- |
| 14 | 无障碍默认关闭且有说明页 | ❌ 未通过（本版不做） | ⚠️ **部分通过** | 服务已实现：`AssistSettings.switchState()` 是 `Boolean?`，默认 `null`（从未表态）而非 `false`；三语各新增 46 条字符串含服务标签/摘要/说明；设置页面板有「打开系统设置」「检查授权状态」「关闭辅助功能」三个真实入口。**但真机端到端未验证**——节点树遍历与 `performAction(ACTION_CLICK)` 只能上设备走一遍（§4） |
| 15 | 关闭无障碍后不再执行辅助操作 | ➖ 不适用 | ✅ **已通过** | 两条独立路径：`LumoAccessibilityService.onUnbind` / `onDestroy` 清空当前尝试；`LumoApp.kt` 接 `ON_RESUME` → `refreshServiceState`，**授权在尝试进行中被撤销时会把 attempt 一起清掉**（`AccessibilityRuntimeTest` 有专门一例钉住）。59 例全绿（`t13.txt`） |
| 2 | 冷启动有品牌动画并平滑进入主界面 | ⚠️ 部分通过 | ⚠️ 部分通过（依据加强） | 新增 `StartupTimelineTest` **12 例**（`t15.txt`）：强度为 0 返回 `null`（是**跳过**不是缩短）、九档强度下阶段边界严格落在时间轴内部、**每阶段不少于 1 毫秒**、满强度时落在规格点名的窗口内（品牌 ≤0.5s / 灯光 ≤1.4s / 交棒 ≤2.4s）、阶段长度单调、三预设均可播。**观感与首帧延迟仍未测试** |

**无障碍服务的诚实边界**：59 例测试覆盖的是**纯逻辑与授权状态识别**（决策分支、目标编解码、服务开关的两条识别路径、运行时状态机）。**真机上的节点树遍历与合成点击没有被实际运行验证过**，本机没有可用的模拟器或真机。这一条按「实现完成、真机未验」记，**不记全绿**。

### 0A.1 1.03 新增的测试

| 测试文件 | 例数 | 驱动方式 | 结果 |
| --- | --- | --- | --- |
| `app/src/test/java/com/lumobrowser/app/accessibility/AccessibilityAssistTest.kt` | 20 | 纯 JVM | 决策函数全分支、`Forbidden` 全覆盖、标签匹配 | ✅（`t13.txt`） |
| `app/src/test/java/com/lumobrowser/app/accessibility/AssistTargetCodecTest.kt` | 22 | 纯 JVM | 编解码往返、转义边界、`upsert`/`remove` 语义 | ✅（`t13.txt`） |
| `app/src/test/java/com/lumobrowser/app/accessibility/AccessibilityRuntimeTest.kt` | 17 | Robolectric `@Config(sdk = [33])`，真实写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | 服务开关识别、arm 拒绝路径、五个终态都不留下 armed attempt、**过期的按下被丢弃而非张冠李戴** | ✅（`c30.txt`，干净重建） |
| `app/src/test/java/com/lumobrowser/app/ui/components/StartupTimelineTest.kt` | 12 | 纯 JVM | `startupTimeline()` 的 12 条性质 | ✅（`t15.txt`） |

> 运行命令（`--tests` 在 `cmd /c` 内要写成转义形式，此前记成「本机坏掉」是引号问题）：
>
> ```bash
> gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.accessibility.*"
> gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.ui.components.StartupTimelineTest"
> ```

**无障碍这一轮的三个真实缺陷全部由新测试抓出**，全是产品代码的 bug、不是测试写错：

1. `isForbiddenAssistPackage` 比对裸常量 `com.lumobrowser.app`，而 debug 变体的 applicationId 是 `com.lumobrowser.app.debug`——**「开发者手机上实际安装的那个变体」成了唯一漏网的包**，失败还是静默的（该抓它的检查本身才是错的）。同文件的 `isLumoPackage` 早就同时涵盖两种拼法，只是没被用上。
2. `AssistTargetCodec.unescape` 的未知转义分支只吃掉反斜杠后的字符，把 `C:\vpn` 变成 `C:vpn`，而同段注释声称「原样保留」。
3. `isEnabledViaSecureSetting` 假定可以自己把相对拼法补全，但 `ComponentName.unflattenFromString` **自己就会把前导 `.` 对条目里的 package 展开**，而 debug 变体的 package 是 applicationId，展开后比真正存在的类名多一段——**用户已在 Android 设置里打开的服务被报告成关闭**。这是该文件 KDoc 写明的失败模式：false negative 让用户损失一个他明明已经打开的功能。

**另有四个缺陷是编译器抓出来的，不是测试**——定稿轮补记，因为它们的发现方式本身值得记：

4. `AccessibilityAssistRuntime.notePressed(attempt)` 收下参数却从不读它（`Parameter 'attempt' is never used`）。这个参数存在的意义就是让调用方说明「我按的是哪一次尝试」，却从未被核对，于是「一次回调里不可能换掉 attempt」这个假设从未被检查。真出问题时失败形态是**静默且张冠李戴**的：一个属于已作废尝试的按下，会被报告成**当前**这次尝试成功了。现在对不上就丢弃——既不改结果，也不顺手解除用户正在看的那次尝试。补了 `aPressFromASupersededAttemptIsDroppedRatherThanMisattributed`（第 17 例）。
5. `SettingsScreen.kt` 的 `AccessibilityTextField(..., onSubmit = ...)` 从未被调用（`Parameter 'onSubmit' is never used`）。调用方传的 lambda 能正常保存目标，`imeAction` 也正确设成了 `ImeAction.Done`——**但键盘上的「完成」键按下去什么都不会发生**，因为缺的是 `KeyboardActions(onDone = ...)`。功能没坏（下面那个独立的「添加」按钮是真的），坏的是一条**看起来通、其实断的捷径**。

> 这两条**不会让任何测试变红，也不会让任何构建失败**——它们只会让一个用户按下去没有反应。而它们以 `never used` 警告的形式在前几次构建中一直存在、一直被当作背景噪音。**一条总是出现、又从不影响结果的警告，会自然退化成没人读的装饰。** 修掉之后，构建日志里 Kotlin 警告数为 **0**（`c30.txt`）。

另有一处**不是**产品缺陷但值得记：`_mojibake_scan.py` 第一版把 `.kt` 里所有非 ASCII 都当可疑，被项目刻意使用的 `—`／`→`／`·` 淹没（408 条）。收窄成三件事（`U+FFFD`、已知损坏音节字符集、非 `strings.xml` 的程式档里出现 CJK 表意文字）后，107 个文件里命中 0 条，另有 35 行 CJK 人工确认**皆为刻意**（引用 brief 原文、i18n 测试夹具、`generate_sfx.py` 的风格注释等）。

---

## 0B. v2.2 二十条验收标准结果表

> **这是 2.2 当时的历史记录**，其中第 14 / 15 条已被 1.03 取代（见上一节）。保留原表是因为它记录了判断口径与每一条的依据来源。

规格第七节列出二十条验收标准，并要求**区分「已通过」「未通过」「未测试」，不得把未执行的测试标记为通过**。以下逐条列出。

判定口径：**「已通过」= 有本机实际执行过的检查（测试、命令、代码审计）支撑**；**「部分通过」= 机制已实现且有测试，但有一整条分支只能靠设备验证**；**「未测试」= 需要设备或人工听感，本机无法执行**。

| # | 验收标准 | 结果 | 依据 |
| --- | --- | --- | --- |
| 1 | 状态栏显示正常 | ✅ 已通过 | 全应用改走 `LumoInsets`；`StartupSmokeTest` / `AppFlowSmokeTest` inflate 真实 Activity 并断言 `TestTags.HOME_ROOT` |
| 2 | 冷启动有品牌动画并平滑进入主界面 | ⚠️ 部分通过 | `startupTimeline()` 与三阶段比例有代码级验证；**观感与首帧延迟未测试**（见本文件第 4 节；v2.2 那一轮的构建留档在 [`BUILD.md`](BUILD.md) §8.4） |
| 3 | 返回主界面不重播启动动画 | ✅ 已通过 | 返回是导航，不经过 `MainActivity.onCreate`；重播闸门是 `savedInstanceState == null && !LumoApplication.startupAnimationShown`（Application 级） |
| 4 | 不同主题下没有明显黑影与脏边 | ⚠️ 部分通过 | 两处 `Color.Black` 阴影与浅色主题 `hairline()` 的黑色根因已定位并修复；**渲染结果未用眼睛确认**（无设备截图） |
| 5 | 快速点击时动画与声音及时响应 | ⚠️ 部分通过 | `SoundPool` 预载 + 未载入完成时静音而非报错，机制上不会阻塞；**实际延迟未测量** |
| 6 | 关闭音效后普通操作不再播音 | ✅ 已通过 | 总开关在 `SoundSynth.isAudible()` 内统一拦截；`SoundCueTaxonomyTest` 钉住家族归属 |
| 7 | 切换风格后实际播放的素材改变 | ✅ 已通过 | `SoundLibraryTest` 钉住 19 cue × 4 风格 = 76 个 `R.raw` 全部存在且互不相同；`verify_sfx.py` 的 `hf_ratio` 证明四种风格泛音内容确有差异 |
| 8 | 试听不卡顿 | ⚠️ 部分通过 | `preview()` 绕过总开关与分组（设计意图已注明），走同一条 `SoundPool` 预载路径；**未在设备上试听** |
| 9 | VPN 未配置时明确提示、不显示虚假成功 | ✅ 已通过 | `VpnReportedState.NotConfigured`；`VpnIntegrationTest.withoutABackendThePanelSaysNotConfiguredRatherThanDisconnected`（这条测试抓出过一个真实 bug，见第 3.14 节） |
| 10 | VPN 授权被拒绝后仍可正常使用 | ✅ 已通过 | `onVpnConsentResult(granted = false)` → `publish(Failed(Unauthorized))`，不改变任何其他状态；浏览器与收藏不依赖 VPN 状态 |
| 11 | VPN 连接失败有明确错误与手动入口 | ✅ 已通过 | `VpnStatusReport.lastFailure` + `vpnFailureText()` 三语映射；「系统 VPN 设置」按钮**永远显示**（不受能力闸门限制） |
| 12 | VPN 状态变化时界面及时更新 | ✅ 已通过 | `VpnTunnelStatus.state` 是 process-scoped `StateFlow`，`vpnReport` 由 `combine(...)` 合成；`theRuntimeStateIsProcessWideSoThePanelOutlivesTheService` 钉住这条 |
| 13 | 网站加载失败不无限刷新、不反复启动 VPN | ✅ 已通过 | `VPN_WAIT_TIMEOUT_MS = 30_000L`（旧代码**完全没有逾时**）；`AssistRetryState` 的 `MAX_ATTEMPTS = 2` + `COOLDOWN_MS = 15_000L` 闩锁；`NetworkAssistTest` 15 例 |
| 14 | 无障碍默认关闭且有说明页 | ❌ **未通过（本版不做）** | 本版**没有实现无障碍服务**。规格 2.3 允许「无法安全实现时如实说明」，理由见 [`VPN_INTEGRATION.md`](VPN_INTEGRATION.md) 第四节 |
| 15 | 关闭无障碍后不再执行辅助操作 | ➖ 不适用 | 同上：本版从未执行任何辅助操作 |
| 16 | 数据库 / 收藏 / 分类 / 浏览模式与原有设置完整保留 | ✅ 已通过 | 数据库版本仍为 3，**本轮无 schema 变更**；`DatabaseMigrationTest` 11 例覆盖 v1→v2→v3；`BookmarkRepositoryTest` / `SettingsRepositoryTest` 全绿 |
| 17 | 动效音效玻璃不造成明显掉帧 | ⚠️ 部分通过 | 极光背景只有两个 `rememberInfiniteTransition` 且只在 `graphicsLayer {}` 内读取（每帧不重组）；启动动画**全程无 blur、单一驱动源**；**帧率未测量**（无设备） |
| 18 | 低性能设备能正常启动与操作 | ⚠️ 部分通过 | `SimplifiedEffectsTest` 10 例 + `SurfaceElevationTest` 4 例钉住降级路径；**无低端设备可测** |
| 19 | 退出 / 切后台 / 恢复前台时资源状态正常 | ⚠️ 部分通过 | `SoundSynth.release()`、`LumoVpnService.onDestroy()` → 幂等 `teardown()`；`onRevoke()` 处理撤销；**真机后台切换未测试** |
| 20 | 实际编译生成 APK 并安装启动验证 | ⚠️ 部分通过 | APK **已实际编译**并用 `apksigner` / `aapt2` 校验（第 7 节）；**安装与启动验证未执行**（无设备） |

**统计：9 条已通过，10 条部分通过，1 条未通过（本版明确不做），0 条被谎报为通过。**

「部分通过」全部是同一个原因：**需要一台设备**。这些不是「大概可以」，而是「机制已实现且有测试钉住，但最后一段只能由设备证实」。第 4 节逐项列出了它们各自需要什么。

---

## 0C. 1.04 验收标准覆盖表（A / B / C / D 逐项）

本表回应 1.04 任务书的第八项：把 **A（网页连续跳转 14 项）/ B（外部软件跳转 8 项）/ C（设置和按钮 7 项）/ D（崩溃日志检查）** 逐项标成「通过 / 失败 / 未测试」。

### 0C.0 三种证据强度，先说清楚

「通过」不是一种东西。下面每一行都标明它是哪一种，免得把读代码当成跑过了：

| 标记 | 含义 |
| --- | --- |
| **【单测】** | 有可执行的自动化测试，在真实 Gradle 构建（`:app:testDebugUnitTest`）里跑过并通过。这是唯一算得上「执行过」的证据。 |
| **【代码】** | 有源码坐标可读、逻辑成立，但**没有在任何设备上跑过**。 |
| **【未测】** | 需要模拟器或真机，本环境没有。**如实标记，不以推理代替实测。** |

**环境限制（本轮全部结论的边界）**：Windows / JDK 17 / Gradle 8.6 / Android SDK 34。`adb devices` 为空，模拟器无法启动（无 AVD 镜像、无硬件加速）。因此**凡是需要「打开应用、点一下」才能回答的问题，本表一律是【未测】**——包括本轮最重要的那一个：弹窗 SIGSEGV 在真机上是否真的不再发生。

### 0C.1 A — 网页连续跳转（14 项）

| # | 检查项 | 证据 | 结论 |
| --- | --- | --- | --- |
| A1 | 点收藏卡片在**应用内** WebView 打开 | `AppFlowSmokeTest.tappingACard_opensTheInAppBrowser`（`app/src/test/java/com/lumobrowser/app/AppFlowSmokeTest.kt`） | **【单测】通过** |
| A2 | 站内链接留在同一个 WebView，不跳到外部浏览器 | `WebNavigationPolicyTest` 34 例；`WebNavigationPolicy.classifyNavigation` 对 http/https 回 `NavigationKind.InApp` | **【单测】通过** |
| A3 | 第二层页面正常 | 同上；`LumoWebView.shouldOverrideUrlLoading` 对 `InApp` 回 `false`（把导航交还 WebView，保住 history / redirect / POST body） | **【代码】通过** |
| A4 | **第三层页面**（用户原始报告的闪退层级） | 根因已定位并修复，见 `docs/WEB_NAVIGATION.md` §3.3 | **【未测】** |
| A5 | 第四层及更深 | 同上 | **【未测】** |
| A6 | `target="_blank"` 不再复用主 WebView | `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` 的 `adoptPopup(result)` 里已没有 `transport.webView = this`，改为 `val scratch = newPopupScratch()`；`newPopupScratch()` 每次都造一个**从未导航过**的 WebView | **【单测】通过**（策略层）＋**【代码】通过**（生命周期层） |
| A7 | `window.open` 走同一条路径 | `BrowserConfigManager.kt:161 supportMultipleWindows = true` → 两者都进 `onCreateWindow` → 同一个 `adoptPopup` | **【代码】通过** |
| A8 | 弹窗请求的 URL 在应用内打开 | `newPopupScratch()` 的 `deliver` 闭包调用 `dispatchNavigation(url, inApp = { load(url) })` | **【代码】通过** |
| A9 | 弹窗 URL 是外部 scheme → 走确认面板 | `dispatchNavigation` 的 `ConfirmExternal` 分支 → `onExternalNavigationRequested` | **【代码】通过** |
| A10 | 弹窗 URL 是被拦截 scheme → 错误页而非静默失败 | `dispatchNavigation` 的 `Blocked` 分支 → `reportError(ERROR_UNSUPPORTED_SCHEME, …)` | **【代码】通过** |
| A11 | 加载进度动画 | `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:657`、`:665`、`:691` | **【代码】通过** |
| A12 | 前进 / 后退 / 重新加载（含禁用态） | `BrowserScreen.kt:1672 enabled = state.canGoBack`、`:1681 enabled = state.canGoForward`、`:1687 icon = if (state.isLoading) LumoIcons.Close else LumoIcons.Refresh` | **【代码】通过** |
| A13 | 重定向仍留在应用内 | `WebNavigationPolicyTest`；`InApp` 分支回 `false` 让 WebView 自己处理 3xx | **【单测】通过** |
| A14 | 渲染进程死亡后不再回调已死的界面 | 退役状态机；`LumoWebView.onRenderProcessGone` → `retired` 旗标，`onCreateWindow` 的 `view.post` 内改为 `if (!retired) emit(...)` | **【代码】通过** |

### 0C.2 B — 外部软件跳转（8 项）

| # | 检查项 | 证据 | 结论 |
| --- | --- | --- | --- |
| B1 | 识别外部 scheme（`market:` / `tel:` / `mailto:` / 自定义 scheme） | `WebNavigationPolicy.classifyNavigation` → `NavigationKind.ConfirmExternal` | **【单测】通过** |
| B2 | **不直接跳出**，先弹底部确认面板 | `app/src/main/java/com/lumobrowser/app/ui/ExternalLinkSheet.kt`；`BrowserScreen.pendingHandoff` | **【单测】通过** |
| B3 | 面板显示目标应用的可读名称 | `IntentUrl.externalTargetLabel(url)` | **【代码】通过** |
| B4 | 只有用户确认才交接 | `app/src/main/java/com/lumobrowser/app/util/ExternalLauncher.kt`（`object ExternalLauncher`）由 `HandoffOutcome` 回报结果 | **【代码】通过** |
| B5 | 取消则留在原本的网页，不清空 WebView | `BackHandler`（`BrowserScreen.kt:1035`）第一个分支就是 `pendingHandoff != null -> pendingHandoff = null` | **【代码】通过** |
| B6 | `intent://` URL 能被解析 | `IntentUrl.parseIntentUrl(url): IntentUrlSpec?`；`data class IntentUrlSpec` | **【单测】通过** |
| B7 | 目标应用不存在时给出失败提示而非崩溃 | `sealed interface HandoffOutcome`；`WebNavigationPolicyTest` 覆盖 | **【单测】通过** |
| B8 | 被拦截的 scheme（`javascript:` / `file:` / `data:`）不执行 | `WebNavigationPolicy.blockedReason(url)`；`dispatchNavigation` 的 `Blocked` 分支 | **【单测】通过** |

### 0C.3 C — 设置和按钮（7 项）

| # | 检查项 | 证据 | 结论 |
| --- | --- | --- | --- |
| C1 | 设置分成八个分类，可进可退 | `SettingsSearchTest` 31 例；`AppFlowSmokeTest.openSettingsPage(vararg trail: SettingsRoute)` 逐层点 `TestTags.settingsCategory(route)` / `TestTags.settingsGroup(route)` | **【单测】通过** |
| C2 | 设置搜索（含多字词与无结果态） | `ui.SettingsSearchTest` 31 例 | **【单测】通过** |
| C3 | 破坏性操作有二次确认对话框 | `TestTags.SETTINGS_CONFIRM_DIALOG` / `SETTINGS_CONFIRM_ACCEPT` / `SETTINGS_CONFIRM_CANCEL` | **【单测】通过** |
| C4 | 设置写入 DataStore 并持久化 | `AppFlowSmokeTest.settings_choicesPersistToDataStore` | **【单测】通过** |
| C5 | **重建 Activity 后设置仍在** | `AppFlowSmokeTest.settings_surviveActivityRecreation`（`:437` 的 `recreate()`） | **【单测】通过** |
| C6 | 按钮形状与阴影匹配（本轮 ⑤ 的原始症状） | `ui.components.ButtonStateTest` 8 例 ＋ `ui.components.SurfaceElevationTest` 4 例 | **【单测】通过** |
| C7 | 禁用态的描边与填色同步淡化 | `ButtonStateTest.a disabled border fades along with its fill`（同时断言宽度与色相不变） | **【单测】通过** |

C5 值得单独说一句：**它是整个测试套件里离用户投诉最近的那一条。** 「在手机上安装了，打不开」归根结底是「Activity 能不能活下来、活下来之后界面还在不在」。它在这一轮一度是红的，红在 `AppFlowSmokeTest.kt:151` 的 `clickOption`（设置页改成两层之后，控件在语义树里根本不存在），**不是红在 `recreate()` 之后**——也就是说，那是一个测试跟不上界面重构，不是应用真的坏了。加上导航步骤之后它变绿，recreation 本身从头到尾没有问题。

### 0C.4 D — 崩溃日志检查

**结论：【未测】。本环境没有 `adb`、没有设备、没有模拟器，`adb logcat` 无法执行。**

因此本轮**不能**声明「弹窗 SIGSEGV 已在真机上不再发生」。这是整个 1.04 最重要的一条诚实边界：修复的推理链是完整的（Chromium 官方文档有一节标题逐字要求 createWindow 的 WebView 必须没被导航过；旧代码 `transport.webView = this` 逐字违反；`supportMultipleWindows = true` 让每一次 `target="_blank"` 都走进去），但那仍然是**推理**，不是测量。

在真机上要查的三件事，留给下一次有设备时执行：

```
adb logcat -b crash -d                       # 上次闪退的 tombstone 摘要
adb logcat -d | Select-String "signal 11|SIGSEGV|libwebviewchromium"
```

以及应用内置的诊断面板（浏览器工具栏 → 诊断）：其中的 `popups` 计数会随 `target="_blank"` 递增，可以据此确认弹窗路径真的被走到了、而且进程还活着。

### 0C.5 本轮修复的 5 条失败测试（过程记录）

设置页从「一整条平铺长列表」改成「八分类两层导航」之后，第一次针对新代码跑全量测试得到 **460 例 / 5 失败**：

| 失败 | 原因 | 处置 |
| --- | --- | --- |
| `AppFlowSmokeTest.settings_choicesPersistToDataStore` @ `:151` | 旧测试直接找控件标签，控件在新结构里还没进语义树 | 新增 `openSettingsPage(vararg trail: SettingsRoute)` 先把分类卡片点开 |
| `AppFlowSmokeTest.settings_surviveActivityRecreation` @ `:151` | 同上（**失败点在 `:151`，不在 `:437` 的 `recreate()` 之后**） | 同上 |
| `AppFlowSmokeTest.settings_everyAccentSwatchAppliesWithoutCrash` @ `:362` | 同上 | 同上 |
| `StartupSmokeTest.settingsScreenComposes` @ `StartupSmokeTest.kt:122` | 同上 | 同上 |
| `ui.SettingsSearchTest.the multi-word fallback needs at least two words` | **测试自己的夹具写错了**：断言 `score("dark", …) == 0`，但 "dark" 是该夹具的精确关键字，正确返回 `KEYWORD_EXACT` | 修夹具，**不放宽生产代码的评分逻辑** |

前四条界面测试用的修法是**加导航步骤**——没有删用例、没有放宽期望值、没有用 `runCatching` 把 `performClick` 包起来。第五条改的是测试夹具而不是被测逻辑：为了让一个测试变绿去松动关键字评分，等于用测试掩盖产品行为。

### 0C.6 本轮新增的测试接点

设置页改成两层结构之后，测试需要新的锚点。`app/src/main/java/com/lumobrowser/app/ui/TestTags.kt` 新增了一个区块（`:188` 起，注释 `// ---- v1.04 settings navigation ----`，到 `:244` 结束，共 15 个常量 + 3 个函数）：

| 名称 | 位置 | 来源 |
| --- | --- | --- |
| `SETTINGS_BACK` = `"settings-back"` | `ui/TestTags.kt:72` | 既有，本轮复用 |
| `SETTINGS_CATEGORY_CARD` = `"settings-category-card"` | `ui/TestTags.kt:197` | 本轮新增 |
| `SETTINGS_GROUP_CARD` = `"settings-group-card"` | `ui/TestTags.kt:200` | 本轮新增 |
| `fun settingsCategory(route: SettingsRoute) = "$SETTINGS_CATEGORY_CARD-${route.name}"` | `ui/TestTags.kt:240` | 本轮新增 |
| `fun settingsGroup(route: SettingsRoute) = "$SETTINGS_GROUP_CARD-${route.name}"` | `ui/TestTags.kt:242` | 本轮新增 |

其余 13 个：`SETTINGS_HOME`@191、`SETTINGS_HOME_HINT`@194、`SETTINGS_PAGE_TITLE`@203、`SETTINGS_SEARCH`@206、`SETTINGS_SEARCH_CLEAR`@209、`SETTINGS_SEARCH_RESULTS`@212、`SETTINGS_SEARCH_EMPTY`@215、`SETTINGS_SEARCH_HIT`@218、`SETTINGS_CONFIRM_DIALOG`@226、`SETTINGS_CONFIRM_ACCEPT`@229、`SETTINGS_CONFIRM_CANCEL`@232、`SETTINGS_ABOUT_LICENCES`@235、`SETTINGS_LICENCES_BODY`@238，加上 `fun settingsSearchHit(id)`@244。


## 0D. 1.05 验收标准覆盖表（A / B / C / D 逐项）

本表是 1.05 这一轮的验收账。**三种证据强度必须分清楚**——和 0C.0 节的口径一致：

| 记号 | 含义 |
| --- | --- |
| ✅ **通过** | 有可执行的证据：JUnit 用例、编译产物、或从构建出的二进制里读出的值 |
| 🔶 **静态审查** | 读了代码、路径唯一、没有反例，但**没有可执行的检查**。它比「看起来对」强，比「跑过」弱 |
| ❌ **未测试** | 需要真机/模拟器，本环境没有。**不得因为「代码看起来对」就改记成通过** |

**本环境没有设备也没有模拟器。** 所以任何关于「渲染出来什么样」「耳朵听到什么」的条目一律标 ❌，不管静态审查多确定。

### 0D.1 A — 按钮的阴影、形状与涟漪（6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| A1 | 禁用时填充、文字、边框**一起**淡出，色相与边框宽度不变 | ✅ 通过 | `ButtonStateTest`（8 例）中 `every fill and label fades when the control is disabled`、`a disabled border fades along with its fill`（回归测试） |
| A2 | 渐变（非纯色）边框不会被压平成单色 | ✅ 通过 | `ButtonStateTest` 的 `a non-solid border is left untouched rather than flattened`（`assertSame`） |
| A3 | 没有边框的质感不会凭空长出边框；标高为 0 时没有阴影 | ✅ 通过 | `ButtonStateTest` 的 `a texture without a border stays without one`、`a shadow is exactly what the elevation says` |
| A4 | 禁用态的淡化不会把颜色压到不可见 | ✅ 通过 | `ButtonStateTest` 的 `the disabled fade never collapses a colour to invisible` |
| A5 | **阴影不再被形状裁剪切成方块**（本轮报告的主症状） | ❌ 未测试 | 修法是修饰符顺序（`.shadow()` 在 `.clip()` 之前），已静态审查；但「看起来是不是方块」只能渲染出来看 |
| A6 | **涟漪被约束在按钮形状内**，且不越出圆角 | ❌ 未测试 | 涟漪改由视觉节点用 `Modifier.indication` 绘制，外层触控节点 `indication = null`；需要真实触摸才能观察 |

### 0D.2 B — 反馈音效（7 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| B1 | 判定顺序固定：已关闭 → 无引擎 → 类别关闭 → app 音量为零 → 铃声安静 → 流音量为零 → 样本未就绪 | ✅ 通过 | `SoundGateTest`（14 例）；顺序本身即政策，逐条钉死 |
| B2 | **震动模式与静音模式同等对待**（本轮修复的正文） | ✅ 通过 | `SoundGateTest` 的 `vibrate is as quiet as silent`；对照 `RINGER_MODE_NORMAL` 不静音 |
| B3 | 播放流与自检流是同一条（不再各记一份） | ✅ 通过 | 编译通过 + 静态审查：`playbackStream = attributes.volumeControlStream`，`isAudible` 读同一个值 |
| B4 | app 音量为零与设备音量为零是两件事，各自可辨 | ✅ 通过 | `SoundGateTest` 的 `app volume zero is not device volume zero`、负音量视为零 |
| B5 | 试听绕过总开关与类别开关，但**不绕过**其余条件 | ✅ 通过 | `SoundGateTest` 的 `preview跳过两个开关`、`preview 仍拒绝假装零输出设备发出了声音` |
| B6 | 样本仍在解码时报告「载入中」而不是坏掉 | ✅ 通过 | `SoundGateTest` 的 `a sample still decoding reports not-ready` |
| B7 | **震动模式下真的能听到声音** | ❌ 未测试 | 需要真机 + 耳朵。本环境无法播放音频，也无法读手机铃声状态 |

补充（不计入 7 项）：失败原因会写一行去重日志（`Log.i(TAG, "Feedback audio dropped: …")`）——🔶 静态审查，需要真机 `logcat` 才算通过。

### 0D.3 C — 启动黑屏（7 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| C1 | `Theme.LumoBrowser.Starting` 解析成功、父主题为 `Theme.SplashScreen`、五个 item 齐全 | ✅ 通过 | **出货的 `resources.arsc` 里解析出来的**：`0x7f100115 style/Theme.LumoBrowser.Starting`，父主题 `style/Theme.SplashScreen`，五个 item 全部命中（§7.1 校验表）；`StartupSmokeTest` / `AppFlowSmokeTest` 在 Robolectric SDK 33 下真的启动了 `MainActivity` |
| C2 | `installSplashScreen()` 是 `onCreate` 第一句，且在 `super.onCreate` 之前 | ✅ 通过 | 同上（Robolectric 实际执行了 `onCreate`）；🔶 顺序本身为静态审查 |
| C3 | 清单里只有 MainActivity 用启动主题，`<application>` 主题不动 | ✅ 通过 | 静态审查 + `aapt2 dump badging` 的 `launchable-activity` 正常 |
| C4 | `values-night` 背景色与 Compose 启动遮罩同色 | ✅ 通过（表述已按事实收窄）| 源文件 `app/src/main/res/values-night/themes.xml` 存在；`@color/lumo_backdrop` 实测 `#FF06070E`，与 `LumoThemeState.backgroundBase` 同值。**注意出货的 `resources.arsc` 里没有 `(night)` 变体**——夜间那份与默认那份逐字相同，链接器把完全一致的配置变体合并掉了（依据：AppCompat 那些**内容不同**的夜间样式仍以 `(night)` 存在）。两种配置解析出同一个颜色，正是该文件要保证的事；详见 §0D.7 |
| C5 | 三段动画合计 1140 ms，顺序与比例保持 | ✅ 通过 | `StartupTimelineTest`（12 例）；常量实测 260 / 400 / 480 |
| C6 | 关闭动画时跳过；系统「减少动效」时压到 0.35 而不是跳过 | ✅ 通过 | `StartupTimelineTest` |
| C7 | **冷启动黑屏消失**，且 API 26–30 与 API 31+ 各验证一次 | ❌ 未测试 | 两条实现路径不同（平台属性 vs 库的兼容启动窗口），且需要真实冷启动 |

### 0D.4 D — 网站跳转开关（10 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| D1 | 开关默认关，升级不改变既有浏览方式 | ✅ 通过 | `CrossSiteLinkPolicyTest`（25 例）的 `the switch off means nothing ever leaves` + `UserPreferences.browserCrossSiteExternal = false` |
| D2 | 关时任何 http/https 地址都不出去 | ✅ 通过 | 同上组用例 |
| D3 | 开时跨站链接交给系统浏览器 | ✅ 通过 | `the switch on hands out a link that leaves the site` |
| D4 | **开时同站链接（二、三级页）仍留在应用内** | ✅ 通过 | `the switch on still keeps same-site navigation here` |
| D5 | 开关只移动网页，不改动 `tel:` / `mailto:` / `intent:` 的既有确认流程 | ✅ 通过 | `the switch on only ever moves web addresses` |
| D6 | 解析不出来的地址一律视为同站（保守方向） | ✅ 通过 | `an unreadable address is treated as the same site` |
| D7 | userinfo 按**最后一个** `@` 切（`example.com@evil.com` → `evil.com`） | ✅ 通过 | `the host after an at sign is the real host` |
| D8 | `co.uk` 被并成一个站——**已知错误方向被写成断言** | ✅ 通过 | `a two-label key merges co uk domains and that is the safe direction` |
| D9 | 只有主框架参与跨站移交，iframe 不把用户带走 | 🔶 静态审查 | `isForMainFrame` 只用在跨站那一处判定上；**没有可执行检查** |
| D10 | **系统选择器的实际行为**（弹出样子、单浏览器设备、取消后页面保留） | ❌ 未测试 | 需要真机与多浏览器环境 |

意外收获：手写 `hostOf` 的一个理由被测试钉住了——`a path with spaces is still read, not rejected`（`java.net.URI` 会对这种地址抛异常）。

### 0D.5 E — 回归

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| E1 | 全量单元测试 | ✅ 通过 | **32 个测试类 / 505 个用例 / 0 失败 / 0 错误 / 0 跳过**（v1.04 基线 29 类 460 例） |
| E2 | v1.04 的导航分类未被跨站改动破坏 | ✅ 通过 | `WebNavigationPolicyTest` 34 例全过 |
| E3 | 按钮状态表未被阴影重构破坏 | ✅ 通过 | `ButtonStateTest` 8 例全过 |
| E4 | 三语言字符串 key 集合完全相同（各 431） | ✅ 通过 | 新增 `StringResourceParityTest`（6 例），含格式说明符 `%1$s` 对齐检查 |
| E5 | 全树每一条 `R.string.` 引用都在 XML 里存在 | ✅ 通过 | 427 个不同的 `R.string.` 引用逐一比对，0 缺失 |
| E6 | 冷构建零 `e:` 零 `w:` | ✅ 通过 | `gradle clean :app:assembleDebug`，见 §7.1 |
| E7 | **真机安装并启动** | ❌ 未测试 | 本环境没有设备也没有模拟器 |

### 0D.6 这一轮的教训

1.05 查到最后，五个症状里有**两个**是同一个病：**同一件事在两个地方各记了一份，然后它们不一致。**

- 音效：播放用一条流，自检读另一条流；
- 按钮：禁用态的颜色在一个地方淡出，边框在另一个地方没淡。

所以这一轮新增的三条断言（`SoundGateTest`、`ButtonStateTest` 的边框回归、`StringResourceParityTest`）都是同一个形状：**把「两处必须一致」变成会失败的测试**。`StringResourceParityTest` 尤其值得记一笔——三份 `strings.xml` 少一条翻译，编译器不管、AAPT 不管、原来的 460 条测试也不管，只有它管。


### 0D.7 校验脚本自己造出来的两次假警报，和一个连带发现

这一节记的不是产品的缺陷，是**检查本身的缺陷**。留在这里的理由和 1.04 那节「一次被自己抓到的假绿」一样：一个判断错在哪，比它当时报了什么更重要。

**假警报一：`manifest theme attribute present = False`。** 我给 1.05 的校验脚本加了一条探针，想确认「启动主题不是只存在于资源池里，而是真的被 MainActivity 用着」。写法是拿 `Theme.LumoBrowser.Starting` 这个**名字**去搜 `AndroidManifest.xml` 的字节。**这条判断永远为假。** 二进制清单里，`android:theme` 存的是资源 **id**——`aapt2 dump xmltree` 显示 `android:theme(0x01010000)=@0x7f100115`——样式的名字根本不在那个文件里。所以它对着一个完全正确的清单报了 `False`。**这与 1.03 那次把「正确声明的无障碍服务」报成缺失是同一个形状**（用 UTF-8 字节去搜 UTF-16 字符串池）：一个看起来在检查、其实恒为假的判断。改成直接做那个更强的断言——读出 MainActivity 的 `android:theme` 指向哪个 id，再用 `aapt2 dump resources` 把该 id 解析成样式名——现在的输出是 `MainActivity android:theme = 0x7f100115 -> style/Theme.LumoBrowser.Starting`。

**假警报二：`resolved None`。** 修完上一条，第一版解析把**每一个** id 都解析成了 `None`，看上去像「主题不在资源表里」。真因是 `aapt2` 在 Windows 上输出 CRLF，而我的正则用 `\S+$` 锚行尾——在 MULTILINE 下 `$` 匹配的是 `\n` 之前的位置，不是 `\r\n` 之前，于是**永远匹配不上**。**又是一个恒为假的判断，而且是在修「恒为假的判断」的过程中产生的。** 修法是在解析前先把输出统一成 LF。

**连带发现：`values-night` 的夜间变体没有出现在出货的 `resources.arsc` 里。** 查 `aapt2 dump resources`，`style/Theme.LumoBrowser` 与 `style/Theme.LumoBrowser.Starting` 都**只有 `()` 一个配置**，`color/lumo_backdrop` 也只有 `() #ff06070e`——没有 `(night)`。但中间产物里有：`app/build/intermediates/merged_res/debug/mergeDebugResources/values-night-v8_values-night-v8.arsc.flat`（2208 字节）确实含 `Theme.LumoBrowser.Starting` 与 `lumo_backdrop`。

**所以是链接器把这份夜间变体合并掉了，而不是「资源合并没看到这个目录」。** 判断依据是反例：AppCompat 自己那些**内容不同**的夜间样式照常以 `(night)` 存在（`style/Theme.AppCompat.DayNight`、`style/Base.Theme.SplashScreen.DayNight` 等都有 `(night)` 行）。这里两份内容逐字相同，链接器就没有保留一个与默认完全一致的配置变体。

**后果是没有后果**：两种配置解析出来的背景色是同一个 `#FF06070E`，而那正是 `values-night/themes.xml` 要保证的事。**但表述必须跟着改**——「`values-night` 变体存在」这句话对源文件成立，对出货的 APK 不成立，所以 §0D.3 的 C4 一行已按事实重写，而不是继续沿用那句听起来更顺的旧措辞。

## 0E. 1.06 验收标准覆盖表（A / B / C / D / E / F 逐项）

口径与 0C.0 / 0D 一致：

| 记号 | 含义 |
| --- | --- |
| ✅ **通过** | 有可执行的证据：JUnit 用例、编译产物、或从构建出的二进制里读出的值 |
| 🔶 **静态审查** | 读了代码、路径唯一、没有反例，但**没有可执行的检查** |
| ❌ **未测试** | 需要真机/模拟器，本环境没有。**不得因为「代码看起来对」就改记成通过** |

**本环境没有设备也没有模拟器**（`adb` 不存在，SDK 里没有 system image）。所以任何「点下去之后系统弹出什么」的条目一律标 ❌，不管静态审查多确定。

### 0E.1 A — 根因 A：网页地址在意图建立之前就被否掉（6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| A1 | 一个**链接策略明确不打算移交**的网页地址，仍然必须能建立意图 | ✅ 通过 | `webAddress_isBuiltEvenThoughTheLinkPolicyDeclinesToOfferIt`：同一个地址 `externalLinkOffer(url)` 为 `null` **且** `ExternalLauncher.build(url)` 非空，两条断言同时成立 |
| A2 | `http` / `https` 生成 `ACTION_VIEW`，且**整条地址**（含 query 与 fragment）不被截断 | ✅ 通过 | `webAddress_buildsAViewIntent`、`webAddress_keepsTheWholeAddress` |
| A3 | 空白地址不构造任何意图 | ✅ 通过 | `blankAddresses_buildNothing` |
| A4 | 明确拒绝的 scheme（`javascript:`、`data:` 等）不构造意图 | ✅ 通过 | `refusedSchemes_buildNothing` |
| A5 | 每个构造出的意图都请求自己的任务（`FLAG_ACTIVITY_NEW_TASK`） | ✅ 通过 | `everyBuiltIntent_asksForItsOwnTask` |
| A6 | 其它人定义的 scheme（`taobao:` 这类）照样建立意图 | ✅ 通过 | `otherPeopleSSchemes_areBuilt` |
| A7 | **真机上点一次网页里的链接，App 真的被拉起来** | ❌ 未测试 | 需要真机与真实 App。本环境只能断言「意图被构造并交给平台」 |

### 0E.2 B — 根因 B：把查询当闸门（6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| B1 | **查询一个处理器都看不见时，地址仍然被交出去**并报告 `Opened` | ✅ 通过 | `invisibleHandler_isStillHandedTheAddress`：先**真的查一次** `queryIntentActivities` 并断言结果为空，再断言 `launch()` 返回 `Opened` |
| B2 | 平台真的拒绝时才报「没有处理器」 | ✅ 通过 | `whenThePlatformRefuses_launchReportsNoHandler`（`ActivityNotFoundException`） |
| B3 | 其它失败原因逐字回报，不被吞掉 | ✅ 通过 | `whenThePlatformFailsForAnotherReason_launchReportsWhy` |
| B4 | 不可用的地址报 `Unusable`，且**什么都不启动** | ✅ 通过 | `whenTheAddressIsRefused_launchReportsUnusableAndStartsNothing` |
| B5 | 面板不再存在「此设备上没有应用能处理该地址」这个判断 | ✅ 通过 | 全仓库 grep：`canHandle` **0 命中**；`ExternalLauncher` / `ExternalLinkSheet` / `PendingHandoff` 三处定义与参数全部删除；`externalLinkAppNone` 只在**真实尝试之后**的 `HandoffOutcome.NoHandler` 分支使用 |
| B6 | 「查询受包可见性过滤、`startActivity` 不受」这一前提成立 | 🔶 静态审查 | AOSP 取证：`ActivityStarter.java:568` → `ActivityTaskSupervisor.resolveIntent:740-768` 传 `resolveForStart = true`，`ComputerEngine.queryIntentActivitiesInternal:561-570` 因而对 exported 目标不设 `blockNormalResolution`；公开查询入口传 `false`（`:617-624`）。**没有可执行检查**——这需要一台 Android 11+ 真机 |

### 0E.3 C — 跳转判定的两个新条件（4 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| C1 | **服务器发的 `30x` 不算「离开」**，不移交 | ✅ 通过 | `CrossSiteLinkPolicyTest`（27 例，1.05 为 25）中 `a server redirect never leaves the app` |
| C2 | **子框架（iframe）的导航不移交**，但 scheme 处理不受影响 | ✅ 通过 | 同组的 `a sub-frame navigation never leaves the app` |
| C3 | `LumoWebView` 真的把 `isForMainFrame` / `isRedirect` 传下来，判定不在调用点另写一份 | ✅ 通过 | 编译通过 + 静态审查：`dispatchNavigation(rawUrl, from, isForMainFrame, isRedirect, inApp)` 三个参数一路来自 `request.isForMainFrame` / `request.isRedirect`，调用点本身**不含任何规则** |
| C4 | 判定不出来就不移交（解析不出主机的地址当同站） | ✅ 通过 | `an unreadable address is treated as the same site`（1.05 已有，1.06 未改动） |

### 0E.4 D — 两个「看起来像会生效」的查询细节（4 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| D1 | 排除项放在**选择器自己**的 extras 上，而不是被包裹的 target 上 | ✅ 通过 | `theChooserCarriesItsExclusionsOnItself`：断言 `EXTRA_EXCLUDE_COMPONENTS` 在 chooser 上**且含本应用包名**，同时断言 target 上的同一个 extra 为 `null` |
| D2 | 没有 `CATEGORY_DEFAULT`、平台永远不会启动的 filter **不算候选** | ✅ 通过 | `aHandlerThePlatformWouldNotStart_isNotCountedAsAChoice`：一个可启动 + 一个不可启动 → 必须**不弹选择器**（`ACTION_VIEW`，不是 `ACTION_CHOOSER`），旧的 `flags = 0` 写法会失败 |
| D3 | 两处查询都用 `MATCH_DEFAULT_ONLY` | ✅ 通过 | 静态审查：`ExternalLauncher.kt:221,230` |
| D4 | `createChooser` 不复制 target 上的 extras、也不复制 `FLAG_ACTIVITY_*` | 🔶 静态审查 | AOSP 取证：`Intent.createChooser`（`Intent.java:1049-1083`）只复制 `EXTRA_INTENT`／标题／sender／四个 `FLAG_GRANT_*`；读 extra 的是 `ChooserActivity`（android-14 `~:649`、android-11 `:694`），`ResolverActivity` 在两版都 0 引用 |

### 0E.5 E — 无障碍设置入口的第三次同型预检（3 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| E1 | `canOpenSettings` 这个预检不复存在 | ✅ 通过 | 全仓库 grep **0 命中**；`AccessibilityStatus.kt` 只留 `settingsIntent()`，KDoc 写明为什么故意不提供它 |
| E2 | 真的打不开时，用户看到的提示与从前**一字不差** | 🔶 静态审查 | `MainViewModel.openIntent` 的 `catch (ActivityNotFoundException) { _message.value = UiMessage.IntentUnavailable }` 未被改动，删除的只是它前面那道恒为假的预检 |
| E3 | 真机上点「打开无障碍设置」确实进到系统设置页 | ❌ 未测试 | 需要真机 |

### 0E.6 F — 设置页与字符串（4 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| F1 | 「权限与隐私」分类及其入口已删除，无障碍入口改由「隐私」分类进入且仍然可达 | ✅ 通过 | `SettingsSearchTest`（31 例）全过；全仓库 grep `privacyNote｜categoryPrivacy｜category_privacy｜privacy_note` **0 命中**，无悬空引用 |
| F2 | 三语言字符串各 428 条，**名称集合三方对称差 0**，格式参数不匹配 0 | ✅ 通过 | `StringResourceParityTest`（6 例）＋ 本轮独立量测脚本：35554 / 34615 / 34804 字节，无 BOM、纯 CRLF |
| F3 | 全树每一条 `R.string.` 引用都在 XML 里存在 | ✅ 通过 | **426 个**不同的 `R.string.` 引用逐一比对，0 缺失 |
| F4 | 底层权限检查与安全校验未被这次删除波及（最小权限、不忽略 HTTPS 证书错误、不默认上传） | 🔶 静态审查 | 本轮未触碰 `BrowserConfigManager` 的 `onReceivedSslError` 路径、未新增任何权限、未新增任何上传路径；`QUERY_ALL_PACKAGES` 仍未持有 |

### 0E.7 G — 回归与构建（6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| G1 | 全量单元测试 | ✅ 通过 | **33 个测试类 / 527 个用例 / 0 失败 / 0 错误 / 0 跳过**（1.05 为 32 类 505 例） |
| G2 | v1.05 的导航分类未被跨站改动破坏 | ✅ 通过 | `WebNavigationPolicyTest` 34 例全过 |
| G3 | 设置持久化未被新字段破坏（含升级默认值） | ✅ 通过 | `SettingsRepositoryTest` 10 → 12 例全过 |
| G4 | 构建零 `e:` 零 `w:` | ✅ 通过（有限定） | `:app:assembleDebug` 与 `:app:testDebugUnitTest` 均 0 条 Kotlin 警告（含测试源集）。**1.06 没有做 `--no-build-cache` 冷构建**：两次构建里 `:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 都不是 UP-TO-DATE（编译器确实看过本轮改动），但增量编译只重编改动的文件及其依赖，未改动文件里的旧警告不会被重新打印。所以这一条**不等于** 1.05 那种无条件结论，详见 §7.0 |
| G5 | `versionCode` 8 / `versionName` `1.06`；`applicationId`、签名、用户数据不变 | ✅ 通过 | 从出货 APK 读出的 `versionCode`/`versionName`（§7.0）；数据库 schema 未变（仍是 3），`applicationIdSuffix = ".debug"` 未变 |
| G6 | **真机安装并启动** | ❌ 未测试 | 本环境没有设备也没有模拟器 |

### 0E.8 H — 未被任何自动化覆盖、必须由真机回答的清单

这一版把三个「查询当闸门」删掉了，删掉的代价是**判断被推迟到尝试的那一刻**。所以下面这些在真机上**一次点击就能回答**，而在这里一次都答不了：

1. 开着「网站跳转」点一个跨站链接，**系统选择器是否真的出现**；设备上只有一个浏览器时是否直接进入它；
2. **取消选择器之后**，页面是否原样还在（代码里是回落到内置 WebView 打开并给提示，`openElsewhere` 的返回值走 `notice`）；
3. `taobao:` / `weixin:` 这类**自定义协议**是否真的落到对应 App（本环境只有「被系统拒绝」这一条路径被断言过）；
4. 「菜单点了没反应」是否真的不再发生——需要一个**会自己重定向或 `pushState`** 的页面才看得到那个同帧竞争；
5. 无障碍设置按钮是否真的进到系统设置页；
6. `startActivity` 不受包可见性过滤这条前提，需要一台 Android 11+ 真机确认（模拟器也可以，但本环境两者都没有）。

---

## 0F. 1.07 验收标准覆盖表（A / B / C / D / E / F / G / H 逐项）

**这一版的验收主题不是「新功能能跑」，而是「崩溃不再静默」。** 三种证据强度沿用 §0C.0 的定义：**通过（实测）**= 本机真的跑过并看到结果；**静态审查**= 读了源码/产物字节并逐条核对，但没有运行时证据；**未测试**= 本环境做不到。

### 0F.1 A — 崩溃自述（`crash/CrashGuard.kt`、`crash/CrashRecord.kt`，10 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| A1 | 未捕获异常被记录成可读报告（含时间、异常、消息、线程名） | ✅ 通过 | `CrashGuardTest`（14 例），报告写到 `filesDir/crash/last-crash.txt` |
| A2 | **一定调用前一个处理器**（进程照常终止，不做半死进程） | ✅ 通过 | 串接测试：先设哨兵 handler 再 `install`，触发后哨兵收到**同一个** Throwable |
| A3 | 第二次 `install()` 幂等，且**不会把链上处理器再串一次** | ✅ 通过 | `assertSame` + `seen.size == 1` |
| A4 | 报告有界：每 throwable ≤ 48 帧、cause 链 ≤ 6 层、正文 ≤ 12 000 字元 | ✅ 通过 | `CrashRecordTest` 三例（200 帧 → `... 152 more frames`；12 层链截断；超长正文截断） |
| A5 | `cause === current` 的自指环不会死循环 | ✅ 通过 | `traceOf` 环终止分支（静态审查 + 解析测试） |
| A6 | 崩溃计数器与「待处理」旗标分开：`ack` 保留文件、删除才清计数 | ✅ 通过 | `CrashGuardTest`：`clearPendingCrash` 后文件仍在；`clearCrash` 后文件、旗标、计数三者归零 |
| A7 | 处理器内部任何一步失败都不会再抛（`runCatching` + `guarded`） | ✅ 通过 | `CrashGuard.kt:256-257 guarded()`；`deviceSummary` 每行独立兜底 |
| A8 | 空消息 / 空白线程名 / 畸形文件都不崩，最坏返回 `null` | ✅ 通过 | `CrashRecordTest` 五种拒收 + `CrashGuardTest` 畸形档 |
| A9 | 报告里的设备事实包含 **page size**、**WebView 提供方**、包名（含 debuggable） | ✅ 通过 | `CrashGuardTest` 断言 `page size: ` / `webview: ` / `package: ` |
| A10 | 记录动作**不消耗**安全模式请求（两件事互不干扰） | ✅ 通过 | `CrashGuardTest`：`record` 后 `consumeSafeMode` 仍为真 |

### 0F.2 B — 恢复页（`crash/CrashRecoveryActivity.kt`，7 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| B1 | 有报告时显示：标题、异常类、消息、page size、webview、五个按钮 | ✅ 通过 | `CrashRecoveryActivityTest`（8 例，遍历 `android.R.id.content` 的文本） |
| B2 | 无报告时显示空状态，且**不显示**复制/删除按钮 | ✅ 通过 | 同上（`crash_recovery_empty`） |
| B3 | 「安全模式启动」→ 进 `MainActivity`、清 pending、`consumeSafeMode` 为真 | ✅ 通过 | `shadowOf(activity).nextStartedActivity` 断言组件名 + 旗标状态 |
| B4 | 「正常启动」→ 进 `MainActivity`、清 pending、`consumeSafeMode` 为假 | ✅ 通过 | 同上 |
| B5 | 复制按钮把报告全文放进剪贴板 | ✅ 通过 | `primaryClip` 文本以 `HEADER` 开头且含消息 |
| B6 | 分享按钮发出 `ACTION_CHOOSER` | ✅ 通过 | `nextStartedActivity` 的 action 断言 |
| B7 | **整页不依赖 Compose**（崩溃源在 Compose 侧时也打得开） | ✅ 静态审查 | 文件只 import 平台 View 与 `R.string`，无 `setContent` |

### 0F.3 C — 安全模式（`crash/SafeMode.kt`，6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| C1 | 关掉五个开关：动画强度 `0f`、预设 `Minimal`、背景光斑、简化效果、广告屏蔽 | ✅ 通过 | `SafeModeTest`（5 例）逐项断言 `copy()` 结果 |
| C2 | **真的关掉了动效**（以后果断言，而不是以字段断言） | ✅ 通过 | `MotionPresets.config(...).animationsEnabled == false` 且 `startupTimeline(motion) == null` |
| C3 | 不动用户的其他偏好（主题模式、背景样式、语言） | ✅ 通过 | 测试里刻意把它们设成 `Light` / `Glass` / `ChineseTraditional` 再断言不变 |
| C4 | 幂等：连续两次应用结果相同 | ✅ 通过 | 断言 `apply(apply(p)) == apply(p)` |
| C5 | **永不写回 DataStore**（安全模式是本次启动的输入，不是设置修改） | ✅ 静态审查 | `SafeMode.apply` 是纯函数；`MainActivity.kt:191` 只在 `LumoRoot` 内做局部替换 |
| C6 | 安全模式下广告屏蔽同时关闭 | ✅ 通过 | `LumoApplication.kt:123 adBlocker.enabled = prefs.adBlockEnabled && !safeMode` |

### 0F.4 D — 启动崩溃排查（本轮「没有找到凶手」本身也是结论，5 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| D1 | 用户装的 1.06 与当前源码的差异被逐档列出，且**无一项能解释启动崩溃** | ✅ 静态审查 | `dist/LumoBrowser-v1.06-source.zip` 解档 diff：`MainActivity.kt`、`AndroidManifest.xml` 完全相同 |
| D2 | 启动路径**无必然抛出点**（无 `!!` / 无无兜底枚举反查 / 无系统设置写入） | ✅ 静态审查 | 全 `app/src/main` 扫描（15 处命中全部安全，逐条列在 §7 讨论中） |
| D3 | **16 KB 内存页**假设被否证（四个 ABI 的原生库均已 16 KB 对齐） | ✅ 通过（自解析 ELF） | 四个 `.so` 的 `PT_LOAD` `p_align` 全为 `0x4000`；`zipalign -P` 不可用（build-tools 34.0.0 无此选项） |
| D4 | 「旧版写入的损坏持久值导致启动崩溃」被否证 | ✅ 静态审查 | `AssistTargetCodec.decode` 逐行 `mapNotNull`、`split` 不抛；`SettingsRepository` 零 `valueOf`/`!!` |
| D5 | 语言重建不会无限循环（`LanguageMirror.write` 在 `recreateActivity()` 之前） | ✅ 静态审查 | `MainActivity.kt` 语言 `LaunchedEffect` 逐行核对 |

### 0F.5 E — 广告屏蔽（4 个类，63 例）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| E1 | hosts 形式规则（`0.0.0.0`/`127.0.0.1` 前缀、`@@` 例外）解析正确 | ✅ 通过 | `AdBlockRulesTest` 20 例 |
| E2 | 路径片段规则不区分大小写、去除 `*` 尾，且**最短长度 4** | ✅ 通过 | `AdBlockRulesTest`：`/a`、`/**` 被丢弃并计数 |
| E3 | 判定顺序：主框架不拦 → 非 http(s) 不拦 → 例外优先 → 主机规则 → 第三方才看路径 | ✅ 通过 | `AdBlockPolicyTest` 22 例（含「主机规则不受第三方限制」） |
| E4 | `hostOf` 处理 userinfo、端口、**尾端根点**、**IPv6 字面值** | ✅ 通过 | `AdBlockPolicyTest`（`[2606:4700::1111]:443` → `2606:4700::1111`） |
| E5 | `siteOf` = 从右数第二个点之后（无 public suffix list 的诚实边界） | ✅ 通过 | 回归例：`www.news.example.org` 与 `news.example.org` 视为同站 |
| E6 | 拦截命中时的合成响应正确（透明 1×1 PNG、按扩展名/`Accept` 推 MIME） | ✅ 通过 | `AdBlockResponsesTest` 12 例（PNG 已用 `py -3` 独立解析校验：70 字节、三个 CRC 正确） |
| E7 | 计数写入被合并（不是每次拦截都落盘） | ✅ 通过 | `AdBlockManagerTest` 9 例（含 `advanceTimeBy(FLUSH_INTERVAL_MS + 1)` 的 flusher 测试） |
| E8 | 判定缓存带上界（512 项 LRU）且线程安全 | ✅ 通过 | `DecisionCache` `LinkedHashMap` + `@Synchronized get/put` |
| E9 | **HTTPS 证书错误一个都不忽略** | ✅ 静态审查 | 拦截发生在 `shouldInterceptRequest`；`onReceivedSslError` 仍是 `cancel()`，未新增任何放行分支 |

### 0F.6 F — Release 签名与两个 APK（6 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| F1 | Release 包用真密钥签名，v2 方案通过 | ✅ 通过 | `apksigner verify --print-certs`：`CN=LumoBrowser`，证书 SHA-256 `4beb5163…75a9` |
| F2 | Release 包**不可调试** | ✅ 通过 | `aapt2 dump badging` 无 `application-debuggable`（Debug 包有） |
| F3 | 两个包包名不同，可同机共存 | ✅ 通过 | `com.lumobrowser.app.debug` vs `com.lumobrowser.app` |
| F4 | 密钥口令文件缺失时工程**仍能构建**（条件式签名） | ✅ 通过 | `app/build.gradle.kts` 以 `releaseKeystorePropertiesFile.isFile` 守卫 |
| F5 | Debug 包与上一版可互相覆盖安装（同证书、同包名） | ✅ 静态审查 | Debug 证书与 1.05 相同（`ea40d5e9…1934`），`versionCode` 9 > 8 |
| F6 | Release 未混淆（刻意的：R8 未经真机验证，JS 桥接是反射绑定） | ✅ 通过 | `app/build.gradle.kts` 的 `isMinifyEnabled = false` + `isShrinkResources = false` |

### 0F.7 G — 回归与构建（7 项）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| G1 | 全量单元测试 0 失败 0 错误 0 跳过 | ✅ 通过 | **628 例 / 42 类**，`BUILD SUCCESSFUL in 27s` |
| G2 | 构建 0 个 `e:`、0 个 `w:` | ✅ 通过 | `:app:assembleDebug :app:assembleRelease`（`_v107_build_apks1.txt`） |
| G3 | 测试套件曾在 `LanguageSwitchLaunchTest` 卡死的问题已修复 | ✅ 通过 | 该类 16 秒通过；全量套件 27 秒 |
| G4 | 三语言字符串键集一致 | ✅ 通过 | `StringResourceParityTest` 6 例；新增 17 条 `crash_recovery_*` |
| G5 | 源码包可导入 Android Studio（含全部源码、资源、测试、文档） | ✅ 通过 | `dist/LumoBrowser-v1.07-source.zip` **267 个条目**（allowlist 打包，无 `build/`、无 `dist/`） |
| G6 | 无可变产物被写进源码包（APK、临时日志） | ✅ 通过 | `tools/package_source.ps1` 的 include 清单逐项核对 |
| G7 | 交付物哈希可核对 | ✅ 通过 | `dist/SHA256SUMS.txt` **17 条**，本次重算 |

### 0F.8 H — 未被任何自动化覆盖、必须由真机回答的清单

**1.07 的核心功能是「应用在崩溃后自己说明自己」——而它只有在真机上真的崩一次才会被看到。** 本机不具备这个条件，所以以下是本版明确**没有证明**的部分：

1. **Android 14/15 上的启动崩溃是否真的解决**。本版没有任何一项改动声称修掉了它（没有找到根因），只保证「下次启动会说明」；如果崩溃发生在 `Application.onCreate` 装处理器之前、或由系统直接杀进程、或发生在原生库，说明页**也不会出现**。
2. **崩溃后第二次启动是否真的先显示恢复页**（端到端）。逻辑层已由 `CrashRoutingTest` 钉住（pending → 转 `CrashRecoveryActivity` 并 `finish()`），但没有真实崩溃可用。
3. **安全模式启动后应用是否真的可用**（Robolectric 只能证明动效被关掉、偏好没写回）。
4. **恢复页在深色/浅色与三种语言下的实际排版**（测试只断言文本内容，不断言布局）。
5. **换用 Release 包后 ROM 是否不再拦截**——这需要那台具体的手机。
6. **广告屏蔽在真实网站上的效果与副作用**（是否拦错、是否影响首屏速度），本环境无法抓取网络流量对比。

---

## 0G. 1.08 验收标准覆盖表（A / B / C / D / E / F / G 逐项）

### 0G.0 三种证据强度，先说清楚

* ✅ **通过** —— 本机真实执行并看到结果（命令、日志文件、字节数、测试计数）。
* 🟡 **静态审查** —— 逐行读过代码，可以指着某一行说"它就是这么做的"，但**没有**自动化证据，也**没有**真机证据。
* ❌ **未测试** —— 需要设备或真实网络，本环境没有。**列出来，绝不当作"通过"。**

### 0G.1 A — 广告屏蔽真的生效（10 项）

| 编号 | 项 | 状态 | 证据 |
| --- | --- | --- | --- |
| A1 | 视频分片（`.m3u8`/`.mpd`/`.m4s`/`.ts`）不被**主机规则**误杀 | ✅ 通过 | `AdBlockPolicyTest`「streaming segments」4 例（含 `?token=abc.def` 这类带点查询串） |
| A2 | 扩展名**只从路径**读：`?file=stream.m3u8` 不算、`stream.m3u8.js` 不算、大小写不敏感、空串不算 | ✅ 通过 | 同上（`a media extension is read from the path, not from anywhere in the URL`） |
| A3 | 同一主机上的普通文件（`player.js`/`beacon.gif`/`ad.mp4`/`jingle.mp3`）**照旧被拦** | ✅ 通过 | 同上 |
| A4 | 媒体豁免**不影响**路径规则与 `@@` 例外 | ✅ 通过 | 同上（`/adframe/x.m3u8` 仍被拦；`allowed.example.com` 例外仍胜出） |
| A5 | 站点暂停只作用于该站，并**涵盖子域**（暂停 `example.com` 也暂停 `www.example.com`） | ✅ 通过 | `AdBlockManagerTest`「paused sites」4 例 |
| A6 | 暂停页面的请求**完全不进入判定**（`pageChecked` 不增长） | ✅ 通过 | 同上（断言的是「暂停前 + 1」，因为同一条测试里另一次判定已经加过 1） |
| A7 | 恢复时**逐条复原被存下来的条目**，而不是猜一个 | ✅ 通过 | `AdBlockManager.pausedEntriesFor()`（`AdBlockManager.kt`）＋ `resuming the stored entry lifts the pause for the whole site` |
| A8 | 浏览器溢出菜单里有**互斥**的"暂停本站 / 恢复本站"，恢复后**重载本页**看得到差别 | 🟡 静态审查 | `BrowserScreen.kt` 的 `BROWSER_AD_BLOCK_PAUSE` / `BROWSER_AD_BLOCK_RESUME` 分支与 `reloadPage()`；Robolectric 不跑真实重载，故无自动化 |
| A9 | 设置页回答三个问题：**现在用哪份规则**、本页检查了多少请求、上次下载的时间与版本 | 🟡 静态审查 | `SettingsScreen.kt` 广告面板（`s.adBlockSourceDownloaded/Bundled`、`adBlockCheckedLabel`、`adBlockDownloadedValue`）；三语文案齐全 |
| A10 | 规则更新：**只走 HTTPS**、重定向前后各查一次、声明长度与实际读取都有上限、**解析成功前一个字节都不写**、`changed=false` 时界面说的是"与当前规则相同"而不是一次成功更新 | 🟡 静态审查 | `AdBlockRuleUpdater.kt:162`（常量来源）、`:267`/`:281`（前后各查一次）、`:309`（超时）、`:166`（上限）、`:241-249`（合并而非替换）；**该类没有单元测试**（需要网络），本项**不是**"通过" |

### 0G.2 B — 网页视频与全屏（F1–F12 十二项）

逐项的技术细节与代码位置见 [`VIDEO_PLAYBACK.md`](VIDEO_PLAYBACK.md) §3、§4。

| 编号 | 症状 | 状态 |
| --- | --- | --- |
| F3 | `mediaPlaybackRequiresUserGesture` 硬编码 `true` → 脚本自播的播放器全部停在转圈 | ✅ 已修（`Model.kt` 偏好 + `BrowserConfigManager.planFor` + 设置页开关）；「决定」层有测试，**真机播放未测试** |
| F1 | 渲染进程死亡后全屏覆盖层永不退出，退出按钮变空操作 | ✅ 已修（`BrowserScreen.kt:769`）、**真机未测试** |
| F2 | 返回键优先序里没有"页面全屏" | ✅ 已修（`BrowserScreen.kt:1286` 第一档）、**真机未测试** |
| F12 | 退出全屏从不 `removeView`（第二次全屏抛 `child already has a parent`） | ✅ 已修（`BrowserScreen.kt:2081` `onDetachedFromWindow`）、**真机未测试** |
| F6 | 渲染进程重建无上限且重载同一 URL，可成死循环 | ✅ 已修（`BrowserScreen.kt:2141` `MAX_RENDERER_RESTARTS = 3` 按地址计数 + 重试面板）、**真机未测试** |
| F4 | "空渲染"探针把播放器判成空页 → AUTO 阶梯整页重载 → 打断正在起来的播放器 | ✅ 已修（`LumoWebView.kt:1346` 探针把 `video,audio,canvas,object,embed` 也算内容）＋ 新增「用户主动停止」守卫（`userCancelledLoad`：**停止一次加载是对这次加载的决定，不是关于这个站点的证据**），故取消后不会再悄悄爬升阶梯 |
| F5 | SSL 错误一律 `cancel()` | ⛔ **刻意不改** —— 硬约束是**不忽略 HTTPS 证书错误**；改它就是违反约束。改为在错误页里说清楚发生了什么 |
| F8 | 弹窗仍要求用户手势（异步 `window.open` 被静默拒绝） | ⛔ 刻意不改（放宽它就等于给页面一个无需手势的弹窗通道） |
| F9 | 页面权限一律拒绝（摄像头/麦克风/定位） | ⛔ 刻意不改（没有权限请求 UI，就不该有权限授予） |
| F10 | 主机规则对第一方也生效 | ⛔ 刻意不改（"这个域在清单里"就是它被拦的理由） |
| F11 | 第三方 Cookie 每站默认关闭 | ⛔ 刻意不改（数据最小化优先） |
| — | 全屏/视频的**端到端**结果 | ❌ **未测试** —— 手工清单在 `VIDEO_PLAYBACK.md` §6（7 条），只有真机能回答 |

### 0G.3 C — 导航安全模块（9 项）

| 编号 | 项 | 状态 | 证据 |
| --- | --- | --- | --- |
| C1 | 四条规则：重定向环（同一地址第 3 次）、弹窗泛滥（10 秒内第 4 个）、不可信 scheme、Safe Browsing 命中 | ✅ 通过 | `NavigationSecurityTest` 52 例 |
| C2 | 每种拒绝都有稳定 `id` 与可读 `label`（界面与记录共用一套） | ✅ 通过 | `NavigationReason`（`security/NavigationSecurity.kt`）52 例中覆盖 |
| C3 | 记录有界（40 条）、`clearLog()` 只清记录 | ✅ 通过 | `NavigationSecurityManagerTest` 29 例 |
| C4 | `clearLog()` **不**调用 `reset()`：清读者正在看的记录，不该改变浏览器正在做的事 | ✅ 通过 | `NavigationSecurityManagerTest`；接线处 `MainViewModel.clearNavigationSecurityLog()` 只调 `clearLog()` |
| C5 | 与既有 `WebNavigationPolicy.classifyNavigation` 的分工：后者决定"要不要在应用内打开"，本模块决定"已经决定要导航之后，这一次是否安全" | ✅ 通过 | `WebNavigationPolicyTest` 34 例仍全绿（没有两个权威互相覆盖） |
| C6 | 已接线四个入口：用户导航 / 页面导航（仅主框架 + http/https）/ 弹窗投递 / Safe Browsing 命中 | 🟡 静态审查 | `LumoWebView.kt:459`、`:820`、`:599`、`:1108`；**没有测试用真实的 `WebView` 走这四个回调** |
| C7 | 未接线入口与理由（`noteSafeBrowsingUnavailable` 无平台信号、`onSchemeRequest` 会与 `classifyNavigation` 打架、`noteUserRule` 功能未做） | ✅ 通过（作为**文档事实**） | [`NAVIGATION_SECURITY.md`](NAVIGATION_SECURITY.md) §6 |
| C8 | 设置页显示拦下了多少次、最后一次是什么原因、以及可清空的记录列表 | 🟡 静态审查 | `SettingsScreen.kt` 安全面板（`SETTINGS_SECURITY_LOG_CLEAR`）；三语 20 条字符串 ✅ |
| C9 | 两个同名 `NavigationKind` 不会按"邻近性"解析错 | ✅ 通过 | 接线处显式 import（`LumoWebView.kt` import 区注释）；编译通过是硬证据 |

### 0G.4 D — 保留既有功能（回归，5 项）

| 编号 | 项 | 状态 | 证据 |
| --- | --- | --- | --- |
| D1 | 1.07 的 628 例**全部仍然通过** | ✅ 通过 | 44 个类的全量结果，0 失败 0 错误 0 跳过（`docs/build-logs/v108-tests.txt`） |
| D2 | 崩溃自述与安全模式（1.07 的核心）未被破坏 | ✅ 通过 | `crash.*` 5 个类 38 例全绿 |
| D3 | 广告屏蔽的既有 63 例未被破坏（新增 8 例） | ✅ 通过 | 4 个类 71 例全绿 |
| D4 | 收藏 / 设置持久化、备份恢复、语言、动效、VPN 等既有能力 | ✅ 通过 | `data.*` 94 例、`feedback.*` 29 例、`ui.theme.*` 38 例、`vpn.VpnIntegrationTest` 22 例全绿 |
| D5 | 字符串三语对称（含 1.08 新增 20 条 `security_*`） | ✅ 通过 | `StringResourceParityTest` 6 例（三方名称集合对称差 0） |

### 0G.5 E — 真实构建、全量测试与逐项标记（7 项）

| 编号 | 项 | 状态 | 证据 |
| --- | --- | --- | --- |
| E1 | 真的编译了（不是"应该能编译"） | ✅ 通过 | `:app:compileDebugKotlin` `BUILD SUCCESSFUL in 9s`（`docs/build-logs/v108-compile.txt`），且主源码集中**最新**的字节码时间戳晚于**最新**的源码修改时间 |
| E2 | 真的跑了全量单元测试 | ✅ 通过 | `BUILD SUCCESSFUL in 38s`，**718 / 44 / 0 / 0 / 0** |
| E3 | 真的产出了两个 APK | ✅ 通过 | `:app:assembleDebug :app:assembleRelease` `BUILD SUCCESSFUL in 1m 44s`（`docs/build-logs/v108-assemble.txt`），字节数与 SHA-256 见 §7.0 |
| E4 | 编译错误与警告 | ✅ 通过 | 全流程 0 个 `e:`、0 个 `w:`（**边界**：本轮不是 `--no-build-cache` 冷构建，故"零警告"的正确读法是"本轮改动过的文件零警告"，见 §7.0） |
| E5 | 一次真实的失败与它的修法被留下来 | ✅ 通过 | `docs/build-logs/v108-compile-failed-safebrowsing.txt`：`'onSafeBrowsingHit' overrides nothing`（放在了 `LumoChromeClient` 里，它是 `WebViewClient` 的方法）→ 移到 `LumoClient` 后 `BUILD SUCCESSFUL` |
| E6 | 出货包里有本版的代码与文案 | ✅ 通过 | `aapt dump strings` 读出三语 `security_*` 文案；`classes2.dex` 中 `NavigationSecurityManager` / `pausedEntriesFor` 命中 14 处 |
| E7 | 不能执行的测试**逐条列出** | ✅ 通过 | 本表 ❌ 行 + §4 |

### 0G.6 F — 交付物（6 项）

| 编号 | 项 | 状态 |
| --- | --- | --- |
| F1 | Debug APK（可安装、`applicationId` 带 `.debug` 后缀、与 release **可共存**） | ✅ `dist/LumoBrowser-v1.08-debug.apk`（19,535,475 B，SHA-256 `833FEE7E…C911E`，`aapt dump xmltree` 命中 `android:debuggable`） |
| F2 | Release APK（真密钥签名、**非 debuggable**、可覆盖已装的 1.07 release） | ✅ `dist/LumoBrowser-v1.08-release.apk`（13,182,384 B，SHA-256 `876BAFA2…6E42`，xmltree 里 `debuggable` **0 命中**，证书 SHA-256 `4beb5163…75a9` 与 1.07 相同） |
| F3 | 源码 ZIP（完整 Gradle 工程 + 密钥 + 文档） | ✅ `dist/LumoBrowser-v1.08-source.zip`，**287 个条目**（1.07 是 267；含 `docs/` 20 份文档、22 份构建日志、76 个音效素材、`assets/adblock/rules.txt`、`keystore/` 2 份；白名单打包，无 `build/`、无 `dist/`、无 `local.properties`） |
| F4 | 校验和清单 | ✅ `dist/SHA256SUMS.txt`，**20 条**（`dist/` 下除自身外全部文件；v1.08 三条为本版新增），打包后重算 |
| F5 | 文档：构建指南 / 测试记录 / 三份专题说明 / 变更日志 | ✅ `docs/BUILD.md`、`docs/TESTING.md`、`docs/AD_BLOCKING.md`、`docs/VIDEO_PLAYBACK.md`、`docs/NAVIGATION_SECURITY.md`、`CHANGELOG.md` |
| F6 | 构建日志留档 | ✅ `docs/build-logs/v108-compile.txt`、`v108-compile-failed-safebrowsing.txt`、`v108-tests.txt`、`v108-assemble.txt`、`v108-test-adblock.txt`、`v108-test-config.txt` |

### 0G.7 G — 必须由真机或真实网络回答的清单（7 条，**全部未测试**）

1. **为什么在手机上打不开** —— 需要那台手机的 Logcat。1.08 没有拿到，因此**没有**对这一条做任何猜测性修复。
2. 网页视频能不能播放（含自动播放开关开/关两种状态）。
3. 全屏进入/退出、旋转、以及"渲染进程被杀后退出全屏"是否真的恢复。
4. 真实站点上的广告拦截效果与误拦（媒体豁免是否真的救回了视频分片）。
5. 站点暂停在真实站点上"暂停 → 重载 → 恢复 → 重载"是否如菜单所述。
6. 导航安全的四个入口在真实网页上是否按预期拒绝（重定向环、弹窗洪水）。
7. 崩溃自述页端到端（一次真实崩溃 → 下次启动看到报告）—— 1.07 遗留，仍未验证。

---

## 0H. 1.09 走查记录（Android 模拟器）

1.08 那一轮的 §0.0 里，「真机安装与手动走查」是 ❌，原因是当时本机没有任何设备。1.09 轮的条件变了：本机已装上 Android 模拟器与系统镜像，走查第一次真的跑了起来。

设备与包：Android 模拟器，安装包 `com.lumobrowser.app.debug`（debug 包名来自 `applicationIdSuffix = ".debug"`，`versionName` 是 `1.09-debug`）。每一步都留有截图。

模拟器工具链（本机实测）：

```
D:\AndroidDev\sdk\emulator\emulator.exe            存在
D:\AndroidDev\sdk\platform-tools\adb.exe           存在
AVD                                                lumo109
系统镜像                                           D:\AndroidDev\sdk\system-images\android-34\google_apis\x86_64
```

截图存放在工程树之外，因此本节以观察结论与命令为准，不给出截图路径。§1 里「设备/模拟器 无」那一行记录的是 1.08 那一轮的环境，保留原文。

### 0H.1 被要求的检查逐项标记

| 编号 | 被要求的检查 | 是否执行 | 观察结果 |
| --- | --- | --- | --- |
| H1 | 布局切换：设置关闭时收藏页是顶部横向分类条 | ✅ 已执行 | 搜索框下方一条**横向**分类条 |
| H2 | 打开开关并正常使用后变成左侧竖向栏 | ✅ 已执行 | 分类条改画成**左侧竖向栏**，卡片在它右边 |
| H3 | 再关闭开关恢复顶部 | ✅ 已执行 | 恢复顶部横向条——**两个方向都观察到** |
| H4 | 持久化：开关 → `am force-stop` → 重启后仍是左栏 | ✅ 已执行 | 仍是左侧栏，说明选择是从存储读回的，不是回落默认值 |
| H5 | 从左栏筛选（中文分类名） | ✅ 已执行 | 右侧列表被筛成该分类，该条目呈选中态 |
| H6 | 从左栏筛选（拉丁分类名） | ✅ 已执行 | 与 H5 一致 |
| H7 | 点 `All` 恢复完整列表 | ✅ 已执行 | 恢复完整列表 |
| H8 | 汉字名称逐字竖排（`学习`、`充值`） | ✅ 已执行 | 一个字一行 |
| H9 | 拉丁字母/数字名称不被拆开（`esim`、`API`、`Wi-Fi 6`） | ✅ 已执行 | 各自作为一个完整单词绘制 |
| H10 | 超长分类名换成多行、不溢出 | ✅ 已执行 | 换成多行，没有溢出 |
| H11 | 左栏不盖住卡片网格 | ✅ 已执行 | 网格完整可见 |
| H12 | 左栏模式下卡片文字与标签完整可点 | ✅ 已执行 | 标题、域名、`#分类` 标签、每张卡的溢出菜单都在且可点 |
| H13 | 返回：书签 → 一级页 → 向下滚动 → 页内链接进二级页 → 返回 | ✅ 已执行 | 一级页带回 `nav=back_forward`，阅读位置仍在链接附近，**没有**回到顶部（见 §0H.6） |
| H14 | 布局切换的耗时 / 掉帧 | ⚠️ **部分执行** | 配置值有单元测试锁死（MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand：三个预设 300 / 250 / 140 ms，全部落在 120–300 区间）。**设备上的帧时间没有测量**，只观察到切换会画出过渡帧（见 §0H.9） |
| H15 | 左侧栏宽度公式的两个边界（72 dp / 108 dp）、其他屏宽、横屏 | ❌ **未执行** | 只在一种屏宽下走查 |
| H16 | release 包的设备走查 | ❌ **未执行** | 只在 debug 包上走查 |
| H17 | 返回后表单状态（输入框内容）是否随历史恢复 | ❌ **未执行** | 没有构造用例 |
| H18 | Logcat 检查 | ✅ 已执行 | 按 \FATAL EXCEPTION\ / \AndroidRuntime\ 过滤，本版运行期间没有崩溃记录（见 §0H.8） |
| H20 | 搜索词与分类筛选同时生效 | ✅ 已执行 | 见 §0H.10：两条都起作用，且是**交集** |
| H21 | 系统动画关闭时切换不播放过渡 | ✅ 已执行 | 见 §0H.11：`transition_animation_scale=0` 时切换**立即**完成 |
| H22 | 界面音效与触觉（开关关闭时静音、开启时有提示、触觉可独立关闭） | ❌ **未执行** | 只做了代码层核对；设备上没有听、没有振，且走查过程中把音效总开关关掉过（存储里读到 `sound_enabled = 0`）。见 §0H.12 / §0H.21 |
| H23 | 开关的持久化（读应用自己的存储，而不是看界面） | ✅ 已执行 | 见 §0H.18：点开关 → 文件里变成 `'Left'`；再点 → `'Top'`；两个方向都写进去了 |
| H24 | 左栏条目不重复、卡片齐全 | ✅ 已执行 | 见 §0H.19：9 个条目、8 张卡片，同一帧内核对 |
| H25 | 删除被选中的分类后是否会回落而不崩 | ❌ **未执行** | 本轮没有构造「选中的分类被删掉」这个用例 |
| H19 | 崩溃自述页端到端（1.07 遗留） | ❌ **未执行** | 需要一次真实崩溃，本轮没有制造 |

### 0H.2 布局切换与持久化

- 开关关闭时打开收藏页：搜索框下方是横向分类条。
- 打开「设置 → 外观与布局 → 分类栏显示位置」后正常使用应用，再回到收藏页：分类条画成左侧竖向栏，卡片在右侧。
- 再关闭开关：恢复顶部横向条。**两个方向都观察到了**，不是只验证了单向。
- 持久化：打开开关后执行 `am force-stop`，重新启动应用，左侧栏仍是当前布局。因此这一项来自存储读回，而不是内存默认值。

### 0H.3 从左栏筛选

- 点左栏里的一个分类条目：右侧列表被筛成该分类，该条目呈选中态。
- 点 `All`：恢复完整列表。
- 中文分类名与拉丁分类名各观察一次，两次行为一致。
- 本轮**没有**验证「筛选后返回再进入」是否保持筛选（未执行项）。

### 0H.4 文字排布与卡片内容

设备上实际观察到的绘制结果：

| 分类名 | 绘制结果 |
| --- | --- |
| `学习`、`充值` | 逐字竖排，一个字一行 |
| `esim`、`API`、`Wi-Fi 6` | 各自作为一个完整单词，未被拆成单字符 |
| 一个超长分类名 | 换成多行，没有溢出 |
| 卡片网格 | 没有被左侧栏盖住 |

左栏模式下卡片本身的内容不变：标题、域名、`#分类` 标签、每张卡的溢出菜单都仍然可见且可点。

### 0H.5 全量单元测试里的一个不稳定用例（**未修好，已定性**）

全量单元测试的合计是 **45 个测试类 / 728 个测试 / 0 失败 / 0 错误 / 0 跳过**。这个数字在交付前出现过失败，逐字记录如下：

- 失败的用例：`com.lumobrowser.app.StartupSmokeTest.localizedStrings_resolveToRealText`
- 顶层异常：`kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`
- 其下被抑制的异常：`java.lang.IllegalStateException: Illegal connection pointer 54`，来自 Robolectric 的 `ShadowLegacySQLiteConnection`
- 单独重跑：`:app:testDebugUnitTest --tests "com.lumobrowser.app.StartupSmokeTest"`，`BUILD SUCCESSFUL`
- 随后一次全量运行：全绿

它又在**交付前的独立冷构建**里出现一次（同一个用例、同一个异常，指针编号 54 → 55），所以不能按"偶尔一次、忽略"处理。定性的触发形状：

1. 这个测试类用 `createAndroidComposeRule<MainActivity>()` 跑真实 Activity；
2. Room 的查询跑在 JVM 级的 `arch_disk_io` 线程池上，`@After` 里排空 main looper 够不到它；
3. 上一个用例留下的那条查询，在**下一个**用例开始时才碰到 Robolectric 已经重置的连接表，于是在**后台线程**上抛出 `Illegal connection pointer`；
4. `runTest` 在用例主体开始前发现这个"野异常"，用 `UncaughtExceptionsBeforeTest` 把该用例判失败——用例自己做的事并没有错。

**两条修法都试过，都实测失败，所以都没有留下**（这是本节最重要的部分）：

| 尝试 | 做法 | 实测结果 |
| --- | --- | --- |
| A. 重试规则 | 只在 `UncaughtExceptionsBeforeTest` 且带 `Illegal connection pointer` 时把用例重跑，最多 3 次 | ❌ **不可能生效**。重跑等于第二次执行同一个 `createAndroidComposeRule` 语句，Compose 自己的 `runTest` 直接拒绝：`java.lang.IllegalStateException: Only a single call to runTest can be performed during one test` |
| B. 把 Room 的磁盘工作钉到一个自有线程 | 用 `ArchTaskExecutor.setDelegate(...)` 把 `executeOnDiskIO` 换成单线程执行器，并在用例结束时排空队列；挂在 `StartupSmokeTest` 与 `AppFlowSmokeTest` 上 | ❌ **反而更糟**。`--rerun-tasks` 连跑三次：第一次 `StartupSmokeTest.addEntry_reachesEditor` 失败；第二次 `AppFlowSmokeTest` **8 个用例一起失败**，全部是 `Timed out waiting for: "Example" to be persisted after pressing save，bookmarks observed = []`——单线程把写入排在了长期驻留的观察者后面，出现了饿死；第三次全绿。**用一个偶发换八个必现是不可接受的交换，所以这条规则被整份删掉，两个测试类恢复成 1.08 原样（已用源码包逐字节比对确认相同）。** |

**当前状态：没修好，测试侧根因已定性，产品代码不涉及。** 两次尝试的代码都不在交付里，因此交付树与 v1.08 在这两个测试文件上逐字节一致。实际影响是：全量跑大约每几次会有一次红，**重跑即绿**——交付前连续 `--rerun-tasks` 的稳定性数据见 §0H.5a。真正该做的下一步是让 `@After` 等 ViewModel 的数据工作结算（现在只排空了 main looper），那需要动 `LumoApplication` 的 scope 所有权；本轮没有做，也不声称做过。

### 0H.5a 删掉两次尝试之后，全量套件的稳定性实测

上面的清理做完之后（两个测试类恢复成 1.08 原样、两条规则都不在树里），`--rerun-tasks` 连续跑 **4 次全量**：

| 次序 | 结果 |
| --- | --- |
| 1 | `BUILD SUCCESSFUL in 2m 31s` |
| 2 | `BUILD SUCCESSFUL in 2m 16s` |
| 3 | `BUILD SUCCESSFUL in 2m 23s` |
| 4 | `BUILD SUCCESSFUL in 2m 18s` |

四次都是 45 类 / 728 例 / 0 失败 / 0 错误 / 0 跳过。用 `--rerun-tasks` 是为了**强制重跑**，不让 Gradle 的 up-to-date 检查把上一轮的结果当成这一轮的结果——否则"稳定性"只是"没有真的执行"。四次不足以证明不稳定已经消失（它本来就是间歇的），但足以说明**当前交付树上的套件是绿的**，而 §0H.5 里那两条修法带来的退化（8 个用例一起超时）已经不存在。

### 0H.6 返回路径的设备证据（含 `no-store` 的观察）

走查步骤：从收藏打开一级页 → 向下滚动 → 点页内链接跳到二级页 → 按返回。

| 观察项 | 结果 |
| --- | --- |
| 一级页重新出现时的 `nav` 值 | `nav=back_forward`——文档是**从历史恢复**的，不是作为一份全新文档重新取回的 |
| 阅读位置 | 仍在链接附近，**没有**回到顶部 |
| 夹具站点的访问日志 | 该 origin 发送 `Cache-Control: no-store`；日志显示在返回这一步的**同一秒**里出现了一条 `GET /long.html` |

前两行与第三行并列记录：**在这一台 origin 上，文档确实被重新请求了一次，而历史 entry 的状态同时被恢复了。** 本节不推断这两者之间的先后与因果——`no-store` 禁止复用缓存副本，重新请求在它预期之内，但引擎内部两者如何编排，本轮没有测量。

对照（**另一次更早的**等价走查，不是同一次运行的第二个条件）：同一个夹具 origin 以 `public, max-age=600` 提供页面时，返回的页面**一条请求都没发**，`nav` 同样是 `back_forward`，位置同样保留。

代码侧的实现见 [`WEBVIEW_HISTORY_RESTORE.md`](WEBVIEW_HISTORY_RESTORE.md)：返回调用的是 WebView 自己的 `goBack()`，不调用 `loadUrl(previous)`，也不做预防性 `reload()`；`app/src/main` 里 `loadUrl(` 与 `reload(` 的全部命中已在该文档 §三逐条列出。

### 0H.7 交付源码包的独立冷构建（交付前最后一步）

交付的源码包不是一个"看起来完整"的压缩包——它被解到工作树之外的目录里独立构建过一次，用的是工作树里**没有**的东西（该目录自己的 `local.properties`）：

1. 从 `dist/LumoBrowser-v1.09-source.zip` 解压到 `%TEMP%` 下的一个新目录（包内只有一个顶层目录 `LumoBrowser`）；
2. 只补一个 `local.properties`（源码包按约定不含它，它是机器相关文件）；
3. `gradle --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`。

结果：

| 项 | 结果 |
| --- | --- |
| 构建 | `BUILD SUCCESSFUL in 3m 1s`，无 `e:` 行 |
| 单元测试 | **45 个测试类 / 728 个测试 / 0 失败 / 0 错误 / 0 跳过** |
| 该目录产出的 debug APK | 19,552,711 字节 |
| 该目录产出的 release APK | 13,199,620 字节 |

release APK 与 `dist` 里的字节数完全相同；debug APK 小一些，差值集中在 dex 与 zipalign 填充上，**这是同一个源码在两次构建里的正常现象，不是少了代码**——同一份源码的 `app-debug.apk` 在多次构建里量到过 20,067,098 / 20,067,017 / 19,552,711 三个值，而 release 包始终是 13,199,620。判断"包里有没有这一版的代码"要看 dex 里的标记，不要看字节数。

因此源码包的两个不同结论都成立：**它自足**（能从零构建出可运行的 APK 并跑通全部单元测试），**它不是交付 APK 的逐字节复现**（debug 包受构建时序影响）。

---

## 1. 测试环境

```
操作系统    Windows（本机）
JDK         Java 17.0.12 LTS      D:\Program Files\Java\jdk-17
Android SDK D:\AndroidDev\sdk     platform 34 / build-tools 34.0.0 / platform-tools 37.0.1
Gradle      8.6（D:\AndroidDev\gradle-8.6，wrapper 同版本）
AGP 8.4.2 · Kotlin 1.9.24 · KSP 1.9.24-1.0.20 · Compose BOM 2024.05.00
Robolectric 4.12.1（离线模式，运行时 jar 见下）
设备/模拟器  无（emulator 与 system-images 均未安装，0 个 AVD）
```

执行命令：

```powershell
$env:JAVA_HOME = "D:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "D:\AndroidDev\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

Set-Location D:\deepseek\Website\LumoBrowser

# 构建 + 单元测试（本次交付的最终验证命令）
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

# UI 测试编译（不运行）
.\gradlew.bat :app:assembleDebugAndroidTest

# 只跑两个冒烟测试类（改完 UI 后最快的回归方式，约 40 秒）
.\gradlew.bat :app:testDebugUnitTest --tests=com.lumobrowser.app.StartupSmokeTest
.\gradlew.bat :app:testDebugUnitTest --tests=com.lumobrowser.app.AppFlowSmokeTest

# 交付前的冷构建（清空 build/ 与 .gradle/，禁用构建缓存）——警告只有这样才能看全
.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-build-cache
```

结果从 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 里读，不看控制台滚屏：

```powershell
Get-ChildItem app\build\test-results\testDebugUnitTest\TEST-*.xml | ForEach-Object {
    $x = [xml](Get-Content $_.FullName)
    "{0,-42} tests={1} failures={2} errors={3}" -f `
        $x.testsuite.name, $x.testsuite.tests, $x.testsuite.failures, $x.testsuite.errors
}
```

最终一次运行输出（1.02 交付构建 —— **对交付的源码包做的冷构建**，见下方说明与 `RELEASE-1.02.md` 第八节）：

```
# 解压 dist/LumoBrowser-1.02-source.zip 到空目录，只补一个 local.properties，然后：
gradle clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest \
       --no-daemon --console=plain --no-build-cache
BUILD SUCCESSFUL in 17m 4s       gradle exit=0   （零 error、零 FAILURE）
单元测试 263 个用例，0 失败、0 错误、0 跳过（18 个测试类）
app-debug.apk  18,377,227 B   (versionCode=3 / versionName=1.02-debug)
sha256=E365F2A2DB1115651CAFCB1D2DC7EBC917817F90B06DF356E3C6C90C3F4163EE
```

> `dist/LumoBrowser-1.02-debug.apk` **就是这个冷构建产出的文件**（已复制过去，字节数与哈希都未改）。所以可以说的最强命题是："交付目录里的 APK，就是交付的源码包用上面这条命令建出来的那一个"——不是"照着它重新打了一个包"。
>
> 那 17 分钟不是这个工程的工作量，是这台机器的空闲内存：Gradle daemon 2.5 GB + Kotlin daemon 2.5 GB + 测试 worker 2 GB，而机器总共 15.8 GB。**更早一轮（248 例）同一条命令只用了 4m 6s。** 详见 `RELEASE-1.02.md` 第八节。
>
> **这次冷构建第一轮是红的**：`LanguageSwitchLaunchTest` 报 `java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49`。原因不是被测代码，是 Gradle 给**测试 JVM**（独立于 daemon，`org.gradle.jvmargs` 管不到）的默认堆只有 512 MB，而这个用例会在 Robolectric 里把整棵 Compose 树撑起来。修法是在 `app/build.gradle.kts` 里显式设 `maxHeapSize = "2g"`——**没有削弱断言，也没有把它排除出测试集**。
>
> 更早一轮在源码树里做的是 `:app:testDebugUnitTest`（BUILD SUCCESSFUL in 3m 3s，263 例）+ `:app:assembleDebug`（产出 18,457,992 B）。那个包**功能等价但不是逐字节相同**（DEX 里的 `BuildConfig.BUILD_TIME` 每次构建都不同，导致字符串表位移），最后被冷构建产物取代。
>
> 交付的 `dist/LumoBrowser-1.02-source.zip` **本身做过一次独立的冷构建验证**：把**压缩包之外什么都不要**——解压到一个空目录（`D:\LumoBrowser-102-coldbuild3`）+ 只补一个 `local.properties`，跑上面那条命令。**实测数字（耗时、用例数、产出 APK 的字节数与哈希、以及与源码树构建的逐条目差异追溯）记在 [`RELEASE-1.02.md`](RELEASE-1.02.md) 第八节**——那里是交付记录的权威版本，本文档不复述，避免两处数字漂移。上一版（`LumoBrowser-v2-source.zip`，85 文件）同样验证过：`exit=0`、`BUILD SUCCESSFUL in 5m 29s`、零 error 零 warning、13 个测试类 223 用例 0 失败、产出 APK 与交付的 APK 字节数完全相同（18,298,055 B）。

---

## 2. 单元测试结果

> **当前交付 1.08 的数字是 44 个测试类 / 718 个用例 / 0 失败 / 0 错误 / 0 跳过**（见 §0.0、§0G.5 与 §7.0）。
>
> 下面这一节保留的是 **1.03 那一轮**的逐类明细（263 例 / 18 类），它后面的逐类说明（§2.1）也仍是那一版写的。此后每一轮新增的类与用例记在各自的小节里：**1.04 轮 29 类 / 460 例**（新增 `WebNavigationPolicyTest` 34、`SettingsSearchTest` 31、`ButtonStateTest` 8）、**1.05 轮 32 类 / 505 例**（新增 `CrossSiteLinkPolicyTest` 25、`SoundGateTest` 14、`StringResourceParityTest` 6）、**1.06 轮 33 类 / 527 例**（新增 `ExternalLauncherTest` 18，`CrossSiteLinkPolicyTest` 25 → 27，`SettingsRepositoryTest` 10 → 12）、**1.07 轮 42 类 / 628 例**（新增 `crash/` 下 5 个类共 38 例、`browser/adblock/` 下 4 个类共 63 例；`LanguageSwitchLaunchTest` 修好卡死后从「跑不完」变为 16 秒通过，见 §3.12）、**1.08 轮 44 类 / 718 例**（新增 `browser/security/` 下 2 个类共 81 例——`NavigationSecurityTest` 52、`NavigationSecurityManagerTest` 29；`AdBlockPolicyTest` 22 → 26、`AdBlockManagerTest` 9 → 13 共 +8 例；`BrowserConfigManagerTest` 30 → 31 是**替换**而非新增——删掉一条永远通过的测试，换成两条真断言，见 §7.0）。

**1.07 轮的 628 例逐类明细**（取自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`，42 个文件时间戳一致）：

| 测试类 | 用例数 | 测试类 | 用例数 |
| --- | ---: | --- | ---: |
| `WebNavigationPolicyTest` | 34 | `CrashGuardTest` | 14 |
| `SettingsSearchTest` | 31 | `CrashRecordTest` | 10 |
| `BrowserConfigManagerTest` | 30 | `CrashRecoveryActivityTest` | 8 |
| `AutoModeTest` | 28 | `SafeModeTest` | 5 |
| `MotionAndThemeTest` | 28 | `CrashRoutingTest` | 1 |
| `CrossSiteLinkPolicyTest` | 27 | `BackupManagerTest` | 14 |
| `UrlToolsTest` | 24 | `BookmarkRepositoryTest` | 15 |
| `AdBlockPolicyTest` | 22 | `BrowserModeTest` | 18 |
| `AdBlockRulesTest` | 20 | `DatabaseMigrationTest` | 11 |
| `AdBlockResponsesTest` | 12 | `SettingsRepositoryTest` | 12 |
| `AdBlockManagerTest` | 9 | `SoundCueTaxonomyTest` | 10 |
| `AccessibilityAssistTest` | 20 | `SoundGateTest` | 14 |
| `AccessibilityRuntimeTest` | 17 | `SoundLibraryTest` | 5 |
| `AssistTargetCodecTest` | 22 | `LanguageSwitchDecisionTest` | 6 |
| `AppFlowSmokeTest` | 11 | `LanguageSwitchLaunchTest` | 1 |
| `NetworkAssistTest` | 15 | `StartupSmokeTest` | 7 |
| `ScrollMemoryTest` | 15 | `ButtonStateTest` | 8 |
| `ExternalLauncherTest` | 18 | `StartupTimelineTest` | 12 |
| `FormatUtilTest` | 14 | `SurfaceElevationTest` | 4 |
| `JsonUtilTest` | 18 | `SimplifiedEffectsTest` | 10 |
| `StringResourceParityTest` | 6 | `VpnIntegrationTest` | 22 |

（合计 628。**新增的 101 例全部来自 1.07**：`crash/` 38 例 + `adblock/` 63 例。）

**263 个用例，0 失败。** 逐类明细（取自 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）：

| 测试类 | 用例数 | 失败 | 错误 | 跳过 | 类型 |
| --- | ---: | ---: | ---: | ---: | --- |
| `com.lumobrowser.app.data.UrlToolsTest` | 24 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.data.BackupManagerTest` | 14 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.util.JsonUtilTest` | 18 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.util.FormatUtilTest` | 14 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.ui.theme.MotionAndThemeTest` | 27 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.data.BrowserModeTest` | 18 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.browser.AutoModeTest` | 28 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.browser.BrowserConfigManagerTest` | 30 | 0 | 0 | 0 | 纯 JVM |
| `com.lumobrowser.app.ui.theme.SimplifiedEffectsTest` | 10 | 0 | 0 | 0 | 纯 JVM（第五轮新增） |
| `com.lumobrowser.app.ui.components.SurfaceElevationTest` | 4 | 0 | 0 | 0 | 纯 JVM（第五轮新增） |
| **`com.lumobrowser.app.browser.NetworkAssistTest`** | **15** | **0** | **0** | **0** | **纯 JVM（本轮新增）** |
| `com.lumobrowser.app.data.BookmarkRepositoryTest` | 15 | 0 | 0 | 0 | Robolectric |
| `com.lumobrowser.app.data.SettingsRepositoryTest` | 10 | 0 | 0 | 0 | Robolectric |
| `com.lumobrowser.app.data.DatabaseMigrationTest` | 11 | 0 | 0 | 0 | Robolectric + 真 SQLite（第五轮新增） |
| `com.lumobrowser.app.StartupSmokeTest` | 7 | 0 | 0 | 0 | Robolectric + Compose（启动真 Activity） |
| `com.lumobrowser.app.AppFlowSmokeTest` | 11 | 0 | 0 | 0 | Robolectric + Compose（真实交互） |
| `com.lumobrowser.app.LanguageSwitchDecisionTest` | 6 | 0 | 0 | 0 | Robolectric（纯函数决策） |
| `com.lumobrowser.app.LanguageSwitchLaunchTest` | 1 | 0 | 0 | 0 | Robolectric + Compose（启动真 Activity） |
| **合计** | **263** | **0** | **0** | **0** | **18 个测试类** |

耗时分布值得单独一提：`LanguageSwitchLaunchTest` **单个类耗时 136.50 秒，占全部用例时间的 89%**——它真的启动 `MainActivity` 并等 `recreate()` 收敛。其余 17 个类基本都在 1 秒内。所以"改完 UI 先跑冒烟"这条建议（第 1 节）不是随口说的：跑全量要 3 分多钟，跑两个冒烟类只要约 40 秒，而它们覆盖的正是"能不能启动"这个最致命的性质。

> 上面这张表里 `NetworkAssistTest` 那一行是**第六轮**加的。它在同一份代码上把 `assistOfferFor` 的每条分支都走了一遍——那个函数是整个网络辅助功能的全部判断（说不说、说什么），而且是个七参数的纯函数，所以"每站点覆盖优先于全局设置""证书失败永远不甩锅给 VPN"这类主张在这里是可断言的，不需要设备。

报告：`app/build/reports/tests/testDebugUnitTest/index.html`

### 2.1 各测试类覆盖了什么

**`UrlToolsTest`（24）** — 网址安全边界，本项目最关键的校验逻辑。
`acceptsPlainHttpsUrl` · `acceptsHttpUrl` · `addsHttpsSchemeWhenMissing` · `addsHttpsSchemeForProtocolRelativeUrl` · `trimsSurroundingWhitespace` · `keepsPathQueryAndFragment` · `keepsExplicitPort` · `rejectsJavascriptScheme` · `rejectsJavascriptSchemeWithMixedCase` · `rejectsDataScheme` · `rejectsFileScheme` · `rejectsIntentScheme` · `rejectsContentScheme` · `rejectsBlankInput` · `rejectsHostWithoutDot` · `rejectsInputWithEmbeddedWhitespace` · `normalizeFallsBackToTrimmedInput` · `isValidMirrorsNormalizeOrNull` · `isBrowsableOnlyAllowsHttpAndHttps` · `displayDomainStripsWwwPrefix` · `displayDomainFallsBackForUnparsableInput` · `suggestTitleUsesHostLabel` · `lastPathSegmentDecodesEscapes` · `keepsInternationalisedHostsInPunycode`
→ 验证了 `javascript:`（含大小写混写）、`data:`、`file:`、`intent:`、`content:` 五类危险协议被拒；无点主机名与含空白输入被拒；只有 `http`/`https` 被判为可浏览。

**`BackupManagerTest`（14）** — 备份格式的往返保真与容错。
`roundTripPreservesAllFields` · `roundTripIsStableOnAlreadyNormalisedUrls` · `exportDeclaresSchemaAndVersion` · `exportedBookmarksDoNotLeakInternalIds` · `rejectsForeignSchema` · `rejectsNewerSchemaVersion` · `skipsInvalidEntriesInsteadOfFailingWholeFile` · `fillsMissingTitleFromUrl` · `normalisesUrlsOnImport` · `importsEmptyBookmarkList` · `preservesPinFlagOnImport` · `suggestedFileNameMatchesPattern` · `mimeTypeIsJson` · `parseResultIsNotSharedBetweenCalls`
→ 重点：**导出的 JSON 不含内部 id 与时间戳**（`exportedBookmarksDoNotLeakInternalIds`）；**拒绝读取更高版本 schema**（`rejectsNewerSchemaVersion`，避免半懂不懂地解析未来格式）；单条坏记录只计入 `skipped` 而不整文件失败；**二次导入是幂等的**（`roundTripIsStableOnAlreadyNormalisedUrls`，防止每次往返都漂移一点）。

**`JsonUtilTest`（18）** — 自研零依赖 JSON 读写器。
`objectRoundTrips` · `stringsWithQuotesAndNewlinesAreEscaped` · `unicodeEscapesAreDecoded` · `integralDoublesPrintWithoutDecimalPoint` · `fractionsKeepTheirDecimalPoint` · `nonFiniteNumbersWriteAsZero` · `nestedStructuresRoundTrip` · `exponentsAreParsed` · `nullLiteralIsRepresented` · `booleanAndNumberAreNotCoercedToStrings` · `trailingCharactersAreRejected` · `unterminatedStringIsRejected` · `unterminatedObjectIsRejected` · `emptyInputIsRejected` · `brokenNumberIsRejected` · `whitespaceBetweenTokensIsAllowed` · `unknownFieldsArePreservedOnParse` · `emptyContainersRoundTrip`
→ 解析器是**严格**的：尾随字符、未闭合的字符串/对象、`1.2.3` 这类坏数字一律抛 `JsonParseException`，不静默返回半个结果。

**`MotionAndThemeTest`（27）** — 动效配置与主题推导（都是纯函数，可精确断言）。
`eachPresetProducesConfig` · `presetsDifferInIntensity` · `strengthSliderScalesEveryPresetMonotonically` · `minimalPresetDisablesAmbientBackgroundAnimation` · `zeroStrengthCollapsesDurationsToZero` · `zeroStrengthDisablesAnimations` · `systemReduceMotionLowersonlyNonEssentialMotion` · `systemReduceMotionStillLeavesFeedbackAlive` · `entryDelayIsZeroWhenAnimationsDisabled` · `entryDelayIsCappedForLongLists` · `entryDelayGrowsWithIndex` · `strengthScalesDurationMonotonically` · `darkModeOverridesSystemLight` · `lightModeOverridesSystemDark` · `systemModeFollowsSystem` · `buildProducesDistinctSchemesForLightAndDark` · `themeCarriesEveryUserChoiceThrough` · `accentHueChangesTheAccentColour` · `reduceMotionReachesThemeState` · `glassStyleKeepsCardAlphaWithinReadableRange` · `glowStrengthIsNonNegativeForEveryStyle` · `accentPalettesProvideStablePresets` · `customAccentHonoursHue` · `radiusTokensClampToSaneRange` · `radiusDerivationsStayOrdered` · `stableHashIsDeterministicForDomainColouring` · `bookmarkDomainUsesUrlTools`
→ 对应需求中的三条硬约束：**动画强度 0 时所有时长归零**（动画真的关掉，不是"变快"）；**系统减少动效时只降低非必要动效**（极光背景关闭，但点击反馈仍在，`systemReduceMotionStillLeavesFeedbackAlive`）；**错峰延迟有上限**（`entryDelayIsCappedForLongLists`，几百条收藏不会让用户等到天荒地老）。

**`FormatUtilTest`（14）** — 时间人性化、URL 缩短、颜色混合、FNV-1a 稳定哈希、首字母、百分比换算与夹取。
`freshTimestampReadsAsJustNow` · `minutesBucket` · `hoursBucket` · `daysBucket` · `bucketBoundariesDoNotOverlap` · `shortenUrlLeavesShortUrlsAlone` · `shortenUrlTruncatesLongUrlsWithEllipsis` · `blendAtZeroAndOneReturnsEndpoints` · `blendInMiddleIsBetweenEndpoints` · `stableHashIsDeterministicAndSpread` · `initialsPreferTitleWords` · `initialsFallBackToDomainWhenTitleBlank` · `percentAndDenormalizeRoundTrip` · `percentClampsOutOfRangeInput`

**`BookmarkRepositoryTest`（15，Robolectric + 内存 Room）** — 真实走 Room 的 SQL，不是 mock。
`addThenReadBack` · `newBookmarksGetIncreasingSortOrder` · `pinnedBookmarkSortsBeforeUnpinnedOnes` · `updateKeepsSortOrderAndCreationTime` · `deleteRemovesRow` · `restoreUndoPutBackTheSameRowWithPosition` · `applyOrderRewritesSortOrder` · `searchMatchesTitleUrlAndDescription` · `searchIsCaseInsensitive` · `categoriesAreDistinctAndSorted` · `replaceAllSwapsTheEntireTable` · `replaceAllGivesImportedRowsFreshSequentialOrder` · `deleteAllEmptiesTable` · `getReturnsNullForUnknownId` · `emptyDatabaseHasNoCategories`
→ 重点：**编辑不改变原始 `sortOrder` 与 `createdAt`**（否则每次编辑卡片都会跳到列表末尾）；**删除再撤销能回到原来的位置**；搜索三字段都命中且大小写不敏感；`replaceAll` 给导入行重新分配连续顺序。

**`SettingsRepositoryTest`（10，Robolectric + DataStore 临时文件）**
`defaultsMatchDocumentedBaseline` · `everyEnumSettingRoundTrips` · `booleanSettingsRoundTrip` · `numericSettingsRoundTrip` · `outOfRangeNumbersAreClamped` · `negativeNumbersAreClampedUpward` · `resetAllReturnsEverySettingToDefaults` · `aFreshRepositoryOverTheSameFileSeesTheSameValues` · `unreadableEnumValueFallsBackToDefaultInsteadOfCrashing` · `blankEnumValueFallsBackToDefault`
→ 重点：**所有设置在重启后能恢复**（`aFreshRepositoryOverTheSameFileSeesTheSameValues`，用同一个文件开第二个 Repository 来模拟重启）；越界数值被夹取而不是写进去；**存储里出现无法识别的枚举值时回落到默认值而不是崩溃**（这条防的是"新版本降级回旧版本"这类真实场景）。

### 2.2 启动与核心流程冒烟测试（本轮新增，也是本轮最重要的改动）

这两个类**不测数据层**，它们启动真实的 `MainActivity`、跑真实的 Compose 组合、点真实的按钮。写它们的原因见第 3.7 节：一个"每次启动必崩"的缺陷，在 122 个不碰 UI 的测试下完全隐身。

**`StartupSmokeTest`（7 个）** —— 冷启动契约：

| 用例 | 断言 |
| --- | --- |
| `coldStart_composesHomeScreen` | 首帧能组合出来，不抛异常 |
| `coldStart_showsTitleAddAndOverflowEntries` | 标题、新增入口、溢出菜单都在 |
| `coldStart_emptyStateOffersAnAction` | 空列表时给出的不是空白屏，而是可点的动作 |
| `overflowMenu_reachesSettings` | 右上角溢出菜单真的能到设置（不是死按钮） |
| `addEntry_reachesEditor` | 新增入口真的能到编辑器 |
| `settingsScreenComposes` | 设置页能组合 |
| `localizedStrings_resolveToRealText` | 本地化字符串解析成非空文本，而不是空串 |

**`AppFlowSmokeTest`（11 个）** —— 用户真会做的流程：

| 用例 | 覆盖的流程 |
| --- | --- |
| `savingABookmark_persistsItAndShowsACard` | 走完编辑器保存 → 落库 → 卡片出现在首页 |
| `tappingACard_opensTheInAppBrowser` | **点卡片进应用内浏览器**（需求里的第一条主路径） |
| `browserOpensImmersive_thenGrabberRevealsTheToolbar` | 沉浸式契约：到达时工具栏必须**不在**（否则遮挡网页），点抓取条后必须出现 |
| `browserClose_returnsToHome` | 从浏览器关得回首页 |
| `cardMenu_edit_reachesTheEditor` | 卡片菜单 → 编辑 |
| `cardMenu_pin_persistsThePinnedFlag` | 卡片菜单 → 置顶，且**真的写进了数据库** |
| `cardMenu_deleteThenUndo_restoresTheBookmark` | 删除 → 撤销条出现 → 撤销后卡片和数据库都回来 |
| `search_filtersTheGrid` | 搜索真的过滤网格，不只是高亮 |
| `settings_everyAccentSwatchAppliesWithoutCrash` | 6 个主题色全部点一遍（每个都重建整套 M3 色阶 + extra 色组） |
| `settings_choicesPersistToDataStore` | 设置项**写进 DataStore**，而不是只让 chip 变个色 |
| `settings_surviveActivityRecreation` | Activity 重建后设置仍在（等价于最小化的"重启恢复"） |

**`LanguageSwitchDecisionTest`（6 个）+ `LanguageSwitchLaunchTest`（1 个）** —— 第三轮新增，钉住"切换语言时无限重建"这个缺陷（根因见第 3.11 节）：

| 用例 | 断言 |
| --- | --- |
| `placeholderInitialValue_neverTriggersRecreation` | **原始缺陷本身**：偏好尚未加载（`loaded == null`）时，无论 Activity 当前套用哪种语言都**不得**触发重建 |
| `matchingLanguage_doesNotRecreate` | 已套用的语言与存储值一致时，不重建 |
| `realChange_recreatesExactlyOnce` | 语言确实变了，判定为需要重建 |
| `recreationConverges_forEveryStoredLanguage` | 对 `AppLanguage` 的**每一个取值**模拟 8 轮"新建实例 → 真实偏好到达"，重建次数**恰为 1**（不是 0，也不是 2+） |
| `alreadyAppliedLanguage_isStableAcrossManyFrames` | 收敛之后连续评估 50 次都不再重建（排除"很慢的回环"） |
| `mirrorRoundTripsEveryLanguageIncludingFollowSystem` | 语言镜像对每种语言都能往返，**包括"跟随系统"**（原来 `System` 会经 `toLocale()` 变成 `null`，无法与"从未写入"区分） |
| `launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate` | 真启动 `MainActivity`，用 `Application.ActivityLifecycleCallbacks` 数 `onActivityCreated`：全过程**只创建 1 个 Activity 实例**，且运行中的 Activity 资源 locale 确实是 `en` |

> **这个测试的边界（如实声明）**：它**只声称**基线启动恰好创建 1 个 Activity、且任何后续创建都会被同一个 `onActivityCreated` 回调计到；**不声称**已观察到一次强制重建。原因见第 3.11 节：`Activity.recreate()` 在 Robolectric 下会把 Compose Activity 撑爆（`OutOfMemoryError`），那个正向对照测试因此被删掉了。

### 2.3 Robolectric 离线配置

本机无法完成 TLS 证书吊销检查，Robolectric 首次运行会去 Maven Central 拉运行时 jar 并**永久挂起**（`jstack` 显示线程停在 `MavenArtifactFetcher.fetchArtifact`）。因此工程改为从本地目录解析，并开启 `robolectric.offline=true` 让"文件缺失"立即报错而不是静默联网：

```kotlin
it.systemProperty("robolectric.offline", "true")
it.systemProperty("robolectric.dependency.dir", robolectricDepsDir())
```

已预置到 `D:\robolectric-deps\`：

- `android-all-instrumented-13-robolectric-9030017-i6.jar`（155.3 MB，SDK 33）
- `android-all-instrumented-14-robolectric-10818077-i6.jar`（143.7 MB，SDK 34）

两个都需要：测试类写了 `@Config(sdk = [33])`，但 `targetSdk = 34` 会让 Robolectric 在某些路径上回落到 34。给新机器准备这两个 jar 的完整命令见 [`BUILD.md` 第 4 节](BUILD.md)。

**另外还需要 Robolectric 的原生运行时**（`@SQLiteMode(SQLiteMode.Mode.NATIVE)` 依赖它，见 3.9 节第一条）。它来自 `org.robolectric:nativeruntime:4.12.1` 与 `org.robolectric:nativeruntime-dist-compat:1.0.9`，是普通 Gradle 依赖，由 `robolectric` 传递引入，**不需要手工放 jar**——但首次解析必须能访问 Maven Central（或已在 Gradle 缓存中，本机即是后者）。若离线且缓存里没有，构建会在 `:app:testDebugUnitTest` 的依赖解析阶段直接失败，而不是静默降级成 legacy SQLite。

---

## 3. 测试过程中发现并修复的生产代码缺陷

第一轮 122 个用例最初跑出 **36 个失败**，逐个追查后 **4 个是真实的产品缺陷**（其余是测试自己的期望写错了）。后续做真机问题排查时又发现 **2 个**（3.7 的启动崩溃与 3.8 的测试标签缺失），合计 **6 个**：

### 3.1 `shortenUrl` 的长度上限形同虚设（已修）

`util/FormatUtil.kt` 旧实现先剥掉 `https://` 前缀再判断长度：

```kotlin
val clean = url.removePrefix("https://").removePrefix("http://")
return if (clean.length <= max) clean else clean.take(max - 1) + "…"
```

后果：一个 60 字符的 URL 剥掉前缀后只剩 52 字符，于是被判为"足够短"而**原样返回 60 字符**，超出调用方给的 `max`；而且短 URL 也会被无谓地剥掉协议头。已改为先判长、必要时才截断：

```kotlin
if (max <= 1) return "…"
return if (url.length <= max) url else url.take(max - 1) + "…"
```

### 3.2 单字标题的首字母占位取错（已修）

`initials("News", …)` 返回 `"NE"` 而不是 `"N"` —— 旧实现对单段文本取前两个字母：

```kotlin
parts.size == 1 -> parts[0].take(2).uppercase()
```

这会让 favicon 占位块里出现两个毫无意义的字母。已改为按词取首字母：`"Google Books" → "GB"`、`"News" → "N"`、标题为空时回落到域名（`"news.example.com" → "NE"`）。

### 3.3 国际化域名（IDN）被整条丢弃（已修）

`data/UrlTools.kt` 的 `normalizeOrNull` 用 `java.net.URI` 解析，而 `URI` 对非 ASCII 的 authority 直接拒绝：

```
roundTripPreservesAllFields 期望 imported=2，实得 imported=1
```

`https://例子.测试/page` 被判定为无效网址、计入 `skipped` 丢弃。这不只是备份问题——用户在编辑器里输入中文域名同样存不进去。已改为先经 `IDN.toASCII` 转成 punycode 再解析：

```kotlin
val asciiHost = runCatching { IDN.toASCII(hostPart) }.getOrElse { return null }
...
val asciiCandidate = if (asciiHost == hostPart) candidate else candidate.replaceFirst(hostPart, asciiHost)
```

`https://例子.测试/page` → `https://xn--fsqu00a.xn--0zwm56d/page`，这正是 WebView 与系统解析器需要的形态。新增用例 `keepsInternationalisedHostsInPunycode` 锁定该行为。

### 3.4 系统「减少动效」没有关掉背景光斑（已修）

`ui/theme/Motion.kt` 的 `MotionPresets.config` 只降低了动画强度，**没有关闭极光背景动画**：

```kotlin
// 旧：return base.copy(strength = effective.coerceIn(0f, 1f))
return base.copy(
    strength = effective.coerceIn(0f, 1f),
    backgroundAnimationEnabled = base.backgroundAnimationEnabled && !systemReduceMotion,
)
```

持续漂移的背景光斑是最典型的"非必要动效"，系统已经明确要求减少动效时它必须停；但短促的点击反馈保留（需求是**降低**非必要动效，不是删掉所有反馈）。由 `systemReduceMotionLowersonlyNonEssentialMotion` 与 `reduceMotionReachesThemeState` 两个用例锁定。

### 3.5 一次**冷构建**（`--no-build-cache`）又暴露了 15 条编译警告（已全部处理）

上面几轮的构建都是增量的，Kotlin 只重编改动过的文件，因此一大批警告被增量编译掩盖了，此前"零 warning"的观察是错的。清空 `build/` 与 `.gradle/` 后用 `--no-build-cache` 完整重编，出现 15 条警告，其中**两条是真缺陷**：

- **`BrowserScreen.kt` 的 `onOpenSettings` 参数从未被使用**——浏览器界面拿不到设置入口，等于一个"接了线却没接上"的死参数。已把设置按钮接进工具栏第二行（`TestTags.BROWSER_SETTINGS`），从网页内可直接进设置。
- **`BrowserScreen.kt` 的 `showMenu` 是死状态**——早期设计工具栏溢出菜单留下的残渣，已删除。

其余 13 条的处理：

| 警告 | 处理 |
| --- | --- |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 已废弃 | **保留并 `@Suppress("DEPRECATION")`**。它们在 API 30+ 废弃是因为默认已关闭，但 API 26–29 上这是唯一能关掉它的开关；删掉等于在仍支持的老设备上悄悄重新打开 `file://` 跨源读取 |
| `copyBackForwardList()?.size ?: 0` 冗余安全调用 | 改为 `runCatching { copyBackForwardList().size }.getOrDefault(0)`——该方法声明为非空，但 WebView 销毁后会**抛异常**而不是返回 null，守卫调用比守卫返回值更对 |
| `LumoIcons` 7 个方向性图标用了非镜像版本 | 换成 `Icons.AutoMirrored.Filled.*`（`ArrowBack`/`ArrowForward`/`OpenInNew`/`VolumeUp`/`VolumeOff`/`List`/`Sort`）。项目支持 RTL，非镜像版本在从右到左的语言里会把返回箭头指反 |
| `MainViewModel` 的 `flatMapLatest` 需要 `ExperimentalCoroutinesApi` 选择性加入 | 改为文件级 `@file:OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)`（`debounce` 与 `flatMapLatest` 都用到了，声明级注解只能覆盖其中一处） |
| `buildExtraColors` 的 `secondary` 参数从未使用 | 让它真正参与：玻璃填充叠一层极淡的次级主题色，玻璃不再是一块灰 |

**教训**：只在增量构建上看警告会误判。交付前必须做一次 `--no-build-cache` 的冷构建再确认。

### 3.6 同时修正的测试自身错误

- `JsonUtilTest` 声明了错误的 package（`data` 而类在 `util`），整个文件 18 个用例全部 `Unresolved reference`。
- `FormatUtilTest` 把 `relativeTime` 的三个本地化回调参数当字符串传（实际是 `(Long) -> String`）。
- `BackupManagerTest.suggestedFileNameMatchesPattern` 期望长度 33，实际是 **37**（`lumobrowser-backup-` 19 + `yyyyMMdd-HHmm` 13 + `.json` 5）——实现是对的。
- `UrlToolsTest` / `BackupManagerTest` 多处期望 `https://example.com/` 带尾斜杠，而纯主机名归一化后**不带**尾斜杠；`FormatUtilTest` 对 `shortenUrl` 的期望建立在 3.1 的错误行为上。
- `MotionAndThemeTest.presetsDifferInIntensity` 用 `strength` 区分三个预设是错的：`strength` 是用户的全局强度乘数，不是预设身份标识（`strengthInput = 1f` 时三个预设都返回 `1f`）。改为断言真正区分预设的量：动画时长、位移距离、起止缩放、错峰步长。

### 3.7 【严重】应用每次启动都崩溃——真机"安装成功但打不开"的根因（已修）

**这是上一版 APK 装到手机上打不开的直接原因，也是本轮最重要的修复。**

**症状**：APK 能正常安装，点图标立刻闪退，连首屏都到不了。

**根因**：`ui/components/Surfaces.kt:79` 定义了一个读取即抛错的 CompositionLocal：

```kotlin
private val LocalLumoExtra = staticCompositionLocalOf<LumoExtraColors> {
    error("LumoExtraColors read outside LumoTheme")
}
```

它的提供者 `ProvideLumoExtras`（`ui/components/Surfaces.kt:87`）**在全工程内从未被调用过**（`grep ProvideLumoExtras` 只有定义处，没有调用点）。而 `ui/LumoApp.kt:440` 有这么一行：

```kotlin
val extra = lumoExtra
```

于是**首次组合必然抛 `java.lang.IllegalStateException: LumoExtraColors read outside LumoTheme`，应用每次启动都崩溃**。复现是 100% 确定的，不是概率问题。

**为什么此前所有验证都没抓到**：那 122 个单元测试里**没有任何一个执行过 `setContent`**——没有 Activity、没有组合、没有 Compose 运行时。它们测的是网址归一化、JSON、排序、主题计算，全都是纯逻辑。所以"122 个测试全绿 + 冷构建零 error 零 warning"与"应用能不能启动"之间**没有任何因果关系**。

**修复**：把它绑到主题上，让这个错误在结构上不可表达（`ui/theme/LumoTheme.kt`）：

```kotlin
ProvideLumoExtras(
    colors = remember(resolved) {
        buildExtraColors(isDark = resolved.isDark, accent = resolved.accent, secondary = resolved.accentSecondary)
    },
) {
    MaterialTheme(colorScheme = resolved.material, typography = LumoTypography, shapes = resolved.radius.toShapes(), content = content)
}
```

用**动画后的 accent** 派生 extras，这样 extra 色组会跟着 M3 色阶一起平滑过渡（对应需求第 4 项"主题色平滑过渡"）。理由写在代码注释里：每个 surface composable 都需要 extras，交给各入口自己提供意味着**漏一次就首次组合直接崩溃**；绑到 theme 上使这个错误不可表达。

**工程里只有 3 个抛错式默认值的 `staticCompositionLocalOf`**，逐一核对过提供点：

| CompositionLocal | 定义处 | 提供处 | 状态 |
| --- | --- | --- | --- |
| `LocalLumoTheme` | `ui/theme/LumoTheme.kt:68` | `ui/theme/LumoTheme.kt:262` | ✅ 本来就对 |
| `LocalLumoStrings` | `ui/LumoStringsSnapshot.kt:187` | `MainActivity.kt:134` | ✅ 本来就对 |
| `LocalLumoExtra` | `ui/components/Surfaces.kt:79` | **无** | ❌ **本轮修复** |

**回归锁定**：`StartupSmokeTest` 在修复前 **7/7 全部以同一异常失败**，修复后 7/7 全绿。这个类现在会长期看着这条路径。

### 3.8 撤销条与设置项没有任何测试标签（已修）

`ui/TestTags.kt` 里定义了 `UNDO_BAR` / `UNDO_ACTION`（第 23–24 行），但**全工程没有任何一处应用过它们**。撤销条本身是真实存在且可用的（`ui/LumoApp.kt:327-345` 渲染 `UndoBar`，`visible = undo != null`，动作调 `viewModel.undoLastAction()`），只是没法被自动化测试定位——写成测试会得到一个"功能不存在"的假结论。

同理，`TestTags.kt:67` 的 `fun option(tag: String, value: String)`（用于设置页每个选项）**零调用**，导致设置页所有选项行（主题模式/主题风格/背景/卡片样式/按钮质感/动画预设/布局/语言/强调色）**一个标签都没有**。

**修复**（这是真实接线，不是为测试改产品语义）：

- `ui/LumoApp.kt` 的 `UndoBar` 补上 `.testTag(TestTags.UNDO_BAR)`，撤销按钮补上 `.testTag(TestTags.UNDO_ACTION)`。
- `ui/SettingsScreen.kt` 的 `ChoiceChips` 增加 `tag: String` 参数，每个 chip 打 `TestTags.option(tag, value.toString())`；`AccentPicker` 每个色板打 `TestTags.option("accent", name)`。
- **标签用枚举常量名而不是可见文案**——可见文案是本地化的，切到繁体中文或英文后标签身份就变了，测试会在某种语言下莫名失败。

### 3.9 写这批 JVM UI 测试时踩到的坑（供后续维护参考）

这些不是产品缺陷，但每一个都会让测试给出**错误的失败结论**，记录下来避免重复踩：

| 坑 | 现象 | 正解 |
| --- | --- | --- |
| Robolectric 默认的 legacy SQLite shadow | `Illegal connection pointer 1` / `Cannot perform this operation because the connection pool has been closed` 随机出现在不相干的用例上 | 加 `@SQLiteMode(SQLiteMode.Mode.NATIVE)`。原生 SQLite 没有那套"连接指针注册表"，整类噪声消失 |
| 在 `@After` 里 `database.close()` | **下一个**用例的 Activity 还握着旧 Room 单例，写进已关闭的连接池，表现为"应用默默丢失收藏"。实测：关库时 11 个里 9 个失败 | **不关库**。原生 SQLite 下单个 Room 实例跨测试方法复用完全没问题 |
| 在 `waitUntil` 的条件里 `runBlocking` | 阻塞主线程，而它等的写操作恰好跑在 `viewModelScope`（Main）上——自己饿死自己 | 后台收集进 `AtomicReference`，或者短 `runBlocking` + `Thread.sleep` 轮询 |
| `waitUntil` 的 15 s 是**虚拟时钟** | DataStore 的写跑在真实 IO 线程上，虚拟时钟可能瞬间烧完 15 s，看起来像"设置永远存不上" | 用真实时间的轮询等待；断言要读**存储本身**，而不是读某个可能还没跟上的收集器 |
| 主 Looper 的时钟不会自己走 | 搜索框有 300 ms 去抖，`waitUntil` 不推进 Looper 时钟 → 去抖永远不触发 → 搜索测试超时 | 显式 `Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))` |
| `first { 谓词 }` 在 `runBlocking` 里不返回 | 即使值已经匹配也照样挂到超时（而普通 `first()` 读得好好的） | 用普通 `first()` 取值后在循环里自己判断 |
| 测试辅助函数 `addBookmark` 返回 `storedBookmarks().first().id` | 列表是"置顶优先 + 排序号升序"，`first()` 拿到的是**最老那条**，于是加第二个收藏时拿到第一个的 id | 按标题匹配取 id |
| 设置页是长滚动面板 | 折叠线以下的 chip 没有可见边界，点上去落空，看起来像"设置没保存" | 点之前先 `performScrollTo()` |

---

### 3.10 第二轮：性能与交互审查发现并修复的 20 个缺陷

真机能启动之后，用户反馈变成「感觉卡卡的，按键没反馈不灵敏，我要你全检查下」。**这一轮的发现方式与 3.1–3.8 完全不同**：不是跑测试跑出来的，而是把一个**只读审查**按 10 个检查项（组合期读动画状态、每帧对象分配、只定义不调用的公开函数、缺 `indication` / 触摸目标 <48dp、主线程阻塞 I/O、缺稳定 key 导致动画重播、O(n²) 扫描、WebView 卡顿、滚动时发声振动、Compose 正确性）铺到全部 Kotlin 源文件上逐条核出来的。

**为什么必须静态审查**：这 20 项里没有一项会让测试失败。

- `playTap` 之类的函数**零调用点**——编译通过、测试通过、代码看起来"实现了音效"，但一次都不会执行。
- `LumoBackground` 每帧重组——没有断言能表达"这一帧做了多少工作"。
- 32dp 的命中区——`performClick()` 在测试里**永远命中**，因为它按语义节点点击，不按手指的物理面积。

| 症状 | 缺陷 | 位置 | 检测手段（为什么测试抓不到） |
| --- | --- | --- | --- |
| 卡顿 | 组合期读无限动画相位 ⇒ **每帧重组整个主屏** + 全屏 3 个大径向渐变每帧重新光栅化（每帧 10+ 次分配） | `ui/components/LumoBackground.kt` | 无断言可表达"每帧工作量"；且缺陷与背景样式无关，选纯色也每帧重组 |
| 卡顿 | 加载进度喂给 **layout** modifier（`.fillMaxWidth(progress)`）⇒ 网页加载时每帧重组**并重新 measure** | `ui/BrowserScreen.kt` | 同上；且只在加载期间发生，测试里页面瞬间"完成" |
| 卡顿 | 卡片入场动效用 `remember(bookmark.id)`，滚出视口即销毁 ⇒ **每次滚回来重播**；`alpha=0` 期间仍可点 | `ui/HomeScreen.kt` | Robolectric 里列表不会真的滚出视口 |
| 卡顿 | 拖动自动滚屏把**卡片局部坐标**当**视口坐标** ⇒ 手指刚过卡片上缘就开始滚，且返回值无上限 | `ui/components/Reorder.kt` | 需要真实手势与视口几何 |
| 卡顿 | 拖动时每个 item 每帧 `indexOfFirst` ⇒ O(n²) | `ui/HomeScreen.kt` | 收藏数少时无感，测试数据量小 |
| 按键没反馈 | `playTap/playToggle/playConfirm/playError` **全工程零调用点** | `ui/MainViewModel.kt` | 编译通过、可被调用——"存在但没人调用"是静态可查、动态不可查的 |
| 按键没反馈 | 四个共用按钮 `indication = null` ⇒ 连水波纹都没有 | `ui/components/Surfaces.kt` | 水波纹是视觉效果，无断言覆盖 |
| 按键没反馈 | 滑杆拖动过程中**每帧**发声音振动（需求明令禁止） | `ui/SettingsScreen.kt` | 需要真实手指拖动 |
| 不灵敏 | 卡片"更多"按钮命中区只有 32dp；`LumoTextButton` ≈38dp、`LumoChip` ≈33dp | `ui/components/Surfaces.kt` | **`performClick()` 按语义节点命中，无视物理命中区** —— 测试永远点得中 |
| 不灵敏 | 唤出工具栏的把手只有 56×22dp，无水波纹，失手穿透到网页 | `ui/BrowserScreen.kt` | 同上 |
| 不灵敏 | 十个主题色票是裸 30dp 圆，可见圆即命中区 | `ui/SettingsScreen.kt` | 同上 |
| 顺带 | 圆角/透明度滑杆**每拖曳帧一次 DataStore 原子写盘** | `ui/SettingsScreen.kt` | 需要真实拖动；单测直接调 setter，看不到频率 |
| 顺带 | 每张卡每次重组重建 `ImageRequest` + 重新解析 `URI(url).host` | `ui/HomeScreen.kt` | 分配量不可断言 |
| 顺带 | 浅色模式下主按钮白字压浅底（三种质感都不画实心强调色）⇒ 对比度 ≈1.1:1 | `ui/HomeScreen.kt` + `ui/theme/LumoTheme.kt` | 测试跑在深色默认下；且"看不见"不是逻辑错误 |
| 顺带 | 备份导出/导入的 JSON 序列化 + SAF 读写**全在主线程** | `ui/LumoApp.kt` | 数据量小则无感；且回调本就在主线程 |
| 顺带 | 撤销条只有一个按钮，`onDismiss` 只能**经由**撤销抵达 ⇒ 永远压在底部 | `ui/LumoApp.kt` | 测试点 Undo 就通过了，没人测"不想撤销怎么办" |
| 顺带 | 主题化 snackbar `lumoSnackbar` **从未被引用**（`SnackbarHost` 缺 content lambda）——**能编译、不生效** | `ui/LumoApp.kt` | 渲染出来的是合法 snackbar，只是样式不对 |
| 顺带 | `onVibrateRequest = { }` 把网页 `navigator.vibrate()` 接成空 lambda | `ui/BrowserScreen.kt` | 需要真机触觉才能察觉 |
| 顺带 | 同一组合内两次 `isBookmarked(...)`，每次全表线性扫描 | `ui/BrowserScreen.kt` | WebView 状态发射频率高时才明显 |
| 顺带 | 重写后遗留未使用的 `theme` 参数（编译警告） | `ui/components/LumoBackground.kt` | 这条**反而**是编译器抓到的 |

**修复后的回归**（`clean` 冷构建 + 全量单测）：

```
BUILD SUCCESSFUL in 1m 21s        Kotlin 编译 0 error、0 warning
140 用例 · 0 失败 · 0 错误 · 0 跳过    （与改动前同数，无回归）
```

> 逐条修法与代码位置见 [`../README.md`](../README.md) 第 8 节。
>
> ⚠️ **必须说清楚的边界**：第 12–20 项（写盘频率、对象复用、主线程 I/O、接线缺失）是**代码层面可判定**的缺陷；但第 1–11 项改善的是"每帧做多少工作"与"手指能不能点中"，**收益只能在设备上用帧率与命中实测来确认**。本机没有设备，所以 4.4 节的补充验证清单里，这项排在最前面。

### 3.11 第三轮：设置里切换语言卡死（无限 Activity 重建）

用户反馈（真机侧载）："打开设置切换语言时，就卡住了"——而且此后每次启动都卡。这次**没有一个测试失败过**，缺陷是顺着代码路径读出来的。

**根因是三个环节叠加**，缺任何一个都不成立：

| 环节 | 位置 | 事实 |
| --- | --- | --- |
| 偏好收集用了非空占位值 | `MainActivity.kt` | `collectAsStateWithLifecycle(initialValue = UserPreferences.Default)` |
| 占位值的语言恒为"跟随系统" | `data/Model.kt` | `val appLanguage: AppLanguage = AppLanguage.System` / `val Default = UserPreferences()` |
| 决策拿"存储值"和"组合内 `remember` 的值"相比 | `MainActivity.kt` | `var appliedLanguage by remember { mutableStateOf(prefs.appLanguage) }` |

**机制**：每个新 Activity 实例的首次组合**只看到占位值**，所以 `remember` 种下的 `appliedLanguage` 恒为 `System`；等真实偏好（例如 English）到达时判定不符 → `recreate()` → 新实例又从 `System` 重新开始。**只要存过任何非"跟随系统"的语言就永不收敛**。用户此前没改过语言，存储值恰好等于占位值，所以前两轮从未触发——这正是它躲过 140 个用例的原因：**没有任何一个用例跨越了"真实偏好到达"这一刻**。

**修法**（五处，全部落盘）：

1. `appliedLanguage` 从组合内的 `remember` 提升为 **Activity 字段**，在 `attachBaseContext` 里（任何视图存在之前）从 `LanguageMirror` 读取并记录；`null` 表示"用系统 locale"。
2. 偏好收集的初值改为 `null`（`loaded == null` 表示**尚未加载**），决策因此能区分"还没加载"和"已加载且相符"——**这正是原来不可能做出的区分**。
3. 决策收敛成一个可被测试直接钉住的纯函数：`shouldRecreateForLanguage(applied: AppLanguage?, loaded: UserPreferences?): Boolean`。
4. **先同步写镜像再重建**（`LanguageMirror.write(...)` 在 `recreateActivity()` 之前）。`attachBaseContext` 不是协程，下一次实例建立时必须已经能读到新值。
5. `LanguageMirror` 改存 `AppLanguage` 枚举名而非 `Locale`——原来 `System` 经 `toLocale()` 变成 `null`，镜像**无法区分"跟随系统"与"从未写入"**，这是回环的另一半。

**收敛性**（`recreationConverges_forEveryStoredLanguage` 对每一种语言各模拟 8 轮）：已存 English 且镜像已写 ⇒ **0 次重建**；从"跟随系统"切 English ⇒ **1 次**；切回"跟随系统" ⇒ **1 次**；冷启动时镜像尚未写入的竞态 ⇒ **1 次**（重建后镜像已写，自我修复）。

**写这个回归测试时踩到的两个坑**（都是测试基础设施问题，不是产品缺陷）：

| 坑 | 现象 | 处置 |
| --- | --- | --- |
| `ShadowLooper.idle()` 在有无限动画时**永不返回** | 主屏背景光斑每跑一帧就重排下一帧，队列永远不为空 ⇒ 自旋到 `java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49`。Compose 自己的测试规则知道"判断空闲时要忽略无限动画"，裸的 looper pump 不知道 | 改用 `ShadowLooper.idleFor(Duration)` 按**有界虚拟时间**推进 |
| `Activity.recreate()` 在 Robolectric 下**把 Compose Activity 撑爆** | 本想加一个"强制重建一次、确认计数器真的看得见"的正向对照，结果测试 JVM 直接 OOM（连测试 XML 都没产出） | **删除该对照测试**，并在测试 KDoc 里写明本测试只声称什么、不声称什么（见 2.2 节的边界声明） |

> 定位过程的两个中间误判也记在这里，避免下次重犯：①一开始以为 OOM 是测试 JVM 堆太小，加了 `maxHeapSize = "2g"` —— **没用，已撤回**（冷跑 512MB 本来就够）；②把计数器改成不保留实例、并在 `count > 1` 时提前退出 pump，才把"JVM 崩溃"还原成"可读的失败行"，从而把 OOM 精确定位到那个正向对照测试。**先让失败可读，再判断原因。**

**本轮回归**：

```
BUILD SUCCESSFUL in 3m 35s        Kotlin 编译 0 error、0 warning
147 用例 · 0 失败 · 0 错误 · 0 跳过    （原 140 + 本次新增 7）
```

> 逐条修法与代码位置见 [`../README.md`](../README.md) 第 7 节。

### 3.12 第四轮（1.02）：为浏览模式补测试时抓到的两个生产缺陷

这一轮的新功能是"四种浏览模式 + 每网址独立设置 + 浏览器兼容性"。为了不把"COMPAT 与 MOBILE 有区别"写成一句注释，我把模式配置拆成了纯函数（`profileFor` / `SiteConfig.resolve` / `planFor` / `classifyFailure` / `AutoModeState.onVerdict`），并给它们写了 76 个用例。**写完第一次跑，就抓到两个真实缺陷——都不是测试写错。**

#### 3.12.1 【严重】`WebViewFeature` 宣称支持却抛异常，整个浏览器页崩掉

**症状**：4 个用例失败，其中 3 个是完全相同的栈：

```
java.lang.UnsupportedOperationException: This method is not supported by the current version
  of the framework and the current WebView APK
  at androidx.webkit.internal.WebViewFeatureInternal.getUnsupportedOperationException(WebViewFeatureInternal.java:689)
  at androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(WebSettingsCompat.java:439)
  at com.lumobrowser.app.browser.BrowserConfigManagerKt.applySettings(BrowserConfigManager.kt:229)
  at com.lumobrowser.app.browser.BrowserConfigManagerKt.applySiteConfig(BrowserConfigManager.kt:243)
  at com.lumobrowser.app.browser.LumoWebView.applySiteConfig(LumoWebView.kt:167)
  at com.lumobrowser.app.browser.BrowserScreenKt$BrowserScreen$6$1.invoke(BrowserScreen.kt:356)
```

第 4 个失败（`StartupSmokeTest.settingsScreenComposes`）报的是 `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`——**上一个测试外泄的异常污染了它**，同一个根因。

**根因**：`WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)` 返回 `true`，紧接着 `WebSettingsCompat.setAlgorithmicDarkeningAllowed(...)` 却抛 `UnsupportedOperationException`。`androidx.webkit` 的能力查询**不是承诺**：部分 OEM 的 WebView 与单元测试用的 Robolectric stub 都会"先宣称支持、再从 setter 抛异常"。

**为什么这是真机缺陷而不是测试环境怪癖**：这段代码跑在 `AndroidView` 的 `factory` 里，也就是**浏览器页第一次组合的时候**。异常一出，整页连第一次组合都活不过去。一个**纯装饰性**的深色适配开关，在一切正常的机器上表现就是"这个页面打不开"——与用户上一轮反馈的"装得上但打不开"属于同一类。

**修法**：所有能力查询都过统一的 `featureGated(...)`：

```kotlin
private inline fun featureGated(feature: String, block: () -> Unit) {
    try {
        if (!WebViewFeature.isFeatureSupported(feature)) return
        block()
    } catch (_: RuntimeException) {
    }
}
```

保留能力查询（支持时才设，避免无谓的异常开销），另外 catch `RuntimeException`。**刻意不 catch `Throwable`**——`OutOfMemoryError`、`StackOverflowError` 是真实状况，吞掉它们只会把崩溃推迟到更难查的地方。装饰性能力失败就降级，真错误继续抛。

#### 3.12.2 每网址"请求桌面版"开关在非桌面版模式下是空操作

`BrowserConfigManagerTest.theDesktopSiteToggleLiftsMobileToADesktopUserAgent` 在 `BrowserConfigManagerTest.kt:149` 抛 `NullPointerException`（`plan.userAgentString!!` 为 null）。查下去发现是生产代码：

```kotlin
// 修前
config.desktopSiteEnabled && profile.desktopUserAgent -> desktopUserAgent(defaultUserAgent)
```

而 `SiteConfig.resolve()` 里已经有：

```kotlin
desktopSiteEnabled = desktopSiteEnabled || profile.desktopUserAgent
```

也就是说 `profile.desktopUserAgent` 的含意**早已被折进** `desktopSiteEnabled`。这个多余的 `&&` 于是不只是冗余——它把开关在 **Mobile / Compat / Auto** 三种模式下变成了**空操作**：用户在编辑页打开"请求桌面版"、保存、重新打开，发出去的仍然是手机 UA，**而诊断面板还会报告 `desktop-ua`**（因为面板读的是已经合并过的 `ResolvedSiteConfig`）。一个能打开、能保存、能显示、但什么都不做的开关。

**修法**：只判断 `config.desktopSiteEnabled` 一个标志。桌面版模式经由 `resolve()` 的合并照旧拿到桌面 UA；其余模式下用户**显式**打开时也真的会发桌面 UA——**显式的每网址选择优先于模式的默认值**。三条回归用例锁住，其中一条是反向保护（默认必须仍为关，别把每个收藏都变成桌面请求）。

#### 3.12.3 本轮的教训

一个 UI 上存在的开关，**存在本身不是它生效的证据**。这个开关从 1.02 的浏览模式功能落地起就在编辑页上，而它有一半情况是什么都不做的——直到有人为 `planFor(...)` 写了断言才暴露出来。**"用户能看见的每一个控件，都必须有一条断言穿过它。"**

另一个同源的观察：`isFeatureSupported` 这类"先问再调"的 API，**问和调之间没有原子性**，问到的答案也可能就是错的。凡是"问一次、调一次"的第三方能力查询，调用点都必须自己带降级路径。

**本轮回归**：

```
BUILD SUCCESSFUL in 4m 15s        Kotlin 编译 0 error、0 warning
223 用例 · 0 失败 · 0 错误 · 0 跳过    （原 147 + 本轮新增 76）
```

> 逐条修法与代码位置见 [`../README.md`](../README.md) 第 10 节。

### 3.13 第五轮：为了让规则可测而先改生产代码（以及迁移测试自己的三处错）

这一轮新增三个测试类（`SimplifiedEffectsTest` 10、`SurfaceElevationTest` 4、`DatabaseMigrationTest` 11），而它们各自都改动了生产代码或暴露了测试自身的缺陷——**没有一个是"顺手补几个断言"**。

**（一）"简化效果"开关是死的，根因是规则不可测。**

设置页的这个开关存进了 DataStore、也持久化了，但**全库没有任何一行读它**。真要说"哪一行错了"，一行都没错——错在**阴影规则内联在 `@Composable` 函数体里**：

```kotlin
// 改之前（Surfaces.kt，示意）：规则藏在三个 when 分支里
CardStyle.Glass -> Modifier.shadow(if (elevated) 14.dp else 0.dp)
CardStyle.Elevated -> Modifier.shadow(if (elevated) 14.dp else 6.dp)
```

想验证"打开开关后阴影是否归零"，唯一办法是**渲染整个 App 用眼睛看**。于是抽成顶层的纯函数：

```kotlin
internal fun lumoShadowElevation(style: CardStyle, elevated: Boolean, simplified: Boolean): Dp
```

`if (simplified) return 0.dp`，否则按样式给 14 / 6 / 0 dp；`lumoSurface` 的三个分支全部改调它。改完之后 4 条断言就直接钉住了规则本身。

**（二）第一版测试断言写错了，是断言错、不是规则错。**

`theSwitchIsTheOnlyThingThatFlattensAnElevatedSurface` 第一版在 `elevated = false` 的位置比对"哪些样式会塌陷"，跑红了。读断言才发现：**在 `elevated = false` 位置 `Elevated` 也会从 6 dp 掉到 0 dp**，所以三种样式全都"塌陷"，断言 `listOf(Glass, Outlined)` 自然不成立。改到 `elevated = true` 位置比对（该位置只有 `Outlined` 本来就没有阴影）之后通过。**这类失败必须逐字读断言再改，不能因为"改完就绿了"就当成修好了。**

**（三）迁移测试第一次运行：244 例 5 红，全是 fixture 的洞。**

5 条失败都在 `DatabaseMigrationTest`，逐字读 `app/build/test-results/testDebugUnitTest/TEST-com.lumobrowser.app.data.DatabaseMigrationTest.xml` 得到两类：

1. 4 条报 `java.lang.IllegalStateException: Migration didn't properly handle: bookmarks(...)`，`Expected` 里有三个索引而 `Found` 是 `indices=[]`。**根因是我手写的 v2 fixture 只下了 `CREATE TABLE`，漏了三个 `CREATE INDEX`。** `BookmarkEntity` 从 schema 1 起就声明了 `indices = [Index("sortOrder"), Index("category"), Index("isPinned")]`，Room 的 `onCreate` 一向会建它们，**所以"没有索引的数据库"这个场景在真实设备上根本不存在**——测试造了一个不存在的世界，然后报怨应用不适应它。
2. 1 条报 `expected:<[..., networkAssist]> but was:<[...]>`。**根因是 `Room.databaseBuilder(...).build()` 只构造包装器**，`RoomOpenHelper.onUpgrade`（迁移与 schema 校验都在其中）要到**第一次真正使用数据库**时才跑，所以那次测试**根本没执行迁移**。这比第 1 类危险得多：它不是"测试红了"，而是"测试**绿了但什么也没测**"。

修法：fixture 的 `ddl` 参数从 `String?` 改成 `List<String>`（CREATE TABLE ＋ 三个索引一起下）；`openWithRoom()` 显式补一句 `database.openHelper.writableDatabase` **强制打开**，并把理由写进 KDoc。

**（四）第二次运行：244 例 3 红，这次是断言把枚举当字符串比。**

三条失败：

```
migrationFrom1To3_walksBothStepsAndKeepsEveryRow            expected:<Auto> but was:<auto>
migration2To3_doesNotDisturbTheColumnsAddedByThePreviousMigration  expected:<Desktop> but was:<desktop>
migration2To3_keepsEveryRowAndDefaultsTheNewColumn          expected:<FollowGlobal> but was:<global>
```

**`BookmarkDao.getAllOnce()` 返回的是 Room 实体 `BookmarkEntity`，它的字段是存储字符串**（`"auto"`、`"desktop"`、`"global"`），不是领域枚举。而 `assertEquals` 的签名是 `(Any?, Any?)`——**拿枚举比字符串，编译期零警告，运行期必错。**

修法**刻意不是改成比字符串**（那样测试就变成"和实现一样"，实现改了测试也跟着改，什么也钉不住），而是：

- 走 `toDomain()` 断言——因为**"迁移留下了应用能用的数据"的定义就是 mapper 能把它变成正确的枚举**；
- 对"升级来的行与新插入的行无法区分"这一条，改成 `val fresh = BookmarkEntity(title = "x", url = "u")` 后比较 `fresh.networkAssist` 与每行的值，即**比较存储字节**——因为那一句问的正是磁盘上的值。

**（五）本轮回归**：

```
gradle :app:testDebugUnitTest     BUILD SUCCESSFUL in 3m 6s
263 用例 · 0 失败 · 0 错误 · 0 跳过    （原 223 + 第五轮新增 25 + 第六轮新增 15）
18 个测试类
```

**这一轮最该记住的一条**：第五轮那 248 例里新增的 25 例，有 15 例（`SimplifiedEffectsTest` + `SurfaceElevationTest`）之所以能存在，是因为**先把规则从 `@Composable` 里搬了出来**。剩下的 11 例（迁移）则证明了另一件事——**一个从来没被测过的迁移，和"没有迁移"在证据强度上是一样的**：`MIGRATION_1_2` 进过两个交付版本，但直到这一轮之前，没有任何东西验证过它真的能跑。

### 第六轮：补上「每收藏网址网络辅助偏好」的最后一段接线

第六轮把设置项补进了编辑器（此前只有浏览器内的网络面板有），顺带写下 `NetworkAssistTest` 15 例。三个教训：

1. **一个"已经接好线"的功能，可能只是接好了一半。** 每站点的网络辅助在浏览器面板里可选、能存库、浏览器也真的读它——从任何单一屏幕看都是完整的。缺的那半是编辑器：用户在收藏列表里点「编辑」，看不到这个选项，只能先打开网页再进面板改。功能没坏，但入口散落在两个地方，而"选项在哪"本身就是功能的一部分。
2. **把判断写成纯函数，测试就能覆盖全部分支而不是抽样。** `assistOfferFor(failure, assistEnabled, mode, siteFollowsGlobal, siteMode, showVpnHint, vpnConfigured)` 是七参数纯函数，15 例就把「每站点覆盖优先于全局设置」「全局关闭开关不覆盖显式站点选择」「证书失败永远不产生任何提示」这些主张变成了可断言的。其中一条专门盯着一个陷阱：`SiteNetworkAssist.FollowGlobal.asGlobalMode()` 必须返回某个值，它返回 `Silent`——如果解析逻辑偷懒用了它，而不是去读真正的全局值，那么所有"没有意见"的站点会被永久静音，从外面看和"功能没做"一模一样。
3. **同一批测试在源码树里是绿的，在冷构建里红了一例——差异不在代码，在堆。** 首次对交付包做冷构建时：

   ```
   com.lumobrowser.app.LanguageSwitchLaunchTest > launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate FAILED
       java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49
   263 tests completed, 1 failed
   ```

   单元测试跑在独立于 Gradle daemon 的另一个 JVM 里，Gradle 给它的默认堆是 **512 MB**，而 `LanguageSwitchLaunchTest` 会在 Robolectric 里把 Activity 和整棵 Compose 树真的撑起来（单跑 136 秒）。512 MB 够不够，取决于那一刻构建机还在并行做什么。修法是在 `app/build.gradle.kts` 的 `testOptions.unitTests.all { }` 里显式设 `maxHeapSize = "2g"`——**没有削弱断言，也没有把它排除出测试集**。

   这一条的教训比前两条更值得记住：**一个会因为构建机负载而随机变红的测试集，它证明的东西比报告上写的少**。报告上写着 263 例 0 失败，但那份绿是"当时机器比较闲"的绿。让测试确定性通过的方式只有两种——给它足够的资源，或者承认它测不了；第二种在这里不成立。

---

### 3.14 第七轮（2.2）：VPN 集成测试一次运行抓出三个真实产品缺陷

`VpnIntegrationTest` 是这个项目里少见的一种测试：它测的不是「功能是否按预期工作」，而是**「这个构建能不能诚实地承认它做不到某件事」**。第一次运行，22 例里 3 例红，**三例全部是产品代码的真 bug**，没有一例是测试写错。

#### 缺陷一：值里的分隔符变成了栏位分隔符

`theSeparatorInsideAValueDoesNotBecomeAField`

```
java.lang.AssertionError: expected:<VpnProfile(server=vpn|example.com, ...)> but
                     was:<VpnProfile(server=, port=0, protocol=null, displayName=, ...)>
```

`VpnProfileCodec.encode` 把 `dnsServers` / `routes` 用 `joinToString(" ")` 直接接起来，**列表条目本身没有转义**。`dnsServers = listOf("10.0.0.1|evil")` 里的 `|` 原样进了记录，`splitFields` 把它当成栏位分隔符，8 栏变 9 栏，`decode` 于是回了一个空 profile。

这个 bug 的形状值得记住：**它损坏的是使用者的端点，而不是遗失一个偏好**。一个 DNS 位址里含有分隔符的 profile 会静默地变成「什么都没配置」，而使用者看到的是「我明明填过」。

修法不是「join 之后整体转义」——那样只保护了栏位分隔符，条目自己的空格与 join 用的空格仍然无法区分，一条含空格的 DNS 会变成两台伺服器。改成**逐条目转义**（`encodeList` / `decodeList`），并给空格加了 `\s` 转义。

#### 缺陷二：`decode` 吞掉了坏值，与它自己的文档矛盾

`anUnreadableRecordReadsAsNothingConfigured`

```
java.lang.AssertionError: record=v1|host|not-a-port|wireguard|name|||1400
  expected:<VpnProfile(server=, port=0, protocol=null, ...)>
  but was:<VpnProfile(server=host, port=0, protocol=WireGuard, displayName=name, ...)>
```

`decode` 写的是 `fields[2].toIntOrNull() ?: 0`——坏掉的 port 被吞成 `0`，其余栏位照收，回传一个「半套」profile。而同一个档案的 KDoc 写的是「A record that does not parse … decodes to an empty profile」。

**八栏是一次 `encode` 描述一个端点。** 救回其中七栏，等于是发明一个使用者从来没打过、也没有任何来源的值。现在 `decode` 是严格的：port 不在 `1..65535` 就整笔拒绝，mtu 解析不出来也整笔拒绝。`decode` 只读，整笔拒绝**不会损失磁碟上的任何东西**——使用者可以回头修正，而不是被应用偷偷改成另一个端点。

#### 缺陷三：能力问题被塌缩成一个

`withoutABackendThePanelSaysNotConfiguredRatherThanDisconnected`

```java
assertFalse(report.hasProfile)   // 这个构建没有协议实现，也没有设定端点
```

`VpnReport.kt` 原本写 `hasProfile = connectability != VpnConnectability.NoProfile`。但 `VpnConnectability` **只报告第一个失败的检查**：`NoBackend` 在 profile 检查**之前**就返回了，所以 `NoBackend != NoProfile` 恒为真。

后果是：**对每一个使用「没有协议实现的构建」的人，面板都会说「你还没配置端点」。** 它把「我们做不到」说成了「你没做」。

修法是把两个问题分开：`canConnect`（这个构建有没有能力）与 `hasProfile`（有没有端点）是两个独立的问题，由呼叫端把 profile 自己的判决传进来。新参数**刻意没有预设值**——有预设值的话，未来的呼叫端会静默地拿到错答案。

> **这一轮最值得记住的一条**：这三个缺陷有一个共同形状——**应用在一个它没有能力回答的问题上，给出了一个听起来很确定的答案**。第一个说「你没配置」（其实记录读坏了），第二个说「配置好了」（其实只读了一半），第三个说「你没配置」（其实是应用没这功能）。规格 2.2 禁止伪造连接状态，而伪造往往不是从「假装连上了」开始，是从「假装知道」开始的。

#### 附带：可复用的构建教训

这一轮两次 `EXIT=1` 都不是代码问题，而是 **`:app:` 系列的 Gradle 任务被并发执行**：

```
Execution failed for task ':app:processDebugResources'.
> ... R.jar: 另一个程序正在使用此文件，进程无法访问。
Execution failed for task ':app:bundleDebugClassesToRuntimeJar'.
> java.nio.file.FileSystemException: ... classes.jar: 另一个程序正在使用此文件...
```

根因是**旧的背景构建还在跑并占着档案锁**，而它测的是早已被改动的树。这两次如果不去读日志，很容易误判成「代码改坏了」。**启动新构建前先确认旧的已结束。**

---

### 3.11 1.07：三个真实的生产代码缺陷（测试抓出来的）

**这三个都不是我「顺手发现」的，而是写测试或读断言失败结果时被逼出来的。**

#### 缺陷 1：`CrashRecord.parse` 用 `trim()` 吃掉了堆栈第一帧的缩进

`every field survives being written and read back` 报出：

```
org.junit.ComparisonFailure: expected:<[\t]at com.lumobrowser.a...> but was:<[]at com.lumobrowser.a...>
```

**报告的首行帧前的 tab 在往返之后消失了。** 原因不是我写错了 `parse` 的分区逻辑，而是两处 `joinToString("\n").trim()`：`trim()` 会去掉整段文本**首尾**的空白，而堆栈正文的第一个字符**恰好就是制表符**。于是同一份崩溃，磁盘上的文件、屏幕上的文本、复制出来的内容三者不再逐字相同——**这正是这一版最不能容忍的缺陷类型**（报告的全部价值在于它是同一个东西）。

修法是改用 `trimEnd()`（只去掉末尾换行，保留首帧缩进），并在源码里写下理由：「`trimEnd`, never `trim`: every frame line starts with a tab」。

#### 缺陷 2：广告屏蔽的路径片段规则最短长度是 1

`AdBlockRules.parse` 原本的守门条件是 `if (fragment.length > 1)`，也就是**长度 ≥ 2 就收**。于是一条 `/a` 规则（长度 2）会被诚实地存下来——而 `/a` 是**几乎每一个第三方网址**的子串。这不是一条窄规则，是一条**把网页拦烂的宽规则**。

修法：具名常量 `MIN_PATH_FRAGMENT_LENGTH = 4`（`/ads` 是最短的合理广告路径；`/ad` 会误中 `/admin/`、`/adapter`），出厂的规则文件最短片段是 `/adcall`（7），不受影响。测试同步改名为 `a path fragment too short to name an ad path is not worth blocking on`。

#### 缺陷 3：`AdBlockPolicy.hostOf` 既不认尾端根点，也不认 IPv6

两个都来自测试写完之后我自己回头读代码：

- `ads.example.com.`（**尾端根点**）是完全合法的绝对域名，也是最便宜的绕过手段——规则命中不到它，等于这个广告位被绕过。
- `[2606:4700::1111]:443` 会被 `indexOf(':')` 截成 `2606`（IPv6 字面值里的冒号比端口号先出现）。

修法：先认 `[` 并在 `]` 处收尾（未闭合则回空字符串），否则才按冒号切端口，最后 `trimEnd('.')`。同一个文件的 `siteOf` 也从「去掉最左标签」改成「从右数第二个点之后全部」——原实作让 `www.news.example.org` 与 `news.example.org` 算成**两个不同的站**，方向是**会弄坏网页的那一侧**（把第一方资源当第三方拦）。

### 3.12 1.07：一个把整套测试跑不完的 Robolectric 陷阱

`:app:testDebugUnitTest` 曾**跑 20 分钟零个测试完成**（`binary/output.bin` 是 0 字节）。用 `jcmd <pid> Thread.print` 抓到：

```
"SDK 33 Main Thread @coroutine#53" ... cpu=800640.62ms elapsed=824.33s runnable
  java.lang.ThreadLocal$ThreadLocalMap.cleanSomeSlots
  ← kotlinx.coroutines.UndispatchedCoroutine.saveThreadContext(CoroutineContext.kt:247)
  ← androidx.compose.ui.platform.AndroidUiDispatcher.performTrampolineDispatch(AndroidUiDispatcher.android.kt:81)
  ← org.robolectric.shadows.ShadowPausedLooper$IdlingRunnable.doRun(ShadowPausedLooper.java:600)
  ← ... ActivityController.visible(ActivityController.java:232)
  ← RoboMonitoringInstrumentation.startActivitySyncInternal
  ← ActivityScenario.launch
  ← com.lumobrowser.app.LanguageSwitchLaunchTest.launchWithAlreadyAppliedLanguage_...
```

**主线程自旋了 824 秒的 CPU 时间，一个测试都没跑完。** 真因是**裸 `ActivityScenario.launch` 与无限动画不能共存**：`launch` 内部走 `ActivityController.visible()` → `ShadowPausedLooper.idle()`，而主屏的漂移极光（`LumoBackground.kt:184 rememberInfiniteTransition` + `infiniteRepeatable(tween(31_000))` / `tween(47_000)`）**每跑完一帧就排下一帧**，队列永远不为空 ⇒ `idle()` 永不返回。Compose 自己的测试规则（`createAndroidComposeRule`）知道「判断空闲时要忽略无限动画」，裸的 looper pump 不知道。

`docs/TESTING.md` §3.9 早已记过这个陷阱的**另一种形态**（手写 `ShadowLooper.idle()` 时要用有界 `idleFor(Duration)` 绕开）。这次的触发点在 `launch` **内部**，绕不过去。

修法（`LanguageSwitchTest.kt`）：新增 `arrangeAmbientMotionOff()`，在 `launch` **之前**把 `backgroundGlowEnabled` 写成 `false` 并等 DataStore 收敛（`awaitPreferences`，单一 wall-clock 轮询收集器，10 秒 deadline）。**这是用户本来就有的开关，与「语言判定」这件事毫无关系**，所以它不会让测试变得不忠实。效果：这一类从「永不返回」变成 **16 秒通过**，全量套件 **27 秒**。

**留给后续维护者的教训：在 Robolectric 里启动 `MainActivity` 之前，先把环境动效关掉。** 任何「先 `launch` 再看情况」的写法都会挂在这里，而且症状是**没有输出**，不是失败。

### 3.13 1.07：两个只在「写测试」时才暴露的坑

- **Gradle Kotlin 脚本里不能写 `java.util.Properties` 的全限定名。** 我在 `app/build.gradle.kts` 里加了 Release 签名配置后，配置阶段直接失败：`e: app/build.gradle.kts:24:38: Unresolved reference: util` 与 `:26:59: Unresolved reference: load`。原因是**该文件自己的第 1-2 行就写着这条警告**——在 Gradle Kotlin 脚本中，裸的 `java.*` 引用会解析到 **Gradle 的 `java` 项目扩展**，而不是 JDK 包。正确做法是在档头 `import java.util.Properties` 然后写 `Properties()`。**我是先读到警告、后踩进坑里的**，所以这条记在这里：读到警告不等于避开了它。
- **不要用「行数」断言一段有格式的文本。** `CrashRecordTest` 的 cause 链上限测试最初写 `assertEquals(MAX_CAUSES + 1, trace.lines().size)`，得到 `expected:<7> but was:<12>`。12 的构成是：第 0 层只写一行 `(no frames: …)`（**没有表头行**——链头的类名与消息只出现在报告表头的 `exception:` / `message:` 两行里），第 1..5 层各写两行，加上一行截断标记。改成按 level 断言之后**更好**，因为它同时钉住了「链头不在堆栈正文里」这个设计：

  ```kotlin
  assertFalse("the head belongs to the record header, not to the trace: $trace", trace.contains("level 12"))
  assertTrue(trace.contains("level 11"))
  assertTrue(trace.contains("level 7"))
  assertFalse("a cause past the cap must not be reported: $trace", trace.contains("level 6"))
  ```

  另外那一例必须把 synthetic 例外的 `stackTrace` 设为 `emptyArray()`，否则 **12 000 字元上限会先咬到**，量到的就不是 cause 上限了——**一个测试要测的是哪个上限，必须让它成为第一个会触发的东西。**

---

## 4. 未能执行的测试（明确列出）

### 4.1 为什么无法执行

本机**没有可用的 Android 运行环境**，实测证据：

```
D:\AndroidDev\sdk\emulator          → 不存在
D:\AndroidDev\sdk\system-images     → 不存在
%USERPROFILE%\.android\avd          → 0 个 AVD
D:\AndroidDev\sdk\platform-tools\adb.exe → 存在，但 adb devices 无任何连接设备
```

安装模拟器需要额外下载 system image（数百 MB）并创建 AVD，本机 TLS 吊销检查不通、下载极其不稳定，且本轮的交付目标是**可安装的 Debug APK + 可构建的源码**，因此没有把时间花在搭建模拟器上。**因此下列测试没有执行，也没有任何"看起来很像通过"的替代品**：

| 未执行的测试 | 原因 |
| --- | --- |
| `connectedDebugAndroidTest`（5 个 UI 用例） | 无设备/模拟器 |
| APK 安装验证 | 无设备/模拟器 |
| 核心流程手动走查（新增→搜索→编辑→置顶→删除→撤销、WebView 导航、主题切换、语言切换、备份导入导出） | 无设备/模拟器 |
| 真机动效流畅度与帧率 | 无设备/模拟器 |
| 真机滚动时的音效/触觉"不触发"验证 | 无设备/模拟器 |
| 四种浏览模式在真实网站上的效果差异（例如某个只有桌面版能用的站点） | 无设备/模拟器，且需要一个真实会失败的网站 |
| 网页内 `target="_blank"` / `window.open` / 文件上传选择器 / 下载在真机 WebView 上的行为 | 无设备/模拟器——这些 API 在 Robolectric 下是 stub，本机无法验证 |
| Release 包的真机运行（签名与「非 debuggable」已由 1.07/1.08 的产物校验证明，见 §7.0） | 无设备；两个变体都**没有开 R8**，所以这里剩下的未知只是"真机上跑起来如何"，而不是混淆问题 |
| **1.08：网页视频能不能播放**（含自动播放开关两种状态） | 无设备/模拟器；需要真实站点与真实解码器 |
| **1.08：全屏进入/退出、旋转、渲染进程被杀后退出全屏** | 无设备/模拟器 |
| **1.08：真实站点上的广告拦截效果与误拦**（媒体豁免是否真的救回视频分片） | 无设备，且需要一个真实的、带广告与媒体分片的站点 |
| **1.08：站点暂停的"暂停 → 重载 → 恢复 → 重载"** | 无设备；Robolectric 不跑真实的 `reload()` 网络加载 |
| **1.08：导航安全四个入口在真实网页上的行为**（重定向环、弹窗洪水、Safe Browsing 命中） | 无设备，且需要构造真实的恶意页面 |
| **1.08（也是 1.07 遗留）：应用在手机上打不开的真因** | 需要那台手机的 Logcat；本机无设备、无模拟器，`adb devices` 为空 |

### 4.2 UI 测试已经写好并**通过编译**，只差一台设备

`app/src/androidTest/java/com/lumobrowser/app/LumoBrowserUiTest.kt` 包含 5 个用例，且 `:app:assembleDebugAndroidTest` 已**成功编译**（产出 `app-debug-androidTest.apk`，1003 KB），说明代码本身可编译、可运行，只是无处运行：

| 用例 | 覆盖的流程 |
| --- | --- |
| `homeScreenShowsHeaderAndEmptyState` | 首屏渲染：标题、新增入口、搜索框、空状态 |
| `addBookmarkThenFindItBySearch` | 新增收藏 → 卡片出现 → 搜索不匹配（空状态出现）→ 搜索匹配（卡片回来） |
| `editorRejectsInvalidUrl` | 输入 `javascript:alert(1)` 保存被拒、错误提示出现 |
| `deleteCanBeUndone` | 删除 → 撤销条出现 → 撤销后卡片回到列表 |
| `settingsScreenOpensAndExposesCoreSections` | 右上角菜单 → 设置页 → 返回首页 |

有设备后执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

注意 Debug 包名是 `com.lumobrowser.app.debug`，runner 会据此寻找目标应用。

### 4.3 边界说明：哪些"原本要设备才能验证"的东西，已经在 JVM 上验证了

在没有设备的前提下，第 2.2 节的两个冒烟测试类已经把下面这些**从"未验证"变成了"已验证"**——它们跑的是真实的 `MainActivity`、真实的 Compose 组合、真实的点击：

| 事项 | 无冒烟测试时 | 现在 |
| --- | --- | --- |
| 应用能不能启动 | ❌ 完全未知（于是漏掉了 3.7 的启动崩溃） | ✅ `StartupSmokeTest` 7 个用例 |
| 首屏会不会白屏/崩 | ❌ 未知 | ✅ 断言首帧能组合 |
| 点卡片能不能进浏览器 | ❌ 未知 | ✅ `tappingACard_opensTheInAppBrowser` |
| 保存的收藏能不能落库 | ❌ 未知 | ✅ 断言真的写进了 Room |
| 置顶/删除/撤销是不是真有效 | ❌ 未知 | ✅ 断言数据库状态，不只是断言 UI |
| 设置项重启后还在不在 | ❌ 未知 | ✅ 断言 DataStore，且过了 Activity 重建 |
| 每个主题色会不会点崩 | ❌ 未知 | ✅ 6 个色板全点一遍 |

**仍然必须靠设备验证、JVM 测不了的**（沿用 4.1 的清单）：WebView 的真实网络加载与渲染、`target="_blank"` / `window.open` 的真实行为、证书错误与断网错误页的实际观感、**动效流畅度与帧率**、**触摸命中率**（自动化点击按语义节点命中，物理命中区再小也点得中，所以这类缺陷测不出来）、滚动时确实不出声不振动、真机安装与冷启动首帧耗时。这些**本轮没有执行**，也不存在任何"看起来通过"的替代品。

### 4.4 建议的补充验证清单（拿到设备后）

**第二轮的性能/交互修复，收益必须靠这一组来确认**（这是本次交付最大的未验证面）：

1. **帧率实测**：用 `adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset` → 滚动主屏 10 秒 → `adb shell dumpsys gfxinfo com.lumobrowser.app.debug`，重点看 **janky frames 比例**与 95/99 分位耗时。对比第二轮改动前的 APK（若还留着）最直观。
2. **背景样式对照**：在「纯色 / 网格 / 光斑」三种背景间切换，分别滑动主屏。**修复前三种都掉帧（因为每帧重组与样式无关）；修复后纯色/网格应与静止列表一致**。
3. **触摸命中率**：刻意点卡片右上角"更多"按钮的**边缘外沿**（旧版 32dp 命中区会落空），以及浏览器顶部把手条**上下各偏 10px** 的位置（旧版 22dp 高会落空）。这两处应能稳定呼出。
4. **按键反馈**：点任一按钮听声音 + 感受振动；确认**滚动列表、拖动排序、页面加载时全程无声音无振动**（这是需求明令禁止的）。
5. **滑杆不写盘**：拖动圆角/透明度滑杆时，拇指应紧跟手指且**无卡顿**（旧版每个拖曳帧一次 DataStore 写盘）；松手后重启应用确认值已保存。
6. **浅色模式主按钮**：「新增收藏」按钮在浅色主题下应清晰可读（旧版白字压浅底，对比度约 1.1:1，几乎看不见）。
7. **撤销条**：删除一个收藏后，撤销条应能被右侧 × **直接关掉**，且不与 snackbar 叠在一起。

**第一轮及基础功能**：

8. 安装 APK，冷启动看首屏（不应有白屏闪烁，窗口背景已设为暗色）。
9. 新增收藏 → 搜索 → 编辑标题（确认卡片**不跳到列表末尾**）→ 置顶 → 取消置顶。
10. 长按卡片拖动排序，含拖到屏幕上下边缘时的自动滚动（**旧版刚过卡片上缘就会误触发**，现在应只在接近视口真实边缘时滚动）。
11. 点击卡片打开网页：验证加载进度、后退/前进可用态、刷新/停止、站内跳转、`target="_blank"` 在应用内打开。
12. 断网后打开一个网址，验证错误页文案与"重试/关闭/外部打开"三个动作；再访问一个证书有问题的站点，确认**没有"继续访问"选项**。
13. 设置里连续切换主题色，确认颜色**平滑过渡**而非突跳。
14. 语言切换为繁体中文与 English，确认整屏文案随之改变且重启后保持。
15. 导出备份 → 清空数据 → 导入（分别测"替换"与"追加"），确认收藏完整回来且可撤销；**收藏较多时导出不应冻结界面**（旧版 JSON 序列化 + 文件读写在主线程）。
16. 打开系统「开发者选项 → 动画程序时长缩放 → 关闭」，确认背景光斑停止、其余交互可用。
17. 关闭音效与触觉开关后，确认点击**完全没有**声音与振动。

**第五轮（状态栏、性能开关、网络辅助、迁移）新增的验证项**：

18. **状态栏遮挡**：把系统切到浅色、App 保持深色主题（以及反过来：系统深色 + App 浅色），确认**状态栏图标始终看得见**；再逐屏检查首页 / 编辑器 / 设置 / 浏览器的顶部，确认**没有任何内容被状态栏压住**。这一项必须**两种深浅组合都试**——本轮修的两层根因里，第一层只在其中一种组合下才显形。
19. **浏览器沉浸式全屏**：打开设置里的"浏览器沉浸式全屏"，进浏览器确认网页画到状态栏后面且**进度条仍可见、仍贴着状态栏下沿**（这是本轮踩过的坑：直接给 2 dp 的进度条套 inset 会把它的背景拉伸成整条色带）。
20. **性能开关的前后对照**（本轮唯一可量化的争议点）：`adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset` → 滚动主屏 10 秒 → `adb shell dumpsys gfxinfo com.lumobrowser.app.debug`，分别记录**关闭**与**打开"简化效果"**时的 janky frames 比例与 95/99 分位。**这是本轮唯一能给出真实前后数据的方法，本机没有执行**。
21. **网络与 VPN 辅助**：手机开飞行模式打开一个网址，确认浏览器网络面板给出的建议**与失败原因对得上**（连接类问题才提 VPN；证书问题**不提**换 UA 或绕过）；再故意连一个 VPN 后点"连接后重试"，确认重试**只发生一次**、且用尽后按钮变为禁用并说明原因；最后确认"管理 VPN"能跳到系统 VPN 画面（若该 ROM 没有此画面，应提示而**不是崩溃**）。
22. **覆盖安装保留收藏**：先在设备上装 1.00 或 1.0.1 的包并添加若干收藏，**直接覆盖安装** 1.03，确认收藏一条不少、`networkAssist` 默认为"跟随全局"。**迁移逻辑已在真 SQLite 上测过（11 例），但真机覆盖安装这一条没有执行**——测试能证明迁移语句正确，证明不了你的设备上真能装上去。

**第七轮（1.03：无障碍辅助服务）新增的验证项** —— **这一组是本版最大的未验证面**：

23. **服务能被系统看见**：安装后进「系统设置 → 无障碍 → 已安装的服务」，确认列表里**有 LumoBrowser**（标签与摘要来自三语字符串，跟随系统语言）。如果这里找不到它，后面每一项都不必测了——说明 manifest 或服务配置有问题。
   > 这是本版**唯一无法在 JVM 上验证的那一步**：59 例测试全部走 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的读写与纯逻辑，**没有任何一例真的让 Android 加载过这个服务**。`aapt2 dump xmltree` 能证明 manifest 里有这个组件，证明不了系统愿意把它列出来。
24. **打开授权 → 状态识别**：在系统设置里打开这个服务，回到 LUMO 设置页点「检查授权状态」，确认**报告为已开启**。再关掉，确认报告为关闭。**两种拼法都试一次**——有些 ROM 写入 `ENABLED_ACCESSIBILITY_SERVICES` 时用完整类名，有些用相对拼法（`.accessibility.LumoAccessibilityService`）。后者正是本版修掉的第三个缺陷：修之前，**用户已经打开了服务却被告知关闭**。
25. **真的按下一次**：在 LUMO 里声明一个目标（例如 VPN 客户端的「连接」按钮），触发一次尝试，切到那个 App。确认：**只按一次**；12 秒内没切过去则不再生效；按下后设置页报告 `Pressed`。
26. **禁止的目标确实不响**：把系统某个同意对话框上的「确定」声明成目标（`packageName` 填 `com.android.permissioncontroller` 一类），确认结果是 **`Forbidden` 而不是真的按下去**。**这一条是整组里最重要的一条**——它验证的是"权限很大但行为很小"这句话到底成不成立。
27. **关掉之后立刻失效**：在一次尝试进行中（`Watching` 状态）去系统设置里关掉服务，回到 LUMO 确认尝试已被清掉、不会在后台继续找按钮。
28. **不看输入框**：在目标 App 的某个页面打开一个文本输入框并聚焦，触发一次尝试，确认**没有读取或改动任何输入内容**（辅助服务的节点遍历显式跳过 `isEditable` 与类名含 `EditText` 的节点）。
29. **无障碍 TalkBack 描述**：开着 TalkBack 走一遍主屏 / 编辑器 / 设置页 / 无障碍面板，确认每个按钮都有可读标签、图表类装饰不被朗读、字号调到最大时**不出现文字截断或重叠**。
   > 这一条属于 2.2 遗留清单第 ⑨ 项（无障碍描述与字体缩放），**本版没有推进**，列在这里是为了不让它从清单上消失。

30. **启动动画观感**：12 例测试能证明 `startupTimeline()` 的**数学**是对的（阶段比例、边界、不低于 1 毫秒），证明不了它**看起来**对不对。冷启动看一遍三阶段过渡，确认没有跳帧或闪白。

---

## 5. 关于性能与功耗的设计性验证

这些是**设计约束 + 代码层面的保证**，不是跑出来的基准数据（无设备实测，见第 4 节）：

| 约束 | 实现保证 | 位置 |
| --- | --- | --- |
| **背景不引起重组** | 背景的漂移相位**只在 `Modifier.graphicsLayer { }` 的 lambda 里读**——只重写 RenderNode 变换，**不重组、不重绘**。相位声明为 `State<Float>` 而非 `by` 委托，就是为了让"读值"这个动作在源码里显式可见（一旦有人把它挪进组合作用域，缺陷会立刻回来） | `ui/components/LumoBackground.kt` |
| **静止背景零帧回调** | 动画关闭时**根本不创建 `InfiniteTransition`**，走静态路径用冻结相位画一次。（此前只是"冻结数值"，重组照样每帧发生） | 同上 |
| 每帧不分配 | 三个光斑**展开写**而不是 `listOf(Triple(...))`；`Brush` 只在绘制时按需构造，不做跨帧缓存也不每帧爆量分配 | 同上 |
| 背景动画不影响滚动流畅度 | 背景只用**一个 Canvas**；**刻意不使用 `RenderEffect` 模糊**（中端 GPU 上会掉帧）；漂移用 31 s / 47 s 长周期线性插值，单帧位移极小 | 同上 |
| 动画关闭时不耗电 | 见上"静止背景零帧回调"；选纯色/网格时同样没有任何动画对象 | 同上 |
| **用户能主动关掉持续成本** | 设置页的"简化效果"开关现在真的接上了（此前是**存下来但没有任何代码读**的假开关）：它一并关掉全库**唯一**的 `rememberInfiniteTransition`（极光漂移）与**两处** `Modifier.shadow(` 造成的逐 surface 离屏模糊。阴影规则提取为纯函数 `lumoShadowElevation(style, elevated, simplified)`，由 `SurfaceElevationTest` 4 例钉住 | `ui/theme/LumoTheme.kt`、`ui/components/Surfaces.kt` |
| **三个"少动一点"的开关互不替代** | 简化效果＝持续成本（耗电/掉帧）；"减少动态效果"＝跟随系统的无障碍设置（可能因晕动症打开）；动画强度/极简预设＝口味。由一个开关去改另一个的语义会让用户失去其中一项，所以三者独立，差异由 `SimplifiedEffectsTest` 10 例逐条钉住 | 同上 |
| **滚动成本不随收藏数量线性增长** | 阴影是**每个 surface 一次离屏模糊**，首页每张卡片都有一个——这是全 App 唯一随列表长度增长的每帧成本。它是自动画关闭后剩下的那一项，也正是"简化效果"开关要关的东西 | `ui/components/Surfaces.kt:200` |
| 加载进度不吃布局 | 进度条用 `Modifier.drawBehind` 在**绘制**阶段读动画值，只重绘不重组、不重新 measure（此前 `.fillMaxWidth(progress)` 是 layout modifier，加载期间整屏每帧 re-measure） | `ui/BrowserScreen.kt` |
| 列表滚回来不重播动画 | `EntryAnimationTracker` 按收藏 id 记忆是否已入场，只有首次才播；且未播过的卡片以 `appeared=true` 起步，不会出现"看不见但能点"的透明卡片 | `ui/HomeScreen.kt` |
| 拖曳不写盘 | 设置页滑杆用本地 draft，**松手才持久化**，拖动过程中零 DataStore 写入、主题树零重组 | `ui/SettingsScreen.kt` |
| 拖动排序不 O(n²) | `draggingKey` / `previewIndex` / `draggingFrom` 提升到 grid 层一次算好，列表用 `itemsIndexed` 取下标，不再每 item 每帧 `indexOf` | `ui/HomeScreen.kt` |
| 拖动自动滚屏有上限 | `autoScrollDelta` 一律 `coerceIn(-26f, 26f)`，且用 `listState.layoutInfo` 换算**真实视口坐标**（此前拿卡片局部坐标当视口坐标） | `ui/components/Reorder.kt` |
| 主线程不做 I/O | 备份的 JSON 序列化与 SAF 读写全部在 `Dispatchers.IO`；结果经 `viewModelScope` 回主线程 | `ui/LumoApp.kt` |
| 不在滚动/重绘时出声或振动 | 音效与触觉都**没有**挂到滚动、页面加载或重组上，只由明确的用户操作调用。设置页滑杆**只在 `onValueChangeFinished`** 发反馈（`onValueChange` 每帧都触发，逐帧发声振动是需求明令禁止的） | `feedback/SoundSynth.kt`、`feedback/Haptics.kt`、`ui/SettingsScreen.kt` |
| 高频操作不重复触发反馈 | 音效 45 ms、触觉 40 ms 去抖 | 同上 |
| 长时间浏览不费马达 | 触觉幅度 ≤190，且为毫秒级短波形 | 同上 |
| 不因音频失败而卡 UI | 播放走 `Dispatchers.Default` 协程，异常全部吞掉 | 同上 |
| 列表长也不卡 | 错峰入场延迟上限 8 项且只播一次；拖动时**不改动渲染列表**，只在松手时写一次库 | `ui/theme/Motion.kt`、`ui/components/Reorder.kt` |
| 拖动排序不需要第三方库 | 基于 `LazyGridState.layoutInfo` 的可见项中心判定 | `ui/components/Reorder.kt` |
| 对象复用 | 卡片的 `ImageRequest` 与 favicon URL 用 `remember(url)` 缓存，滑动时不反复构造 | `ui/HomeScreen.kt` |
| 没有二进制资源负担 | 全部图标为 vector，音效运行时合成，**APK 内无 PNG、无音频文件** | `app/src/main/res/` |

---

## 6. 隐私性验证

| 项 | 验证方式 | 结果 |
| --- | --- | --- |
| 权限最小化 | `aapt2 dump badging` | 仅 `INTERNET`、`ACCESS_NETWORK_STATE`、`VIBRATE`（另有 AGP 自动注入的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`） |
| 不参与系统云备份 | `AndroidManifest.xml` | `allowBackup="false"`、`fullBackupContent="false"`、`dataExtractionRules` 中 cloud-backup 与 device-transfer 全部 exclude |
| 不外传收藏/历史/设置 | 代码审查 | 无账号、无统计、无广告、无自建后端；唯一外发请求是 favicon 的 `icons.duckduckgo.com/ip3/<host>.ico`，只带主机名，由 Coil 缓存在磁盘 |
| 不忽略 HTTPS 证书错误 | 代码审查 + 测试 | `onReceivedSslError` 只调 `handler.cancel()`，从不 `proceed()`；`ErrorKind.Ssl.isRetryable == false`；错误页文案明确写出"不会忽略证书错误" |
| WebView 最小权限 | 代码审查 | 文件访问/内容访问/`file://` 跨源全部关闭；混合内容 `NEVER_ALLOW`；不覆写 `onPermissionRequest`（相机/麦克风/定位默认拒绝）；JS 桥只暴露 `vibrate()` 与 `hostVersion()` 两个方法 |
| 不改系统全局亮度 | 代码审查 | 只用 `window.attributes.screenBrightness`；`BRIGHTNESS_OVERRIDE_NONE` 表示跟随系统 |
| 备份文件不含内部标识 | 单元测试 | `exportedBookmarksDoNotLeakInternalIds` 断言导出内容不含 id 与时间戳 |

---

## 7. 构建产物校验
### 7.0 1.08（当前交付，versionCode 10）

#### 构建

```
cd D:\deepseek\Website\LumoBrowser
Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug :app:assembleRelease --console=plain
```

```
BUILD SUCCESSFUL in 1m 44s
```

完整日志：[`build-logs/v108-assemble.txt`](build-logs/v108-assemble.txt)（本版的编译日志是 [`build-logs/v108-compile.txt`](build-logs/v108-compile.txt)，全量测试日志是 [`build-logs/v108-tests.txt`](build-logs/v108-tests.txt)）。输出里 **0 个 `e:`、0 个 `w:`**。

**本轮的边界（与 1.06 / 1.07 相同的写法）**：这不是 `--no-build-cache` 冷构建，所以「0 个 `e:` / 0 个 `w:`」的正确读法是「**本轮改动过的文件零警告**」。让这条更强一点的证据是：本轮改动的主源码在最后一次 `:app:compileDebugKotlin` 里**真的被重新编译过**（`BUILD SUCCESSFUL in 9s`，`17 actionable tasks: 10 executed`），并且核对过时间戳——主源码集里最新的 `.class` 晚于最新的源码修改时间。另有一次**真实的编译失败**被留在 [`build-logs/v108-compile-failed-safebrowsing.txt`](build-logs/v108-compile-failed-safebrowsing.txt)：`'onSafeBrowsingHit' overrides nothing`（`onSafeBrowsingHit` 是 `WebViewClient` 的方法，第一版放进了 `LumoChromeClient`），移到 `LumoClient` 后编译通过。

#### 产物

```
--- app/build/outputs/apk/debug/app-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='10' versionName='1.08-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable      ← Debug 变体确实带这个标志
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
19,535,475 字节   SHA-256 833FEE7EA51CD2507DEF2E09A8E0EEC9911AAC1C85665FBD2854B04C7C5C911E
apksigner: exit 0 / Verifies；v2 true，证书 CN=Android Debug
           （SHA-256 ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934，与 1.05／1.07 同一张）

--- app/build/outputs/apk/release/app-release.apk ---
package: name='com.lumobrowser.app' versionCode='10' versionName='1.08'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'      ← badging 输出里没有 application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
13,182,384 字节   SHA-256 876BAFA2160AF51CB41ECC783972A3340D111C103EBF1AA97A272249835A6E42
apksigner: exit 0 / Verifies；v2 true、v1 / v3 / v3.1 / v4 false，1 个签名者
           CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN
           （SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9）
```

**Release 的证书与 1.07 是同一把**（同一个 `keystore/lumobrowser-release.jks`），所以这个包可以**直接覆盖**手机上已安装的 1.07 release，不用先卸载；Debug 包的 `applicationId` 带 `.debug` 后缀，与 Release 可共存。

两个包都复制进 `dist/`：`dist/LumoBrowser-v1.08-debug.apk`、`dist/LumoBrowser-v1.08-release.apk`。

**Release 比 Debug 小 6,353,091 字节**，原因与 1.07 相同、不是混淆（两个变体都没开 R8）：Debug 变体额外打包了 `androidx.compose.ui:ui-tooling` 与 `ui-test-manifest` 两个调试期依赖。

`zipalign -c -p -v 4` 对两个包均返回 `Verification succesful`。**16 KB 页面**这一条沿用 1.07 的替代证据并**在本版 APK 上重测**：从 `dist/LumoBrowser-v1.08-release.apk` 里取出两个 64 位 ABI 的 `libdatastore_shared_counter.so`，解析 ELF program header，`PT_LOAD` 的 `p_align` 全部是 **`0x4000`（16 KB）**——`arm64-v8a` 三个 `PT_LOAD`、`x86_64` 两个。`-P 16` 仍然**无法**用 build-tools 34.0.0 校验（`zipalign.exe` 没有 `-P` 选项，实测报 `unknown option -- P`）。（32 位 ABI 的 ELF 头布局不同，本轮的解析脚本没读对它们；这不影响结论——16 KB 页只对 64 位 ABI 有意义。）

#### 出货包内容抽查

不信"源码里有就等于 APK 里有"，所以直接从**出货的 release 包**里读：

```
& aapt.exe dump strings dist/LumoBrowser-v1.08-release.apk
  → String #1212: 安全浏览已开启：由系统按自己的清单检查每个地址，本浏览器不会覆盖它给出的警告
  → String #867 : Safe Browsing is switched on: the platform checks each address against its own list, …
（2120 条字符串资源里包含本版新增的三语 security_* 文案）

解包后的 classes2.dex 中 "NavigationSecurityManager" / "pausedEntriesFor" 命中 14 处
```

#### 单元测试（同一次交付里重跑）

```
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL in 38s
31 actionable tasks: 10 executed, 21 up-to-date
```

`app/build/test-results/testDebugUnitTest/*.xml` 聚合：**44 个测试类 / 718 个用例 / 0 失败 / 0 错误 / 0 跳过**（上一版 42 类 / 628 例）。逐类明细见下表。

| 测试类 | 用例 |
| --- | --- |
| `browser.security.NavigationSecurityTest` | 52 |
| `browser.WebNavigationPolicyTest` | 34 |
| `ui.SettingsSearchTest` | 31 |
| `browser.BrowserConfigManagerTest` | 31 |
| `browser.security.NavigationSecurityManagerTest` | 29 |
| `browser.AutoModeTest` | 28 |
| `ui.theme.MotionAndThemeTest` | 28 |
| `browser.CrossSiteLinkPolicyTest` | 27 |
| `browser.adblock.AdBlockPolicyTest` | 26 |
| `data.UrlToolsTest` | 24 |
| `accessibility.AssistTargetCodecTest` | 22 |
| `vpn.VpnIntegrationTest` | 22 |
| `accessibility.AccessibilityAssistTest` | 20 |
| `browser.adblock.AdBlockRulesTest` | 20 |
| `util.ExternalLauncherTest` | 18 |
| `data.BrowserModeTest` | 18 |
| `util.JsonUtilTest` | 18 |
| `accessibility.AccessibilityRuntimeTest` | 17 |
| `data.BookmarkRepositoryTest` | 15 |
| `browser.NetworkAssistTest` | 15 |
| `browser.ScrollMemoryTest` | 15 |
| `crash.CrashGuardTest` | 14 |
| `data.BackupManagerTest` | 14 |
| `feedback.SoundGateTest` | 14 |
| `util.FormatUtilTest` | 14 |
| `browser.adblock.AdBlockManagerTest` | 13 |
| `crash.CrashRecordTest` | 10 |
| `feedback.SoundCueTaxonomyTest` | 10 |
| `ui.theme.SimplifiedEffectsTest` | 10 |
| 其余 15 个类（`data.SettingsRepositoryTest` 12 · `browser.adblock.AdBlockResponsesTest` 12 · `ui.components.StartupTimelineTest` 12 · `AppFlowSmokeTest` 11 · `data.DatabaseMigrationTest` 11 · `ui.components.ButtonStateTest` 8 · `crash.CrashRecoveryActivityTest` 8 · `StartupSmokeTest` 7 · `LanguageSwitchDecisionTest` 6 · `util.StringResourceParityTest` 6 · `crash.SafeModeTest` 5 · `feedback.SoundLibraryTest` 5 · `ui.components.SurfaceElevationTest` 4 · `CrashRoutingTest` 1 · `LanguageSwitchLaunchTest` 1） | 109 |

> 上表 29 类合计 609，其余 15 类合计 109，**总计 44 类 / 718 例**——每个数字都直接来自 `app/build/test-results/testDebugUnitTest/*.xml`，不是手抄估算。

#### 交付物哈希

`dist/SHA256SUMS.txt` 在打包源码 ZIP 之后重算，**本次共 20 条**（未变的条目与上一版逐字相同），本版新增：

```
833FEE7EA51CD2507DEF2E09A8E0EEC9911AAC1C85665FBD2854B04C7C5C911E  LumoBrowser-v1.08-debug.apk
876BAFA2160AF51CB41ECC783972A3340D111C103EBF1AA97A272249835A6E42  LumoBrowser-v1.08-release.apk
（源码包的哈希同样登记在这个文件里，但**不在这里复述**）
```

### 7.0a 1.07（versionCode 9，保留原文）

#### 构建

```
cd D:\deepseek\Website\LumoBrowser
Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug :app:assembleRelease --console=plain
```

```
BUILD SUCCESSFUL in 2m 26s
APP_EXIT=0
```

日志：本次未单独留档（同一次调用的完整输出见工作目录的 `_v107_build_apks1.txt`），**Debug 与 Release 在同一个 Gradle 调用里构建**，输出里 `0 个 e:`、`0 个 w:`。

**这一轮同样没有做 `--no-build-cache` 冷构建**（与 1.06 相同的边界）：`0 个 e: / 0 个 w:` 应读作「**本轮改动的代码零警告**」，而不是「整棵源码树零警告」。唯一让这条更强一点的证据是：本版改动了 `app/build.gradle.kts`（新增签名配置与 `import java.util.Properties`），**Gradle 配置阶段被重新执行过一遍**，并且我在这一轮真的踩到并修掉了一次配置阶段失败（`Unresolved reference: util`，见 §3.13）。

#### 产物

```
--- app/build/outputs/apk/debug/app-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='9' versionName='1.07-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
19,416,196 字节   SHA-256 39B472D87C0832652C9246CED158D27D594F43568619BF22ED4B9FB9A7B276C2
apksigner: v2 true，证书 CN=Android Debug（SHA-256 ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934，与 1.05 同一张）

--- app/build/outputs/apk/release/app-release.apk ---
package: name='com.lumobrowser.app' versionCode='9' versionName='1.07'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'      ← 无 application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
13,099,148 字节   SHA-256 4AD270C838863BAA2EE72C00073D7A5E230ADBAAF3E53355D1079FD49057424E
apksigner: v2 true，证书 CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN
           （SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9）
```

两个包都复制进 `dist/`：`dist/LumoBrowser-v1.07-debug.apk`、`dist/LumoBrowser-v1.07-release.apk`。

**Release 比 Debug 小 6,317,048 字节**，原因不是混淆（两个变体都没开 R8）：Debug 变体额外打包了 `androidx.compose.ui:ui-tooling` 与 `ui-test-manifest` 两个调试期依赖。这是 1.07 能够回答「是不是 ROM 拦了 debuggable 应用」的那半个答案。

`zipalign -c -p -v 4` 对两个包均返回 `Verification succesful`；**16 KB zip 对齐（`-P 16`）无法用 build-tools 34.0.0 校验**（`zipalign.exe` 没有 `-P` 选项，实测报 `unknown option -- P`），所以这一条改用**自解析 ELF program header** 的替代证据：APK 内四个 ABI 的 `libdatastore_shared_counter.so`，其 `PT_LOAD` 的 `p_align` 全部是 `0x4000`（16 KB）。

#### 交付物哈希

`dist/SHA256SUMS.txt` 本次由脚本重算，共 **17 条**（未变的 14 条与上一版逐字相同），新增：

```
39B472D87C0832652C9246CED158D27D594F43568619BF22ED4B9FB9A7B276C2  LumoBrowser-v1.07-debug.apk
4AD270C838863BAA2EE72C00073D7A5E230ADBAAF3E53355D1079FD49057424E  LumoBrowser-v1.07-release.apk
（源码包的哈希同样登记在这个文件里，但**不在这里复述**）
```

> **源码包的字节数与 SHA-256 故意不写进本文档**：`docs/TESTING.md` 自己就在源码包内，写进去会**自我指涉**——每次改这句话都会改变压缩包，从而让刚写下的哈希立刻作废。所以**唯一权威的哈希清单是压缩包之外的 [`dist/SHA256SUMS.txt`](../dist/SHA256SUMS.txt)**（这也是 README 第 1 节的同一约定）。本文档只保证：那个 zip 是**在本文档最后一次修改之后**才打包的，因此它里面装的就是当前这份文档。

源码包 **267 个条目**（1.06 为 239 档），由 `tools/package_source.ps1` 的 allowlist 生成；已核对包内**不含** `build/`、`dist/`、临时日志与 APK，且**含** 4 个 `crash/` 源文件、4 个 `crash/` 测试类、`app/src/main/assets/adblock/rules.txt`、`keystore/`（2 档）与 32 个 `docs/` 条目。打包后逐条比对过 5 份文档在包内与磁盘上的字节数完全一致（`README.md`、`CHANGELOG.md`、`docs/TESTING.md`、`docs/BUILD.md`、`docs/build-logs/README.md`）——**这句不写具体数字，理由同上**。

### 7.0a 1.06（versionCode 8，保留原文）

#### 构建

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug --console=plain
```

```
BUILD SUCCESSFUL in 17s
40 actionable tasks: 9 executed, 31 up-to-date
ASSEMBLE_EXIT=0
```

日志：[`docs/build-logs/v106-assemble.txt`](build-logs/v106-assemble.txt)。

**这一轮没有做 1.05 那样的 `clean --no-build-cache` 冷构建，而这句话是本节的边界，不是脚注。** 所以「零警告」这一条在 1.06 的强度**低于** 1.05，必须写清楚低在哪里：

- 本轮真正跑过的任务里有 `> Task :app:compileDebugKotlin`（**不是** `UP-TO-DATE`）、`:app:compileDebugJavaWithJavac`、`:app:dexBuilderDebug`、`:app:mergeProjectDexDebug`、`:app:packageDebug`、`:app:assembleDebug`、`:app:kspDebugKotlin`、`:app:generateDebugBuildConfig`；其余任务的状态是 `UP-TO-DATE` 或 `NO-SOURCE`。**编译器确实看过本轮改动的每一个 Kotlin 文件**——这 25 个改动文件全部落在 `app/src/main` 与 `app/src/test` 的 Kotlin 源集里，没有一个文件是靠缓存跳过编译的。
- 但增量编译只重编**改动的文件及其依赖**。一个从未被本轮碰过的文件里如果本来就有一条警告，这轮不会重新打印它。1.05 的 `--no-build-cache` 把整棵源码树重新编了一遍，所以它那一次是真的可以无条件说「0 条」；1.06 这一轮的 `0 个 e:` / `0 个 w:` 应当读作「**本轮改动的代码零警告**」。**没有做就是没有做。**
- `classes.dex` 合计 **60,733,592 字节**（1.05 是 60,730,168），分布在 12 个 dex 文件里，`classes.dex` 一个占 43,380,024 字节。

AGP 的 buildconfig 弃用警告照旧存在、照旧未处理（与 Kotlin 无关，1.04 起就有）：`WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.`

#### 产物

```
package: name='com.lumobrowser.app.debug' versionCode='8' versionName='1.06-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

**19,356,507 字节**，写出于 `2026-10-11 02:41:02`，SHA-256 `12C0E108715182AD491BFABD4195E283848B3768CA3D2F67ED94802524BD63F2`，复制为 `dist/LumoBrowser-v1.06-debug.apk`。

`apksigner verify --print-certs --verbose` 的实测结果：**v2 签名方案 true，v1 / v3 / v3.1 / v4 均 false，1 个签名者，`CN=Android Debug`**（证书 SHA-256 `ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934`，与 1.05 是同一张调试证书）。**这是 Debug 签名的测试包，不是正式发布签名**——它的签名方案组合与证书都由 Android SDK 的调试密钥决定，签名配置见 [`BUILD.md`](BUILD.md) §6。

#### 单元测试

```
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --console=plain
```

```
BUILD SUCCESSFUL in 10m 46s
31 actionable tasks: 10 executed, 21 up-to-date
TESTS_EXIT=0
```

日志：[`docs/build-logs/v106-tests.txt`](build-logs/v106-tests.txt)。结果：**33 个测试类 / 527 个用例 / 0 失败 / 0 错误 / 0 跳过**（上一版 32 类 505 例）。

这一轮新增的是 `ExternalLauncherTest` **18 例**，而它的存在本身就是一条判断：**1.05 交付了 505 个测试，没有一个调用过 `ExternalLauncher.build`**——修好的那条路径当时没有被任何用例走过，所以它能不能用是没人知道的。除了新增，两处计数变化是 `CrossSiteLinkPolicyTest` 25 → 27、`SettingsRepositoryTest` 10 → 12。

例数不是抄进文档的：`_v106_logs.py` 在生成日志时当场数 `app/build/test-results/testDebugUnitTest/` 下的 **33 份** JUnit XML，把 `tests` / `failures` / `errors` / `skipped` 四个属性逐文件相加，任何一个非零就拒绝写这一段。

#### 发布校验

`_verify106.py` 输出 `VERDICT: OK -- nothing to report`。1.05 的脚本只做「该有的东西在不在」，1.06 补上了两件相反方向的事：**验证被删掉的东西真的没了**，以及**验证出厂的那份测试文件里真的写着那条回归**。「文件存在」和「文件里有那个回归」是两个不同的主张，而这个版本的教训正是「一个看起来在检查、其实永远为假的判断」。逐条结果：

| 检查 | 结果 |
| --- | --- |
| `dist/SHA256SUMS.txt` 登记 16 个文件、逐个实测 | 16 / 16 通过，0 个不符 |
| 源码包条目 / 音效素材 | 239 条目、76 个 `.wav`、19 事件 × 4 风格 |
| 源码包 REQUIRED 清单（含两份 1.06 构建日志、三份 1.06 文档、`ExternalLauncherTest.kt`） | 缺失 0 |
| **出厂的 `ExternalLauncherTest.kt` 内容探针**（两处根因的回归、chooser extras、`MATCH_DEFAULT_ONLY`、`addUnstartableHandler`） | 5 / 5 逐字命中 |
| 三份 `strings.xml` | 各 428 条、`<string` 开闭配对、名称集合三份完全相同 |
| APK 条目 / `classes.dex` | 575 条目 / 60,733,592 字节 |
| APK `resources.arsc` | 245,228 字节；`external_link_app_none` / `external_link_app_chooser` / `external_link_unusable` 全部命中 |
| **反向检查：1.06 删掉的那三个字符串键** | `privacy_note` / `category_privacy` / `privacy_note_body` 在 `resources.arsc` 里**全部 GONE** |
| **APK 里的新类 `com/lumobrowser/app/ui/HandoffGate`** | 命中（1.06 唯一新增的类，就是「确认面板点第二次不得启动第二次」那道闸门） |
| **MainActivity 的 `android:theme`** | `0x7f100115` → `style/Theme.LumoBrowser.Starting`（经 `aapt2 dump resources` 解析，不是猜名字） |
| 启动主题三个关键 item | `postSplashScreenTheme`、`windowSplashScreenBackground`、`windowSplashScreenAnimationDuration=400` 全部命中 |
| 应用主题 `android:windowBackground` | `@color/lumo_backdrop` |
| 无障碍服务四项 manifest 探针 | 全部 True |
| **`QUERY_ALL_PACKAGES`** | **不存在**。1.06 删掉 `canHandle()` 的理由就是「包管理器查询答不了『有没有应用能处理』」，而拿这个权限去把一句话说得更满，是拿隐私换文案 |
| APK 自己声明的版本戳 | `com.lumobrowser.app.debug` / `versionCode=8` / `versionName=1.06-debug` |

**这一轮的校验脚本也造出过一次假警报**，而且是同一类病：`aapt2` 在 Windows 上输出 CRLF，而探针里的 `resource (0x[0-9a-f]+) (style/\S+)$` 用了 `$` 锚点，于是**每一个 id 都解析成 `None`**，`MainActivity android:theme = None`——一个「查不到」被打印出来，看起来像 APK 有问题。修法是先把输出里的 `\r\n` 规整成 `\n`，并把这段经过写进脚本注释。§0D.7 记的是 1.05 的两次同类事件，这是第三次。

#### 源码包

`dist/LumoBrowser-v1.06-source.zip`，**239 个文件**（1.05 是 236）；含 76 个音效素材、14 份构建日志、三份 1.06 新文档（[`EXTERNAL_BROWSER_POLICY.md`](EXTERNAL_BROWSER_POLICY.md)、[`WEB_NAVIGATION.md`](WEB_NAVIGATION.md) 的 1.06 修订版与本文档）。

**压缩包的字节数与 SHA-256 都不写在这里**——本文件本身就在压缩包内，写进去会自我指涉、永远对不上（这一条是 1.05 那一轮用一次真实的过期数字换来的，见 §7.1）。精确值统一公布在压缩包之外的 [`dist/SHA256SUMS.txt`](../dist/SHA256SUMS.txt)。

### 7.1 1.05（上一版，versionCode 7）

#### 构建（`--no-build-cache`）

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' clean :app:assembleDebug --no-build-cache --console=plain
```

```
BUILD SUCCESSFUL in 36s
41 actionable tasks: 40 executed, 1 up-to-date
ASSEMBLE_EXIT=0
```

日志：[`docs/build-logs/v105-assemble.txt`](build-logs/v105-assemble.txt)。

**这是本套交付里第一次完全绕开 Gradle 构建缓存。** 1.04 的 `clean :app:assembleDebug` 里有 `16 from cache`——`clean` 删得掉 `app/build`，删不掉构建缓存；输入哈希没变就复用输出，这是正确行为，但它让「零警告」这句话需要限定：**没跑过的文件不会打印警告**。1.05 加上 `--no-build-cache` 之后，`41 actionable tasks` 里 **40 个真的执行**，没有任何任务的输出是从缓存取来的，于是：

- **Kotlin 警告 `w:` = 0 条，错误 `e:` = 0 条**，在最终源码上从头编译取得，不需要任何限定语。
- `classes.dex` 合计 **60,730,168 字节**（1.04 是 60,662,048），分布在 12 个 dex 文件里，`classes.dex` 一个占 43,380,024 字节。

AGP 的 buildconfig 弃用警告原样记录、本轮未处理（与 Kotlin 无关，1.04 起就存在）：

```
WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.
```

#### 产物

```
package: name='com.lumobrowser.app.debug' versionCode='7' versionName='1.05-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

**19,340,939 字节**，写出于 `2026-10-11 01:24:18`，SHA-256 `D7894D077D3AF692989A131EBCD99428EFE8313E4F473F42D9AD5BA74F009BCB`。Debug 签名（`CN=Android Debug`），**不是正式发布签名**。

#### 单元测试

```
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --no-build-cache --console=plain
```

```
BUILD SUCCESSFUL in 22m 51s
31 actionable tasks: 14 executed, 17 up-to-date
TESTS_EXIT=0
```

日志：[`docs/build-logs/v105-tests.txt`](build-logs/v105-tests.txt)。结果：**32 个测试类 / 505 个用例 / 0 失败 / 0 错误 / 0 跳过**（上一版 29 类 460 例；新增 `CrossSiteLinkPolicyTest` 25 例、`SoundGateTest` 14 例、`StringResourceParityTest` 6 例）。

这一轮是增量构建（`14 executed, 17 up-to-date`），所以它证明的是「测试全绿」，**不是**「零警告」——但零警告已经由上面那次 `--no-build-cache` 的装配构建独立证明。两份日志各自负责自己那一半，没有互相借用。

例数不是抄进文档的：`_verify105.py` 在写报告时当场数 `app/build/test-results/testDebugUnitTest/` 下的 32 份 JUnit XML，把 `tests` / `failures` / `errors` / `skipped` 四个属性逐文件相加，任何一个非零就拒绝写这一段。

#### 发布校验

`_verify105.py` 输出 `VERDICT: OK -- nothing to report`。它比 1.04 的校验脚本多读一样东西：**`resources.arsc`**。理由是 1.05 有两样交付物**没有自己的 class**——启动主题（含 `values-night` 孪生）与 15 条新字符串；它们可以在每个 Kotlin class 都在、每条单测都过的情况下**缺席于 APK**，因为单测读的是源码树而不是构建产物。逐条结果：

| 检查 | 结果 |
| --- | --- |
| `dist/SHA256SUMS.txt` 登记 14 个文件、逐个实测 | 14 / 14 通过，0 个不符 |
| 源码包条目 / 音效素材 | 236 条目、76 个 `.wav`、19 事件 × 4 风格 |
| 源码包 REQUIRED 清单（含三份 1.05 文档与四个新测试类） | 缺失 0 |
| 三份 `strings.xml` | 各 431 条、`<string` 开闭配对、名称集合三份完全相同 |
| APK 条目 / `classes.dex` | 575 条目 / 60,730,168 字节 |
| APK `resources.arsc` | 246,048 字节；启动主题名与 1.05 字符串键全部命中 |
| **MainActivity 的 `android:theme`** | `0x7f100115` → `style/Theme.LumoBrowser.Starting`（经 `aapt2 dump resources` 解析，不是猜名字） |
| 启动主题三个关键 item | `postSplashScreenTheme=@style/Theme.LumoBrowser`、`windowSplashScreenBackground=@color/lumo_backdrop`、`windowSplashScreenAnimationDuration=400` 全部命中 |
| 应用主题 `android:windowBackground` | `@color/lumo_backdrop` |
| 无障碍服务四项 manifest 探针 | 全部 True |

**这一轮校验脚本自己造出了两次假警报**，两次都记在 §0D.7——因为「一个看起来在检查、其实永远为假的判断」正是 1.03 那一版的教训，而它在 1.05 又出现了两次。

#### 源码包

`dist/LumoBrowser-v1.05-source.zip`，**236 个文件**（1.04 是 226）；含 76 个音效素材、12 份构建日志、三份 1.05 新文档。**压缩包的字节数与 SHA-256 都不写在这里**——本文件本身就在压缩包内，写进去会自我指涉、永远对不上。（这一节的第一稿确实写了字节数，随后 README 与本文档本身又改了两轮、重新打包，那个数字当场就过期了，正好把这个道理演示了一遍。）精确值统一公布在压缩包之外的 [`dist/SHA256SUMS.txt`](../dist/SHA256SUMS.txt)。

### 7.2 1.04（versionCode 6）

#### 构建

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' clean :app:assembleDebug --console=plain
```

```
BUILD SUCCESSFUL in 1m 8s
41 actionable tasks: 24 executed, 16 from cache, 1 up-to-date
EXIT=0
```

日志：[`docs/build-logs/v104-assemble.txt`](build-logs/v104-assemble.txt)。

`:app:kspDebugKotlin` 与 `:app:compileDebugKotlin` 两行都没有 `FROM-CACHE` / `UP-TO-DATE` 后缀——**它们真的执行了**。这一点必须写清楚，因为 `16 from cache` 里的资源与 dex 合并任务是从 Gradle 构建缓存恢复的（`:app:clean` 删了 `app/build`，但构建缓存仍在，输入哈希一致就复用输出，这是正确的）。**源码到产物之间唯一关键的那一步——Kotlin 编译——是真正跑过的**，所以：

- **Kotlin 警告 `w:` = 0 条，错误 `e:` = 0 条，且在最终源码上从头编译取得。** 这是 1.04 唯一的决定性零警告证明。测试那轮（`v104-tests.txt`）是**增量**构建（`10 executed, 21 up-to-date`），Kotlin 不会重新打印未改动文件的警告，所以它只能证明「测试全绿」，不能证明「零警告」。
- `classes.dex` 60,662,048 字节。

另有一条与 Kotlin 无关的项目配置警告，如实记录、本轮未处理：

```
WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.
The current default is 'false'. It will be removed in version 9.0 of the Android Gradle plugin.
```

#### APK

| 项目 | 值 |
| --- | --- |
| 文件 | `dist/LumoBrowser-v1.04-debug.apk` |
| 大小 | 19,310,815 字节 |
| SHA-256 | `68b494fc56e822b443c8e8a2f3d342700de4541d8dd2b82022f8016832c442e3` |
| 包名 | `com.lumobrowser.app.debug`（debug 变体，`applicationIdSuffix = ".debug"`） |
| versionCode / versionName | `6` / `1.04-debug` |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| 原生 ABI | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |
| APK 内条目 | 570 |
| APK 内 `res/raw` 音效 | 76 个 `.wav`（19 cues × 4 textures） |

**这是 Debug APK，用 Android 的 debug keystore 签名，仅供个人测试安装，不是正式签名版本。** 换一台机器重新构建会得到功能等价但哈希不同的包（DEX 里的 `BuildConfig.BUILD_TIME`、zip 条目时间戳与签名块每次都会变）。

#### 源码包

| 项目 | 值 |
| --- | --- |
| 文件 | `dist/LumoBrowser-v1.04-source.zip` |
| 条目数 | 226（1.03 是 209） |
| 内含音效 | 76 个 `.wav`，解析出 19 cues × 4 textures |
| 内含构建日志 | `docs/build-logs/` 下 2 份新版 + 8 份留档 |
| 排除 | `build/`、`.gradle/`、`.kotlin/`、`.idea/`、`dist/`、`local.properties`，以及 `.txt`/`.apk`/`.aar`/`.log`/`.zip`/`.iml` 等（`docs/build-logs/` 下的 `.txt` 例外保留） |

**这个压缩包的精确字节数与 SHA-256 不写在本文件里**——本文件自己就在压缩包里，写进去会自我指涉、永远对不上。精确哈希统一公布在压缩包之外的 [`dist/SHA256SUMS.txt`](../dist/SHA256SUMS.txt)。

#### 三语言字符串

| 文件 | `<string>` 开 / 闭 / 名称 | 行尾 |
| --- | --- | --- |
| `app/src/main/res/values/strings.xml` | 416 / 416 / 416 | CRLF |
| `app/src/main/res/values-zh/strings.xml` | 416 / 416 / 416 | CRLF |
| `app/src/main/res/values-zh-rTW/strings.xml` | 416 / 416 / 416 | CRLF |

三份的名称集合**完全相同**。1.04 删掉了 1.03 遗留的两条死字符串 `browser_address` 与 `browser_loading`（各 418 → 416），删后三份仍然相等，且全树 `dead=0`（每一条字符串 id 都至少有一处引用）。

> **这两张表和下面的数字是 1.04 的留档，不是当前值。** 1.05 各增加到 **431**，见 §7.1。

#### 单元测试

```
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --console=plain
```

```
BUILD SUCCESSFUL in 11m 2s
31 actionable tasks: 10 executed, 21 up-to-date
EXIT=0
classes=29  tests=460  failures=0  errors=0  skipped=0
```

逐类计数（从 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 逐文件聚合，不是从日志文本里数出来的）：

| 测试类 | 例数 | | 测试类 | 例数 |
| --- | --- | --- | --- | --- |
| `AppFlowSmokeTest` | 11 | | `data.BrowserModeTest` | 18 |
| `LanguageSwitchDecisionTest` | 6 | | `data.DatabaseMigrationTest` | 11 |
| `LanguageSwitchLaunchTest` | 1 | | `data.SettingsRepositoryTest` | 10 |
| `StartupSmokeTest` | 7 | | `data.UrlToolsTest` | 24 |
| `accessibility.AccessibilityAssistTest` | 20 | | `feedback.SoundCueTaxonomyTest` | 10 |
| `accessibility.AccessibilityRuntimeTest` | 17 | | `feedback.SoundLibraryTest` | 5 |
| `accessibility.AssistTargetCodecTest` | 22 | | `ui.SettingsSearchTest` | 31 |
| `browser.AutoModeTest` | 28 | | `ui.components.ButtonStateTest` | 8 |
| `browser.BrowserConfigManagerTest` | 30 | | `ui.components.StartupTimelineTest` | 12 |
| `browser.NetworkAssistTest` | 15 | | `ui.components.SurfaceElevationTest` | 4 |
| `browser.ScrollMemoryTest` | 15 | | `ui.theme.MotionAndThemeTest` | 28 |
| `browser.WebNavigationPolicyTest` | 34 | | `ui.theme.SimplifiedEffectsTest` | 10 |
| `data.BackupManagerTest` | 14 | | `util.FormatUtilTest` | 14 |
| `data.BookmarkRepositoryTest` | 15 | | `util.JsonUtilTest` | 18 |
| | | | `vpn.VpnIntegrationTest` | 22 |
| | | | **合计** | **460** |

1.04 的 **29 类 / 460 例** 对比 1.03 的 **25 类 / 372 例**；本轮新增 `browser.WebNavigationPolicyTest`（34）、`ui.SettingsSearchTest`（31）、`ui.components.ButtonStateTest`（8）。

#### 交付校验

`_verify104.py`（在项目目录之外，不随源码包交付）对 `dist/` 做终检：

```
VERDICT: OK -- nothing to report
  checksum file: entries=12 checked=12 bad=0
  source zip: entries=226 wavs=76 cues=19 textures=4
  source zip: REQUIRED missing = 0 (none)
  STRINGS ok  values/strings.xml           open=416 close=416 names=416
  STRINGS ok  values-zh/strings.xml        open=416 close=416 names=416
  STRINGS ok  values-zh-rTW/strings.xml    open=416 close=416 names=416
  STRINGS name sets identical across all three files
  apk: entries=570 classes.dex bytes=60662048 res/raw wavs=76
  apk: required classes in classes.dex:
  apk: sfx cues=19 textures=4
  apk: res/xml entries matching 'accessibility' = ['res/xml/lumo_accessibility_service.xml']
  apk: manifest declares the accessibility service             = True
  apk: manifest requires BIND_ACCESSIBILITY_SERVICE            = True
  apk: manifest has the service intent-filter action           = True
  apk: manifest points at the accessibility service config     = True
```

其中「required classes in classes.dex」在 1.04 做过一次**刻意的放宽**：1.03 探的是 `L<路径>;`，而 1.04 新增的 `WebNavigationPolicy.kt`、`IntentUrl.kt`、`ExternalLinkSheet.kt` 以顶层函数为主，类型落在 `<文件名>Kt`，裸 `L<路径>;` 会把**已经打进包里的类**误报成缺失。1.04 改为探裸路径子串——这个检查要抓的是「整个文件没被编译进去」，子串骗不过它；而假缺失等于报告一个不存在的缺陷，本项目已经为这种误报耗掉过一轮（见 1.03 的 README §16.4）。



### 7.3 1.03（versionCode 5）

```
文件名      app-debug.apk（交付为 dist/LumoBrowser-1.03-debug.apk）
大小        18.33 MB（19,218,591 字节）
SHA-256     0B5A1FC4BAB84282A95144B8413DD04F7C35447626F61445170E9A3DB00B5C60
            （Debug 包逐次重新签名，每次构建哈希都会变；
              这个值对应 dist/ 里那一个文件，详见 dist/SHA256SUMS.txt）
版本        versionCode=5  versionName=1.03-debug
构建        gradle clean :app:testDebugUnitTest :app:assembleDebug --no-daemon
            → BUILD SUCCESSFUL in 16m 37s（日志 c30.txt）
            这是一次 **clean 重建**（不同于 2.2 的增量构建），
            单元测试与 APK 在同一次调用里跑完：372 例 0 失败，
            并且构建日志里的 Kotlin 警告数为 0。
            产物从 app/build/outputs/apk/debug/ 复制到 dist/，字节数与哈希均未改动。
签名        apksigner verify --print-certs → Signer #1
            DN: C=US, O=Android, CN=Android Debug
            证书 SHA-256 ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934
            （与 1.02 / 2.2 同一个 Debug 证书，可互相覆盖安装）
            注意：这是 **Debug** 证书，不是发布签名，不可用于正式分发
包结构      570 个条目 / classes.dex 60,490,544 字节 + resources.arsc +
            AndroidManifest.xml + lib/{arm64-v8a,armeabi-v7a,x86,x86_64}
            资源表 res/raw/ 下 76 个 .wav

aapt2 dump badging
            package: name='com.lumobrowser.app.debug' versionCode='5'
                     versionName='1.03-debug'
            sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
            native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'

新增组件    service com.lumobrowser.app.accessibility.LumoAccessibilityService
              android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
              <intent-filter> android.accessibilityservice.AccessibilityService
              <meta-data android:name="android.accessibilityservice"
                         android:resource="@xml/lumo_accessibility_service" />
            经 aapt2 dump xmltree 确认（AndroidManifest.xml 第 122 行起）。

            这一条专门列出来，是因为**发布校验脚本第一次跑时把它报成了缺失**：
            _verify103.py 用 UTF-8 字节去搜一个字符串池是 UTF-16 的二进制 XML，
            于是「服务已正确声明」被读成「服务不存在」。修法是同时搜两种编码。
            一个永远返回 false 的检查，看起来和「一切正常」没有任何区别。

classes.dex 实测含以下 14 个类（_verify103.py 逐条搜 DEX 类型描述符）：
  1.03   accessibility/LumoAccessibilityService    无障碍服务本体
         accessibility/AccessibilityAssistRuntime  进程级尝试状态机
         accessibility/AccessibilityAssistKt       decideAssistAction + 标签匹配
         accessibility/AssistTarget                用户声明的目标
         accessibility/AssistOutcome               七种结果
         accessibility/AccessibilityStatus         启用状态识别
         accessibility/AssistSettings              偏好 → 运行时快照
         accessibility/AssistTargetCodec           行格式编解码
  v2.2   vpn/LumoVpnService · vpn/VpnController · vpn/VpnAppDirectory
         ui/components/StartupAnimationKt
         feedback/SoundSynth · feedback/SoundLibrary
```

**交付校验脚本 `_verify103.py` → `VERDICT: OK`**，它逐条检查而不是声称：

| 检查 | 结果 |
| --- | --- |
| `dist/SHA256SUMS.txt` 重建后逐条回读 | 10 个文件，`bad=0` |
| 源包必需新档（12 条 1.03 + 5 条 v2.2 + 76 个 wav） | 缺失 0 |
| 三份 `strings.xml` 的标签配平与条数 | 各 `open=371 close=371 names=371`，无 `U+FFFD` |
| 三份 `strings.xml` 的名称集合 | 完全一致 |
| APK `classes.dex` 里的 14 个类 | 全部 FOUND |
| APK 资源表 `res/xml/lumo_accessibility_service.xml` | 存在 |
| APK manifest 声明服务 / 要求 `BIND_ACCESSIBILITY_SERVICE` / intent-filter / meta-data | 四项全 True |

> 为什么校验脚本要数「371 条字符串」和「名称集合一致」：这两个文件在 1.03 期间**被写坏过一次**（PowerShell 的 `Get-Content -Raw` 对无 BOM 的 UTF-8 用了 ANSI 码页，吃掉了每个多位元组字符后面的那个 ASCII 字符）。修复过的东西，正是最该继续被检查的东西。标签不平衡会让 aapt2 报错（这是好的失败）；但**三份文件名称集合不一致不会报任何错**，只会让界面按语言逐条回退、显示成两种语言混在一起。

### 7.4 v2.2（versionCode 4）

```
文件名      app-debug.apk（交付为 dist/LumoBrowser-2.2-debug.apk）
大小        18.27 MB（19,154,932 字节）
SHA-256     1938A655B49023EA1D92BA1CA70AF8A06FE38A275B2286DF8C886617AB0DB5D8
            （Debug 包逐次重新签名，每次构建哈希都会变；
              这个值对应 dist/ 里那一个文件，详见 dist/SHA256SUMS.txt）
版本        versionCode=4  versionName=2.2-debug
构建        gradle :app:assembleDebug --no-daemon → BUILD SUCCESSFUL in 41s
            （日志 b22.txt；产物从 app/build/outputs/apk/debug/ 复制到 dist/，
              字节数与哈希均未改动）
签名        apksigner verify --verbose --print-certs → Verifies
            v2 方案通过（v1 / v3 / v3.1 / v4 均 false）
            签名者 CN=Android Debug，证书 SHA-256
            ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934
            （与 1.02 同一个 Debug 证书，可与 1.02 / 1.0.1 互相覆盖安装）
            注意：这是 **Debug** 证书，不是发布签名，不可用于正式分发
包结构      568 个条目 / classes.dex 60,363,736 字节 + resources.arsc +
            AndroidManifest.xml + lib/{arm64-v8a,armeabi-v7a,x86,x86_64}

新增权限    FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
            （声明给 LumoVpnService。本版 TunnelBackends 为空，服务一定在
              startForeground() 之前就以 NoBackend 自停，所以这三条实际从未被请求；
              声明它们是为了让 manifest 对「有 backend 时这个类会做什么」保持诚实）
新增组件    service com.lumobrowser.app.vpn.LumoVpnService
              android:exported="true"          ← 必需：系统以不同 UID 解析这个 component
                                                  来回答 VpnService.prepare()，非 exported
                                                  会「明明没授权却回报已授权」
              android:permission="android.permission.BIND_VPN_SERVICE"
                                                ← 真正的闸门是这条 signature 权限
              android:foregroundServiceType="specialUse"
                                                ← Android 14 的 FGS 类型没有 VPN 成员
              <intent-filter> android.net.VpnService
            未加 android:process —— 服务通过 process 级的 VpnTunnelStatus 上报，
            切独立进程会静默断掉这条通道

内容实测    res/raw/ 下 76 个 .wav（19 事件 × 4 风格）；
            classes.dex 含 vpn/LumoVpnService、vpn/VpnController、vpn/VpnProfileCodec、
            vpn/VpnAppDirectory、ui/components/StartupAnimationKt、feedback/SoundSynth、
            feedback/SoundLibrary
            ——最后这两项由发布校验脚本实测，不是声称：

              源码树   res/raw/*.wav = 76
              源码包   189 个条目，res/raw/*.wav = 76，cues=19 textures=4
              APK      资源表 res/raw/*.wav = 76，cues=19 textures=4
              且 7 个 v2.2 类名全部在 classes.dex 中命中

            76 个生成素材正是那种「悄悄没打进包、编译照样通过、装上也照样能开，
            只是全程静音」的东西，所以三处各数一遍，数不上就拒绝发布。

未做        本版**没有**重做「源码包解开重新冷构建」的二次验证。1.02 做过
            （D:\LumoBrowser-102-coldbuild3），2.2 没有。这一条记在
            本节与第 4 节，没有当成做过。

文件名      app-debug-androidTest.apk（UI 测试包，编译产物，未运行）
大小        1,027,292 字节（与 1.02 相同 —— 本轮未改动 instrumented 测试源集）

文件名      LumoBrowser-2.2-source.zip（交付为 dist/LumoBrowser-2.2-source.zip）
大小        约 0.84 MB —— **精确字节数不在此写死**，理由见本节末尾
文件数      189（= 1.02 的 94 + 76 个音效素材 + 3 份新文档 + 2 个音效工具脚本
            + 本轮新增的 Kotlin 源与测试）
            包根目录为 LumoBrowser/，与工程目录同名
            打包脚本对 REQUIRED 清单与「76 个 wav / 19 cue / 4 texture」做硬断言，
            缺任何一项就 raise，不产出压缩包
```

### 7.5 1.02（留档）

```
文件名      app-debug.apk（交付为 dist/LumoBrowser-1.02-debug.apk）
大小        17.52 MB（18,377,227 字节）
SHA-256     E365F2A2DB1115651CAFCB1D2DC7EBC917817F90B06DF356E3C6C90C3F4163EE
            实测：dist/ 里的文件就是源码包冷构建
            （D:\LumoBrowser-102-coldbuild3）产出的 app-debug.apk，
            复制过去时字节数与哈希均未改动
版本        versionCode=3  versionName=1.02-debug
签名        v2 方案通过，签名者 CN=Android Debug（同上）
包结构      classes.dex ~ classesN.dex + resources.arsc +
            AndroidManifest.xml + lib/{arm64-v8a,armeabi-v7a,x86,x86_64}

文件名      LumoBrowser-1.02-source.zip
大小        约 0.40 MB —— **精确字节数不在此写死**
文件数      94（含 1.02 新增的 BrowserModeTest.kt / AutoModeTest.kt /
            BrowserConfigManagerTest.kt / SimplifiedEffectsTest.kt /
            SurfaceElevationTest.kt / DatabaseMigrationTest.kt / Insets.kt /
            NetworkLabels.kt / NetworkStatus.kt / NetworkMonitor.kt / VpnAssist.kt /
            PageFailure.kt / CHANGELOG.md / docs/BROWSER_MODES.md /
            docs/RELEASE-1.02.md，并排除全部 .txt 构建日志与 dist/ 自身）
```

（交付物的 SHA-256 统一公布在压缩包之外的 dist/SHA256SUMS.txt——
 写在本文件里会自我指涉：本文件本身就在压缩包内。上面只给出 APK 的哈希，
 因为 APK 不在压缩包内；源码包自身的精确字节数与哈希会随着本文件的
 每次修改而变化，所以一律不在此处写死。）

```
package: name='com.lumobrowser.app.debug' versionCode='4' versionName='2.2-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'（zh / zh-TW 同名）
launchable-activity: com.lumobrowser.app.MainActivity
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: android.permission.INTERNET
uses-permission: android.permission.ACCESS_NETWORK_STATE
uses-permission: android.permission.VIBRATE
uses-permission: com.lumobrowser.app.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
                 （AndroidX 自动注入，非本应用声明）
```

> 权限清单**在本轮没有增加**。网络与 VPN 辅助功能是**解释层**：应用永远不自己建立 VPN 隧道，只读取系统网络状态、并在用户已经配好 VPN 时提示"连上后重试"。隧道本身在 Android 自己的 VPN 画面里启动。

交付物哈希可以自行复核（不改动任何文件，`-Recurse` 覆盖 `previous-1.0.1/`）：

```powershell
cd D:\deepseek\Website\LumoBrowser
Get-ChildItem dist -Recurse -File | Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    ForEach-Object {
        $rel = $_.FullName.Substring((Resolve-Path dist).Path.Length + 1) -replace '\\', '/'
        "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $rel
    }
# 与 dist\SHA256SUMS.txt 逐行比对（本轮已实测：6 条全部 OK，0 条不一致）
```

> `dist/` 顶层是 1.02 的两个交付物；上一版（1.0.1 / `versionCode=2`）的两个文件在 `dist/previous-1.0.1/` 留档。`dist/SHA256SUMS.txt` **登记全部 6 个文件**（含留档的旧包与本轮之前的一版 `LumoBrowser-v2-*`），所以不会出现"下错包"的情况；本轮实测逐条比对通过。各版本使用同一张 Debug 证书（`CN=Android Debug`），因此可以互相覆盖安装。

**签名声明**：该 APK 使用 Android 默认的 **Debug 签名**（`~/.android/debug.keystore`），**不是正式发布签名**，不可用于分发。Release 构建的签名配置方法见 [`BUILD.md` 第 6 节](BUILD.md)。

哈希对应本文档撰写时最后一次构建；重新构建后哈希会变化，请以自己构建出的文件为准。

### 0H.8 Logcat 检查（第二轮走查）

本版运行期间（安装 → 逐项走查 → 反复切换布局 → 进出二级页）按下列模式过滤 Logcat：

```
adb logcat -d -t 400 | Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime|ANR'
```

结果：**没有匹配到本应用的崩溃或 ANR 记录**。同一次过滤里出现过的只有系统其它组件的历史行，不属于本包。

这一项在 §0H.1 的 H18 里原先记作「未执行」，第二轮补上了；与它配套的还有一条：本轮**没有**主动制造崩溃去验证崩溃自述页（H19 仍是未执行）。

### 0H.9 布局切换会画出过渡帧（第一轮的直接观察）

在默认动画设置下，点击「分类栏显示位置」开关并**立即**（约 150 ms 后）截图，拍到的既不是纯顶部布局、也不是纯左侧布局，而是两者同时在画面上的中间帧。这与 `AnimatedContent` 的写法一致：旧布局 `fadeOut + shrinkHorizontally`、新布局 `fadeIn + expandHorizontally`，两者用同一个 `tween` 并行跑完。

**这一观察能证明的只有「过渡帧确实存在」**，不能证明时长。时长那一栏的证据是配置值加单元测试（§0H.1 的 H14）。

### 0H.10 搜索词 + 分类筛选同时生效（交集）

要求方给的清单里有一条是「搜索与分类筛选同时工作」。这一条第一轮漏了，第二轮补齐，走法如下：

| 步骤 | 操作 | 观察结果 |
| --- | --- | --- |
| 1 | 在左栏选中「未分类」，搜索框输入 `esimcard` | 「Nothing matched」，副标题提示清除分类筛选 |
| 2 | 清空搜索（点搜索框右侧的清除按钮），搜索框回到占位文案 | 字段读回为空字符串（`uiautomator` 读 `EditText` 的 `text`） |
| 3 | 选中「未分类」，输入 `esim` | 仍然「Nothing matched」——该分类下确实没有匹配项 |
| 4 | 保持搜索词 `esim`，改选分类 `esim` | 结果**恰好一张卡**：`#esim` |

第 3 步与第 4 步成对，说明两条筛选是**交集**而不是「后一条覆盖前一条」：同一个搜索词下，换一个分类就从 0 条变成 1 条。

顺带记录一条 **harness 上的坑**，不是应用的问题：左侧栏在软键盘弹出时会缩短、条目重新排布，所以固定的 `adb shell input tap` 坐标在输入法开合前后会指向不同条目。本轮几次「点错分类」都是这个原因，正确做法是每次交互前用 `uiautomator dump` 重新量一次 `bounds`。

### 0H.11 系统动画关闭时的行为

`WindowUtil.isSystemReduceMotionEnabled()` 读的是平台动画缩放：`TRANSITION_ANIMATION_SCALE` 或 `ANIMATOR_DURATION_SCALE` 为 `0` 就算「系统要求减少动态效果」。设备上的验证：

| 步骤 | 命令 / 操作 | 观察结果 |
| --- | --- | --- |
| 1 | `adb shell settings put global transition_animation_scale 0.0` | 读回 `0.0` |
| 2 | `am force-stop` 后重启应用（该标志只在建主题时读一次） | 应用正常启动 |
| 3 | 进设置打开开关，回到收藏页 | 左侧栏**已经就位**；在开关点击后约 60 ms 截到的帧里，设置页那一行仍显示旧状态、画面没有出现中间布局 |

第 3 步与 §0H.9 的对照是这一节的全部信息量：**同一台设备、同一个开关，动画开着时能拍到中间帧，动画关掉后拍不到**。时长本身的数字仍然以配置值（`duration(base) = base × strength`，reduce-motion 时 `strength` 上限 0.35）为准。

走查结束后已把系统设置恢复：`transition_animation_scale=1.0`、`window_animation_scale=1.0`。

### 0H.12 音效与触觉：只做了代码层核对（**未做设备验证**）

要求方把「音效与触觉」列为独立一项。本轮在这一项上**只做了代码核对**，没有在设备上听到或感受到任何一次反馈，原因与边界如下：

- 1.09 **没有新增任何音频或触觉代码**。把 `app/src/main/java/com/lumobrowser/app/feedback/` 与 1.08 源码包逐文件比对：6 个文件，**0 个新增、0 个删除、0 个修改**。
- 分类栏选中时的反馈走的是既有通路：`HomeScreen` 里 `LocalLumoFeedback.current` 的 `confirm()`，即 `SoundCue.Confirm`（`SoundCueFamily.Outcome`）。它在 `LaunchedEffect(selected)` 里触发，且只在选中项**真的换了**的时候触发——同一个分类连点两次不会响两次。
- 开关归属：`SoundCueFamily.Outcome` 属于「无独立开关」的三类之一（`Pointer` / `Navigation` / `Outcome`），只跟随总开关 `soundEnabled`；有三个独立开关的是 `PageLoading` / `Vpn` / `Launch`。「分类切换」因此**不需要也不应该**再加一个开关，这与要求方「已有项要复用、不要重复造」一致。
- 触觉侧 `Haptics.perform()` 自己会先 `vibrator.hasVibrator()` 再决定要不要振，不支持的设备自动降级，且不额外申请权限。

**设备上未验证的具体内容**（如实记录）：总开关关掉后是否真的静音、选中分类是否真的出声、触觉是否真的振、静音模式与音频焦点是否被正确遵守。这一轮的模拟器没有用来听声音，也没有专门检查振动器状态。

### 0H.13 快速反复切换开关（部分执行）

同一轮里连续点击开关 6 次（每次间隔约 300 ms），之后退出并重启应用：左侧栏按存储值正确出现，**分类条目没有重复、没有错位**，条数与入库的 9 个（`All` + `未分类` + 7 个分类）一致。

**没做的是**：在 6 次点击的过程中逐帧确认没有闪烁或半渲染状态。这一条只记录了「收敛后的结果是正确的」。

### 0H.14 第二轮走查的构建与数字（`docs/build-logs/v109r6-build.txt`）

| 项目 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | **45 类 / 729 例 / 0 失败 / 0 错误 / 0 跳过** |
| `:app:assembleDebug` | `app-debug.apk` **20,067,046 字节** |
| `:app:assembleRelease` | `app-release.apk` **13,199,620 字节** |
| 同一次调用 | `BUILD SUCCESSFUL in 2m 6s`，exit 0 |

比第一轮的 728 例多出的那一例是 `MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand`，它锁的是「三个预设的 UI 过渡时长都在 120–300 ms 区间内」。

### 0H.18 第二轮：开关的持久化是**读存储**验的，不是看界面

前面几轮反复出现同一个问题：界面上那个开关的读数不可靠——盲点坐标会落到别的控件上，截图又常常拍在过渡中间。所以第二轮改成**直接读应用自己的存储**：`files/datastore/lumo_settings.preferences_pb`，就是 `SettingsRepository` 真正写入的那个文件。解码工具是 `D:\deepseek\Website\_v109h\show_prefs.py`（不依赖 protobuf 库）。

| 步骤 | 动作 | 文件里的 `category_rail_placement` | 界面 |
| --- | --- | --- | --- |
| 1 | 起始状态 | `'Top'` | 顶部横向分类条 |
| 2 | 点开关一次 | `'Left'` | —— |
| 3 | 连点 6 次（每次间隔 220 ms） | `'Left'` | 左侧竖向栏，9 个条目无重复、8 张卡片齐全（见 §0H.19） |
| 4 | 再点一次 | `'Top'` | 开关画成关闭态 |
| 5 | 再点一次 | `'Left'` | 左侧竖向栏 |

第 4 步与第 5 步成对，是这一节的关键：**同一个开关在两个方向上都写进了存储，而且写入的值与界面上看到的状态一致**（关 → `Top` + 开关关闭态；开 → `Left` + 左侧栏）。§0H.4 那条「`force-stop` 后仍是左栏」因此有了机制上的解释：值确实在文件里，重启是把它读回来。

同一次读出的另外两个键：`drag_hint_shown = 1`、`sound_enabled = 0`。后者说明**走查过程中把界面音效总开关关掉过**——这正是 §0H.21 没有变成 ✅ 的原因。

### 0H.19 左栏在设备上的真实内容（9 条目 / 8 卡片 / 无重复）

左侧栏模式下的整屏截图（同一帧内）读数：

| 观察项 | 结果 |
| --- | --- |
| 左栏条目，自上而下 | `All`、`Uncategorised`、`API`、`Wi-Fi 6`、`averyverylongcategoryname`、`esim`、`充值`、`学习`、`生活记录`——**9 个**，与「全部 + 未分类 + 7 个分类」一致，**没有重复条目** |
| 选中态 | `All` 有选中底色与左侧指示条，其余为未选中样式 |
| 两字中文 | `充值`、`学习` 各占两行（逐字） |
| 三字中文 | `生活记录` 一行放下（`未分类` 同理）——说明「逐字竖排」只对会挤不下的名字生效 |
| 超长拉丁名 | `averyverylongcategoryname` 换行占三行，未溢出、未遮挡 |
| 右侧网格 | 8 张卡片全部可见：`Study…`、`esim …`、`API r…`、`Top-up`、`Router`、`Daily`、`Uncategorised one`、`Long category`，各自带 `#分类` 标签与溢出菜单 |

这一帧同时也回答了 H15 的一部分（超长名、空分类、中英混合同时成立），但**没有**回答 72/108 dp 边界与横屏。

### 0H.20 布局切换的时长：**设备上没测成，如实记录**

这一项试了三次，三次都没拿到可用读数。写出来是为了避免下一个人重复走这三条路：

| 尝试 | 做法 | 结果 |
| --- | --- | --- |
| A | 点击开关后立刻 `screencap` | 拍到过一张过渡帧（§0H.9），但 `screencap` 一次要几百毫秒，两帧之间动画已经跑完，**量不出时长** |
| B | `adb shell screenrecord` 录下切换瞬间 | 能录到，但模拟器的编码器丢帧严重：一次切换只落盘 4–15 帧，且这一轮**所有** `screenrecord` 调用都只录到 0.4–1.7 秒就结束（`--time-limit 6`/`10` 都没用满）。帧序里能看到「左栏高亮像素 72,359 → 64,224」这样的一次性跳变，但**中间帧缺失**，用这种采样去报一个 300 ms 的数字是不诚实的 |
| C | 把 `animator_duration_scale` 调到 5 倍再录 | 应用确实跟着变慢了（界面上能看出更长的过渡），但 `screenrecord` 的丢帧问题一模一样，仍然只有十几帧 |

**结论：时长这一栏的证据是配置值加单元测试，不是设备测量。** 三个预设 300 / 250 / 140 ms、全部落在 120–300 区间，由 `MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` 锁死；设备上确认的只有「过渡帧存在」（§0H.9）和「系统动画关闭时不存在」（§0H.11）。走查结束后已把三个动画缩放恢复为 `1.0`。

### 0H.21 界面音效与触觉：这一轮**没有**验证（H22 保持未执行）

第二轮尝试过在设备上听一次音效，但没有做成，具体卡在哪：

- 读存储时发现 `sound_enabled = 0`——**走查过程中把总开关关掉了**，而关掉的状态下「听不到声音」什么都证明不了。
- 要在模拟器上把这件事做实，需要：打开总开关 → 触发一次分类切换或删除 → 从 `adb shell dumpsys audio` 或音频焦点日志里确认有一次播放，并证明关掉开关后**没有**。本轮没有走到这一步，所以不写「音效正常」。
- 代码层的核对（1.09 没有新增任何音频/触觉代码、`confirm()` 走的是既有 cue、`Outcome` 类只跟随总开关、`Haptics.perform()` 自检 `hasVibrator()`）见 §0H.12。

### 0H.15 交付源码包的独立冷构建（r6，`docs/build-logs/v109r6-zipverify.txt`）

交付前把**要交出去的那个 zip**解到工作树之外（`D:\deepseek\Website\_zipverify109\LumoBrowser`），只补一个 `local.properties`，然后整套跑一遍：

| 项目 | 结果 |
| --- | --- |
| 命令 | `gradle --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` |
| 结果 | `BUILD SUCCESSFUL in 6m 40s`，**exit 0**（100 个任务：66 执行、34 来自缓存） |
| 单元测试 | **45 类 / 729 例 / 0 失败 / 0 错误 / 0 跳过**——与源码树里的合计**完全相同** |
| 冷构建出的 release APK | **13,199,620 字节**，与交付的 `dist/LumoBrowser-v1.09-release.apk` **字节数相同** |
| 冷构建出的 debug APK | 19,552,711 字节（与交付的 20,067,046 不同，原因见 §0H.7：debug 包的重打包与 zipalign 填充） |

这一条是本次交付真正依赖的断言：**源码包不需要作者工作树里的任何东西，就能把 release 包构建出来。**

### 0H.15a 「字节数相同」不等于「逐字节相同」——把差别写清楚

冷构建出的 release 包与交付的那个都是 **13,199,620 字节**，但 SHA256 不同。逐条目比对之后差别是**三处**：

| 条目 | 结果 |
| --- | --- |
| 条目总数 / 条目名列表 | 567 / 567，**列表完全一致** |
| `classes.dex` | CRC 相同（`fc1fc146`，31,618,552 字节） |
| `AndroidManifest.xml` | CRC 相同（`ecd99897，11,872 字节） |
| `resources.arsc` | CRC 相同（`0741e812`，249,892 字节） |
| `classes2.dex` | **内容不同** |
| `assets/dexopt/baseline.prof` | **内容不同** |
| `assets/dexopt/baseline.profm` | **内容不同** |

也就是说：**清单、资源、主 dex 完全一致，差别集中在第二个 dex 与 dexopt 基线剖面**——那是 R8/D8 每次构建不保证逐字节复现的部分。冷构建出的那个 release 包里，本轮的 7 个标记同样**全部命中**（dex 合计 44,824,744 字节）。

所以本文件里凡写「release APK 与交付件字节数相同」的地方，说的都是**大小相同**，不是字节相同。真正说明「包里有没有本版代码」的是 §0H.16 的标记检查，不是哈希相等。

### 0H.15b 返工后重做的冷构建（r7，交付的那个 zip 本身）

返工改了源码，所以 §0H.15 / §0H.15a 的冷构建**重做了一遍**，而且这次要验证的正是 `dist/` 里那个新包：

| 项目 | 结果 |
| --- | --- |
| 被验证的包 | `dist/LumoBrowser-v1.09-source.zip`（299 个条目；字节数与哈希看 `dist/SHA256SUMS.txt` 与 `dist/pack-report.txt`，本文档不抄——理由见 §0H.17） |
| 解到哪里 | `D:\deepseek\Website\_zipverify109c\LumoBrowser`（工作树之外），只补一个 `local.properties` |
| 命令 | `gradle --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` |
| 结果 | `BUILD SUCCESSFUL in 2m 52s`，**exit 0**（100 个任务：66 执行、34 来自缓存；比 r6 的 6m 40s 快是因为 Gradle 构建缓存这次命中了） |
| 单元测试 | **45 类 / 729 例 / 0 失败 / 0 错误 / 0 跳过**——与源码树里的合计完全相同（r6 那次冷构建少 1 例，是 §0H.5 那个不稳定用例当次没被计入） |
| 冷构建出的 release APK | **13,199,620 字节**，与交付的 `dist/LumoBrowser-v1.09-release.apk` 字节数相同 |
| 冷构建出的 debug APK | 19,552,711 字节（与交付的 20,067,566 不同，原因见 §0H.7） |

逐条目比对后，冷构建的 release 包与交付件**只差三处**，形状与 §0H.15a 完全一样：567 个条目名一致，`classes.dex`、`AndroidManifest.xml`、`resources.arsc` 的 CRC 相同，不同的是 `classes2.dex`（`f4acaa90` → `183b5ed8`）、`assets/dexopt/baseline.prof`（9,790 → 9,780 字节）与 `assets/dexopt/baseline.profm`。结论不变：**大小相同不是字节相同**，R8/D8 的输出不保证逐字节复现。

### 0H.16 出货 APK 的 dex 标记（返工后复检）

四个 APK（源码树 debug/release + `dist/` debug/release）逐个把 `classes*.dex` 拼起来搜索本轮的标记：

| 标记 | 四个包 |
| --- | --- |
| `goBackKeepingPosition` | ✅ 全部命中 |
| `goForwardKeepingPosition` | ✅ 全部命中 |
| `category_rail_placement` | ✅ 全部命中 |
| `setting_category_rail` | ✅ 全部命中 |
| `HISTORY_RESTORE_POLL_MS` | ✅ 全部命中 |
| `isCjkOnly` | ✅ 全部命中 |
| `railLabel` | ✅ 全部命中 |
| `categoryRailWidth`（返工新增，用来确认包里的左栏宽度是固定值那一版） | ✅ 全部命中 |

共 **8 个标记 × 4 个包 = 32 次命中，0 次未命中**。dex 字节数：debug 61,094,548、release 44,824,200。这一遍是把 APK 当 zip 读、在 Python 里按 `latin-1` 解码 dex 后做子串搜索（`_v109h/dex_markers.py`），不依赖 `jar`/`unzip` 这些外部命令。

### 0H.17 交付件清单

| 文件 | 说明 |
| --- | --- |
| `dist/LumoBrowser-v1.09-debug.apk` | 调试包，`versionCode=11` / `versionName=1.09-debug`，包名 `com.lumobrowser.app.debug` |
| `dist/LumoBrowser-v1.09-release.apk` | 发布包，`versionCode=11` / `versionName=1.09`，与 1.08 同一签名证书（`SHA-256 digest: 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9`），可直接覆盖升级 |
| `dist/LumoBrowser-v1.09-source.zip` | 完整源码包（299 个条目），已用独立冷构建验证可自足构建（§0H.15b） |
| `dist/SHA256SUMS.txt` | 上面三个文件加 1.08 三个交付件的 SHA256 |
| `dist/pack-report.txt` | 打包时的自检输出（条目数、字符串条数、音效文件数） |

**哈希一律以 `dist/SHA256SUMS.txt` 为准，本文档不抄具体数值。** 原因不是偷懒，是抄了会错：源码包里带着 `docs/`，而本文档自己就在 `docs/` 里，所以「把哈希写进文档 → 重新打包」会把刚写下的那个哈希改掉。这一点是**实测**的，不是推理——

| 尝试 | 结果 |
| --- | --- |
| 打包 → 把哈希写进 0H.17 → 再打包 | 文档里留下的哈希**与新包不再匹配**（`BC42A01B…` 对不上重建后的包） |
| 改用一个新文件名（`-FINAL`）再走一遍 | 仍然对不上：重新打包让包体从 1,519,085 变成 1,519,248 字节，哈希随之改变 |
| 返工这一轮改成「文档里不写源码包的字节数，只写条目数」之后 | 仍然改了三次包体：`2,273,829` → `2,276,085` → 最终值，每次都是因为文档被改过。最后把 CHANGELOG 与 §0.0 表里残留的字节数也一并换成「看 `SHA256SUMS.txt`」 |

结论：**一个包含自己的归档无法在自己的内容里写出自己的哈希。** 所以哈希放在包**外面**的 `SHA256SUMS.txt` 里，本文档只说明去哪里看；源码包的**字节数**同理，它也会因为文档改动而变化，值得记的是**条目数**（299）与**能不能自足构建**（§0H.15b）。APK 不受这条限制（它们不包含文档），所以字节数照写，但也统一登记在 `SHA256SUMS.txt` 里，避免两处数字各自漂移。

`dist/` 里同时留了 1.08 的三个交付件（`LumoBrowser-v1.08-*.{apk,zip}`），这样「从 1.08 升级到 1.09」这件事有真实的上一版可比对。

### 0H.22 返工：布局被退回后重做的结构（`docs/build-logs/v109r7-build.txt`）

第一版交付后用户退回了左侧栏的排布。返工的内容与它自己的验证记录：

| 项 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` | `BUILD SUCCESSFUL in 46s`，exit 0；**45 类 / 729 例 / 0 失败 / 0 错误 / 0 跳过**（数字取自 `app/build/test-results/testDebugUnitTest`，不是控制台输出） |
| 结构改动 | `HomeScreen` 由「整页一个 `Row`」改成「`Column`：整行标题区 `HomeContentHeader` + `weight(1f)` 的 `AnimatedContent` 二选一」。标题与搜索框因此不再参与排布切换，任何模式下都是满宽、单行 |
| 左栏宽度 | 由 `((maxWidth − cardGap) × 0.26).coerceIn(72.dp, 108.dp)` 改为固定 `LumoSpacing.categoryRailWidth = 64.dp` |
| 默认布局 | `UserPreferences.layoutMode` 默认由 `Grid` 改为 `List`，理由见 `docs/BOOKMARK_LAYOUT.md` §九（双列时文本列只剩约 46 dp） |
| 走查替换了什么 | 返工前那一轮的排布走查（§0H.18 / §0H.19 里的 108 dp 左栏读数）**已被返工后的读数替换**；`docs/BOOKMARK_LAYOUT.md` §七 是现在的记录 |
| 走查**没有**重跑的部分 | 收藏数据独立性（`updatedAt` 不变）与 WebView 返回两组观察仍是返工前在同一设备上做的：返工只动了 `HomeScreen` 的布局分支与两个默认值，没有改数据层，但这是**推断**，不是重测 |
| 一次被纠正的错误结论 | `pm clear` 之后看到左侧栏，曾据此认为默认值已改成 `Left`。实际是 `files/datastore/` 根本不存在（`run-as cat` 的 77 字节"文件"是一行 `No such file or directory`），应用没写任何偏好，看到的只是当时的界面状态。改用 `uninstall` + 全新 `install` 重测，干净状态是**顶部横条 + 单列列表**。详见 `docs/BOOKMARK_LAYOUT.md` §7.0a |
