@file:Suppress("NOTHING_TO_INLINE", "UnusedReceiverParameter")

package com.github.project_fredica.apputil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


@Serializable
@JvmInline
value class JsonString internal constructor(val str: String) {
    override fun toString(): String = str

    fun toJsonElement(): JsonElement {
        return AppUtil.GlobalVars.json.decodeFromString<JsonElement>(str)
    }
}

val AppUtil.GlobalVars.json by lazy {
    Json {
    }
}

val AppUtil.GlobalVars.jsonPretty by lazy {
    Json {
        prettyPrint = true
    }
}

private fun Any?.dumpJson0(pretty: Boolean = false): Result<JsonString> {
    return try {
        val j = if (pretty) AppUtil.GlobalVars.jsonPretty else AppUtil.GlobalVars.json
        Result.success(
            JsonString(j.encodeToString(this))
        )
    } catch (err: Throwable) {
        Result.failure(err)
    }
}

fun String?.dumpJson(pretty: Boolean = false) = this.dumpJson0(pretty = pretty)
fun Number?.dumpJson(pretty: Boolean = false) = this.dumpJson0(pretty = pretty)
fun JsonElement?.dumpJson(pretty: Boolean = false) = this.dumpJson0(pretty = pretty)
fun Boolean?.dumpJson(pretty: Boolean = false) = this.dumpJson0(pretty = pretty)
fun Map<String, String>?.dumpJson(pretty: Boolean = false) = this.dumpJson0(pretty = pretty)
//fun String?.encodeToJson(pretty: Boolean = false) = this.encodeToJson0(pretty = pretty)
//fun String?.encodeToJson(pretty: Boolean = false) = this.encodeToJson0(pretty = pretty)
//fun String?.encodeToJson(pretty: Boolean = false) = this.encodeToJson0(pretty = pretty)

fun AppUtil.dumpJson(obj: Any?, pretty: Boolean = false): Result<JsonString> {
    return obj.dumpJson0(pretty = pretty)
}

fun JsonObject.getString(k: String): Result<String> {
    return getStringNullable(k).mapCatching {
        if (it == null) throw NullPointerException() else it
    }
}

fun JsonObject.getStringNullable(k: String): Result<String?> {
    try {
        val v = this[k] ?: return Result.success(null)
        v as JsonPrimitive
        if (!v.isString) {
            throw ClassCastException("value $v is not string")
        }
        return Result.success(v.content)
    } catch (err: Throwable) {
        return Result.failure(err)
    }
}