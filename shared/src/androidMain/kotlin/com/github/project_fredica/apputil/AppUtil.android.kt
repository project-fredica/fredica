package com.github.project_fredica.apputil

import com.google.common.base.CaseFormat
import io.ktor.client.engine.ProxyConfig

actual fun AppUtil.internalReadNetworkProxy(): ProxyConfig? = null

actual fun AppUtil.internalReadNetworkProxyUrl(): String = ""
