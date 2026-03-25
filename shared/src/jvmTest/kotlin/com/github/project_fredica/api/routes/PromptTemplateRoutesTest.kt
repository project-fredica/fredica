package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateRoutesTest —— CRUD route 集成测试
// =============================================================================
//
// 使用真实 SQLite 临时文件 + PromptTemplateService 初始化，直接调用 handler()。
// 每个测试方法独立初始化，互不干扰。
// =============================================================================

import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PromptTemplate
import com.github.project_fredica.db.PromptTemplateDb
import com.github.project_fredica.db.PromptTemplateService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateRoutesTest {

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("prompt_routes_test_", ".db")
            .also { it.deleteOnExit() }
        val database = Database.connect(
            url = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        val db = PromptTemplateDb(database)
        db.initialize()
        PromptTemplateService.initialize(db)
    }

    // ── 辅助序列化结构 ────────────────────────────────────────────────────────

    @Serializable
    private data class OkResponse(val ok: Boolean? = null, val error: String? = null)

    @Serializable
    private data class DeleteResponse(
        val ok: Boolean? = null,
        val deleted_id: String? = null,
        val error: String? = null,
    )

    /** 构造 GET 路由参数：`{"key":["value"]}` 格式（Map<String, List<String>>） */
    private fun getParam(key: String, value: String) =
        buildValidJson { kv(key, JsonArray(listOf(JsonPrimitive(value)))) }

    // ── PromptTemplateListRoute ───────────────────────────────────────────────

    @Test
    fun `list returns built-in system templates`() = runBlocking {
        val result = PromptTemplateListRoute.handler("{}")
        val items = result.str.loadJsonModel<List<PromptTemplateListItem>>().getOrThrow()

        // 至少有一条系统模板
        assertTrue(items.isNotEmpty(), "应包含至少一条系统模板")
        assertTrue(items.any { it.sourceType == "system" }, "应包含 system 模板")
        // 列表项不包含 script_code（PromptTemplateListItem 无此字段）
        Unit
    }

    @Test
    fun `list returns user templates alongside system templates`() = runBlocking {
        // 先保存一个用户模板
        val saveParam = buildValidJson {
            kv("id", "route-test-tpl-1")
            kv("name", "测试模板1")
            kv("script_code", "async function main() { return 'x' }")
            kv("category", "weben_extract")
        }
        PromptTemplateSaveRoute.handler(saveParam.str)

        val result = PromptTemplateListRoute.handler("{}")
        val items = result.str.loadJsonModel<List<PromptTemplateListItem>>().getOrThrow()

        assertTrue(items.any { it.id == "route-test-tpl-1" }, "应包含刚保存的用户模板")
        assertTrue(items.any { it.sourceType == "system" }, "应同时包含系统模板")
        Unit
    }

    @Test
    fun `list with category filter returns matching templates only`() = runBlocking {
        val saveParam = buildValidJson {
            kv("id", "route-test-tpl-cat")
            kv("name", "分类测试")
            kv("script_code", "async function main() { return 'y' }")
            kv("category", "other_cat")
        }
        PromptTemplateSaveRoute.handler(saveParam.str)

        val filteredParam = getParam("category", "other_cat")
        val result = PromptTemplateListRoute.handler(filteredParam.str)
        val items = result.str.loadJsonModel<List<PromptTemplateListItem>>().getOrThrow()

        assertTrue(items.all { it.category == "other_cat" }, "过滤后只应返回指定 category")
        assertTrue(items.any { it.id == "route-test-tpl-cat" })
        Unit
    }

    // ── PromptTemplateGetRoute ────────────────────────────────────────────────

    @Test
    fun `get returns full template including script_code`() = runBlocking {
        val saveParam = buildValidJson {
            kv("id", "route-get-tpl")
            kv("name", "获取测试")
            kv("script_code", "async function main() { return 'full' }")
            kv("schema_target", "weben_v1")
        }
        PromptTemplateSaveRoute.handler(saveParam.str)

        val result = PromptTemplateGetRoute.handler(
            getParam("id", "route-get-tpl").str
        )
        val tpl = result.str.loadJsonModel<PromptTemplate>().getOrThrow()

        assertEquals("route-get-tpl", tpl.id)
        assertEquals("获取测试", tpl.name)
        assertEquals("async function main() { return 'full' }", tpl.scriptCode)
        assertEquals("weben_v1", tpl.schemaTarget)
        Unit
    }

    @Test
    fun `get returns system template from built-in list`() = runBlocking {
        val sysId = BUILT_IN_PROMPT_TEMPLATES.first().id
        val result = PromptTemplateGetRoute.handler(
            getParam("id", sysId).str
        )
        val tpl = result.str.loadJsonModel<PromptTemplate>().getOrThrow()

        assertEquals(sysId, tpl.id)
        assertEquals("system", tpl.sourceType)
        Unit
    }

    @Test
    fun `get returns error for nonexistent template`() = runBlocking {
        val result = PromptTemplateGetRoute.handler(
            getParam("id", "no-such-tpl").str
        )
        @Serializable data class ErrorResp(val error: String? = null)
        val resp = result.str.loadJsonModel<ErrorResp>().getOrThrow()
        assertNotNull(resp.error)
        assertTrue(resp.error.isNotBlank())
        Unit
    }

    // ── PromptTemplateSaveRoute ───────────────────────────────────────────────

    @Test
    fun `save creates new user template and returns it`() = runBlocking {
        val param = buildValidJson {
            kv("id", "route-save-new")
            kv("name", "新模板")
            kv("description", "描述文字")
            kv("category", "weben_extract")
            kv("script_code", "async function main() { return 'saved' }")
            kv("schema_target", "weben_v1")
        }
        val result = PromptTemplateSaveRoute.handler(param.str)
        val tpl = result.str.loadJsonModel<PromptTemplate>().getOrThrow()

        assertEquals("route-save-new", tpl.id)
        assertEquals("新模板", tpl.name)
        assertEquals("user", tpl.sourceType)
        assertTrue(tpl.updatedAt > 0L)
        Unit
    }

    @Test
    fun `save rejects id starting with sys_`() = runBlocking {
        val param = buildValidJson {
            kv("id", "sys_forbidden")
            kv("name", "禁止写入")
            kv("script_code", "async function main() { return '' }")
        }
        val result = PromptTemplateSaveRoute.handler(param.str)
        @Serializable data class ErrorResp(val error: String? = null)
        val resp = result.str.loadJsonModel<ErrorResp>().getOrThrow()
        assertNotNull(resp.error)
        assertTrue(resp.error.contains("sys_"))

        // 验证 DB 中没有写入
        assertNull(PromptTemplateService.repo.getById("sys_forbidden"))
        Unit
    }

    @Test
    fun `save rejects blank name`() = runBlocking {
        val param = buildValidJson {
            kv("id", "route-blank-name")
            kv("name", "   ")
            kv("script_code", "async function main() { return '' }")
        }
        val result = PromptTemplateSaveRoute.handler(param.str)
        @Serializable data class ErrorResp(val error: String? = null)
        val resp = result.str.loadJsonModel<ErrorResp>().getOrThrow()
        assertNotNull(resp.error)
        Unit
    }

    @Test
    fun `save update preserves original created_at`() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val param = buildValidJson {
            kv("id", "route-preserve-ts")
            kv("name", "时间戳测试")
            kv("script_code", "async function main() { return 'v1' }")
        }
        PromptTemplateSaveRoute.handler(param.str)

        val firstSaved = PromptTemplateService.repo.getById("route-preserve-ts")
        assertNotNull(firstSaved)
        val originalCreatedAt = firstSaved.createdAt

        // 稍后更新
        val updateParam = buildValidJson {
            kv("id", "route-preserve-ts")
            kv("name", "时间戳测试更新")
            kv("script_code", "async function main() { return 'v2' }")
        }
        PromptTemplateSaveRoute.handler(updateParam.str)

        val updated = PromptTemplateService.repo.getById("route-preserve-ts")
        assertNotNull(updated)
        assertEquals(originalCreatedAt, updated.createdAt, "更新时不应改变 created_at")
        assertEquals("时间戳测试更新", updated.name)
        Unit
    }

    // ── PromptTemplateDeleteRoute ─────────────────────────────────────────────

    @Test
    fun `delete removes user template and returns ok`() = runBlocking {
        val saveParam = buildValidJson {
            kv("id", "route-del-tpl")
            kv("name", "待删除")
            kv("script_code", "async function main() { return '' }")
        }
        PromptTemplateSaveRoute.handler(saveParam.str)

        val result = PromptTemplateDeleteRoute.handler(
            buildValidJson { kv("id", "route-del-tpl") }.str
        )
        val resp = result.str.loadJsonModel<DeleteResponse>().getOrThrow()
        assertTrue(resp.ok == true)
        assertEquals("route-del-tpl", resp.deleted_id)

        assertNull(PromptTemplateService.repo.getById("route-del-tpl"))
        Unit
    }

    @Test
    fun `delete returns error for system template id`() = runBlocking {
        val result = PromptTemplateDeleteRoute.handler(
            buildValidJson { kv("id", "sys_weben_extract_v1") }.str
        )
        val resp = result.str.loadJsonModel<DeleteResponse>().getOrThrow()
        assertFalse(resp.ok == true)
        assertNotNull(resp.error)
        Unit
    }

    @Test
    fun `delete returns error for nonexistent template`() = runBlocking {
        val result = PromptTemplateDeleteRoute.handler(
            buildValidJson { kv("id", "no-such-template") }.str
        )
        val resp = result.str.loadJsonModel<DeleteResponse>().getOrThrow()
        assertFalse(resp.ok == true)
        assertNotNull(resp.error)
        Unit
    }
}
