package com.github.project_fredica.db

/**
 * 任务调度优先级常量与工具函数。
 *
 * ## 范围分区
 * | 范围   | 用途                     |
 * |--------|--------------------------|
 * | 0      | 最低优先级               |
 * | 1-10   | 重型 GPU 任务（ASR/转录）|
 * | 11-20  | 轻型 GPU 任务（转码等）  |
 *
 * 数字越大越优先；同优先级按 created_at 升序（先进先出）。
 *
 * 非 GPU 任务不调用 [com.github.project_fredica.worker.GpuResourceLock.withGpuLock]，
 * 其 priority 值仅影响 WorkerEngine 的 claimNext() 调度顺序。
 */
object TaskPriority {

    /** 最低优先级 */
    const val LOWEST = 0

    // ── 重型 GPU 任务（ASR/转录，1-10）──────────────────────────────────────

    /** TRANSCRIBE 低优先级 */
    const val TRANSCRIBE_LOW = 3

    /** TRANSCRIBE 中优先级（默认） */
    const val TRANSCRIBE_MEDIUM = 6

    /** TRANSCRIBE 高优先级 */
    const val TRANSCRIBE_HIGH = 9

    // EXTRACT_AUDIO / ASR_SPAWN_CHUNKS 继承 ASR 工作流的 priority 参数，无独立常量。

    // ── 轻型 GPU 任务（转码等，11-20）───────────────────────────────────────

    /** TRANSCODE_MP4 默认优先级 */
    const val TRANSCODE_MP4 = 14

    /** DOWNLOAD_BILIBILI_VIDEO 默认优先级（与转码同级） */
    const val DOWNLOAD_BILIBILI_VIDEO = 14

    // ── CPU / 系统任务（不占 GPU 锁，优先级仅影响调度顺序）────────────────

    /** NETWORK_TEST 中等优先级 */
    const val NETWORK_TEST = 5

    /** DOWNLOAD_TORCH 等系统后台任务 */
    const val DOWNLOAD_TORCH = 0

    // ── 测试专用 ─────────────────────────────────────────────────────────

    /** 测试中不关心优先级时使用的默认值 */
    const val DEV_TEST_DEFAULT = 0

    /**
     * 根据用户选择的 ASR 优先级档位返回对应的 priority 值。
     *
     * @param level "low" / "medium" / "high"（大小写不敏感），未知值返回 [TRANSCRIBE_MEDIUM]
     */
    fun asrPriority(level: String?): Int = when (level?.lowercase()) {
        "low" -> TRANSCRIBE_LOW
        "high" -> TRANSCRIBE_HIGH
        else -> TRANSCRIBE_MEDIUM
    }
}
