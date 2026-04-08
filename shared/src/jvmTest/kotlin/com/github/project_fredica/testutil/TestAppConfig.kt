package com.github.project_fredica.testutil

// =============================================================================
// TestAppConfig —— 测试用配置读取工具
// =============================================================================
//
// 从应用真实 DB（AppUtil.Paths.appDbPath）读取测试专用配置项，
// 供需要真实凭据的集成测试使用（如 LLM API key）。
//
// 只暴露测试相关字段，不返回完整 AppConfig，避免测试代码依赖生产配置结构。
//
// 使用方式：
//   val cfg = TestAppConfig.load() ?: return  // DB 不存在，跳过
//   if (cfg.llmTestApiKey.isBlank()) return   // 未配置，跳过
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.loadJsonModel
import com.github.project_fredica.db.AppConfigTable
import com.github.project_fredica.llm.LlmDefaultRoles
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmTestConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.ktorm.database.Database
import org.ktorm.dsl.associate
import org.ktorm.dsl.from
import org.ktorm.dsl.inList
import org.ktorm.dsl.select
import org.ktorm.dsl.where
import java.io.File


data class LlmTestConfigImpl(
    val llmModelsJson: String,
    val llmDefaultRolesJson: String,
) : LlmTestConfig {
    private val devTestModel: LlmModelConfig? get() {
        val roles = llmDefaultRolesJson.loadJsonModel<LlmDefaultRoles>().getOrNull() ?: return null
        val modelId = roles.devTestModelId.takeIf { it.isNotBlank() } ?: return null
        val models = llmModelsJson.loadJsonModel<List<LlmModelConfig>>().getOrNull() ?: return null
        return models.firstOrNull { it.id == modelId }
    }

    override val llmTestApiKey: String get() = devTestModel?.apiKey ?: ""
    override val llmTestBaseUrl: String get() = devTestModel?.baseUrl ?: ""
    override val llmTestModel: String get() = devTestModel?.model ?: ""
}

object TestAppConfig {
    private val logger = createLogger()

    /**
     * 应用真实 DB 路径。
     *
     * 应用从 composeApp/ 目录启动，DB 在 composeApp/.data/db/fredica_app.db。
     * 测试从 shared/ 目录启动，需向上一级找到 composeApp/.data。
     */
    private fun resolveDbFile(): File {
        val workDir = File(System.getProperty("user.dir")).absoluteFile
        // 测试工作目录通常是 shared/，向上找 composeApp/.data
        val candidates = listOf(
            workDir.resolve(".data").resolve("db").resolve("fredica_app.db"),
            workDir.parentFile?.resolve("composeApp")?.resolve(".data")?.resolve("db")?.resolve("fredica_app.db"),
        )
        val res = candidates.filterNotNull().firstOrNull { it.exists() }
            ?: candidates.filterNotNull().first()  // 不存在时返回第一个候选，调用方检查 exists()
        logger.debug("[TestAppConfig] resolveDbFile is $res")
        return res
    }

    /**
     * 从应用真实 DB 读取 LLM 测试配置。
     *
     * @return [LlmTestConfig]，若 DB 文件不存在则返回 null（测试应跳过）。
     */
    fun loadLlmConfig(): LlmTestConfig? {
        val dbFile = resolveDbFile()
        if (!dbFile.exists()) {
            logger.info("[TestAppConfig] dbFile not exist")
            return null
        }

        val db = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )

        val keys = listOf("llm_models_json", "llm_default_roles_json")
        val map = runBlocking(Dispatchers.IO) {
            db.from(AppConfigTable)
                .select(AppConfigTable.key, AppConfigTable.value)
                .where { AppConfigTable.key inList keys }
                .associate { row ->
                    (row[AppConfigTable.key] ?: "") to (row[AppConfigTable.value] ?: "")
                }
        }
        logger.debug("[TestAppConfig] map from AppConfigTable is : $map")

        val res = LlmTestConfigImpl(
            llmModelsJson = map["llm_models_json"] ?: "[]",
            llmDefaultRolesJson = map["llm_default_roles_json"] ?: "{}",
        )
        logger.info("[TestAppConfig] config is $res")
        return res
    }
}
