package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.MaterialCategoryService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MaterialSetCategoriesRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val desc = "替换视频的分类关联（删除旧的、写入新的）"

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<MaterialSetCategoriesParam>().getOrThrow()
        MaterialCategoryService.repo.setVideoCategories(p.videoId, p.categoryIds)
        return ValidJsonString("""{"updated":true}""")
    }
}

@Serializable
data class MaterialSetCategoriesParam(
    @SerialName("video_id") val videoId: String,
    @SerialName("category_ids") val categoryIds: List<String> = emptyList(),
)
