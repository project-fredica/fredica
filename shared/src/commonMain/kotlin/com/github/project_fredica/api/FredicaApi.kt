package com.github.project_fredica.api

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.dumpJson
import io.github.optimumcode.json.pointer.JsonPointer
import io.github.optimumcode.json.schema.AbsoluteLocation
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface FredicaApi {
    companion object {
        suspend fun getAllRoutes(): List<Route<*, *>> {
            return listOf(
                *getNativeRoutes().toTypedArray()
            ).sortedBy { route -> route.name }
        }

        const val DEFAULT_DEV_WEBUI_PORT: UShort = 7630u
        const val DEFAULT_KTOR_SERVER_PORT: UShort = 7631u
    }

    interface Route<P, R> {
        val name: String

        suspend fun getParamSchemaObj(): JsonObject

        suspend fun checkParam(param: P): Result<Unit> {
            val paramSchemaObj = getParamSchemaObj()
            val schema = JsonSchema.fromJsonElement(paramSchemaObj)
            return AppUtil.dumpJson(param).map { it.toJsonElement() }.map { jsonElement ->
                val errors = mutableListOf<ValidationError>()
                val success = schema.validate(jsonElement, errors::add)
                return if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        RouteCheckParamValidationFailedException(
                            details = errors.map { ValidationError2.from(it) }, routeName = this.name
                        )
                    )
                }
            }
        }

        suspend fun handle(param: P): Response<R>

        @Serializable
        data class ValidationError2(
            val schemaPath: JsonPointer,
            val objectPath: JsonPointer,
            val message: String,
            val details: Map<String, String> = emptyMap(),
            val absoluteLocation: AbsoluteLocation? = null,
        ) {
            companion object {
                fun from(v: ValidationError) = ValidationError2(
                    v.schemaPath,
                    v.objectPath,
                    v.message,
                    v.details,
                    v.absoluteLocation,
                )
            }
        }

        class RouteCheckParamValidationFailedException(val details: List<ValidationError2>, val routeName: String) :
            IllegalArgumentException(
                "Invalid param of route $routeName :\n${
                    details.joinToString("\n") { it.message }
                }")
    }
}

expect suspend fun FredicaApi.Companion.init(options: Any? = null)

expect suspend fun FredicaApi.Companion.getNativeRoutes(): List<FredicaApi.Route<*, *>>


