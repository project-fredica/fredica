package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.AsrConfigGetRoute
import com.github.project_fredica.api.routes.AsrConfigSaveRoute
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.model.AsrConfigSaveParam
import com.github.project_fredica.asr.service.AsrConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "AsrConfigRoute" }

/** GET /api/v1/AsrConfigGetRoute — 读取 ASR 配置 */
@Suppress("UnusedReceiverParameter")
suspend fun AsrConfigGetRoute.handler2(param: String): ValidJsonString {
    return try {
        val response = AsrConfigService.getAsrConfig()
        AppUtil.dumpJsonStr(response).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[AsrConfigGetRoute] 读取 ASR 配置失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}

/** POST /api/v1/AsrConfigSaveRoute — 保存 ASR 配置（部分更新） */
@Suppress("UnusedReceiverParameter")
suspend fun AsrConfigSaveRoute.handler2(param: String): ValidJsonString {
    return try {
        val saveParam = param.loadJsonModel<AsrConfigSaveParam>().getOrThrow()
        AsrConfigService.saveAsrConfig(saveParam)
        val updated = AsrConfigService.getAsrConfig()
        AppUtil.dumpJsonStr(updated).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[AsrConfigSaveRoute] 保存 ASR 配置失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
