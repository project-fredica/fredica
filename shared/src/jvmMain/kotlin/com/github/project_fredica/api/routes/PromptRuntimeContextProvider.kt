package com.github.project_fredica.api.routes

// =============================================================================
// PromptRuntimeContextProvider (jvmMain)
// =============================================================================
//
// 为 GraalJS 沙箱提供素材数据访问。
//
// 设计约束：
//   - 所有公开方法均为普通（非 suspend）函数，通过 runBlocking 调用 DB / 路由。
//   - 由 PromptScriptRuntime 将这些方法注入为 GraalJS ProxyExecutable。
//   - 每次执行创建一次实例，持有 materialId 并追踪 API 调用次数（上限 20 次）。
// =============================================================================

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toJsonArray
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class PromptRuntimeContextProvider(private val materialId: String) {
    private val logger = createLogger { "PromptRuntimeContextProvider" }
    private var apiCallCount = 0

    companion object {
        const val MAX_API_CALLS = 20
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private fun checkQuota() {
        if (++apiCallCount > MAX_API_CALLS) {
            throw RuntimeException("API 调用次数超出限制（最多 $MAX_API_CALLS 次）")
        }
    }

    // ── 公开 API（注入为 GraalJS 宿主函数）────────────────────────────────────

    /**
     * 读取模板变量。
     *
     * 支持的键：
     *   - `material.title` / `material/current/title`        — 素材标题
     *   - `material.description`                             — 素材描述
     *   - `subtitle` / `material/current/subtitles/default_text` — 第一条字幕全文
     */
    fun getVar(key: String): String {
        checkQuota()
        return when (key) {
            "material.title", "material/current/title" -> runBlocking {
                MaterialVideoService.repo.findById(materialId)?.title ?: ""
            }
            "material.description" -> runBlocking {
                MaterialVideoService.repo.findById(materialId)?.description ?: ""
            }
            "subtitle", "material/current/subtitles/default_text" -> runBlocking { fetchSubtitleText() }
            else -> {
                logger.warn(
                    "[PromptRuntimeContextProvider] 未知变量 key=$key",
                    isHappensFrequently = true, err = null,
                )
                ""
            }
        }
    }

    /**
     * 返回变量的 Schema 说明（用于脚本开发参考）。
     *
     * 支持的键：
     *   - `material.title` / `material.description` / `subtitle` — 字段说明
     *   - `weben/summary` / `weben_schema_hint`                  — Weben JSON 结构约束
     */
    fun getSchemaHint(key: String): String = when (key) {
        "material.title", "material/current/title" -> "String — 素材标题"
        "material.description" -> "String — 素材描述"
        "subtitle", "material/current/subtitles/default_text" -> "String — 字幕全文（换行分隔）"
        "weben/summary", "weben_schema_hint" -> buildWebenSchemaHint()
        else -> "unknown"
    }

    /**
     * 调用指定内部路由并返回 JSON 字符串。
     * 支持的路由：MaterialSubtitleListRoute · MaterialSubtitleContentRoute · MaterialGetRoute
     */
    fun readRoute(routeName: String, param: String): String {
        checkQuota()
        return runBlocking {
            when (routeName) {
                "MaterialSubtitleListRoute" -> MaterialSubtitleListRoute.handler(
                    buildValidJson {
                        kv("material_id", listOf(JsonPrimitive(materialId)).toJsonArray())
                    }.str
                ).str
                "MaterialSubtitleContentRoute" -> MaterialSubtitleContentRoute.handler(param).str
                "MaterialGetRoute" -> MaterialGetRoute.handler(
                    buildValidJson {
                        kv("id", listOf(JsonPrimitive(materialId)).toJsonArray())
                    }.str
                ).str
                else -> {
                    logger.warn(
                        "[PromptRuntimeContextProvider] 未知路由 routeName=$routeName",
                        isHappensFrequently = true, err = null,
                    )
                    buildValidJson { kv("error", "unknown route: $routeName") }.str
                }
            }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────

    /** 获取素材第一条字幕的全文。找不到或失败时返回空串。 */
    private suspend fun fetchSubtitleText(): String {
        val queryParam = buildValidJson {
            kv("material_id", listOf(JsonPrimitive(materialId)).toJsonArray())
        }.str
        val items = MaterialSubtitleListRoute.handler(queryParam).str
            .loadJsonModel<List<MaterialSubtitleItem>>().getOrElse { return "" }
        if (items.isEmpty()) return ""
        val first = items.first()
        val contentParam = AppUtil.dumpJsonStr(
            MaterialSubtitleContentParam(subtitleUrl = first.subtitleUrl, source = first.source)
        ).getOrThrow().str
        return MaterialSubtitleContentRoute.handler(contentParam).str
            .loadJsonModel<MaterialSubtitleContentResponse>().getOrNull()?.text ?: ""
    }

    /**
     * 构建 Weben 知识提取 JSON 结构约束提示（与前端 buildWebenSchemaHint() 保持一致）。
     * 概念类型与谓词枚举须与 fredica-webui/app/util/weben.ts 中的 CONCEPT_TYPES / PREDICATES 保持同步。
     */
    private fun buildWebenSchemaHint(): String {
        val conceptTypes = listOf(
            "术语", "理论", "协议", "算法", "器件芯片",
            "公式", "设计模式", "工具软件", "硬件经验", "开发经验", "方法技巧",
        )
        val predicates = listOf("包含", "依赖", "用于", "对比", "是...的实例", "实现", "扩展")
        return """
            请严格输出如下 JSON 结构：
            {
              "concepts": [{ "name": string, "type": string, "description": string, "aliases"?: string[] }],
              "relations": [{ "subject": string, "predicate": string, "object": string, "excerpt"?: string }],
              "flashcards": [{ "question": string, "answer": string, "concept": string }]
            }
            concept.type 可选值：${conceptTypes.joinToString("、")}
            relation.predicate 可选值：${predicates.joinToString("、")}
        """.trimIndent()
    }
}
