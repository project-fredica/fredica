package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.asr.route_ext.handler2

/**
 * 根据素材标题和简介，用 LLM 猜测视频的主要语言。
 *
 * GET /api/v1/MaterialLanguageGuessRoute?material_id=...
 *
 * 响应：{"language": "zh"} / {"language": "auto"} / {"language": null}
 * - ISO 639-1 代码：LLM 确信视频内容为该语言
 * - "auto"：LLM 无法确定（搬运/配音/多语言视频）
 * - null：无 LLM 配置
 *
 * 业务逻辑委托给 LanguageGuessService，结果文件缓存到素材目录。
 */
object MaterialLanguageGuessRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "用 LLM 猜测素材视频的主要语言（ISO 639-1 代码或 auto），结果文件缓存到素材目录"

    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}
