package com.github.project_fredica.llm

import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.LlmResponseCache
import com.github.project_fredica.db.LlmResponseCacheService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

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
        // 计算缓存键
        val cacheKey = LlmCacheKeyUtil.buildCacheKey(
            req.modelConfig.model, req.modelConfig.baseUrl, req.messages
        )
        val keyHash = LlmCacheKeyUtil.hashKey(cacheKey)

        logger.debug("[LlmRequestService] streamRequest: model=${req.modelConfig.model} keyHash=${keyHash.take(8)}... disableCache=${req.disableCache}")

        // [后期预留] 修订优先
        // LlmResponseRevisionService.repo.findActiveByHash(keyHash)?.let { rev ->
        //     logger.debug("[LlmRequestService] revision hit: keyHash=${keyHash.take(8)}...")
        //     onChunk(rev.revisedText)
        //     return LlmResponse(rev.revisedText, LlmResponseSource.REVISION, keyHash, cacheKey)
        // }

        // 缓存快速路径（锁外，允许偶发穿透）
        if (!req.disableCache) {
            LlmResponseCacheService.repo.findByHash(keyHash)?.takeIf { it.isValid }?.let { cached ->
                logger.debug("[LlmRequestService] cache hit (fast path): keyHash=${keyHash.take(8)}... length=${cached.responseText.length}")
                LlmResponseCacheService.repo.updateHit(keyHash, nowSec())
                simulateStream(cached.responseText, onChunk)
                return LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
            }
        }

        // 分段锁：防止同一 keyHash 并发重复请求 LLM
        return getMutexForKey(keyHash).withLock {
            logger.debug("[LlmRequestService] acquired lock for keyHash=${keyHash.take(8)}...")

            // Double-check：锁内再次检查缓存（可能在等锁期间被其他协程写入）
            if (!req.disableCache) {
                LlmResponseCacheService.repo.findByHash(keyHash)?.takeIf { it.isValid }?.let { cached ->
                    logger.debug("[LlmRequestService] cache hit (double-check): keyHash=${keyHash.take(8)}... length=${cached.responseText.length}")
                    LlmResponseCacheService.repo.updateHit(keyHash, nowSec())
                    simulateStream(cached.responseText, onChunk)
                    return@withLock LlmResponse(cached.responseText, LlmResponseSource.CACHE, keyHash, cacheKey)
                }
            }

            // 实际 LLM 调用
            logger.debug("[LlmRequestService] calling LLM: model=${req.modelConfig.model} baseUrl=${req.modelConfig.baseUrl}")
            val requestBody = buildLlmRequestBody(req)

            val result: String = try {
                LlmSseClient.streamChat(
                    modelConfig = req.modelConfig,
                    requestBody = requestBody,
                    onChunk = onChunk,
                )
            } catch (e: CancellationException) {
                // 协程取消：记录 debug 日志后重新抛出（不可吞掉取消信号）
                logger.debug("[LlmRequestService] request cancelled: keyHash=${keyHash.take(8)}...")
                throw e
            } catch (e: LlmProviderException) {
                // LLM 提供商错误：记录 warn 日志后重新抛出（调用方会处理）
                logger.warn(
                    "[LlmRequestService] LLM provider error: type=${e.type} status=${e.httpStatus} keyHash=${
                        keyHash.take(
                            8
                        )
                    }...", isHappensFrequently = false, err = e
                )
                throw e
            }

            logger.debug("[LlmRequestService] LLM response received: keyHash=${keyHash.take(8)}... length=${result.length}")

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
                logger.debug(
                    "[LlmRequestService] cache written: keyHash=${keyHash.take(8)}... result text : ${
                        result.take(
                            100
                        )
                    }..."
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
    val base = buildJsonObject {
        put("model", req.modelConfig.model)
        put("messages", AppUtil.GlobalVars.json.parseToJsonElement(req.messages.raw).jsonArray)
        put("stream", true)  // 强制流式，所有 OpenAI 兼容模型都支持
    }.toMutableMap()
    // 透传 extraFields（temperature、max_tokens、response_format 等）
    req.extraFields?.forEach { (k, v) -> base[k] = v }
    return base.toJsonObject().toString()
}
