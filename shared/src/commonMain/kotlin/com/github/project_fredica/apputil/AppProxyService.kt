package com.github.project_fredica.apputil

import com.github.project_fredica.db.AppConfigService

/**
 * 应用层代理地址读取服务。
 *
 * 优先级：AppConfig.proxyUrl（用户在 App 内配置的覆盖值） > 系统代理（[internalReadNetworkProxyUrl]）
 *
 * 需要读取代理地址时应使用此服务，而非直接调用 [internalReadNetworkProxyUrl]。
 * [internalReadNetworkProxyUrl] 仅读取系统级代理，不感知 App 内的覆盖配置。
 */
object AppProxyService {
    /**
     * 返回当前应用代理地址（"http://host:port" 格式），无可用代理时返回空串。
     *
     * 优先返回用户在 App 设置中填写的 [com.github.project_fredica.db.AppConfig.proxyUrl]（非空时视为覆盖）；
     * 若未配置则回落到系统代理 [internalReadNetworkProxyUrl]。
     */
    suspend fun readProxyUrl(): String {
        val cfg = runCatching { AppConfigService.repo.getConfig() }.getOrNull()
        val appProxyUrl = cfg?.proxyUrl?.takeIf { it.isNotBlank() }
        if (appProxyUrl != null) return appProxyUrl
        return AppUtil.internalReadNetworkProxyUrl()
    }
}