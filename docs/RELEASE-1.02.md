# LumoBrowser 1.02 交付说明

本文件是 1.02 版本的**交付记录**，包含：交付物与校验值、构建日志、测试报告、状态栏适配说明、性能优化与对照数据、VPN 辅助说明、数据库迁移说明、源码包冷构建自足性验证。

`README.md` 讲怎么用，`docs/BUILD.md` 讲怎么从源码构建，`docs/TESTING.md` 讲怎么跑测试，`CHANGELOG.md` 讲改了什么。**本文件回答的是「这一版到底交付了什么、验证到什么程度」**——包括哪些测过、哪些没测过。

---

## 一、交付物

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `dist/LumoBrowser-1.02-debug.apk` | 18,377,227 | `E365F2A2DB1115651CAFCB1D2DC7EBC917817F90B06DF356E3C6C90C3F4163EE` |

这份 APK **就是本文档第八节那次冷构建的产物**，不是源码树里增量构建的产物。理由与追溯过程见第八节；签名与清单元数据见下面的 `apksigner` / `aapt2` 实测输出。

> `dist/LumoBrowser-1.02-source.zip` **自身的字节数与 SHA-256 不写在本文件里**，理由和 `README.md`、`docs/TESTING.md` 里那条一样：本文件就在压缩包内，写死会自我指涉——每改一次本文档，包的字节数与哈希就变一次，永远对不上。
>
> **交付物的精确哈希统一公布在压缩包之外的 `dist/SHA256SUMS.txt`**，`dist/` 下 6 个文件逐条实测校验通过（`checked=6 bad=0`）。

校验：

```powershell
Get-FileHash dist\LumoBrowser-1.02-debug.apk -Algorithm SHA256
Get-FileHash dist\LumoBrowser-1.02-source.zip -Algorithm SHA256
```

### APK 元数据（`aapt2 dump badging` 实测输出，非手写）

```
package: name='com.lumobrowser.app.debug' versionCode='3' versionName='1.02-debug'
sdkVersion:'26'
targetSdkVersion:'34'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.VIBRATE'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
launchable-activity: name='com.lumobrowser.app.MainActivity'
```

### 签名校验（`apksigner verify` 实测输出，非手写）

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
```

`apksigner verify` 退出码为 `0`。`launchable-activity` 存在、`MainActivity` 已 `exported="true"`——这两条是「装得上但打不开」最常见的两个成因，本包都不成立。

### 原生库：四种 ABI 全在，且都是 4096 字节对齐

`extractNativeLibs="false"`（Android 6+ 的标准打包方式）要求 APK 里的 `.so` 在 zip 中按页对齐，否则 `System.loadLibrary` 会抛 `UnsatisfiedLinkError`——这也是一种「装得上但打不开」。本包实测：

| 条目 | 未压缩大小 | zip 内数据偏移 | 4096 对齐 |
| --- | ---: | ---: | :---: |
| `lib/arm64-v8a/libdatastore_shared_counter.so` | 7,112 B | 17,825,792 | ✅ |
| `lib/armeabi-v7a/libdatastore_shared_counter.so` | 4,416 B | 17,842,176 | ✅ |
| `lib/x86/libdatastore_shared_counter.so` | 5,148 B | 17,858,560 | ✅ |
| `lib/x86_64/libdatastore_shared_counter.so` | 6,224 B | 17,874,944 | ✅ |

四种 ABI 齐全，因此「手机是 arm64 而包里只有 x86」这类也不成立。这是直接从 APK 的 zip 本地标头读出来的偏移，不是从构建脚本推断的。

**另外查了 16 KB 页这一条**（Android 15 起部分设备用 16 KB 内存页，Google Play 从 2025 年 11 月起要求新应用支持）：四个 `.so` 的 **ELF 程序头 `PT_LOAD` 段 `p_align` 全部是 `16384`**，不是旧的 4096：

| 条目 | ELF 位数 | `PT_LOAD` 段的 `p_align` | 16 KB 页兼容 |
| --- | --- | --- | :---: |
| `lib/arm64-v8a/libdatastore_shared_counter.so` | ELF64 | `[16384, 16384, 16384]` | ✅ |
| `lib/armeabi-v7a/libdatastore_shared_counter.so` | ELF32 | `[16384, 16384]` | ✅ |
| `lib/x86/libdatastore_shared_counter.so` | ELF32 | `[16384, 16384]` | ✅ |
| `lib/x86_64/libdatastore_shared_counter.so` | ELF64 | `[16384, 16384]` | ✅ |

（zip 内数据偏移 17,825,792 / 17,842,176 / 17,858,560 / 17,874,944 也**同时**是 4096 和 16384 的整数倍。）

所以「手机上装得上却打不开」的原生库一侧（ABI 缺失、页对齐、16 KB 页）三条都已经实测排除。

> LumoBrowser 自身没有写任何 C/C++；这四个 `.so` 来自 AndroidX DataStore 的计数器实现。

### 这是什么包，不是什么包

- 这是 **Debug 包**：`applicationId` 带 `.debug` 后缀，与正式包**可并存安装**；用 Android 默认调试密钥签名，证书指纹每台机器不同。
- **它不是正式签名版本。** 本轮没有配置正式签名（没有 keystore、也没有密码），所以不产出 Release APK——把 Debug 包改名成 Release 包就是伪造。要正式包请见 `docs/BUILD.md` 的签名一节。
- Debug 包保留了 `BuildConfig.BUILD_TIME`（编译时间戳），设置页「关于」面板会显示 `versionName (versionCode) · 收藏数 · 构建时间`。侧载安装的包必须能自己说清是哪一个。

### 源码包内容

`LumoBrowser-1.02-source.zip` 含 **94 个文件**，解压后根目录即 `LumoBrowser/`，可直接用 Android Studio 打开。已排除：

- `build/`、`.gradle/`、`.kotlin/`、`.idea/` —— 构建与 IDE 产物；
- `dist/` —— 含上一个版本的 APK 与源码包，避免包中包；
- 全部 `*.txt` —— 开发过程中留下的 Gradle 输出日志（共 43 个）；
- `local.properties` —— 机器相关，写的是本机的 SDK 路径，恢复到别的机器上会指向不存在的路径。

未排除且**必须保留**：`gradlew` / `gradlew.bat` / `gradle/wrapper/`（Android Studio 导入需要）、`app/schemas/`（Room 导出的 schema JSON，是迁移测试的比对基准）、`app/proguard-rules.pro`。

源码包里已包含 `README.md`、`CHANGELOG.md` 与 `docs/` 三份文档，无需另外下载。

---

## 二、构建日志

### 环境（实测）

| 项 | 值 |
| --- | --- |
| JDK | 17.0.12 LTS（`D:\Program Files\Java\jdk-17`） |
| Gradle | 8.6（`D:\AndroidDev\gradle-8.6`，命令行用 `gradle.bat`，不用 `gradlew`） |
| Android SDK | `D:\AndroidDev\sdk`，platform 只有 `android-34` |
| build-tools | 34.0.0（`aapt2` / `apksigner` 所在目录） |
| AGP / Kotlin | 8.4.2 / 1.9.24 |
| Compose BOM | 2024.05.00 |
| Room / DataStore / WebKit / Coil | 2.6.1 / 1.1.1 / 1.12.0 / 2.6.0 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 34 |

### 命令

> **本节记录的是源码树里的那次构建**，它产出了交付物**之前的候选包**。最终交付的 `dist/LumoBrowser-1.02-debug.apk` 换成了**对交付源码包做冷构建**的产物（理由与差异追溯见第八节）。这里的数字仍然有效——它说的就是「在这台机器的源码树里构建」这件事。

```powershell
$env:JAVA_HOME="D:\Program Files\Java\jdk-17"
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT="D:\AndroidDev\sdk"
Set-Location "D:\deepseek\Website\LumoBrowser"
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

### 结果

```
BUILD SUCCESSFUL in 3m 9s
```

- `:app:testDebugUnitTest` —— **263 例，0 失败，0 跳过**
- `:app:assembleDebug` —— 产出 `app/build/outputs/apk/debug/app-debug.apk`

**零 error、零 warning**（唯一的提示是 AGP 建议把 `BuildConfig` 迁移到 Gradle 构建文件，属模板自带的迁移建议，不是本次改动引入的）。

`--no-daemon` 是刻意的：本机 Gradle 守护进程反复出现过句柄未释放导致后续构建卡住的情况，单次构建多花约十几秒，换来确定性。

### 构建过程里修掉的问题

构建不是一次过的。按发生顺序，真实错误与处理：

1. **`WebViewFeature` 宣称支持却抛异常** —— `UnsupportedOperationException` 从 `setAlgorithmicDarkeningAllowed` 抛出，经 `BrowserConfigManagerKt.applySettings` → `LumoWebView.applySiteConfig` → `AndroidView` 的 `factory`，**让整个浏览器页在构造期崩掉**。修法是 `featureGated` 保留能力查询并 catch `RuntimeException`（刻意不 catch `Throwable`）。见 `CHANGELOG.md`。
2. **PowerShell 写坏了源文件的编码** —— 本机 `pwsh` 的 `Set-Content` 默认按 ANSI（CP936/GBK）写文件，用它改写 Kotlin 源码把破折号 `—`（UTF-8 `E2 80 94`）压成了 `E2 80 3F`，共 24 处，另有两行中文被破坏。改用 Python 以**字节层**替换修复，并写扫描脚本确认全库 108 个文本文件 `invalid=0`。**教训：本工程不允许用 PowerShell 的 `Get-Content`/`Set-Content`/`-replace` 改写源码或多语言字符串。**
3. **迁移测试的 fixture 缺少索引** —— 详见「测试报告」第四节，这是**测试写错**，不是应用缺陷。

---

## 三、测试报告

### 总览

```
:app:testDebugUnitTest → 263 tests, 0 failures, 0 errors, 0 skipped（18 个测试类）
```

结果文件：`app/build/test-results/testDebugUnitTest/TEST-<全限定类名>.xml`。

> 注意：此 Gradle 版本把 `<failure>` 挂在 `<testsuite>` 层，**不是每个 `<testcase>` 一个**。统计总数要看 `testsuite` 标头的 `tests=` / `failures=` 属性，逐个数 `<testcase>` 下的 `<failure>` 会得到 0，看起来像全过了。

### 逐类明细（实测耗时）

| 测试类 | 用例数 | 失败 | 耗时(s) | 覆盖什么 |
| --- | ---: | ---: | ---: | --- |
| `AppFlowSmokeTest` | 11 | 0 | 12.06 | 真启动 MainActivity，点击主流程 |
| `StartupSmokeTest` | 7 | 0 | 0.93 | 启动不崩 |
| `LanguageSwitchLaunchTest` | 1 | 0 | 136.58 | 切换语言后重建 Activity |
| `LanguageSwitchDecisionTest` | 6 | 0 | 1.11 | 重建决策纯函数 |
| `BookmarkRepositoryTest` | 15 | 0 | 0.71 | 收藏增删改查/排序/置顶 |
| `SettingsRepositoryTest` | 10 | 0 | 0.46 | 偏好持久化与恢复 |
| `BackupManagerTest` | 14 | 0 | 0.05 | 备份导出/导入、旧备份兼容 |
| `UrlToolsTest` | 24 | 0 | 0 | 网址规范化、非法网址 |
| `JsonUtilTest` | 18 | 0 | 0 | JSON 转义与解析 |
| `FormatUtilTest` | 14 | 0 | 0 | 域名提取、时间格式化 |
| `BrowserModeTest` | 18 | 0 | 0 | 四种模式的差异与稳定性 |
| `AutoModeTest` | 28 | 0 | 0.01 | AUTO 阶梯有界性、不可配置的失败 |
| `BrowserConfigManagerTest` | 30 | 0 | 0.02 | 安全策略固定、UA 优先级 |
| `MotionAndThemeTest` | 27 | 0 | 0.02 | 动画预设与系统减少动效 |
| `SimplifiedEffectsTest` | 10 | 0 | 0 | 性能开关对主题的影响 |
| `SurfaceElevationTest` | 4 | 0 | 0.03 | 阴影高度规则 |
| `DatabaseMigrationTest` | 11 | 0 | 2.00 | v1→v2→v3 迁移（真 SQLite 文件） |
| `NetworkAssistTest` | 15 | 0 | 0.01 | 每站点网络辅助的读写链路与全部提示分支 |
| **合计** | **263** | **0** | **154.03** | |

`LanguageSwitchLaunchTest` 的 136.58 秒占全部用例时间的 89%——它真的启动 `MainActivity` 并等 `recreate()` 收敛。这是「一个每次启动必崩的缺陷能完全隐身」的代价换来的，值得付。

### 本次新增的三个测试类

**`DatabaseMigrationTest`（11 例，真 SQLite）**
标注 `@RunWith(AndroidJUnit4::class) @Config(sdk = [33]) @SQLiteMode(SQLiteMode.Mode.NATIVE)`——`NATIVE` 走的是真 SQLite，不是 Robolectric 的内存假实现，否则迁移的 DDL 语法根本没被验证过。手法：用 `FrameworkSQLiteOpenHelperFactory`（Room 生产环境用的同一个 factory）以**手写的 v1/v2 DDL** 造出真数据库文件，再让 Room 用 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)` 打开它。

**`SimplifiedEffectsTest`（10 例）** 与 **`SurfaceElevationTest`（4 例）**：见第五节。

### 三次失败与根因（都是测试的洞，不是应用的洞）

第一次运行：**243 例 5 失败**。逐条查到根因后，发现**没有一条是应用缺陷**，全是我的测试夹具写错了——这正是测试该做的事：如果它写成「和实现一样」，这 5 个问题一个都不会暴露。

**① 4 例失败：`IllegalStateException: Migration didn't properly handle: bookmarks(...)`**

```
Expected: indices=[Index{name='index_bookmarks_sortOrder'...}, Index{name='index_bookmarks_category'...}, Index{name='index_bookmarks_isPinned'...}]
   Found: indices=[]
```

根因：我手写的 v2 夹具只下了 `CREATE TABLE`，**漏了三个 `CREATE INDEX`**。`BookmarkEntity` 自 schema 1 起就声明了 `indices = [Index("sortOrder"), Index("category"), Index("isPinned")]`，Room 的 `onCreate` 一向会建它们。

**结论：漏索引的数据库是不存在的场景。** 夹具模拟一个真实设备上不存在的状态，本身就是错的。已改为 v1/v2 夹具都带上三个索引的完整 DDL，并**新增一例**断言迁移后三个索引仍在。

**② 1 例失败：`expected:<[..., networkAssist]> but was:<[...]>`**

根因不在迁移，在测试的惰性：`Room.databaseBuilder(...).build()` **只构造包装器**，`RoomOpenHelper.onUpgrade`（迁移与 Room 自己的 schema 校验都在这里）要到**第一次真正使用数据库**时才跑。那一例根本没触发迁移，却「通过」了除断言外的所有步骤。

修法：`openWithRoom()` 里显式 `database.openHelper.writableDatabase` **强制打开**。这个方法上写了 KDoc 说明为什么必须这么做——**不触碰数据库的迁移测试会假通过**，这是比失败更危险的情况。

**③ 3 例失败：`expected:<Auto> but was:<auto>` / `expected:<Desktop> but was:<desktop>` / `expected:<FollowGlobal> but was:<global>`**

根因：`BookmarkDao.getAllOnce()` 返回的是 **Room 实体** `BookmarkEntity`，其字段是**存储字符串**（`"auto"`、`"global"`），不是领域枚举。`assertEquals` 的签名是 `(Any?, Any?)`，所以拿枚举比字符串**编译期毫无警告，运行时必错**。

修法不是把断言改成字符串——那样测试就变成了「和实现一样」。改为走 `toDomain()` 断言，因为**「迁移留下了应用能用的数据」的定义就是 `toDomain()` 能把它变成正确的枚举**；同时对「升级行与新插入行必须无法区分」这一条，改成比较**存储字节**（`assertEquals(fresh.networkAssist, row.networkAssist)`），因为那一句问的正是磁盘上的值。

### 明确**没有**执行的测试

环境里**没有连接真机，也没有可用的模拟器**（`adb devices` 无设备；没有配置 AVD，也没有可用的硬件加速）。因此以下**全部未执行**，不得被视为已通过：

| 未执行的验证 | 为什么做不了 | 怎么做 |
| --- | --- | --- |
| 真机/模拟器安装与启动 | 无设备 | `adb install -r dist\LumoBrowser-1.02-debug.apk` |
| `:app:connectedDebugAndroidTest`（设备端插桩测试） | 无设备 | 接上设备后执行 |
| 真机页面加载、前进后退、`target="_blank"`、下载、文件选择 | 需要真实 WebView 与网络 | 见 `docs/TESTING.md` 手工清单 |
| **帧率 / 卡顿率 / 功耗**实测数据 | 需要设备上的 `gfxinfo`/Perfetto | 见第五节「怎么自己测出前后数据」 |
| VPN 辅助在真实受限网络下的表现 | 需要受限网络与已配置的 VPN | 见第六节 |
| 从 1.0.1 覆盖安装后收藏仍在（端到端） | 需要设备 | 装 1.0.1 → 加收藏 → 覆盖装 1.02 |

**这些不是「应该没问题」，是「没有验证」。** 迁移路径有单元测试覆盖（真 SQLite、真 DDL），但端到端的覆盖安装没有跑过。

---

## 四、状态栏与系统栏适配说明

### 问题 3 的根因不是内边距

真机反馈「点『添加网址』后添加页遮住系统状态栏、顶部布局异常」。查下来根因有两层，**都不是**加点 padding 能解决的：

**第一层：状态栏图标的明暗被写死了。** `themes.xml` 里 `windowLightStatusBar` 固定为 `false`（永远浅色图标），而 `enableEdgeToEdge()` 不带参数时，图标明暗跟随的是**系统**深色设置，不是**应用**主题。于是两种组合都会得到看不见的图标：

- 应用浅色主题 + 白色图标；
- 系统浅色 + 应用深色主题（应用用浅色图标，但系统栏后面是应用自己的浅色背景）。

看起来就像顶部布局坏了。修法：新增 `applySystemBarAppearance(window, isDark)`，由**应用实际解析出的主题**驱动 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`，在 `SideEffect` 中同步——窗口属性必须与正在绘制的画格一致，晚一帧就是闪一下。

**第二层：inset 的处理各屏不一致。** 有的屏用 `statusBarsPadding()`，有的用 `navigationBarsPadding()`，有的三连 `statusBarsPadding().navigationBarsPadding().imePadding()`，主屏则完全没有。本轮统一为一处定义。

### 统一后的 inset API

`app/src/main/java/com/lumobrowser/app/ui/components/Insets.kt` 是**唯一**的 inset 来源：

| 名字 | 值 | 给谁用 |
| --- | --- | --- |
| `LumoInsets` | `WindowInsets.safeDrawing` | 通用 |
| `LumoTopInsets` / `LumoBottomInsets` / `LumoVerticalInsets` | `safeDrawing` 的单边 | 分边控制 |
| `LumoStatusBar` | `WindowInsets.statusBars` | 只关心状态栏时 |
| `LumoScreenInsets` | `WindowInsets.systemBars` | **只给浏览器**：键盘弹出时系统栏仍在，`safeDrawing` 会让工具栏跳动 |

对应修饰符：`Modifier.lumoScreenInsets()` / `.lumoTopInsets()` / `.lumoBottomInsets()` / `.lumoVerticalInsets()` / `.lumoStatusBarInset()`。

**全库已无 `statusBarsPadding()` / `navigationBarsPadding()` / `imePadding()` 的直接调用**（只剩 `Insets.kt` 的 KDoc 里提到名字）。收敛到一个文件的好处很直接：状态栏问题只需要在一个地方改。

### 四屏的接入点

| 屏 | 文件 | 接入 |
| --- | --- | --- |
| 主屏 | `app/src/main/java/com/lumobrowser/app/ui/HomeScreen.kt:147` | 根 `Box` 加 `.lumoScreenInsets()`（此前完全没处理，是遮挡主因） |
| 浏览器 | `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt:281` | `val pageInset = if (immersiveFullscreen) Modifier else Modifier.lumoTopInsets()` |
| 编辑页 | `app/src/main/java/com/lumobrowser/app/ui/EditorScreen.kt:148` | 三连 padding → 单个 `.lumoScreenInsets()` |
| 设置页 | `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt:143` | 两连 → 单个 `.lumoScreenInsets()` |

### 进度条那个坑（必须记下来）

给浏览器顶部 2dp 进度条直接套 inset 是**错的**：`Modifier.lumoTopInsets()` 会把它的盒子撑成「2dp 高 + 状态栏高」，背景自然铺满整条。正确写法是**外层 `Box` 承担 inset，内层 2dp 条只管画**：

```kotlin
Box(Modifier.fillMaxWidth().then(pageInset).align(Alignment.TopStart)) {
    // 内层 2dp 进度条
}
```

### 沉浸式全屏

新增设置项「浏览器沉浸式全屏」（`browserImmersiveFullscreen`，默认**关**）。开启后浏览器网页区域不再让出状态栏，内容顶到屏幕边缘。

- 它**刻意不并入动画预设**：这是浏览器的布局选择，不是「动效强度」。三条动画相关的设置（`animationPreset` 口味、`reduceMotion` 无障碍、`simplifiedEffects` 持续成本）与它互不等价。
- 不读写任何系统级设置，只改本应用窗口内的布局。

---

## 五、性能：定位、优化与对照数据

### 定位：卡顿来自哪两处

问题 2 的反馈是「部分页面加载慢或滚动卡顿」。按固定检查项把渲染路径过了一遍，找到两类**持续性的**每帧成本：

**① 极光背景是一个永远不停的自旋。**

全应用只有**一处** `rememberInfiniteTransition`（`app/src/main/java/com/lumobrowser/app/ui/components/LumoBackground.kt:184`，标签 `"lumo-aurora"`），带两个 `animateFloat` + `infiniteRepeatable`（`:188`、`:197`）。它握着帧时钟，意味着只要它活着，Compose **每个 vsync 都会请求一帧**。而在浏览器里那一层是**被网页完全遮住**的——花在看不见的地方，还要和 WebView 自己的渲染器抢主线程。

**② 每一个卡片表面都是一次离屏模糊。**

`Modifier.shadow(...)` 在 `app/src/main/java/com/lumobrowser/app/ui/components/Surfaces.kt:200`（卡片/面板/栏）与 `:482-483`（按钮）两处调用。阴影不是免费的描边，是**每个表面一次单独的离屏渲染 + 模糊**。而主屏是**每个收藏一张卡片**，所以这一项的成本随收藏数线性增长，且正好落在用户持续滚动的那一屏上——这就是「滚动卡顿」这个感受的来源。

### 优化：分两层

**第一层（1.02 已做，自动生效）：** 导航到浏览器目的地时，背景层自动转为静态帧。用户的偏好没有被覆盖，只是不再花在看不见的地方。

**第二层（1.02 新增，用户可控）：设置页 → 性能 → 「简化效果」。**

`simplifiedEffects` 的语义是**「关掉持续性的渲染成本」**，不是口味、也不是无障碍：

| 开关 | 关掉什么 | 保留什么 |
| --- | --- | --- |
| `simplifiedEffects`（简化效果） | 动态背景、光斑/sheen 图层、所有投射阴影 | 主题、配色、圆角、卡片样式、**全部交互动画** |
| `reduceMotion`（系统减少动效） | 压缩所有位移与时长 | 结构、层级 |
| `AnimationPreset.Minimal`（极简预设） | 一部分装饰动画 | 用户的其余选择 |

**三者互不等价**，`SimplifiedEffectsTest` 里有专门的用例锁住这一点（`reduceMotionAndSimplifiedEffectsAreDifferentSwitches`、`minimalPresetAndSimplifiedEffectsAreDifferentSwitches`、`feedbackAnimationsSurvive_simplifyingIsNotTheSameAsFreezing`）。

接线的实现方式：

- `app/src/main/java/com/lumobrowser/app/ui/theme/LumoTheme.kt`：新增 `val simplifiedEffects get() = prefs.simplifiedEffects`；`backgroundGlow` 改为 `prefs.backgroundGlowEnabled && !simplifiedEffects`（`backgroundAnimates` 因为要求 `backgroundGlow` 而**自动**连带走假——不另加条件，否则会出现永远为假的死项）。
- 阴影规则被提取成**纯函数** `lumoShadowElevation(style, elevated, simplified): Dp`（`Surfaces.kt`）。这一条很重要：规则原来内联在 `@Composable` 里，**唯一能检查它的方法就是渲染整个应用用眼睛看**——一个开关接到空处也不会有人发现。提取之后，开关两侧都可测（`SurfaceElevationTest` 4 例）。

这个设置项**之前是一个假开关**：`simplifiedEffects` 在数据层有存储、有 setter、在设置页有 UI，但**没有任何渲染路径读它**。文案却写着「关闭动态背景与厚重阴影」，代码行为与文案完全不一致。现在补齐了。

### 可实测的对照数据

**必须区分两类数据。** 下表左半是我在这台机器上**真的测出来**的；右半是需要真机、**本轮没有执行**的。

#### 已实测（可复现）

| 数据 | 1.01（上一版） | 1.02 | 怎么得到的 |
| --- | ---: | ---: | --- |
| Debug APK 体积 | 17.45 MB（18,299,055 B） | 17.52 MB（18,377,227 B） | `dist/LumoBrowser-v2-debug.apk` vs `dist/LumoBrowser-1.02-debug.apk` |
| 单元测试用例数 | 147（1.0.1 记录） | 263 | `app/build/test-results/testDebugUnitTest/*.xml` |
| 测试类数 | — | 18 | 同上 |
| 全量测试耗时 | — | 154.03 s | 同上 |
| 全库无限动画数量 | 1 | **1**（`LumoBackground.kt:184`，且只在 `backgroundAnimates` 为真时可达） | 全库 grep `rememberInfiniteTransition` / `infiniteRepeatable` |
| 阴影调用点 | 2 | **2**（`Surfaces.kt:200`、`:482`），简化效果开启后**两处都返回 0.dp** | 全库 grep `Modifier.shadow(` |

**APK 体积变大 0.07 MB 不是退步**：新增了网络状态判定、VPN 状态机、迁移、四语言字符串。体积不是性能指标，列在这里只是因为它是我能**真的**报出来的数字。

**关于「无限动画数量」这一行**：它从 1 变成 1，看起来什么都没做。真正的变化是**可达性**——`simplifiedEffects` 打开后 `backgroundGlow` 为假、`backgroundAnimates` 连带为假，那条路径变成不可达。这是**静态审计**的结论（代码路径分析），不是运行时测量。

#### 未实测（需要设备，本轮没做）

| 想要的数字 | 状态 | 怎么自己测出来 |
| --- | --- | --- |
| 滚动 + 简化效果关：帧率 / 卡顿帧比例 | **未测** | `adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset` → 滚动 10 秒 → `adb shell dumpsys gfxinfo com.lumobrowser.app.debug` 看 `Janky frames` |
| 滚动 + 简化效果开：同上 | **未测** | 同上，先在设置里打开「简化效果」 |
| 浏览器页有无动态背景的 GPU 占用 | **未测** | Perfetto 抓 `gfx` + `sched`，比对 RenderThread 的忙碌占比 |
| 待机耗电 | **未测** | `adb shell dumpsys batterystats` 对比 1 小时待机 |
| 收藏 200 条时的首屏滚动 | **未测** | 导入 200 条备份后重跑 gfxinfo |

**为什么没测**：这台机器上没有连接真机，也没有可用的模拟器。**我不会编一组帧率数字出来。** 上面左表里的每一项都能在交付物里复核；右表里的每一项都需要一台设备。

复现步骤（接上设备后，两条命令）：

```powershell
# 关掉简化效果
adb shell am start -n com.lumobrowser.app.debug/com.lumobrowser.app.MainActivity
adb shell dumpsys gfxinfo com.lumobrowser.app.debug reset
# ...在主屏上下滚动 10 秒...
adb shell dumpsys gfxinfo com.lumobrowser.app.debug > before.txt

# 打开简化效果（设置 → 性能 → 简化效果），重复上面三步
adb shell dumpsys gfxinfo com.lumobrowser.app.debug > after.txt
```

比对两个文件里的 `Janky frames: N (P%)`。**同一个包、同一台设备、同一段手势**，两次唯一的差别就是这个开关——这是唯一能把差异归因到它的做法。

---

## 六、VPN 与网络辅助说明

### 设计原则：本应用永远不建立隧道

**LumoBrowser 不实现 VPN，不含任何 VPN 协议代码，不创建任何隧道。** 它做的是三件事：

1. 读系统报告的网络状态（`ACCESS_NETWORK_STATE`）；
2. 在**怀疑**是网络受限时，**解释**页面为什么失败；
3. 在用户明确点击时，**跳转到 Android 自己的 VPN 设置**，让用户在那里连。

隧道归系统与用户自己的 VPN 应用管。越过这条线意味着本应用要去处理别人的密钥、别人的服务器、别人的隐私政策——不在一个收藏浏览器的职责范围内。

### 权限

**没有新增任何权限。** 1.02 的权限清单与 1.0 相同，只有三条：

```
INTERNET              —— 浏览器本体
ACCESS_NETWORK_STATE  —— 读网络类型与连通性，用于状态显示与错误分类
VIBRATE               —— 触觉反馈
```

**没有 `BIND_VPN_SERVICE`，没有 `QUERY_ALL_PACKAGES`，没有位置权限。** 用 `adb shell dumpsys package com.lumobrowser.app.debug | findstr requested` 可复核。

### 十项设置（设置页 →「网络与 VPN」）

| 设置项 | 默认 | 作用 |
| --- | --- | --- |
| VPN 辅助 | **关** | 总开关。关着时下面的判定一概不参与 |
| 网络辅助模式 | 自动 | 自动 / 手动 / 静默 |
| 受限时显示提示 | 开 | 是否在疑似受限时提示 |
| 允许由我发起连接 | 开 | 关掉后，应用连「去开 VPN」的按钮都不显示 |
| 打开前检查网络 | **关** | 打开收藏前先看网络，默认不打扰 |
| VPN 连上后重试 | 开 | 见下面的有界重试 |
| 记住每网址选择 | 开 | 每个收藏可以有自己的网络偏好 |
| 我已配置 VPN | 关 | 用户自己声明「我这台机器上有 VPN」，应用**不检测** |
| 管理与打开系统 VPN 设置 | — | 跳 `Settings.ACTION_VPN_SETTINGS` |
| 打开系统网络设置 | — | 跳 `Settings.ACTION_WIRELESS_SETTINGS` |

### 隐私

- **不上传任何东西**：没有遥测、没有崩溃上报、没有「网络质量」回传。收藏、浏览历史、设置、网络状态**全部留在设备上**。
- **不检测用户装了哪个 VPN 应用**：那需要包可见性，本应用不申请。「我已配置 VPN」是用户自己的声明。
- **不记录隧道内容**：因为不存在隧道。应用看到的就是 WebView 看到的。

### 有界重试（唯一允许的自动重载）

失败后重试**是有预算的**，超过就停：`AssistRetryState` 记录已用次数，`exhausted` 为真时按钮变为不可用并改成「已达重试上限」。理由：一个无限重试的按钮在一个确实打不开的站上就是**耗电与流量的循环**。

**唯一的例外**是「连接 VPN 后重试」：

- 它是**用户明确要求**的；
- 它等的条件（VPN 起来了）是**新信息**，不是重复同一次请求；
- 两种结果（`Connected` / `Failed`）都会清掉等待旗标，所以**不可能成环**。

### 错误分类只在失败当刻做一次

`classifyPageFailure(...)` 在 `LaunchedEffect(state.error)` 里调用，**不随实时网络状态重算**。原因：页面失败之后才发生的断网**不是那次失败的原因**；跟着实时网络走，等于用当前连接状态给一次过去的失败重贴标签——用户会看到一个说「你没有网络」的提示，而他此刻网络正常。

### 诚实边界

- 网络状态是**系统报告的状态**，不是本应用探测的结果。系统说有网、但这个网访问不了某个站，是完全可能的，本应用看到的就是前者。
- 在**受限网络**（校园网、公司网关、地区限制）下的实际表现**没有测过**——需要真实的受限网络环境与一台已配置的 VPN。
- VPN 连接成功与否由 `vpnState`（`Unknown` / `Disconnected` / `Connecting` / `Connected` / `Failed`）表达。`Unknown` 是**真实状态**，不是错误：在拿不到系统信息时它诚实地表示「不知道」，而不是猜一个。

---

## 七、数据库迁移说明

### 版本链路

| Room 版本 | 引入的列 | 迁移 | schema 文件 |
| ---: | --- | --- | --- |
| 1 | 9 个基础列（`id`/`title`/`url`/`description`/`category`/`isPinned`/`sortOrder`/`createdAt`/`updatedAt`） | — | `app/schemas/com.lumobrowser.app.data.LumoDatabase/1.json` |
| 2 | +8 个浏览列（`browserMode`、`userAgentOverride`、`javaScriptEnabled`、`domStorageEnabled`、`desktopSiteEnabled`、`useThirdPartyCookies`、`lastSuccessfulMode`、`lastModeChangeTime`） | `MIGRATION_1_2` | `.../2.json` |
| 3 | +`networkAssist` | `MIGRATION_2_3` | `.../3.json` |

### 关键实现

```kotlin
// app/src/main/java/com/lumobrowser/app/data/Database.kt:227-231
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN networkAssist TEXT NOT NULL DEFAULT 'global'")
    }
}
```

**两个迁移都注册在 builder 上**：

```kotlin
// app/src/main/java/com/lumobrowser/app/data/Database.kt:263-272
Room.databaseBuilder(context.applicationContext, LumoDatabase::class.java, NAME)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()
```

**为什么必须两个都留**：一个装了 1.00 的设备升到 1.02，磁盘上的库还是 version 1，Room 要走 **1 → 2 → 3** 两步。只留 `MIGRATION_2_3` 会让这类设备直接抛 `A migration from 1 to 3 was required but not found`——**不是升级失败，是启动就崩**。这正是「代码整理时顺手删掉旧迁移」会造成的故障，所以有专门的用例锁住它（`bothMigrations_areRegisteredSoADirectlyUpgradedInstallCannotThrow`、`migrationFrom1To3_walksBothStepsAndKeepsEveryRow`）。

### 三条硬约束

1. **没有 `fallbackToDestructiveMigration()`。** 那会在用户看不懂的时机**静默清空收藏**，与「不清空数据」直接冲突。宁可让迁移失败并留下可诊断的错误，也不静默丢数据。
2. **迁移只做加列**——不改名、不删除、不重建表。这是唯一能保证**每一行的 rowid 和每一个值都原样保留**的操作（`migration2To3_isAdditive_itNeverTouchesExistingColumns` 锁住这一点：复制-删除式的迁移会在这里表现为列顺序或列名变化）。
3. **默认值带引号**：`DEFAULT 'global'`，与 Room 写进 `CREATE TABLE` 的形式**逐字节一致**。写成不带引号的 `DEFAULT global` 会依赖 SQLite 的裸标识符回退，**过不了 Room 的 schema 校验**（`migratedColumn_isNotNullWithTheQuotedDefaultRoomValidates` 断言 `dflt_value` 恰好等于 `'global'`）。

### 索引（一个容易漏的地方）

三个索引从 schema 1 起就存在，**两个迁移都没有提到它们**：

```sql
CREATE INDEX IF NOT EXISTS `index_bookmarks_sortOrder` ON `bookmarks` (`sortOrder`)
CREATE INDEX IF NOT EXISTS `index_bookmarks_category`   ON `bookmarks` (`category`)
CREATE INDEX IF NOT EXISTS `index_bookmarks_isPinned`   ON `bookmarks` (`isPinned`)
```

这恰恰是要测的：一次**重建表**的迁移（SQLite 改列的常规做法）会把索引一起丢掉，而**应用不会报任何错**——查询结果仍然正确，只是变慢，慢在一个用户持续滚动的列表上。

`theIndicesSurviveMigration_andAreStillUsedForTheSortQuery` 在迁移后查 `sqlite_master` 确认三个索引仍在，并跑 `EXPLAIN QUERY PLAN SELECT * FROM bookmarks ORDER BY isPinned DESC, sortOrder ASC, id ASC`。

### 存储值不跟着 Kotlin 常量名走

`networkAssist` 列存的是 `storageName`（`"global"` / `"auto"` / `"manual"` / `"silent"`），不是 Kotlin 枚举常量名。这样**重命名一个常量不会改写用户已经选过的值**。代价是必须有测试保证每个值都能往返（`everyStoredNetworkAssist_survivesTheColumnRoundTrip`），否则改名之后旧行会读不出来。

### 未验证的部分

**「从 1.0.1 覆盖安装到 1.02，收藏还在」这个端到端流程没有跑过**——需要一台设备。迁移逻辑本身有 11 例真 SQLite 测试覆盖（含 1→3 跳版），但覆盖安装还牵涉 Android 的包管理与数据目录保留，那是单元测试碰不到的一层。

设备上的验证步骤：

```powershell
adb install -r dist\previous-1.0.1\LumoBrowser-debug.apk   # 先装 1.0.1
# 打开应用，加两条收藏
adb install -r dist\LumoBrowser-1.02-debug.apk             # 覆盖安装
# 打开应用，两条收藏应该都还在
adb shell "run-as com.lumobrowser.app.debug sqlite3 databases/lumobrowser.db 'PRAGMA user_version'"
# 期望输出 3
```

---

## 八、源码包冷构建自足性验证

**要证明的东西**：`dist/LumoBrowser-1.02-source.zip` 是**自足**的——拿到它的人不需要这台机器上的任何隐藏状态（构建缓存、`local.properties`、已下载的产物），就能从零建出一个可安装的 APK。

**做法**：解压到一个**此前不存在**的空目录，除了补一个 `local.properties`（写 SDK 路径，机器相关，故意不打包）之外**不加任何东西**，跑一条命令。

```powershell
Expand-Archive dist\LumoBrowser-1.02-source.zip -DestinationPath D:\LumoBrowser-102-coldbuild3
Set-Content D:\LumoBrowser-102-coldbuild3\LumoBrowser\local.properties "sdk.dir=D\:\\AndroidDev\\sdk"

Set-Location D:\LumoBrowser-102-coldbuild3\LumoBrowser
cmd /c "gradle clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon --console=plain --no-build-cache"
```

`--no-build-cache` 是关键的一条：开着构建缓存，这个验证就会退化成「证明缓存里有东西」。

### 实测结果

| 项 | 结果 |
| --- | --- |
| Gradle 退出码 | **0** |
| 结果行 | **BUILD SUCCESSFUL in 17m 4s**（墙钟 1,025.3 秒） |
| 编译诊断 | **零 `e:`、零 `FAILURE`** |
| 单元测试 | **18 个测试类 / 263 个用例 / 0 失败 / 0 跳过** |
| 与源码树内跑的结果对比 | 类数、用例数、失败数**完全一致** |
| 产出 APK | `app/build/outputs/apk/debug/app-debug.apk` = **18,377,227 B** |
| 产出 APK SHA-256 | `E365F2A2DB1115651CAFCB1D2DC7EBC917817F90B06DF356E3C6C90C3F4163EE` |
| 产出测试包 | `app-debug-androidTest.apk` = 1,027,292 B（SHA-256 `25227285A6F235E760571D3848AF2B2DA8A496C6307E5B72AE5B685B74C9DA15`） |

**交付的 `dist/LumoBrowser-1.02-debug.apk` 就是这个文件**（原样复制，字节数与哈希都没改）。所以关于交付物可以说的最强命题是：

> 交付目录里的 APK，**就是**交付的源码包用上面那一条命令建出来的那一个。

而不是「照着源码另外打了一个包」。`apksigner verify` 与 `aapt2 dump badging` 对该文件的实测输出见第一节。

### 十七分钟：这次构建先失败过一次，修的不是被测代码

冷构建在这台机器上跑过三轮，**第一轮就红了**（`GRADLE_EXIT=1`），失败的是这一条：

```
com.lumobrowser.app.LanguageSwitchLaunchTest > launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate FAILED
    java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49
263 tests completed, 1 failed
```

**挂掉的不是被测代码。** 单元测试跑在**独立于 Gradle daemon 的另一个 JVM** 里，`org.gradle.jvmargs` 管不到它，Gradle 给它的默认堆是 **512 MB**。`LanguageSwitchLaunchTest` 会在 Robolectric 里把 Activity 和整棵 Compose 树真的撑起来，单跑就要 136 秒——512 MB 够不够，取决于那一刻构建机还在并行做什么。源码树里跑同一批测试时它过了，冷构建时并行任务更多，它就死了。

修的是**资源不足的 worker，不是症状**：`app/build.gradle.kts` 的 `testOptions.unitTests.all { }` 里显式设了 `it.maxHeapSize = "2g"`。**没有削弱这个断言，也没有把它排除出测试集。**

这一条比修法本身重要：**一个会因为构建机负载而随机变红的测试集，它证明的东西比报告上写的少。** 报告写「263 例 0 失败」，但那份绿如果是「当时机器比较闲」的绿，它就没有看上去那么值钱。

### 关于那个 17 分钟

同一批源码、同一条命令，本工程在更早一轮（248 例、尚未给测试 JVM 设堆）跑完用了 **4m 6s**。差的不是工作量，是当时构建机还有多少空闲内存：`gradle.properties` 里 Gradle daemon 自己就要 `-Xmx2560m`，Kotlin daemon 再来一份，测试 worker 现在还要 2 GB——这台机器总共 15.8 GB。**这个数字是机器负载的函数，不是源码的属性**，换台空闲的机器会明显更快；反过来，把这里的 17m 4s 当成「这个工程要跑十七分钟」也是误读。

### 为什么它与源码树里那个 APK 不是同一个文件（差异追溯）

源码树里单独跑 `:app:assembleDebug` 产出的是 **18,457,992 B**，与首轮冷构建产物（18,376,887 B）差 **81,105 B**。这个差额值得追到底，因为它也可能意味着其中一个包是**过期**的。逐层查下来是这样：

| 检查 | 结果 |
| --- | --- |
| zip 条目数 | 两者**都是 491** |
| 未压缩长度不同的条目 | **只有 1 个**：`classes7.dex`（877,388 vs 876,960，差 428 B） |
| 全部条目压缩后总长 | 18,244,610 vs 18,244,512，**只差 98 B** |
| APK Signing Block 空隙 | 两者**都是 4,096 B** |
| 中央目录大小 / 尾端 | 两者**都是 46,873 B / 22 B** |
| DEX 标记探针 | 两者**都含** `lumoShadowElevation`（`classes4.dex`）、`simplifiedEffects`（`classes5.dex`、`classes8.dex`）、`browserImmersiveFullscreen`、`networkAssist` |

两个结论：

1. **源码树里那个包不是过期包。** 本轮新增的四个代码标记在**两个包里都在**。原本怀疑「`Surfaces.kt` 重构与构建并行、导致交付包落后于源码」，这个假设被证伪了——它本来就是错的。
2. **差额不在内容里，在打包开销里。** 结构完全同形（同为 491 条目、同样的签名块空隙、同样的中央目录与尾端），压缩后只差 98 B，剩下的 81,007 B 落在**逐条目的本地标头与对齐填充**上。这属于 zip 打包的可变部分，不是代码差异。

至于那 98 B / 428 B：Debug 包内嵌 `BuildConfig.BUILD_TIME`（编译时间戳，设置页「关于」面板会显示它）。两次构建时间戳不同 → DEX 字符串表位移 → `classes7.dex` 长了几百字节。**同一份源码的两次 Debug 构建本来就不会逐字节相同**，这也是 `README.md` 里提醒「别用哈希跨机器比对 Debug 包」的原因。

同一个效应也解释了本次交付的包与上一轮冷构建包的差别：**18,377,227 vs 18,376,887，差 340 B**——两次冷构建之间源码只多了编辑器里那一段网络辅助接线和 15 个测试用例，其余全部来自时间戳。

既然功能等价，交付选了**冷构建**那一份：它的来源是一条写在文档里、任何人都能重跑的命令，而源码树那一份依赖本机增量状态。

### 文档改动没有破坏这次验证

本节是在冷构建**之后**才写进文档的（写它时又顺手更新了第一、二、三、九节的数字），也就是说压缩包内容在验证之后又变过一次。这必须交代清楚，否则「APK 来自源码包」就成了一句随着文档漂移而失效的话。

所以做了一次**全树逐文件哈希比对**（`D:\deepseek\Website\LumoBrowser` 的当前工作树 vs `D:\LumoBrowser-102-coldbuild3\LumoBrowser` 的解压树，两边都跳过 `build/`、`.gradle/`、`.kotlin/`、`.idea/`、`dist/` 与 `local.properties`）：

| 比对结果 | 数量 |
| --- | ---: |
| 只在当前树里（全部是 `.txt` 构建日志，**打包时已排除**） | 45 |
| 只在解压树里 | **1** —— `cold3.txt`（这次冷构建自己的日志，只存在于解压目录） |
| 两边都有但**内容不同** | **5** —— `README.md`、`CHANGELOG.md`、`docs/BUILD.md`、`docs/RELEASE-1.02.md`、`docs/TESTING.md` |
| 两边都有但内容不同，**排除 `.md`** | **0** |

同样的比对也对**最终打包出来的 zip** 做过一遍（比较 zip 内条目与该解压树中的同名文件）：95 个条目中 **90 个逐字节相同**，0 个在解压树里缺失，**排除 `.md` 后差异为 0**。

**结论：冷构建之后，没有任何非文档文件被改动过。** 源码、资源、Gradle 脚本、Room schema、测试源集全部逐字节未变，改的只有 5 个 Markdown 文档。所以上面那些实测数字对最终交付的包依然成立。

### 这次验证没有覆盖什么

- **没有在设备上安装过**。本机 `adb devices` 为空（无真机、无模拟器、也无 AVD 与系统映像），所以「构建成功」与「装上去能打开」是两件事，这里只证明了前者。
- **没有验证 release 变体**。没有 keystore，本轮不产出正式签名包。
- **`app-debug-androidTest.apk` 只编译、未运行**（无设备）。

---

## 九、一句话总结

1.02 修掉了四个真机反馈的问题（按钮质感与背景效果是假开关、状态栏遮挡、滚动卡顿的持续成本、缺少浏览模式），补齐了 VPN 辅助的**解释层**（不含隧道），把数据库升到 v3 并保留了从 v1 起的完整迁移链路。

**已验证**：263 例单元测试全绿、真 SQLite 上的迁移、交付的源码包能从零冷构建出交付的那个 APK。
**未验证**：任何需要真机的东西——安装、帧率、受限网络下的 VPN 表现、覆盖安装保留收藏。第六节与第三节列出了完整清单。这些**没有测过**，不是「应该没问题」。

绕了一圈回到最初的反馈——「装到手机上打不开」。本机**没有任何设备**，所以我无法亲手复现它。能做的是把已知的「装得上但打不开」成因逐条排除：

| 成因 | 结论 | 证据位置 |
| --- | --- | --- |
| 启动即崩的 `LocalLumoExtra` | 第一轮已修；提供者包住整个 `MaterialTheme`，漏提供在结构上不可表达 | [`TESTING.md`](TESTING.md) §3.7 |
| 跳版数据库迁移缺失导致的启动崩溃 | `MIGRATION_1_2` 与 `MIGRATION_2_3` 都已注册，11 例真 SQLite 测试覆盖 | 第六节 |
| launcher Activity 未导出 | `MainActivity` 已 `exported="true"`，`launchable-activity` 存在 | 第一节 |
| 签名无效 | `apksigner verify` 退出码 0，v2 方案通过 | 第一节 |
| ABI 缺失 / 原生库未对齐 | 四种 ABI 全在，四个 `.so` 全部 4096 字节对齐 | 第一节「原生库」 |
| 16 KB 内存页设备加载不了旧对齐的 `.so` | 四个 `.so` 的 ELF `PT_LOAD` 段 `p_align` 全是 `16384` | 第一节「原生库」 |
| 系统 WebView 被禁用/缺失 | 启动路径完全不碰 WebView（唯一的 `WebView(...)` 构造在 `BrowserScreen.kt:419`） | 全库 grep |
| 旧版本残留数据与新 schema 冲突 | **未排除**——这是唯一需要用户在手机上动手的一条（先卸载再装） | [`README.md`](../README.md) §14.1 |

这些排除了**已知**的崩溃路径，不等于证明它一定能打开——那需要一台设备，而要在设备上定位，需要那份 `adb logcat -b crash`。取证步骤写在 [`README.md`](../README.md) §2.1 与 §14。
