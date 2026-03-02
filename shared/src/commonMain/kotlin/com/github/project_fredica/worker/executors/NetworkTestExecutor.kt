package com.github.project_fredica.worker.executors

// =============================================================================
// NetworkTestExecutor —— 网速和延迟测试执行器（直连 + 代理双路测试）
// =============================================================================
//
// 用途：
//   1. 开发期可视化调试：无外部依赖的轻量任务，直接触发 Worker Engine 观察状态流转
//   2. 网络诊断工具：区分"直连"和"代理"两种路径，帮助判断代理是否生效
//
// 客户端来源（均取自 AppUtil.GlobalVars 全局变量）：
//   - AppUtil.GlobalVars.ktorClientLocal   → 直连（强制 ProxyConfig.NO_PROXY）
//   - AppUtil.GlobalVars.ktorClientProxied → 代理（读取系统代理配置）
//   两个 client 由 AppUtil 统一管理，executor 不自行构造 HttpClient。
//
// 超时控制：
//   全局 client 未安装 HttpTimeout 插件，改用 withTimeout { } 协程超时。
//   withTimeout 在超时时抛出 TimeoutCancellationException，仅取消内部 block，
//   不影响外层协程结构，可安全捕获。
//
// 位于 commonMain，无平台特定依赖。
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.isDirect
import com.github.project_fredica.apputil.readNetworkProxy
import com.github.project_fredica.db.Task
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.measureTime

/**
 * 对一组 URL 同时发起"直连"和"代理"两路 HEAD 请求，统计各自的延迟和连通性。
 *
 * Payload JSON:
 * ```json
 * {"urls": ["https://www.baidu.com", ...], "timeout_ms": 5000}
 * ```
 *
 * Result JSON:
 * ```json
 * {
 *   "proxy_configured": true,
 *   "results": [
 *     {
 *       "url": "https://www.baidu.com",
 *       "direct":  {"latency_ms": 42,  "status": "ok"},
 *       "proxied": {"latency_ms": 38,  "status": "ok"}
 *     },
 *     {
 *       "url": "https://www.google.com",
 *       "direct":  {"latency_ms": null, "status": "timeout", "error": "超时（>5000ms）"},
 *       "proxied": {"latency_ms": 350,  "status": "ok"}
 *     }
 *   ]
 * }
 * ```
 * 若系统未配置代理，`proxy_configured=false`，所有 `proxied` 字段均为 `null`。
 */
object NetworkTestExecutor : TaskExecutor {
    override val taskType = "NETWORK_TEST"
    private val logger = createLogger()

    // 未指定 urls 时的默认测试目标
    private val DEFAULT_URLS = listOf(
        "https://www.baidu.com",
        "https://www.google.com",
        "https://github.com",
        "https://api.openai.com",
    )

    @Serializable
    data class Payload(
        val urls: List<String> = DEFAULT_URLS,
        @SerialName("timeout_ms") val timeoutMs: Int = 5_000,
    )

    /**
     * 单路（直连或代理）的连接测试结果。
     *
     * @param latencyMs 实测延迟（毫秒），超时或出错时为 null
     * @param status    ok / timeout / error
     * @param error     错误详情（status 非 ok 时填写）
     */
    @Serializable
    data class ConnResult(
        @SerialName("latency_ms") val latencyMs: Long? = null,
        val status: String,
        val error: String? = null,
    )

    /**
     * 单条 URL 的完整测试结果。
     *
     * @param direct  通过 [AppUtil.GlobalVars.ktorClientLocal]（强制直连）的结果
     * @param proxied 通过 [AppUtil.GlobalVars.ktorClientProxied]（系统代理）的结果；
     *                未配置系统代理时为 null
     */
    @Serializable
    data class UrlResult(
        val url: String,
        val direct: ConnResult,
        val proxied: ConnResult?,
    )

    /** execute() 的整体输出，序列化为 task.result 写入数据库。 */
    @Serializable
    data class TestOutput(
        /** 系统是否配置了代理（false 时所有 proxied 字段均为 null）。 */
        @SerialName("proxy_configured") val proxyConfigured: Boolean,
        val results: List<UrlResult>,
        /**
         * 检测到代理异常时的提示信息，null 表示无异常。
         *
         * 触发条件：代理已配置 + 所有代理请求失败 + 至少一条直连成功。
         * 典型场景：系统代理被识别为 HTTP 类型，但实际为 SOCKS5 服务，
         * 导致 OkHttp 的 HTTP CONNECT 握手被服务端拒绝（WSAECONNABORTED）。
         */
        @SerialName("proxy_warning") val proxyWarning: String? = null,
    )

    override suspend fun execute(task: Task): ExecuteResult {
        val payload = try {
            Json.decodeFromString<Payload>(task.payload)
        } catch (_: Throwable) {
            Payload() // payload 解析失败时使用默认值
        }

        // ── 判断系统代理状态 ──────────────────────────────────────────────────
        // AppUtil.readNetworkProxy() 读取平台系统代理，isDirect() 返回 true 表示无代理
        val proxyConfig = AppUtil.readNetworkProxy()
        val proxyConfigured = !proxyConfig.isDirect()

        logger.info(
            "NetworkTestExecutor: 开始测试 ${payload.urls.size} 个 URL，" +
                    "超时=${payload.timeoutMs}ms，代理=${if (proxyConfigured) "已配置" else "未配置"}"
        )

        // ── 取全局共享 client（由 AppUtil.GlobalVars 统一管理） ───────────────
        // GlobalVars 内部 lazy 初始化，首次访问时才创建；executor 不持有引用，不负责关闭。
        val directClient = AppUtil.GlobalVars.ktorClientLocal      // 强制 NO_PROXY
        val proxiedClient = if (proxyConfigured) AppUtil.GlobalVars.ktorClientProxied else null

        // ── 逐一测试（顺序执行，保持结果与输入 URL 顺序一致） ──────────────
        val results = withContext(Dispatchers.Default) {
            payload.urls.map { url ->
                async {
                    val direct = testUrl(url, directClient, payload.timeoutMs.toLong(), "直连")
                    val proxied = proxiedClient?.let { testUrl(url, it, payload.timeoutMs.toLong(), "代理") }
                    UrlResult(url = url, direct = direct, proxied = proxied)
                }
            }.awaitAll()
        }

        val directOk  = results.count { it.direct.status == "ok" }
        val proxiedOk = results.count { it.proxied?.status == "ok" }
        logger.info(
            "NetworkTestExecutor: 测试完成 — " +
                    "直连正常=$directOk/${results.size}，" +
                    "代理正常=$proxiedOk/${if (proxyConfigured) results.size else 0}"
        )

        // ── 代理异常检测 ──────────────────────────────────────────────────────
        // 条件：代理已配置 AND 所有代理请求失败 AND 至少一条直连成功
        // 这种组合说明代理本身可能有问题，而非目标网站不可达。
        val proxyWarning: String? = if (proxyConfigured) {
            val proxyAllFailed = results.all { r -> r.proxied == null || r.proxied.status != "ok" }
            val directAnyOk   = results.any  { it.direct.status == "ok" }
            if (proxyAllFailed && directAnyOk) {
                // 进一步判断是否是 WSAECONNABORTED 特征（TCP 已建立但被本地中止）
                // 该错误通常由协议不匹配引起：ProxySelector 把 SOCKS5 代理识别为 HTTP 类型，
                // OkHttp 发送 HTTP CONNECT 握手，SOCKS5 服务端不理解而关闭连接。
                val abortKeywords = listOf("中止了一个已建立的连接", "established connection was aborted")
                val looksLikeAbort = results.any { r ->
                    abortKeywords.any { kw -> r.proxied?.error?.contains(kw, ignoreCase = true) == true }
                }
                if (looksLikeAbort) {
                    "代理连接异常（WSAECONNABORTED）：TCP 三次握手已完成（代理软件正在运行），" +
                    "但连接随即被本地 JVM/OkHttp 中止。" +
                    "已知根本原因：当代理软件（如 Clash）的系统代理类型设置为「HTTP」时，" +
                    "JVM OkHttp 向代理端口发送 HTTP CONNECT 握手，" +
                    "而 Clash 在 HTTP 模式下对该握手的处理会导致连接被立即关闭。" +
                    "【修复方法】在 Clash 中将「设置 → 系统代理 → 类型」从 HTTP 改为 PAC；" +
                    "PAC 模式下 JVM 通过 PAC 脚本逐 URL 路由，可绕过此问题。" +
                    "如需进一步排查，可临时启用 NetworkTestProxyDebugTest 对代理端口进行协议探测。"
                } else {
                    "代理异常：代理已配置，但所有代理请求均失败，而直连测试正常。" +
                    "可能原因：代理服务未启动、协议不匹配或防火墙拦截了代理连接。" +
                    "如使用 Clash，可尝试将系统代理类型从「HTTP」改为「PAC」后重新测试。"
                }
            } else null
        } else null

        proxyWarning?.let { logger.warn("NetworkTestExecutor: $it") }

        return ExecuteResult(result = Json.encodeToString(TestOutput(proxyConfigured, results, proxyWarning)))
    }

    /**
     * 用指定 [client] 对 [url] 发起 HEAD 请求，通过 [withTimeout] 实现超时控制。
     *
     * 为何使用 withTimeout 而非 HttpTimeout 插件：
     *   全局 client（AppUtil.GlobalVars）不安装 HttpTimeout，避免为所有请求添加超时。
     *   withTimeout 仅取消 lambda 内部的协程，不影响外层结构，可安全捕获
     *   TimeoutCancellationException（它只取消内部 block，不会传播到调用方协程）。
     *
     * @param label 日志标签（"直连" 或 "代理"），便于在日志中区分两路测试
     */
    private suspend fun testUrl(
        url: String,
        client: HttpClient,
        timeoutMs: Long,
        label: String,
    ): ConnResult {
        return try {
            val elapsed = measureTime {
                withTimeout(timeoutMs) {
                    client.head(url)
                }
            }
            logger.debug("NetworkTestExecutor [$label]: $url → ${elapsed.inWholeMilliseconds}ms")
            ConnResult(latencyMs = elapsed.inWholeMilliseconds, status = "ok")
        } catch (e: TimeoutCancellationException) {
            logger.debug("NetworkTestExecutor [$label]: $url → 超时（>${timeoutMs}ms）")
            ConnResult(status = "timeout", error = "超时（>${timeoutMs}ms）")
        } catch (e: Throwable) {
            val msg = e.message?.take(120) ?: e::class.simpleName ?: "未知错误"
            logger.debug("NetworkTestExecutor [$label]: $url → 错误: $e")
            ConnResult(status = "error", error = msg)
        }
    }
}
