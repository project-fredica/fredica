package com.github.project_fredica.api.routes

import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.weben.WebenConceptDb
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenSourceDb
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
private data class BatchImportResponse(
    val ok: Boolean? = null,
    val error: String? = null,
    val source_id: String? = null,
    val concept_created: Int? = null,
    val concept_total: Int? = null,
)

class WebenConceptBatchImportRouteTest {

    private lateinit var db: Database
    private lateinit var conceptDb: WebenConceptDb
    private lateinit var sourceDb: WebenSourceDb

    private fun ctx() = RouteContext(identity = null, clientIp = null, userAgent = null)

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("weben_batch_import_", ".db").also { it.deleteOnExit() }
        db = Database.connect("jdbc:sqlite:${tmpFile.absolutePath}", "org.sqlite.JDBC")

        val materialDb = MaterialDb(db)
        val materialVideoDb = MaterialVideoDb(db)
        materialDb.initialize()
        materialVideoDb.initialize()
        MaterialCategoryDb(db).initialize()
        MaterialService.initialize(materialDb)
        MaterialVideoService.initialize(materialVideoDb)

        conceptDb = WebenConceptDb(db)
        sourceDb = WebenSourceDb(db)
        conceptDb.initialize()
        sourceDb.initialize()

        WebenConceptService.initialize(conceptDb)
        WebenSourceService.initialize(sourceDb)

        MaterialVideoService.repo.upsertAll(
            listOf(
                MaterialVideo(
                    id = "mat-1",
                    title = "测试素材",
                    sourceType = "bilibili",
                    sourceId = "BV1test",
                    coverUrl = "",
                    description = "",
                    duration = 120,
                    localVideoPath = "",
                    localAudioPath = "",
                    transcriptPath = "",
                    extra = "{}",
                    createdAt = System.currentTimeMillis() / 1000L,
                    updatedAt = System.currentTimeMillis() / 1000L,
                )
            )
        )
        Unit
    }

    @Test
    fun `batch import creates source concepts aliases and source excerpts`() = runBlocking {
        val result = WebenConceptBatchImportRoute.handler(
            """
                {
                  "material_id": "mat-1",
                  "concepts": [
                    {"name": "GPIO", "types": ["术语"], "description": "通用输入输出接口", "aliases": ["General Purpose IO"]},
                    {"name": "开漏输出", "types": ["术语"], "description": "一种输出模式", "aliases": []}
                  ]
                }
            """.trimIndent(),
            ctx()
        )

        val payload = result.str.loadJsonModel<BatchImportResponse>().getOrThrow()
        assertEquals(true, payload.ok)

        val sourceId = payload.source_id
        assertNotNull(sourceId)
        val source = sourceDb.getById(sourceId)
        assertNotNull(source)
        assertEquals("mat-1", source.materialId)

        val gpio = conceptDb.getByCanonicalName("GPIO", materialId = "mat-1")
        val openDrain = conceptDb.getByCanonicalName("开漏输出", materialId = "mat-1")
        assertNotNull(gpio)
        assertNotNull(openDrain)
        assertEquals("通用输入输出接口", gpio.briefDefinition)

        val aliases = conceptDb.listAliases(gpio.id)
        assertTrue(aliases.any { it.alias == "General Purpose IO" })

        val sources = conceptDb.listSources(gpio.id)
        assertTrue(sources.any { it.sourceId == sourceId && it.excerpt == "通用输入输出接口" })

        assertEquals(2, payload.concept_created)
        assertEquals(2, payload.concept_total)
    }

    @Test
    fun `reimport updates existing concept instead of duplicating`() = runBlocking {
        WebenConceptBatchImportRoute.handler(
            """
                {
                  "material_id": "mat-1",
                  "concepts": [
                    {"name": "GPIO", "types": ["术语"], "description": "旧描述", "aliases": ["GPIO口"]}
                  ]
                }
            """.trimIndent(),
            ctx()
        )

        val result = WebenConceptBatchImportRoute.handler(
            """
                {
                  "material_id": "mat-1",
                  "concepts": [
                    {"name": "GPIO", "types": ["术语"], "description": "新描述", "aliases": ["General Purpose IO"]}
                  ]
                }
            """.trimIndent(),
            ctx()
        )

        val payload = result.str.loadJsonModel<BatchImportResponse>().getOrThrow()
        assertEquals(true, payload.ok)
        assertEquals(0, payload.concept_created)
        assertEquals(1, payload.concept_total)

        val gpio = conceptDb.getByCanonicalName("GPIO", materialId = "mat-1")
        assertNotNull(gpio)
        assertEquals("新描述", gpio.briefDefinition)
        assertEquals(1, conceptDb.listAll(materialId = "mat-1").count { it.canonicalName == "GPIO" })

        val aliases = conceptDb.listAliases(gpio.id)
        assertTrue(aliases.any { it.alias == "GPIO口" })
        assertTrue(aliases.any { it.alias == "General Purpose IO" })
    }

    @Test
    fun `batch import preserves free-form concept type`() = runBlocking {
        val result = WebenConceptBatchImportRoute.handler(
            """
                {
                  "material_id": "mat-1",
                  "concepts": [
                    {"name": "链表", "types": ["数据结构"], "description": "一种线性数据结构"}
                  ]
                }
            """.trimIndent(),
            ctx()
        )

        val payload = result.str.loadJsonModel<BatchImportResponse>().getOrThrow()
        assertEquals(true, payload.ok)

        val linkedList = conceptDb.getByCanonicalName("链表", materialId = "mat-1")
        assertNotNull(linkedList)
        assertEquals("数据结构", linkedList.conceptType)
    }

    @Test
    fun `blank concept type returns error field`() = runBlocking {
        val result = WebenConceptBatchImportRoute.handler(
            """
                {
                  "material_id": "mat-1",
                  "concepts": [
                    {"name": "GPIO", "types": ["   "], "description": "通用输入输出接口"}
                  ]
                }
            """.trimIndent(),
            ctx()
        )
        val payload = result.str.loadJsonModel<BatchImportResponse>().getOrThrow()
        val error = payload.error
        assertNotNull(error)
        assertTrue(error.contains("concept types 不能全部为空"))
    }

    @Test
    fun `invalid json returns error field`() = runBlocking {
        val result = WebenConceptBatchImportRoute.handler("not-json", ctx())
        val payload = result.str.loadJsonModel<BatchImportResponse>().getOrThrow()
        val error = payload.error
        assertNotNull(error)
        assertTrue(error.isNotBlank())
    }
}
