package com.github.project_fredica.asr.service

import com.github.project_fredica.asr.model.AsrConfigResponse
import com.github.project_fredica.asr.model.AsrConfigSaveParam
import com.github.project_fredica.db.AppConfigService

/**
 * ASR 权限与测试配置的业务逻辑层。
 *
 * 在 AppConfig 的 4 个 asr_* 字段之上提供语义化 API，
 * 供路由层和 Executor 调用。
 */
object AsrConfigService {

    suspend fun getAsrConfig(): AsrConfigResponse {
        val config = AppConfigService.repo.getConfig()
        return AsrConfigResponse(
            allowDownload = config.asrAllowDownload,
            disallowedModels = config.asrDisallowedModels,
            testAudioPath = config.asrTestAudioPath,
            testWaveCount = config.asrTestWaveCount,
        )
    }

    suspend fun saveAsrConfig(param: AsrConfigSaveParam) {
        val current = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(
            current.copy(
                asrAllowDownload = param.allowDownload ?: current.asrAllowDownload,
                asrDisallowedModels = param.disallowedModels ?: current.asrDisallowedModels,
                asrTestAudioPath = param.testAudioPath ?: current.asrTestAudioPath,
                asrTestWaveCount = param.testWaveCount ?: current.asrTestWaveCount,
            )
        )
    }

    /**
     * 判断指定模型是否被允许使用。
     * 空黑名单表示不限制（全部允许）；非空时，黑名单中的模型被禁用。
     */
    suspend fun isModelAllowed(model: String): Boolean {
        val disallowed = AppConfigService.repo.getConfig().asrDisallowedModels.trim()
        if (disallowed.isEmpty()) return true
        val set = disallowed.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return model.trim() !in set
    }

    /**
     * 从候选模型列表中排除黑名单中的模型。
     * 空黑名单 → 返回全部。
     */
    suspend fun filterDisallowedModels(allModels: List<String>): List<String> {
        val disallowed = AppConfigService.repo.getConfig().asrDisallowedModels.trim()
        if (disallowed.isEmpty()) return allModels
        val set = disallowed.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return allModels.filter { it.trim() !in set }
    }

    suspend fun isDownloadAllowed(): Boolean {
        return AppConfigService.repo.getConfig().asrAllowDownload
    }
}
