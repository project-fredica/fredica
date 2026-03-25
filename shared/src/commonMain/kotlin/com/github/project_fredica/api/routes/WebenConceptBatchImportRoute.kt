package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.weben.WebenConcept
import com.github.project_fredica.db.weben.WebenConceptAlias
import com.github.project_fredica.db.weben.WebenConceptService
import com.github.project_fredica.db.weben.WebenConceptSource
import com.github.project_fredica.db.weben.WebenFlashcard
import com.github.project_fredica.db.weben.WebenFlashcardService
import com.github.project_fredica.db.weben.WebenRelation
import com.github.project_fredica.db.weben.WebenRelationService
import com.github.project_fredica.db.weben.WebenRelationSource
import com.github.project_fredica.db.weben.WebenSource
import com.github.project_fredica.db.weben.WebenSourceService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /api/v1/WebenConceptBatchImportRoute
 *
 * 将 summary.weben 解析出的 concepts / relations / flashcards 一次性写入 Weben。
 * 当前采取最小闭环策略：
 * - concept 以 canonical_name 幂等合并，不存在则创建
 * - aliases / source excerpt 作为附属信息追加
 * - relation 仅在主客体概念均可解析时写入
 * - flashcard 仅在 concept 可解析时写入
 */
object WebenConceptBatchImportRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptBatchImportRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "批量导入 Weben concepts / relations / flashcards"

    override suspend fun handler(param: String): ValidJsonString {
        return try {
            val p = param.loadJsonModel<WebenConceptBatchImportParam>().getOrThrow()
            logger.debug(
                "WebenConceptBatchImportRoute: materialId=${p.materialId}" +
                    " concepts=${p.concepts.size} relations=${p.relations.size} flashcards=${p.flashcards.size}"
            )

            val source = ensureSource(p)
            val conceptIdByName = linkedMapOf<String, String>()
            var conceptCreated = 0
            var relationImported = 0
            var flashcardImported = 0

            p.concepts.forEach { item ->
                val concept = upsertConcept(item)
                conceptIdByName[item.name] = concept.id
                conceptCreated += if (concept.created) 1 else 0
                appendConceptArtifacts(concept.id, source.id, item)
            }

            p.relations.forEach { item ->
                val subjectId = conceptIdByName[item.subject] ?: WebenConceptService.repo.getByCanonicalName(item.subject)?.id
                val objectName = item.`object`
                val objectId = conceptIdByName[objectName] ?: WebenConceptService.repo.getByCanonicalName(objectName)?.id
                if (subjectId == null || objectId == null) {
                    logger.debug(
                        "WebenConceptBatchImportRoute: 跳过 relation，概念未命中" +
                            " subject=${item.subject} object=$objectName"
                    )
                    return@forEach
                }
                val relation = WebenRelation(
                    id = java.util.UUID.randomUUID().toString(),
                    subjectId = subjectId,
                    predicate = item.predicate,
                    objectId = objectId,
                    confidence = 1.0,
                    isManual = false,
                    createdAt = nowSec(),
                    updatedAt = nowSec(),
                )
                WebenRelationService.repo.upsert(relation)
                if (!item.excerpt.isNullOrBlank()) {
                    WebenRelationService.repo.addSource(
                        WebenRelationSource(
                            relationId = relation.id,
                            sourceId = source.id,
                            excerpt = item.excerpt,
                        )
                    )
                }
                relationImported += 1
            }

            p.flashcards.forEach { item ->
                val conceptId = conceptIdByName[item.concept] ?: WebenConceptService.repo.getByCanonicalName(item.concept)?.id
                if (conceptId == null) {
                    logger.debug("WebenConceptBatchImportRoute: 跳过 flashcard，概念未命中 concept=${item.concept}")
                    return@forEach
                }
                WebenFlashcardService.repo.create(
                    WebenFlashcard(
                        id = java.util.UUID.randomUUID().toString(),
                        conceptId = conceptId,
                        sourceId = source.id,
                        question = item.question,
                        answer = item.answer,
                        cardType = "qa",
                        isSystem = true,
                        nextReviewAt = nowSec(),
                        createdAt = nowSec(),
                    )
                )
                flashcardImported += 1
            }

            logger.info(
                "WebenConceptBatchImportRoute: 导入完成 sourceId=${source.id}" +
                    " conceptCreated=$conceptCreated relationImported=$relationImported flashcardImported=$flashcardImported"
            )
            buildValidJson {
                kv("ok", true)
                kv("source_id", source.id)
                kv("concept_created", conceptCreated)
                kv("concept_total", p.concepts.size)
                kv("relation_imported", relationImported)
                kv("flashcard_imported", flashcardImported)
            }
        } catch (e: Throwable) {
            logger.warn("[WebenConceptBatchImportRoute] 批量导入失败", isHappensFrequently = false, err = e)
            buildValidJson { kv("error", e.message ?: "unknown") }
        }
    }

    private suspend fun ensureSource(param: WebenConceptBatchImportParam): WebenSource {
        val existing = param.sourceId?.let { WebenSourceService.repo.getById(it) }
        if (existing != null) return existing

        val material = param.materialId?.let { MaterialVideoService.repo.findById(it) }
        val nowSec = nowSec()
        val created = WebenSource(
            id = java.util.UUID.randomUUID().toString(),
            materialId = param.materialId,
            url = param.sourceUrl ?: material?.sourceId?.let { "https://www.bilibili.com/video/$it" } ?: "material://${param.materialId ?: "unknown"}",
            title = param.sourceTitle ?: material?.title ?: "Weben 导入结果",
            sourceType = "bilibili_video",
            bvid = null,
            durationSec = material?.duration?.toDouble(),
            qualityScore = 0.8,
            analysisStatus = "completed",
            createdAt = nowSec,
        )
        WebenSourceService.repo.create(created)
        logger.info("WebenConceptBatchImportRoute: 已创建导入来源 sourceId=${created.id} materialId=${param.materialId}")
        return created
    }

    private suspend fun upsertConcept(item: ImportConceptItem): UpsertedConcept {
        val existing = WebenConceptService.repo.getByCanonicalName(item.name)
        if (existing != null) {
            val nowSec = nowSec()
            val updated = existing.copy(
                conceptType = item.type.ifBlank { existing.conceptType },
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
            canonicalName = item.name,
            conceptType = item.type.ifBlank { "术语" },
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
    val relations: List<ImportRelationItem> = emptyList(),
    val flashcards: List<ImportFlashcardItem> = emptyList(),
)

@Serializable
data class ImportConceptItem(
    val name: String,
    val type: String,
    val description: String,
    val aliases: List<String>? = null,
)

@Serializable
data class ImportRelationItem(
    val subject: String,
    val predicate: String,
    val `object`: String,
    val excerpt: String? = null,
)

@Serializable
data class ImportFlashcardItem(
    val question: String,
    val answer: String,
    val concept: String,
)

private data class UpsertedConcept(
    val id: String,
    val created: Boolean,
)
