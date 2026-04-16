package com.github.project_fredica.auth

import com.github.project_fredica.api.routes.RouteContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RouteContext 属性测试：identity / requireIdentity / authenticatedUser / userId。
 */
class RouteAuthContextTest {

    private val tenantUser = AuthIdentity.TenantUser(
        userId = "u-tenant-1",
        username = "alice",
        displayName = "Alice",
        permissions = "read,write",
        sessionId = "sess-t-1",
    )

    private val rootUser = AuthIdentity.RootUser(
        userId = "u-root-1",
        username = "admin",
        displayName = "Admin",
        permissions = "*",
        sessionId = "sess-r-1",
    )

    // AC1: identity with TenantUser → 返回 TenantUser
    @Test
    fun ac1_identity_with_tenant_user() {
        val ctx = RouteContext(identity = tenantUser, clientIp = null, userAgent = null)
        val identity = ctx.identity
        assertNotNull(identity)
        assertIs<AuthIdentity.TenantUser>(identity)
        assertEquals("u-tenant-1", identity.userId)
    }

    // AC2: identity with RootUser → 返回 RootUser
    @Test
    fun ac2_identity_with_root_user() {
        val ctx = RouteContext(identity = rootUser, clientIp = null, userAgent = null)
        val identity = ctx.identity
        assertNotNull(identity)
        assertIs<AuthIdentity.RootUser>(identity)
        assertEquals("u-root-1", identity.userId)
    }

    // AC3: identity with Guest → 返回 Guest
    @Test
    fun ac3_identity_with_guest() {
        val ctx = RouteContext(identity = AuthIdentity.Guest, clientIp = null, userAgent = null)
        val identity = ctx.identity
        assertNotNull(identity)
        assertIs<AuthIdentity.Guest>(identity)
    }

    // AC4: identity null → 返回 null
    @Test
    fun ac4_identity_null() {
        val ctx = RouteContext(identity = null, clientIp = null, userAgent = null)
        assertNull(ctx.identity)
    }

    // AC5: requireIdentity with identity → 返回 identity
    @Test
    fun ac5_requireIdentity_with_identity() {
        val ctx = RouteContext(identity = tenantUser, clientIp = null, userAgent = null)
        val identity = ctx.requireIdentity()
        assertIs<AuthIdentity.TenantUser>(identity)
        assertEquals("alice", identity.username)
    }

    // AC6: requireIdentity without identity → 抛 IllegalStateException
    @Test
    fun ac6_requireIdentity_without_identity() {
        val ctx = RouteContext(identity = null, clientIp = null, userAgent = null)
        assertFailsWith<IllegalStateException> {
            ctx.requireIdentity()
        }
    }

    // AC7: authenticatedUser with TenantUser → 返回 Authenticated
    @Test
    fun ac7_authenticatedUser_with_tenant() {
        val ctx = RouteContext(identity = tenantUser, clientIp = null, userAgent = null)
        val user = ctx.authenticatedUser
        assertNotNull(user)
        assertIs<AuthIdentity.Authenticated>(user)
        assertEquals("u-tenant-1", user.userId)
    }

    // AC8: authenticatedUser with Guest → 返回 null
    @Test
    fun ac8_authenticatedUser_with_guest() {
        val ctx = RouteContext(identity = AuthIdentity.Guest, clientIp = null, userAgent = null)
        assertNull(ctx.authenticatedUser)
    }

    // AC9: userId with TenantUser → 返回 userId
    @Test
    fun ac9_userId_with_tenant() {
        val ctx = RouteContext(identity = tenantUser, clientIp = null, userAgent = null)
        assertEquals("u-tenant-1", ctx.userId)
    }

    // AC10: userId with Guest → 返回 null
    @Test
    fun ac10_userId_with_guest() {
        val ctx = RouteContext(identity = AuthIdentity.Guest, clientIp = null, userAgent = null)
        assertNull(ctx.userId)
    }

    // AC11: clientIp and userAgent are accessible
    @Test
    fun ac11_clientIp_and_userAgent() {
        val ctx = RouteContext(identity = tenantUser, clientIp = "10.0.0.1", userAgent = "TestAgent/1.0")
        assertEquals("10.0.0.1", ctx.clientIp)
        assertEquals("TestAgent/1.0", ctx.userAgent)
    }
}
