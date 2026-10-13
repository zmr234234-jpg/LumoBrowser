# LUMO v1.10 交付说明

版本目标 **LUMO v1.10**（`versionCode 12` / `versionName "1.10"`），基于 `1.09版本/LumoBrowser-v1.09-source.zip` 解出的干净源码树做增量修改。本文件如实记录改了什么、构建/测试的真实结果、以及未完成项。

---

## 1. 修改文件清单

### 新增（3 个）

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/data/CategoryValidator.kt` | 分类名校验规则（空/重复/超长，上限 20 Unicode 码点） |
| `app/src/main/java/com/lumobrowser/app/ui/CategoryRenameDialog.kt` | 重命名弹窗（自动聚焦、内联错误提示、键盘完成键保存） |
| `app/src/test/java/com/lumobrowser/app/data/CategoryValidatorTest.kt` | 校验器 9 例单测 |

### 修改（9 个）

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/lumobrowser/app/data/Database.kt` | BookmarkDao 新增 `renameCategory(old, newName, now)` targeted UPDATE |
| `app/src/main/java/com/lumobrowser/app/data/BookmarkRepository.kt` | 新增 `renameCategory` 透传方法 |
| `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt` | 新增 `renameCategory(original, rawNew)` + `UiMessage.CategoryRenamed` |
| `app/src/main/java/com/lumobrowser/app/ui/HomeScreen.kt` | 签名加 `onCategoryRename`；hoist `renamingCategory` 状态；CategoryRow/CategoryRail/CategoryRailItem 接长按；末尾渲染弹窗 |
| `app/src/main/java/com/lumobrowser/app/ui/components/Surfaces.kt` | LumoChip 加 `onLongClick` 参数，`clickable` → `combinedClickable` |
| `app/src/main/java/com/lumobrowser/app/ui/LumoApp.kt` | HomeScreen 调用传 `onCategoryRename`；消息 when 加 `CategoryRenamed` 分支 |
| `app/src/main/java/com/lumobrowser/app/ui/TestTags.kt` | 新增 `CATEGORY_RENAME_*` 5 个 testTag |
| `app/src/main/res/values*/strings.xml`（3 文件） | 新增 7 条文案（标题/提示/空/重复/超长/系统/成功） |
| `app/src/main/java/com/lumobrowser/app/util/LumoStrings.kt` + `ui/LumoStringsSnapshot.kt` | 新增对应访问器与 getter |
| `app/build.gradle.kts` | versionCode 11→12，versionName "1.09"→"1.10" |

> 说明：`LumoStrings.kt` 与 `LumoStringsSnapshot.kt` 在清单里算 2 个文件，加上 3 个 strings.xml 和其余源码，合计新增 3 + 修改 9 = 12 个源文件/资源文件。

---

## 2. 数据库 / 持久化变更说明

**没有 schema 迁移，`LumoDatabase` 版本保持 3。**

分类没有独立表、没有主键——分类就是 `bookmarks.category` 字符串列（`SELECT DISTINCT category FROM bookmarks WHERE category != ''`）。重命名通过一条 targeted UPDATE 原地改写该字符串：

```sql
UPDATE bookmarks SET category = :newName, updatedAt = :now WHERE category = :old
```

- 保留原分类身份（即字符串值本身被更新，而非插入新分类）。
- 该分类下所有书签的 `id / url / title / description / isPinned / sortOrder / createdAt / browserMode / networkAssist` 等字段**均不变**，仅 `category` 与 `updatedAt` 变化。
- 不新建表、不 drop 表、不拷贝数据，因此无迁移风险。重启应用后名称持久正确（数据存在 Room 数据库文件，由同一 DAO 读写）。

---

## 3. 版本变更记录

见 [`CHANGELOG.md`](CHANGELOG.md) 顶部「## 1.10 — versionCode 12」条目（本文件 §1 是它的精炼版）。

---

## 4. 测试报告

### 4.1 全量单元测试（Robolectric）

```
命令：gradle :app:testDebugUnitTest --console=plain
结果：BUILD SUCCESSFUL in 48s
classes=46  tests=740  failures=0  errors=0  skipped=0
```

1.09 基线为 44 类 718 例；本版新增 2 个测试类（`CategoryValidatorTest` + 9 例），`BookmarkRepositoryTest` 新增 2 例，合计新增 11 例，总量 46 类 740 例，**全部通过**。

### 4.2 新增测试覆盖点

- `renameCategoryRewritesEveryBookmarkInPlace`：改名后逐字段断言书签 id/url/title/sortOrder/isPinned/createdAt 不变，只 category 变；分类列表正确（`Projects`/`Reading`）。
- `renameCategoryReturnsZeroForUnknownCategory`：不存在的分类返回 0，不误伤其它分类。
- `CategoryValidatorTest`：正常名 / trim 首尾空白 / 空与纯空白 / 与其它分类重名 / 同名 no-op / 超 20 码点 / 恰好 20 / emoji 按 1 码点计 / 拉丁数字符号。

### 4.3 未执行的测试（如实说明）

- **未在真实 Android 设备上安装验证**（无连接设备）。长按手势、软键盘弹出、触感反馈的真实手感、音效实际听感均未在真机上确认。
- **音效资源未做试听**：本版没有新增音源，重命名动作复用既有 `SoundCue.Confirm`（wav 资源已在工程中、被既有测试 `SoundLibraryTest` 覆盖加载路径），但「人耳试听」未执行。
- **未做 `--no-build-cache` 冷构建**（与 1.06/1.07/1.08 相同的边界）。「0 个 e:」的准确范围是本轮改动的文件，不是全树冷编译。唯一一条 Kotlin 警告 `w: HomeScreen.kt:484 Parameter 'categories' is never used` 是**历史遗留**（`bookContentItems` 的 `categories` 参数在 1.09 就已存在且未使用，非本版引入）。

---

## 5. 构建产物

| 变体 | 路径 | 大小 | 包名 | 签名 |
| --- | --- | --- | --- | --- |
| Debug | `dist/LumoBrowser-v1.10-debug.apk` | 19,570,379 B | `com.lumobrowser.app.debug` | Android Debug |
| Release | `dist/LumoBrowser-v1.10-release.apk` | 13,217,288 B | `com.lumobrowser.app` | `CN=LumoBrowser`，证书 SHA-256 `4beb5163…75a9`（与 1.07/1.08 同一把密钥，可直接覆盖安装） |

- Debug `versionName = "1.10-debug"`，Release `versionName = "1.10"`。
- Release `apksigner verify --print-certs` 通过；两个包 applicationId 不同，可共存。

---

## 6. 已知问题与未完成项

1. **真机验证缺失**：长按手势、软键盘弹出、触感真实手感、音效听感未在真机确认（无设备）。
2. **音效未试听**：复用既有 `Confirm` cue，未做人耳试听。
3. **未做冷构建**：全树冷编译的「零警告」证据未补齐（历史遗留 1 条未使用参数警告）。
4. **系统保留分类策略**：当前仅 `UNCATEGORISED` 哨兵受保护（不可编辑）。项目数据模型里没有「全部」这个系统分类——「全部」只是分类栏里的一个过滤 chip（`selected == null`），不是可重命名的实体，因此不存在「把『全部』误重命名」的路径。若未来引入更多系统保留分类，需扩展 `CategoryValidator`/弹窗的保护判定。

---

## 7. 构建命令（复现）

```powershell
$env:JAVA_HOME="D:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="D:\AndroidDev\sdk"
$env:ANDROID_SDK_ROOT="D:\AndroidDev\sdk"
Set-Location D:\deepseek\LumoBrower\_work_1.10\LumoBrowser
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:assembleDebug :app:assembleRelease --console=plain
& 'D:\AndroidDev\gradle-8.6\bin\gradle.bat' :app:testDebugUnitTest --console=plain
```

（`local.properties` 已含 `sdk.dir=D\:\\AndroidDev\\sdk`；离线环境下 wrapper 会失败，故直接用本机 Gradle 8.6。）
