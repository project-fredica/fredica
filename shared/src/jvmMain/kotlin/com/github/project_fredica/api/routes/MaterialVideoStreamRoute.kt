package com.github.project_fredica.api.routes

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.auth.AuthService
import com.github.project_fredica.db.MaterialVideoService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ClosedWriteChannelException

/**
 * GET /api/v1/MaterialVideoStreamRoute?material_id=xxx
 *
 * 以 Cookie 认证方式流式输出 video.mp4，支持 Range 分片请求（HTML5 视频 seek）。
 *
 * 认证：读取 Cookie "fredica_token"，通过 AuthService.resolveIdentity 校验 token 有效性。
 *
 * 响应头：
 * - ETag: 基于 materialId + 文件修改时间，用于 304 条件请求
 * - Cache-Control: no-cache（有新版本时重新验证）
 *
 * Range 支持：由 Ktor PartialContent 插件自动处理，无需手动解析。
 */
object MaterialVideoStreamRoute {
    const val PATH = "/api/v1/MaterialVideoStreamRoute"

    suspend fun handle(ctx: RoutingContext) {
        val logger = createLogger()
        val call = ctx.call

        // Cookie 认证：通过 AuthService.resolveIdentity 校验 token 有效性
        val token = call.request.cookies["fredica_token"]
        if (token.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        if (AuthService.resolveIdentity("Bearer $token") == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val materialId = call.request.queryParameters["material_id"]
        if (materialId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

        MaterialVideoService.repo.findById(materialId) ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val mediaDir = AppUtil.Paths.materialMediaDir(materialId)
        val mp4File = mediaDir.resolve("video.mp4")
        if (!mp4File.exists()) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val etag = "\"${materialId}-${mp4File.lastModified()}\""

        // 304 支持：ETag 命中时直接返回 Not Modified
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch != null && ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified)
            return
        }

        call.response.headers.append(HttpHeaders.ETag, etag)
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")

        try {
            call.respondFile(mp4File)
        } catch (_: ClosedWriteChannelException) {
            // 客户端在流式传输过程中主动断开连接（关闭标签页、切换素材等），属于正常现象。
            // 不 catch 会导致 Ktor 将此异常打印为 ERROR 级别日志，产生大量噪音。
            logger.debug("client disconnected mid-stream: materialId=$materialId , reason=ClosedWriteChannelException")
        } catch (_: ClosedByteChannelException) {
            // 同上：Netty 底层 I/O 通道关闭时抛出的另一种断连异常，与上面互补覆盖。
            logger.debug("client disconnected mid-stream: materialId=$materialId , reason=ClosedByteChannelException")
        }
    }
}
