package com.github.project_fredica.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginRateLimiterTest {

    @BeforeTest
    fun setup() {
        LoginRateLimiter.resetForTest()
    }

    @AfterTest
    fun teardown() {
        LoginRateLimiter.resetForTest()
    }

    // L1: 无失败记录时允许登录
    @Test
    fun l1_check_allows_when_no_failures() {
        assertNull(LoginRateLimiter.check("192.168.1.1", "alice"))
    }

    // L2: IP 维度 — 未达上限时允许
    @Test
    fun l2_ip_below_limit_allows() {
        repeat(LoginRateLimiter.IP_MAX_FAILURES - 1) {
            LoginRateLimiter.recordFailure("10.0.0.1", "user$it")
        }
        assertNull(LoginRateLimiter.check("10.0.0.1", "newuser"))
    }

    // L3: IP 维度 — 达到上限时拒绝
    @Test
    fun l3_ip_at_limit_rejects() {
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "user$it")
        }
        val wait = LoginRateLimiter.check("10.0.0.1", "newuser")
        assertNotNull(wait)
        assertTrue(wait > 0)
        assertTrue(wait <= LoginRateLimiter.WINDOW_SECONDS.toInt())
    }

    // L4: 用户名维度 — 未达上限时允许
    @Test
    fun l4_username_below_limit_allows() {
        repeat(LoginRateLimiter.USERNAME_MAX_FAILURES - 1) {
            LoginRateLimiter.recordFailure("192.168.0.$it", "alice")
        }
        assertNull(LoginRateLimiter.check("10.0.0.99", "alice"))
    }

    // L5: 用户名维度 — 达到上限时拒绝
    @Test
    fun l5_username_at_limit_rejects() {
        repeat(LoginRateLimiter.USERNAME_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("192.168.0.$it", "alice")
        }
        val wait = LoginRateLimiter.check("10.0.0.99", "alice")
        assertNotNull(wait)
        assertTrue(wait > 0)
    }

    // L6: 不同 IP 互不影响
    @Test
    fun l6_different_ips_independent() {
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "user$it")
        }
        // 另一个 IP 不受影响
        assertNull(LoginRateLimiter.check("10.0.0.2", "newuser"))
    }

    // L7: 不同用户名互不影响
    @Test
    fun l7_different_usernames_independent() {
        repeat(LoginRateLimiter.USERNAME_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("192.168.0.$it", "alice")
        }
        // 另一个用户名不受影响
        assertNull(LoginRateLimiter.check("10.0.0.99", "bob"))
    }

    // L8: clearOnSuccess 清除失败记录
    @Test
    fun l8_clearOnSuccess_resets() {
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "alice")
        }
        // 确认被限制
        assertNotNull(LoginRateLimiter.check("10.0.0.1", "alice"))

        // 清除
        LoginRateLimiter.clearOnSuccess("10.0.0.1", "alice")

        // 应该允许
        assertNull(LoginRateLimiter.check("10.0.0.1", "alice"))
    }

    // L9: clearOnSuccess 只清除指定 IP 和用户名
    @Test
    fun l9_clearOnSuccess_scoped() {
        // 两个 IP 都达到上限
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "user$it")
        }
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.2", "other$it")
        }

        // 只清除第一个
        LoginRateLimiter.clearOnSuccess("10.0.0.1", "user0")

        // 第一个 IP 允许
        assertNull(LoginRateLimiter.check("10.0.0.1", "newuser"))
        // 第二个 IP 仍被限制
        assertNotNull(LoginRateLimiter.check("10.0.0.2", "newuser"))
    }

    // L10: 用户名维度比 IP 维度更严格（5 < 10）
    @Test
    fun l10_username_limit_stricter_than_ip() {
        // 同一 IP + 同一用户名失败 5 次
        repeat(LoginRateLimiter.USERNAME_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "alice")
        }
        // IP 维度还没到上限（5 < 10），但用户名维度已到上限
        val wait = LoginRateLimiter.check("10.0.0.99", "alice")
        assertNotNull(wait, "username limit should trigger before IP limit")
    }

    // L11: resetForTest 清除所有状态
    @Test
    fun l11_resetForTest_clears_all() {
        repeat(LoginRateLimiter.IP_MAX_FAILURES) {
            LoginRateLimiter.recordFailure("10.0.0.1", "alice")
        }
        assertNotNull(LoginRateLimiter.check("10.0.0.1", "alice"))

        LoginRateLimiter.resetForTest()

        assertNull(LoginRateLimiter.check("10.0.0.1", "alice"))
    }
}
