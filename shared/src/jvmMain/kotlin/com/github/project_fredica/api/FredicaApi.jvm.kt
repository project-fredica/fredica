package com.github.project_fredica.api

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toKotlinJson
import com.github.project_fredica.apputil.withContextVertx
import com.github.project_fredica.orm.SqliteOrm
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.vertx.jdbcclient.JDBCConnectOptions
import io.vertx.jdbcclient.JDBCPool
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.PoolOptions
import kotlinx.serialization.ExperimentalSerializationApi

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.*
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable


@Serializable
data class FredicaApiJvmInitOption(
    val ktorServerHost: String = "localhost",
    val ktorServerPort: UShort = FredicaApi.DEFAULT_KTOR_SERVER_PORT,
    val localhostWebUIPort: UShort = FredicaApi.DEFAULT_DEV_WEBUI_PORT,
) {
    fun ktorServerAllowHosts() = listOf("localhost:$localhostWebUIPort")
}


actual suspend fun FredicaApi.Companion.init(options: Any?) {
    val o = if (options == null) FredicaApiJvmInitOption() else options as FredicaApiJvmInitOption
    FredicaApiJvmContext.init(o)
}

actual suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route<*, *>> {
    return listOf()
}

object FredicaApiJvmContext {
    lateinit var db: Pool

    suspend fun init(option: FredicaApiJvmInitOption) {
        val logger = createLogger()
        withContextVertx { vertx ->
            val dbPath = AppUtil.Paths.appDbPath.also { it.parentFile.mkdirs() }
            db = JDBCPool.pool(
                vertx,
                JDBCConnectOptions()
                    .setJdbcUrl("jdbc:sqlite:file:$dbPath")
                    .setUser("sa"),
                PoolOptions().setMaxSize(16)
            )
            db.query("SELECT 114514").execute().coAwait().forEach {
                logger.debug("test sql result : ${it.toJson()}")
            }
            initDB()
        }
        embeddedServer(
            Netty,
            port = option.ktorServerPort.toInt(), // This is the port to which Ktor is listening
            host = option.ktorServerHost,
            module = {
                module(option)
            }).startSuspend(wait = false)
    }

    private suspend fun initDB() {
        SqliteOrm.allTable.forEach { table ->
            table.createTable(createDbExecutor())
        }
    }

    private fun createDbExecutor(): SqliteOrm.Executor {
        val logger = createLogger()

        return object : SqliteOrm.Executor {
            override operator fun invoke(
                s: String, args: List<Any>?
            ): Flow<SqliteOrm.Executor.InvokeResult> {
                return flow {
                    logger.debug("SQL executing: \n$s\n$args")
                    val rowSet = if (args == null || args.isEmpty()) {
                        db.query(s).execute().coAwait()
                    } else {
                        db.preparedQuery(s)
                            .execute(Tuple.wrap(args.toTypedArray())).coAwait()
                    }
                    rowSet.forEach { row ->
                        val rowJson = row.toJson()
                        logger.debug("SQL executing row :\n$rowJson")
                        emit(SqliteOrm.Executor.RowResult(rowJson.toKotlinJson()))
                    }
                    logger.debug("SQL executing finish")
                    emit(SqliteOrm.Executor.Finish)
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun Application.module(
        option: FredicaApiJvmInitOption
    ) {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            option.ktorServerAllowHosts().forEach {
                allowHost(it)
            }
            allowHeader(HttpHeaders.ContentType)
        }
        configureRouting()
    }

    fun Application.configureRouting() {
        routing {

        }
    }
}