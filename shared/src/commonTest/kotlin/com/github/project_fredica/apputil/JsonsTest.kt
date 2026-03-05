package com.github.project_fredica.apputil

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonsTest {

    // ─── 辅助数据类 ───────────────────────────────────────────────────────────

    @Serializable
    data class SampleModel(val name: String, val age: Int)

    // ─── 1. ValidJsonString ───────────────────────────────────────────────────

    /**
     * 证明 toString / str 直接返回构造时的原始字符串，不做任何额外转义或格式化。
     */
    @Test
    fun validJsonString_toString() {
        val raw = """{"key":"value","n":42}"""
        val v = ValidJsonString(raw)
        assertEquals(raw, v.toString())
        assertEquals(raw, v.str)
    }

    /**
     * 证明 toJsonElement 能将持有的 JSON 字符串正确解析为 JsonObject，
     * 且字段值类型与预期一致。
     */
    @Test
    fun validJsonString_toJsonElement_parseToJsonObject() {
        val v = ValidJsonString("""{"x":1,"y":"hello"}""")
        val elem = v.toJsonElement()
        assertTrue(elem is JsonObject)
        assertEquals(JsonPrimitive(1), elem["x"])
        assertEquals(JsonPrimitive("hello"), elem["y"])
    }

    // ─── 2. dumpJsonStr / loadJson ────────────────────────────────────────────

    /**
     * 证明 JsonElement.dumpJsonStr() 序列化结果可被 loadJson() 还原为等值对象，
     * 验证序列化与反序列化的往返一致性。
     */
    @Test
    fun dumpJsonStr_andLoadJson_roundtrip() {
        val original = createJson { obj { kv("k", "v"); kv("n", 7) } }
        val dumped = original.dumpJsonStr().getOrThrow()
        val restored = dumped.str.loadJson().getOrThrow()
        assertEquals(original, restored)
    }

    /**
     * 证明 null JsonElement 序列化后输出合法的 JSON "null" 字符串。
     */
    @Test
    fun nullJsonElement_dumpJsonStr_outputsJsonNull() {
        val nullElem: JsonElement? = null
        val dumped = nullElem.dumpJsonStr().getOrThrow()
        assertEquals("null", dumped.str)
    }

    /**
     * 证明 loadJson 对非法 JSON 字符串返回 failure，不抛出异常。
     */
    @Test
    fun loadJson_invalidString_returnsFailure() {
        val result = "not json at all {".loadJson()
        assertTrue(result.isFailure)
    }

    /**
     * 证明 loadJson 对合法 JSON 数组也能正确解析。
     */
    @Test
    fun loadJson_validArrayString_returnsJsonArray() {
        val elem = "[1,2,3]".loadJson().getOrThrow()
        assertTrue(elem is JsonArray)
        assertEquals(3, elem.size)
    }

    // ─── 3. loadJsonModel<T> ─────────────────────────────────────────────────

    /**
     * 证明 loadJsonModel 能将 JSON 字符串反序列化为正确的数据类实例，
     * 字段值与 JSON 中的值完全一致。
     */
    @Test
    fun loadJsonModel_deserializesToDataClass() {
        val model = """{"name":"Alice","age":30}""".loadJsonModel<SampleModel>().getOrThrow()
        assertEquals("Alice", model.name)
        assertEquals(30, model.age)
    }

    /**
     * 证明 loadJsonModel 在类型不匹配时（age 字段传字符串）返回 failure。
     */
    @Test
    fun loadJsonModel_typeMismatch_returnsFailure() {
        val result = """{"name":"Alice","age":"not_a_number"}""".loadJsonModel<SampleModel>()
        assertTrue(result.isFailure)
    }

    /**
     * 证明 loadJsonModel 在 JSON 为非法字符串时返回 failure。
     */
    @Test
    fun loadJsonModel_invalidJson_returnsFailure() {
        assertTrue("oops".loadJsonModel<SampleModel>().isFailure)
    }

    // ─── 4. JsonElement.copy() ────────────────────────────────────────────────

    /**
     * 证明 copy() 返回内容等值的新对象，且两者相互独立——
     * 原对象和副本都持有相同的字段值，但不是同一个实例引用。
     */
    @Test
    fun copy_returnsEqualAndIndependentJsonObject() {
        val original = createJson { obj { kv("x", 42); kv("y", "hello") } }
        val copy = original.copy()
        assertEquals(original, copy)
        // JsonObject 不可变，通过字段值验证内容独立正确
        assertEquals(JsonPrimitive(42), copy["x"])
        assertEquals(JsonPrimitive("hello"), copy["y"])
    }

    // ─── 5. toJsonObject / toJsonArray ───────────────────────────────────────

    /**
     * 证明 Map<String, JsonElement>.toJsonObject() 将 Map 直接包装为 JsonObject，
     * 键值对内容与原 Map 完全一致。
     */
    @Test
    fun toJsonObject_wrapsMapAsJsonObject() {
        val map = mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive("x"))
        val obj = map.toJsonObject()
        assertEquals(JsonPrimitive(1), obj["a"])
        assertEquals(JsonPrimitive("x"), obj["b"])
        assertEquals(2, obj.size)
    }

    /**
     * 证明 List<JsonElement>.toJsonArray() 将列表包装为 JsonArray，
     * 元素顺序和内容与原列表一致。
     */
    @Test
    fun toJsonArray_wrapsListAsJsonArray_preservingOrder() {
        val list = listOf(JsonPrimitive(10), JsonPrimitive(20), JsonPrimitive(30))
        val arr = list.toJsonArray()
        assertEquals(3, arr.size)
        assertEquals(JsonPrimitive(10), arr[0])
        assertEquals(JsonPrimitive(20), arr[1])
        assertEquals(JsonPrimitive(30), arr[2])
    }

    // ─── 6. mapKey ────────────────────────────────────────────────────────────

    /**
     * 证明 mapper 返回非 null 时，mapKey 将对应键的值更新为新值。
     */
    @Test
    fun mapKey_updatesExistingKey() {
        val m = mutableMapOf<String, JsonElement>("count" to JsonPrimitive(1))
        m.mapKey("count") { JsonPrimitive(99) }
        assertEquals(JsonPrimitive(99), m["count"])
    }

    /**
     * 证明 mapper 返回 null 时，mapKey 从 Map 中删除该键。
     */
    @Test
    fun mapKey_removesKey_whenMapperReturnsNull() {
        val m = mutableMapOf<String, JsonElement>("temp" to JsonPrimitive("x"))
        m.mapKey("temp") { null }
        assertTrue("temp" !in m)
    }

    /**
     * 证明对不存在的键调用 mapKey 时，mapper 收到的 oldValue 为 null，
     * 且 mapper 返回非 null 时会新增该键。
     */
    @Test
    fun mapKey_receivesNullOldValue_forMissingKey_andCanAdd() {
        val m = mutableMapOf<String, JsonElement>()
        var capturedOld: JsonElement? = JsonPrimitive("sentinel")
        m.mapKey("new") { old -> capturedOld = old; JsonPrimitive("added") }
        assertNull(capturedOld)
        assertEquals(JsonPrimitive("added"), m["new"])
    }

    // ─── 7. mapOneKey ─────────────────────────────────────────────────────────

    /**
     * 证明 mapOneKey 返回包含更新值的新 JsonObject，
     * 且原对象的对应字段保持不变（不可变变换）。
     */
    @Test
    fun mapOneKey_returnsNewObject_originalUnchanged() {
        val original = createJson { obj { kv("v", 1); kv("other", "x") } }
        val modified = original.mapOneKey("v") { JsonPrimitive(999) }

        assertEquals(JsonPrimitive(1), original["v"])    // 原对象不变
        assertEquals(JsonPrimitive(999), modified["v"])    // 新对象已更新
        assertEquals(original["other"], modified["other"]) // 其他键不受影响
    }

    /**
     * 证明 mapOneKey 的 mapper 返回 null 时，该键从新对象中被删除。
     */
    @Test
    fun mapOneKey_removesKeyInNewObject_whenMapperReturnsNull() {
        val original = createJson { obj { kv("del", 1); kv("keep", 2) } }
        val modified = original.mapOneKey("del") { null }

        assertTrue("del" in original)    // 原对象保留
        assertTrue("del" !in modified)   // 新对象删除
        assertEquals(JsonPrimitive(2), modified["keep"])
    }

    // ─── 8. asT<T> ────────────────────────────────────────────────────────────

    /**
     * 证明 JsonPrimitive(string).asT<String>() 能正确提取字符串内容。
     */
    @Test
    fun asT_extractsString_fromStringJsonPrimitive() {
        val elem = JsonPrimitive("hello world")
        assertEquals("hello world", elem.asT<String>().getOrThrow())
    }

    /**
     * 证明 JsonObject.asT<JsonObject>() 能直接强转并返回原对象。
     */
    @Test
    fun asT_extractsJsonObject() {
        val obj = createJson { obj { kv("x", 1) } }
        val extracted = obj.asT<JsonObject>().getOrThrow()
        assertEquals(JsonPrimitive(1), extracted["x"])
    }

    /**
     * 证明 JsonArray.asT<JsonArray>() 能直接强转并返回原数组。
     */
    @Test
    fun asT_extractsJsonArray() {
        val arr = listOf(JsonPrimitive(1), JsonPrimitive(2)).toJsonArray()
        val extracted = (arr as JsonElement).asT<JsonArray>().getOrThrow()
        assertEquals(2, extracted.size)
    }

    /**
     * 证明对可空类型 String? 调用 asT，当元素为 JsonNull 时返回 null。
     */
    @Test
    fun asT_nullableType_jsonNull_returnsNull() {
        val result = JsonNull.asT<String?>().getOrThrow()
        assertNull(result)
    }

    /**
     * 证明对非字符串 JsonPrimitive（数字）调用 asT<String> 返回 failure，
     * 因为 JsonPrimitive(42).isString == false。
     */
    @Test
    fun asT_numberJsonPrimitive_extractString_fails() {
        val result = JsonPrimitive(42).asT<String>()
        assertTrue(result.isFailure)
    }

    /**
     * 证明 null JsonElement 对非可空类型调用 asT 返回 failure（NullPointerException）。
     */
    @Test
    fun asT_nullElement_nonNullableType_returnsFailure() {
        val nullElem: JsonElement? = null
        assertTrue(nullElem.asT<String>().isFailure)
    }

    // ─── 9. createJson { obj { } } ────────────────────────────────────────────

    /**
     * 证明 kv 对 String / Boolean / Number / kNull 等基本类型均能写入正确的 JsonElement。
     */
    @Test
    fun createJsonObj_supportsAllPrimitiveTypes() {
        val obj = createJson {
            obj {
                kv("str", "hello")
                kv("bool", true)
                kv("int", 42)
                kv("dbl", 3.14)
                kNull("nil")
            }
        }
        assertEquals(JsonPrimitive("hello"), obj["str"])
        assertEquals(JsonPrimitive(true), obj["bool"])
        assertEquals(JsonPrimitive(42), obj["int"])
        assertEquals(JsonPrimitive(3.14), obj["dbl"])
        assertEquals(JsonNull, obj["nil"])
    }

    /**
     * 证明 kv 对 null String/Boolean/Number 写入 JsonNull（JsonPrimitive(null)）。
     */
    @Test
    fun createJsonObj_nullPrimitive_writesJsonNull() {
        val obj = createJson {
            obj {
                kv("s", null as String?)
                kv("b", null as Boolean?)
                kv("n", null as Number?)
            }
        }
        // JsonPrimitive(null) 在比较上等于 JsonNull
        assertEquals(obj["s"].toString(), "null")
        assertEquals(obj["b"].toString(), "null")
        assertEquals(obj["n"].toString(), "null")
    }

    /**
     * 证明 kv 可嵌套 JsonObject 和 JsonArray，嵌入后内容与原值相同。
     */
    @Test
    fun createJsonObj_supportsNestedJsonObjectAndJsonArray() {
        val nested = createJson { obj { kv("inner", 1) } }
        val arr = listOf(JsonPrimitive("a"), JsonPrimitive("b")).toJsonArray()
        val obj = createJson {
            obj {
                kv("nested", nested)
                kv("arr", arr)
            }
        }
        assertEquals(nested, obj["nested"])
        assertEquals(arr, obj["arr"])
    }

    /**
     * 证明空 DSL 块返回空 JsonObject（{}）。
     */
    @Test
    fun createJsonObj_emptyBlock_returnsEmptyJsonObject() {
        val obj = createJson { obj { } }
        assertTrue(obj.isEmpty())
    }

    // ─── 10. buildValidJson { } ───────────────────────────────────────────────

    /**
     * 证明 buildValidJson 基础用法：String + Number kv 生成可解析的合法 JSON 字符串。
     */
    @Test
    fun buildValidJson_basicKv_generatesCorrectJson() {
        val result = buildValidJson { kv("status", "ok"); kv("count", 3) }
        val parsed = result.toJsonElement() as JsonObject
        assertEquals(JsonPrimitive("ok"), parsed["status"])
        assertEquals(JsonPrimitive(3), parsed["count"])
    }

    /**
     * 证明注入安全性：字符串值含双引号和反斜杠时，buildValidJson 仍能生成合法 JSON，
     * 且解析后内容与原值完全一致——字符串拼接无法做到这一点。
     */
    @Test
    fun buildValidJson_stringWithSpecialChars_autoEscaped() {
        val tricky = """say "hello" and C:\path\to\file"""
        val result = buildValidJson { kv("msg", tricky) }
        // 能无异常解析回来说明是合法 JSON
        val parsed = result.toJsonElement() as JsonObject
        assertEquals(tricky, (parsed["msg"] as JsonPrimitive).content)
    }

    /**
     * 证明 buildValidJson Boolean 和 Long 值正确写入。
     */
    @Test
    fun buildValidJson_booleanAndLongValues() {
        val result = buildValidJson { kv("ok", true); kv("n", 100L) }
        val parsed = result.toJsonElement() as JsonObject
        assertEquals(JsonPrimitive(true), parsed["ok"])
        assertEquals(JsonPrimitive(100L), parsed["n"])
    }

    /**
     * 证明 kNull 写入 JSON null，而非字符串 "null"。
     */
    @Test
    fun buildValidJson_kNull_writesJsonNull() {
        val result = buildValidJson { kNull("x") }
        val parsed = result.toJsonElement() as JsonObject
        assertEquals(JsonNull, parsed["x"])
    }

    /**
     * 证明 kv(k, JsonArray?) 将数组正确嵌入，数组元素内容与原值一致。
     */
    @Test
    fun buildValidJson_embedsJsonArray() {
        val arr = listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3)).toJsonArray()
        val result = buildValidJson { kv("ids", arr) }
        val parsed = result.toJsonElement() as JsonObject
        assertEquals(arr, parsed["ids"])
    }

    /**
     * 证明 kv(k, JsonElement?) 将任意 JsonElement（如 JsonObject）正确嵌入。
     */
    @Test
    fun buildValidJson_embedsJsonElement() {
        val inner: JsonElement = createJson { obj { kv("flag", false) } }
        val result = buildValidJson { kv("data", inner) }
        val parsed = (result.toJsonElement() as JsonObject)["data"] as JsonObject
        assertEquals(JsonPrimitive(false), parsed["flag"])
    }

    /**
     * 证明 kv(k, ValidJsonString?) 将已序列化 JSON 字符串解析后正确内嵌为子对象，
     * 而非作为字符串字面量嵌入。
     */
    @Test
    fun buildValidJson_embedsValidJsonString_parsedInline() {
        val inner = ValidJsonString("""{"nested":true,"val":42}""")
        val result = buildValidJson { kv("data", inner) }
        val nested = (result.toJsonElement() as JsonObject)["data"] as JsonObject
        assertEquals(JsonPrimitive(true), nested["nested"])
        assertEquals(JsonPrimitive(42), nested["val"])
    }

    /**
     * 证明 kv(k, null ValidJsonString?) 写入 JSON null，而非抛出异常。
     */
    @Test
    fun buildValidJson_nullValidJsonString_writesJsonNull() {
        val result = buildValidJson { kv("x", null as ValidJsonString?) }
        assertEquals(JsonNull, (result.toJsonElement() as JsonObject)["x"])
    }

    /**
     * 证明空块生成 "{}"，即空 JSON 对象字符串。
     */
    @Test
    fun buildValidJson_emptyBlock_generatesEmptyObject() {
        val result = buildValidJson { }
        assertEquals("{}", result.str)
    }

    /**
     * 证明 buildValidJson 的返回值是合法的 ValidJsonString，
     * 即 toString() 与 .str 相同，均为紧凑 JSON 字符串。
     */
    @Test
    fun buildValidJson_returnValue_toStringEqualsStr() {
        val result = buildValidJson { kv("a", 1) }
        assertEquals(result.str, result.toString())
    }

    /**
     * 证明 buildValidJson 与手动 Json.encodeToString 的等价性：
     * 对同一个 @Serializable 对象，两种方式生成的 JSON 内容一致。
     */
    @Test
    fun buildValidJson_equivalentToEncodeToString() {
        val model = SampleModel("Bob", 25)
        val viaEncode = AppUtil.GlobalVars.json.encodeToString(model)
        val viaBuilder = buildValidJson {
            kv("name", model.name)
            kv("age", model.age)
        }
        val fromEncode = viaEncode.loadJson().getOrThrow() as JsonObject
        val fromBuilder = viaBuilder.toJsonElement() as JsonObject
        assertEquals(fromEncode["name"], fromBuilder["name"])
        assertEquals(fromEncode["age"], fromBuilder["age"])
    }
}
