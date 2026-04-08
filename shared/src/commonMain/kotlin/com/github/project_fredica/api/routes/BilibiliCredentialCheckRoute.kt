package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import com.github.project_fredica.api.post
import com.github.project_fredica.apputil.ValidJsonString
import com.github.project_fredica.apputil.toValidJson
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 此路由已迁移至 kmpJsBridge：`CheckBilibiliCredentialJsMessageHandler`。
 *
 * 凭据检测涉及用户敏感信息（sessdata 等），不应通过 HTTP API 暴露给外部调用者。
 * 仅保留此文件以防止编译错误（可安全删除）。
 *
 * @deprecated 请使用 bridge 方法 `check_bilibili_credential` 替代。
 */
object BilibiliCredentialCheckRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "已废弃：请使用 kmpJsBridge check_bilibili_credential"

    private val logger = createLogger { "BilibiliCredentialCheckRoute" }

    override suspend fun handler(param: String): ValidJsonString {
        logger.warn("BilibiliCredentialCheckRoute 已废弃，请使用 kmpJsBridge check_bilibili_credential")
        return buildJsonObject {
            put("configured", false)
            put("valid", false)
            put("message", "此接口已废弃，请使用桌面端 App")
        }.toValidJson()
    }
}
