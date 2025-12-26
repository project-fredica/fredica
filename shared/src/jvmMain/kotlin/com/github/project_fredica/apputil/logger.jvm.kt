package com.github.project_fredica.apputil


import com.github.project_fredica.apputil.Logger.LogLevel.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.math.max
import kotlin.time.ExperimentalTime

fun Logger.LogLevel.castToKotlinLoggingLevel(): Level {
    return when (this) {
        DEBUG -> Level.DEBUG
        INFO -> Level.INFO
        WARN -> Level.WARN
        ERROR -> Level.ERROR
    }
}

@OptIn(ExperimentalTime::class)
@Suppress("NOTHING_TO_INLINE")
actual inline fun Logger.Companion.log(level: Logger.LogLevel, tag: String, msg: String) {
    LoggerFactory
        .getLogger(tag)
        .atLevel(level.castToKotlinLoggingLevel())
        .log("{}", msg)
}

@Suppress("NOTHING_TO_INLINE")
actual inline fun Logger.exception(msg: String, err: Throwable) {
    LoggerFactory
        .getLogger(tag)
        .atLevel(ERROR.castToKotlinLoggingLevel())
        .setCause(err)
        .log("{}", msg)
}

@Suppress("NOTHING_TO_INLINE", "UNNECESSARY_SAFE_CALL")
actual inline fun Logger.Companion.defaultTagArg(): String {
    val stackTraces = Thread.currentThread().stackTrace
    if (stackTraces.isEmpty()) {
        return "(no_stack_traces)"
    }
    val stackTrace = stackTraces[max(0, stackTraces.lastIndex - 1)]
    val sb = StringBuilder()
    stackTrace.fileName?.let {
        sb.append(it).append(' ')
    }
    stackTrace.className?.let {
        sb.append(it).append(" ")
    }
    stackTrace.methodName?.let {
        sb.append(it).append(" ")
    }
    sb.removeSuffix(" ")
    return sb.toString()
}