package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GET /api/v1/FasterWhisperConfigInfoRoute
 *
 * 返回 faster-whisper 配置摘要（只读）。
 * 前端用此接口了解当前配置状态，不触发任何任务。
 *
 * 响应：
 * ```json
 * {
 *   "model": "large-v3",
 *   "compute_type": "auto",
 *   "device": "auto",
 *   "models_dir": "",
 *   "compat_json": "{...}"
 * }
 * ```
 */
object FasterWhisperConfigInfoRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取 faster-whisper 配置摘要（只读）"

    override suspend fun handler(param: String): ValidJsonString {
        val cfg = AppConfigService.repo.getConfig()
        return buildJsonObject {
            put("model", cfg.fasterWhisperModel)
            put("compute_type", cfg.fasterWhisperComputeType)
            put("device", cfg.fasterWhisperDevice)
            // routeApi 用户侧不应该知晓模型的存储目录。
            // put("models_dir", cfg.fasterWhisperModelsDir)
            put("compat_json", cfg.fasterWhisperCompatJson)
        }.toValidJson()
    }
}
