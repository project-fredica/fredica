package com.github.project_fredica.db

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// =============================================================================
// StartupReconcileGuard —— 通用启动级单次对账保护（可复用，两级锁）
// =============================================================================
//
// 与 WebenSourceReconcileGuard（WebenSourceListRoute.kt）逻辑相同，
// 但封装为参数化类，可在不同维度（workflowRunId / materialId）各自实例化，
// 互不干扰。
//
// 两级锁设计：
//   guardMutex（短暂持有）：保护 reconciledKeys / keyMutexes 的读写
//   per-key Mutex（长时间持有，含对账 IO）：串行化同一 key 的并发对账；
//     不同 key 的对账可并发，互不阻塞。
//
// 使用方式：
//   val guard = StartupReconcileGuard("WorkflowRun")
//   guard.reconcileOnceIfNeeded(wfId) { /* 对账逻辑 */ }
//
// 生命周期：跟随实例，通常为进程级单例（object 或 companion object 中声明）。
// =============================================================================
class StartupReconcileGuard(private val tag: String) {
    private val logger = createLogger { "StartupReconcileGuard[$tag]" }

    /** 元数据操作的短暂 Mutex（只在查找/写入 reconciledKeys / keyMutexes 时持有） */
    private val guardMutex = Mutex()

    /** 本次 APP 启动内已完成对账的 key 集合，仅在 guardMutex 保护下读写 */
    private val reconciledKeys = mutableSetOf<String>()

    /** 每个 key 的专属 Mutex，懒创建，避免不同 key 互相阻塞 */
    private val keyMutexes = mutableMapOf<String, Mutex>()

    /**
     * 若 [key] 在本次 APP 启动内尚未对账，在其专属 Mutex 下执行 [block] 并标记完成。
     * 若已对账则立即跳过，不执行 [block]。
     *
     * 并发行为：
     * - 同一 key 的多个并发调用：第一个执行 [block]，其余等待后 double-check 跳过
     * - 不同 key 的并发调用：互不阻塞（各自持有独立 Mutex）
     * - 已对账的 key（后续反复查询）：Level-1 快速路径，几乎零开销
     *
     * 异常处理：若 [block] 抛出异常，则本次不写入 reconciledKeys，
     * 下次调用时会重新尝试对账，避免一次性失败永久跳过。
     */
    suspend fun reconcileOnceIfNeeded(key: String, block: suspend () -> Unit) {
        // ── Level-1：检查已对账集合并获取 per-key Mutex（短暂持有 guardMutex）────
        val (alreadyDone, keyMutex) = guardMutex.withLock {
            Pair(key in reconciledKeys, keyMutexes.getOrPut(key) { Mutex() })
        }
        // 大多数调用（已对账后的反复查询）在此快速返回，避免不必要的 Level-2 锁竞争
        if (alreadyDone) return

        // ── Level-2：per-key Mutex 串行化同一 key 的对账 ─────────────────────────
        keyMutex.withLock {
            // Double-check：在等待 keyMutex 期间，另一协程可能已完成对账
            val stillNeeded = guardMutex.withLock { key !in reconciledKeys }
            if (!stillNeeded) {
                logger.debug("[$tag] key=$key 等待锁期间已被其他协程完成对账，跳过")
                return@withLock
            }

            logger.info("[$tag] key=$key 本次启动首次访问，开始对账")
            try {
                // 执行对账（此时 guardMutex 已释放，不阻塞其他 key 的 Level-1 访问）
                block()
            } catch (e: Throwable) {
                // 对账失败不应阻断响应，不写入 reconciledKeys，下次访问重新尝试
                logger.error("[$tag] key=$key 对账异常，本次跳过标记", e)
                return@withLock
            }

            // 标记对账完成（持 guardMutex 原子写入）
            guardMutex.withLock { reconciledKeys.add(key) }
            logger.info("[$tag] key=$key 对账完成，已标记（后续查询将跳过）")
        }
    }
}

// =============================================================================
// 全局共享对账保护实例
// =============================================================================
//
// wfStartupGuard 由 WorkflowRunStatusService 和 TaskStatusService 共享：
// 若 WorkflowRunStatusService.getById("wf-A") 已将 wf-A 标记为已对账，
// 则 TaskStatusService.listByWorkflowRun("wf-A") 会直接跳过，不重复 recalculate。
// =============================================================================

/** WorkflowRun 维度的共享对账保护（WorkflowRunStatusService + TaskStatusService 共用） */
internal val wfStartupGuard = StartupReconcileGuard("WorkflowRun")

// =============================================================================
// WorkflowRunStatusService —— WorkflowRun 增删改查统一服务层
// =============================================================================
//
// 职责：
//   封装 WorkflowRunRepo 的所有操作，并在读操作中添加首次启动对账保护。
//
// 对账触发（每次 APP 启动，每个 workflowRunId 只执行一次）：
//   getById(id) ── 首次查询某 WF 时，先调用 recalculate() 确保汇总状态
//                  与 Task 实际状态一致（防止上次会话 recalculate() 失败遗留的落后状态）
//
// 写操作（create / recalculate / reconcileNonTerminal）：
//   直接转发到 WorkflowRunService.repo，不触发对账（对账方向是"读时修正"）
//
// 与 WorkflowRunService 的关系：
//   WorkflowRunService 仅持有 WorkflowRunRepo 引用（无业务逻辑）。
//   本服务在其之上增加对账保护，路由层统一使用本服务，
//   WorkerEngine 等引擎内部继续使用 WorkflowRunService.repo（无需对账开销）。
// =============================================================================
object WorkflowRunStatusService {
    private val logger = createLogger { "WorkflowRunStatusService" }

    // ── 写操作：直接转发，不触发对账 ─────────────────────────────────────────

    /**
     * 创建 WorkflowRun 记录（幂等：id 冲突时静默忽略）。
     */
    suspend fun create(run: WorkflowRun) {
        logger.debug("create: id=${run.id} template=${run.template} materialId=${run.materialId}")
        WorkflowRunService.repo.create(run)
    }

    /**
     * 重新从 Task 实际状态推导并更新 WorkflowRun 的 done_tasks / total_tasks / status。
     * 由 WorkerEngine 在每次任务状态变更后调用，本服务直接转发。
     */
    suspend fun recalculate(workflowRunId: String) {
        logger.debug("recalculate: workflowRunId=$workflowRunId")
        WorkflowRunService.repo.recalculate(workflowRunId)
    }

    /**
     * 批量对账：对所有非终态 WorkflowRun 调用 recalculate()，修正落后的汇总状态。
     * 由 WorkerEngine 启动恢复时调用，本服务直接转发。
     *
     * @return 状态被修改的 WorkflowRun 数量
     */
    suspend fun reconcileNonTerminal(): Int {
        logger.debug("reconcileNonTerminal: 开始批量对账")
        val count = WorkflowRunService.repo.reconcileNonTerminal()
        logger.info("reconcileNonTerminal: 完成，共修正 $count 个 WorkflowRun")
        return count
    }

    // ── 读操作：首次访问触发启动对账 ─────────────────────────────────────────

    /**
     * 按 ID 查询 WorkflowRun，首次访问时对账（确保汇总状态与 Task 实际一致）。
     *
     * 对账内容：
     * - 若 WF 为非终态（pending / running）：调用 recalculate() 重算状态
     * - 若 WF 为终态或不存在：跳过对账
     *
     * 首次对账后，后续查询直接返回 DB 数据，不再触发 recalculate()。
     *
     * @param id WorkflowRun ID
     * @return WorkflowRun 对象，不存在时返回 null
     */
    suspend fun getById(id: String): WorkflowRun? {
        wfStartupGuard.reconcileOnceIfNeeded(id) {
            reconcileWf(id)
        }
        return WorkflowRunService.repo.getById(id)
    }

    // ── 内部对账实现（供 TaskStatusService 复用）──────────────────────────────

    /**
     * 对单个 WorkflowRun 执行对账：确保其汇总状态与 Task 实际状态一致。
     *
     * 执行逻辑：
     * - 查询 WF；若不存在或已为终态，直接返回（终态无需重算）
     * - 否则调用 recalculate() 重新从 Task 表推导状态
     *
     * 此方法为 internal，供 TaskStatusService 复用，不对外暴露。
     */
    internal suspend fun reconcileWf(workflowRunId: String) {
        val wf = runCatching {
            WorkflowRunService.repo.getById(workflowRunId)
        }.getOrElse {
            logger.error("reconcileWf: WorkflowRun($workflowRunId) 查询失败，跳过对账", it)
            return
        }

        if (wf == null) {
            logger.debug("reconcileWf: WorkflowRun($workflowRunId) 不存在，跳过")
            return
        }

        if (wf.status in setOf("completed", "failed", "cancelled")) {
            logger.debug("reconcileWf: WorkflowRun($workflowRunId) 已为终态(${wf.status})，跳过")
            return
        }

        runCatching {
            WorkflowRunService.repo.recalculate(workflowRunId)
        }.onSuccess {
            logger.info("reconcileWf: WorkflowRun($workflowRunId) 对账完成（原状态=${wf.status}）")
        }.onFailure {
            logger.error("reconcileWf: WorkflowRun($workflowRunId) recalculate 失败", it)
        }
    }
}

// =============================================================================
// TaskStatusService —— Task 增删改查统一服务层
// =============================================================================
//
// 职责：
//   封装 TaskRepo 的所有操作，并在读操作中添加首次启动对账保护。
//
// 对账触发（每次 APP 启动，每个维度 key 只执行一次）：
//
//   findById(taskId) ── 首次查询某任务时，对其父 WorkflowRun 对账
//     原因：任务自身状态由 WorkerEngine 实时写入，始终准确；
//           但父 WF 的汇总状态可能因上次 recalculate() 失败而落后，
//           首次查询时修正，使调用方后续查 WF 状态时得到准确值。
//
//   listByWorkflowRun(wfId) ── 首次查询某 WF 的任务列表时，对该 WF 对账
//
//   listAll(materialId=...) ── 首次查询某素材任务时，对该素材下所有非终态 WF 对账
//   listAll(workflowRunId=...) ── 首次查询某 WF 的任务时，对该 WF 对账
//   listAll（均未提供）── 不触发对账（WorkerEngine 启动时已全局对账）
//
// 写操作（create / createAll / updateStatus / updateProgress / …）：
//   直接转发到 TaskService.repo，不触发对账。
//
// 引擎操作（claimNext / resetStaleTasks / failOrphanedTasks / …）：
//   直接转发到 TaskService.repo；WorkerEngine 直接调用这些方法，
//   本服务将其收拢此处，但不添加对账保护（引擎操作本身已是对账的一部分）。
//
// 与 TaskService 的关系：
//   TaskService 仅持有 TaskRepo 引用（无业务逻辑）。
//   本服务在其之上增加对账保护，路由层统一使用本服务。
// =============================================================================
object TaskStatusService {
    private val logger = createLogger { "TaskStatusService" }

    /** WorkflowRun 维度：与 WorkflowRunStatusService 共享 wfStartupGuard */
    private val wfGuard get() = wfStartupGuard

    /**
     * material 维度的对账保护：独立于 WF 维度，
     * 因为一个 material 对应多个 WF，需要批量对账。
     */
    private val materialGuard = StartupReconcileGuard("Task/Material")

    // ── 写操作：直接转发，不触发对账 ─────────────────────────────────────────

    /** 插入单个任务（id 冲突时静默忽略）。 */
    suspend fun create(task: Task): Task {
        logger.debug("create: id=${task.id} type=${task.type} wfId=${task.workflowRunId}")
        return TaskService.repo.create(task)
    }

    /** 批量插入任务（id 冲突时静默忽略）。 */
    suspend fun createAll(tasks: List<Task>) {
        logger.debug("createAll: ${tasks.size} 个任务，types=${tasks.map { it.type }}")
        TaskService.repo.createAll(tasks)
    }

    /**
     * 更新任务状态，同时记录 result / error / error_type。
     * - 状态变为 running 时自动记录 started_at
     * - 状态变为 completed / failed / cancelled 时自动记录 completed_at
     */
    suspend fun updateStatus(
        id: String,
        status: String,
        result: String? = null,
        error: String? = null,
        errorType: String? = null,
    ) {
        logger.debug("updateStatus: id=$id → status=$status errorType=$errorType")
        TaskService.repo.updateStatus(id, status, result, error, errorType)
    }

    /** 将 retry_count +1（任务失败可重试时，在 reset 到 pending 之前调用）。 */
    suspend fun incrementRetry(id: String) {
        logger.debug("incrementRetry: id=$id")
        TaskService.repo.incrementRetry(id)
    }

    /** 更新任务执行进度（0–100）。 */
    suspend fun updateProgress(id: String, progress: Int) {
        TaskService.repo.updateProgress(id, progress)
    }

    /** 更新任务暂停状态（is_paused）。 */
    suspend fun updatePaused(id: String, paused: Boolean) {
        logger.debug("updatePaused: id=$id paused=$paused")
        TaskService.repo.updatePaused(id, paused)
    }

    /** 更新任务可暂停标志（is_pausable），由 Python progress 消息透传。 */
    suspend fun updatePausable(id: String, pausable: Boolean) {
        TaskService.repo.updatePausable(id, pausable)
    }

    // ── 引擎操作：直接转发（WorkerEngine 内部逻辑，不加对账保护）─────────────

    /**
     * 原子认领下一个可执行任务（DAG 感知，所有 depends_on 均已 completed 才认领）。
     * 由 WorkerEngine 轮询协程调用。
     */
    suspend fun claimNext(workerId: String): Task? = TaskService.repo.claimNext(workerId)

    /**
     * 启动恢复：重置 APP 强杀遗留的僵尸任务。
     * - running → failed（执行结果不确定，标记永久失败）
     * - claimed → pending（仅认领未执行，可安全重新入队）
     *
     * @return 受影响任务所属的 workflowRunId 集合（调用方据此触发 recalculate）
     */
    suspend fun resetStaleTasks(): Set<String> {
        val affected = TaskService.repo.resetStaleTasks()
        if (affected.isNotEmpty()) {
            logger.info("resetStaleTasks: 重置完成，影响 ${affected.size} 个 WorkflowRun")
        }
        return affected
    }

    /**
     * 对账：标记所有孤立 Task 为 failed（workflowRunId 对应的 WF 已被删除）。
     *
     * @return 被标记为 failed 的 Task ID 列表
     */
    suspend fun failOrphanedTasks(): List<String> {
        val ids = TaskService.repo.failOrphanedTasks()
        if (ids.isNotEmpty()) {
            logger.info("failOrphanedTasks: 标记孤立任务 ${ids.size} 个为 failed")
        }
        return ids
    }

    /**
     * 级联取消：将指定 WorkflowRun 下所有等待中（pending / claimed）的任务标记为 cancelled。
     * 注意：running 任务需通过 TaskCancelService 发送信号，本方法不干预。
     *
     * @param workflowRunId 目标工作流运行实例 ID
     * @return 被取消的 Task ID 列表
     */
    suspend fun cancelPendingTasksByWorkflowRun(workflowRunId: String): List<String> {
        val ids = TaskService.repo.cancelPendingTasksByWorkflowRun(workflowRunId)
        if (ids.isNotEmpty()) {
            logger.info("cancelPendingTasksByWorkflowRun: wfId=$workflowRunId 取消 ${ids.size} 个 pending/claimed 任务")
        }
        return ids
    }

    // ── 读操作：首次访问触发启动对账 ─────────────────────────────────────────

    /**
     * 按 ID 查询单个任务，并对其父 WorkflowRun 进行首次启动对账。
     *
     * 注意：任务自身状态由 WorkerEngine 实时写入，始终准确，无需修正。
     * 此处对父 WF 的对账是副作用——确保后续通过 WorkflowRunStatusService.getById()
     * 查询时能看到正确的汇总状态。
     *
     * @param id Task ID
     * @return Task 对象，不存在时返回 null
     */
    suspend fun findById(id: String): Task? {
        val task = TaskService.repo.findById(id) ?: return null
        // 对父 WorkflowRun 首次对账（wfStartupGuard 保证同一 wfId 全局只对账一次）
        wfGuard.reconcileOnceIfNeeded(task.workflowRunId) {
            WorkflowRunStatusService.reconcileWf(task.workflowRunId)
        }
        return task
    }

    /**
     * 按 workflowRunId 查询任务列表，首次访问时对该 WorkflowRun 进行对账。
     *
     * 对账确保 WorkflowRun.status 与 Task 实际状态一致；
     * 后续反复查询（如 3 秒轮询）直接跳过对账，零额外开销。
     *
     * @param workflowRunId WorkflowRun ID
     * @return 该 WF 下所有任务，按 priority DESC、created_at ASC 排序
     */
    suspend fun listByWorkflowRun(workflowRunId: String): List<Task> {
        wfGuard.reconcileOnceIfNeeded(workflowRunId) {
            WorkflowRunStatusService.reconcileWf(workflowRunId)
        }
        return TaskService.repo.listByWorkflowRun(workflowRunId)
    }

    /**
     * 分页查询任务列表（任务中心 / 素材模态框），首次访问时触发对账。
     *
     * 对账策略（按优先级）：
     * - 提供 [materialId]：对账该素材下所有非终态 WorkflowRun（最常用场景）
     * - 提供 [workflowRunId]（未提供 materialId）：对账该 WorkflowRun
     * - 均未提供：不触发对账（WorkerEngine 启动时已执行全局对账）
     *
     * status 特殊值：
     * - `pending`  = pending + claimed（等待中）
     * - `running`  = running 且 is_paused=false（执行中）
     * - `paused`   = running 且 is_paused=true（已暂停）
     * - 其他值精确匹配
     */
    suspend fun listAll(
        taskId: String? = null,
        workflowRunId: String? = null,
        status: String? = null,
        materialId: String? = null,
        categoryId: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        sortDesc: Boolean = true,
    ): TaskListResult {
        when {
            // material 维度优先：一次对账覆盖该素材下所有 WorkflowRun
            materialId != null ->
                materialGuard.reconcileOnceIfNeeded(materialId) {
                    reconcileMaterial(materialId)
                }
            // 按 workflowRunId 过滤：对账单个 WorkflowRun
            workflowRunId != null ->
                wfGuard.reconcileOnceIfNeeded(workflowRunId) {
                    WorkflowRunStatusService.reconcileWf(workflowRunId)
                }
            // 无 materialId / workflowRunId：WorkerEngine 启动时已全局对账，不重复触发
        }

        return TaskService.repo.listAll(
            taskId        = taskId,
            workflowRunId = workflowRunId,
            status        = status,
            materialId    = materialId,
            categoryId    = categoryId,
            page          = page,
            pageSize      = pageSize,
            sortDesc      = sortDesc,
        )
    }

    // ── 内部对账实现 ───────────────────────────────────────────────────────────

    /**
     * 对 material 下所有非终态 WorkflowRun 进行对账。
     *
     * 执行步骤：
     * 1. 查询该 material 下所有 Task（最多 500 条），提取唯一 workflowRunId 集合
     * 2. 对每个 WF 调用 WorkflowRunStatusService.reconcileWf()
     *    - wfStartupGuard 保证同一 wfId 在 WF 维度全局只对账一次，无重复开销
     *    - 终态 WF（completed / failed / cancelled）在 reconcileWf() 内部跳过
     *
     * 注意：pageSize=500 是合理上限；若某素材任务超过 500 条，部分 WF 将在
     * 下次 listAll(materialId=...) 或 wfId 维度的查询中被对账（最终一致）。
     */
    private suspend fun reconcileMaterial(materialId: String) {
        val tasks = runCatching {
            TaskService.repo.listAll(materialId = materialId, pageSize = 500).items
        }.getOrElse {
            logger.error("reconcileMaterial: materialId=$materialId 查询 Task 失败，跳过对账", it)
            return
        }

        val wfIds = tasks.map { it.workflowRunId }.toSet()
        if (wfIds.isEmpty()) {
            logger.debug("reconcileMaterial: materialId=$materialId 无关联 WorkflowRun，跳过")
            return
        }

        logger.debug("reconcileMaterial: materialId=$materialId 共 ${wfIds.size} 个关联 WorkflowRun，开始对账")
        var processedCount = 0
        for (wfId in wfIds) {
            // wfGuard 确保同一 wfId 在整个 APP 生命周期只对账一次，不重复 recalculate
            wfGuard.reconcileOnceIfNeeded(wfId) {
                WorkflowRunStatusService.reconcileWf(wfId)
            }
            processedCount++
        }
        logger.info("reconcileMaterial: materialId=$materialId 完成，共处理 $processedCount 个 WorkflowRun")
    }
}
