package com.github.project_fredica.worker.executors

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.bilibiliVideoId
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.json
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialType
import com.github.project_fredica.db.MaterialVideo
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.db.Task
import com.github.project_fredica.material_category.model.MaterialCategorySyncItem
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncItemService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

object MaterialCategorySyncBilibiliSeriesExecutor : TaskExecutor {
    override val taskType = "SYNC_BILIBILI_SERIES"
    private val logger = createLogger()

    @Serializable
    data class Payload(
        @SerialName("platform_info_id") val platformInfoId: String,
    )

    interface ApiClient {
        suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject
        suspend fun getMeta(seriesId: String, mid: String): JsonObject
    }

    var apiClient: ApiClient = DefaultApiClient

    private object DefaultApiClient : ApiClient {
        override suspend fun getPage(seriesId: String, mid: String, page: Int): JsonObject {
            val body = buildJsonObject {
                put("series_id", seriesId)
                put("mid", mid)
                put("page", page)
            }.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/series/get-page", body)
            return AppUtil.GlobalVars.json.parseToJsonElement(resp).jsonObject
        }

        override suspend fun getMeta(seriesId: String, mid: String): JsonObject {
            val body = buildJsonObject {
                put("series_id", seriesId)
                put("mid", mid)
            }.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/series/get-meta", body)
            return AppUtil.GlobalVars.json.parseToJsonElement(resp).jsonObject
        }
    }

    override suspend fun execute(task: Task): ExecuteResult = withContext(Dispatchers.IO) {
        val payload = try {
            task.payload.loadJsonModel<Payload>().getOrThrow()
        } catch (e: Throwable) {
            return@withContext ExecuteResult(error = "Invalid payload: ${e.message}", errorType = "PAYLOAD_ERROR")
        }

        val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(payload.platformInfoId)
            ?: return@withContext ExecuteResult(error = "平台源不存在: ${payload.platformInfoId}", errorType = "NOT_FOUND")

        val config = try {
            AppUtil.GlobalVars.json.decodeFromString(
                MaterialCategorySyncPlatformIdentity.serializer(),
                platformInfo.platformConfig,
            )
        } catch (e: Throwable) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(platformInfo.id, "平台配置解析失败: ${e.message}")
            return@withContext ExecuteResult(error = "平台配置解析失败: ${e.message}", errorType = "CONFIG_ERROR")
        }

        if (config !is MaterialCategorySyncPlatformIdentity.BilibiliSeries) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(platformInfo.id, "不支持的同步类型: ${config.syncType}")
            return@withContext ExecuteResult(
                error = "不支持的同步类型: ${config.syncType}",
                errorType = "UNSUPPORTED_TYPE",
            )
        }

        val seriesId = config.seriesId.toString()
        val mid = config.mid.toString()
        val categoryId = platformInfo.categoryId

        logger.debug("SyncBilibiliSeries: payload parsed, platformInfoId=${payload.platformInfoId}, seriesId=$seriesId, mid=$mid, categoryId=$categoryId")

        try {
            val meta = apiClient.getMeta(seriesId, mid)
            val name = meta["name"]?.jsonPrimitive?.content ?: ""
            if (name.isNotEmpty()) {
                MaterialCategorySyncPlatformInfoService.repo.updateDisplayName(platformInfo.id, name)
            }
        } catch (e: Throwable) {
            logger.warn("SyncBilibiliSeries: 获取列表名称失败 seriesId=$seriesId: ${e.message}")
        }

        logger.info("SyncBilibiliSeries: 开始同步 seriesId=$seriesId mid=$mid categoryId=$categoryId [taskId=${task.id}]")

        MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "syncing")

        try {
            var page = 1
            var totalSynced = 0

            while (true) {
                val resp = apiClient.getPage(seriesId, mid, page)
                val videos = resp["videos"]?.jsonArray ?: break

                logger.debug("SyncBilibiliSeries: page=$page, videoCount=${videos.size} [taskId=${task.id}]")
                if (videos.isEmpty()) break

                val items = mutableListOf<MaterialCategorySyncItem>()
                val materialIds = mutableListOf<String>()
                val materialVideos = mutableListOf<MaterialVideo>()
                val nowSec = System.currentTimeMillis() / 1000L

                for (video in videos) {
                    val obj = video.jsonObject
                    val bvid = obj["bvid"]?.jsonPrimitive?.content ?: continue
                    val materialId = bilibiliVideoId(bvid)
                    val title = obj["title"]?.jsonPrimitive?.content ?: ""
                    val cover = obj["pic"]?.jsonPrimitive?.content ?: ""
                    val duration = obj["duration"]?.jsonPrimitive?.int ?: 0
                    val pubdate = obj["pubdate"]?.jsonPrimitive?.long ?: 0L

                    val extraJson = buildJsonObject {
                        put("bvid", bvid)
                        put("series_id", seriesId)
                    }.toString()

                    materialVideos.add(
                        MaterialVideo(
                            id = materialId,
                            type = MaterialType.VIDEO,
                            sourceType = "bilibili_series",
                            sourceId = bvid,
                            title = title,
                            coverUrl = cover,
                            description = "",
                            duration = duration,
                            localVideoPath = "",
                            localAudioPath = "",
                            transcriptPath = "",
                            extra = extraJson,
                            createdAt = pubdate.takeIf { it > 0 } ?: nowSec,
                            updatedAt = nowSec,
                        )
                    )

                    items.add(
                        MaterialCategorySyncItem(
                            id = UUID.randomUUID().toString(),
                            platformInfoId = platformInfo.id,
                            materialId = materialId,
                            platformItemId = bvid,
                            syncedAt = nowSec,
                        )
                    )
                    materialIds.add(materialId)
                }

                if (items.isNotEmpty()) {
                    MaterialVideoService.repo.upsertAll(materialVideos)
                    MaterialCategorySyncItemService.repo.upsertBatch(items)
                    MaterialCategoryService.repo.linkMaterials(
                        materialIds = materialIds,
                        categoryIds = listOf(categoryId),
                        addedBy = "sync",
                    )
                    totalSynced += items.size
                    logger.debug("SyncBilibiliSeries: page=$page upserted=${items.size}, totalSynced=$totalSynced [taskId=${task.id}]")
                }

                val hasMore = resp["has_more"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                logger.debug("SyncBilibiliSeries: page=$page hasMore=$hasMore [taskId=${task.id}]")
                if (!hasMore) break
                page++
            }

            val itemCount = MaterialCategorySyncItemService.repo.countByPlatformInfo(platformInfo.id)
            val now = System.currentTimeMillis() / 1000L

            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncSuccess(
                id = platformInfo.id,
                syncCursor = "",
                lastSyncedAt = now,
                itemCount = itemCount,
            )

            logger.info("SyncBilibiliSeries: 同步完成 seriesId=$seriesId synced=$totalSynced total=$itemCount [taskId=${task.id}]")

            val result = buildJsonObject {
                put("synced_count", totalSynced)
                put("total_count", itemCount)
            }.toString()
            ExecuteResult(result = result)
        } catch (e: Throwable) {
            logger.error("SyncBilibiliSeries: 同步失败 seriesId=$seriesId [taskId=${task.id}]: ${e.message}")
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(
                id = platformInfo.id,
                error = e.message ?: "Unknown error",
            )
            ExecuteResult(error = "同步失败: ${e.message}", errorType = "SYNC_ERROR")
        }
    }
}
