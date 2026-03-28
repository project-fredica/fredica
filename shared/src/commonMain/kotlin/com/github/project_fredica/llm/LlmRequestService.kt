package com.github.project_fredica.llm

/**
 * LLM 请求服务接口，封装缓存查询、分段锁、LLM 调用、写缓存等核心逻辑。
 *
 * 路由层（[com.github.project_fredica.api.routes.LlmProxyChatRoute]）和
 * JsBridge 层通过 [LlmRequestServiceHolder] 获取实例调用。
 */
interface LlmRequestService {

    /**
     * 流式调用：每个 chunk 通过 [onChunk] 实时回调，完成后返回 [LlmResponse]。
     *
     * - 缓存命中：将缓存文本分段回调（模拟流式，无 delay），source=CACHE
     * - LLM 新请求：实时回调每个 delta，source=LLM_FRESH
     * - [后期] 修订命中：整体一次回调，source=REVISION
     *
     * 取消语义：依赖协程结构化取消，调用方取消所在协程即可；
     * LlmSseClient 内部通过 isActive 检测，抛 CancellationException 向上传播。
     */
    suspend fun streamRequest(
        req: LlmRequest,
        onChunk: suspend (String) -> Unit,
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
