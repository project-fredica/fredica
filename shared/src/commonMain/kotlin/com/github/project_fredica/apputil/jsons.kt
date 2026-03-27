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
 * 构建 JSON 对象的 DSL 工具。通过 [createJson] 入口函数使用：
 *
 * ```kotlin
 * val json = createJson {
 *     obj {
 *         kv("name", "Alice")
 *         kv("age", 30)
 *         kNull("avatar")
 *     }
 * }
 * ```
 */
object CreateJsonUtil {
    /**
     * JSON 对象构建上下文，提供类型化的 `kv` / `kNull` 方法，
     * 避免手动创建 [JsonPrimitive] 等底层类型。
     */
    interface ObjContext {
        fun kv(k: String, v: String?)
        fun kv(k: String, v: Boolean?)
        fun kv(k: String, v: Number?)

        /** 将键 [k] 的值显式设为 JSON null。 */
        fun kNull(k: String)
        fun kv(k: String, v: JsonObject?)
        fun kv(k: String, v: JsonArray?)

        /** 直接嵌入任意 [JsonElement]，null 时写入 JSON null。 */
        fun kv(k: String, v: JsonElement?)

        /**
         * 将已序列化的 [ValidJsonString] 解析后嵌入，null 时写入 JSON null。
         * 适用于持有其他序列化结果（如 `Json.encodeToString(obj)`）需要内嵌的场景。
         */
        fun kv(k: String, v: ValidJsonString?)
    }

    /** 在 DSL 块中构建一个 [JsonObject]，在 [scope] 内调用 `kv` / `kNull` 方法添加键值对。 */
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

            override fun kv(k: String, v: JsonElement?) {
                m[k] = v ?: JsonNull
            }

            override fun kv(k: String, v: ValidJsonString?) {
                m[k] = if (v == null) JsonNull
                else AppUtil.GlobalVars.json.decodeFromString<JsonElement>(v.str)
            }
        }
        ctx.run { scope() }
        return m.toJsonObject()
    }
}

/**
 * [CreateJsonUtil] DSL 的入口函数，提供 JSON 构建上下文。
 *
 * ```kotlin
 * val body = createJson { obj { kv("page", 1); kv("size", 20) } }
 * ```
 */
inline fun <R> createJson(scope: CreateJsonUtil.() -> R): R {
    return scope(CreateJsonUtil)
}

/**
 * 以 [CreateJsonUtil] DSL 风格构建 JSON 对象，直接返回 [ValidJsonString]。
 *
 * 是 `createJson { obj { … } }.dumpJsonStr().getOrThrow()` 的简化写法，
 * 可安全替换手动字符串拼接的 `ValidJsonString("""{"key":"$value"}""")` 模式——
 * 字符串值由 [JsonPrimitive] 自动转义，不存在 JSON 注入风险。
 *
 * ```kotlin
 * // Before（字符串插值：$id 若含引号或反斜杠会破坏 JSON 结构）
 * return ValidJsonString("""{"error":"not_found","id":"$id"}""")
 *
 * // After（类型安全，自动转义）
 * return buildValidJson {
 *     kv("error", "not_found")
 *     kv("id", id)
 * }
 * ```
 *
 * 嵌套 `@Serializable` 子对象时，先转成 [JsonElement] 再传给 [CreateJsonUtil.ObjContext.kv]：
 * ```kotlin
 * return buildValidJson {
 *     kv("pipeline", AppUtil.GlobalVars.json.encodeToJsonElement(pipeline))
 *     kv("tasks",    AppUtil.GlobalVars.json.encodeToJsonElement(tasks))
 * }
 * ```
 */
inline fun buildValidJson(scope: CreateJsonUtil.ObjContext.() -> Unit): ValidJsonString =
    ValidJsonString(CreateJsonUtil.obj(scope).toString())

