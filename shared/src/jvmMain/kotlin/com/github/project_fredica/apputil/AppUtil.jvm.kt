@file:Suppress("UnusedReceiverParameter")

package com.github.project_fredica.apputil

import io.ktor.client.engine.ProxyConfig
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.burningwave.core.assembler.StaticComponentContainer
import sun.misc.Unsafe
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI

actual fun AppUtil.readNetworkProxy(): ProxyConfig? {
//    val logger = createLogger()
    val res = readNetworkProxy0()
//    logger.debug("readNetworkProxy : $res")
    return res
}

/**
 * 读取系统代理地址，返回 "http://host:port" 格式字符串。
 * 无代理时返回空串。
 *
 * 直接使用 [InetSocketAddress.getHostString] + [InetSocketAddress.getPort]，
 * 避免依赖 toString() 的格式（如 "/127.0.0.1/<unresolved>:7890"）。
 */
actual fun AppUtil.readNetworkProxyUrl(): String {
    val proxy = readNetworkProxy0() ?: return ""
    return try {
        val inetAddr = proxy.address() as? java.net.InetSocketAddress
            ?: return ""
        // getHostString() 返回原始主机名或 IP，不触发 DNS 解析，不含 <unresolved> 后缀
        val host = inetAddr.hostString
        val port = inetAddr.port
        "http://$host:$port"
    } catch (e: Throwable) {
        ""
    }
}

private fun AppUtil.readNetworkProxy0(): ProxyConfig? {
    val targetURI = URI("https://www.google.com")
    val proxies: MutableList<Proxy?>? = ProxySelector.getDefault().select(targetURI)

    val proxy = proxies
        ?.filterNotNull()
        ?.firstOrNull { !it.isDirect() }
        ?: return null

    // 当 JVM 报告 HTTP 类型时，探测端口是否实际支持 SOCKS5。
    // 背景：Clash 在 HTTP 模式下，Windows 系统代理注册为 http=127.0.0.1:7890，
    // JVM ProxySelector 返回 Proxy.Type.HTTP，OkHttp 随即对 HTTPS 请求发送
    // "CONNECT host:443 HTTP/1.1" 隧道请求，Clash 对此处理异常导致 WSAECONNABORTED。
    // 若端口实际是 SOCKS5（Clash mixed port 同时支持 HTTP 和 SOCKS5），
    // 强制返回 SOCKS 类型，OkHttp 将改用 SOCKS5 协议，绕过 HTTP CONNECT 问题。
    if (proxy.type() == Proxy.Type.HTTP) {
        val addr = proxy.address() as? InetSocketAddress
        if (addr != null && probeIsSocks5(addr)) {
            return Proxy(Proxy.Type.SOCKS, addr)
        }
    }

    return proxy
}

/**
 * 向代理端口发送 SOCKS5 握手包，判断是否为 SOCKS5 代理。
 *
 * SOCKS5 握手：Client → 0x05 0x01 0x00；Server → 0x05 0x?? 表示 SOCKS5。
 * 超时 1s，失败静默返回 false，不影响正常代理流程。
 */
private fun probeIsSocks5(addr: InetSocketAddress): Boolean {
    return try {
        Socket().use { s ->
            s.connect(addr, 1_000)
            s.soTimeout = 1_000
            s.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            s.getOutputStream().flush()
            val resp = ByteArray(2)
            val n = s.getInputStream().read(resp)
            n >= 2 && (resp[0].toInt() and 0xFF) == 5
        }
    } catch (_: Throwable) {
        false
    }
}

fun AppUtil.MonkeyPatch.burningwaveExportAllModule() {
    StaticComponentContainer.Modules.exportAllToAll()
}


actual suspend fun AppUtil.Paths.InternalInit.detectAppDataDirOnInit(): File {
    return File(".data")
}

suspend fun AppUtil.Paths.InternalInit.detectDevProjectRootDir(): File? {
    val logger = createLogger()

    val res = withContext(Dispatchers.IO) {
        val currentDir = File(System.getProperty("user.dir")).absoluteFile
        logger.debug("current dir is $currentDir")
        if (currentDir.name == "composeApp" && currentDir.parentFile.isDirectory && currentDir.parentFile.name == "fredica") {
            return@withContext currentDir.parentFile
        }
        return@withContext null
    }
    logger.debug("devProjectRootDir is $res")
    return res
}

val AppUtil.Paths.devProjectRootDir: File? by lazy {
    runBlocking {
        return@runBlocking AppUtil.Paths.InternalInit.detectDevProjectRootDir()
    }
}

fun AppUtil.GlobalVars.InternalInit.getUnsafe(): Unsafe {
    val logger = createLogger()
    val unsafe = UnsafeAccess.UNSAFE!!
    logger.debug("get jvm unsafe : $unsafe")
    return unsafe
}

val AppUtil.GlobalVars.unsafe: Unsafe by lazy {
    AppUtil.GlobalVars.InternalInit.getUnsafe()
}

val AppUtil.GlobalVars.shutdownHookThreadGroup by lazy { ThreadGroup("shutdownHookThreadGroup") }

/**
 * 前置清理锁：在所有 [addShutdownHook] 回调开始执行前必须先完成的清理工作。
 * null 表示未注册前置清理（各 hook 直接执行）。
 * 通过 [AppUtil.setPreShutdownCleanup] 设置，完成后自动 complete 此 Deferred。
 */
private var preShutdownCleanupDeferred: CompletableDeferred<Unit>? = null

/**
 * 注册前置清理逻辑：在所有 [addShutdownHook] 回调实际运行前，先执行 [scope] 完成清理，
 * 再释放前置清理锁，让各 shutdown hook 并发执行。
 *
 * 只能调用一次；[scope] 内的异常不会阻止锁释放（避免死锁）。
 */
fun AppUtil.setPreShutdownCleanup(scope: suspend CoroutineScope.() -> Unit) {
    val logger = createLogger()
    val deferred = CompletableDeferred<Unit>()
    preShutdownCleanupDeferred = deferred
    val t = Thread(
        AppUtil.GlobalVars.shutdownHookThreadGroup, {
            runBlocking(Dispatchers.IO) {
                try {
                    logger.debug("[preShutdownCleanup] start")
                    scope()
                    logger.debug("[preShutdownCleanup] done")
                } catch (err: Throwable) {
                    logger.error("[preShutdownCleanup] error", err)
                } finally {
                    deferred.complete(Unit) // 无论成功失败，始终释放锁，避免其他 hook 永久阻塞
                }
            }
        }, "shutdownHookThread-preShutdownCleanup"
    )
    t.isDaemon = false
    t.priority = Thread.MAX_PRIORITY
    Runtime.getRuntime().addShutdownHook(t)
}

actual fun AppUtil.addShutdownHook(tag: String, scope: suspend CoroutineScope.() -> Unit) {
    val logger = createLogger()
    val t = Thread(
        AppUtil.GlobalVars.shutdownHookThreadGroup, {
            runBlocking(Dispatchers.IO) {
                try {
                    preShutdownCleanupDeferred?.await() // 等待前置清理完成后再执行本 hook
                    logger.debug("[$tag] start shutdown hook")
                    scope()
                    logger.debug("[$tag] success run shutdown hook")
                } catch (err: Throwable) {
                    logger.error("[$tag] error on shutdown hook", err)
                }
            }
        }, "shutdownHookThread-$tag"
    )
    t.isDaemon = false
    t.priority = Thread.MAX_PRIORITY
    Runtime.getRuntime().addShutdownHook(t)
}