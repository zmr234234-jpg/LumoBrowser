# 音效无声的根因与修复（v1.05）

本文对应 v1.05 的第 ② 项：「点击音效无声、音效设置无效」。它记录**根因是什么**、**证据在哪**、**改了什么**、以及**哪些结论还没有在真机上验证**。

本文只谈反馈音效的播放链路。素材本身的来源、生成方式与清单在 [`AUDIO_ASSETS.md`](AUDIO_ASSETS.md)；v1.05 没有改动任何素材。

---

## 1. 结论

v1.04 用 `AudioAttributes.USAGE_ASSISTANCE_SONIFICATION` 建 `SoundPool`。这个 usage 在 AOSP 里映射到 **`STREAM_SYSTEM`**，而 `STREAM_SYSTEM` 在铃声模式为**静音或震动**时都会被系统强制静音。与此同时，v1.04 的可闻性自检 `SoundSynth.isAudible()` 读的是 **`STREAM_MUSIC`**。

两个流不是同一条。后果：

> **只要手机处于震动模式（或静音模式），整个应用的反馈音效完全无声，而 `isAudible()` 依然返回 `true`。** 没有日志、没有异常、设置页的「音效」开关仍然显示为开启。用户看到的是一个开着却没有任何反应的开关——这正是「音效疑似失效」的报告内容。

这也解释了「开关拨来拨去没有区别」：自检查的是一条永远不会被静音影响的流，所以它对真正的故障结构性失明。

---

## 2. 证据

根因不是推测，AOSP 源码逐行可查。

### 2.1 usage 映射到哪条流

`frameworks/base/media/java/android/media/AudioAttributes.java`：

```java
1776:        switch (aa.getUsage()) {
1777:            case USAGE_MEDIA:
1778:            case USAGE_GAME:
1779:            case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
1780:            case USAGE_ASSISTANT:
1781:                return AudioSystem.STREAM_MUSIC;
1782:            case USAGE_ASSISTANCE_SONIFICATION:
1783:                return AudioSystem.STREAM_SYSTEM;
```

`USAGE_ASSISTANCE_SONIFICATION = 13`（`:195`），`AudioSystem.STREAM_SYSTEM = 1`、`STREAM_MUSIC = 3`。

注意：`AudioAttributes` **没有** `getLegacyStreamType()` 这个公开方法。公开的名字是 `getVolumeControlStream()`（`:1742`），隐藏的静态版叫 `toLegacyStreamType()`（`:1754`，`@UnsupportedAppUsage`）。v1.05 的第一次编译就是在这里失败的——`legacyStreamType` 不在 `android.jar` 的 SDK 表面上，报 `Unresolved reference: legacyStreamType`。

### 2.2 震动模式会不会静音 `STREAM_SYSTEM`

会。`frameworks/base/services/core/java/com/android/server/audio/AudioService.java` 的 `muteRingerModeStreams()`：

```java
6168:        final boolean ringerModeMute = ringerMode == AudioManager.RINGER_MODE_VIBRATE
6169:                || ringerMode == AudioManager.RINGER_MODE_SILENT;
```

被静音的流由 `mRingerModeAffectedStreams` 决定，而它的默认值（`updateRingerAndZenModeAffectedStreams()`，`:7536-7540`）是：

```java
        int ringerModeAffectedStreams = mSettings.getSystemIntForUser(mContentResolver,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                 (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)),
                 UserHandle.USER_CURRENT);
```

`STREAM_SYSTEM` 在默认掩码里。所以 **v1.04 的 `ringerMode == RINGER_MODE_SILENT` 单个判断本身就少了一半**：`VIBRATE` 单独存在就足以杀掉声音。

### 2.3 为什么自检看不见

`STREAM_SYSTEM` 的音量在用户界面上是**别名到 `STREAM_RING`** 的（`AudioService.java:626-639` 与 `:674-687` 的 `STREAM_VOLUME_ALIAS_VOICE` / `STREAM_VOLUME_ALIAS_DEFAULT`），而且默认就在满格（`AudioSystem.DEFAULT_STREAM_VOLUME` 给 `STREAM_SYSTEM` 是 `7`，上限也是 `7`）。

所以：**样本根本没有被衰减，它是被整体静音的。** 任何读音量索引的判断都不可能发现这件事——`getStreamVolume(STREAM_SYSTEM)` 在震动模式下照样回一个正常数字。这不是漏检，是查错了对象。

### 2.4 这是已知问题，不是本项目的孤例

Stack Overflow 60303150「Android SoundPool plays no sound」的提问者用的正是 `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` + `setOnLoadCompleteListener`，加载回调正常触发、日志显示正在播放，但完全没有声音。他自己的采纳答案：

> "So apparently `AudioAttributes.USAGE_ASSISTANCE_SONIFICATION` was the issue, it works fine with `AudioAttributes.USAGE_GAME` and `AudioAttributes.USAGE_MEDIA`. I have no idea why, though."

报告的是一位不知道为什么的开发者。第 2.1–2.3 节就是那个「为什么」。

---

## 3. 修复

### 3.1 换成不被铃声模式静音的流

`app/src/main/java/com/lumobrowser/app/feedback/SoundSynth.kt`：

```kotlin
private val attributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()
```

`USAGE_MEDIA` → `STREAM_MUSIC`。`STREAM_MUSIC` 从不被铃声模式静音，而且**它正是硬件音量键控制的那条流**——用户在设置里调的就是它。

`CONTENT_TYPE_SONIFICATION` 保留：它描述的是「这是什么声音」，不是「走哪条流」。

### 3.2 只留一份事实

v1.04 的错，本质上是同一个事实被写了两遍（一遍给播放器、一遍给自检），然后两遍走散了。所以现在：

```kotlin
private val playbackStream: Int = runCatching { attributes.volumeControlStream }
    .getOrDefault(AudioManager.STREAM_MUSIC)
```

自检读的流**从建播放器用的那份 `attributes` 推导出来**，不可能再分叉。`getVolumeControlStream()` 是公开 API 里的对应名字（见 2.1）。

### 3.3 震动与静音同等对待

`app/src/main/java/com/lumobrowser/app/feedback/SoundGate.kt`：

```kotlin
fun isRingerQuiet(ringerMode: Int): Boolean =
    ringerMode == AudioManager.RINGER_MODE_SILENT ||
        ringerMode == AudioManager.RINGER_MODE_VIBRATE
```

### 3.4 把判定抽出来，让它可测、可上报

判定逻辑全部搬进 `SoundGate.kt`，它**不引用任何 Android 类**（`RINGER_MODE_*` 的整数值由 `SoundSynth` 填进 `SoundConditions`）。这样：

- 它能在纯 JVM 单元测试里跑（`app/src/test/java/com/lumobrowser/app/feedback/SoundGateTest.kt`，14 例），不需要 Robolectric，也不需要设备；
- **判定顺序本身就是政策**，写在测试里就不会被无意改掉。

判定顺序（`SoundGate.blockedBy`）：

| 顺序 | 条件 | 结果 |
| --- | --- | --- |
| 1 | 用户关掉了音效 | `Disabled` |
| 2 | 播放器没建起来 | `NoEngine` |
| 3 | 这一类音效被关掉 | `CategoryOff` |
| 4 | 应用内音量为零 | `AppVolumeZero` |
| 5 | 尊重静音 且 铃声为静音或震动 | `RingerQuiet` |
| 6 | 播放流的音量为零 | `StreamVolumeZero` |
| 7 | 样本还在解码 | `SampleNotReady` |
| — | 以上都不成立 | 放行 |

`NoEngine` 排在用户可改的设置**之前**：一个建不起来的播放器不是用户的设置问题，把它报成「你关掉了音效」是在撒谎。

### 3.5 失败要留下痕迹

`SoundSynth.play()` / `preview()` 的返回值从 `Unit` 改成 `SoundBlock?`（`null` = 真的发出去了）。被丢弃的那一次走 `note(block)`，用 `loggedBlock` 去重后写一行：

```
Feedback audio dropped: RingerQuiet (stream=3)
```

去重是必要的：按钮点击很密集，同一原因不该刷满 logcat。

**被 debounce 吞掉的一次不算失败，返回 `null`。** 两个很短的 cue 相隔几毫秒是正常现象，不是故障，把它报成故障会让状态行不停地闪。

`SoundPool.play()` 返回 0（非零才是成功）时报 `PlayRejected`；样本还没解码完报 `SampleNotReady`。这两条覆盖了 `SoundPool` 自己的两种失败方式。

### 3.6 设置页显示真实状态

`app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` 的音效区块新增一行只读状态：

- 第一行：正常时显示「正常 — 可以听到反馈音效」，否则按 `SoundBlock` 逐项显示具体原因（已关闭 / 这类音效已关闭 / 上面的音量为零 / 手机处于静音或震动 / 媒体音量为零 / 没有可用的音频输出 / 音效仍在载入 / 设备拒绝播放该音效）。
- 第二行：`媒体音量 N / M`，读的就是 `playbackStream`。

它查的**就是 `play()` 查的同一个门**（`MainViewModel.soundStatus()` → `SoundSynth.status()` → `SoundGate`），所以设置页不可能在引擎丢掉每一次 cue 的时候显示「开启」。

试听按下的那一次是例外：`PlayRejected` 只在**尝试启动的那一刻**存在，事后查询看不到它。所以试听会拿自己的返回值覆盖状态行的 `blocked` 字段——只有试听自己知道刚才那一下到底有没有响。

---

## 4. 取舍，以及没有做的事

**没有申请音频焦点。** 这不是疏漏，是决定。反馈音效只有 30–450 ms，为了它去 `requestAudioFocus()` 会让用户正在听的音乐每次点按都被压低或暂停——那比它要修的问题严重得多。

不需要焦点也能混音：ducking 是**对方应用申请焦点**触发的，不是本方播放触发的。一个从不申请焦点的应用不会给音乐应用发任何信号，两条流直接在 `STREAM_MUSIC` 上相加。

代价要如实说：**不申请焦点也意味着收不到焦点丢失的通知**，所以有电话打进来时本应用不会自动让路。对一个几百毫秒的音效，这个交换是可以接受的；写在这里是为了让下一个读代码的人知道它是故意的，不是忘了。

**`USAGE_MEDIA` 的代价**：点击音效从此受媒体音量控制，用户把媒体音量调到零就听不到了。这算不算「正确」有讨论空间，但它至少是**用户能控制、能看见**的行为，而 `USAGE_ASSISTANCE_SONIFICATION` 的静音是用户看不见、也查不到的。

**试听绕过了两个开关，但没有绕过其他条件。** `previewBlock()` 无视「音效总开关」和「这一类音效」——试听存在的意义就是回答「这个声音是什么样」，而那是开启之前的问题。其余条件（引擎、音量、铃声、样本）一律照常，所以试听不会假装一个零输出的设备发出了声音。

---

## 5. 没有验证的部分

**没有在真机上验证。** 这台环境没有设备也没有模拟器（`adb devices` 为空，SDK 里没有 `emulator.exe`，也没有 `system-images`）。

已经验证的：源码编译通过（`e:` 0 条）、`SoundGate` 的 14 条判定用例在纯 JVM 上全过、`SoundSynth` 的公开 API 与全部调用点一致。

没有验证的：真机上实际能听到声音。这需要一台手机，**并且需要分别在正常铃声和震动模式下各试一次**——震动模式那一次才是这次修复的直接目标。

另外两条与本文相关但未在本环境确认的事：

- `SoundPool.play()` 对「尚未解码完的样本」是否确定返回 0，官方 `SoundPool` 文档**没有**明确写。异步契约和大量社区报告都指向这个结论，但本文不把它当作已证事实——`SampleNotReady` 的判定不依赖 `play()` 的返回值，它自己查 `ready` 集合。
- 音频产品策略（audio product strategy）在 Android 10+ 上会先于 2.1 的那个 `switch` 决定实际路由（`AudioAttributes.java:1772-1774`）。对 sonification 来说结论仍是 system 流，但严格来说那条 `switch` 在真机上是回退路径。

---

## 6. 相关文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/feedback/SoundSynth.kt` | 播放器、`attributes`、`playbackStream`、`play()` / `preview()` / `status()` |
| `app/src/main/java/com/lumobrowser/app/feedback/SoundGate.kt` | 纯判定的门与 `SoundBlock`（不引用 Android 类） |
| `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt` | `previewSound()` 回传 `SoundBlock?`、`soundStatus()` |
| `app/src/main/java/com/lumobrowser/app/ui/SettingsScreen.kt` | 音效区块的真实状态行与试听 |
| `app/src/test/java/com/lumobrowser/app/feedback/SoundGateTest.kt` | 14 例判定测试（纯 JVM） |

素材清单、合成方式与响度见 [`AUDIO_ASSETS.md`](AUDIO_ASSETS.md)。
