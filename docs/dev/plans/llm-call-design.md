---
title: LLM_CALL 节点设计
order: 4
---

# LLM_CALL 节点设计

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-06
> **适用模块**：`shared/commonMain`（SSE 工具层）、`shared/jvmMain`（Executor、多模型配置）
> **关联计划**：`workflow-design.md` §4.4

---

## 1. 定位与动机

`LLM_CALL` 是工作流系统中的一等公民节点，用于在音视频处理流程后期调用 LLM 执行 AI 分析（摘要、章节划分、字幕生成等）。

### 为什么用 Kotlin/JVM 而非 Python

| 维度 | Kotlin/JVM（自实现 SSE） | Python openai 库 |
|------|------------------------|-----------------|
| Android 复用 | `commonMain` SSE 工具层可直接复用 | 无法复用 |
| 灵活性 | 原始 JSON 透传，不受强类型 SDK 约束 | openai 库强类型绑定，扩展受限（如自定义 extra_body、任意 response_format） |
| 依赖链 | 仅 Ktor HttpClient（已有依赖） | 需额外维护 Python 依赖 |
| 与 WorkflowEngine 集成 | 同进程直接回调，无跨进程开销 | 需 WebSocket 跨进程通信 |
| 多模型配置 | 统一 `LlmModelConfig` 管理，能力标签驱动 | 需在 Python 侧重复维护 |

**结论**：`LlmCallExecutor` 在 `jvmMain` 实现；SSE 解析工具类 `SseLineParser` 放在 `commonMain`，未来 Android 可直接复用。

---

## 2. 架构分层

```
commonMain/apputil/
  └── SseLineParser.kt          ← 平台无关的 SSE 行解析器（纯 Kotlin，无 IO 依赖）

commonMain/llm/
  └── LlmModelConfig.kt         ← 多模型配置数据类 + 能力标签枚举（平台无关）
  └── LlmCallPayload.kt         ← Task payload 数据类（kotlinx.serialization）

jvmMain/llm/
  └── LlmSseClient.kt           ← SSE 流式请求封装（Ktor HttpClient + SseLineParser）

jvmMain/worker/executors/
  └── LlmCallExecutor.kt        ← JVM TaskExecutor 实现
```

---

## 3. 多模型配置

### 3.1 背景

不同 LLM 服务/模型的能力差异显著：

| 能力 | 说明 | 典型支持 |
|------|------|---------|
| `VISION` | 图像输入（base64 或 URL） | GPT-4o、Claude 3、Gemini Pro Vision |
| `JSON_SCHEMA` | `response_format: json_schema` 结构化输出 | GPT-4o、DeepSeek-V3 |
| `STREAMING` | SSE 流式输出 | 绝大多数现代模型 |
| `MCP` | Model Context Protocol 工具调用 | Claude 3.5+（通过 Anthropic API） |
| `FUNCTION_CALLING` | OpenAI 格式 tools/functions | GPT-4、DeepSeek、Moonshot |
| `LONG_CONTEXT` | 上下文窗口 ≥ 128K tokens | GPT-4o、Claude 3、Gemini 1.5 |

### 3.2 LlmModelConfig 数据类

```kotlin
// commonMain/llm/LlmModelConfig.kt

enum class LlmCapability {
    VISION,           // 支持图像输入
    JSON_SCHEMA,      // 支持 response_format: json_schema
    STREAMING,        // 支持 SSE 流式输出
    MCP,              // 支持 Model Context Protocol
    FUNCTION_CALLING, // 支持 OpenAI tools/functions 格式
    LONG_CONTEXT,     // 上下文窗口 ≥ 128K tokens
}

@Serializable
data class LlmModelConfig(
    val id: String,                          // 唯一标识，如 "gpt-4o-mini-work"
    val name: String,                        // 用户可读名称，如 "GPT-4o Mini（工作用）"
    @SerialName("base_url") val baseUrl: String,   // API 基础 URL
    @SerialName("api_key")  val apiKey: String,    // API Key（Phase 1.5 明文；Phase 2+ 加密）
    val model: String,                       // 模型名称，如 "gpt-4o-mini"
    val capabilities: Set<LlmCapability> = setOf(LlmCapability.STREAMING),
    @SerialName("context_window") val contextWindow: Int = 8192,  // token 上限
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 4096,
    val temperature: Double = 0.7,           // 默认温度（可被 payload 覆盖）
    val notes: String = "",                  // 用户备注
)
```

### 3.3 多模型配置存储

`AppConfig` 新增 `llmModels` 字段，存储 `List<LlmModelConfig>` 的 JSON 序列化：

```sql
-- AppConfig 新增字段
llm_models_json  TEXT NOT NULL DEFAULT '[]'   -- List<LlmModelConfig> 序列化
```

**管理 API：**

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET`  | `/LlmModelListRoute`   | 查询所有已配置模型 |
| `POST` | `/LlmModelSaveRoute`   | 新增或更新模型配置（按 id 幂等） |
| `POST` | `/LlmModelDeleteRoute` | 删除模型配置 |

### 3.4 在 payload_tpl 中引用模型

`LLM_CALL` 节点的 `payload_tpl` 通过 `model_id` 字段引用已配置的模型，WorkflowEngine 在渲染时从 `AppConfig.llmModels` 中查找并注入 `base_url`、`api_key`、`model`：

```json
{
  "model_id": "gpt-4o-mini-work",
  "system_prompt": "你是一个视频内容分析助手。",
  "user_prompt": "请分析以下转录文本：\n%context.transcribe.text%",
  "response_format": {
    "type": "json_schema",
    "json_schema": { "...": "..." }
  }
}
```

WorkflowEngine 渲染时：
1. 按 `model_id` 查找 `LlmModelConfig`
2. 校验所需能力（如 `JSON_SCHEMA`）是否在 `capabilities` 中，不满足则提前失败并给出明确错误
3. 将 `base_url`、`api_key`、`model` 注入最终 payload，传给 `LlmCallExecutor`

---

## 4. SSE 客户端实现（自造轮子）

### 4.1 为什么自实现

现有 Kotlin LLM SDK（如 `openai-kotlin`）使用强类型模型绑定，无法灵活传递任意 `json_schema`、`tools`、`extra_body` 等字段。自实现只需：

1. Ktor `HttpClient` 发起 `POST`，设置 `Accept: text/event-stream`
2. 逐行读取响应体，解析 `data: {...}` 格式
3. 累积 `delta.content` 直到收到 `[DONE]`

### 4.2 SseLineParser（commonMain）

```kotlin
// commonMain/apputil/SseLineParser.kt

/**
 * 解析单行 SSE 文本，返回 data 字段内容（去掉 "data: " 前缀）。
 * 返回 null 表示非 data 行（注释行、空行、event/id 行）。
 * 返回 "[DONE]" 表示流结束信号。
 */
object SseLineParser {
    fun parseLine(line: String): String? {
        if (line.startsWith("data: ")) return line.removePrefix("data: ").trim()
        return null  // 忽略 event:、id:、注释行、空行
    }
}
```

### 4.3 LlmSseClient（jvmMain）

```kotlin
// jvmMain/llm/LlmSseClient.kt

/**
 * 流式调用 OpenAI 兼容 Chat Completions API（SSE）。
 *
 * @param modelConfig  模型配置（含 base_url、api_key）
 * @param requestBody  完整请求 JSON 字符串（调用方构造，不做强类型绑定）
 * @param onChunk      每收到一个 delta.content 片段时回调
 * @param cancelSignal 取消信号（完成时中断流）
 * @return 完整 content 字符串（所有 delta 拼接）；取消时返回 null
 */
suspend fun streamChat(
    modelConfig: LlmModelConfig,
    requestBody: String,
    onChunk: ((String) -> Unit)? = null,
    cancelSignal: CompletableDeferred<Unit>? = null,
): String? {
    val client = GlobalVars.httpClient

    // 非流式降级：模型不支持 STREAMING 时直接读完整响应
    if (LlmCapability.STREAMING !in modelConfig.capabilities) {
        return fetchNonStreaming(client, modelConfig, requestBody)
    }

    val fullContent = StringBuilder()

    client.preparePost("${modelConfig.baseUrl}/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
        header(HttpHeaders.Accept, "text/event-stream")
        contentType(ContentType.Application.Json)
        setBody(requestBody)
        timeout { requestTimeoutMillis = 120_000 }
    }.execute { response ->
        val channel: ByteReadChannel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            if (cancelSignal?.isCompleted == true) return@execute
            val line = channel.readUTF8Line() ?: break
            val data = SseLineParser.parseLine(line) ?: continue
            if (data == "[DONE]") break
            val chunk = extractDeltaContent(data) ?: continue
            fullContent.append(chunk)
            onChunk?.invoke(chunk)
        }
    }

    if (cancelSignal?.isCompleted == true) return null
    return fullContent.toString()
}

/** 非流式降级：直接读取完整 JSON 响应体中的 content */
private suspend fun fetchNonStreaming(
    client: HttpClient,
    modelConfig: LlmModelConfig,
    requestBody: String,
): String {
    val response = client.post("${modelConfig.baseUrl}/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer ${modelConfig.apiKey}")
        contentType(ContentType.Application.Json)
        setBody(requestBody)
        timeout { requestTimeoutMillis = 120_000 }
    }
    val body = response.bodyAsText()
    return Json { ignoreUnknownKeys = true }
        .parseToJsonElement(body).jsonObject
        .getValue("choices").jsonArray[0].jsonObject
        .getValue("message").jsonObject
        .getValue("content").jsonPrimitive.content
}

/** 从单条 SSE data JSON 中提取 choices[0].delta.content */
private fun extractDeltaContent(dataJson: String): String? = runCatching {
    Json { ignoreUnknownKeys = true }
        .parseToJsonElement(dataJson).jsonObject
        ["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("delta")?.jsonObject
        ?.get("content")?.jsonPrimitive?.contentOrNull
}.getOrNull()
```

---

## 5. LlmCallExecutor

### 5.1 Task Payload 格式

`LLM_CALL` 节点的 `payload_tpl` 渲染后得到如下 JSON：

```json
{
  "model_id": "gpt-4o-mini-work",
  "base_url": "https://api.openai.com/v1",
  "api_key": "sk-...",
  "model": "gpt-4o-mini",
  "system_prompt": "你是一个视频内容分析助手。",
  "user_prompt": "请分析以下转录文本：\n...",
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "video_analysis",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {
          "summary":  { "type": "string" },
          "topics":   { "type": "array", "items": { "type": "string" }},
          "chapters": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "title":     { "type": "string" },
                "start_sec": { "type": "number" }
              },
              "required": ["title", "start_sec"]
            }
          }
        },
        "required": ["summary", "topics", "chapters"]
      }
    }
  },
  "temperature": 0.3,
  "max_tokens": 4096
}
```

`base_url`、`api_key`、`model` 由 WorkflowEngine 从 `LlmModelConfig` 注入，`payload_tpl` 中只需写 `model_id`。

### 5.2 LlmCallPayload 数据类

```kotlin
// commonMain/llm/LlmCallPayload.kt

@Serializable
data class LlmCallPayload(
    @SerialName("model_id")       val modelId: String,
    @SerialName("base_url")       val baseUrl: String,       // 由 WorkflowEngine 注入
    @SerialName("api_key")        val apiKey: String,        // 由 WorkflowEngine 注入
    val model: String,                                        // 由 WorkflowEngine 注入
    @SerialName("system_prompt")  val systemPrompt: String? = null,
    @SerialName("user_prompt")    val userPrompt: String,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")     val maxTokens: Int? = null,
)
```

### 5.3 Executor 实现骨架

```kotlin
// jvmMain/worker/executors/LlmCallExecutor.kt

class LlmCallExecutor : TaskExecutor {

    override suspend fun execute(task: Task): ExecuteResult {
        val payload = decodeJson<LlmCallPayload>(task.payload)
        val modelConfig = AppConfigService.repo.getLlmModel(payload.modelId)
            ?: return ExecuteResult(error = "未找到模型配置：${payload.modelId}", errorType = "CONFIG_ERROR")

        val cancelSignal = TaskCancelService.register(task.id)
        try {
            val requestBody = buildRequestJson(payload, modelConfig)
            var progressPct = 0

            val content = LlmSseClient.streamChat(
                modelConfig = modelConfig,
                requestBody = requestBody,
                onChunk = { _ ->
                    if (progressPct < 95) {
                        progressPct = minOf(progressPct + 2, 95)
                        TaskService.repo.updateProgress(task.id, progressPct)
                    }
                },
                cancelSignal = cancelSignal,
            ) ?: return ExecuteResult(error = "已取消", errorType = "CANCELLED")

            validateRequiredFields(content, payload.responseFormat)
            TaskService.repo.updateProgress(task.id, 100)
            return ExecuteResult(result = content)

        } finally {
            TaskCancelService.unregister(task.id)
        }
    }

    private fun buildRequestJson(payload: LlmCallPayload, modelConfig: LlmModelConfig): String {
        return buildJsonObject {
            put("model", modelConfig.model)
            put("stream", LlmCapability.STREAMING in modelConfig.capabilities)
            put("messages", buildJsonArray {
                payload.systemPrompt?.let {
                    addJsonObject { put("role", "system"); put("content", it) }
                }
                addJsonObject { put("role", "user"); put("content", payload.userPrompt) }
            })
            (payload.temperature ?: modelConfig.temperature).let { put("temperature", it) }
            (payload.maxTokens ?: modelConfig.maxOutputTokens).let { put("max_tokens", it) }
            // response_format 原样透传（JsonElement），不做强类型绑定
            payload.responseFormat?.let { put("response_format", it) }
        }.toString()
    }

    /** 校验结构化输出的 required 字段是否存在 */
    private fun validateRequiredFields(content: String, responseFormat: JsonElement?) {
        val schema = responseFormat?.jsonObject
            ?.get("json_schema")?.jsonObject
            ?.get("schema")?.jsonObject ?: return
        val required = schema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: return
        val result = runCatching {
            Json.parseToJsonElement(content).jsonObject
        }.getOrElse {
            throw IllegalStateException("LLM 响应不是合法 JSON：${content.take(200)}")
        }
        val missing = required.filter { it !in result }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("LLM 响应缺少必填字段：$missing")
        }
    }
}
```

---

## 6. 支持的 API 兼容性

| 服务 | base_url 示例 | 推荐能力标签 |
|------|--------------|------------|
| OpenAI | `https://api.openai.com/v1` | `STREAMING, JSON_SCHEMA, FUNCTION_CALLING, VISION, LONG_CONTEXT` |
| DeepSeek | `https://api.deepseek.com/v1` | `STREAMING, JSON_SCHEMA, FUNCTION_CALLING, LONG_CONTEXT` |
| Moonshot | `https://api.moonshot.cn/v1` | `STREAMING, FUNCTION_CALLING, LONG_CONTEXT` |
| OpenRouter | `https://openrouter.ai/api/v1` | 取决于所选模型，手动配置 |
| Ollama | `http://localhost:11434/v1` | `STREAMING`（`JSON_SCHEMA` 取决于模型） |
| Anthropic（via OpenRouter） | `https://openrouter.ai/api/v1` | `STREAMING, VISION, MCP, LONG_CONTEXT` |

所有服务均通过同一套 `LlmSseClient` 调用，差异仅在 `base_url`、`api_key` 和 `capabilities` 标签。

---

## 7. 能力校验规则

WorkflowEngine 在渲染 `LLM_CALL` 节点 payload 时，根据 payload 内容自动推断所需能力并校验：

| payload 字段 | 所需能力 | 不满足时行为 |
|-------------|---------|------------|
| `response_format.type == "json_schema"` | `JSON_SCHEMA` | WorkflowNodeRun 置为 `failed`，错误信息说明原因 |
| `messages` 含图像内容（`type: image_url`） | `VISION` | 同上 |
| `tools` 字段存在 | `FUNCTION_CALLING` | 同上 |
| `stream: true`（默认） | `STREAMING` | 自动降级为非流式，不报错 |

---

## 8. API Key 存储策略

| 阶段 | 存储方式 | 读取路径 |
|------|---------|---------|
| Phase 1.5 | `LlmModelConfig.apiKey`（明文，存入 `AppConfig.llmModelsJson`） | WorkflowEngine 直接读取 |
| Phase 2+ | 加密 Secret Store，`LlmModelConfig.apiKey` 存 secret 引用 key | 解密后注入 |

---

## 9. 前端：模型配置页

**设置页新增「LLM 模型」分区：**

- 模型列表（卡片形式），每张卡片显示：名称、base_url、model、能力标签（彩色 badge）
- 新增/编辑模型弹窗：
  - 名称、base_url、api_key（密码框）、model 输入框
  - 能力标签多选（checkbox 列表，含说明文字）
  - context_window / max_output_tokens 数字输入
  - 「测试连接」按钮（发送简单 ping 请求验证 API Key 有效性）
- 删除模型（若有工作流节点引用该 model_id，给出警告）

---

## 10. 实现清单

- [x] `SseLineParser.kt`（commonMain）：SSE 行解析
- [x] `LlmModelConfig.kt`（commonMain）：多模型配置数据类 + `LlmCapability` 枚举 + `LlmDefaultRoles` + `LlmProviderType`
- [x] `LlmCallPayload.kt`（commonMain）：payload 数据类
- [x] `LlmSseClient.kt`（jvmMain）：流式 SSE 请求，支持取消信号 + 非流式降级
- [ ] `LlmCallExecutor.kt`（jvmMain）：Executor 实现，注册到 `FredicaApi`（Phase 1.5）
- [x] `AppConfig` 新增 5 个字段（`llmModelsJson` / `llmDefaultRolesJson` / `llmTestApiKey` / `llmTestBaseUrl` / `llmTestModel`）；`AppConfigDb` 同步
- [x] `LlmModelListRoute` / `LlmModelSaveRoute` / `LlmModelDeleteRoute` / `LlmDefaultRolesGetRoute` / `LlmDefaultRolesSaveRoute`（5 个路由）
- [x] `LlmSseClientTest.kt`（jvmTest）：无令牌自动跳过，测试流式请求 + 取消
- [x] 前端 `app-desktop-setting.tsx`：新增 LLM 模型配置入口卡片
- [x] 前端 `app-desktop-setting.llm-model-config.tsx`：模型列表 + 编辑弹窗 + 能力标签 + 默认角色分配 + 测试令牌配置

### 设计补充（2026-03-06）

- `LlmProviderType` 枚举解耦不同接口类型（当前 `OPENAI_COMPATIBLE`，未来扩展 `ANTHROPIC_NATIVE` 等）
- `LlmDefaultRoles` 独立于 `LlmModelConfig`，存储 `llm_default_roles_json`，只保留 `model_id` 引用
- 测试令牌（`llm_test_api_key` / `llm_test_base_url` / `llm_test_model`）独立于业务配置，在前端测试令牌区块配置
