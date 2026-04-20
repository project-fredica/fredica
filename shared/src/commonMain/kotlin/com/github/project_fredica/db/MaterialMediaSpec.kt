package com.github.project_fredica.db

import com.github.project_fredica.apputil.AppUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 素材的媒体文件规格，根据 [MaterialVideo.type] + [MaterialVideo.sourceType] 解析。
 *
 * 集中管理下载产物路径、转码输入检测、payload 构建等逻辑，
 * 避免在 Route / Service 层硬编码文件名和 sourceType 字符串比较。
 *
 * 通过 [MaterialVideo.toMediaSpec] 扩展函数构造。
 */
sealed interface MaterialMediaSpec {

    val materialId: String
    val mediaDir: File

    /** 是否需要先从平台下载原始媒体文件。 */
    val needsDownload: Boolean

    /** 构建转码任务的 payload JSON 字符串，若无可用输入则返回 null。 */
    fun buildTranscodePayload(hwAccel: String = "auto"): String?

    /**
     * Bilibili 视频：需要先下载（DASH m4s 或 FLV）再转码为 mp4。
     *
     * 下载产物约定（由 Python 下载服务写入）：
     *  - DASH 格式：`video.m4s` + `audio.m4s` + `download_m4s.done`
     *  - FLV 格式：`video.flv` + `download_flv.done`
     */
    data class BilibiliVideo(
        override val materialId: String,
        val bvid: String,
        val page: Int,
        override val mediaDir: File,
    ) : MaterialMediaSpec {
        override val needsDownload get() = true

        val videoM4s: File get() = mediaDir.resolve("video.m4s")
        val audioM4s: File get() = mediaDir.resolve("audio.m4s")
        val videoFlv: File get() = mediaDir.resolve("video.flv")
        val outputMp4: File get() = mediaDir.resolve("video.mp4")

        val dashDoneMarker: File get() = mediaDir.resolve("download_m4s.done")
        val flvDoneMarker: File get() = mediaDir.resolve("download_flv.done")

        fun isDownloaded(): Boolean =
            (dashDoneMarker.exists() && videoM4s.exists() && audioM4s.exists()) ||
            (flvDoneMarker.exists() && videoFlv.exists())

        fun hasTranscodeInput(): Boolean =
            (videoM4s.exists() && audioM4s.exists()) || videoFlv.exists()

        fun hasDash(): Boolean = videoM4s.exists() && audioM4s.exists()

        fun buildDownloadPayload(checkSkip: Boolean = true): String =
            buildJsonObject {
                put("bvid", bvid)
                put("page", page)
                put("output_path", outputMp4.absolutePath)
                put("check_skip", checkSkip)
            }.toString()

        /** 从已下载的 bilibili 源构建转码 payload（mode=from_bilibili_download）。 */
        fun buildDownloadTranscodePayload(hwAccel: String = "auto", checkSkip: Boolean = true): String =
            buildJsonObject {
                put("mode", "from_bilibili_download")
                put("output_dir", mediaDir.absolutePath)
                put("output_path", outputMp4.absolutePath)
                put("hw_accel", hwAccel)
                put("check_skip", checkSkip)
            }.toString()

        override fun buildTranscodePayload(hwAccel: String): String? {
            if (!hasTranscodeInput()) return null
            return buildJsonObject {
                if (hasDash()) {
                    put("input_video", videoM4s.absolutePath)
                    put("input_audio", audioM4s.absolutePath)
                } else {
                    put("input_video", videoFlv.absolutePath)
                }
                put("output_path", outputMp4.absolutePath)
                put("hw_accel", hwAccel)
            }.toString()
        }
    }

    /** 本地已有源文件的视频，直接转码，无需下载。 */
    data class LocalVideo(
        override val materialId: String,
        override val mediaDir: File,
    ) : MaterialMediaSpec {
        override val needsDownload get() = false

        override fun buildTranscodePayload(hwAccel: String): String =
            buildJsonObject {
                put("output_path", mediaDir.resolve("video.mp4").absolutePath)
                put("hw_accel", hwAccel)
            }.toString()
    }

    companion object {
        /**
         * 判断素材是否属于"bilibili 视频"——需要走下载+转码流水线的那类素材。
         *
         * 必须同时检查 [type] 和 [sourceType]：`sourceType.startsWith("bilibili_")`
         * 只说明来源是 bilibili，不代表素材是视频——未来可能出现 bilibili 音频、
         * bilibili 专栏等非视频素材，它们的下载/转码策略完全不同。
         */
        fun isBilibiliVideo(type: String, sourceType: String): Boolean =
            type == MaterialType.VIDEO &&
            (sourceType == "bilibili" || sourceType.startsWith("bilibili_"))
    }
}

private val PAGE_REGEX = Regex("__P(\\d+)$")

/**
 * 根据 type + sourceType 解析媒体规格。
 *
 * 当前仅处理 video 类型；未来扩展其他 [MaterialType] 时在此添加分支。
 */
suspend fun MaterialVideo.toMediaSpec(): MaterialMediaSpec {
    val mediaDir = AppUtil.Paths.materialMediaDir(id)
    if (MaterialMediaSpec.isBilibiliVideo(type, sourceType)) {
        val page = PAGE_REGEX.find(id)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return MaterialMediaSpec.BilibiliVideo(
            materialId = id,
            bvid = sourceId,
            page = page,
            mediaDir = mediaDir,
        )
    }
    return MaterialMediaSpec.LocalVideo(materialId = id, mediaDir = mediaDir)
}
