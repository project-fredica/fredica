package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.weben.WebenNote
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenNoteSaveRoute
 *
 * 创建或更新笔记（按 id 幂等）。
 * 若 id 为空则自动生成新 UUID（创建）；否则更新 content + updated_at（编辑）。
 */
object WebenNoteSaveRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "创建或更新概念笔记（按 id 幂等）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<WebenNoteSaveParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L
        val note = WebenNote(
            id        = p.id ?: java.util.UUID.randomUUID().toString(),
            conceptId = p.conceptId,
            content   = p.content,
            createdAt = nowSec,
            updatedAt = nowSec,
        )
        WebenNoteService.repo.save(note)
        return buildValidJson { kv("ok", true); kv("id", note.id) }
    }
}

@Serializable
data class WebenNoteSaveParam(
    /** null 表示新建，传入现有 id 则更新。 */
    val id: String? = null,
    @SerialName("concept_id") val conceptId: String,
    val content: String,
)
