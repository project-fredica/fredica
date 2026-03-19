package com.github.project_fredica.apputil

import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证系统代理地址格式化逻辑。
 *
 * readNetworkProxyUrl() 的核心是：
 *   InetSocketAddress.getHostString() + port → "http://host:port"
 *
 * 关键点：必须用 getHostString()，不能用 toString()。
 * InetSocketAddress(host, port).toString() 在未解析时返回 "host/<unresolved>:port"，
 * 而 getHostString() 始终返回原始主机名字符串，不含 <unresolved> 后缀。
 */
class NetworkProxyUrlTest {

    /** 模拟 readNetworkProxyUrl 的地址格式化逻辑（与 AppUtil.jvm.kt 保持一致）。 */
    private fun formatProxyUrl(host: String, port: Int): String {
        val addr = InetSocketAddress.createUnresolved(host, port)
        return "http://${addr.hostString}:${addr.port}"
    }

    @Test
    fun `localhost address formats correctly`() {
        assertEquals("http://127.0.0.1:7890", formatProxyUrl("127.0.0.1", 7890))
    }

    @Test
    fun `hostname formats correctly`() {
        assertEquals("http://proxy.example.com:8080", formatProxyUrl("proxy.example.com", 8080))
    }

    @Test
    fun `getHostString does not contain unresolved suffix`() {
        // 这是修复的核心：createUnresolved 的 toString() 含 <unresolved>，但 getHostString() 不含
        val addr = InetSocketAddress.createUnresolved("127.0.0.1", 7890)
        assertTrue(
            addr.toString().contains("<unresolved>"),
            "toString() 应含 <unresolved>（验证问题确实存在）"
        )
        assertEquals(
            "127.0.0.1",
            addr.hostString,
            "getHostString() 不应含 <unresolved>"
        )
    }

    @Test
    fun `port is preserved correctly`() {
        assertEquals("http://127.0.0.1:1080", formatProxyUrl("127.0.0.1", 1080))
        assertEquals("http://127.0.0.1:3128", formatProxyUrl("127.0.0.1", 3128))
    }
}
