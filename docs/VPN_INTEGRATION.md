# VPN 集成说明（1.03 / versionCode 5）

> 本文随 v2.2 专项任务建立，1.03 更新。第四节在 2.2 时记的是「本版本没有实现无障碍服务」，
> 该状态已在 1.03 结束——服务已实现，说明迁至 [`docs/ACCESSIBILITY_ASSIST.md`](ACCESSIBILITY_ASSIST.md)，
> 第四节保留当时的取舍过程并补上实现后的对照。

本文回答规格第八节要求的四个问题，逐条给出**可核对的事实**而不是承诺：

1. [LumoBrowser 能不能真实建立 VPN 连接？](#1-lumobrowser-能不能真实建立-vpn-连接)
2. [支持哪些外部 VPN 服务？](#2-支持哪些外部-vpn-服务)
3. [是否依赖用户自己提供的服务器或账号？](#3-是否依赖用户自己提供的服务器或账号)
4. [无障碍功能实际用来做什么？](#4-无障碍功能实际用来做什么)

以及两个补充章节：[改了什么、为什么](#5-改了什么为什么)和[怎么验证](#6-怎么验证)。

---

## 1. LumoBrowser 能不能真实建立 VPN 连接？

**不能。这个版本一条隧道也建不起来，应用也如实这样说。**

这不是"还没做完"，而是本版本刻意选择的发布状态：规格 2.2 明文禁止虚构 VPN 服务器、账号、密钥、连接成功状态或代理能力，所以与其放一个声称能连、点下去必然失败的按钮，不如把这个事实放在设置页状态面板里第一眼就能看到的位置。

判断依据是一个可断言的常量，不是注释：

```kotlin
// app/src/main/java/com/lumobrowser/app/vpn/VpnTunnel.kt
object TunnelBackends {
    private val backends = mutableListOf<TunnelBackend>()
    fun all(): List<TunnelBackend> = synchronized(lock) { backends.toList() }   // 空
    fun isAvailable(): Boolean = all().isNotEmpty()                             // false
}
```

`app/src/test/java/com/lumobrowser/app/vpn/VpnIntegrationTest.kt` 里的
`thisBuildShipsNoProtocolBackendAndSaysSo` 把这个空 registry 钉成断言。**任何人将来注册一个
backend，这个测试就会红**，而红是正确的：出货运作一个协议等于出运一个已经对真实服务器跑过的实现，
那是必须同时更新文档的事。

### 那么实际存在的是什么？

存在的是**一条完整的、除最后一段之外全部可运行的隧道管线**。缺的只有"把字节送进 tun"这一步。

| 环节 | 状态 | 位置 |
| --- | --- | --- |
| 系统授权（`VpnService.prepare()` → 同意对话框 → 结果） | **可用** | `VpnController.authorization()`、`VpnAuthorization`、`MainViewModel.connectVpn()` / `onVpnConsentResult()` |
| `VpnService` 生命周期（启动、停止、撤销、销毁） | **可用** | `vpn/LumoVpnService.kt` |
| 前台服务通知与通道 | **可用** | `LumoVpnService.goForeground()`，通道 `lumo_vpn`，`IMPORTANCE_LOW` |
| `Builder` 配置（会话名、MTU、DNS、路由、底层网络） | **可用** | `LumoVpnService.startTunnel()` |
| 状态上报（进程级 StateFlow） | **可用** | `VpnTunnelStatus` |
| 状态合成与六态呈现（+1） | **可用** | `vpn/VpnReport.kt`、`VpnReportedState` |
| 端点配置的持久化与转义 | **可用** | `VpnProfileCodec`、`UserPreferences.vpnProfile`、`SettingsRepository.setVpnProfile()` |
| 已安装 VPN 应用的发现与打开 | **可用** | `vpn/VpnAppDirectory.kt` |
| **协议实现（WireGuard / OpenVPN / 其它）** | **不存在** | `TunnelBackends` 为空 |
| **服务器配置界面、账号与密钥存储** | **不存在** | —— |

### 为什么协议实现不存在，而不是写一个占位

一个 `TunnelBackend` 的实现必须真的把 tun 里的 IP 包取出来、按协议封装、通过一个已经
`protect()` 过的 socket 送出去，并把回来的包写回 tun；再加上握手、密钥轮换、DNS 与路由策略、拆分隧道。
没有真实服务器可对接时，这些代码写完也无法验证——**而无法验证的隧道代码在用户那里失败的样子，
和应用本身坏掉的样子完全一样**。规格 2.2 对这种情形的指示就是照做：

> 缺少真实服务器或可对接应用时，如實說明依賴條件，並先完成可測試的集成接口。

本文和 `TunnelBackend` 接口就是那个"可测试的集成接口"。

### 那么点"连接"会发生什么？

`MainViewModel.connectVpn()` 的第一层分流就是答案：

```kotlin
when (VpnController.connectability(profile)) {
    VpnConnectability.NoBackend -> VpnTunnelStatus.publish(Failed(TunnelStartResult.NoBackend))
    VpnConnectability.NoProfile -> VpnTunnelStatus.publish(Failed(TunnelStartResult.NotConfigured))
    VpnConnectability.Ready -> /* 才轮到询问授权 */ ...
}
```

**顺序本身就是设计**：本版本永远走第一条，所以

- 系统的 VPN 授权对话框**不会出现**——用户不会被要求授权一条不可能存在的隧道；
- `LumoVpnService` 连前台通知都不会发，它在 `startForeground()` 之前就
  `publish(Failed(NoBackend)); stopSelf()`；
- 状态面板显示"未配置"以及"本版本不包含 VPN 协议实现"的说明，不是"连接失败"。

最后一点是刻意的用词区分。`NotConfigured` 与 `NoBackend` 是**两个不同的** `TunnelStartResult`，
合并它们会把"这个构建缺功能"说成"你没配置好"。

---

## 2. 支持哪些外部 VPN 服务？

**方案 A（对接用户已安装的 VPN 应用）实现了"发现"和"打开"两级，没有实现"控制"。**

规格 2.2 对这一层的要求是：

> 只用该应用**正式提供的公开 API/Intent**，**不得猜测任意 VPN 应用的包名、接口或内部实现**；
> 只有该应用明确支持时才提供自动启动/连接。

### 已实现：发现

`VpnAppDirectory.installedVpnApps(context)` 用一条**平台自己就能回答**的查询列出设备上真实的 VPN 应用：

```kotlin
val intent = Intent("android.net.VpnService")
val services = if (Build.VERSION.SDK_INT >= 33) {
    manager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
} else {
    @Suppress("DEPRECATION") manager.queryIntentServices(intent, 0)
}
```

不需要枚举包名，不需要 `QUERY_ALL_PACKAGES`，也不需要任何猜测：**每一个 `VpnService`
都必须在自己的 manifest 里声明 `android.net.VpnService`**，这是平台的规则，不是我们的假设。

对每个命中的应用，`describe()` 取应用标签与 `getLaunchIntentForPackage()`。
`launchIntent` 为 null 时**条目保留**——用户可以看见 LumoBrowser 找到了它，以及为什么打不开它，
而不是应用悄悄省略掉一个用户知道存在于自己手机上的东西。

### 未实现：控制

`VpnAppControlApis` 是**刻意空的**：

```kotlin
object VpnAppControlApis {
    private val entries: List<VpnAppControlApi> = emptyList()
}
```

本次开发尝试查证第三方应用的公开控制接口（例如 OpenVPN for Android 的
`de.blinkt.openvpn.api.profileList` / `START_PROFILE` 系列），**没有拿到可引用的原文**——
`web_fetch` 对该域名返回 `Error: URL hostname ... resolves to a non-public IP address`。搜索结果
只给出来源列表，没有可核对的接口定义。

**所以没有写。** 理由是规格 2.2 禁止猜测，而更实际的理由是：凭记忆捏一条 action 字符串，
失败时在用户眼里是"LumoBrowser 坏了"，不是"这个应用不支持"。

将来要加一条，代价是一行数据加一个测试，不需要动 UI 或状态机：

```kotlin
VpnAppControlApi(
    packageName = "…",            // 该应用的包名
    source = "…",                 // 该应用官方文档的 URL
    startAction = "…",            // 文档里逐字写的 action
    stopAction = null,
    resolve = { context -> /* 该应用是否真的接受这个 action */ },
)
```

`VpnAppControlApi` 做成数据而不是硬编码 `when` 分支，就是为了让这些字段**可以被阅读、查核、删除**——
每个字段都是关于别人应用的主张，只能来自那个应用自己的文档。
`noThirdPartyVpnAppIsClaimedToBeControllable` 测试当前空的 registry。

### 用户实际能做什么

| 目标 | 入口 | 行为 |
| --- | --- | --- |
| 打开自己已安装的 VPN 应用 | 设置 → 网络与 VPN → 已安装的 VPN 应用 → 点条目 | `getLaunchIntentForPackage()`，不猜 action |
| 进入系统 VPN 设置 | 面板里的"系统 VPN 设置" | `Settings.ACTION_VPN_SETTINGS` |
| 进入系统无线设置 | 面板里的"网络设置" | `Settings.ACTION_WIRELESS_SETTINGS` |
| 连接本应用自己的隧道 | 面板里的"连接" | **只在 `canConnect && hasProfile` 时出现**；本版本不出现 |

### 支持范围（明确说明）

- **支持**：发现任何声明了 `android.net.VpnService` 的应用并打开它的主界面。
- **不支持**：代替任何第三方应用发起、停止或配置连接。
- **不依赖**：任何特定应用的包名、版本或私有接口。
- **受平台限制**：Android 不允许第三方应用在没有 `QUERY_ALL_PACKAGES` 的情况下枚举 VPN 配置
  （profile），本应用不申请该权限，所以**看不到用户的隧道配置，只知道哪些应用能建隧道**。

---

## 3. 是否依赖用户自己提供的服务器或账号？

**依赖。而且本版本连收集它们的界面都没有。**

规格 2.2 要求方案 B "必須具備真實可用的 VPN 協議實現、伺服器配置與認證流程"，并明确：

> **不得僅創建一個 `VpnService` 類就聲稱 VPN 可用**；
> **不得虛構 VPN 伺服器、帳號、密鑰、連接成功狀態或代理能力**。

本版本创建的不止一个 `VpnService` 类——授权、生命周期、状态、持久化、界面都做了——但**没有**协议实现，
所以按规格的字面要求，它**不声称 VPN 可用**。

数据模型已经为真实凭据留好了位置，而且刻意**没有**收集凭据：

```kotlin
data class VpnProfile(
    val server: String = "",
    val port: Int = 0,
    val protocol: VpnProtocol? = null,
    val displayName: String = "",
    val dnsServers: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val mtu: Int = DEFAULT_MTU,
)
```

凭据（密码、私钥、预共享密钥）**不在这个结构里**，KDoc 写明了原因：为一个建不起来的隧道收集密码，
只是把一个用户无法评估的风险提前收下。等到真有协议实现时，凭据应该进 `EncryptedSharedPreferences`
或 Android Keystore，而不是进这个可以自由序列化的 data class。

`UserPreferences.vpnProfile` 存的是 `VpnProfileCodec` 编码后的**单个不透明字符串**，
键名 `vpn_profile`，格式 `v1|server|port|protocol|name|dns|routes|mtu`：

- 自由文本字段转义 `\` → `\\`、`|` → `\p`、换行 → `\n`，解码时用手工扫描器而不是 `String.split('|')`；
- 版本前缀不认识、字段数不对、端口不是数字 → **返回 `VpnProfile()`**（未配置），绝不猜一个端点；
- 解析失败与"用户没填过"是同一个结果，这是刻意的：一个读不懂的记录，安全读法是"没有配置"。

**做出货决定时需要知道的**：以本版本的实际能力，服务器与账号的问题不成立——
应用不会连出去，因为没有任何代码知道怎么连。

---

## 4. 无障碍功能实际用来做什么？

**1.03 起已实现。** 完整说明在 [`docs/ACCESSIBILITY_ASSIST.md`](ACCESSIBILITY_ASSIST.md)。

版本 2.2 交付时这一节写的是「本版本没有实现无障碍服务」，那个状态在本版已经结束。保留这段历史是因为它记录了一个真实的取舍过程，而当时的判断标准——**为一个不存在的服务做授权状态检查，只能检查出一个恒为 false 的值，那不是功能，是装饰**——现在依然是判断同类事情的标准。区别只在于：服务现在真的存在了，所以那三个入口（打开系统设置 / 检查授权状态 / 关闭辅助功能）查的是一个会随用户在 Android 设置里的真实勾选而变化的量。

规格 2.3 的三条边界，逐条对应到实现：

| 规格要求 | 实现位置 | 怎么保证 |
| --- | --- | --- |
| 无障礙權限不是 VPN 權限 | 两个服务互不引用 | `LumoAccessibilityService` 与 `LumoVpnService` 没有共享状态；关掉任何一个，另一个照常工作 |
| 不得讀取或記錄其他應用的密碼/短信/驗證碼/支付信息 | `AccessibilityAssist.kt` 的 `collectCandidates` / `LumoAccessibilityService` | 走节点树时**显式跳过** `isEditable` 或类名含 `EditText` 的节点，且**不读 `node.text` 以外的任何东西、不落盘、不记录**。找到目标按一下就结束，没有任何"旁路采集"路径 |
| 不得暗中點擊授權按鈕或代替用戶同意 | `isForbiddenAssistPackage` | 六个平台套件（系统设置、权限控制器、安装器、SystemUI 等）永远返回 `Forbidden`，用户即使把它们声明成目标也不会被按 |

规格第七节的两条验收标准：

- **"无障礙預設關閉且有說明"** —— `AssistSettings.switchState()` 返回 `Boolean?`，默认 `null`（从未表态）而不是 `false`（看过并关掉）。服务在 Android 侧还需用户单独勾选，两道开关都不动时服务不会被启动。
- **"關閉無障礙後不再執行輔助操作"** —— 由两条独立路径保证：服务侧的 `onUnbind` / `onDestroy` 会清空当前尝试，UI 侧的 `refreshServiceState` 在 `ON_RESUME` 时重新检查授权，**授权在尝试进行中被撤销时会把 attempt 一起清掉**（`AccessibilityRuntimeTest` 有专门一例钉住这条）。

当初写下的五条"将来实现时的既定约束"，实现时逐条照做，没有一条被放宽：

1. 默认关闭 → `Boolean? = null` + 系统侧二次勾选；
2. 只在 LUMO 自己的辅助流程里操作 → 目标逐条由用户声明，不存在"在所有 App 里找按钮"的路径；
3. 从系统设置返回后重新检查授权 → `LumoApp.kt` 接 `ON_RESUME`；
4. 不自动点击授权按钮、不代替用户同意、不后台无限循环 → `isForbiddenAssistPackage` + `MAX_PRESSES = 1` + `WINDOW_MS = 12_000L`；
5. 不确定时停止并提示用户手动完成 → `Exhausted` / `NotFound` 两个终态在界面上必定跟一句「请自行完成」。

**本版本在这条上的诚实边界**：实现完成、58 例纯逻辑与 Robolectric 测试通过，但**真机端到端未验证**——本机没有可用的模拟器或真机，节点树遍历与 `performAction(ACTION_CLICK)` 的实际效果只能在真机上人工走一遍。详见 [`docs/ACCESSIBILITY_ASSIST.md`](ACCESSIBILITY_ASSIST.md) 第 5.2 与第 6 节。

---

## 5. 改了什么、为什么

这一节记录本次替换掉的旧实现，因为它的失败方式值得记住。

### 旧实现的两个结构性错误

旧代码在 `app/src/main/java/com/lumobrowser/app/browser/NetworkMonitor.kt` 里有一个
`enum class VpnConnectionState` 加上一个 `resolveVpnState(...)`。它有两个问题：

**错误一：把设备的观测当成应用的结论。**

```kotlin
// 旧代码
if (network.vpnActive) return VpnConnectionState.Connected
```

`network.vpnActive` 读的是 `NetworkCapabilities.TRANSPORT_VPN`——**关于这台手机的事实**。
它无法区分"LumoBrowser 建的隧道"和"公司 MDM 推的隧道"，也无法区分"隧道通了"和
"隧道通了但目标站点依然不可达"。用它去渲染"已连接"，等于把手机的状态冒充成应用的功劳。

新实现把它降级成 `VpnReportedState.DetectedNetwork`，并在状态行下面配一句说明。
`resolveVpnReport` 的优先序在 KDoc 里逐条写了理由，核心是：**只有 `TunnelRuntimeState.Up`
——也就是本应用自己的服务报告隧道起来了——才是 `Connected`。**

**错误二：三态里有二态永远到不了。**

```kotlin
// 旧代码
private val lastVpnAttempt = MutableStateFlow<VpnAttempt?>(null)   // 初始化为 null
fun noteVpnAttempt() { ... }                                       // 零调用点
fun clearVpnAttempt() { ... }                                      // 零调用点
```

`Connecting` 和 `Failed` 由 `lastVpnAttempt` 驱动，而这个 flow 从初始化之后**再没有人写过**，
所以这两个状态在物理上不可达。后果不只是两个字符串不显示：

- 浏览器里"连接 VPN 后重试"会 `waitingForVpn = true` 然后等一个永远不会到来的信号。
  **唯一的逃逸路径是 `Failed`，而 `Failed` 不可达。** 用户点下去等于掉进真空。
- `vpn_state_connecting` / `vpn_state_failed` 两条字符串永远不会出现在屏幕上。

新实现在 `BrowserScreen.kt` 里加了 `VPN_WAIT_TIMEOUT_MS = 30_000L` 的**墙钟上限**：

> 一个用户无法退出的等待，和一个卡死没有区别，所以它会到期，并且会说明。

### 顺带修掉的两个死开关

同一轮审计发现两个有 UI、有持久化、但从未出现在任何条件式里的偏好：

| 偏好 | 设置页 | 旧行为 |
| --- | --- | --- |
| `checkNetworkBeforeOpening` | 有开关 | 从未被读取 |
| `rememberPerSiteNetworkAssist` | 有开关 | 每站点选择照样持久化，但从未被查询 |

两者都已接进实际逻辑，或在 KDoc 里说明其现状。**一个能切换但没有任何效果的开关，
比没有这个开关更糟**——它让用户以为自己配置了什么。

### 现在的分流顺序

```
用户按下"连接"
  ├─ TunnelBackends 为空 ──────────→ Failed(NoBackend)，不询问授权，不发通知
  ├─ profile.isUsable 为假 ────────→ Failed(NotConfigured)
  └─ Ready
       ├─ prepare() 返回 null ──────→ startService，服务自己上报进度
       ├─ prepare() 返回 Intent ────→ 存进 vpnConsentRequest，由 Activity 启动系统对话框
       └─ prepare() 抛异常 ─────────→ Failed(Failed(reason))
```

`VpnStatusReport` 的六个规格状态加一个：

| 状态 | 触发条件 |
| --- | --- |
| `NotConfigured` | 没有可用的 protocol backend |
| `Disconnected` | 有 backend + 完整 profile，但没有隧道在跑 |
| `Connecting` | 本应用自己的服务正在拨号 |
| `Connected` | **本应用自己的隧道报告已建立** |
| `DetectedNetwork` | 平台报告有 `TRANSPORT_VPN` 网络（**不是本应用建的**） |
| `Failed` | 本应用的上一次尝试失败 |
| `Unknown` | 平台拒绝了网络查询 |

第七个（`DetectedNetwork`）不是规格里的一态，而是**规格 2.4 那句话的实现**：

> 只能檢測到系統 VPN 網絡存在時必須標記為「檢測到 VPN 網絡」，不得冒充已驗證目標網站可訪問。

把它做成一个独立的枚举值而不是一个布尔标志，是因为**布尔标志可以和"已连接"同时为真**，
而枚举值不行——这个区别就是那句话的全部内容。

---

## 6. 怎么验证

### 已在本机执行

```powershell
# 1. 编译
$env:JAVA_HOME='D:\Program Files\Java\jdk-17'
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT='D:\AndroidDev\sdk'
Set-Location 'D:\deepseek\Website\LumoBrowser'
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:compileDebugKotlin --no-daemon --console=plain

# 2. VPN 层契约测试（纯 JVM，不需要设备）
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --no-daemon --console=plain
```

`app/src/test/java/com/lumobrowser/app/vpn/VpnIntegrationTest.kt` 覆盖的是本文件里每一条断言：

| 测试 | 钉住的事实 |
| --- | --- |
| `thisBuildShipsNoProtocolBackendAndSaysSo` | registry 为空、`isAvailable()` 为 false、完整 profile 也不可连 |
| `noThirdPartyVpnAppIsClaimedToBeControllable` | 没有关于别人应用的主张 |
| `aSystemVpnNetworkIsReportedAsDetectedNotConnected` | **核心不变量**：系统 VPN 网络 ≠ 已连接 |
| `anEstablishedTunnelIsTheOnlyThingReportedAsConnected` | 只有 `Up` 才产生 `Connected` |
| `aStaleFailureDoesNotOutrankALiveVpnNetwork` | 优先序，且失败信息不丢 |
| `withoutABackendThePanelSaysNotConfiguredRatherThanDisconnected` | 两种"没连上"不合并 |
| `anUnknownNetworkReadingIsReportedAsUnknownRatherThanOffline` | 不把"问不到"说成"没有网" |
| `anUnreadableRecordReadsAsNothingConfigured` | 读不懂的记录 → 未配置，不猜端点 |
| `theSeparatorInsideAValueDoesNotBecomeAField` | profile 转义往返 |
| `onlyABackendsOwnFailureCarriesFreeText` | 只有 backend 的失败带英文自由文本 |
| `theRuntimeStateIsProcessWideSoThePanelOutlivesTheService` | 状态在服务被杀后仍在 |

### 无法在本机执行（如实列出）

| 项目 | 为什么不能 | 需要什么才能做 |
| --- | --- | --- |
| 真机安装与启动验证 | 无设备、无模拟器、无 AVD、无 system image；`adb devices -l` 只有表头 | 一台 Android 设备或可用的模拟器 |
| 同意对话框的端到端流程 | 同上；`VpnService.prepare()` 需要真实系统 | 同上 |
| 真实隧道建立 | **没有协议实现，也没有服务器** | 一个协议实现 + 一台用户自己的服务器 |
| 第三方 VPN 应用的发现结果 | 需要设备上真的装有 VPN 应用 | 同上 |
| 前台服务通知的显示 | 需要真实系统 | 同上 |

### 复现"本版本不能连"的步骤

在有设备的机器上：

1. 安装 `LumoBrowser-*-debug.apk`；
2. 打开 设置 → 网络与 VPN；
3. 状态行应显示**未配置**，下面应显示"本版本不包含 VPN 协议实现"的说明；
4. **不应该**出现"连接"按钮——它只在 `canConnect && hasProfile` 时渲染；
5. **不应该**出现任何系统 VPN 授权对话框；
6. 如果设备上装有 VPN 应用，"已安装的 VPN 应用"一节应列出它们，点条目应打开该应用。

第 4 和第 5 条是这个版本最重要的两个否定断言：**没有能力的时候，界面不应该摆出一个会失败的按钮，
也不应该向系统要一个它用不上的授权。**
