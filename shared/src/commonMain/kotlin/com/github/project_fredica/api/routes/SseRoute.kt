package com.github.project_fredica.api.routes

import com.github.project_fredica.api.FredicaApi
import io.ktor.server.routing.RoutingContext

/**
 * SSE 流式路由接口。
 *
 * 实现此接口的路由通过 [handle] 直接写入响应流，不走 handler() 返回值机制。
 * 路由框架检测到 Mode.Sse 时调用 handle(ctx)，而非 handler(param, context)。
 */
interface SseRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode get() = FredicaApi.Route.Mode.Sse

    /** 不应被调用；SSE 路由通过 handle() 直接写响应。 */
    override suspend fun handler(param: String, context: RouteContext): Any =
        error("SseRoute.handler() must not be called directly; use handle(ctx)")

    suspend fun handle(ctx: RoutingContext)
}
