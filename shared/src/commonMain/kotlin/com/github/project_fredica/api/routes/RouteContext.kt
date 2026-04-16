package com.github.project_fredica.api.routes

import com.github.project_fredica.auth.AuthIdentity

/**
 * 路由执行上下文，由 handleRoute() 构造并传入 handler。
 * 替代原来的 RouteAuthContext + RouteRequestContext 协程上下文注入。
 */
data class RouteContext(
    val identity: AuthIdentity?,
    val clientIp: String?,
    val userAgent: String?,
) {
    /** 获取已认证用户身份，Guest 返回 null */
    val authenticatedUser: AuthIdentity.Authenticated?
        get() = identity as? AuthIdentity.Authenticated

    /** 获取已认证用户 ID，Guest/未认证返回 null */
    val userId: String?
        get() = authenticatedUser?.userId

    /** 要求身份存在，否则抛异常（用于 requiresAuth=true 的路由） */
    fun requireIdentity(): AuthIdentity =
        identity ?: error("RouteContext: identity is null (route requires auth but identity is missing)")
}
