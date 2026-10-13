# LumoBrowser 构建指南

本文档描述如何从源码构建 LumoBrowser。所有命令都在 **Windows + PowerShell** 下实测通过；Linux/macOS 只需把 `gradlew.bat` 换成 `./gradlew`、路径分隔符换成 `/`。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17**（实测 17.0.12 LTS） | AGP 8.4 要求 JDK 17；用 21 亦可但未实测 |
| Android SDK Platform | **android-34** | `compileSdk = 34` |
| Android SDK Build-Tools | **34.0.0** | |
| Android SDK Platform-Tools | 任意近期版本 | 仅安装 APK 时需要 |
| Gradle | **8.6**（随 wrapper 自动下载） | 无需自行安装。**离线环境下 wrapper 会在 `Install.forceFetch` 处失败**（`java.net.ConnectException: Connection refused`）——那时改用本机安装的 Gradle：`& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug --console=plain`（见 §7 排障表） |
| Android Studio | Hedgehog (2023.1.1) 或更新 | 用命令行构建则不需要 |

构建工具链版本（`gradle/libs.versions.toml` 与 `app/build.gradle.kts`）：

```
AGP 8.4.2 · Kotlin 1.9.24 · KSP 1.9.24-1.0.20 · Compose Compiler 1.5.14
Compose BOM 2024.05.00 · Room 2.6.1 · DataStore 1.1.1 · AndroidX WebKit 1.12.0
Navigation Compose 2.7.7 · Lifecycle 2.7.0 · Coil 2.6.0 · Coroutines 1.8.0
compileSdk 34 · targetSdk 34 · minSdk 26
```

---

## 2. 配置 SDK 路径

`local.properties`（**不要提交到版本库**，`.gitignore` 已忽略）：

```properties
sdk.dir=D\:\\AndroidDev\\sdk
```

把路径换成你自己的 SDK 位置。常见默认值：

- Windows：`C:\Users\<你>\AppData\Local\Android\Sdk`
- macOS：`/Users/<你>/Library/Android/sdk`
- Linux：`/home/<你>/Android/Sdk`

用 Android Studio 打开工程时它会自动生成这个文件，可以不手工创建。

> `local.properties` 的路径写法必须是 properties 转义格式：反斜杠要写成 `\\`，盘符后的冒号也要转义成 `\:`。写成 `sdk.dir=D:\AndroidDev\sdk` 也能识别，但更稳妥的是上面那种。

---

## 3. 命令行构建

### 3.1 Debug APK（推荐，也是本次交付的产物）

```powershell
$env:JAVA_HOME = "D:\Program Files\Java\jdk-17"     # 换成你的 JDK 17 路径
$env:ANDROID_HOME = "D:\AndroidDev\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME            # 两个都设，兼容不同工具

Set-Location D:\deepseek\Website\LumoBrowser
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

安装到已连接设备：

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

> Debug 构建带 `applicationIdSuffix = ".debug"`，实际包名是 `com.lumobrowser.app.debug`，版本名 `1.05-debug`（当前版本；`versionCode=7`）。因此它可以和正式版共存，卸载时也要认准这个包名（`adb uninstall com.lumobrowser.app.debug`）。
>
> **注意 `versionName` 与 `versionCode` 的对应关系变过一次**：`versionCode` 4 对应 `2.2`，5 对应 `1.03`，6 对应 `1.04`，**7 对应 `1.05`**。版本序列回到 1.x 是刻意的——1.03 是接在 1.02 之后的第三个交付版本，不是 v2.2 专项任务的收尾。看到 `2.2` 的包，那是上一版。

### 3.2 运行单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

测试报告：`app\build\reports\tests\testDebugUnitTest\index.html`
原始结果：`app\build\test-results\testDebugUnitTest\TEST-*.xml`

**首次运行前请先看第 4 节（离线测试配置）**，否则可能长时间卡住。

### 3.3 编译 UI 测试（不运行）

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
```

### 3.4 清理

```powershell
.\gradlew.bat clean
```

---

## 4. 离线测试配置（重要）

### 4.1 测试 JVM 的堆

单元测试跑在**独立于 Gradle daemon 的另一个 JVM** 里，`org.gradle.jvmargs` 管不到它。Gradle 对它的默认值是 **512 MB**——本工程的 `LanguageSwitchLaunchTest` 会在 Robolectric 里把 Activity 和整棵 Compose 树真的撑起来，单跑就要 136 秒，512 MB 是否够用取决于构建机当时还在并行做什么。

实测症状（首次对交付包做冷构建时出现，源码树里跑同一批测试却是绿的）：

```
com.lumobrowser.app.LanguageSwitchLaunchTest > launchWithAlreadyAppliedLanguage_staysInThatLocaleAndDoesNotRecreate FAILED
    java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49
263 tests completed, 1 failed
```

`app/build.gradle.kts` 因此显式设了 `it.maxHeapSize = "2g"`。这是一个**会因为构建机负载而随机变红的测试**，修的是资源不足的 worker，不是断言本身——**没有削弱它，也没有把它排除出测试集**。

### 4.2 Robolectric 运行时 jar

`:app:testDebugUnitTest` 里的 Robolectric 测试默认会在首次运行时**从 Maven Central 下载 `android-all-instrumented` 运行时 jar**（150 MB 左右）。

在**无法完成 TLS 证书吊销检查**的机器上（典型报错 `schannel: CRYPT_E_NO_REVOCATION_CHECK`，或 curl 下载卡死不动），这个下载会**永久挂起**，测试任务永远不会结束——`jstack` 能看到线程停在 `org.robolectric.internal.dependency.MavenArtifactFetcher.fetchArtifact`。

本工程因此**可以**改为从本地目录解析该 jar（见 `app/build.gradle.kts` 的 `testOptions.unitTests.all`）。

**这一步是可选的，新克隆的仓库不需要做。** 只有当你确实被上面那个下载问题挡住时，才去准备下面两个 jar 并放进某个目录；目录不存在时构建**不会**进入离线模式，而是走 Robolectric 的正常联网下载（这是刻意的：新机器上预设一个不存在的路径，会让所有测试以 `couldn't find an android-all jar` 失败，比联网下载更难排查）。

```kotlin
// 仅在下面这个目录真的存在时才会设置这两个属性
it.systemProperty("robolectric.offline", "true")
it.systemProperty("robolectric.dependency.dir", depsDir)
```

目录的解析顺序（`resolvedRobolectricDepsDir()`）：

1. Gradle 属性 `robolectric.deps.dir`
2. 环境变量 `ROBOLECTRIC_DEPS`
3. 默认值 `D:/robolectric-deps`

命中的目录**必须真实存在**，否则视为"没有离线目录"。

准备好那个目录时，需要手工放两个 jar：

```powershell
New-Item -ItemType Directory -Force D:\robolectric-deps | Out-Null
$base = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented"
# SDK 33（@Config(sdk = [33]) 的测试用它）
curl.exe -L --ssl-no-revoke --fail --retry 3 -o D:\robolectric-deps\android-all-instrumented-13-robolectric-9030017-i6.jar `
  "$base/13-robolectric-9030017-i6/android-all-instrumented-13-robolectric-9030017-i6.jar"
# SDK 34（targetSdk 回落到它时用）
curl.exe -L --ssl-no-revoke --fail --retry 3 -o D:\robolectric-deps\android-all-instrumented-14-robolectric-10818077-i6.jar `
  "$base/14-robolectric-10818077-i6/android-all-instrumented-14-robolectric-10818077-i6.jar"
```

想放到别处就设环境变量 `ROBOLECTRIC_DEPS=D:\your\dir`，或用
`.\gradlew.bat :app:testDebugUnitTest -Probolectric.deps.dir=D:/your/dir`。

**文件名不能改**：Robolectric 的 `LocalDependencyResolver` 按 `{artifactId}-{version}[-{classifier}].{type}` 拼路径，名字不对会直接抛
`IllegalArgumentException: Path is not a file: <path>`。

`robolectric.offline=true` 的作用是让"文件缺失"**立刻报错而不是静默联网挂死**——这正是排查这类问题最需要的失败模式。

> Android Studio 内置终端里跑测试同样受这个配置影响，无需额外设置。

### 4.1 还需要 Robolectric 的原生 SQLite 运行时

两个冒烟测试类（`StartupSmokeTest`、`AppFlowSmokeTest`）用了 `@SQLiteMode(SQLiteMode.Mode.NATIVE)`。它依赖 `org.robolectric:nativeruntime`（由 `robolectric` 传递引入，是**普通 Gradle 依赖，不需要手工放 jar**，也**不受第 4 节离线配置影响**——那套配置只管 `android-all-instrumented` 这类运行时 jar）。

为什么必须用它：Robolectric 默认的 legacy SQLite shadow 维护一张进程级的"连接指针注册表"，而 Room 的查询执行器是真实线程池、活得比单个测试沙箱久，于是上一个用例留下的连接指针到下一个用例就失效，报出与产品逻辑毫无关系的噪声：

```
java.lang.IllegalStateException: Illegal connection pointer 156.
    Current pointers for thread Thread[arch_disk_io_0 @coroutine#538,5,SDK 33] []
    at org.robolectric.shadows.ShadowLegacySQLiteConnection$Connections.getConnection
```

切到原生 SQLite 后这整类噪声消失，失败信息变成真实断言。**如果你的构建在依赖解析阶段就报找不到 `nativeruntime`，那是网络/缓存问题，不要靠去掉 `@SQLiteMode` 注解来绕过**——那只是把噪声请回来。

---

## 5. 用 Android Studio 构建

1. `File → Open`，选择 `LumoBrowser` 目录（含 `settings.gradle.kts` 的那一层）。
2. 等待 Gradle Sync；若提示 SDK 路径缺失，按第 2 节补 `local.properties`。
3. 选择 `app` 配置 → `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
4. 或直接 `Run`：需要真机（开启 USB 调试）或已创建的 AVD。

首次 Sync 会下载 AGP、Kotlin、Compose 等依赖（约数百 MB），需要能访问 `dl.google.com` 与 `repo1.maven.org`。

---

## 6. Release 签名

从 1.07 起本工程**自带一套可用的侧载签名**，因此交付物有两个 APK：Debug 包（供调试）与**已签名的 Release 包**。Release 包不是发布到应用商店的版本，它是"给这台手机装一个不是 debuggable 的应用"的版本——部分 OEM ROM 会拦截或杀掉 debuggable 的侧载应用，那种"闪一下就退回桌面"不是代码问题，只有非 debug 包能排除它。

### 6.1 工程里已经接好的部分

`app/build.gradle.kts` 会**有条件地**读取 `keystore/keystore.properties`：

```kotlin
import java.util.Properties   // 注意：必须在 `plugins { }` 之前 import

val releaseKeystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    signingConfigs {
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

要点：

- **没有这个属性文件时构建照常工作**，release 变体只是产出未签名包。签名从来不是构建的前提。
- **Gradle Kotlin 脚本里的 `java.util.*` 不能写成全限定名**：裸的 `java.util.Properties()` 会解析到 Gradle 的 `java` 项目扩展而不是 JDK 包，得到 `Unresolved reference: util`。必须像上面那样用文件头的 `import`。
- **R8 在这个变体里是关掉的**（`isMinifyEnabled = false`）。原因不是懒：混淆从未在真机上验证过，而 `addJavascriptInterface` 出来的桥方法是反射绑定的——一个 R8 引入的问题会和正在诊断的启动崩溃长得一模一样，无法区分。规则文件仍然传着，等真机验证过再打开。

### 6.2 随工程附带的密钥

| 项 | 值 |
| --- | --- |
| 密钥库 | `keystore/lumobrowser-release.jks`（PKCS12，2 770 字节） |
| 别名 | `lumobrowser` |
| 算法 | RSA 2048 / `SHA256withRSA` |
| 有效期 | 10 950 天（约 30 年） |
| 证书 SHA-256 | `4B:EB:51:63:CF:90:4E:66:3D:73:BF:E4:DB:F3:B7:5F:85:3A:48:47:99:CC:D2:47:42:6A:8E:20:BB:BB:75:A9` |
| 口令文件 | `keystore/keystore.properties`（`storePassword` / `keyPassword` 都是 `lumobrowser-release-2026`） |

**这是一把给个人侧载用的抛弃式密钥，不是应用商店密钥**：口令就写在旁边的文件里，因为"一个能被旁人打开的手机上的一个浏览器的签名密钥"不值得保护。要上架请换成自己的密钥（见下）。`.gitignore` 已忽略 `*.jks`、`*.keystore` 与 `/keystore/keystore.properties`。

密钥是这样生成的（需要重做时照抄）：

```powershell
New-Item -ItemType Directory -Force -Path keystore | Out-Null
& 'D:\Program Files\Java\jdk-17\bin\keytool.exe' -genkeypair -v `
  -keystore keystore\lumobrowser-release.jks -storetype PKCS12 `
  -alias lumobrowser -keyalg RSA -keysize 2048 -validity 10950 `
  -storepass 'lumobrowser-release-2026' `
  -dname 'CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN'
```

### 6.3 用你自己的密钥替换

1. 生成或复用你的密钥库；
2. 编辑 `keystore/keystore.properties`，把四个值指向它（`storeFile` 相对项目根目录，或用绝对路径）；
3. 重新构建。

```properties
storeFile=D\:\\keys\\my-upload-key.jks
storePassword=……
keyAlias=……
keyPassword=……
```

### 6.4 构建与验证

```powershell
.\gradlew.bat :app:assembleRelease

# 产物
# app\build\outputs\apk\release\app-release.apk

# 验证签名（build-tools 34.0.0）
& 'D:\AndroidDev\sdk\build-tools\34.0.0\apksigner.bat' verify --print-certs --verbose `
  app\build\outputs\apk\release\app-release.apk

# 确认它不是 debuggable，并看清版本号
& 'D:\AndroidDev\sdk\build-tools\34.0.0\aapt2.exe' dump badging `
  app\build\outputs\apk\release\app-release.apk
```

Debug 与 Release 的 applicationId 不同（`.debug` 后缀 vs 无后缀），所以**两个包可以同时装在一台手机上**，互不覆盖。安装 Release 包之前不需要先卸载 Debug 包。

---

## 7. 排障

| 现象 | 原因与处理 |
| --- | --- |
| `SDK location not found` | 缺 `local.properties` 或路径写错，见第 2 节 |
| `Unsupported class file major version` / `Android Gradle plugin requires Java 17` | `JAVA_HOME` 指向了非 17 的 JDK，显式设为 JDK 17 |
| 依赖下载失败 / `Could not resolve ...` | 需要访问 `dl.google.com`、`repo1.maven.org`；代理环境下在 `gradle.properties` 配 `systemProp.https.proxyHost` 等 |
| 测试任务长时间无输出、永不结束 | Robolectric 在联网拉运行时 jar 并卡在吊销检查上，见第 4 节 |
| `Path is not a file: ...android-all-instrumented...` | 本地 jar 缺失或文件名不对，见第 4 节 |
| `schannel: next InitializeSecurityContext failed: CRYPT_E_NO_REVOCATION_CHECK` | 本机无法做 TLS 吊销检查；给 `curl.exe` 加 `--ssl-no-revoke` 即可 |
| Gradle wrapper 下载失败 | 检查 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl`；工程里设了 `validateDistributionUrl=false`，离线时也可改用本地分发包地址 |
| `.\gradlew.bat` 报 `java.net.ConnectException: Connection refused: no further information`，堆栈里有 `org.gradle.wrapper.Install.forceFetch` | wrapper 在尝试**下载**发行版（`C:\Users\<你>\.gradle\wrapper\dists\gradle-8.6-bin\...` 里那个目录存在但没解压完）。这不是构建错误，是环境状态。离线时直接用本机安装的 Gradle：`& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug --console=plain` |
| 构建成功但安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 设备上已装同包名但签名不同的版本，先 `adb uninstall com.lumobrowser.app.debug` |
| 安装后点图标立刻闪退 | 参考 [`TESTING.md`](TESTING.md) 第 3.7 节：这类"启动即崩"多半是某个抛错式 `staticCompositionLocalOf` 没有提供者。跑 `StartupSmokeTest` 能立刻复现（它真的启动 `MainActivity`），比在真机上猜快得多 |
| 两个冒烟测试报 `Illegal connection pointer` / `connection pool has been closed` | 见 4.1 节：确认 `@SQLiteMode(SQLiteMode.Mode.NATIVE)` 还在；并且**不要在 `@After` 里 `database.close()`**，那会让下一个用例的 Activity 握着已关闭的连接池，表现为"应用默默丢失收藏" |

---

## 8. 已验证的构建结果
### 8.0 1.08（当前交付，versionCode 10）—— Debug 与 Release 同批构建 + 全量单测（**不是**冷构建）

```
cd D:\deepseek\Website\LumoBrowser
Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug :app:assembleRelease --console=plain
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --console=plain
```

```
:app:assembleDebug :app:assembleRelease   BUILD SUCCESSFUL in 1m 44s   0 个 e: / 0 个 w:
:app:testDebugUnitTest                    BUILD SUCCESSFUL in 38s      files=44 tests=718 failures=0 errors=0 skipped=0
```

可复制的日志原文在 [`docs/build-logs/`](build-logs/README.md)：`v108-assemble.txt`、`v108-tests.txt`、`v108-compile.txt`，另有两次**失败**的编译日志 `v108-compile-failed-safebrowsing.txt`（见下方第 3 点）——失败记录同样留档，因为「只留成功日志」等于把排障过程删掉。

**产物（两个）**：

```
--- app/build/outputs/apk/debug/app-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='10' versionName='1.08-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
19,535,475 字节
SHA-256 833FEE7EA51CD2507DEF2E09A8E0EEC9911AAC1C85665FBD2854B04C7C5C911E
apksigner verify --print-certs → v2 true · CN=Android Debug · 证书 SHA-256 ea40d5e9…361934（与 1.05/1.06/1.07 同一张调试证书）
aapt dump xmltree <apk> AndroidManifest.xml → 命中 android:debuggable(0x0101000f)=(type 0x12)0xffffffff

--- app/build/outputs/apk/release/app-release.apk ---
package: name='com.lumobrowser.app' versionCode='10' versionName='1.08'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'          ← 没有 application-debuggable（同一命令 0 命中）
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
13,182,384 字节
SHA-256 876BAFA2160AF51CB41ECC783972A3340D111C103EBF1AA97A272249835A6E42
apksigner verify --print-certs → v2 true（v1/v3/v3.1/v4 均 false，与 1.07 相同）· 1 个签名者
                                 CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN
                                 证书 SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9
                                 ← 与 1.07 Release 同一把密钥 ⇒ 可以直接覆盖手机上已装的 1.07
```

三个必须写清楚的边界：

1. **没有做 `--no-build-cache` 冷构建**（与 1.06、1.07 相同的边界）。因此「0 个 e: / 0 个 w:」的准确范围是**本轮改动的文件**，不是全树冷构建。本版也确实出过编译错误（下一条），错误被真实日志暴露、定位、修好后才有上面这条 `BUILD SUCCESSFUL`，**没有靠改版本号或忽略错误绕过**。
2. **`isMinifyEnabled = false` 仍然是刻意的**（理由见 §8.0a 与 §6.1）：Release 变体存在的意义是「排除 ROM 拦截 debuggable 侧载应用」，不是「更小的包」。所以 **Release 比 Debug 小 6,353,091 字节**（19,535,475 − 13,182,384）来自 Debug 变体额外打包的 `ui-tooling` 与 `ui-test-manifest`，不是混淆。
3. **本版唯一一次编译失败值得留档**：把 `WebViewClient.onSafeBrowsingHit` 误写进 `LumoChromeClient` 后，Gradle 报 `e: LumoWebView.kt:1201:9 'onSafeBrowsingHit' overrides nothing` 与 `e: LumoWebView.kt:1208:9 Unresolved reference`。原因是 `LumoWebView.kt` 内有两个 client 类，`onSafeBrowsingHit` 属于 `LumoClient`（:771 起）而非 `LumoChromeClient`（:1112 起）。搬到正确类后一次通过。**教训：同一个文件里有多个覆写类时，先确认覆写方法属于哪个类，再动手写。**

**发布前的实测校验**（命令与完整输出见 [`TESTING.md`](TESTING.md) §7.0）：

- `zipalign -c -p -v 4` 对两个包都返回 `Verification succesful`；build-tools 34.0.0 的 `zipalign` 仍然**没有 `-P` 选项**，16 KB 只能靠自解析 ELF：从 Release 包取 `libdatastore_shared_counter.so`，`arm64-v8a` 三个 `PT_LOAD` 与 `x86_64` 两个 `PT_LOAD` 的 `p_align` 均为 `0x4000`。
- **出货运载物抽查**（不信「源码里有 = APK 里有」）：`aapt dump strings` 在 Release 包的字符串池（共 2120 条）里读到本版新增的「安全浏览已开启：由系统按自己的清单检查每个地址，本浏览器不会覆盖它给出的警告」与对应英文串；`classes2.dex` 中 `NavigationSecurityManager`、`pausedEntriesFor` 命中 14 处。
- 测试数字与逐类明细见 [`TESTING.md`](TESTING.md) §0.0、§0G、§2 与 §7.0（**718 例 / 44 类 / 0 失败 / 0 错误 / 0 跳过**）。

### 8.0a 1.07（versionCode 9，保留原文）—— Debug 与 Release 同批构建（**不是**冷构建）

```
cd D:\deepseek\Website\LumoBrowser
Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug :app:assembleRelease --console=plain
```

```
BUILD SUCCESSFUL in 2m 26s
APP_EXIT=0
```

**这是本工程第一次同时产出 Debug 与 Release 两个包。** 两点必须先说清楚：

- **Release 包用的是随工程附带的密钥**（`keystore/keystore.properties` → `keystore/lumobrowser-release.jks`），它是**个人侧载用的一次性密钥**，口令就写在 [`BUILD.md`](BUILD.md) §6.2 里。**要上架请换成自己的密钥**（§6.3 有步骤）。
- **`isMinifyEnabled = false` 是刻意的。** Release 变体存在的理由是「排除 ROM 拦截 debuggable 侧载应用」这一条，不是「更小的包」。R8 从未在真机上验证过，而 `addJavascriptInterface` 出来的桥方法是**反射绑定**的——一个 R8 引入的问题会和正在诊断的启动崩溃长得一模一样。**能读的堆栈比省下的 6 MB 值钱。**

**这一轮同样没有做 `--no-build-cache` 冷构建**（与 1.06 相同的边界）：`0 个 e: / 0 个 w:` 的范围是**本轮改动的代码**。唯一的加强项是：本版改动了构建脚本本身（新增签名配置与 `import java.util.Properties`），**Gradle 配置阶段确实被重新执行过**——事实上我在这一轮真的踩到了一次配置阶段失败（`Unresolved reference: util`，原因是 Gradle Kotlin 脚本里不能写 `java.util.Properties` 这种全限定名），修好后才有了上面这条 `BUILD SUCCESSFUL`。

**产物（两个）**：

```
--- app/build/outputs/apk/debug/app-debug.apk ---
package: name='com.lumobrowser.app.debug' versionCode='9' versionName='1.07-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'  application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
19,416,196 字节
SHA-256 39B472D87C0832652C9246CED158D27D594F43568619BF22ED4B9FB9A7B276C2
apksigner verify --print-certs → v2 true · CN=Android Debug · 证书 SHA-256 ea40d5e9…361934（与 1.05/1.06 同一张调试证书）

--- app/build/outputs/apk/release/app-release.apk ---
package: name='com.lumobrowser.app' versionCode='9' versionName='1.07'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'          ← 没有 application-debuggable
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
uses-permission: INTERNET / ACCESS_NETWORK_STATE / VIBRATE / FOREGROUND_SERVICE
               / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS
               / com.lumobrowser.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
13,099,148 字节
SHA-256 4AD270C838863BAA2EE72C00073D7A5E230ADBAAF3E53355D1079FD49057424E
apksigner verify --print-certs → v2 true · CN=LumoBrowser, OU=Personal, O=LumoBrowser, L=Unknown, ST=Unknown, C=CN
                                证书 SHA-256 4beb5163cf904e663d73bfe4dbf3b75f853a484799ccd247426a8e20bbbb75a9
```

**Release 比 Debug 小 6,317,048 字节，不是混淆的功劳**（两个变体都没开 R8）：Debug 变体额外打包了 `androidx.compose.ui:ui-tooling` 与 `ui-test-manifest` 两个调试期依赖。

两个包都复制进 `dist/`：`dist/LumoBrowser-v1.07-debug.apk`（19,416,196 B）与 `dist/LumoBrowser-v1.07-release.apk`（13,099,148 B）。**两个包包名不同**（`.debug` 后缀），可以同时装在一台手机上；也正因如此，**从 Debug 换到 Release 不继承数据**。

`zipalign -c -p -v 4` 对两个包都返回 `Verification succesful`。**16 KB zip 对齐无法用本机 build-tools 校验**：`zipalign.exe`（34.0.0）没有 `-P` 选项（实测 `unknown option -- P`），所以改用**自解析 ELF program header** 的替代证据——APK 内四个 ABI 的原生库，其 `PT_LOAD` 的 `p_align` 全部是 `0x4000`（16 KB），已满足 Android 15 的要求。

测试侧的数字（628 例 / 42 类）与逐类明细见 [`TESTING.md`](TESTING.md) §0.0、§2 与 §7.0。

### 8.0b 1.06（versionCode 8，保留原文）—— 增量构建（**不是**冷构建）

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug --console=plain
```

```
BUILD SUCCESSFUL in 17s
40 actionable tasks: 9 executed, 31 up-to-date
ASSEMBLE_EXIT=0
```

完整原始日志：[`docs/build-logs/v106-assemble.txt`](build-logs/v106-assemble.txt)。

**这一轮的构建证据比 1.05 弱一档，因为它没有做冷构建，这件事写在标题里而不是脚注里。** 1.05 用 `clean --no-build-cache` 让 41 个任务里的 40 个真的执行，于是「零警告」不需要任何限定语。1.06 这一轮是增量构建（**9 executed / 31 up-to-date**），那句话就必须加上限定：

- **Kotlin 警告 `w:` = 0 条，错误 `e:` = 0 条**，限定范围是**本轮改动的代码**。依据是执行过的任务里有 `> Task :app:compileDebugKotlin`（不是 `UP-TO-DATE`）与 `:app:compileDebugUnitTestKotlin`，而 1.06 的全部 25 个改动文件都落在这两个 Kotlin 源集内——编译器没有跳过任何一个改动文件。但增量编译只重编改动的文件及其依赖，**一个从未被本轮碰过的文件里如果本来就有一条警告，这轮不会重新打印它**。1.05 的结论是无条件的，这一条不是。
- `classes.dex` 合计 **60,733,592 字节**（1.05 是 60,730,168），分布在 12 个 dex 文件里，`classes.dex` 一个占 43,380,024 字节。
- 本轮真正跑过的 `:app` 任务（日志里**不带** `UP-TO-DATE` / `NO-SOURCE` 后缀的那些行）：`checkKotlinGradlePluginConfigurationErrors`、`generateDebugBuildConfig`、`kspDebugKotlin`、`compileDebugKotlin`、`compileDebugJavaWithJavac`、`dexBuilderDebug`、`mergeProjectDexDebug`、`packageDebug`、`createDebugApkListingFileRedirect`、`assembleDebug`。Gradle 的汇总行报的是 `9 executed, 31 up-to-date`（它数的是 actionable 任务，与日志行数差一个生命周期任务）；无论按哪个口径，**`:app:compileDebugKotlin` 都不在 `UP-TO-DATE` 之列**，而这是上面那条限定的唯一依据。

构建过程中仍有一条**项目配置警告**，与 Kotlin 无关，1.04 起就存在，本轮如实记录但未处理：

```
WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.
The current default is 'false'. It will be removed in version 9.0 of the Android Gradle plugin.
```

它来自 `app/build.gradle.kts` 的 `buildFeatures { buildConfig = true }`，是 AGP 的迁移提示而非本项目的错误；删掉这行会让 `BuildConfig.VERSION_NAME` 消失（`SettingsScreen.kt` 在读它），因此保留。

**产物**：`app/build/outputs/apk/debug/app-debug.apk`，**19,356,507 字节**（1.05 是 19,340,939），写出于 `2026-10-11 02:41:02`，复制为 `dist/LumoBrowser-v1.06-debug.apk`。`aapt2 dump badging` 实测：

```
package: name='com.lumobrowser.app.debug' versionCode='8' versionName='1.06-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
launchable-activity: name='com.lumobrowser.app.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

SHA-256：`12C0E108715182AD491BFABD4195E283848B3768CA3D2F67ED94802524BD63F2`。

`apksigner verify --print-certs --verbose`：**v2 签名方案 true，v1 / v3 / v3.1 / v4 均 false，1 个签名者，`CN=Android Debug`**，证书 SHA-256 `ea40d5e92f338b422643a35a88a90978feda3fc7e1851c98db1f25a85a361934`（与 1.05 同一张调试证书）。**这是 Debug 签名的测试包，不是正式发布签名。** 签名配置见本文 §6。

单元测试用同一份源码在同一轮里跑：完整日志见 [`docs/build-logs/v106-tests.txt`](build-logs/v106-tests.txt)，结果见 [`TESTING.md`](TESTING.md) §7.0。

**想把这档证据补回来**，就是 1.05 那条命令，本机约 40 秒：

```
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' clean :app:assembleDebug --no-build-cache --console=plain
```

### 8.1 1.05（上一版，versionCode 7）—— 冷构建（`--no-build-cache`）

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' clean :app:assembleDebug --no-build-cache --console=plain
```

```
BUILD SUCCESSFUL in 36s
41 actionable tasks: 40 executed, 1 up-to-date
ASSEMBLE_EXIT=0
```

完整原始日志：[`docs/build-logs/v105-assemble.txt`](build-logs/v105-assemble.txt)。

**1.05 的构建证据比 1.04 强一档，原因值得说明。** 1.04 的 `clean` 构建里仍有一批任务命中 Gradle 构建缓存（`clean` 删掉 `app/build`，但缓存是按输入哈希索引的，输入没变就复用输出），所以那一轮必须额外论证「Kotlin 编译确实执行过」。1.05 这一轮加上了 `--no-build-cache`：`41 actionable tasks` 里 **40 个真的执行**，没有任何一个任务的输出是从缓存搬来的。于是「零警告」不再需要任何限定语。

- **Kotlin 警告 `w:` = 0 条，错误 `e:` = 0 条**，且没有任何任务来自缓存。
- `classes.dex` 合计 60,730,168 字节（1.04 是 60,662,048），分布在 12 个 dex 文件里，`classes.dex` 一个占 43,380,024 字节。

构建过程中仍有一条**项目配置警告**，与 Kotlin 无关，1.04 起就存在，本轮如实记录但未处理：

```
WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.
The current default is 'false'. It will be removed in version 9.0 of the Android Gradle plugin.
```

它来自 `app/build.gradle.kts` 的 `buildFeatures { buildConfig = true }`，是 AGP 的迁移提示而非本项目的错误；删掉这行会让 `BuildConfig.VERSION_NAME` 消失（`SettingsScreen.kt` 在读它），因此保留。

**产物**：`app/build/outputs/apk/debug/app-debug.apk`，**19,340,939 字节**，写出于 `2026-10-11 01:24:18`，复制为 `dist/LumoBrowser-v1.05-debug.apk`。`aapt2 dump badging` 实测：

```
package: name='com.lumobrowser.app.debug' versionCode='7' versionName='1.05-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

SHA-256：`D7894D077D3AF692989A131EBCD99428EFE8313E4F473F42D9AD5BA74F009BCB`。

**这是 Debug 签名的测试包，不是正式发布签名。** 签名配置见本文 §6。

单元测试用同一份源码在同一轮里跑：完整日志见 [`docs/build-logs/v105-tests.txt`](build-logs/v105-tests.txt)，结果见 [`TESTING.md`](TESTING.md) §7.1。

### 8.2 1.04（versionCode 6）—— clean 装配

```
cd D:\deepseek\Website\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' clean :app:assembleDebug --console=plain
```

```
BUILD SUCCESSFUL in 1m 8s
41 actionable tasks: 24 executed, 16 from cache, 1 up-to-date
EXIT=0
```

完整原始日志：[`docs/build-logs/v104-assemble.txt`](build-logs/v104-assemble.txt)。

**这次构建里最值得看的两行：**

```
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
```

两行都**没有** `FROM-CACHE`，也**没有** `UP-TO-DATE`——它们真的执行了。这一点必须讲清楚，因为 `41 actionable tasks` 里有 `16 from cache`：`:app:clean` 删掉了 `app/build`，但 **Gradle 的构建缓存仍然生效**，命中的全是资源处理与 dex 合并类任务（`mergeDebugResources`、`processDebugResources`、`mergeExtDexDebug` …）。构建缓存是按输入哈希命中的，输入没变就复用输出，这是正确的；而**源码与产物之间那条最关键的链——Kotlin 编译——是真正跑过的**。所以下面两个结论成立：

- **Kotlin 警告 `w:` = 0 条，错误 `e:` = 0 条**，且是在最终源码上**从头编译**得到的。这是 1.04 唯一的决定性零警告证明。测试那轮（`v104-tests.txt`）是增量构建，Kotlin 不会重新打印未改动文件的警告，因此**不能拿它当零警告的证据**。
- `classes.dex` 60,662,048 字节。

构建过程中另有一条**项目配置警告**，与 Kotlin 无关，本轮如实记录但未处理：

```
WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.
The current default is 'false'. It will be removed in version 9.0 of the Android Gradle plugin.
```

它来自 `app/build.gradle.kts` 的 `buildFeatures { buildConfig = true }`，是 AGP 的迁移提示而非本项目的错误；删掉这行会让 `BuildConfig.VERSION_NAME` 消失（`SettingsScreen.kt:926` 在读它），因此本轮保留。

**产物**：`app/build/outputs/apk/debug/app-debug.apk`，19,310,815 字节，由 `:app:packageDebug` 与 `:app:assembleDebug` 在同一分钟内写出（`00:26:33`），复制为 `dist/LumoBrowser-v1.04-debug.apk`。`aapt2 dump badging` 实测：

```
package: name='com.lumobrowser.app.debug' versionCode='6' versionName='1.04-debug'
sdkVersion:'26'  targetSdkVersion:'34'  compileSdkVersion='34'
application-label:'LumoBrowser'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

**这是 Debug 签名的测试包，不是正式发布签名。** 签名配置见本文 §6。



本文档中的命令均在撰写时真实执行过（Windows 11 / JDK 17.0.12 / AGP 8.4.2 / Gradle 8.6 / Android SDK 34）。

### 8.3 1.03 交付构建（在本机构建，干净重建）

```
gradle clean :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

```
BUILD SUCCESSFUL in 16m 37s
50 actionable tasks: 32 executed, 17 from cache, 1 up-to-date

app-debug.apk  19,218,591 B（18.33 MB）  versionCode=5 / versionName=1.03-debug
SHA-256  0B5A1FC4BAB84282A95144B8413DD04F7C35447626F61445170E9A3DB00B5C60
单元测试 372 tests completed, 0 failed, 0 errors, 0 skipped（25 个测试类）
Kotlin 警告 0 条
```

产物从 `app/build/outputs/apk/debug/` 原样复制为 `dist/LumoBrowser-1.03-debug.apk`，字节数与哈希均未改动。原始日志随交付发出：[`build-logs/c30.txt`](build-logs/c30.txt)。

> **这是本版与上一版最重要的差别：1.03 是一次真正的 `clean` 重建。** 2.2 那一版是增量构建（`25 up-to-date`），而增量构建下 Kotlin **不会重新打印未改动文件的警告**，`w:` 计数会看起来是 0——本工程曾经因此误以为"零警告"。1.03 的 `32 executed` 说明编译真的重跑了，**所以「Kotlin 警告 0 条」这个说法在这一版是站得住的**。
>
> 这个区别有实际后果：1.03 的两个真实缺陷（`notePressed(attempt)` 收下参数却从不读它、`AccessibilityTextField` 的 `onSubmit` 从未被调用）**正是靠 `clean` 重建里重新打印出来的两条 `Parameter 'x' is never used` 才被发现的**。在增量构建下它们不会出现在日志里。**一次"看起来一样成功"的构建，会安静地藏起它上次说过的所有话。**
>
> 同一批警告还留在 [`build-logs/c28.txt`](build-logs/c28.txt)（修复前的干净重建）里，与 `c30.txt` 对着看，可以确认它们不是自己消失的。

> ⚠️ **本版没有做「源码包冷构建」的二次验证。** 1.02 做过（见 §8.5），2.2 与 1.03 都没有。也就是说：**1.03 的 APK 与 1.03 的源码包之间，"这个包确实是从这个包里构建出来的"这一条只有同一棵工作树作为证据，没有独立复现。** 这一条明确记在 [`TESTING.md`](TESTING.md) 第 4 节与第 7.3 节，没有当成做过。想补做的话就是 §8.5 那条命令。

### 8.4 v2.2 交付构建（留档 —— 增量构建）

```
gradle :app:assembleDebug --no-daemon --console=plain
```

```
BUILD SUCCESSFUL in 41s
40 actionable tasks: 15 executed, 25 up-to-date

app-debug.apk  19,154,932 B（18.27 MB）  versionCode=4 / versionName=2.2-debug
SHA-256  1938A655B49023EA1D92BA1CA70AF8A06FE38A275B2286DF8C886617AB0DB5D8
```

> **这是一次增量构建**（`25 up-to-date`），不是 `clean` 冷构建——所以它上面的"零警告"不能作为证据，理由见 §8.1。

> **一次运行里只跑一个 `:app:` 任务。** 本轮有两次 `EXIT=1` 都不是代码问题，而是旧的背景构建还在跑、占着 `R.jar` / `classes.jar` 的文件锁（`java.nio.file.FileSystemException: ... 另一个程序正在使用此文件`）。启动新构建前先确认旧的已结束。

### 8.5 1.02 交付构建（留档 —— 源码包冷构建）

交付的 `dist/LumoBrowser-1.02-debug.apk` 是**对交付的源码包**做的冷构建产物：解压 `dist/LumoBrowser-1.02-source.zip` 到空目录，只补一个 `local.properties`，然后跑下面这条命令。跑完把这个 APK 原样复制进 `dist/`，字节数与哈希均未改动。

```
gradle clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest \
       --no-daemon --console=plain --no-build-cache
→  BUILD SUCCESSFUL in 17m 4s，exit=0（零 error、零 FAILURE）
   单元测试：263 tests completed, 0 failed, 0 errors, 0 skipped（18 个测试类）
   app-debug.apk  18,377,227 B（17.52 MB）  versionCode=3 / versionName=1.02-debug
   SHA-256 e365f2a2db1115651cafcb1d2dc7ebc917817f90b06df356e3c6c90c3f4163ee
   app-debug-androidTest.apk  1,027,292 B
```

> **那 17 分钟是这台机器的空闲内存，不是这个工程的工作量。** Gradle daemon（`-Xmx2560m`）+ Kotlin daemon + 测试 worker（2 GB）三者相加约 7 GB，而机器总共 15.8 GB；更早一轮同一条命令只用 **4m 6s**。换台空闲机器会明显更快。

> **这一轮冷构建第一次是红的**，失败的是 `LanguageSwitchLaunchTest` 的 `java.lang.OutOfMemoryError at OnPositionedDispatcher.kt:49`——不是被测代码，是 Gradle 给**测试 JVM** 的默认堆只有 512 MB（`org.gradle.jvmargs` 管不到这个进程，见 §4.1）。修法是在 `app/build.gradle.kts` 里显式设 `maxHeapSize = "2g"`，**没有削弱断言，也没有把它排除出测试集**。

> 在此之前，源码树里还单独跑过一轮 `:app:testDebugUnitTest`（BUILD SUCCESSFUL in 3m 3s，263 例）+ `:app:assembleDebug`（产出 18,457,992 B）。那个包与冷构建产物**功能等价但非逐字节相同**——DEX 里的 `BuildConfig.BUILD_TIME` 每次构建都不同，会让字符串表位移；逐条目比对与差异追溯见 [`RELEASE-1.02.md`](RELEASE-1.02.md) 第八节。

> **这是 Debug 签名包，不是正式发布签名版。** `aapt2 dump badging` 与 `apksigner verify --print-certs` 的实测输出见 [`RELEASE-1.02.md`](RELEASE-1.02.md) 第 1 节。

### 8.6 更早一轮（第四轮）的冷构建

清空 `build/` 与 `.gradle/`、禁用构建缓存——**只有这样才能看全编译警告**：

```
.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-build-cache

→  BUILD SUCCESSFUL in 4m 15s，gradle exit=0
   Kotlin 编译：0 error、0 warning
   单元测试：223 tests completed, 0 failed, 0 skipped
   app-debug.apk             18,298,055 B   (versionCode=3 / versionName=1.02-debug)
   app-debug-androidTest.apk 1,027,292 B（仅编译，未运行）
```

> 构建耗时逐次波动（3m 35s / 4m 6s / 4m 15s / … / **17m 4s**），差异来自增量的代码量、测试量，以及**构建机当时有多少空闲内存**。最后那个 17m 4s 与它前面的 4m 6s 跑的是同一批源码、同一条命令，区别只是测试 JVM 从 512 MB 提到了 2 GB（见 §4.1）而机器内存没变——**所以不要把它当性能指标，也不要把某一轮的耗时当成这个工程的属性。**

> 注意：增量构建（不清 `build/`、不禁用缓存）时 Kotlin 编译器**不会重新打印未改动文件的警告**，`w:` 计数会看起来是 0——这正是本工程曾经误以为"零警告"的原因。要看真实的警告数，必须跑冷构建命令。

> **交付的源码包是否自足，是单独实测过的**：把 `dist/LumoBrowser-1.02-source.zip` 解压到**空目录**、只补一个 `local.properties`，重跑上面那条冷构建命令。实测结果（命令、耗时、用例数、产出 APK 的字节数对比）记在 [`RELEASE-1.02.md`](RELEASE-1.02.md) 第八节。

## 9. 构建日志（随交付发出）

`docs/build-logs/` 下有 **21 份原始 Gradle 日志副本**与一份索引：`README.md`（索引）、**1.08 的六份**——`v108-assemble.txt`（`assembleDebug` + `assembleRelease`，1m 44s）、`v108-tests.txt`（全量单测，44 类 718 例）、`v108-compile.txt`（最终编译，0 `e:` / 0 `w:`）、`v108-compile-failed-safebrowsing.txt`（**失败**的那次编译，`onSafeBrowsingHit` 写错了类）、`v108-test-adblock.txt`（广告屏蔽子集 71 例）、`v108-test-config.txt`（`BrowserConfigManagerTest` 31 例）；**1.07 的两份**——`v107-assemble.txt`（Debug + Release 同批构建，2m 26s）与 `v107-tests.txt`（42 类 628 例）；`v106-assemble.txt` / `v106-tests.txt`（1.06）、`v105-assemble.txt`（**1.05 的交付装配**，`clean :app:assembleDebug --no-build-cache`，也就是那个 19,340,939 字节 APK 的来源）与 `v105-tests.txt`（**1.05 的全量单测**，32 类 505 例）、`v104-assemble.txt`（1.04 的交付装配，19,310,815 字节）与 `v104-tests.txt`（1.04 的全量单测，29 类 460 例）、`c30.txt` / `c29.txt` / `c28.txt`（1.03 的三次构建）、`t13.txt` / `t15.txt`（无障碍与启动时间轴的单测）、`t10.txt`（v2.2 全量单测）、`cold102.txt`（1.02 的源码包冷构建）。**失败的那次也收进来**是有意的：只留成功日志，等于把排障过程从交付物里删掉。

没有摘要、没有重排版，每一行都是 Gradle 自己写出来的。索引逐列给出「文件 / 时间 / 命令 / 结果 / 它证明什么」。

为什么单独放一个目录而不是散在根目录下：本仓库的打包脚本默认排除 `.txt`（那条规则是为 `local.properties` 之类的东西定的），所以在前几轮里**文档正文反复引用 `c30.txt`、`t13.txt` 这些名字，而这些文件从来没进过源码包**——引用一个收件人手上没有的文件，等于没有引用。现在打包脚本为 `docs/build-logs/` 开了一个**只对这个前缀生效**的白名单例外，发布校验脚本也会断言这些文件确实出现在源码包的条目列表里。

> **日志文件名不携带任何信息。** `c30` / `t13` 这类编号是构建时随手起的顺序，`c30.txt` 内部没有任何一个字说明它是 1.03 的构建——Gradle 不往日志里写版本号。**1.04 起文件名开始带版本前缀（`v104-...`），这是本版有意的改变**，因为上一轮已经出现过一次「拿 `_verify103.py` 去验 1.04 产物」的混淆风险；但前缀只是给人看的提示，真正能核对的是哈希。文件名与版本的对应关系来自索引表，而索引表是本次交付的一部分，不是日志自身的属性。**如果这个对应关系重要，可靠的核对方式是重新构建一次并对哈希，而不是相信这张表**（命令见 §3.1）。

```bash
cd LumoBrowser
gradle clean :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

---

详细测试记录见 [`TESTING.md`](TESTING.md)；**1.08**（当前交付）的构建与测试实测值见 [`TESTING.md`](TESTING.md) §7.0，逐项验收账见 §0G 与 §0.0；**1.07** 的值见 §7.0a 与 §0F，1.07 轮新增的三个生产代码缺陷与两个测试陷阱见 §3.11–§3.13；**1.06** 的值见 §7.0b 与 §0E；**1.05** 的构建与测试实测值见 §7.1，1.05 的逐项验收账见 §0D；1.04 的值见 §7.2。
