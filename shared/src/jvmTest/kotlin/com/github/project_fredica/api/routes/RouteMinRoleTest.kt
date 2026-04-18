package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.auth.AuthRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteMinRoleTest {

    // M1: 所有 commonRoutes 都有显式 minRole（编译器已保证，此为运行时双重验证）
    @Test
    fun m1_all_common_routes_have_explicit_minRole() {
        val routes = FredicaApi.Route.Companion.getCommonRoutes()
        for (route in routes) {
            assertNotNull(route.minRole, "${route.name} must have explicit minRole")
        }
    }

    // M2: 提交任务的 POST 路由必须至少 TENANT
    @Test
    fun m2_task_submission_routes_require_tenant() {
        val taskSubmissionRoutes = listOf(
            BilibiliVideoDownloadRoute,
            BilibiliVideoAiConclusionRoute,
            MaterialVideoTranscodeMp4Route,
            MaterialBilibiliDownloadTranscodeRoute,
            MaterialAsrTranscribeRoute,
            MaterialImportRoute,
            NetworkTestRoute,
        )
        for (route in taskSubmissionRoutes) {
            assertTrue(
                route.minRole.ordinal >= AuthRole.TENANT.ordinal,
                "${route.name} submits tasks and must require at least TENANT, but has ${route.minRole}"
            )
        }
    }

    // M3: requiresAuth=false 的路由应为 GUEST
    @Test
    fun m3_no_auth_routes_should_be_guest() {
        val noAuthRoutes = FredicaApi.Route.Companion.getCommonRoutes()
            .filter { !it.requiresAuth }
        for (route in noAuthRoutes) {
            assertEquals(
                AuthRole.GUEST, route.minRole,
                "${route.name} has requiresAuth=false but minRole=${route.minRole} (should be GUEST)"
            )
        }
    }

    // M4: 管理路由必须 ROOT
    @Test
    fun m4_admin_routes_require_root() {
        val adminRoutes = listOf(
            RestartTaskLogListRoute,
            RestartTaskLogUpdateDispositionRoute,
            WebserverAuthTokenGetRoute,
            WebserverAuthTokenUpdateRoute,
            UserCreateRoute,
            UserDisableRoute,
            UserEnableRoute,
            UserListRoute,
        )
        for (route in adminRoutes) {
            assertEquals(
                AuthRole.ROOT, route.minRole,
                "${route.name} is an admin route and must require ROOT, but has ${route.minRole}"
            )
        }
    }

    // M5: 写操作路由至少 TENANT
    @Test
    fun m5_write_routes_require_at_least_tenant() {
        val writeRoutes = listOf(
            MaterialDeleteRoute,
            MaterialCategorySimpleCreateRoute,
            MaterialCategorySimpleDeleteRoute,
            MaterialCategorySimpleUpdateRoute,
            MaterialCategorySimpleImportRoute,
            MaterialCategorySyncCreateRoute,
            MaterialCategorySyncDeleteRoute,
            MaterialCategorySyncUpdateRoute,
            MaterialCategorySyncSubscribeRoute,
            MaterialCategorySyncUnsubscribeRoute,
            MaterialCategorySyncTriggerRoute,
            MaterialSetCategoriesRoute,
            PromptTemplateSaveRoute,
            PromptTemplateDeleteRoute,
            PostprocessSubtitleSaveRoute,
            TaskCancelRoute,
            TaskPauseRoute,
            TaskResumeRoute,
            LlmCacheInvalidateRoute,
            LlmModelProbeRoute,
        )
        for (route in writeRoutes) {
            assertTrue(
                route.minRole.ordinal >= AuthRole.TENANT.ordinal,
                "${route.name} is a write route and must require at least TENANT, but has ${route.minRole}"
            )
        }
    }
}
