package com.github.project_fredica.worker.executors

import com.github.project_fredica.api.routes.NETWORK_TEST_DEFAULT_URLS
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.internalReadNetworkProxyUrl
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.Task
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/**
 * 网速和延迟测试执行器。
 *
 * ## Payload 格式
 * ```json
 * { "urls": ["https://..."], "timeout_ms": 5000 }
 * ```
 *
 * ## 结果格式
 * 见 [NetworkTestOutput]，前端直接解析 task.result。
 */
object NetworkTestExecutor : TaskExecutor {
    override val taskType = "NETWORK_TEST"
    private val logger = createLogger()

    @Serializable
    private data class Payload(
        val urls: List<String> = NETWORK_TEST_DEFAULT_URLS,
        @SerialName("timeout_ms") val timeoutMs: Int = 5_000,
    )

    @Serializable
    data class ConnResult(
        @SerialName("latency_ms") val latencyMs: Long?,
        val status: String,   // "ok" | "timeout" | "error"
        val error: String?,
    )

    @Serializable
    data class UrlResult(
        val url: String,
        val direct: ConnResult,
        val proxied: ConnResult?,
    )

    @Serializable
    data class NetworkTestOutput(
        @SerialName("proxy_configured") val proxyConfigured: Boolean,
        val results: List<UrlResult>,
        @SerialName("proxy_warning") val proxyWarning: String?,
    )

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        // loadJsonModel 是项目约定的反序列化入口（json-handling.md §1.3）
        val payload = task.payload.loadJsonModel<Payload>().getOrElse { Payload() }

        logger.info("NetworkTestExecutor: 开始测试 [taskId=${task.id}, urls=${payload.urls.size}, timeout=${payload.timeoutMs}ms]")

        // 读取系统代理 URL（"http://host:port" 或 ""）
        val proxyUrlStr = AppUtil.internalReadNetworkProxyUrl()
        val proxyConfigured = proxyUrlStr.isNotBlank()
        val javaProxy: Proxy? = if (proxyConfigured) buildProxy(proxyUrlStr) else null

        val results = payload.urls.map { url ->
            val direct = testUrl(url, Proxy.NO_PROXY, payload.timeoutMs)
            val proxied = if (javaProxy != null) testUrl(url, javaProxy, payload.timeoutMs) else null
            UrlResult(url = url, direct = direct, proxied = proxied)
        }

        // 检测代理异常（直连可达但代理全不可达）
        val proxyWarning: String? = if (proxyConfigured && javaProxy != null) {
            val directOk = results.count { it.direct.status == "ok" }
            val proxiedOk = results.count { it.proxied?.status == "ok" }
            if (directOk > 0 && proxiedOk == 0) "直连可达但代理不可达，代理可能配置有误（$proxyUrlStr）" else null
        } else null

        val output = NetworkTestOutput(
            proxyConfigured = proxyConfigured,
            results = results,
            proxyWarning = proxyWarning,
        )

        logger.info("NetworkTestExecutor: 测试完成 [taskId=${task.id}, proxyConfigured=$proxyConfigured, warning=$proxyWarning]")
        // dumpJsonStr 是项目约定的序列化入口（json-handling.md §1.2）
        ExecuteResult(result = AppUtil.dumpJsonStr(output).getOrThrow().str)
    }

    private fun buildProxy(proxyUrlStr: String): Proxy? {
        return try {
            val uri = URI(proxyUrlStr)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
        } catch (e: Throwable) {
            // 代理 URL 格式异常属于预期外的配置错误，用 warn 记录（error-handling.md §2 Kotlin）
            logger.warn("[NetworkTestExecutor] buildProxy: 代理 URL 解析失败 url=$proxyUrlStr", isHappensFrequently = false, err = e)
            null
        }
    }

    /**
     * 对单个 URL 发起 HEAD 请求，测量延迟。
     *
     * - status="ok"      → 收到 HTTP 响应，latencyMs 为往返时间
     * - status="timeout" → Java connectTimeout / readTimeout 触发 SocketTimeoutException，
     *                      或 withTimeoutOrNull 安全网触发
     * - status="error"   → 其他 I/O 异常（连接被拒、DNS 失败、SSL 错误等）
     *
     * CancellationException 不被捕获——协程取消信号必须透传（error-handling.md §7.5）。
     */
    internal suspend fun testUrl(url: String, proxy: Proxy, timeoutMs: Int): ConnResult =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            // 在 Java connectTimeout/readTimeout 之上再加 500ms 安全网
            val result = withTimeoutOrNull(timeoutMs.toLong() + 500L) {
                try {
                    val conn = URL(url).openConnection(proxy) as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connect()
                    // 显式读取响应码：驱动 HTTP 请求发送 + 响应头读取，readTimeout 在此生效
                    @Suppress("UNUSED_VARIABLE")
                    val responseCode = conn.responseCode
                    val latencyMs = System.currentTimeMillis() - startMs
                    conn.disconnect()
                    ConnResult(latencyMs = latencyMs, status = "ok", error = null)
                } catch (e: CancellationException) {
                    // 取消信号必须透传，不能转成 status="error"（error-handling.md §7.5）
                    throw e
                } catch (e: SocketTimeoutException) {
                    // Java 层 connectTimeout / readTimeout 到期：明确报 "timeout"
                    ConnResult(latencyMs = null, status = "timeout", error = e.message)
                } catch (e: Throwable) {
                    // 其他 I/O 异常（拒绝连接、DNS、SSL 等）属于预期失败，不需要 stacktrace
                    ConnResult(latencyMs = null, status = "error", error = e.message ?: e.javaClass.simpleName)
                }
            }
            // withTimeoutOrNull 触发（安全网超时）
            result ?: ConnResult(latencyMs = null, status = "timeout", error = "安全超时（>${timeoutMs + 500}ms）")
        }
}
