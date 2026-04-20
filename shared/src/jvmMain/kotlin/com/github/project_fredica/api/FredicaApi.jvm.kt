package com.github.project_fredica.api

import com.github.project_fredica.api.FredicaApiJvmService.init
import com.github.project_fredica.api.routes.*
import com.github.project_fredica.apputil.*
import com.github.project_fredica.apputil.internalReadNetworkProxy
import com.github.project_fredica.asr.service.BilibiliSubtitleBodyCacheService
import com.github.project_fredica.asr.service.BilibiliSubtitleMetaCacheService
import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountInfoDb
import com.github.project_fredica.bilibili_account_pool.db.BilibiliAccountPoolDb
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountInfoService
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import com.github.project_fredica.auth.*
import com.github.project_fredica.db.*
import com.github.project_fredica.db.weben.*
import com.github.project_fredica.llm.LlmRequestServiceHolder
import com.github.project_fredica.llm.LlmRequestServiceImpl
import com.github.project_fredica.material_category.db.MaterialCategoryAuditLogDb
import com.github.project_fredica.material_category.db.MaterialCategoryDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncItemDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncPlatformInfoDb
import com.github.project_fredica.material_category.db.MaterialCategorySyncUserConfigDb
import com.github.project_fredica.material_category.service.MaterialCategoryAuditLogService
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncItemService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.material_category.service.MaterialCategorySyncUserConfigService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.TaskCancelService
import com.github.project_fredica.worker.WorkerEngine
import com.github.project_fredica.asr.executor.AsrSpawnChunksExecutor
import com.github.project_fredica.asr.executor.TranscribeExecutor
import com.github.project_fredica.worker.executors.*
import inet.ipaddr.AddressStringException
import inet.ipaddr.IPAddressString
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ktorm.database.Database


@Serializable
data class FredicaApiJvmInitOption(
    val ktorServerHost: String = "localhost",
    val ktorServerPort: UShort = FredicaApi.DEFAULT_KTOR_SERVER_PORT,
    val localhostWebUIPort: UShort = FredicaApi.DEFAULT_DEV_WEBUI_PORT,
) {
    fun ktorServerAllowHosts() = listOf("localhost:$localhostWebUIPort")

    fun getLocalDomain(): String {
        val logger = createLogger()
        if (ktorServerHost == "localhost") {
            return "localhost"
        }
        return try {
            val addr = IPAddressString(ktorServerHost).toAddress()
            if (addr.isLoopback or addr.isAnyLocal) {
                "localhost"
            } else {
                addr.toString()
            }
        } catch (e: AddressStringException) {
            logger.error("Failed to cast $ktorServerHost to address")
            throw e
        }
    }
}


actual suspend fun FredicaApi.Companion.init(options: Any?) {
    val o = if (options == null) FredicaApiJvmInitOption() else options as FredicaApiJvmInitOption
    FredicaApiJvmService.init(o)
}

actual suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route> {
    return listOf(
        TorchInstallCheckRoute,
        WebenConceptTypeHintsRoute,
        LlmProxyChatRoute,
        PromptTemplateRunRoute,
        PromptTemplatePreviewRoute,
        PromptScriptGenerateRoute,
    )
}

actual suspend fun FredicaApi.Companion.getNativeWebServerLocalDomainAndPort(): Pair<String, UShort>? {
    val logger = createLogger()
    val r = Pair(
        FredicaApiJvmService.CurrentInstanceHandler.initOption.getLocalDomain(),
        FredicaApiJvmService.CurrentInstanceHandler.getLocalPort(),
    )
    logger.debug("native web server local domain and port is : $r")
    return r
}

actual suspend fun FredicaApi.PyUtil.get(path: String): String {
    return PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, path)
}

actual suspend fun FredicaApi.PyUtil.post(path: String, body: String, timeoutMs: Long): String {
    return PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Post, path, body, timeoutMs)
}

object FredicaApiJvmService {

    object CurrentInstanceHandler {
        lateinit var initOption: FredicaApiJvmInitOption
        lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        lateinit var engineScope: kotlinx.coroutines.CoroutineScope

        fun getLocalPort(): UShort = initOption.ktorServerPort
    }

    suspend fun init(option: FredicaApiJvmInitOption) {
        val logger = createLogger()
        CurrentInstanceHandler.initOption = option

        // Initialize SQLite database and service layers
        withContext(Dispatchers.IO) {
            val dbPath = AppUtil.Paths.appDbPath.also { it.parentFile.mkdirs() }
            val database = Database.connect(
                url = "jdbc:sqlite:${dbPath.absolutePath}",
                driver = "org.sqlite.JDBC",
            )

            // AppConfig（最先，其他服务可能依赖配置）
            AppConfigDb(database).also { it.initialize(); AppConfigService.initialize(it) }
                .let { logger.debug("AppConfigService initialized, db path: $dbPath") }
            // 预热 webserver_auth_token 缓存（避免 resolveIdentity 首次读 SQLite）
            WebserverAuthTokenCache.init(AppConfigService.repo.getConfig().webserverAuthToken)
            // Auth（user 表先于 auth_session，因外键引用）
            UserDb(database).also        { it.initialize(); UserService.initialize(it) }
            AuthSessionDb(database).also { it.initialize(); AuthSessionService.initialize(it) }
            AuditLogDb(database).also    { it.initialize(); AuditLogService.initialize(it) }
            // 邀请链接
            GuestInviteLinkDb(database).also  { it.initialize(); GuestInviteLinkService.initialize(it) }
            GuestInviteVisitDb(database).also { it.initialize(); GuestInviteVisitService.initialize(it) }
            TenantInviteLinkDb(database).also { it.initialize(); TenantInviteLinkService.initialize(it) }
            TenantInviteRegistrationDb(database).also { it.initialize(); TenantInviteRegistrationService.initialize(it) }
            // Material（顺序有依赖：base → video → category → task）
            MaterialDb(database).also          { it.initialize(); MaterialService.initialize(it) }
            MaterialVideoDb(database).also     { it.initialize(); MaterialVideoService.initialize(it) }
            MaterialCategoryDb(database).also  { it.initialize(); MaterialCategoryService.initialize(it) }
            MaterialCategorySyncPlatformInfoDb(database).also { MaterialCategorySyncPlatformInfoService.initialize(it) }
            MaterialCategorySyncUserConfigDb(database).also   { MaterialCategorySyncUserConfigService.initialize(it) }
            MaterialCategorySyncItemDb(database).also         { MaterialCategorySyncItemService.initialize(it) }
            MaterialCategoryAuditLogDb(database).also         { MaterialCategoryAuditLogService.initialize(it) }
            MaterialTaskDb(database).also      { it.initialize(); MaterialTaskService.initialize(it) }
            // Worker 任务队列
            TaskDb(database).also              { it.initialize(); TaskService.initialize(it) }
            WorkflowRunDb(database).also       { it.initialize(); WorkflowRunService.initialize(it) }
            RestartTaskLogDb(database).also    { it.initialize(); RestartTaskLogService.initialize(it) }
            // Torch 镜像缓存
            TorchMirrorCacheDb(database).also { db ->
                db.initialize()
                TorchMirrorCacheService.initialize(db)
                TorchMirrorVersionsCacheService.initialize(db)
            }
            // Bilibili 缓存
            BilibiliAiConclusionCacheDb(database).also  { it.initialize(); BilibiliAiConclusionCacheService.initialize(it) }
            BilibiliSubtitleMetaCacheDb(database).also  { it.initialize(); BilibiliSubtitleMetaCacheService.initialize(it) }
            BilibiliSubtitleBodyCacheDb(database).also  { it.initialize(); BilibiliSubtitleBodyCacheService.initialize(it) }
            // Bilibili 账号池
            BilibiliAccountPoolDb(database).also { it.initialize(); BilibiliAccountPoolService.initialize(it) }
            BilibiliAccountInfoDb(database).also { it.initialize(); BilibiliAccountInfoService.initialize(it) }
            // LLM 响应缓存
            LlmResponseCacheDb(database).also { it.initialize(); LlmResponseCacheService.initialize(it) }
            // Weben 知识图谱
            WebenSourceDb(database).also         { it.initialize(); WebenSourceService.initialize(it) }
            WebenConceptDb(database).also        { it.initialize(); WebenConceptService.initialize(it) }
            WebenExtractionRunDb(database).also  { it.initialize(); WebenExtractionRunService.initialize(it) }
            WebenSegmentDb(database).also        { it.initialize(); WebenSegmentService.initialize(it) }
            WebenNoteDb(database).also           { it.initialize(); WebenNoteService.initialize(it) }
            // Prompt 脚本模板
            PromptTemplateDb(database).also { it.initialize(); PromptTemplateService.initialize(it) }

            logger.debug("All DB services initialized")
        }

        // LlmRequestService（依赖 LlmResponseCacheService，必须在 DB 初始化后）
        LlmRequestServiceHolder.instance = LlmRequestServiceImpl()

        // AuthService / LoginRateLimiter（commonMain 路由通过 Holder 访问）
        AuthServiceHolder.initialize(AuthService)
        LoginRateLimiterHolder.initialize(LoginRateLimiter)

        CurrentInstanceHandler.server = embeddedServer(
            Netty, port = option.ktorServerPort.toInt(), host = option.ktorServerHost, module = {
                initModule(option)
            })
        logger.debug("server start suspend start")
        CurrentInstanceHandler.server.startSuspend(wait = false)
        logger.debug("server start suspend finish")

        // 启动异步任务引擎，显式传入全部 executor（JVM 平台特有的 executor 在此注册）。
        // WorkerEngine 位于 commonMain，defaultExecutors 仅含纯 Kotlin 的通用 executor；
        // 依赖 ProcessBuilder / PythonUtil 的 JVM executor 在此处补充传入。
        val engineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        CurrentInstanceHandler.engineScope = engineScope
        WorkerEngine.start(
            maxWorkers = 32,
            scope = engineScope,
            executors = listOf(
                NetworkTestExecutor,
                DownloadBilibiliVideoExecutor,
                TranscodeMp4Executor,
                ExtractAudioExecutor,
                AsrSpawnChunksExecutor,
                TranscribeExecutor,
                DownloadTorchExecutor,
                MaterialCategorySyncBilibiliFavoriteExecutor,
                MaterialCategorySyncBilibiliUploaderExecutor,
                MaterialCategorySyncBilibiliSeasonExecutor,
                MaterialCategorySyncBilibiliSeriesExecutor,
                MaterialCategorySyncBilibiliVideoPagesExecutor,
            ),
        )
        logger.debug("WorkerEngine started")

        // 启动后异步触发设备检测（不阻塞启动流程）
        engineScope.launch {
            try {
                _runStartupDeviceDetect()
            } catch (e: Throwable) {
                logger.warn("[startup] device detect launch failed", isHappensFrequently = false, err = e)
            }
        }

        // 注册前置清理：关闭 Python 服务 / 数据库等 shutdown hook 之前，
        // 先取消所有正在运行的任务，等它们离开 running 状态后再释放锁。
        AppUtil.setPreShutdownCleanup {
            logger.info("[preShutdownCleanup] 开始取消正在运行的任务…")
            val runningTasks = TaskService.repo.listAll(status = "running", pageSize = 200).items

            for (task in runningTasks) {
                val cancelled = TaskCancelService.cancel(task.id)
                logger.info("[preShutdownCleanup] 任务 ${task.id} (${task.type}) 发送取消信号：signalled=$cancelled")
            }

            if (runningTasks.isNotEmpty()) {
                // 等待所有 running 任务结束（最多 60s）
                val deadlineMs = System.currentTimeMillis() + 60_000L
                while (System.currentTimeMillis() < deadlineMs) {
                    val stillRunning = TaskService.repo.listAll(status = "running", pageSize = 200).items
                    if (stillRunning.isEmpty()) break
                    logger.debug("[preShutdownCleanup] 等待 ${stillRunning.size} 个任务结束…")
                    delay(300)
                }
            }
            logger.info("[preShutdownCleanup] 任务清理完成，释放前置清理锁")
        }
    }


    @Suppress("FunctionName")
    private suspend fun _runStartupDeviceDetect() {
        val logger = createLogger()

        // 等待 Python 服务就绪（最多 60 秒，每 3 秒重试）
        val deadlineMs = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, "/ping")
                break
            } catch (_: Throwable) {
                delay(3000)
            }
        }
        try {
            val resultText = PythonUtil.Py314Embed.PyUtilServer.requestText(
                HttpMethod.Post, "/device/detect", requestTimeoutMs = 120_000L
            )
            val parsed = AppUtil.GlobalVars.json.parseToJsonElement(resultText).jsonObject
            val deviceInfoJson = parsed["device_info_json"]?.toString() ?: ""
            val ffmpegProbeJson = parsed["ffmpeg_probe_json"]?.toString() ?: ""

            val ffmpegProbeElement = parsed["ffmpeg_probe_json"]?.jsonObject
            val probeFound = ffmpegProbeElement?.get("found")?.jsonPrimitive?.booleanOrNull ?: false
            val probePath = ffmpegProbeElement?.get("path")?.jsonPrimitive?.content ?: ""

            val config = AppConfigService.repo.getConfig()
            val autoFillPath = config.ffmpegPath.isEmpty() && probeFound && probePath.isNotEmpty()
            AppConfigService.repo.updateConfig(
                config.copy(
                    deviceInfoJson = deviceInfoJson,
                    ffmpegProbeJson = ffmpegProbeJson,
                    ffmpegPath = if (autoFillPath) probePath else config.ffmpegPath,
                )
            )
            if (autoFillPath) {
                logger.info("[startup] ffmpegPath auto-filled: $probePath")
            }
            logger.info("[startup] device detect complete, ffmpegProbeJson saved")
        } catch (e: Throwable) {
            logger.warn("[startup] device detect failed", isHappensFrequently = false, err = e)
        }

        // torch 版本探测（异步，不阻塞启动）
        try {
            val torchResult = PythonUtil.Py314Embed.PyUtilServer.requestText(
                HttpMethod.Post, "/torch/resolve-spec", requestTimeoutMs = 900_000L
            )
            val torchJson = AppUtil.GlobalVars.json.parseToJsonElement(torchResult).jsonObject
            val recommendedVariant = torchJson["recommended_variant"]?.jsonPrimitive?.content ?: ""
            val config2 = AppConfigService.repo.getConfig()
            AppConfigService.repo.updateConfig(config2.copy(
                torchRecommendedVariant = recommendedVariant,
                torchRecommendationJson = torchResult,
            ))
            logger.info("[startup] torch resolve-spec complete, recommended=$recommendedVariant")
        } catch (e: Throwable) {
            logger.warn("[startup] torch resolve-spec failed", isHappensFrequently = false, err = e)
        }
    }

    /**
     * 在 APP 完全就绪（KCEF 初始化完成）后调用，启动后台对账任务。
     *
     * 必须在 [init] 之后调用（依赖 engineScope 和各服务已初始化）。
     * 为非挂起函数，直接在 KCEF 的 onInitialized 回调中调用即可。
     */
    fun onAppReady() {
        val logger = createLogger()
        logger.info("[onAppReady] KCEF 初始化完成，启动后台对账任务…")
        CurrentInstanceHandler.engineScope.launch(Dispatchers.IO) {
            try {
                WebenSourceListRoute.startupReconcileAll()
            } catch (e: Throwable) {
                logger.warn("[onAppReady] startupReconcileAll failed", isHappensFrequently = false, err = e)
            }
        }
        CurrentInstanceHandler.engineScope.launch(Dispatchers.IO) {
            try {
                MaterialCategoryService.repo.reconcileAllOrphanMaterials()
            } catch (e: Throwable) {
                logger.warn("[onAppReady] reconcileAllOrphanMaterials failed", isHappensFrequently = false, err = e)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun Application.initModule(
        option: FredicaApiJvmInitOption
    ) {
        install(ContentNegotiation) {
            json()
        }
        install(PartialContent)
        install(CORS) {
            option.ktorServerAllowHosts().forEach {
                allowHost(it)
            }
            allowCredentials = true
            arrayOf(
                HttpHeaders.ContentType,
                HttpHeaders.Authorization,
            ).forEach {
                allowHeader(it)
            }
            HttpMethod.DefaultMethods.forEach {
                allowMethod(it)
            }
        }
        initRoute()
    }

    private suspend fun Application.initRoute() {
        val logger = createLogger()
        val allRoutes = FredicaApi.getAllRoutes()

        suspend fun RoutingContext.handleRouteResult(route: FredicaApi.Route, scope: suspend () -> Any) {
            try {
                when (val result = scope()) {
                    is ImageProxyResponse -> {
                        call.response.headers.append(
                            HttpHeaders.CacheControl, "public, max-age=31536000, immutable"
                        )
                        call.respondBytes(result.bytes, ContentType.parse(result.contentType))
                    }

                    is ValidJsonString -> call.respondText(result.str, ContentType.Application.Json)
                    else -> call.respond(result)
                }
            } catch (err: Throwable) {
                logger.error("Failed in route ${route.name}", err)
                // 检测 SocketException + 系统代理组合，附加修复建议字段供前端 toast 提示。
                // 根本原因：Clash HTTP 模式下 OkHttp 发送 CONNECT 隧道请求被中止（WSAECONNABORTED）。
                // 详细诊断过程与修复方案见 LlmProxyDebugTest。
                val hasProxy = AppUtil.internalReadNetworkProxy() != null
                val fixAdvice = if (hasProxy &&
                    (err.message?.contains("中止了一个已建立的连接") == true ||
                     err.message?.contains("connection aborted", ignoreCase = true) == true ||
                     err.message?.contains("WSAECONNABORTED", ignoreCase = true) == true ||
                     err.cause?.message?.contains("中止了一个已建立的连接") == true)
                ) "OpenClashPAC" else null
                val errorJson = buildJsonObject {
                    put("error", err.message ?: err::class.simpleName ?: "unknown")
                    if (fixAdvice != null) put("FredicaFixBugAdvice", fixAdvice)
                }
                call.respondText(errorJson.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        suspend fun RoutingContext.handleRoute(route: FredicaApi.Route, getBody: suspend RoutingContext.() -> String) {
            if (route.requiresAuth && !checkAuth()) return
            val identity = call.attributes.getOrNull(AuthIdentityKey)
            // 角色检查
            if (route.minRole != AuthRole.GUEST) {
                val roleLevel = when (identity) {
                    is AuthIdentity.Authenticated -> identity.role.ordinal
                    else -> AuthRole.GUEST.ordinal
                }
                if (roleLevel < route.minRole.ordinal) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "权限不足"))
                    return
                }
            }
            // 权限标签检查
            if (route.requiredPermissions.isNotEmpty()) {
                val userPerms = if (identity is AuthIdentity.Authenticated && identity.permissions.isNotBlank())
                    identity.permissions.split(",").map { it.trim() }.toSet()
                else emptySet()
                val missing = route.requiredPermissions - userPerms
                if (missing.isNotEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "缺少权限: ${missing.joinToString()}"))
                    return
                }
            }
            val clientIp = call.request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
                ?: call.request.header("X-Real-IP")?.trim()
                ?: call.request.local.remoteAddress
            val routeContext = RouteContext(
                identity = identity,
                clientIp = clientIp,
                userAgent = call.request.userAgent(),
            )
            handleRouteResult(route) {
                route.handler(getBody(), routeContext)
            }
        }

        routing {
            get("/api/v1/ping") {
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "message" to "Connect success", "addr" to call.request.local.toString()
                    )
                )
            }

            // 视频流（Cookie 认证 + Range 支持，单独注册：GET 方法 + 自行处理 Cookie 鉴权）
            get(MaterialVideoStreamRoute.PATH) {
                MaterialVideoStreamRoute.handle(this)
            }

            for (route in allRoutes) {
                when (route.mode) {
                    FredicaApi.Route.Mode.Get -> get("/api/v1/${route.name}") {
                        handleRoute(route) { AppUtil.GlobalVars.json.encodeToString(call.queryParameters.toMap()) }
                    }

                    FredicaApi.Route.Mode.Post -> post("/api/v1/${route.name}") {
                        handleRoute(route) { call.receiveText() }
                    }

                    FredicaApi.Route.Mode.Sse -> post("/api/v1/${route.name}") {
                        if (!checkAuth()) return@post
                        (route as SseRoute).handle(this)
                    }
                }
            }
        }
    }
}


val AuthIdentityKey = AttributeKey<AuthIdentity>("AuthIdentity")

private suspend fun RoutingContext.checkAuth(): Boolean {
    val authHead = call.request.headers["Authorization"]
    val identity = AuthService.resolveIdentity(authHead)
    if (identity == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return false
    }
    call.attributes.put(AuthIdentityKey, identity)
    return true
}
