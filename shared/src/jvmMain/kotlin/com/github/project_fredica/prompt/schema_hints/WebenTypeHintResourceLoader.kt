package com.github.project_fredica.prompt.schema_hints

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.jsonCanonical
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.weben.WebenConceptService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object WebenTypeHintResourceLoader {
    private val logger = createLogger { "WebenTypeHintResourceLoader" }
    private const val DEFAULT_TYPES_RESOURCE = "weben_type_hints/concept_type_defaults.json"

    private val defaultConceptTypes: List<String> by lazy {
        val loader = Thread.currentThread().contextClassLoader
            ?: WebenTypeHintResourceLoader::class.java.classLoader
        val raw = loader.getResourceAsStream(DEFAULT_TYPES_RESOURCE)
            ?.bufferedReader()?.readText()
            ?: run {
                logger.warn(
                    "[WebenTypeHintResourceLoader] 找不到资源 $DEFAULT_TYPES_RESOURCE",
                    isHappensFrequently = false,
                    err = null,
                )
                return@lazy emptyList()
            }
        return@lazy AppUtil.GlobalVars.json.decodeFromString(ListSerializer(String.serializer()), raw)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun loadDefaultConceptTypes(): List<String> {
        return defaultConceptTypes
    }

    suspend fun loadMergedConceptTypes(): List<String> {
        val defaults = loadDefaultConceptTypes()
        val existing = runCatching {
            WebenConceptService.repo.listDistinctConceptTypes()
        }.getOrElse { err ->
            logger.error(
                "[WebenTypeHintResourceLoader] 读取现有 concept type 失败，回退到默认 examples",
                err
            )
            emptyList()
        }
        return (defaults + existing)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun injectExamples(schemaJson: String, examples: List<String>): String {
        if (examples.isEmpty()) return jsonCanonical(schemaJson)
        val root =
            AppUtil.GlobalVars.json.parseToJsonElement(schemaJson) as? JsonObject ?: return jsonCanonical(schemaJson)
        val exampleArray = JsonArray(examples.map { JsonPrimitive(it) })

        fun mergeTypeNode(typeNode: JsonObject): JsonObject = JsonObject(typeNode.toMutableMap().apply {
            this["example"] = JsonPrimitive(examples.first())
            this["examples"] = exampleArray
        })

        fun mergeConceptSchema(conceptSchema: JsonObject): JsonObject {
            val properties = conceptSchema["properties"] as? JsonObject ?: return conceptSchema
            // types: List<String> → navigate to "types".items and inject examples there
            val typesNode = properties["types"] as? JsonObject ?: return conceptSchema
            val itemsNode = typesNode["items"] as? JsonObject ?: return conceptSchema
            val mergedItemsNode = mergeTypeNode(itemsNode)
            val mergedTypesNode = JsonObject(typesNode.toMutableMap().apply {
                this["items"] = mergedItemsNode
            })
            val mergedProperties = JsonObject(properties.toMutableMap().apply {
                this["types"] = mergedTypesNode
            })
            return JsonObject(conceptSchema.toMutableMap().apply {
                this["properties"] = mergedProperties
            })
        }

        val defs = root["\$defs"] as? JsonObject
        if (defs != null) {
            val conceptKey = defs.keys.firstOrNull { it.endsWith("WebenSummaryConcept") }
            if (conceptKey != null) {
                val conceptSchema = defs[conceptKey] as? JsonObject
                if (conceptSchema != null) {
                    val mergedDefs = JsonObject(defs.toMutableMap().apply {
                        this[conceptKey] = mergeConceptSchema(conceptSchema)
                    })
                    val mergedRoot = JsonObject(root.toMutableMap().apply {
                        this["\$defs"] = mergedDefs
                    })
                    return jsonCanonical(mergedRoot.toString())
                }
            }
        }

        val properties = root["properties"] as? JsonObject ?: return jsonCanonical(schemaJson)
        val conceptsNode = properties["concepts"] as? JsonObject ?: return jsonCanonical(schemaJson)
        val itemsNode = conceptsNode["items"] as? JsonObject ?: return jsonCanonical(schemaJson)
        val mergedItemsNode = mergeConceptSchema(itemsNode)
        val mergedConceptsNode = JsonObject(conceptsNode.toMutableMap().apply {
            this["items"] = mergedItemsNode
        })
        val mergedProperties = JsonObject(properties.toMutableMap().apply {
            this["concepts"] = mergedConceptsNode
        })
        val mergedRoot = JsonObject(root.toMutableMap().apply {
            this["properties"] = mergedProperties
        })
        return jsonCanonical(mergedRoot.toString())
    }
}
