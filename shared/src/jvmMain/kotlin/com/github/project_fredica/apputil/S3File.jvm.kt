package com.github.project_fredica.apputil

import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.http.engine.ProxySelector
import java.net.Proxy


actual fun S3File.Companion.createHttpClientEngine(): HttpClientEngine? {
    val logger = createLogger()
    return aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine {
        this.proxySelector = ProxySelector { url ->
            logger.debug("select s3 client proxy for url : $url")
            val proxy = AppUtil.readNetworkProxy()
            if (proxy.isDirect()) {
                logger.debug("s3 proxy is direct")
                return@ProxySelector aws.smithy.kotlin.runtime.http.engine.ProxyConfig.Direct
            }
            if (proxy.type() !== Proxy.Type.HTTP) {
                logger.info("S3Client HttpClientEngine unsupported proxy type ${proxy.type()} , use Direct")
                return@ProxySelector aws.smithy.kotlin.runtime.http.engine.ProxyConfig.Direct
            }
            val addr = "${proxy.address()}"
            logger.debug("proxy address is $addr")
            return@ProxySelector aws.smithy.kotlin.runtime.http.engine.ProxyConfig.Http(addr)
        }
    }
}