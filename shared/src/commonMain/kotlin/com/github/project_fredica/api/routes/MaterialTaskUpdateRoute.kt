package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialTaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Updates the status of a material task (typically called back by the Python service).
 * Allowed status transitions: queued → running → done / failed
 */
object MaterialTaskUpdateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "更新素材任务状态（供 Python 服务回调）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialTaskUpdateParam>().getOrThrow()
        MaterialTaskService.repo.updateStatus(
            id = p.id,
            status = p.status,
            errorMsg = p.errorMsg,
            outputPath = p.outputPath,
        )
        return ValidJsonString("""{"ok":true}""")
    }
}

@Serializable
data class MaterialTaskUpdateParam(
    val id: String,
    val status: String,
    @SerialName("error_msg")   val errorMsg: String = "",
    @SerialName("output_path") val outputPath: String = "",
)
