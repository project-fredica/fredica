package com.github.project_fredica.worker

// =============================================================================
// WorkerEngine —— 单节点异步任务调度引擎
// =============================================================================
//
// 工作原理：
//   1. 启动一个"轮询协程"，每隔 POLL_INTERVAL_MS 尝试从数据库认领一个任务
//   2. 认领成功后，launch 一个新协程来执行该任务（异步，不阻塞轮询）
//   3. 用 Semaphore(maxWorkers) 限制同时执行的任务数量上限
//   4. 队列为空时，轮询间隔自动退避到 IDLE_BACKOFF_MS，减少空轮询开销
//
// Task 状态机（完整流转）：
//   创建          → pending
//   claimNext()   → pending  → claimed   （Worker 原子认领，DAG 依赖全部 completed 才可认领）
//   dispatch()    → claimed  → running   （Executor 开始执行）
//   执行成功      → running  → completed → 触发 WorkflowRun recalculate
//   用户取消      → running  → cancelled → 触发 WorkflowRun recalculate
//   失败可重试    → running  → pending   （retry_count++，重新入队等待 claimNext）
//   失败不可重试  → running  → failed    → 触发 WorkflowRun recalculate
//   级联取消      → pending/claimed → cancelled（WorkflowRun 取消时批量处理）
//   启动恢复      → running/claimed/pending → cancelled（APP 重启时清理僵尸任务）
//
// 多平台说明：
//   WorkerEngine 本身位于 commonMain，仅依赖 kotlinx.coroutines（多平台）。
//   Executor 实现位于 jvmMain，由 FredicaApi.jvm.kt 启动时通过 executors 参数传入。
//
// Phase 1 限制：
//   - 单节点模式，WORKER_ID 硬编码为 "local-node-1"
//   - 无心跳/死亡检测（Phase 3 实现）
//   - Executor 列表在启动时固定，不支持热加载
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.db.RestartTaskLogService
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object WorkerEngine {
    private val logger = createLogger()

    // 单节点模式下 worker ID 固定；多节点时每台机器有独立 ID（Phase 3）
    const val WORKER_ID = "local-node-1"

    // 有任务时的轮询间隔（1 秒）
    private const val POLL_INTERVAL_MS = 1_000L

    // 队列为空时的退避间隔（5 秒），减少无效数据库查询
    private const val IDLE_BACKOFF_MS = 5_000L

    // ==========================================================================
    // commonMain 通用 Executor：纯 Kotlin 实现，无任何平台特定依赖。
    // JVM 专属 Executor（依赖 ProcessBuilder / PythonUtil）在 jvmMain 中实现，
    // 由 FredicaApi.jvm.kt 启动时通过 executors 参数显式传入。
    // ==========================================================================
    private val commonExecutors: List<TaskExecutor> = listOf()

    /** 已注册的 Executor，key = taskType（如 "DOWNLOAD_VIDEO"）。 */
    private val registry = mutableMapOf<String, TaskExecutor>()

    /**
     * 启动 Worker 引擎。在服务器初始化完成后调用一次。
     *
     * 此函数为挂起函数（suspend），在返回前会先完成所有启动恢复操作
     * （resetStaleTasks / failOrphanedTasks / reconcileNonTerminal），
     * 确保调用方在创建新任务前，上次会话的僵尸任务已全部清理完毕。
     * 这样可以避免测试中的竞态：若任务在恢复完成前已插入，resetStaleTasks
     * 会将其误取消。
     *
     * @param maxWorkers  最大并行任务数（受 Semaphore 限制）
     * @param scope       协程 scope（通常使用服务器生命周期的 IO scope）
     * @param executors   Executor 列表；JVM 平台由 FredicaApi.jvm.kt 传入；
     *                    测试时可传入 FakeExecutor 覆盖
     */
    suspend fun start(
        maxWorkers: Int,
        scope: CoroutineScope,
        executors: List<TaskExecutor> = commonExecutors,
    ) {
        // 注册 Executor（允许测试用 fake 覆盖 default）
        executors.forEach { registry[it.taskType] = it }
        logger.info("WorkerEngine 启动 — maxWorkers=$maxWorkers, executors=${registry.keys}")

        // 启动恢复：同步完成，确保调用方（测试或生产代码）在此后插入的任务不被误取消
        runStartupRecovery()

        val sem = Semaphore(maxWorkers) // 信号量：同时最多 maxWorkers 个任务在执行

        // 主轮询协程：不断尝试认领任务
        scope.launch {
            var idling = false // 用于避免"空队列"日志刷屏
            while (isActive) {
                val task = try {
                    TaskService.repo.claimNext(WORKER_ID)
                } catch (e: Throwable) {
                    // claimNext 出错（如数据库连接断开）：等待后重试
                    logger.error("WorkerEngine: claimNext 出错", e)
                    delay(IDLE_BACKOFF_MS)
                    continue
                }

                if (task == null) {
                    // 队列为空，退避到较长的间隔
                    if (!idling) {
                        logger.debug("WorkerEngine: 队列为空，开始退避轮询")
                        idling = true
                    }
                    delay(IDLE_BACKOFF_MS)
                    continue
                }

                idling = false
                logger.info("WorkerEngine: [pending→claimed] 认领任务 ${task.id}（type=${task.type}）")

                // 启动独立协程执行任务，轮询协程不等待它完成，继续认领下一个
                scope.launch {
                    sem.withPermit { // 占用一个 Semaphore 许可，超过 maxWorkers 时在此等待
                        dispatch(task)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── runStartupRecovery：启动恢复（同步执行，start() 返回前完成）────────────

    /**
     * 执行三道启动恢复保护，所有步骤完成后 start() 才返回。
     *
     * 同步执行的目的：确保调用方在创建新任务之前，上次会话的僵尸任务已全部清理完毕。
     * 若恢复在后台异步执行，调用方插入的新 pending 任务可能被 resetStaleTasks() 误取消。
     */
    private suspend fun runStartupRecovery() {
        // 1. 快照非终态任务（在 resetStaleTasks() 修改状态之前），用于记录重启日志
        val nonTerminalSnapshot = try {
            TaskService.repo.snapshotNonTerminalTasks()
        } catch (e: Throwable) {
            logger.warn("WorkerEngine 启动恢复: 快照非终态任务失败", isHappensFrequently = false, err = e); emptyList()
        }

        // 2. 重置僵尸任务（running/claimed/pending → cancelled）
        try {
            val affectedWfIds = TaskService.repo.resetStaleTasks()
            if (affectedWfIds.isNotEmpty()) {
                affectedWfIds.forEach {
                    try { WorkflowRunService.repo.recalculate(it) } catch (_: Throwable) {}
                }
                logger.info("WorkerEngine 启动恢复: 已重置 ${affectedWfIds.size} 个工作流的遗留僵尸任务")
            }
        } catch (e: Throwable) {
            logger.error("WorkerEngine 启动恢复失败", e)
        }

        // 3. 写重启日志（仅当确实有非终态任务被记录）
        if (nonTerminalSnapshot.isNotEmpty()) {
            val sessionId = UUID.randomUUID().toString()
            val nowSec = System.currentTimeMillis() / 1000L
            try {
                RestartTaskLogService.repo.recordRestartSession(sessionId, nonTerminalSnapshot, nowSec)
                logger.info("WorkerEngine 启动恢复: 记录中断任务 ${nonTerminalSnapshot.size} 条，session=$sessionId")
            } catch (e: Throwable) {
                logger.warn("WorkerEngine 启动恢复: 写重启日志失败（不影响正常启动）", isHappensFrequently = false, err = e)
            }
        }

        // 4. 孤立任务对账：标记 workflow_run 已被删除的任务为 failed
        try {
            val orphaned = TaskService.repo.failOrphanedTasks()
            if (orphaned.isNotEmpty()) {
                logger.info("WorkerEngine 启动恢复: 清理孤立任务 ${orphaned.size} 个")
            }
        } catch (e: Throwable) {
            logger.error("WorkerEngine 孤立任务对账失败", e)
        }

        // 5. WorkflowRun 批量对账：修正因 recalculate() 异常而落后的汇总状态
        try {
            val fixed = WorkflowRunService.repo.reconcileNonTerminal()
            if (fixed > 0) {
                logger.info("WorkerEngine 启动恢复: 修正落后状态的 WorkflowRun $fixed 个")
            }
        } catch (e: Throwable) {
            logger.error("WorkerEngine WorkflowRun 对账失败", e)
        }
    }

    // ── dispatch：任务执行 + 重试/失败处理 ────────────────────────────────────

    /**
     * 将认领到的任务分发给对应的 Executor 执行，并处理执行结果。
     *
     * 执行流程：
     * 1. 根据 task.type 从 registry 查找 Executor；找不到则永久失败
     * 2. check_skip 检查：payload 含 check_skip=true 且 executor.canSkip() 为真时，
     *    直接标记 completed（result={"skipped":true}），跳过实际执行
     * 3. 将任务状态改为 running
     * 4. 调用 Executor.execute()（捕获所有异常，防止协程崩溃）
     * 5. 根据结果更新状态：
     *    - CANCELLED → cancelled（不重试）
     *    - 成功 → completed
     *    - 失败 + 可重试（retryCount < maxRetries，且非 AWAITING_CREDENTIAL）
     *        → retry_count++ → 回到 pending（等待下次 claimNext 认领）
     *    - 失败 + 无重试 → failed（永久）
     * 6. 触发 WorkflowRun recalculate（更新整体进度和状态）
     */
    private suspend fun dispatch(task: Task) {
        // 步骤 1：查找 Executor
        val executor = registry[task.type]
        if (executor == null) {
            logger.error("WorkerEngine: 找不到 type=${task.type} 的 Executor，任务 ${task.id} 将永久失败")
            finishFailed(task, "No executor registered for type=${task.type}", "NO_EXECUTOR")
            afterTaskFinished(task.workflowRunId)
            return
        }

        // 步骤 1.5：check_skip 跳过检查
        // payload 中 check_skip=true 时，询问 executor 是否可跳过（前置结果已存在）。
        // 跳过时直接标记 completed，不进入 running 状态，也不消耗重试次数。
        // 典型场景：WorkflowRun 重新触发时，已下载/已转码的任务自动跳过。
        val checkSkip = runCatching {
            Json.parseToJsonElement(task.payload).jsonObject["check_skip"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
        if (checkSkip && executor.canSkip(task)) {
            logger.info("WorkerEngine: 任务 ${task.id}（${task.type}）已跳过 — 前置结果已存在，直接标记 completed")
            TaskService.repo.updateStatus(task.id, "completed", result = """{"skipped":true}""")
            afterTaskFinished(task.workflowRunId)
            return
        }

        // 步骤 2：claimed → running（Executor 开始执行）
        logger.info("WorkerEngine: [claimed→running] 任务 ${task.id}（${task.type}），workflowRunId=${task.workflowRunId}")
        TaskService.repo.updateStatus(task.id, "running")

        // 步骤 3：执行任务（捕获所有异常，防止协程崩溃导致引擎停止）
        // 信号注册/注销由 WebSocketTaskExecutor 基类统一处理，引擎层无需重复操作。
        val execResult = try {
            executor.execute(task)
        } catch (e: Throwable) {
            logger.error("WorkerEngine: Executor 抛出未捕获异常，任务 ${task.id}", e)
            ExecuteResult(error = e.message ?: e::class.simpleName, errorType = "EXCEPTION")
        }

        // 步骤 4：根据结果更新状态
        when {
            execResult.errorType == "CANCELLED" -> {
                // running → cancelled：用户主动取消，不触发重试
                TaskService.repo.updateStatus(task.id, "cancelled", error = execResult.error, errorType = "CANCELLED")
                logger.info("WorkerEngine: [running→cancelled] 任务 ${task.id}（${task.type}）已被用户取消")
                // 通知 Executor 处理业务副作用（如重置 WebenSource 状态）
                runCatching { executor.onTaskFailed(task, execResult) }
                    .onFailure { logger.error("WorkerEngine: onTaskFailed 回调异常，任务 ${task.id}", it) }
            }
            execResult.isSuccess -> {
                // running → completed
                TaskService.repo.updateStatus(task.id, "completed", result = execResult.result)
                logger.info("WorkerEngine: [running→completed] 任务 ${task.id}（${task.type}）执行成功")
            }
            else -> {
                // AWAITING_CREDENTIAL：等待用户配置凭据，不自动重试（需用户手动干预）
                val canRetry = task.retryCount < task.maxRetries &&
                        execResult.errorType != "AWAITING_CREDENTIAL"
                if (canRetry) {
                    // running → pending（失败可重试，retry_count++，重新入队）
                    TaskService.repo.incrementRetry(task.id)
                    TaskService.repo.updateStatus(
                        id = task.id,
                        status = "pending",
                        error = execResult.error,
                        errorType = execResult.errorType,
                    )
                    logger.warn(
                        "WorkerEngine: [running→pending] 任务 ${task.id}（${task.type}）失败，将重试" +
                        "（第 ${task.retryCount + 1}/${task.maxRetries} 次）: ${execResult.error}"
                    )
                } else {
                    // 重试次数耗尽或不可重试，永久失败
                    finishFailed(task, execResult.error, execResult.errorType)
                    // 通知 Executor 处理业务副作用（如重置 WebenSource 状态）
                    runCatching { executor.onTaskFailed(task, execResult) }
                        .onFailure { logger.error("WorkerEngine: onTaskFailed 回调异常，任务 ${task.id}", it) }
                }
            }
        }

        // 步骤 5：重新计算工作流运行实例进度（recalculate 属于 WorkflowRunRepo 职责）
        afterTaskFinished(task.workflowRunId)
    }

    /**
     * 将任务标记为永久失败并记录日志。
     * 调用时机：重试次数耗尽，或 errorType 为不可重试类型（如 AWAITING_CREDENTIAL）。
     */
    private suspend fun finishFailed(task: Task, error: String?, errorType: String?) {
        // running → failed（重试耗尽或不可重试类型）
        TaskService.repo.updateStatus(task.id, "failed", error = error, errorType = errorType)
        logger.error("WorkerEngine: [running→failed] 任务 ${task.id}（${task.type}）永久失败: $error [errorType=$errorType]")
    }

    /**
     * 任务状态变更后重新计算 WorkflowRun 整体进度。
     *
     * recalculate() 会统计同一 workflowRunId 下所有任务的状态，
     * 更新 workflow_run.status / done_tasks / total_tasks。
     * 失败不致命（下次有任务完成时会再次触发），仅记录日志。
     */
    private suspend fun afterTaskFinished(workflowRunId: String) {
        try {
            WorkflowRunService.repo.recalculate(workflowRunId)
            logger.debug("WorkerEngine: WorkflowRun $workflowRunId 进度已重新计算")
        } catch (e: Throwable) {
            logger.error("WorkerEngine: 重新计算 WorkflowRun $workflowRunId 进度失败", e)
        }
    }
}
