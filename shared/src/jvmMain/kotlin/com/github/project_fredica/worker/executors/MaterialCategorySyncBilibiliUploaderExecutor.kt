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
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.material_category.model.MaterialCategorySyncItem
import com.github.project_fredica.material_category.model.MaterialCategorySyncPlatformIdentity
import com.github.project_fredica.material_category.service.MaterialCategoryService
import com.github.project_fredica.material_category.service.MaterialCategorySyncItemService
import com.github.project_fredica.material_category.service.MaterialCategorySyncPlatformInfoService
import com.github.project_fredica.python.PythonUtil
import com.github.project_fredica.worker.ExecuteResult
import com.github.project_fredica.worker.TaskPauseResumeChannels
import com.github.project_fredica.worker.WebSocketTaskExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.github.project_fredica.bilibili_account_pool.service.BilibiliAccountPoolService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

object MaterialCategorySyncBilibiliUploaderExecutor : WebSocketTaskExecutor() {
    override val taskType = "SYNC_BILIBILI_UPLOADER"
    private val logger = createLogger()

    @Serializable
    data class Payload(
        @SerialName("platform_info_id") val platformInfoId: String,
    )

    interface SyncClient {
        suspend fun getInfo(mid: String): JsonObject
        suspend fun runSync(
            paramJson: String,
            onRawMessage: suspend (String) -> Unit,
            onProgress: suspend (Int) -> Unit,
            cancelSignal: CompletableDeferred<Unit>,
            pauseResumeChannels: TaskPauseResumeChannels,
        ): String?
    }

    var syncClient: SyncClient = DefaultSyncClient

    private object DefaultSyncClient : SyncClient {
        override suspend fun getInfo(mid: String): JsonObject {
            val body = buildJsonObject { put("mid", mid) }.toString()
            val resp = FredicaApi.PyUtil.post("/bilibili/uploader/get-info", body)
            return AppUtil.GlobalVars.json.parseToJsonElement(resp).jsonObject
        }

        override suspend fun runSync(
            paramJson: String,
            onRawMessage: suspend (String) -> Unit,
            onProgress: suspend (Int) -> Unit,
            cancelSignal: CompletableDeferred<Unit>,
            pauseResumeChannels: TaskPauseResumeChannels,
        ): String? {
            return PythonUtil.Py314Embed.PyUtilServer.websocketTask(
                pth = "/bilibili/uploader/sync-task",
                paramJson = paramJson,
                onProgress = { pct -> onProgress(pct) },
                onRawMessage = { msg -> onRawMessage(msg) },
                cancelSignal = cancelSignal,
                pauseChannel = pauseResumeChannels.pause,
                resumeChannel = pauseResumeChannels.resume,
            )
        }
    }

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
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

        if (config !is MaterialCategorySyncPlatformIdentity.BilibiliUploader) {
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(platformInfo.id, "不支持的同步类型: ${config.syncType}")
            return@withContext ExecuteResult(
                error = "不支持的同步类型: ${config.syncType}",
                errorType = "UNSUPPORTED_TYPE",
            )
        }

        val mid = config.mid.toString()
        val categoryId = platformInfo.categoryId
        val oldCursor = platformInfo.syncCursor.toLongOrNull() ?: 0L

        logger.debug("SyncBilibiliUploader: payload parsed, platformInfoId=${payload.platformInfoId}, mid=$mid, categoryId=$categoryId, oldCursor=$oldCursor")

        try {
            val info = syncClient.getInfo(mid)
            val name = info["name"]?.jsonPrimitive?.content ?: ""
            if (name.isNotEmpty()) {
                MaterialCategorySyncPlatformInfoService.repo.updateDisplayName(platformInfo.id, name)
            }
        } catch (e: Throwable) {
            logger.warn("SyncBilibiliUploader: 获取UP主名称失败 mid=$mid: ${e.message}")
        }

        logger.info("SyncBilibiliUploader: 开始同步 mid=$mid categoryId=$categoryId cursor=$oldCursor [taskId=${task.id}]")

        MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "syncing")

        var newCursor = oldCursor
        var totalSynced = 0

        try {
            val accounts = BilibiliAccountPoolService.buildSyncAccountList()
            logger.debug("SyncBilibiliUploader: built ${accounts.size} sync accounts [taskId=${task.id}]")
            val paramJson = buildJsonObject {
                put("mid", mid)
                put("old_cursor", oldCursor)
                put("accounts", accounts)
            }.toString()

            val result = syncClient.runSync(
                paramJson = paramJson,
                onRawMessage = { rawMsg ->
                    val json = AppUtil.GlobalVars.json.parseToJsonElement(rawMsg).jsonObject
                    val msgType = json["type"]?.jsonPrimitive?.content
                    when (msgType) {
                        "page" -> {
                            val videos = json["videos"]?.jsonArray ?: return@runSync
                            val pageNum = json["page"]?.jsonPrimitive?.content ?: "?"
                            logger.debug("SyncBilibiliUploader: received page=$pageNum, videoCount=${videos.size} [taskId=${task.id}]")
                            val items = mutableListOf<MaterialCategorySyncItem>()
                            val materialIds = mutableListOf<String>()
                            val materialVideos = mutableListOf<MaterialVideo>()
                            val nowSec = System.currentTimeMillis() / 1000L

                            for (video in videos) {
                                val obj = video.jsonObject
                                val bvid = obj["bvid"]?.jsonPrimitive?.content ?: continue
                                val pubdate = obj["created"]?.jsonPrimitive?.long ?: 0L

                                val materialId = bilibiliVideoId(bvid)
                                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                                val cover = obj["pic"]?.jsonPrimitive?.content ?: ""
                                val description = obj["description"]?.jsonPrimitive?.content ?: ""
                                val lengthStr = obj["length"]?.jsonPrimitive?.content ?: "0:00"
                                val duration = parseLengthToSeconds(lengthStr)

                                val extraJson = buildJsonObject {
                                    put("upper_name", obj["author"]?.jsonPrimitive?.content ?: "")
                                    put("upper_mid", obj["mid"]?.jsonPrimitive?.long ?: 0L)
                                    put("bvid", bvid)
                                }.toString()

                                materialVideos.add(
                                    MaterialVideo(
                                        id = materialId,
                                        type = MaterialType.VIDEO,
                                        sourceType = "bilibili_uploader",
                                        sourceId = bvid,
                                        title = title,
                                        coverUrl = cover,
                                        description = description,
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

                                if (pubdate > newCursor) {
                                    newCursor = pubdate
                                }
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
                                logger.debug("SyncBilibiliUploader: page=$pageNum upserted=${items.size}, totalSynced=$totalSynced, newCursor=$newCursor [taskId=${task.id}]")
                            }
                        }
                        "account_switch" -> {
                            val from = json["from"]?.jsonPrimitive?.content ?: ""
                            val to = json["to"]?.jsonPrimitive?.content ?: ""
                            val reason = json["reason"]?.jsonPrimitive?.content ?: ""
                            logger.info("SyncBilibiliUploader: 账号切换 $from → $to ($reason) [taskId=${task.id}]")
                        }
                    }
                },
                onProgress = { pct ->
                    TaskService.repo.updateProgress(task.id, pct)
                },
                cancelSignal = cancelSignal,
                pauseResumeChannels = pauseResumeChannels,
            )

            if (result == null) {
                logger.info("SyncBilibiliUploader: 同步已取消 [taskId=${task.id}]")
                return@withContext ExecuteResult(error = "用户已取消同步", errorType = "CANCELLED")
            }

            val itemCount = MaterialCategorySyncItemService.repo.countByPlatformInfo(platformInfo.id)
            val now = System.currentTimeMillis() / 1000L

            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncSuccess(
                id = platformInfo.id,
                syncCursor = newCursor.toString(),
                lastSyncedAt = now,
                itemCount = itemCount,
            )

            logger.info("SyncBilibiliUploader: 同步完成 mid=$mid synced=$totalSynced total=$itemCount [taskId=${task.id}]")

            val resultJson = buildJsonObject {
                put("synced_count", totalSynced)
                put("total_count", itemCount)
                put("new_cursor", newCursor.toString())
            }.toString()
            ExecuteResult(result = resultJson)
        } catch (e: Throwable) {
            if (cancelSignal.isCompleted) {
                logger.info("SyncBilibiliUploader: 取消信号已触发，忽略异常 [taskId=${task.id}]: ${e.message}")
                return@withContext ExecuteResult(error = "用户已取消同步", errorType = "CANCELLED")
            }
            logger.error("SyncBilibiliUploader: 同步失败 mid=$mid [taskId=${task.id}]: ${e.message}")
            MaterialCategorySyncPlatformInfoService.repo.updateAfterSyncFailure(
                id = platformInfo.id,
                error = e.message ?: "Unknown error",
            )
            ExecuteResult(error = "同步失败: ${e.message}", errorType = "SYNC_ERROR")
        }
    }

    private fun parseLengthToSeconds(length: String): Int {
        val parts = length.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }
}
