# 全專案功能審查與清理（v1.04 · 目標 ④）

這份文檔是 v1.04 目標 ④ 的交付物。它回答一個問題：**這個專案裡「看起來能用」的功能，有多少是真的連到行為的？**

審查對象是 `LumoBrowser` 全部使用者可見的設定項、它們背後的 `UserPreferences` 欄位，以及真正讀取這些欄位的程式碼。審查方法是**逐欄位追蹤讀者**，不是閱讀 UI 程式碼後憑印象判斷。

---

## 1. 判定方法

### 1.1 三道追蹤

對 `app/src/main/java/com/lumobrowser/app/data/Model.kt` 裡 `UserPreferences` 的**全部 58 個欄位**，逐一追蹤：

1. **定義**：欄位在 `UserPreferences` 裡的宣告。
2. **持久化**：`data/SettingsRepository.kt` 裡的 `Keys`／讀取／`setXxx` 三處。
3. **讀者**：全專案（`app/src/main/java/**/*.kt`）中讀取 `prefs.<欄位>` 或 `preferences.<欄位>` 的位置。

追蹤工具是 `_v104_trace.py`，輸出為 `_v104_trace.txt`（本輪重新產生，附錄 A 全文收錄）。

### 1.2 「載體檔案」的排除

`Keys`、`Model.kt`、`SettingsRepository.kt`、`LumoStringsSnapshot.kt`、`SettingsScreen.kt` 這五類檔案**本身就是設定鏈路的一部分**——它們讀一個欄位，不代表有任何行為依賴它。

所以工具把它們標為 `carrier`，只有 `carrier` 以外的檔案才算 `READER`。

**一個欄位如果在 `carrier` 之外沒有任何讀者，它就是「孤兒」。**58 個欄位裡有 **5 個孤兒**。

### 1.3 方法本身的兩處修正（重要）

第一版的判定有兩個**假陽性**，必須先修掉，否則整份報告會指認錯人：

**假陽性一：`useCustomAccent`**

工具報它是孤兒，因為 `LumoTheme.kt` 讀的是 `prefs.accentHue`，不是這個旗標本身。但這不是死偏好——它是**純 UI 附屬狀態**：`SettingsScreen.kt:459` 讀它決定要不要畫自訂色相滑桿，`SettingsScreen.kt:461` 是它的寫入端。真正的顏色來源 `accentHue` 有真實讀者（`ui/theme/LumoTheme.kt` 的 `buildAccent(prefs)`，無條件使用）。

→ 判定：**保留**。它不控制行為，只控制介面呈現。

**假陽性二：三個無障礙欄位**

工具報 `accessibilityAssistEnabled`、`accessibilityAssistTargets`、`accessibilityAssistNoteAcknowledged` 是「不在 UI 裡」。

**這是錯的。**追蹤當時只掃了 `SettingsScreen.kt`，而 `AccessibilityAssistPanel` 的狀態是從 `ui/LumoApp.kt:538-540` 灌進去的：

```
ui/LumoApp.kt:538    switchOn = preferences.accessibilityAssistEnabled,
ui/LumoApp.kt:539    noteAcknowledged = preferences.accessibilityAssistNoteAcknowledged,
ui/LumoApp.kt:540    targets = AssistTargetCodec.decode(preferences.accessibilityAssistTargets),
```

另外 `accessibility/AssistSettings.kt:55-56` 也讀它們：

```
accessibility/AssistSettings.kt:55    enabled = prefs.accessibilityAssistEnabled,
accessibility/AssistSettings.kt:56    targets = AssistTargetCodec.decode(prefs.accessibilityAssistTargets),
```

→ 判定：**保留**。三個欄位都真的有讀者，而且接在無障礙輔助服務上。

**假陽性三：`dragHintShown`**

它是「拖曳提示看過了沒」的一次性旗標，不是設定項。讀者在 `ui/HomeScreen.kt:210`（`if (!preferences.dragHintShown && !isList)`），寫入端在 `ui/LumoApp.kt:351`。

→ 判定：**保留**（非設定項，屬於 UI 狀態）。

**修正完這三項之後**，只剩 3 個欄位是「除了自己的寫入路徑以外，沒有任何讀者」。它們才是真正偽裝成可用的開關。

---

## 2. 判定總表

入口位置的 `SettingsScreen.kt` 行號是 **v1.04 重構後**的當前行號（該檔現為 2524 行）。重構後每個設定項都歸屬到一個 `SettingsRoute` 頁面，行號僅供本版本定位用；跨版本引用請以「路由頁面 + 標籤存取器名」為準。

### 2.1 外觀與主題

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 主題模式 | `ui/SettingsScreen.kt:430` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 主題風格 | `ui/SettingsScreen.kt:443` | 保留 | 讀者 `ui/theme/LumoTheme.kt` ×2 |
| 主題色 | `ui/SettingsScreen.kt:456` | 保留 | 讀者 `ui/theme/LumoTheme.kt` ×2 |
| 自訂色相旗標 | `ui/SettingsScreen.kt:459,461` | 保留 | UI 附屬狀態，見 §1.3 假陽性一 |
| 背景 | `ui/SettingsScreen.kt:470` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 背景自訂色 | `ui/SettingsScreen.kt:500,506` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 背景亮度 | `ui/SettingsScreen.kt:515` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 背景不透明度 | `ui/SettingsScreen.kt:524` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 背景光暈 | `ui/SettingsScreen.kt:533` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 卡片樣式 | `ui/SettingsScreen.kt:543` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕質感 | `ui/SettingsScreen.kt:556` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕按下動效 | `ui/SettingsScreen.kt:597` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕高光 | `ui/SettingsScreen.kt:603` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕形狀 | `ui/SettingsScreen.kt:609` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕圓角 | `ui/SettingsScreen.kt:615` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕陰影 | `ui/SettingsScreen.kt:625` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 按鈕自訂色 | `ui/SettingsScreen.kt:634,640` | 保留 | 讀者 `ui/components/Surfaces.kt` |
| 圓角 | `ui/SettingsScreen.kt:649` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 卡片不透明度 | `ui/SettingsScreen.kt:658` | 保留 | 讀者 `ui/theme/LumoTheme.kt` |
| 版面模式 | `ui/SettingsScreen.kt:672` | 保留 | 讀者 `ui/HomeScreen.kt` |
| 置頂優先 | `ui/SettingsScreen.kt:684` | 保留 | 讀者 `ui/HomeScreen.kt` |
| 亮度跟隨系統 | `ui/SettingsScreen.kt:691` | 保留 | 讀者 `ui/LumoApp.kt` |
| 亮度視窗值 | `ui/SettingsScreen.kt:699` | 保留 | 讀者 `ui/LumoApp.kt` |
| 語言 | `ui/SettingsScreen.kt:713` | 保留 | 讀者 `ui/LumoApp.kt`、`MainActivity` |
| 重置外觀 | `ui/SettingsScreen.kt:419` | 保留 | 真實動作，v1.04 移到外觀樞紐頁 |

### 2.2 瀏覽器與網頁

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 自動回退模式 | `ui/SettingsScreen.kt:730` | 保留 | 讀者 `browser/AutoMode.kt` |
| 網頁文字縮放 | `ui/SettingsScreen.kt:737` | 保留 | 讀者 `browser/BrowserConfigManager.kt` |
| 沉浸式全螢幕 | `ui/SettingsScreen.kt:750` | 保留 | 讀者 `ui/BrowserScreen.kt` |
| ~~預設瀏覽模式~~ | ~~原 `ui/SettingsScreen.kt:505-518`~~ | **刪除** | **假開關**，見 §3.1 |
| 相容模式／AUTO | `browser/AutoMode.kt` | 保留 | AUTO 升級階梯，有真實狀態機 |

### 2.3 收藏與網址管理

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 匯出備份 | `ui/SettingsScreen.kt:762` | 保留 | 真實動作 |
| 匯入備份 | `ui/SettingsScreen.kt:770` | 保留 | 真實動作 |
| 清除瀏覽資料 | `ui/SettingsScreen.kt:778` | 保留 | 真實動作，v1.04 起走統一確認對話框 |
| 重置設定 | `ui/SettingsScreen.kt:786` | 保留 | 真實動作，v1.04 起走統一確認對話框 |
| 拖曳提示狀態 | `ui/HomeScreen.kt:210` | 保留 | 非設定項，見 §1.3 假陽性三 |

### 2.4 音效與觸感

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 音效總開關 | `ui/SettingsScreen.kt:799` | 保留 | 讀者 `feedback/SoundSynth.kt` |
| 音量 | `ui/SettingsScreen.kt:806` | 保留 | 同上 |
| 音效風格 | `ui/SettingsScreen.kt:819` | 保留 | 同上（76 個 WAV 資源） |
| 試聽 | `ui/SettingsScreen.kt:831` | 保留 | 真實動作 |
| 各組音效開關 ×3 | `ui/SettingsScreen.kt:846-863` | 保留 | 讀者 `feedback/SoundCue.kt`；三組為 `soundLoadingCues`／`soundVpnCues`／`soundStartupCues` |
| 尊重靜音模式 | `ui/SettingsScreen.kt:865` | 保留 | 讀者 `feedback/SoundSynth.kt` |
| 觸覺總開關 | `ui/SettingsScreen.kt:873` | 保留 | 讀者 `feedback/` |
| 觸覺強度 | `ui/SettingsScreen.kt:880` | 保留 | 同上 |
| 觸覺跟隨音效 | `ui/SettingsScreen.kt:889` | 保留 | 同上 |

### 2.5 網路與 VPN

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| VPN 狀態面板 | `ui/SettingsScreen.kt:906` | 保留 | 真實狀態讀取 |
| VPN 輔助總開關 | `ui/SettingsScreen.kt:918` | 保留 | 讀者 `vpn/VpnController.kt` |
| 網路輔助模式 | `ui/SettingsScreen.kt:925` | 保留 | 讀者 `browser/BrowserConfigManager.kt` |
| 顯示 VPN 提示 | `ui/SettingsScreen.kt:939` | 保留 | 讀者 `ui/BrowserScreen.kt` |
| 允許使用者發起連線 | `ui/SettingsScreen.kt:945` | 保留 | 讀者 `vpn/VpnController.kt` |
| 連上 VPN 後重試 | `ui/SettingsScreen.kt:951` | 保留 | 讀者 `ui/BrowserScreen.kt` |
| 使用者已配置 VPN | `ui/SettingsScreen.kt:964` | 保留 | 讀者 `vpn/VpnAppDirectory.kt` |
| 網路輔助說明已讀 | `ui/SettingsScreen.kt:994` | 保留 | 真實讀者（見下） |
| 前往系統 VPN 設定 | `ui/SettingsScreen.kt:971` | 保留 | 真實動作 |
| ~~開啟前檢查網路~~ | ~~原 `ui/SettingsScreen.kt:613-617`~~ | **刪除** | **假開關**，見 §3.2 |
| ~~記住每個網址的選擇~~ | ~~原 `ui/SettingsScreen.kt:625-629`~~ | **刪除** | **假開關**，見 §3.3 |

`networkAssistNoteAcknowledged` 在 `carrier` 之外沒有讀者，但它的讀者就在設定頁本身，是**真實**的：

```
ui/SettingsScreen.kt:994    if (!preferences.networkAssistNoteAcknowledged) {
```

它控制 VPN 說明下方的「我已閱讀」按鈕要不要出現。判定：**保留**。

### 2.6 性能與動畫

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 簡化特效 | `ui/SettingsScreen.kt:1010` | 保留 | 讀者 `ui/theme/Motion.kt` |
| 動畫預設 | `ui/SettingsScreen.kt:1021` | 保留 | 同上 |
| 動畫強度 | `ui/SettingsScreen.kt:1034` | 保留 | 同上 |
| 減少動效 | `ui/SettingsScreen.kt:1043` | 保留 | 曾經是死偏好，v2.2 已修 |

### 2.7 權限與隱私

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 無障礙輔助服務 | `ui/SettingsScreen.kt:1060-1066` | 保留 | 狀態來自 `ui/LumoApp.kt:538-540`，見 §1.3 假陽性二 |
| 本機資料承諾 | `ui/SettingsScreen.kt:1074-1083` | 保留 | 靜態聲明，非開關 |

### 2.8 關於 LUMO

| 功能名 | 入口位置 | 判定 | 原因 |
|---|---|---|---|
| 版本號 | `ui/SettingsScreen.kt:1089` | 保留 | `BuildConfig` |
| 建置時間 | `ui/SettingsScreen.kt:1097` | 保留 | `BuildConfig` |
| 開源許可 | `ui/SettingsScreen.kt:1105` | 新增 | v1.04 新增三級頁，列出實際相依 |

---

## 3. 判定為「刪除」的項目（本輪已執行）

**判定「刪除」的定義**：沒有讀者，或與其他項目重複。相對地，**「暫緩」**（§6）指的是有真實讀者但本輪不改。

三個項目都是**同一個病**：有一個可切換的開關、有一個持久化欄位、有一條完整的寫入鏈路，但**沒有任何程式碼讀它**。使用者切換它，設定被存下來，然後什麼都不會發生。

### 3.1 預設瀏覽模式 `defaultBrowserMode`

- 欄位宣告：`data/Model.kt:347`
- 持久化：`data/SettingsRepository.kt:84,175-176,261`
- 寫入鏈路：`ui/LumoApp.kt:487` → `ui/MainViewModel.kt:394` → `SettingsRepository.setDefaultBrowserMode`
- **它應該影響的地方**：`ui/BrowserScreen.kt:316` 是這樣決定模式的：

  ```
  val effectiveMode = if (config.mode == BrowserMode.Auto) auto.mode else config.mode
  ```

  而 `config` 來自 `data/BrowserConfig.kt:47` 的 `get() = SiteConfig(mode = browserMode, …)`——**每個網址自己存在資料庫裡的模式**，資料庫預設值是 `'auto'`。

  也就是說：新模式網站走 AUTO 階梯，已設定過的網站走它自己的記錄，**`defaultBrowserMode` 從頭到尾沒有被任何一行程式碼讀取**。

→ **已刪除 UI**（原 `ui/SettingsScreen.kt:505-518`，共 14 行：`LabeledRow(s.modeDefaultBadge)` + `ChoiceChips` + `s.modeHint(...)` + 分隔線）。
→ **欄位與 DataStore key 保留**：`data/**` 不在本輪可改範圍，且刪 key 會讓既有安裝的資料變成無主資料。

### 3.2 開啟前檢查網路 `checkNetworkBeforeOpening`

- 欄位宣告：`data/Model.kt:376`
- 持久化：`data/SettingsRepository.kt:94,190-191,273`
- 寫入鏈路：`ui/LumoApp.kt:503` → `ui/MainViewModel.kt:410`

唯一提到「網路」的相關程式碼是 `browser/NetworkStatus.kt:178`：

```
if (!network.availability.hasNetwork) return PageFailure.NoNetwork
```

但這是**載入失敗之後的分類**，不是「開啟之前先檢查」。而且它讀的是 `network`（當下的網路狀態），不是這個偏好。

→ **已刪除 UI**（原 `ui/SettingsScreen.kt:613-617`）。
→ **欄位與 key 保留**，理由同上。

### 3.3 記住每個網址的選擇 `rememberPerSiteNetworkAssist`

- 欄位宣告：`data/Model.kt:380`
- 持久化：`data/SettingsRepository.kt:96,194-195,276-277`
- 寫入鏈路：`ui/LumoApp.kt:505` → `ui/MainViewModel.kt:412-413`

實際的逐網址行為讀的是別的東西——`ui/BrowserScreen.kt:899` 的 `siteAssist = config.networkAssist` 與 `:902` 的 `onPickSiteAssist`，資料來源同樣是每網址的資料庫記錄，不是這個全域偏好。

→ **已刪除 UI**（原 `ui/SettingsScreen.kt:625-629`）。
→ **欄位與 key 保留**，理由同上。

### 3.4 兩個死字串資源

`app/src/main/res/values/strings.xml:61-62`（以及 `values-zh`、`values-zh-rTW` 的對應行；行號為審查當時的座標，現已刪除）定義了：

- `browser_address`（"Address" / 「地址」/ 「網址」）
- `browser_loading`（"Loading…" / 「加载中…」 / 「載入中…」）

對整個 `app/src` 遞迴搜尋 `R.string.browser_address`、`@string/browser_address` 及其同類，**除了定義本身以外沒有任何引用**。它們是早期網址列設計的殘留。

判定：**刪除**。理由：沒有讀者。注意這與「暫緩」不同——「暫緩」是有讀者但本輪不改。

**已於本輪執行。** `D:\deepseek\Website\_v104_dropstrings.py` 刪掉六行（3 檔 × 2 條），並在寫檔前後各自斷言：

- 每檔恰好少 2 條，三份各 **418 → 416**；
- 三份 `<string name="…">` 名稱集合仍然完全相同（416 個名字）；
- 純 CRLF 保持：`values` CRLF 461、`values-zh` 455、`values-zh-rTW` 455，**bare LF 皆 0**；
- `values/strings.xml` 裸 ASCII 撇號仍為 **0**。

`_v104_verify.py` 重跑結果：`resources=416 … referenced anywhere in app/src=416 dead=0`、`VERDICT: OK`。刪除後 `values` 34589 bytes、`values-zh` 33641、`values-zh-rTW` 33818。

> **對照**：同樣在搜尋中出現的 `app_name` 也「沒有被 `LumoStrings.kt` 使用」，但它**不是**死的——`ui/components/StartupAnimation.kt:174` 有 `stringResource(R.string.app_name)`，`AndroidManifest.xml:93` 有 `android:label="@string/app_name"`。三個 `accessibility_assist_service_*` 同樣由 `res/xml/lumo_accessibility_service.xml` 與 manifest 使用。**掃描必須涵蓋 XML 與 manifest，否則會誤殺。**

### 3.5 三個假開關的回呼參數（**§3.1–§3.3 的連帶清理**）

把 §3.1、§3.2、§3.3 三個假開關的 UI 刪掉之後，`SettingsScreen(...)` 的三個回呼就再也沒有呼叫點。編譯器對它們各報一條警告：

```
w: .../ui/SettingsScreen.kt:180:5 Parameter 'onDefaultBrowserMode' is never used
w: .../ui/SettingsScreen.kt:196:5 Parameter 'onCheckNetworkBeforeOpening' is never used
w: .../ui/SettingsScreen.kt:198:5 Parameter 'onRememberPerSiteNetworkAssist' is never used
```

判定：**刪除**（不是「暫緩」——沒有呼叫點的參數不會有讀者）。`_v104_fixwarn.py` 刪掉 `ui/SettingsScreen.kt` 的這三行、同檔已無用處的 `import com.lumobrowser.app.data.BrowserMode`，以及 `ui/LumoApp.kt` 的三個對應引數。

**沒有選擇 `@Suppress("UNUSED_PARAMETER")`**：它作用在整個函式上，會一併關掉這個函式的 `UNUSED_PARAMETER` 診斷，而 1.03 輪正是靠這條診斷抓到 `notePressed(attempt)` 收下參數卻從不讀它、以及 `AccessibilityTextField` 的 `onSubmit` 從未被呼叫這兩個真缺陷。**用一條抓過真 bug 的診斷去換三個死參數的安靜，代價不對等。**

**但要注意這三個欄位本身沒有被清掉**：`data/Model.kt:347` `defaultBrowserMode`、`:376` `checkNetworkBeforeOpening`、`:380` `rememberPerSiteNetworkAssist` 仍存在，也仍然是**只有 setter 沒有讀者的孤兒**（`data/**` 不在本輪可動範圍）。刪掉回呼只是不再從設定畫面餵它們，欄位與 DataStore key 都還在。

**這是一次 `app/src/main/` 的改動**，所以本輪的 APK 必須在這次改動之後重建，不能沿用改動前的產物。

---

## 4. 判定為「修復」的項目（本會話較早階段執行，今日原始碼複查確認仍在）

### 4.1 深色主題下換頁閃白

`browser/LumoWebView.kt` 的 `init` 原本從未呼叫 `setBackgroundColor(...)`，程式化建立的 `WebView` 預設是不透明白底，深色主題下換頁間隙整屏閃白。

現況：

```
browser/LumoWebView.kt:330    setBackgroundColor(DEFAULT_SURFACE_COLOR)
browser/LumoWebView.kt:343    setBackgroundColor(argb)
```

### 4.2 返回上一頁不保留捲動位置

新增 `browser/ScrollMemory.kt`，在 `onPageFinished` 時 `scrollY` 已歸零之前先把位置記下。

### 4.3 使用者主動停止載入被誤判為「空頁面」，靜默改掉該網站的模式

`onPageFinished` 在**取消載入後照常觸發**，此時文件是空的。原本的探針會回報 `rendered=false`，接著 `advanceAuto(classifyFailure(null, 0, documentIsEmpty = true))` **推進 AUTO 升級階梯，靜默改掉該網站的模式**——使用者只是按了停止，網站模式卻被改了。

現況（`userCancelledLoad` 旗標）：

```
browser/LumoWebView.kt:169    private var userCancelledLoad: Boolean = false
browser/LumoWebView.kt:415    userCancelledLoad = true          // stopLoadingPage()
browser/LumoWebView.kt:677    userCancelledLoad = false         // 新載入開始
browser/LumoWebView.kt:694    val cancelled = userCancelledLoad // onPageFinished()
browser/LumoWebView.kt:701    if (!cancelled && configNow != null && planNow != null &&
browser/LumoWebView.kt:702        shouldProbeRenderedContent(url, planNow.javaScriptEnabled)
```

### 4.4 切換深淺主題後，網頁仍被強制暗化

`browser/BrowserConfigManager.kt:173` 的 `algorithmicDarkening = darkTheme` 是**由主題推導**的，但 `ui/BrowserScreen.kt` 套用計畫的副作用原本只以 `resolved` 為 key，主題變更不會重跑。

現況：`ui/BrowserScreen.kt:415` 已改為 `LaunchedEffect(resolved, theme.isDark)`。

---

## 5. 判定為「保留」的項目

- §2 總表中所有標「保留」的列。
- 三個在 §1.3 被誤判、經修正後確認為真實可用的欄位：`useCustomAccent`、以及三個無障礙欄位。
- `networkAssistNoteAcknowledged`：讀者在 `ui/SettingsScreen.kt:994`。

---

## 6. 判定為「暫緩」的項目

**「暫緩」＝有真實讀者，但本輪不改。**

| 功能名 | 位置 | 為什麼暫緩 |
|---|---|---|
| VPN 設定檔 `vpnProfile` | `data/Model.kt:399`，讀者 `ui/MainViewModel.kt:444` | 有真實讀者（`VpnProfileCodec.decode(it.vpnProfile)`），但**刻意沒有編輯介面**。且本版本 `vpn/TunnelBackends` 註冊表刻意為空，無法建立真實連線，做了介面也沒有東西可用。 |
| 拖曳提示 `dragHintShown` | `data/Model.kt:319`，讀者 `ui/HomeScreen.kt:210` | 有真實讀者。它不是設定項，是一次性 UI 狀態，不該出現在設定頁。 |

---

## 7. 本輪**未能**驗證的事項

這一節是誠實聲明，不是免責條款。

1. **真機端到端未驗證。**本輪只做了編譯與 JVM 單元測試，**沒有**在任何實體裝置或模擬器上運行。
2. **無障礙輔助服務的授權流程未在真機驗證。**`isEnabledViaSecureSetting` 的判定邏輯有單元測試（v1.03 輪 59 例），但「使用者在 Android 設定裡打開服務後，本應用能否正確重查到」這件事沒有真機驗證過。
3. **VPN 真實連線在本版本仍然不可用。**`vpn/TunnelBackends` 註冊表刻意為空，這是設計決定而非缺陷。使用者看到的「已連接」只會在本應用自己建立的隧道上出現；只偵測到系統 VPN 網路時報告的是獨立值 `DetectedNetwork`。
4. **本輪沒有執行 instrumentation test。**`app/src/androidTest/java/com/lumobrowser/app/LumoBrowserUiTest.kt` 未執行（需要裝置）。
5. **設定搜尋只做到「跳到該項所在的頁面」，沒有做到逐列高亮。**任務書要求「結果直接跳到該項所在頁面並高亮／定位」，本輪實作的是前者。**這一項是未完成，不是已完成。**詳見 `docs/SETTINGS_STRUCTURE.md` §5。

---

```text
UserPreferences fields: 58

themeMode                                  ThemeMode              READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

themeStyle                                 ThemeStyle             READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x2
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

accentHue                                  Float                  READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x2
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

useCustomAccent                            Boolean                **ORPHAN** exposed
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

backgroundStyle                            BackgroundStyle        READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

defaultCardStyle                           CardStyle              READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

buttonTexture                              ButtonTexture          READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x2
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x3
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

cornerRadiusDp                             Int                    READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

cardAlpha                                  Float                  READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x3
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

windowBrightnessFollowSystem               Boolean                READER     exposed
      read by   com\lumobrowser\app\MainActivity.kt                  x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

windowBrightness                           Float                  READER     exposed
      read by   com\lumobrowser\app\MainActivity.kt                  x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

appLanguage                                AppLanguage            READER     exposed
      read by   com\lumobrowser\app\LumoApplication.kt               x4
      read by   com\lumobrowser\app\MainActivity.kt                  x3
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x2
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x3
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

animationPreset                            AnimationPreset        READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

animationStrength                          Float                  READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

reduceMotion                               Boolean                READER     exposed
      read by   com\lumobrowser\app\MainActivity.kt                  x3
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x3
      read by   com\lumobrowser\app\ui\theme\Motion.kt               x5
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

soundEnabled                               Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x2
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x6

soundVolume                                Float                  READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

hapticsEnabled                             Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x4

soundStyle                                 SoundStyle             READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x4

soundStartupCues                           Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\ui\SoundLabels.kt                x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

soundLoadingCues                           Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\ui\SoundLabels.kt                x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

soundVpnCues                               Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\ui\SoundLabels.kt                x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

soundRespectSilentMode                     Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

hapticStrength                             HapticStrength         READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

hapticsFollowSound                         Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x2
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

pinnedFirst                                Boolean                READER     exposed
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

layoutMode                                 LayoutMode             READER     exposed
      read by   com\lumobrowser\app\ui\HomeScreen.kt                 x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

dragHintShown                              Boolean                READER     NOT-IN-UI
      read by   com\lumobrowser\app\ui\HomeScreen.kt                 x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

backgroundUseCustomColor                   Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

backgroundColorArgb                        Long                   READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

backgroundBrightness                       Float                  READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

backgroundOpacity                          Float                  READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

backgroundGlowEnabled                      Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

buttonCustomShape                          Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

buttonCornerRadiusDp                       Int                    READER     exposed
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

buttonElevationDp                          Int                    READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

buttonUseCustomColor                       Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

buttonColorArgb                            Long                   READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

buttonHighlight                            Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      read by   com\lumobrowser\app\util\LumoStrings.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\LumoStringsSnapshot.kt        x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x3

buttonPressAnimation                       Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

defaultBrowserMode                         BrowserMode            **ORPHAN** NOT-IN-UI
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

browserAutoFallback                        Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

browserTextZoomPercent                     Int                    READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

browserImmersiveFullscreen                 Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\BrowserScreen.kt              x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

vpnAssistEnabled                           Boolean                READER     exposed
      read by   com\lumobrowser\app\browser\NetworkStatus.kt         x1
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

networkAssistMode                          NetworkAssistMode      READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x2

showVpnHintOnSuspectedRestriction          Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x2
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

allowUserInitiatedVpnConnect               Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

checkNetworkBeforeOpening                  Boolean                **ORPHAN** NOT-IN-UI
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

retryAfterVpnConnected                     Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\BrowserScreen.kt              x2
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x2
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

rememberPerSiteNetworkAssist               Boolean                **ORPHAN** NOT-IN-UI
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

vpnConfiguredByUser                        Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

vpnProfile                                 String                 READER     NOT-IN-UI
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x5
      carrier   com\lumobrowser\app\data\Model.kt                    x3
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

networkAssistNoteAcknowledged              Boolean                **ORPHAN** exposed
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

simplifiedEffects                          Boolean                READER     exposed
      read by   com\lumobrowser\app\ui\components\Surfaces.kt        x4
      read by   com\lumobrowser\app\ui\theme\LumoTheme.kt            x3
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5
      carrier   com\lumobrowser\app\ui\SettingsScreen.kt             x1

accessibilityAssistEnabled                 Boolean                READER     NOT-IN-UI
      read by   com\lumobrowser\app\accessibility\AssistSettings.kt  x1
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

accessibilityAssistTargets                 String                 READER     NOT-IN-UI
      read by   com\lumobrowser\app\accessibility\AssistSettings.kt  x1
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      read by   com\lumobrowser\app\ui\MainViewModel.kt              x2
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

accessibilityAssistNoteAcknowledged        Boolean                READER     NOT-IN-UI
      read by   com\lumobrowser\app\ui\LumoApp.kt                    x1
      carrier   com\lumobrowser\app\data\Model.kt                    x1
      carrier   com\lumobrowser\app\data\SettingsRepository.kt       x5

==============================================================================
ORPHANS (no reader outside Model/SettingsRepository/SettingsScreen): 5
   useCustomAccent : Boolean
   defaultBrowserMode : BrowserMode
   checkNetworkBeforeOpening : Boolean
   rememberPerSiteNetworkAssist : Boolean
   networkAssistNoteAcknowledged : Boolean

NOT OFFERED IN SettingsScreen.kt: 8
   dragHintShown : Boolean
   defaultBrowserMode : BrowserMode
   checkNetworkBeforeOpening : Boolean
   rememberPerSiteNetworkAssist : Boolean
   vpnProfile : String
   accessibilityAssistEnabled : Boolean
   accessibilityAssistTargets : String
   accessibilityAssistNoteAcknowledged : Boolean
```
