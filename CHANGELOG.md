# 更新日志

本文件记录 LumoBrowser 每个交付版本改了什么、为什么改。条目按**倒序**排列，最新版本在最上面。

版本号来自 `app/build.gradle.kts` 的 `versionCode` / `versionName`；设置页「关于」面板会显示 `versionName (versionCode) · 收藏数`，以及 `BuildConfig.BUILD_TIME` 编译时间戳——Debug 包是手动侧载的，装了哪个包必须能自己说出来。

---

## 1.10 — versionCode 12

基线是 **v1.09 的源码工程**（`1.09版本/LumoBrowser-v1.09-source.zip` 解出的干净树），本版只做增量：**长按分类按钮原地重命名**，其余交互、音效、触感、动画、无障碍与持久化全部复用 1.09 已有的实现。没有另起工程，没有删除已有功能，没有新增权限，没有改动数据库 schema。

### 一、长按分类按钮，直接修改分类名称

一句话概括：**分类没有自己的表，也没有自己的 id——分类就是 `bookmarks.category` 字符串列**，`observeCategories()` 是 `SELECT DISTINCT category`。因此「重命名」就是一条 targeted UPDATE，天然保留所有书签关联，不需要建表、不需要 schema 迁移（`LumoDatabase` 版本保持 3）。

- 交互：短按分类仍切换筛选（原有行为不变）；长按打开重命名弹窗，`combinedClickable` 保证长按触发后**不会**再执行短按的分类切换。顶部横向栏与左侧竖向栏两处都接上了长按。
- 弹窗（新增 `ui/CategoryRenameDialog.kt`）：默认填入当前名称，自动聚焦输入框并弹软键盘；提供「保存 / 取消」；取消、按返回键或点击遮罩关闭都不改动原名称；键盘「完成」键走与「保存」完全相同的校验。保存成功后 UI 立即刷新，无需重启。
- 数据一致性（`data/Database.kt` BookmarkDao + `data/BookmarkRepository.kt`）：`@Query("UPDATE bookmarks SET category = :newName, updatedAt = :now WHERE category = :old")`，返回被改写的书签条数（0 = 分类不存在）。这条 SQL 只改 `category` 字符串，**不动**书签的 id、URL、标题、图标、排序、置顶状态、创建时间与访问历史，也不新建分类替代旧分类、不把所有书签归入当前分类。
- 校验（新增 `data/CategoryValidator.kt`）：名称不能为空或纯空白（trim 后再判）、自动去除首尾空白、拒绝与其它分类重名、上限 20 个 Unicode 码点（emoji 算 1 个，不是 UTF-16 的 2 个）；支持中文/英文/数字/符号/emoji；输入法组合输入不被截断。校验失败在弹窗内联提示，不静默失败；名称未变化时直接关闭不写库。
- 特殊分类保护：`UNCATEGORISED` 哨兵（`"\u0000uncategorised"`）按**值**识别，不被误判为用户自建分类；长按它不会打开编辑框。用户自建分类即便恰好叫「全部」，也按值处理、照常可重命名。
- 双重校验：弹窗先做本地校验，`MainViewModel.renameCategory()` 写入前再校验一次（防御 UI 层被绕过）。写入走已有的 `submit {}` 防重复提交 latch，成功后同步更新 `categoryFilter`（若当前正选中该分类），播 `SoundCue.Confirm` + `HapticCue.Success`，并发出 `UiMessage.CategoryRenamed` 供 Snackbar 显示。

### 二、交互动效、音效、触感、无障碍与持久化

本版**没有新增**任何开关、cue、音频资源或动画预设——需求四/五要求的能力在 1.09 已全部存在，本版复用并接线到重命名动作上：

- 音效/触感开关（`soundEnabled` / `hapticsEnabled`）、音色与强度、`hapticsFollowSound` 均已在设置页「声音与触感」；动画强度（`animationStrength` 0–1）与减弱动效（`reduceMotion`）已在「性能」；关闭后立即生效并持久化到 DataStore。
- 重命名动作的音效用既有 `SoundCue.Confirm`（同 cue 的触觉映射 `HapticCue.Confirm`/`Success`），不新增音源、不申请录音权限。
- 选中态动画、弹窗淡入/位移动画、布局切换动画沿用 `MotionConfig`（三档 nav 300/250/140 ms，乘全局强度，强度 0 即时长 0）；「关闭动画」复用 `animationStrength=0` 或 `reduceMotion`，无需新枚举。

### 三、测试与交付

- 新增测试：`BookmarkRepositoryTest` 补 `renameCategoryRewritesEveryBookmarkInPlace`（改写 2 条、逐字段断言 id/url/title/sortOrder/pin/createdAt 不变、分类列表正确）与 `renameCategoryReturnsZeroForUnknownCategory`；新增 `CategoryValidatorTest`（9 例：trim/空/重复/同名 no-op/超长/边界 20/emoji 码点/拉丁数字符号）。
- 构建与全量单测结果见交付文档；`versionCode 12 / versionName "1.10"`。

---

## 1.09 — versionCode 11

本版的起点是那份「收藏夹分类栏左侧竖排 + 布局切换开关 + 动画与音效」的需求，随后接上了「返回键要从 WebView 自己的历史恢复」。基线是 **v1.08 的源码工程本身**（`dist/LumoBrowser-v1.08-source.zip` 解出的干净树），不是上一轮的 v1.09 工作树——避免把未经验证的改动带进来。

本版共四件事：分类栏位置与它的设置项、位置切换的动画、复用既有音效与触觉、返回键的历史恢复接线。

### 一、收藏分类栏：顶部横向 ⇄ 左侧竖向

一句话概括：**分类栏只换位置，不动数据**。

- 新增偏好 `categoryRailPlacement`（`DataStore` 键 `category_rail_placement`，取值 `Top` / `Left`，**默认 `Top`**，与 1.08 的观感一致）。它记录在 `data/Model.kt` 的 `CategoryRailPlacement` 枚举里，与 `LayoutMode`（卡片列数）**刻意分开**：这是两个独立的问题，共用一个字段会让「列表模式」和「分类栏在左边」变成同一个开关。
- 设置页新增「外观与布局 → 分类栏显示位置」（`setting_category_rail` / `setting_category_rail_hint`，含中/繁/英三语），开启即左侧、关闭即顶部，改动立即生效，无需重启。开关是布尔控件、偏好是枚举，中间的 `Boolean.toRailPlacement()` 转换留在设置页内部。存了无法识别的值时读回默认的 `Top`，不抛异常。
- 收藏页两种排布共用同一个 `LazyVerticalGrid` 定义（`BookmarkGrid` + 两个 `LazyGridScope` 扩展 `headerItem` / `bookContentItems`），只有分类栏这一个部件在两种排布之间移动；因此不可能出现「同一屏里两个分类控件」。
- 左侧竖排的**排版策略**：整体自上而下排列；名称逐字竖排**仅对纯汉字名称**生效（「学习」「充值」竖排），含拉丁字母或数字的名称（`esim`、`API`、`Wi-Fi 6`）保持整串可读、按词换行——把 `esim` 拆成四行是降低可读性，不是特性。规则落在 `internal fun railLabel(label: String)` 与 `internal fun isCjkOnly(text: String)`，判定区间是 `U+4E00..U+9FFF`。
- **独立滚动**：左侧分类栏是收藏网格的**兄弟节点**而不是覆盖层，两个滚动容器并列（分类栏持有 `rememberLazyListState()`，网格持有 `rememberLazyGridState()`），互不嵌套，因此拖动一个永远不会传到另一个。网格的滚动状态建在排布分支之外，切换位置时右侧列表保留自己的位置。
- **左侧栏宽度是固定值** `LumoSpacing.categoryRailWidth = 64.dp`。第一版按窗口宽度取 26%（夹在 72–108 dp），在 411 dp 的机器上算出 108 dp：宽到能读成「第二列内容」，卡片被压窄，而且标题与搜索框当时也在这个分支里面，一起被挤成两行。返工后宽度固定、不随窗口增长，横屏时富余宽度全部给卡片。
- **标题与搜索框在排布分支之外**：`HomeScreen` 现在是一个 `Column`，第一段是整行的 `HomeContentHeader`（标题、收藏条数、更多、添加、搜索框），第二段 `weight(1f)` 的 `AnimatedContent` 才在「左侧栏 + 卡片」「顶部横条 + 卡片」之间二选一。于是无论哪种排布，标题都不折行、搜索框都是满宽；左侧栏只占内容区的 64 + 12 dp。
- 默认布局改成**单列列表**（`UserPreferences.layoutMode` 的默认值 `Grid` → `List`）。原因是双列网格在手机宽度上留给标题/域名的文本列只有约 46 dp（411 dp 宽的机器上：375 内容宽 → 每卡 181 dp → 减去内边距 28、图标 50、溢出按钮 32），`Uncategorised one` 只能显示成 `Unca…`。这与分类栏的位置无关：分类栏放回顶部时截断照样出现。显式选过 `Grid` 的用户仍然用 `Grid`。

### 二、动画

- 布局切换走 `AnimatedContent`：横向淡入淡出 + 水平展开/收起，时长取自主题 `motion.duration(navDurationMs)`——三档预设分别是 **300 / 250 / 140 ms**，全部落在需求给 UI 过渡规定的 120–300 ms 区间内，再乘以全局动画强度；强度为 0 时时长就是 0，动画塌缩为瞬间完成，而不是仅仅变快。这三个数与它们的区间由 `MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` 锁住，将来往预设里加一个超出区间的数字会直接让测试失败。`gridState` 建在分支之外，因此切换**保留右侧列表的滚动位置**。刻意不做高度/尺寸动画——那会让 `LazyVerticalGrid` 每帧重新测量所有可见卡片。
- 分类选中：背景色、指示条、文字色三项短动画，时长同样来自主题。
- 减弱动效（系统无障碍标志与应用自身偏好的**合并结果**）把全局强度压到不超过 0.35，并直接关掉背景漂移动画；按压、淡入这类短反馈保留，要求是「减弱」不是「移除」。
- 卡片入场动画沿用 1.08 的实现，本版未改。

### 三、音效与触觉：复用，没有新增

- 分类选中用的反馈在**选中项真的发生变化**时触发一次（`LaunchedEffect(selected)` 比较快照 `previousSelection`），重复点同一个分类不会再响一次；反馈挂在「选中」这个结果上，不挂在点击上。
- 左栏取用的是既有的 `LocalLumoFeedback.current`，调用的是既有的 `feedback.confirm()`，即 `SoundCue.Confirm`，同一 cue 的触觉映射是 `HapticCue.Confirm`。
- 开关全部是 1.08 已有的：`soundEnabled` / `soundVolume` / `soundStyle` / `hapticsEnabled` / `hapticStrength` / `hapticsFollowSound`，由 `MainViewModel.syncFeedbackEngines(prefs)` 统一下发。**没有新增开关、没有新增 cue、没有新增音频资源**；`feedback/` 目录仍是原来的 6 个文件。

### 四、返回键：从 WebView 历史恢复

- `browser/LumoWebView.kt` 新增 `goBackKeepingPosition()` / `goForwardKeepingPosition()`：读 `copyBackForwardList()` 定位目标 entry，把该 entry 地址对应的阅读位置放进 `pendingRestore`，再调用引擎自己的 `goBack()` / `goForward()` 移动游标。
- 这条路径**不调用** `loadUrl(previous)`，也**不做**预防性的 `reload()`：把地址再请求一次是一次全新导航，会得到新文档、新表单状态，而且历史里会多出一条。整个 `app/src/main` 里 `loadUrl(` 与 `reload(` 的全部命中已逐条列在 [`docs/WEBVIEW_HISTORY_RESTORE.md`](docs/WEBVIEW_HISTORY_RESTORE.md) §三，其中没有一条在返回路径上。
- 工具栏 `onBack` / `onForward` 与系统返回键都接到这两个方法；`BackHandler` 只在历史移动**报告无处可去**时关闭页面（`if (view == null || !view.goBackKeepingPosition()) { onClose() }`），而不是先做一次需要同步维护的 `canGoBack()` 判断。
- 滚动位置由 `browser/ScrollMemory.kt` 按地址记录（`accessOrder = true` 的 LRU，上限 24 条，`null` 与 `0` 是两个不同答案），恢复时用有界轮询（每 150 ms、最多 8 次）等 `currentUrl` 与目标地址一致后再应用。轮询的用意是：一次由引擎已持有的文档作答的返回**根本不会触发加载**，只挂 `onPageFinished` 会漏掉它。轮询超时放弃的是那个偏移量，不是这次导航。

### 五、没有动的东西

收藏数据结构、数据库 schema、包名、签名与用户数据一律未改；本版**没有**新增收藏写入路径，也没有把任何 URL 写回收藏记录的逻辑——见 [`docs/BOOKMARK_DATA_INTEGRITY.md`](docs/BOOKMARK_DATA_INTEGRITY.md)。

### 六、本版的真实数字

| 项目 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | **45 个测试类 / 729 个测试 / 0 失败 / 0 错误 / 0 跳过**，`BUILD SUCCESSFUL in 46s`（`docs/build-logs/v109r7-build.txt`，与两个 assemble 同一次调用，exit 0）。数字来自 `app/build/test-results/testDebugUnitTest` 下的 45 个 XML 统计——Gradle 控制台本身不打印用例数。本版相对 1.08 的 718 例 / 44 类，多出 11 例、1 类（`CategoryRailTest` 10 例 + `MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` 1 例） |
| 一次真实的不稳定用例（**未修好，已定性**） | `StartupSmokeTest.localizedStrings_resolveToRealText` 在全量运行与独立冷构建里各失败一次，报 `UncaughtExceptionsBeforeTest`，其下是被抑制的 `java.lang.IllegalStateException: Illegal connection pointer`（Robolectric `ShadowLegacySQLiteConnection`）。定性的形状：Room 的查询跑在 JVM 级 `arch_disk_io` 池上，跨过 Robolectric 每用例重置的连接表，落在**下一个**用例开始前抛出野异常；用例自己并没有错。**两条修法都试过并实测失败，因此都没有留下**：重试规则不可能生效（重跑触发 Compose 的 `Only a single call to runTest can be performed during one test`）；把 `executeOnDiskIO` 钉到单线程反而让 `AppFlowSmokeTest` 8 个用例一起超时（写入被长期驻留的观察者饿死）。两条规则已整份删除，两个测试类与 v1.08 逐字节一致。删掉之后连续 4 次 `--rerun-tasks` 全量全绿（2m 16s–2m 31s）。详见 [`docs/TESTING.md`](docs/TESTING.md) §0H.5 与 §0H.5a |
| `:app:assembleDebug :app:assembleRelease` | `BUILD SUCCESSFUL in 2m 6s`，0 个 `e:`（`docs/build-logs/v109r6-build.txt`） |
| 交付产物 | `dist/LumoBrowser-v1.09-debug.apk` **20,067,566 B**、`dist/LumoBrowser-v1.09-release.apk` **13,199,620 B**、`dist/LumoBrowser-v1.09-source.zip`（**299 个文件**；字节数与哈希见 `dist/SHA256SUMS.txt` 与 `dist/pack-report.txt`，本文档不抄源码包的字节数——抄了就会被下一次重新打包改掉，见 [`docs/TESTING.md`](docs/TESTING.md) §0H.17）。release 包与 1.08 同一个签名证书（`apksigner verify --print-certs` → `SHA-256 digest: 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9`），所以可以从 1.08 直接覆盖升级。**debug 包的字节数在几次构建之间会变**（量到过 20,067,098 / 20,067,017 / 20,067,046 / 20,067,562 / 20,067,566），release 包逐字节稳定；判定"包里有没有本版代码"看 dex 标记，不看字节数 |
| 源码包自足（独立冷构建） | 把交付的源码包解到工作树之外、只补一个 `local.properties`，`gradle --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` → `BUILD SUCCESSFUL in 3m 1s`，**45 / 728 / 0 / 0 / 0**，release APK 字节数与交付件完全相同。见 [`docs/TESTING.md`](docs/TESTING.md) §0H.7 |
| 出货包里确实有本版的代码 | 四个 APK 的 `classes*.dex` 里都能搜到 `goBackKeepingPosition`、`goForwardKeepingPosition`、`category_rail_placement`、`setting_category_rail`、`HISTORY_RESTORE_POLL_MS` |
| 模拟器走查 | 见 [`docs/TESTING.md`](docs/TESTING.md) §0H 与 [`docs/BOOKMARK_LAYOUT.md`](docs/BOOKMARK_LAYOUT.md) §七：布局切换（两个方向）、`force-stop` 后仍读回左侧栏、**干净安装时的两个默认值**（顶部横条 + 单列列表，§7.0a 记录了中间一次错误结论的纠正）、从左栏筛选、名称排布、卡片内容、**搜索词与分类筛选的交集**（§0H.10）、**系统动画关闭时不播放过渡**（§0H.11）、返回路径（含 `nav=navigate` / `nav=back_forward` 成对读数与 `no-store` 的观察）。三张对照图在 `docs/screenshots/v109-*.png` |
| 收藏数据实测 | 走查结束后把库拉下来逐行核对：**8 条收藏、8 个互不相同的 URL、8 行的 `updatedAt` 全未变化**。见 [`docs/BOOKMARK_DATA_INTEGRITY.md`](docs/BOOKMARK_DATA_INTEGRITY.md) §六 |
| 未执行的验证 | 排布切换的**帧时间**、72/108 dp 宽度边界、release 包的设备走查、返回路径的表单状态恢复、返回后继续前进、`target="_blank"` / SPA 等特例导航；**音效与触觉只做了代码层核对，设备上未听未振**——逐条列在 [`docs/TESTING.md`](docs/TESTING.md) §0H.1 / §0H.12 |


---

## 1.08 — versionCode 10

起点仍然是那句话（v1.07 的开头引用了它）：**「在手机上安装了，打不开，在优化一下。」**

先说清楚**这一版没有解决什么**：打不开的**真因至今没有找到**，因为要找到它必须有那台手机的 logcat，而它一直没拿到（本环境也没有模拟器、没有系统镜像、没有可连的真机）。所以 v1.08 **不声称修好了启动崩溃**。v1.07 已经交付的是"让崩溃无法静默"的那一套（崩溃自报、恢复页、安全模式、已签名的非 debuggable release 包）。本版在其上做的是**把已经写进去、但没真正生效或没真正接上的功能补完**，并且把每一项能力与它的边界一起写进文档。

### 一、网页视频与全屏：六项修复

起点是一份**只读诊断**（逐行读 `browser/` 与 `ui/` 两条路径，不改任何文件、不跑 Gradle），给出 F1–F12 清单。修了其中六项：

| 编号 | 症状 | 修法 |
| --- | --- | --- |
| **F3** | `mediaPlaybackRequiresUserGesture` **硬编码 `true`** 且界面无处可改——凡是脚本自行起播的播放器（下一集、跨站嵌入、广告后续播）都停在转圈上，页面上没有任何解释 | 新增偏好 `mediaAutoplayAllowed`（默认开），决定落在纯函数 `planFor(..., autoplayAllowed)`；改动**不重载页面** |
| **F1** | 渲染进程死亡后全屏覆盖层无人清理，退出按钮变成空操作 | 渲染进程死亡时清 `pageFullscreen`；`exitFullscreen()` **即使已无回调可调**也照样通知宿主；退出动作**由视图自身是否全屏决定** |
| **F2** | 返回键优先序里没有"页面全屏"：全屏看视频按返回会改变文档 | 返回键第一档改成"先退出页面全屏" |
| **F12** | 退出全屏从不 `removeView`：视频在没有可见 surface 的情况下继续播放，第二次全屏抛 `child already has a parent` | 全屏容器在 `onDetachedFromWindow` 里摘下页面视图 |
| **F6** | 渲染进程重建**没有次数上限**且重载同一 URL，可成死循环 | 每个地址 3 次预算，用尽后停在说明面板上，并给一个**用户主动点**的重试 |
| **F4** | "空渲染"探针把播放器页面判成空页 → AUTO 阶梯整页重载 → 正好打断正在起来的播放器 | 探针把 `video,audio,canvas,object,embed` 的存在也算作有内容 |

**五项刻意不改**（理由逐条写在 [`docs/VIDEO_PLAYBACK.md`](docs/VIDEO_PLAYBACK.md) §4）：F5（SSL 错误仍然一律取消——本项目的硬约束是**不忽略 HTTPS 证书错误**，"修"它就是违反约束）、F8（弹窗仍要求手势）、F9（页面权限仍一律拒绝）、F10（主机规则仍覆盖第一方）、F11（第三方 Cookie 仍每站默认关闭）。

顺带修掉一条**说假话的测试**：`BrowserConfigManagerTest.mediaNeverAutoplaysInAnyModeOrConfiguration` 只断言 `planFor` 的参数默认值，因此无论应用实际发货成什么样子都会通过——这正是硬编码能活这么久的原因。它被替换成双向断言 + 出厂默认值断言。

### 二、广告屏蔽：从"写好了"到"真的生效"

v1.07 的阻断引擎本身是好的，问题在**边界**和**用户能不能控制它**：

| 新增 | 要点 |
| --- | --- |
| **媒体豁免** | `.m3u8` / `.mpd` / `.m4s` / `.ts` 不吃**主机**规则（只从 path 读扩展名）；路径规则与例外不受影响；同一主机上的普通文件照旧被拦 |
| **站点级暂停** | 浏览器溢出菜单里就能暂停/恢复本站（涵盖子域，恢复时逐条复原覆盖条目并重载本页），设置页也列出已暂停站点 |
| **规则更新** | **用户手动触发**；来源是**常量**（随包快照的来源站），没有"规则地址"输入框；仅 HTTPS 且重定向前后各查一次；声明长度与实际读取都有上限；**合并而非替换**（随包列表 + 上游新增），所以在解析成功前一个字节都不写 |
| **不伪造"已是最新"** | 更新结果带 `changed`：下载到的是**同一份正在用的清单**时，界面说的是"与当前规则相同"，不是一次成功更新 |
| **拦截记录** | 最近 60 条（有界），设置页可清空；清空记录**不**重置累计拦截数 |
| **设置页回答三个问题** | 现在用的是哪一份规则（随包/已下载）、本页检查了多少请求、上次下载的时间与版本 |

`docs/AD_BLOCKING.md` 的 §1–§11 是 v1.07 的正文，末尾新增的附录写 v1.08 的四件事，并**更正**了其中一句已经被本版推翻的话（"没有做在线规则更新"）。

### 三、导航安全：一个新的独立模块

新增 `app/src/main/java/com/lumobrowser/app/browser/security/`（纯 Kotlin 决策层 887 行 + 状态层 479 行），四条规则：**重定向环**（同一地址在一次导航里出现到第 3 次）、**弹窗泛滥**（10 秒内超过 3 个窗口请求）、**不可信 scheme**、**Safe Browsing 命中**。

三个值得写下来的决定：

1. **它不是第二个"要不要在应用内打开"的判断者**。那个问题仍由既有的 `WebNavigationPolicy.classifyNavigation` 回答；本模块只回答"已经决定要导航之后，这一次导航是否安全"。代价是两个同名 `NavigationKind` 并存，因此接线处必须显式 import（注释写在 `LumoWebView.kt` 的 import 区）。
2. **已接线与未接线的入口分成两张表写清楚**（[`docs/NAVIGATION_SECURITY.md`](docs/NAVIGATION_SECURITY.md) §5、§6）。未接线的四个方法各有理由，其中 `noteSafeBrowsingUnavailable()` **没有平台信号可以接**——因此设置页那句"不可用"在实务上不可达，文案也据此改写成**只声称可验证的事**："应用把它开着，并且从不覆盖平台给出的警告"。
3. **清空记录不等于重置状态**：`clearLog()` 只清记录，`reset()`（连重定向链与弹窗时间窗一起清）刻意不被设置页调用——清掉读者正在看的记录，不应该改变浏览器正在做的事。

### 四、本版的真实数字

| 项目 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | **718 个测试 / 44 个测试类 / 0 失败 / 0 错误 / 0 跳过**，`BUILD SUCCESSFUL in 38s`（v1.07 基线 628 例；本版 +89：导航安全 81、广告屏蔽 8；另有一次**替换**：`BrowserConfigManagerTest` 里那个永远为真的 `mediaNeverAutoplaysInAnyModeOrConfiguration` 被换成两条真会失败的断言，30 → 31 例） |
| `:app:assembleDebug :app:assembleRelease` | `BUILD SUCCESSFUL in 1m 44s`，0 个 `e:`（编译错误）/ 0 个 `w:`（编译警告）；**本版出过一次真实的编译错误**并已修复（`onSafeBrowsingHit` 写错类，日志留档为 `docs/build-logs/v108-compile-failed-safebrowsing.txt`），不是靠改版本号或忽略错误绕过的 |
| 交付物 | `dist/LumoBrowser-v1.08-debug.apk`（19,535,475 B，SHA-256 `833FEE7E…C911E`）、`dist/LumoBrowser-v1.08-release.apk`（13,182,384 B，SHA-256 `876BAFA2…6E42`，真密钥签名、**非 debuggable**、与 1.07 同一把证书故可直接覆盖升级）、`dist/LumoBrowser-v1.08-source.zip`（**287 个条目**，1.07 是 267）、`dist/SHA256SUMS.txt`（**20 条**，打包后重算并逐条回读校验，`bad=0`） |
| 未执行的验证 | 任何需要真机/模拟器的项：安装、启动、网页视频播放、全屏退出、真实站点上的广告误拦。`adb devices` 为空、无 AVD、无系统镜像——全部**逐条列在** [`docs/TESTING.md`](docs/TESTING.md)，并标明"未测试"而不是"通过" |

### 五、仍然不知道的事（不打算含糊过去）

* **启动崩溃的真因**：仍然未知。本版没有拿到 logcat，也没有新的证据指向某个具体原因，因此没有对启动路径做"猜测性修复"。
* **16 KB 页面假说**：v1.07 已经用 ELF program header 推翻（四个 ABI 的 `PT_LOAD p_align` 全是 `0x4000`）。
* **视频与全屏的修复效果**：只有单元测试覆盖"决定"这一层，真机上是否真的放得出来，必须由那台手机回答（手工清单在 `docs/VIDEO_PLAYBACK.md` §6）。

---


起点是用户的一句话：**「在手机上安装了，打不开，在优化一下。」**

装了 `dist/LumoBrowser-v1.06-debug.apk` 的那台手机是 Android 14/15，症状是**点开图标闪一下就退回桌面**，而报告问题的人**拿不到 logcat**（原话：「不方便，先按你的判断修」）。这一版是对那句「打不开」的回应——**不是因为我找到了那个崩溃**，而是因为**我不可能找到它**，所以换了一条能交付答案的路。

### 一、先把能排除的排除掉

这三件事都没有抓到凶手，但它们把范围压小了，而且都是可以复现的事实：

1. **与用户装的那颗包逐字比对。** 把 `dist/LumoBrowser-v1.06-source.zip` 解到 `_v106src/` 与当前源码逐档 diff：`MainActivity.kt`（320 行）与 `AndroidManifest.xml`（204 行）**完全相同**；差异只有 `LumoApplication.kt`（+32 行，只多了广告屏蔽接线）、`StartupAnimation.kt`（+17 行）、`ui/LumoApp.kt`（7 行）、`res/values/themes.xml`（`windowSplashScreenAnimationDuration` 400 → 0）。**这里没有一项能解释启动崩溃。**
2. **启动路径审计。** 全 `app/src/main` 没有 `!!`、没有无兜底的枚举反查（三处都是 `entries.firstOrNull { … } ?: Default`）、没有 `checkNotNull`/`require`、没有向系统设置**写入**（`util/WindowUtil.kt` 只读亮度与动画缩放）；三处 `error("… read outside …")` 守门都包在正确的 provider 里，`StartupSmokeTest` 在 API 34 上真的启动 `MainActivity` 并全绿；`AssistTargetCodec.decode` 逐行 `mapNotNull`、`split` 不抛、畸形行直接丢——**「旧版写入的损坏数据导致启动崩溃」这条不成立**。
3. **两条装置专属假设被否证。**
   - **16 KB 内存页**：自解析 APK 内四个 `.so` 的 ELF program header，**四个 ABI 的 `PT_LOAD` 的 `p_align` 全部是 `0x4000`（16 KB）**，已符合 Android 15 的要求。这条不成立。（附带验证：`zipalign.exe`（build-tools 34.0.0）**不支持** `-P`，无法用官方工具检查 16 KB 对齐，所以才自己读 ELF。）
   - **SplashScreen 相关**：`windowSplashScreenAnimationDuration` 早已从 400 改成 `0`（这是**图标退出动画**的长度，不是停留时间；留着它会让标志播两遍），且工程**刻意没有** `setKeepOnScreenCondition`——把首帧挡在任何东西后面正是「装上了却打不开」的形状。

### 二、于是换一条路：让崩溃无法静默

没有日志就没有诊断，那就让**应用自己记录并说明自己的死亡**。新增 `app/src/main/java/com/lumobrowser/app/crash/` 四个文件：

- **`CrashGuard.kt`**（258 行）——在 `LumoApplication.onCreate` 里紧跟 `super.onCreate()` 装 `Thread.setDefaultUncaughtExceptionHandler`。三个刻意的选择：
  - **一定调用前一个处理器**（`previous?.uncaughtException(...)`）：记录完把进程交还平台，让它照常死。吞掉异常会造成一个半死的进程，比崩溃更难查。
  - **用 `SharedPreferences` 的 `commit()`，不用 DataStore**：这段代码跑在一个马上要被杀掉的线程上，只有同步落盘是正确形状。
  - **一切有界、一切包在 `runCatching` 里**：一个会抛异常的崩溃处理器会把「用户的 bug 报告」换成「更糟的一份报告」。
  - 顺带排除的一个坑：`install()` 用**对象身份**判断（`current === handler`）而不是布尔标志，否则测试里第二次 `install()` 会把第一个链上的处理器**再串一次**，异常被记两遍。
- **`CrashRecord.kt`**（144 行）——报告是**一段人类可读的文字**：磁盘上的文件、屏幕上的文本、分享出去的内容是同一个东西。有界：每个 throwable 最多 **48 帧**、一条 cause 链最多 **6 层**、正文 **12 000 字元**、`cause === current` 视为环并终止。`parse()` 遇到不认识的内容返回 `null` 而不抛。
- **`CrashRecoveryActivity.kt`** —— 恢复页**只用平台 View**：不用 Compose、不建 WebView、不读 Compose 主题。如果崩溃出在 Compose 侧，这一页必须完全不碰它。
- **`SafeMode.kt`** —— 安全模式是一个纯函数 `apply(prefs)`：动画强度 `0f`、动画预设 `Minimal`、关掉环境光斑与简化效果、关掉广告屏蔽；**其余偏好一字不动，且永不写回 DataStore**（安全模式只是这一次启动的输入，不是一次设置修改）。

### 三、下次启动会先说明发生了什么

`MainActivity.onCreate` 在 `setContent` **之前**检查 `CrashGuard.hasPendingCrash(this)`：有就 `startActivity(CrashGuard.recoveryIntent(this))` 然后 `finish()`——**这条路径上永远不会构建 Compose 树**，所以即使崩溃源在 Compose 里，说明页也打得开。

报告里的三行通常就能定位问题：`page size:`（16 KB 设备上原生库会不会出问题）、`webview:`（WebView 提供方包名与版本）、`package:`（含 `debuggable`）。整页可选、可**复制**、可**分享**，所以手机不方便连电脑时也能把证据发出来。

### 四、另外交付一个**已签名的 Release 包**

部分 OEM ROM 会拦截或杀掉 **debuggable** 的侧载应用——那也是「闪一下就退回桌面」，而且**不是代码问题**，靠改代码永远修不好。所以这一版多给一个包：

- `dist/LumoBrowser-v1.07-release.apk`：真密钥签名（`CN=LumoBrowser`，证书 SHA-256 `4beb5163…75a9`）、**不可调试**、包名 `com.lumobrowser.app`（不带 `.debug`），**可与 Debug 包同机共存**。
- 密钥由 `keystore/keystore.properties` 条件式接入 `app/build.gradle.kts`（文件不存在时工程照样能构建，只是没有 Release 签名）。这是**个人侧载用的一次性密钥**，口令就写在文档里，因为它不值得保护；要上架请换成自己的。
- **Release 变体故意不混淆**（`isMinifyEnabled = false`、`isShrinkResources = false`）：R8 从未在真机上验证过，而 `addJavascriptInterface` 出来的桥方法是反射绑定的——一个 R8 引入的问题会和正在诊断的启动崩溃长得一模一样。

### 五、广告屏蔽（本版主线功能）

请求拦截层，随 `WebViewClient.shouldInterceptRequest` 落地：

- `AdBlockRules` 解析 hosts 形式规则（含 `0.0.0.0`/`127.0.0.1` 前缀、`@@` 例外、`/path*` 片段），`AdBlockPolicy` 决定拦不拦，`AdBlockManager` 管加载、缓存与计数；出厂的规则文件在 `app/src/main/assets/adblock/rules.txt`。
- **只拦第三方请求、只拦子资源、绝不拦主框架**；`DecisionCache`（512 项 LRU）避免逐请求重复判定；计数每 2 秒合并写一次 DataStore，不是每次拦截都写盘。
- **HTTPS 证书错误一个都不忽略**——拦截发生在请求层，与证书校验无关，`onReceivedSslError` 照旧走取消。
- 补下的两个规则解析真缺陷：路径片段最小长度 **4**（`/a` 这种两字符片段会匹配几乎每个第三方地址，把网页拦烂）、单独一行的 sink 地址（`0.0.0.0`）不再被当成一条主机规则；`AdBlockPolicy.hostOf` 补上**尾端根点**与 **IPv6 字面值**（`[2606:4700::1111]:443` 以前会被 `indexOf(':')` 截成 `2606`），`siteOf` 改为「从右数第二个点之后全部」（原实作让 `www.news.example.org` 与 `news.example.org` 算不同站）。

### 六、顺手修掉一个真 bug：测试套件曾经跑不完

`:app:testDebugUnitTest` 在一次改动后**跑 20 分钟零个测试完成**。`jcmd Thread.print` 抓到主线程在 `ThreadLocal$ThreadLocalMap.cleanSomeSlots` 自旋（**824 秒 CPU 时间**），栈底是 `LanguageSwitchLaunchTest` 的 `ActivityScenario.launch`。

真因是**裸 `ActivityScenario` 与无限动画不能共存**：`launch` 内部走 `ActivityController.visible()` → `ShadowPausedLooper.idle()`，而主屏的漂移极光每跑完一帧就排下一帧，**队列永远不为空** ⇒ 永不返回。（`docs/TESTING.md:915` 早记过这个陷阱的另一种形态，当时用手写的有界 `idleFor` 绕开；这次触发点在 `launch` 内部，绕不过去。）修法是**在启动前把环境动效关掉**——`arrangeAmbientMotionOff()` 写 `backgroundGlowEnabled = false` 并等 DataStore 收敛，这是用户本来就有的开关，与语言判定无关。修好后这一类从「永不返回」变成 **16 秒通过**，全量套件 **27 秒**。

### 七、测试

| 项目 | 结果 |
| --- | --- |
| 全量单元测试 | **628 例 / 42 个类，0 失败 0 错误 0 跳过**（1.06 是 527 例 / 33 类），`BUILD SUCCESSFUL in 27s` |
| 本版新增：崩溃自述 | **38 例**（`CrashGuardTest` 14 · `CrashRecordTest` 10 · `CrashRecoveryActivityTest` 8 · `SafeModeTest` 5 · `CrashRoutingTest` 1） |
| 本版新增：广告屏蔽 | **63 例**（`AdBlockPolicyTest` 22 · `AdBlockRulesTest` 20 · `AdBlockResponsesTest` 12 · `AdBlockManagerTest` 9） |
| 构建 | `:app:assembleDebug :app:assembleRelease` → `BUILD SUCCESSFUL in 2m 26s`，**0 个 `e:`、0 个 `w:`** |
| 产物 | Debug **19,416,196 字节** · Release **13,099,148 字节**；`apksigner` 与 `aapt2 dump badging` 逐项校验（Release 无 `application-debuggable`，四 ABI 齐全） |
| 三语言 | 新增 17 条 `crash_recovery_*` 字符串，`StringResourceParityTest` 6 例通过（三份 strings 键集一致） |

`CrashGuard` 的两条最容易写错的语义各有一个测试钉住：**串接**（先设一个哨兵处理器再 `install`，触发后哨兵必须收到**同一个** Throwable，且报告已落盘）、**幂等**（第二次 `install` 不会把链上的处理器再串一次，`seen.size == 1`）。安全模式则用它**真正的后果**证明：`MotionPresets.config(safe.animationPreset, safe.animationStrength, safe.reduceMotion).animationsEnabled == false` 且 `startupTimeline(motion) == null`——即极光无限动画与启动覆盖层都不会被组合。

### 八、交付物与文档

- `dist/LumoBrowser-v1.07-debug.apk`、`dist/LumoBrowser-v1.07-release.apk`、`dist/LumoBrowser-v1.07-source.zip`（**267 个条目**，含 4 个 crash 源文件、4 个 crash 测试类、`assets/adblock/rules.txt`、`tools/package_source.ps1`、`keystore/` 与 32 份文档），`dist/SHA256SUMS.txt` 重算为 **17 条**。
- 新增文档：[`docs/CRASH_RECOVERY.md`](docs/CRASH_RECOVERY.md)（崩溃自述与安全模式的完整说明）、[`docs/AD_BLOCKING.md`](docs/AD_BLOCKING.md)、[`docs/ANIMATION_SYSTEM.md`](docs/ANIMATION_SYSTEM.md)。
- 重写：[`docs/BUILD.md`](docs/BUILD.md) §6「Release 签名」为 6.1–6.4（条件式签名、随附密钥、换成自己的密钥、构建与校验命令），§7 排障表新增 `gradlew.bat` 报 `Connection refused` 时改用本机 Gradle 的一行。
- 新增打包脚本 `tools/package_source.ps1`（allowlist 打包，拒绝把 `build/`、`dist/`、临时日志带进源码包）。

### 九、没有验证的部分

- **那台手机上的启动崩溃是否真的解决，本版无法证明**：本机没有模拟器/真机（`adb devices` 为空、SDK 无 system image、Robolectric 只有 API 33 运行时），也没有那台设备的 logcat。本版能做的是把「装上了却打不开」从**只能猜**变成**下次启动会自己说明**，并提供一个非 debuggable 的包来排除 ROM 拦截。
- **恢复页与安全模式的真机表现**未经真机验证（只有 Robolectric 覆盖：38 例）。特别是「崩溃后第二次启动真的会先出说明页」这一条，需要一次真实崩溃才能证实。
- **广告屏蔽的真实效果**未在真机对比过（无网络抓取条件），只有 63 例纯逻辑测试与规则解析测试；出厂规则文件的命中率没有实测数据。
- **Release 包的 R8 关闭**意味着包更大（12.49 MB），这是刻意的取舍，不是优化失败。

---

## 1.06 — versionCode 8

起点是用户的一句话：**「网页跳转 App 没有反应」**，外加三个点名要修的地方。查下去发现**症状有四种，病只有一种**：

> **用一次看不见全貌的查询，去替一次本来会成功的尝试下结论。**

三次都问的是同一件事——「这台设备上有没有东西能处理这个地址」——而 Android 11 以后这个问题**根本答不准**：包可见性（`<queries>`）会把真实存在的处理器从查询结果里滤掉，而 **`startActivity` 不被过滤**。于是「查询说没有、启动会成功」不是异常，是**预期行为**（AOSP `ActivityTaskSupervisor.resolveIntent` 走的是 `resolveForStart = true` 那条路，`ComputerEngine.queryIntentActivitiesInternal:561-570`）。三处预检分别是确认面板的 `canHandle`、跳转前的 `resolveActivity`、无障碍设置的 `canOpenSettings`，**三处都删掉了**。

### 一、根因 A：网页地址在意图建立之前就被否掉了

**症状**：设置里打开「网站跳转」，点网页里的链接，弹出的是「这台设备上没有其他浏览器可以打开该地址」——而设备上装着三个浏览器。

**真因是两行代码撞在一起。** `ExternalLauncher.build()` 的第一句是 `externalLinkOffer(url) ?: return null`，而 `externalLinkOffer` 只对 `WebNavigationPolicy` 判定为 `ConfirmExternal` 的 scheme 给答案；**`http`/`https` 属于 `InApp`，不在其中**。于是**每一个网页地址**都在意图还不存在的时候变成 `null`，`launch()` 把它读成 `Unusable`，界面照实汇报「没有应用能处理」。

**这个 bug 能连着两个版本出厂，是因为测试从没碰过它**：1.05 有 505 个测试，**没有一个调用过 `ExternalLauncher.build`**。

**修法**：网页地址就是它本来的样子——`ACTION_VIEW` + 地址，不需要任何「offer」批准（`webAddress_isBuiltEvenThoughTheLinkPolicyDeclinesToOfferIt` 把这两件事分开钉住：同一个地址，`externalLinkOffer(url)` 必须是 `null`，而 `build(url)` 必须非空）。

### 二、根因 B：启动前先查一次包管理器，查不到就不启动

`queryIntentActivities` / `resolveActivity` 在 Android 11+ 受 `<queries>` 过滤，`startActivity` 不受。一个真实存在、真能启动的浏览器可以被一次看不见它的查询否掉；`resolveActivity` 还只回**偏好**处理器，会让用户看不到自己其实能选的别的应用。

**修法**：查询只决定「值不值得弹选择器」，**启动一律尝试，`ActivityNotFoundException` 才是「没有处理器」**。`invisibleHandler_isStillHandedTheAddress` 先用一次真实查询断言「这里一个处理器都看不见」，再断言 `launch()` 仍然返回 `Opened`。

### 三、面板不再宣称「这台设备上没有应用能处理该地址」

那句结论 `PackageManager` **得不出**。更糟的是它正下方的确认按钮**故意保持可按**——面板一边说没有、一边允许按下，两者互相打脸。

`ExternalLauncher.canHandle`、`ExternalLinkSheet` 的 `canHandle` 参数、`PendingHandoff` 的 `canHandle` 字段**全部删除**（全仓库 `canHandle` 0 命中）。面板只剩两句实话：「打开方式：某个具名应用」（候选恰好一个时）或「由你选择的应用」，真正的答案由**尝试本身**在事后给出（`HandoffOutcome`）。**删掉一个判断，比让它继续说话安全。**

### 四、跳转判定补上两个条件：子框架与服务器重定向

`WebNavigationPolicy.leavesForCrossSite` 从三个参数变成五个，新增 `isForMainFrame` 与 `isRedirect`（`LumoWebView.dispatchNavigation` 一路把 `request.isForMainFrame` / `request.isRedirect` 传下来）：

- **子框架（iframe）的导航不移交**——一个嵌进来的广告不该把用户从页面里带走；scheme 处理不受影响，`tel:` 链接在 frame 里照样能到确认面板。
- **服务器发的 `30x` 不移交**——用户点的是他看到的那个链接，跟着重定向走出应用等于在**一次已经做完的**点击底下把页面换掉，再把它报告成用户从没选过的浏览器切换；而且重定向链里 `from` 描述的是**正在重定向的文档**，跨站比较此时没有可靠基准。

**判定不出来就不移交**：解析不出主机的地址一律当同站。两条各有一个测试钉住。

### 五、四条「开到别处」的路合流为一条

开关移交、错误页的「用其他浏览器打开」、工具栏动作、重试条——用户眼里是同一个请求：**这里读不了，去别处读**。它们以前有**两份各自实现**，于是一个应用可以一边拒绝交出一个它明明有浏览器可用的地址，一边在真没有浏览器时安静地什么都不启动。

现在全部经过 `ui/BrowserScreen.kt` 的 `openElsewhere(context, url, s)`（`:206`），`util/FileIo.kt` 里那个只发一条 `ACTION_VIEW`、**什么都不回报**的 `openExternally` 已删除。**取消选择器不会闪退、也不会留下一张空白页**：移交失败时回落到内置 WebView 打开并给出提示。连点去重由 `HandoffGate`（`BrowserScreen.kt:169`）负责——同一个地址的重复请求是**同一次点击到了两次**，不是第二次请求。

开关状态只有一个来源：菜单里的勾与 `LumoWebView.externalizeCrossSite` 读同一个偏好（`data/SettingsRepository.kt` 新增 `browserCrossSiteExternal`，DataStore 持久化、有默认值、升级不失败）。关：http/https 全部留在内置 WebView；开：`alwaysChoose = true`，走**系统自己的选择器**。

### 六、「菜单点了没反应」的真因是工具栏在用户开口之前就把自己收起来了

导航一开始就会隐藏 chrome，而**页面自己发起的重定向或 `history.pushState` 会在同一次点击的同一帧里做到这件事**——用户看到的是「菜单弹不出来」，不是「菜单坏了」。

修法是把隐藏条件接上 `menuExpanded` / `diagnosticsOpen`：**用户正在读的东西不从他手里抽走**（`LaunchedEffect(state.url) { if (!menuExpanded && !diagnosticsOpen) chromeVisible = false }`）。

### 七、无障碍设置入口的第三次同型预检

`AccessibilityStatus.canOpenSettings` 是同一个病的第三种写法：`settingsIntent().resolveActivity(context.packageManager) != null`，查不到就回「此裝置不提供無障礙設定」，按钮变成死按钮。而调用方 `MainViewModel.openIntent` **本来就已经** `catch (ActivityNotFoundException)` 并给出**一模一样**的提示——那道预检既多余又有害。

`canOpenSettings` 整段删除，`openAccessibilitySettings()` 缩成 `= openIntent(AccessibilityStatus.settingsIntent())`。用户看到的文案一字未变。

### 八、两个只有在读 AOSP 之后才会发现的细节

这两处都不是「不好看」，是**看起来完全像会生效**：

1. **`EXTRA_EXCLUDE_COMPONENTS` 放错了层。** `Intent.createChooser`（`Intent.java:1049-1083`）只复制 `EXTRA_INTENT`、标题、sender 与四个 `FLAG_GRANT_*` URI 权限旗标，**不复制 extras、也不复制任何 `FLAG_ACTIVITY_*`**；读这个 extra 的是 **`ChooserActivity`**，读的是**chooser 自己**的 extras（`ResolverActivity` 在 API 30 与 34 里都是 0 引用）。旧代码把 extra 放在被包裹的 target intent 上，被无声丢弃——**「排除 LUMO 自己」从来没有生效过**。现在 extra 放在 `chooser()` 自己身上，`theChooserCarriesItsExclusionsOnItself` 同时断言「在 chooser 上」和「不在 target 上」。
2. **查询用的 flags 太松。** `queryIntentActivities(intent, 0)` 会把**没有 `CATEGORY_DEFAULT`、实际上永远启动不了**的 filter 也算成候选（平台在启动隐式意图前会强制加上 `MATCH_DEFAULT_ONLY`，`ActivityTaskSupervisor.java:744-745`），于是「只有一个能启动的处理器」被算成两个，凭空弹出一个选择器。两处查询都改成 `PackageManager.MATCH_DEFAULT_ONLY`，`aHandlerThePlatformWouldNotStart_isNotCountedAsAChoice` 钉住它。

顺带修正 `AndroidManifest.xml` 里 `<queries>` 的一段**假注释**：它原先声称「无 data 限制的 `ACTION_VIEW` 是覆盖全部处理器的签名」。**不是。** 平台是把 `<queries><intent>` 当**意图**去 match 候选的 **IntentFilter**（`AppsFilterUtils.java:187-192`），而没有 data 的意图**只 match 完全没有 `<data>` 的 filter**（`IntentFilter.matchData:1652-1657`）——所以它碰不到 `taobao:` / `weixin:` 这类声明了 scheme 的处理器。**没有任何功能依赖它碰到**：`startActivity` 不像查询那样受过滤，自定义 scheme 一样会交出去，过滤只让面板报不出名字，而面板本来就会说「由你选择的应用」。`QUERY_ALL_PACKAGES` 仍未持有。

### 九、设置页删掉「权限与隐私」分类

按简报要求移除该分类及其中读不出结果、点了不知道发生什么的入口（无障碍入口改从「隐私」分类进入，功能本身保留）。**底层权限检查与安全校验一律保留**：最小权限、不忽略 HTTPS 证书错误、不默认上传任何数据这些约束都在代码里，而不是在设置页的说明文字里。三语言各删 3 条字符串（**431 → 428**）。

### 十、测试

**527 例 / 33 个类**（1.05 为 505 例 / 32 类），全部通过，**0 失败 0 错误 0 跳过**。新增 `ExternalLauncherTest`（18 例），`CrossSiteLinkPolicyTest` 25 → 27，`SettingsRepositoryTest` 10 → 12。`assembleDebug` 与 `testDebugUnitTest` **0 个 `e:`、0 个 `w:`**。

**三语言字符串一致性是量出来的，不是看出来的**：三份 `strings.xml` 各 428 条，名称集合三方对称差 0，`%d`/`%s` 格式参数不匹配 0，`app/src` 里 426 个 `R.string.` 引用全部可解析，无 BOM、纯 CRLF。

⚠️ **诚实边界**：这台环境**没有设备也没有模拟器**（`adb` 不存在，SDK 里没有 system image），所以上面所有修法都只到「编译通过 + 单元测试通过 + 静态审查」。**一次真实的「网页 → App」点击只能由真机回答**：系统选择器是否真的出现、取消后页面是否原样保留、`taobao:` 这类自定义协议是否真的落到对应 App、以及取消后「回落内置 WebView」的提示是否如期出现。详见 [`docs/TESTING.md`](docs/TESTING.md) 的未测试清单。

还有一条必须写在这里的边界：**1.06 的构建是增量构建，没有做 1.05 那样的 `clean --no-build-cache` 冷构建。** 所以「0 个 `e:`、0 个 `w:`」在 1.06 的正确读法是「**本轮改动的代码零警告**」——`> Task :app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 确实不是 `UP-TO-DATE`，25 个改动文件全部落在这两个源集里，但增量编译不会重新打印未改动文件里的旧警告。1.05 那一次是无条件结论，这一次不是。[`docs/TESTING.md`](docs/TESTING.md) §7.0 与 [`docs/BUILD.md`](docs/BUILD.md) §8.0 都把这条写在正文里，不留限定语在脚注。

### 十一、交付物与文档

- **`dist/LumoBrowser-v1.06-debug.apk`** —— 19,356,507 字节，SHA-256 `12C0E108715182AD491BFABD4195E283848B3768CA3D2F67ED94802524BD63F2`。`aapt2` 读出 `versionCode=8` / `versionName=1.06-debug`；`apksigner` 读出 v2 方案签名、`CN=Android Debug`（**Debug 签名，不是正式发布签名**）。
- **`dist/LumoBrowser-v1.06-source.zip`** —— 239 个文件，含 76 个音效素材与 14 份构建日志。`dist/SHA256SUMS.txt` 登记 16 个交付文件并逐个重验通过。
- **`_verify106.py`** 输出 `VERDICT: OK -- nothing to report`。它比 1.05 的校验脚本多做了三件相反方向的事：**断言被删掉的东西真的不在 APK 里**（`privacy_note` / `category_privacy` / `privacy_note_body` 在 `resources.arsc` 里全部 GONE）、**断言出厂的那份 `ExternalLauncherTest.kt` 里真的写着两处根因的回归**（「文件存在」与「文件里有那个回归」是两个不同的主张）、**断言 1.06 唯一新增的类 `com/lumobrowser/app/ui/HandoffGate` 在 `classes.dex` 里**。另有一条反向隐私检查：**APK 不持有 `QUERY_ALL_PACKAGES`**。
- **`docs/TESTING.md`**：新增 **§0.0 当前交付（1.06）** 实测数字表、**§0E 1.06 验收标准覆盖表**（A–H 八组）、**§7.0 1.06 构建与测试记录**；原 §7.0–§7.4 顺延为 §7.1–§7.5。**`docs/BUILD.md`**：新增 **§8.0 1.06 构建记录**，原 §8.0–§8.5 顺延为 §8.1–§8.6。两处顺延连带的交叉引用（`TESTING.md` §7.1 校验表、`BUILD.md` §8.1 / §8.5 / §7.2）已一并改正。
- 顺带修掉一处**悬空引用**：`TESTING.md` §0B 的 v2.2 表格里写着「观感与首帧延迟未测试（§8.2）」，而 `TESTING.md` 根本没有第 8 节——那个编号指的是 `BUILD.md`，且随版本滚动早已指错。现在写明是「本文件第 4 节；v2.2 那一轮的构建留档在 `BUILD.md` §8.4」。
- 设计文档同步到 1.06：[`docs/EXTERNAL_BROWSER_POLICY.md`](docs/EXTERNAL_BROWSER_POLICY.md) 重写（新增 §2.6「服务器发的重定向不算『离开』」、§2.7「判定不出来就不移交」、§4.1「两条根因」、§4.2「四条路现在是同一条」），[`docs/WEB_NAVIGATION.md`](docs/WEB_NAVIGATION.md) 更新数据流与函数坐标。**`WEB_NAVIGATION.md` 里原先有一句假话被删掉了**：它说「LumoBrowser 自己的 `MainActivity` 声明了 http/https 的 `VIEW` 过滤器」——manifest 里从来没有这一条。
- **这一轮的校验脚本自己也造出过一次假警报**：`aapt2` 在 Windows 上输出 CRLF，而探针里的 `$` 锚点没考虑 `\r`，于是每个资源 id 都解析成 `None`，报告里出现 `MainActivity android:theme = None`。看起来像 APK 有问题，其实是探针有问题——**和 1.03 那次「UTF-8 搜索 UTF-16 字符串池」是同一类病**（一个看起来在检查、其实永远为假的判断）。修法写进了脚本注释。

---

## 1.05 — versionCode 7

这一轮没有一句「打不开」那样的暴击，而是五个**看得见但不致命**的毛病，加一个用户点名要的新开关。五个里有两个查到最后发现是**两个地方对同一件事各记了一份**。

### 一、按钮的阴影、形状与涟漪

**症状**：按钮（尤其是卡片上的小图标按钮）底下是一块**方的暗色块**，阴影范围比按钮的可见边界大。

**真因不在颜色，在裁剪顺序。** `Modifier.shadow()` 的模糊量约等于 elevation，默认 `buttonElevationDp = 4`。原来的修饰符链是 `.shadow(...)` → `.clip(shape)` 挂在**同一个节点**上，而 1.04 为了挡住溢出的涟漪，给外层触控节点也加了 `.clip(shape)`——于是那个 4dp 的模糊被切在了圆角方框的边缘上，柔和的投影变成了**硬边的深色方块**。

**修法只有一条规则：`.shadow()` 写在 `.clip()` 之前。** 修饰符由左至右嵌套，阴影写在前面时，它由裁剪之外的图层绘制。为此把 `lumoButtonSurface` 拆成两个函数：

- 新增 `Modifier.lumoButtonShadow(style, shape, enabled)`——只负责投影，且 `clip = false`；
- `lumoButtonSurface` **去掉了 `enabled` 参数**（否则会留下一条 `Parameter 'enabled' is never used` 警告），只剩下裁剪、填充、边框和高光。

四个按钮调用点（`LumoIconButton` / `LumoPrimaryButton` / `LumoTextButton` / `LumoChip`）全部改成「阴影先、裁剪后」。**涟漪改用 `Modifier.indication` 挂在视觉节点上**，外层触控节点的 `indication = null`——这样涟漪由它自己所处的那个已经裁剪过的节点绘制，而不是由未裁剪的 48dp 触控目标绘制。`LumoPrimaryButton` 原本**完全没有裁剪**，涟漪本来就溢出圆角，这是同一张报告的另一半。

顺带修掉一个只有放大才看得见的毛病：禁用按钮的填充淡到 45%，**边框却仍是满强度**，一圈清晰的描边围着褪色的形状，读起来像「启用着但是空的」而不是「不可用」。新增 `LumoButtonStyle.borderFor(enabled)` / `quietBorderFor(enabled)`，填充、描边、文字一起淡出；**渐变色边框不动**——它没有单一的 alpha 可调，半淡化等于发明第二个渐变。

### 二、音效在震动模式下整个应用无声

**症状**：设置里音效是开的，点按钮没有任何声音。不是小，是完全没有。

**真因是两个流不是同一条。** `SoundSynth` 用 `AudioAttributes.USAGE_ASSISTANCE_SONIFICATION` 建 `SoundPool`，AOSP 把它映射到 **`STREAM_SYSTEM`**（`AudioAttributes.java:1776-1783`）；而它的自检 `isAudible()` 读的是 **`STREAM_MUSIC`**。

`STREAM_SYSTEM` 在铃声为**静音或震动**时都被强制静音——`AudioService.java:6168` 逐字就是 `final boolean ringerModeMute = ringerMode == RINGER_MODE_VIBRATE || ringerMode == RINGER_MODE_SILENT;`，`:7536-7540` 的默认遮罩里明确含 `STREAM_SYSTEM`。`STREAM_MUSIC` 不受影响。

于是：**手机只要在震动模式（极常见），整个应用一点声音都没有，而自检仍然回 `true`，设置页仍然显示音效开启，没有任何日志。** `STREAM_SYSTEM` 的音量还被别名到 `STREAM_RING` 且默认满格，所以样本根本没被衰减——**任何读音量索引的判断都不可能发现这件事**。Stack Overflow 60303150 是同一个坑的已知案例，提问者的配置与本项目逐项相同。

**修法四处：**

1. **换到不被铃声模式静音的流**：`USAGE_MEDIA` + `CONTENT_TYPE_SONIFICATION` → `STREAM_MUSIC`。这正是硬件音量键控制的那条流，用户想听就能听到、想关就能关。SoundPool **从不申请音频焦点**，所以它不会打断、不会 duck、不会暂停用户正在放的音乐——不申请焦点就不触发 ducking，两条流直接在 `STREAM_MUSIC` 上相加。
2. **`playbackStream` 从同一份 `attributes` 推导**（`attributes.volumeControlStream`），杜绝「同一件事写两份」——那正是 1.04 的错。
3. **`RINGER_MODE_VIBRATE` 与 `RINGER_MODE_SILENT` 同等看待。**
4. **判定逻辑抽进 `SoundGate`**（纯 JVM，不碰任何 Android 类），`play()` / `preview()` / `status()` 全部走同一个门，并返回被拦下的**原因**（`SoundBlock`），失败时写一行去重的日志。设置页新增的「音效检查」行查的就是**同一个门**，所以界面不可能在引擎丢弃每一条音效时说「正常」。

### 三、启动先黑屏，再出现启动动画

**症状**：点图标后是一段**没有任何内容的深色矩形**，之后才是品牌标记淡入。

那段空窗期是真实的：窗口背景从进程 fork 就存在，而第一个 Compose 帧要等 `onCreate` → `setContent` → 组合 → 布局 → 绘制整条链走完，这段完全由系统展示。1.04 只声明了一个主题，可见属性只有一条纯色 `windowBackground`，所以系统能画出来的就是一个空矩形；应用内动画再叠 **2340 ms**，把开场拉成了两秒多。

**修法**：引入 `androidx.core:core-splashscreen`，新增启动主题 `Theme.LumoBrowser.Starting`（父主题 `Theme.SplashScreen`），让**系统启动窗口**在 `onCreate` 之前就画出品牌标记，背景色与 Compose 启动遮罩取同一个 `@color/lumo_backdrop`（`#FF06070E`）所以交接是同一块面；`MainActivity.onCreate` 第一句调 `installSplashScreen()`（必须在 `super.onCreate` 之前）；应用内三段动画从 960/1720/2000 **收到 260/400/480 = 1140 ms**，顺序与比例保持。

**刻意没做两件事**：不用 `setKeepOnScreenCondition`（它的语义是「把第一帧挡在后面」，而这个应用已经为「为动画强等」付过一次代价，等于把黑屏风险从「一开始」搬到「条件不满足时」）；不靠延长动画掩盖空窗。

`values-night/themes.xml` 是**新建**的，夜里背景色**没有**翻成浅色——LumoBrowser 的默认外观由用户偏好在 Compose 里决定而不是系统 `uiMode`，翻成浅色只会让它与要交接的遮罩对不上。这个文件买的是「不碰巧继承」。

### 四、跨站链接开关（新增，默认关）

右上角三个点菜单新增第四项「网站跳转」。**默认关，升级不改变任何人的浏览方式。**

- **关**（默认）：所有网页链接都在 LUMO 内打开，与 1.04 完全一致。
- **开**：跳到**其他站点**的链接交给系统浏览器；同站链接（含二、三级页）仍然留在应用内。

**跨站判定是刻意的近似**，用「后两段标签」当站点 key，没有 public suffix list。所以 `a.co.uk` 和 `b.co.uk` 会被判为同站。**这个错误方向是选过的**：判成同站（假阴性）的代价是一条本可以交出去的链接留在了应用里，用户仍在读他想读的东西；判成跨站（假阳性）的代价是在用户读到一半时把他踢出去。**低报可以承受，高报不行。** 这条已知错误被写成了断言（`a two-label key merges co uk domains and that is the safe direction`），免得下一个人把它「修好」而把方向翻过来。

同理，**任何解析不出来的地址一律视为同站**——一个读不懂的地址永远不构成「把用户送出应用」的理由。

`hostOf` 是手写解析，不用 `java.net.URI`（它会对路径里有空格、或主机名含非 ASCII 的完全正常地址抛 `URISyntaxException`），也不能用 `android.net.Uri`（这个文件要在没有 Android 类的 JVM 上跑测试）。userinfo 按**最后一个** `@` 切——按第一个切会把 `https://example.com@evil.com/x` 读成 `example.com`，那等于把一个页面指向别处的链接留在应用里。

**这个开关只在两个浏览器之间移动网页，不改变「打开 App」的方式。** `tel:` / `mailto:` / `market:` / `intent:` 从来不经过跨站判定，无论开关开还是关都走既有的确认面板。启动本身用 Android 自己的解析器（`queryIntentActivities`，不是 `resolveActivity`；`EXTRA_EXCLUDE_COMPONENTS` 排除自己；**绝不用 `Intent.parseUri`**，它听 `component=` 和 `launchFlags=`，是 intent 重定向向量），所以弹出来的清单恰好就是这台设备上实际安装的浏览器，没有任何条目被凭空造出来。

**跨站移交没有二次确认面板**：用户把开关打开，本身就已经表达了「这些链接交出去」的意愿，每条再问一次就是简报里点名的第二个弹窗。

### 五、设置页的音效状态与试听反馈

音效设置区新增一行**真实状态**：被拦下时说明原因（已关闭／这类音效已关闭／上面的音量为零／手机处于静音或振动／媒体音量为零／设备没有可用音频输出／音效仍在载入／设备拒绝播放），放行时说「正常」。下面一行显示实际读取到的媒体音量。

**试听按钮也改成说实话**：`previewSound` 现在返回 `SoundBlock?`，试听失败会显示失败原因。这里有一个只有试听才看得见的坑——`SoundBlock.PlayRejected` 只存在于「尝试启动的那一刻」，**事后查询看不到它**，所以试听必须汇报它自己那次尝试的答案，而不是回头再查一遍状态。

试听刻意**绕过**「音效总开关」和「这类音效开关」（它存在的意义就是回答「这声音什么样」，那是开启之前的问题），但**不绕过**其余条件——一个输出音量为零的设备不该假装发出了声音。

### 六、一次编译错误，和它暴露的东西

修复音效时写了 `attributes.legacyStreamType`，编译报：

```
e: SoundSynth.kt:134:64 Unresolved reference: legacyStreamType
e: SoundSynth.kt:135:10 Type mismatch: inferred type is Unit but Int was expected
```

`AudioAttributes.getLegacyStreamType()` **不存在于公开 SDK 表面**——公开版是 `getVolumeControlStream()`，`toLegacyStreamType()` 是 `@hide` 且标了 `@UnsupportedAppUsage`。改用公开的那个之后 `BUILD SUCCESSFUL`，零 `e:` 零 `w:`。

**这条错误值得记下来，因为它差点被绕过。**「改个版本号或换个写法让它编过」在这里会写出一条只在部分设备上可用的代码；正确做法是去找公开 API 里语义相同的那一个。

### 七、字符串与测试

- 三份 `strings.xml` 从 **416 条增至 431 条**（新增 15 条：跨站开关 4 条、音效状态 10 条、其余 1 条），三份 key 集合**完全相同**。新增 `StringResourceParityTest`（6 例）把这件事**变成会失败的测试**——以前少一条翻译不会被任何测试或编译器发现。它同时检查「同一文件里同名重复」（AAPT 后者胜，等于一次没生效的编辑）和「`%1$s` 在某个语言里被丢掉」（不会崩，只会静默印错）。
- **新增 3 个测试类、45 个用例**：`CrossSiteLinkPolicyTest`（25）、`SoundGateTest`（14）、`StringResourceParityTest`（6）。`StartupTimelineTest` 里两条钉住旧时长的用例被更新，**没有删除或掏空任何断言**。
- **全量单元测试：32 个测试类 / 505 个用例 / 0 失败 / 0 错误 / 0 跳过。**
- **`gradle clean :app:assembleDebug` 通过**，`BUILD SUCCESSFUL in 1m 26s`，**零 `e:` 零 `w:`**。APK 19,340,939 字节，`versionCode='7' versionName='1.05-debug'`，minSdk 26 / targetSdk 34，四种 ABI 齐全。

### 八、版本号

`versionCode` **6 → 7**、`versionName` **"1.04" → "1.05"**。Debug 包因为有 `versionNameSuffix = "-debug"`，实际显示 `1.05-debug`。

### 九、没有验证的部分

这台环境**没有设备也没有模拟器**，所以下面这些只做到了「编译通过 + 单元测试通过 + 静态审查」，**没有在真机上跑过**，测试报告里逐项标注为「未测试」：

- 按钮阴影在真机上是否真的不再是方块（本环境无法渲染）；
- **震动模式下是否真的有声音**——这是本轮最需要真机确认的一条；
- 冷启动黑屏是否真的消失，且 **API 26–30 与 API 31+ 要各看一次**（两条实现路径不同）；
- 跨站开关拨动后系统选择器的实际样子、取消后页面是否原样保留；
- 菜单项与状态行在小屏 + 大字体下的换行。

---

## 1.04 — versionCode 6

用户的一句话是这一轮的起点：**「在手机上安装了，打不开，在优化一下」**。

这一版没有把「打不开」当成一个 bug 去猜，而是把它拆成两条**各自独立、各自可复现**的崩溃路径，分别找到根因再修。两条的性质完全不同：一条是「进程死了之后还去跟它说话」，另一条是「把已经导航过的 WebView 当成新建的弹窗容器交出去」。**只修任何一条，另一条都还会崩。**

### 一、闪退根因之一：弹窗复用了已经导航过的 WebView

这是**更贴症状的那一条**。Chromium 的 `android_webview/docs/how-does-on-create-window-work.md` 有一节标题逐字就是 **"New WebView for popup window must not have been previously navigated"**；AOSP 的 `WebViewContentsClientAdapter.java` 注释亦为「createWindow 的 WebView 必须没有加载过任何 url。不然就不能作为 createWindow 的 webview 使用。」

而旧代码做的正是相反的事：

```kotlin
transport.webView = this      // this 就是那个刚刚导航过无数次的主 WebView
result.sendToTarget()
```

`BrowserConfigManager` 里 `supportMultipleWindows = true`，所以**每一次点 `target="_blank"` 都会走进 `onCreateWindow`**。一个站点的第三、第四层页面正是站内 `target="_blank"` 链接最密的地方——「二级页正常、三级页闪退」的位置就此对上。而且失败是 **native SIGSEGV，不是 Java 异常，`runCatching` 拦不住**。

**修法**：每次弹窗请求都新建一个 scratch WebView（`LumoWebView.newPopupScratch()`），它不挂在任何视图树上、也从不被允许提交任何文档——所有导航都被取消，所以它永远停在 `about:blank`，因此任何时刻都能安全 `destroy()`。新的请求到来时先销毁旧的再替换；`retire()` 里也一并销毁。

同时把三路分派抽成 `dispatchNavigation(url, inApp)`：**点链接**和**弹窗要打开的地址**现在走同一份判定。抽出来的理由不是省代码，而是如果只把分类写在 `shouldOverrideUrlLoading` 里，弹窗路径就会自己长出一份副本，两份迟早会对「哪些协议留在应用内」给出不同答案。`inApp` 参数在两个入口下语义不同，这个差别是有意的：点击链接时留空（交还给 WebView 才能保住 history、重定向和 POST body），弹窗时传 `load(url)`（弹窗没有正在飞的导航可以交还）。

### 二、闪退根因之二：渲染进程死后回调了尸体

`WebView` 是 Java 对象，比渲染进程活得久。渲染进程被系统杀掉之后，`WebView` 上的每个方法**仍然会正常返回**，但每一次调用都要跨进一个已经不存在的进程；这种失败同样是 native abort。**唯一的防御是不要调用。**

旧代码在 `onRenderProcessGone` 里做了三件危险的事：调 `reportError()` 构造 `BrowserState`（要读 `url`/`title`/`canGoBack()`/`canGoForward()`/`copyBackForwardList()`）、回调宿主让它 `removeView`、然后 `destroy()`——而 Compose 之后还会释放第三次。

**修法**是一个退役状态机（`retired` / `destroyed` 两个独立布尔量，`retire()` / `retireAndDestroy()`），每个会跨进渲染进程的入口都加 `if (retired) return`。两处容易被忽略但很关键：**`emit()` 的求值顺序**（原来是 `onState?.invoke(BrowserState(...))`，Kotlin 会先把整个引数清单求值完才发现没人可通知——也就是对一具尸体照做了一整套跨进程调用；现在先取出回调再构造状态），以及 **`AndroidView` 的 `onRelease`** 成为全 App 唯一的销毁点。

完整机制、两个入口的分派、以及未执行的验证项见 [`docs/WEB_NAVIGATION.md`](docs/WEB_NAVIGATION.md) 第三节。

### 三、外部软件跳转：先问，再交出去

在此之前，网页里的 `tel:` / `mailto:` / `market:` / `intent://` 链接要么被一律报成错误（旧版把所有非 http 的 scheme 都当「协议被拦截」），要么在 `ShareUtil.openExternally` 里无声地拉起另一个 App。

这一版建立了一条不变量：**`LumoWebView` 永不启动 activity、永不自行解析 intent；全 App 只有一处能造成外部效果，而那一处会先问用户。** 点这类链接现在会从底部滑出一张 Material 3 确认面板，写清楚「要打开什么」和「由谁打开」，用户确认后才交给系统。

三个值得记的决定：

- **`intent://` 由本项目的解析器重建，绝不用 `Intent.parseUri`。** 平台的解析器会吃 `component=` 和 `launchFlags=`，那正是 intent redirection 的入口；而本项目的 `parseIntentUrl` 把这两个字段显式忽略，并且**语法合法但无法构成 Intent 的地址会被整体丢弃**，而不是递一个半成品给平台去宽松解读。
- **每次启动都把 LUMO 自己排除掉。** 本应用注册了 http/https 的 `VIEW`，不排除就会「解析到自己」——用户按了确认，什么也没发生。
- **确认按钮的可用性不依赖预检结果。** `canHandle` 是一次 `PackageManager` 查询，Android 11+ 的包可见性会让它看不到没有在 `<queries>` 里放行的包。如果拿它当 `enabled` 的条件，一次预检失误就会让一个**本来能用的链接永久按不动**。所以预检只影响文案，真正的答案由点击后的 `HandoffOutcome` 回报。

新增 `browser/WebNavigationPolicy.kt`、`browser/IntentUrl.kt`、`browser/ExternalLinkOffer.kt`、`util/ExternalLauncher.kt`、`ui/ExternalLinkSheet.kt`，三语各 16 条新字符串，以及 `WebNavigationPolicyTest`（34 例）。

### 四、按钮规范：形状、涟漪、禁用态

报告是「按下去的反馈和按钮的形状对不上」。审计之后发现根因不是某一条修饰符写错了，而是**同一件事在三个按钮里各写了一遍**，三份副本各自漂移。这一版修掉三处：

- **`LumoIconButton` 的涟漪溢出形状。** `clickable` 挂在 48dp 触控区上，而按钮的视觉形状是里面那个 44dp 圆角容器，外层的 `clickable` 之前没有 `clip`，涟漪于是按 48dp 的方形铺开、从圆角外面溢出来。加 `.clip(shape)` 即可——**`clip` 只影响绘制，不影响指针输入**，所以 48dp 的触控区原样保留。
- **禁用态只淡填色、不淡描边。** 填色降到 45%，轮廓却保持全强度，围着一个变暗的形状，看起来像「能按，只是内容空了」而不是「不可用」。新增 `borderFor` / `quietBorderFor` / `fadedIf`，让边框与填色一起走。**渐变描边刻意不动**：它没有单一 alpha 可调，淡化等于替用户发明第二个渐变。
- **色票的涟漪硬编码白色。** 八个自定义颜色里有三个是近白，白涟漪画在白底上等于没有反馈。改成从色票本身派生（`readableOn`）。

完整规范（正常 / 按压 / 禁用 / 主题切换 / 触控区域五态、修饰符链为什么必须是这个顺序、形状 token 与阴影规则）见新文档 [`docs/BUTTON_SPEC.md`](docs/BUTTON_SPEC.md)，配套回归测试 `ButtonStateTest`（8 例，纯 JVM）。

### 五、设置页重构与功能审查

设置页从「一整条平铺长列表」改成系统设置那样的**两层结构**：进入时是八个分类卡片，点进去是分类内的分组卡片，标题栏有返回。

- **新增设置搜索。** 顶部搜索框可以按中文或英文关键字直接跳到任意一项设置，支持多字词与「无结果」态。配套 `SettingsSearchTest`（31 例，1.04 新增）。
- **破坏性操作统一走二次确认对话框。** 之前有的操作确认、有的直接执行；现在复位、清除数据、删除分类等一律先弹同一个对话框。
- **删掉三个假开关。** `defaultBrowserMode`（默认浏览模式）、`checkNetworkBeforeOpening`（打开前检查网络）、`rememberPerSiteNetworkAssist`（记住每个网址的网络辅助选择）——这三个设置在界面上能点、能存、能持久化，**但整个 `app/src/main` 里没有任何一处读它们**。每一个都被追到「声明 + DataStore key + 读写 + setter + ViewModel 方法 + 接线」这条链路的尽头，而链路的终点是空的。留着比删掉更贵：用户会以为它生效了。三个底层字段（`data/Model.kt:347`、`:376`、`:380`）**本轮没有清理**，它们在孤儿清单里留档——删字段要动 DataStore 的序列化路径，升级安装时有把用户既有设置弄丢的风险，而收益是零。
- **删掉两条死字符串。** `browser_address` 与 `browser_loading`，全树零引用。三份 `strings.xml` 各 418 → 416 条，删后三份的名称集合仍然完全相同、全树 `dead=0`。
- **顺带清掉三条 Kotlin 警告，而且没有用 `@Suppress`。** 上面三个假开关的 UI 删掉之后，它们对应的回呼参数成了死参数（`w: .../ui/SettingsScreen.kt:180:5 Parameter 'onDefaultBrowserMode' is never used` 等三条）。处置是**删参数**而不是压制警告：`@Suppress("UNUSED_PARAMETER")` 作用在整个函数上，会一并关掉这条诊断——而 1.03 那一轮正是靠它抓到 `notePressed(attempt)` 收下参数却从不读、与 `AccessibilityTextField.onSubmit` 从未被调用这两个真缺陷。为了三个死参数的安静，关掉一个抓过真 bug 的诊断，代价不对等。

完整审查（58 个偏好字段逐个的读者追踪、逐条裁决、原始追踪记录）见 [`docs/FEATURE_CLEANUP.md`](docs/FEATURE_CLEANUP.md)；两层结构的地图与 14 条路由见 [`docs/SETTINGS_STRUCTURE.md`](docs/SETTINGS_STRUCTURE.md)。

**一次被自己抓到的假绿，一并记在这里。** 设置页重构落地之后我跑过一次全量测试，得到 **429 例 / 0 失败**，一度把它当成「重构没破坏东西」的证据。**那是错的。** 那次运行启动于 23:39，而重构 23:43 才落盘——Gradle 早就把旧版 `SettingsScreen.kt` 编译完了，它测的是**重构前的界面**。针对新代码真正跑出来的是 **460 例 / 5 失败**：4 条是旧冒烟测试还在按「一整条长列表」的假设点控件（新结构里那个控件还没进语义树），1 条是新搜索测试自己的夹具写错了。5 条全部修好之后，才有了最终那个绿。

留在这里而不是删掉，是因为**「测试通过了」这句话是在什么代码上通过的，比通过本身更重要**——尤其当一个版本的全部起因，就是一句「安装上了，但打不开」。

### 六、版本号

`versionCode` **5 → 6**，`versionName` **`1.03` → `1.04`**（Debug 变体显示 `1.04-debug`）。`applicationId` 与签名配置不变；DataStore 与 Room 的既有数据无需迁移——本版只新增偏好项，没有改动任何已有 key 的含义或类型。

---

## 1.03 — versionCode 5

版本号从 `2.2` 改回 `1.03`：这个包不是 v2.2 专项任务的收尾，而是**接在 1.02 之后的第三个交付版本**——中间那轮 v2.2 的序号把版本序列拉离了发布节奏，本次拉回来。所以 `versionCode` 从 4 进位到 **5**，`versionName` 从 `2.2` 改为 **`1.03`**（Debug 变体实际显示 `1.03-debug`）。

本轮只有一件事，但它是那件一直挂着的：**无障碍辅助服务从「明确不做」变成「已实现」**。

### 无障碍辅助服务（规格 2.3）：从记录「不做」到真正落地

2.2 时这一条记的是**不做**，理由写在 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md) 第四节：规格要的三个入口（打开系统设置 / 检查授权状态 / 关闭辅助功能）依赖一个真实存在、可被授权的服务，而当时没有服务——「为一个不存在的服务做授权状态检查，只能检查出一个恒为 false 的值，那不是功能，是装饰」。那个判断现在依然成立；变的只是服务真的存在了。

完整说明见新文档 [`docs/ACCESSIBILITY_ASSIST.md`](docs/ACCESSIBILITY_ASSIST.md)。这一版新增六个源文件、一份服务配置、三语共 46 条新字符串，以及三个测试文件。

#### 它做什么

用户在 LUMO 里指定某个 App 里的某一个按钮，LUMO 可以替他**按一次**。典型场景是 VPN 客户端——系统授权对话框已经同意过，但每次都要去另一个 App 点一下「连接」。

刻意**不是**：不是自动化脚本引擎、不是按键精灵、不是「帮我在所有 App 里找按钮」。目标必须逐条由用户声明，一次尝试只针对一个目标，按下一次就结束。

#### 安全边界：权限很大，行为很小

- **一次只有一个尝试，而且会过期。** `AssistAttempt.WINDOW_MS = 12_000L`、`MAX_PRESSES = 1`。尝试由一次明确的用户动作武装，由五条条件之一结束（按了一次、超时、用户离开、服务被关、调用方主动解除）。**超时检查在包名比对之前**——有专门的测试钉住这一点：同一个「错误的 App」输入，未过期时返回 `NoTarget`，已过期时返回 `Exhausted`，证明过期的尝试连「这个包是不是目标」都不会去问。
- **有一批 App 永远不碰。** `isForbiddenAssistPackage` 覆盖六个平台套件以及 LUMO 自己。理由很直接：**用户最可能顺手声明成目标的，恰恰是同意对话框上的那个「确定」**。让辅助服务去点同意对话框，等于把「用户同意」变成一句空话。
- **服务配置每一项都取最窄值。** 刻意没有 `flagRetrieveInteractiveWindows`（会暴露系统持有的所有窗口；服务只读 `rootInActiveWindow`，正好是屏幕上那一个）、没有 `flagRequestFilterKeyEvents`（会投递每次按键；这个功能从来不需要知道任何人打了什么字）、没有 `flagRequestTouchExplorationMode`、没有 `flagRequestAccessibilityButton`、没有 `flagIncludeNotImportantViews`。只留 `flagReportViewIds`，它给本来就在读的节点附上 view id，不开启任何新能力。
- **不是独立进程。** 和 `LumoVpnService` 同理：`AccessibilityAssistRuntime` 是进程级 `StateFlow`，和界面侧武装尝试的调用共享；放进独立进程，界面武装的尝试服务根本看不见。
- **不读输入框。** 走节点树时显式跳过 `isEditable` 或类名含 `EditText` 的节点，不落盘、不记录，找到目标按一下就结束。
- **默认是三态而非布尔。** `AssistSettings.switchState()` 是 `Boolean?`，默认 `null`（从未表态）而不是 `false`（看过并关掉）。把「没问过」渲染成「已拒绝」是另一种不诚实。

#### 三个真实缺陷（都是新测试抓出来的）

1. **`isForbiddenAssistPackage` 漏掉了自己。** 原写法比对裸常量 `com.lumobrowser.app`，但 debug 变体的 applicationId 是 `com.lumobrowser.app.debug`——**「开发者手机上实际安装的那个变体」成了唯一漏网的包**，而失败是静默的：该抓它的检查本身才是错的。同文件里 `isLumoPackage` 早就同时涵盖两种拼法，只是没被用上。
2. **`AssistTargetCodec.unescape` 吃掉反斜杠。** 未知转义分支原本 `else -> append(escaped)`，把存下来的 `C:\vpn` 变成 `C:vpn`，而同一段的注释却声称「原样保留」。现在是 `else -> append('\\').append(escaped)`。
3. **`isEnabledViaSecureSetting` 永远认不出相对拼法。** 修复前的写法假定可以自己把相对拼法补全，但 `ComponentName.unflattenFromString` **自己就会把前导 `.` 对条目里的 package 展开**，而 debug 变体的 package 是 applicationId，展开后比真正存在的类名多一段——于是**用户已经在 Android 设置里打开的服务，被 LUMO 报告成关闭**。这是 KDoc 里写明的那个失败模式：false negative 让用户损失一个他明明已经打开的功能，而且他会以为是 LUMO 坏了。修复改为比对 Android 不会改写的那条尾巴（类名 + 它上面那段包名），并且因为调用方已先要求 package 是自己的 applicationId，比对天然被限制在自身范围内。

#### 定稿轮：编译器警告指出的两处死接线

前三个缺陷是测试抓出来的。这两个不是——**是编译器说的**。`c28.txt` 的构建日志里躺着两条 `Parameter 'x' is never used`，看起来像噪音，其实是两处「声明了、传进去了、名字也说明它该做什么、但从来没有被调用」的接线：

4. **`notePressed(attempt)` 收下参数却从不读它。** 参数存在是为了让调用方表明「我按的是哪一次尝试」，但它从未被核对，于是「一次回调里不可能换掉 attempt」这个假设从未被检查过。真出问题时失败形态是**静默且张冠李戴**的：一个属于已被作废的尝试的按下，会被报告成**当前**这次尝试成功了。现在核对，对不上就丢弃——既不改结果，也不顺手解除用户正在看的那次尝试。补了一例 `aPressFromASupersededAttemptIsDroppedRatherThanMisattributed`。
5. **`AccessibilityTextField` 的 `onSubmit` 从未被调用。** 调用方传的是一个能正常保存目标的 lambda，`imeAction` 也正确设成了 `ImeAction.Done`——**但键盘上的「完成」键按下去什么都不会发生**，因为缺的是 `KeyboardActions(onDone = ...)`。用户输入完按钮文字、按下完成、界面毫无反应，只能自己去找下面那个独立的「添加」按钮。功能没坏（那个按钮是真的），坏的是一条**看起来通、其实断的捷径**。两条死接线都不会让任何测试变红，也不会让任何构建失败——它们只会让一个用户按下去没有反应。

> 这两条值得单独记一笔，是因为它们的**发现方式**：构建日志里那两条 `never used` 警告在前几次构建中一直存在，一直被当作背景噪音。**一条总是出现、又从不影响结果的警告，会自然退化成没人读的装饰**——和这一版反复遇到的是同一种东西。

#### 顺带关掉的一个旧缺口

- **`startupTimeline()` 有测试了。** 2.2 遗留清单里的第 ⑧ 项。12 例，覆盖：强度为 0 时返回 `null`（是**跳过**而非缩短）、负强度、超过 1 的强度被夹取而非放大、**每个阶段都不少于 1 毫秒**（`coerceAtLeast(1)` 存在的理由——阶段为 0 不是更短的动画而是**顺序违例**）、九档强度下阶段边界都严格落在时间轴内部、满强度时三个阶段落在规格点名的窗口内（品牌 ≤0.5s、灯光 ≤1.4s、交棒 ≤2.4s）、阶段长度单调、三个预设都能播。

#### 测试

```bash
cd LumoBrowser
gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.accessibility.*"
gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.ui.components.StartupTimelineTest"
```

无障碍 **59 例**（`t13.txt`；定稿轮补了 `aPressFromASupersededAttemptIsDroppedRatherThanMisattributed` 一例，见上文第 4 条），启动动画 **12 例**（`t15.txt`），全部通过。全量 **372 例 / 0 失败**，干净重建 `BUILD SUCCESSFUL in 16m 37s`（`c30.txt`），构建日志里的 Kotlin 警告数为 **0**。

#### 交付补缺：构建日志（收尾轮）

验收要求把「Gradle 构建日志」列入交付物，而**此前这些日志从没进过任何一个交付包**——打包脚本的 `SKIP_EXT` 含 `.txt`（那条规则是为 `local.properties` 之类的东西定的），而文档正文却按名字引用了 `c30.txt`（4 次）、`t13.txt`（6 次）、`t15.txt`（4 次）、`c28.txt`（1 次）。**引用一个收件人手上没有的文件，等于没有引用**——一份「有据可查」的测试报告，如果查不到那个据，它和一句断言没有区别。

现在 `docs/build-logs/` 下有 8 份原始日志副本与一份索引（逐列给出「文件 / 时间 / 命令 / 结果 / 它证明什么」）。源码包因此从 201 档变成 **209 档**。三处配套改动：

- 打包脚本新增**只对 `docs/build-logs/` 这个前缀生效**的白名单例外（注释里写明为什么要限定前缀：这类规则的失效方式就是某次重构后悄悄放宽到整棵树）。
- 发布校验脚本 `_verify103.py` 把 8 个文件加进 `REQUIRED_ZIP`——**加断言的唯一理由是白名单是一条可以被静默取消的规则**，而它失效时不会有任何东西报错，因为缺少的正是那个本该指出缺少的东西。
- `docs/BUILD.md` §8.0 改写成 1.03 的干净重建，并新增 §9 说明日志目录。

顺带修正一处**过期的文档标题**：`docs/BUILD.md` §8.0 一直写着「v2.2 交付构建（**当前交付物**）」，而 2.2 早已不是当前交付物。同时把 1.03 与 2.2 的差别写清楚——1.03 是 `clean` 重建，2.2 是增量构建，**而增量构建下 Kotlin 不会重新打印未改动文件的警告**，所以 2.2 那份日志里的「零警告」不能作为证据。这不是细节：1.03 的两个真实缺陷正是靠 `clean` 重建里重新打印出来的警告才被发现的。

#### 明确没做

- **真机端到端验证。** 本机没有可用的模拟器或真机，节点树遍历与 `performAction(ACTION_CLICK)` 的实际效果**未被实际运行验证过**。验收表里这一条按「实现完成、真机未验」记，不记全绿。
- 2.2 遗留清单里仍未关掉的：⑦ 按钮七态的「载入中／成功／失败」三态视觉；⑨ 页面切换残影/闪白、滚动位置保留、载入状态可取消可重试、无障碍描述与字体缩放。这些在本版**没有推进**，逐条仍列在 [`docs/TESTING.md`](docs/TESTING.md) 的验收表里，状态与 2.2 时一致。

---

## 2.2 — versionCode 4

> **注意**：本节的版本号是历史记录，已被 1.03 取代。`versionCode` 4 → **5**、`versionName` `2.2` → **`1.03`**；本节描述的 VPN、音效、按钮视觉、启动动画四项功能**全部保留**，无障碍一项已由 1.03 实现。

这一轮对应 v2.2 专项升级任务：**VPN、无障碍、音效、按钮视觉、启动动画、细节打磨**。六项里有五项落地，一项明确不做——每一项的结论都在下面，包括没做的那个。

### VPN：从「解释层」重写成「观测层」（2.1 / 2.2 / 2.4 / 2.5）

**先诊断，结论是：整个 VPN 辅助功能是一个解释层，里面没有任何隧道。**

审计取证（全部可复现，见 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md)）：

- 全库 grep `VpnService|establish|ParcelFileDescriptor|setConfigureIntent|prepare(` → **无匹配**。Manifest 里只有一份 `<activity>` 和一个 FileProvider，**没有 `<service>`、没有 `BIND_VPN_SERVICE`**。
- `VpnConnectionState.Connecting` / `.Failed` 是**永远到不了的死状态**：它们由 `MainViewModel` 的 `lastVpnAttempt` 驱动，而唯一会写它的 `noteVpnAttempt()` / `clearVpnAttempt()` **零呼叫点**，该字段初始化为 `null` 后再不改动。
- `checkNetworkBeforeOpening` 与 `rememberPerSiteNetworkAssist` 是**死开关**：有偏好字段、有 DataStore key、有设置页开关、有字符串，但**从未出现在任何条件式里**。
- 「有 VPN」是**使用者自己在设置页勾的**（`vpnConfiguredByUser`），没有任何 profile 被探测过。
- 没有任何连通行探测（grep `InetAddress|openConnection|HttpURLConnection|OkHttp|Socket(|isReachable|DnsResolver` → 无匹配）。

结构性错误是两个：**把 `TRANSPORT_VPN` 这个关于装置的观测，当成本应用做出的结论**；以及**用一个从来没有写入者的旗标去驱动状态机的两个状态**。

#### 现在的实现

- 新增 `vpn/` 包五个文件：`VpnAppDirectory.kt`（方案 A 的发现层）、`VpnTunnel.kt`（profile、编解码、协议注册表、程序级状态）、`LumoVpnService.kt`（真正的 `VpnService`）、`VpnController.kt`（授权与启动）、`VpnReport.kt`（纯函数状态合成）。
- **「已连接」只有一个来源**：本应用自己建立的隧道（`TunnelRuntimeState.Up`）。只检测到系统 VPN 网络时报告 **`DetectedNetwork`**——独立枚举值而不是布林旗标，因为布林可以和「已连接」同时为真，枚举不行。规格 2.4 要求「只能检测到系统 VPN 网络存在时不得冒充已连接」，这条是它的实现。
- 状态七态：`NotConfigured` / `Disconnected` / `Connecting` / `Connected` / `DetectedNetwork` / `Failed` / `Unknown`。规格要求的六态之外多出的第七态就是 `DetectedNetwork`。
- **授权流程是真的**：`VpnService.prepare()` 每次重新问、不快取（背景期间可能被撤销）；系统同意对话框的结果**先消费再启动**，否则对话框被系统关掉时用户会面对一个关不掉的回圈。
- **`exported="true"` 是必要的**：系统以不同 UID 解析这个 component 来回答 `VpnService.prepare()`，非 exported 的 component 对那个查询不可见，后果是「明明没授权却回报已授权」。真正的闸门是 `BIND_VPN_SERVICE` 这个 signature 权限，只有系统持有。
- `START_NOT_STICKY` 是刻意的：隧道必须是使用者要的，系统在行程死后复活它等于制造一个没人能确认的「已连接」状态。
- `specialUse` 前台服务类型：Android 14 的 FGS 类型列举里**没有 VPN 成员**，`specialUse` 就是平台对「不属于列举清单的长时间服务」的答案。

#### 能不能真实建立连接：**不能**

`TunnelBackends` 的注册表是**空的**，而且这不是「暂时为空」——出货一个协议等于出货一个对真实服务器跑过的实现，而两者都不存在。规格 2.2 明确禁止只建一个 `VpnService` 类就声称 VPN 可用，也禁止虚构服务器、账号、密钥或连接成功状态。

这个事实**写成了断言而不是注解**：测试 `thisBuildShipsNoProtocolBackendAndSaysSo` 会在有人注册 backend 时变红——而那正是应该发生的事。使用者自己的服务器地址存在 `vpnProfile` 里（单笔记录、手工转义、严格解析），但**本版连收集它的界面都没有**，因为没有 backend 可拨时收集端点只是收集。

#### 方案 A：发现与打开，不猜测

不硬编任何第三方 VPN 应用的包名、action 字串或 extras 布局。理由：本次查证该公开接口未取得可引用原文（`web_fetch` 对 `github.com` 与 `raw.githubusercontent.com` 都回报 `Error: URL hostname ... resolves to a non-public IP address`），而规格 2.2 明文禁止猜测包名与接口。凭记忆捏一条 action，失败时在使用者眼里是「LumoBrowser 坏了」，而不是「这个应用不支持」。

改用**平台自己能回答**的机制：所有 `VpnService` 都必须在 manifest 宣告 `android.net.VpnService`，所以 `queryIntentServices` 能列出**真实已安装的 VPN 应用**，既不必枚举套件，也不需要 `QUERY_ALL_PACKAGES`。发现与打开（`getLaunchIntentForPackage`）已实现；**控制刻意留空**（`VpnAppControlApis` 是空清单，加一条目 = 一笔 entry 加一个测试）。

#### 有界的自动重试（2.5）

- `VPN_WAIT_TIMEOUT_MS = 30_000L`：墙钟上限，刻意不可配置。**旧的 `waitingForVpn` 完全没有逾时**，唯一的逃逸是一个从来没有写入者的失败讯号——那个分支的诚实描述是「永远等下去」。
- 「连接后重试」需要**三个条件同时成立**：使用者允许本应用发起连接、这个组建真的带 protocol backend、存在 backend 可拨的 profile。缺任何一条，那颗按钮就变成应用守不住的承诺。
- 证书错误、404、DNS 失败、脚本错误**永远不会被当成需要 VPN**（`classifyPageFailure` 的 11 个值里只有 network-related 的会触发协助提议）。

### 无障碍服务：本版明确不做（2.3）

> **这一条已被 1.03 取代。** 下面记的是 2.2 当时的决定与理由，保留原样——因为它记录的标准现在依然有效（「为一个不存在的服务做授权状态检查，只能检查出一个恒为 false 的值」）。服务已在 **1.03** 实现，见本文件顶部那一节与 [`docs/ACCESSIBILITY_ASSIST.md`](docs/ACCESSIBILITY_ASSIST.md)。

规格允许「如果不能安全实现，如实说明」。本版**没有实现无障碍服务**：manifest 里没有 `BIND_ACCESSIBILITY_SERVICE`，全库没有 `AccessibilityService` 子类、没有 `performGlobalAction`、没有 `dispatchGesture`。

不做而不是做个阉割版，理由写在 [`docs/VPN_INTEGRATION.md`](docs/VPN_INTEGRATION.md) 第四节，连带记下了未来实现必须遵守的五条约束——其中最重要的一条是：**无障碍权限不是 VPN 权限**，两者不得混为同一个总开关。那五条约束在 1.03 实现时**逐条照做，没有一条被放宽**。

### 音效：从 12 种会响变成 19 种（3.x）

- **素材全部自生成**：`tools/generate_sfx.py`（纯标准库）产出 **76 个 WAV** = 19 种 cue × 4 种风格，22050 Hz / 16-bit / 单声道，共 668,268 字节。**没有采样任何游戏、影视、其他应用或商业品牌音效。** 细节见 [`docs/AUDIO_ASSETS.md`](docs/AUDIO_ASSETS.md)。
- **四种风格走的是不同的合成参数**，不是同一个声音换四种均衡：极简柔和（缓起音、少泛音）、清脆科技（快起音、5 个 partial 到 6.1×）、轻盈玻璃感（**非谐** partial 2.76/5.40/8.93 + 长尾）、沉稳低调（低八度、暗）。
- **可复现性是修出来的**：原本用 Python 内建 `hash()` 做扰动种子，而它受 `PYTHONHASHSEED` 加盐、**每次执行都不同**——「重新生成」会得到一批内容不同、档名相同的 wav。改用自己实现的 FNV-1a 后，清空目录连跑两次逐字节相同。
- 播放引擎换成 `SoundPool`：非同步预载 76 档、`setMaxStreams(4)`、同一 cue 按自身长度去抖、未载入完成时**静音而不是报错**（早期点击不挡按钮）、**刻意不请求 audio focus**（30–450 ms 的通知音抢焦点会把使用者的音乐压下去，比它修的问题更糟）。
- **接线缺口是这一轮最实际的修复**：76 个 wav 全部出货，但**19 个 cue 只有 12 个真的会响**。`LoadStart`/`LoadSuccess`/`LoadFailure`/`VpnConnecting`/`VpnConnected`/`VpnFailed`/`LaunchEnd`/`Navigate`/`DialogOpen`/`DialogClose`/`Press`/`Release` 全部没有呼叫点。现在**全部接上**（唯一例外见下）。
- **边缘触发而非电平触发**：网页载入记住前一个相位（停留十分钟的页面不会反复宣告「已载入」）；VPN **跳过本次组合看到的第一个值**（重播旧闻不是回馈）；`DetectedNetwork` **刻意没有音效**——那是这个功能被重写掉的假「已连接」宣称的听觉版本。
- `Press` 在按下时响，`Release` **只在取消时响**。一次提交的点击会在几十毫秒后产生自己的结果音，第三个声音正是规格 3.5 禁止的堆叠；被取消的按下则是唯一一个「不会有结果音」的时刻，也正是使用者需要听到「没发生」的时刻。
- 三颗开关与规格逐条对应：**启动音效（预设关闭，因为是唯一一个没人碰任何东西就被触发、且最长的音）/ 网页载入音效 / VPN 状态音效**。`Interface` 家族刻意**没有**独立开关——给「点按钮的声音」一个开关，等于让使用者有机会关掉之后以为应用坏了。
- 试听刻意**绕过**总开关、家族开关与当前风格：试听按钮存在的唯一目的就是回答「这个风格听起来怎样」，而那是使用者**决定要不要开音效之前**问的问题。
- 已知未接线：**`SettingsSaved` 没有任何呼叫点**。本应用设置是即时生效的，每个离散控件的提交都已经产生更具体的 cue，再叠一个会变成一次操作两声。素材完整可播（可用试听验证），缺的只是一个不重复的表达时机。这一点同样列在 `docs/TESTING.md` 的未实现表中。

### 按钮：依源码定位原因，而不是统一设 `shadow = 0`（四）

规格明确禁止用「全部压平」来掩盖问题。逐条根因与修法：

- **黑色投影**：全树只有两处 `Modifier.shadow`，都在 `Surfaces.kt`，且都写死 `Color.Black`，`clip = false`，还写在 `.clip(shape)` **之前**——所以元件的 clip 裁不到阴影。现在改走主题的 `shadowAmbient` / `shadowSpot`：深色主题用低对比柔和边界，浅色主题不再有大面积黑投影。
- **浅色主题的灰色脏边**：`hairline(alpha)` 在浅色主题回传的是 **`Color.Black.copy(alpha = alpha * 0.55f)`**，于是 `hairline(0.9f)` 是黑 @ 0.495，驱动着 Outlined 卡片边框、chip 后备边框、色票边框、强调点边框。现在浅色主题的 hairline 是亮的。
- **重复阴影**：12 组父子都带阴影，最严重的是 `BookmarkCard` **在同一条 modifier 链上三层**（拖曳 `graphicsLayer{shadowElevation}` + `surfaceTreatment` + 嵌套 `LumoIconButton`）。已压平。
- **涟漪用的是 Material 2**：唯一的涟漪定义 import 的是 `androidx.compose.material.ripple.rememberRipple`，在一个 Material 3 应用里，而且 `bounded` 写死 `true`。已稽核全部呼叫点（含新增的 `HomeScreen` DragHint），确保没有无界涟漪扩散到相邻卡片。
- 按下缩放从固定值改为跟随 `MotionConfig`，沉浸预设为 `0.972f`，并加了重复提交闩锁。

### 启动动画：2.34 秒三阶段（五）

见 [`docs/STARTUP_ANIMATION.md`](docs/STARTUP_ANIMATION.md)。两个结构性决定：

1. **它是覆盖层，不是闸门。** 真实内容从第一帧起就在下面组合与布局，动画只是在它上面画一层然后揭开。这是针对本项目上一个真实故障设计的：**一个启动动画最坏的失败模式，就是它自己变成打不开的原因。**
2. **它不拥有任何指标输入。** 没有 `clickable`、没有 `pointerInput`，播到一半就开始操作的使用者仍能碰到他瞄准的控件。

阶段边界存的是**占总时长的比例**而不是绝对偏移量，所以动画强度可以整体缩放它而不会让阶段互相重叠。时间轴走完就结束，**没有任何基于总时长的补时**。只在冷启动播放：`savedInstanceState == null && !LumoApplication.startupAnimationShown`，旗标活在 Application 上（用 Composition 状态做不到，因为被重建的正是 Composition 本身）。返回主界面不重播是免费得到的——那是导航，不经过 `onCreate`。

### 细节打磨（六）

- **修掉一个真 bug**：`prefs.reduceMotion` 是**死偏好**——有栏位、有 key、有读取、有 setter、有设置页开关，但 `buildLumoTheme` 只接收 `prefs.animationPreset`、`prefs.animationStrength` 和**系统**旗标。应用内的「减少动效」开关从来没有生效过。已修，并加回归测试 `theInAppReduceMotionSwitchAlsoReachesThemeState`。
- 状态列遮挡修复：全应用改走 `LumoInsets`（`WindowInsets.safeDrawing` 家族）。

### 验证

- **测试：21 类 300+ 例**，含新增的 `VpnIntegrationTest`（22 例）与音效分类/素材测试。逐条结果与**未测试项**见 [`docs/TESTING.md`](docs/TESTING.md)。
- **VPN 这一轮抓到 3 个真实的产品 bug**（都是测试先红、根因在产品码）：编解码器把清单条目直接拼接导致值内的 `|` 变成栏位分隔符；`decode` 把坏掉的 port 吞成 `0` 却照收其余栏位，与自己文档写的「解析失败就读成空 profile」矛盾；`hasProfile` 从 `VpnConnectability` 反推，但那个枚举只报告**第一个**失败的检查，于是 `NoBackend` 被当成「你没配置端点」。三处都已修，并各补一条测试。
- **一个可复用的教训**：`:app:` 系列的 Gradle 任务**不能并发跑**。两次 `EXIT=1` 都是 `classes.jar` / `R.jar` 被另一个还在跑的旧构建占住，启动新构建前必须先确认旧的已结束。

---

## 1.02 — versionCode 3

这一轮是**回归修复 + 功能升级**，对应四条真机反馈：

1. 设置页的背景效果、按钮质感选项点击后没有实际反馈；
2. 内置浏览器兼容性不足，部分收藏网站打不开、部分页面加载慢或滚动卡顿；
3. 点「添加网址」后，添加页遮住系统状态栏、顶部布局异常；
4. 需要浏览模式自动/手动切换 + 每个网址独立设置。

### 新增：浏览模式与每网址独立配置

- 新增 `BrowserMode`：**自动 / 移动版 / 兼容 / 桌面版**。四个模式不是同义词，各自的渲染配置有实质差异（用户代理、viewport、混合内容策略、缓存、文字缩放），差异表见 [`docs/BROWSER_MODES.md`](docs/BROWSER_MODES.md)。
- 每个收藏可以拥有自己的网页设置：模式、自定义 User-Agent、JavaScript、DOM 存储、第三方 Cookie、请求桌面版。这些都持久化在 `Bookmark` 行上，随收藏一起备份/恢复。
- **自动模式是有界的**：`Mobile → Compat → Desktop`，最多三次，只在「换个渲染方式确实可能成功」的失败上切换。证书错误、404、DNS 解析失败、超时**永不触发切换**——换个 User-Agent 不会让 404 变成 200，而把证书错误当成渲染问题重试，等于给安全绕过加了几道手续。状态机是纯函数 `AutoModeState.onVerdict(...)`，不变量：尝试次数只增、只增一、饱和于顶端。
- 浏览器内右上角「更多」菜单可随时手动切模式、一键「还原默认」、查看**网页诊断**面板。菜单里显示的是**当前实际生效**的模式，不是存储的值——「桌面版」开关注入了桌面 UA，诊断面板必须报告有效值，否则面板就是在骗人。
- 网页诊断面板回答「为什么这个站不行」：URL、配置模式、**实际生效**模式、最终 User-Agent、加载阶段、错误类别与详情、HTTP 状态、是否发生过重定向、弹窗请求与被拒次数、权限拒绝次数、JS/DOM/Cookie/桌面版/文字缩放各项开关、配置摘要、以及**系统 WebView 的包名与版本**——「这个站不支持」很多时候是关于系统 WebView 的判断，而不是关于本应用的。

### 新增：浏览兼容性底层能力

- `BrowserConfigManager` 把「配置计划」与「应用到 WebView」分开：`planFor(...)` 是纯函数、可单测，`applySettings(...)` 幂等且**不碰缓存、历史或当前文档**，所以模式切换不会在调用方决定重载之前就毁掉用户正在看的页面。
- `setSupportMultipleWindows(true)` + `onCreateWindow`：`target="_blank"` 与 `window.open` 不再被静默丢弃，在同标签内打开。是否跟随新窗口由 `shouldFollowNewWindow(url, isUserGesture)` 判定——必须有用户手势，且目标地址必须是可浏览的 http/https。
- 补上 `onShowFileChooser`（文件选择，**无论选中还是取消都必定回呼**，否则页面的 `<input type=file>` 会永久挂起）、`onPermissionRequest`（默认拒绝并计数）、`setDownloadListener`（下载与失败都有可见反馈）。
- **渲染进程崩溃可恢复**：`onRenderProcessGone` 不再让整个浏览器黑屏，而是销毁死掉的 WebView、重建、重载，并给出提示。

### 修复：按钮质感点了没反应（问题 1）

**根因是结构性的，不是漏了某个 `when` 分支。** 同一个 `ButtonTexture` 在 `Surfaces.kt` 里被**重复解释了三遍**（`LumoIconButton`、`LumoPrimaryButton`、`LumoChip` 各一份 `when`），于是同一个「玻璃」在三种按钮上是三种观感；更要命的是，新增的选项**没有任何通道**去影响填充、边框、阴影、圆角和高光——它们只改了一个没人读的枚举值。

- 新增统一的 `LumoButtonStyle` + `lumoButtonStyle()` + `Modifier.lumoButtonSurface(...)`，四个共用按钮（图标按钮、主按钮、文字按钮、Chip）**全部走同一条解析路径**，旧的三份重复 `when` 已删除。这样「改动质感的那个设置项」与「看到质感变化的地方」是同一处。
- 质感从 3 种扩到 **7 种**：实心、柔和立体、玻璃、极简扁平、磨砂玻璃、半透明悬浮、细腻边缘高光。扁平**真的没有阴影**，悬浮**真的是双倍投影**，而不是七个别名。
- 按钮新增可调：自定义圆角、阴影高度、自定义主色、边缘高光、按压动画。
- 内容色不再硬编码白色，而是走 WCAG 对比度计算（`contrastContent`）：先试品牌色，再试主题前景色，最后按填充色的相对亮度选深/浅。**修复了浅色模式下主按钮白字对比度约 1.1:1、几乎不可见的问题。**
- 文字按钮（取消/还原/对话框）也被纳入质感系统——否则改质感会**静默跳过**全应用每一个文字按钮。

### 修复：背景效果点了没反应（问题 1）

- 背景从 3 种扩到 **6 种**：极光、科技网格、纯色、**纯色（无装饰）**、**渐变**、**玻璃拟态**。
- 新增自定义背景色、背景亮度、装饰层透明度、光效开关。
- **即时预览**：色板直接摆在它预览的选项下方，读的是「选中的样式」而不是持久化的值，所以点击当刻就变，不必等 DataStore 往返。预览与真实背景**共用同一份绘制代码**（`LumoBackdrop`）——会走样的预览比没有预览更糟。
- 新增「恢复默认外观」，**只重置外观点，不动语言、收藏、音效与触觉开关**。
- 亮度用 RGB 标量乘而非 alpha 混合：半透明主题下调低亮度会让**桌面透过来**，那是反效果。

### 修复：添加网址页遮住状态栏（问题 3）

**根因不在内边距，而在状态栏图标的明暗。** `themes.xml` 里 `windowLightStatusBar` 被写死为 `false`（永远浅色图标），而 `enableEdgeToEdge()` 不带参数时图标明暗跟随的是**系统**深色设置，不是应用主题。于是「应用浅色主题 + 白色图标」和「系统浅色 + 应用深色主题」都会得到看不见的状态栏图标，看起来就像顶部布局坏了。

- 新增 `applySystemBarAppearance(window, isDark)`，由**应用实际解析出的主题**驱动 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`，在 `SideEffect` 中同步——这是必须与正在绘制的画格一致的窗口属性。
- 只碰图标对比，两条系统栏仍由 `enableEdgeToEdge()` 保持透明，**不读写任何系统级设置，也不改全局亮度**。
- 编辑页与浏览器页现在都挂在应用主题背景上，状态栏区域不再是硬编码的深色。

### 修复：页面加载慢 / 滚动卡顿（问题 2 的性能面）

- **窗口背景层在网页打开时暂停。** `LumoBackground` 的极光是一个 `InfiniteTransition`——它握着帧时钟，意味着只要有页面开着，Compose 每个 vsync 都会请求一帧，和 WebView 自己的渲染器抢同一个主线程。而那一层在浏览器里是**被完全遮住**的。现在导航到浏览器目的地时背景自动转为静态帧：用户的偏好没有被覆盖，只是不再花在看不见的地方。
- `WebViewFeature` 保护的调用全部包了 `runCatching`。`isFeatureSupported` 的答案**不是承诺**：存在（部分 OEM 的 WebView、以及单元测试的 Robolectric stub）宣称支持 `ALGORITHMIC_DARKENING` 却从 setter 抛 `UnsupportedOperationException` 的实现。未加保护时，一个纯装饰性开关会在 `AndroidView` 构造期**让整个浏览器页崩溃**——在一切都正常的机器上，这看起来就是「这页就是打不开」。

### 新增：性能开关真正接线（此前是假开关）

「简化效果」这个设置项**在数据层存在、在设置页有 UI、文案写着「关闭动态背景与厚重阴影」，但没有任何渲染路径读它**。又一个能点、能存、什么都不做的开关——正是本轮要消灭的东西。

语义定案为**「关掉持续性的渲染成本」**，与另外两个开关互不等价：

| 开关 | 关掉什么 | 保留什么 |
| --- | --- | --- |
| `simplifiedEffects` | 动态背景、光斑图层、**全部投射阴影** | 主题、配色、圆角、卡片样式、**全部交互动画** |
| `reduceMotion`（系统减少动效） | 压缩所有位移与时长 | 结构、层级 |
| `AnimationPreset.Minimal` | 一部分装饰动画 | 用户的其余选择 |

三条专门的用例锁住这个不等价性（`reduceMotionAndSimplifiedEffectsAreDifferentSwitches`、`minimalPresetAndSimplifiedEffectsAreDifferentSwitches`、`feedbackAnimationsSurvive_simplifyingIsNotTheSameAsFreezing`）。

实现上有一个刻意的重构：阴影规则被提取为**纯函数** `lumoShadowElevation(style, elevated, simplified): Dp`。规则原来内联在 `@Composable` 里，**唯一能检查它的方法就是渲染整个应用用眼睛看**——这正是它能一直是个死开关的原因。提取之后两侧都可测（`SurfaceElevationTest` 4 例）。另外 `backgroundGlow` 改为 `backgroundGlowEnabled && !simplifiedEffects`，而 `backgroundAnimates` 因为已经要求 `backgroundGlow` 而**自动**连带走假——**不另加条件，否则会出现永远为假的死项**。

文案无需改动：三语 `setting_simplified_effects_hint` 本来就写着「关闭动态背景与厚重阴影 / 不会改动你的主题、配色、圆角与动画设置」。现在**代码行为终于和文案一致**。

### 新增：网络状态与 VPN 辅助（解释层，不含隧道）

**本应用永远不建立隧道。** 没有 VPN 协议代码，**没有新增任何权限**（仍是 `INTERNET` / `ACCESS_NETWORK_STATE` / `VIBRATE` 三条，与 1.0 相同），没有 `BIND_VPN_SERVICE`，没有 `QUERY_ALL_PACKAGES`。它只做「读系统报告的状态 → 解释失败原因 → 用户明确点击时跳到 Android 自己的 VPN 设置页」。

- 设置页新增「网络与 VPN」区块（10 项）与「性能」区块，含网络/ VPN 状态显示、辅助模式三选（自动/手动/静默）、以及跳转系统设置的两个入口。
- 浏览器右上角「更多」菜单新增「网络」项，打开面板显示：网络状态、VPN 状态、传输类型（`WIFI`/`CELLULAR` **刻意不翻译**——那是用户在 Android 设置里看到的那串字）、失败原因、以及**每网址**的网络辅助选择。
- **每网址的网络辅助偏好现在有两个入口**：浏览器网络面板，以及收藏的编辑器。两边写的是同一个字段（`bookmarks.networkAssist`），因此不会出现分叉。编辑器给它单独一段而不是并进「浏览模式」的芯片行：一个站点可以既需要桌面版布局又完全连得通，这是两个不同的问题。默认值 `FollowGlobal`（「跟随全局设置」）也是升级默认，所以这一项对任何已存在的收藏都是**行为不变**的。
- 这一项的价值在**判断本身可测**：`assistOfferFor(...)` 是七参数纯函数，`NetworkAssistTest`（15 例）覆盖它的每一条分支。其中一例专门钉住一个陷阱——`SiteNetworkAssist.FollowGlobal.asGlobalMode()` 必须返回某个值，它返回 `Silent`；如果解析逻辑偷懒用了它而不是去读真正的全局设置，所有「没有意见」的站点会被永久静音，而症状看起来和「功能没做」完全一样。
- 失败分类 `classifyPageFailure(...)` **只在失败当刻做一次，不随实时网络状态重算**。页面失败之后才发生的断网不是那次失败的原因；跟着实时网络走，等于用当前连接状态给一次过去的失败重贴标签——用户会看到「你没有网络」，而他此刻网络正常。
- **重试是有预算的**（`AssistRetryState`），超过上限按钮变为不可用。无限重试的按钮在一个确实打不开的站上就是耗电与流量的循环。唯一例外是「连接 VPN 后重试」：它是用户明确要求的，它等的条件（VPN 起来）是**新信息**而非重复同一次请求，且两种结果都会清掉等待旗标，所以不可能成环。
- 系统设置跳转用 `getApplication<Application>().startActivity(...)` 并 catch `ActivityNotFoundException`（某些定制 ROM 没有该页面）——**缺一个系统画面不能让设置页崩掉**。
- 「VPN 辅助」默认**关**，「打开前检查网络」默认**关**。辅助关闭时下面的判定一概不参与。
- 隐私：**不上传任何东西**。没有遥测、没有崩溃上报、没有「网络质量」回传。也**不检测用户装了哪个 VPN 应用**（那需要包可见性，不申请）；「我已配置 VPN」是用户自己的声明。

### 新增：浏览器沉浸式全屏 + 全应用 inset 统一

- 新增设置项「浏览器沉浸式全屏」（默认**关**），开启后网页区域顶到屏幕边缘。
- 它**刻意不并入动画预设**：这是浏览器的布局选择，不是「动效强度」。
- 四屏（主屏 / 浏览器 / 编辑页 / 设置页）的 inset 处理此前**各写各的**——有的用 `statusBarsPadding()`、有的三连 padding、**主屏则完全没处理**。现在全部收敛到 `ui/components/Insets.kt` 一处，并新增 `LumoScreenInsets`（= `WindowInsets.systemBars`，**只给浏览器**：键盘弹出时系统栏仍在，`safeDrawing` 会让工具栏跳动）。
- 全库已无 `statusBarsPadding()` / `navigationBarsPadding()` / `imePadding()` 的直接调用。
- **踩到的坑**：给浏览器顶部 2dp 进度条直接套 inset 是错的——`lumoTopInsets()` 会把它的盒子撑成「2dp 高 + 状态栏高」，背景自然铺满整条。正确写法是外层 `Box` 承担 inset，内层 2dp 条只管画。

### 修复：其它审查发现的缺陷

这一轮把整个代码库按固定检查项又过了一遍，除上述之外还修掉：

- 进度条原先用 `Modifier.fillMaxWidth(progress)`——**layout modifier 每帧触发重新测量**；改为在 `drawBehind` 里画宽度，只重绘不重测。
- `isBookmarked(state.url)` 在同一次组合里被调用两次，每次都线性扫全部收藏；改为一个 `bookmarked` 局部值。
- 浏览器工具列的唤出把手是 56×22dp 的细条，命中区不足 48dp；视觉尺寸不变，外层套满 48dp 命中盒并加了水波纹。
- 设置页滑杆原先**每拖动一帧就写一次 DataStore 并触发一次音效**——正是需求明令禁止的「每次滚动或重绘都发声」。改为本地草稿值 + 仅在松手时提交。
- 强调色色板原先命中区只有 30dp；现在外层 48dp，视觉尺寸不变。
- 卡片拖动排序的自动滚动原先没有上限——手指移出视口后滚动速度随距离无限增长；现在夹在 ±26px/帧。
- 收藏卡片的 favicon `ImageRequest` 每次组合重新构造（导致重复加载与闪烁），改为按 URL `remember`。
- 主屏「添加」按钮的图标色跟随 `onPrimary`，但在任何质感下按钮填充都不是实心强调色，浅色模式下对比度约 1.1:1；改为跟随强调色。

### 修复：每网址「请求桌面版」开关在非桌面版模式下是空操作

编写 `BrowserConfigManagerTest` 时抓出的真实缺陷，不是测试写错。

`planFor(...)` 判断桌面 UA 的条件是 `config.desktopSiteEnabled && profile.desktopUserAgent`。而 `SiteConfig.resolve()` 已经把 `profile.desktopUserAgent` **合并进** `desktopSiteEnabled`，所以那个附加条件既冗余、又让开关在 **Mobile / Compat / Auto** 三种模式下彻底失效：用户在编辑页打开「请求桌面版」、保存、重新打开——发出的仍然是手机 UA，诊断面板却报告 `desktop-ua`。一个能打开、能保存、能显示、但什么都不做的开关，正是本轮要消灭的东西。

现在只判断 `config.desktopSiteEnabled`：桌面版模式经由 `resolve()` 的合并照旧拿到桌面 UA；其余模式下用户**显式**打开该开关时也真的会发桌面 UA——显式的每网址选择优先于模式的默认值。回归由三个用例锁住（含一条「默认仍为关，别把每个收藏都变成桌面请求」的反向保护）。

### 修复：`WebViewFeature` 宣称支持却抛异常，导致整页崩掉

`:app:testDebugUnitTest` 有 4 个用例失败，栈顶是 `WebViewFeatureInternal.getUnsupportedOperationException` → `WebSettingsCompat.setAlgorithmicDarkeningAllowed` → `BrowserConfigManagerKt.applySettings` → `LumoWebView.applySiteConfig` → `AndroidView` 的 `factory`。

`WebViewFeature.isFeatureSupported(...)` 的答案**不是承诺**：部分 OEM 的 WebView 与单元测试用的 Robolectric stub 都会宣称支持 `ALGORITHMIC_DARKENING` / `SAFE_BROWSING_ENABLE`，然后从 setter 抛 `UnsupportedOperationException`。未加保护时，一个**纯装饰性**的深色适配开关会在浏览器页构造期让整页崩掉——在一切正常的机器上，这看起来就是「这个页面就是打不开」。

新增 `private inline fun featureGated(feature, block)`：保留能力查询，另外 catch `RuntimeException`（**刻意不 catch `Throwable`**——`OutOfMemoryError` 是真实状况，不能吞）。这是本轮的第二个「装得上也打得开，但一进浏览器就炸」类缺陷。

### 修复：繁体中文缺字

`values-zh-rTW/strings.xml` 此前只有 169 行，本轮新增的约 80 条字符串**完全没有进繁中文件**。Android 会沿 `zh-rTW → zh → default` 回退，所以繁体用户看到的是**简体字**。现已补齐全部繁中翻译。

### 版本号

`versionCode` **2 → 3**，`versionName` **1.0.1 → 1.02**（Debug 变体带后缀，实际显示 `1.02-debug`）。

### 数据库迁移

Room `version` **1 → 2 → 3**，两次迁移都保留：

| 版本 | 新增列 | 迁移 |
| ---: | --- | --- |
| 2 | 8 个浏览相关列 | `MIGRATION_1_2`（逐列 `ALTER TABLE ADD COLUMN` + 默认值） |
| 3 | `networkAssist` | `MIGRATION_2_3`（单条 `ALTER TABLE bookmarks ADD COLUMN networkAssist TEXT NOT NULL DEFAULT 'global'`） |

**两个迁移都必须注册在 builder 上。** 一台装了 1.00 的设备升到 1.02，磁盘上的库还是 version 1，Room 要走 **1 → 2 → 3** 两步；只留 `MIGRATION_2_3` 会让这类设备抛 `A migration from 1 to 3 was required but not found`——**不是升级失败，是启动就崩**。「整理代码时顺手删掉旧迁移」正是会造成这个故障，所以有专门用例锁住（`bothMigrations_areRegisteredSoADirectlyUpgradedInstallCannotThrow`）。

**刻意不提供 `fallbackToDestructiveMigration()`**：那会在用户看不懂的时机静默清空收藏，与「不重建项目、不删数据库结构、不清空数据」的硬约束直接冲突。迁移只做加列，不做改名、不做删除，所以从 1.0 / 1.0.1 覆盖安装不会丢任何收藏。

两个细节值得单独记下来，因为它们各自都是一个「应用不会报错但确实坏了」的陷阱：

- **默认值必须带引号**（`DEFAULT 'global'`），与 Room 写进 `CREATE TABLE` 的形式逐字节一致。写成不带引号的 `DEFAULT global` 会依赖 SQLite 的裸标识符回退，**过不了 Room 的 schema 校验**。
- **三个索引从 schema 1 起就存在，两个迁移都没提到它们**——这正是要测的：一次**重建表**的迁移会把索引一起丢掉，而**应用不会报任何错**，只是查询变慢，慢在用户持续滚动的那个列表上。

新增 `app/src/test/java/com/lumobrowser/app/data/DatabaseMigrationTest.kt`（**11 例**）覆盖以上全部：`@SQLiteMode(NATIVE)` 走**真 SQLite**（不是 Robolectric 的内存假实现，否则 DDL 语法根本没被验证过），用手写的 v1/v2 DDL 造出真数据库文件，再让 Room 用 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)` 打开它。

**写这批测试时踩到的两个坑（都是测试的洞，不是应用的洞）：**

1. `Room.databaseBuilder(...).build()` **只构造包装器**，`RoomOpenHelper.onUpgrade`（迁移与 schema 校验都在这里）要到**第一次真正使用数据库**时才跑。不触碰数据库的迁移测试会**假通过**。`openWithRoom()` 因此显式 `database.openHelper.writableDatabase` 强制打开，KDoc 写明了原因。
2. `BookmarkDao.getAllOnce()` 返回的是 **Room 实体**，其字段是**存储字符串**（`"auto"`、`"global"`），不是领域枚举。`assertEquals` 的签名是 `(Any?, Any?)`，所以拿枚举比字符串**编译期毫无警告、运行时必错**——本轮就写了三处这样的断言。修法不是改成比字符串（那测试就变成「和实现一样」），而是走 `toDomain()` 断言，因为**「迁移留下了应用能用的数据」的定义就是 `toDomain()` 能把它变成正确的枚举**；对「升级行与新插入行无法区分」这一条，则改成比较**存储字节**。

### 交付构建：换成源码包冷构建的产物

`dist/LumoBrowser-1.02-debug.apk` 最初是源码树里 `:app:assembleDebug` 的产物（18,457,992 B）。最终交付换成了**对 `dist/LumoBrowser-1.02-source.zip` 做冷构建**的产物（**18,377,227 B**，SHA-256 `E365F2A2…`），这个文件被原样复制进 `dist/`。

换的理由是**可宣称的命题更强**：冷构建那一份的来源是一条写在文档里、任何人都能重跑的命令（`--no-build-cache`，解压到空目录只补一个 `local.properties`），而源码树那一份依赖本机的增量状态。同一次调用里单元测试全绿（18 个测试类 263 例 0 失败 0 跳过）。

两个包**功能等价但非逐字节相同**，差异已追到底：zip 条目数都是 491，未压缩长度不同的**只有 `classes7.dex`**（差 428 B），压缩后总长**只差 98 B**，签名块空隙、中央目录、尾端三者**完全同形**；剩下 81,007 B 落在逐条目的本地标头与对齐填充上。根因是 Debug 包内嵌 `BuildConfig.BUILD_TIME`，两次构建的时间戳不同会让 DEX 字符串表位移——**同一份源码的两次 Debug 构建本来就不会逐字节相同**。

同一个效应也解释了最后两轮冷构建之间的 340 B 差（18,377,227 vs 18,376,887）：中间源码只多了编辑器里那一段网络辅助接线和 15 个用例，其余全来自时间戳。

> ⚠️ 这也意味着**不要用哈希去跨机器比对 Debug 包**。要校验手上的文件是否就是本次交付的包，比对 `dist/SHA256SUMS.txt`。

### 修复：单元测试 JVM 用了默认堆，冷构建时 OOM

第一次对交付包做冷构建时，`:app:testDebugUnitTest` 以 `263 tests completed, 1 failed` 结束——**失败的不是被测代码**：

```
com.lumobrowser.app.LanguageSwitchLaunchTest > launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate FAILED
    java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49
```

单元测试跑在**独立于 Gradle daemon 的另一个 JVM** 里，而 `app/build.gradle.kts` 从来没给它设过堆，于是它用的是 Gradle 的默认值 512 MB。这个用例会在 Robolectric 里真的把 Activity 和整棵 Compose 树撑起来，单跑就要 136 秒——512 MB 够不够，取决于那一刻构建机还在同时干什么。源码树里跑的时候它过了，冷构建时并行任务更多，它就死了。

修的是**资源不足的 worker，不是症状**：在 `testOptions.unitTests.all { }` 里显式设 `maxHeapSize = "2g"`。没有削弱这个断言，也没有把它排除出测试集——**一个会因为构建机负载而随机变红的测试，和一个真的坏了的测试，在「能不能信这份报告」这件事上是一样的**。这件事本身也值得记：测试报告如果依赖运行环境的空闲程度，那它证明的东西就比它看上去少。

### 调查：把「装在手机上打不开」的已知成因逐条排除

用户反馈装在手机上打不开。本机 `adb devices` 为空、也没有模拟器/AVD/系统映像，所以**无法复现**。能做的是把「装得上但打不开」这一类故障的常见成因逐条查过去，并把「怎么把现场变成一段可读文字」写进 README。**新增的证据只有原生库那一条**，其余是既有事实的汇总：

| 成因 | 结论 | 新增证据 |
| --- | --- | --- |
| launcher Activity 未导出 | 不成立：`MainActivity` 有 `exported="true"` + `MAIN`/`LAUNCHER` | — |
| 签名无效被系统拒绝 | 不成立：`apksigner verify` 退出码 0，v2 通过 | — |
| 跳版升级时数据库迁移缺失 → 启动崩 | 不成立：`MIGRATION_1_2` 与 `MIGRATION_2_3` 都已注册，11 例真 SQLite 测试 | — |
| 启动时 `CompositionLocal` 未提供 | 第一轮已修，新版结构上不可表达 | — |
| **系统 WebView 被禁用/缺失** | **不成立**：启动路径完全不碰 WebView——全工程只有一处 `WebView(...)` 构造（`BrowserScreen.kt:419`）、一处读 WebView 版本（`BrowserScreen.kt:293`），都在浏览器屏；首页只用 Compose + Room + DataStore | ✅ 全库 grep |
| **ABI 缺失 / 原生库未按页对齐** | **不成立**：四种 ABI 全在（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`），四个 `.so` 在 zip 内的数据偏移全部 **4096 字节对齐** | ✅ 直接解析 APK 的 `lib/` 条目与 zip 本地标头 |
| **16 KB 内存页设备加载不了旧对齐的 `.so`** | **不成立**：四个 `.so` 的 ELF `PT_LOAD` 段 `p_align` 全部是 **`16384`**（Android 15 起部分设备用 16 KB 页） | ✅ 解析 `.so` 的 ELF 程序头 |
| 旧版本残留应用数据与新 schema 冲突 | **未排除**，需要用户在手机上动手：先卸载再装 | — |

`extractNativeLibs="false"`（清单里实测就是这个值）要求 `.so` 页对齐，否则 `System.loadLibrary` 抛 `UnsatisfiedLinkError`——症状同样是「点图标没反应」。这两条此前从没验过，现在验了：ABI 齐全、zip 偏移对齐、ELF 段对齐到 16 KB。

`README.md` 新增**第 14 节「装上了却打不开：逐条排除记录」**（含上面这张表和「抓不到崩溃栈时怎么判断」），`§2.1` 的取证步骤加了交叉引用，`docs/RELEASE-1.02.md` 第一节新增原生库对照表。**这些排除了已知成因，不等于证明它一定能打开**——那需要一台设备，以及那份 `adb logcat -b crash`。

### 修改文件清单

本工程在交付目录里不是一个 Git 仓库（`git status` 不可用），所以下面这份清单是按**文件系统的最后修改时间**筛出来的：1.02 这一轮的改动全部集中在 `2026-10-10 14:09` 之后，而上一版（1.0.1）的产物停在 `12:13`。时间戳是客观信号，不是回忆。

**新增文件（17 个）**

| 文件 | 大小 | 作用 |
| --- | ---: | --- |
| `app/src/main/java/com/lumobrowser/app/data/BrowserConfig.kt` | 8,688 B | `BrowserMode` / `BrowserModeProfile` / `SiteConfig` / `ResolvedSiteConfig` 与解析逻辑 |
| `app/src/main/java/com/lumobrowser/app/browser/BrowserConfigManager.kt` | 14,634 B | 纯函数 `planFor(...)` + 机械落盘 `applySettings(...)` + UA 解析 + `featureGated` |
| `app/src/main/java/com/lumobrowser/app/browser/AutoMode.kt` | 9,069 B | AUTO 的**有界**升级阶梯与 `classifyFailure` 判决 |
| `app/src/main/java/com/lumobrowser/app/browser/NetworkStatus.kt` | — | 网络状态读取与 `NetworkSnapshot` |
| `app/src/main/java/com/lumobrowser/app/browser/NetworkMonitor.kt` | — | 平台回呼注册、`networkSnapshots` 流 |
| `app/src/main/java/com/lumobrowser/app/browser/VpnAssist.kt` | — | `VpnConnectionState` / `resolveVpnState` / 有界重试状态 |
| `app/src/main/java/com/lumobrowser/app/browser/PageFailure.kt` | — | `PageFailure`、`classifyPageFailure`、`assistOfferFor`、`suggestedActions` |
| `app/src/main/java/com/lumobrowser/app/ui/components/Insets.kt` | — | **全应用唯一的 inset 来源** |
| `app/src/main/java/com/lumobrowser/app/ui/NetworkLabels.kt` | — | 七个对枚举做穷尽 `when` 的文案函数（含 `siteNetworkHint`：**同名模式在设置页与收藏里含义相同，只有 `FollowGlobal` 需要一个指向别处的句子**） |
| `app/src/test/java/com/lumobrowser/app/data/BrowserModeTest.kt` | — | **18 例**：四个 profile 互不相同、`storageName` 稳定、未知值回落 |
| `app/src/test/java/com/lumobrowser/app/browser/AutoModeTest.kt` | — | **28 例**：阶梯有界性、闩锁、各类失败不可配置 |
| `app/src/test/java/com/lumobrowser/app/browser/BrowserConfigManagerTest.kt` | — | **30 例**：安全策略固定、UA 优先级、桌面版开关 |
| `app/src/test/java/com/lumobrowser/app/ui/theme/SimplifiedEffectsTest.kt` | — | **10 例**：性能开关与另外两个开关互不等价 |
| `app/src/test/java/com/lumobrowser/app/ui/components/SurfaceElevationTest.kt` | — | **4 例**：阴影高度规则（提取纯函数后才可测） |
| `app/src/test/java/com/lumobrowser/app/data/DatabaseMigrationTest.kt` | — | **11 例**：真 SQLite 上的 v1→v2→v3 迁移 |
| `app/src/test/java/com/lumobrowser/app/browser/NetworkAssistTest.kt` | — | **15 例**（第六轮）：每站点网络辅助的读写链路，以及 `assistOfferFor` 的**全部**分支 |
| `docs/BROWSER_MODES.md` / `docs/RELEASE-1.02.md` | 17,498 B / — | 四种模式的行为差异；本轮的交付记录（构建日志、测试报告、状态栏/性能/VPN/迁移说明） |

**修改文件（30 个）**

| 文件 | 改了什么 |
| --- | --- |
| `app/build.gradle.kts` | `versionCode` 2→3、`versionName` 1.0.1→1.02；**测试 JVM 的 `maxHeapSize = "2g"`**（默认 512 MB 会在冷构建时把 `LanguageSwitchLaunchTest` 打成 OOM） |
| `app/src/main/java/com/lumobrowser/app/data/Model.kt` | `Bookmark` +8 个浏览列 +`networkAssist`；`UserPreferences` +26 项；新增 `NetworkAssistMode` / `SiteNetworkAssist`；`ButtonTexture` +4；`BackgroundStyle` +3 |
| `app/src/main/java/com/lumobrowser/app/data/SettingsRepository.kt` | 26 个新键与 setter；`resetAppearance()`（只重置外观，保留语言） |
| `app/src/main/java/com/lumobrowser/app/data/Database.kt` | Room `version` 1→2→3 + `MIGRATION_1_2` + `MIGRATION_2_3` + `updateNetworkAssist` |
| `app/src/main/java/com/lumobrowser/app/data/BrowserConfig.kt` | `SiteConfig` +`networkAssist`（`Bookmark.siteConfig` 与 `withSiteConfig` 两处同步） |
| `app/src/main/java/com/lumobrowser/app/data/BookmarkRepository.kt` | 配合新列 |
| `app/src/main/AndroidManifest.xml` | 配合浏览器能力与权限边界（**权限清单未增加**） |
| `app/src/main/res/xml/network_security_config.xml` | 明文流量策略（不忽略证书错误） |
| `app/src/main/java/com/lumobrowser/app/browser/BrowserState.kt` | `ErrorKind` 与可重试判定 |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | 多窗口、文件选择、权限、下载、崩溃恢复、按网址套配置 |
| `app/src/main/java/com/lumobrowser/app/browser/LumoJsBridge.kt` | 桥接最小权限 |
| `app/src/main/java/com/lumobrowser/app/MainActivity.kt` | 状态栏图标明暗由 **App 主题**驱动（问题 3 的根因） |
| `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` | 更多菜单、浏览模式切换、诊断面板、**网络面板**、失败分类、有界重试、`pageInset`；站点芯片下方共用的 `siteNetworkHint` |
| `app/src/main/java/com/lumobrowser/app/ui/EditorScreen.kt` | 浏览模式 / UA / JS / DOM / 第三方 Cookie 区块 + 背景层 + inset 统一；**第六轮补上每站点网络辅助选择**（`EDITOR_NETWORK_CHIP`） |
| `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` | 背景与按钮区块（含即时预览）、浏览区块、**性能区块**、**网络与 VPN 区块**、恢复默认外观 |
| `app/src/main/java/com/lumobrowser/app/ui/HomeScreen.kt` | 根 `Box` 加 `.lumoScreenInsets()`（状态栏遮挡的主因修复） |
| `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt` | 30 个新委托 + `network` / `vpnState` / `pageFailure` / `retryState` 流 + 系统设置跳转 |
| `app/src/main/java/com/lumobrowser/app/ui/LumoApp.kt` | 新回调接线与消息映射（含 `IntentUnavailable`） |
| `app/src/main/java/com/lumobrowser/app/ui/TestTags.kt` | 8 个新测试标签（含 `EDITOR_NETWORK_CHIP`） |
| `app/src/main/java/com/lumobrowser/app/ui/LumoStringsSnapshot.kt` | 新字符串的 snapshot 属性 |
| `app/src/main/java/com/lumobrowser/app/util/LumoStrings.kt` | 新字符串访问器 |
| `app/src/main/java/com/lumobrowser/app/ui/components/LumoBackground.kt` | 拆出 `LumoBackdrop` / `LumoBackdropPreview`，六种绘制；相位只在 `graphicsLayer` 里读 |
| `app/src/main/java/com/lumobrowser/app/ui/components/Surfaces.kt` | 三份重复 `when` 收敛为 `LumoButtonStyle` + `lumoButtonSurface`；**阴影规则提取为纯函数 `lumoShadowElevation`** |
| `app/src/main/java/com/lumobrowser/app/ui/theme/LumoTheme.kt` | `backgroundBase` / `backgroundLayerAlpha` / `backgroundGlow` / `backgroundAnimates` / `buttonRadius` / **`simplifiedEffects`** |
| `app/src/main/res/values/strings.xml` | 约 80 条新字符串（英文）＋`site_network_global_hint` |
| `app/src/main/res/values-zh/strings.xml` | 对应的简体中文＋`site_network_global_hint` |
| `app/src/main/res/values-zh-rTW/strings.xml` | 对应的繁体中文（**修掉了整批漏翻**）＋`site_network_global_hint` |
| `README.md` / `docs/BUILD.md` / `docs/TESTING.md` | 交付说明、构建结果与测试记录按 1.02 更新 |
| `app/src/main/java/com/lumobrowser/app/ui/theme/Motion.kt` | 三个预设与 `systemReduceMotion` 的合成 |
| `app/src/main/java/com/lumobrowser/app/feedback/*` | 音效与触觉边界（**不在滚动或重绘时触发**） |

**未改动（遵守硬约束）**：包名 `com.lumobrowser.app`、签名配置、`BookmarkEntity` 的既有 9 个列名与表名、`UNCATEGORISED` 常量值、备份文件的 JSON 格式（新列以可选字段追加，旧备份仍可导入）、**权限清单**（仍是 `INTERNET` / `ACCESS_NETWORK_STATE` / `VIBRATE` 三条）。

---

## 1.0.1 — versionCode 2

### 修复：设置里切换语言时卡死

真机上**一打开设置切换语言就卡住**，而且此后每次启动都会卡。这不是偶发，是结构性死循环：

- `MainActivity` 用 `collectAsStateWithLifecycle(initialValue = UserPreferences.Default)` 收集偏好，而 `UserPreferences.Default.appLanguage` 是 `AppLanguage.System`——**占位值永远是「跟随系统」**。
- 判断「要不要重建 Activity」时，代码拿**存储的语言**和**组合内 `remember` 出来的值**比。后者每个新实例都从那个占位值种下，所以恒为 `System`。
- 于是只要存过任何非「跟随系统」的语言，每个新 Activity 都在真实偏好到达后判定「过时了」→ `recreate()` → 新实例又从 `System` 重新开始 → **永不收敛**。用户从未改过语言时存储值恰好等于占位值，所以第一、二轮从没触发过。

修复分三处：

- 把「本实例实际套用的语言」提升为 Activity 字段，在 `attachBaseContext`（任何视图存在之前）从镜像读取并记录；
- 偏好改为 `initialValue = null` 收集，好让决策能区分「**尚未加载**」与「已加载且相符」；
- 判断收敛为纯函数 `shouldRecreateForLanguage(applied, loaded)`，并且**先同步写语言镜像再重建**——`attachBaseContext` 不是协程，下一次建立时必须已经能读到新值。

顺带修掉回环的另一半：`LanguageMirror` 原来存 `Locale`，而 `System` 经 `toLocale()` 映射为 `null`，导致镜像**无法区分「跟随系统」和「从未写入」**。现在改存 `AppLanguage` 枚举名。

### 新增

- 构建时间编译进 `BuildConfig.BUILD_TIME`，设置页「关于」面板新增「构建时间」一行。

### 回归

新增 `LanguageSwitchDecisionTest` 6 例 + `LanguageSwitchLaunchTest` 1 例；全量 147 用例 0 失败。

---

## 1.0 — versionCode 1

首个可安装版本。

- Kotlin + Jetpack Compose + Material 3 + Room + DataStore + Android WebView + AndroidX WebKit。
- 主屏收藏卡片（网站名 / 域名 / favicon / 描述），支持新增、编辑、删除、搜索、分类、置顶、拖动排序、备份恢复。
- 点卡片在应用内 WebView 打开；支持加载、历史、前进后退、刷新、进度、错误处理、站内链接。
- 深色/浅色主题、玻璃拟态、自定义强调色、三种动画预设（沉浸/柔和/极简）。
- 自生成短音效与触觉反馈，全局静音/音量/触觉开关。
- 简体中文 / 繁体中文 / English 三语。

### 修复：真机「安装成功但打不开」

上一版 APK 装到手机上点图标立刻闪退。根因：`ui/components/Surfaces.kt` 里的 `LocalLumoExtra` 是一个**读取即抛错**的 `CompositionLocal`，而它的提供者 `ProvideLumoExtras` 在全工程里从未被调用过——于是**首次组合必然抛 `IllegalStateException: LumoExtraColors read outside LumoTheme`，每次启动都崩**。

修复方式是把 `ProvideLumoExtras` 绑进 `LumoTheme`，让「漏提供」这个错误在结构上不可表达。

**为什么之前没发现**：原有的 122 个单元测试**没有一个执行过 `setContent`**——没有 Activity、没有组合、没有 Compose 运行时，所以一个「每次启动必崩」的缺陷可以完全隐身。为此新增了两个**真正启动 `MainActivity` 并点击按钮**的冒烟测试类（`StartupSmokeTest` 7 例 + `AppFlowSmokeTest` 11 例）。
