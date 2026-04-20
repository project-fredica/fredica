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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

object MaterialCategorySyncBilibiliVideoPagesExecutor : TaskExecutor {
    override val taskType = "SYNC_BILIBILI_VIDEO_PAGES"
    private val logger = createLogger()

    @Serializable
    data class Payload(
        @SerialName("platform_info_id") val platformInfoId: String,
    )

    interface ApiClient {
        suspend fun getPages(bvid: String): JsonArray
        suspend fun getInfo(bvid: String): JsonObject
    }

    var apiClient: ApiClient = DefaultApiClient

    private object DefaultApiClient : ApiClient {
        override suspend fun getPages(bvid: String): JsonArray {
            val body = buildJsonObject {}.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/video/get-pages/$bvid", body)
            return AppUtil.GlobalVars.json.parseToJsonElement(resp) as JsonArray
        }

        override suspend fun getInfo(bvid: String): JsonObject {
            val body = buildJsonObject {}.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/video/get-info/$bvid", body)
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

        if (config !is MaterialCategorySyncPlatformIdentity.BilibiliVideoPages) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(platformInfo.id, "不支持的同步类型: ${config.syncType}")
            return@withContext ExecuteResult(
                error = "不支持的同步类型: ${config.syncType}",
                errorType = "UNSUPPORTED_TYPE",
            )
        }

        val bvid = config.bvid
        val categoryId = platformInfo.categoryId

        logger.debug("SyncBilibiliVideoPages: payload parsed, platformInfoId=${payload.platformInfoId}, bvid=$bvid, categoryId=$categoryId")

        try {
            val info = apiClient.getInfo(bvid)
            val title = info["title"]?.jsonPrimitive?.content ?: ""
            if (title.isNotEmpty()) {
                MaterialCategorySyncPlatformInfoService.repo.updateDisplayName(platformInfo.id, title)
            }
        } catch (e: Throwable) {
            logger.warn("SyncBilibiliVideoPages: 获取视频标题失败 bvid=$bvid: ${e.message}")
        }

        logger.info("SyncBilibiliVideoPages: 开始同步 bvid=$bvid categoryId=$categoryId [taskId=${task.id}]")

        MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "syncing")

        try {
            val pages = apiClient.getPages(bvid)
            logger.debug("SyncBilibiliVideoPages: fetched ${pages.size} pages for bvid=$bvid [taskId=${task.id}]")

            val items = mutableListOf<MaterialCategorySyncItem>()
            val materialIds = mutableListOf<String>()
            val materialVideos = mutableListOf<MaterialVideo>()
            val nowSec = System.currentTimeMillis() / 1000L

            for (pageElem in pages) {
                val obj = pageElem.jsonObject
                val pageNum = obj["page"]?.jsonPrimitive?.int ?: continue
                val materialId = bilibiliVideoId(bvid, pageNum)
                val platformItemId = "${bvid}_P$pageNum"
                val partTitle = obj["part"]?.jsonPrimitive?.content ?: ""
                val duration = obj["duration"]?.jsonPrimitive?.int ?: 0

                val extraJson = buildJsonObject {
                    put("bvid", bvid)
                    put("page", pageNum)
                }.toString()

                materialVideos.add(
                    MaterialVideo(
                        id = materialId,
                        type = MaterialType.VIDEO,
                        sourceType = "bilibili_video_pages",
                        sourceId = bvid,
                        title = partTitle.ifEmpty { "P$pageNum" },
                        coverUrl = "",
                        description = "",
                        duration = duration,
                        localVideoPath = "",
                        localAudioPath = "",
                        transcriptPath = "",
                        extra = extraJson,
                        createdAt = nowSec,
                        updatedAt = nowSec,
                    )
                )

                items.add(
                    MaterialCategorySyncItem(
                        id = UUID.randomUUID().toString(),
                        platformInfoId = platformInfo.id,
                        materialId = materialId,
                        platformItemId = platformItemId,
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
                logger.debug("SyncBilibiliVideoPages: upserted ${items.size} pages for bvid=$bvid [taskId=${task.id}]")
            }

            val itemCount = MaterialCategorySyncItemService.repo.countByPlatformInfo(platformInfo.id)
            val now = System.currentTimeMillis() / 1000L

            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncSuccess(
                id = platformInfo.id,
                syncCursor = "",
                lastSyncedAt = now,
                itemCount = itemCount,
            )

            logger.info("SyncBilibiliVideoPages: 同步完成 bvid=$bvid total=$itemCount [taskId=${task.id}]")

            val result = buildJsonObject {
                put("synced_count", items.size)
                put("total_count", itemCount)
            }.toString()
            ExecuteResult(result = result)
        } catch (e: Throwable) {
            logger.error("SyncBilibiliVideoPages: 同步失败 bvid=$bvid [taskId=${task.id}]: ${e.message}")
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(
                id = platformInfo.id,
                error = e.message ?: "Unknown error",
            )
            ExecuteResult(error = "同步失败: ${e.message}", errorType = "SYNC_ERROR")
        }
    }
}
