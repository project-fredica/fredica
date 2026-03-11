package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenFlashcardService
import kotlinx.serialization.encodeToString

/**
 * GET /api/v1/WebenFlashcardListRoute?concept_id=<uuid>
 *
 * 查询某概念的全部闪卡列表（按创建时间升序）。
 * 包含系统自动生成的闪卡（is_system=true）和用户手动创建的闪卡（is_system=false）。
 */
object WebenFlashcardListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenFlashcardListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询某概念的全部闪卡"

    override suspend fun handler(param: String): ValidJsonString {
        val query     = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val conceptId = query["concept_id"]?.firstOrNull()

        // concept_id 为必填；缺失时返回空数组
        if (conceptId == null) {
            logger.debug("WebenFlashcardListRoute: 缺少 concept_id 参数，返回空数组")
            return ValidJsonString("[]")
        }

        logger.debug("WebenFlashcardListRoute: 查询闪卡列表 conceptId=$conceptId")
        val cards = WebenFlashcardService.repo.listByConcept(conceptId)
        logger.debug("WebenFlashcardListRoute: 返回 ${cards.size} 张闪卡 conceptId=$conceptId")
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(cards))
    }
}
