package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.github.project_fredica.db.MaterialVideoService
import com.github.project_fredica.auth.AuthRole

/**
 * GET /api/v1/MaterialVideoCheckRoute
 *
 * 检查指定素材的 video.mp4 是否已就绪（转码完成）。
 *
 * 查询参数：
 * - material_id: 素材 ID
 *
 * 响应示例：
 * ```json
 * {"ready": true, "file_mtime": 1710000000000, "file_size": 123456789}
 * {"ready": false, "file_mtime": null, "file_size": null}
 * {"error": "MATERIAL_NOT_FOUND"}
 * ```
 */
object MaterialVideoCheckRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "检查素材 video.mp4 是否就绪"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val materialId = p["material_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "MISSING_MATERIAL_ID") }.toValidJson()

        MaterialVideoService.repo.findById(materialId)
            ?: return buildJsonObject { put("error", "MATERIAL_NOT_FOUND") }.toValidJson()

        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val mp4File = mediaDir.resolve("video.mp4")
        val doneFile = mediaDir.resolve("transcode.done")

        // 双重校验：文件存在 + done 标记，防止转码中途返回不完整文件
        if (!mp4File.exists() || !doneFile.exists()) {
            return buildJsonObject {
                put("ready", false)
                put("file_mtime", null as Long?)
                put("file_size", null as Long?)
            }.toValidJson()
        }

        return buildJsonObject {
            put("ready", true)
            put("file_mtime", mp4File.lastModified())
            put("file_size", mp4File.length())
        }.toValidJson()
    }
}
