@file:Suppress("NOTHING_TO_INLINE")

package com.github.project_fredica.apputil


import kotlin.jvm.JvmInline


/**
 * 多平台日志底层输出函数，由各平台 actual 实现：
 * - JVM/Desktop：输出到 `System.out` / `System.err`
 * - Android：调用 `android.util.Log`
 *
 * 通常不直接调用此函数，而是通过 [Logger] 实例的方法调用。
 */
expect inline fun Logger.Companion.log(level: Logger.LogLevel, tag: String, msg: String)

/**
 * 多平台日志门面，以内联值类形式携带 `tag`，避免运行时对象装箱开销。
 *
 * 推荐通过 [createLogger] 工厂函数创建实例：
 * ```kotlin
 * val log = createLogger()               // tag 由平台自动推断（通常为调用类名）
 * val log = createLogger { "MyModule" }  // 显式指定 tag
 *
 * log.debug("Starting...")
 * log.error("Failed", exception)
 * ```
 */
@Suppress("NOTHING_TO_INLINE")
@JvmInline
value class Logger(val tag: String) {
    inline fun debug(msg: String) = log(level = LogLevel.DEBUG, tag = tag, msg = msg)
    inline fun info(msg: String) = log(level = LogLevel.INFO, tag = tag, msg = msg)
    inline fun warn(msg: String) = log(level = LogLevel.WARN, tag = tag, msg = msg)
    inline fun error(msg: String) = log(level = LogLevel.ERROR, tag = tag, msg = msg)

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    companion object
}

/**
 * 输出带异常堆栈信息的错误日志，由各平台 actual 实现。
 * 通常通过 [Logger.error] 的 `err: Throwable?` 重载间接调用。
 */
expect inline fun Logger.exception(msg: String, err: Throwable)

/**
 * 获取当前调用位置的默认 tag 字符串（通常为类名），由平台 actual 实现。
 * 用于 [createLogger] 的无参默认值。
 */
expect inline fun Logger.Companion.defaultTagArg(): String

/**
 * 创建 [Logger] 实例的工厂函数。
 *
 * @param tag 日志 tag，默认由平台自动推断（通常为调用类名），也可显式传入。
 */
inline fun createLogger(tag: () -> String = { Logger.defaultTagArg() }) = Logger(tag())

/**
 * 输出错误日志，根据 [err] 是否为 null 决定是否附带异常堆栈。
 *
 * - `err == null`：仅输出消息文本
 * - `err != null`：调用 [Logger.exception] 同时输出消息和堆栈
 */
inline fun Logger.error(msg: String, err: Throwable?) = if (err == null) {
    this.error(msg)
} else {
    this.exception(msg, err)
}

/**
 * 输出警告日志，根据 [err] 是否为 null 以及 [isHappensFrequently] 决定异常信息详细程度。
 *
 * - `err == null`：仅输出消息文本
 * - `err != null && isHappensFrequently == true`：输出 `msg > ExceptionType : message`（简短，避免高频场景刷屏）
 * - `err != null && isHappensFrequently == false`：输出消息 + 完整 stacktrace
 *
 * 使用场景：
 * - 预期失败但不频繁（如启动时 Python 服务未就绪）→ `isHappensFrequently = false`
 * - 预期失败且高频（如轮询超时、每次请求都可能失败）→ `isHappensFrequently = true`
 */
inline fun Logger.warn(msg: String, isHappensFrequently: Boolean, err: Throwable?) = if (err == null) {
    this.warn(msg)
} else if (isHappensFrequently) {
    this.warn("$msg > ${err::class.simpleName} : ${err.message}")
} else {
    this.warn("$msg\n${err.stackTraceToString()}")
}