package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.LlmResponseCacheService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull

object LlmCacheQueryRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询 LLM 响应缓存"

    private val logger = createLogger()

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildJsonObject { put("error", "invalid query params") }.toValidJson()
        }

        val keyHash = query["key_hash"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "key_hash required") }.toValidJson()

        val cache = LlmResponseCacheService.repo.findByHash(keyHash)

        return buildJsonObject {
            if (cache != null) {
                put("cache", AppUtil.dumpJsonStr(cache).getOrThrow().toJsonElement())
            } else {
                put("cache", JsonNull)
            }
            put("revision", JsonNull)
        }.toValidJson()
    }
}
