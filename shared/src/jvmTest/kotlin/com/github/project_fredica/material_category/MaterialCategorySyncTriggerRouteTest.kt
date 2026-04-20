package com.github.project_fredica.material_category

import com.github.project_fredica.api.routes.MaterialCategorySyncTriggerRoute
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.WorkflowRunDb
import com.github.project_fredica.db.WorkflowRunService
import com.github.project_fredica.material_category.db.MaterialCategoryAuditLogDb
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncUserConfigDb
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformInfo
import com.github.project_fredica.material_category.model.MaterialCategorySyncUserConfig
import com.github.project_fredica.material_category.route_ext.handler2
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategorySyncTriggerRouteTest {
    private lateinit var db: Database
    private lateinit var tmpFile: File
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var platformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var userConfigDb: MaterialCategorySyncUserConfigDb
    private lateinit var auditLogDb: MaterialCategoryAuditLogDb
    private lateinit var workflowRunDb: WorkflowRunDb
    private lateinit var taskDb: TaskDb

    private val now = System.currentTimeMillis() / 1000L

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "user-a",
        username = "alice",
        displayName = "Alice",
        permissions = "",
        sessionId = "sess-a",
    )

    private fun ctx(identity: AuthIdentity?) = RouteContext(
        identity = identity,
        clientIp = "127.0.0.1",
        userAgent = "test",
    )

    private fun parseJson(result: Any): JsonObject {
        return AppUtil.GlobalVars.json.parseToJsonElement(result.toString()).jsonObject
    }

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_sync_trigger_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        categoryDb = MaterialCategoryDb(db)
        platformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        userConfigDb = MaterialCategorySyncUserConfigDb(db)
        auditLogDb = MaterialCategoryAuditLogDb(db)
        workflowRunDb = WorkflowRunDb(db)
        taskDb = TaskDb(db)
        runBlocking {
            categoryDb.initialize()
            workflowRunDb.initialize()
            taskDb.initialize()
        }
        MaterialCategoryService.initialize(categoryDb)
        MaterialCategorySyncPlatformInfoService.initialize(platformInfoDb)
        MaterialCategorySyncUserConfigService.initialize(userConfigDb)
        MaterialCategoryAuditLogService.initialize(auditLogDb)
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun createCategoryAndPlatformInfo(
        userId: String = "user-a",
        platformInfoId: String = "pi-1",
        syncState: String = "idle",
    ): Pair<String, String> {
        val cat = runBlocking { categoryDb.create(userId, "sync-test", "") }
        runBlocking {
            platformInfoDb.create(
                MaterialCategorySyncPlatformInfo(
                    id = platformInfoId,
                    syncType = "bilibili_favorite",
                    platformId = "bilibili_fav_12345",
                    categoryId = cat.id,
                    syncState = syncState,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return cat.id to platformInfoId
    }

    private fun subscribeUser(
        platformInfoId: String,
        userId: String = "user-a",
        configId: String = "uc-1",
    ) = runBlocking {
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = configId,
                platformInfoId = platformInfoId,
                userId = userId,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    @Test
    fun sy10_trigger_creates_workflow_and_sets_syncing() = runBlocking {
        val (catId, piId) = createCategoryAndPlatformInfo()
        subscribeUser(piId)

        val param = buildJsonObject { put("platform_info_id", piId) }.toString()
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val workflowRunId = result["workflow_run_id"]?.jsonPrimitive?.content
        assertNotNull(workflowRunId)

        val updated = platformInfoDb.getById(piId)!!
        assertEquals("syncing", updated.syncState)

        val wfRun = WorkflowRunService.repo.getById(workflowRunId)
        assertNotNull(wfRun)
        assertEquals("sync_bilibili_favorite", wfRun.template)
        assertEquals(catId, wfRun.materialId)
        assertEquals(1, wfRun.totalTasks)

        val tasks = TaskService.repo.listByWorkflowRun(workflowRunId)
        assertEquals(1, tasks.size)
        assertEquals("SYNC_BILIBILI_FAVORITE", tasks[0].type)
        assertTrue(tasks[0].payload.contains(piId))
        Unit
    }

    @Test
    fun sy11_already_syncing_returns_error() = runBlocking {
        val (_, piId) = createCategoryAndPlatformInfo(syncState = "syncing")
        subscribeUser(piId)

        val param = buildJsonObject { put("platform_info_id", piId) }.toString()
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))

        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("正在运行"))
        Unit
    }

    @Test
    fun sy12_not_subscribed_returns_error() = runBlocking {
        createCategoryAndPlatformInfo()

        val param = """{"platform_info_id":"pi-1"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))

        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("未订阅"))
        Unit
    }

    @Test
    fun sy13_not_logged_in_returns_error() = runBlocking {
        createCategoryAndPlatformInfo()
        subscribeUser("pi-1")

        val param = """{"platform_info_id":"pi-1"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(AuthIdentity.Guest)))

        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("未登录"))
        Unit
    }

    @Test
    fun sy14_nonexistent_platform_info_returns_error() = runBlocking {
        val param = """{"platform_info_id":"nonexistent"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))

        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("不存在"))
        Unit
    }

    @Test
    fun sy15_trigger_writes_audit_log() = runBlocking {
        val (_, piId) = createCategoryAndPlatformInfo()
        subscribeUser(piId)

        val param = buildJsonObject { put("platform_info_id", piId) }.toString()
        MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser))

        val logs = auditLogDb.listByCategoryId(platformInfoDb.getById(piId)!!.categoryId)
        assertTrue(logs.isNotEmpty())
        val triggerLog = logs.find { it.action == "sync_trigger" }
        assertNotNull(triggerLog)
        assertTrue(triggerLog.detail.contains("workflow_run_id"))
        Unit
    }
}
