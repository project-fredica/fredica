package com.github.project_fredica.apputil

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlin.time.Instant

object AppUtil {
    const val APP_NAME = "Fredica"

    object Paths {
        val appDataDir: File get() = File(".data").absoluteFile
        val appDataCacheDir: File get() = appDataDir.resolve("cache")
        val appDataLogDir: File get() = appDataDir.resolve("log")

        val appDbPath: File get() = appDataDir.resolve("db").resolve("fredica_app.db")
    }

    object MonkeyPatch

    object GlobalVars {
        val ktorClient = HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                }

                proxy = readNetworkProxy()
            }
        }
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

expect fun AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(src: String): String