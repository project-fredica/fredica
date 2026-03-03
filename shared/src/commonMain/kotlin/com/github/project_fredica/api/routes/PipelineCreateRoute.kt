package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toJsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.github.project_fredica.db.PipelineInstance
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * POST /api/v1/PipelineCreateRoute
 *
 * Creates a pipeline instance and enqueues all its tasks.
 * Returns 409-like error if an active pipeline already exists for the same (material_id, template).
 * Validates that depends_on IDs all refer to tasks within the same batch (DAG cycle check: IDs must exist).
 */
object PipelineCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "创建流水线实例并批量提交任务"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<PipelineCreateParam>().getOrThrow()
        val nowSec = System.currentTimeMillis() / 1000L

        // Idempotency: reject if active pipeline already exists
        if (PipelineService.repo.hasActivePipeline(p.materialId, p.template)) {
            return buildValidJson {
                kv("error", "active_pipeline_exists")
                kv("material_id", p.materialId)
                kv("template", p.template)
            }
        }

        val pipelineId = UUID.randomUUID().toString()

        // Assign stable IDs first so depends_on references are valid
        val taskIds = p.tasks.map { UUID.randomUUID().toString() }

        // Basic DAG validation: depends_on items must be IDs within this batch
        for ((idx, taskDef) in p.tasks.withIndex()) {
            val depIds = parseIds(taskDef.dependsOn)
            val invalidDeps = depIds.filter { depId -> depId !in taskIds }
            if (invalidDeps.isNotEmpty()) {
                return buildValidJson {
                    kv("error", "invalid_depends_on")
                    kv("task_index", idx)
                    kv("invalid_ids", invalidDeps.map { JsonPrimitive(it) }.toJsonArray())
                }
            }
            // Detect self-cycle
            if (taskIds[idx] in depIds) {
                return buildValidJson { kv("error", "self_cycle"); kv("task_index", idx) }
            }
        }

        val tasks = p.tasks.mapIndexed { idx, def ->
            Task(
                id             = taskIds[idx],
                type           = def.type,
                pipelineId     = pipelineId,
                materialId     = p.materialId,
                status         = "pending",
                priority       = def.priority,
                dependsOn      = def.dependsOn,
                cachePolicy    = def.cachePolicy,
                payload        = def.payload,
                idempotencyKey = def.idempotencyKey,
                maxRetries     = def.maxRetries,
                createdAt      = nowSec,
            )
        }

        val pipeline = PipelineInstance(
            id         = pipelineId,
            materialId = p.materialId,
            template   = p.template,
            status     = "pending",
            totalTasks = tasks.size,
            doneTasks  = 0,
            createdAt  = nowSec,
        )

        PipelineService.repo.create(pipeline)
        TaskService.repo.createAll(tasks)

        return buildValidJson { kv("pipeline_id", pipelineId); kv("task_count", tasks.size) }
    }

    private fun parseIds(json: String): List<String> = try {
        json.trim('[', ']').split(',')
            .map { it.trim('"', ' ') }
            .filter { it.isNotBlank() }
    } catch (_: Throwable) { emptyList() }
}

@Serializable
data class PipelineCreateParam(
    @SerialName("material_id") val materialId: String,
    val template: String,
    val tasks: List<PipelineTaskDef>,
)

@Serializable
data class PipelineTaskDef(
    val type: String,
    @SerialName("depends_on")      val dependsOn: String = "[]",
    val priority: Int = 0,
    val payload: String = "{}",
    @SerialName("cache_policy")    val cachePolicy: String = "NONE",
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("max_retries")     val maxRetries: Int = 3,
)
