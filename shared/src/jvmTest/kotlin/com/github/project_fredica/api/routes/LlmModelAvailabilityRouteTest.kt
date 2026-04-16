package com.github.project_fredica.api.routes

import com.github.project_fredica.db.AppConfig
import com.github.project_fredica.db.AppConfigDb
import com.github.project_fredica.db.AppConfigService
import com.github.project_fredica.api.routes.RouteContext
import com.github.project_fredica.llm.LlmCapability
import com.github.project_fredica.llm.LlmModelConfig
import com.github.project_fredica.llm.LlmProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ktorm.database.Database
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmModelAvailabilityRouteTest {
    private lateinit var db: Database
    private lateinit var tmpDbFile: File
    private lateinit var appConfigDb: AppConfigDb
    private val noContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    @BeforeTest
    fun setup() = runBlocking {
        tmpDbFile = File.createTempFile("llm_model_availability_", ".db").also { it.deleteOnExit() }
        db = Database.connect(
            url = "jdbc:sqlite:${tmpDbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
        )
        appConfigDb = AppConfigDb(db)
        appConfigDb.initialize()
        AppConfigService.initialize(appConfigDb)
    }

    private suspend fun updateModels(models: List<LlmModelConfig>) {
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(
            config.copy(
                llmModelsJson = Json.encodeToString(models)
            )
        )
    }

    private fun buildModel(appModelId: String) = LlmModelConfig(
        id = "id-$appModelId",
        name = "Model $appModelId",
        providerType = LlmProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
        capabilities = setOf(LlmCapability.STREAMING),
        appModelId = appModelId,
    )

    @Test
    fun `returns no available models when config is empty`() = runBlocking {
        updateModels(emptyList())

        val result = LlmModelAvailabilityRoute.handler("{}", noContext).str
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(0, json["available_count"]?.jsonPrimitive?.int)
        assertEquals(false, json["has_any_available_model"]?.jsonPrimitive?.boolean)
        assertEquals(false, json["selected_model_available"]?.jsonPrimitive?.boolean)
        assertEquals("null", json["selected_model_id"].toString())
    }

    @Test
    fun `returns selected model available when selected id exists`() = runBlocking {
        updateModels(listOf(buildModel("chat_model_id"), buildModel("coding_model_id")))

        val result = LlmModelAvailabilityRoute.handler("""{"selected_model_id":["chat_model_id"]}""", noContext).str
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(2, json["available_count"]?.jsonPrimitive?.int)
        assertEquals(true, json["has_any_available_model"]?.jsonPrimitive?.boolean)
        assertEquals(true, json["selected_model_available"]?.jsonPrimitive?.boolean)
        assertEquals("chat_model_id", json["selected_model_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `returns selected model unavailable when selected id missing`() = runBlocking {
        updateModels(listOf(buildModel("chat_model_id")))

        val result = LlmModelAvailabilityRoute.handler("""{"selected_model_id":["missing_model"]}""", noContext).str
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(1, json["available_count"]?.jsonPrimitive?.int)
        assertEquals(true, json["has_any_available_model"]?.jsonPrimitive?.boolean)
        assertEquals(false, json["selected_model_available"]?.jsonPrimitive?.boolean)
        assertEquals("missing_model", json["selected_model_id"]?.jsonPrimitive?.content)
    }
}
