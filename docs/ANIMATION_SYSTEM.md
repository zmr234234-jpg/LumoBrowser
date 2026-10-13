# 动效系统

本文把规格里的**十条动效要求**逐条落到代码位置，并说明**三条横切规则**（动画等级、减少动效、性能契约）是怎么统一的。它是「动效」这一类交付对应的设计说明；启动动画单独写在 [`STARTUP_ANIMATION.md`](STARTUP_ANIMATION.md)，声音与触觉写在 [`SOUND_FEEDBACK.md`](SOUND_FEEDBACK.md)。

所有时长都经过同一个入口：`app/src/main/java/com/lumobrowser/app/ui/theme/Motion.kt` 的 `MotionConfig`。

---

## 1. 唯一的时长入口

```kotlin
fun duration(base: Int): Int = if (!animationsEnabled) 0 else (base * strength).toInt().coerceAtLeast(1)
```

- `animationsEnabled = strength > 0.001f`（`Motion.kt:54`）——强度为 0 时**所有**时长归零，动画不是「变快」而是**不存在**。
- 位移与缩放同样按强度缩放：`offset(base) = base * strength`（`:59`）、`scaleDelta(base) = base * strength`（`:61`）。
- 缓动统一为 `LumoEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)`（`:25`）；预设的规格化 spec 由 `cardEntrySpec`（`:64`）与 `navSpec`（`:67`）提供。

任何一个动效都不在自己内部硬编码毫秒数——这是「全局动画等级」能生效的前提：如果某处写死 `tween(300)`，那个动效就是设置管不到的。

---

## 2. 十条要求的落点

| # | 规格要求 | 实现 | 可核对的事实 |
| --- | --- | --- | --- |
| 1 | 卡片淡入 + 轻微上移 | `ui/HomeScreen.kt:682` | `animateFloatAsState(targetValue = if (appeared) 1f else 0f, tween(motion.duration(motion.cardEntryDurationMs), delayMillis = motion.entryDelay(entryDelayIndex)))`；位移 `translationY = (1f - entryProgress) * motion.offset(motion.cardEntryOffsetPx)`（`:696`）、缩放 `1f - (1f - entryProgress) * (1f - motion.cardEntryScaleFrom)`（`:697`）。同屏卡片按索引错峰，但**错峰有上限**：`entryDelay()` 对索引做 `coerceAtMost(MAX_STAGGER_INDEX = 8)`（`Motion.kt:87-90`），否则第 60 张卡片要等 2.7 秒 |
| 2 | 按钮按下缩小、释放回弹 | `ui/components/Surfaces.kt:244`（`Modifier.lumoPress`），缩放动画在 `:256` | 按下用 `tween(motion.duration(motion.pressDurationMs))`，释放用 `motion.pressSpring()`；弹簧参数按预设取 `releaseSpringDamping`／`releaseSpringStiffness * strength`（`Motion.kt:82`）。**是否启用由设置决定**：`pressAnimated = prefs.buttonPressAnimation && motion.animationsEnabled`，`target = when { !pressAnimated -> 1f; pressed -> pressedScale ?: motion.pressScaleDown; else -> 1f }`——把它放在这个修饰子内部，是因为它是全应用按压回馈的唯一入口（11 个调用点：`BrowserScreen.kt:826`、`:1949`、`HomeScreen.kt:701`、`SettingsScreen.kt:1986`、`:2191`、`:2294`、`:2429`、`Surfaces.kt:672`、`:718`、`:773`、`:874`）。规格见 [`BUTTON_SPEC.md`](BUTTON_SPEC.md) |
| 3 | 页面平滑转场 | `ui/LumoApp.kt:311-330` | NavHost 的 enter/exit 全部走 `tween(motion.duration(motion.navDurationMs))`，退出用**一半**时长（`:318`、`:328`），所以进入比离开慢——反向会读成「页面在追手指」。同一套时长也用于浏览器内的浮层：工具栏与抓手 `ui/BrowserScreen.kt:810`（`fadeIn(200)`／`fadeOut(120)`）与 `:883`（`fadeIn(140) + slideInVertically(180) { -it / 2 }`）、诊断面板 `:988`、网络面板 `:1020`、错误条 `:1078`（从下方 `it / 2` 进入）、外链确认层 `ui/ExternalLinkSheet.kt:98`（背景淡入）与 `:116`（卡片上滑） |
| 4 | 主题色平滑过渡 | `ui/theme/LumoTheme.kt:342` | `animateColorAsState(targetValue = state.accent, tween(motion.duration(motion.accentDurationMs), easing = FastOutSlowInEasing))`，动画关闭时用 `snap()`。**只动画这一个输入**：Material 3 的色调角色全部从它派生，所以整个配色是交叉淡入的。KDoc（`:328`）写明理由：用户可以在设置里改主题色，硬切会读成闪烁 |
| 5 | 收藏成功反馈 | `feedback/LumoFeedback.kt:57`（`bookmarkAdded()`）+ `ui/HomeScreen.kt:682` | 声音与触觉是 `SoundCue.BookmarkAdded`（246 ms，`feedback/SoundCue.kt:153`）与其映射的 `HapticCue.Success`（`:76`）；视觉反馈是卡片插入列表后走第 1 条的入场动画——新卡片从上方轻微上移到位，不需要额外弹窗 |
| 6 | 置顶 / 删除 / 撤销反馈 | `ui/LumoApp.kt:579`（撤销条）+ `ui/HomeScreen.kt:244` | 撤销条 `AnimatedVisibility(slideInVertically { it } + fadeIn(tween(160)) / slideOutVertically { it } + fadeOut(tween(120)))`，是删除与整库恢复的唯一回退入口；拖动排序时同屏卡片用 `animateFloatAsState(..., animationSpec = theme.motion.springMedium())`（`:246`）让位，`springMedium()` 在 `Motion.kt:96`；置顶状态由卡片上的徽标（`:753`）与排序位置共同表达 |
| 7 | 加载进度动画 | `ui/BrowserScreen.kt:584` | `animateFloatAsState(targetValue = state.progress.coerceIn(0f, 1f), tween(theme.motion.duration(220)))`。**进度条是画出来的，不是布局出来的**（同处上方的注释）：一次页面加载里如果每帧都重新测量布局，进度条自己就会变成掉帧源 |
| 8 | 渐变光斑与玻璃质感 | `ui/components/LumoBackground.kt:179`（`DriftingAurora`） | 一个 `rememberInfiniteTransition(label = "lumo-aurora")` 驱动两个相位：`tween(31_000, LinearEasing)`（`:192`）与 `tween(47_000, LinearEasing)`（`:201`），互质周期所以重复间隔很长。玻璃质感由 `SurfaceTreatment`／`lumoButtonSurface` 承担（`ui/components/Surfaces.kt`），不是动画 |
| 9 | 全局动画等级 | `Motion.kt:180-182` + 设置页 `ui/SettingsScreen.kt` | 强度滑杆写入 `animationStrength`（`data/SettingsRepository.kt:49` 附近），经 `MotionPresets.config(preset, strengthInput, reduceMotion)`（`Motion.kt:124`）产生 `MotionConfig`；预设改的是各项基准时长与幅度，强度是对**全部**时长的统一乘数 |
| 10 | 系统减少动效时降低非必要动画 | `MainActivity.kt:176` → `ui/theme/LumoTheme.kt:203-223` | `isSystemReduceMotionEnabled(app)`（`util/WindowUtil.kt:36`）与用户自己的 `prefs.reduceMotion` 在 `buildLumoTheme` 里合并成一个旗标（`:222`），再进入 `MotionPresets.config`：**强度封顶 0.35**（`Motion.kt:180`），**漂移背景直接关闭**（`:186`）。合并发生在纯函数里是有原因的——应用内开关曾经有一个版本「存下来了、读回来了、显示为开、但从未被查询」（`LumoTheme.kt:198-201` 的注释记着这次事故） |

---

## 3. 三个预设

| | 沉浸 Immersive | 柔和 Soft | 极简 Minimal |
| --- | --- | --- | --- |
| 卡片入场时长 / 错峰 / 位移 / 起始缩放 | 520 ms / 45 ms / 26 px / 0.94 | 380 ms / 28 ms / 14 px / 0.97 | 190 ms / 0 ms / 4 px / 0.995 |
| 按下缩放 / 按压时长 | 0.972 / 90 ms | 0.975 / 80 ms | 0.99 / 60 ms |
| 释放弹簧（阻尼 / 刚度） | 0.36 / 520 | 0.62 / 460 | 1.0 / 700 |
| 导航转场 | 340 ms | 250 ms | 140 ms |
| 主题色过渡 | 460 ms | 340 ms | 160 ms |
| 反馈 / 基准时长 | 280 / 400 ms | 240 / 300 ms | 160 / 160 ms |
| 漂移背景 | 开 | 开 | **关** |

（数值来源：`Motion.kt:126-175`。三者的 `strength` 都是 `1f`——预设改的是形状，不是速度。）

极简的阻尼 `1f` 是**不弹**：`Spring.DampingRatioNoBouncy` 语义，配合 700 的刚度让释放在一帧量级内结束。这不是「更快的沉浸」，是另一种手感。

---

## 4. 两条独立的「少一点效果」通道

| | 减少动效 Reduce motion | 简化视觉效果 Simplified effects |
| --- | --- | --- |
| 键 | `reduce_motion`（`SettingsRepository.kt:50`） | `simplified_effects`（`:107`） |
| 语义 | 「**别动**」 | 「**别画重的东西**」 |
| 作用 | 强度封顶 0.35 → 所有时长按比例缩短；漂移背景关闭；系统 flag 与它合并 | 漂移背景关闭（`LumoTheme.kt:144`）；阴影降级（`Surfaces.kt:151`、`:159`、`:167` 传 `theme.simplifiedEffects`，`:519` 直接把 elevation 归零） |
| 不影响 | 主题、配色、圆角、布局 | 主题、配色、圆角、动画设置 |

`LumoTheme.kt:137` 的注释把两者分开写明白了：想要**更少运动**的人要的是 `reduceMotion`，只是不想要**厚阴影和动态背景**的人要的是 `simplifiedEffects`。把它们合成一个开关会让其中一半用户被迫接受另一半的取舍。

**注意不要把「减少动效」理解成「跳过启动动画」**：`STARTUP_ANIMATION.md` §1 记录了这一点，`startupTimeline` 只在**强度为 0** 时返回 `null`。

---

## 5. 性能契约（背景动画不得影响网页操作、滚动与续航）

`LumoBackground.kt:47-71` 的注释是一份可执行的契约，四条：

1. **构图期不读状态**。两个漂移相位刻意保留为 `State<Float>` 而不是用 `by` 委托（`:186-188`），唯一读取点是下面的 `graphicsLayer { }` 块（`:217-225`）。用 `by` 的话读取发生在组合期，每一帧都会让 Compose 重新组合整个背景。
2. **只有 `graphicsLayer` 参数在变**。一帧的代价是重写 RenderNode 的变换，不触发测量、布局或重绘。
3. **非极光样式完全没有动画**：`Plain`／`Gradient`／`Glass` 等分支是静态 `Canvas`（`:250`、`:252`、`:254`），只有 `Aurora` 走漂移层（`:128`）。
4. **环境动效关闭时不组合动画层**（`:61`），并且有 10% 的 overscan（`AURORA_OVERSCAN`，漂移幅度 `AURORA_DRIFT_DP = 22f`，`:483`）保证平移与呼吸永远不会露出硬边。

**与 WebView 的关系**：背景在浏览器页面**下面**，网页本身是独立渲染的 WebView，页面滚动不驱动任何 Compose 动画。全库没有 `Modifier.blur`／`RenderEffect` 用于背景（启动动画同理，见 `STARTUP_ANIMATION.md` §5）——半透明 `graphicsLayer` 会强制整屏离屏缓冲，那是续航与滚动流畅度上最容易踩的坑。

---

## 6. 滚动绝不发声、绝不振动

规格明令禁止在每次滚动或重绘时播放音效或振动。这条不是靠自觉，而是靠触发点结构：

- `browser/LumoWebView.kt:462-467` 的 `onScrollChanged` 只做一件事：`scrollMemory?.remember(url.orEmpty(), t)`（记住阅读位置，供返回时恢复）。**这里没有任何反馈调用**。
- 声音与触觉只从用户**动作**触发：`Modifier.lumoPress` 只发 `PressInteraction.Press` → `pressFeedback.press()` 与 `PressInteraction.Cancel` → `pressFeedback.release()`（`Surfaces.kt:269-277`）。**提交型点击不补第三声**：按下的那一声已经是这次交互的声音，成功与否由结果决定要不要再响一次。
- 区分「按下」与「结果」的代价是必须有分类表，这是 [`SOUND_FEEDBACK.md`](SOUND_FEEDBACK.md) 与 `SoundGateTest`／`SoundCueTaxonomyTest` 覆盖的内容。

---

## 7. 已知缺口（如实记录）

1. **`feedbackDurationMs` 与 `baselineDurationMs` 目前没有消费点**。两者在三个预设里都有值（`Motion.kt:137-138`、`:154-155`、`:171-172`），但全库只有 `Motion.kt` 自己提到它们：撤销条用的是字面量 160/120（`LumoApp.kt:579`），浮层用的是各自的 `enterMs`／`exitMs`。也就是说**这两个旋钮现在转不动任何东西**。要么把撤销条与浮层的时长接到它们上面，要么把它们从 `MotionConfig` 里删掉——留着不说会让人以为改预设就能改变这两个反馈的速度。
2. **真机观感未验证**。本环境没有模拟器或真机（`adb devices -l` 只有表头），所以帧率、是否有可见卡顿、长时间冷启动下的表现、以及低端机上的实际续航都无法在这里测。可复现的采集命令写在 `README.md`：

   ```
   adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset
   ```
3. **强度滑杆的中间档只被单元测试覆盖**（`MotionAndThemeTest`），没有被真机肉眼验证过——0.35 与 0.7 的手感差异是主观的，代码只能保证它单调。
