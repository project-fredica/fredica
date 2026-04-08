package com.github.project_fredica.llm

// =============================================================================
// LlmProxyDebugTest —— LLM 调用在系统代理开启时失败的诊断测试（默认禁用）
// =============================================================================
//
// ── 背景与症状 ────────────────────────────────────────────────────────────────
//
// 症状：
//   开启系统代理（Clash HTTP 模式）后，LlmSseClient.streamChat 调用
//   api.deepseek.com 时抛出：
//     java.net.SocketException: 你的主机中的软件中止了一个已建立的连接
//   而关闭代理后调用正常。
//
//   此异常会从 LlmSseClient 透传到路由层，由 FredicaApi.jvm.kt 的
//   handleRouteResult 统一捕获，检测到系统代理时在响应 JSON 中附加：
//     "FredicaFixBugAdvice": "OpenClashPAC"
//   前端 app_fetch.ts 的 checkFixBugAdvice() 检测到该字段后弹出 toast 提示用户。
//
// 根本原因（已确认）：
//   LlmSseClient 使用 AppUtil.GlobalVars.ktorClientProxied，该客户端通过
//   JVM ProxySelector 读取系统代理，返回 Proxy.Type.HTTP 类型。
//   OkHttp 对 HTTPS 请求会向 HTTP 代理发送 "CONNECT host:443 HTTP/1.1" 隧道请求。
//   Clash 在 HTTP 模式下对此握手处理异常，导致连接被中止（WSAECONNABORTED）。
//
// ── 已验证修复方案 ────────────────────────────────────────────────────────────
//
// 方案 A（已验证有效 ✓，用户侧）：Clash 系统代理类型从 HTTP 改为 PAC
//   PAC 模式下 JVM 按脚本逐 URL 路由，不再直接发 CONNECT，问题消失。
//   实测：切换 PAC 模式后 Step 3 调用成功，问题消失。
//
// 方案 B（代码侧，未验证）：在 readNetworkProxy0() 中探测代理端口协议：
//   若 SOCKS5 握手成功 → 强制返回 Proxy.Type.SOCKS 类型
//   OkHttp 对 SOCKS5 代理不发 CONNECT，直接走 SOCKS5 协议
//
// 方案 C（代码侧，激进，未验证）：LlmSseClient 改用 ktorClientLocal（直连），
//   由用户在代理软件中配置规则让 api.deepseek.com 走代理
//
// ── 前端用户提示链路 ─────────────────────────────────────────────────────────
//
//   LlmSseClient 抛出 SocketException
//     → 透传到 FredicaApi.jvm.kt handleRouteResult catch 块
//     → 检测 AppUtil.readNetworkProxy() != null + 异常消息匹配
//     → 响应 JSON 附加 "FredicaFixBugAdvice": "OpenClashPAC"
//     → 前端 app_fetch.ts checkFixBugAdvice() 检测字段
//     → toast.warn("建议将 Clash 代理模式切换为 PAC 模式")
//
// ── 本测试的结构 ──────────────────────────────────────────────────────────────
//
//   Step 1：读取系统代理，确认代理类型与地址
//   Step 2：对 api.deepseek.com:443 发送裸 HTTP CONNECT，观察代理响应
//   Step 3：用 ktorClientProxied 直接调用 LlmSseClient.streamChat（PAC 模式下预期成功）
//   Step 4：用 ktorClientLocal（直连）调用 LlmSseClient.streamChat，对比结果
//   Step 5：综合结论与修复建议
//
// ── 使用方法 ──────────────────────────────────────────────────────────────────
//
//   1. 确保系统代理已开启（如 Clash HTTP 模式）。
//   2. 在 AppConfig 中配置 dev_test_model_id（指向 llm_models_json 中的某个模型），
//      或直接在 llm_default_roles_json 中设置 devTestModelId。
//   3. 将下方 @Ignore 注解临时注释掉（或在 IDE 中单独运行此测试）。
//   4. 查看控制台输出的诊断报告。
//   5. 调试完毕后恢复 @Ignore。
//
// 注意：此测试不含 assertTrue/assertEquals，所有结论通过 println 输出。
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.readNetworkProxy
import com.github.project_fredica.testutil.TestAppConfig
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import kotlin.test.BeforeTest
import kotlin.test.Test

class LlmProxyDebugTest {

    private var skipTest = false
    private lateinit var modelConfig: LlmModelConfig

    @BeforeTest
    fun setup() {
        System.setProperty("java.net.useSystemProxies", "true")

        val cfg = TestAppConfig.loadLlmConfig()
        if (cfg == null || !cfg.llmTestIsConfigured) {
            println("[setup] llmTestApiKey/BaseUrl/Model 未配置，跳过测试")
            skipTest = true
            return
        }
        modelConfig = LlmModelConfig(
            id = "test",
            name = "Test Model",
            baseUrl = cfg.llmTestBaseUrl,
            apiKey = cfg.llmTestApiKey,
            model = cfg.llmTestModel,
            capabilities = setOf(LlmCapability.STREAMING),
        )
        println("[setup] 使用模型: baseUrl=${modelConfig.baseUrl} model=${modelConfig.model}")
    }

    @Test
    fun diagnoseLlmProxyIssue() = runBlocking {
        if (skipTest) return@runBlocking

        println("\n" + "=".repeat(60))
        println("LlmProxyDebugTest — LLM 代理调用诊断报告")
        println("=".repeat(60))

        // ── Step 1：读取系统代理 ──────────────────────────────────────────────
        println("\n[Step 1] 系统代理检测")
        println("-".repeat(40))

        val targetUri = URI("https://www.google.com")
        val systemProxies = ProxySelector.getDefault().select(targetUri)
            .filterNotNull()
            .filter { it.type() != Proxy.Type.DIRECT }

        val appProxy = AppUtil.readNetworkProxy()

        if (systemProxies.isEmpty() && appProxy == null) {
            println("  未检测到系统代理。此测试用于诊断代理开启时的问题，请先开启代理再运行。")
            return@runBlocking
        }

        systemProxies.forEachIndexed { i, p ->
            println("  ProxySelector[$i]: type=${p.type()}, address=${p.address()}")
        }
        println("  AppUtil.readNetworkProxy(): $appProxy")

        val proxy = systemProxies.firstOrNull() ?: appProxy
        val proxyAddr = proxy?.address() as? InetSocketAddress
        if (proxyAddr == null) {
            println("  无法解析代理地址，测试终止。")
            return@runBlocking
        }
        println("  → 将探测代理: type=${proxy.type()}, host=${proxyAddr.hostName}, port=${proxyAddr.port}")

        // ── Step 2：对 api.deepseek.com:443 发送裸 HTTP CONNECT ──────────────
        println("\n[Step 2] HTTP CONNECT 探测（目标: api.deepseek.com:443）")
        println("-".repeat(40))
        try {
            Socket().use { s ->
                s.connect(proxyAddr, 5_000)
                s.soTimeout = 5_000
                val out = s.getOutputStream()
                val inp = s.getInputStream()

                val request = "CONNECT api.deepseek.com:443 HTTP/1.1\r\n" +
                    "Host: api.deepseek.com:443\r\n" +
                    "User-Agent: LlmProxyDebugTest/1.0\r\n\r\n"
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.flush()

                val buf = ByteArray(1024)
                val n = try { inp.read(buf) } catch (e: Exception) { -1 }

                if (n > 0) {
                    val resp = String(buf, 0, n, Charsets.ISO_8859_1).trim()
                    val firstLine = resp.lines().firstOrNull() ?: ""
                    println("  响应首行: $firstLine")
                    when {
                        firstLine.contains("200") ->
                            println("  → HTTP CONNECT 被接受 ✓ — 代理支持 HTTPS 隧道，问题可能在 TLS 层")
                        firstLine.contains("407") ->
                            println("  → 代理要求认证 (407) — OkHttp 未发送 Proxy-Authorization")
                        firstLine.startsWith("HTTP") ->
                            println("  → 收到 HTTP 响应但状态码异常: $firstLine")
                        else ->
                            println("  → 响应非 HTTP 格式，可能是 SOCKS5 代理被误识别为 HTTP")
                    }
                    if (resp.length > firstLine.length) {
                        println("  完整响应（前 300 字符）:\n${resp.take(300)}")
                    }
                } else if (n == 0) {
                    println("  收到 0 字节 → 连接被立即关闭")
                    println("  → 最可能原因：Clash HTTP 模式对 CONNECT 握手处理异常")
                } else {
                    println("  读取超时或连接在读取前被关闭")
                    println("  → 代理可能拒绝了 CONNECT 请求")
                }
            }
        } catch (e: Exception) {
            println("  探测异常: ${e::class.simpleName}: ${e.message}")
        }

        // ── Step 3：用 ktorClientProxied 调用 streamChat（PAC 模式下预期成功）──
        println("\n[Step 3] 通过代理调用 LlmSseClient.streamChat（PAC 模式下预期成功）")
        println("-".repeat(40))

        val requestBody = buildJsonObject {
            put("model", modelConfig.model)
            put("stream", true)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", "Reply with exactly one word: hello")
                }
            })
            put("max_tokens", 10)
        }.toString()

        try {
            val result = LlmSseClient.streamChat(
                modelConfig = modelConfig,
                requestBody = requestBody,
            )
            println("  ✓ 调用成功（代理工作正常）: result=$result")
            println("  → 代理问题已不存在，或当前代理模式不触发此 bug")
        } catch (e: Exception) {
            println("  ✗ 调用失败: ${e::class.qualifiedName}")
            println("  错误信息: ${e.message}")
            val cause = e.cause
            if (cause != null) {
                println("  根因: ${cause::class.qualifiedName}: ${cause.message}")
                val rootCause = cause.cause
                if (rootCause != null) {
                    println("  根因2: ${rootCause::class.qualifiedName}: ${rootCause.message}")
                }
            }
            when {
                e.message?.contains("中止了一个已建立的连接") == true ||
                e.message?.contains("connection aborted") == true ||
                e.message?.contains("WSAECONNABORTED") == true ->
                    println("  → 确认是 WSAECONNABORTED：HTTP CONNECT 隧道被代理中止")
                e.message?.contains("Connection refused") == true ->
                    println("  → 代理端口拒绝连接，代理软件可能未运行")
                e.message?.contains("timed out") == true ->
                    println("  → 连接超时")
                else ->
                    println("  → 其他网络异常")
            }
        }

        // ── Step 4：用 ktorClientLocal 直连调用（对照组）────────────────────
        println("\n[Step 4] 直连调用 LlmSseClient（绕过代理，对照组）")
        println("-".repeat(40))
        println("  注意：此步骤直接连接 ${modelConfig.baseUrl}，不经过代理")

        try {
            // 直接用 ktorClientLocal 构造请求，绕过代理
            var directResult: String? = null
            AppUtil.GlobalVars.ktorClientLocal.preparePost("${modelConfig.baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                directResult = response.bodyAsText().take(200)
                println("  HTTP 状态: ${response.status.value}")
            }
            println("  ✓ 直连成功: ${directResult?.take(100)}")
            println("  → 确认：API key 有效，网络直连可达，问题仅在代理路径")
        } catch (e: Exception) {
            println("  ✗ 直连也失败: ${e::class.simpleName}: ${e.message}")
            println("  → 可能是 API key 无效或网络不可达（与代理无关）")
        }

        // ── Step 5：综合结论 ──────────────────────────────────────────────────
        println("\n[Step 5] 综合结论与修复建议")
        println("=".repeat(60))
        println("""
  ── 根本原因 ────────────────────────────────────────────────────────────────

  LlmSseClient 使用 ktorClientProxied，该客户端通过 JVM ProxySelector
  读取系统代理。当 Clash 设置为 HTTP 模式时：
    1. Windows 系统代理注册为 http=127.0.0.1:7890
    2. JVM 返回 Proxy.Type.HTTP
    3. OkHttp 对 HTTPS 请求发送 "CONNECT api.deepseek.com:443 HTTP/1.1"
    4. Clash HTTP 模式对此握手处理异常 → WSAECONNABORTED

  ── 前端用户提示链路 ────────────────────────────────────────────────────────

  此异常透传到 FredicaApi.jvm.kt handleRouteResult catch 块，
  检测到系统代理时响应 JSON 附加 "FredicaFixBugAdvice": "OpenClashPAC"，
  前端 app_fetch.ts checkFixBugAdvice() 检测到该字段后弹出 toast 提示用户。

  ── 修复方案（按优先级）────────────────────────────────────────────────────

  方案 A（已验证有效 ✓，用户侧）：
    Clash「设置 → 系统代理 → 类型」从 HTTP 改为 PAC
    PAC 模式下 JVM 按脚本逐 URL 路由，不再直接发 CONNECT
    → 实测：切换 PAC 模式后 Step 3 调用成功，问题消失

  方案 B（代码侧，未验证）：
    在 readNetworkProxy0() 中探测代理端口协议：
    若 SOCKS5 握手成功 → 强制返回 Proxy.Type.SOCKS 类型
    OkHttp 对 SOCKS5 代理不发 CONNECT，直接走 SOCKS5 协议

  方案 C（代码侧，激进，未验证）：
    LlmSseClient 改用 ktorClientLocal（直连），
    由用户在代理软件中配置规则让 api.deepseek.com 走代理
        """.trimIndent())
        println("=".repeat(60) + "\n")
    }
}
