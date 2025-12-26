package com.github.project_fredica.api.routes

import kotlinx.serialization.json.JsonObject


interface SqliteCrudCommand

interface SqliteCrudResult

abstract class SqliteCrudRoute : com.github.project_fredica.api.FredicaApi.Route<SqliteCrudCommand, SqliteCrudResult> {
    override val name: String
        get() = TODO("Not yet implemented")

    override suspend fun getParamSchemaObj(): JsonObject {
        TODO("Not yet implemented")
    }

    override suspend fun handle(param: SqliteCrudCommand): com.github.project_fredica.api.Response<SqliteCrudResult> {
        TODO("Not yet implemented")
    }
}