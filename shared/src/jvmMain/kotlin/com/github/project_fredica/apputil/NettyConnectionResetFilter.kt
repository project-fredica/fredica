package com.github.project_fredica.apputil

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker
import java.net.SocketException

/**
 * Logback TurboFilter：过滤 Netty I/O 事件循环产生的 "Connection reset" 日志。
 *
 * 背景：视频播放器 seek 时浏览器会丢弃旧 HTTP 连接并发起新的 Range 请求。
 * Netty 在尝试从已关闭的 socket 读取时抛出 SocketException("Connection reset")，
 * 并由 Ktor 的 channel exception handler 以 DEBUG 级别打印完整堆栈。
 * 该异常发生在 Netty I/O 事件线程（NioByteUnsafe.read），完全在应用 coroutine 之外，
 * 无法通过 route 层的 try-catch 拦截，只能在日志层过滤。
 *
 * 过滤条件（三者同时满足才 DENY）：
 *   1. 级别为 DEBUG
 *   2. 消息包含 "I/O operation failed"
 *   3. cause 是 SocketException 且 message 包含 "Connection reset"
 */
class NettyConnectionResetFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level,
        format: String?,
        params: Array<out Any>?,
        t: Throwable?,
    ): FilterReply {
        if (level == Level.DEBUG
            && format?.contains("I/O operation failed") == true
            && t is SocketException
            && t.message?.contains("Connection reset") == true
        ) {
            return FilterReply.DENY
        }
        if (level == Level.DEBUG
            && format?.contains("I/O operation failed") == true
            && t is java.io.IOException
            && t.message?.contains("你的主机中的软件中止了一个已建立的连接") == true
        ) {
            return FilterReply.DENY
        }
        return FilterReply.NEUTRAL
    }
}
