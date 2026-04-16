package com.github.project_fredica.auth

// =============================================================================
// LoginRateLimiter —— 内存级登录频率限制器（jvmMain）
// =============================================================================
//
// 为什么用内存而非 DB：
//   - 登录频率限制需要极低延迟，DB 写入会拖慢每次登录请求
//   - 单进程部署，内存状态足够；多实例部署时需换成 Redis（当前不需要）
//
// 两个维度独立计数，任一超限即拒绝：
//   - IP 维度（上限 10）：防止同一 IP 暴力枚举不同用户名
//   - 用户名维度（上限 5）：防止分布式 IP 针对特定账号暴力破解
//   IP 上限更高，因为 NAT 环境下多用户共享同一 IP 是正常情况。
//
// 滑动窗口实现：
//   记录每次失败的 Unix 秒时间戳，check() 时过滤掉窗口外的记录，
//   统计窗口内失败次数。比固定窗口更平滑，不会在窗口边界突然重置。
//
// 定期清理（maybeCleanup）：
//   每 10 分钟扫描一次，删除已过期的空桶，防止内存无限增长。
//   不用定时器，而是在 check() 时懒触发，避免额外线程。
// =============================================================================

import java.util.concurrent.ConcurrentHashMap

/**
 * 内存级登录频率限制器。
 *
 * 两个维度独立计数：
 * - 同一 IP：5 分钟内最多 10 次失败
 * - 同一用户名：5 分钟内最多 5 次失败
 *
 * 任一维度超限即拒绝，返回剩余等待秒数。
 */
object LoginRateLimiter : LoginRateLimiterApi {
    const val IP_MAX_FAILURES = 10
    const val USERNAME_MAX_FAILURES = 5
    const val WINDOW_SECONDS = 300L  // 5 分钟
    private const val CLEANUP_INTERVAL_MS = 600_000L  // 10 分钟

    private data class FailureRecord(
        val timestamps: MutableList<Long> = mutableListOf(),
    )

    private val ipFailures = ConcurrentHashMap<String, FailureRecord>()
    private val usernameFailures = ConcurrentHashMap<String, FailureRecord>()

    @Volatile
    private var lastCleanupMs = System.currentTimeMillis()

    /**
     * 检查是否允许登录尝试。
     *
     * @return null 表示允许；非 null 表示剩余等待秒数
     */
    override fun check(ip: String, username: String): Int? {
        maybeCleanup()
        val nowSec = System.currentTimeMillis() / 1000L
        val cutoff = nowSec - WINDOW_SECONDS

        val ipWait = checkBucket(ipFailures, ip, cutoff, nowSec, IP_MAX_FAILURES)
        if (ipWait != null) return ipWait

        val usernameWait = checkBucket(usernameFailures, username, cutoff, nowSec, USERNAME_MAX_FAILURES)
        if (usernameWait != null) return usernameWait

        return null
    }

    /** 记录一次失败 */
    override fun recordFailure(ip: String, username: String) {
        val nowSec = System.currentTimeMillis() / 1000L
        ipFailures.getOrPut(ip) { FailureRecord() }.timestamps.add(nowSec)
        usernameFailures.getOrPut(username) { FailureRecord() }.timestamps.add(nowSec)
    }

    /** 登录成功后清除该 IP + 用户名的失败记录 */
    override fun clearOnSuccess(ip: String, username: String) {
        ipFailures.remove(ip)
        usernameFailures.remove(username)
    }

    private fun checkBucket(
        map: ConcurrentHashMap<String, FailureRecord>,
        key: String,
        cutoff: Long,
        nowSec: Long,
        maxFailures: Int,
    ): Int? {
        val record = map[key] ?: return null
        synchronized(record) {
            record.timestamps.removeAll { it < cutoff }
            if (record.timestamps.size >= maxFailures) {
                val oldest = record.timestamps.first()
                val waitUntil = oldest + WINDOW_SECONDS
                return (waitUntil - nowSec).coerceAtLeast(1).toInt()
            }
        }
        return null
    }

    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) return
        lastCleanupMs = now
        val cutoff = now / 1000L - WINDOW_SECONDS
        cleanup(ipFailures, cutoff)
        cleanup(usernameFailures, cutoff)
    }

    private fun cleanup(map: ConcurrentHashMap<String, FailureRecord>, cutoff: Long) {
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            synchronized(entry.value) {
                entry.value.timestamps.removeAll { it < cutoff }
                if (entry.value.timestamps.isEmpty()) iter.remove()
            }
        }
    }

    /** 仅用于测试：重置所有状态 */
    fun resetForTest() {
        ipFailures.clear()
        usernameFailures.clear()
        lastCleanupMs = System.currentTimeMillis()
    }
}
