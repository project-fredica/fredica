package com.github.project_fredica.asr

// =============================================================================
// AsrConfigServiceTest —— AsrConfigService 单元测试
// =============================================================================
//
// 被测对象：AsrConfigService（ASR 权限与测试配置的业务逻辑层）
//
// 测试矩阵：
//   1. testGetAsrConfig_defaults              — 默认配置读取
//   2. testSaveAsrConfig_fullUpdate           — 全字段更新
//   3. testSaveAsrConfig_partialUpdate        — 部分字段更新（null 字段保持不变）
//   4. testIsModelAllowed_emptyMeansAll       — 空黑名单 → 全部允许
//   5. testIsModelAllowed_disallowedModels    — 指定黑名单 → 黑名单内禁用
//   6. testIsModelAllowed_trimWhitespace      — 模型名前后空格被 trim
//   7. testFilterDisallowedModels_emptyAll    — 空黑名单 → 返回全部
//   8. testFilterDisallowedModels_filtered    — 指定黑名单 → 排除
//   9. testIsDownloadAllowed_default          — 默认允许下载
//  10. testIsDownloadAllowed_disabled         — 禁用下载
//
// 测试环境：每个测试用例独立的 SQLite 临时文件。
// =============================================================================

import com.github.project_fredica.asr.model.AsrConfigSaveParam
import com.github.project_fredica.asr.service.AsrConfigService
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsrConfigServiceTest {

    private lateinit var db: Database

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("asr_cfg_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url    = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        val appConfigDb = AppConfigDb(db)
        appConfigDb.initialize()
        AppConfigService.initialize(appConfigDb)
    }

    // ── 测试 1：默认配置读取 ──────────────────────────────────────────────

    @Test
    fun testGetAsrConfig_defaults() = runBlocking {
        val config = AsrConfigService.getAsrConfig()
        assertEquals(true, config.allowDownload, "默认允许下载")
        assertEquals("", config.disallowedModels, "默认黑名单为空（全部允许）")
        assertEquals("", config.testAudioPath, "默认测试音频路径为空")
        assertEquals(10, config.testWaveCount, "默认测试波形数为 10")
        Unit
    }

    // ── 测试 2：全字段更新 ──────────────────────────────────────────────────

    @Test
    fun testSaveAsrConfig_fullUpdate() = runBlocking {
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(
                allowDownload = false,
                disallowedModels = "tiny,base,small",
                testAudioPath = "/tmp/test.wav",
                testWaveCount = 20,
            )
        )
        val config = AsrConfigService.getAsrConfig()
        assertEquals(false, config.allowDownload)
        assertEquals("tiny,base,small", config.disallowedModels)
        assertEquals("/tmp/test.wav", config.testAudioPath)
        assertEquals(20, config.testWaveCount)
        Unit
    }

    // ── 测试 3：部分字段更新 ────────────────────────────────────────────────

    @Test
    fun testSaveAsrConfig_partialUpdate() = runBlocking {
        // 先设置初始值
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(
                allowDownload = false,
                disallowedModels = "tiny,base",
                testAudioPath = "/audio/a.wav",
                testWaveCount = 5,
            )
        )
        // 只更新 disallowedModels 和 testWaveCount
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(
                disallowedModels = "tiny,base,medium",
                testWaveCount = 15,
            )
        )
        val config = AsrConfigService.getAsrConfig()
        assertEquals(false, config.allowDownload, "未更新的字段应保持不变")
        assertEquals("tiny,base,medium", config.disallowedModels, "disallowedModels 应被更新")
        assertEquals("/audio/a.wav", config.testAudioPath, "未更新的字段应保持不变")
        assertEquals(15, config.testWaveCount, "testWaveCount 应被更新")
        Unit
    }

    // ── 测试 4：空黑名单 → 全部允许 ──────────────────────────────────────

    @Test
    fun testIsModelAllowed_emptyMeansAll() = runBlocking {
        // 默认 disallowedModels 为空
        assertTrue(AsrConfigService.isModelAllowed("tiny"), "空黑名单应允许 tiny")
        assertTrue(AsrConfigService.isModelAllowed("large-v3"), "空黑名单应允许 large-v3")
        assertTrue(AsrConfigService.isModelAllowed("anything"), "空黑名单应允许任意模型")
        Unit
    }

    // ── 测试 5：指定黑名单 → 黑名单内禁用 ────────────────────────────────

    @Test
    fun testIsModelAllowed_disallowedModels() = runBlocking {
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(disallowedModels = "tiny,base,small")
        )
        assertFalse(AsrConfigService.isModelAllowed("tiny"), "黑名单中的 tiny 应被禁用")
        assertFalse(AsrConfigService.isModelAllowed("base"), "黑名单中的 base 应被禁用")
        assertFalse(AsrConfigService.isModelAllowed("small"), "黑名单中的 small 应被禁用")
        assertTrue(AsrConfigService.isModelAllowed("medium"), "不在黑名单中的 medium 应允许")
        assertTrue(AsrConfigService.isModelAllowed("large-v3"), "不在黑名单中的 large-v3 应允许")
        Unit
    }

    // ── 测试 6：模型名 trim ────────────────────────────────────────────────

    @Test
    fun testIsModelAllowed_trimWhitespace() = runBlocking {
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(disallowedModels = " tiny , base , small ")
        )
        assertFalse(AsrConfigService.isModelAllowed("tiny"), "黑名单中有空格应被 trim")
        assertFalse(AsrConfigService.isModelAllowed(" base "), "查询参数有空格应被 trim")
        assertTrue(AsrConfigService.isModelAllowed("medium"), "不在黑名单中应允许")
        Unit
    }

    // ── 测试 7：filterDisallowedModels 空黑名单 → 返回全部 ──────────────

    @Test
    fun testFilterDisallowedModels_emptyAll() = runBlocking {
        val all = listOf("tiny", "base", "small", "medium", "large-v3")
        val result = AsrConfigService.filterDisallowedModels(all)
        assertEquals(all, result, "空黑名单应返回全部模型")
        Unit
    }

    // ── 测试 8：filterDisallowedModels 指定黑名单 → 排除 ────────────────

    @Test
    fun testFilterDisallowedModels_filtered() = runBlocking {
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(disallowedModels = "tiny,small")
        )
        val all = listOf("tiny", "base", "small", "medium", "large-v3")
        val result = AsrConfigService.filterDisallowedModels(all)
        assertEquals(listOf("base", "medium", "large-v3"), result, "应排除黑名单中的模型")
        Unit
    }

    // ── 测试 9：默认允许下载 ────────────────────────────────────────────────

    @Test
    fun testIsDownloadAllowed_default() = runBlocking {
        assertTrue(AsrConfigService.isDownloadAllowed(), "默认应允许下载")
        Unit
    }

    // ── 测试 10：禁用下载 ──────────────────────────────────────────────────

    @Test
    fun testIsDownloadAllowed_disabled() = runBlocking {
        AsrConfigService.saveAsrConfig(
            AsrConfigSaveParam(allowDownload = false)
        )
        assertFalse(AsrConfigService.isDownloadAllowed(), "禁用后应返回 false")
        Unit
    }
}
