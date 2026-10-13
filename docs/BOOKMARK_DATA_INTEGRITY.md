# 收藏数据完整性（1.09）

本文回答一个问题：**为什么本版不可能把两条收藏压成一条，也不可能把收藏的 URL 悄悄改写。**

结论先写：收藏表的**主键是自增的 `id`，不是 `url`**；`url` 列上没有任何唯一索引；所有 `UPDATE` 与 `DELETE` 语句都带 `WHERE id = :id`；而本版**没有新增任何一条写收藏的代码路径**。

---

## 一、表结构：主键是自增 id

`app/src/main/java/com/lumobrowser/app/data/Database.kt`：

```kotlin
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["sortOrder"]),
        Index(value = ["category"]),
        Index(value = ["isPinned"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "category") val category: String = "",
    @ColumnInfo(name = "isPinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "sortOrder") val sortOrder: Long = 0L,
    …
)
```

三条决定性的事实：

1. `@PrimaryKey(autoGenerate = true) val id: Long = 0L`：唯一性由数据库分配的行号保证，与 URL 无关。
2. 三个索引（`sortOrder`、`category`、`isPinned`）**都不是 unique 索引**，`url` 列上根本没有索引。
3. 因此「同一个 URL 存两次」在 schema 层面是合法的，不会被数据库合并或拒绝。

`@Insert(onConflict = OnConflictStrategy.REPLACE)` 里的 `REPLACE` 只在**主键或唯一索引冲突**时才触发，而上表没有任何 URL 相关的唯一约束——所以它永远不会因为「URL 相同」而替换掉一行。

---

## 二、所有会写收藏行的 SQL

`BookmarkDao`（`data/Database.kt`）里每一条会改动 `bookmarks` 的语句：

| 语句 | SQL / 注解 | 作用范围 |
| --- | --- | --- |
| `insert` | `@Insert(onConflict = OnConflictStrategy.REPLACE)` | 新行；`id = 0L` 时由数据库分配 |
| `insertAll` | `@Insert(onConflict = OnConflictStrategy.REPLACE)` | 同上，批量 |
| `update` | `@Update` | 按实体里的主键定位一行 |
| `delete` | `@Delete` | 按实体里的主键定位一行 |
| `deleteById` | `@Query("DELETE FROM bookmarks WHERE id = :id")` | 一行 |
| `updateSortOrder` | `@Query("UPDATE bookmarks SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :id")` | 一行，只改两列 |
| `updatePinned` | `@Query("UPDATE bookmarks SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")` | 一行，只改两列 |
| `updateBrowserMode` | `@Query("UPDATE bookmarks SET browserMode = :mode, lastSuccessfulMode = :lastSuccessful, lastModeChangeTime = :now WHERE id = :id")` | 一行，只改三列 |
| `updateNetworkAssist` | `@Query("UPDATE bookmarks SET networkAssist = :value, updatedAt = :now WHERE id = :id")` | 一行，只改两列 |
| `deleteAll` | `@Query("DELETE FROM bookmarks")` | **全表**，只在导入替换时使用 |

**每一条 `UPDATE` 和每一条定向 `DELETE` 都带 `WHERE id = :id`。** 唯一没有 `WHERE` 的是 `deleteAll`，它只在「整库导入替换」和「撤销整库导入」两条路径上出现（见 §四）。

读取侧还有 `observeAll`、`findById`、`getAllOnce`、`search`、`observeCategories`、`maxSortOrder`、`count`、`findByUrl`。其中 `findByUrl` 是 `@Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")`——它**只读**，且 `LIMIT 1` 本身就承认了「同 URL 可能不止一行」。

### 迁移只做加法

`@Database(entities = [BookmarkEntity::class], version = 3, exportSchema = true)`。两次迁移都只加列：

- `MIGRATION_1_2`：8 条 `ALTER TABLE bookmarks ADD COLUMN …`。
- `MIGRATION_2_3`：1 条 `ALTER TABLE bookmarks ADD COLUMN networkAssist TEXT NOT NULL DEFAULT 'global'`。

`Room.databaseBuilder(…, NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()`——**没有**任何 destructive fallback。数据库的 KDoc 写明了原因：已经装着收藏的安装必须原地升级，迁移校验失败时让 Room 抛异常才是正确结果，为了消掉错误而丢掉用户的表不是。

---

## 三、写入被收在一层里

只有 `BookmarkRepository` 拿得到 DAO：

```kotlin
// app/src/main/java/com/lumobrowser/app/LumoApplication.kt
val bookmarks: BookmarkRepository by lazy { BookmarkRepository(database.bookmarkDao()) }
```

全工程里 `dao.` 的调用只出现在 `data/BookmarkRepository.kt` 内部（`insert`、`update`、`delete`、`deleteById`、`restore`、`updatePinned`、`updateSortOrder`、`deleteAll`、`insertAll`、`updateBrowserMode`），没有任何 UI 代码直接碰 DAO。

`BookmarkRepository` 里几个值得单独指出的行为：

| 方法 | 关键行为 |
| --- | --- |
| `add(title, url, description, category, pinned, siteConfig, now)` | 先取 `maxSortOrder()`，新行 `sortOrder = if (pinned) -next else next`；写入前 URL 过 `UrlTools.normalize(url)` |
| `update(bookmark, now)` | **先 `dao.findById(bookmark.id)`**，找不到就返回 `false`（不新建行）；写入时显式保留 `sortOrder = existing.sortOrder` 与 `createdAt = existing.createdAt` |
| `restore(bookmark)` | `dao.insert(bookmark.toEntity())`，**带着原 id 插回**——撤销删除时行号不变 |
| `applyOrder(orderedIds, now)` | 逐行 `dao.updateSortOrder(id, (index + 1) * STEP, now)`，一次一行 |
| `replaceAll(bookmarks)` | `dao.deleteAll()` + `dao.insertAll(... copy(id = 0L))`，**唯一**会重建整张表的操作 |

`update` 的写法是关键：改一条收藏必须先读到那一行，读不到就什么也不做。因此不存在「按 URL 找到一条就覆盖」的代码。

---

## 四、收藏行会被写入的每一处

全部写调用都在 `app/src/main/java/com/lumobrowser/app/ui/MainViewModel.kt`（`repository` 就是 `LumoApplication.bookmarks`）：

| 行 | 调用 | 触发它的用户动作 |
| --- | --- | --- |
| 223 | `repository.add(title, url, description, category, pinned, siteConfig)` | 添加收藏 |
| 233 | `repository.update(bookmark)` | 编辑后保存 |
| 252 | `repository.updateBrowserMode(id, config.mode, config.lastSuccessfulMode)` | 某个站点的浏览模式首次生效（因此刻意不出声） |
| 259 | `repository.setPinned(bookmark.id, pinned)` | 置顶 / 取消置顶 |
| 275 | `repository.delete(bookmark.id)` | 删除一条收藏（随后写入撤销快照） |
| 287 | `repository.applyOrder(ordered.map { it.id })` | 拖动排序结束 |
| 296 | `UndoKind.Delete -> snapshot.bookmarks.forEach { repository.restore(it) }` | 撤销删除 |
| 298 | `UndoKind.Restore -> repository.replaceAll(snapshot.bookmarks)` | 撤销「恢复」 |
| 341 | `repository.replaceAll(pending.result.bookmarks)` | 导入备份（整体替换） |
| 344 | `pending.result.bookmarks.forEach { repository.restore(it) }` | 导入失败后的回滚 |

读取侧只有两处：`repository.search(query)`（119 行，搜索）、`repository.allOnce()`（340 行，导入前取快照）。收藏列表本身来自 `repository.bookmarks` 这个 `Flow`。

**列表筛选不写任何东西。** 搜索词与分类筛选都只写内存里的 `MutableStateFlow`：

```kotlin
// ui/MainViewModel.kt
private val searchQuery = MutableStateFlow("")
private val categoryFilter = MutableStateFlow<String?>(null)
…
searchQuery.value = value      // 205 行
categoryFilter.value = category // 209 行
```

---

## 五、本版没有新增任何收藏写入

1.09 改的是分类栏画在哪、以及返回键怎么走，**没有**碰 `BookmarkEntity`、`BookmarkDao`、`BookmarkRepository`，也**没有**新增任何写收藏的调用点。上面 §四 的清单就是当前工程的完整清单，新增一条收藏路径必然要在这张表里多出一行。

同一句话在 [`../CHANGELOG.md`](../CHANGELOG.md) 的 1.09 小节里也写了，两处指的是同一件事。

至于「返回时会不会把网页地址写回收藏」：不会。返回走的是 WebView 自己的历史，见 [`WEBVIEW_HISTORY_RESTORE.md`](WEBVIEW_HISTORY_RESTORE.md)——那条路径上没有任何收藏写入。

---

## 六、1.09 实测：八条收藏，八个地址（简表）

这一节记录的是一次**成品的走查**，不是代码审阅。步骤按需求方给的清单来：准备至少四条不同 URL 的收藏，记下每条的 URL 与唯一 id；反复切换布局；打开其中一条进二级页；按返回；退出；重新进收藏夹；再核一遍。

设备上的库一共 8 条（覆盖中文名、英文名、数字加空格、空分类、超长分类），走查结束后把库拉下来逐行打印，结果是：

| id | 分类 | URL | `updatedAt` |
| --- | --- | --- | --- |
| 1 | 学习 | `http://10.0.2.2:8765/booka.html` | 1000 |
| 2 | esim | `http://10.0.2.2:8765/bookb.html` | 1000 |
| 3 | API | `http://10.0.2.2:8765/bookc.html` | 1000 |
| 4 | 充值 | `http://10.0.2.2:8765/bookd.html` | 1000 |
| 5 | Wi-Fi 6 | `http://10.0.2.2:8765/long.html` | 1000 |
| 6 | 生活记录 | `http://10.0.2.2:8765/booka2.html` | 1000 |
| 7 | （空 = 未分类） | `http://10.0.2.2:8765/bookb2.html` | 1000 |
| 8 | averyverylongcategoryname | `http://10.0.2.2:8765/bookc2.html` | 1000 |

三个断言直接从这个结果读出来：

1. **8 行就是 8 行**，没有两条并成一条，没有多出副本。
2. **8 个 URL 互不相同**（`distinct urls: 8 of 8 rows`），没有出现「所有收藏都变成同一个地址」。
3. **`updatedAt` 全是 1000**，即整轮走查里没有任何一行被写过——分类筛选、布局切换、进二级页、按返回，这四件事都没有碰收藏表。

第 3 条是这张表里最有信息量的一列：`updatedAt` 是每次 `update` 都会刷新的列，它没动，就说明**没有任何一次 `UPDATE` 落到这些行上**，而不只是「值看起来还对」。

分类名里的中文在设备终端打印时会显示成乱码（控制台编码问题），所以上面是按 Unicode 码点核对过的：`学习` = `U+5B66 U+4E60`，`充值` = `U+5145 U+503C`，`生活记录` = `U+751F U+6D3B U+8BB0 U+5F55`。**中文分类名本身是完好的**，乱码只发生在终端显示这一层。

### 6.1 本轮没有做的事

- 本轮**没有**在**未修复**的构建上复现「收藏被覆盖」这个现象。这句话必须说清楚：本次走查证明的是**当前这棵树不会覆盖**，而不是「我们抓到了旧版本的现行」。根因那一层是代码审阅给出的（§一～§五），设备上验证的是结果。
- 本轮**没有**构造「导入备份后立刻切布局」这种组合用例。
- 本轮**没有**验证 `-wal` 里是否残留了未落盘的行——拉库时是把三个文件一起拉的，但没有单独比对只有主文件的情形。

### 6.2 返工之后：§六 的观察没有重跑

第一版交付后，用户退回了左侧栏的排布，`HomeScreen` 的结构与两个默认值（左栏宽度、默认布局）因此被改过。§六 的这张表是**返工之前**在同一台模拟器上测的。

如实记录：

- 返工改的是 `ui/HomeScreen.kt` 的布局分支、`ui/theme/LumoTheme.kt` 的一个尺寸常量、`data/Model.kt` 的两个默认值。数据层（`data/Database.kt`、`data/BookmarkRepository.kt`、`data/SettingsRepository.kt` 的读写路径）**一个字都没改**。
- 但「没改数据层所以行为不变」是**推断**，不是重测。要重测得重新造夹具、再走一遍分类切换与布局切换并把库拉下来逐行核对 `updatedAt`。
- 返工后**没有**做这件事。上面那三行断言仍然只对返工前的构建成立。

---

## 七、在测试机上查看一条收藏行

要核对一条收藏的 `id` / `url` / `category` / `updatedAt`，把数据库从设备上拉下来：

```powershell
adb shell am force-stop com.lumobrowser.app.debug
adb shell "run-as com.lumobrowser.app.debug cat databases/lumobrowser.db"   > lumobrowser.db
adb shell "run-as com.lumobrowser.app.debug cat databases/lumobrowser.db-wal" > lumobrowser.db-wal
adb shell "run-as com.lumobrowser.app.debug cat databases/lumobrowser.db-shm" > lumobrowser.db-shm
```

**必须把 `-wal` 与 `-shm` 一起拉下来。** Room 默认开启 WAL：刚提交的行可能还只在 `lumobrowser.db-wal` 里，主库文件本身看上去是旧的。只拉主文件会得到「提交过的行不见了」这种假象，而不是真实数据。三个文件放在同一目录后用 SQLite 打开，WAL 会被自动重放。

`com.lumobrowser.app.debug` 是 debug 包名（`applicationIdSuffix = ".debug"`）；release 包是 `com.lumobrowser.app`。`run-as` 只对 debuggable 的包装有效，所以这套命令只在 debug 包上可用——release 包要看数据库得先 root 或走 `adb backup`。

---

## 八、相关文档

- [`BOOKMARK_LAYOUT.md`](BOOKMARK_LAYOUT.md) —— 分类栏的两种位置与排版
- [`WEBVIEW_HISTORY_RESTORE.md`](WEBVIEW_HISTORY_RESTORE.md) —— 返回路径与 URL 的关系
- [`../CHANGELOG.md`](../CHANGELOG.md) —— 1.09 变更条目
