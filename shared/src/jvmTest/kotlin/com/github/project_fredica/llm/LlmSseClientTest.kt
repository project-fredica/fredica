package com.github.project_fredica.llm

import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope

class LlmSseClientTest {

    private var skipTest = false
    private lateinit var modelConfig: LlmModelConfig

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("llmtest_", ".db").also { it.deleteOnExit() }
        val db = Database.connect(
            url = "jdbc:sqlite:${tmpFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        val configDb = AppConfigDb(db)
        configDb.initialize()
        AppConfigService.initialize(configDb)

        val config = AppConfigService.repo.getConfig()
        if (config.llmTestApiKey.isBlank() || config.llmTestBaseUrl.isBlank() || config.llmTestModel.isBlank()) {
            skipTest = true
            return@runBlocking
        }
        modelConfig = LlmModelConfig(
            id = "test",
            name = "Test Model",
            baseUrl = config.llmTestBaseUrl,
            apiKey = config.llmTestApiKey,
            model = config.llmTestModel,
            capabilities = setOf(LlmCapability.STREAMING),
        )
    }

    @Test
    fun testStreamChat_skipsIfNoToken() {
        if (skipTest) return

        val requestBody = buildJsonObject {
            put("model", modelConfig.model)
            put("stream", true)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", "Say hello in one word.")
                }
            })
            put("max_tokens", 20)
        }.toString()

        var chunkCount = 0
        val result = runBlocking {
            LlmSseClient.streamChat(
                modelConfig = modelConfig,
                requestBody = requestBody,
                onChunk = { chunkCount++ },
            )
        }

        assertNotNull(result)
        assertTrue(result.isNotBlank(), "Expected non-empty response")
        assertTrue(chunkCount > 0, "Expected at least one chunk callback")
    }

    @Test
    fun testStreamChat_cancel() {
        if (skipTest) return

        val requestBody = buildJsonObject {
            put("model", modelConfig.model)
            put("stream", true)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", "Write a very long essay about the history of computing.")
                }
            })
            put("max_tokens", 500)
        }.toString()

        val cancelSignal = CompletableDeferred<Unit>()
        val result = runBlocking {
            coroutineScope {
                LlmSseClient.streamChat(
                    modelConfig = modelConfig,
                    requestBody = requestBody,
                    onChunk = { cancelSignal.complete(Unit) },
                    cancelSignal = cancelSignal,
                )
            }
        }

        assertNull(result, "Expected null when cancelled")
    }
}
