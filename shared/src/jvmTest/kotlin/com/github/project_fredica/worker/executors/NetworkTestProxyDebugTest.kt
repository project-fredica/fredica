package com.github.project_fredica.worker.executors

// =============================================================================
// NetworkTestProxyDebugTest —— 代理协议探测调试测试（默认禁用）
// =============================================================================
//
// ── 背景与目的 ────────────────────────────────────────────────────────────────
//
// 症状：
//   NetworkTestExecutor 的代理路径（ktorClientProxied）对所有 URL 均报错
//   "java.net.SocketException: 你的主机中的软件中止了一个已建立的连接"
//   而直连路径（ktorClientLocal）工作正常。
//
// 错误含义（Windows 错误码 WSAECONNABORTED = 10053）：
//   "已建立的连接" → TCP 三次握手已完成（代理端口确实在监听）；
//   "被本地软件中止" → 不是对端主动 RST，而是本机 JVM/OkHttp 放弃了连接。
//
// ── 已确认的根本原因（Clash HTTP 模式与 JVM OkHttp 不兼容）────────────────
//
//   当 Clash 的「设置 → 系统代理 → 类型」设置为「HTTP」时：
//     1. Windows 系统代理记录为 http=127.0.0.1:7890（纯 HTTP 代理）。
//     2. JVM ProxySelector 读取后返回 Proxy.Type.HTTP。
//     3. OkHttp 对 HTTPS 请求发送 "HTTP CONNECT host:443 HTTP/1.1"。
//     4. Clash 在 HTTP 模式下对该握手的处理存在问题，导致连接被立即关闭
//        → JVM 收到连接中止 → WSAECONNABORTED。
//
//   【已验证的修复方法】
//     在 Clash 中将「设置 → 系统代理 → 类型」从「HTTP」改为「PAC」：
//     PAC 模式下，Windows 注册的是 auto-config URL；JVM 通过 PAC 脚本
//     逐 URL 决定路由，不再对代理端口发送 HTTP CONNECT，从而绕过此问题。
//
// ── 其他可能原因（本测试可协助排查）────────────────────────────────────────
//   - 代理软件未运行但端口仍在系统配置中（此时 TCP 会被拒，不是 WSAECONNABORTED）
//   - 代理实际上是 SOCKS5，但 JVM 报告为 HTTP 类型 → OkHttp CONNECT 被拒
//   - Windows Defender / 防火墙拦截了对 127.0.0.1 的代理流量
//   - 代理服务器要求 Proxy-Authorization（返回 407，OkHttp 处理失败）
//
// ── 本测试的结构 ──────────────────────────────────────────────────────────────
//
// 测试在 TCP 层和应用协议层对代理端口进行探测，输出诊断报告到 stdout：
//   Step 1：读取 JVM ProxySelector 报告的代理类型与地址
//   Step 2：测试 TCP 连通性（排除"代理未运行"假说）
//   Step 3：发送裸 HTTP CONNECT 请求，观察响应（验证代理是否接受 HTTP CONNECT）
//   Step 4：发送 SOCKS5 握手包，观察响应（排查是否误识别为 HTTP 的 SOCKS5 代理）
//   Step 5：汇总报告——根据上述探测结果推断根本原因并给出修复建议
//
// ── 使用方法 ──────────────────────────────────────────────────────────────────
//
//   1. 确保本机代理软件正在运行且系统代理已配置。
//   2. 将下方 @Ignore 注解临时注释掉（或在 IDE 中单独运行此测试）。
//   3. 查看控制台输出的诊断报告。
//   4. 根据报告结论，修改代理软件配置（如 Clash 改为 PAC 模式）。
//   5. 调试完毕后恢复 @Ignore。
//
// 注意：此测试不含 assertTrue/assertEquals，所有结论通过 println 输出，
// 目的是探测诊断而非自动化验证（因为"正确"答案因运行环境而异）。
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.internalReadNetworkProxy
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("此测试用于探测诊断代理无法正常工作的情况，一般无需测试")
class NetworkTestProxyDebugTest {
    @BeforeTest
    fun beforeTest() {
        System.setProperty("java.net.useSystemProxies", "true");
    }


    @Test
    fun debugProxyProtocol() = runBlocking {
        println("\n" + "=".repeat(60))
        println("NetworkTestProxyDebugTest — 代理协议探测报告")
        println("=".repeat(60))

        // ── Step 1：读取系统代理 ──────────────────────────────────────────────
        println("\n[Step 1] 代理检测结果")
        println("-".repeat(40))

        val targetUri = URI("https://www.google.com")
        val systemProxies =
            setOf(
                *ProxySelector.getDefault().select(targetUri).toTypedArray().ifEmpty { arrayOf() },
                AppUtil.internalReadNetworkProxy()
            ).filterNotNull()


        if (systemProxies.isEmpty()) {
            println("  ProxySelector 返回空列表，系统未配置代理。测试终止。")
            return@runBlocking
        }

        println("  URI: $targetUri")
        systemProxies.forEachIndexed { i, p ->
            println("  代理 [$i]: type=${p.type()}, address=${p.address()}")
        }

        val proxy = systemProxies.firstOrNull { it.type() != Proxy.Type.DIRECT }
        if (proxy == null) {
            println("  所有代理均为 DIRECT，系统未配置非直连代理。测试终止。")
            return@runBlocking
        }

        val proxyAddr = proxy.address() as? InetSocketAddress
        if (proxyAddr == null) {
            println("  无法解析代理地址为 InetSocketAddress，测试终止。")
            return@runBlocking
        }

        println("\n  → 将使用代理: type=${proxy.type()}, host=${proxyAddr.hostName}, port=${proxyAddr.port}")
        println("  → 注意：type=${proxy.type()} 是 JVM 的判断，实际协议可能不同（见 Step 3/4）")

        // ── Step 2：TCP 连通性 ────────────────────────────────────────────────
        println("\n[Step 2] TCP 连通性测试（3s 超时）")
        println("-".repeat(40))

        val tcpOk = try {
            Socket().use { s ->
                s.connect(proxyAddr, 3_000)
                println("  ✓ TCP 连接成功 → 代理端口正在监听")
            }
            true
        } catch (e: Exception) {
            println("  ✗ TCP 连接失败: ${e.message}")
            println("  → 结论：代理软件可能未运行，或端口/地址有误")
            false
        }

        if (!tcpOk) return@runBlocking

        // ── Step 3：HTTP CONNECT 探测 ─────────────────────────────────────────
        // 向代理端口发送标准 HTTP CONNECT 请求头，观察响应。
        // 预期行为：
        //   - HTTP 代理：返回 "HTTP/1.1 200 Connection established"
        //   - SOCKS5 代理：不理解 HTTP，立即关闭连接（或返回乱码）
        //   - 需要认证的 HTTP 代理：返回 "HTTP/1.1 407 Proxy Authentication Required"
        println("\n[Step 3] HTTP CONNECT 协议探测")
        println("-".repeat(40))
        try {
            Socket().use { s ->
                s.connect(proxyAddr, 3_000)
                s.soTimeout = 3_000
                val out = s.getOutputStream()
                val inp = s.getInputStream()

                val request = "CONNECT www.google.com:443 HTTP/1.1\r\n" +
                        "Host: www.google.com:443\r\n" +
                        "User-Agent: NetworkTestProxyDebugTest/1.0\r\n\r\n"
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.flush()

                val buf = ByteArray(1024)
                val n = try {
                    inp.read(buf)
                } catch (e: Exception) {
                    -1
                }

                if (n > 0) {
                    val resp = String(buf, 0, n, Charsets.ISO_8859_1).trim()
                    val firstLine = resp.lines().firstOrNull() ?: ""
                    println("  响应首行: $firstLine")
                    when {
                        firstLine.contains("200") ->
                            println("  → 结论：HTTP CONNECT 被接受 ✓（这是标准 HTTP 代理行为）")

                        firstLine.contains("407") ->
                            println("  → 结论：代理要求认证 (407)，OkHttp 未发送 Proxy-Authorization")

                        firstLine.startsWith("HTTP") ->
                            println("  → 结论：收到 HTTP 响应，但状态码异常：$firstLine")

                        else ->
                            println("  → 结论：响应不是 HTTP 格式，可能不是 HTTP 代理")
                    }
                    if (resp.length > firstLine.length) {
                        println("  完整响应（前 300 字符）:\n${resp.take(300)}")
                    }
                } else if (n == 0) {
                    println("  收到 0 字节 → 连接被立即关闭（典型 SOCKS5 对 HTTP CONNECT 的反应）")
                } else {
                    println("  读取超时或连接在读取前被关闭")
                }
            }
        } catch (e: Exception) {
            println("  探测异常: ${e::class.simpleName}: ${e.message}")
        }

        // ── Step 4：SOCKS5 握手探测 ───────────────────────────────────────────
        // 向代理端口发送 SOCKS5 协议握手包，观察响应。
        // SOCKS5 握手格式：
        //   Client → Server: 0x05 0x01 0x00  (VER=5, NMETHODS=1, METHOD=0x00 无认证)
        //   Server → Client: 0x05 0x00        (VER=5, METHOD=0x00 接受无认证)  → 确认 SOCKS5
        //   Server → Client: 0x05 0xFF        (VER=5, METHOD=0xFF 无可接受方法) → 也是 SOCKS5，但要认证
        //   其他响应                           → 不是 SOCKS5
        println("\n[Step 4] SOCKS5 握手探测")
        println("-".repeat(40))
        try {
            Socket().use { s ->
                s.connect(proxyAddr, 3_000)
                s.soTimeout = 3_000
                val out = s.getOutputStream()
                val inp = s.getInputStream()

                // SOCKS5 greeting: VER=5, NMETHODS=1, METHOD=0x00 (no auth)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()

                val resp = ByteArray(2)
                val n = try {
                    inp.read(resp)
                } catch (e: Exception) {
                    -1
                }

                if (n >= 2) {
                    val ver = resp[0].toInt() and 0xFF
                    val method = resp[1].toInt() and 0xFF
                    println("  响应: VER=0x%02X, METHOD=0x%02X".format(ver, method))
                    when {
                        ver == 5 && method == 0x00 ->
                            println("  → 结论：确认是 SOCKS5 代理（无认证）✓")

                        ver == 5 && method == 0xFF ->
                            println("  → 结论：确认是 SOCKS5 代理，但要求认证（无可接受方法）")

                        ver == 5 ->
                            println("  → 结论：SOCKS5 服务器，使用了未知认证方法 0x%02X".format(method))

                        else ->
                            println("  → 结论：响应首字节不是 0x05，不是标准 SOCKS5")
                    }
                } else if (n == 0) {
                    println("  收到 0 字节 → 连接被立即关闭")
                } else {
                    println("  读取超时或连接在读取前关闭")
                }
            }
        } catch (e: Exception) {
            println("  探测异常: ${e::class.simpleName}: ${e.message}")
        }

        // ── Step 5：综合结论 ──────────────────────────────────────────────────
        println("\n[Step 5] 综合结论与修复建议")
        println("=".repeat(60))
        println(
            """
  ── 已确认的根本原因与首选修复方案 ────────────────────────────────────────

  症状：WSAECONNABORTED（TCP 已建立但被本地 JVM/OkHttp 中止）

  ┌ 首选修复（已验证）─────────────────────────────────────────────────────┐
  │  如使用 Clash：                                                         │
  │  「设置 → 系统代理 → 类型」从 HTTP 改为 PAC                            │
  │                                                                         │
  │  原理：HTTP 模式下 Windows 注册 http=127.0.0.1:7890，JVM 读取后以      │
  │  Proxy.Type.HTTP 类型传给 OkHttp；OkHttp 对 HTTPS 请求发送 HTTP        │
  │  CONNECT 握手，Clash 在 HTTP 模式下对此处理异常导致连接被中止。         │
  │  PAC 模式下 JVM 改为按 PAC 脚本逐 URL 路由，不再直接 CONNECT，         │
  │  从而绕过该问题。                                                        │
  └─────────────────────────────────────────────────────────────────────────┘

  ── 根据 Step 3 / Step 4 结果排查其他可能原因 ──────────────────────────

  ┌──────────────────────────┬────────────────────┬──────────────────────────┐
  │ Step 3 (HTTP CONNECT)    │ Step 4 (SOCKS5)    │ 结论与建议               │
  ├──────────────────────────┼────────────────────┼──────────────────────────┤
  │ 200 Connection           │ 任意               │ HTTP CONNECT 正常，问题  │
  │ established              │                    │ 出在 TLS 握手或防火墙；  │
  │                          │                    │ 尝试切换为 PAC 模式      │
  ├──────────────────────────┼────────────────────┼──────────────────────────┤
  │ 407 Proxy Auth Required  │ 任意               │ 代理要求认证，OkHttp 未  │
  │                          │                    │ 发送 Proxy-Authorization │
  ├──────────────────────────┼────────────────────┼──────────────────────────┤
  │ 0 字节 / 连接被关闭      │ SOCKS5 确认        │ 实为 SOCKS5 但被误识别   │
  │                          │ (VER=5,METHOD=0)   │ 为 HTTP；在 AppUtil 中   │
  │                          │                    │ 探测后强制设 SOCKS5 类型 │
  ├──────────────────────────┼────────────────────┼──────────────────────────┤
  │ 0 字节 / 连接被关闭      │ 非 SOCKS5          │ → 最可能是 Clash HTTP 模 │
  │                          │                    │ 式兼容性问题，切换 PAC   │
  ├──────────────────────────┼────────────────────┼──────────────────────────┤
  │ TCP 连接失败（Step 2 ✗） │ —                  │ 代理软件未运行或端口有误 │
  └──────────────────────────┴────────────────────┴──────────────────────────┘
        """.trimIndent()
        )
        println("=".repeat(60) + "\n")
    }
}
