package com.github.project_fredica.api.routes

// =============================================================================
// PostprocessSubtitleSaveRoute —— POST /api/v1/PostprocessSubtitleSaveRoute
// =============================================================================
//
// 保存 LLM 后处理字幕到素材目录下的 asr_postprocess_result/ 子目录。
//
// 请求体字段：
//   material_id: string   — 素材 ID
//   model_id: string      — 使用的 LLM 模型 ID
//   srt_content: string   — SRT 字幕文本
//
// 返回：{ filename, saved_at }
//
// 保存前会校验 SRT 格式，解析失败则返回 { error: "..." }。
// =============================================================================

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.apputil.warn
import com.github.project_fredica.auth.AuthRole
import com.github.project_fredica.asr.srt.ParseSrtBlocksResult
import com.github.project_fredica.asr.srt.SrtUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PostprocessSubtitleSaveRoute : FredicaApi.Route {
    private val logger = createLogger { "PostprocessSubtitleSaveRoute" }

    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.TENANT
    override val desc = "保存 LLM 后处理字幕到素材目录"

    @Serializable
    private data class SaveParam(
        @SerialName("material_id") val materialId: String,
        @SerialName("model_id") val modelId: String,
        @SerialName("srt_content") val srtContent: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        return try {
            val p = param.loadJsonModel<SaveParam>().getOrThrow()
            logger.debug("PostprocessSubtitleSaveRoute: materialId=${p.materialId} modelId=${p.modelId}")

            if (p.materialId.isBlank()) {
                return buildJsonObject { put("error", "material_id 不能为空") }.toValidJson()
            }
            if (p.srtContent.isBlank()) {
                return buildJsonObject { put("error", "srt_content 不能为空") }.toValidJson()
            }

            // SRT 格式校验
            val parseResult = SrtUtil.parseSrtBlocks(p.srtContent)
            if (parseResult is ParseSrtBlocksResult.Empty) {
                return buildJsonObject { put("error", "SRT 格式校验失败：未找到有效的字幕段") }.toValidJson()
            }
            val blockCount = (parseResult as ParseSrtBlocksResult.Ok).blocks.size
            if (blockCount == 0) {
                return buildJsonObject { put("error", "SRT 格式校验失败：解析到 0 个字幕段") }.toValidJson()
            }

            val mediaDir = AppUtil.Paths.materialMediaDir(p.materialId)
            val outDir = mediaDir.resolve("asr_postprocess_result")
            outDir.mkdirs()

            val ts = System.currentTimeMillis() / 1000
            val hash4 = p.modelId.hashCode().toUInt().toString(16).take(4)
            val filename = "pp_${ts}_${hash4}.srt"
            outDir.resolve(filename).writeText(p.srtContent)

            logger.info("PostprocessSubtitleSaveRoute: 已保存 $filename ($blockCount 段)")
            buildJsonObject {
                put("filename", filename)
                put("saved_at", ts)
            }.toValidJson()
        } catch (e: Throwable) {
            logger.warn("[PostprocessSubtitleSaveRoute] 保存失败", isHappensFrequently = false, err = e)
            buildJsonObject { put("error", e.message ?: "unknown") }.toValidJson()
        }
    }
}
