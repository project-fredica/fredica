package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat
import io.ktor.client.engine.ProxyConfig

actual fun AppUtil.readNetworkProxy(): ProxyConfig? = null

actual fun AppUtil.readNetworkProxyUrl(): String = ""
