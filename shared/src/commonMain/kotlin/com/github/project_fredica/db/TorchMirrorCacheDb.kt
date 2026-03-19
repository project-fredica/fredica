package com.github.project_fredica.db

import com.github.project_fredica.apputil.createLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import org.ktorm.dsl.*
import org.ktorm.schema.BaseTable
import org.ktorm.schema.long
import org.ktorm.schema.varchar

// ---------------------------------------------------------------------------
// 表定义
// ---------------------------------------------------------------------------

/**
 * torch_mirror_cache 表：通用 key-value 缓存表，供多个 Service 共享。
 *
 * 当前使用方：
 *   - [TorchMirrorCacheService]         key = "all_variants"
 *   - [TorchMirrorVersionsCacheService] key = "mirror_versions:{mirror_key}"
 *
 * 字段说明：
 *   key        = 缓存键（TEXT PRIMARY KEY）
 *   value      = 完整 JSON 字符串
 *   updated_at = Unix 毫秒时间戳，用于判断缓存是否过期
 */
object TorchMirrorCacheTable : BaseTable<Nothing>("torch_mirror_cache") {
    val key = varchar("key")
    val value = varchar("value")
    val updatedAt = long("updated_at")

    override fun doCreateEntity(row: QueryRowSet, withReferences: Boolean): Nothing =
        throw UnsupportedOperationException()
}

// ---------------------------------------------------------------------------
// Repo 接口
// ---------------------------------------------------------------------------

interface TorchMirrorCacheRepo {
    /** 读取缓存，返回 (json, updatedAtMs)，不存在时返回 null。 */
    suspend fun get(key: String): Pair<String, Long>?
    /** 写入或更新缓存（INSERT OR REPLACE）。 */
    suspend fun put(key: String, value: String, updatedAt: Long)
    /** 删除指定 key 的缓存（用于强制刷新）。 */
    suspend fun delete(key: String)
}

// ---------------------------------------------------------------------------
// DB 实现
// ---------------------------------------------------------------------------

class TorchMirrorCacheDb(private val db: Database) : TorchMirrorCacheRepo {

    private val logger = createLogger()

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS torch_mirror_cache (
                            key        TEXT PRIMARY KEY,
                            value      TEXT NOT NULL DEFAULT '',
                            updated_at INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                }
            }
        }
        logger.debug("[TorchMirrorCacheDb] table initialized")
    }

    /** 按 key 查询缓存行，返回 (json, updatedAtMs)；不存在时返回 null。 */
    override suspend fun get(key: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val result = db.from(TorchMirrorCacheTable)
            .select()
            .where { TorchMirrorCacheTable.key eq key }
            .map { row ->
                val v = row[TorchMirrorCacheTable.value] ?: return@map null
                val t = row[TorchMirrorCacheTable.updatedAt] ?: 0L
                Pair(v, t)
            }
            .firstOrNull()
        if (result != null) {
            logger.debug("[TorchMirrorCacheDb] get hit: key=$key updatedAt=${result.second}")
        } else {
            logger.debug("[TorchMirrorCacheDb] get miss: key=$key")
        }
        result
    }

    /** 写入或覆盖缓存行（INSERT OR REPLACE）。 */
    override suspend fun put(key: String, value: String, updatedAt: Long): Unit =
        withContext(Dispatchers.IO) {
            db.useConnection { conn ->
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO torch_mirror_cache (key, value, updated_at) VALUES (?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, key)
                    ps.setString(2, value)
                    ps.setLong(3, updatedAt)
                    ps.executeUpdate()
                }
            }
            logger.debug("[TorchMirrorCacheDb] put: key=$key updatedAt=$updatedAt valueLen=${value.length}")
        }

    /** 删除指定 key 的缓存行。 */
    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("DELETE FROM torch_mirror_cache WHERE key = ?").use { ps ->
                ps.setString(1, key)
                ps.executeUpdate()
            }
        }
        logger.debug("[TorchMirrorCacheDb] delete: key=$key")
    }
}

// ---------------------------------------------------------------------------
// TorchMirrorCacheService：all-mirror-variants 查询结果缓存
// ---------------------------------------------------------------------------

/**
 * TorchMirrorCacheService：所有镜像合并后的 variant 列表缓存（对应 Python /torch/all-mirror-variants）。
 *
 * - 缓存有效期 [CACHE_TTL_MS]（3 个月），过期后下次请求自动刷新。
 * - [Mutex] 保证同一时刻只有一个协程执行网络请求，其余等待后复用结果。
 * - [invalidate] 删除缓存，下次 [getOrFetch] 必然重新请求。
 */
object TorchMirrorCacheService {

    /** 缓存有效期：3 个月 */
    const val CACHE_TTL_MS = 90L * 24 * 60 * 60 * 1000L

    /** 缓存键 */
    const val KEY_ALL_VARIANTS = "all_variants"

    private val logger = createLogger()

    private var _repo: TorchMirrorCacheRepo? = null
    val repo: TorchMirrorCacheRepo
        get() = _repo ?: throw IllegalStateException("TorchMirrorCacheService not initialized")

    fun initialize(repo: TorchMirrorCacheRepo) {
        _repo = repo
        logger.debug("[TorchMirrorCacheService] initialized")
    }

    /**
     * 防并发锁：同一时刻只允许一个协程执行 [fetch]，其余等待后复用结果。
     * Mutex 是非公平的，等待者在锁释放后会重新读取缓存，而不是重复 fetch。
     */
    private val mutex = Mutex()

    /**
     * 读取缓存；若缓存不存在或已过期，则调用 [fetch] 获取新数据并写入缓存。
     *
     * @param forceRefresh 为 true 时跳过缓存直接重新请求
     * @param fetch        实际网络请求函数，返回 JSON 字符串
     * @return 缓存或新鲜的 JSON 字符串
     */
    suspend fun getOrFetch(forceRefresh: Boolean = false, fetch: suspend () -> String): String {
        // 快速路径：不加锁先读缓存，避免每次都竞争锁
        if (!forceRefresh) {
            val cached = repo.get(KEY_ALL_VARIANTS)
            if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
                logger.debug("[TorchMirrorCacheService] cache hit (fast path), age=${System.currentTimeMillis() - cached.second}ms")
                return cached.first
            }
        }

        // 慢路径：加锁，防止并发重复 fetch
        logger.debug("[TorchMirrorCacheService] cache miss or forceRefresh=$forceRefresh, acquiring lock")
        return mutex.withLock {
            // double-check：可能已被前一个等待者刷新
            if (!forceRefresh) {
                val cached = repo.get(KEY_ALL_VARIANTS)
                if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
                    logger.debug("[TorchMirrorCacheService] cache hit (double-check), age=${System.currentTimeMillis() - cached.second}ms")
                    return@withLock cached.first
                }
            }

            logger.debug("[TorchMirrorCacheService] fetching from Python…")
            val json = fetch()
            repo.put(KEY_ALL_VARIANTS, json, System.currentTimeMillis())
            logger.debug("[TorchMirrorCacheService] cache written, valueLen=${json.length}")
            json
        }
    }

    /** 删除缓存，下次 [getOrFetch] 必然重新请求。 */
    suspend fun invalidate() {
        logger.debug("[TorchMirrorCacheService] invalidate")
        repo.delete(KEY_ALL_VARIANTS)
    }
}

// ---------------------------------------------------------------------------
// TorchMirrorVersionsCacheService：per-mirror variant 版本查询结果缓存
// ---------------------------------------------------------------------------

/**
 * TorchMirrorVersionsCacheService：缓存每个镜像站的 variant 列表查询结果（对应 Python /torch/mirror-versions）。
 *
 * 复用 [TorchMirrorCacheRepo]（同一张表），缓存键为 "mirror_versions:{mirror_key}"。
 * - 缓存有效期 [CACHE_TTL_MS]（1 小时）
 * - per-key [Mutex] 防止同一 mirror_key 并发重复请求
 */
object TorchMirrorVersionsCacheService {

    /** 缓存有效期：3 个月 */
    const val CACHE_TTL_MS = 90L * 24 * 60 * 60 * 1000L

    private val logger = createLogger()

    private var _repo: TorchMirrorCacheRepo? = null
    val repo: TorchMirrorCacheRepo
        get() = _repo ?: throw IllegalStateException("TorchMirrorVersionsCacheService not initialized")

    fun initialize(repo: TorchMirrorCacheRepo) {
        _repo = repo
        logger.debug("[TorchMirrorVersionsCacheService] initialized")
    }

    /** per-key Mutex，防止同一 mirror_key 并发重复 fetch */
    private val mutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(mirrorKey: String) = mutexes.getOrPut(mirrorKey) { Mutex() }

    /** 缓存键格式："mirror_versions:{mirror_key}" */
    private fun cacheKey(mirrorKey: String) = "mirror_versions:$mirrorKey"

    /**
     * 读取缓存；若不存在或已过期，调用 [fetch] 获取新数据并写入缓存。
     *
     * @param mirrorKey    镜像站 key，如 "nju"
     * @param forceRefresh 为 true 时跳过缓存直接重新请求
     * @param fetch        实际网络请求函数，返回 JSON 字符串
     */
    suspend fun getOrFetch(mirrorKey: String, forceRefresh: Boolean = false, fetch: suspend () -> String): String {
        val key = cacheKey(mirrorKey)

        // 快速路径：不加锁先读缓存
        if (!forceRefresh) {
            val cached = repo.get(key)
            if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
                logger.debug("[TorchMirrorVersionsCacheService] cache hit (fast path) mirror=$mirrorKey age=${System.currentTimeMillis() - cached.second}ms")
                return cached.first
            }
        }

        // 慢路径：per-key 加锁，防止并发重复 fetch
        logger.debug("[TorchMirrorVersionsCacheService] cache miss or forceRefresh=$forceRefresh mirror=$mirrorKey, acquiring lock")
        return mutexFor(mirrorKey).withLock {
            // double-check：可能已被前一个等待者刷新
            if (!forceRefresh) {
                val cached = repo.get(key)
                if (cached != null && System.currentTimeMillis() - cached.second < CACHE_TTL_MS) {
                    logger.debug("[TorchMirrorVersionsCacheService] cache hit (double-check) mirror=$mirrorKey age=${System.currentTimeMillis() - cached.second}ms")
                    return@withLock cached.first
                }
            }

            logger.debug("[TorchMirrorVersionsCacheService] fetching from Python mirror=$mirrorKey…")
            val json = fetch()
            repo.put(key, json, System.currentTimeMillis())
            logger.debug("[TorchMirrorVersionsCacheService] cache written mirror=$mirrorKey valueLen=${json.length}")
            json
        }
    }

    /** 删除指定镜像的缓存。 */
    suspend fun invalidate(mirrorKey: String) {
        logger.debug("[TorchMirrorVersionsCacheService] invalidate mirror=$mirrorKey")
        repo.delete(cacheKey(mirrorKey))
    }
}
