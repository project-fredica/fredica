@file:Suppress("NOTHING_TO_INLINE")

package com.github.project_fredica.apputil


import kotlin.jvm.JvmInline


expect inline fun Logger.Companion.log(level: Logger.LogLevel, tag: String, msg: String)

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

expect inline fun Logger.exception(msg: String, err: Throwable)


expect inline fun Logger.Companion.defaultTagArg(): String

inline fun createLogger(tag: () -> String = { Logger.defaultTagArg() }) = Logger(tag())

inline fun Logger.error(msg: String, err: Throwable?) = if (err == null) {
    this.error(msg)
} else {
    this.exception(msg, err)
}