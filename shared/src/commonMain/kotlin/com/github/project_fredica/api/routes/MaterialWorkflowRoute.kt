package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.MaterialWorkflowService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 素材工作流路由：按模板启动多任务 WorkflowRun。
 *
 * 业务逻辑由 [MaterialWorkflowService] 实现，此处只做 HTTP 解析 / 响应。
 *
 * 当前支持模板：
 *   - `bilibili_download_transcode`：bilibili 下载 + 转码两步流水线
 */
object MaterialWorkflowRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "素材工作流：按模板启动 WorkflowRun（支持 DAG 依赖）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialWorkflowParam>().getOrThrow()
        val material = MaterialVideoService.repo.findById(p.materialId)
            ?: return buildValidJson { kv("error", "MATERIAL_NOT_FOUND") }

        return when (p.template) {
            "bilibili_download_transcode" -> {
                when (val r = MaterialWorkflowService.startBilibiliDownloadTranscode(material)) {
                    is MaterialWorkflowService.StartResult.AlreadyActive ->
                        buildValidJson { kv("error", "TASK_ALREADY_ACTIVE") }
                    is MaterialWorkflowService.StartResult.Started ->
                        buildValidJson {
                            kv("workflow_run_id",   r.workflowRunId)
                            kv("download_task_id",  r.downloadTaskId)
                            kv("transcode_task_id", r.transcodeTaskId)
                        }
                }
            }
            else -> buildValidJson { kv("error", "UNKNOWN_TEMPLATE") }
        }
    }
}

@Serializable
data class MaterialWorkflowParam(
    @SerialName("material_id") val materialId: String,
    val template: String,
)
