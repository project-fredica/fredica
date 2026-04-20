package com.github.project_fredica.material_category.service

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.db.CommonWorkflowService
import com.github.project_fredica.db.WorkflowRunStatusService
import com.github.project_fredica.material_category.model.MaterialCategoryAuditLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object MaterialCategorySyncTriggerService {
    private val logger = createLogger()

    sealed class TriggerResult {
        data class Success(val workflowRunId: String) : TriggerResult()
        data class Error(val message: String) : TriggerResult()
    }

    private val SYNC_TYPE_TO_TASK_TYPE = mapOf(
        "bilibili_favorite" to "SYNC_BILIBILI_FAVORITE",
        "bilibili_uploader" to "SYNC_BILIBILI_UPLOADER",
        "bilibili_season" to "SYNC_BILIBILI_SEASON",
        "bilibili_series" to "SYNC_BILIBILI_SERIES",
        "bilibili_video_pages" to "SYNC_BILIBILI_VIDEO_PAGES",
    )

    suspend fun trigger(platformInfoId: String, userId: String): TriggerResult {
        logger.debug("trigger: platformInfoId=$platformInfoId, userId=$userId")

        val platformInfo = MaterialCategorySyncPlatformInfoService.repo.getById(platformInfoId)
            ?: return TriggerResult.Error("同步源不存在")

        logger.debug("trigger: syncType=${platformInfo.syncType}, syncState=${platformInfo.syncState}, categoryId=${platformInfo.categoryId}")

        val userConfig = MaterialCategorySyncUserConfigService.repo
            .findByPlatformInfoAndUser(platformInfo.id, userId)
        if (userConfig == null) {
            return TriggerResult.Error("未订阅该同步源")
        }

        if (platformInfo.syncState == "syncing") {
            logger.debug("trigger: syncState=syncing, reconciling lastWorkflowRunId=${platformInfo.lastWorkflowRunId}")
            if (!reconcileSyncState(platformInfo.id, platformInfo.lastWorkflowRunId)) {
                return TriggerResult.Error("同步任务正在运行")
            }
        }

        val taskType = SYNC_TYPE_TO_TASK_TYPE[platformInfo.syncType]
            ?: return TriggerResult.Error("不支持的同步类型: ${platformInfo.syncType}")

        val taskPayload = buildJsonObject {
            put("platform_info_id", platformInfo.id)
        }.toString()

        logger.debug("trigger: creating workflow, taskType=$taskType, template=sync_${platformInfo.syncType}")

        val workflowRunId = CommonWorkflowService.createWorkflow(
            template = "sync_${platformInfo.syncType}",
            materialId = platformInfo.categoryId,
            tasks = listOf(
                CommonWorkflowService.TaskDef(
                    type = taskType,
                    materialId = platformInfo.categoryId,
                    payload = taskPayload,
                    priority = 10,
                    maxRetries = 0,
                )
            ),
        )

        logger.debug("trigger: workflow created, workflowRunId=$workflowRunId")

        MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfo.id, "syncing")
        MaterialCategorySyncPlatformInfoService.repo.setLastWorkflowRunId(platformInfo.id, workflowRunId)

        MaterialCategoryAuditLogService.repo.insert(
            MaterialCategoryAuditLog(
                id = UUID.randomUUID().toString(),
                categoryId = platformInfo.categoryId,
                userId = userId,
                action = "sync_trigger",
                detail = buildJsonObject {
                    put("platform_info_id", platformInfo.id)
                    put("sync_type", platformInfo.syncType)
                    put("workflow_run_id", workflowRunId)
                }.toString(),
                createdAt = System.currentTimeMillis() / 1000L,
            )
        )

        return TriggerResult.Success(workflowRunId)
    }

    /**
     * @return true if sync_state was reconciled (no longer running), false if still running
     */
    private suspend fun reconcileSyncState(platformInfoId: String, lastWorkflowRunId: String?): Boolean {
        if (lastWorkflowRunId.isNullOrEmpty()) {
            logger.debug("reconcileSyncState: no lastWorkflowRunId, skip")
            return false
        }

        val wf = WorkflowRunStatusService.getById(lastWorkflowRunId)
        logger.debug("reconcileSyncState: workflowRunId=$lastWorkflowRunId, wfStatus=${wf?.status}")
        if (wf == null || wf.status in setOf("completed", "failed", "cancelled")) {
            val newState = if (wf?.status == "completed") "idle" else "failed"
            logger.debug("reconcileSyncState: reconciled to newState=$newState")
            MaterialCategorySyncPlatformInfoService.repo.setSyncState(platformInfoId, newState)
            return true
        }
        return false
    }
}
