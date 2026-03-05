@file:Suppress("UnusedReceiverParameter")

package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat
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
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

actual fun AppUtil.readNetworkProxy(): ProxyConfig? {
//    val logger = createLogger()
    val res = readNetworkProxy0()
//    logger.debug("readNetworkProxy : $res")
    return res
}

private fun AppUtil.readNetworkProxy0(): ProxyConfig? {
//    val logger = createLogger()
    val availableProxies = mutableListOf<ProxyConfig>()
    val targetURI = URI("https://www.google.com")

    // Get the list of proxies applicable for the given URI
    val proxies: MutableList<Proxy?>? = ProxySelector.getDefault().select(targetURI)

    if (proxies !== null) {
        for (proxy in proxies) {
            if (proxy.isDirect()) {
//                logger.debug("skip direct proxy : $proxy")
                continue
            }
//            logger.debug("available proxy : $proxy")
            availableProxies.add(proxy)
        }
    }

    if (availableProxies.isEmpty()) {
        return null
    }

    return availableProxies.first()
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