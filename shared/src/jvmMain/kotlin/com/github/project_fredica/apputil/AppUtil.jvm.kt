@file:Suppress("UnusedReceiverParameter")

package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat
import io.ktor.client.engine.ProxyConfig
import io.vertx.core.Vertx
import org.burningwave.core.assembler.StaticComponentContainer
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

actual fun AppUtil.readNetworkProxy(): ProxyConfig? {
    return readNetworkProxy0()
}

private fun AppUtil.readNetworkProxy0(): ProxyConfig? {
    val logger = createLogger()
    val availableProxies = mutableListOf<ProxyConfig>()
    val targetURI = URI("https://www.google.com")

    // Get the list of proxies applicable for the given URI
    val proxies: MutableList<Proxy?>? = ProxySelector.getDefault().select(targetURI)

    if (proxies !== null) {
        for (proxy in proxies) {
            if (proxy == null) {
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