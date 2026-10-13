# 收藏分类栏布局（1.09）

## 一、一句话设计

收藏页的**分类栏只有一个**，位置二选一：搜索框下方的横向条，或左侧的竖向栏。

- 决定位置的是偏好字段 `UserPreferences.categoryRailPlacement`，取值 `CategoryRailPlacement.Top` / `CategoryRailPlacement.Left`，**默认 `Top`**。
- 两种排布共用同一套网格定义：`BookmarkGrid` 加 `LazyGridScope` 扩展 `bookContentItems`。移动的只有分类栏这一个部件：分类栏要么是 `CategoryRail`（左侧），要么是 `CategoryRow`（顶部），两者不会同时存在，因此不可能出现「同一屏里两个分类控件」。
- **标题区与搜索框不参与这个分支。** 它们是 `HomeContentHeader`，在分支**之前**画一次，永远占满整行——见 §三之 3.0。这一点是本次返工的核心：第一版把整个页面塞进 `Row`，标题和搜索框跟着卡片一起被挤到右侧，出现了标题折行、搜索框变窄、卡片被压窄的连锁问题。
- 本版**没有**新增任何收藏写入路径，分类栏也不写收藏。详见 [`BOOKMARK_DATA_INTEGRITY.md`](BOOKMARK_DATA_INTEGRITY.md)。

---

## 二、设置项存在哪

| 项 | 值 |
| --- | --- |
| 界面位置 | 设置 → 外观与布局（`SettingsRoute.AppearanceLayout`）→ 「布局」区 |
| 控件 | `SwitchRow(label = s.settingCategoryRail, hint = s.settingCategoryRailHint, checked = preferences.categoryRailPlacement == CategoryRailPlacement.Left, onChecked = { onCategoryRailPlacement(it.toRailPlacement()) })` |
| 相邻控件 | 同一区里上方是 `LabeledRow(label = s.layoutMode)` + `ChoiceChips`（网格 / 列表），下方是 `pinnedFirst`、`brightnessFollowSystem` 与亮度滑块 |
| 字符串资源 | `setting_category_rail`、`setting_category_rail_hint`，另有 `setting_category_rail_on` / `setting_category_rail_off` 两个状态文案 |
| 字符串读取 | `LumoStrings.settingCategoryRail(c)` / `settingCategoryRailHint(c)` / `settingCategoryRailOn(c)` / `settingCategoryRailOff(c)` |
| 偏好字段 | `UserPreferences.categoryRailPlacement: CategoryRailPlacement = CategoryRailPlacement.Top`（`data/Model.kt`） |
| 枚举 | `enum class CategoryRailPlacement { Top, Left }`（`data/Model.kt`） |
| 持久化键 | `stringPreferencesKey("category_rail_placement")`（`data/SettingsRepository.kt`） |
| 写入路径 | 开关 → `onCategoryRailPlacement`（`ui/LumoApp.kt` 接到 `viewModel::setCategoryRailPlacement`）→ `MainViewModel.setCategoryRailPlacement(value)` → `SettingsRepository.setCategoryRailPlacement(value)` → DataStore |
| 读回 | `SettingsRepository` 读取时走 `prefs[Keys.categoryRailPlacement].toEnum(defaults.categoryRailPlacement)`：键缺失或存了无法识别的值时回落 `Top`，不抛异常 |
| 默认值 | `Top`，与 1.08 及更早版本画出来的位置一致 |
| 生效方式 | 立即生效，不需要重启 |

`CategoryRailPlacement` 与 `LayoutMode`（`Grid` / `List`，决定一行几张卡）是**两个独立的枚举**。合并成一个字段会让「列表模式」和「分类栏在左边」变成同一个开关。

`Boolean.toRailPlacement()` 是 `ui/SettingsScreen.kt` 里这个页面私有的扩展函数，只做一件事：

```kotlin
private fun Boolean.toRailPlacement(): CategoryRailPlacement =
    if (this) CategoryRailPlacement.Left else CategoryRailPlacement.Top
```

开关是布尔控件，偏好是枚举，中间这层转换刻意留在设置的边界上，不进 `MainViewModel`。

---

## 三、左侧栏的排版策略

### 3.0 页面结构：谁在分支里，谁在分支外

见 `HomeScreen.kt` 的 `HomeScreen` 函数（约 183 行起）：

```kotlin
Box(modifier = Modifier.fillMaxSize().testTag(TestTags.HOME_ROOT)
        .lumoScreenInsets()
        .padding(horizontal = LumoSpacing.screenHorizontal)) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 固定区：全宽，只画一次，在分支之外。
        HomeContentHeader(s = s, totalCount = totalCount, query = query, …)

        // 只有它下面的内容区被二选一。
        AnimatedContent(
            targetState = preferences.categoryRailPlacement == CategoryRailPlacement.Left,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { railOnTheLeft ->
            if (railOnTheLeft) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CategoryRail(…, width = LumoSpacing.categoryRailWidth)
                    Spacer(Modifier.width(LumoSpacing.cardGap))
                    BookmarkGrid(…, modifier = Modifier.fillMaxHeight().weight(1f))
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CategoryRow(…)                       // 1.08 的顶部横条
                    BookmarkGrid(…, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
```

于是屏幕被分成两个区域：

| 区域 | 内容 | 宽度 |
| --- | --- | --- |
| 固定区 | `LumoBrowser` 标题、收藏条数、更多按钮、添加按钮、搜索框 | 整屏减去 `screenHorizontal`（18 dp）×2 |
| 内容区（左栏模式） | 左侧分类栏 + 右侧卡片列表 | 左栏 `categoryRailWidth`，右栏吃掉剩余 |
| 内容区（顶栏模式） | 顶部横向分类条 + 下方卡片列表 | 两者都是整行宽 |

`HomeContentHeader` 只是把参数原样转给 `HomeHeader`，存在的原因是让「标题不在分支里」这件事在代码里有一处明确的落点，而不是靠读 `Column` 的嵌套关系反推。

### 3.1 位置与宽度

左栏的宽度是**一个固定常量**，不按窗口比例算：

```kotlin
// ui/theme/LumoTheme.kt
object LumoSpacing {
    val screenHorizontal = 18.dp
    val cardGap = 12.dp
    val categoryRailWidth = 64.dp
}
```

```kotlin
// ui/HomeScreen.kt
CategoryRail(…, width = LumoSpacing.categoryRailWidth)
Spacer(Modifier.width(LumoSpacing.cardGap))
BookmarkGrid(…, modifier = Modifier.fillMaxHeight().weight(1f))
```

- 第一版用的是 `((maxWidth − LumoSpacing.cardGap) × 0.26).coerceIn(72.dp, 108.dp)`：在 411 dp 的参考机上算出 108 dp，宽到能读成「第二列内容」，卡片因此被压窄。返回给用户的两张对比图就是这一版与 64 dp 的差别。
- 分类名最长只有几个字，且 CJK 名逐字竖排（§3.2），所以固定窄带就够用：1080 px（411 dp）下左栏 64 dp + 间距 12 dp + 内容区左右各 18 dp，卡片拿到约 299 dp；若栏宽取 108 dp，卡片只剩约 255 dp。
- 左侧**不加额外水平内边距**（整屏的 18 dp 由外层 `lumoScreenInsets().padding(horizontal = LumoSpacing.screenHorizontal)` 出），所以 64 dp 就是分类项的完整可用宽度。
- `CategoryRail` 的宽度必须显式传入：`Row` 里的 `LazyColumn` 不给宽度会吃掉所有可用空间，网格拿到 0，页面只剩一个满宽的分类条目（黑屏）。
- 宽度不随窗口增长，横屏下分类栏仍是 64 dp，多出来的宽度全部归卡片。

### 3.2 排版方向与文字方向是两件事

分类栏整体是**竖向排列**（`LazyColumn`），但每个名称**怎么排**是逐名判断的，规则在 `HomeScreen.kt` 的两个 `internal` 函数里：

```kotlin
internal fun railLabel(label: String): String = if (isCjkOnly(label)) {
    label.filterNot { it.isWhitespace() }.map(Char::toString).joinToString("\u200B")
} else {
    label
}

/** True when every non-space character of [text] is a Han character. */
internal fun isCjkOnly(text: String): Boolean {
    val meaningful = text.filterNot { it.isWhitespace() }
    return meaningful.isNotEmpty() && meaningful.all { it.code in 0x4E00..0x9FFF }
}
```

规则一句话：**去掉空白后非空、且每个字符都落在 `U+4E00..U+9FFF` 的名称逐字竖排；其余名称整串保留。**

- 逐字竖排时先去掉字符之间的空白，再用 U+200B ZERO WIDTH SPACE 连接。换行本身已经分隔了两个字，留一个空格会在两个字中间多出一道可见的空隙。
- 拉丁字母或数字**不拆**。`esim` 拆成 `e` / `s` / `i` / `m` 四行是可读性倒退，不是特性；`API`、`Wi-Fi 6` 同理，它们作为一个整体按词换行。
- 名称的容器是 `Text(text = railLabel(label), textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)`。超长分类名会换成多行，超过四行才省略，不会横向溢出。

### 3.3 选中态

选中态由三项短动画组成（时长见 §五）：

| 通道 | 选中 | 未选中 |
| --- | --- | --- |
| 背景色 | `accent.copy(alpha = if (theme.isDark) 0.26f else 0.16f)` | `Color.Transparent` |
| 左侧指示条 | 不透明度 `1f`（`Box(Modifier.width(3.dp).height(18.dp))`，圆角 2 dp） | `0f` |
| 文字色 | `accent` | `MaterialTheme.colorScheme.onSurfaceVariant` |

指示条是行内的第一个子节点，因此贴在左边缘；其后是 6 dp 的 `Spacer`，再是居中的名称。整行的形状是 `RoundedCornerShape(theme.buttonRadius.md)`，点击涟漪是 `lumoRipple(accent.copy(alpha = 0.18f))`。

### 3.4 触摸区域与内边距

```kotlin
modifier
    .fillMaxWidth()
    .heightIn(min = if (compact) 36.dp else LumoMinTouchTarget)
    …
    .padding(horizontal = 6.dp, vertical = 8.dp)
```

- 常态下每项最小高度取 `LumoMinTouchTarget`（48 dp）。
- `compact` 由 `fontScale > 1.3f` 决定。字号被系统放大时行高降到 36 dp，让同样数量的分类还能一屏放下。
- 行的水平内边距 6 dp、垂直 8 dp；指示条 3 dp、间距 6 dp。
- 分类栏整体底部留 `contentPadding = PaddingValues(bottom = 96.dp)`，避免最后一个分类落进系统手势条。

分类栏与顶部横向条各自带测试标签：`TestTags.CATEGORY_RAIL = "category-rail"`、`TestTags.categoryRailItem(name) = "category-rail-item-$name"`，横向条沿用 `TestTags.CATEGORY_ROW = "category-row"` 与 `TestTags.categoryChip(name) = "category-chip-$name"`。`CATEGORY_RAIL` 是独立标签而不是复用 `CATEGORY_ROW`，这样测试才能说出「看到的是哪一种条」。

---

## 四、独立滚动

两个滚动容器是**兄弟节点，不互相嵌套**：

| 容器 | 滚动状态 | 位置 |
| --- | --- | --- |
| 分类栏 | `val railState = rememberLazyListState()` | `CategoryRail` 内部 |
| 卡片网格 | `val gridState = rememberLazyGridState()` | `HomeScreen` 顶层，在 `AnimatedContent` **之前**创建 |

- 分类栏是 `LazyColumn`，卡片区是 `LazyVerticalGrid`，两者并排放在同一个 `Row` 里。从分类栏开始的拖动由分类栏自己消费，传不到网格；反过来也一样。因为没有嵌套，也就没有嵌套滚动冲突需要仲裁。
- `gridState` 创建在分支之外，所以切换位置时右侧列表**保留滚动位置**，不会因为换了排布就跳回顶部。
- 分类栏只做纵向滚动，没有横向手势。

---

## 五、动画清单

### 5.1 位置切换

`HomeScreen.kt` 用一个 `AnimatedContent` 在两种排布之间做横向淡入淡出：

```kotlin
AnimatedContent(
    targetState = preferences.categoryRailPlacement == CategoryRailPlacement.Left,
    transitionSpec = {
        val duration = theme.motion.duration(theme.motion.navDurationMs)
        val fade = tween<Float>(durationMillis = duration)
        val slide = tween<IntSize>(durationMillis = duration)
        (fadeIn(fade) + expandHorizontally(slide, clip = false))
            .togetherWith(fadeOut(fade) + shrinkHorizontally(slide, clip = false))
    },
    label = "category-rail-placement",
    modifier = Modifier.fillMaxWidth().weight(1f),
) { railOnTheLeft -> … }
```

- 时长取 `theme.motion.duration(theme.motion.navDurationMs)`，即由动画预设的 `navDurationMs`（300 / 250 / 140）乘以全局动画强度得出。它是**共用**的 UI 过渡时长，不是这一处专有的数字：`MotionConfig.transitionSpec` 用的是同一个字段，所以切换布局与页面里其它过渡共享同一份预算。
- 刻意**不做**高度/尺寸动画：对 `LazyVerticalGrid` 的高度做动画会让每一帧重新测量所有可见卡片。
- `AnimatedContent` 只包内容区（`weight(1f)`），标题与搜索框在它外面，因此切换布局时标题区既不重画也不位移。

### 5.2 分类选中

三个 `animate*AsState`，都用同一个时长（`theme.motion.duration(theme.motion.navDurationMs)`，缓动 `tween`）：

| 动画 | 调用 | 标签 |
| --- | --- | --- |
| 背景色 | `animateColorAsState` | `rail-item-background` |
| 指示条透明度 | `animateFloatAsState` | `rail-item-indicator` |
| 文字色 | `animateColorAsState` | `rail-item-text` |

### 5.3 时长由谁决定

所有时长来自 `ui/theme/Motion.kt` 的 `MotionConfig`，不写死：

| 预设 | `baselineDurationMs` | `accentDurationMs` | `navDurationMs` | `feedbackDurationMs` | `cardEntryDurationMs` | `cardEntryDelayStepMs` | `pressScaleDown` | 背景动画 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `Immersive` | 300 | 300 | 300 | 280 | 520 | 45 | 0.972 | 开 |
| `Soft` | 250 | 250 | 250 | 240 | 380 | 28 | 0.975 | 开 |
| `Minimal` | 140 | 140 | 140 | 160 | 190 | 0 | 0.99 | 关 |

`navDurationMs` 是位置切换与分类选中动画共用的那个数，三个预设分别是 300 / 250 / 140 ms，都在 120–300 ms 的预算内；`cardEntryDurationMs`（列表入场）刻意留在这个区间之外，因为它是「复杂切换」那一类。`MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand` 会在任何预设把 `navDurationMs` / `accentDurationMs` / `baselineDurationMs` / `feedbackDurationMs` 弄出 120–300 时失败。再乘以全局强度：

```kotlin
val animationsEnabled: Boolean get() = strength > 0.001f

fun duration(base: Int): Int = if (!animationsEnabled) 0 else (base * strength).toInt().coerceAtLeast(1)
```

关键性质：强度正好为 0（动画关闭）时，`duration` 返回 **0**，所有动画塌缩成「瞬间完成」，而不是仅仅变快。

### 5.4 减弱动效

减弱动效是**一个**开关，由系统无障碍标志与应用自身的 `reduceMotion` 偏好合并后传入 `MotionPresets.config(preset, strengthInput, reduceMotion)`：

```kotlin
val effective = if (reduceMotion) minOf(strengthInput, 0.35f) else strengthInput
return base.copy(
    strength = effective.coerceIn(0f, 1f),
    backgroundAnimationEnabled = base.backgroundAnimationEnabled && !reduceMotion,
)
```

两条行为：

1. 强度被压到不超过 0.35：非必要的动效变成「短但仍可感知」，`duration` 与 `entryDelay` 随之缩短。
2. 背景漂移直接关掉，而不是放慢——它是最典型的「非必要动效」。按压、淡入、进度这类短反馈保留，因为要求是「减弱」不是「移除」。

### 5.5 本版没有改的动画

卡片入场动画（`cardEntrySpec`、`entryDelay(index)`、`MAX_STAGGER_INDEX = 8` 与 `EntryAnimationTracker`）沿用 1.08 的实现，本版未改动。分类栏没有自己的入场动画。

---

## 六、音效与触觉

分类栏**复用既有反馈通道**，没有新增音频代码。

- 取用方式：`CategoryRail` 内 `val feedback = LocalLumoFeedback.current`。
- 触发方式：`LaunchedEffect(selected)` 里比较「上一次选中的是谁」，只有选中项**确实变了**才调一次 `feedback.confirm()`：

```kotlin
var previousSelection by remember { mutableStateOf(selected) }
LaunchedEffect(selected) {
    if (previousSelection != selected) {
        previousSelection = selected
        feedback.confirm()
    }
}
```

  反馈挂在「选中」这个结果上，不挂在点击上：点同一个分类两次不会响第二遍。

- 复用到的既有 cue：`LumoFeedback.confirm() = onCue(SoundCue.Confirm)`；同一 cue 的触觉映射是 `SoundCue.Confirm -> HapticCue.Confirm`。
- 播放链：`LumoApplication.uiFeedback = LumoFeedback(onCue = { cue -> sound.play(cue); hapticFor(cue)?.let(haptics::perform) })`，由 `MainActivity` 用 `CompositionLocalProvider(LocalLumoFeedback provides app.uiFeedback, …)` 提供一次；`LumoFeedback.None` 是无声的兜底实例，没有提供者时降级为「没有声音」，不会崩。
- 管这条链的开关全部是 1.08 已有的：`soundEnabled`、`soundVolume`、`soundStyle`、`hapticsEnabled`、`hapticStrength`、`hapticsFollowSound`。它们由 `MainViewModel.syncFeedbackEngines(prefs)` 落到 `app.sound.configure(…)` 与 `app.haptics.configure(enabled = prefs.hapticsEnabled && !(prefs.hapticsFollowSound && !prefs.soundEnabled), strength = prefs.hapticStrength)`。
- 静音门在 `SoundSynth.play` 里先问 `SoundGate.blockedBy(conditions)`；`soundLoadingCues` / `soundVpnCues` / `soundStartupCues` 三个分组开关只覆盖 `SoundCueGroup.PageLoading` / `Vpn` / `Launch`，而 `SoundCueFamily.allowedBy(enabled) = group?.let { it in enabled } ?: true`——不属于任何分组的 cue 不受这三个开关影响。
- 结论：`feedback/` 目录仍是原来的 6 个文件，左栏没有引入新的 cue、新的开关或新的音频资源。

---

## 七、实测记录（Android 模拟器）

设备：Android 模拟器，1080 × 2340 px / 411 dp 宽，安装包 `com.lumobrowser.app.debug`。以下每一步都留有截图（`screenshots/v109-*.png` 与 `D:\deepseek\Website\_v109h\*.png`）。

三张用于对照的图：

| 文件 | 是什么 |
| --- | --- |
| `screenshots/v109-left-rail-REJECTED.png` | 第一版的左栏：标题折行、搜索框变窄、卡片挤成一条 |
| `screenshots/v109-default-list-top-rail.png` | 返工后的默认状态（干净安装后直接看到的） |
| `screenshots/v109-left-rail-list.png` | 返工后的左栏模式 |

### 7.0 返工后的页面结构（2026-10-13 重新走查）

用户在收到第一版后指出了第一版的缺陷，本节的测量是返工后重新做的，替换了返工前的记录。

| 编号 | 检查项 | 测量结果 |
| --- | --- | --- |
| G1 | 标题 `LumoBrowser` | 单行，`(50,186)-(423,257)`，宽 373 px；左侧没有任何控件与它抢位 |
| G2 | 搜索框 | 满宽，从内容区左边缘到右边缘，没有被左栏挤窄 |
| G3 | 计数行 `8 bookmarks` | `(50,257)-(258,296)`，与标题同列 |
| G4 | 左栏宽度 | 64 dp；分类项 x 范围 `50..296`（含每项自身的 6 dp 内边距），与卡片区不重叠 |
| G5 | 卡片文本列 | 单列模式下标题起于内容区左边缘，文本可用宽度约 339 dp |
| G6 | 卡片内容完整性 | 标题、域名、描述、`#分类` 标签、溢出菜单全部显示且有可点区域 |
| G7 | 左栏与卡片区之间 | `LumoSpacing.cardGap`（12 dp）的 `Spacer`，两侧各自滚动互不影响 |

对照测量（说明第一版的问题不是「左栏太宽」这一条，而是它的**构造方式**）：

| 编号 | 结构 | 标题 | 搜索框 | 卡片文本列 |
| --- | --- | --- | --- | --- |
| H1 | 第一版：整个页面在 `Row` 里，标题与搜索框是右侧那一列的头部 | 折成两行 | 跟着右列变窄 | 被左栏与标题区一起挤窄 |
| H2 | 返工后：标题与搜索框在分支之外，整行 | 单行 | 满宽 | 只被左栏占去 64 + 12 dp |

### 7.0a 干净安装时的默认值（实测，且纠正过一次错误结论）

这一节记录的是一次**真实测出来的默认值**，中间出过一次错，两次都写下来：

1. 先按 `adb shell pm clear` + 重新启动走了一遍，看到的是**左侧栏**，当时据此写下「默认已是左栏」。
2. 随后直接读存储才发现：`pm clear` 之后 `files/datastore/` 目录**根本不存在**（77 字节的「文件」其实是一行 `cat: ...: No such file or directory`）。也就是说应用没有写任何偏好，「看到左栏」只能来自当时的界面状态，不能作为默认值的证据 —— 这一步的结论作废。
3. 改成 `am uninstall` + 全新 `adb install`，再确认 `files/datastore/` 不存在，然后放回夹具数据库重新启动，得到：

| 项 | 值 |
| --- | --- |
| 分类栏位置 | **顶部横条**（`category_rail_placement` 的代码默认值是 `Top`） |
| 默认布局 | **单列列表**（设置页里 `List` 是选中态；`layoutMode` 的代码默认值是 `List`） |
| 偏好文件 | 干净启动后不存在；只有在**改过某项设置之后**才会被写出来。第一次改完开关再读，文件是 35 字节，只有 `category_rail_placement = 'Left'` 一条 |

第 2 步的教训：`run-as ... cat` 失败时输出的是错误文本，而 shell 重定向会把它当成文件内容写下来，长度看起来还挺像样。读这类文件前先确认里面不是一行错误信息。

### 7.1 布局切换（两个方向都观察到）

| 编号 | 操作 | 观察结果 |
| --- | --- | --- |
| A1 | 开关关闭时打开收藏页 | 搜索框下方是一条**横向**分类条 |
| A2 | 打开「分类栏显示位置」开关，正常使用应用 | 分类条改画成**左侧竖向栏**，卡片在它右边 |
| A3 | 再关闭该开关 | 恢复顶部横向条 |
| A4 | 开关来回切 6 次（每次间隔 220 ms） | 不崩、不卡死；`category_rail_placement` 仍是一个合法值，界面与存储一致 |

### 7.2 持久化

持久化不是靠「看起来还是那个样子」判断的，而是直接读应用私有的 DataStore 文件：

```powershell
adb exec-out "run-as com.lumobrowser.app.debug cat files/datastore/lumo_settings.preferences_pb" > out.pb
python _v109h\show_prefs.py out.pb
```

| 编号 | 操作 | 存储里读到的值 |
| --- | --- | --- |
| B1 | 初始（开关关闭） | `category_rail_placement = 'Top' (string)` |
| B2 | 打开开关 | `'Left'` |
| B3 | 连点 6 次（220 ms 间隔） | `'Left'` —— 与界面上看到的排布一致 |
| B4 | 再切一次 | `'Top'`，随后再切回 `'Left'` |
| B5 | `am force-stop` → 重新启动 | 仍是 `'Left'`，界面也仍是左栏：值是从存储读回的 |
| B6 | 全新安装（`uninstall` 后重新 `install`，`files/datastore/` 不存在） | 界面直接是**顶部横条 + 单列列表**；两个设置各自的默认值见 §7.0a |

### 7.3 从左栏筛选

| 编号 | 操作 | 观察结果 |
| --- | --- | --- |
| C1 | 点左栏里的一个分类条目 | 右侧列表被筛成该分类，该条目呈选中态 |
| C2 | 点 `All` | 恢复完整列表 |
| C3 | 分别用中文分类名与拉丁分类名各做一次 | 两次行为一致 |
| C4 | 搜索框里输入 `esim` 的同时点 `未分类` | 空结果并给出「换关键词或清掉分类筛选」的提示；同一关键词下改点 `esim` 分类则正好剩一张卡 —— 两个条件是**取交集**，不是叠加 |

### 7.3 从左栏筛选

| 编号 | 操作 | 观察结果 |
| --- | --- | --- |
| C1 | 点左栏里的一个分类条目 | 右侧列表被筛成该分类，该条目呈选中态 |
| C2 | 点 `All` | 恢复完整列表 |
| C3 | 分别用中文分类名与拉丁分类名各做一次 | 两次行为一致 |

### 7.4 文字排布（设备上观察到的）

| 编号 | 分类名 | 观察结果 |
| --- | --- | --- |
| D1 | `学习`、`充值` | 逐字竖排，一个字一行 |
| D2 | `esim`、`API`、`Wi-Fi 6` | 各自作为一个完整单词，未被拆开 |
| D3 | 一个超长分类名 | 换成多行，没有溢出 |
| D4 | 卡片网格 | 没有被左侧栏盖住 |

### 7.5 左栏模式下的卡片内容

| 编号 | 检查项 | 观察结果 |
| --- | --- | --- |
| E1 | 标题、域名、描述、`#分类` 标签、每张卡的溢出菜单 | 单列模式下全部完整显示；双列模式下标题与域名会被省略号截断，原因见 §九 |
| E2 | 卡片与左栏的关系 | 卡片起于左栏右侧，没有被盖住，也没有出现重复的图标列或空白占位区 |

### 7.6 已知未做的事

- 本轮**没有**测量排布切换的实际耗时或掉帧；上面写的是动画时长由谁决定，不是设备上的帧时间。
- 本轮**没有**在其它屏幕宽度或横屏下走查左栏（64 dp 为固定值，横屏只是把富余宽度全给卡片）。
- 走查只在 `com.lumobrowser.app.debug` 这一个包上进行，release 包没有做设备走查。
- 走查用的数据库是手工造的数据（8 条收藏，中文与拉丁分类名各若干），不是真实用户数据。
- 返工后的走查**没有**再重跑一遍收藏数据独立性（`updatedAt` 不变）与 WebView 返回那两组观察；那两组是返工前在同一设备上做的，记录在 `BOOKMARK_DATA_INTEGRITY.md` 与 `WEBVIEW_HISTORY_RESTORE.md`。

---

## 九、为什么 1.09 把默认布局改成单列列表

`UserPreferences.layoutMode` 的默认值从 1.08 的 `Grid` 改成 `List`（`data/Model.kt:344`）。这只决定**第一次**读到这个偏好时用什么；设置里显式选过 `Grid` 之后就一直用 `Grid`。

原因是**双列网格在手机宽度上放不下卡片的内容**，而这不是左侧分类栏造成的：分类栏改成顶部横条时，同样的截断照样出现。以 411 dp 宽的参考机为例：

| 项 | 占用 |
| --- | --- |
| 整屏内容宽 | 411 − 18 × 2 = 375 dp |
| 双列时每张卡 | (375 − 12) / 2 ≈ 181 dp |
| 卡内水平内边距 | 14 × 2 = 28 dp |
| 图标 | 38 dp + 12 dp 间距 |
| 溢出按钮 | 32 dp（`LumoIconButton(containerSize = 32.dp)`） |
| **留给标题/域名的文本列** | **约 46 dp** |

46 dp 大约放得下 8–10 个西文字符。设备上实测的文本节点宽度也印证了这一点：标题节点 `226..352 px`（126 px ≈ 48 dp）、域名节点 `226..347 px`（121 px）。于是 `Uncategorised one` 显示成 `Unca…`，描述与标签只能靠换行往下挤，这正是用户报告的「标题、网址、描述、标签被挤压、裁切或覆盖」。

单列时同样一张卡拿到约 339 dp，文本列约 200 dp 以上，标题、域名、描述、`#分类` 标签四项都能完整显示。

`Grid` 仍然是设置里可选项（设置 → 外观与布局 → 默认布局），显式选择优先于默认值：默认值只决定**第一次**读到这个偏好时用什么。

---

## 八、相关文档

- [`BOOKMARK_DATA_INTEGRITY.md`](BOOKMARK_DATA_INTEGRITY.md) —— 收藏行的主键、全部写入语句与「本版没有新增写入」的依据
- [`WEBVIEW_HISTORY_RESTORE.md`](WEBVIEW_HISTORY_RESTORE.md) —— 返回键与 WebView 历史
- [`SETTINGS_STRUCTURE.md`](SETTINGS_STRUCTURE.md) —— 设置页的分区结构
- [`ANIMATION_SYSTEM.md`](ANIMATION_SYSTEM.md) —— 动画预设与全局强度
- [`SOUND_FEEDBACK.md`](SOUND_FEEDBACK.md) —— 音效与触觉的开关与分组
- [`TESTING.md`](TESTING.md) —— 本轮的测试数字与走查表
- [`../CHANGELOG.md`](../CHANGELOG.md) —— 1.09 变更条目
