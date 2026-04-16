package com.github.project_fredica.api.routes

import com.github.project_fredica.api.routes.RouteContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmModelProbeRouteTest {
    @Serializable
    private data class ProbeError(val error: String? = null)

    private val noContext = RouteContext(identity = null, clientIp = null, userAgent = null)

    @Test
    fun `probe returns error when base url is invalid`() = runBlocking {
        val result = LlmModelProbeRoute.handler(
            """
            {"provider_type":"OPENAI_COMPATIBLE","base_url":"","api_key":"x","model":"gpt-4o-mini"}
            """.trimIndent(),
            noContext,
        )
        val error = Json.decodeFromString<ProbeError>(result.str)
        assertNotNull(error.error)
        Unit
    }

    @Test
    fun `probe response shape uses error field on failure`() = runBlocking {
        val result = LlmModelProbeRoute.handler(
            """
            {"provider_type":"OPENAI_COMPATIBLE","base_url":"http://127.0.0.1:1/v1","api_key":"x","model":"qwen-128k"}
            """.trimIndent(),
            noContext,
        )
        val root = Json.parseToJsonElement(result.str).jsonObject
        assertTrue(root.containsKey("error"))
    }
}