package com.github.project_fredica.material_category

import com.github.project_fredica.api.routes.*
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthIdentity
import com.github.project_fredica.material_category.db.*
import com.github.project_fredica.material_category.model.*
import com.github.project_fredica.material_category.route_ext.*
import com.github.project_fredica.material_category.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialCategoryRouteTest {
    private lateinit var db: Database
    private lateinit var categoryDb: MaterialCategoryDb
    private lateinit var platformInfoDb: MaterialCategorySyncPlatformInfoDb
    private lateinit var userConfigDb: MaterialCategorySyncUserConfigDb
    private lateinit var syncItemDb: MaterialCategorySyncItemDb
    private lateinit var auditLogDb: MaterialCategoryAuditLogDb
    private lateinit var tmpFile: File

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "user-a",
        username = "alice",
        displayName = "Alice",
        permissions = "",
        sessionId = "sess-a",
    )
    private val rootUser = AuthIdentity.RootUser(
        userId = "root-1",
        username = "root",
        displayName = "Root",
        permissions = "",
        sessionId = "sess-root",
    )
    private val guestIdentity = AuthIdentity.Guest

    private fun ctx(identity: AuthIdentity?) = RouteContext(
        identity = identity,
        clientIp = "127.0.0.1",
        userAgent = "test",
    )

    @BeforeTest
    fun setup() {
        tmpFile = File.createTempFile("test_mc_route_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        categoryDb = MaterialCategoryDb(db)
        platformInfoDb = MaterialCategorySyncPlatformInfoDb(db)
        userConfigDb = MaterialCategorySyncUserConfigDb(db)
        syncItemDb = MaterialCategorySyncItemDb(db)
        auditLogDb = MaterialCategoryAuditLogDb(db)
        runBlocking { categoryDb.initialize() }
        MaterialCategoryService.initialize(categoryDb)
        MaterialCategorySyncPlatformInfoService.initialize(platformInfoDb)
        MaterialCategorySyncUserConfigService.initialize(userConfigDb)
        MaterialCategorySyncItemService.initialize(syncItemDb)
        MaterialCategoryAuditLogService.initialize(auditLogDb)
    }

    @AfterTest
    fun teardown() {
        tmpFile.delete()
    }

    private fun parseJson(result: Any): JsonObject {
        return AppUtil.GlobalVars.json.parseToJsonElement(result.toString()).jsonObject
    }

    // ── SimpleCreate ──

    @Test
    fun rt01_simple_create_success() = runBlocking {
        val param = """{"name":"学习","description":"desc"}"""
        val result = parseJson(MaterialCategorySimpleCreateRoute.handler2(param, ctx(tenantUser)))
        assertEquals("user-a", result["owner_id"]?.jsonPrimitive?.content)
        assertEquals("学习", result["name"]?.jsonPrimitive?.content)
        Unit
    }

    @Test
    fun rt02_simple_create_guest_fails() = runBlocking {
        val param = """{"name":"test"}"""
        val result = parseJson(MaterialCategorySimpleCreateRoute.handler2(param, ctx(guestIdentity)))
        assertNotNull(result["error"])
        Unit
    }

    @Test
    fun rt03_simple_create_blank_name_fails() = runBlocking {
        val param = """{"name":"  "}"""
        val result = parseJson(MaterialCategorySimpleCreateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("空"))
        Unit
    }

    @Test
    fun rt04_simple_create_long_name_fails() = runBlocking {
        val longName = "a".repeat(65)
        val param = """{"name":"$longName"}"""
        val result = parseJson(MaterialCategorySimpleCreateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("过长"))
        Unit
    }

    // ── SimpleUpdate ──

    @Test
    fun rt05_simple_update_name() = runBlocking {
        val cat = categoryDb.create("user-a", "原名", "")
        val param = """{"id":"${cat.id}","name":"新名"}"""
        val result = parseJson(MaterialCategorySimpleUpdateRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val updated = categoryDb.getById(cat.id)!!
        assertEquals("新名", updated.name)
        Unit
    }

    @Test
    fun rt06_simple_update_non_owner_fails() = runBlocking {
        val cat = categoryDb.create("user-b", "test", "")
        val param = """{"id":"${cat.id}","name":"hacked"}"""
        val result = parseJson(MaterialCategorySimpleUpdateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("权限"))
        Unit
    }

    @Test
    fun rt07_simple_update_root_can_update_others() = runBlocking {
        val cat = categoryDb.create("user-a", "test", "")
        val param = """{"id":"${cat.id}","name":"root-renamed"}"""
        val result = parseJson(MaterialCategorySimpleUpdateRoute.handler2(param, ctx(rootUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        Unit
    }

    @Test
    fun rt08_simple_update_uncategorized_name_fails() = runBlocking {
        categoryDb.ensureUncategorized("user-a")
        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val param = """{"id":"$uncatId","name":"renamed"}"""
        val result = parseJson(MaterialCategorySimpleUpdateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("默认分类"))
        Unit
    }

    @Test
    fun rt09_simple_update_sync_category_rejected() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-cat", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-1", syncType = "bilibili_favorite", platformId = "12345",
                platformConfig = """{"type":"bilibili_favorite","media_id":12345}""",
                displayName = "test", categoryId = cat.id,
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"id":"${cat.id}","name":"new-name"}"""
        val result = parseJson(MaterialCategorySimpleUpdateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("同步分类"))
        Unit
    }

    // ── SimpleDelete ──

    @Test
    fun rt10_simple_delete_success() = runBlocking {
        val cat = categoryDb.create("user-a", "to-delete", "")
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        assertNull(categoryDb.getById(cat.id))
        Unit
    }

    @Test
    fun rt11_simple_delete_uncategorized_fails() = runBlocking {
        categoryDb.ensureUncategorized("user-a")
        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val param = """{"id":"$uncatId"}"""
        val result = parseJson(MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("默认分类"))
        Unit
    }

    @Test
    fun rt12_simple_delete_non_owner_fails() = runBlocking {
        val cat = categoryDb.create("user-b", "test", "")
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("权限"))
        assertNotNull(categoryDb.getById(cat.id))
        Unit
    }

    @Test
    fun rt13_simple_delete_orphans_moved_to_uncategorized() = runBlocking {
        val cat = categoryDb.create("user-a", "to-delete", "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat.id), addedBy = "user")
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val uncatId = MaterialCategoryDefaults.uncategorizedId("user-a")
        val uncatList = categoryDb.listMine("user-a")
        val uncat = uncatList.find { it.id == uncatId }
        assertNotNull(uncat)
        assertTrue(uncat.materialCount >= 1)
        Unit
    }

    @Test
    fun rt14_simple_delete_sync_category_rejected() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-cat", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-del", syncType = "bilibili_favorite", platformId = "99999",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("同步分类"))
        Unit
    }

    // ── SimpleImport ──

    @Test
    fun rt15_simple_import_success() = runBlocking {
        val cat = categoryDb.create("user-a", "import-target", "")
        val param = """{"material_ids":["mat-1","mat-2"],"category_ids":["${cat.id}"]}"""
        val result = parseJson(MaterialCategorySimpleImportRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(2, result["material_count"]?.jsonPrimitive?.int)
        Unit
    }

    @Test
    fun rt16_simple_import_empty_materials_fails() = runBlocking {
        val cat = categoryDb.create("user-a", "test", "")
        val param = """{"material_ids":[],"category_ids":["${cat.id}"]}"""
        val result = parseJson(MaterialCategorySimpleImportRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        Unit
    }

    @Test
    fun rt17_simple_import_no_permission_fails() = runBlocking {
        val cat = categoryDb.create("user-b", "private-cat", "")
        val param = """{"material_ids":["mat-1"],"category_ids":["${cat.id}"]}"""
        val result = parseJson(MaterialCategorySimpleImportRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("无权"))
        Unit
    }

    @Test
    fun rt18_simple_import_with_allow_others_add() = runBlocking {
        val cat = categoryDb.create("user-b", "shared-cat", "")
        categoryDb.update(cat.id, "user-b", allowOthersView = true, allowOthersAdd = true)
        val param = """{"material_ids":["mat-1"],"category_ids":["${cat.id}"]}"""
        val result = parseJson(MaterialCategorySimpleImportRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        Unit
    }

    @Test
    fun rt19_simple_import_replace_existing() = runBlocking {
        val cat1 = categoryDb.create("user-a", "cat1", "")
        val cat2 = categoryDb.create("user-a", "cat2", "")
        categoryDb.linkMaterials(listOf("mat-1"), listOf(cat1.id), addedBy = "user")
        val param = """{"material_ids":["mat-1"],"category_ids":["${cat2.id}"],"replace_existing":true}"""
        val result = parseJson(MaterialCategorySimpleImportRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        Unit
    }

    // ── List (filtered) ──

    @Test
    fun rt20_list_default_returns_own_and_public() = runBlocking {
        categoryDb.create("user-a", "my-cat", "")
        val bPub = categoryDb.create("user-b", "b-public", "")
        categoryDb.update(bPub.id, "user-b", allowOthersView = true)
        categoryDb.create("user-b", "b-private", "")

        val param = """{}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        val names = items.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("my-cat" in names)
        assertTrue("b-public" in names)
        assertFalse("b-private" in names)
        Unit
    }

    @Test
    fun rt21_list_filter_mine() = runBlocking {
        categoryDb.create("user-a", "my-cat", "")
        val bPub = categoryDb.create("user-b", "b-public", "")
        categoryDb.update(bPub.id, "user-b", allowOthersView = true)

        val param = """{"filter":"MINE"}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        val names = items.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("my-cat" in names)
        assertFalse("b-public" in names)
        Unit
    }

    @Test
    fun rt22_list_filter_public() = runBlocking {
        categoryDb.create("user-a", "my-private", "")
        val bPub = categoryDb.create("user-b", "b-public", "")
        categoryDb.update(bPub.id, "user-b", allowOthersView = true)

        val param = """{"filter":"PUBLIC"}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        val names = items.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertFalse("my-private" in names)
        assertTrue("b-public" in names)
        Unit
    }

    @Test
    fun rt23_list_search() = runBlocking {
        categoryDb.create("user-a", "Kotlin学习", "")
        categoryDb.create("user-a", "Java学习", "")
        categoryDb.create("user-a", "其他", "")

        val param = """{"search":"学习"}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        assertEquals(2, items.size)
        Unit
    }

    @Test
    fun rt24_list_pagination() = runBlocking {
        for (i in 1..5) categoryDb.create("user-a", "cat-$i", "")

        val param = """{"offset":0,"limit":2}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(5, result["total"]!!.jsonPrimitive.int)
        assertEquals(0, result["offset"]!!.jsonPrimitive.int)
        assertEquals(2, result["limit"]!!.jsonPrimitive.int)
        Unit
    }

    @Test
    fun rt25_list_guest_only_sees_public() = runBlocking {
        categoryDb.create("user-a", "private-cat", "")
        val pub = categoryDb.create("user-a", "public-cat", "")
        categoryDb.update(pub.id, "user-a", allowOthersView = true)

        val param = """{}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(guestIdentity)))
        val items = result["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("public-cat", items[0].jsonObject["name"]!!.jsonPrimitive.content)
        Unit
    }

    @Test
    fun rt26_list_enriches_sync_info() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-cat", "")
        val nowSec = System.currentTimeMillis() / 1000L
        val configJson = """{"type":"bilibili_favorite","media_id":12345}"""
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-enrich", syncType = "bilibili_favorite", platformId = "12345",
                platformConfig = configJson, displayName = "我的收藏夹",
                categoryId = cat.id, itemCount = 42,
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-enrich", platformInfoId = "pi-enrich", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )

        val param = """{}"""
        val result = parseJson(MaterialCategoryListRoute.handler2(param, ctx(tenantUser)))
        val items = result["items"]!!.jsonArray
        val syncCat = items.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content == "sync-cat"
        }?.jsonObject
        assertNotNull(syncCat)
        val sync = syncCat["sync"]?.jsonObject
        assertNotNull(sync)
        assertEquals("pi-enrich", sync["id"]?.jsonPrimitive?.content)
        assertEquals("bilibili_favorite", sync["sync_type"]?.jsonPrimitive?.content)
        assertEquals(42, sync["item_count"]?.jsonPrimitive?.int)
        assertEquals(1, sync["subscriber_count"]?.jsonPrimitive?.int)
        assertNotNull(sync["my_subscription"])
        Unit
    }

    // ── SyncCreate ──

    @Test
    fun rt27_sync_create_success() = runBlocking {
        val param = """{"sync_type":"bilibili_favorite","platform_config":{"media_id":12345},"display_name":"我的收藏"}"""
        val result = parseJson(MaterialCategorySyncCreateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["platform_info"])
        assertNotNull(result["user_config"])
        assertTrue(result["is_new_platform"]?.jsonPrimitive?.boolean == true)
        Unit
    }

    @Test
    fun rt28_sync_create_duplicate_subscription_fails() = runBlocking {
        val param = """{"sync_type":"bilibili_uploader","platform_config":{"mid":67890}}"""
        MaterialCategorySyncCreateRoute.handler2(param, ctx(tenantUser))
        val result2 = parseJson(MaterialCategorySyncCreateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result2["error"])
        assertTrue(result2["error"]!!.jsonPrimitive.content.contains("已订阅"))
        Unit
    }

    @Test
    fun rt29_sync_create_invalid_config_fails() = runBlocking {
        val param = """{"sync_type":"unknown_type","platform_config":{"foo":"bar"}}"""
        val result = parseJson(MaterialCategorySyncCreateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        Unit
    }

    @Test
    fun rt30_sync_create_guest_fails() = runBlocking {
        val param = """{"sync_type":"bilibili_favorite","platform_config":{"media_id":12345}}"""
        val result = parseJson(MaterialCategorySyncCreateRoute.handler2(param, ctx(guestIdentity)))
        assertNotNull(result["error"])
        Unit
    }

    // ── SyncSubscribe ──

    @Test
    fun rt31_sync_subscribe_existing_platform() = runBlocking {
        val cat = categoryDb.create("user-b", "sync-cat", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-sub", syncType = "bilibili_favorite", platformId = "55555",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"platform_info_id":"pi-sub"}"""
        val result = parseJson(MaterialCategorySyncSubscribeRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["user_config"])
        assertNotNull(result["platform_info"])
        Unit
    }

    @Test
    fun rt32_sync_subscribe_duplicate_fails() = runBlocking {
        val cat = categoryDb.create("user-b", "sync-cat2", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-sub2", syncType = "bilibili_favorite", platformId = "66666",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-dup", platformInfoId = "pi-sub2", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"platform_info_id":"pi-sub2"}"""
        val result = parseJson(MaterialCategorySyncSubscribeRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("已订阅"))
        Unit
    }

    @Test
    fun rt33_sync_subscribe_nonexistent_platform_fails() = runBlocking {
        val param = """{"platform_info_id":"nonexistent"}"""
        val result = parseJson(MaterialCategorySyncSubscribeRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("不存在"))
        Unit
    }

    // ── SyncUnsubscribe ──

    @Test
    fun rt34_sync_unsubscribe_success() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-unsub", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-unsub", syncType = "bilibili_favorite", platformId = "77777",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-unsub", platformInfoId = "pi-unsub", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"user_config_id":"uc-unsub"}"""
        val result = parseJson(MaterialCategorySyncUnsubscribeRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        assertNull(userConfigDb.getById("uc-unsub"))
        Unit
    }

    @Test
    fun rt35_sync_unsubscribe_non_owner_fails() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000L
        val cat = categoryDb.create("user-b", "sync-unsub2", "")
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-unsub2", syncType = "bilibili_favorite", platformId = "88888",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-unsub2", platformInfoId = "pi-unsub2", userId = "user-b",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"user_config_id":"uc-unsub2"}"""
        val result = parseJson(MaterialCategorySyncUnsubscribeRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("权限"))
        Unit
    }

    // ── SyncUpdate ──

    @Test
    fun rt36_sync_update_name() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-update", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-upd", syncType = "bilibili_favorite", platformId = "11111",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"id":"${cat.id}","name":"新同步名"}"""
        val result = parseJson(MaterialCategorySyncUpdateRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val updated = categoryDb.getById(cat.id)!!
        assertEquals("新同步名", updated.name)
        Unit
    }

    @Test
    fun rt37_sync_update_non_sync_category_fails() = runBlocking {
        val cat = categoryDb.create("user-a", "normal-cat", "")
        val param = """{"id":"${cat.id}","name":"new-name"}"""
        val result = parseJson(MaterialCategorySyncUpdateRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("不是同步分类"))
        Unit
    }

    // ── SyncDelete ──

    @Test
    fun rt38_sync_delete_success() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-del", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-del2", syncType = "bilibili_favorite", platformId = "22222",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-del", platformInfoId = "pi-del2", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySyncDeleteRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        assertNull(categoryDb.getById(cat.id))
        assertNull(platformInfoDb.getById("pi-del2"))
        assertNull(userConfigDb.getById("uc-del"))
        Unit
    }

    @Test
    fun rt39_sync_delete_non_sync_fails() = runBlocking {
        val cat = categoryDb.create("user-a", "normal-del", "")
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySyncDeleteRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("不是同步分类"))
        Unit
    }

    @Test
    fun rt40_sync_delete_while_syncing_fails() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-busy", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-busy", syncType = "bilibili_favorite", platformId = "33333",
                categoryId = cat.id, syncState = "syncing",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"id":"${cat.id}"}"""
        val result = parseJson(MaterialCategorySyncDeleteRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("正在运行"))
        Unit
    }

    // ── SyncTrigger ──

    @Test
    fun rt41_sync_trigger_success() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-trigger", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-trig", syncType = "bilibili_favorite", platformId = "44444",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-trig", platformInfoId = "pi-trig", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"platform_info_id":"pi-trig"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("pending", result["sync_state"]?.jsonPrimitive?.content)
        val updated = platformInfoDb.getById("pi-trig")!!
        assertEquals("pending", updated.syncState)
        Unit
    }

    @Test
    fun rt42_sync_trigger_not_subscribed_fails() = runBlocking {
        val cat = categoryDb.create("user-b", "sync-trig2", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-trig2", syncType = "bilibili_favorite", platformId = "55556",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"platform_info_id":"pi-trig2"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("未订阅"))
        Unit
    }

    @Test
    fun rt43_sync_trigger_already_syncing_fails() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-trig3", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-trig3", syncType = "bilibili_favorite", platformId = "55557",
                categoryId = cat.id, syncState = "syncing",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-trig3", platformInfoId = "pi-trig3", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{"platform_info_id":"pi-trig3"}"""
        val result = parseJson(MaterialCategorySyncTriggerRoute.handler2(param, ctx(tenantUser)))
        assertNotNull(result["error"])
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("正在运行"))
        Unit
    }

    // ── SyncList ──

    @Test
    fun rt44_sync_list_returns_user_subscriptions() = runBlocking {
        val cat = categoryDb.create("user-a", "sync-list", "")
        val nowSec = System.currentTimeMillis() / 1000L
        platformInfoDb.create(
            MaterialCategorySyncPlatformInfo(
                id = "pi-list", syncType = "bilibili_favorite", platformId = "99990",
                categoryId = cat.id, createdAt = nowSec, updatedAt = nowSec,
            )
        )
        userConfigDb.create(
            MaterialCategorySyncUserConfig(
                id = "uc-list", platformInfoId = "pi-list", userId = "user-a",
                createdAt = nowSec, updatedAt = nowSec,
            )
        )
        val param = """{}"""
        val resultStr = MaterialCategorySyncListRoute.handler2(param, ctx(tenantUser))
        val arr = AppUtil.GlobalVars.json.parseToJsonElement(resultStr.toString()).jsonArray
        assertEquals(1, arr.size)
        val item = arr[0].jsonObject
        assertNotNull(item["platform_info"])
        assertNotNull(item["user_config"])
        assertNotNull(item["subscriber_count"])
        Unit
    }

    @Test
    fun rt45_sync_list_guest_fails() = runBlocking {
        val param = """{}"""
        val result = parseJson(MaterialCategorySyncListRoute.handler2(param, ctx(guestIdentity)))
        assertNotNull(result["error"])
        Unit
    }

    // ── Audit log verification ──

    @Test
    fun rt46_simple_delete_writes_audit_log() = runBlocking {
        val cat = categoryDb.create("user-a", "audit-del", "")
        val param = """{"id":"${cat.id}"}"""
        MaterialCategorySimpleDeleteRoute.handler2(param, ctx(tenantUser))
        val logs = auditLogDb.listByCategoryId(cat.id, 10)
        assertTrue(logs.isNotEmpty())
        assertEquals("simple_delete", logs[0].action)
        assertEquals("user-a", logs[0].userId)
        Unit
    }

    @Test
    fun rt47_simple_update_writes_audit_log() = runBlocking {
        val cat = categoryDb.create("user-a", "audit-upd", "")
        val param = """{"id":"${cat.id}","name":"renamed"}"""
        MaterialCategorySimpleUpdateRoute.handler2(param, ctx(tenantUser))
        val logs = auditLogDb.listByCategoryId(cat.id, 10)
        assertTrue(logs.isNotEmpty())
        assertEquals("simple_update", logs[0].action)
        Unit
    }

    @Test
    fun rt48_sync_create_writes_audit_log() = runBlocking {
        val param = """{"sync_type":"bilibili_video_pages","platform_config":{"bvid":"BV1test123"}}"""
        val result = parseJson(MaterialCategorySyncCreateRoute.handler2(param, ctx(tenantUser)))
        val piObj = result["platform_info"]!!.jsonObject
        val categoryId = piObj["category_id"]!!.jsonPrimitive.content
        val logs = auditLogDb.listByCategoryId(categoryId, 10)
        assertTrue(logs.isNotEmpty())
        assertEquals("sync_subscribe", logs[0].action)
        Unit
    }
}
