package com.github.project_fredica.python

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.get
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.apputil.json
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.db.Task
import com.github.project_fredica.db.TaskService
import com.github.project_fredica.db.TorchMirrorCacheService
import com.github.project_fredica.db.TorchMirrorVersionsCacheService
import com.github.project_fredica.db.WorkflowRun
import com.github.project_fredica.db.WorkflowRunStatusService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Torch 相关业务的服务层，聚合所有 torch 操作。
 *
 * 所有方法均返回 JSON 字符串，供 JsMessageHandler 直接回调前端。
 * 异常由调用方（handler 的 try-catch 或基类兜底）处理。
 */
object TorchService {
    private val logger = createLogger()

    private val ACTIVE_STATUSES = setOf("pending", "claimed", "running")

    // ── 参数数据类 ─────────────────────────────────────────────────────────────

    @Serializable
    data class DownloadParam(
        @SerialName("variant")           val variant: String = "",
        @SerialName("torch_version")     val torchVersion: String = "",
        @SerialName("index_url")         val indexUrl: String = "",
        @SerialName("index_url_mode")    val indexUrlMode: String = "replace",
        @SerialName("use_proxy")         val useProxy: Boolean = false,
        @SerialName("proxy")             val proxy: String = "",
        @SerialName("expected_version")  val expectedVersion: String = "",
        @SerialName("custom_packages")   val customPackages: String = "",
        @SerialName("custom_index_url")  val customIndexUrl: String = "",
        @SerialName("custom_variant_id") val customVariantId: String = "",
    )

    @Serializable
    data class SaveConfigParam(
        @SerialName("torch_variant")            val torchVariant: String = "",
        @SerialName("torch_download_use_proxy") val torchDownloadUseProxy: Boolean = false,
        @SerialName("torch_download_proxy_url") val torchDownloadProxyUrl: String = "",
        @SerialName("torch_download_index_url") val torchDownloadIndexUrl: String = "",
        @SerialName("torch_custom_packages")    val torchCustomPackages: String = "",
        @SerialName("torch_custom_index_url")   val torchCustomIndexUrl: String = "",
        @SerialName("torch_custom_variant_id")  val torchCustomVariantId: String = "",
    )

    // ── 配置读写 ───────────────────────────────────────────────────────────────

    /** 获取当前 torch 配置与推荐信息。 */
    suspend fun getInfo(): String {
        val cfg = AppConfigService.repo.getConfig()
        return buildJsonObject {
            put("torch_variant", cfg.torchVariant)
            put("torch_recommended_variant", cfg.torchRecommendedVariant)
            put("torch_recommendation_json", cfg.torchRecommendationJson)
            put("torch_download_use_proxy", cfg.torchDownloadUseProxy)
            put("torch_download_proxy_url", cfg.torchDownloadProxyUrl)
            put("torch_download_index_url", cfg.torchDownloadIndexUrl)
            put("torch_custom_packages", cfg.torchCustomPackages)
            put("torch_custom_index_url", cfg.torchCustomIndexUrl)
            put("torch_custom_variant_id", cfg.torchCustomVariantId)
        }.toString()
    }

    /** 保存用户选择的 torch variant 及代理配置。 */
    suspend fun saveConfig(param: SaveConfigParam): String {
        if (param.torchVariant.isBlank()) {
            return buildJsonObject { put("error", "MISSING_TORCH_VARIANT") }.toString()
        }
        val cfg = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(cfg.copy(
            torchVariant          = param.torchVariant,
            torchDownloadUseProxy = param.torchDownloadUseProxy,
            torchDownloadProxyUrl = param.torchDownloadProxyUrl,
            torchDownloadIndexUrl = param.torchDownloadIndexUrl,
            torchCustomPackages   = param.torchCustomPackages,
            torchCustomIndexUrl   = param.torchCustomIndexUrl,
            torchCustomVariantId  = param.torchCustomVariantId,
        ))
        return buildJsonObject { put("ok", true) }.toString()
    }

    // ── GPU 探测 ───────────────────────────────────────────────────────────────

    /** 重新探测 GPU，更新推荐 variant 并持久化到 AppConfig。 */
    suspend fun runDetect(): String {
        val resultText = FredicaApi.PyUtil.post(
            path = "/torch/resolve-spec",
            body = "",
            timeoutMs = 30_000L,
        )
        val torchJson = AppUtil.GlobalVars.json.parseToJsonElement(resultText).jsonObject
        val recommendedVariant = torchJson["recommended_variant"]?.jsonPrimitive?.contentOrNull ?: ""
        val cfg = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(cfg.copy(
            torchRecommendedVariant = recommendedVariant,
            torchRecommendationJson = resultText,
        ))
        return buildJsonObject {
            put("torch_recommended_variant", recommendedVariant)
            put("torch_recommendation_json", resultText)
        }.toString()
    }

    // ── 安装检测 ───────────────────────────────────────────────────────────────

    /** 检查 pip 安装目录中是否已有可用 torch。 */
    suspend fun check(expectedVersion: String = ""): String {
        val downloadDir = AppUtil.Paths.pipLibDir.absolutePath
        val path = buildString {
            append("/torch/check?download_dir=$downloadDir")
            if (expectedVersion.isNotBlank()) append("&expected_version=$expectedVersion")
        }
        logger.info("[TorchService.check] path=$path")
        return FredicaApi.PyUtil.get(path)
    }

    // ── pip 命令预览 ───────────────────────────────────────────────────────────

    /** 生成 pip install 命令字符串（前端预览用，不实际执行）。 */
    suspend fun getPipCommand(
        torchVersion: String,
        variant: String,
        indexUrl: String,
        indexUrlMode: String,
        useProxy: Boolean,
        proxy: String,
    ): String {
        if (indexUrl.isBlank()) return buildJsonObject { put("command", "") }.toString()
        val downloadDir = AppUtil.Paths.torchDownloadDir.absolutePath
        val path = buildString {
            append("/torch/pip-command?index_url=$indexUrl")
            if (torchVersion.isNotBlank()) append("&torch_version=$torchVersion")
            append("&download_dir=$downloadDir")
            if (variant.isNotBlank()) append("&variant=$variant")
            append("&index_url_mode=$indexUrlMode")
            if (useProxy && proxy.isNotBlank()) append("&use_proxy=true&proxy=$proxy")
        }
        logger.info("[TorchService.getPipCommand] variant=$variant torchVersion=$torchVersion path=$path")
        return FredicaApi.PyUtil.get(path)
    }

    // ── 镜像检测 ───────────────────────────────────────────────────────────────

    /** 探测各镜像站对指定 variant 的支持情况。 */
    suspend fun mirrorCheck(variant: String, useProxy: Boolean, proxy: String): String {
        if (variant.isBlank()) return buildJsonObject { put("error", "variant is required") }.toString()
        val path = buildString {
            append("/torch/mirror-check?variant=$variant")
            if (useProxy && proxy.isNotBlank()) append("&proxy=$proxy")
        }
        logger.debug("[TorchService.mirrorCheck] variant=$variant path=$path")
        return FredicaApi.PyUtil.get(path)
    }

    /** 并发查询所有镜像支持的 variant 列表（带缓存，TTL 90 天）。 */
    suspend fun allMirrorVariants(useProxy: Boolean, proxy: String, forceRefresh: Boolean = false): String {
        val path = buildString {
            append("/torch/all-mirror-variants/")
            if (useProxy && proxy.isNotBlank()) append("?proxy=$proxy")
        }
        logger.debug("[TorchService.allMirrorVariants] useProxy=$useProxy forceRefresh=$forceRefresh path=$path")
        return TorchMirrorCacheService.getOrFetch(forceRefresh = forceRefresh) { FredicaApi.PyUtil.get(path) }
    }

    /** 查询指定镜像站支持的 variant 列表（带缓存）。 */
    suspend fun mirrorVersions(mirrorKey: String, useProxy: Boolean, proxy: String): String {
        if (mirrorKey.isBlank()) return buildJsonObject { put("error", "mirror_key is required") }.toString()
        val path = buildString {
            append("/torch/mirror-versions/?mirror_key=$mirrorKey")
            if (useProxy && proxy.isNotBlank()) append("&proxy=$proxy")
        }
        logger.debug("[TorchService.mirrorVersions] mirrorKey=$mirrorKey path=$path")
        return TorchMirrorVersionsCacheService.getOrFetch(mirrorKey) { FredicaApi.PyUtil.get(path) }
    }

    // ── 下载任务 ───────────────────────────────────────────────────────────────

    /** 创建 DOWNLOAD_TORCH 任务；若已有活跃任务则返回 TASK_ALREADY_ACTIVE。 */
    suspend fun startDownload(param: DownloadParam): String {
        logger.info("[TorchService.startDownload] variant=${param.variant} " +
            "torchVersion=${param.torchVersion} indexUrl=${param.indexUrl} " +
            "indexUrlMode=${param.indexUrlMode} useProxy=${param.useProxy}")

        val activeTask = TaskService.repo.listByType("DOWNLOAD_TORCH")
            .firstOrNull { it.status in ACTIVE_STATUSES }
        if (activeTask != null) {
            return buildJsonObject {
                put("error", "TASK_ALREADY_ACTIVE")
                put("workflow_run_id", activeTask.workflowRunId)
            }.toString()
        }

        val payload = AppUtil.dumpJsonStr(param).getOrThrow().str
        val nowSec = System.currentTimeMillis() / 1000L
        val workflowRunId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        WorkflowRunStatusService.create(
            WorkflowRun(
                id = workflowRunId,
                materialId = "",
                template = "torch_download",
                status = "pending",
                totalTasks = 1,
                doneTasks = 0,
                createdAt = nowSec,
            )
        )
        TaskService.repo.create(
            Task(
                id = taskId,
                type = "DOWNLOAD_TORCH",
                workflowRunId = workflowRunId,
                materialId = "",
                payload = payload,
                createdAt = nowSec,
            )
        )

        return buildJsonObject {
            put("task_id", taskId)
            put("workflow_run_id", workflowRunId)
        }.toString()
    }

    /** 查询当前活跃的 DOWNLOAD_TORCH 任务（页面刷新后恢复进度面板用）。 */
    suspend fun getActiveDownload(): String {
        val task = TaskService.repo.listByType("DOWNLOAD_TORCH")
            .firstOrNull { it.status in ACTIVE_STATUSES }
        return buildJsonObject {
            put("workflow_run_id", task?.workflowRunId ?: "")
            put("task_id", task?.id ?: "")
            put("status", task?.status ?: "")
        }.toString()
    }
}