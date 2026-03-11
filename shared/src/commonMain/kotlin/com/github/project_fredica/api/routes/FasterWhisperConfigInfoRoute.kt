package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.db.AppConfigService

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
        return buildValidJson {
            kv("model", cfg.fasterWhisperModel)
            kv("compute_type", cfg.fasterWhisperComputeType)
            kv("device", cfg.fasterWhisperDevice)
            kv("models_dir", cfg.fasterWhisperModelsDir)
            kv("compat_json", cfg.fasterWhisperCompatJson)
        }
    }
}
