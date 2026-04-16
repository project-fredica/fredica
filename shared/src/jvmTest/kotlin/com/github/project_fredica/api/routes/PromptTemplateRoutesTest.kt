package com.github.project_fredica.api.routes

// =============================================================================
// PromptTemplateRoutesTest —— CRUD route 集成测试
// =============================================================================
//
// 使用真实 SQLite 临时文件 + PromptTemplateService 初始化，直接调用 handler()。
// 每个测试方法独立初始化，互不干扰。
// =============================================================================

import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.PromptTemplate
import com.github.project_fredica.db.PromptTemplateDb
import com.github.project_fredica.db.PromptTemplateService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private val ctx = RouteContext(identity = null, clientIp = null, userAgent = null)

    /** 构造 GET 路由参数：`{"key":["value"]}` 格式（Map<String, List<String>>） */
    private fun getParam(key: String, value: String) =
        buildJsonObject { put(key, JsonArray(listOf(JsonPrimitive(value)))) }.toString()

    // ── PromptTemplateListRoute ───────────────────────────────────────────────

    @Test
    fun `list returns built-in system templates`() = runBlocking {
        val result = PromptTemplateListRoute.handler("{}", ctx)
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
        val saveParam = buildJsonObject {
            put("id", "route-test-tpl-1")
            put("name", "测试模板1")
            put("script_code", "async function main() { return 'x' }")
            put("category", "weben_extract")
        }.toString()
        PromptTemplateSaveRoute.handler(saveParam, ctx)

        val result = PromptTemplateListRoute.handler("{}", ctx)
        val items = result.str.loadJsonModel<List<PromptTemplateListItem>>().getOrThrow()

        assertTrue(items.any { it.id == "route-test-tpl-1" }, "应包含刚保存的用户模板")
        assertTrue(items.any { it.sourceType == "system" }, "应同时包含系统模板")
        Unit
    }

    @Test
    fun `list with category filter returns matching templates only`() = runBlocking {
        val saveParam = buildJsonObject {
            put("id", "route-test-tpl-cat")
            put("name", "分类测试")
            put("script_code", "async function main() { return 'y' }")
            put("category", "other_cat")
        }.toString()
        PromptTemplateSaveRoute.handler(saveParam, ctx)

        val filteredParam = getParam("category", "other_cat")
        val result = PromptTemplateListRoute.handler(filteredParam, ctx)
        val items = result.str.loadJsonModel<List<PromptTemplateListItem>>().getOrThrow()

        assertTrue(items.all { it.category == "other_cat" }, "过滤后只应返回指定 category")
        assertTrue(items.any { it.id == "route-test-tpl-cat" })
        Unit
    }

    // ── PromptTemplateGetRoute ────────────────────────────────────────────────

    @Test
    fun `get returns full template including script_code`() = runBlocking {
        val saveParam = buildJsonObject {
            put("id", "route-get-tpl")
            put("name", "获取测试")
            put("script_code", "async function main() { return 'full' }")
            put("schema_target", "weben_v1")
        }.toString()
        PromptTemplateSaveRoute.handler(saveParam, ctx)

        val result = PromptTemplateGetRoute.handler(getParam("id", "route-get-tpl"), ctx)
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
        val result = PromptTemplateGetRoute.handler(getParam("id", sysId), ctx)
        val tpl = result.str.loadJsonModel<PromptTemplate>().getOrThrow()

        assertEquals(sysId, tpl.id)
        assertEquals("system", tpl.sourceType)
        Unit
    }

    @Test
    fun `get returns error for nonexistent template`() = runBlocking {
        val result = PromptTemplateGetRoute.handler(getParam("id", "no-such-tpl"), ctx)
        @Serializable data class ErrorResp(val error: String? = null)
        val resp = result.str.loadJsonModel<ErrorResp>().getOrThrow()
        assertNotNull(resp.error)
        assertTrue(resp.error.isNotBlank())
        Unit
    }

    // ── PromptTemplateSaveRoute ───────────────────────────────────────────────

    @Test
    fun `save creates new user template and returns it`() = runBlocking {
        val param = buildJsonObject {
            put("id", "route-save-new")
            put("name", "新模板")
            put("description", "描述文字")
            put("category", "weben_extract")
            put("script_code", "async function main() { return 'saved' }")
            put("schema_target", "weben_v1")
        }.toString()
        val result = PromptTemplateSaveRoute.handler(param, ctx)
        val tpl = result.str.loadJsonModel<PromptTemplate>().getOrThrow()

        assertEquals("route-save-new", tpl.id)
        assertEquals("新模板", tpl.name)
        assertEquals("user", tpl.sourceType)
        assertTrue(tpl.updatedAt > 0L)
        Unit
    }

    @Test
    fun `save rejects id starting with sys_`() = runBlocking {
        val param = buildJsonObject {
            put("id", "sys_forbidden")
            put("name", "禁止写入")
            put("script_code", "async function main() { return '' }")
        }.toString()
        val result = PromptTemplateSaveRoute.handler(param, ctx)
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
        val param = buildJsonObject {
            put("id", "route-blank-name")
            put("name", "   ")
            put("script_code", "async function main() { return '' }")
        }.toString()
        val result = PromptTemplateSaveRoute.handler(param, ctx)
        @Serializable data class ErrorResp(val error: String? = null)
        val resp = result.str.loadJsonModel<ErrorResp>().getOrThrow()
        assertNotNull(resp.error)
        Unit
    }

    @Test
    fun `save update preserves original created_at`() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val param = buildJsonObject {
            put("id", "route-preserve-ts")
            put("name", "时间戳测试")
            put("script_code", "async function main() { return 'v1' }")
        }.toString()
        PromptTemplateSaveRoute.handler(param, ctx)

        val firstSaved = PromptTemplateService.repo.getById("route-preserve-ts")
        assertNotNull(firstSaved)
        val originalCreatedAt = firstSaved.createdAt

        // 稍后更新
        val updateParam = buildJsonObject {
            put("id", "route-preserve-ts")
            put("name", "时间戳测试更新")
            put("script_code", "async function main() { return 'v2' }")
        }.toString()
        PromptTemplateSaveRoute.handler(updateParam, ctx)

        val updated = PromptTemplateService.repo.getById("route-preserve-ts")
        assertNotNull(updated)
        assertEquals(originalCreatedAt, updated.createdAt, "更新时不应改变 created_at")
        assertEquals("时间戳测试更新", updated.name)
        Unit
    }

    // ── PromptTemplateDeleteRoute ─────────────────────────────────────────────

    @Test
    fun `delete removes user template and returns ok`() = runBlocking {
        val saveParam = buildJsonObject {
            put("id", "route-del-tpl")
            put("name", "待删除")
            put("script_code", "async function main() { return '' }")
        }.toString()
        PromptTemplateSaveRoute.handler(saveParam, ctx)

        val result = PromptTemplateDeleteRoute.handler(
            buildJsonObject { put("id", "route-del-tpl") }.toString(),
            ctx,
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
            buildJsonObject { put("id", "sys_weben_extract_v1") }.toString(),
            ctx,
        )
        val resp = result.str.loadJsonModel<DeleteResponse>().getOrThrow()
        assertFalse(resp.ok == true)
        assertNotNull(resp.error)
        Unit
    }

    @Test
    fun `delete returns error for nonexistent template`() = runBlocking {
        val result = PromptTemplateDeleteRoute.handler(
            buildJsonObject { put("id", "no-such-template") }.toString(),
            ctx,
        )
        val resp = result.str.loadJsonModel<DeleteResponse>().getOrThrow()
        assertFalse(resp.ok == true)
        assertNotNull(resp.error)
        Unit
    }
}
