package com.github.project_fredica.llm

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.toJsonObject
import com.github.project_fredica.db.LlmResponseCache
import com.github.project_fredica.db.LlmResponseCacheService
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LlmRequestServiceImpl : LlmRequestService {

    private val logger = createLogger()

    // WeakHashMap 按 keyHash 分配独立锁，锁释放后自动 GC（WeakReference 语义）
    private val keyMutexMap = java.util.WeakHashMap<String, Mutex>()

    private fun getMutexForKey(keyHash: String): Mutex =
        synchronized(keyMutexMap) {
            keyMutexMap.getOrPut(keyHash) { Mutex() }
        }

    override suspend fun streamRequest(
        req: LlmRequest,
        onChunk: suspend (String) -> Unit,
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
                LlmResponseCacheService.repo.updateHit(keyHash, nowSec())
                simulateStream(cached.responseText, onChunk)
                return LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
            }
        }

        // 分段锁：防止同一 keyHash 并发重复请求 LLM
        return getMutexForKey(keyHash).withLock {
            // Double-check
            if (!req.disableCache) {
                LlmResponseCacheService.repo.findByHash(keyHash)?.takeIf { it.isValid }?.let { cached ->
                    LlmResponseCacheService.repo.updateHit(keyHash, nowSec())
                    simulateStream(cached.responseText, onChunk)
                    return@withLock LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
                }
            }

            // 实际 LLM 调用；协程取消时 streamChat 抛 CancellationException 向上传播
            val requestBody = buildLlmRequestBody(req)
            val result: String = LlmSseClient.streamChat(
                modelConfig = req.modelConfig,
                requestBody = requestBody,
                onChunk = onChunk,
            )

            // 写缓存
            if (!req.disableCache || req.overwriteOnDisable) {
                val now = nowSec()
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

    private fun nowSec() = System.currentTimeMillis() / 1000L
}

/** 缓存命中时模拟流式，按字符边界分段同步回调（不引入 delay） */
private suspend fun simulateStream(text: String, onChunk: suspend (String) -> Unit) {
    val chunkSize = 20
    var i = 0
    while (i < text.length) {
        val end = minOf(i + chunkSize, text.length)
        onChunk(text.substring(i, end))
        i = end
    }
}

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
