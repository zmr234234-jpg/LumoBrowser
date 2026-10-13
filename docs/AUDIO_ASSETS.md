# 音效资产与播放引擎

本文对应 v2.2 规格第三节。它回答四个问题：这些声音**从哪来**、**能不能复现**、**实际会响的有哪几种**、以及**为什么这么实现**。

> **1.03 更新**：本节描述的音效系统在 1.03 **没有改动**，19 种事件 × 4 种风格共 76 个素材与播放引擎的行为与 v2.2 完全一致。1.03 只做无障碍辅助服务（见 [`ACCESSIBILITY_ASSIST.md`](ACCESSIBILITY_ASSIST.md)）。

---

## 1. 来源、作者、许可

| 项目 | 内容 |
| --- | --- |
| 来源 | 100% 由本仓库的 `tools/generate_sfx.py` 程序化合成 |
| 作者 | LumoBrowser 项目自身（生成器脚本随源码交付） |
| 第三方素材 | **无。** 没有采样自任何游戏、影视、其他应用或商业品牌音效库 |
| 网络下载 | **无。** 生成过程不访问网络 |
| 许可类型 | 与项目源码相同（生成器是源码的一部分，产物是它的确定性输出） |
| 使用条件 | 无附加条件。可自由再生成、修改参数、增删 cue |

这一点是刻意的：规格 3.2 禁止抓取受版权保护的素材，而一份**能被重新生成的**素材库比一堆来源不明的 wav 更容易审计——任何人跑一遍脚本就能确认这些字节确实来自这份代码。

---

## 2. 生成器与可复现性

```
python tools/generate_sfx.py      # 写出 76 个 wav 到 app/src/main/res/raw/
python tools/verify_sfx.py        # 检查并写出 tools/sfx_verification.txt
```

- `tools/generate_sfx.py`：**只用 Python 标准库**（`wave`、`struct`、`math`、`array`）。不依赖 numpy，不依赖任何音频库。
- 输出格式：**22050 Hz / 16-bit / 单声道 / PCM**。选择 22050 Hz 而非 44100 Hz 是因为最高频的 partial 在 6.1× 基频，而 cue 的基频都在 400–1400 Hz 区间，奈奎斯特频率足够；每个文件因此只有几 KB。
- 峰值策略：每个文件归一化到 `PEAK = 0.72`，留出余量给 `SoundPool` 混音时可能的重叠播放。所有文件的实测峰值落在 0.4315 – 0.7200。
- 包络：每个文件开头 3 ms 淡入、结尾 12 ms 淡出。淡出是必需的——直接截断会产生一个阶跃，在扬声器上听起来是一声「啪」而不是一个音符。

### 2.1 为什么种子是 FNV-1a 而不是 `hash()`

生成器原本用 Python 内建 `hash()` 给每个文件加一点固定扰动。那是错的：`hash()` 对字符串的加盐受 `PYTHONHASHSEED` 控制，**每次运行都不同**，于是「重新生成」会得到一批内容不同、文件名相同的 wav——素材库就有了两个互相矛盾的版本，而且没有任何东西会报警。

现在用 FNV-1a（自己实现的，8 行）计算固定种子。验证方式是把 `app/src/main/res/raw/` 清空后连跑两次，逐文件比对字节：**结果相同**。

---

## 3. 四种风格（规格 3.4）

「风格」不是把同一个声音换四种均衡。四种风格走的是**不同的合成参数**，因为「极简柔和」与「清脆科技」的差别在起音时间和泛音结构里，不在音量里。

| texture | detune | attack | partials | lowpass | drive | tail | 听感 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `soft` | 0.94× | 4.0 ms | 3 个谐波 | 0.55 | – | – | 极简柔和：缓起音、少泛音 |
| `crisp` | 1.00× | 0.8 ms | 5 个（到 6.1×） | 0.95 | 0.12 | – | 清脆科技：快起音、宽泛音 |
| `glass` | 1.26× | – | **非谐** 2.76 / 5.40 / 8.93 | – | – | 1.55× | 轻盈玻璃感：非谐分音 + 长尾 |
| `deep` | 0.50× | – | 低次谐波 | 0.42 | 0.22 | – | 沉稳低调：低八度、暗 |

`glass` 的 partial 比例刻意选**非整数**：整数比例听起来是乐器，非整数比例听起来是玻璃或金属。这是「玻璃感」在物理上的来源，不是靠加混响糊出来的。

### 3.1 怎么证明四种风格真的不同

人耳判断不能进测试，所以用能量分布判断。指标是 **`hf_ratio` = 一阶差分 RMS / 信号 RMS**——一阶差分是 +6 dB/oct 的高通，它对泛音内容敏感、对基频不敏感。

实测均值（`tools/sfx_verification.txt`）：

| texture | mean hf_ratio |
| --- | --- |
| `deep` | 0.137 |
| `soft` | 0.244 |
| `crisp` | 0.335 |
| `glass` | 0.650 |

四个值互不相同且顺序符合设计意图：deep 最暗、glass 最亮。

**踩过的坑（已修正）**：最初用**过零率**（ZCR）当亮度指标，结果 `vpn_connecting` 报的是 `deep=358 soft=680 crisp=680 glass=1047`——soft 与 crisp 平手。根因是那条 cue 是一段**纯扫频**，ZCR 量到的是**基频**随时间的变化，而不是泛音内容，对纯扫频根本无法区分 texture。改用 `hf_ratio` 后分离恢复。ZCR 仍列在报告里供参考，但**不再作为任何断言依据**。

---

## 4. 十九种独立音效（规格 3.3 要求 ≥ 16）

每种 cue 都有四种风格的完整素材，共 **19 × 4 = 76 个 wav**。`durationMs` 列的是 crisp 版的长度，也就是 `SoundSynth` 用来去抖的值。

| # | cue | 触发时机 | ms | 家族 / 受哪个开关管 |
| --- | --- | --- | --- | --- |
| 1 | `Press` | 手指按下（`PressInteraction.Press`） | 56 | Interface（只受总开关） |
| 2 | `Release` | 按下被取消（`PressInteraction.Cancel`） | 58 | Interface |
| 3 | `Click` | 普通点击：导航、开面板、点卡片 | 81 | Interface |
| 4 | `Toggle` | 状态翻转：开关、筛选 chip、选择 | 71 | Interface |
| 5 | `Navigate` | 页面切换（返回栈条目变化） | 146 | Interface |
| 6 | `DialogOpen` | 弹窗出现 | 136 | Interface |
| 7 | `DialogClose` | 弹窗消失 | 121 | Interface |
| 8 | `BookmarkAdded` | 添加网址成功 | 246 | Interface |
| 9 | `SettingsSaved` | 保存设置成功 | 208 | Interface（见 §6.3） |
| 10 | `Confirm` | 确认一个非破坏性决定 | 216 | Interface |
| 11 | `DeleteConfirm` | 确认删除收藏 | 229 | Interface |
| 12 | `Error` | 输入被拒绝 | 216 | Interface |
| 13 | `LoadStart` | 网页开始加载 | 116 | PageLoading |
| 14 | `LoadSuccess` | 网页加载成功 | 201 | PageLoading |
| 15 | `LoadFailure` | 网页加载失败 | 236 | PageLoading |
| 16 | `VpnConnecting` | VPN 开始连接 | 156 | Vpn |
| 17 | `VpnConnected` | VPN 已连接 | 296 | Vpn |
| 18 | `VpnFailed` | VPN 连接失败 | 258 | Vpn |
| 19 | `LaunchEnd` | 启动动画结束 | 446 | Launch（**默认关闭**） |

音高是有意义的，不是随手取的：成功类走 C-G-C 三和音与上行五度，失败类走下行小二度。用户不需要听懂乐理，但「往上」和「往下」的差别不需要学。

`DeleteConfirm` 与 `Confirm` **刻意不同**：改名和删除不是同一个决定，如果听起来一样，用户会学会两个都不听——而那正是他在反射性确认删除时最需要听的一刻。

时长分布（最长 446 ms `LaunchEnd`，最短 56 ms `Press`）全部落在 sonification 的合理区间内：短到不拖慢操作，长到能被辨认。

---

## 5. 播放引擎

`app/src/main/java/com/lumobrowser/app/feedback/SoundSynth.kt`，基于 `SoundPool`。

| 规格要求 | 实现 |
| --- | --- |
| 预加载，不要每次点击重建播放器 | 构造时 `SoundPool.Builder()`；`SoundLibrary.all` 一次投入 |
| 启动后**异步**载入 | `init` 内 `scope.launch { loadAll() }`，主线程不做解码 |
| 处理载入完成 | `setOnLoadCompleteListener`，成功的 sampleId 进 `ready` |
| 未初始化不得阻塞按钮 | 未 `ready` 的 cue 直接 `return`——**早期点击是静音，不是错误**，按钮立刻响应 |
| 快速连续点击的重叠策略 | `setMaxStreams(4)`；同一 cue 用 `isTooSoon()` 去抖（间隔 < 该 cue 的 `durationMs` 则丢弃） |
| 音频焦点 | **刻意不请求。** 30–450 ms 的通知音抢焦点会把用户正在听的音乐压下去，比它解决的问题更糟 |
| 静音 / 勿扰 | `isAudible()` 检查 `volume <= 0`、`respectSilentMode` 且 `ringerMode == SILENT`、`getStreamVolume(STREAM_MUSIC) > 0` |
| 音效失败不得崩溃 | 全部播放路径不抛异常；载入失败只是那个 cue 不响 |
| 释放 | `release()` 释放 `SoundPool` 并取消 scope |

### 5.1 试听刻意绕过开关

`preview(cue, style, volume)` **不看** `enabled`、不看家族开关、不看当前保存的风格。理由写在代码里：试听按钮存在的唯一目的就是回答「这个风格听起来怎样」，而那是用户**决定要不要开音效之前**问的问题。把试听锁在总开关后面，等于把答案藏在用户正在做的那道决定后面。

试听也**不写回**任何设置——它只发声，不保存。

### 5.2 三颗开关与规格的对应

规格 3.4 明确列出七项设置，其中三颗是「是否播放」的独立开关。实现为四个家族，但只有三颗开关：

| 家族 | 包含的 cue | 独立开关 |
| --- | --- | --- |
| `Interface` | Press, Release, Click, Toggle, Navigate, DialogOpen, DialogClose, BookmarkAdded, SettingsSaved, Confirm, DeleteConfirm, Error | **无**（只受总开关） |
| `PageLoading` | LoadStart, LoadSuccess, LoadFailure | `sound_loading_cues` |
| `Vpn` | VpnConnecting, VpnConnected, VpnFailed | `sound_vpn_cues` |
| `Launch` | LaunchEnd | `sound_startup_cues`（**默认 false**） |

`Interface` 没有独立开关是刻意的：给「点按钮的声音」一个开关，等于让用户有机会关掉之后以为应用坏了。总开关就是它的开关。

`Interface` 家族在代码里仍然以 `SoundCueFamily` 存在（而不是硬写在 `when` 里），因为「哪些 cue 属于哪一组」这件事必须能被测试读到，而不是散落在条件式里。

### 5.3 为什么启动音效默认关闭

`LaunchEnd` 是**唯一一个用户没有碰任何东西就被触发的**声音，也是最长的一个（446 ms）。规格 5.3 要求它默认关闭，代码里 `soundStartupCues` 的默认值就是 `false`，KDoc 写明了理由。用户在设置页主动打开后才会响。

---

## 6. 实际会响的有哪些

素材全部出货不代表全部会响。以下是**逐条核对过的**接线状态。

### 6.1 已接线（全部 19 种）

| cue | 呼叫点 |
| --- | --- |
| `Press` / `Release` | `ui/components/Surfaces.kt` → `Modifier.lumoPress`，收 `PressInteraction.Press` / `.Cancel` |
| `Click` | `Surfaces.kt`（4 处按钮元件）、`HomeScreen.kt`、`SettingsScreen.kt`（2）、`MainViewModel.kt` |
| `Toggle` | `Surfaces.kt`、`SettingsScreen.kt`（2）、`MainViewModel.kt` |
| `Navigate` | `ui/LumoApp.kt`：返回栈条目 id 变化时 |
| `DialogOpen` / `DialogClose` | `feedback/LumoFeedback.kt` → `LumoDialogCues()`，被 `LumoApp.kt` 的还原确认弹窗与 `SettingsScreen.kt` 的两个确认弹窗调用 |
| `BookmarkAdded` | `MainViewModel.kt` 添加收藏 |
| `Confirm` | `MainViewModel.kt`（8 处）、`browser/LumoWebView.kt`（2 处） |
| `DeleteConfirm` | `MainViewModel.kt` 删除收藏 |
| `Error` | `MainViewModel.kt` |
| `LoadStart` / `LoadSuccess` / `LoadFailure` | `ui/BrowserScreen.kt` |
| `VpnConnecting` / `VpnConnected` / `VpnFailed` | `ui/LumoApp.kt` |
| `LaunchEnd` | `MainActivity.kt` → `LumoStartup(onFinished = …)` |

### 6.2 边缘触发，而不是电平触发

网页加载、VPN 状态、导航这三组都不是「状态一变就响」，而是**只在状态迁移时响一次**：

- 网页加载记住前一个相位。停留十分钟的已加载页面不会反复宣告「已加载」；进度从 12% 跳到 47% 仍然是同一次加载。
- VPN 状态**跳过本次组合看到的第一个值**。应用打开时就已经连上的隧道、或语言切换重建 Activity 之前就失败过的连接，都不是用户刚刚做的事；重播它等于每次回到应用都播报旧闻。
- `DetectedNetwork`（「检测到 VPN 网络」）**刻意没有音效**。那个状态的意思是「有一个 VPN 网络存在，但不是我们建立的」；给它配音效就是这段代码被重写掉的那个假「已连接」宣称的听觉版本。

### 6.3 已知的未接线：`SettingsSaved`

**`SettingsSaved` 目前没有任何呼叫点。** 这是有意的，不是遗漏。

本应用的设置是**即时生效**的（这是规格第六节的明确要求），没有「保存」按钮。每一个离散设置控件的提交都已经产生了一个更具体的 cue（chip 和开关是 `Toggle`，动作行是 `Click`，恢复默认是 `Confirm`）。再叠一个 `SettingsSaved` 会变成一次操作两声——正是规格 3.5 禁止的「同一事件重复播放大量声音」。

素材在四种风格里都是完整可播放的（可用设置页的试听按钮验证），cue 也通过 `LumoFeedback.settingsSaved()` 暴露；缺的只是一个不重复的表达时机。这一点同样列在 `docs/TESTING.md` 的「未测试 / 未实现」表中。

---

## 7. 验证

```
python tools/verify_sfx.py
```

输出 `tools/sfx_verification.txt`，逐文件列出 ms / peak / rms / DC 偏移 / hf_ratio / zcr，并检查：

- 静音文件（RMS 为 0）
- 削波（peak ≥ 1.0）
- DC 偏移超限
- 首尾阶跃（淡入淡出是否真的存在）
- 重复文件（两个 cue 内容完全相同）
- 四种 texture 的 `hf_ratio` 排序

**当前结果：`problems: 0`。**

`tools/sfx_manifest.txt` 是 76 个文件的字节数与时长清单，供人工抽查。

---

## 8. 无法在本机验证的部分

以下需要真机或模拟器，本环境没有（`adb devices -l` 只有表头，无 AVD、无 system image）：

1. **实际听感**——四种风格的区别是否如表格所述，只能由人耳判断。`hf_ratio` 证明的是泛音内容不同，不是「好听」。
2. **延迟**——`SoundPool` 预加载后首次发声的延迟，在真机上通常 < 20 ms，但本环境无法测量。
3. **蓝牙 / 耳机的路由与音量**——`getStreamVolume(STREAM_MUSIC)` 在蓝牙设备上的行为未验证。
4. **勿扰模式下 `ringerMode` 的实际取值**——厂商 ROM 存在差异。
5. **长时间连续点击下的内存与句柄**——`setMaxStreams(4)` 的边界行为未在真机压力下验证。

复现方式见 `docs/TESTING.md`。
