package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.weben.WebenConcept
import com.github.project_fredica.db.weben.WebenConceptAlias
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenConceptSource
import com.github.project_fredica.db.weben.WebenExtractionRun
import com.github.project_fredica.db.weben.WebenExtractionRunService
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenExtractionRunSaveRoute
 *
 * 前端"保存到 Weben"的主入口（取代直接调 WebenConceptBatchImportRoute）。
 * 职责：
 *   1. 创建或复用 WebenSource
 *   2. 批量 upsert 用户审核通过的概念
 *   3. 保存本次提取运行的上下文（提示词、LLM 输入/输出）到 weben_extraction_run
 */
object WebenExtractionRunSaveRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenExtractionRunSaveRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "保存概念提取运行（含提取上下文 + 概念批量写入）"

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val p = param.loadJsonModel<WebenExtractionRunSaveParam>().getOrThrow()
            logger.debug(
                "WebenExtractionRunSaveRoute: materialId=${p.materialId} concepts=${p.concepts.size}"
            )

            val source = ensureSource(p)
            var conceptCreated = 0

            p.concepts.forEach { item ->
                val result = upsertConcept(item, source.id, source.materialId)
                conceptCreated += if (result.created) 1 else 0
            }

            val run = WebenExtractionRun(
                id           = java.util.UUID.randomUUID().toString(),
                sourceId     = source.id,
                materialId   = p.materialId,
                promptScript = p.promptScript,
                promptText   = p.promptText,
                llmModelId   = p.llmModelId,
                llmInputJson = p.llmInputJson,
                llmOutputRaw = p.llmOutputRaw,
                conceptCount = p.concepts.size,
                createdAt    = nowSec(),
            )
            WebenExtractionRunService.repo.create(run)

            logger.info(
                "WebenExtractionRunSaveRoute: 保存完成 sourceId=${source.id} runId=${run.id}" +
                    " conceptCreated=$conceptCreated conceptTotal=${p.concepts.size}"
            )
            buildJsonObject {
                put("ok", true)
                put("source_id", source.id)
                put("run_id", run.id)
                put("concept_created", conceptCreated)
                put("concept_total", p.concepts.size)
            }.toValidJson()
        } catch (e: Throwable) {
            logger.warn("[WebenExtractionRunSaveRoute] 保存失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }

    private suspend fun ensureSource(param: WebenExtractionRunSaveParam): WebenSource {
        val existing = param.sourceId?.let { WebenSourceService.repo.getById(it) }
        if (existing != null) return existing

        val normalizedMaterialId = param.materialId?.takeIf { it.isNotBlank() }
        val material = normalizedMaterialId?.let { MaterialVideoService.repo.findById(it) }
        val nowSec = nowSec()
        val created = WebenSource(
            id             = java.util.UUID.randomUUID().toString(),
            materialId     = normalizedMaterialId,
            url            = param.sourceUrl ?: material?.sourceId?.let { "https://www.bilibili.com/video/$it" }
                ?: normalizedMaterialId?.let { "material://$it" } ?: "",
            title          = param.sourceTitle ?: material?.title ?: "Weben 导入结果",
            sourceType     = "bilibili_video",
            bvid           = null,
            durationSec    = material?.duration?.toDouble(),
            qualityScore   = 0.8,
            analysisStatus = "completed",
            createdAt      = nowSec,
        )
        WebenSourceService.repo.create(created)
        logger.info("WebenExtractionRunSaveRoute: 已创建来源 sourceId=${created.id} materialId=${param.materialId}")
        return created
    }

    private suspend fun upsertConcept(item: ExtractionRunConceptItem, sourceId: String, materialId: String?): UpsertResult {
        val typeList = item.types.map { it.trim() }.filter { it.isNotBlank() }
        require(typeList.isNotEmpty()) { "concept types 不能全部为空: ${item.name}" }
        val normalizedType = typeList.joinToString(",")

        val existing = WebenConceptService.repo.getByCanonicalName(item.name, materialId)
        if (existing != null) {
            val nowSec = nowSec()
            val updated = existing.copy(
                conceptType     = normalizedType,
                briefDefinition = item.description.ifBlank { existing.briefDefinition },
                metadataJson    = existing.metadataJson,
                confidence      = maxOf(existing.confidence, 1.0),
                lastSeenAt      = nowSec,
                updatedAt       = nowSec,
            )
            WebenConceptService.repo.update(updated)
            appendArtifacts(existing.id, sourceId, item)
            return UpsertResult(existing.id, created = false)
        }

        val nowSec = nowSec()
        val created = WebenConcept(
            id              = java.util.UUID.randomUUID().toString(),
            materialId      = materialId,
            canonicalName   = item.name,
            conceptType     = normalizedType,
            briefDefinition = item.description.ifBlank { null },
            confidence      = 1.0,
            firstSeenAt     = nowSec,
            lastSeenAt      = nowSec,
            createdAt       = nowSec,
            updatedAt       = nowSec,
        )
        WebenConceptService.repo.create(created)
        appendArtifacts(created.id, sourceId, item)
        return UpsertResult(created.id, created = true)
    }

    private suspend fun appendArtifacts(conceptId: String, sourceId: String, item: ExtractionRunConceptItem) {
        item.aliases.orEmpty().map { it.trim() }.filter { it.isNotBlank() && it != item.name }.distinct()
            .forEach { alias ->
                WebenConceptService.repo.addAlias(
                    WebenConceptAlias(conceptId = conceptId, alias = alias, aliasSource = "summary.weben")
                )
            }
        if (item.description.isNotBlank()) {
            WebenConceptService.repo.addSource(
                WebenConceptSource(conceptId = conceptId, sourceId = sourceId, excerpt = item.description)
            )
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L
}

@Serializable
data class WebenExtractionRunSaveParam(
    @SerialName("material_id")    val materialId: String? = null,
    @SerialName("source_id")      val sourceId: String? = null,
    @SerialName("source_url")     val sourceUrl: String? = null,
    @SerialName("source_title")   val sourceTitle: String? = null,
    @SerialName("prompt_script")  val promptScript: String? = null,
    @SerialName("prompt_text")    val promptText: String? = null,
    @SerialName("llm_model_id")   val llmModelId: String? = null,
    @SerialName("llm_input_json") val llmInputJson: String? = null,
    @SerialName("llm_output_raw") val llmOutputRaw: String? = null,
    val concepts: List<ExtractionRunConceptItem> = emptyList(),
)

@Serializable
data class ExtractionRunConceptItem(
    val name: String,
    val types: List<String>,
    val description: String,
    val aliases: List<String>? = null,
)

private data class UpsertResult(val id: String, val created: Boolean)
