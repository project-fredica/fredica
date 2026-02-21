@file:Suppress("NOTHING_TO_INLINE", "UnusedReceiverParameter")

package com.github.project_fredica.apputil

import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


@Serializable
@JvmInline
value class ValidJsonString internal constructor(val str: String) {
    override fun toString(): String = str

    fun toJsonElement(): JsonElement {
        return AppUtil.GlobalVars.json.decodeFromString<JsonElement>(str)
    }
}

val AppUtil.GlobalVars.json by lazy {
    Json.Default
}

val AppUtil.GlobalVars.jsonPretty by lazy {
    Json {
        prettyPrint = true
    }
}

private inline fun <reified T : Any> T?.dumpJsonStr0(pretty: Boolean = false) = Result.wrap {
    val j = if (pretty) AppUtil.GlobalVars.jsonPretty else AppUtil.GlobalVars.json
    return@wrap ValidJsonString(j.encodeToString(this))
}

fun JsonElement?.dumpJsonStr(pretty: Boolean = false) = this.dumpJsonStr0(pretty = pretty)
fun AppUtil.dumpJsonStr(obj: Any?, pretty: Boolean = false) = obj.dumpJsonStr0(pretty = pretty)

fun String.loadJson(): Result<JsonElement> = Result.wrap {
    AppUtil.GlobalVars.json.decodeFromString<JsonElement>(this)
}

inline fun <reified M> String.loadJsonModel(): Result<M> = Result.wrap {
    AppUtil.GlobalVars.json.decodeFromString<M>(this)
}

inline fun <reified T : JsonElement> T.copy(): T {
    return this.dumpJsonStr().getOrThrow().str.loadJson().getOrThrow() as T
}

fun Map<String, JsonElement>.toJsonObject() = JsonObject(this)

fun MutableMap<String, JsonElement>.mapKey(k: String, mapper: (oldValue: JsonElement?) -> JsonElement?) {
    val oldV = this[k]
    val newV = mapper(oldV)
    if (newV != null) {
        this[k] = newV
    } else {
        this.remove(k)
    }
}

fun JsonObject.mapOneKey(k: String, mapper: (oldValue: JsonElement?) -> JsonElement?) = this.toMutableMap().apply {
    mapKey(k, mapper)
}.toJsonObject()

fun List<JsonElement>.toJsonArray() = JsonArray(this)

inline fun <reified T> JsonElement?.asT(): Result<T> = Result.wrap {
    val typ = typeInfo<T>()
    if (this == null || this is JsonNull?) {
        if (typ.kotlinType!!.isMarkedNullable) {
            return@wrap null as T
        } else {
            throw NullPointerException("Require type $typ , but value is $this")
        }
    }
    if (typ.type == String::class) {
        this as JsonPrimitive
        if (this.isString) {
            return@wrap this.content as T
        } else {
            throw ClassCastException("Require type $typ , but value is $this")
        }
    }
    if (typ.type == JsonObject::class) {
        return@wrap this as JsonObject as T
    }
    if (typ.type == JsonArray::class) {
        return@wrap this as JsonArray as T
    }
    TODO()
}

object CreateJsonUtil {
    interface ObjContext {
        fun kv(k: String, v: String?)
        fun kv(k: String, v: Boolean?)
        fun kv(k: String, v: Number?)
        fun kNull(k: String)
        fun kv(k: String, v: JsonObject?)
        fun kv(k: String, v: JsonArray?)
    }

    inline fun obj(scope: ObjContext.() -> Unit): JsonObject {
        val m = mutableMapOf<String, JsonElement>()
        val ctx = object : ObjContext {
            override fun kv(k: String, v: String?) {
                m[k] = JsonPrimitive(v)
            }

            override fun kv(k: String, v: Boolean?) {
                m[k] = JsonPrimitive(v)
            }

            override fun kv(k: String, v: Number?) {
                m[k] = JsonPrimitive(v)
            }

            override fun kNull(k: String) {
                m[k] = JsonNull
            }

            override fun kv(k: String, v: JsonObject?) {
                m[k] = v ?: JsonNull
            }

            override fun kv(k: String, v: JsonArray?) {
                m[k] = v ?: JsonNull
            }
        }
        ctx.run { scope() }
        return m.toJsonObject()
    }
}

inline fun <R> createJson(scope: CreateJsonUtil.() -> R): R {
    return scope(CreateJsonUtil)
}

