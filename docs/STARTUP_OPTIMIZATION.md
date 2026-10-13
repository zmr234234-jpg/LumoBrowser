# 启动黑屏的根因与优化（v1.05）

本文对应 v1.05 的第 ③ 项：「启动先黑屏再出现启动动画」。它记录**黑屏到底是什么**、**为什么会出现**、**改了什么**、以及**哪些结论还没有在真机上验证**。

应用内那三段动画本身的设计见 [`STARTUP_ANIMATION.md`](STARTUP_ANIMATION.md)；v1.05 没有改变它的形状，只改了时长，并让系统启动窗口在它之前接管画面。

---

## 1. 黑屏是什么

用户看到的顺序原本是：

```
点击图标  →  [一段没有任何内容的深色矩形]  →  品牌标记淡入  →  首页
```

中间那段空矩形就是「黑屏」。它不是 Compose 画错了，而是**在应用内动画开始之前，屏幕上确实什么内容都没有**——只有一个纯色窗口背景。

关键在于它持续多久。窗口背景从进程 fork 那一刻就存在，而第一个 Compose 帧要等到 `MainActivity.onCreate` → `setContent` → 组合 → 布局 → 绘制整条链路走完。这段空窗期完全由系统展示，应用没有任何东西可以画。

改之前，应用内动画还要再叠加 **2340 ms**：

| 常量 | v1.04 | v1.05 |
| --- | --- | --- |
| `BASE_BRAND_MS` | 960 | **260** |
| `BASE_LIGHT_MS` | 1720 | **400** |
| `BASE_HANDOFF_MS` | 2000 | **480** |
| 合计 | **2340 ms** | **1140 ms** |

于是「黑屏 → 动画」被拉成了一个很显眼的、总长超过两秒的开场。

---

## 2. 改了什么

### 2.1 让系统启动窗口承担开场（新增 `Theme.LumoBrowser.Starting`）

v1.04 只声明了一个主题 `Theme.LumoBrowser`，它唯一可见的属性是一条纯色 `android:windowBackground`。启动窗口用的就是它，所以系统能画出来的东西只有一个空矩形。

v1.05 新增一个专门的**启动主题**，父主题是 `Theme.SplashScreen`：

```xml
<style name="Theme.LumoBrowser.Starting" parent="Theme.SplashScreen">
    <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
    <item name="windowSplashScreenBackground">@color/lumo_backdrop</item>
    <item name="windowSplashScreenAnimationDuration">400</item>
    <item name="postSplashScreenTheme">@style/Theme.LumoBrowser</item>
    <item name="android:windowBackground">@color/lumo_backdrop</item>
</style>
```

四个属性各自的作用：

- `windowSplashScreenAnimatedIcon`：API 31+ 的系统启动窗口里显示的图标。**品牌标记从系统能画第一帧起就在屏幕上**，空矩形没有了。
- `windowSplashScreenBackground`：和 Compose 启动遮罩自己画的背景色**是同一个颜色**（`@color/lumo_backdrop` = `#FF06070E` = `LumoThemeState.backgroundBase`），所以系统窗口到应用内遮罩的交接是同一块面，而不是两块相邻的深色矩形。
- `windowSplashScreenAnimationDuration`：**只是图标退出动画的长度，不是启动页停留时间。** 库自己的文档对此说得很明确，不能拿它当计时器用。
- `postSplashScreenTheme`：`installSplashScreen()` 在 `setContent` 之前把活动切回普通主题，这是它必须能从当前主题解析出来的东西——也正因如此，父主题必须是 `Theme.SplashScreen`。

`Theme.LumoBrowser` 保持原样，继续做启动之后的活动主题，所以系统拆掉启动窗口时落在的是这个应用一向就有的那个窗口上。

### 2.2 依赖与安装

- `gradle/libs.versions.toml`：新增 `coreSplashscreen = "1.0.1"` 与 `androidx-core-splashscreen` 别名。
- `app/build.gradle.kts`：`implementation(libs.androidx.core.splashscreen)`。它把平台启动窗口向后移植到 API 26+；API 31+ 上则正是它把上面那些平台属性接到真正的系统启动窗口上。
- `app/src/main/AndroidManifest.xml`：**只有 MainActivity 的 `android:theme` 改成 `@style/Theme.LumoBrowser.Starting`**。`<application>` 的主题**故意不动**——一个没有调用 `installSplashScreen()` 的入口拿到启动主题，会永远停在启动主题上。清单里没有第二个 Activity。

### 2.3 `installSplashScreen()` 的位置

`app/src/main/java/com/lumobrowser/app/MainActivity.kt` 的 `onCreate` 第一句：

```kotlin
installSplashScreen()
super.onCreate(savedInstanceState)
```

**必须在 `super.onCreate` 之前。** 这是库的契约，写在 `MainActivity.kt` 的注释里。

### 2.4 应用内动画收到 600–1200 ms 预算内

`app/src/main/java/com/lumobrowser/app/ui/components/StartupAnimation.kt` 的三个常量改成 `260 / 400 / 480`，合计 **1140 ms**。三段的比例与先后顺序保持（480 > 400 > 260，新比例 1 : 1.54 : 1.85，旧的是 1 : 1.79 : 2.08）。

`startupTimeline()` 在关闭动画时仍然返回 `null`，`LumoStartup` 仍然把 `null` 当作跳过。系统「减少动效」仍然是把强度**压到 0.35**（1140 → 399 ms），而不是变成跳过——这条行为没有变。

---

## 3. 刻意没有做的事

### 3.1 不用 `setKeepOnScreenCondition`

库提供了这个钩子，可以一直把系统启动窗口按在屏幕上，直到应用说「好了」。**没有用它，这是决定。**

它的语义是**把第一帧挡在后面**。这个应用已经因为「为了动画强等」付出过一次代价，再引入一个「必须等某个条件才允许出现第一帧」的开关，等于把黑屏的风险从「一开始」搬到「条件不满足的时候」——而条件不满足的那条路径从来没人测。

系统启动窗口的存续时间由**第一帧什么时候准备好**决定。让它自然交接，比让应用去猜一个时间点安全。

### 3.2 不靠延长动画掩盖空窗

反过来也一样：把动画拉长只会让「黑屏 + 动画」这段总时长更长。1.05 的方向是把总时长从 2340 ms 压到 1140 ms，同时让空窗期本身有内容。

### 3.3 深色/浅色都用同一个背景色

`app/src/main/res/values-night/themes.xml` 是 v1.05 **新建**的文件，里面是 `values/themes.xml` 那两个主题的字面副本，**背景色没有翻成浅色**。

理由是真实的：LumoBrowser 的默认外观是深色，而它由**用户自己的偏好**在 Compose 里决定，不是由系统 `uiMode` 决定。默认配置下 `buildLumoTheme` 返回深色方案，所以 Compose 启动遮罩在一台处于浅色模式的设备上**同样**画 `backgroundBase` = `0xFF06070E`。把启动窗口在这里翻成浅色，只会让它和它要交接的遮罩对不上，把一个方向的闪烁换成另一个方向的。

那这个目录买到了什么：**它把夜间变体显式写出来，而不是让它碰巧继承。** 如果没有它，夜间启动主题会从库的 `Theme.SplashScreen` 继承 `Base.v21.Theme.SplashScreen`（一个 `android:Theme.DeviceDefault.NoActionBar`），它的 `?android:colorBackground` 和兼容启动 drawable **都不保证是深的**。那才是这个文件要挡住的故障：一个深色应用的启动窗口因为在夜间模式下没人覆盖启动主题的默认值而渲染成浅色。

### 3.4 图标用前景 drawable，不用自适应图标

`windowSplashScreenAnimatedIcon` 指向 `@drawable/ic_launcher_foreground`（自适应启动图标自己的前景层），**不是** `@mipmap/ic_launcher`。原因是 API 31 以下，把自适应图标当作启动图标使用会被不可预测地裁剪和缩放，而指向前景 drawable 是官方文档给出的规避方式。

---

## 4. 没有验证的部分

**没有在真机上验证。** 这台环境没有设备也没有模拟器。

已经验证的：

- `gradle :app:compileDebugKotlin` 通过，无 `e:`；
- 单元测试里 `StartupSmokeTest`（7 例）与 `AppFlowSmokeTest`（11 例）在 Robolectric `@Config(sdk = [33])` 下**真的启动了 `MainActivity`**，所以新的活动主题加 `installSplashScreen()` 在 SDK 33 上被实际执行过并且没有破坏启动；
- `StartupTimelineTest`（12 例）钉住三段的时长、顺序与窗口边界。

没有验证的，需要真机手工确认：

- **冷启动时黑屏是否真的消失。** 本环境无法观察一次真实冷启动。
- **API 26–30 与 API 31+ 要分别看。** 两条路径的实现不同：API 31+ 用平台属性，API 26–30 用库的兼容启动窗口（`android:windowBackground` 那条就是给它用的）。
- **系统窗口与应用内遮罩的接缝。** 两者设计成同色，但这是读 `LumoTheme.kt` 与库源码得出的结论，没有看过相邻的两帧。
- **`windowSplashScreenAnimationDuration = 400`** 无法目视判断；标记是静态矢量，没有别的东西依赖它。
- **首帧速度对观感的影响。** 在 API 31+ 上，启动窗口能不能一直撑到第一帧就绪而不出现可见缝隙，取决于首 Compose 帧多快到达。本次没有加任何计时器，也没有阻塞主线程。

---

## 5. 相关文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/res/values/themes.xml` | `Theme.LumoBrowser.Starting`（启动主题） |
| `app/src/main/res/values-night/themes.xml` | 新建，夜间字面副本 |
| `app/src/main/AndroidManifest.xml` | MainActivity 的主题指向启动主题 |
| `app/src/main/java/com/lumobrowser/app/MainActivity.kt` | `installSplashScreen()`（`onCreate` 第一句） |
| `app/src/main/java/com/lumobrowser/app/ui/components/StartupAnimation.kt` | `260 / 400 / 480` = 1140 ms |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | `androidx.core:core-splashscreen:1.0.1` |
| `app/src/test/java/com/lumobrowser/app/ui/components/StartupTimelineTest.kt` | 12 例时长/顺序/边界 |
