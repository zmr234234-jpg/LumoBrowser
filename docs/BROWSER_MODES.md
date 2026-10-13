# 浏览模式与每网址独立设置

这份文档说明 LumoBrowser 的**浏览模式**是什么、四个模式之间到底差在哪、自动模式什么时候会自己切换、以及**每个收藏**各自的网页设置怎么存、怎么生效。

> 面向两类读者：想让某个站点正常显示的使用者，以及要改这套逻辑的维护者。前者看 §1、§2、§6；后者从 §3 开始。

---

## 1. 四个模式分别解决什么问题

| 模式 | 一句话 | 什么时候用它 |
| --- | --- | --- |
| **自动** | 以移动版起步，**只在能修好的失败上**依次升级 | 默认。绝大多数站点 |
| **移动版** | 标准移动端渲染，**永不自动改变** | 自动模式把某个站改坏了，你要把它钉死 |
| **兼容** | 放宽渲染，给「严格默认值会弄坏」的站点 | 页面一片空白 / 布局错乱 / 内容被裁掉 |
| **桌面版** | 用桌面 User-Agent 请求桌面站 | 移动站功能残缺，而桌面站正常 |

**「兼容」不是「移动版」的别名。** 这是这套设计里最容易退化的地方：如果两者配置相同，「兼容」就只是一个听起来更安全的按钮。它们的实质差异见 §2 的表，而且有单元测试（`BrowserModeTest`）断言四者的配置**互不相同**——防止将来有人「简化」成同一个 profile。

---

## 2. 四个模式的实际配置差异

每个模式解析成一个 `BrowserModeProfile`（`data/BrowserConfig.kt`）：

| | User-Agent | wide viewport | overview mode | 混合内容 | 缓存 | 文字缩放 |
| --- | --- | --- | --- | --- | --- | --- |
| **移动版** | 设备默认 | 开 | **关** | 严格（`MIXED_CONTENT_NEVER_ALLOW`） | 正常 | 用户设置值 |
| **桌面版** | 桌面模板 | 开 | 开 | 严格 | 正常 | 用户设置值 |
| **兼容** | **设备默认** | 开 | 开 | **宽松（`COMPATIBILITY_MODE`）** | **强制跳过缓存** | **强制 100%** |
| **自动** | 起步即移动版，运行期升级 | | | | | |

几个刻意的决定：

- **`Auto` 解析成「移动版」的 profile，不是第五种配置。** 如果自动模式有自己独立的渲染配置，用户就没法在界面上指认自己正在看的是哪一种——模式名和观察到的渲染必须一一对应。自动模式的「自动」体现在**运行期会升级**，而不是体现在一套隐藏配置上。
- **兼容模式用设备默认 UA，不用桌面 UA。** 兼容的定位是「最小变动地把页面救回来」。换成桌面 UA 是最大的行为变动，应该由用户显式选择桌面版，或由自动模式的第三级去尝试。
- **兼容模式强制跳过缓存。** 这类页面常常是被缓存住的一份坏响应；强制重新取一次是最便宜的一步，而且如果问题不在缓存，代价也只是这一次多走一趟网络。
- **兼容模式把文字缩放钉在 100%。** 渲染空白的站点，常见成因是移动样式表假定了一个固定字号，而用户调过的缩放把它推到了布局之外。钉住缩放等于移除了这个变量——这也是为什么在兼容模式下设置里的文字缩放滑杆**不生效**，诊断面板会显示实际生效值。

### User-Agent 的解析顺序

`planFor(...)` 里的优先级是：

1. 该收藏的**自定义 User-Agent**（非空白）——最高优先级，用户写什么就是什么；
2. 否则，如果**请求桌面版**开关打开 → 使用桌面模板；
3. 否则 `null`。

**第 3 条用 `null` 而不是「平台默认 UA 的副本」，这是关键的。** `null` 表示「交给系统」，`WebSettings.userAgentString = null` 会让 WebView 用当前引擎的真实默认值。如果把默认值读出来存成字符串再写回去，就等于**把 UA 永久钉死在装机那天的 WebView 版本上**——系统 WebView 更新后，本应用仍然在报旧版本，站点会据此提供过时的内容。

**「请求桌面版」与「桌面版模式」是叠加关系，不是替代关系。** `ResolvedSiteConfig.desktopSiteEnabled = desktopSiteEnabled || profile.desktopUserAgent`——选了桌面版模式，就是在要求桌面站，哪怕那个开关从来没被碰过。所以诊断面板必须报告**有效值**而不是存储值，否则面板在骗人。

> **1.02 修掉的一个真实缺陷（由 `BrowserConfigManagerTest` 抓出）**
>
> 上面第 2 条原先写的是「开关打开**且** profile 要求桌面 UA」，也就是 `config.desktopSiteEnabled && profile.desktopUserAgent`。而 `resolve()` 已经把 `profile.desktopUserAgent` **合并进** `desktopSiteEnabled` 了，于是这个额外条件不仅冗余，还把开关在**非桌面版模式**下变成了空操作：用户在添加/编辑页打开「请求桌面版」，页面行为毫无变化，诊断面板也会显示 `desktop-ua` 而实际发出去的仍是手机 UA。
>
> 这就是「一个能打开、能保存、但什么都不做的开关」——本轮任务要消灭的正是这一类。现在只判断 `config.desktopSiteEnabled` 一个标志：桌面版模式经由 `resolve()` 的合并仍然拿到桌面 UA，而 Mobile / Compat / Auto 下用户显式打开开关时也**真的**会发桌面 UA。**显式的每网址选择优先于模式的默认值。**
>
> 回归由三个用例锁住：`theDesktopSiteToggleLiftsMobileToADesktopUserAgent`、`theDesktopSiteToggleAlsoWorksInCompatAndAuto`、`theDesktopSiteToggleIsStillOffByDefaultInEveryMode`（最后一条防止把每个收藏都变成桌面请求）。

桌面 UA 模板由本机引擎版本拼出：从默认 UA 里用 `Chrome/(\d+(?:\.\d+)*)` 取版本号，套进 `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<版本> Safari/537.36`。取不到 Chrome token 时**只把 `" Mobile"` 去掉，不猜版本号**——宁可给出一个略保守的 UA，也不要伪造一个不存在的引擎版本。

### 应用时会设成什么

`planFor(...)` 产出的 `BrowserSettingsPlan` 里，这些是**固定值**，不随模式变化：

| 设置 | 值 | 为什么 |
| --- | --- | --- |
| `supportMultipleWindows` | **true** | 不打开这个开关，`onCreateWindow` **根本不会被调用**——`target="_blank"` 与 `window.open` 会被静默丢弃，用户看到的是「链接点了没反应」 |
| `javaScriptCanOpenWindowsAutomatically` | false | 弹窗必须来自用户手势 |
| `mediaPlaybackRequiresUserGesture` | true | 不自动播放 |
| `allowFileAccess` | false | 最小权限 |
| `allowContentAccess` | false | 最小权限 |
| `safeBrowsingEnabled` | true | 安全浏览 |
| `algorithmicDarkening` | 跟随应用深色主题 | 让网页跟随应用主题 |

另外 `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 在 `LumoWebView.init` 里显式设为 `false`（带 `@Suppress("DEPRECATION")`——API 30 起已弃用，但它仍是 API 26..29 上**唯一**能关掉 `file://` 跨来源读取的开关，删掉会在旧机上静默重新打开）。

---

## 3. 自动模式：什么时候切换，什么时候不切

自动模式是一条**有界阶梯**：

```
Mobile  ──失败且可配置──▶  Compat  ──失败且可配置──▶  Desktop  ──▶  停（exhausted）
```

- `AUTO_LADDER = listOf(Mobile, Compat, Desktop)`，最多 **3 次**尝试（`AUTO_MAX_ATTEMPTS`）。
- 顺序的理由：**移动版第一**（手机上的正确渲染），**兼容第二**（最小变动，保留设备 UA），**桌面最后**（最大行为变动，在小屏幕上最有可能更糟）。

### 「可配置的失败」的判定

判定函数是 `classifyFailure(kind, httpStatus, documentIsEmpty)`，返回 `Keep` / `Switch` / `NotConfigurable`。守卫顺序**就是政策本身**，不要随意调整：

1. `httpStatus >= 400` → **`NotConfigurable`**。404 在任何 User-Agent 下都是 404。
2. 错误类别是 `Ssl` / `UnsupportedScheme` / `TooManyRedirects` / `FileNotFound` → **`NotConfigurable`**。**证书错误必须作为证书错误呈现。** 换 UA 重试一次证书错误，等于给安全绕过加了几道手续——而本项目的硬约束明确禁止忽略 HTTPS 证书错误。
3. 错误类别是 `HostLookup` / `Connect` / `Timeout` / `Proxy` → **`NotConfigurable`**。页面根本没到达，没有东西可以「换个方式渲染」。
4. `Generic` → **`Switch`**。
5. 否则，**文档渲染出来是空的** → `Switch`，否则 `Keep`。

> ⚠️ **`ErrorKind.isRetryable` 与这里的判定是两个不同的问题，不要合并。** `isRetryable` 问的是「网络层面值不值得重试」，`CompatibilityVerdict` 问的是「页面层面换个渲染配置有没有用」。把两者混为一谈的后果是：一次 Wi-Fi 抖动就会把某个站点**永久**标记成「需要桌面模式」。

### 状态机的不变量

`AutoModeState`（`browser/AutoMode.kt`）是纯函数式不可变值：

- `attempt` **只增**，每次**只增一**，且**只在 `Switch` 裁决上增**；
- 饱和于阶梯顶端，之后置 `exhausted = true`；
- `cancelled()` 同时置 `exhausted = true`——用户按下「停止」之后，后续所有裁决都是 no-op。**这是用户意图优先于启发式的地方。**
- `onLoaded()` 只在 `switching == true` 时清除标志。

这些不变量由 `AutoModeTest` 断言，包括「attempt 不越界」「非 Switch 裁决不推进」「exhausted 后全部 no-op」。

### 在界面上是什么样

- 浏览器右上角「更多」菜单里的模式，显示的是**当前实际生效**的模式。
- 自动模式正在尝试下一级时，菜单顶部显示「正在尝试兼容模式…」并提供**停止**。
- 阶梯走完后显示「兼容模式无法修复此页面」，并停止继续尝试。
- **手动点选任一具体模式会重置阶梯**——那是用户在覆盖自动行为，不该被残留的自动状态干扰。
- 「还原默认」把该收藏的模式恢复为设置里的全局默认。

---

## 4. 每网址独立设置

### 存哪

浏览相关的 8 个字段直接是 `Bookmark` 行的列（Room `bookmarks` 表，schema v2）：

| 列 | 类型 | 含义 |
| --- | --- | --- |
| `browserMode` | TEXT | `Auto` / `Mobile` / `Desktop` / `Compat` |
| `userAgentOverride` | TEXT | 自定义 UA，空 = 不用 |
| `javaScriptEnabled` | INTEGER | 默认 1 |
| `domStorageEnabled` | INTEGER | 默认 1 |
| `desktopSiteEnabled` | INTEGER | 默认 0 |
| `useThirdPartyCookies` | INTEGER | 默认 0 |
| `lastSuccessfulMode` | TEXT NULL | **观测值**，不是设置值 |
| `lastModeChangeTime` | INTEGER | 上次观测时间 |

**放在 `Bookmark` 上而不是单独的配置表**，理由是生命周期完全重合：配置存在的意义就是「打开这个收藏时怎么渲染」。独立表会带来孤儿行、级联删除、以及备份/恢复要同时处理两张表的问题，换不来任何好处。

因为它们就是书签的列，所以**备份与恢复自动涵盖**它们（`BackupManager` 导出的是完整书签对象），schema v1 → v2 的迁移也只是加列，不影响既有收藏。

### `lastSuccessfulMode` 是观测，不是设置

这个字段记录「上一次在这个网址上真正加载成功的是哪个模式」，由浏览器在**加载成功**时写回。

- 它让下次打开这个站能直接从已知能用的模式起步，而不是从阶梯底部重爬一遍。
- **编辑页不会覆盖它。** `EditorScreen` 保存时显式带上 `lastSuccessfulMode = original?.lastSuccessfulMode`——表单里没有这个字段，用户没在编辑它，表单就不该拥有它。这是「编辑页可能还握着一份未保存的旧副本」这类静默回退问题的典型来源。
- 写回走 `BookmarkRepository.updateBrowserMode(id, mode, lastSuccessful)`，**单列更新**而不是整行读-改-写。理由同上：浏览器改模式时编辑页可能正握着同一笔书签的未保存文本，整行写回会**静默还原用户正在进行的编辑**。

### 从哪改

- **编辑页**（新增/编辑收藏）：模式 chips + 「高级网页设置」折叠区（JS / DOM 存储 / 第三方 Cookie / 请求桌面版 / 自定义 UA）。
- **浏览器内**：右上角「更多」菜单，改的是**本地副本**，随浏览过程即时生效。
- **设置页「浏览」区**：全局默认模式、失败自动切换开关、全局文字缩放。全局默认是新建收藏的初值。

### 写回的边界

浏览器内的模式改动**只对收藏库中确实存在的网址持久化**。

- 从收藏卡片进入 → 该网址在库里 → 改动写入对应的书签行。
- 从系统「分享到 LumoBrowser」进来一个**没收藏过的**网址 → 没有书签行可挂配置 → 改动**不持久化**，本次浏览内有效。

这是刻意的：为一次临时访问**凭空造一条收藏**，是在用户没要求的情况下静默改变他的收藏库。

---

## 5. 诊断面板

「更多」菜单 → **网页诊断**。它存在的理由：用户说「这个站打不开」时，可用的信息通常只有这一句，而真正的原因可能是 DNS、证书、HTTP 状态码、WebView 版本、或者应用自己的某个开关。

面板报告：

| 项 | 说明 |
| --- | --- |
| URL | 当前地址 |
| 配置模式 / **实际生效模式** | 两者可能不同（自动模式升级过、或桌面版开关注入了桌面 UA） |
| 最终 User-Agent | **实际发给站点的那个字符串**，不含品牌标记以外的加工 |
| 加载阶段 | 空闲 / 加载中 / 已加载 / 失败 |
| 错误类别与详情 | 归类后的 `ErrorKind` 加原始描述 |
| HTTP 状态 | 有响应时的状态码 |
| 是否重定向过 | |
| 弹窗请求 / 被拒次数 | |
| 权限请求被拒次数 | |
| JS / DOM 存储 / 第三方 Cookie / 桌面版 / 文字缩放 | **各项的生效值** |
| 配置摘要 | 一行可读的配置描述 |
| 系统 WebView | 包名与版本（`WebViewCompat.getCurrentWebViewPackage`） |

### 它不可能泄露隐私——这是类型保证的

`PageDiagnostics` 这个数据类的字段**结构上无法承载页面内容**：

- 没有正文文本、没有表单值、没有请求头、没有 Cookie 的字段；
- 连一个 `Map<String, String>` 都没有——**没有地方可以塞进一个 cookie 或 bearer token**；
- 它**永不持久化、永不上传**：不写数据库、不写 DataStore、不写 logcat、不发网络请求。

这不是「我们记得不要上传」的约定，而是**类型上做不到**。加字段时若想加一个 `extra: Map<String,String>`，那一步会明显到无法忽略。

---

## 6. 使用者速查

**页面一片空白 / 布局错乱 / 内容被裁掉** → 更多菜单 → 兼容模式。仍然不行 → 桌面版。

**移动站功能残缺，桌面站正常** → 更多菜单 → 桌面版。

**某个页面总被重定向到 App 下载页，或者总弹「请用桌面浏览器打开」** → 桌面版，或在编辑页填一个自定义 User-Agent。

**自动模式一直在这个站上乱跳** → 手动选「移动版」把它钉死，或在设置里关掉「页面异常时自动切换」。

**页面加载很慢、每次进来都要重新下** → 检查是否意外停在兼容模式（它会强制跳过缓存）。是的话切回移动版或自动。

**要报告一个站打不开** → 更多菜单 → 网页诊断，把面板内容截图。里面有系统 WebView 版本——「这个站不支持」很多时候其实是关于系统 WebView 的判断。

---

## 7. 相关代码

| 文件 | 职责 |
| --- | --- |
| `data/BrowserConfig.kt` | `SiteConfig` / `BrowserModeProfile` / `ResolvedSiteConfig` / `profileFor()` / `resolve()` —— 纯数据与解析 |
| `data/Model.kt` | `BrowserMode` 枚举（带 `storageName`，用于 Room 持久化） |
| `browser/BrowserConfigManager.kt` | `BrowserSettingsPlan` / `planFor()` / `applySettings()` / `applySiteConfig()` / UA 模板与品牌标记 |
| `browser/AutoMode.kt` | `CompatibilityVerdict` / `classifyFailure()` / `AUTO_LADDER` / `AutoModeState` / `shouldFollowNewWindow()` |
| `browser/LumoWebView.kt` | WebView 子类：`onCreateWindow`、`onShowFileChooser`、`onPermissionRequest`、`setDownloadListener`、渲染进程崩溃恢复、错误页注入 |
| `browser/BrowserState.kt` | `BrowserError` / `ErrorKind` / `PageDiagnostics` / `buildDiagnostics()` |
| `ui/BrowserScreen.kt` | 模式菜单、诊断面板、AUTO 状态机驱动、沉浸式 chrome |
| `ui/EditorScreen.kt` | 每网址设置的表单入口 |
| `ui/SettingsScreen.kt` | 全局默认模式、自动切换开关、全局文字缩放 |
| `data/Database.kt` | `MIGRATION_1_2`（新增 8 列） |

### 测试覆盖

| 测试类 | 覆盖 |
| --- | --- |
| `BrowserModeTest` | 四个 profile **互不相同**（防「兼容」退化成「移动版」的别名）；`Auto` 解析成 `Mobile` 的 profile；桌面版模式下 `desktopSiteEnabled` 为真 |
| `AutoModeTest` | 阶梯顺序与上界；`classifyFailure` 的守卫顺序（404 与证书错误**永不** `Switch`）；状态机不变量；`cancelled()` 后全部 no-op |
| `BrowserConfigManagerTest` | UA 解析优先级；`null` 表示平台默认而非副本；品牌标记幂等；桌面 UA 从本机 Chrome 版本拼出；文字缩放 clamp 到 50..200 |
| `UrlToolsTest` | 可浏览性判定（`http` / `https` 之外一律不可浏览） |
| `AppFlowSmokeTest` | 真机路径：启动 MainActivity、点卡片进浏览器、工具列沉浸与唤出 |
