package com.github.project_fredica

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.FredicaApiJvmInitOption
import com.github.project_fredica.api.init
import com.github.project_fredica.apputil.createLogger
import dev.datlag.kcef.KCEFBuilder
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.JVMPlatform
import com.github.project_fredica.apputil.burningwaveExportAllModule
import com.github.project_fredica.apputil.exception
import com.github.project_fredica.apputil.globalVertx
import com.github.project_fredica.apputil.readNetworkProxy
import com.github.project_fredica.apputil.unsafe
import com.github.project_fredica.resources.Res
import com.github.project_fredica.resources.icon_512x512
import dev.datlag.kcef.KCEF
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import kotlin.math.max
import kotlin.system.exitProcess


fun main() {
    val appStartTime = AppUtil.Times.now()
    System.setProperty("java.net.useSystemProxies", "true");
    println("初始化中...")
    val logger = createLogger()
    logger.debug("初始化中...")
    logger.debug("logger init")

    AppUtil.MonkeyPatch.burningwaveExportAllModule()

    runBlocking(Dispatchers.IO) {
        try {
            listOf(
                async {
                    logger.debug("start read properties")
                    for (prop in System.getProperties() ?: mapOf()) {
                        val valueInfo = if (setOf("java.library.path", "java.class.path").contains(prop.key)) {
                            (prop.value as String).split(";").sortedBy { it }.joinToString("") { "\n    $it" }
                        } else {
                            prop.value
                        }
                        logger.debug("property : ${prop.key} -> $valueInfo")
                    }
                    logger.debug("finish read properties")
                    logger.debug("start read env")
                    for (env in System.getenv()) {
                        val envInfo = if (env.key.lowercase() == "path") {
                            env.value.split(";").sortedBy { it }.joinToString("") { "\n    $it" }
                        } else {
                            env.value
                        }
                        logger.debug("env : ${env.key} -> $envInfo")
                    }
                    logger.debug("finish read env")
                },
                async {
                    AppUtil.MonkeyPatch.hookWebviewBrowserDownloadKtorClientProxy()
                },
                async {
                    withContext(Dispatchers.IO) {
                        AppUtil.GlobalVars.globalVertx.setTimer(500) {
                            logger.debug("Vert.X init success")
                        }
                    }
                },
                async {
                    val unsafe = AppUtil.GlobalVars.unsafe
                    logger.debug("Unsafe is $unsafe , addressSize is ${unsafe.addressSize()}")
                }
            ).awaitAll()
            val fredicaApiOption = FredicaApiJvmInitOption()
            FredicaApi.init(
                options = fredicaApiOption
            )
        } catch (err: Throwable) {
            logger.exception("Failed init app", err)
            exitProcess(1)
        }
    }

    val nativeAssetPath = JVMPlatform.getNativeAssetPath()
    logger.debug("nativeAssetPath is $nativeAssetPath")
    val kcefBundleDir = nativeAssetPath.resolve("kcef-bundle").also { it.mkdirs() }
    val kcefCacheDir = AppUtil.Paths.appDataCacheDir.resolve("kcef-cache").also { it.mkdirs() }
    val kcefLogPath = AppUtil.Paths.appDataLogDir.resolve("kcef").also { it.mkdirs() }.resolve("kcef.log")

    application {
        val appRenderTime = AppUtil.Times.now()
        logger.debug("app launch until render cast ${appRenderTime - appStartTime}")
        Window(
            onCloseRequest = ::exitApplication,
            title = AppUtil.APP_NAME,
            icon = painterResource(Res.drawable.icon_512x512),
            onPreviewKeyEvent = {
                logger.debug("on preview key event : $it")
                return@Window false
            },
            onKeyEvent = {
                logger.debug("on key event : $it")
                return@Window false
            }) {
            var restartRequired by remember { mutableStateOf(false) }
            var downloading by remember { mutableStateOf(0F) }
            var initialized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    logger.debug("start init KCEF browser")
                    KCEF.init(builder = {
                        installDir(kcefBundleDir)
                        progress {
                            onDownloading {
                                downloading = max(it, 0F)
                            }
                            onInitialized {
                                initialized = true
                            }
                        }
                        settings {
                            cachePath = kcefCacheDir.toString()
                            logFile = kcefLogPath.toString()
                            logSeverity = KCEFBuilder.Settings.LogSeverity.Default
                        }
                    }, onError = {
                        if (it !== null) {
                            logger.exception("Error on KCEF init", it)
                            throw it
                        } else {
                            logger.error("Error on KCEF init , but missing exception")
                            throw IllegalStateException("Error when KCEF init")
                        }
                    }, onRestartRequired = {
                        logger.warn("KCEF require restart")
                        restartRequired = true
                    })
                }
            }

            if (restartRequired) {
                Text(text = "Restart required.")
            } else {
                if (initialized) {
                    App()
                } else {
                    Text(text = "Downloading $downloading%")
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    logger.debug("window dispose , start dispose KCEF browser")
                    KCEF.disposeBlocking()
                    logger.debug("KCEF browser disposed")
                }
            }
        }
    }
}


@Suppress("UnusedReceiverParameter")
fun AppUtil.MonkeyPatch.hookWebviewBrowserDownloadKtorClientProxy() {
    val f = KCEFBuilder.Download.Builder.GitHub::class.java.getDeclaredField("client")
    f.isAccessible = true
    val c = f.get(null) as HttpClient
    c.engine.config.proxy = AppUtil.readNetworkProxy()
}