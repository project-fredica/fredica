package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.db.weben.WebenNote
import com.github.project_fredica.db.weben.WebenNoteService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * POST /api/v1/WebenNoteSaveRoute
 *
 * 创建或更新笔记（按 id 幂等）。
 *
 * 操作分支：
 *   - id 为 null → 自动生成新 UUID，INSERT 新笔记（创建）
 *   - id 不为 null → 以相同 id UPSERT，更新 content + updated_at（编辑）
 *
 * DB 实现（WebenNoteDb.save）使用 INSERT OR REPLACE，确保 id 冲突时自动覆盖。
 */
object WebenNoteSaveRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenNoteSaveRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "创建或更新概念笔记（按 id 幂等）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<WebenNoteSaveParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L

        // 判断创建还是更新，用于日志和语义区分
        val isNew = p.id == null
        val noteId = p.id ?: java.util.UUID.randomUUID().toString()

        logger.debug(
            "WebenNoteSaveRoute: ${if (isNew) "创建" else "更新"}笔记" +
            " id=$noteId conceptId=${p.conceptId}" +
            " contentLen=${p.content.length}"
        )

        val note = WebenNote(
            id        = noteId,
            conceptId = p.conceptId,
            content   = p.content,
            createdAt = nowSec,
            updatedAt = nowSec,
        )
        WebenNoteService.repo.save(note)

        logger.info("WebenNoteSaveRoute: 笔记已${if (isNew) "创建" else "更新"} id=$noteId conceptId=${p.conceptId}")
        return buildJsonObject { put("ok", true); put("id", note.id) }.toValidJson()
    }
}

@Serializable
data class WebenNoteSaveParam(
    /** null 表示新建，传入现有 id 则更新。 */
    val id: String? = null,
    @SerialName("concept_id") val conceptId: String,
    val content: String,
)
