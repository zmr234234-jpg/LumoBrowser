# 网站跳转开关与外部移交的行为与策略（v1.06）

本文对应 v1.05 的第 ④ 项（右上角三个小点菜单里的「网站跳转」开关）与 v1.06 的 ①②③ 项（网页跳 App、开关真实生效、跳转判定）。它说明**开与关各自会发生什么**、**跨站是怎么判定的**、**这个判定在哪个方向上是故意不精确的**、**一个地址离开应用之后由谁负责**、以及**哪些结论还没有在真机上验证**。

网页本身的导航分类（`target="_blank"`、`window.open`、`tel:` / `mailto:` 等）在 v1.04 就已定型，见 [`WEB_NAVIGATION.md`](WEB_NAVIGATION.md)。

**v1.06 修掉的两个根因**（症状都是「点了没反应」或「说不行、其实行」，详见第 4.1 节）：

- **开关把地址交出去之后，启动器在意图建立之前就把它否掉了。** `ExternalLauncher.build` 从 `externalLinkOffer` 开始，而那个函数只对策略判定为 `ConfirmExternal` 的地址回答，`http`/`https` 恰恰不是——它是 `InApp`。于是开关移交出去的每一个网页地址都在**意图还不存在**的时候就被拒绝，回来后变成 `Unusable`，界面告诉用户「这台设备上没有其他浏览器可以打开该地址」，而设备上明明装着浏览器。
- **启动前先查一次包管理器，查不到就不启动。** Android 11+ 的包可见性会过滤 `queryIntentActivities`，而 `startActivity` **不受过滤**。一个真实存在、真能启动的浏览器可以被一次看不见它的查询否掉。现在查询只决定「值不值得弹选择器」，启动一律尝试，`ActivityNotFoundException` 才是「没有处理器」。

---

## 1. 两个状态

| 链接类型 | 开关**关**（默认） | 开关**开** |
| --- | --- | --- |
| 同站 http/https 链接（含二、三级页） | LUMO 内置 WebView | **LUMO 内置 WebView（不变）** |
| 跨站 http/https 链接 | LUMO 内置 WebView | **交给系统浏览器** |
| `target="_blank"` / `window.open` 打开的同站页面 | LUMO 内置 WebView | LUMO 内置 WebView |
| `target="_blank"` / `window.open` 打开的跨站页面 | LUMO 内置 WebView | 交给系统浏览器 |
| `tel:` / `mailto:` / `market:` / `intent:` / 其他自定义协议 | 既有底部确认面板 | **既有底部确认面板（不变）** |
| iframe（子框架）内的链接 | LUMO 内置 WebView | **LUMO 内置 WebView（不变）** |
| 解析不出来的地址 | LUMO 内置 WebView | **LUMO 内置 WebView（不变）** |

**默认是关。** 升级到 1.05 不会改变任何人既有的浏览方式——这正是 `browserCrossSiteExternal` 默认 `false` 的理由。

「交给系统浏览器」的意思是：地址交给 Android 自己的解析器，弹出的是**系统自己的选择器**。产生那份清单的是系统，所以它恰好就是这台设备上实际安装的浏览器，没有任何条目被凭空造出来，也没有指名一个用户没装的浏览器。取消选择器 = 什么都不发生，当前页面原样留着。

**这个开关只在两个浏览器之间移动网页，不改变「打开 App」的方式。** `tel:` / `mailto:` 之类的处理路径完全不受它影响，见第 4 节。

---

## 2. 跨站怎么判定

`app/src/main/java/com/lumobrowser/app/browser/WebNavigationPolicy.kt` 里四个纯函数：

| 函数 | 作用 |
| --- | --- |
| `hostOf(url: String?): String?` | 取出 http/https 地址的主机名（小写），其他一律 `null` |
| `siteOf(host: String): String` | 把一个主机名归约成比较用的 key |
| `sameSite(one: String?, two: String?): Boolean` | 两个地址是否同站 |
| `leavesForCrossSite(from: String?, to: String?, enabled: Boolean, isForMainFrame: Boolean = true, isRedirect: Boolean = false): Boolean` | 这条链接该不该出去 |

政策本体只有一行（v1.06 起多了两个参数，理由见 2.5 / 2.6）：

```kotlin
fun leavesForCrossSite(
    from: String?,
    to: String?,
    enabled: Boolean,
    isForMainFrame: Boolean = true,
    isRedirect: Boolean = false,
): Boolean = enabled && isForMainFrame && !isRedirect && isWebAddress(to) && !sameSite(from, to)
```

`enabled` 为 `false` 时对**所有**地址都返回 `false`。这是刻意的：开关不可能只生效一半，因为不存在第二个能跟菜单说法不一致的地方。

### 2.1 `hostOf` 是手写的

不用 `java.net.URI`：它会对路径里有空格、或主机名含非 ASCII 字符的完全正常地址抛 `URISyntaxException`，而用户真的点下去的那个链接，绝不能因为里面有个字符就被拒绝。

更不能用 `android.net.Uri`：这个文件要在没有 Android 类的 JVM 上跑单元测试。

手写解析要处理的坑都处理了：

- 只有 `http` / `https` 算有主机；`ftp:` / `mailto:` / `tel:` / `file:` / `javascript:` / `about:` 一律 `null`；
- `http:example.com`（没有 `//`）返回 `null`——它是合法地址，含义和 `http://example.com` 不同，猜它指哪台主机不值得；
- 端口不算主机名的一部分；
- **userinfo 按最后一个 `@` 切**：`https://example.com@evil.com/x` 的真主机是 `evil.com`。按第一个 `@` 切会读成 `example.com`，那就等于把一个页面指向别处的链接留在应用里；
- IPv6 字面量去掉方括号、保留冒号；方括号没有闭合就返回 `null` 而不是猜；
- 结尾的根点（`example.com.`）去掉；大小写不敏感。

### 2.2 `siteOf` 用「后两段标签」近似

| 输入 | key |
| --- | --- |
| `www.example.com` | `example.com` |
| `a.b.c.example.com` | `example.com` |
| `localhost` | `localhost` |
| `192.168.1.1` | `192.168.1.1`（IP 字面量自成一个 key） |
| `2606:4700::1111` | `2606:4700::1111` |

IP 字面量不参与后缀归约，因为没有后缀规则适用于地址：`192.168.1.1` 和 `192.168.1.2` 确实是两个站点。

### 2.3 这个近似在哪个方向上错，是刻意的

**`a.co.uk` 和 `b.co.uk` 会被判为同站。** 这需要一份 public suffix list 才能做对，而这个项目没有。

错的方向是被选过的：

- 判成**同站**（假阴性）的代价 = 一条本可以交出去的链接留在了 LUMO。用户仍在读他想读的东西，只是没换浏览器。
- 判成**跨站**（假阳性）的代价 = 在用户读到一半时把他踢出应用去另一个浏览器。

**低报可以承受，高报不行。** 所以宁可少报。

### 2.4 解析不出来 = 同站

```kotlin
val a = hostOf(one)?.let(::siteOf) ?: return true
```

任一边解析不出来就回 `true`（同站）。一个读不懂的地址，永远不构成「把用户送出应用」的理由。一次导航要么是**有把握的跨站**，要么留在原地。

### 2.5 只有主框架参与

`isForMainFrame` 只用在跨站判定这一处。iframe 里的链接不会触发跨站移交——一个嵌入的广告或小组件不该把用户从页面里带走。

但**协议处理不受影响**：`isForMainFrame` 和 scheme 分类是两件独立的事，所以 iframe 里的 `tel:` 照样会走确认面板。

### 2.6 服务器发的重定向不算「离开」

`isRedirect` 取自 `WebResourceRequest.isRedirect`。**它为真时不移交，即使地址确实跨站。**

用户点的是他看到的那个链接，服务器回了一个别的地址。跟着它走出应用，等于在一次**用户已经做完的**点击底下把页面换掉，再把它报告成一次用户从没选过的浏览器切换。而且重定向链里 `from` 描述的是**正在重定向的那个文档**，不是用户正在读的那个，跨站比较此时没有可靠的基准。

这一条同时钉住了简报第 ③ 项要求的另一半：**普通页面重定向、跨域跳转、站内导航都不得被误判成「打开 App」**——它们只是导航，除非那是用户自己点下的、有把握的跨站链接。

### 2.7 判定不出来就不移交

`leavesForCrossSite` 是个纯函数，五个条件**全部**满足才为真：开关开着、是主框架、不是重定向、是 http/https、且确实跨站。任何一项读不出来（`from` 解析不出主机、`to` 不是网页地址、请求信息缺失），结果都是 `false`，链接留在 LUMO 里。

**「拿不准」永远不构成把用户送出应用的理由。** 少交出去一条链接的代价是用户还在读他想读的东西；多交出去一条的代价是在用户读到一半时把他踢出应用。

---

## 3. 判定发生在哪里

`app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` 里，`dispatchNavigation(...)` 是链接点击与弹窗地址**共用的唯一入口**：

```kotlin
NavigationKind.InApp -> {
    val handoff = onCrossSiteHandoffRequested
    if (handoff != null && leavesForCrossSite(from, rawUrl, externalizeCrossSite, isForMainFrame, isRedirect)) {
        handoff(rawUrl)
        return true
    }
    inApp?.invoke()
    return false
}
```

`inApp` 只在弹窗那条路上非空（`{ load(url) }`），**普通链接点击时它是 `null`，函数返回 `false`，于是由 WebView 自己完成这次导航**。这就是「不会重复载入」的机制：要用回呼去接管时必须回 `true`，而在 LUMO 里只有「交出去」才回 `true`。

两个入口走同一个函数，是为了让它们**不可能对「哪些协议留在应用里」产生分歧**。

`from` 取的是**本体 WebView 的当前地址**，不是弹窗那个临时 WebView 的——临时 WebView 的文档按构造就是 `about:blank`，没有站点可比。

**回呼没有接线时会落回 `inApp`。** 所以把开关打开绝不可能让链接变成死的。

`externalizeCrossSite` 是在 `AndroidView` 的 `update` lambda 里读的，不是在 `factory` 里：

```kotlin
webView.externalizeCrossSite = crossSiteExternal
```

`factory` 每个 WebView 只跑一次，`update` 在建立时和之后都会跑。用户可以在页面开着的时候去拨那个开关，在构造时抓一次值会让菜单说一套、页面做另一套。

---

## 4. 外部协议：两种状态下一模一样

`tel:` / `mailto:` / `market:` / `intent:` 以及任何自定义协议**从来不经过** `leavesForCrossSite`。它们在更早的 `classifyNavigation` 里就被判成 `ConfirmExternal`，走既有的底部确认面板——**开关开或关都一样**。

这是有意的：这个开关移动的是**网页**，不是「打开 App 的方式」。

真正去启动 App 的是 `app/src/main/java/com/lumobrowser/app/util/ExternalLauncher.kt`。它是**全应用唯一一处**「网页内容可以导致应用之外发生一件事」的地方，所以几要点都是硬规则：

- **绝不用 `Intent.parseUri`。** 它会听 `component=` 和 `launchFlags=`，那是一个 intent 重定向向量：页面据此点名另一个应用内部的类。自写的 `buildFromIntentUrl` 直接拒绝这两个字段，一个 `intent:` 地址只能作为普通的 action/data/package 请求到达目标应用，或者干脆不到达。
- **绝不在启动之前先查一次包管理器、查不到就放弃。** `queryIntentActivities` 在 Android 11+ 会被 `<queries>` 过滤，而 `startActivity` **不被过滤**。查询现在只决定一件事：**值不值得弹选择器**（看不见任何候选时不弹——一个空的选择器是用户必须退出的死路，而平台自己的解析不受可见性限制，它找到多个处理器时会自己弹）。启动一律尝试，`ActivityNotFoundException` 才是「没有处理器」。
- **绝不用一次查询去宣称「这台设备上没有应用能处理该地址」。** 面板曾经显示这句话，1.06 删掉了：那是包可见性**得不出**的结论，而它正下方的确认按钮**故意保持可按**，两者会互相打脸。面板现在只说「打开方式：某个具名应用」，或者诚实的「由你选择的应用」；真正的答案由**尝试本身**在事后给出（`NoHandler` → 「此设备上没有可处理该地址的应用。」）。
- **用 `Intent.EXTRA_EXCLUDE_COMPONENTS` 把自己排除。** 否则「用其他应用打开」可能又回到 LUMO，点下去像按钮没反应。LUMO 自己的 Activity 今天不声明任何 `VIEW` 过滤器，这条是留给以后加过滤器时不会把「用别的浏览器打开」变成「再打开 LUMO 一次」。
- **候选多于一个时 `handlerLabel()` 回 `null`**，面板说「你来选一个应用」，而不是替用户发明一个赢家。回 `null` 时**不携带任何声明**——包括不声明「没有」。
- 结果是一个封闭类型：`Opened` / `NoHandler` / `Unusable` / `Failed(message)`。

### 4.1 两条根因：为什么以前点了没反应

**A. 网页地址在意图建立之前就被否掉了。** `build()` 以前从 `externalLinkOffer()` 开始，而那个函数只对策略判定为 `ConfirmExternal` 的地址回答——`http`/`https` **不是**那一种，它是 `InApp`。于是开关移交出去的每一个网页地址都撞在 `?: return null` 上，回来是 `Unusable`，界面说「这台设备上没有其他浏览器可以打开该地址」，而设备上装着浏览器。修法是把网页地址当作它本来的样子处理：`ACTION_VIEW` + 地址，不需要任何「offer」批准。

**B. 启动前先查一次，查不到就不启动。** 见上：可见性过滤会把一个真实存在、真能启动的处理器藏起来。修法是**只把查询当作「要不要弹选择器」的输入，不再当作闸门**。

两条的形状是同一个：**用一次看不见全貌的查询，去替一次本来会成功的尝试下结论。** 1.06 的规则因此是一条：**可能性由尝试回答，查询只负责优化呈现。**

### 4.2 四条「开到别处」的路，现在是同一条

开关移交、错误页的「用其他浏览器打开」、工具栏动作、重试条——在用户眼里是同一个请求：**这里读不了，去别处读**。它们以前有**两份各自实现**，于是一个应用可以一边拒绝交出一个它明明有浏览器可用的地址，一边在真没有浏览器时安静地什么都不启动。

现在它们全部经过 `ui/BrowserScreen.kt` 里的同一个 `openElsewhere(context, url, strings)`，它调 `ExternalLauncher.launch(..., alwaysChoose = true)` 并给出该显示的提示（`null` = 已有别的应用接手，不清掉上一则提示）。`util/FileIo.kt` 里那个只会发一条 `ACTION_VIEW`、什么都不回报的 `openExternally` 已删除。

**开关移交失败时会回落到内置 WebView。** 移交是在「另一个应用会接手」的前提下取消这次导航的；如果没有任何应用接手，停在这里就会留下一条**看得见地点了没反应**的链接——也就是本版要修的那个报告。所以它改在内置 WebView 里打开，同时给出提示。这也正是简报要求的「提供返回内置浏览器的选项」：失败回落本身就是那个选项。

`alwaysChoose = true` 在这里的意思是「**即使你以前给这类地址设过默认应用，也请让我选一次**」，因为开关承诺的就是「一个你来选的浏览器」。但**看得见的候选只有一个时不弹选择器**——那时选择器里没有选择，只有一个浏览器前面挡着一次多余的点击。


跨站移交本身也复用同一个启动器，只是**没有确认面板**：用户把开关打开，本身就已经表达了「这些链接交出去」的意愿，每一条再问一次就是简报里点名的第二个弹窗。

启动结果的反馈分两条路，因为两条路的**知情程度不同**：

**确认面板（`tel:` / `mailto:` / 自定义协议）**——用户刚刚按下确认，每一句话都该告诉他结果：

| 结果 | 界面行为 |
| --- | --- |
| `Opened(label)` | 提示「请选择应用」（`external_link_opened_chooser`）。这是祈使句，不是「已打开」的虚假陈述：`startActivity` 成功只代表选择器被显示出来了，用户还可能取消 |
| `NoHandler` | 提示「此设备上没有可处理该地址的应用。」（`external_link_app_none`）——**这是尝试之后才知道的事实** |
| `Unusable` | 不启动（地址本身不可用），面板的确认按钮本来就不可按 |
| `Failed(message)` | 提示「无法在其他应用打开该地址：…」 |

**直接移交（开关、错误页、工具栏、重试条，即 `openElsewhere`）**——用户表达的意愿是「去别处读」，所以只有坏消息才打断他：

| 结果 | 界面行为 |
| --- | --- |
| `Opened` | **什么都不做**——没有坏消息就不要清掉上一则提示，也不要告诉用户他刚做过的动作完成了 |
| `NoHandler` / `Unusable` | 提示「这台设备上没有其他浏览器可以打开该地址。」并**回落到内置 WebView 打开** |
| `Failed(message)` | 提示「无法在其他应用打开该地址：…」并**回落到内置 WebView 打开** |


---

## 5. 菜单与持久化

### 5.1 菜单项

`app/src/main/java/com/lumobrowser/app/ui/HomeScreen.kt` 的右上角三点菜单里，导入项之后是第四项：

- **两行文字**：标题 + 一行小字说明。只给一个勾的话，用户必须先点开才知道它做什么，而那个项目存在的意义正是避免这件事。
- 前置图标 `LumoIcons.OpenExternal`。
- 开启时后置一个由主题强调色着色的勾选图标，关闭时没有。

`Surfaces.kt` 的 `LumoMenuItem` 为此新增了一个可选的 `trailingIcon` 参数（`DropdownMenuItem` 原生支持）。

菜单结构没有变，只是多了一项；三个主题下都自适应。

### 5.2 持久化

| 层 | 位置 |
| --- | --- |
| 数据模型 | `data/Model.kt` 的 `UserPreferences.browserCrossSiteExternal: Boolean = false` |
| 存储 | `data/SettingsRepository.kt` 的 `booleanPreferencesKey("browser_cross_site_external")` |
| ViewModel | `ui/MainViewModel.kt` 的 `setBrowserCrossSiteExternal(value)` |

`setBrowserCrossSiteExternal` 接受的是**绝对设定**，翻转在调用端做（`LumoApp.kt` 里传 `!preferences.browserCrossSiteExternal`），这样「现在到底该是什么状态」只有一个来源。

设置是 DataStore 偏好，**立即生效、重启恢复**。新增字段有默认值，所以升级不会因为多了一个 DataStore 键而启动失败，旧收藏/历史/主题/设置继续可用。

菜单显示的勾与 `LumoWebView.externalizeCrossSite` 读的是**同一个偏好**，所以菜单说法与实际行为不可能不一致。

**不存在语义重复的冲突开关**：这是唯一一个控制跨站移交的偏好，没有第二个地方能覆盖它。

---

## 6. 自动化测试

两个纯 JVM 测试类，都不需要设备：

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `app/src/test/java/com/lumobrowser/app/browser/CrossSiteLinkPolicyTest.kt` | **27**（1.05 为 25） | 主机解析、站点归约、`leavesForCrossSite` 的五个条件 |
| `app/src/test/java/com/lumobrowser/app/util/ExternalLauncherTest.kt` | **16**（1.06 新增） | 意图构建、选择器策略、失败分类 |

`CrossSiteLinkPolicyTest` 里几条是专门钉住「故意不精确」这个决定的：

- `a two-label key merges co uk domains and that is the safe direction` —— 把 2.3 节的已知错误写成断言，免得下一个人把它「修好」而把错误方向翻过来；
- `an unreadable address is treated as the same site` —— 钉住 2.4 节的保守方向；
- `the host after an at sign is the real host` —— 钉住 userinfo 按最后一个 `@` 切；
- `a path with spaces is still read, not rejected` —— 注释里写明这就是手写解析器的原因；
- `the switch on only ever moves web addresses` —— 钉住第 4 节：开关不许开始宣称 `tel:` / `mailto:` / `intent:`；
- **`a sub-frame navigation never leaves the app`（1.06 新增）** —— 钉住 2.5 节；
- **`a server redirect never leaves the app`（1.06 新增）** —— 钉住 2.6 节。

`ExternalLauncherTest` 里两条是本版两条根因的回归测试：

- **`webAddress_isBuiltEvenThoughTheLinkPolicyDeclinesToOfferIt`** —— 同一个地址，`externalLinkOffer(url)` 必须是 `null`，而 `ExternalLauncher.build(url)` 必须非空。**根因 A 一旦复发，这一条立刻失败**：它把「不提供 offer」与「不能构建意图」这两件曾被混为一谈的事分开钉住。
- **`invisibleHandler_isStillHandedTheAddress`** —— 先用一次 `queryIntentActivities` 断言「这个地址在这里看不见任何处理器」，再断言 `launch()` 仍然返回 `Opened`。钉住根因 B。

测试替身也换了：`addBrowser` 不再用已弃用的 `addResolveInfoForIntent` 往映射里塞一条记录，而是 `addActivityIfNotPresent` + `addIntentFilterForActivity` 建**真实的 intent filter**。区别是实质性的——旧写法什么都不验证，即使应用向平台要一个任何浏览器都不可能接受的 intent 也照样通过。

同一目录下的 `WebNavigationPolicyTest`（34 例）覆盖 v1.04 就有的导航分类，没有被改动，仍然全过。

---

## 7. 没有验证的部分

**没有在真机上验证。** 这台环境没有设备也没有模拟器，用户报告的那台手机不在本环境内可达。

已经验证的：编译通过（`e:` 0 条、`w:` 0 条）；`CrossSiteLinkPolicyTest` 27 例、`ExternalLauncherTest` 16 例、`WebNavigationPolicyTest` 34 例、`SettingsRepositoryTest` 12 例全过。

没有验证的，需要真机手工确认：

- **一次真实的网页 → App 点击。** 从真实网页点一个 `tel:` / `mailto:` / App Link，观察「点了没反应」是否真的消失——**这一条只能由真机回答**，静态审查不能替代。
- **系统选择器实际弹出的样子。** 本环境无法触发一次真实的多浏览器选择器，也无法确认取消后页面是否原样保留（代码路径上没有 `loadUrl` 被调用，但那是静态结论）。
- **单浏览器设备上的行为。** 只有一个浏览器时系统可能直接启动而不弹选择器——这属于 Android 解析器的行为，本项目不干预，但本环境无法观察。
- **`intent://` 地址真的能拉起目标 App。** 本环境没有任何目标应用可装、可启动。
- **`NoHandler` 与 `Failed` 两条提示的实际文案在窄屏上的换行。**
- **菜单在三个主题下、以及小屏 + 大字体下的实际排布。**

---

## 8. 相关文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/browser/WebNavigationPolicy.kt` | `hostOf` / `siteOf` / `sameSite` / `leavesForCrossSite` |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | `dispatchNavigation`、`externalizeCrossSite`、`onCrossSiteHandoffRequested` |
| `app/src/main/java/com/lumobrowser/app/util/ExternalLauncher.kt` | 构建意图、决定要不要弹选择器、启动并回报结果（`build` / `handlerLabel` / `launch`） |
| `app/src/main/java/com/lumobrowser/app/ui/BrowserScreen.kt` | `openElsewhere`（四条「开到别处」的路）、接上移交回呼、把开关推进 `update` |
| `app/src/main/java/com/lumobrowser/app/ui/ExternalLinkSheet.kt` | 底部确认面板（不再声明「没有应用能处理」） |
| `app/src/main/java/com/lumobrowser/app/ui/HomeScreen.kt` | 三点菜单第四项 |
| `app/src/main/java/com/lumobrowser/app/data/Model.kt`、`data/SettingsRepository.kt` | 偏好与持久化 |
| `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt` | `setBrowserCrossSiteExternal` |
| `app/src/main/java/com/lumobrowser/app/util/FileIo.kt` | 分享与复制地址；**1.06 起不再包含 `openExternally`** |
| `app/src/test/java/com/lumobrowser/app/browser/CrossSiteLinkPolicyTest.kt` | 27 例 |
| `app/src/test/java/com/lumobrowser/app/util/ExternalLauncherTest.kt` | 16 例 |
