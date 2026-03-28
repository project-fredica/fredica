---
title: LLM 响应缓存与 LlmRequestService 重构计划
order: 530
---

# LLM 响应缓存与 LlmRequestService 重构计划

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-28
> **适用模块**：`shared/commonMain`（DB 层、Service 接口）、`shared/jvmMain`（HTTP 路由、LlmSseClient）、`composeApp`（JsBridge handler）、`fredica-webui`（缓存管理前端）
> **关联文档**：`llm-call-design.md`

---

## 1. 动机与目标

| 问题 | 现状 | 目标 |
|------|------|------|
| Token 浪费 | 相同 prompt 每次都发往 LLM | 命中缓存直接返回，节省 token 费用 |
| 响应延迟 | 流式 SSE 需等待 LLM 生成 | 缓存命中时毫秒级返回 |
| 路由/服务耦合 | 业务逻辑写在路由 handle 里 | 解耦为 `LlmRequestService`，路由/handler 只做适配 |
| messages 固化 | `message: String` 仅支持单条 user 消息 | 支持完整 messages JSON（multi-turn / system prompt） |
| 缓存不可废除 | 错误结果或过期输出无法刷新 | 手动废除、`disable_cache` 强制刷新、JSON 解析失败自动废除 |
| 缓存不可修订 | LLM 输出不满意只能重发 | 新增修订表（后期），后续调用优先返回修订结果 |

---

## 2. 整体架构

```
前端 (fredica-webui)
  │  POST /api/v1/LlmProxyChatRoute        (SSE 流式，主入口)
  │  POST /api/v1/LlmCacheInvalidateRoute  (废除缓存)
  │  GET  /api/v1/LlmCacheQueryRoute       (按 cache_key 查询单条，需提供 key)
  │  POST /api/v1/LlmCacheReviseRoute      (用户修订，后期)
  ▼
LlmProxyChatRoute        ← HTTP 适配层
LlmProxyChatJsMessageHandler  ← JsBridge 适配层（composeApp）
  ▼（两者都调用）
LlmRequestService        ← 服务层（commonMain 接口 / jvmMain 实现）
  ├─ [后期] 查 LlmResponseRevision（修订表，优先级最高）
  ├─ 查 LlmResponseCache（is_valid=1，disableCache=false 时）
  ├─ 并发防重：Mutex 分段锁（WeakHashMap<String, Mutex> 按 keyHash 存取）
  ├─ 未命中 → 调用 LlmSseClient.streamChat()
  └─ 写回缓存（INSERT OR REPLACE）

DB 层 (commonMain 接口 / jvmMain JDBC 实现)
  ├─ LlmResponseCache     ← Phase 1 新增
  └─ LlmResponseRevision  ← 后期新增，表结构已在本文预定义
```

---

## 3. 缓存键设计

### 3.1 key 与 keyHash 的区分

缓存使用 **两级键**：

| 字段 | 含义 | 构造方式 |
|------|------|---------|
| `cache_key` | 可读、可反序列化的原始键 | base64(model_name) `\|` base64(base_url) `\|` base64(messages_json_canonical)，三段独立编码 |
| `key_hash` | 不可逆的唯一标识，DB UNIQUE 约束 | SHA-256(`cache_key`) 的 hex 字符串（64 chars） |

`cache_key` 三段内容各自 Base64 编码后以 `|` 拼接（`|` 不在 Base64 字母表中，split 无歧义，即使原始内容含 `|` 也不影响解析）。前端查询时传 `key_hash`（不透传原始内容，保护隐私）；后端持有 `cache_key` 用于调试与审计。

### 3.2 messages_json_canonical

"规范化 messages JSON" 指将 messages 列表以固定字段顺序序列化为不含多余空格的 JSON 字符串，消除等价内容因序列化差异产生的键碰撞。

由于 Python openai 库的 message 类型定义复杂（`ChatCompletionMessageParam` 含 vision、function call 等多种形式），Kotlin 侧**不建立强类型 `LlmMessage` 数据类**，而是用 value class 包装原始 JSON 字符串。

规范化能力提取到通用工具层 `jsons.kt`，不与 LLM 业务绑定：

```kotlin
// commonMain/apputil/jsons.kt（新增）

/**
 * 对任意 JSON 字符串做 key 字母序规范化（递归排序所有层级的 object key）。
 * 用于消除相同结构因序列化 key 顺序不同产生的字符串差异。
 * 返回紧凑格式（无多余空格）的 JSON 字符串。
 */
fun jsonCanonical(json: String): String = jsonElementCanonical(
    AppUtil.GlobalVars.json.parseToJsonElement(json)
).toString()

private fun jsonElementCanonical(elem: JsonElement): JsonElement = when (elem) {
    is JsonObject -> JsonObject(
        elem.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to jsonElementCanonical(v) }
    )
    is JsonArray -> JsonArray(elem.map { jsonElementCanonical(it) })
    else -> elem   // JsonPrimitive / JsonNull，原样返回
}
```

LLM 侧的 `LlmMessagesJson` 直接复用此工具：

```kotlin
// commonMain/llm/LlmMessagesJson.kt

/** 包装 messages 列表的原始 JSON 字符串，避免强类型绑定造成的字段丢失 */
@JvmInline
value class LlmMessagesJson(val raw: String) {
    /** 规范化：调用通用 jsonCanonical，消除 key 顺序/空格差异 */
    fun canonicalize(): String = jsonCanonical(raw)
}
```

### 3.3 LlmCacheKeyUtil

`cache_key` 的格式为三段独立 Base64 拼接，用 `|` 分隔。每段各自 Base64 编码，因此分隔符 `|` 不会出现在 Base64 内容中（Base64 字母表不含 `|`），反序列化时直接按 `|` split 即可，无歧义。

```kotlin
// commonMain/llm/LlmCacheKeyUtil.kt

object LlmCacheKeyUtil {

    /**
     * 构建可读缓存键：
     *   base64(model_name) + "|" + base64(baseUrl) + "|" + base64(messagesJsonCanonical)
     * 每段独立编码，| 为固定分隔符（不出现在 Base64 字母表中）。
     */
    fun buildCacheKey(
        modelName: String,
        baseUrl: String,
        messagesJson: LlmMessagesJson,
    ): String {
        fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "${enc(modelName)}|${enc(baseUrl.trimEnd('/'))}|${enc(messagesJson.canonicalize())}"
    }

    /**
     * 从 cache_key 反序列化出原始三段内容（调试 / 审计用）。
     * 返回 Triple(modelName, baseUrl, messagesJsonCanonical)，失败返回 null。
     */
    fun parseCacheKey(cacheKey: String): Triple<String, String, String>? = runCatching {
        fun dec(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
        val parts = cacheKey.split("|")
        if (parts.size != 3) return null
        Triple(dec(parts[0]), dec(parts[1]), dec(parts[2]))
    }.getOrNull()

    /**
     * 计算 cache_key 的 SHA-256 哈希（DB UNIQUE 约束键）。
     */
    fun hashKey(cacheKey: String): String = sha256Hex(cacheKey)
}

// commonMain 声明，jvmMain actual 实现
expect fun sha256Hex(input: String): String

// jvmMain
actual fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

### 3.4 分段并发锁设计

目的：**防止同一请求被并发执行两次**（即缓存未命中时，两个并发调用同时穿透到 LLM，导致重复请求）。

方案：用一个线程安全的 `WeakHashMap<String, Mutex>` 按 `keyHash` 分配独立锁，锁释放后自动 GC（WeakReference 语义）。

```kotlin
// jvmMain/llm/LlmRequestServiceImpl.kt（内部实现细节）

private val keyMutexMap = java.util.WeakHashMap<String, kotlinx.coroutines.sync.Mutex>()

private fun getMutexForKey(keyHash: String): kotlinx.coroutines.sync.Mutex =
    synchronized(keyMutexMap) {
        keyMutexMap.getOrPut(keyHash) { kotlinx.coroutines.sync.Mutex() }
    }

// 使用：
getMutexForKey(keyHash).withLock {
    // double-check 缓存 → LLM 调用 → 写缓存
}
```

> **注意**：锁的粒度是单个 `keyHash`，并发调用不同 prompt 互不阻塞；相同 prompt 的第二个请求等锁后在锁内 double-check 命中缓存，不重复调用 LLM。

---

## 4. 数据模型设计

### 4.1 `llm_response_cache` 表

```sql
CREATE TABLE IF NOT EXISTS llm_response_cache (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash        TEXT NOT NULL UNIQUE,            -- SHA-256(cache_key) hex，DB 查询键
    cache_key       TEXT NOT NULL,                   -- Base64 可读键，反序列化用
    model_name      TEXT NOT NULL,
    base_url        TEXT NOT NULL,
    messages_json   TEXT NOT NULL,                   -- messages_json_canonical（规范化）
    response_text   TEXT NOT NULL,                   -- LLM 完整响应文本
    is_valid        INTEGER NOT NULL DEFAULT 1,      -- 0 = 已废除（软删除）
    created_at      INTEGER NOT NULL,                -- Unix 秒
    last_hit_at     INTEGER NOT NULL,
    hit_count       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lrc_hash ON llm_response_cache(key_hash);
```

Kotlin 数据类（`commonMain/db/LlmResponseCache.kt`）：

```kotlin
@Serializable
data class LlmResponseCache(
    val id: Long = 0,
    val keyHash: String,
    val cacheKey: String,
    val modelName: String,
    val baseUrl: String,
    val messagesJson: String,          // 规范化后的 messages JSON
    val responseText: String,
    val isValid: Boolean = true,
    val createdAt: Long,
    val lastHitAt: Long,
    val hitCount: Int = 0,
)

interface LlmResponseCacheRepo {
    /** 按 key_hash 查询，无效（is_valid=0）视为未命中 */
    suspend fun findByHash(keyHash: String): LlmResponseCache?
    /** INSERT OR REPLACE（以 key_hash UNIQUE 约束保证幂等） */
    suspend fun upsert(entry: LlmResponseCache)
    /** 更新命中统计 */
    suspend fun updateHit(keyHash: String, hitAt: Long)
    /** 废除缓存：is_valid = 0，不物理删除 */
    suspend fun invalidate(keyHash: String)
    /** 按 model_name + base_url 批量废除（模型配置变更时使用） */
    suspend fun invalidateByModel(modelName: String, baseUrl: String)
}

object LlmResponseCacheService {
    private var _repo: LlmResponseCacheRepo? = null
    val repo get() = _repo ?: error("LlmResponseCacheService 未初始化")
    fun initialize(repo: LlmResponseCacheRepo) { _repo = repo }
}
```

### 4.2 `llm_response_revision` 表（预定义，后期实现）

```sql
CREATE TABLE IF NOT EXISTS llm_response_revision (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash      TEXT NOT NULL,                     -- 对应缓存表的 key_hash
    cache_key     TEXT NOT NULL,
    model_name    TEXT NOT NULL,
    base_url      TEXT NOT NULL,
    messages_json TEXT NOT NULL,
    original_text TEXT NOT NULL,                     -- 修订前 LLM 输出快照
    revised_text  TEXT NOT NULL,                     -- 用户修订后文本
    revised_at    INTEGER NOT NULL,
    revised_by    TEXT NOT NULL DEFAULT 'user',
    is_active     INTEGER NOT NULL DEFAULT 1,
    note          TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_lrr_hash ON llm_response_revision(key_hash, is_active);
```

```kotlin
// 数据类与 Service 单例预留，后期实现时补充
@Serializable
data class LlmResponseRevision(
    val id: Long = 0,
    val keyHash: String,
    val cacheKey: String,
    val modelName: String,
    val baseUrl: String,
    val messagesJson: String,
    val originalText: String,
    val revisedText: String,
    val revisedAt: Long,
    val revisedBy: String = "user",
    val isActive: Boolean = true,
    val note: String = "",
)

interface LlmResponseRevisionRepo {
    suspend fun findActiveByHash(keyHash: String): LlmResponseRevision?
    suspend fun insert(entry: LlmResponseRevision)
    suspend fun revoke(id: Long)
}

object LlmResponseRevisionService {
    private var _repo: LlmResponseRevisionRepo? = null
    val repo get() = _repo ?: error("LlmResponseRevisionService 未初始化")
    fun initialize(repo: LlmResponseRevisionRepo) { _repo = repo }
}
```

---

## 5. LlmRequestService 设计

### 5.1 入参与返回类型

```kotlin
// commonMain/llm/LlmRequestTypes.kt

/**
 * LLM 调用入参。
 * messages 使用 [LlmMessagesJson] 包装原始 JSON 字符串，不做强类型绑定，
 * 避免因 OpenAI-compatible API 的 message 格式多样（vision、function call 等）导致字段丢失。
 */
data class LlmRequest(
    val modelConfig: LlmModelConfig,
    /** 原始 messages JSON 字符串（由调用方构造，可含 system/user/assistant 等任意合法格式） */
    val messages: LlmMessagesJson,
    /** 其他 OpenAI 请求体字段（response_format、temperature、max_tokens 等），原样透传 */
    val extraFields: JsonObject? = null,
    /**
     * true = 跳过缓存读取，强制请求 LLM。
     * 是否覆盖旧缓存取决于 [overwriteOnDisable]。
     */
    val disableCache: Boolean = false,
    /**
     * disableCache=true 时，是否覆盖写入缓存。
     * - true（默认）：以新结果覆盖旧缓存（is_valid 恢复为 1）
     * - false：不写缓存（适用于"保留修订内容"等场景，后期修订表接入后可按业务设置）
     */
    val overwriteOnDisable: Boolean = true,
)

data class LlmResponse(
    val text: String,
    val source: LlmResponseSource,
    val keyHash: String,
    val cacheKey: String,
)

enum class LlmResponseSource { CACHE, LLM_FRESH, REVISION /* 后期 */ }
```

### 5.2 接口声明

```kotlin
// commonMain/llm/LlmRequestService.kt

interface LlmRequestService {

    /**
     * 流式调用：每个 chunk 通过 [onChunk] 实时回调，完成后返回 [LlmResponse]。
     *
     * - 缓存命中：将缓存文本分段回调（模拟流式，无 delay），source=CACHE
     * - LLM 新请求：实时回调每个 delta，source=LLM_FRESH
     * - [后期] 修订命中：整体一次回调，source=REVISION
     *
     * 取消语义：依赖协程结构化取消，调用方取消所在协程即可；
     * LlmSseClient 内部通过 isActive 检测响应取消，抛 CancellationException 向上传播。
     */
    suspend fun streamRequest(
        req: LlmRequest,
        onChunk: (String) -> Unit,
    ): LlmResponse

    /**
     * 非流式调用：收集所有 chunk 拼接后一次性返回，默认基于 [streamRequest] 实现。
     * 适合 Executor 等不需要实时回调的场景。
     */
    suspend fun request(req: LlmRequest): LlmResponse {
        val sb = StringBuilder()
        val resp = streamRequest(req, onChunk = { sb.append(it) })
        // sb 与 resp.text 在正确实现下内容一致；以 sb 为准，确保不遗漏任何 chunk
        return resp.copy(text = sb.toString())
    }
}

object LlmRequestServiceHolder {
    lateinit var instance: LlmRequestService
}
```

> `request()` 的默认实现通过 `onChunk` 累积 chunk，流式完成后以 `copy(text = sb.toString())` 替换 text 字段。注意：`streamRequest` 实现层**不应**忽略 `onChunk` 回调，否则 `request()` 收不到 chunk，返回空字符串。

### 5.3 实现骨架（jvmMain）

```kotlin
// jvmMain/llm/LlmRequestServiceImpl.kt

class LlmRequestServiceImpl : LlmRequestService {

    override suspend fun streamRequest(
        req: LlmRequest,
        onChunk: (String) -> Unit,
    ): LlmResponse {
        val cacheKey = LlmCacheKeyUtil.buildCacheKey(
            req.modelConfig.model, req.modelConfig.baseUrl, req.messages
        )
        val keyHash = LlmCacheKeyUtil.hashKey(cacheKey)

        // [后期预留] 修订优先
        // LlmResponseRevisionService.repo.findActiveByHash(keyHash)?.let { rev ->
        //     onChunk(rev.revisedText)
        //     return LlmResponse(rev.revisedText, LlmResponseSource.REVISION, keyHash, cacheKey)
        // }

        // 缓存快速路径（锁外，允许偶发穿透）
        if (!req.disableCache) {
            LlmResponseCacheService.repo.findByHash(keyHash)?.takeIf { it.isValid }?.let { cached ->
                LlmResponseCacheService.repo.updateHit(keyHash, now())
                simulateStream(cached.responseText, onChunk)
                return LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
            }
        }

        // 分段锁：防止同一 keyHash 并发重复请求 LLM
        return getMutexForKey(keyHash).withLock {
            // Double-check
            if (!req.disableCache) {
                LlmResponseCacheService.repo.findByHash(keyHash)?.takeIf { it.isValid }?.let { cached ->
                    LlmResponseCacheService.repo.updateHit(keyHash, now())
                    simulateStream(cached.responseText, onChunk)
                    return@withLock LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
                }
            }

            // 实际 LLM 调用；协程取消时 streamChat 抛 CancellationException 向上传播，不经过此行
            // streamChat 改造后不再返回 null（null 仅为旧 cancelSignal 路径遗留），保留 ?: 作防御
            val requestBody = buildLlmRequestBody(req)
            val result: String = LlmSseClient.streamChat(
                modelConfig = req.modelConfig,
                requestBody = requestBody,
                onChunk = onChunk,
            ) ?: throw kotlinx.coroutines.CancellationException("LLM request cancelled")

            // 写缓存
            if (!req.disableCache || req.overwriteOnDisable) {
                val now = now()
                LlmResponseCacheService.repo.upsert(
                    LlmResponseCache(
                        keyHash = keyHash,
                        cacheKey = cacheKey,
                        modelName = req.modelConfig.model,
                        baseUrl = req.modelConfig.baseUrl,
                        messagesJson = req.messages.canonicalize(),
                        responseText = result,
                        isValid = true,
                        createdAt = now,
                        lastHitAt = now,
                    )
                )
            }
            LlmResponse(result, LlmResponseSource.LLM_FRESH, keyHash, cacheKey)
        }
    }
}

/** 缓存命中时模拟流式，按字符边界分段同步回调（不引入 delay） */
private inline fun simulateStream(text: String, onChunk: (String) -> Unit) {
    val chunkSize = 20
    var i = 0
    while (i < text.length) {
        val end = minOf(i + chunkSize, text.length)
        onChunk(text.substring(i, end))
        i = end
    }
}
```

### 5.4 buildLlmRequestBody

```kotlin
private fun buildLlmRequestBody(req: LlmRequest): String {
    val supportsStreaming = LlmCapability.STREAMING in req.modelConfig.capabilities
    val base = createJson {
        obj {
            kv("model", req.modelConfig.model)
            kv("messages", AppUtil.GlobalVars.json.parseToJsonElement(req.messages.raw).jsonArray)
            kv("stream", supportsStreaming)
        }
    }.toMutableMap()
    // 透传 extraFields（temperature、max_tokens、response_format 等）
    req.extraFields?.forEach { (k, v) -> base[k] = v }
    return base.toJsonObject().toString()
}
```

---

## 6. 适配层改造

### 6.1 LlmProxyChatRequest 改造

**当前**：

```kotlin
data class LlmProxyChatRequest(
    val appModelId: String,
    val message: String,
)
```

**目标**：

```kotlin
@Serializable
data class LlmProxyChatRequest(
    @SerialName("app_model_id")    val appModelId: String,
    /** 原始 messages JSON 字符串（支持任意 OpenAI-compatible 格式） */
    @SerialName("messages_json")   val messagesJson: String,
    @SerialName("disable_cache")   val disableCache: Boolean = false,
    /** disableCache=true 时是否覆盖旧缓存，默认 true */
    @SerialName("overwrite_on_disable") val overwriteOnDisable: Boolean = true,
    /** 其他请求字段（temperature、max_tokens、response_format 等）JSON 字符串，可选 */
    @SerialName("extra_fields_json") val extraFieldsJson: String? = null,
)
```

### 6.2 LlmProxyChatRoute.handle() 简化后形态

```kotlin
suspend fun handle(ctx: RoutingContext) {
    val call = ctx.call
    val req = call.receiveText().loadJsonModel<LlmProxyChatRequest>().getOrElse { e ->
        logger.error("LlmProxyChatRoute: 请求体解析失败", e)
        call.response.status(HttpStatusCode.BadRequest); return
    }

    val modelConfig = run {
        val config = AppConfigService.repo.getConfig()
        val models = config.llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrElse { emptyList() }
        models.find { it.appModelId == req.appModelId }
    } ?: run {
        call.response.status(HttpStatusCode.NotFound); return
    }

    val llmReq = LlmRequest(
        modelConfig = modelConfig,
        messages = LlmMessagesJson(req.messagesJson),
        extraFields = req.extraFieldsJson?.let {
            AppUtil.GlobalVars.json.parseToJsonElement(it).jsonObject
        },
        disableCache = req.disableCache,
        overwriteOnDisable = req.overwriteOnDisable,
    )

    try {
        call.respondBytesWriter(ContentType.Text.EventStream) {
            try {
                val resp = LlmRequestServiceHolder.instance.streamRequest(
                    req = llmReq,
                    onChunk = { chunk ->
                        writeStringUtf8("data: $chunk\n\n")
                        flush()
                    },
                )
                // 尾部 source 元数据事件，供前端展示缓存状态
                writeStringUtf8(
                    "event: llm_source\n" +
                    "data: ${buildValidJson { kv("source", resp.source.name); kv("key_hash", resp.keyHash) }.str}\n\n"
                )
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            } catch (e: LlmProviderException) {
                logger.warn("LlmProxyChatRoute: provider error type=${e.type} status=${e.httpStatus}", isHappensFrequently = false, err = e)
                writeStringUtf8(
                    "event: llm_error\n" +
                    "data: ${buildValidJson { kv("error_type", e.type.name); kv("message", e.providerMessage) }.str}\n\n"
                )
                flush()
            }
        }
    } catch (e: Exception) {
        logger.error("LlmProxyChatRoute: 异常", e)
        runCatching { call.response.status(HttpStatusCode.BadGateway) }
    }
}
```

### 6.3 LlmProxyChatJsMessageHandler 简化后形态

JsBridge 场景不需要 SSE，直接调用非流式接口：

```kotlin
@Serializable
data class Param(
    @SerialName("app_model_id")      val appModelId: String,
    @SerialName("messages_json")     val messagesJson: String,
    @SerialName("disable_cache")     val disableCache: Boolean = false,
    @SerialName("overwrite_on_disable") val overwriteOnDisable: Boolean = true,
    @SerialName("extra_fields_json") val extraFieldsJson: String? = null,
)

override suspend fun handle2(...) {
    // 参数解析 + 模型查找（同路由层，省略）
    logger.warn("[LlmProxyChatJsMessageHandler] appModelId=${param.appModelId} disableCache=${param.disableCache}")
    try {
        val resp = LlmRequestServiceHolder.instance.request(llmReq)
        callback(buildValidJson {
            kv("content", resp.text)
            kv("source", resp.source.name)
            kv("key_hash", resp.keyHash)
        }.str)
    } catch (e: LlmProviderException) {
        logger.warn("[LlmProxyChatJsMessageHandler] provider error type=${e.type}", isHappensFrequently = false, err = e)
        callback(buildValidJson { kv("error", e.providerMessage); kv("error_type", e.type.name) }.str)
    } catch (e: Exception) {
        logger.error("[LlmProxyChatJsMessageHandler] unexpected error", e)
        callback(buildValidJson { kv("error", e.message ?: "unknown error") }.str)
    }
}
```

---

## 7. LLM 提供商错误处理

`LlmSseClient.streamChat` 在 HTTP 非 2xx 时目前记录错误后 `return@execute`（返回空字符串而非 null）。需在服务层明确区分"调用成功但内容为空"与"调用失败"，避免将错误响应写入缓存。

### 7.1 错误分类

| 情形 | HTTP 状态 | 处理方式 |
|------|----------|---------|
| 认证失败 | 401 | 抛 `LlmProviderException(type=AUTH_ERROR)`，不写缓存 |
| 限流 | 429 | 抛 `LlmProviderException(type=RATE_LIMIT)`，不写缓存，上层可重试 |
| 服务端错误 | 5xx | 抛 `LlmProviderException(type=SERVER_ERROR)`，不写缓存 |
| 内容过滤 | 400 content_filter | 抛 `LlmProviderException(type=CONTENT_FILTER)`，不写缓存 |
| 模型不存在 | 404 / 400 model_not_found | 抛 `LlmProviderException(type=MODEL_NOT_FOUND)`，不写缓存 |
| 成功但内容为空 | 200 | 写缓存（空字符串视为合法响应） |

```kotlin
// commonMain/llm/LlmProviderException.kt

class LlmProviderException(
    val type: Type,
    val httpStatus: Int,
    val providerMessage: String,
) : Exception("LLM provider error [$type] status=$httpStatus: $providerMessage") {
    enum class Type {
        AUTH_ERROR, RATE_LIMIT, SERVER_ERROR, CONTENT_FILTER, MODEL_NOT_FOUND, UNKNOWN
    }
}
```

`LlmSseClient` 在非 2xx 时解析错误体，构造并抛出 `LlmProviderException`（替换原来的 `logger.error + return@execute`）。

服务层 `streamRequest` 捕获后重新抛出，**不写缓存**。路由层 / JsBridge handler 在 catch 中返回结构化错误响应。

### 7.2 路由层错误响应格式

§6.2 的 `respondBytesWriter` 内已展示完整形态，此处仅摘出关键片段：

```kotlin
// LlmProxyChatRoute：respondBytesWriter 内层 catch (e: LlmProviderException)
writeStringUtf8(
    "event: llm_error\n" +
    "data: ${buildValidJson { kv("error_type", e.type.name); kv("message", e.providerMessage) }.str}\n\n"
)
flush()
```

```kotlin
// JsBridge handler：
callback(buildValidJson {
    kv("error", e.providerMessage)
    kv("error_type", e.type.name)
}.str)
```

---

## 8. 新增 API 路由

### 8.1 废除缓存（`LlmCacheInvalidateRoute`）

```
POST /api/v1/LlmCacheInvalidateRoute
Body（二选一）:
  { "key_hash": "abc123..." }
  { "app_model_id": "xxx", "messages_json": "..." }   // 后端按 appModelId 查 modelConfig，取 model_name + base_url 计算 key_hash
Response: { "ok": true }
```

### 8.2 查询缓存详情（`LlmCacheQueryRoute`）

**隐私设计**：`key_hash` 是 SHA-256 不可逆哈希，只有掌握原始 messages 内容的调用方才能计算出对应的 `key_hash`，因此以 `key_hash` 作为查询参数不会泄露其他用户的缓存。分页查询全量缓存（含可读 `cache_key`）仅限 JsBridge（系统主页调用，需系主认证），不开放给普通 HTTP 前端。

```
GET /api/v1/LlmCacheQueryRoute?key_hash=abc123
Response:
{
  "cache": { ...LlmResponseCache 字段（含 cache_key，可反序列化出原始内容）} | null,
  "revision": null   // 后期填充
}
```

### 8.3 用户修订（后期）

```
POST /api/v1/LlmCacheReviseRoute
Body: {
  "key_hash": "abc123...",
  "original_text": "...",
  "revised_text": "...",
  "note": ""
}
Response: { "ok": true, "revision_id": 42 }
```

---

## 9. 前端改造要点

### 9.1 llm.ts 调用侧改造

`llmChat()` AsyncGenerator 的参数从单条 `message` 改为 `messagesJson`（原始 JSON 字符串），并新增 `disableCache`、`overwriteOnDisable`、`extraFieldsJson` 参数。

```typescript
// 修改前
const body = { app_model_id: modelId, message: userInput }

// 修改后
const body = {
  app_model_id: modelId,
  messages_json: JSON.stringify([{ role: "user", content: userInput }]),
  disable_cache: false,
}
```

对现有业务调用方（weben.ts、PromptBuilder 等），封装 helper：

```typescript
export function singleUserMessage(content: string): string {
  return JSON.stringify([{ role: "user", content }])
}
```

### 9.2 source 事件解析与缓存状态管理

后端在 SSE 末尾发送 `event: llm_source` 行，前端解析后维护状态：

```typescript
interface LlmResponseMeta {
  source: 'CACHE' | 'LLM_FRESH' | 'REVISION'
  keyHash: string
}
```

SSE `event: llm_source` 的 `data:` 行用 `json_parse` 解析（禁止裸 `JSON.parse`，详见 [json-handling.md §3.1](../json-handling.md)）：

```typescript
import { json_parse } from "~/util/json";

// 在 SSE 事件监听中
if (eventType === "llm_source") {
  const meta = json_parse<LlmResponseMeta>(data);
  setResponseMeta(meta);
}
```

`event: llm_error` 同理，data 行用 `json_parse` 解析后按 `error_type` 处理（见 §9.4）。

### 9.3 废除缓存触发时机与逻辑

| 触发时机 | source 限制 | 行为 |
|---------|------------|------|
| 用户点击「刷新」按钮 | 仅 `CACHE` 时显示按钮 | 调用 `LlmCacheInvalidateRoute`，重发请求（`disable_cache` 不需要手动设 true，废除后自然未命中） |
| JSON 解析失败 | 仅 `CACHE` 触发自动废除；`LLM_FRESH` 时不自动废除（避免掩盖 LLM 本身输出问题） | 废除缓存 + 单次重发，重发后仍失败则展示错误，不继续重试 |
| 修订内容被撤销（后期） | `REVISION` | 撤销修订后，下次调用降级走缓存 |

> `LLM_FRESH` 出现解析错误时，属于 LLM 输出本身的问题，不应自动废除（也没有缓存可废除），应直接向用户展示错误，由用户决定是否重试或调整 prompt。

### 9.4 LLM 错误展示

前端监听 `event: llm_error`，按 `error_type` 分情况处理：

| error_type | 展示策略 |
|-----------|---------|
| `AUTH_ERROR` | Toast："API Key 无效，请检查模型配置" |
| `RATE_LIMIT` | Toast："请求过于频繁，请稍后重试"，显示重试按钮 |
| `SERVER_ERROR` | Toast："LLM 服务异常（5xx）" |
| `CONTENT_FILTER` | Toast："内容被安全过滤，请修改输入" |
| `MODEL_NOT_FOUND` | Toast："模型不存在，请检查模型名称" |
| `UNKNOWN` | `reportHttpError` 兜底 |

### 9.5 手动修订 UI（后期）

- LLM 输出区域「编辑」入口 + 修订提交（`LlmCacheReviseRoute`）
- `source=REVISION` 时展示「用户已修订」badge
- 「撤销修订」按钮

---

## 10. 生命周期与约定

### cancelSignal 废弃，改为结构化取消

`LlmSseClient.streamChat` 移除 `cancelSignal` 参数，将原 `cancelSignal?.isCompleted` 检测改为 `!isActive`，依赖协程结构化取消：

- **路由层**：Ktor 请求断开时自动取消对应协程，`readUTF8Line` 在协程取消时抛 `CancellationException`，自然中断
- **JsBridge handler**：同上
- **LlmCallExecutor**：`TaskCancelService` 改为直接取消 Executor 所在的 coroutine scope，不再注册/传递 `CompletableDeferred`

**`CancellationException` 处理规范**（详见 [error-handling.md §7.5](../error-handling.md)）：`LlmSseClient.streamChat` 内部 catch `CancellationException` 时必须 re-throw，不可吞掉，否则协程取消信号丢失；`LlmRequestServiceImpl.streamRequest` 同理，不在此处 catch 取消异常，让其自然向上传播：

```kotlin
// LlmSseClient.streamChat 内部
} catch (e: CancellationException) {
    logger.debug("streamChat cancelled: model=${modelConfig.model}")
    throw e  // 必须 re-throw，不可吞掉
} catch (e: Exception) {
    logger.error("streamChat exception: model=${modelConfig.model}", e)
    throw e
}
```

### 缓存写入约定

| 情形 | 是否写缓存 |
|------|----------|
| LLM 调用成功（含空字符串响应） | ✅ 写入 |
| LLM 调用被取消（返回 null） | ❌ 不写 |
| LLM 抛出 `LlmProviderException` | ❌ 不写 |
| `disableCache=true` 且 `overwriteOnDisable=false` | ❌ 不写 |
| `disableCache=true` 且 `overwriteOnDisable=true`（默认） | ✅ 覆盖写入（is_valid=1） |
| 后期：修订表命中时 `disableCache=true` | 建议调用方设 `overwriteOnDisable=false`，保留修订 |

### DB 初始化位置

`LlmResponseCacheDb.initialize()` 在 `FredicaApi.jvm.kt` 的 `withContext(Dispatchers.IO)` 块中，接在 Bilibili 缓存组之后：

```kotlin
// Bilibili 缓存
BilibiliAiConclusionCacheDb(database).also  { ... }
BilibiliSubtitleMetaCacheDb(database).also  { ... }
BilibiliSubtitleBodyCacheDb(database).also  { ... }
// LLM 响应缓存
LlmResponseCacheDb(database).also { it.initialize(); LlmResponseCacheService.initialize(it) }
```

### messages_json 存储内容

存 **规范化后** 的 messages JSON（`LlmMessagesJson.canonicalize()` 输出），与 `cache_key` 中嵌入的内容一致，便于人工审计时直接从 DB 读取。

### simulateStream 不引入 delay

缓存命中分段回调纯粹是同步切分字符串，不加 `delay`。若前端需要"打字机"效果，在前端侧节流处理，后端保持低延迟。

---

## 11. 层级关系总结

| 层 | 文件 | 职责 | Phase 1 动作 |
|----|------|------|-------------|
| 工具层 | `LlmSseClient` | HTTP SSE 通信，抛 `LlmProviderException` | **改造**：错误路径改为抛异常 |
| 服务层 | `LlmRequestService` | 缓存查询 → 分段锁 → LLM 调用 → 写缓存 | **新增** |
| 路由层 | `LlmProxyChatRoute` | HTTP 适配，委托 Service，处理错误事件 | **简化** |
| Bridge 层 | `LlmProxyChatJsMessageHandler` | JsBridge 适配，委托 Service | **简化** |
| Executor | `LlmCallExecutor`（后续实现） | 替换为调用 `LlmRequestService` | 实现时直接用 Service |

---

## 12. 实现分阶段计划

### Phase 1：Kotlin 后端核心

**公共基础**

- [ ] `jsons.kt`（commonMain/apputil）：新增通用 `jsonCanonical(json: String): String` + 递归辅助 `jsonElementCanonical()`
- [ ] `LlmMessagesJson.kt`（commonMain/llm）：value class，`canonicalize()` 委托 `jsonCanonical`
- [ ] `sha256Hex` expect/actual：commonMain 声明 + jvmMain `MessageDigest` 实现
- [ ] `LlmCacheKeyUtil.kt`（commonMain/llm）：`buildCacheKey()`（Base64）+ `parseCacheKey()`（反序列化）+ `hashKey()`（SHA-256）
- [ ] `LlmProviderException.kt`（commonMain/llm）：错误类型枚举 + 异常类

**LlmSseClient 改造**

- [ ] HTTP 非 2xx 时解析错误体，构造并抛出 `LlmProviderException`（替换原 `logger.error + return@execute` 逻辑）
- [ ] 移除 `cancelSignal: CompletableDeferred<Unit>?` 参数，将 `cancelSignal?.isCompleted` 检测改为 `!isActive`（依赖结构化取消）
- [ ] 错误体解析：尝试提取 `error.code` / `error.message`，推断 `LlmProviderException.Type`

**DB 层**

- [ ] `LlmResponseCache.kt`（commonMain/db）：数据类 + `LlmResponseCacheRepo` 接口 + `LlmResponseCacheService` 单例
- [ ] `LlmResponseCacheDb.kt`（jvmMain/db）：JDBC 实现（`findByHash` / `upsert` / `updateHit` / `invalidate` / `invalidateByModel`）+ DDL
- [ ] `FredicaApi.jvm.kt`：在 `initializeDb` 块中注册 `LlmResponseCacheDb`

**服务层**

- [ ] `LlmRequestTypes.kt`（commonMain/llm）：`LlmRequest` / `LlmResponse` / `LlmResponseSource`
- [ ] `LlmRequestService.kt`（commonMain/llm）：`interface LlmRequestService`，`streamRequest` 为抽象方法，`request` 为默认实现（收集 chunk 拼接）；`LlmRequestServiceHolder` 单例
- [ ] `LlmRequestServiceImpl.kt`（jvmMain/llm）：`LlmRequestServiceImpl : LlmRequestService`，含锁外快速路径、`WeakHashMap` 分段锁、double-check、`buildLlmRequestBody()`、`simulateStream()`
- [ ] `FredicaApi.jvm.kt`：`LlmRequestServiceHolder.instance = LlmRequestServiceImpl()`
- [ ] `LlmCallExecutor` 取消机制：移除 `cancelSignal` 传递，改为取消 Executor coroutine scope

**适配层改造**

- [ ] `LlmProxyChatRequest`：`message` → `messagesJson` + `disableCache` + `overwriteOnDisable` + `extraFieldsJson`
- [ ] `LlmProxyChatRoute.handle()`：委托 Service，尾部追加 `event: llm_source`，catch `LlmProviderException` 发送 `event: llm_error`
- [ ] `LlmProxyChatJsMessageHandler.Param`：同步改造，调用 `LlmRequestServiceHolder.instance.request()`，返回 `content` + `source` + `key_hash`

**测试**

测试文件位于 `shared/src/jvmTest/kotlin/.../`，遵循 [testing.md](../testing.md) 的 SQLite 隔离约定（临时文件，不用 `:memory:`）。

**`apputil/JsonCanonicalTest`**：纯逻辑，无 DB，直接 `@Test` 调用

| 用例 | 验证点 |
|------|--------|
| `keyOrderNormalized` | `{"b":1,"a":2}` → `{"a":2,"b":1}` |
| `nestedObjectRecursive` | 嵌套 object 各层 key 均排序 |
| `arrayPreservesOrder` | array 内元素顺序不变 |
| `arrayInnerObjectNormalized` | array 内 object 的 key 也排序 |
| `idempotent` | 对已规范化内容再次调用结果不变 |

**`llm/LlmCacheKeyUtilTest`**：纯逻辑，无 DB

| 用例 | 验证点 |
|------|--------|
| `sameInputSameKey` | 相同三段输入 → 相同 cacheKey 和 keyHash |
| `baseUrlTrailingSlashNormalized` | `http://api/` 与 `http://api` → 相同 key |
| `differentMessagesDifferentKey` | messages 内容不同 → key 不同 |
| `parseCacheKeyRoundtrip` | `buildCacheKey` → `parseCacheKey` 还原三段内容 |
| `pipeInModelNameSafe` | modelName 含 `\|` 字符 → 解析仍正确（`\|` 不在 Base64 字母表） |
| `pipeInBaseUrlSafe` | baseUrl 含 `\|` 字符 → 解析仍正确 |
| `messagesKeyOrderNormalized` | messages key 顺序不同但等价 → 相同 key |

**`llm/LlmMessagesJsonTest`**：纯逻辑

| 用例 | 验证点 |
|------|--------|
| `canonicalizeDelegatesJsonCanonical` | 与直接调 `jsonCanonical` 结果一致 |

**`llm/LlmSseClientProviderExceptionTest`**：测试 `LlmSseClient` 改造后的错误路径，使用 `MockEngine`（ktor-client-mock）构造非 2xx 响应

```kotlin
// 用 MockEngine 构造 401 响应，验证 LlmSseClient 抛 LlmProviderException(type=AUTH_ERROR)
val mockEngine = MockEngine { request ->
    respond(
        content = """{"error":{"code":"invalid_api_key","message":"Incorrect API key"}}""",
        status = HttpStatusCode.Unauthorized,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
```

| 用例 | 触发条件 | 验证点 |
|------|---------|--------|
| `http401ThrowsAuthError` | 401 响应 | 抛 `LlmProviderException(type=AUTH_ERROR)` |
| `http429ThrowsRateLimit` | 429 响应 | 抛 `LlmProviderException(type=RATE_LIMIT)` |
| `http500ThrowsServerError` | 500 响应 | 抛 `LlmProviderException(type=SERVER_ERROR)` |
| `http400ContentFilterThrowsContentFilter` | 400 + `error.code=content_filter` | 抛 `LlmProviderException(type=CONTENT_FILTER)` |
| `http200EmptyContentReturnsEmptyString` | 200 正常 SSE，无 delta content | 返回空字符串，不抛异常 |
| `cancellationExceptionPropagates` | 协程取消 | `CancellationException` 向上传播，不被吞掉 |

**`db/LlmResponseCacheDbTest`**：需要 SQLite 临时文件

```kotlin
@BeforeTest fun setUp() {
    tmpFile = File.createTempFile("llm_cache_test_", ".db").also { it.deleteOnExit() }
    db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
    repo = LlmResponseCacheDb(db).also { it.initialize() }
}
```

| 用例 | 验证点 |
|------|--------|
| `insertAndFindByHash` | upsert 后 findByHash 返回正确记录 |
| `upsertOverwrites` | 相同 keyHash 二次 upsert → 覆盖 responseText |
| `invalidateHidesRecord` | invalidate 后 findByHash 返回 null（is_valid=0） |
| `invalidateByModel` | 按 modelName+baseUrl 批量废除，其他 model 不受影响 |
| `updateHitIncrementsCount` | updateHit 后 hitCount +1，lastHitAt 更新 |
| `findByHashReturnsNullIfAbsent` | 未插入的 keyHash → 返回 null |

**`llm/LlmRequestServiceTest`**：用 fake 替代 DB 和 LlmSseClient，不需要 SQLite

```kotlin
// Fake repo — 内存 Map 实现 LlmResponseCacheRepo
class FakeLlmResponseCacheRepo : LlmResponseCacheRepo {
    val store = mutableMapOf<String, LlmResponseCache>()
    override suspend fun findByHash(keyHash: String) = store[keyHash]?.takeIf { it.isValid }
    override suspend fun upsert(entry: LlmResponseCache) { store[entry.keyHash] = entry }
    override suspend fun updateHit(keyHash: String, hitAt: Long) { /* no-op */ }
    override suspend fun invalidate(keyHash: String) { store[keyHash] = store[keyHash]!!.copy(isValid = false) }
    override suspend fun invalidateByModel(modelName: String, baseUrl: String) { /* 按需实现 */ }
}

// Fake streamChat — 替换 LlmSseClient，避免真实 HTTP
// 在 LlmRequestServiceImpl 构造时注入，或用 companion object 替换全局实现
```

| 用例 | 前置条件 | 验证点 |
|------|---------|--------|
| `cacheHitSkipsLlm` | FakeRepo 预置缓存 | `streamChat` 不被调用；source=CACHE；onChunk 收到模拟流数据 |
| `cacheMissCallsLlm` | FakeRepo 为空 | `streamChat` 被调用一次；source=LLM_FRESH；结果写入 FakeRepo |
| `disableCacheOverwriteTrue` | FakeRepo 预置缓存，`disableCache=true, overwriteOnDisable=true` | `streamChat` 被调用；新结果覆盖写入 FakeRepo（is_valid=true） |
| `disableCacheOverwriteFalse` | FakeRepo 预置缓存，`disableCache=true, overwriteOnDisable=false` | `streamChat` 被调用；FakeRepo 内容不变 |
| `providerExceptionNotCached` | `streamChat` fake 抛 `LlmProviderException` | 异常向上传播；FakeRepo 无新记录 |
| `cancellationNotCached` | 协程在 streamChat 期间取消 | `CancellationException` 向上传播；FakeRepo 无新记录 |
| `concurrentSameHashDoesNotDuplicateLlmCall` | 两个并发请求相同 keyHash，FakeRepo 为空 | `streamChat` 仅被调用一次（第二个在锁内 double-check 命中缓存） |

> **并发测试说明**：在 `runBlocking` 中 `launch` 两个并发协程，用 `CountDownLatch` 或 `Channel` 确保两者同时进入快速路径后再放行；验证 `streamChat` 调用计数（通过 `AtomicInteger` 计数器 fake）为 1。

---

### Phase 2：缓存管理路由 + 前端核心改造

**Kotlin 后端**

- [ ] `LlmCacheInvalidateRoute.kt`：接受 `key_hash` 或 `app_model_id + messages_json` 两种形式
- [ ] `LlmCacheQueryRoute.kt`：按 `key_hash` 查询单条（HTTP 前端可用），全量分页仅 JsBridge
- [ ] 注册到 `all_routes.kt`

**前端**

- [ ] `llm.ts`：`llmChat()` 改为 `messagesJson` 参数 + `disableCache` + `overwriteOnDisable` + `extraFieldsJson`；解析 `event: llm_source` 和 `event: llm_error`；返回值携带 `meta: LlmResponseMeta`
- [ ] `singleUserMessage()` helper 函数，供旧调用方快速迁移
- [ ] 全站调用侧迁移（weben.ts、PromptBuilder 等）：改用 `singleUserMessage()` 或直接传 `messagesJson`
- [ ] `LlmCacheSourceBadge.tsx`：`CACHE` 显示「缓存命中」chip + 「刷新」按钮；`LLM_FRESH` 无 badge
- [ ] 「刷新」逻辑：调用 `LlmCacheInvalidateRoute` → 重发请求
- [ ] JSON 解析失败自动废除 + 单次重试（仅 `source=CACHE` 触发）
- [ ] LLM 错误展示：按 `error_type` 分情况 Toast / 重试按钮

**测试**

Kotlin 路由测试不涉及真实 HTTP，用 `testApplication { }` + `MockEngine`：

**`llm/LlmCacheInvalidateRouteTest`**

| 用例 | 验证点 |
|------|--------|
| `invalidateByKeyHash` | POST `{key_hash}` → 200 `{ok:true}`；FakeRepo 对应记录 is_valid=0 |
| `invalidateByAppModelId` | POST `{app_model_id, messages_json}` → 后端计算 keyHash，废除正确记录 |
| `invalidateNonexistentKeyHash` | keyHash 不存在 → 200 `{ok:true}`（幂等，不报错） |

**`llm/LlmCacheQueryRouteTest`**

| 用例 | 验证点 |
|------|--------|
| `queryHit` | GET `?key_hash=xxx`，FakeRepo 有记录 → 返回 cache 字段非 null |
| `queryMiss` | FakeRepo 无记录 → 返回 `{cache: null}` |
| `queryInvalidated` | FakeRepo 记录 is_valid=0 → 返回 `{cache: null}` |

前端测试使用 Vitest，位于 `fredica-webui/tests/util/`：

**`tests/util/llm.test.ts`**（`llmChat()` 改造后的 SSE 解析逻辑）

Mock 策略：`vi.stubGlobal("fetch", ...)` 返回 fake SSE stream（`ReadableStream`），`vi.unstubAllGlobals()` 在 `afterEach` 清理。

| 用例 | 验证点 |
|------|--------|
| `L1` | 正常 SSE 流 → AsyncGenerator yield 各 chunk，最终 `meta.source=LLM_FRESH` |
| `L2` | SSE 含 `event: llm_source` 行 → `meta.source=CACHE`，`meta.keyHash` 正确 |
| `L3` | SSE 含 `event: llm_error` 行 → generator throw `LlmProviderError`，含 `errorType=RATE_LIMIT` |
| `L4` | `singleUserMessage("hello")` → 返回 `JSON.stringify([{role:"user",content:"hello"}])` |
| `L5` | `disableCache=true` 时请求体含 `disable_cache:true` |

> SSE fake stream 构造参考：用 `ReadableStream` + `TextEncoder` 逐行写入 `data:` 和 `event:` 行，模拟真实流式响应。

---

### Phase 3：修订表后端（后期）

- [ ] `LlmResponseRevision.kt`（commonMain/db）：数据类 + Repo 接口 + Service 单例
- [ ] `LlmResponseRevisionDb.kt`（jvmMain/db）：JDBC 实现 + DDL
- [ ] `FredicaApi.jvm.kt`：注册 `LlmResponseRevisionDb`
- [ ] `LlmRequestServiceImpl`：在锁外快速路径前插入修订查询逻辑
- [ ] `LlmCacheReviseRoute.kt`：提交修订
- [ ] `LlmCacheRevokeRevisionRoute.kt`：撤销修订
- [ ] `LlmCacheQueryRoute`：response 中补充 `revision` 字段

**测试**

**`db/LlmResponseRevisionDbTest`**：SQLite 临时文件隔离（同 Phase 1 DB 测试模式）

| 用例 | 验证点 |
|------|--------|
| `insertAndFindActiveByHash` | insert 后 findActiveByHash 返回记录 |
| `revokeHidesRevision` | revoke 后 findActiveByHash 返回 null（is_active=0） |
| `multipleRevisionsOnlyActiveReturned` | 同一 keyHash 多条记录，只返回 is_active=1 的最新一条 |

**`llm/LlmRequestServiceRevisionTest`**（复用 Phase 1 的 FakeRepo 体系，新增 `FakeLlmResponseRevisionRepo`）

| 用例 | 验证点 |
|------|--------|
| `revisionTakesPriorityOverCache` | FakeRevisionRepo 有记录 → source=REVISION，不查缓存，不调 LlmSseClient |
| `cacheFallsBackWhenNoRevision` | FakeRevisionRepo 为空，FakeCacheRepo 有记录 → source=CACHE |
| `revokedRevisionFallsBackToCache` | FakeRevisionRepo 记录 is_active=0 → 降级走缓存 |

---

### Phase 4：修订前端（后期）

- [ ] LLM 输出区域「编辑」入口 + 修订提交表单
- [ ] `source=REVISION` badge + 「撤销修订」按钮
- [ ] 修订历史查看（可选）
