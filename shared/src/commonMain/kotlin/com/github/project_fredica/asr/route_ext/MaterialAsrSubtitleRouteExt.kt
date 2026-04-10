package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.MaterialAsrSubtitleRoute
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.service.MaterialSubtitleService
import com.github.project_fredica.asr.model.AsrSubtitleResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "MaterialAsrSubtitleRoute" }

/** 读取指定模型的 ASR 转录结果详情（分段字幕 + 元数据）。 */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialAsrSubtitleRoute.handler2(param: String): ValidJsonString {
    return try {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return AppUtil.dumpJsonStr(AsrSubtitleResponse()).getOrThrow()
        val modelSize = query["model_size"]?.firstOrNull() ?: "large-v3"

        val response = MaterialSubtitleService.readAsrSubtitleDetail(materialId, modelSize)

        logger.debug("materialId=$materialId segments=${response.segmentCount} partial=${response.partial}")
        AppUtil.dumpJsonStr(response).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[MaterialAsrSubtitleRoute] 读取 ASR 字幕失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
