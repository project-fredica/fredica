package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.*
import com.github.project_fredica.db.LlmResponseCacheService

object LlmCacheQueryRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "查询 LLM 响应缓存"

    private val logger = createLogger()

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrElse {
            return buildValidJson { kv("error", "invalid query params") }
        }

        val keyHash = query["key_hash"]?.firstOrNull()
            ?: return buildValidJson { kv("error", "key_hash required") }

        val cache = LlmResponseCacheService.repo.findByHash(keyHash)

        return buildValidJson {
            if (cache != null) {
                kv("cache", AppUtil.dumpJsonStr(cache).getOrThrow())
            } else {
                kv("cache", null as String?)
            }
            kv("revision", null as String?)
        }
    }
}
