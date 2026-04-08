package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.warn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.weben.WebenConcept
import com.github.project_fredica.db.weben.WebenConceptAlias
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenConceptSource
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenConceptBatchImportRoute
 *
 * 将 summary.weben 解析出的 concepts 一次性写入 Weben。
 * concept 以 canonical_name 幂等合并，不存在则创建；aliases / source excerpt 作为附属信息追加。
 * `type` 为开放文本字段，后端不维护允许列表，也不注入默认类型。
 */
object WebenConceptBatchImportRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptBatchImportRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "批量导入 Weben concepts"

    override suspend fun handler(param: String): ValidJsonString {
        return try {
            val p = param.loadJsonModel<WebenConceptBatchImportParam>().getOrThrow()
            logger.debug(
                "WebenConceptBatchImportRoute: materialId=${p.materialId}" +
                    " concepts=${p.concepts.size}"
            )

            val source = ensureSource(p)
            var conceptCreated = 0

            p.concepts.forEach { item ->
                val concept = upsertConcept(item, source.materialId)
                conceptCreated += if (concept.created) 1 else 0
                appendConceptArtifacts(concept.id, source.id, item)
            }

            logger.info(
                "WebenConceptBatchImportRoute: 导入完成 sourceId=${source.id}" +
                    " conceptCreated=$conceptCreated"
            )
            buildJsonObject {
                put("ok", true)
                put("source_id", source.id)
                put("concept_created", conceptCreated)
                put("concept_total", p.concepts.size)
            }.toValidJson()
        } catch (e: Throwable) {
            logger.warn("[WebenConceptBatchImportRoute] 批量导入失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }

    private suspend fun ensureSource(param: WebenConceptBatchImportParam): WebenSource {
        val existing = param.sourceId?.let { WebenSourceService.repo.getById(it) }
        if (existing != null) return existing

        val materialId = param.materialId?.takeIf { it.isNotBlank() }
        val material = materialId?.let { MaterialVideoService.repo.findById(it) }
        val nowSec = nowSec()
        val created = WebenSource(
            id = java.util.UUID.randomUUID().toString(),
            materialId = materialId,
            url = param.sourceUrl ?: material?.sourceId?.let { "https://www.bilibili.com/video/$it" } ?: "material://${materialId ?: "unknown"}",
            title = param.sourceTitle ?: material?.title ?: "Weben 导入结果",
            sourceType = "bilibili_video",
            bvid = null,
            durationSec = material?.duration?.toDouble(),
            qualityScore = 0.8,
            analysisStatus = "completed",
            createdAt = nowSec,
        )
        WebenSourceService.repo.create(created)
        logger.info("WebenConceptBatchImportRoute: 已创建导入来源 sourceId=${created.id} materialId=${materialId}")
        return created
    }

    private suspend fun upsertConcept(item: ImportConceptItem, materialId: String?): UpsertedConcept {
        val typeList = item.types.map { it.trim() }.filter { it.isNotBlank() }
        require(typeList.isNotEmpty()) { "concept types 不能全部为空: ${item.name}" }
        val normalizedType = typeList.joinToString(",")
        val existing = WebenConceptService.repo.getByCanonicalName(item.name, materialId)
        if (existing != null) {
            val nowSec = nowSec()
            val updated = existing.copy(
                conceptType = normalizedType,
                briefDefinition = item.description.ifBlank { existing.briefDefinition },
                metadataJson = existing.metadataJson,
                confidence = maxOf(existing.confidence, 1.0),
                lastSeenAt = nowSec,
                updatedAt = nowSec,
            )
            WebenConceptService.repo.update(updated)
            return UpsertedConcept(updated.id, created = false)
        }

        val nowSec = nowSec()
        val created = WebenConcept(
            id = java.util.UUID.randomUUID().toString(),
            materialId = materialId,
            canonicalName = item.name,
            conceptType = normalizedType,
            briefDefinition = item.description.ifBlank { null },
            confidence = 1.0,
            firstSeenAt = nowSec,
            lastSeenAt = nowSec,
            createdAt = nowSec,
            updatedAt = nowSec,
        )
        WebenConceptService.repo.create(created)
        return UpsertedConcept(created.id, created = true)
    }

    private suspend fun appendConceptArtifacts(conceptId: String, sourceId: String, item: ImportConceptItem) {
        item.aliases.orEmpty().map { it.trim() }.filter { it.isNotBlank() && it != item.name }.distinct().forEach { alias ->
            WebenConceptService.repo.addAlias(
                WebenConceptAlias(
                    conceptId = conceptId,
                    alias = alias,
                    aliasSource = "summary.weben",
                )
            )
        }
        if (item.description.isNotBlank()) {
            WebenConceptService.repo.addSource(
                WebenConceptSource(
                    conceptId = conceptId,
                    sourceId = sourceId,
                    excerpt = item.description,
                )
            )
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L
}

@Serializable
data class WebenConceptBatchImportParam(
    @SerialName("material_id") val materialId: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("source_title") val sourceTitle: String? = null,
    val concepts: List<ImportConceptItem> = emptyList(),
)

@Serializable
data class ImportConceptItem(
    val name: String,
    val types: List<String>,
    val description: String,
    val aliases: List<String>? = null,
)

private data class UpsertedConcept(
    val id: String,
    val created: Boolean,
)
