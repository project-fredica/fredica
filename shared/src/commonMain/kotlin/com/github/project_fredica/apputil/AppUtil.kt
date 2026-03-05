package com.github.project_fredica.apputil

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.Proxy
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.Instant

/**
 * 应用核心工具单例，按职责划分为多个嵌套对象（Paths / GlobalVars / Times 等）。
 * 作为全局命名空间，集中管理文件路径、HTTP 客户端等共享资源。
 */
object AppUtil {
    const val APP_NAME = "Fredica"

    /**
     * 应用数据目录及子目录路径管理。
     * 所有路径均相对于平台特定的 appDataDir（由 actual 实现决定具体位置）。
     */
    object Paths {
        /** 内部初始化标记对象，用于约束 `detectAppDataDirOnInit` 的调用来源。 */
        object InternalInit

        /**
         * 应用数据根目录，首次访问时异步检测并创建。
         * 具体位置由平台 actual 的 [detectAppDataDirOnInit] 决定，
         * 例如 Windows 上通常为 `%APPDATA%\Fredica`。
         */
        val appDataDir: File by lazy {
            runBlocking {
                withContext(Dispatchers.IO) {
                    InternalInit.detectAppDataDirOnInit().also {
                        it.mkdirs()
                    }.absoluteFile
                }
            }
        }

        /** 通用缓存目录：`{appDataDir}/cache` */
        val appDataCacheDir: File get() = appDataDir.resolve("cache")

        /** 图片缓存目录：`{appDataDir}/cache/images`，用于缓存封面等远程图片。 */
        val appDataImageCacheDir: File get() = appDataCacheDir.resolve("images")

        /** 日志目录：`{appDataDir}/log` */
        val appDataLogDir: File get() = appDataDir.resolve("log")

        /** SQLite 数据库文件路径：`{appDataDir}/db/fredica_app.db` */
        val appDbPath: File get() = appDataDir.resolve("db").resolve("fredica_app.db")

        /** 媒体文件根目录：`{appDataDir}/media` */
        val appMediaDir: File get() = appDataDir.resolve("media")

        /**
         * 返回指定素材的媒体工作目录（首次调用时自动创建）：
         * `{appDataDir}/media/{materialId}/`
         */
        fun materialMediaDir(materialId: String): File =
            appMediaDir.resolve(materialId).also { it.mkdirs() }
    }

    /** 猴子补丁扩展函数的命名空间接收者，用于限制内部修复扩展的作用域。 */
    object MonkeyPatch

    /**
     * 全局共享资源，使用 `lazy` 延迟初始化，避免启动时不必要的资源开销。
     */
    object GlobalVars {
        private fun OkHttpClient.Builder.useAppKtorClientCommonConfig() {
            followRedirects(true)
            followSslRedirects(true)
        }

        /**
         * 读取系统网络代理配置的 Ktor HTTP 客户端，用于访问外部网络。
         * 代理配置由平台 actual 的 [AppUtil.readNetworkProxy] 提供。
         */
        val ktorClientProxied
            get() = HttpClient(OkHttp) {
                engine {
                    config {
                        useAppKtorClientCommonConfig()
                    }

                    proxy = readNetworkProxy()
                }
            }

        /**
         * 强制直连（不走代理）的 Ktor HTTP 客户端，用于访问本地服务器或局域网资源。
         * 即使系统配置了代理，此客户端也会绕过。
         */
        val ktorClientLocal by lazy {
            HttpClient(OkHttp) {
                engine {
                    config {
                        useAppKtorClientCommonConfig()
                    }

                    proxy = ProxyConfig.NO_PROXY
                }
            }
        }

        /** GlobalVars 内部初始化标记对象，用于约束特定初始化函数的调用来源。 */
        object InternalInit
    }

    /** 时间工具，封装 `kotlin.time.Clock` 以便统一替换为测试时钟。 */
    object Times {
        /** 返回当前 UTC 时刻。 */
        fun now(): Instant {
            return kotlin.time.Clock.System.now()
        }
    }

    /** 字符串工具的命名空间接收者，扩展函数通过 `AppUtil.StrUtil.xxx` 调用。 */
    object StrUtil
}

/** 读取当前平台的系统网络代理配置，返回 null 表示直连。由平台 actual 实现。 */
expect fun AppUtil.readNetworkProxy(): ProxyConfig?

/**
 * 判断此代理配置是否为直连（无代理）。
 * 通过 Kotlin Contracts 向编译器声明：返回 false 时 `this` 一定不为 null。
 */
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

/**
 * 检测并返回应用数据目录，在应用首次启动时执行一次。
 * 由平台 actual 实现。
 */
expect suspend fun AppUtil.Paths.InternalInit.detectAppDataDirOnInit(): File

/**
 * 注册应用关闭钩子，在 JVM 退出时执行 [scope] 中的清理逻辑（如关闭数据库连接）。
 * [tag] 用于日志标识，[scope] 在协程作用域内执行。
 */
expect fun AppUtil.addShutdownHook(tag: String, scope: suspend CoroutineScope.() -> Unit)