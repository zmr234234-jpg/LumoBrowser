# 启动动画

本文对应 v2.2 规格第五节。它说明三阶段动画**怎么分配时间**、**什么时候不播**、以及**为什么它不会拖慢启动**。

> **1.03 更新**：动画实现在 1.03 **没有改动**。变的是它**有测试了**——2.2 遗留清单第 ⑧ 项「`startupTimeline()` 的纯函数测试」已经关掉，见 [`TESTING.md`](TESTING.md) §0A.1 与 `app/src/test/java/com/lumobrowser/app/ui/components/StartupTimelineTest.kt`（12 例）。测试钉住的几条性质：强度为 0 时返回 `null`（是**跳过**不是缩短）、`coerceAtLeast(1)` 保证**每个阶段都不少于 1 毫秒**（阶段为 0 不是更短的动画而是顺序违例）、九档强度下阶段边界严格落在时间轴内部、满强度时落在本文点名的窗口内。

> **1.07 更新（本文档这一节已按当前源码改正）**：时间基准**从 480/860/1000 = 2340 ms 改成 260/400/480 = 1140 ms**（`StartupAnimation.kt:102-104`），§1 的表与预设时长随之更新。同一轮还新增了**平台启动窗口主题** `Theme.LumoBrowser.Starting`，所以 **§6 整节重写**——旧版 §6 写的「没有接入 `core-splashscreen`、没有配置 `windowSplashScreen*` 属性」在 1.05 之后**已不再成立**，那是本文档与源码脱节的地方，不是代码的问题。§7.1 也已作废：`startupTimeline()` 的纯函数测试**已经存在**（旧文说它还没写）。

---

## 1. 三阶段与时间

规格把三个阶段按墙钟窗口命名：品牌淡入到 0.5 s、光线过渡到 1.4 s、主界面在 2.4 s 前到位。这三个数字是**上限**，实现整体落在它们之内（1140 ms 走完三段，每一段的结束点都早于对应窗口）。

实现在 `app/src/main/java/com/lumobrowser/app/ui/components/StartupAnimation.kt`：

| 阶段 | 常量 | 基准时长 | 累计 |
| --- | --- | --- | --- |
| 1 · 品牌淡入 + 极小缩放 | `BASE_BRAND_MS`（`:102`） | 260 ms | 260 ms |
| 2 · 光线 / 渐变 | `BASE_LIGHT_MS`（`:103`） | 400 ms | 660 ms |
| 3 · 主界面渐滑 + 淡入 | `BASE_HANDOFF_MS`（`:104`） | 480 ms | **1140 ms** |

比例不变：交接仍是最长的一段、光线居中、开场沉降最短，所以三段的先后顺序与 2.4 s 版本一致，只是整体提前到启动预算（600–1200 ms）之内完成。平台的启动窗口主题已经承担了「把品牌标志第一时间放上屏幕」这件事（见 §6），覆盖层的工作是**把标志接过去**，而不是介绍它。

三个边界存的是**占总时长的比例**（`brandEnd`、`lightEnd`），不是绝对偏移量。这样动画强度设置可以整体缩放它，而没有任何一个阶段会漂到别的阶段前面去——用绝对毫秒数的话，强度调到 0.3 时三个窗口会挤在一起并互相重叠。

`startupTimeline(motion)` 在三个基准值上各乘 `motion.strength`，**每个阶段都不低于 1 毫秒**（`coerceAtLeast(1)`：某一段算成 0 不是更短的动画，而是阶段顺序违例）：

| strength | 来源 | 实际总时长 |
| --- | --- | --- |
| 1.00 | 默认（设置里的强度滑杆默认就是 1） | 1140 ms |
| 0.70 | 用户自行调低滑杆 | 798 ms |
| 0.35 | 减少动效的上限（见下） | 399 ms |
| 0.00 | 用户把滑杆拉到最低 | **不播放**（返回 `null`） |

**三个预设不改变这个时间轴**：`MotionPresets` 里三者的 `strength` 都是 `1f`（`Motion.kt:139`、`:156`、`:173`），预设改的是卡片入场、按压缩放、导航与强调色的时长，不是启动动画。能让启动动画变短的只有强度滑杆与减少动效上限。

系统开启「移除动画」或应用内「减少动效」打开时，`MotionConfig.strength` 被**封顶在 0.35**（`Motion.kt:180`），`startupTimeline` 返回 **399 ms** 的时间轴——**是缩短，不是跳过**。这是刻意的，而且与用户看到的文案一致：设置项写的是「系统开启『移除动画』时，本应用也会自动**降低**非必要动效」（`app/src/main/res/values-zh/strings.xml:103-104`），不是「移除全部动画」。真正被**完全关掉**的是**漂移背景**（`MotionConfig.backgroundAnimationEnabled`，`Motion.kt:186`），因为它是唯一一个自己不会停下来的效果；按压缩放、淡入、进度这些短反馈保持存活。只有强度为 0——也就是用户自己把滑杆拉到最低——才返回 `null`，那是**跳过**：要求不要动效的人没有要求「同一个东西的小号版本」。

---

## 2. 两个结构性决定

### 2.1 它是覆盖层，不是闸门

`LumoStartup` 从第一帧起就在下面组合并布局真实内容，动画只是在它**上面**画一层，然后揭开。没有 `isReady`、没有加载门、没有任何东西在等动画。

这一条是针对本项目上一个真实故障设计的。1.02 版本在手机上「装上了却打不开」，事后排除时确认启动路径上曾经出现过不该有的东西。**一个启动动画最坏的失败模式，就是它自己变成了打不开的原因。** 把它做成覆盖层之后，最坏情况是动画画得不好看——内容已经在下面了。

推论：`content` 的参数是普通的 `@Composable () -> Unit`，没有被包裹进任何条件分支。

### 2.2 它不拥有任何指针输入

`StartupAnimation.kt` 里没有 `clickable`，没有 `pointerInput`，没有可吞掉点击的东西。动画播到一半就开始操作的用户，仍然能碰到他瞄准的那个控件。

规格明确禁止这个动画拖慢响应，而「动画期间点不动」正是这条禁止最常见的违反方式。

---

## 3. 什么时候播，什么时候不播

规格 5.2 要求区分「冷启动」与「返回主界面／切换网页／从设置页返回」，且后者**不得重播**。

判断在 `MainActivity.kt:116-117`：

```kotlin
showStartupAnimation = savedInstanceState == null && !LumoApplication.startupAnimationShown
if (showStartupAnimation) LumoApplication.startupAnimationShown = true
```

两个条件都必须成立：

| 条件 | 拦住的场景 |
| --- | --- |
| `savedInstanceState == null` | 旋转屏幕、分屏尺寸变化、系统回收后重建 —— 这些都不是冷启动 |
| `!LumoApplication.startupAnimationShown` | 进程内第二次创建 Activity —— 语言切换会 `recreateActivity()`，那也不该重播 |

**旗标为什么是 process-scoped 而不是 `remember`**：`LumoApplication.startupAnimationShown` 是一个 `var`，活在 Application 上，所以它的生命周期就是进程的生命周期。用 Composition 里的状态做不到这件事——被重建的正是 Composition 本身。

**「返回主界面不重播」是免费得到的**：返回是导航，不经过 `MainActivity.onCreate`。主界面、编辑器、浏览器、设置页之间的移动全部在同一个 NavHost 里，动画根本不在那条路径上。

### 3.1 初始化提前完成时允许自然结束

时间轴自己走完就结束，不等任何别的东西。`StartupAnimation.kt:61-63` 的注释写明了：**没有任何基于 `totalMs` 的补时**，一个还在后面初始化的启动不是把用户留在这里的理由。

本应用的首帧内容本来就是真实的（`MainActivity.kt:134` 用 `UserPreferences.Default` 占据位，`LaunchedEffect` 拿到 DataStore 的值后立刻替换），所以「动画结束时界面已就绪」这件事不需要额外等待就已经成立。

### 3.2 初始化异常时不会卡在动画页

因为它是覆盖层，不是闸门：即使 `startupTimeline` 的计算、渐进值的推进或 `onFinished` 回调全部出问题，`content` 也已经在下面组合好了。**不存在一个「永远停在动画页」的状态**——那需要动画有能力把内容挡住，而它没有。

---

## 4. 启动音效

`LaunchEnd` 是唯一一个**用户没有碰任何东西就被触发的**声音，也是全部十九个 cue 里最长的（446 ms）。规格 5.3 要求它默认关闭。

- `UserPreferences.soundStartupCues` 的默认值是 **`false`**。
- 触发点在 `MainActivity.kt` 的 `LumoStartup(onFinished = …)`，与动画结束严格同步。
- 与动画**不互相等待**：`onFinished` 里先清 `playing` 旗标再播 cue，`SoundSynth` 的播放本身是异步的，动画不会为了音效多留一帧。
- 音效关闭或静音时，动画照常播完——音效不参与任何阶段的时间计算。

---

## 5. 视觉实现里刻意回避的东西

| 规格要求 | 做法 |
| --- | --- |
| 不得使用过重模糊 | 全程**没有用 `Modifier.blur` / `RenderEffect`**。光效用 `Canvas` 画径向渐变和路径实现 |
| 不得使用持续高成本特效 | 只有一个驱动源：一个 0→1 的 `progress` 值。三个阶段全部由它派生，没有第二个 `animate*AsState`、没有 `rememberInfiniteTransition` |
| 预加载不得阻塞主线程 | 动画不预加载任何东西。它画的都是程序化的图形，没有位图、没有 SVG 解析 |
| 只在冷启动播放 | 见 §3 |

只有一个驱动源还有一个副作用：三个阶段不可能互相漂移。用三个独立的 `animate*AsState` 的话，在强度设置为非整数比例时它们的实际结束时间会错开几毫秒，肉眼可见为一次抖动。

---

## 6. 系统 SplashScreen 与这个动画的关系

规格 5.1 要求**正确区分系统 SplashScreen 与应用内启动动画，不得让两者连续重复显示造成等待翻倍**。

1.05 起本应用**接入了 `androidx.core:core-splashscreen` 1.0.1**（`gradle/libs.versions.toml:8`、`:24`；依赖声明在 `app/build.gradle.kts:134`），入口是 `<activity>` 上的启动主题 `Theme.LumoBrowser.Starting`（`app/src/main/AndroidManifest.xml:116`），它的父主题是库自己的 `Theme.SplashScreen`（`app/src/main/res/values/themes.xml:38`）。四个关键属性：

| 属性 | 值 | 作用 |
| --- | --- | --- |
| `windowSplashScreenAnimatedIcon` | `@drawable/ic_launcher_foreground` | 品牌标志从进程 fork 起就在屏幕上。**刻意不引用 adaptive icon**：API 31 以下把 adaptive icon 当 splash 图标用会被不可预测地裁剪与缩放，指向它的 foreground drawable 是官方记在文档里的绕法 |
| `windowSplashScreenBackground` | `@color/lumo_backdrop`（`#FF06070E`） | 与 Compose 覆盖层自己画的背景**是同一个色值**，所以系统窗口与覆盖层是一块连续的表面，而不是两块碰巧挨着的深色矩形 |
| `windowSplashScreenAnimationDuration` | `0` | 这是**图标退出动画**的长度，不是 splash 停留时间（停留时间由首帧何时就绪决定，库文档明确说这个属性不影响它）。它曾经是 400 ms，而那是交接的错误一半：覆盖层会在同一位置、同一背景上再画同一个标志，于是标志先被平台淡出、再被应用画一遍——**标志播两遍**，正是规格点名要避免的。设为 0 之后交接在构造上就不可见 |
| `postSplashScreenTheme` | `@style/Theme.LumoBrowser` | `installSplashScreen()` 在 `setContent` 之前应用它，所以 Activity 从启动主题出来时已经是普通主题 |

**刻意没有使用 `setKeepOnScreenCondition`**：把首帧挡在任何东西后面，正是本应用已经上线过一次的「装上了却打不开」的失败模式（见 §2.1）。

`installSplashScreen()` 在 `MainActivity.kt:103`，位于 `setContent`（`:130`）之前。

真实的启动序列因此是：

1. 系统从进程 fork 起画启动窗口：`@color/lumo_backdrop` 上居中一个品牌标志；
2. 首帧就绪，平台拆掉启动窗口——没有退出动画，所以这次切换看不出来；
3. Compose 首帧，`LumoStartup` 覆盖层在**同一背景、同一位置**画**同一个**标志，然后推进三阶段时间轴。

**不存在两个连续的品牌动画**：标志自始至终只出现一次，平台与 Compose 之间交接的是「谁在画它」，不是重新介绍它。`Theme.LumoBrowser` 保持为 post-splash 主题，平台于是把启动窗口拆到应用本来就有的那个窗口上。

`values-night/themes.xml:33-40` 重复了同一组字面值，而不是翻成浅色背景：窗口背景在深色与浅色下是同一个值（首帧颜色一致性由 Compose 的 `Surface` 接手）。没有 `values-v31/`，也没有额外的 splash drawable。

---

## 7. 测试

`app/src/test/java/com/lumobrowser/app/` 下与启动相关的既有测试：

| 测试 | 覆盖 |
| --- | --- |
| `StartupSmokeTest`（7 例） | 冷启动路径能 inflate 到 `TestTags.HOME_ROOT`，主题与语言不错乱 |
| `AppFlowSmokeTest`（11 例） | 主界面 → 编辑器 → 设置 → 浏览器的真实导航 |
| `LanguageSwitchTest`（7 例） | 语言切换后重启进入正确语言（旧版本文档把它写成 `LanguageSwitchLaunchTest`／1 例，那是错的） |
| `StartupTimelineTest`（12 例） | `startupTimeline()` 的纯函数行为：强度映射、阶段地板、边界顺序、强度 0 的 `null` |
| `MotionAndThemeTest`（28 例） | 预设与强度的映射、系统 reduce-motion 的合并 |
| `SimplifiedEffectsTest`（10 例） | 简化效果开关的传播 |

这三个会 inflate 真实 Activity 的 Robolectric 测试（`StartupSmokeTest`、`AppFlowSmokeTest`、`LanguageSwitchTest`）都断言 `TestTags.HOME_ROOT` 存在——**只要动画还是覆盖层，它们就继续通过**，因为内容始终在组合里。如果哪天有人把 `LumoStartup` 改成闸门，这三个测试会立刻变红。这是刻意的：把「不许变成闸门」写进既有测试，比写进注释难违反。

### 7.1 已经补上的测试

`startupTimeline()` 的**纯函数测试**已经写好：`app/src/test/java/com/lumobrowser/app/ui/components/StartupTimelineTest.kt`（12 例），覆盖 `strength` 与三段的映射、每个阶段 `coerceAtLeast(1)` 的地板（某一段算成 0 是顺序违例而不是更短的动画）、九档强度下阶段边界严格落在时间轴内部、以及强度为 0 时的 `null` 分支。见 [`TESTING.md`](TESTING.md) §0A.1。

旧版这一节写的是「尚未做的」——那一条已经关闭。§1 里 `0.35 → 399 ms` 那一行**不是**「减少动效会跳过启动动画」：减少动效只是让时间轴缩短（封顶 0.35），`null` 只出现在用户把强度滑杆拉到 0 时。

---

## 8. 无法在本机验证的部分

需要真机或模拟器，本环境没有（`adb devices -l` 只有表头，无 AVD、无 system image）：

1. **动画的实际观感与流畅度**——帧率、是否有可见卡顿。
2. **首帧到达的延迟**——即系统纯色窗口显示多久。这个值取决于设备，代码无法测量。
3. **与系统启动窗口的衔接是否无缝**——是否有颜色跳变。
4. **「旋转屏幕不重播」的实际行为**——需要设备触发配置变更。
5. **长时间冷启动下是否掉帧**——需要 `dumpsys gfxinfo`。

第 5 项的复现命令已写在 `README.md`：

```
adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset
```
