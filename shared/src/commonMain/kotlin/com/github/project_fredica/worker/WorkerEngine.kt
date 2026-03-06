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
// 任务完成后的状态流转：
//   执行成功 → status=completed → 触发 pipeline recalculate
//   执行失败 + 还有重试次数 → retry_count++ → status=pending（重新入队）
//   执行失败 + 无重试次数  → status=failed → 触发 pipeline recalculate
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
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
     * @param maxWorkers  最大并行任务数（受 Semaphore 限制）
     * @param scope       协程 scope（通常使用服务器生命周期的 IO scope）
     * @param executors   Executor 列表；JVM 平台由 FredicaApi.jvm.kt 传入；
     *                    测试时可传入 FakeExecutor 覆盖
     */
    fun start(
        maxWorkers: Int,
        scope: CoroutineScope,
        executors: List<TaskExecutor> = commonExecutors,
    ) {
        // 注册 Executor（允许测试用 fake 覆盖 default）
        executors.forEach { registry[it.taskType] = it }
        logger.info("WorkerEngine 启动 — maxWorkers=$maxWorkers, executors=${registry.keys}")

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
                logger.debug("WorkerEngine: 认领任务 ${task.id}（type=${task.type}）")

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

    // ── dispatch：任务执行 + 重试/失败处理 ────────────────────────────────────

    /**
     * 将认领到的任务分发给对应的 Executor 执行，并处理执行结果。
     *
     * 执行流程：
     * 1. 根据 task.type 从 registry 查找 Executor
     * 2. 将任务状态改为 running
     * 3. 调用 Executor.execute()（可能抛出异常）
     * 4. 根据结果更新状态：
     *    - 成功 → completed
     *    - 失败 + 可重试 → retry_count++ → 回到 pending
     *    - 失败 + 无重试 → failed
     * 5. 触发 pipeline recalculate（更新流水线整体进度）
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

        // 步骤 2：标记为执行中
        TaskService.repo.updateStatus(task.id, "running")

        // 步骤 3：执行任务（捕获所有异常，防止协程崩溃）
        val execResult = try {
            executor.execute(task)
        } catch (e: Throwable) {
            logger.error("WorkerEngine: Executor 抛出异常，任务 ${task.id}", e)
            ExecuteResult(error = e.message ?: e::class.simpleName, errorType = "EXCEPTION")
        }

        // 步骤 4：根据结果更新状态
        if (execResult.errorType == "CANCELLED") {
            TaskService.repo.updateStatus(task.id, "cancelled", error = execResult.error, errorType = "CANCELLED")
            logger.info("WorkerEngine: 任务 ${task.id} 已被用户取消")
        } else if (execResult.isSuccess) {
            TaskService.repo.updateStatus(task.id, "completed", result = execResult.result)
            logger.info("WorkerEngine: 任务 ${task.id} 执行成功")
        } else {
            // AWAITING_CREDENTIAL：等待用户配置凭据，不自动重试（需用户手动跳过或重试）
            val canRetry = task.retryCount < task.maxRetries &&
                    execResult.errorType != "AWAITING_CREDENTIAL"
            if (canRetry) {
                // 记录重试次数 +1，然后重置回 pending（让 claimNext 再次认领）
                TaskService.repo.incrementRetry(task.id)
                TaskService.repo.updateStatus(
                    id = task.id,
                    status = "pending",
                    error = execResult.error,
                    errorType = execResult.errorType,
                )
                logger.warn(
                    "WorkerEngine: 任务 ${task.id} 失败（第 ${task.retryCount + 1}/${task.maxRetries} 次重试）: ${execResult.error}"
                )
            } else {
                // 重试次数耗尽，永久失败
                finishFailed(task, execResult.error, execResult.errorType)
            }
        }

        // 步骤 5：重新计算工作流运行实例进度（recalculate 属于 WorkflowRunRepo 职责）
        afterTaskFinished(task.workflowRunId)
    }

    /** 将任务标记为永久失败并记录日志。 */
    private suspend fun finishFailed(task: Task, error: String?, errorType: String?) {
        TaskService.repo.updateStatus(task.id, "failed", error = error, errorType = errorType)
        logger.error("WorkerEngine: 任务 ${task.id} 永久失败（重试耗尽）: $error")
    }

    /**
     * 任务状态变更后重新计算流水线进度。
     * 失败不致命（下次有任务完成时会再次触发），仅记录日志。
     */
    private suspend fun afterTaskFinished(workflowRunId: String) {
        try {
            WorkflowRunService.repo.recalculate(workflowRunId)
        } catch (e: Throwable) {
            logger.error("WorkerEngine: 重新计算工作流运行实例 $workflowRunId 进度失败", e)
        }
    }
}
