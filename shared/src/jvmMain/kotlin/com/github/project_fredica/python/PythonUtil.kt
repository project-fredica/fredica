package com.github.project_fredica.python

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.JVMPlatform
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.exception
import com.github.project_fredica.apputil.getAsset
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toFixed
import com.github.project_fredica.apputil.warn
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.DurationUnit


object PythonUtil {
    private val logger = createLogger()

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
            ProcessBuilder(executablePath, *args).directory(File(pyUtilServerProjectPath)).also { pb ->
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
                    // embed Python 的 ._pth 文件不可修改。
                    // 在 Lib/site-packages 下写入 fredica_pip.pth，内容为 pipLibDir 绝对路径，
                    // site 模块启动时会扫描 site-packages 下的 .pth 文件并加入 sys.path。
//                    val sitePackagesDir = File(, "Lib/site-packages").also { it.mkdirs() }
                    val sitePackagesDir =
                        executable.parentFile.resolve("Lib").resolve("site-packages").also { it.mkdirs() }
                    val fredPthFile = sitePackagesDir.resolve("fredica_pip.pth")
                    val pipLibPath = AppUtil.Paths.pipLibDir.absolutePath
                    if (!fredPthFile.exists() || fredPthFile.readText().trim() != pipLibPath) {
                        fredPthFile.writeText(pipLibPath)
                        logger.debug("${Py314Embed::class.simpleName} wrote fredica_pip.pth -> $pipLibPath")
                    }
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
                    AppUtil.Paths.pipLibDir.mkdirs()
                    // 用 requirements.txt 内容哈希作为安装标志，哈希变化时重装
                    val reqContent = File(requirementsPath).readText()
                    val reqHash = reqContent.hashCode().toString()
                    val hashFile = AppUtil.Paths.pipLibDir.resolve(".installed_hash")
                    val installedHash = if (hashFile.exists()) hashFile.readText().trim() else ""
                    if (installedHash != reqHash) {
                        logger.debug("${Py314Embed::class.simpleName} requirements changed, running pip install")
                        runPythonSubprocess(
                            listOf(
                                "-m",
                                "pip",
                                "install",
                                "--no-input",
                                "--target",
                                AppUtil.Paths.pipLibDir.absolutePath,
                                "setuptools",
                                "wheel"
                            )
                        ).await()
                        runPythonSubprocess(
                            listOf(
                                "-m",
                                "pip",
                                "install",
                                "--no-input",
                                "--target",
                                AppUtil.Paths.pipLibDir.absolutePath,
                                "-r",
                                requirementsPath
                            )
                        ).await()
                        hashFile.writeText(reqHash)
                    } else {
                        logger.debug("${Py314Embed::class.simpleName} requirements unchanged, skip pip install")
                    }
                    logger.debug("${Py314Embed::class.simpleName} init finish")
                    _isInit = true
                }
            }
        }

        private val pipInstallMutex = Mutex()

        /** 按需安装 pip 包到 [AppUtil.Paths.pipLibDir]，并发调用时串行执行。 */
        suspend fun installPackage(packageSpec: String) {
            pipInstallMutex.withLock {
                runPythonSubprocess(
                    listOf(
                        "-m", "pip", "install", "--no-input",
                        "--target", AppUtil.Paths.pipLibDir.absolutePath,
                        packageSpec,
                    )
                ).await()
            }
        }

        object PyUtilServer {
            private val logger = createLogger()
            private val port get() = FredicaApi.DEFAULT_PYUTIL_SERVER_PORT
            private lateinit var proc: Process
            private val _PyUtilServer get() = PyUtilServer::class.simpleName

            /** Dedicated client with WebSocket plugin for task endpoints. */
            private val wsClient by lazy {
                HttpClient(OkHttp) {
                    install(WebSockets)
                }
            }

            suspend fun start() {
                if (::proc.isInitialized) {
                    logger.debug("$_PyUtilServer already start")
                    return
                }
                logger.debug("start $_PyUtilServer")
                return withContext(Dispatchers.IO) {
                    val pb = newPythonProcessBuilder(
                        "-m", "uvicorn",
                        "fredica_pyutil_server.app:app",
                        "--host", "127.0.0.1",
                        "--port", "$port",
                    ).inheritIO()
                    pb.environment()["FREDICA_DATA_DIR"] = AppUtil.Paths.appDataDir.absolutePath
                    proc = pb.start()
                    delay(1000)
                    var count = 0
                    while (count <= 10) {
                        try {
                            if (!proc.isAlive) {
                                throw IllegalStateException("failed to start $_PyUtilServer , process ${proc.pid()} unexpected die , return code ${proc.exitValue()}")
                            }
                            delay(1000)
                            try {
                                requestText(HttpMethod.Get, "/ping")
                                break
                            } catch (err: Throwable) {
                                logger.debug("failed to ping $_PyUtilServer , cause by : $err")
                            }
                        } finally {
                            count++
                        }
                    }
                    logger.debug("test ping $_PyUtilServer")
                    requestText(HttpMethod.Get, "/ping")
                    logger.debug("success ping $_PyUtilServer")
                }
            }

            suspend fun stop() {
                if (!::proc.isInitialized) {
                    logger.debug("$_PyUtilServer not start")
                    return
                }
                logger.debug("stoping $_PyUtilServer process ${proc.pid()} ")
                withContext(Dispatchers.IO) {
                    if (proc.isAlive) {
                        proc.destroy()
                        delay(3000)
                        var count = 0L
                        while (proc.isAlive) {
                            try {
                                logger.debug("waiting destroy $_PyUtilServer process ${proc.pid()} ...")
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
                logger.debug("stop $_PyUtilServer , destroy finish")
            }

            suspend fun requestText(
                method: HttpMethod,
                pth: String,
                body: String? = null,
                requestTimeoutMs: Long = 900_000L,
            ): String {
                logger.debug("request to $_PyUtilServer route ${method.value} $pth")
                val resp = AppUtil.GlobalVars.ktorClientLocal.request {
                    url {
                        protocol = URLProtocol.HTTP
                        port = this@PyUtilServer.port.toInt()
                        host = "localhost"
                        // path() 会对整个字符串做 URL 编码，导致 ? 变成 %3F。
                        // 将路径和 query string 分开处理，避免 query 被当成路径编码。
                        val qIdx = pth.indexOf('?')
                        if (qIdx < 0) {
                            path(pth)
                        } else {
                            path(pth.substring(0, qIdx))
                            pth.substring(qIdx + 1).split('&').forEach { kv ->
                                val eqIdx = kv.indexOf('=')
                                if (eqIdx < 0) parameters.append(kv, "")
                                else parameters.append(kv.substring(0, eqIdx), kv.substring(eqIdx + 1))
                            }
                        }
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
                    // 默认超时 900s（15min），由调用方通过 requestTimeoutMs 覆盖
                }
                if (resp.status.value != 200) {
                    throw IllegalStateException("failed request $_PyUtilServer , status code is ${resp.status.value} , response body : ${resp.bodyAsText()}")
                }
                val bodyText = resp.bodyAsText()
                logger.debug(
                    "response from $_PyUtilServer route ${method.value} $pth : ${
                        bodyText.let {
                            if (it.length <= 200) it else {
                                it.slice(0..200) + "..."
                            }
                        }
                    }")
                return bodyText
            }

            /**
             * 执行基于 WebSocket 的长时任务（init_param_and_run 协议）。
             *
             * @param pth              WebSocket 路径
             * @param paramJson        init_param_and_run 命令的 data 字段（JSON 字符串）
             * @param onProgress       进度回调（0-100），每次收到 progress 消息时且进度值int有变化时调用。
             * @param onPausable       可暂停状态回调；Python 端 progress 消息含 pausable 字段时调用，
             *                         true = 当前可暂停，false = 不可暂停（前端据此禁用暂停按钮）
             * @param onPauseRequest   Python 主动发起暂停的回调；收到 {"type":"pause_request"} 时调用，
             *                         参数为 reason 字符串；Executor 应在此更新 Task 的 is_paused 状态
             * @param onResumeRequest  Python 主动恢复的回调；收到 {"type":"resume_request"} 时调用；
             *                         Executor 应在此将 Task 的 is_paused 重置为 false
             * @param cancelSignal     取消信号；完成后会向 Python 端发送 {"command":"cancel"}
             * @param pauseChannel     暂停信号 Channel；收到 Unit 后发送 {"command":"pause"}
             * @param resumeChannel    恢复信号 Channel；收到 Unit 后发送 {"command":"resume"}
             * @return 成功时返回 done 消息的 JSON 字符串；被取消时返回 null
             */
            suspend fun websocketTask(
                pth: String,
                paramJson: String,
                onProgress: (suspend (Int) -> Unit)? = null,
                onProgressLine: (suspend (String) -> Unit)? = null,
                onPausable: (suspend (Boolean) -> Unit)? = null,
                onPauseRequest: (suspend (reason: String) -> Unit)? = null,
                onResumeRequest: (suspend () -> Unit)? = null,
                /** 收到框架未处理的消息类型时调用（如 "segment"），参数为原始 JSON 字符串 */
                onRawMessage: (suspend (String) -> Unit)? = null,
                cancelSignal: Deferred<Unit>? = null,
                pauseChannel: ReceiveChannel<Unit>? = null,
                resumeChannel: ReceiveChannel<Unit>? = null,
            ): String? = withContext(Dispatchers.IO) {
                logger.debug("start websocketTask to ws $pth")
                val paramJsonElement = if (paramJson.isBlank()) {
                    createJson { obj { } }
                } else {
                    paramJson.loadJsonModel<JsonObject>().also {
                        if (it.isFailure) {
                            logger.exception(
                                "Failed to parse paramJson in websocketTask , source is $paramJson",
                                it.exceptionOrNull()!!
                            )
                        }
                    }.getOrThrow()
                }
                logger.debug("success to parse json of websocketTask : $paramJsonElement")

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
                            logger.debug("websocketTask start await cancel signal")
                            sig.await()
                            logger.debug("websocketTask received an cancel signal")
                            try {
                                send(Frame.Text("""{"command":"cancel"}"""))
                                logger.debug("websocketTask sent cancel command for $pth")
                            } catch (err: Exception) { /* WebSocket 可能已关闭，可忽略 */
                                logger.debug("An error on send cancel command to websocketTask : $err")
                            }
                        }
                    }

                    // 监听暂停信号
                    val pauseJob = pauseChannel?.let { ch ->
                        launch {
                            for (unit in ch) {
                                logger.debug("websocketTask received an pause signal")
                                try {
                                    send(Frame.Text("""{"command":"pause"}"""))
                                    logger.debug("websocketTask sent pause command for $pth")
                                } catch (err: Exception) {
                                    logger.debug("An error on websocketTask send pause signal : $err")
                                }
                            }
                        }
                    }

                    // 监听恢复信号
                    val resumeJob = resumeChannel?.let { ch ->
                        launch {
                            for (unit in ch) {
                                logger.debug("websocketTask received an resume signal")
                                try {
                                    send(Frame.Text("""{"command":"resume"}"""))
                                    logger.debug("websocketTask sent resume command for $pth")
                                } catch (err: Exception) {
                                    logger.debug("An error on websocketTask send pause signal : $err")
                                }
                            }
                        }
                    }

                    try {
                        // Send init_param_and_run command
                        val initCmd = buildValidJson {
                            kv("command", "init_param_and_run")
                            kv("data", paramJsonElement)
                        }.str
                        send(Frame.Text(initCmd))
                        logger.debug("websocketTask sent init_param_and_run for $pth")
                        var lastPct = -1

                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val json = try {
                                AppUtil.GlobalVars.json.parseToJsonElement(text).jsonObject
                            } catch (err: Exception) {
                                logger.warn(
                                    "Failed parse json in websocketTask text frame data : text is $text",
                                    isHappensFrequently = true,
                                    err = err
                                )
                                continue
                            }

                            when (json["type"]?.jsonPrimitive?.content) {
                                "progress" -> {
                                    val pct = json["percent"]?.jsonPrimitive?.int ?: -1
                                    val statusText =
                                        json["statusText"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                                    statusText?.let { onProgressLine?.invoke(it) }
                                    if (onProgress !== null && pct >= 0 && lastPct != pct) {
                                        onProgress(pct)
                                        lastPct = pct
                                    }
                                    // 透传 pausable 字段：Python 端每条 progress 消息携带此字段，
                                    // 表示当前任务是否支持暂停（由 _does_support_pause() 决定）。
                                    // 缺失时不调用回调（保持上次状态），避免旧端点兼容性问题。
                                    val pausableRaw = json["pausable"]
                                    pausableRaw?.jsonPrimitive?.let { prim ->
                                        val pausable = runCatching { prim.boolean }.getOrNull()
                                        pausable?.let { onPausable?.invoke(it) }
                                    }
                                }

                                "pause_request" -> {
                                    val reason = json["reason"]?.jsonPrimitive?.content ?: ""
                                    logger.debug("websocketTask pause_request $pth : reason=$reason")
                                    onPauseRequest?.invoke(reason)
                                }

                                "resume_request" -> {
                                    logger.debug("websocketTask resume_request $pth")
                                    onResumeRequest?.invoke()
                                }

                                "done" -> {
                                    resultText = text
                                    logger.debug("websocketTask done $pth")
                                    break
                                }

                                "error" -> {
                                    errorMsg = (json["error"] ?: json["message"])?.jsonPrimitive?.content ?: "unknown error"
                                    logger.debug("websocketTask error $pth : $errorMsg")
                                    break
                                }

                                else -> {
                                    val msgType = json["type"]?.jsonPrimitive?.content
                                    if (onRawMessage != null) {
                                        onRawMessage(text)
                                    } else if (msgType !in setOf("check_result", "download_start", "segment")) {
                                        logger.warn("unexpected frame text in websocketTask : text is $text")
                                    }
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