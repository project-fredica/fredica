@file:Suppress("UnusedReceiverParameter")

package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat
import io.ktor.client.engine.ProxyConfig
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess
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
    val logger = createLogger()
    val res = readNetworkProxy0()
    logger.debug("readNetworkProxy : $res")
    return res
}

private fun AppUtil.readNetworkProxy0(): ProxyConfig? {
    val logger = createLogger()
    val availableProxies = mutableListOf<ProxyConfig>()
    val targetURI = URI("https://www.google.com")

    // Get the list of proxies applicable for the given URI
    val proxies: MutableList<Proxy?>? = ProxySelector.getDefault().select(targetURI)

    if (proxies !== null) {
        for (proxy in proxies) {
            if (proxy.isDirect()) {
                logger.debug("skip direct proxy : $proxy")
                continue
            }
            logger.debug("available proxy : $proxy")
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


actual fun AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(src: String): String {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, src)
}

actual suspend fun AppUtil.Paths.InternalInit.detectAppDataDirOnInit(): File {
    return File(".data")
}

suspend fun AppUtil.Paths.InternalInit.detectDevProjectRootDir(): File? {
    val logger = createLogger()

    val res = withContext(Dispatchers.IO) {
        val currentDir = File(System.getProperty("user.dir")).absoluteFile
        logger.debug("current dir is $currentDir")
        if (currentDir.name == "composeApp"
            && currentDir.parentFile.isDirectory
            && currentDir.parentFile.name == "fredica"
        ) {
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