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


/**
 * 已验证合法的 JSON 字符串值类，确保持有的字符串一定是合法 JSON（由调用方负责保证，
 * 或通过 [dumpJsonStr] 序列化生成）。
 *
 * 使用 `@JvmInline` 避免运行时装箱，[toString] 直接返回 JSON 字符串。
 * `internal` 构造函数防止外部绕过序列化直接构造无效实例。
 */
@Serializable
@JvmInline
value class ValidJsonString(val str: String) {
    override fun toString(): String = str

    /** 将此 JSON 字符串反序列化为 [JsonElement] 树。 */
    fun toJsonElement(): JsonElement {
        return AppUtil.GlobalVars.json.decodeFromString<JsonElement>(str)
    }
}

/** 将 [JsonObject] 包装为 [ValidJsonString]（不做额外序列化，直接 toString）。 */
fun JsonObject.toValidJson() = ValidJsonString(toString())

/** 全局默认 JSON 实例（紧凑格式），延迟初始化。 */
val AppUtil.GlobalVars.json by lazy {
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

/** 全局美化打印 JSON 实例，用于调试输出，延迟初始化。 */
val AppUtil.GlobalVars.jsonPretty by lazy {
    Json {
        prettyPrint = true
    }
}

private inline fun <reified T : Any> T?.dumpJsonStr0(pretty: Boolean = false) = Result.wrap {
    val j = if (pretty) AppUtil.GlobalVars.jsonPretty else AppUtil.GlobalVars.json
    return@wrap ValidJsonString(j.encodeToString(this))
}

/**
 * 将 [JsonElement]（或 null）序列化为 [ValidJsonString]，包装在 [Result] 中。
 * @param pretty 是否使用美化格式（默认 false）
 */
fun JsonElement?.dumpJsonStr(pretty: Boolean = false) = this.dumpJsonStr0(pretty = pretty)

/**
 * 将任意可序列化对象序列化为 [ValidJsonString]，包装在 [Result] 中。
 * 通过 `AppUtil` 接收者调用以限制命名空间：`AppUtil.dumpJsonStr(obj)`。
 */
inline fun <reified T : Any> AppUtil.dumpJsonStr(obj: T?, pretty: Boolean = false) = Result.wrap {
    val j = if (pretty) AppUtil.GlobalVars.jsonPretty else AppUtil.GlobalVars.json
    return@wrap ValidJsonString(j.encodeToString(obj))
}

/**
 * 将 JSON 字符串反序列化为 [JsonElement] 树，包装在 [Result] 中。
 * 适合处理结构未知的 JSON 数据。
 */
fun String.loadJson(): Result<JsonElement> = Result.wrap {
    AppUtil.GlobalVars.json.decodeFromString<JsonElement>(this)
}

/**
 * 将 JSON 字符串反序列化为指定类型 [M] 的数据模型，包装在 [Result] 中。
 * [M] 必须为 `@Serializable` 类型。
 *
 * ```kotlin
 * val result = jsonStr.loadJsonModel<MyData>()
 * ```
 */
inline fun <reified M> String.loadJsonModel(): Result<M> = Result.wrap {
    AppUtil.GlobalVars.json.decodeFromString<M>(this)
}

/**
 * 通过序列化 + 反序列化对 [JsonElement] 进行深拷贝，
 * 确保返回的新实例与原对象完全独立（无共享引用）。
 */
inline fun <reified T : JsonElement> T.copy(): T {
    return this.dumpJsonStr().getOrThrow().str.loadJson().getOrThrow() as T
}

/** 将 `Map<String, JsonElement>` 转换为 [JsonObject]（仅包装，不拷贝）。 */
fun Map<String, JsonElement>.toJsonObject() = JsonObject(this)

/**
 * 对可变 Map 中的指定键执行原地变换：
 * - [mapper] 返回非 null → 写入新值
 * - [mapper] 返回 null → 删除该键
 *
 * @param k 要变换的键
 * @param mapper 接收旧值（键不存在时为 null），返回新值或 null
 */
fun MutableMap<String, JsonElement>.mapKey(k: String, mapper: (oldValue: JsonElement?) -> JsonElement?) {
    val oldV = this[k]
    val newV = mapper(oldV)
    if (newV != null) {
        this[k] = newV
    } else {
        this.remove(k)
    }
}

/**
 * 对 [JsonObject] 中的单个键执行不可变变换，返回新的 [JsonObject]，原对象不变。
 * 底层将 JsonObject 转为可变 Map，调用 [mapKey]，再转回 JsonObject。
 */
fun JsonObject.mapOneKey(k: String, mapper: (oldValue: JsonElement?) -> JsonElement?) = this.toMutableMap().apply {
    mapKey(k, mapper)
}.toJsonObject()

/** 将 `List<JsonElement>` 转换为 [JsonArray]（仅包装，不拷贝）。 */
fun List<JsonElement>.toJsonArray() = JsonArray(this)

/**
 * 将 [JsonElement] 类型安全地转换为目标类型 [T]，包装在 [Result] 中。
 *
 * 支持的目标类型：
 * - `String`：要求 JsonPrimitive 且为字符串类型（`isString == true`）
 * - `JsonObject`：直接强转
 * - `JsonArray`：直接强转
 * - 可空类型（`T?`）：当元素为 null 或 JsonNull 时返回 null
 *
 * 不支持的类型当前会调用 `TODO()` 抛出 [NotImplementedError]。
 */
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


/**
 * 对任意 JSON 字符串做 key 字母序规范化（递归排序所有层级的 object key）。
 * 用于消除相同结构因序列化 key 顺序不同产生的字符串差异。
 * 返回紧凑格式（无多余空格）的 JSON 字符串。
 */
fun jsonCanonical(json: String): String = jsonElementCanonical(
    AppUtil.GlobalVars.json.parseToJsonElement(json)
).toString()

private fun jsonElementCanonical(elem: JsonElement): JsonElement = when (elem) {
    is JsonObject -> JsonObject(
        elem.entries
            .sortedBy { it.key }
            .associate { (k, v) -> k to jsonElementCanonical(v) }
    )
    is JsonArray -> JsonArray(elem.map { jsonElementCanonical(it) })
    else -> elem   // JsonPrimitive / JsonNull，原样返回
}

