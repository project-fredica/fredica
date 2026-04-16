package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.dumpJsonStr
import com.github.project_fredica.prompt.schema_hints.WebenTypeHintResourceLoader
import com.github.project_fredica.auth.AuthRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/v1/WebenConceptTypeHintsRoute
 *
 * 返回前端可用于展示/筛选的概念类型 hint 列表。
 * 数据来源为：commonMain/resources 下的默认类型资源 + 当前知识库中已有 concept_type distinct 值。
 * 这些值仅用于 hint，不构成允许列表。
 */
object WebenConceptTypeHintsRoute : FredicaApi.Route {
    private val logger = createLogger { "WebenConceptTypeHintsRoute" }

    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "获取 Weben concept type hints（默认资源 + 现有值）"
    override val minRole = AuthRole.GUEST

    @Serializable
    data class TypeHintItem(
        val key: String,
        val label: String,
        val color: String,
        @SerialName("source_type") val sourceType: String,
    )

    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val defaults = WebenTypeHintResourceLoader.loadDefaultConceptTypes()
        val defaultSet = defaults.toSet()
        val items = WebenTypeHintResourceLoader.loadMergedConceptTypes().map { type ->
            TypeHintItem(
                key = type,
                label = type,
                color = colorForType(type),
                sourceType = if (defaultSet.contains(type)) "default" else "existing",
            )
        }
        logger.debug("WebenConceptTypeHintsRoute: merged=${items.size}")
        return AppUtil.dumpJsonStr(items).getOrThrow()
    }

    private fun colorForType(type: String): String {
        val hash = type.fold(0) { acc, ch -> acc * 31 + ch.code }
        val palette = listOf(
            "bg-blue-100 text-blue-700 ring-blue-200",
            "bg-purple-100 text-purple-700 ring-purple-200",
            "bg-cyan-100 text-cyan-700 ring-cyan-200",
            "bg-green-100 text-green-700 ring-green-200",
            "bg-orange-100 text-orange-700 ring-orange-200",
            "bg-rose-100 text-rose-700 ring-rose-200",
            "bg-pink-100 text-pink-700 ring-pink-200",
            "bg-teal-100 text-teal-700 ring-teal-200",
            "bg-amber-100 text-amber-700 ring-amber-200",
            "bg-lime-100 text-lime-700 ring-lime-200",
            "bg-sky-100 text-sky-700 ring-sky-200",
            "bg-gray-100 text-gray-600 ring-gray-200",
        )
        return palette[kotlin.math.abs(hash) % palette.size]
    }
}
