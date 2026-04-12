package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.AsrConfigGetRoute
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.model.AsrConfigPublicResponse
import com.github.project_fredica.asr.service.AsrConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "AsrConfigRoute" }

/** GET /api/v1/AsrConfigGetRoute — 读取 ASR 公开配置（仅权限信息，不含服主测试参数） */
@Suppress("UnusedReceiverParameter")
suspend fun AsrConfigGetRoute.handler2(param: String): ValidJsonString {
    return try {
        val full = AsrConfigService.getAsrConfig()
        val public = AsrConfigPublicResponse(
            allowDownload = full.allowDownload,
            disallowedModels = full.disallowedModels,
        )
        AppUtil.dumpJsonStr(public).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[AsrConfigGetRoute] 读取 ASR 配置失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
