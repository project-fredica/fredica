package com.github.project_fredica.api

import aws.smithy.kotlin.runtime.net.IpV4Addr
import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigRepo
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.MaterialCategoryDb
import com.github.project_fredica.db.MaterialCategoryService
import com.github.project_fredica.db.MaterialVideoDb
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.python.PythonUtil
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
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.util.toMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        FredicaApiJvmService.CurrentInstance.initOption.getLocalDomain(),
        FredicaApiJvmService.CurrentInstance.getLocalPort(),
    )
    logger.debug("native web server local domain and port is : $r")
    return r
}

actual suspend fun FredicaApi.PyUtil.get(path: String): String {
    return PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, path)
}

object FredicaApiJvmService {

    object CurrentInstance {
        lateinit var initOption: FredicaApiJvmInitOption
        lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

        fun getLocalPort(): UShort {
            val p = initOption.ktorServerPort
            return p
        }
    }

    suspend fun init(option: FredicaApiJvmInitOption) {
        val logger = createLogger()
        CurrentInstance.initOption = option

        // Initialize SQLite database and config service
        withContext(Dispatchers.IO) {
            val dbPath = AppUtil.Paths.appDbPath.also { it.parentFile.mkdirs() }
            val database = Database.connect(
                url = "jdbc:sqlite:${dbPath.absolutePath}",
                driver = "org.sqlite.JDBC",
            )
            val appConfigDb = AppConfigDb(database)
            appConfigDb.initialize()
            AppConfigService.initialize(appConfigDb)
            logger.debug("AppConfigService initialized, db path: $dbPath")

            val materialVideoDb = MaterialVideoDb(database)
            materialVideoDb.initialize()
            MaterialVideoService.initialize(materialVideoDb)
            logger.debug("MaterialVideoService initialized")

            val materialCategoryDb = MaterialCategoryDb(database)
            materialCategoryDb.initialize()
            MaterialCategoryService.initialize(materialCategoryDb)
            logger.debug("MaterialCategoryService initialized")
        }

        CurrentInstance.server = embeddedServer(
            Netty, port = option.ktorServerPort.toInt(), host = option.ktorServerHost, module = {
                initModule(option)
            })
        logger.debug("server start suspend start")
        CurrentInstance.server.startSuspend(wait = false)
        logger.debug("server start suspend finish")
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
                            HttpHeaders.CacheControl,
                            "public, max-age=31536000, immutable"
                        )
                        call.respondBytes(result.bytes, ContentType.parse(result.contentType))
                    }
                    else -> call.respond(result)
                }
            } catch (err: Throwable) {
                logger.error("Failed in route ${route.name}", err)
                call.respond(HttpStatusCode.InternalServerError)
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

            for (route in allRoutes) {
                when (route.mode) {
                    FredicaApi.Route.Mode.Get -> {
                        get("/api/v1/${route.name}") {
                            if (route.requiresAuth) {
                                handleAuth {
                                    if (it == null) {
                                        return@get
                                    }
                                }
                            }

                            val query = call.queryParameters.toMap()
                            handleRouteResult(route) {
                                route.handler(
                                    AppUtil.GlobalVars.json.encodeToString(query)
                                )
                            }
                        }
                    }

                    FredicaApi.Route.Mode.Post -> {
                        post("/api/v1/${route.name}") {
                            if (route.requiresAuth) {
                                handleAuth {
                                    if (it == null) {
                                        return@post
                                    }
                                }
                            }

                            val body = call.receiveText()
                            handleRouteResult(route) {
                                route.handler(body)
                            }
                        }
                    }
                }
            }
        }
    }
}


sealed interface FredicaApiJvmRouteAuthData {
    data class Token(
        val token: String
    ) : FredicaApiJvmRouteAuthData
}


private suspend inline fun RoutingContext.handleAuth(scope: (FredicaApiJvmRouteAuthData?) -> Unit) {
    val authHead = call.request.headers["Authorization"]
    if (authHead.isNullOrBlank()) {
        call.respond(HttpStatusCode.Unauthorized)
        scope(null)
        return
    }
    if (authHead.startsWith("Bearer ")) {
        val token = authHead.removePrefix("Bearer ")
        scope(FredicaApiJvmRouteAuthData.Token(token = token))
        return
    }
    // Unrecognized header format
    call.respond(HttpStatusCode.Unauthorized)
    scope(null)
    return
}
