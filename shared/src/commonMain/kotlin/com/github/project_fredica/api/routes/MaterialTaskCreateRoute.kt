package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialTask
import com.github.project_fredica.db.MaterialTaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

object MaterialTaskCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "创建素材处理任务（支持批量，适用于任意素材类型）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialTaskCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L

        val tasks = p.tasks.map { item ->
            MaterialTask(
                id = UUID.randomUUID().toString(),
                materialId = item.materialId,
                taskType = item.taskType,
                status = "queued",
                dependsOn = item.dependsOn,
                inputPath = item.inputPath,
                outputPath = "",
                errorMsg = "",
                createdAt = nowSec,
                updatedAt = nowSec,
            )
        }

        MaterialTaskService.repo.createAll(tasks)
        return ValidJsonString("""{"created":${tasks.size}}""")
    }
}

@Serializable
data class MaterialTaskCreateParam(
    val tasks: List<MaterialTaskCreateItem>,
)

@Serializable
data class MaterialTaskCreateItem(
    @SerialName("material_id")  val materialId: String,
    @SerialName("task_type")    val taskType: String,
    @SerialName("depends_on")   val dependsOn: String = "[]",
    @SerialName("input_path")   val inputPath: String = "",
)
