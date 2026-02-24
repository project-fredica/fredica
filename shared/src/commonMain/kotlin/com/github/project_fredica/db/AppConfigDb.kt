package com.github.project_fredica.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ktorm.database.Database
import org.ktorm.dsl.*
import org.ktorm.schema.BaseTable
import org.ktorm.schema.varchar
import kotlin.collections.iterator

// 键值对表：每个配置项占一行，key 为字段名，value 统一存为 TEXT
object AppConfigTable : BaseTable<Nothing>("app_config") {
    val key = varchar("key")
    val value = varchar("value")

    override fun doCreateEntity(row: QueryRowSet, withReferences: Boolean): Nothing =
        throw UnsupportedOperationException()
}

// 各配置项的默认值（均序列化为字符串存储）
private val defaultKv: Map<String, String> = mapOf(
    "server_port" to "7631",
    "data_dir" to "",
    "auto_start" to "false",
    "start_minimized" to "false",
    "open_browser_on_start" to "true",
    "theme" to "system",
    "language" to "zh-CN",
    "proxy_enabled" to "false",
    "proxy_url" to "",
    "rsshub_url" to "",
)

private fun AppConfig.toKvMap(): Map<String, String> = mapOf(
    "server_port" to serverPort.toString(),
    "data_dir" to dataDir,
    "auto_start" to autoStart.toString(),
    "start_minimized" to startMinimized.toString(),
    "open_browser_on_start" to openBrowserOnStart.toString(),
    "theme" to theme,
    "language" to language,
    "proxy_enabled" to proxyEnabled.toString(),
    "proxy_url" to proxyUrl,
    "rsshub_url" to rsshubUrl,
)

private fun Map<String, String>.toAppConfig() = AppConfig(
    serverPort = get("server_port")?.toIntOrNull() ?: 7631,
    dataDir = get("data_dir") ?: "",
    autoStart = get("auto_start")?.toBooleanStrictOrNull() ?: false,
    startMinimized = get("start_minimized")?.toBooleanStrictOrNull() ?: false,
    openBrowserOnStart = get("open_browser_on_start")?.toBooleanStrictOrNull() ?: true,
    theme = get("theme") ?: "system",
    language = get("language") ?: "zh-CN",
    proxyEnabled = get("proxy_enabled")?.toBooleanStrictOrNull() ?: false,
    proxyUrl = get("proxy_url") ?: "",
    rsshubUrl = get("rsshub_url") ?: "",
)

class AppConfigDb(private val db: Database) : AppConfigRepo {

    suspend fun initialize() {
        db.useConnection { conn ->
            conn.createStatement().use { stmt ->
                withContext(Dispatchers.IO) {
                    stmt.execute(
                        """
                    CREATE TABLE IF NOT EXISTS app_config (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                    )
                }
            }
            // 仅插入尚不存在的键，不覆盖已有值
            conn.prepareStatement("INSERT OR IGNORE INTO app_config (key, value) VALUES (?, ?)").use { ps ->
                for ((k, v) in defaultKv) {
                    ps.setString(1, k)
                    ps.setString(2, v)
                    withContext(Dispatchers.IO) {
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun getConfig(): AppConfig = withContext(Dispatchers.IO) {
        db.from(AppConfigTable)
            .select()
            .associate { row ->
                (row[AppConfigTable.key] ?: "") to (row[AppConfigTable.value] ?: "")
            }
            .toAppConfig()
    }

    override suspend fun updateConfig(config: AppConfig): Unit = withContext(Dispatchers.IO) {
        db.useConnection { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO app_config (key, value) VALUES (?, ?)").use { ps ->
                for ((k, v) in config.toKvMap()) {
                    ps.setString(1, k)
                    ps.setString(2, v)
                    ps.executeUpdate()
                }
            }
        }
    }
}
