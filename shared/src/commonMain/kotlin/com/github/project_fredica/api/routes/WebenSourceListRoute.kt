package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

// =============================================================================
// WebenSourceReconcileGuard —— 启动级单次对账保护（per-material，两级锁）
// =============================================================================
//
// 问题背景：
//   前端知识标签每 5 秒轮询一次 WebenSourceListRoute。reconcileSources() 的主要价值
//   在于检测"上次 APP 崩溃遗留的僵死状态"——这只需在本次启动首次访问时执行一次，
//   后续轮询重复执行属于无意义的 DB 读写，且在并发场景下存在重复对账风险。
//
// 两级锁设计：
//   Level-1  guardMutex：短暂持有，保护 reconciledIds / materialMutexes 的读写
//   Level-2  per-material Mutex：长时间持有（含对账 IO），串行化同一 material 的
//            并发对账；不同 material 的对账可并发执行，互不阻塞
//
// 并发行为：
//   - 同一 material 的多个并发请求：第一个执行对账，其余等待后 double-check 跳过
//   - 不同 material 的并发请求：互不阻塞（各自持有独立 Mutex）
//   - 已对账的 material（5 秒轮询）：Level-1 快速路径，几乎零开销
//
// 生命周期：进程级单例，APP 重启后自动重置（reconciledIds 随进程消亡）。
// =============================================================================
object WebenSourceReconcileGuard {
    private val logger = createLogger { "WebenSourceReconcileGuard" }

    /** 元数据操作的短暂 Mutex（只在查找/写入 reconciledIds / materialMutexes 时持有） */
    private val guardMutex = Mutex()

    /** 本次 APP 启动内已完成对账的 material_id 集合，仅在 guardMutex 保护下读写 */
    private val reconciledIds = mutableSetOf<String>()

    /** 每个 material 的专属 Mutex，懒创建，避免不同 material 互相阻塞 */
    private val materialMutexes = mutableMapOf<String, Mutex>()

    /**
     * 批量标记多个 material 为已对账，跳过实际对账逻辑。
     * 用于启动级全量对账完成后，防止后续 API 轮询重复执行对账逻辑。
     */
    suspend fun markReconciled(materialIds: Collection<String>) {
        if (materialIds.isEmpty()) return
        guardMutex.withLock { reconciledIds.addAll(materialIds) }
        logger.debug("WebenSourceReconcileGuard: 批量标记 ${materialIds.size} 个 material 为已对账")
    }

    /**
     * 若 [materialId] 在本次 APP 启动内尚未对账，在其专属 Mutex 下执行 [block] 并标记完成。
     * 若已对账则立即跳过，不执行 [block]。
     *
     * 可安全地在多个协程中并发调用：
     * - 第一个到达的协程执行 [block]
     * - 随后到达的协程（等待 materialMutex 后）double-check 发现已完成，直接跳过
     * - 已对账的 material 只需持有 guardMutex 极短时间，轮询开销可忽略
     */
    suspend fun reconcileOnceIfNeeded(materialId: String, block: suspend () -> Unit) {
        // ── Level-1：检查已对账集合并获取 per-material Mutex（短暂持有 guardMutex）──
        val (alreadyDone, materialMutex) = guardMutex.withLock {
            Pair(materialId in reconciledIds, materialMutexes.getOrPut(materialId) { Mutex() })
        }
        // 大多数调用（已对账的 5 秒轮询）在此快速返回，避免不必要的 Level-2 锁竞争
        if (alreadyDone) return

        // ── Level-2：per-material Mutex 串行化同一 material 的对账 ─────────────────
        materialMutex.withLock {
            // Double-check：在等待 materialMutex 期间，另一协程可能已完成对账
            val stillNeeded = guardMutex.withLock { materialId !in reconciledIds }
            if (!stillNeeded) {
                logger.debug("WebenSourceReconcileGuard: materialId=$materialId 等待锁期间已被其他协程完成对账，跳过")
                return@withLock
            }

            logger.info("WebenSourceReconcileGuard: materialId=$materialId 本次启动首次访问，开始对账")
            try {
                // 执行对账（此时 guardMutex 已释放，不阻塞其他 material 的 Level-1 访问）
                block()
            } catch (e: Throwable) {
                // 对账失败不应阻断响应，记录日志后继续；下次访问会重新尝试（未写入 reconciledIds）
                logger.error("WebenSourceReconcileGuard: materialId=$materialId 对账异常，本次跳过标记", e)
                return@withLock
            }

            // 标记本 material 对账完成（持 guardMutex 原子写入）
            guardMutex.withLock { reconciledIds.add(materialId) }
            logger.info("WebenSourceReconcileGuard: materialId=$materialId 对账完成，已标记（后续轮询将跳过）")
        }
    }
}

/**
 * GET /api/v1/WebenSourceListRoute[?material_id=<uuid>]
 *
 * 来源库列表，可按 material_id 过滤（不传则返回所有），按创建时间降序。
 *
 * ## 状态对账（Reconcile）
 *
 * 对所有非终态（pending / analyzing）的来源进行状态对账：
 *
 * ### 对账原则
 * 0. workflowRunId 为 null（旧数据迁移前记录 / 写入中断）→ failed
 *    （没有 WorkflowRun 可推进分析，永久卡死比显示失败更糟糕）
 * 1. WorkflowRun 不存在（已被删除）→ failed（孤立记录）
 * 2. WorkflowRun 为终态（failed / cancelled / completed）→ 直接同步
 * 3. WorkflowRun 非终态时，进一步检查 Task 实际状态（WorkflowRun 可能未同步）：
 *    - 任务列表为空（任务已被删除）→ failed
 *    - 任意任务 failed / cancelled   → failed
 *    - 全部任务 completed            → completed
 *    - 仍有任务 running / pending    → 不修改（正常推进中）
 *
 * ### 对账触发策略（首次启动保护）
 * - **按 material_id 查询**（知识标签场景）：借助 [WebenSourceReconcileGuard] 保证
 *   每个 material 每次 APP 启动只对账一次；并发请求安全串行，重复轮询直接跳过。
 * - **全量查询**（不传 material_id）：每次均对账，保持原有兜底行为。
 *
 * ### 设计动机
 * - APP 强杀后，WorkerEngine 启动恢复会把 running → failed、claimed → pending，
 *   并调用 recalculate() 更新 WorkflowRun。但若启动恢复和本接口的调用存在竞态，
 *   或 recalculate() 自身失败，WorkflowRun 可能暂时落后于 Task 实际状态。
 * - 直接读 Task 表提供了第二道保险，确保 WebenSource 状态最终一致。
 * - WorkflowRun 若与 Task 不一致，同时触发 recalculate() 补偿修正。
 */
object WebenSourceListRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenSourceListRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "来源库列表（可按 material_id 过滤，含双层状态对账 + 首次启动保护）"

    override suspend fun handler(param: String): ValidJsonString {
        val query      = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()

        val sources    = WebenSourceService.repo.listAll(materialId)

        val anyReconciled: Boolean
        if (materialId != null) {
            // 有 material_id（知识标签场景）：
            // 每次 APP 启动只对账一次，两级锁保护并发安全，后续轮询零开销跳过
            var reconciled = false
            WebenSourceReconcileGuard.reconcileOnceIfNeeded(materialId) {
                reconciled = reconcileSources(sources)
            }
            anyReconciled = reconciled
        } else {
            // 全量查询（不传 material_id）：每次均对账（兜底保护）
            anyReconciled = reconcileSources(sources)
        }

        val finalSources = if (anyReconciled) {
            // 对账修改了状态，重新查库获取最新数据
            logger.debug("WebenSourceListRoute: 对账有变更，重新查询 materialId=$materialId")
            WebenSourceService.repo.listAll(materialId)
        } else {
            sources
        }
        return ValidJsonString(AppUtil.GlobalVars.json.encodeToString(finalSources))
    }

    /**
     * 遍历所有非终态来源，比对 WorkflowRun 和 Task 的实际状态，
     * 修正因 recalculate() 遗漏或 APP 崩溃导致的 analysisStatus 落后。
     *
     * @return 是否有任何来源状态被修改
     */
    internal suspend fun reconcileSources(sources: List<WebenSource>): Boolean {
        val nonTerminal = sources.filter {
            it.analysisStatus != "completed" && it.analysisStatus != "failed"
        }
        if (nonTerminal.isEmpty()) {
            logger.debug("reconcileSources: 无非终态来源，跳过对账（共 ${sources.size} 条）")
            return false
        }
        logger.debug("reconcileSources: 开始对账，非终态来源 ${nonTerminal.size}/${sources.size} 条")

        var anyReconciled = false
        for (source in nonTerminal) {
            val wfId = source.workflowRunId
            if (wfId == null) {
                // workflowRunId 为 null 且来源处于非终态（pending / analyzing）：
                // 意味着分析流水线从未被正确关联——可能是写入中断、版本迁移前的旧数据，
                // 或创建 WorkflowRun 时发生了异常。
                //
                // 关键问题：没有 WorkflowRun 可以驱动分析，此来源将永远停留在非终态，
                // 前端永远显示"排队中"，用户无法感知也无法重试——这比显示失败更糟糕。
                //
                // 正确处理：标记为 failed，让用户可以看到并选择重新提交。
                // 注：WebenSource 没有 cancelled 状态，failed 是此类孤立记录的唯一合适终态。
                logger.warn(
                    "reconcileSources: source=${source.id} 处于非终态（${source.analysisStatus}）" +
                    " 但 workflowRunId=null，无工作流可推进分析 → 标记 failed（避免永久卡死）"
                )
                runCatching { WebenSourceService.repo.updateAnalysisStatus(source.id, "failed") }
                    .onFailure { logger.error("reconcileSources: source=${source.id} 写入 failed 失败（null wfId 路径）", it) }
                anyReconciled = true
                continue
            }

            // 第一层：检查 WorkflowRun 状态
            val wf    = runCatching { WorkflowRunService.repo.getById(wfId) }.getOrNull()
            // 第二层：检查 Task 实际状态（WorkflowRun 可能与 Task 不同步）
            val tasks = runCatching { TaskService.repo.listByWorkflowRun(wfId) }.getOrNull().orEmpty()

            val newStatus: String = when {
                // WorkflowRun 已被删除 → 孤立记录，标记失败
                wf == null -> {
                    logger.warn("reconcileSources: source=${source.id} 对应 WorkflowRun($wfId) 已被删除，标记 failed（孤立记录）")
                    "failed"
                }

                // WorkflowRun 已标记终态 → 直接采用
                wf.status == "failed" || wf.status == "cancelled" -> {
                    logger.info("reconcileSources: source=${source.id} WorkflowRun($wfId) 终态=${wf.status}，同步为 failed")
                    "failed"
                }
                wf.status == "completed" -> {
                    logger.info("reconcileSources: source=${source.id} WorkflowRun($wfId) 终态=completed，同步为 completed")
                    "completed"
                }

                // WorkflowRun 非终态：从 Task 实际状态推导，防止 WorkflowRun 未同步
                tasks.isEmpty() -> {
                    // 任务已被全部删除，工作流孤立，无法继续推进
                    logger.warn("reconcileSources: source=${source.id} WorkflowRun($wfId) 非终态但任务列表为空，标记 failed（任务已被删除）")
                    "failed"
                }
                tasks.any { it.status == "failed" || it.status == "cancelled" } -> {
                    val badIds = tasks.filter { it.status == "failed" || it.status == "cancelled" }.map { it.id }
                    logger.info("reconcileSources: source=${source.id} 存在 failed/cancelled 任务 $badIds，标记 failed")
                    "failed"
                }
                tasks.all { it.status == "completed" } -> {
                    logger.info("reconcileSources: source=${source.id} 所有 ${tasks.size} 个任务均 completed，标记 completed")
                    "completed"
                }

                // 仍有任务 running / pending / claimed → 正常推进中，不修改
                else -> {
                    val statusSummary = tasks.groupingBy { it.status }.eachCount()
                    logger.debug("reconcileSources: source=${source.id} 任务正在推进中 $statusSummary，保持 ${source.analysisStatus}")
                    continue
                }
            }

            if (newStatus != source.analysisStatus) {
                runCatching { WebenSourceService.repo.updateAnalysisStatus(source.id, newStatus) }
                    .onFailure { logger.error("reconcileSources: source=${source.id} 写入新状态 $newStatus 失败", it) }
                // WorkflowRun 状态落后时，补触发 recalculate 使其与 Task 重新同步
                if (wf != null && newStatus != wf.status) {
                    runCatching { WorkflowRunService.repo.recalculate(wfId) }
                        .onFailure { logger.error("reconcileSources: WorkflowRun($wfId) recalculate 失败", it) }
                }
                logger.info("reconcileSources: source=${source.id} 状态已修正 ${source.analysisStatus} → $newStatus")
                anyReconciled = true
            }
        }

        if (anyReconciled) {
            logger.info("reconcileSources: 对账完成，有状态变更（共 ${nonTerminal.size} 条非终态来源）")
        } else {
            logger.debug("reconcileSources: 对账完成，无状态变更（共 ${nonTerminal.size} 条非终态来源）")
        }
        return anyReconciled
    }

    /**
     * 启动级全量对账：在 APP 完全初始化（KCEF 就绪）后，一次性对账所有非终态来源，
     * 并将所有 material_id 标记为已对账，避免后续 API 轮询重复执行对账逻辑。
     *
     * 由 FredicaApiJvmService.onAppReady() 在 KCEF 初始化完成后异步调用。
     */
    internal suspend fun startupReconcileAll() {
        val sources = runCatching { WebenSourceService.repo.listAll(null) }.getOrElse {
            logger.warn("startupReconcileAll: 查询来源失败，跳过对账 ${it.message}")
            return
        }
        val hasNonTerminal = sources.any { it.analysisStatus != "completed" && it.analysisStatus != "failed" }
        if (hasNonTerminal) {
            reconcileSources(sources)
        } else {
            logger.debug("startupReconcileAll: 无非终态来源，跳过对账（共 ${sources.size} 条）")
        }
        // 将所有出现过的 material_id 标记为已对账，后续轮询直接走零开销快速路径
        val materialIds = sources.mapNotNull { it.materialId }.toSet()
        WebenSourceReconcileGuard.markReconciled(materialIds)
        logger.info("startupReconcileAll: 完成，共 ${sources.size} 条来源，${materialIds.size} 个 material 已标记")
    }
}

