package com.github.project_fredica.auth

import com.github.project_fredica.db.AppConfigService
import kotlin.concurrent.Volatile

/**
 * webserver_auth_token 的内存缓存（invalidation + lazy reload）。
 *
 * 失效点：AppConfigDb.updateConfig()、AppConfigDb.updateConfigPartial()。
 * 读取点：AuthService.resolveIdentity()（每次 API 请求）。
 * 启动时通过 init() 预热；DB 写入时自动 invalidate()，下次 get() 重新加载。
 */
object WebserverAuthTokenCache {
    @Volatile
    private var _token: String? = null

    /** 预热缓存（启动时调用一次） */
    fun init(token: String) { _token = token }

    /** 读取缓存，cache miss 时从 DB 重新加载 */
    suspend fun get(): String {
        _token?.let { return it }
        val token = AppConfigService.repo.getConfig().webserverAuthToken
        _token = token
        return token
    }

    /** 清除缓存，下次 get() 将重新从 DB 加载 */
    fun invalidate() { _token = null }
}
