package com.github.project_fredica.apputil

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Proxy
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.Instant

object AppUtil {
    const val APP_NAME = "Fredica"

    object Paths {
        object InternalInit

        val appDataDir: File by lazy {
            runBlocking {
                withContext(Dispatchers.IO) {
                    InternalInit.detectAppDataDirOnInit().also {
                        it.mkdirs()
                    }.absoluteFile
                }
            }
        }
        val appDataCacheDir: File get() = appDataDir.resolve("cache")
        val appDataLogDir: File get() = appDataDir.resolve("log")

        val appDbPath: File get() = appDataDir.resolve("db").resolve("fredica_app.db")
    }

    object MonkeyPatch

    object GlobalVars {
        val ktorClient by lazy {
            HttpClient(OkHttp) {
                engine {
                    config {
                        followRedirects(true)
                    }

                    proxy = readNetworkProxy()
                }
            }
        }

        object InternalInit
    }

    object Times {
        fun now(): Instant {
            return kotlin.time.Clock.System.now()
        }
    }

    object StrUtil {

    }
}

expect fun AppUtil.readNetworkProxy(): ProxyConfig?

@OptIn(ExperimentalContracts::class)
fun ProxyConfig?.isDirect(): Boolean {
    contract {
        returns(false) implies (this@isDirect != null)
    }
    if (this == null) {
        return true
    }
    return this.type() == Proxy.Type.DIRECT
}

expect fun AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(src: String): String

expect suspend fun AppUtil.Paths.InternalInit.detectAppDataDirOnInit(): File