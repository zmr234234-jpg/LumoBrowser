# 广告屏蔽：实现、判定与能力边界（v1.07）

> 本文写的是 v1.07 实际交付的广告屏蔽，**不是**它能做到的宣传。凡是这里说"拦得住"的，都有对应的代码位置和测试；凡是没做、做不到、或没有验证过的，都单独写在 §1、§9 和 §11，不藏在正文里。

---

## 1. 一句话与边界

**做法**：在已有的 WebView 架构里，于 `WebViewClient.shouldInterceptRequest()` 处对**子资源请求**做一次本地规则匹配。命中就**不发网络请求**，直接返回一个本地构造的最小响应。

**不做**：不改系统代理、不装 VPN、不做 HTTPS 中间人、不注入 JS 改页面 DOM、不申请任何新权限（v1.07 的 `AndroidManifest.xml` 相对 v1.06 **没有新增权限**）。

**因此拦不住**（这是架构决定的，不是没做完）：

| 拦不住的东西 | 原因 |
| --- | --- |
| 与网页同域的第一方广告（如 `news.example.com/ads/…`） | 主机规则命中不了自己的域名；路径规则按设计只对第三方子资源生效（§5 第 4 条） |
| 页面加载后由 JS 动态插入 DOM 的广告位 | 那是页面内部的事，`shouldInterceptRequest` 只看得到它后来发出的请求 |
| 由页面脚本弹出的新窗口广告（`window.open`） | 属于导航而不是子资源，本应用对导航的处置见 [`WEB_NAVIGATION.md`](WEB_NAVIGATION.md)；LUMO 会让它留在应用内或交给系统浏览器，而不是静默丢弃 |
| 视频站的前贴片广告 | 与视频流同域、同一批请求 |
| 服务端渲染进 HTML 的赞助内容、原生广告文案 | 请求层面看不出与正文的区别 |
| 已经缓存过的资源 | WebView 命中缓存时不会调用 `shouldInterceptRequest` |

**没有做在线规则更新**：本版没有可用的规则更新源，因此**不伪造**"检查更新""规则已是最新"这类界面。规则随 APK 提供，首次启动、离线都可用；设置页显示的版本号与日期是规则文件自己的表头，不是应用编造的（§4）。

---

## 2. 文件与职责

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/browser/adblock/AdBlockRules.kt` | 规则文件的**解析**与**匹配**（纯 Kotlin，无 Android 类型，可在 JVM 上快跑） |
| `app/src/main/java/com/lumobrowser/app/browser/adblock/AdBlockPolicy.kt` | **什么时候允许匹配生效**：主文档永不出局、例外优先、路径规则只限第三方、协议与主机归一化、主机判定缓存 |
| `app/src/main/java/com/lumobrowser/app/browser/adblock/BlockedResponses.kt` | 被拦的请求**回什么字节、按什么类型回** |
| `app/src/main/java/com/lumobrowser/app/browser/adblock/AdBlockManager.kt` | 生命周期与状态：读规则、开关、计数、持久化、失败降级；`AdBlockState` 是它的对外事实 |
| `app/src/main/java/com/lumobrowser/app/browser/adblock/BundledAdBlockRules.kt` | 从 `assets/adblock/rules.txt` 读规则（`withContext(Dispatchers.IO)`） |
| `app/src/main/assets/adblock/rules.txt` | 随包的规则集：7594 字节、360 行、56 行注释、**246 条主机规则 + 14 条例外 + 23 条路径片段**，`version: 2026.10.11` / `updated: 2026-10-11`，解析失败条目 0 |
| `app/src/main/java/com/lumobrowser/app/browser/LumoWebView.kt` | 唯一的接线点：`shouldInterceptRequest`（`:782`）读 `adBlocker`（`:786`），命中则 `BlockedResponses.describe(url, accept)`（`:819`）；页面开始加载时 `resetPageCount()`（`:840`） |
| `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` | 设置页的广告屏蔽区：开关、说明、本页/累计计数、规则版本与日期、被忽略条目、失败原因、"关闭并刷新本页" |
| `app/src/main/java/com/lumobrowser/app/data/SettingsRepository.kt` | 持久化键 `ad_block_enabled`（`:91`）、`ad_block_total_blocked`（`:92`）；写入 `setAdBlockEnabled`（`:300`）与 `addAdBlockBlocked`（`:310`） |

**过滤逻辑没有一行写在 `Activity` 或 Compose 里**：UI 只读 `AdBlockState`。

---

## 3. 一次请求的完整路径

```
WebView 子资源请求
   └─ LumoWebView.shouldInterceptRequest()            LumoWebView.kt:782   （WebView 工作线程）
        ├─ adBlocker ?: return null                     :786  开关关掉或未装配 → 直接放行
        ├─ AdBlockManager.shouldBlock(url, pageHost, isForMainFrame)   AdBlockManager.kt:129
        │     └─ AdBlockPolicy.shouldBlock(rules, url, pageHost, isForMainFrame, cache)   AdBlockPolicy.kt:119
        │          ① 主文档？→ 放行      ② 非 http/https？→ 放行
        │          ③ 例外命中？→ 放行     ④ 主机规则命中？→ 拦截（走 DecisionCache）
        │          ⑤ 第三方？否 → 放行     ⑥ 路径片段命中？→ 拦截
        └─ 命中 → BlockedResponses.describe(url, accept)  → 本地 WebResourceResponse
```

* **主线程上没有任何匹配工作**：`shouldInterceptRequest` 本身就在 WebView 的工作线程；`shouldBlock` 不挂起、不 I/O、只读内存里的 `Set`/`List` 与一个 LRU。
* **不在 `shouldInterceptRequest` 里碰 UI**：唯一被写的是两个 `AtomicLong` 计数和一个 `MutableStateFlow`（`AdBlockManager.record()` / `publishCounts()`）。
* **规则只解析一次**：`AdBlockManager.ensureLoaded()`（`:120`）在 `loadJob == null` 时才起一个协程；解析在 `Dispatchers.Default` 上做（`withContext(Dispatchers.Default) { AdBlockRules.parse(text) }`），不占主线程。**不会每次请求重读规则文件。**
* **主机判定带缓存**：`AdBlockPolicy.DecisionCache`（`AdBlockPolicy.kt:149`）是一个 512 条的 LRU（`LinkedHashMap` accessOrder=true），键是归一化后的主机名；**规则集一变就整体清空**（每一个条目都是旧规则集的答案）。例外的判定不经过缓存（`AdBlockPolicy.kt:132`，它在 `:134` 的缓存之前返回），因为它必须在任何缓存之前生效。

---

## 4. 规则格式与随包规则集

解析器接受现实里规则文件的两种写法，其余一律**计数并丢弃**，不猜：

```
# 注释，# version: X / # updated: Y 是唯一有语义的两行（设置页显示它们）
example.com            拦截该主机及其所有子域
@@example.com          例外：永不拦截（先于一切规则判定）
/fragment              路径片段，大小写不敏感，只对第三方子资源生效；短于 4 个字符的丢弃
0.0.0.0 example.com    hosts 文件形式，同样接受
||example.com^         ABP 域名锚点写法，会被还原
$third-party 等修饰符  直接丢弃该条目并计数（见下）
```

**`$` 修饰符为什么丢弃而不是忽略**：本引擎只实现"主机 + 子域"这一种规则。把 `$third-party,script` 当成普通主机规则用，等于让一条规则在它的作者明确排除的场景里生效——那比不写这条规则更糟。丢弃会进 `ignoredRules`，设置页看得到。

**收下的必须像一个主机名，否则一律计数丢弃**：单独一行的 `0.0.0.0` / `127.0.0.1`（hosts 行掉了主机名）、`localhost`、`com` 这类单标签名、超过 253 字符、以及含空格、通配符或 `[a-z0-9.-_]` 之外字符的行都不收（`normalizeHostRule`，`AdBlockRules.kt:190`）。

**路径片段有一个长度下限**：短于 `MIN_PATH_FRAGMENT_LENGTH = 4`（`AdBlockRules.kt:105`）的片段如 `/a`、`/**` 会被丢弃并计数。路径匹配是对每个第三方地址做**子串**比对，而 `/a` 出现在 `/assets/`、`/api/`、`/ajax/` 里，`/ad` 出现在 `/admin/`、`/adapter` 里——短片段不是窄规则，是极宽的规则。

**规则集里刻意不含的域名，以及原因**（逐条写在 `assets/adblock/rules.txt` 的注释里）：

* `googletagmanager.com`——标签容器，很多站点的 cookie 同意横幅和 A/B 分流也走它，拦掉可能留下一个**关不掉的浮层**；它触发的广告标签指向的广告主机本来就被拦。
* `connect.facebook.net`——同时是 Facebook 登录 SDK，拦掉会弄坏部分站点的登录；简报把登录排在这类追踪之前。
* `imasdk.googleapis.com`——Google IMA 播放器把 SDK 加载失败当作播放失败，拦掉可能连视频本身一起坏掉。
* `fonts.googleapis.com`、`ajax.googleapis.com`、`cdn.jsdelivr.net`、`cdnjs.cloudflare.com`、`*.cloudfront.net`、`*.amazonaws.com`——通用基础设施，**任何**一条针对它们的规则都会弄坏普通网页。
* 短链站（`adf.ly`、`shorte.st`、`ouo.io`、`adfoc.us`）——那是一个用户主动要去的页面，拦掉等于把链接换成空白。

被点名的这些域名同时在文件底部的**例外列表**里，这样以后改规则也不会误伤。

---

## 5. 判定策略：六条硬规则，顺序固定

`AdBlockPolicy.shouldBlock()`（`AdBlockPolicy.kt:119`）按顺序执行，任何一步说"放行"就结束：

1. **规则集为空/为 null → 放行。** 加载失败降级成 `AdBlockRules.Unavailable`，它是一个正常值而不是错误状态。
2. **主文档永不参与判定**（`isForMainFrame`）。不是"一般不拦"，是**永不**：拦住文档就不是过激的广告过滤，而是坏掉的浏览器。调用方传 `isForMainFrame`，这一层直接拒绝，将来的重构也没法把主文档送进匹配器。
3. **协议必须是 http/https。** `data:`、`blob:`、`file:`、`about:` 等一律放行。
4. **例外优先。** `@@` 列表在任何拦截规则之前判定，包括路径片段。
5. **主机规则**：`example.com` 命中 `example.com` 及 `a.b.example.com`；后缀比对是**按标签对齐**的，`notexample.com` **不会**命中 `example.com`（`AdBlockRules.matchesAny`，`:229`）。主机名先归一化（`AdBlockPolicy.hostOf`，`:33`）：切掉 scheme，在**最后一个** `@` 处切掉 userinfo（`user:p@ss@ads.example.com` 归一到 `ads.example.com`，否则被拦的主机就被当成 userinfo 丢在一边），IPv6 字面值先解方括号再切端口（`[2606:4700::1111]:443` → `2606:4700::1111`；直接按第一个冒号切会读成 `2606`），最后去掉**尾端根点**（`ads.example.com.` 是合法的绝对域名，也是最省事的绕过手段）并小写；括号没有闭合（`http://[`）时不返回半个地址，直接当成读不出主机名。
6. **路径片段只对第三方请求生效**（`isThirdParty`）。`/banners/` 完全可能是一个网站自己的图片目录，所以它只在"请求主机与页面主机不属于同一个站点"时才参与判定。站点判定取主机名的**后两段**（`siteOf`，`:96`；实现是"从右数第二个点之后的全部"，所以 `cdn.www.example.com` 与 `example.com` 算同一个站点）——**没有 public suffix list**，所以 `bbc.co.uk` 与 `ads.bbc.co.uk` 会被算成同一个站点。这是**安全方向的错**：结果是该请求跳过路径规则、只由主机规则判定，页面还能用；反过来猜就会让 `/banners/` 拦掉站点自己的配图。主机规则**不受**这一条限制：例外与主机规则在先，第一方请求里被点名的广告主机照样拦（`AdBlockPolicy.kt:132-135` 在 `:137` 之前）。

**归一化与协议处理全部手写**，不用 `java.net.URI` / `android.net.Uri`：任何解析异常都会变成"这个请求被拦了"，而这个函数跑在 `shouldInterceptRequest` 里。每一处失败路径都返回空主机名，**空主机名不匹配任何规则**——失败方向永远是"放行"。

---

## 6. 被拦的请求回什么字节

`BlockedResponses.describe(url, accept)`（`BlockedResponses.kt:53`）只有两种形状，选择标准是**不要比广告更糟**：

* **图片请求 → 真的 1×1 透明 PNG**，类型强制为 `image/png`。对横幅返回"什么都没有"会让页面重排：槽位保留 CSS 尺寸但被替换元素塌陷，所以诚实的答案是一个能解码的最小图片。类型强制为 PNG 是因为**字节流和它声明的类型必须一致**——请求要的是 `.gif` 或 `.svg` 也一样。这个常量不是"粘贴完就信"：`AdBlockResponsesTest` 每次构建都会解码它、逐块校验 CRC、确认 `IHDR` 是 1×1、位深 8、色彩类型 6（70 字节）。
* **其余请求 → 零字节，沿用请求要的类型**。空的 `application/javascript` 是一个什么都不定义的脚本，空的 `text/css` 是一张没有规则的样式表——两者都是页面本来就处理得了的普通结果；反过来把一个脚本按 HTML 回给页面，会变成控制台解析错误甚至坏掉的文档。
* 类型解析**先看扩展名、再看 `Accept` 头，最后回落到 `text/plain`**（对任何上下文都惰性，适合"猜"）。扩展名解析刻意严格（`extensionOf`，`:86`）：`?v=1.2.3` 里的点、目录名里的点、超过 5 个字符或含非 `[a-z0-9]` 的"扩展名"都不算扩展名，因为**这个答案决定内容类型**。

> **减少广告占位空白**在实现上就是这条：图片槽位拿到真实像素，脚本/样式槽位拿到空对象而不是错误。

---

## 7. 统计：哪些数字是真的

| 设置页显示 | 数据来源 |
| --- | --- |
| 本页拦截数量 | `AdBlockState.pageBlocked`，`AtomicLong`，页面开始加载时 `resetPageCount()` 清零 |
| 累计拦截数量 | `AdBlockState.totalBlocked` **持久化在 DataStore**（`ad_block_total_blocked`），跨重启保留 |
| 规则版本 / 更新时间 | 规则文件自己的 `# version:` / `# updated:` 表头；文件没写就是空，设置页不编 |
| 规则条目数 | 解析后的真实计数：主机规则 / 路径片段 / 例外 |
| 被忽略条目 | 解析时数出来的、看不懂或丢弃的行数 |
| 规则加载失败 | 只在真的读不到或解析不了时才有值，并给出原因 |

**没有"防护等级""已是最新""拦截率"这类没有数据支撑的东西。**

**累计数是有意"近似"的**：计数与落盘是解耦的——一个页面可能产生上百次拦截，而 DataStore 每 **2 秒**（`AdBlockManager.FLUSH_INTERVAL_MS = 2_000`）写一次累计增量，写失败会把增量**放回去**而不是丢掉。代价是：进程在这个窗口内被杀，最多丢 2 秒的计数。所以设置页把它写成"store 记得的累计数"，而不是精确总数。

---

## 8. 开关：关掉之后会发生什么

* 开关值存在 `ad_block_enabled`，默认**开**；改一下立即生效，**不需要重启应用**：`LumoApplication` 收集偏好流并同步 `adBlocker.enabled`。
* `AdBlockManager.shouldBlock`（`:129`）第一件事就是看 `rules`，关掉后 `AdBlockManager` 不再参与，`LumoWebView` 在 `:786` 直接放行——**新请求立刻恢复正常**。
* **已经打开的页面不会自己变**：已经被拦掉的请求不会复活。所以设置页提供一个"关闭并刷新本页"的动作，在关掉开关后刷新当前页面，让用户当场看到差别。这是**一次明确的用户动作**，不是偷偷刷新。
* 内容缺失、误拦、页面看起来怪——对应的恢复路径就是这一条。

---

## 9. 失败降级：坏规则不会挡住网页

* 规则文件读不到、内容损坏、被改坏 → `AdBlockManager.load()` 捕获后落到 `AdBlockRules.Unavailable`，并把原因写进 `AdBlockState.loadFailure`。
* `Unavailable` 的 `blockedHosts` 与 `pathFragments` 都是空的，`shouldBlock` 永远返回 `false`——**浏览体验与关掉开关完全一致**。
* **没有任何一条路径会因为这个功能崩掉或挡住页面**：`AdBlockManager` 的公开方法不向调用方抛异常，`AdBlockRulesSource.read()` 抛异常是"被支持的结果"。
* 规则文件损坏时，设置页显示"规则缺失 + 原因"，而不是假装一切正常。

---

## 10. 性能与生命周期

* 规则解析一次，且在 `Dispatchers.Default` 上；读文件在 `Dispatchers.IO` 上。
* 匹配是内存里的 `HashSet` 查找 + 一个 512 条 LRU；**不分配大对象、不挂起、不做 I/O**。
* 计数用 `AtomicLong`，落盘每 2 秒一次合并写入（不是每次拦截写一次）。
* 广告屏蔽器由**应用**持有（`LumoApplication` 的 `by lazy`），不属于任何 WebView：整个进程一份规则、一份计数、一个开关，页面开了又关不会重复创建。
* `shutdown()`（`:161`）取消加载与落盘协程。
* 没有任何新依赖：没有引入体积大或来源不明的库。

---

## 11. 测试覆盖与**未覆盖**

**自动化（JVM 单元测试，无需模拟器）**：四个类合计 **63 例，全部通过**（`gradle :app:testDebugUnitTest --tests 'com.lumobrowser.app.browser.adblock.*'`，`:app:testDebugUnitTest` 通过，0 个 `e:` / 0 个 `w:`）。

| 测试类 | 覆盖 |
| --- | --- |
| `app/src/test/java/com/lumobrowser/app/browser/adblock/AdBlockRulesTest.kt`（20 例） | 解析（两种写法、`||…^`、hosts 形式、`$` 修饰符丢弃、注释表头、坏行计数）、主机与子域匹配、标签对齐（`notads.example.com` 不命中 `ads.example.com`）、路径片段大小写与**长度下限**（`/a`、`/**` 丢弃并计数）、`0.0.0.0` / `127.0.0.1` / `localhost` / 单标签 / 超 253 字符的行一律丢弃、`isEmpty` 对"只有例外"的规则集为真 |
| `.../AdBlockPolicyTest.kt`（22 例） | 六条判定顺序：主文档永不拦、非 http(s) 放行、例外优先、第三方限制与"主机规则不受第三方限制"、主机归一化（端口、userinfo、大小写、**尾端根点**、**IPv6 方括号**、未闭合括号 → 空主机名）、`siteOf` 取后两段（`www.news.example.org` 与 `news.example.org` 同站）、空主机名放行、缓存与失效 |
| `.../AdBlockManagerTest.kt`（9 例） | 开关、计数、`resetPageCount`、落盘合并与失败回填（写失败把增量放回去）、后台 2 秒刷新、规则读取失败降级为 `Unavailable`（保住拦截、丢掉总数）、状态发布 |
| `.../AdBlockResponsesTest.kt`（12 例） | 透明像素是真的 1×1 PNG（逐块 CRC + IHDR 字段）、图片请求拿到像素、非图片请求拿到零字节且类型正确、类型解析优先级与边界（点分查询、超长扩展名、片段） |

**没有测试、也没有验证过的部分是网页兼容性本身**：新闻站、搜索结果页、图片站、视频页、登录页、依赖第三方脚本的页面、有弹窗的站点，以及"误拦之后能不能恢复"——这些只能由**真机在真实网站上**回答。本版交付的是一套有测试保护的判定引擎和一套可随时关掉、可当场刷新对比的开关，**不是**一份"已在各站点验证通过"的结论。

---

# 附：v1.08 补齐的部分

> 上面 §1–§11 写的是 v1.07 的广告屏蔽，其中**一句话已经被本版推翻**（§12.7 更正了它）。本节只写 v1.08 新增的四件事，以及它们各自的边界。

## 12.1 媒体碎片不再被饿死（媒体豁免）

**问题**：主机规则命中一个视频 CDN 时，`.m3u8`、`.m4s`、`.ts`、`.mpd` 这些**分片**会被一起拦掉。表现不是"广告没了"，而是"整部片子放不出来"，而且页面上不会有任何解释。

**修法**：`app/src/main/java/com/lumobrowser/app/browser/adblock/AdBlockPolicy.kt` 增加媒体豁免——扩展名从 **URL 的 path** 里读（不是从整个 URL 里找），命中 `.m3u8` / `.mpd` / `.m4s` / `.ts` 的请求不吃**主机**规则。四条边界都被测试钉住：

* 只在**主机规则**面前豁免：`/adframe/x.m3u8` 这类**路径规则**照样拦。
* **例外（`@@`）永远优先**于豁免，也就是说 allow-list 的行为一个字没变。
* path 判定而非全串判定：`?file=stream.m3u8` 不算，`stream.m3u8.js` 不算，`stream.M3U8` 算（大小写不敏感），空串不算。
* 同一个被拦主机上的普通文件（`player.js`、`beacon.gif`、`ad.mp4`、`jingle.mp3`）**照旧被拦**——豁免只针对分片，不是给整个域名开后门。

## 12.2 站点级暂停（浏览器里就能做，不用进设置）

* `AdBlockManager.isPaused(documentHost)` / `pausedEntriesFor(documentHost)`（返回"覆盖此页的已存条目"而不是一个布尔：要恢复的是**被存下来的那个条目**）/ `pauseSite` / `resumeSite` / `pausedSites`。
* 暂停**涵盖子域**：暂停 `example.com` 也会暂停 `www.example.com`，所以恢复时必须逐条复原覆盖条目，否则菜单说"已恢复"而页面仍然不过滤。
* 入口在**浏览器溢出菜单**里，与"本页已拦截 N 项"挨着：暂停时那里多一行"本站已暂停"，菜单项在**暂停 / 恢复之间互斥**（testTag `BROWSER_AD_BLOCK_PAUSE` / `BROWSER_AD_BLOCK_RESUME`），并且**恢复会重载本页**——不重载的话用户看到的仍是被拦过的页面。
* 已暂停的站点列表在设置页里逐条列出，每一行本身就是"恢复"按钮（`ad_block_paused_*` 字符串）。

## 12.3 规则更新：**用户手动触发**，来源是**常量**

| 事实 | 值 / 位置 |
| --- | --- |
| 来源 | `AdBlockRuleUpdater.SOURCE_URL`（`app/src/main/java/com/lumobrowser/app/browser/adblock/AdBlockRuleUpdater.kt:162`）= `https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=text/plain`（随包快照的来源站）。**没有**"规则地址"输入框，页面或偏好文件都无法把它指到别处。 |
| 触发 | 设置页的"更新规则"那一行（testTag `SETTINGS_AD_BLOCK_UPDATE`）。**自动更新需要先手动更新过一次**：`maybeAutoUpdate`（`:141`）在用户下载过之前什么都不做，所以装上应用本身不会产生用户没要求的流量；此后间隔 `AUTO_UPDATE_INTERVAL_MS = 7 天`（`:178`）。 |
| 传输 | 仅 HTTPS，**重定向前后各查一次**：跳去明文 HTTP 会让更新失败而不是静默降级（`:267`、`:281`）；连接/读取超时 15s / 20s（`:309`），声明长度与实际读取都有 `MAX_BYTES` 上限（`:285`、`:288`）。 |
| 合并而非替换 | 存下来的文件 = **随包列表 + 上游新增**（`:241-249`）。这样靠例外表活着的登录、验证码、播放器 SDK 不会因为一次更新集体失效。 |
| 写入前先解析 | 先解析并计数；截断的、空的、根本不是规则表的内容会被拒绝并给出原因，**正在用的规则继续在用**（`:79`，`MIN_UPSTREAM_HOSTS = 1_000`）。 |
| 版本号说的是实话 | 下载后的表头是 `# version: <日期>+dl`、`# origin: downloaded, then merged with…`（`:241-246`）；设置页的"来源"一行读的是**分层来源实际赢了哪一层**（`AdBlockRulesSource.usesDownloaded`，只有 `read()` 之后才有意义），而不是"我们下载过"。 |

**"已经是最新"这句话不会出现**：更新结果里带 `changed`（`RuleUpdateResult`，`:17`），下载到的是**同一份正在用的清单**时就如实说"与当前规则相同"，而不是渲染成一次成功更新。

## 12.4 拦截记录（有界）

* `AdBlockManager.LOG_CAPACITY = 60`：记录的是**最近**若干条（时间、主机、种类、理由）。有界是刻意的——日志列表本身就是一段缓慢的内存增长。
* 设置页逐条显示，底部有清空按钮（testTag `SETTINGS_AD_BLOCK_LOG_CLEAR`），并写明容量。
* 计数与记录是分开的：**清空记录不会重置累计拦截数**。

## 12.5 设置页现在会回答的三个问题

| 问题 | 面板上对应的行 |
| --- | --- |
| 现在用的是哪一份规则？ | "来源"行：**随包** 或 **已下载**（`ad_block_source_*`）。技术字符串（`source` 字段）刻意不显示给用户。 |
| 这一页到底检查了多少请求？ | "本页已检查"（`adBlockCheckedLabel`）——只报被检查的数量，不编造"拦截率"。 |
| 上一次下载是什么时候、什么版本？ | 下载信息行：日期时间（`DateFormat.getDateFormat` + `getTimeFormat`，跟随系统格式）、主机规则数、短哈希；从未下载过就显示"从未"。 |

## 12.6 测试

广告屏蔽包合计 **71 例，全部通过**（v1.07 是 63 例）：

| 测试类 | 例数 | v1.08 新增覆盖 |
| --- | --- | --- |
| `app/src/test/java/com/lumobrowser/app/browser/adblock/AdBlockPolicyTest.kt` | 26 | §12.1 的四条边界（分片存活、同主机的普通文件仍被拦、扩展名只从 path 读、豁免不影响路径规则与例外） |
| `.../AdBlockManagerTest.kt` | 13 | §12.2 的暂停语义：只作用于本站、涵盖子域且返回要恢复的条目、逐条复原即整站恢复、`setPausedSites` 的清洗与清空 |
| `.../AdBlockRulesTest.kt` | 20 | 解析（未变） |
| `.../AdBlockResponsesTest.kt` | 12 | 响应构造（未变） |

**仍然测不到的**：`AdBlockRuleUpdater` 的真实网络往返（单元测试替换掉的是 `fetch` 函数）、真实站点上"豁免之后视频确实能放"、以及 §12.7 里那句更正的准确性——这些都需要真机与外网。

## 12.7 §1 里那句话的更正

§1 末尾原文是"**没有做在线规则更新**：本版没有可用的规则更新源，因此不伪造'检查更新''规则已是最新'这类界面"。

这句话对 v1.07 是准确的，对 v1.08 **已经不成立**：本版接了 §12.3 的手动更新。同时被它保护的那条原则仍然成立，并且换了实现方式：**不伪造"已是最新"**——现在由 `changed` 字段承担，下载到同一份清单时如实说"与当前规则相同"。
