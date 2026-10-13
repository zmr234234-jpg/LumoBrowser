# Gradle 构建日志（交付副本）

这个目录是**原始构建日志的副本**，不是摘要、不是重排版。每一行都是 Gradle 自己写出来的。

## 为什么单独放一个目录

验收要求把「Gradle 构建日志」作为交付物之一。而日志本身有个特点：**它们是最容易在打包时被漏掉的东西**——本仓库的打包脚本默认排除 `.txt`（那个规则是为 `local.properties` 之类的东西定的），所以在前几轮里，文档正文反复引用 `c30.txt`、`t13.txt` 这些名字，**而这些文件从来没有进过源码包**。引用一个收件人手上没有的文件，等于没有引用。

所以这里做了两件事：把日志放进来，并**在打包脚本里为这个目录开一个白名单例外**（`docs/build-logs/*.txt`），同时在发布校验脚本里加一条断言——这个目录里的文件必须出现在源码包的条目列表里。加断言的理由很直接：白名单是一条**只对某个前缀生效**的规则，而这类规则最容易在某次重构里被静默取消，然后没有人会注意到，因为缺少的正是那个本该指出缺少的东西。

## 文件清单

日志文件名（`cNN` / `tNN`）是构建时随手起的顺序编号，本身不携带任何信息，所以下表是**必须的**——否则这些文件对收件人来说是一堆无法解读的文本。

| 文件 | 时间 | 命令 | 结果 | 它证明什么 |
| --- | --- | --- | --- | --- |
| `c30.txt` | 10-10 23:05 | `gradle clean :app:testDebugUnitTest :app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL in 16m 37s` | **当前发布的 APK 就是这一次构建的产物。** 这是一次真正的 clean 重建（50 个 actionable task：32 executed / 17 from cache / 1 up-to-date），不是增量。单元测试与 APK 在同一次调用里跑完。 |
| `c29.txt` | 10-10 22:48 | 同上，无 `clean` | `BUILD SUCCESSFUL in 11m 5s` | 修掉两个 `Parameter 'x' is never used` 死接线之后的第一次构建。**这一份的价值在于它是「警告消失」的那一侧证据**——同一批警告还留在 `c28.txt` 里，两份对着看，能确认它不是自己消失的。注意这是增量构建（13 executed / 36 up-to-date），所以它不能单独支撑「干净重建」的说法，这也是后来又跑 `c30.txt` 的原因。 |
| `c28.txt` | 10-10 22:32 | 同 `c30.txt` | `BUILD SUCCESSFUL in 11m 41s` | 1.03 的第一次 clean 重建。**这一份存在的理由是它里面的两条警告**：`Parameter 'attempt' is never used` 与 `Parameter 'onSubmit' is never used`。它们是本轮两个真实缺陷的唯一线索，而且在前几次构建里一直存在、一直被当作噪音。 |
| `t13.txt` | 10-10 22:18 | `gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.accessibility.*"` | `BUILD SUCCESSFUL in 32s` | 无障碍三个测试类单独跑通。这一份还记录了一次失败后的成功：`t11.txt` / `t12.txt` 是它前面两次红的（那两次抓出了真实缺陷），但没有进本目录——见下面的「没有放进来的」。 |
| `t15.txt` | 10-10 22:20 | `gradle :app:testDebugUnitTest --tests "com.lumobrowser.app.ui.components.StartupTimelineTest"` | `BUILD SUCCESSFUL in 16s` | `startupTimeline()` 的 12 条纯函数性质。2.2 遗留清单第 ⑧ 项就是靠这一份关掉的。 |
| `t10.txt` | 10-10 21:56 | `gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL in 10m 59s` | v2.2 那一轮的全量单元测试 + 组装。保留它是为了**上一版仍然可查**：验收要求区分「已通过 / 未通过 / 未测试」，而上一版的通过证据不应该只活在这一版的叙述里。 |
| `cold102.txt` | 10-10 15:07 | 在**解压出来的源码包**里跑 `gradle clean :app:assembleDebug --no-daemon` | `BUILD SUCCESSFUL in 4m 15s` | 1.02 的「源码包自足性」验证：把交付的 zip 解开、在一个干净目录里构建，证明这个包不依赖打包机器上的任何残留。**这是全套交付里唯一一次真正做了冷构建验证，1.02 之后（含 2.2 与 1.03）都没有重做**——这一点写在 [`../TESTING.md`](../TESTING.md) 的未执行清单里，没有当作做过。 |

## 没有放进来的

本机还有约 50 份其它日志（失败的编译、被取代的中间构建、逐条试错的过程）。它们没有被复制进来，理由不是它们没价值，而是**交付物需要的是能被核对的证据，不是完整的过程录像**——把 60 份日志一起丢给收件人，等于让他在一堆顺序编号里自己找那几份有意义的，那种交付实际上是不可用的。有意义的取舍已经在上表里，而被取舍掉的那些仍在构建机器上，随时可以补。

明确说明一点：**上表里「它证明什么」一列是本轮会话的记录，不是日志自身能推导出来的**——Gradle 不往日志里写版本号，`c30.txt` 内部没有任何一个字说明它是 1.03 的构建。文件名与版本的对应关系来自运行时代码，而运行时代码是会被质疑的。如果这个对应关系重要，可靠的核对方式是重新构建一次并对哈希，而不是相信这张表。

## 复现

```bash
cd LumoBrowser
gradle clean :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

命令与 `c30.txt` 顶部那条一致。注意本机 `gradle` 不在 `PATH` 上，实际使用的是绝对路径 `D:\AndroidDev\gradle-8.6\bin\gradle.bat`；`gradlew.bat` 在本机走不通，因为 `~/.gradle/wrapper/dists` 不存在。详见 [`../BUILD.md`](../BUILD.md)。

> 日志里的 `WARNING: The option setting 'android.defaults.buildfeatures.buildconfig=true' is deprecated.` 是 Android Gradle Plugin 8.x 的提示，与本次交付无关，也没有被忽略掉——它出现在每一份日志的开头，说明它是稳定的既有状态而不是新引入的问题。

## v1.04（versionCode 6）

| 文件 | 内容 |
| --- | --- |
| `v104-assemble.txt` | `clean :app:assembleDebug --console=plain`。**本轮唯一一次交付构建**：`:app:clean` 之后从零装配，产出 `app-debug.apk`（19,310,815 字节）。`> Task :app:kspDebugKotlin` 与 `> Task :app:compileDebugKotlin` 两行都没有 `FROM-CACHE` / `UP-TO-DATE` 后缀，即真的执行了——这是本轮**决定性的零 Kotlin 警告证明**（`w:` 0 条、`e:` 0 条）。`BUILD SUCCESSFUL in 1m 8s`、`41 actionable tasks: 24 executed, 16 from cache, 1 up-to-date`。`16 from cache` 全是资源与 dex 合并类任务。 |
| `v104-tests.txt` | `:app:testDebugUnitTest --console=plain`，在**最终源码**上执行。`BUILD SUCCESSFUL in 11m 2s`、`EXIT=0`、**29 个测试类 / 460 例 / 0 失败 / 0 错误 / 0 跳过**。这是增量构建（`10 executed, 21 up-to-date`），所以它证明的是「测试全绿」，**不是**「零警告」——Kotlin 不会重新打印未改动文件的警告。 |

## v1.05（versionCode 7）

这一节的命令都带 **`--no-build-cache`**。这不是随手加的：1.04 的 `v104-assemble.txt` 与 `v104-tests.txt` 里都有一批任务是从 Gradle 构建缓存搬来的（`16 from cache` / `21 up-to-date`），于是那两份日志只能证明「结果是对的」，**不能**证明「Kotlin 编译器真的看过这批源码」——没跑过的文件不会打印警告。1.05 的两份日志把这一点补上了。

| 文件 | 内容 |
| --- | --- |
| `v105-assemble.txt` | `clean :app:assembleDebug --no-build-cache --console=plain`。**本套交付里第一次完全绕开构建缓存**：`31 actionable tasks: 14 executed, 17 up-to-date`——产出 `app-debug.apk`（19,340,939 字节）时没有任何一个任务的输出是从缓存取来的。`BUILD SUCCESSFUL in 22m 51s`、**`w:` 0 条、`e:` 0 条**。 |
| `v105-tests.txt` | `:app:testDebugUnitTest --no-build-cache --console=plain`，在**最终源码**上执行。`BUILD SUCCESSFUL in 22m 51s`、`TESTS_EXIT=0`、**32 个测试类 / 505 例 / 0 失败 / 0 错误 / 0 跳过**（上一版 29 类 460 例）。因为同样禁用了缓存，它同时是「测试全绿」与「测试源集真的重新编译过」两侧的证据。 |

> 上面两个数字（`32 类` / `505 例`）不是抄来的，是这份报告在写的时候**当场数 `app/build/test-results/testDebugUnitTest/` 下的 JUnit XML** 数出来的：`tests`、`failures`、`errors`、`skipped` 四个属性逐文件相加，任何一个非零就直接拒绝写这一节。日志里 Gradle 自己打印的行（`BUILD SUCCESSFUL in 22m 51s`、`31 actionable tasks: 14 executed, 17 up-to-date`）则是从日志文本里取的——两处都不是手写。

## v1.06（versionCode 8）

| 文件 | 内容 |
| --- | --- |
| `v106-assemble.txt` | `:app:assembleDebug --console=plain` 的交付装配。**增量构建**（不是 clean、不是 `--no-build-cache`），所以它只证明「这一轮的改动编译通过、APK 出来了」，不支撑「全树零警告」。 |
| `v106-tests.txt` | `:app:testDebugUnitTest --console=plain` 的全量单测。**33 个测试类 / 527 例 / 0 失败 / 0 错误 / 0 跳过**。 |

## v1.07（versionCode 9）

**1.07 是本工程第一次同时产出 Debug 与 Release 两个包**，也是第一次出现「Release 包用真密钥、不可调试」这条交付物。这两份日志在 1.07 交付时**漏收了**（打包脚本的白名单只覆盖 `docs/build-logs/`，而日志本体当时还留在工程根目录），本次（1.08）补进来——正好是这一节第一条规则要防的那种漏。

| 文件 | 内容 |
| --- | --- |
| `v107-assemble.txt` | `:app:assembleDebug :app:assembleRelease --console=plain` → `BUILD SUCCESSFUL in 2m 26s`、`0 个 e:` / `0 个 w:`。产出 19,416,196 字节的 Debug 包与 13,099,148 字节的 Release 包；两个包的 badging 与签名实测值见 [`../BUILD.md`](../BUILD.md) §8.0a。 |
| `v107-tests.txt` | `:app:testDebugUnitTest --console=plain` 的全量单测。**42 个测试类 / 628 例 / 0 失败 / 0 错误 / 0 跳过**（1.06 是 33 类 527 例）。 |

## v1.08（versionCode 10，当前交付）

这一节有六份，其中**一份是失败的编译**——它被刻意收进来。

| 文件 | 内容 |
| --- | --- |
| `v108-assemble.txt` | `:app:assembleDebug :app:assembleRelease --console=plain` → `BUILD SUCCESSFUL in 1m 44s`、`0 个 e:` / `0 个 w:`。产出 19,535,475 字节的 Debug 包与 13,182,384 字节的 Release 包。**增量构建**（非 clean、非 `--no-build-cache`），所以「0 警告」的范围是本轮改动的文件，不是全树冷构建；这条边界写在 [`../TESTING.md`](../TESTING.md) §7.0 与 [`../BUILD.md`](../BUILD.md) §8.0。 |
| `v108-tests.txt` | `:app:testDebugUnitTest --console=plain` 在**最终源码**上的全量单测：`BUILD SUCCESSFUL in 38s`、`31 actionable tasks: 10 executed, 21 up-to-date`（`:app:compileDebugUnitTestKotlin` 确实执行），聚合 JUnit XML 得 **44 类 / 718 例 / 0 失败 / 0 错误 / 0 跳过**（1.07 是 42 类 628 例）。 |
| `v108-compile.txt` | `:app:compileDebugKotlin --console=plain` → `BUILD SUCCESSFUL in 12s`、`0 个 e:` / `0 个 w:`。因为那次显示 `compileDebugKotlin UP-TO-DATE`，另外用**时戳比对**确认它确实编译了当时的源码：`BrowserScreenKt$BrowserModeMenu$1.class` 15:36:44 晚于 `BrowserScreen.kt` 15:36:36、`TestTags.kt` 15:36:33、`SettingsScreen.kt` 15:35:49、`MainViewModel.kt` 15:34:37、`AdBlockManager.kt` 15:36:15。**`UP-TO-DATE` 不等于「没编译新代码」——但要证明这一点得看时戳，不能只看那一行。** |
| `v108-compile-failed-safebrowsing.txt` | **失败的那一次**：`e: LumoWebView.kt:1201:9 'onSafeBrowsingHit' overrides nothing`、`e: LumoWebView.kt:1208:9 Unresolved reference`。原因是把 `WebViewClient.onSafeBrowsingHit` 写进了 `LumoChromeClient`（:1112 起），而它属于同一文件里的 `LumoClient`（:771 起）。搬到正确的类后一次通过。收这份日志的理由：只留成功日志的交付，等于把「怎么发现、怎么定位」这一段从证据里删掉，而那一段恰恰是「没有靠改版本号或忽略错误绕过」的唯一凭据。 |
| `v108-test-adblock.txt` | `:app:testDebugUnitTest --tests "com.lumobrowser.app.browser.adblock.*" --console=plain` 的广告屏蔽子集，**71 例**（`AdBlockPolicyTest` 26 / `AdBlockManagerTest` 13 / `AdBlockRulesTest` 20 / `AdBlockResponsesTest` 12）。 |
| `v108-test-config.txt` | `:app:testDebugUnitTest --tests "com.lumobrowser.app.browser.BrowserConfigManagerTest" --console=plain` → `BUILD SUCCESSFUL in 11s`、该 XML `tests=31 failures=0 errors=0 skipped=0`。这一份同时记录了一次**测试替换**：删掉了只断言参数默认值、因此永远为真的 `mediaNeverAutoplaysInAnyModeOrConfiguration`，换成双向断言每个 `BrowserMode` 的 `autoplayFollowsTheCallerAndNeverTheMode` 与断言出厂偏好值的 `theShippedPreferenceLetsAPageStartItsOwnPlayer`。**一个永远为真的测试比没有测试更糟：它会在报告里显示为通过。** |
