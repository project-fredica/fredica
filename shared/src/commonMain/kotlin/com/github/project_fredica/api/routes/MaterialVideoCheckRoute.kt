package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.buildValidJson
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialVideoService

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

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<Map<String, List<String>>>().getOrElse { emptyMap() }
        val materialId = p["material_id"]?.firstOrNull()
            ?: return buildValidJson { kv("error", "MISSING_MATERIAL_ID") }

        MaterialVideoService.repo.findById(materialId)
            ?: return buildValidJson { kv("error", "MATERIAL_NOT_FOUND") }

        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val mp4File = mediaDir.resolve("video.mp4")
        val doneFile = mediaDir.resolve("transcode.done")

        // 双重校验：文件存在 + done 标记，防止转码中途返回不完整文件
        if (!mp4File.exists() || !doneFile.exists()) {
            return buildValidJson {
                kv("ready", false)
                kv("file_mtime", null as Long?)
                kv("file_size", null as Long?)
            }
        }

        return buildValidJson {
            kv("ready", true)
            kv("file_mtime", mp4File.lastModified())
            kv("file_size", mp4File.length())
        }
    }
}
