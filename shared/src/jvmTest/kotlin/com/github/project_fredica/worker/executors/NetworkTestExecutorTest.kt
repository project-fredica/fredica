package com.github.project_fredica.worker.executors

// =============================================================================
// NetworkTestExecutorTest —— NetworkTestExecutor 单元测试
// =============================================================================
//
// 测试矩阵（纯逻辑，不依赖网络外部服务）：
//   E1 – 连接被拒（端口未开放）→ status="error"
//   E2 – Java connectTimeout 到期（SocketTimeoutException）→ status="timeout"
//   E3 – withTimeoutOrNull 安全网超时 → status="timeout"
//   E4 – 直连成功（本地 HTTP 服务器）→ status="ok"，latencyMs >= 0
//   E5 – 代理连接被拒（假代理地址）→ status="error"
//   E6 – CancellationException 不被 testUrl 吞掉
//   E7 – buildProxy：格式正确的 URL 生成 Proxy，格式错误的返回 null（不抛异常）
//   E8 – execute() 无代理时，results 每项 proxied=null
//   E9 – execute() 结果 JSON 包含 proxy_configured / results / proxy_warning 字段
// =============================================================================

import com.github.project_fredica.db.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTestExecutorTest {

    // ── 测试辅助 ──────────────────────────────────────────────────────────────

    /** 创建一个 Task fixture，payload 可自定义。 */
    private fun makeTask(payload: String = "{}") = Task(
        id = "test-task-id",
        type = "NETWORK_TEST",
        workflowRunId = "test-wfr-id",
        materialId = "system",
        status = "running",
        priority = 5,
        payload = payload,
        createdAt = 0L,
    )

    /**
     * 绑定一个随机端口并立即关闭（不 accept）。
     * 返回那个端口号——在 ServerSocket 关闭后，该端口不再监听，连接会被 OS 拒绝。
     */
    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * 启动一个 ServerSocket，它永远不调用 accept()。
     * 在 OS backlog 范围内，TCP 握手会成功（SYN+ACK），但连接挂起，不发任何数据。
     * 用于触发 readTimeout → SocketTimeoutException。
     *
     * 调用方负责关闭返回的 ServerSocket。
     */
    private fun openHangingServer(): ServerSocket {
        val ss = ServerSocket()
        ss.bind(InetSocketAddress("127.0.0.1", 0), 1) // backlog=1，确保 TCP 握手完成
        return ss
    }

    // ── E1: 连接被拒 ──────────────────────────────────────────────────────────

    @Test
    fun `E1 - connection refused returns status=error`() = runBlocking {
        val port = findFreePort() // 端口已关闭，连接会被 OS 拒绝
        val url = "http://127.0.0.1:$port"
        val result = NetworkTestExecutor.testUrl(url, Proxy.NO_PROXY, timeoutMs = 2_000)
        assertEquals("error", result.status, "连接被拒应报 error，实际: $result")
        assertNull(result.latencyMs)
        assertNotNull(result.error)
        Unit
    }

    // ── E2: Java SocketTimeoutException → timeout ─────────────────────────────

    @Test
    fun `E2 - SocketTimeoutException produces status=timeout`() = runBlocking {
        // openHangingServer：OS 完成 TCP 握手但服务器不写数据 → readTimeout 到期
        val hangingServer = openHangingServer()
        try {
            val port = hangingServer.localPort
            val url = "http://127.0.0.1:$port"
            // 超短超时让测试快速完成
            val result = NetworkTestExecutor.testUrl(url, Proxy.NO_PROXY, timeoutMs = 200)
            assertEquals("timeout", result.status, "SocketTimeoutException 应报 timeout，实际: $result")
            assertNull(result.latencyMs)
        } finally {
            hangingServer.close()
        }
    }

    // ── E3: withTimeoutOrNull 安全网 ──────────────────────────────────────────

    @Test
    fun `E3 - withTimeoutOrNull safety net also produces status=timeout`() = runBlocking {
        // 与 E2 类似，但用极短超时让 withTimeoutOrNull(timeoutMs+500) 先于 readTimeout 触发
        // 注意：Java readTimeout = timeoutMs，withTimeoutOrNull = timeoutMs + 500ms
        // 使用极短的 timeoutMs 让两种路径都在合理时间内完成；
        // 无论哪条路径先触发，最终 status 都应该是 "timeout"
        val hangingServer = openHangingServer()
        try {
            val port = hangingServer.localPort
            val url = "http://127.0.0.1:$port"
            val result = NetworkTestExecutor.testUrl(url, Proxy.NO_PROXY, timeoutMs = 100)
            assertEquals("timeout", result.status, "安全网超时应报 timeout，实际: $result")
        } finally {
            hangingServer.close()
        }
    }

    // ── E4: 直连成功 ──────────────────────────────────────────────────────────

    @Test
    fun `E4 - successful connection returns status=ok with non-negative latency`() = runBlocking {
        // 启动一个最小 HTTP 服务器，返回 200 OK
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val serverJob = launch(Dispatchers.IO) {
            try {
                val client = serverSocket.accept()
                val out = client.getOutputStream()
                val inp = client.getInputStream()
                // 消费请求行（忽略内容）
                val buf = ByteArray(4096)
                inp.read(buf)
                // 返回最小 HTTP/1.1 200
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                client.close()
            } catch (_: Throwable) {}
        }

        try {
            val url = "http://127.0.0.1:$port"
            val result = NetworkTestExecutor.testUrl(url, Proxy.NO_PROXY, timeoutMs = 3_000)
            assertEquals("ok", result.status, "成功连接应报 ok，实际: $result")
            assertNotNull(result.latencyMs)
            assertTrue(result.latencyMs!! >= 0, "latencyMs 应 >= 0")
            assertNull(result.error)
        } finally {
            serverJob.cancelAndJoin()
            serverSocket.close()
        }
    }

    // ── E5: 代理连接被拒 ──────────────────────────────────────────────────────

    @Test
    fun `E5 - proxy connection refused returns status=error`() = runBlocking {
        val proxyPort = findFreePort()
        val badProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        val result = NetworkTestExecutor.testUrl("http://example.com", badProxy, timeoutMs = 2_000)
        assertEquals("error", result.status, "代理连接被拒应报 error，实际: $result")
    }

    // ── E6: CancellationException 透传 ────────────────────────────────────────

    @Test
    fun `E6 - CancellationException propagates from testUrl`() = runBlocking {
        // withTimeout(1ms) 让外层协程立即被取消。
        // testUrl 在 Dispatchers.IO 上执行阻塞 IO，取消信号在阻塞操作（readTimeout）完成后
        // 由 withContext 检查并抛出 CancellationException。
        // timeoutMs=300 确保 readTimeout 在 300ms 内结束测试；
        // 重点是验证 CancellationException 没有被 catch(e: Throwable) 吞掉。
        val hangingServer = openHangingServer()
        try {
            val port = hangingServer.localPort
            val url = "http://127.0.0.1:$port"
            var cancelled = false
            try {
                withTimeout(1L) {
                    // testUrl 内部阻塞 IO；readTimeout=300ms 后 withContext 检测到取消并抛出
                    NetworkTestExecutor.testUrl(url, Proxy.NO_PROXY, timeoutMs = 300)
                }
            } catch (e: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled, "CancellationException 应从 testUrl 透传出来，不应被转成 status=error")
        } finally {
            hangingServer.close()
        }
    }

    // ── E7: buildProxy URL 解析 ───────────────────────────────────────────────

    @Test
    fun `E7 - buildProxy returns Proxy for valid URL and null for malformed`() {
        // 通过反射调用 private buildProxy（Kotlin object 的私有方法）
        val buildProxy = NetworkTestExecutor::class.java
            .getDeclaredMethod("buildProxy", String::class.java)
            .also { it.isAccessible = true }

        val validProxy = buildProxy.invoke(NetworkTestExecutor, "http://127.0.0.1:7890") as? Proxy
        assertNotNull(validProxy, "格式正确的 URL 应生成 Proxy")
        assertEquals(Proxy.Type.HTTP, validProxy.type())

        val nullProxy = buildProxy.invoke(NetworkTestExecutor, "not-a-url") as? Proxy
        assertNull(nullProxy, "格式错误的 URL 应返回 null，不应抛异常")
    }

    // ── E8: execute() 无代理时 proxied=null ───────────────────────────────────

    @Test
    fun `E8 - execute with no proxy sets proxied=null for all results`() = runBlocking {
        // 用一个立即拒绝的端口（快速失败），payload 只测一个 URL
        val port = findFreePort()
        val payload = buildJsonObject {
            put("urls", buildJsonArray { add(JsonPrimitive("http://127.0.0.1:$port")) })
            put("timeout_ms", 500)
        }.toString()
        val task = makeTask(payload)

        // 确保系统无代理（测试环境可能有代理，此处通过检查结果字段间接验证）
        val result = NetworkTestExecutor.execute(task)
        assertTrue(result.isSuccess, "execute 不应抛出异常，应返回 ExecuteResult，实际 error=${result.error}")

        val output = result.result.loadJsonModel<NetworkTestExecutor.NetworkTestOutput>().getOrThrow()
        assertEquals(1, output.results.size)
        // 无代理时 proxied 必须为 null
        assertNull(output.results[0].proxied, "无代理时 proxied 应为 null")
        // proxyConfigured 与系统环境有关，不做断言
    }

    // ── E9: execute() 结果 JSON 结构 ─────────────────────────────────────────

    @Test
    fun `E9 - execute result JSON has required fields`() = runBlocking {
        val port = findFreePort()
        val payload = buildJsonObject {
            put("urls", buildJsonArray { add(JsonPrimitive("http://127.0.0.1:$port")) })
            put("timeout_ms", 300)
        }.toString()
        val result = NetworkTestExecutor.execute(makeTask(payload))
        assertTrue(result.isSuccess)

        val output = result.result.loadJsonModel<NetworkTestExecutor.NetworkTestOutput>().getOrThrow()
        // proxy_configured 字段存在（是 Boolean，不为 null）
        // results 不为空
        assertEquals(1, output.results.size)
        // 每个 UrlResult 都包含 url、direct 字段
        val urlResult = output.results[0]
        assertEquals("http://127.0.0.1:$port", urlResult.url)
        // direct 字段有合法的 status
        val validStatuses = setOf("ok", "timeout", "error")
        assertTrue(urlResult.direct.status in validStatuses, "status 应为 ok/timeout/error，实际: ${urlResult.direct.status}")
    }
}
