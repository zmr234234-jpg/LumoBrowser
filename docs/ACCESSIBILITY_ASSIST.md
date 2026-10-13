# 无障碍辅助服务说明（1.03 / versionCode 5）

本文对应 v2.2 规格任务里的第三项。它回答四个问题：这个服务**到底做什么**、**为什么不那样做**、**用户要经过哪几步**、以及**代码凭什么说自己是对的**。

> 本文是**新增文档**。v2.2 规格原文在 `docs/VPN_INTEGRATION.md` 第四节曾记「本版没有实现无障碍服务」——那句话在本版已经**不再成立**，该节已同步改写并指回本文。

---

## 1. 它做什么

一句话：**用户在 LUMO 里指定某个 App 里的某一个按钮，然后 LUMO 可以替他按一次。**

典型场景是 VPN 客户端：系统 VPN 授权对话框弹出时，用户已经同意过，但每次都要在另一个 App 里点一下「连接」。LUMO 的辅助服务让这一步可以由图一的那个「代为点击」动作完成，而不是让用户自己去找到那个 App。

**它不是**：不是自动化脚本引擎、不是按键精灵、不是「帮我在所有 App 里找按钮」。目标必须先由用户逐条声明，一次尝试只针对一个目标，按下一次就结束。

### 1.1 七个结果，每一个都对用户有话说

`AccessibilityAssist.kt` 里的 `AssistOutcome` 是全部可能的结果。刻意做成封闭枚举而不是布尔值，因为「没成功」有六种完全不同的原因，而它们的**处理方式各不相同**：

| 结果 | 含义 | 用户该做什么 |
| --- | --- | --- |
| `ServiceOff` | 服务没在 Android 设置里打开 | 去打开（面板里有直达按钮） |
| `NoTarget` | 眼前这个 App 没有声明过目标 | 先声明，或切到目标 App |
| `Watching` | 已武装，正在找 | 不用做，等一下 |
| `Pressed` | 按到了，`AssistAttempt.presses` 记次数 | 完成 |
| `NotFound` | 预算用完了目标控件也没出现 | 自己按，或检查声明的按钮文字 |
| `Forbidden` | 眼前这个 App 是 LUMO 永不介入的 | 没有补救，这是设计 |
| `Exhausted` | 尝试超时 | 自己按（界面一定会跟一句「请自行完成」） |

`Watching` 是**唯一非终态**的结果。其余六个都意味着这次尝试已经结束、不会再有任何动作发生——这一点由 `AccessibilityAssistRuntime` 的 `arm` / `notePressed` / `finish` / `disarm` 四个入口共同保证，并由测试逐条验证（见第 5 节）。

---

## 2. 安全边界：为什么它不会失控

辅助服务是 Android 上权限最大的东西之一。**能读屏幕上的一切、能点一切。** 一个「一直在看、随时准备点」的实现迟早会在错的 App 里点到对的文字，或者点第二次把用户刚做的事撤销掉。所以这里的每一条约束都是为了让**权限很大但行为很小**：

### 2.1 一次只有一个尝试，而且它会过期

`AssistAttempt.WINDOW_MS = 12_000L`、`MAX_PRESSES = 1`。

- 尝试由**一次明确的用户动作**武装（`arm`），不是由服务自己发起。
- 结束条件有五条：按了一次、超时、用户离开 App、服务被关掉、或调用方主动 `disarm()`。
- 超时检查在**包名比对之前**。这一条有测试专门钉住：同一个「错误的 App」输入，在未过期时返回 `NoTarget`，在已过期时返回 `Exhausted`——证明过期的尝试连「这个包是不是目标」都不会去问，更不会去读它的节点树。
- `notePressed(attempt)` **会核对传进来的 attempt 是不是当前武装的那一个**，对不上就丢弃。原来的版本收下参数却从不读它（编译器为此发了一条 `Parameter 'attempt' is never used`），于是「一次回调里不可能换掉 attempt」这个假设从未被检查过。真出问题的话，失败形态是**静默且张冠李戴**的：一个属于已被作废的尝试的按下，会被报告成**当前**这次尝试成功了。核对之后，对不上的按下既不改结果，也不会顺手把用户正在看的那次尝试解除掉。

### 2.2 有一批 App 永远不碰

`isForbiddenAssistPackage` 覆盖六个平台套件（系统设置、权限控制器、安装器、SystemUI 等）以及 LUMO 自己。理由很直接：**用户最可能顺手声明成目标的，恰恰是同意对话框上的那个「确定」。** 让辅助服务去点同意对话框，等于把「用户同意」变成一句空话。

这里有一个真实缺陷，是测试抓出来的（`AccessibilityAssistTest` 的 forbidden 一组）：

```kotlin
// 修之前
packageName in FORBIDDEN_ASSIST_PACKAGES || packageName == LUMO_PACKAGE
```

debug 变体的 applicationId 是 `com.lumobrowser.app.debug`。比对一个裸常量，意味着**「开发者手机上实际安装的那个变体」是唯一漏网的包**——而失败是静默的，那个本该抓住它的检查本身才是错的。同文件里 `isLumoPackage` 早就同时涵盖两种拼法，只是没被用上。现在这一行是 `|| isLumoPackage(packageName)`。

### 2.3 服务配置里每一项都取了最窄的值

`app/src/main/res/xml/lumo_accessibility_service.xml` 只声明：

```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
android:accessibilityFeedbackType="feedbackGeneric"
android:accessibilityFlags="flagReportViewIds"
android:canRetrieveWindowContent="true"
android:notificationTimeout="200"
```

刻意**没有**声明的东西，每一条都是 LUMO 用不上的能力：

- `flagRetrieveInteractiveWindows` —— 会暴露系统持有的**所有**窗口。服务只读 `rootInActiveWindow`，那正好是屏幕上那一个窗口，不多不少。
- `flagRequestFilterKeyEvents` —— 会投递每个 App 里的每一次按键。这个功能按的是一个具名按钮，从来不需要知道任何人打了什么字。
- `flagRequestTouchExplorationMode` —— 改变整台设备的触摸处理方式，而这个服务的全部工作只是合成一次点击。
- `flagRequestAccessibilityButton` —— 会在导航栏加一个 LUMO 磁贴，对一个人默认关闭、偶尔使用的功能来说是又一个常驻surface。
- `flagIncludeNotImportantViews` —— 每个事件要走更多节点，换来的候选是**无障碍自己已经判定不值得播报**的那些。

`flagReportViewIds` 是唯一保留的，它只给**本来就在读的**节点附上 view id，不开启任何新能力。

### 2.4 服务不是独立进程

`AndroidManifest.xml` 里没有 `android:process`，理由和 `LumoVpnService` 一样：`AccessibilityAssistRuntime` 是一个进程级的 `StateFlow`，和界面侧武装尝试的调用共享。放到独立进程里，界面武装的尝试服务根本看不见。

`android:exported="true"` 是必需的（系统要从自己的 UID 绑定这个组件来构建设置列表项），安全性由 `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` 保证——**真正的闸门在系统手里**：服务只有在用户于 Android 设置里勾选 LUMO 之后才会运行，取消勾选则一切停止，与 LUMO 自己的开关无关。

### 2.5 默认是关的，而且是三态

`AssistSettings` 的开关状态是 `Boolean?`——默认 `null`，不是 `false`。区别很重要：`false` 表示「用户看过并且关掉了」，`null` 表示「从未表态」。面板据此决定是显示介绍还是显示当前配置，而不是把「没问过」渲染成「已拒绝」。

---

## 3. 用户要经过哪几步

1. **在 LUMO 设置里打开「辅助服务」总开关。** 此时还没发生任何事。
2. **点面板里的「打开系统设置」**（`AccessibilityStatus.settingsIntent()` → `Settings.ACTION_ACCESSIBILITY_SETTINGS`），在 Android 的无障碍列表里勾选 LUMO。系统会展示 `accessibility_assist_service_label` / `_summary` / `_description` 三个字符串，即 XML 里 `android:label` / `android:summary` / `android:description` 指向的那三条。
3. **回到 LUMO，声明目标。** 每个目标是三元组 `AssistTarget(packageName, label, buttonText, isUsable)`：
   - `packageName` —— 哪个 App；
   - `label` —— 界面上显示给用户看的名字（可空，会被 `matchesAssistLabel` 同时匹配 text 与 contentDescription）；
   - `buttonText` —— 要按的那个控件的文字。**这是唯一的匹配依据，不能空。**
4. **在目标 App 里触发尝试。** 服务收到 `typeWindowStateChanged` / `typeWindowContentChanged` 事件后，若当前包名与已武装的尝试相符，才去读窗口内容。

`AssistTarget.isUsable` 的判定是「`packageName` 与 `buttonText` 都非空」，`label` 允许为空——目标名只是给人看的，不影响能否按下。

### 3.1 声明是怎么存下来的

`AssistTargetCodec` 用一个**人可读**的行格式，每行一个目标：

```
com.example.vpn|Example VPN|Connect
```

三个字段用 `|` 分隔。之所以不用 JSON：这份配置的值全部来自用户界面上的文字，出问题时用户（或我们自己）需要能直接看懂它、手改它，而不必先解码一层转义。

转义规则只在必要时介入：字段里出现 `|`、`\`、换行时前置反斜杠。**未知转义序列原样保留两个字符**——这一点也是测试抓出来的缺陷：

```kotlin
// 修之前：只吃掉反斜杠后的字符
else -> append(escaped)
// 把存下来的 C:\vpn 变成 C:vpn，而同一段的注释却声称「原样保留」
// 修之后：
else -> append('\\').append(escaped)
```

一行坏掉不会影响其他行：`decode` 跳过缺 `buttonText` 或 `packageName` 的行，保留其余。`upsert` 保证每个 package 恰好一个目标。

---

## 4. 代码在哪

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `app/src/main/java/com/lumobrowser/app/accessibility/AccessibilityAssist.kt` | 253 | 纯逻辑：`AssistTarget` / `AssistOutcome` / `AssistAttempt` / `AssistDecision` / `decideAssistAction` / `isForbiddenAssistPackage` / `matchesAssistLabel`。无 Android 依赖，可在 JVM 上直接测。 |
| `app/src/main/java/com/lumobrowser/app/accessibility/AccessibilityStatus.kt` | 124 | 服务到底开没开。两条路径：`AccessibilityManager` 的已启用列表，以及 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的原始字符串。 |
| `app/src/main/java/com/lumobrowser/app/accessibility/AccessibilityAssistRuntime.kt` | 136 | 进程级状态机：`attempt` / `outcome` / `serviceEnabled` 三个 `StateFlow`，以及 `arm` / `notePressed` / `finish` / `disarm` / `clearOutcome`。 |
| `app/src/main/java/com/lumobrowser/app/accessibility/AssistSettings.kt` | 74 | 把 `UserPreferences` 里的配置发布成运行时快照；`targetFor(packageName)` 只交出可用目标。 |
| `app/src/main/java/com/lumobrowser/app/accessibility/AssistTargetCodec.kt` | 105 | 第 3.1 节的行格式编解码。 |
| `app/src/main/java/com/lumobrowser/app/accessibility/LumoAccessibilityService.kt` | 230 | 真正的 `AccessibilityService`。走节点树找匹配控件，向上最多 `MAX_ANCESTOR_WALK` 层找可点击祖先，然后 `performAction(ACTION_CLICK)`。 |

### 4.1 `isEnabledViaSecureSetting` 为什么不容易写对

这是本版第三个由测试抓出的真实缺陷，值得单独记，因为它是一类错误的标本。

`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里存的是形如 `pkg/cls` 的条目，而 `cls` **可能写成相对拼法**（`.accessibility.LumoAccessibilityService`）。修复前的写法假定可以由自己把相对拼法补全：

```kotlin
normalise(parsed.className, expected.packageName) == expected.className
```

问题是 `ComponentName.unflattenFromString` **自己就会把前导 `.` 对条目里的 package 展开**，而 debug 变体的 package 是 applicationId（`com.lumobrowser.app.debug`）。展开后得到的名字比真正存在的类名**多一段**，于是比对失败——**用户已经在 Android 设置里打开的服务，被 LUMO 报告成关闭**。这正是该文件 KDoc 里写的那个失败模式：false negative 让用户损失一个他明明已经打开的功能，而且他会以为是 LUMO 坏了。

修复不再试图重建完整名字，而是比对 **Android 不会改写的那条尾巴**：类名本身，以及它上面那一段包名。

```kotlin
val tail = expected.substringAfterLast('.')                        // LumoAccessibilityService
val parent = expected.substringBeforeLast('.').substringAfterLast('.')  // accessibility
return stored == "$parent.$tail" || stored.endsWith(".$parent.$tail")
```

之所以够严：调用方**已经先要求** `parsed.packageName == context.packageName`，所以这个比对天然被限制在自己的 applicationId 之内，不存在把别的 App 的 `…accessibility.LumoAccessibilityService` 认成自己的可能。

---

## 5. 测试

三个测试文件，**59 例，全部通过**（1.03 定稿轮；最初是 59 例，`t13.txt`，`BUILD SUCCESSFUL`，32 秒）：

```bash
cd LumoBrowser
gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.accessibility.*"
```

> shell 注意：`cmd /c` 里 `--tests` 的参数要写成 `` --tests `"com.lumobrowser.app.accessibility.*`" ``。之前记成「`--tests` 在本机坏掉」是引号转义问题，不是 Gradle 的问题。

| 文件 | 驱动方式 | 覆盖 |
| --- | --- | --- |
| `app/src/test/java/com/lumobrowser/app/accessibility/AccessibilityAssistTest.kt` | 纯 JVM，无 Robolectric | 决策函数的全部分支：未武装、包名相符、别的 App、过期与未过期的边界（`WINDOW_MS - 1` / `WINDOW_MS`）、按压预算、`Forbidden` 全覆盖（六个平台套件 + `com.lumobrowser.app` + `com.lumobrowser.app.debug`）、`matchesAssistLabel` 的大小写与空白正规化、超集只算 `Partial`、contentDescription 匹配、空 label 不匹配任何东西、`isUsable` 的判定 |
| `app/src/test/java/com/lumobrowser/app/accessibility/AssistTargetCodecTest.kt` | 纯 JVM | 往返、多目标、字段内含 `\|`、label 含反斜杠、**label 以反斜杠结尾**、按钮文字含换行、`\r\n` 不成断行、中文往返、一行坏掉不影响其他行、未知转义保留两个字符、`upsert` / `remove` 语义、以及**格式人可读**（`assertEquals("com.example.vpn\|Example VPN\|Connect", encoded)`） |
| `app/src/test/java/com/lumobrowser/app/accessibility/AccessibilityRuntimeTest.kt` | `@RunWith(RobolectricTestRunner::class)` `@Config(sdk = [33])`，用 `Settings.Secure.putString(..., ENABLED_ACCESSIBILITY_SERVICES, ...)` **真实驱动** | 服务开关的两条识别路径（含相对拼法）、畸形条目不崩、arm 的五种拒绝与成功路径、**五个终态逐一验证都不留下 armed attempt**、**授权在尝试进行中被撤销时 `refreshServiceState` 会清掉 attempt**、`disarm()` 保留结果而 `clearOutcome()` 才清、`AssistSettings` 的三态、**过期的按下被丢弃而不是张冠李戴**（`aPressFromASupersededAttemptIsDroppedRatherThanMisattributed`，1.03 定稿轮补上——它是 `notePressed` 那条编译器警告的产物，见 2.1） |

### 5.1 一个刻意的测试钩子

`AccessibilityAssistRuntime` 有一个 `@VisibleForTesting internal fun reset()`。KDoc 明说它**刻意不是「取消尝试」的方法**——那是 `disarm()` 和 `finish()` 的工作，两者都保留用户必须看到的结果。把 outcome 也一起忘掉，会留下一颗「什么都没做、也没有任何解释」的按钮，那比不按更糟。

### 5.2 明确没有自动化测试的部分

诚实起见列在这里，第 6 节也会再说一次：

- **服务在真实设备上的节点树遍历。** `LumoAccessibilityService.tryPressDeclaredControl` 依赖真实窗口的 `AccessibilityNodeInfo` 树。Robolectric 不提供它，本机也没有连接模拟器/真机，所以这段只能在真机上人工验证。
- **`performAction(ACTION_CLICK)` 的实际效果。** 同上；代码用 `runCatching` 包住并只认返回值，没有断言测试覆盖。

---

## 6. 这一版没做什么

- **`VpnProfile` 编辑界面**（属于 VPN 那一条，不是这一条）。
- **真机验证。** 本机没有可用的模拟器或真机，辅助服务的端到端行为**未被实际运行验证过**。已构建的 APK 可以安装并手动走第 3 节那四步，但那份证据本版拿不出来。验收表里这一条按「实现完成、真机未验」记，不记全绿。
