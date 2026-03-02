package com.github.project_fredica.apputil


/**
 * 将任意同步操作包装为 [Result]，捕获所有 [Throwable] 并转为 [Result.failure]。
 *
 * 适用于将可能抛出异常的 SDK / 系统 API 调用统一为 Result 风格，
 * 避免在调用处到处写 try-catch。
 *
 * ```kotlin
 * val result = Result.wrap { File("path").readText() }
 * result.onSuccess { ... }.onFailure { ... }
 * ```
 */
inline fun <T> Result.Companion.wrap(scope: () -> T): Result<T> = try {
    Result.success(scope())
} catch (err: Throwable) {
    Result.failure(err)
}

/**
 * 将任意挂起（suspend）操作包装为 [Result]，捕获所有 [Throwable] 并转为 [Result.failure]。
 *
 * 用法与 [wrap] 相同，适用于协程场景。
 *
 * ```kotlin
 * val result = Result.wrapAsync { httpClient.get(url).body<String>() }
 * ```
 */
suspend inline fun <T> Result.Companion.wrapAsync(scope: suspend () -> T): Result<T> = try {
    Result.success(scope())
} catch (err: Throwable) {
    Result.failure(err)
}

/**
 * 将 [Double] 格式化为保留指定小数位数的字符串，用于 UI 数值展示。
 *
 * @param digits 小数点后保留的位数
 * @sample `3.14159.toFixed(2)` → `"3.14"`
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Double.toFixed(digits: Int) = String.format("%.${digits}f", this)