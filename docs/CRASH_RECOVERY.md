# 崩溃自述与安全模式（crash recovery）

> 适用版本：1.07（versionCode 9）及以后。
> 相关源码：`app/src/main/java/com/lumobrowser/app/crash/`（4 个文件）、`MainActivity.kt`、`LumoApplication.kt`。
> 相关测试：`app/src/test/java/com/lumobrowser/app/crash/`（4 个类）＋`CrashRoutingTest.kt`，共 38 例。

## 0. 为什么要做这件事

1.07 的开发起点是一条真实的用户反馈：APK **装得上**，点开**闪一下就退回桌面**，而报告问题的人**拿不到 logcat**（"不方便，先按你的判断修"）。设备是 Android 14/15。

于是有一个必须承认的处境：

- 崩溃发生在用户手机上，本地没有模拟器、没有 AVD、`adb devices` 为空，**无法复现**；
- 应用在启动路径上写得很保守（无 `!!`、无 `require`、无系统设置写入、无未捕获的枚举解析），Robolectric API 34 下启动冒烟测试 `StartupSmokeTest`（7 例，覆盖冷启动构图、空状态、进入编辑器、溢出菜单进设置、三语文案解析）全绿——**看不出冒烟枪**；
- 没有日志，就没有诊断。

结论不是"再猜一个原因"，而是把**"崩溃绝不静默"**做进产品：

1. 应用记录自己的死亡（报告落盘 + 标记下一次启动）；
2. 下一次启动**先说明发生了什么**，并把报告原样交给用户，可以复制、可以分享；
3. 提供一条**把环境动效关掉的进入方式**（安全模式），让"是环境动效害的"这一假设可以被用户亲手排除；
4. 另外交付一个**非 debug 的签名版本**，因为部分 OEM ROM 会拦截或杀掉 debuggable 的侧载应用——那也是一种"闪一下就退回桌面"，而且不是代码问题。

## 1. 四个新角色

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `crash/CrashGuard.kt` | 258 | 安装未捕获异常处理器、写报告、读写"待处理崩溃"与安全模式标记、拼设备摘要 |
| `crash/CrashRecord.kt` | 144 | 报告的格式（`toShareText`）、读回（`parse`）、堆栈渲染（`traceOf`） |
| `crash/CrashRecoveryActivity.kt` | 约 200 | 恢复页：**纯平台 View**，无 Compose、无 WebView、不读 Compose 主题 |
| `crash/SafeMode.kt` | 约 40 | 纯函数：安全模式把 `UserPreferences` 改成什么 |

三个刻意的设计选择（写在 `CrashGuard.kt:25-35` 的 KDoc 里）：

- **一定调用前一个处理器。** `CrashGuard.install` 把自己装在平台处理器**前面**，记录完再把异常交回去（`CrashGuard.kt:76 previous?.uncaughtException(thread, error)`），所以进程仍然按 Android 期望的方式死掉。吞掉异常的崩溃处理器会留下一个半死的进程，并且对用户说谎。
- **用 `SharedPreferences` 的 `commit()`，不用 DataStore。** 写入发生在一个**马上要被杀掉**的线程上：`commit()` 是同步的，返回时字节已在磁盘上；DataStore 是带写队列的协程 API，对一个没有时间的进程是错的形状。落盘报告同理用普通的 `File.writeText`（`CrashGuard.kt:97`）。
- **一切有界并包在 `runCatching` 里。** 会抛异常的崩溃处理器会把用户的 bug 报告换成更糟的一份，因此这里每个平台调用都过 `guarded { }`（`CrashGuard.kt:256`），读不到就写 `unavailable`——而 `unavailable` 本身就是证据。

## 2. 报告长什么样

报告**就是** `toShareText()` 产生的那段文字（`CrashRecord.kt:34-45`）：磁盘上的文件、屏幕上的文本、用户粘贴出去的内容是同一个东西，不是三份可能互相矛盾的东西。格式如下，逐字：

```
LumoBrowser crash report
occurredAt: 1735689600000 (2025-01-01 00:00:00)
exception: java.lang.IllegalStateException
message: the device said no
thread: main
---- device ----
app: LumoBrowser 1.07 (versionCode 9) built 2026-01-01 00:00
package: com.lumobrowser.app, debuggable true
android: 15 (API 35), build BP1A.250305.019
device: Google Pixel 8 (shiba)
abis: arm64-v8a/armeabi-v7a
page size: 4096 bytes
webview: com.google.android.webview 133.0.6943.49 (694304900)
locale: zh_CN
safe mode: false
---- stack trace ----
	at com.lumobrowser.app.MainActivity.onCreate(MainActivity.kt:120)
	...
```

边界（都在 `CrashRecord` 的 companion 里，可被测试直接引用）：

- `MAX_FRAMES_PER_CAUSE = 48`：每个 throwable 最多 48 帧，多余的写成 `... N more frames`（`CrashRecord.kt:87-90`）。
- `MAX_CAUSES = 6`：一条 cause 链最多走 6 层，再深写 `... cause chain truncated at 6 levels`（`:96`）。`cause === current` 视为环并终止（`:93`）——自指的 cause 必须不能把崩溃报告变成挂起。
- `MAX_TRACE_CHARS = 12_000`：堆栈正文硬上限，超出部分截断并附说明。
- **链头不重复**：第 0 层的类名与消息只出现在 `exception:` / `message:` 两行里，`stackTrace` 从它的帧（或 `(no frames: <类名>)`）开始。
- 消息里的换行会压成空格（`:38`），因为一行一个字段才是可读的报告。
- `parse` 遇到不认识的内容**返回 `null` 而不抛异常**（`:107-131`）：调用方是一个必须渲染出点东西的恢复页，以及一个测试套件；在解释异常的时候再抛一个异常，对谁都没好处。

## 3. 崩溃那一刻发生什么

`CrashGuard.install(this)` 是 `LumoApplication.onCreate` 里**紧跟 `super.onCreate()` 的下一行**（`LumoApplication.kt:101` → `:105`，`instance = this` 在 `:106`），因为在启动途中崩溃的，正是最值得记录的那些。

处理器被触发时，按顺序（`CrashGuard.kt:68-77`）：

1. `record(...)`——组记录 → `Log.e` → 写 `filesDir/crash/last-crash.txt` → `SharedPreferences` 里 `pending_crash = true` 且 `crash_count` 加一，全部同步 `commit()`；
2. `startRecovery(app)`——**best effort** 地 `startActivity(recoveryIntent)`；如果平台拒绝（最可能是后台启动 Activity 限制），不影响第 1 步；
3. `previous?.uncaughtException(thread, error)`——把进程交还给平台。

`install` 是幂等的，守门方式用**身份比较**而不是布尔标记（`CrashGuard.kt:63-66`）：如果期间有别人占了这个槽位，就串到那个处理器上，而不是干脆拒绝安装。

## 4. 下一次启动的路由

`MainActivity.onCreate` 在 `enableEdgeToEdge()` 之后、`sharedUrl = extractSharedUrl(intent)` 之前（`MainActivity.kt:108` → `:114-117`）：

```kotlin
if (CrashGuard.hasPendingCrash(this)) {
    startActivity(CrashGuard.recoveryIntent(this))
    finish()
    return
}
```

**这条路径上永远不会构建 Compose 树**——这正是重点：如果崩溃起因于 Compose 侧的某样东西，恢复页必须不碰它。恢复页只是普通 Activity + 普通 View。

`pending_crash` **不会**在路由时清掉：转送不等于用户已经知道发生了什么（`CrashRoutingTest` 专门断言这一点）。旗标只在用户在恢复页上选了"安全模式启动"或"正常启动"时才清（`CrashGuard.kt:121-123`）。

## 5. 安全模式到底关掉了什么

`SafeMode.apply(prefs)`（`SafeMode.kt`）只做五个改动，**其余偏好一字不动**：

| 字段 | 安全模式值 | 作用 |
| --- | --- | --- |
| `animationStrength` | `0f` | `MotionConfig.animationsEnabled = strength > 0.001f` 变成 false ⇒ 漂移极光**根本不会被组合**，启动覆盖层也回 `null` |
| `animationPreset` | `Minimal` | 与上面配合，即便强度又被调高也走最小档 |
| `backgroundGlowEnabled` | `false` | 关闭环境光斑 |
| `simplifiedEffects` | `true` | 关掉阴影一类装饰 |
| `adBlockEnabled` | `false` | 关掉 WebView 的请求拦截（阻断层也是启动路径上的一部分） |

三条边界：

- 安全模式是**运行期覆写，永不写回 DataStore**（`MainActivity.kt:191`）。用户关掉安全模式后，原来的设置原样回来。
- 每进程只判定一次：`val safeMode: Boolean by lazy { CrashGuard.consumeSafeMode(this) }`（`LumoApplication.kt:47`）。因为 `consumeSafeMode` 会清旗标，而改设置、换语言都会重建 Activity——"本进程处于安全模式"必须比 Activity 活得更久，否则被关掉的动效会在同一会话中途回来。广告屏蔽同样受它影响：`adBlocker.enabled = prefs.adBlockEnabled && !safeMode`（`LumoApplication.kt:123`）。
- 安全模式**不修改任何系统设置**，也不碰全局亮度。

## 6. 从报告里能看出什么

`deviceSummary`（`CrashGuard.kt:177-187`）刻意包含三样应用自己事先不知道、用户也打不出来的东西：

- **`page size:`**——Android 15 设备可以跑 16 KB 内存页，为 4 KB 编译的原生库在上面活不下来。报告里写的是 `Os.sysconf(OsConstants._SC_PAGESIZE)` 的**实际数字**（`CrashGuard.kt:207-214`），不是"是/否"，因为 4096 与 16384 之外还有别的取值，而且数字可以人工核对。≥16384 时额外标注 `(16 KB pages)`。
- **`webview:`**——`WebView.getCurrentWebViewPackage()`（`:216-223`）。一个没有可用 WebView 的浏览器有它自己的一种糟糕的一天；provider 为 null 时写 `unavailable (no WebView provider is installed)`。
- **`package: ..., debuggable true/false`**——用来区分"debug APK 被 ROM 拦了"和"代码真的崩了"。
- 另外还有 `app:`（版本、versionCode、构建时间）、`android:`（RELEASE + API + INCREMENTAL）、`device:`（厂商/型号/代号）、`abis:`、`locale:`、`safe mode:`。

报告里的 `safe mode:` 行是**只读**读取（`consumeSafeModeReadOnly`，`:253-254`）：`deviceSummary` 是在 `record` 内部跑的，而"报告"这个动作绝不能改变下一次启动的行为。

## 7. 恢复页上的按钮

`CrashRecoveryActivity` 渲染顺序：标题 → 副标题 → 崩溃时刻与累计次数（`crash_recovery_when`，三个格式参数）→ 报告全文（等宽、可选中）→ 安全模式说明 → 按钮。

| 按钮 | 做的事 |
| --- | --- |
| 安全模式启动 | `setSafeMode(true)` → `clearPendingCrash` → 启动 `MainActivity` → `finish()` |
| 正常启动 | `setSafeMode(false)` → `clearPendingCrash` → 启动 `MainActivity` → `finish()` |
| 复制报告 | 报告全文进剪贴板（失败时 Toast `crash_recovery_copy_failed`） |
| 分享报告 | `ACTION_SEND` + `createChooser`（失败时 Toast `crash_recovery_share_failed`） |
| 删除报告 | `clearCrash` → 画面回到空状态 → Toast；**同时清掉累计次数**，因为画面上写的是"本次安装累计记录 N 次崩溃"，删掉报告还留着旧次数等于在说谎（`CrashGuard.kt:125-138`） |

没有报告时（例如被系统杀死而没有异常、异常处理器也没能落盘）这一页仍然可用：显示 `crash_recovery_empty` 与"安全模式启动 / 正常启动"。（该路径会被 `CrashRecoveryActivityTest` 的 `no report` 用例覆盖。）

## 8. 测试

38 例，全部在 `:app:testDebugUnitTest` 下：

| 测试类 | 例数 | 钉住的东西 |
| --- | --- | --- |
| `crash/CrashRecordTest.kt` | 10 | 逐字段往返、五种畸形输入回 `null`、`(none)`→`null`、换行压平、帧上限 48、cause 链与 6 层上限、12_000 字元上限 |
| `crash/SafeModeTest.kt` | 5 | 五个开关、**不动用户的主题/背景/语言**、以 `MotionPresets.config(...).animationsEnabled == false` 与 `startupTimeline(motion) == null` 证明动效确实归零、幂等、`changes` |
| `crash/CrashGuardTest.kt` | 14 | 记录→读回→pending→计数、二次覆盖、畸形文件、**与前一个处理器串接且第二次 install 不重复串接**、`consumeSafeMode` 只生效一次、`record` 不消耗安全模式请求、`deviceSummary` 九个字段、`recoveryIntent` 的 flag |
| `crash/CrashRecoveryActivityTest.kt` | 8 | 报告渲染、累计次数逐字比对、空状态、两个启动按钮的副作用（含 `nextStartedActivity` 指向 `MainActivity`）、复制进剪贴板、分享丢出 `ACTION_CHOOSER`、删除后归零 |
| `CrashRoutingTest.kt` | 1 | 有待处理崩溃时 `MainActivity` 转往 `CrashRecoveryActivity` 并 `finish`，且 **pending 仍为真** |

两点测试设计上的注意事项：`CrashGuardTest` 的 `@Before`/`@After` 必须存回 `Thread.setDefaultUncaughtExceptionHandler`（同一个 JVM worker 共用处理器）；恢复页没有动画，所以 `ActivityScenario.launch` 在那里是安全的，而 `MainActivity` 的非崩溃路径会组合整个 App（含漂移背景），无法用裸 `buildActivity` 测试——那条路径由 `StartupSmokeTest` 覆盖。

## 9. 边界：这套机制抓不到什么

- **原生崩溃**（`SIGSEGV` 在 `libdatastore_shared_counter.so`、WebView 渲染进程里）没有 Java 异常，`Thread.setDefaultUncaughtExceptionHandler` 不会触发。这类崩溃只能靠报告里的 `page size:` / `webview:` 两行线索去查。
- **`Application.onCreate` 在 `CrashGuard.install` 之前崩溃**，或 JVM 起不来（dex 校验失败、ABI 不匹配）——那时报告机制还不存在。
- **被系统直接杀死**（低内存、后台限制、ROM 清理）不产生异常，没有报告；恢复页的空状态是为此准备的。
- **ANR** 不是未捕获异常，不在此列。
- **恢复页自身依赖 Compose 之外的东西**：它只用平台 View 与普通资源，但仍然是应用的一部分；如果崩溃起因于资源或主题解析，这一页也可能起不来。这是刻意的取舍——不用 Compose 是为了隔离最大的嫌疑区域，而不是让这一页变成不可破坏的。

## 10. 交付给用户的话

如果你装上 1.07 之后仍然一进就退：

1. **再点开一次**。第二次启动会先显示崩溃自述页，而不是直接退出。
2. 在那一页选 **安全模式启动**。它把动画强度、环境光斑、简化效果与广告屏蔽全部关掉后再进入应用。
3. 如果安全模式能进去，用 **分享报告** 把报告发出来——报告里的 `page size:`、`webview:`、`debuggable` 三行通常就能定位是哪一类问题。
4. 报告页上的 **删除报告** 会把报告和累计次数一起清掉。
