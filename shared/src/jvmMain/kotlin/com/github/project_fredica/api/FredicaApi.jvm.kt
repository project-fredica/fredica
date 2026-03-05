package com.github.project_fredica.api

import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialCategoryService
import com.github.project_fredica.db.MaterialDb
import com.github.project_fredica.db.MaterialService
import com.github.project_fredica.db.MaterialTaskDb
import com.github.project_fredica.db.MaterialTaskService
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.PipelineDb
import com.github.project_fredica.db.PipelineService
import com.github.project_fredica.db.TaskDb
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.TaskCancelService
import com.github.project_fredica.worker.WorkerEngine
import com.github.project_fredica.worker.executors.DownloadBilibiliVideoExecutor
import inet.ipaddr.AddressStringException
import inet.ipaddr.IPAddressString
import com.github.project_fredica.api.routes.ImageProxyResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.util.toMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
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
    withContext(Dispatchers.IO) {
        listOf(async {

        }, async {
            val o = if (options == null) FredicaApiJvmInitOption() else options as FredicaApiJvmInitOption
            FredicaApiJvmService.init(o)
        }).awaitAll()
    }
}

actual suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route> {
    return listOf()
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

object FredicaApiJvmService {

    object CurrentInstanceHandler {
        lateinit var initOption: FredicaApiJvmInitOption
        lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

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

            suspend fun <T> boot(label: String, repo: T, init: suspend T.() -> Unit, register: (T) -> Unit) {
                repo.init()
                register(repo)
                logger.debug("$label initialized")
            }

            boot(
                "AppConfigService, db path: $dbPath",
                AppConfigDb(database),
                { initialize() }) { AppConfigService.initialize(it) }
            // 1. Base material table + all type-specific detail table stubs (must come first)
            boot("MaterialService", MaterialDb(database), { initialize() }) { MaterialService.initialize(it) }
            // 2. Video detail table (depends on material base table existing)
            boot("MaterialVideoService", MaterialVideoDb(database), { initialize() }) {
                MaterialVideoService.initialize(
                    it
                )
            }
            // 3. Category definitions + material_category_rel junction table
            boot(
                "MaterialCategoryService",
                MaterialCategoryDb(database),
                { initialize() }) { MaterialCategoryService.initialize(it) }
            // 4. Task table (references material.id)
            boot(
                "MaterialTaskService",
                MaterialTaskDb(database),
                { initialize() }) { MaterialTaskService.initialize(it) }
            // 5. Async worker task queue (pipeline_instance + task + task_event tables)
            boot("TaskService", TaskDb(database), { initialize() }) { TaskService.initialize(it) }
            boot("PipelineService", PipelineDb(database), { initialize() }) { PipelineService.initialize(it) }
        }

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
        WorkerEngine.start(
            maxWorkers = 2,
            scope = engineScope,
            executors = listOf(
                DownloadBilibiliVideoExecutor,
            ),
        )
        logger.debug("WorkerEngine started")

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


    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun Application.initModule(
        option: FredicaApiJvmInitOption
    ) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            option.ktorServerAllowHosts().forEach {
                allowHost(it)
            }
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
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        suspend fun RoutingContext.handleRoute(route: FredicaApi.Route, getBody: suspend RoutingContext.() -> String) {
            if (route.requiresAuth && !checkAuth()) return
            handleRouteResult(route) { route.handler(getBody()) }
        }

        routing {
            get("/api/v1/ping") {
                call.respond(
                    HttpStatusCode.OK, mapOf(
                        "message" to "Connect success", "addr" to call.request.local.toString()
                    )
                )
            }

            for (route in allRoutes) {
                when (route.mode) {
                    FredicaApi.Route.Mode.Get -> get("/api/v1/${route.name}") {
                        handleRoute(route) { AppUtil.GlobalVars.json.encodeToString(call.queryParameters.toMap()) }
                    }

                    FredicaApi.Route.Mode.Post -> post("/api/v1/${route.name}") {
                        handleRoute(route) { call.receiveText() }
                    }
                }
            }
        }
    }
}


private suspend fun RoutingContext.checkAuth(): Boolean {
    val authHead = call.request.headers["Authorization"]
    if (authHead.isNullOrBlank() || !authHead.startsWith("Bearer ")) {
        call.respond(HttpStatusCode.Unauthorized)
        return false
    }
    return true
}
