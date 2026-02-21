package com.github.project_fredica.apputil


inline fun <T> Result.Companion.wrap(scope: () -> T): Result<T> = try {
    Result.success(scope())
} catch (err: Throwable) {
    Result.failure(err)
}

suspend inline fun <T> Result.Companion.wrapAsync(scope: suspend () -> T): Result<T> = try {
    Result.success(scope())
} catch (err: Throwable) {
    Result.failure(err)
}

@Suppress("NOTHING_TO_INLINE")
inline fun Double.toFixed(digits: Int) = String.format("%.${digits}f", this)