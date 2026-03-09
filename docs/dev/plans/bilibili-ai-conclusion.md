# B站 AI 总结功能开发计划

## 需求

在收藏夹视频列表（`BilibiliVideoList`）和素材库（`material-library`）中，
为每个 B 站视频提供"B站AI总结"按钮，点击后弹出模态框展示 AI 总结内容。
部分视频无 AI 总结，需优雅处理（显示"暂无 AI 总结"）。

---

## 数据流

```
bilibili-api-python
  Video.get_ai_conclusion(page_index=0)
      ↓
Python FastAPI  GET /bilibili/video/ai-conclusion/{bvid}/{page_index}
      ↓
Kotlin Route    BilibiliVideoAiConclusionRoute  POST /api/v1/BilibiliVideoAiConclusionRoute
  ├─ is_update=true（手动打开模态框）→ 直接调用 Python，写入缓存，按优先级返回
  └─ is_update=false（自动触发）→ 优先读缓存；无缓存且令牌充足时调用 Python
      ↓
前端按需请求（点击按钮时懒加载，传 is_update=true）
```

---

## 缓存表设计

### 表结构

```sql
CREATE TABLE IF NOT EXISTS bilibili_ai_conclusion_cache (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    bvid        TEXT NOT NULL,
    page_index  INTEGER NOT NULL DEFAULT 0,
    queried_at  INTEGER NOT NULL,   -- Unix 秒
    raw_result  TEXT NOT NULL,      -- 原始 JSON（不论成功失败）
    is_success  INTEGER NOT NULL DEFAULT 0  -- 0=失败, 1=成功
)
```

索引：`CREATE INDEX IF NOT EXISTS idx_bac_bvid_page ON bilibili_ai_conclusion_cache(bvid, page_index, queried_at DESC)`

### 查询优先级

1. 最新成功记录（`is_success=1 ORDER BY queried_at DESC LIMIT 1`）
2. 无成功则最新失败记录（`is_success=0 ORDER BY queried_at DESC LIMIT 1`）
3. 无任何记录 → null（触发实际查询）

---

## 接口设计

### Python 端（已完成）

`GET /bilibili/video/ai-conclusion/{bvid}/{page_index}`

### Kotlin 路由参数

```
POST /api/v1/BilibiliVideoAiConclusionRoute
Body: { "bvid": "BV1xxx", "page_index": 0, "is_update": true/false }
```

**后端令牌桶**（仅对 `is_update=false` 生效）：
- 每 5 秒补充 1 令牌，最多积累 3 个
- 令牌耗尽时返回 `{"code": -429, "message": "rate_limited"}`

**路由逻辑：**
1. 解析 `bvid`, `page_index`, `is_update`
2. `is_update=false` 时：先查缓存，命中则直接返回；无缓存且令牌不足则返回 rate_limited
3. 调用 Python，写入缓存（记录 `is_success`）
4. 按优先级返回（`queryBest`，可能有旧的成功记录）

---

## 文件清单

### 新建文件

| 文件 | 说明 |
|------|------|
| `shared/src/commonMain/.../db/BilibiliAiConclusionCache.kt` | 数据类 + Repo 接口 + Service 单例 |
| `shared/src/jvmMain/.../db/BilibiliAiConclusionCacheDb.kt` | JDBC 实现（建表、insert、queryBest） |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `shared/src/commonMain/.../api/routes/BilibiliVideoAiConclusionRoute.kt` | 增加 `is_update` 参数；缓存查询/写入；令牌桶限速 |
| `shared/src/jvmMain/.../api/FredicaApi.jvm.kt` | 初始化 `BilibiliAiConclusionCacheDb` + Service |
| `fredica-webui/app/components/bilibili/BilibiliAiConclusionModal.tsx` | 请求 body 增加 `is_update: true` |

---

## 详细实现

### 1. BilibiliAiConclusionCache.kt（commonMain）

```kotlin
@Serializable
data class BilibiliAiConclusionCache(
    val id: Long = 0,
    val bvid: String,
    @SerialName("page_index") val pageIndex: Int = 0,
    @SerialName("queried_at") val queriedAt: Long,
    @SerialName("raw_result") val rawResult: String,
    @SerialName("is_success") val isSuccess: Boolean,
)

interface BilibiliAiConclusionCacheRepo {
    suspend fun insert(entry: BilibiliAiConclusionCache)
    suspend fun queryBest(bvid: String, pageIndex: Int): BilibiliAiConclusionCache?
}

object BilibiliAiConclusionCacheService {
    private var _repo: BilibiliAiConclusionCacheRepo? = null
    val repo: BilibiliAiConclusionCacheRepo
        get() = _repo ?: error("BilibiliAiConclusionCacheService 未初始化")
    fun initialize(repo: BilibiliAiConclusionCacheRepo) { _repo = repo }
}
```

### 2. BilibiliAiConclusionCacheDb.kt（jvmMain）

`queryBest` 两次查询，先成功后失败：

```kotlin
override suspend fun queryBest(bvid: String, pageIndex: Int): BilibiliAiConclusionCache? =
    withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("""
                SELECT * FROM bilibili_ai_conclusion_cache
                WHERE bvid=? AND page_index=? AND is_success=1
                ORDER BY queried_at DESC LIMIT 1
            """).use { ps ->
                ps.setString(1, bvid); ps.setInt(2, pageIndex)
                ps.executeQuery().use { rs -> if (rs.next()) return@withContext rowToCache(rs) }
            }
            conn.prepareStatement("""
                SELECT * FROM bilibili_ai_conclusion_cache
                WHERE bvid=? AND page_index=? AND is_success=0
                ORDER BY queried_at DESC LIMIT 1
            """).use { ps ->
                ps.setString(1, bvid); ps.setInt(2, pageIndex)
                ps.executeQuery().use { rs -> if (rs.next()) rowToCache(rs) else null }
            }
        }
    }
```

### 3. BilibiliVideoAiConclusionRoute.kt（修改）

令牌桶（object 内 companion 或顶层 private）：

```kotlin
private val rateLimiter = object {
    private val maxTokens = 3
    private var tokens = maxTokens
    private var lastRefill = System.currentTimeMillis()
    private val refillIntervalMs = 5_000L
    @Synchronized fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        val refilled = ((now - lastRefill) / refillIntervalMs).toInt()
        if (refilled > 0) { tokens = minOf(maxTokens, tokens + refilled); lastRefill += refilled * refillIntervalMs }
        return if (tokens > 0) { tokens--; true } else false
    }
}
```

### 4. FredicaApi.jvm.kt（修改）

在现有初始化序列末尾追加：
```kotlin
val cacheDb = BilibiliAiConclusionCacheDb(db)
BilibiliAiConclusionCacheService.initialize(cacheDb)
```

### 5. BilibiliAiConclusionModal.tsx（修改）

```typescript
body: JSON.stringify({ bvid, page_index: pageIndex, is_update: true }),
```

---

## 关键文件路径

- `shared/src/commonMain/kotlin/com/github/project_fredica/db/BilibiliAiConclusionCache.kt`（新建）
- `shared/src/jvmMain/kotlin/com/github/project_fredica/db/BilibiliAiConclusionCacheDb.kt`（新建）
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/BilibiliVideoAiConclusionRoute.kt`（修改）
- `shared/src/jvmMain/kotlin/com/github/project_fredica/api/FredicaApi.jvm.kt`（修改）
- `fredica-webui/app/components/bilibili/BilibiliAiConclusionModal.tsx`（修改）

---

## 验证

```shell
./gradlew :shared:compileKotlinJvm
./gradlew :shared:jvmTest

# 手动测试：
# 1. 打开模态框（is_update=true）→ 调用 Python，写入缓存，返回结果
# 2. 再次打开同一视频（is_update=true）→ 再次调用 Python，更新缓存
# 3. 自动触发（is_update=false）→ 命中缓存，不调用 Python，日志显示"命中缓存"
# 4. 无缓存且令牌耗尽（is_update=false）→ 返回 code=-429
```
