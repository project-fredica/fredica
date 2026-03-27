package com.github.project_fredica.apputil

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对同一资源键的并发请求串行化（两级锁）。
 * guardMutex 短暂持有，用于获取/创建 per-key Mutex；
 * per-key Mutex 长持有，覆盖整个 Python 调用过程。
 */
class PyCallGuard {
    private val guardMutex = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    private val logger = createLogger()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        logger.debug("py call guard with lock : $key")
        val mutex = guardMutex.withLock { locks.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}
