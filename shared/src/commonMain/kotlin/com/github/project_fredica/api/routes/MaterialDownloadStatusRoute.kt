package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.loadJsonModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.github.project_fredica.auth.AuthRole

/**
 * POST /api/v1/MaterialDownloadStatusRoute
 *
 * 批量检查素材的本地下载状态，通过文件系统扫描素材媒体目录判断是否已下载。
 *
 * 下载完成的判定条件（满足其一即视为已下载）：
 *  - FLV 格式：目录中存在 `download_flv.done` 和 `video.flv`
 *  - M4S/DASH 格式：目录中存在 `download_m4s.done`、`video.m4s` 和 `audio.m4s`
 *
 * 返回：`{ "<materialId>": true/false, ... }`
 */
object MaterialDownloadStatusRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "批量检查素材本地下载状态"
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<MaterialDownloadStatusParam>().getOrThrow()

        val result = withContext(Dispatchers.IO) {
            p.materialIds.associateWith { id -> isDownloaded(id) }
        }

        return buildJsonObject {
            for ((id, downloaded) in result) {
                put(id, downloaded)
            }
        }.toValidJson()
    }

    private suspend fun isDownloaded(materialId: String): Boolean {
        val dir = AppUtil.Paths.materialMediaDir(materialId)
        if (dir.resolve("download_flv.done").exists() && dir.resolve("video.flv").exists()) {
            return true
        }
        if (dir.resolve("download_m4s.done").exists() &&
            dir.resolve("video.m4s").exists() &&
            dir.resolve("audio.m4s").exists()
        ) {
            return true
        }
        return false
    }
}

@Serializable
data class MaterialDownloadStatusParam(
    @SerialName("material_ids") val materialIds: List<String>,
)
