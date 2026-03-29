package com.github.project_fredica.prompt

// =============================================================================
// PromptRuntimeContextProvider (jvmMain)
// =============================================================================
//
// 为 GraalJS 沙箱提供素材数据访问。
//
// 设计约束：
//   - 所有公开方法均为普通（非 suspend）函数，通过 runBlocking 调用 DB / 路由。
//   - 由 PromptScriptRuntime 将这些方法注入为 GraalJS ProxyExecutable。
//   - 每次执行创建一次实例，追踪 API 调用次数（上限 20 次）。
//
// getVar 路径约定：
//   - `material/{materialId}/title`               — 素材标题
//   - `material/{materialId}/description`         — 素材描述
//   - `material/{materialId}/subtitles/{lan}`     — 指定语言字幕全文
//       lan 示例：ai-zh · zh（空串等同于取首条）
//   materialId 由前端编辑器在脚本头部注入（`var __materialId = "..."`），
//   脚本通过路径字符串拼接显式传递，后端解析路径提取 materialId 与 lan。
// =============================================================================

import com.github.project_fredica.api.routes.MaterialGetRoute
import com.github.project_fredica.api.routes.MaterialSubtitleContentParam
import com.github.project_fredica.api.routes.MaterialSubtitleContentResponse
import com.github.project_fredica.api.routes.MaterialSubtitleContentRoute
import com.github.project_fredica.api.routes.MaterialSubtitleItem
import com.github.project_fredica.api.routes.MaterialSubtitleListRoute
import com.github.project_fredica.api.routes.WebenSummary
import com.github.project_fredica.api.routes.jsonSchemaString
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.BilibiliSubtitleUtil
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.toJsonArray
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.MaterialVideoService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class PromptRuntimeContextProvider {
    private val logger = createLogger { "PromptRuntimeContextProvider" }
    private var apiCallCount = 0

    companion object {
        const val MAX_API_CALLS = 20

        // 匹配 "material/{materialId}/{subPath}"，subPath 可含斜杠
        private val MATERIAL_PATH_REGEX = Regex("""^material/([^/]+)/(.+)$""")
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private fun checkQuota() {
        if (++apiCallCount > MAX_API_CALLS) {
            throw RuntimeException("API 调用次数超出限制（最多 $MAX_API_CALLS 次）")
        }
    }

    // ── 公开 API（注入为 GraalJS 宿主函数）────────────────────────────────────

    /**
     * 读取模板变量。路径格式：`material/{materialId}/{subPath}`
     *
     * 支持的 subPath：
     *   - `title`                — 素材标题
     *   - `description`          — 素材描述
     *   - `subtitles/{lan}`      — 字幕全文；lan 为语言代码（如 ai-zh、zh），
     *                              空串表示取首条。
     *                              先查本地缓存，未命中时兜底触发 body 拉取。
     *
     * 示例（脚本中）：
     *   `await getVar("material/" + __materialId + "/subtitles/default_text")`
     *   `await getVar("material/" + __materialId + "/subtitles/ai-zh")`
     */
    fun getVar(key: String): String {
        checkQuota()
        logger.debug("[PromptRuntimeContextProvider] getVar key=$key")
        val match = MATERIAL_PATH_REGEX.find(key)
        if (match != null) {
            val materialId = match.groupValues[1]
            val subPath = match.groupValues[2]
            return when {
                subPath == "title" -> runBlocking {
                    try {
                        MaterialVideoService.repo.findById(materialId)?.title ?: ""
                    } catch (e: Throwable) {
                        logger.error("[PromptRuntimeContextProvider] getVar title 读取失败 materialId=$materialId", e)
                        ""
                    }
                }

                subPath == "description" -> runBlocking {
                    try {
                        MaterialVideoService.repo.findById(materialId)?.description ?: ""
                    } catch (e: Throwable) {
                        logger.error(
                            "[PromptRuntimeContextProvider] getVar description 读取失败 materialId=$materialId",
                            e
                        )
                        ""
                    }
                }

                subPath.startsWith("subtitles/") -> {
                    val lan = subPath.removePrefix("subtitles/").takeIf { it.isNotBlank() }.let {
                        if (it == "first") null else it
                    }
                    runBlocking {
                        try {
                            BilibiliSubtitleUtil.fetchSubtitleTextFromCache(materialId, lan)
                                ?: fetchSubtitleText(materialId, lan)
                                ?: "".also { logger.debug("subtitle empty") }
                        } catch (e: Throwable) {
                            logger.error(
                                "[PromptRuntimeContextProvider] getVar subtitle 读取失败 materialId=$materialId lan=$lan",
                                e,
                            )
                            ""
                        }
                    }
                }

                else -> {
                    logger.warn(
                        "[PromptRuntimeContextProvider] 未知 subPath=$subPath key=$key",
                        isHappensFrequently = false, err = null,
                    )
                    ""
                }
            }
        }
        // 路径以 "material/" 开头但未匹配正则，最常见原因是 materialId 为空串
        // （前端注入了 var __materialId = ""，属于调用方 bug，不是正常情况）
        if (key.startsWith("material/")) {
            logger.warn(
                "[PromptRuntimeContextProvider] material 路径格式错误 key='$key'（期望 material/{id}/{subPath}，__materialId 可能为空）",
                isHappensFrequently = false, err = null,
            )
            return ""
        }
        logger.warn(
            "[PromptRuntimeContextProvider] 未知变量 key='$key'（期望格式：material/{id}/{subPath}）",
            isHappensFrequently = false, err = null,
        )
        return ""
    }

    /**
     * 返回变量的 Schema 说明（用于脚本开发参考）。
     *
     * 支持的键：
     *   - `material/{id}/title`                   — 字段说明
     *   - `material/{id}/description`             — 字段说明
     *   - `material/{id}/subtitles/{lan}`         — 字段说明（含语言参数说明）
     *   - `weben/summary` / `weben_schema_hint`   — Weben JSON 结构约束
     */
    fun getSchemaHint(key: String): String {
        val match = MATERIAL_PATH_REGEX.find(key)
        if (match != null) {
            val subPath = match.groupValues[2]
            return when {
                subPath == "title" -> "String — 素材标题"
                subPath == "description" -> "String — 素材描述"
                subPath.startsWith("subtitles/") ->
                    "String — 字幕全文（换行分隔）；路径格式 subtitles/{lan}，如 subtitles/ai-zh、subtitles/zh；lan 为空则取首条"

                else -> "unknown"
            }
        }
        return when (key) {
            "weben/summary", "weben_schema_hint" -> buildWebenSchemaHint()
            else -> "unknown"
        }
    }

    /**
     * 调用指定内部路由并返回 JSON 字符串。
     * 支持的路由：MaterialSubtitleListRoute · MaterialSubtitleContentRoute · MaterialGetRoute
     *
     * param 格式由调用方负责，直接透传给路由 handler：
     *   - MaterialSubtitleListRoute / MaterialGetRoute：`{"material_id": ["id"]}` 或 `{"id": ["id"]}`
     *   - MaterialSubtitleContentRoute：`{"subtitle_url": "...", "source": "..."}`
     */
    fun readRoute(routeName: String, param: String): String {
        checkQuota()
        logger.debug("[PromptRuntimeContextProvider] readRoute routeName=$routeName")
        return runBlocking {
            try {
                when (routeName) {
                    "MaterialSubtitleListRoute" -> MaterialSubtitleListRoute.handler(param).str
                    "MaterialSubtitleContentRoute" -> MaterialSubtitleContentRoute.handler(param).str
                    "MaterialGetRoute" -> MaterialGetRoute.handler(param).str
                    else -> {
                        logger.warn(
                            "[PromptRuntimeContextProvider] 未知路由 routeName=$routeName",
                            isHappensFrequently = true, err = null,
                        )
                        buildValidJson { kv("error", "unknown route: $routeName") }.str
                    }
                }
            } catch (e: Throwable) {
                logger.error("[PromptRuntimeContextProvider] readRoute 失败 routeName=$routeName", e)
                buildValidJson { kv("error", e.message ?: "unknown") }.str
            }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────────

    /**
     * 字幕兜底查询：meta 必须在缓存中，body 若未缓存则触发 Python 拉取。
     * 查不到、无匹配语言或 body 为空时返回 null。
     *
     * @param language 语言代码（如 `"ai-zh"`），null 取首条。
     */
    private suspend fun fetchSubtitleText(materialId: String, language: String?): String? {
        val queryParam = buildValidJson {
            kv("material_id", listOf(JsonPrimitive(materialId)).toJsonArray())
        }.str
        val items = MaterialSubtitleListRoute.handler(queryParam).str
            .loadJsonModel<List<MaterialSubtitleItem>>().getOrElse { return null }
        if (items.isEmpty()) return null
        val item = if (language != null) {
            items.firstOrNull { it.lan == language } ?: return null
        } else {
            items.first()
        }
        val contentParam = AppUtil.dumpJsonStr(
            MaterialSubtitleContentParam(subtitleUrl = item.subtitleUrl, source = item.source)
        ).getOrThrow().str
        return MaterialSubtitleContentRoute.handler(contentParam).str
            .loadJsonModel<MaterialSubtitleContentResponse>().getOrNull()
            ?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * 返回 Weben 提取结果对应的 JSON Schema。
     */
    private fun buildWebenSchemaHint(): String = WebenSummary::class.jsonSchemaString
}
