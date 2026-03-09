package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenFlashcardListRoute?concept_id=<uuid>
 *
 * 查询某概念的全部闪卡列表（按创建时间升序）。
 */
object WebenFlashcardListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询某概念的全部闪卡"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull() ?: return ValidJsonString("[]")
        val cards     = WebenFlashcardService.repo.listByConcept(conceptId)
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(cards))
    }
}
