package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.promptgraph.PromptGraphDefService
import com.github.project_fredica.db.promptgraph.PromptGraphRunService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PromptGraph 引擎运行器接口（commonMain 可用的抽象层）。
 *
 * 实现由 jvmMain 的 [PromptGraphEngine] 提供，
 * 通过 [PromptGraphEngineService.initialize] 在启动时注入。
 */
interface PromptGraphRunner {
    suspend fun run(
        defId: String,
        initialContext: Map<String, String>,
        materialId: String?,
        workflowRunId: String?,
        cancelSignal: CompletableDeferred<Unit>?,
    ): String
}

/**
 * PromptGraph 引擎服务（commonMain 单例，隐藏 jvmMain 依赖）。
 */
object PromptGraphEngineService {
    private var _runner: PromptGraphRunner? = null
    val runner: PromptGraphRunner
        get() = _runner ?: error("PromptGraphEngineService 未初始化，请先调用 initialize()")
    fun initialize(runner: PromptGraphRunner) { _runner = runner }
}

/**
 * 启动一个 PromptGraphRun。
 *
 * 引擎在后台异步执行（不阻塞 HTTP 响应），立即返回 run_id。
 * 调用方通过 [PromptGraphRunGetRoute] 轮询运行状态。
 *
 * 路由：POST /api/v1/PromptGraphRunStartRoute（需鉴权）
 * 请求体：[PromptGraphRunStartRequest]
 * 响应：{ "run_id": "uuid", "def_id": "...", "status": "pending|running" }
 */
object PromptGraphRunStartRoute : FredicaApi.Route {
    private val logger = createLogger()
    private val engineScope = CoroutineScope(Dispatchers.IO)

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "启动一个 PromptGraphRun（异步执行，立即返回 run_id）"

    @Serializable
    data class PromptGraphRunStartRequest(
        /** PromptGraphDef.id（含系统内置图，如 "system:weben_video_concept_extract"）。 */
        @SerialName("prompt_graph_def_id") val promptGraphDefId: String,
        /**
         * 初始上下文（输入数据），key=变量名，value=值字符串。
         * 引擎执行节点时将这些键值注入 %变量% 模板替换。
         */
        @SerialName("initial_context") val initialContext: Map<String, String> = emptyMap(),
        /** 关联素材 ID（可选）。 */
        @SerialName("material_id") val materialId: String? = null,
        /** 所属 WorkflowRun.id（可选，由 WorkflowEngine 触发时传入）。 */
        @SerialName("workflow_run_id") val workflowRunId: String? = null,
    )

    override suspend fun handler(param: String): ValidJsonString {
        val req = param.loadJsonModel<PromptGraphRunStartRequest>().getOrElse { e ->
            logger.warn("PromptGraphRunStartRoute: 请求体解析失败: ${e.message}")
            return buildValidJson { kv("error", "请求体解析失败: ${e.message}") }
        }

        logger.debug("PromptGraphRunStartRoute: 收到请求 defId=${req.promptGraphDefId} contextKeys=[${req.initialContext.keys.joinToString()}]")

        // 校验 Graph 定义存在
        PromptGraphDefService.repo.getById(req.promptGraphDefId)
            ?: run {
                logger.debug("PromptGraphRunStartRoute: Graph 定义未找到 defId=${req.promptGraphDefId}")
                return buildValidJson { kv("error", "Graph 定义未找到: ${req.promptGraphDefId}") }
            }

        val cancelSignal = CompletableDeferred<Unit>()

        // 异步启动引擎（不阻塞响应）
        engineScope.launch {
            try {
                val runId = PromptGraphEngineService.runner.run(
                    defId          = req.promptGraphDefId,
                    initialContext = req.initialContext,
                    materialId     = req.materialId,
                    workflowRunId  = req.workflowRunId,
                    cancelSignal   = cancelSignal,
                )
                logger.info("PromptGraphRunStartRoute: 运行完成 runId=$runId")
            } catch (e: Exception) {
                logger.error("PromptGraphRunStartRoute: 运行异常", e)
            }
        }

        // 等待引擎创建 PromptGraphRun 记录（最多 300ms），以便返回真实 runId
        logger.debug("PromptGraphRunStartRoute: 等待 300ms 获取 run_id defId=${req.promptGraphDefId}")
        delay(300)
        val latestRun = PromptGraphRunService.repo.listByDef(req.promptGraphDefId, limit = 1).firstOrNull()
        logger.debug("PromptGraphRunStartRoute: 响应 runId=${latestRun?.id} status=${latestRun?.status}")

        return buildValidJson {
            kv("run_id", latestRun?.id ?: "")
            kv("def_id", req.promptGraphDefId)
            kv("status", latestRun?.status ?: "pending")
        }
    }
}
