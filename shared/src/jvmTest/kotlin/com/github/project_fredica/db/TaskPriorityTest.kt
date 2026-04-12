package com.github.project_fredica.db

// =============================================================================
// TaskPriorityTest —— TaskPriority 常量与 asrPriority() 函数的单元测试
// =============================================================================
//
// 被测对象：TaskPriority（优先级常量 + asrPriority 映射函数）
//
// 测试矩阵：
//   1. testAsrPriorityLow           — "low" → TRANSCRIBE_LOW (3)
//   2. testAsrPriorityMedium        — "medium" → TRANSCRIBE_MEDIUM (6)
//   3. testAsrPriorityHigh          — "high" → TRANSCRIBE_HIGH (9)
//   4. testAsrPriorityCaseInsensitive — "HIGH"/"Low" 等大小写不敏感
//   5. testAsrPriorityNull          — null → 默认 TRANSCRIBE_MEDIUM (6)
//   6. testAsrPriorityUnknown       — 未知字符串 → 默认 TRANSCRIBE_MEDIUM (6)
//   7. testHeavyGpuConstantsInRange — 重型 GPU 常量在 1-10 范围内
//   8. testLightGpuConstantsInRange — 轻型 GPU 常量在 11-20 范围内
//   9. testLowestIsZero             — LOWEST = 0
// =============================================================================

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPriorityTest {

    // ── asrPriority() 映射测试 ──────────────────────────────────────────────

    @Test
    fun testAsrPriorityLow() {
        assertEquals(
            TaskPriority.TRANSCRIBE_LOW, TaskPriority.asrPriority("low"),
            "asrPriority(\"low\") 应返回 TRANSCRIBE_LOW (${TaskPriority.TRANSCRIBE_LOW})"
        )
    }

    @Test
    fun testAsrPriorityMedium() {
        assertEquals(
            TaskPriority.TRANSCRIBE_MEDIUM, TaskPriority.asrPriority("medium"),
            "asrPriority(\"medium\") 应返回 TRANSCRIBE_MEDIUM (${TaskPriority.TRANSCRIBE_MEDIUM})"
        )
    }

    @Test
    fun testAsrPriorityHigh() {
        assertEquals(
            TaskPriority.TRANSCRIBE_HIGH, TaskPriority.asrPriority("high"),
            "asrPriority(\"high\") 应返回 TRANSCRIBE_HIGH (${TaskPriority.TRANSCRIBE_HIGH})"
        )
    }

    @Test
    fun testAsrPriorityCaseInsensitive() {
        assertEquals(TaskPriority.TRANSCRIBE_HIGH, TaskPriority.asrPriority("HIGH"),
            "大写 HIGH 应映射到 TRANSCRIBE_HIGH")
        assertEquals(TaskPriority.TRANSCRIBE_LOW, TaskPriority.asrPriority("Low"),
            "首字母大写 Low 应映射到 TRANSCRIBE_LOW")
        assertEquals(TaskPriority.TRANSCRIBE_MEDIUM, TaskPriority.asrPriority("MEDIUM"),
            "全大写 MEDIUM 应映射到 TRANSCRIBE_MEDIUM")
    }

    @Test
    fun testAsrPriorityNull() {
        assertEquals(
            TaskPriority.TRANSCRIBE_MEDIUM, TaskPriority.asrPriority(null),
            "asrPriority(null) 应返回默认值 TRANSCRIBE_MEDIUM"
        )
    }

    @Test
    fun testAsrPriorityUnknown() {
        assertEquals(
            TaskPriority.TRANSCRIBE_MEDIUM, TaskPriority.asrPriority("unknown"),
            "未知字符串应返回默认值 TRANSCRIBE_MEDIUM"
        )
        assertEquals(
            TaskPriority.TRANSCRIBE_MEDIUM, TaskPriority.asrPriority(""),
            "空字符串应返回默认值 TRANSCRIBE_MEDIUM"
        )
    }

    // ── 常量范围测试 ────────────────────────────────────────────────────────

    /**
     * 重型 GPU 任务（ASR/转录）的优先级常量应在 1-10 范围内。
     */
    @Test
    fun testHeavyGpuConstantsInRange() {
        val heavyGpuConstants = listOf(
            "TRANSCRIBE_LOW" to TaskPriority.TRANSCRIBE_LOW,
            "TRANSCRIBE_MEDIUM" to TaskPriority.TRANSCRIBE_MEDIUM,
            "TRANSCRIBE_HIGH" to TaskPriority.TRANSCRIBE_HIGH,
        )
        for ((name, value) in heavyGpuConstants) {
            assertTrue(value in 1..10,
                "$name=$value 应在重型 GPU 范围 1-10 内")
        }
    }

    /**
     * 轻型 GPU 任务（转码等）的优先级常量应在 11-20 范围内。
     */
    @Test
    fun testLightGpuConstantsInRange() {
        val lightGpuConstants = listOf(
            "TRANSCODE_MP4" to TaskPriority.TRANSCODE_MP4,
            "DOWNLOAD_BILIBILI_VIDEO" to TaskPriority.DOWNLOAD_BILIBILI_VIDEO,
        )
        for ((name, value) in lightGpuConstants) {
            assertTrue(value in 11..20,
                "$name=$value 应在轻型 GPU 范围 11-20 内")
        }
    }

    @Test
    fun testLowestIsZero() {
        assertEquals(0, TaskPriority.LOWEST, "LOWEST 应为 0")
    }
}
