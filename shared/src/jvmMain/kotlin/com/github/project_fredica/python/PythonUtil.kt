package com.github.project_fredica.python

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.JVMPlatform
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.getAsset
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toFixed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        /** Creates a ProcessBuilder for the embedded Python with UTF-8 forced on Windows. */
        private fun newPythonProcessBuilder(vararg args: String): ProcessBuilder =
            ProcessBuilder(executablePath, *args)
                .directory(File(pyUtilServerProjectPath))
                .also { pb ->
                    pb.environment()["PYTHONIOENCODING"] = "utf-8"
                    pb.environment()["PYTHONUTF8"] = "1"
                }

        private suspend fun runPythonSubprocess(
            args: List<String>, timeoutMs: Long? = null, check: Boolean = true
        ): Deferred<Unit> {
            return withContext(Dispatchers.IO) {
                val startAt = AppUtil.Times.now()
                val p = newPythonProcessBuilder(*args.toTypedArray()).inheritIO().start()
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

            /** Dedicated client with WebSocket plugin for task endpoints. */
            private val wsClient by lazy {
                HttpClient(OkHttp) {
                    install(WebSockets)
                }
            }

            suspend fun start() {
                if (::proc.isInitialized) {
                    logger.debug("$_name already start")
                    return
                }
                logger.debug("start $_name")
                return withContext(Dispatchers.IO) {
                    proc = newPythonProcessBuilder(
                        "-m", "uvicorn",
                        "fredica_pyutil_server.app:app",
                        "--host", "127.0.0.1",
                        "--port", "$port",
                    ).inheritIO().start()
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


            suspend inline fun <reified T> requestModel(
                method: HttpMethod,
                pth: String,
                body: String? = null,
                requestTimeoutMs: Long = 60_000L,
            ): T {
                val text = requestText(method, pth, body, requestTimeoutMs)
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

            suspend fun requestText(
                method: HttpMethod,
                pth: String,
                body: String? = null,
                requestTimeoutMs: Long = 60_000L,
            ): String {
                logger.debug("request to $_name route ${method.value} $pth")
                val resp = AppUtil.GlobalVars.ktorClientLocal.request {
                    url {
                        protocol = URLProtocol.HTTP
                        port = this@PyUtilServer.port.toInt()
                        host = "localhost"
                        path(pth)
                    }
                    this.method = method
                    contentType(ContentType.Application.Json)
                    if (body != null) {
                        setBody(body)
                    }
                    timeout {
                        this.connectTimeoutMillis = 60_000
                        this.requestTimeoutMillis = requestTimeoutMs
                        this.socketTimeoutMillis = requestTimeoutMs
                    }
                }
                if (resp.status.value != 200) {
                    throw IllegalStateException("failed request $_name , status code is ${resp.status.value} , response body : ${resp.bodyAsText()}")
                }
                val bodyText = resp.bodyAsText()
                logger.debug(
                    "response from $_name route ${method.value} $pth : ${
                        bodyText.let {
                            if (it.length <= 100) it else {
                                it.slice(0..100) + "..."
                            }
                        }
                    }"
                )
                return bodyText
            }

            /**
             * 执行基于 WebSocket 的长时任务（init_param_and_run 协议）。
             *
             * @param pth           WebSocket 路径
             * @param paramJson     init_param_and_run 命令的 data 字段（JSON 字符串）
             * @param onProgress    进度回调（0-100），每次收到 progress 消息时调用
             * @param cancelSignal  取消信号；完成后会向 Python 端发送 {"command":"cancel"}
             * @param pauseChannel  暂停信号 Channel；收到 Unit 后发送 {"command":"pause"}
             * @param resumeChannel 恢复信号 Channel；收到 Unit 后发送 {"command":"resume"}
             * @return 成功时返回 done 消息的 JSON 字符串；被取消时返回 null
             */
            suspend fun websocketTask(
                pth: String,
                paramJson: String,
                onProgress: (suspend (Int) -> Unit)? = null,
                cancelSignal: Deferred<Unit>? = null,
                pauseChannel: ReceiveChannel<Unit>? = null,
                resumeChannel: ReceiveChannel<Unit>? = null,
            ): String? = withContext(Dispatchers.IO) {
                logger.debug("websocketTask to $_name ws $pth")
                var resultText: String? = null
                var errorMsg: String? = null

                wsClient.webSocket(
                    method = HttpMethod.Get,
                    host = "localhost",
                    port = this@PyUtilServer.port.toInt(),
                    path = pth,
                ) {
                    // 监听取消信号，一旦触发就向 Python 端发送 cancel 命令
                    val cancelJob = cancelSignal?.let { sig ->
                        launch {
                            sig.await()
                            try {
                                send(Frame.Text("""{"command":"cancel"}"""))
                                logger.debug("websocketTask sent cancel command for $pth")
                            } catch (_: Exception) { /* WebSocket 可能已关闭，忽略 */ }
                        }
                    }

                    // 监听暂停信号
                    val pauseJob = pauseChannel?.let { ch ->
                        launch {
                            for (unit in ch) {
                                try {
                                    send(Frame.Text("""{"command":"pause"}"""))
                                    logger.debug("websocketTask sent pause command for $pth")
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    // 监听恢复信号
                    val resumeJob = resumeChannel?.let { ch ->
                        launch {
                            for (unit in ch) {
                                try {
                                    send(Frame.Text("""{"command":"resume"}"""))
                                    logger.debug("websocketTask sent resume command for $pth")
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    try {
                        // Send init_param_and_run command
                        val initCmd = """{"command":"init_param_and_run","data":$paramJson}"""
                        send(Frame.Text(initCmd))
                        logger.debug("websocketTask sent init_param_and_run for $pth")
                        var lastPct = -1

                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val json = try {
                                AppUtil.GlobalVars.json.parseToJsonElement(text).jsonObject
                            } catch (_: Exception) {
                                continue
                            }

                            when (json["type"]?.jsonPrimitive?.content) {
                                "progress" -> {
                                    val pct = json["percent"]?.jsonPrimitive?.int ?: 0
                                    if (onProgress !== null && lastPct != pct) {
                                        onProgress(pct)
                                        lastPct = pct
                                    }
                                }

                                "done" -> {
                                    resultText = text
                                    logger.debug("websocketTask done $pth")
                                    break
                                }

                                "error" -> {
                                    errorMsg = json["message"]?.jsonPrimitive?.content ?: "unknown error"
                                    logger.debug("websocketTask error $pth : $errorMsg")
                                    break
                                }
                            }
                        }
                    } finally {
                        cancelJob?.cancel()
                        pauseJob?.cancel()
                        resumeJob?.cancel()
                    }
                }

                // 优先返回已完成的结果
                if (resultText != null) return@withContext resultText
                // 若取消信号已触发，返回 null 表示已取消
                if (cancelSignal?.isCompleted == true) {
                    logger.info("websocketTask cancelled for $pth")
                    return@withContext null
                }
                if (errorMsg != null) throw IllegalStateException("WebSocket task failed: $errorMsg")
                throw IllegalStateException("WebSocket task ended without a result")
            }
        }
    }
}