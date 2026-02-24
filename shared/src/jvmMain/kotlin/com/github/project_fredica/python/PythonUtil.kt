package com.github.project_fredica.python

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.JVMPlatform
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.getAsset
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toFixed
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.path
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.DurationUnit


object PythonUtil {


    object Py314Embed {
        private val logger = createLogger()

        val isInit get() = _isInit

        private var _isInit: Boolean = false
        private val initLock = Mutex()

        private lateinit var executablePath: String
        private lateinit var requirementsPath: String

        private val pyUtilServerProjectPath: String get() = File(requirementsPath).parent

        private suspend fun runPythonSubprocess(
            args: List<String>, timeoutMs: Long? = null, check: Boolean = true
        ): Deferred<Unit> {
            return withContext(Dispatchers.IO) {
                val startAt = AppUtil.Times.now()
                val p = ProcessBuilder().directory(File(pyUtilServerProjectPath))
                    .command(executablePath, *args.toTypedArray()).inheritIO().start()
                logger.debug("start python subprocess , pid is ${p.pid()} , args is $args")
                return@withContext async {
                    val isNotTimeout = if (timeoutMs == null || timeoutMs <= 0) {
                        p.waitFor()
                        true
                    } else {
                        p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    }
                    val endAt = AppUtil.Times.now()
                    logger.debug(
                        "finish python subprocess , pid is ${p.pid()} , exitValue is ${p.exitValue()} , castTime is ${
                            ((endAt - startAt).toLong(
                                DurationUnit.MILLISECONDS
                            ) / 1000.0).toFixed(2)
                        }s , args is $args"
                    )
                    if (!isNotTimeout) {
                        throw TimeoutException("python subprocess timeout , pid is ${p.pid()} , args is $args")
                    }
                    if (check) {
                        if (p.exitValue() != 0) {
                            throw IllegalStateException("python subprocess return not zero , pid is ${p.pid()} , exitValue is ${p.exitValue()} , args is $args")
                        }
                    }
                }
            }
        }

        suspend fun init() {
            initLock.withLock {
                if (_isInit) {
                    logger.debug("${Py314Embed::class.simpleName} is already init")
                    return
                }
                logger.debug("${Py314Embed::class.simpleName} init start")
                withContext(Dispatchers.IO) {
                    val executable =
                        JVMPlatform.getAsset("lfs", "python-314-embed", "python.exe", autoCastSuffix = true)
                    if (executable == null || !executable.isFile) {
                        throw IllegalStateException("bundle python not found")
                    }
                    executablePath = executable.absolutePath
                    logger.debug("${Py314Embed::class.simpleName} executablePath is : $executablePath")
                    val requirements =
                        JVMPlatform.getAsset("fredica-pyutil", "requirements.txt", autoCastSuffix = false)
                    if (requirements == null || !requirements.isFile) {
                        throw IllegalStateException("bundle requirements.txt not found")
                    }
                    requirementsPath = requirements.absolutePath
                    logger.debug("${Py314Embed::class.simpleName} requirements.txt content is :\n${File(requirementsPath).readText()}")
                    runPythonSubprocess(
                        listOf("-V"),
                    ).await()
                    runPythonSubprocess(
                        listOf("-m", "pip", "-V")
                    ).await()
                    runPythonSubprocess(
                        listOf("-m", "pip", "install", "--no-input", "-r", requirementsPath)
                    ).await()
                    logger.debug("${Py314Embed::class.simpleName} init finish")
                    _isInit = true
                }
            }
        }

        object PyUtilServer {
            private val logger = createLogger()
            private val port get() = FredicaApi.DEFAULT_PYUTIL_SERVER_PORT
            private lateinit var proc: Process
            private val _name get() = PyUtilServer::class.simpleName

            suspend fun start() {
                if (::proc.isInitialized) {
                    logger.debug("$_name already start")
                    return
                }
                logger.debug("start $_name")
                return withContext(Dispatchers.IO) {
                    proc = ProcessBuilder().directory(File(pyUtilServerProjectPath)).inheritIO().command(
                        executablePath,
                        "-m",
                        "uvicorn",
                        "fredica_pyutil_server.app:app",
                        "--host",
                        "127.0.0.1",
                        "--port",
                        "$port",
                    ).start()
                    delay(1000)
                    var count = 0
                    while (count <= 10) {
                        try {
                            if (!proc.isAlive) {
                                throw IllegalStateException("failed to start $_name , process ${proc.pid()} unexpected die , return code ${proc.exitValue()}")
                            }
                            delay(1000)
                            try {
                                requestText(HttpMethod.Get, "/ping")
                                break
                            } catch (err: Throwable) {
                                logger.debug("failed to ping $_name , cause by : $err")
                            }
                        } finally {
                            count++
                        }
                    }
                    logger.debug("test ping $_name")
                    requestText(HttpMethod.Get, "/ping")
                    logger.debug("success ping $_name")
                }
            }

            suspend fun stop() {
                if (!::proc.isInitialized) {
                    logger.debug("$_name not start")
                    return
                }
                logger.debug("stoping $_name process ${proc.pid()} ")
                withContext(Dispatchers.IO) {
                    if (proc.isAlive) {
                        proc.destroy()
                        delay(3000)
                        var count = 0L
                        while (proc.isAlive) {
                            try {
                                logger.debug("waiting destroy $_name process ${proc.pid()} ...")
                                delay(1000 + count * 500)
                                if (count >= 5) {
                                    proc.destroyForcibly()
                                }
                            } finally {
                                count++
                            }
                        }
                    }
                }
                logger.debug("stop $_name , destroy finish")
            }


            suspend inline fun <reified T> requestModel(method: HttpMethod, p: String): T {
                val text = requestText(method, p)
                try {
                    val model = text.loadJsonModel<T>().getOrThrow()
                    return model
                } catch (err: Throwable) {
                    throw IllegalStateException(
                        "failed parse json from ${PyUtilServer::class.simpleName} response , source text is : $text",
                        err
                    )
                }
            }

            suspend fun requestText(method: HttpMethod, p: String): String {
                logger.debug("request to $_name route ${method.value} $p")
                val resp = AppUtil.GlobalVars.ktorClientLocal.request {
                    url {
                        protocol = URLProtocol.HTTP
                        port = this@PyUtilServer.port.toInt()
                        host = "localhost"
                        path(p)
                    }
                    this.method = method
                    contentType(ContentType.Application.Json)
                    timeout {
                        this.connectTimeoutMillis = 60000
                        this.requestTimeoutMillis = 60000
                        this.socketTimeoutMillis = 60000
                    }
                }
                if (resp.status.value != 200) {
                    throw IllegalStateException("failed request $_name , status code is ${resp.status.value} , response body : ${resp.bodyAsText()}")
                }
                val bodyText = resp.bodyAsText()
                logger.debug(
                    "response from $_name route ${method.value} $p : ${
                        bodyText.let {
                            if (it.length <= 100) it else {
                                it.slice(0..100) + "..."
                            }
                        }
                    }"
                )
                return bodyText
            }
        }
    }
}