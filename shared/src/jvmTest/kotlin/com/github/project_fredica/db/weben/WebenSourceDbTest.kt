package com.github.project_fredica.db.weben

import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import java.io.File
import java.sql.Types
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebenSourceDbTest {

    private lateinit var db: Database
    private lateinit var sourceDb: WebenSourceDb

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("webensourcedbtest_", ".db").also { it.deleteOnExit() }
        db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")
        sourceDb = WebenSourceDb(db)
        sourceDb.initialize()
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    // ── material_id 归一化 ──────────────────────────────────────────────────

    /**
     * 旧版记录写入空字符串 material_id（数据库存储遗留问题），
     * 读取时应归一化为 null，而非将空串透传给前端。
     */
    @Test
    fun `toSource normalizes empty string material_id to null`() = runBlocking {
        // 直接 INSERT 空字符串，绕过 create() 的 setStringOrNull 逻辑
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO weben_source (id, material_id, url, title, source_type, quality_score, analysis_status, progress, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "src-empty-mat")
                ps.setString(2, "")          // 空字符串，非 NULL
                ps.setString(3, "material://")
                ps.setString(4, "测试来源")
                ps.setString(5, "bilibili_video")
                ps.setDouble(6, 0.8)
                ps.setString(7, "completed")
                ps.setInt(8, 0)
                ps.setLong(9, nowSec())
                ps.executeUpdate()
            }
        }

        val source = sourceDb.getById("src-empty-mat")
        assertNotNull(source)
        assertNull(source.materialId, "空字符串 material_id 应归一化为 null，但得到: '${source.materialId}'")
    }

    @Test
    fun `toSource keeps valid material_id UUID`() = runBlocking {
        val uuid = "d1e2f3a4-b5c6-7890-abcd-ef1234567890"
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO weben_source (id, material_id, url, title, source_type, quality_score, analysis_status, progress, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "src-valid-mat")
                ps.setString(2, uuid)
                ps.setString(3, "https://www.bilibili.com/video/BV1xx")
                ps.setString(4, "测试来源 2")
                ps.setString(5, "bilibili_video")
                ps.setDouble(6, 0.8)
                ps.setString(7, "completed")
                ps.setInt(8, 0)
                ps.setLong(9, nowSec())
                ps.executeUpdate()
            }
        }

        val source = sourceDb.getById("src-valid-mat")
        assertNotNull(source)
        assertEquals(uuid, source.materialId)
    }

    @Test
    fun `toSource keeps SQL NULL material_id as null`() = runBlocking {
        db.useConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO weben_source (id, material_id, url, title, source_type, quality_score, analysis_status, progress, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "src-null-mat")
                ps.setNull(2, Types.VARCHAR)  // SQL NULL
                ps.setString(3, "https://example.com")
                ps.setString(4, "测试来源 3")
                ps.setString(5, "web_article")
                ps.setDouble(6, 0.5)
                ps.setString(7, "pending")
                ps.setInt(8, 0)
                ps.setLong(9, nowSec())
                ps.executeUpdate()
            }
        }

        val source = sourceDb.getById("src-null-mat")
        assertNotNull(source)
        assertNull(source.materialId)
    }

    // ── create / getById 基础功能 ─────────────────────────────────────────────

    @Test
    fun `create and getById round-trip`() = runBlocking {
        val source = WebenSource(
            id             = "src-rt",
            materialId     = "mat-001",
            url            = "https://www.bilibili.com/video/BV1test",
            title          = "Round Trip Test",
            sourceType     = "bilibili_video",
            analysisStatus = "pending",
            createdAt      = nowSec(),
        )
        sourceDb.create(source)

        val loaded = sourceDb.getById("src-rt")
        assertNotNull(loaded)
        assertEquals("mat-001", loaded.materialId)
        assertEquals("pending", loaded.analysisStatus)
    }

    @Test
    fun `updateAnalysisStatus changes status`() = runBlocking {
        val source = WebenSource(
            id = "src-status", materialId = null,
            url = "https://example.com", title = "Test",
            sourceType = "bilibili_video", createdAt = nowSec(),
        )
        sourceDb.create(source)
        sourceDb.updateAnalysisStatus("src-status", "completed")

        val updated = sourceDb.getById("src-status")
        assertNotNull(updated)
        assertEquals("completed", updated.analysisStatus)
    }

    @Test
    fun `getById returns null for unknown id`() = runBlocking {
        assertNull(sourceDb.getById("no-such-id"))
        Unit
    }
}
