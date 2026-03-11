---
title: 提示词图（Prompt Graph）架构设计
order: 3
---

# 提示词图（Prompt Graph）架构设计

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-08
> **适用模块**：`shared`、`fredica-webui`
> **关联计划**：`workflow-design.md`、`llm-call-design.md`

---

## 1. 背景与动机

### 1.1 为什么需要 Prompt Graph

当前 `LLM_CALL` 节点（`workflow-design.md §4.4`）已支持在工作流中调用 LLM，但它把"提示词"当作 `payload_tpl` 的一个字段直接内联在工作流节点里。这在以下场景下会失控：

| 场景 | 问题 |
|------|------|
| AI 学习助手 | 一次学习任务需要多轮 LLM 调用（提取知识点 → 生成问题 → 评估答案），提示词之间有数据依赖，且每步输出 schema 不同 |
| AI 视频剪辑 | 转录文本 → 章节分割 → 剪辑点提取 → 字幕生成，每步 schema 严格，且用户可能对某步结果手动修正后要求重跑后续步骤 |
| AI 数据清洗 | 批量处理，每条数据经过多个 LLM 节点，schema 版本随业务迭代频繁变化 |

核心矛盾：**提示词 Graph 需要频繁变动（提示词内容、节点结构、schema），但下游消费方需要稳定的类型契约。**

### 1.2 设计目标

1. **Prompt Graph 作为一等公民**：独立于 WorkflowDefinition 管理，可被多个工作流复用
2. **结构化输出的类型安全**：JSON Schema 绑定在**边**上，描述"此边传递的数据契约"，与节点解耦
3. **Function Call 原生支持**：工具定义（tool definitions）作为节点的一等公民字段
4. **版本管理与 Fork**：系统内置 Graph 可被用户 Fork，版本迁移有明确的兼容性策略
5. **用户手动更正兼容**：流水线能感知"某节点的输出已被人工覆盖"，并据此决定是否重跑下游

---

## 2. 核心概念

### 2.1 三层模型

```
┌──────────────────────────────────────────┐
│           PromptGraphDef                 │  ← 提示词图"设计"（静态蓝图）
│  节点定义 + 边定义（含 schema）+ 版本     │
└───────────────────┬──────────────────────┘
                    │ 被 WorkflowDefinition 的 LLM_CALL 节点引用
                    │ 或直接实例化（独立运行）
                    ▼
┌──────────────────────────────────────────┐
│           PromptGraphRun                 │  ← 提示词图"运行实例"
│  context + 节点状态 + 人工覆盖记录        │
└───────────────────┬──────────────────────┘
                    │ 每个节点对应一个
                    ▼
┌──────────────────────────────────────────┐
│           PromptNodeRun                  │  ← 单节点执行记录
│  input_snapshot + output + override_info │
└──────────────────────────────────────────┘
```

**与现有 Workflow 的关系：**

- `WorkflowDefinition` 中的 `LLM_CALL` 节点的 `payload_tpl` 新增 `prompt_graph_def_id` 字段
- `WorkflowEngine` 在执行 `LLM_CALL` 节点时，将控制权委托给 `PromptGraphEngine`
- `PromptGraphRun` 的最终输出写回 `WorkflowRun.context_json`，与其他节点无缝衔接

### 2.2 PromptGraphDef（提示词图设计）

```
PromptGraphDef {
  id              唯一标识（UUID）
  name            人类可读名称，如 "视频章节分析图"
  description     描述
  nodes           PromptNodeDef 列表（见 §2.3）
  edges           PromptEdgeDef 列表（见 §2.4）  ← schema 绑定在边上
  schema_registry SchemaEntry 列表（见 §3）       ← 全图 schema 注册表
  migrations      SchemaMigration 列表（见 §3.4）
  version         版本号（整数，每次保存 +1）
  schema_version  schema 语义版本（"1.0.0" 格式，见 §3.3）
  source_type     system | user | system_fork
  parent_def_id   Fork 来源（system_fork 时有值）
  created_at      Unix 秒
  updated_at      Unix 秒
}
```

### 2.3 PromptNodeDef（提示词节点定义）

节点只负责"生成数据"，不声明输出 schema——schema 由出边声明（见 §2.4）。

节点类型：

| 类型 | 说明 |
|------|------|
| `LLM_CALL` | 调用 LLM，有 system_prompt / user_prompt_tpl / tools |
| `CONDITION` | 基于上下文 JS 表达式的条件分支 |
| `MERGE` | 等待所有入边完成（并行汇聚） |
| `TRANSFORM` | 纯 JS 数据变换，无 LLM 调用（格式转换、字段提取） |
| `HUMAN_REVIEW` | 暂停点：等待用户确认或修正后继续（见 §5） |

```
PromptNodeDef {
  id                  节点唯一标识
  type                PromptNodeType（见上表）
  label               展示名称

  // LLM_CALL 专属
  model_role          使用哪个默认角色（chat | vision | coding | custom）
  model_id_override   若非 null，覆盖 model_role 指定的模型
  system_prompt       系统提示词（支持 %变量% 替换）
  user_prompt_tpl     用户提示词模板（支持 %变量% 替换）
  tools               ToolDef 列表（Function Call 工具定义，见 §4）
  tool_choice         "auto" | "none" | {type:"function", function:{name:...}}
  temperature_override 覆盖模型默认温度（null = 使用模型配置）
  max_tokens_override  覆盖最大 token 数
  max_tool_rounds     最多多少轮 function call 循环（默认 5）

  // CONDITION 专属
  condition_expr      JS 表达式（同 WorkflowDefinition）

  // TRANSFORM 专属
  transform_expr      JS 表达式，输入 ctx，返回新的字段对象

  // HUMAN_REVIEW 专属
  review_prompt       展示给用户的说明文字

  // 通用
  context_include     列表，指定哪些上游节点的输出注入到此节点的 prompt（见 §7.2）
  context_max_chars   注入上下文的最大字符数（超出则截断）
  max_retries         失败重试次数（默认 0）
  position            画布坐标 {x, y}
}
```

### 2.4 PromptEdgeDef（边定义，含 Schema 绑定）

**Schema 绑定在边上**，而非节点上。这是本架构的核心设计决策：

- 一条边代表"从 source 节点流向 target 节点的数据通道"
- `schema_id` 声明"此通道传递的数据必须符合该 schema"
- 同一节点可以有多条出边，每条边可以绑定不同的 schema（例如 function call 的不同工具返回不同结构）
- 没有 `schema_id` 的边传递自由文本（如 CONDITION 节点的出边不需要 schema）

```
PromptEdgeDef {
  id              边唯一标识
  source_node_id  出发节点的 PromptNodeDef.id
  target_node_id  目标节点的 PromptNodeDef.id
  condition_key   仅 CONDITION 节点出边使用
  schema_id       引用 schema_registry 中的 SchemaEntry.id（null = 自由文本）
  label           可选的边标签（前端展示用，如 "章节列表"）
}
```

**为什么 schema 在边上而非节点上？**

1. **语义更准确**：schema 描述的是"数据流"的契约，而非节点的属性。节点是"处理器"，边是"数据管道"。
2. **支持多输出**：一个节点可以通过不同出边输出不同结构的数据（如 function call 节点调用了多个工具，每个工具的结果结构不同，分别流向不同的下游节点）。
3. **CONDITION 节点天然兼容**：CONDITION 节点的出边是控制流，不携带数据，`schema_id=null` 自然表达。
4. **下游节点的入边即是其输入契约**：下游节点通过检查入边的 `schema_id` 就能知道自己会收到什么结构的数据，无需查询上游节点定义。

**示例（视频章节分析图）：**

```
[转录节点] ──(schema: transcript)──→ [章节分割 LLM]
                                           │
                          (schema: chapter_list)──→ [剪辑点提取 LLM]
                                           │
                          (schema: chapter_list)──→ [HUMAN_REVIEW]
                                                          │
                                     (schema: chapter_list_reviewed)──→ [字幕生成 LLM]
```

---
## 3. 类型安全：Schema 注册表

### 3.1 SchemaEntry

每个 `PromptGraphDef` 内嵌一个 `schema_registry`，集中管理全图所有边可引用的 schema：

```json
{
  "schema_registry": [
    {
      "id": "chapter_list",
      "description": "视频章节列表",
      "version": "1.2.0",
      "breaking_change": false,
      "schema": {
        "type": "object",
        "properties": {
          "chapters": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "title":     { "type": "string" },
                "start_sec": { "type": "number" },
                "end_sec":   { "type": "number" },
                "summary":   { "type": "string" }
              },
              "required": ["title", "start_sec", "end_sec"]
            }
          }
        },
        "required": ["chapters"]
      }
    }
  ]
}
```

```
SchemaEntry {
  id               在本 PromptGraphDef 内唯一，如 "chapter_list"
  description      人类可读描述
  schema           标准 JSON Schema（Draft 7）对象
  version          语义版本字符串（"major.minor.patch"）
  breaking_change  此版本相对上一版本是否为破坏性变更（见 §3.3）
}
```

### 3.2 边 schema 的执行语义

边上的 `schema_id` 在运行时有两个作用：

**（1）LLM 输出约束**：当 source 节点是 `LLM_CALL` 时，`PromptGraphEngine` 在构建请求时，
从出边的 `schema_id` 取出 `SchemaEntry.schema`，注入到请求体的 `response_format`：

```json
{
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "chapter_list",
      "strict": true,
      "schema": { ... }
    }
  }
}
```

**（2）数据流校验**：节点完成后，`PromptGraphEngine` 用出边的 schema 校验节点输出，
校验失败则触发重试或标记节点失败，**不允许不合规的数据流入下游**。

**多出边节点的处理：**

若一个 `LLM_CALL` 节点有多条出边且各自绑定不同 schema，执行时取**所有出边 schema 的并集**作为 `response_format`（即要求 LLM 输出同时满足所有出边的 schema）。若出边 schema 互相冲突，`PromptGraphEngine` 在创建 `PromptGraphRun` 时提前报错，拒绝启动。

### 3.3 Schema 版本管理

**语义版本规则：**

| 变更类型 | 版本号变化 | breaking_change |
|---------|-----------|-----------------|
| 新增可选字段 | patch +1 | false |
| 修改字段描述 | patch +1 | false |
| 新增必填字段 | minor +1 | false* |
| 删除字段 | major +1 | **true** |
| 修改字段类型 | major +1 | **true** |
| 重命名字段 | major +1 | **true** |

`PromptGraphDef.schema_version` 是整个图的对外契约版本，由图内所有 SchemaEntry 的最高版本推导：
- 任意 SchemaEntry `breaking_change=true` → `schema_version` major +1
- 任意 SchemaEntry minor/patch 变化 → 对应位 +1

**context_json 中的版本标注：**

节点输出写入 `PromptGraphRun.context_json` 时，附带 schema 元信息：

```json
{
  "chapter_split": {
    "_schema_id": "chapter_list",
    "_schema_version": "1.2.0",
    "chapters": [...]
  }
}
```

下游节点读取上游数据时，`PromptGraphEngine` 检查 `_schema_version` 的 major 是否与当前 schema_registry 中的 major 一致，不一致则触发迁移（见 §3.4）。

### 3.4 历史数据的 Schema 迁移

当 `breaking_change=true` 时，历史 `PromptNodeRun.output_json` 格式已过时。

**懒迁移 + 迁移脚本注册：**

```
SchemaMigration {
  schema_id      对应的 SchemaEntry.id
  from_version   "1.x.x"（支持通配符）
  to_version     "2.0.0"
  migrate_expr   JS 表达式，输入旧数据对象，返回新数据对象
}
```

`PromptGraphEngine` 读取历史 `output_json` 时，检测到版本不匹配则自动应用迁移链，
将旧数据升级到当前版本后再使用，**不修改原始 `output_json`**（保留历史快照）。

---

## 4. Function Call 与 MCP 支持

### 4.1 两种工具来源

`LLM_CALL` 节点支持两种工具来源，可以混用：

| 来源 | 配置方式 | 适用场景 |
|------|---------|---------|
| **内联 ToolDef** | `PromptNodeDef.tools` 列表 | 工具逻辑简单，结果直接写入 context |
| **MCP Server** | `PromptNodeDef.mcp_servers` 列表 | 工具由外部 MCP 服务提供，支持复杂副作用（文件读写、数据库查询、外部 API） |

### 4.2 ToolDef（内联工具定义）

```
ToolDef {
  name           工具名称（snake_case，如 "extract_clip_points"）
  description    工具描述（LLM 据此决定何时调用）
  parameters     JSON Schema 对象（描述工具入参结构）
  handler        工具处理方式（ToolHandler 枚举，见下）
  output_edge_id 此工具的调用结果流向哪条出边（关联 PromptEdgeDef.id）
}
```

**ToolHandler 类型：**

| 类型 | 说明 |
|------|------|
| `CONTEXT_WRITE` | 将工具调用参数直接写入 context，不执行外部逻辑（最常用） |
| `WORKFLOW_TASK` | 触发一个新的 WorkflowRun 子任务 |
| `TRANSFORM_EXPR` | 执行 JS 表达式处理工具参数，返回结果写入 context |

**`output_edge_id` 的作用：**

工具调用结果通过 `output_edge_id` 关联到一条出边，该出边的 `schema_id` 约束工具结果的结构。
工具的 `parameters` schema（LLM 侧约束）与出边 `schema_id`（引擎侧校验）形成双重约束，
两者通常一致，但出边 schema 可以更宽松（允许引擎在写入 context 前做格式转换）。

### 4.3 MCP Server 配置

`PromptNodeDef.mcp_servers` 是 `McpServerRef` 列表，引用在 AppConfig 中注册的 MCP 服务：

```
McpServerRef {
  server_id        引用 AppConfig.mcpServersJson 中的 McpServerConfig.id
  allowed_tools    白名单：只暴露给 LLM 的工具名列表（null = 全部暴露）
  output_edge_map  Map<tool_name, edge_id>：指定每个工具的结果流向哪条出边
                   （未在 map 中的工具结果写入 context 但不流向特定出边）
}
```

**AppConfig 中的 MCP 服务注册（新增字段）：**

```
McpServerConfig {
  id           唯一标识
  name         人类可读名称
  transport    "stdio" | "sse" | "streamable_http"
  command      仅 stdio：启动命令（如 "npx -y @modelcontextprotocol/server-filesystem"）
  args         仅 stdio：命令参数列表
  url          仅 sse/streamable_http：服务地址
  env          环境变量 Map（可引用 %secrets.xxx% 注入凭据）
  capabilities 此服务提供的能力标签（tools | resources | prompts）
}
```

**MCP 工具的发现与注入：**

`PromptGraphEngine` 在节点执行前：
1. 连接 `mcp_servers` 中引用的所有 MCP 服务
2. 调用 `tools/list` 获取工具列表，按 `allowed_tools` 过滤
3. 将 MCP 工具与内联 `ToolDef` 合并，统一注入到 LLM 请求的 `tools` 字段
4. LLM 调用 MCP 工具时，`PromptGraphEngine` 转发 `tools/call` 请求到对应 MCP 服务
5. MCP 工具返回结果后，按 `output_edge_map` 路由到对应出边（或写入 context）

**MCP 工具结果的 schema 校验：**

MCP 工具的返回结果结构由 MCP 服务自身定义，`PromptGraphEngine` 无法提前知道。
因此 MCP 工具的 `output_edge_map` 中关联的出边 `schema_id` 作为**运行时校验**：
- 若 MCP 工具返回结果符合出边 schema → 正常流入下游
- 若不符合 → 标记节点失败，错误信息包含 schema 校验详情

### 4.4 MCP Resources 与 Prompts

除工具调用外，MCP 还支持 Resources（资源读取）和 Prompts（提示词模板）：

**Resources（资源注入）：**

```
PromptNodeDef 新增字段：
  mcp_resources   McpResourceRef 列表

McpResourceRef {
  server_id    引用的 MCP 服务
  uri          资源 URI（如 "file:///path/to/doc.txt"，支持 %变量% 替换）
  inject_as    注入到 prompt 的方式："system_context"（追加到 system_prompt）
               | "user_context"（追加到 user_prompt_tpl）
               | "context_field"（写入 context["字段名"]）
  field_name   仅 inject_as="context_field" 时使用
}
```

**Prompts（MCP 提示词模板）：**

MCP Prompts 可以替代节点的 `system_prompt` / `user_prompt_tpl`，由 MCP 服务动态生成提示词：

```
PromptNodeDef 新增字段：
  mcp_prompt   McpPromptRef（null = 使用节点内联提示词）

McpPromptRef {
  server_id    引用的 MCP 服务
  prompt_name  MCP 服务中的提示词名称
  arguments    提示词参数 Map（支持 %变量% 替换）
}
```

当 `mcp_prompt` 非 null 时，`PromptGraphEngine` 调用 MCP 的 `prompts/get`，
将返回的 messages 直接作为 LLM 请求的 messages，忽略节点的 `system_prompt` / `user_prompt_tpl`。

### 4.5 Function Call 执行流程（含 MCP）

```
LLM_CALL 节点执行流程：

  1. 准备工具列表：
     a. 收集内联 ToolDef
     b. 连接 mcp_servers，调用 tools/list，按 allowed_tools 过滤
     c. 合并为统一 tools 列表注入请求

  2. 准备 messages：
     a. 若 mcp_prompt 非 null：调用 MCP prompts/get，使用返回的 messages
     b. 否则：渲染 system_prompt + user_prompt_tpl
     c. 注入 mcp_resources（按 inject_as 方式）

  3. 若出边有 schema_id 且 tool_choice != "auto"：注入 response_format

  4. 调用 LlmSseClient.streamChat()

  5. 解析响应：
     a. 若响应为普通文本 → 按出边 schema 校验，写入 context
     b. 若响应为 tool_calls：
        i.  区分内联工具 vs MCP 工具
        ii. 内联工具：按 ToolDef.handler 分发处理
            MCP 工具：转发 tools/call 到对应 MCP 服务，等待结果
        iii.按 output_edge_map / output_edge_id 路由结果到出边
        iv. 将工具结果追加到 messages（role: "tool"）
        v.  再次调用 LLM（循环，最多 max_tool_rounds 次）

  6. 最后一轮：去掉 tools，强制 response_format 输出，汇总结果

  7. 最终输出按出边 schema 校验后写入 PromptNodeRun.output_json
```

### 4.6 MCP 能力校验

`LlmModelConfig.capabilities` 已有 `MCP` 枚举。`PromptGraphEngine` 在创建 `PromptGraphRun` 时，
若节点配置了 `mcp_servers`，检查所选模型是否具备 `MCP` 能力（即模型支持 tool use），
不具备则拒绝启动并提示用户切换模型。

---

## 5. 用户手动更正兼容

### 5.1 问题分析

```
[转录] → [章节分割 LLM] → [剪辑点提取 LLM] → [字幕生成 LLM]
               ↑
          用户发现章节分割有误，手动修改了 2 个章节的时间戳
```

子问题：
1. **存储**：修正后的数据存在哪里？原始 LLM 输出是否保留？
2. **感知**：下游如何知道它的输入已被人工修正？
3. **重跑策略**：修正后是否需要重跑下游？由谁决定？
4. **Schema 兼容**：人工修正的数据是否仍符合出边 schema？

### 5.2 核心设计：Override 层

`PromptNodeRun` 采用"原始输出 + 覆盖层"双层结构：

```
PromptNodeRun {
  id                    唯一标识
  prompt_graph_run_id   所属 PromptGraphRun.id
  node_def_id           对应 PromptNodeDef.id
  status                pending | running | completed | failed | overridden | stale | skipped
  input_snapshot_json   执行时的完整输入快照（渲染后的 prompt + context 切片）
  output_json           LLM 原始输出（schema 校验通过后写入，永不修改）
  override_json         人工覆盖数据（null = 未覆盖；非 null = 以此为准）
  override_by           覆盖操作者（"user" | 系统迁移标识）
  override_at           覆盖时间（Unix 秒）
  override_note         用户填写的修正说明（可选）
  downstream_policy     覆盖后的下游策略（见 §5.3）
  tokens_input          实际输入 token 数
  tokens_output         实际输出 token 数
  cost_usd              实际成本
  created_at
  completed_at
}
```

**有效输出的读取规则（单一真相来源）：**

```kotlin
fun PromptNodeRun.effectiveOutput(): JsonObject? =
    override_json ?: output_json
```

所有下游节点、context 构建、前端展示，统一调用 `effectiveOutput()`。

**人工覆盖必须通过出边 schema 校验**，否则拒绝保存。

### 5.3 下游重跑策略（downstream_policy）

| 策略 | 含义 | 适用场景 |
|------|------|---------|
| `KEEP` | 保留下游节点的现有输出，不重跑 | 只修正当前节点的展示，不影响后续 |
| `INVALIDATE` | 将直接下游节点标记为 `stale`（过时），等待用户手动触发重跑 | **默认策略** |
| `RERUN` | 立即重跑所有直接下游节点 | 用户明确希望修正传播 |
| `RERUN_ALL` | 重跑整个下游 DAG | 修正影响面大，需要全量更新 |

`stale` 状态的节点在前端显示警告图标，提供"重跑此节点"按钮。

### 5.4 批量数据场景：Few-shot 覆盖注入

AI 数据清洗场景中，同一 `PromptGraphDef` 对大量数据条目各自运行一个 `PromptGraphRun`。
用户修正某条数据后，可将修正"作为示例"影响后续批次的 LLM 调用。

```
PromptNodeDef 新增字段：
  use_overrides_as_fewshot   boolean（默认 false）
  fewshot_max_count          最多注入几条历史覆盖示例（默认 3）
  fewshot_selection          "latest" | "random" | "similar"（Phase 2+）
```

当 `use_overrides_as_fewshot=true` 时，`PromptGraphEngine` 在构建 prompt 时，
自动从历史 `PromptNodeRun` 中查找同一节点的 `override_json` 记录，
将 `(input_snapshot, override_json)` 对注入为 few-shot 示例追加到 system_prompt 末尾。

**人工修正即是最好的训练数据，无需额外标注流程。**

---

## 6. 版本管理与 Fork

### 6.1 版本变迁的三个维度

| 维度 | 触发方 | 影响范围 |
|------|--------|---------|
| **Graph 结构变更**（节点增删、边增删） | 系统迭代 / 用户编辑 | 影响执行路径，历史 PromptGraphRun 与新结构可能不对应 |
| **提示词内容变更**（system_prompt / user_prompt_tpl） | 系统迭代 / 用户编辑 | 不影响结构，影响输出质量，历史数据仍有效 |
| **Schema 变更**（SchemaEntry 增删改） | 系统迭代 | 影响类型契约，需要迁移脚本（见 §3.4） |

### 6.2 版本快照策略

`PromptGraphRun` 在创建时记录版本快照，与 `PromptGraphDef` 解耦：

```
PromptGraphRun {
  id
  prompt_graph_def_id     关联的 PromptGraphDef.id
  graph_def_ver           创建时的 PromptGraphDef.version（整数快照）
  schema_version          创建时的 PromptGraphDef.schema_version（语义版本快照）
  workflow_run_id         所属 WorkflowRun.id（若由 WorkflowEngine 触发）
  workflow_node_run_id    所属 WorkflowNodeRun.id（若由 WorkflowEngine 触发）
  material_id             关联素材（可选，独立运行时使用）
  status                  pending | running | completed | failed | paused
  context_json            各节点 effectiveOutput() 的累积
  created_at
  completed_at
}
```

历史 `PromptGraphRun` 永远基于创建时的 `graph_def_ver` 执行，不受后续版本升级影响。
前端展示历史运行时，标注"基于 v{graph_def_ver}，当前最新版本为 v{latest_ver}"。

### 6.3 系统内置 Graph 与用户 Fork

复用 `WorkflowDefinition` 的 `source_type` 机制（`system | user | system_fork`）。

**与 WorkflowDefinition Fork 的关键区别：**

WorkflowDefinition 的 Fork 有"骨干节点锁定"概念（下载、转码等不可改）。
PromptGraphDef 的 Fork **全部节点可编辑**——提示词图本身就是用户定制的核心，没有不可改的骨干。

Fork 流程：深拷贝 PromptGraphDef（`source_type=system_fork`，`parent_def_id=源ID`，`version=1`），
schema_registry 完整复制，记录 `parent_def_ver_at_fork` 和 `parent_schema_ver_at_fork`。

### 6.4 系统版本升级时的 Fork 同步

当系统发布新版本，内置 `PromptGraphDef` 升级后，用户的 `system_fork` 副本不会自动更新。

```
PromptGraphDef（system_fork）新增字段：
  parent_def_ver_at_fork        Fork 时父 Graph 的 version
  parent_schema_ver_at_fork     Fork 时父 Graph 的 schema_version
```

前端在用户的 Fork 列表中展示"父版本已更新"提示，并提供：
- **查看 diff**：对比父 Graph 当前版本与 Fork 时版本的节点/schema 差异
- **选择性合并**：将父 Graph 的某些节点变更合并到自己的 Fork
- **重新 Fork**：基于最新父版本重新 Fork（会丢失用户定制，需二次确认）

---

## 7. 其他难点补充

### 7.1 提示词注入防御

`system_prompt` 和 `user_prompt_tpl` 支持 `%变量%` 替换，但用户数据可能包含：
- **提示词注入**：恶意指令覆盖系统提示词
- **格式破坏**：数据中包含 `%` 字符被误识别为占位符

**防御措施：**

1. 变量替换时对注入的用户数据转义（`%` → `%%`，渲染后还原）
2. 用户数据只能出现在 `user_prompt_tpl` 的特定占位符中，不能出现在 `system_prompt` 中
3. `PromptNodeDef` 新增 `sanitize_input: boolean`，启用后对注入内容进行基础清洗

### 7.2 长上下文管理

随着 Graph 中节点增多，`context_json` 会越来越大，直接注入 prompt 会超出模型 context window。

`PromptNodeDef.context_include` 白名单：只注入指定上游节点的输出，默认只注入直接上游节点。

```
context_include: ["transcribe", "chapter_split"]
context_max_chars: 8000
```

### 7.3 并发执行与幂等性

`PromptGraphRun` 中的并行节点（多条出边同时激活）可能并发执行。
`PromptGraphEngine` 内部持有 per-run Mutex（按 `prompt_graph_run_id` 分片），
`INSERT INTO prompt_node_run ... ON CONFLICT DO NOTHING` 保证节点不被重复激活。

### 7.4 调试与可观测性

`PromptNodeRun.input_snapshot_json` 存储节点执行时的完整输入快照：

```json
{
  "rendered_system_prompt": "...",
  "rendered_user_prompt": "...",
  "tools": [...],
  "response_format": {...},
  "context_slice": { "transcribe": { "text": "..." } },
  "model_config": { "model": "gpt-4o", "temperature": 0.3 }
}
```

使得：**复现**（用历史快照精确复现某次 LLM 调用）、**对比**（不同版本 Graph 在相同输入下的输出差异）、**调试**（完整的 prompt → 响应 → schema 校验链路）均可实现。

### 7.5 成本控制

```
PromptNodeDef 新增字段：
  token_budget_input    输入 token 上限（超出则截断上下文）
  token_budget_output   输出 token 上限（max_tokens）

PromptNodeRun 新增字段：
  tokens_input    实际输入 token 数
  tokens_output   实际输出 token 数
  cost_usd        实际成本（根据模型单价计算）
```

---

## 8. 数据库 Schema

```sql
CREATE TABLE prompt_graph_def (
    id                        TEXT PRIMARY KEY,
    name                      TEXT NOT NULL,
    description               TEXT,
    nodes_json                TEXT NOT NULL DEFAULT '[]',
    edges_json                TEXT NOT NULL DEFAULT '[]',  -- PromptEdgeDef 含 schema_id
    schema_registry_json      TEXT NOT NULL DEFAULT '[]',  -- SchemaEntry 列表
    migrations_json           TEXT NOT NULL DEFAULT '[]',  -- SchemaMigration 列表
    version                   INTEGER NOT NULL DEFAULT 1,
    schema_version            TEXT NOT NULL DEFAULT '1.0.0',
    source_type               TEXT NOT NULL DEFAULT 'user',
    parent_def_id             TEXT,
    parent_def_ver_at_fork    INTEGER,
    parent_schema_ver_at_fork TEXT,
    created_at                INTEGER NOT NULL,
    updated_at                INTEGER NOT NULL
);

CREATE INDEX idx_pgd_source ON prompt_graph_def(source_type);

CREATE TABLE prompt_graph_run (
    id                    TEXT PRIMARY KEY,
    prompt_graph_def_id   TEXT NOT NULL,
    graph_def_ver         INTEGER NOT NULL,
    schema_version        TEXT NOT NULL,
    workflow_run_id       TEXT,
    workflow_node_run_id  TEXT,
    material_id           TEXT,
    status                TEXT NOT NULL DEFAULT 'pending',
    context_json          TEXT NOT NULL DEFAULT '{}',
    created_at            INTEGER NOT NULL,
    completed_at          INTEGER
);

CREATE INDEX idx_pgr_def    ON prompt_graph_run(prompt_graph_def_id);
CREATE INDEX idx_pgr_wf_run ON prompt_graph_run(workflow_run_id);
CREATE INDEX idx_pgr_status ON prompt_graph_run(status);

CREATE TABLE prompt_node_run (
    id                    TEXT PRIMARY KEY,
    prompt_graph_run_id   TEXT NOT NULL REFERENCES prompt_graph_run(id) ON DELETE CASCADE,
    node_def_id           TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'pending',
    input_snapshot_json   TEXT,
    output_json           TEXT,
    override_json         TEXT,
    override_by           TEXT,
    override_at           INTEGER,
    override_note         TEXT,
    downstream_policy     TEXT DEFAULT 'INVALIDATE',
    tokens_input          INTEGER,
    tokens_output         INTEGER,
    cost_usd              REAL,
    created_at            INTEGER NOT NULL,
    completed_at          INTEGER,
    UNIQUE (prompt_graph_run_id, node_def_id)
);

CREATE INDEX idx_pnr_run ON prompt_node_run(prompt_graph_run_id);
```

---

## 9. API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/PromptGraphDefListRoute` | 分页查询（可按 source_type 过滤） |
| `GET` | `/PromptGraphDefGetRoute` | 按 id 查询单个设计（含 schema_registry） |
| `POST` | `/PromptGraphDefCreateRoute` | 创建用户 Graph |
| `POST` | `/PromptGraphDefUpdateRoute` | 更新 Graph（version +1，schema_version 自动推导） |
| `POST` | `/PromptGraphDefDeleteRoute` | 删除（system 类型不可删） |
| `POST` | `/PromptGraphDefForkRoute` | Fork 系统 Graph |
| `POST` | `/PromptGraphRunStartRoute` | 独立启动一个 PromptGraphRun |
| `GET` | `/PromptGraphRunGetRoute` | 查询运行详情（含所有节点状态） |
| `POST` | `/PromptGraphNodeOverrideRoute` | 提交人工覆盖（含 downstream_policy） |
| `POST` | `/PromptGraphNodeRerunRoute` | 重跑指定节点（及其下游） |

---

## 10. 与 WorkflowDefinition 的集成

### 10.1 LLM_CALL 节点的两种模式

| 模式 | payload_tpl 字段 | 说明 |
|------|-----------------|------|
| **内联模式**（现有） | `system_prompt`、`user_prompt`、`response_format` | 简单单次 LLM 调用 |
| **Graph 模式**（新增） | `prompt_graph_def_id` | 委托给 PromptGraphEngine，支持多节点、schema 注册表、人工覆盖 |

两种模式共存，内联模式用于简单场景，Graph 模式用于复杂多步 LLM 链。

### 10.2 执行流程集成

```
LlmCallExecutor.execute(task)
  if payload.prompt_graph_def_id != null:
    → PromptGraphEngine.run(def_id, context_input)
    → 等待 PromptGraphRun 完成
    → 将终止节点的 effectiveOutput() 写入 Task.result_json
  else:
    → 直接调用 LlmSseClient.streamChat()（内联模式）
```

### 10.3 context_json 的命名空间

`PromptGraphRun` 完成后，输出写回 `WorkflowRun.context_json`，
以 `WorkflowNodeRun.node_def_id` 为 key，附带 schema 元信息：

```json
{
  "download": { "output_path": "..." },
  "llm_analyze": {
    "_prompt_graph_run_id": "uuid-xxx",
    "_schema_version": "1.2.0",
    "chapters": [...],
    "clip_points": [...]
  }
}
```

---

## 11. 关键设计决策汇总

| 决策 | 选择 | 理由 |
|------|------|------|
| Schema 绑定位置 | **边**（PromptEdgeDef.schema_id） | 语义更准确：边是数据管道，节点是处理器；支持同一节点多出边不同 schema |
| Schema 管理方式 | 内嵌 schema_registry，与 Graph 版本绑定 | 单一文件可导出/导入；避免 schema 与 Graph 版本漂移 |
| 类型安全粒度 | schema_version（语义版本）而非 graph version | Graph 结构变化不一定影响输出契约；消费方只关心 schema |
| 人工覆盖存储 | 双层结构（output_json 永不修改 + override_json 覆盖层） | 保留原始 LLM 输出用于审计和对比；effectiveOutput() 统一读取 |
| 下游重跑策略 | downstream_policy 枚举，用户显式选择 | 自动重跑可能浪费 API 额度；让用户决定影响范围 |
| Few-shot 注入 | 从历史 override_json 自动构建 few-shot | 人工修正即是最好的训练数据；无需额外标注流程 |
| Function Call 最后一轮 | 去掉 tools，强制 response_format 输出 | 确保最终输出符合 schema，避免无限 tool call 循环 |
| ToolDef.output_edge_id | 工具结果关联到出边 | 工具调用结果也受出边 schema 约束，类型安全贯穿 function call |
| Graph 与 Workflow 关系 | PromptGraphDef 独立管理，通过 prompt_graph_def_id 引用 | 提示词图可被多个工作流复用；关注点分离 |
| Fork 可编辑性 | PromptGraphDef Fork 全部节点可编辑（无骨干锁定） | 提示词图本身就是用户定制的核心，与 WorkflowDefinition 的骨干概念不同 |
| 历史数据迁移 | 懒迁移 + JS 迁移脚本注册 | 避免大批量数据迁移阻塞；按需升级 |
| 调试支持 | input_snapshot_json 完整快照 | LLM 调试最需要精确复现；快照是最直接的手段 |
| MCP 工具来源 | McpServerRef 引用 AppConfig 注册的服务，与内联 ToolDef 合并 | MCP 服务可跨节点复用；AppConfig 统一管理连接配置和凭据 |
| MCP 工具路由 | output_edge_map 指定每个工具结果流向哪条出边 | 与内联 ToolDef 的 output_edge_id 对称，类型安全贯穿 MCP 工具调用 |
| MCP Resources | McpResourceRef 按 inject_as 方式注入 prompt 或 context | 资源内容与提示词解耦，URI 支持 %变量% 动态替换 |
| MCP Prompts | McpPromptRef 替代节点内联提示词 | 提示词由 MCP 服务动态生成，支持服务端版本管理 |
| MCP 能力校验 | 创建 PromptGraphRun 时检查模型 MCP capability | 提前失败，避免运行到一半才发现模型不支持 tool use |
