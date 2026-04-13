package com.github.project_fredica.asr.route_ext

import com.github.project_fredica.api.routes.MaterialLanguageGuessRoute
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.asr.service.LanguageGuessService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GET /api/v1/MaterialLanguageGuessRoute?material_id=... — 薄路由，委托 LanguageGuessService */
@Suppress("UnusedReceiverParameter")
suspend fun MaterialLanguageGuessRoute.handler2(param: String): ValidJsonString {
    val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
    val materialId = query["material_id"]?.firstOrNull()
        ?: return buildJsonObject { put("language", null as String?) }.toValidJson()

    val lang = LanguageGuessService.guessLanguage(materialId)
    return buildJsonObject { put("language", lang) }.toValidJson()
}
