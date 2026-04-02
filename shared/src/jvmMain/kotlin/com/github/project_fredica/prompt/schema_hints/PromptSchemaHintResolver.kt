package com.github.project_fredica.prompt.schema_hints

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJson
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.prompt.schema.WebenSummary
import com.github.project_fredica.prompt.schema.jsonSchemaString
import kotlinx.coroutines.runBlocking

class PromptSchemaHintResolver {
    private val logger = createLogger { "PromptSchemaHintResolver" }

    /**
     * 解析 prompt 脚本可用的 schema hint key。
     *
     * `weben/summary` 与兼容别名 `weben_schema_hint` 都映射到同一份 WebenSummary schema。
     * 返回值会把后端资源中的默认示例与现有 WebenConcept.type distinct 值合并后，注入到 JSON Schema 的 example/examples 中。
     * 未知 key 返回空串，表示当前没有可注入的 schema hint。
     */
    fun resolve(key: String): String {
        val res = resolve0(key)
        val resJson = res.loadJson()
        return if (resJson.isSuccess) {
            resJson.getOrThrow().dumpJsonStr(pretty = true).getOrThrow().str
        } else {
            res
        }
    }

    private fun resolve0(key: String): String {
        return when (key) {
            "weben/summary", "weben_schema_hint" -> {
                val examples = runBlocking { WebenTypeHintResourceLoader.loadMergedConceptTypes() }
                val schema = WebenTypeHintResourceLoader.injectExamples(
                    schemaJson = WebenSummary::class.jsonSchemaString,
                    examples = examples,
                )
                logger.debug("[PromptSchemaHintResolver] hit key=$key examples=${examples.size} length=${schema.length}")
                schema
            }

            else -> {
                logger.warn(
                    "[PromptSchemaHintResolver] 未知 schema hint key='$key'",
                    isHappensFrequently = false, err = null,
                )
                ""
            }
        }
    }
}
