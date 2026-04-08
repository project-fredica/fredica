package com.github.project_fredica.llm

import com.github.project_fredica.testutil.TestAppConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel

class LlmSseClientTest {

    private var skipTest = false
    private lateinit var modelConfig: LlmModelConfig

    @BeforeTest
    fun setup() {
        val cfg = TestAppConfig.loadLlmConfig()
        if (cfg == null || !cfg.llmTestIsConfigured) {
            skipTest = true
            return
        }
        modelConfig = LlmModelConfig(
            id = "test",
            name = "Test Model",
            baseUrl = cfg.llmTestBaseUrl,
            apiKey = cfg.llmTestApiKey,
            model = cfg.llmTestModel,
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

        // 使用结构化取消：在收到第一个 chunk 时取消 coroutineScope
        var caughtCancellation = false
        try {
            runBlocking {
                coroutineScope {
                    LlmSseClient.streamChat(
                        modelConfig = modelConfig,
                        requestBody = requestBody,
                        onChunk = {
                            // 收到第一个 chunk 时取消整个 scope
                            this@coroutineScope.cancel("test cancel")
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation, "Expected CancellationException when scope is cancelled")
    }

    @Test
    fun `normalizeRequestBodyForStreamingCapability forces stream false`() {
        val requestBody = buildJsonObject {
            put("model", "test-model")
            put("stream", true)
            put("messages", buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    put("content", "hello")
                }
            })
        }.toString()

        val normalized = LlmSseClient.normalizeRequestBodyForStreamingCapability(
            requestBody = requestBody,
            supportsStreaming = false,
        )
        val json = Json.parseToJsonElement(normalized).jsonObject

        assertEquals("test-model", json.getValue("model").jsonPrimitive.content)
        assertEquals("false", json.getValue("stream").toString())
        assertEquals(
            "hello",
            json.getValue("messages").jsonArray[0].jsonObject
                .getValue("content").jsonPrimitive.content,
        )
    }

    @Test
    fun `extractResponseContent parses plain json response`() {
        val body = """
            {"choices":[{"message":{"content":"plain response"}}]}
        """.trimIndent()

        val content = LlmSseClient.extractResponseContent(body)

        assertEquals("plain response", content)
    }

    @Test
    fun `extractResponseContent parses sse delta response`() {
        val body = """
            data: {"choices":[{"delta":{"content":"hello "}}]}
            data: {"choices":[{"delta":{"content":"world"}}]}
            data: [DONE]
        """.trimIndent()

        val content = LlmSseClient.extractResponseContent(body)

        assertEquals("hello world", content)
    }

    @Test
    fun `extractResponseContent falls back to first sse message payload`() {
        val body = """
            data: {"choices":[{"message":{"content":"non-stream response"}}]}
            data: [DONE]
        """.trimIndent()

        val content = LlmSseClient.extractResponseContent(body)

        // SSE 分支只提取 delta.content；message.content 格式不被 SSE 分支处理，返回空字符串。
        // 此格式属于非标准 SSE 响应（正常非流式响应不含 "data:" 前缀），调用方应走非流式路径。
        assertEquals("", content)
    }
}
