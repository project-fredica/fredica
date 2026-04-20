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

object MaterialCategorySyncBilibiliFavoriteExecutor : TaskExecutor {
    override val taskType = "SYNC_BILIBILI_FAVORITE"
    private val logger = createLogger()

    @Serializable
    data class Payload(
        @SerialName("platform_info_id") val platformInfoId: String,
    )

    interface ApiClient {
        suspend fun getPage(fid: String, page: Int): JsonObject
        suspend fun getInfo(fid: String): JsonObject
    }

    var apiClient: ApiClient = DefaultApiClient

    private object DefaultApiClient : ApiClient {
        override suspend fun getPage(fid: String, page: Int): JsonObject {
            val body = buildJsonObject {
                put("fid", fid)
                put("page", page)
            }.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/favorite/get-page", body)
            return AppUtil.GlobalVars.json.parseToJsonElement(resp).jsonObject
        }

        override suspend fun getInfo(fid: String): JsonObject {
            val body = buildJsonObject { put("fid", fid) }.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/favorite/get-info", body)
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

        if (config !is MaterialCategorySyncPlatformIdentity.BilibiliFavorite) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(platformInfo.id, "不支持的同步类型: ${config.syncType}")
            return@withContext ExecuteResult(
                error = "不支持的同步类型: ${config.syncType}",
                errorType = "UNSUPPORTED_TYPE",
            )
        }

        val fid = config.mediaId.toString()
        val categoryId = platformInfo.categoryId
        val oldCursor = platformInfo.syncCursor.toLongOrNull() ?: 0L

        logger.debug("SyncBilibiliFavorite: payload parsed, platformInfoId=${payload.platformInfoId}, fid=$fid, categoryId=$categoryId, oldCursor=$oldCursor")

        try {
            val info = apiClient.getInfo(fid)
            val title = info["title"]?.jsonPrimitive?.content ?: ""
            if (title.isNotEmpty()) {
                MaterialCategorySyncPlatformInfoService.repo.updateDisplayName(platformInfo.id, title)
            }
        } catch (e: Throwable) {
            logger.warn("SyncBilibiliFavorite: 获取收藏夹名称失败 fid=$fid: ${e.message}")
        }

        logger.info("SyncBilibiliFavorite: 开始同步 fid=$fid categoryId=$categoryId cursor=$oldCursor [taskId=${task.id}]")

        MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "syncing")

        try {
            var page = 1
            var newCursor = oldCursor
            var totalSynced = 0
            var reachedOld = false

            while (!reachedOld) {
                val resp = apiClient.getPage(fid, page)
                val medias = resp["medias"]?.jsonArray ?: break

                logger.debug("SyncBilibiliFavorite: page=$page, mediaCount=${medias.size} [taskId=${task.id}]")
                if (medias.isEmpty()) break

                val items = mutableListOf<MaterialCategorySyncItem>()
                val materialIds = mutableListOf<String>()
                val videos = mutableListOf<MaterialVideo>()
                val nowSec = System.currentTimeMillis() / 1000L

                for (media in medias) {
                    val obj = media.jsonObject
                    val bvid = obj["bvid"]?.jsonPrimitive?.content ?: continue
                    val favTime = obj["fav_time"]?.jsonPrimitive?.long ?: 0L

                    if (oldCursor > 0 && favTime <= oldCursor) {
                        logger.debug("SyncBilibiliFavorite: reachedOld at bvid=$bvid, favTime=$favTime <= oldCursor=$oldCursor [taskId=${task.id}]")
                        reachedOld = true
                        break
                    }

                    val materialId = bilibiliVideoId(bvid)
                    val title = obj["title"]?.jsonPrimitive?.content ?: ""
                    val cover = obj["cover"]?.jsonPrimitive?.content ?: ""
                    val intro = obj["intro"]?.jsonPrimitive?.content ?: ""
                    val duration = obj["duration"]?.jsonPrimitive?.int ?: 0
                    val upper = obj["upper"]?.jsonObject
                    val cntInfo = obj["cnt_info"]?.jsonObject

                    val extraJson = buildJsonObject {
                        put("upper_name", upper?.get("name")?.jsonPrimitive?.content ?: "")
                        put("upper_face_url", upper?.get("face")?.jsonPrimitive?.content ?: "")
                        put("upper_mid", upper?.get("mid")?.jsonPrimitive?.long ?: 0L)
                        put("cnt_play", cntInfo?.get("play")?.jsonPrimitive?.int ?: 0)
                        put("cnt_collect", cntInfo?.get("collect")?.jsonPrimitive?.int ?: 0)
                        put("cnt_danmaku", cntInfo?.get("danmaku")?.jsonPrimitive?.int ?: 0)
                        put("fav_time", favTime)
                        put("bvid", bvid)
                    }.toString()

                    videos.add(
                        MaterialVideo(
                            id = materialId,
                            type = MaterialType.VIDEO,
                            sourceType = "bilibili_favorite",
                            sourceId = bvid,
                            title = title,
                            coverUrl = cover,
                            description = intro,
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
                            platformItemId = bvid,
                            syncedAt = nowSec,
                        )
                    )
                    materialIds.add(materialId)

                    if (favTime > newCursor) {
                        newCursor = favTime
                    }
                }

                if (items.isNotEmpty()) {
                    MaterialVideoService.repo.upsertAll(videos)
                    MaterialCategorySyncItemService.repo.upsertBatch(items)
                    MaterialCategoryService.repo.linkMaterials(
                        materialIds = materialIds,
                        categoryIds = listOf(categoryId),
                        addedBy = "sync",
                    )
                    totalSynced += items.size
                    logger.debug("SyncBilibiliFavorite: page=$page upserted=${items.size}, totalSynced=$totalSynced, newCursor=$newCursor [taskId=${task.id}]")
                }

                val hasMore = resp["has_more"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                logger.debug("SyncBilibiliFavorite: page=$page hasMore=$hasMore, reachedOld=$reachedOld [taskId=${task.id}]")
                if (!hasMore) break
                page++
            }

            val itemCount = MaterialCategorySyncItemService.repo.countByPlatformInfo(platformInfo.id)
            val now = System.currentTimeMillis() / 1000L

            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncSuccess(
                id = platformInfo.id,
                syncCursor = newCursor.toString(),
                lastSyncedAt = now,
                itemCount = itemCount,
            )

            logger.info("SyncBilibiliFavorite: 同步完成 fid=$fid synced=$totalSynced total=$itemCount [taskId=${task.id}]")

            val result = buildJsonObject {
                put("synced_count", totalSynced)
                put("total_count", itemCount)
                put("new_cursor", newCursor.toString())
            }.toString()
            ExecuteResult(result = result)
        } catch (e: Throwable) {
            logger.error("SyncBilibiliFavorite: 同步失败 fid=$fid [taskId=${task.id}]: ${e.message}")
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(
                id = platformInfo.id,
                error = e.message ?: "Unknown error",
            )
            ExecuteResult(error = "同步失败: ${e.message}", errorType = "SYNC_ERROR")
        }
    }
}
