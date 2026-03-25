package com.github.project_fredica.api.routes

// =============================================================================
// BuiltInTemplateLoader (JVM actual)
// =============================================================================
//
// 从 commonMain/resources/prompt_templates/ 加载内置系统模板。
// 逻辑：
//   1. 读取 _index.txt 获取模板 ID 列表
//   2. 逐个加载 {id}.meta.json（元数据）+ {id}.js（脚本）
//   3. 组装为 PromptTemplate(sourceType = "system")
//
// 遇到文件缺失或解析失败时记 warn 并跳过该模板，不中断其他模板的加载。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.PromptTemplate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

actual object BuiltInTemplateLoader {
    private val logger = createLogger { "BuiltInTemplateLoader" }

    actual fun loadAll(): List<PromptTemplate> {
        val loader = Thread.currentThread().contextClassLoader
            ?: BuiltInTemplateLoader::class.java.classLoader

        val indexText = loader.getResourceAsStream("prompt_templates/_index.txt")
            ?.bufferedReader()?.readText()
            ?: run {
                logger.warn(
                    "[BuiltInTemplateLoader] 找不到 prompt_templates/_index.txt，内置模板返回空列表",
                    isHappensFrequently = false,
                    err = null,
                )
                return emptyList()
            }

        val ids = indexText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        val templates = ids.mapNotNull { id -> loadOne(loader, id) }
        logger.debug("[BuiltInTemplateLoader] 已加载内置模板 ${templates.size} 条")
        return templates
    }

    private fun loadOne(loader: ClassLoader, id: String): PromptTemplate? {
        // ── 元数据 ────────────────────────────────────────────────────────────
        val metaJson = loader.getResourceAsStream("prompt_templates/$id.meta.json")
            ?.bufferedReader()?.readText()
            ?: run {
                logger.warn(
                    "[BuiltInTemplateLoader] 找不到元数据文件: prompt_templates/$id.meta.json",
                    isHappensFrequently = false,
                    err = null,
                )
                return null
            }

        val meta = metaJson.loadJsonModel<TemplateMeta>().getOrElse {
            logger.warn(
                "[BuiltInTemplateLoader] 解析元数据失败: $id",
                isHappensFrequently = false,
                err = it,
            )
            return null
        }

        // ── 脚本正文 ──────────────────────────────────────────────────────────
        val scriptCode = loader.getResourceAsStream("prompt_templates/$id.js")
            ?.bufferedReader()?.readText()
            ?: run {
                logger.warn(
                    "[BuiltInTemplateLoader] 找不到脚本文件: prompt_templates/$id.js",
                    isHappensFrequently = false,
                    err = null,
                )
                return null
            }

        return PromptTemplate(
            id = meta.id,
            name = meta.name,
            description = meta.description,
            category = meta.category,
            sourceType = "system",
            scriptLanguage = "javascript",
            scriptCode = scriptCode.trimEnd(),
            schemaTarget = meta.schemaTarget,
            basedOnTemplateId = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    @Serializable
    private data class TemplateMeta(
        val id: String,
        val name: String,
        val description: String = "",
        val category: String = "",
        @SerialName("schema_target") val schemaTarget: String = "",
    )
}
