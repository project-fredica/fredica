package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.promptgraph.PromptGraphRunService
import com.github.project_fredica.db.promptgraph.PromptNodeRunService
import kotlinx.serialization.encodeToString

/**
 * 查询 PromptGraphRun 详情（含全部节点运行记录）。
 *
 * 路由：GET /api/v1/PromptGraphRunGetRoute?id=<run_id>（需鉴权）
 * 响应：{ "run": {...}, "node_runs": [...] }
 */
object PromptGraphRunGetRoute : FredicaApi.Route {
    private val logger = createLogger()
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询 PromptGraphRun 详情（含全部节点运行记录）"

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val runId = query["id"]?.firstOrNull()
            ?: return buildValidJson { kv("error", "缺少参数 id") }

        logger.debug("PromptGraphRunGetRoute: 查询 runId=$runId")

        val run = PromptGraphRunService.repo.getById(runId)
            ?: run {
                logger.debug("PromptGraphRunGetRoute: 未找到 runId=$runId")
                return buildValidJson { kv("error", "PromptGraphRun 未找到: $runId") }
            }

        val nodeRuns = PromptNodeRunService.repo.listByRun(runId)
        logger.debug("PromptGraphRunGetRoute: 返回 runId=$runId status=${run.status} nodeRunCount=${nodeRuns.size}")

        val json = AppUtil.GlobalVars.json

        return buildValidJson {
            kv("run", ValidJsonString(json.encodeToString(run)))
            kv("node_runs", ValidJsonString(json.encodeToString(nodeRuns)))
        }
    }
}
