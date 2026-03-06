package com.github.project_fredica.appwebview.messages

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.llm.LlmModelConfig
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 接收前端拖动排序后的 id 列表，按新顺序更新每个模型的 sort_order 字段并持久化。
 * 参数：{ "ids": ["id1", "id2", ...] }
 * 返回：更新后的完整模型列表 JSON
 */
class ReorderLlmModelsJsMessageHandler : MyJsMessageHandler() {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ReorderParam(val ids: List<String>)

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit
    ) {
        val param = message.params.loadJsonModel<ReorderParam>().getOrThrow()
        val config = AppConfigService.repo.getConfig()
        val models = json.decodeFromString<List<LlmModelConfig>>(config.llmModelsJson)
        val modelMap = models.associateBy { it.id }
        // 按传入 id 顺序重建列表，赋予新的 sort_order（从 0 开始递增）
        val reordered = param.ids.mapIndexedNotNull { index, id ->
            modelMap[id]?.copy(sortOrder = index)
        }
        // 保留不在 ids 中的模型（追加到末尾，sort_order 接续）
        val reorderedIds = param.ids.toSet()
        val remaining = models.filterNot { it.id in reorderedIds }
            .mapIndexed { i, m -> m.copy(sortOrder = reordered.size + i) }
        val updated = reordered + remaining
        AppConfigService.repo.updateConfig(config.copy(llmModelsJson = json.encodeToString(updated)))
        callback(json.encodeToString(updated))
    }
}
