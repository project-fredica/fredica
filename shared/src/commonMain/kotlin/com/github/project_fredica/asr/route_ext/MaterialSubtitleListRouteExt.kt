package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.MaterialSubtitleListRoute
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.asr.service.MaterialSubtitleService
import com.github.project_fredica.asr.model.MaterialSubtitleItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = createLogger { "MaterialSubtitleListRoute" }

/** 扫描素材的所有字幕来源（bilibili + ASR），返回统一列表。 */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialSubtitleListRoute.handler2(param: String): ValidJsonString {
    return try {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
            ?: return AppUtil.dumpJsonStr(emptyList<MaterialSubtitleItem>()).getOrThrow()

        val items = MaterialSubtitleService.scanAllSubtitleItems(materialId)
        logger.debug("materialId=$materialId 字幕数=${items.size}")
        AppUtil.dumpJsonStr(items).getOrThrow()
    } catch (e: Throwable) {
        logger.warn("[MaterialSubtitleListRoute] 扫描字幕列表失败", isHappensFrequently = false, err = e)
        buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
    }
}
