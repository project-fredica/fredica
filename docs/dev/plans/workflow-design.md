---
title: 工作流系统架构设计
---

# 工作流系统架构设计

> **文档状态**：草案（Draft）— 持续更新
> **创建日期**：2026-03-06
> **最近修订**：2026-03-06
> **适用模块**：`shared`、`fredica-webui`
> **关联计划**：`decentralized-task-management.md`

---

## 1. 背景与动机

### 现状局限

当前 Fredica 的任务触发方式是"单任务启动"（`MaterialRunTaskRoute`）：每次手动选择一种操作（下载、转码等），后台创建一个 `WorkflowRun` 容器和一个 `Task`，二者生命周期一一对应。

这种设计对简单操作足够，但面对音视频处理全链路时力不从心：

| 痛点 | 说明 |
|------|------|
| 无法建模依赖链 | 转录必须在提取音轨之后；合并字幕必须在各段转录完成之后，这些先后关系无法提前声明 |
| 无条件分支 | 有字幕时跳过 Whisper 识别、无字幕时走 OCR——这类条件判断无处表达 |
| 无复用性 | 每次素材处理都要人工逐步触发，无法保存"处理方案"供复用 |
| 无中途暂停点 | 只能运行全流程，无法声明"先跑到转码完成，后续步骤等我确认再继续" |
| 无 LLM 集成 | AI 分析（摘要/章节/关键词提取）无法作为流程节点声明 |

### 目标

设计一套**工作流系统**，让音视频处理的全链路可以：

1. 以可视化 DAG 声明处理流程（节点 = 操作，边 = 依赖/条件）
2. 复用工作流设计，针对不同素材实例化多次运行
3. 支持**启动到中间节点**：运行到指定节点后自动停止，等待人工或后续触发
4. 条件分支：根据前序节点的输出结果选择不同的后续路径
5. 状态与 Task 系统无缝对接，由 WorkerEngine 驱动实际执行
6. 集成 LLM（OpenAI 兼容 API + JSON Schema 结构化输出）作为一等公民节点

---

## 2. 核心概念

### 2.1 两层模型：设计 vs 运行实例

```
┌─────────────────────────────────┐
│       WorkflowDefinition        │  ← 工作流"设计"（静态蓝图，可复用）
│  节点定义 + 边定义 + 版本        │
└────────────────┬────────────────┘
                 │ 实例化（针对某个素材 + 目标节点）
                 ▼
┌─────────────────────────────────┐
│         WorkflowRun             │  ← 工作流"运行实例"（动态执行记录）
│  状态 + 上下文数据 + 节点运行记录 │
└────────────────┬────────────────┘
                 │ 每个任务节点创建一个
                 ▼
┌─────────────────────────────────┐
│             Task                │  ← 现有 Task 系统（由 WorkerEngine 调度）
│  status + progress + result     │
└─────────────────────────────────┘
```

**WorkflowDefinition（工作流设计）** 是静态的、可复用的 DAG 蓝图，由用户在前端编辑器中创建。一份设计可以对不同素材反复实例化，不存储任何运行时状态。

**WorkflowRun（工作流运行实例）** 是将某份 WorkflowDefinition 应用于某个素材的一次实际执行记录。它持有动态状态：哪些节点已完成、当前在运行哪个节点、运行到哪个目标节点为止。

### 2.2 WorkflowDefinition（工作流设计）

```txt
WorkflowDefinition {
  id              唯一标识（UUID）
  name            人类可读名称，如"Bilibili 完整处理流程"
  description     描述文字
  nodes           NodeDef 列表（见 §2.3）
  edges           EdgeDef 列表（见 §2.4）
  version         版本号（整数，每次编辑保存后 +1）
  source_type     来源类型：system | user | system_fork（见 §4.5）
  parent_def_id   若 source_type=system_fork，记录源系统工作流的 ID；其他情况为 null
  created_at      创建时间（Unix 秒）
  updated_at      最近编辑时间（Unix 秒）
}
```

WorkflowDefinition 存为 JSON，可导出/导入，便于分享"处理方案"。

### 2.3 节点定义（NodeDef）

每个节点代表一个处理步骤，分为以下几种类型：

| 类型 | 说明 |
|------|------|
| `TASK` | 任务节点，对应一种 TaskExecutor（如 DOWNLOAD_BILIBILI_VIDEO、TRANSCODE_MP4、**LLM_CALL**） |
| `CONDITION` | 条件节点，根据上下文数据的 JS 表达式求值，选择激活哪条出边 |
| `MERGE` | 合并节点，等待所有入边全部到达后才激活（用于并行分支汇聚） |
| `SUB_WORKFLOW` | 子工作流节点，引用另一个 WorkflowDefinition 在此处内联展开（见 §4.5） |

```text
NodeDef {
  id              节点在本 WorkflowDefinition 内的唯一标识，如 "download"、"transcode"
  type            NodeType: TASK | CONDITION | MERGE | SUB_WORKFLOW
  label           前端展示的节点名称
  task_type       仅 TASK 节点：对应的 TaskExecutor 类型，如 "TRANSCODE_MP4"、"LLM_CALL"
  payload_tpl     仅 TASK 节点：任务 payload 的 JSON 模板，支持变量替换（见下方命名空间说明）
  condition_expr  仅 CONDITION 节点：JS 表达式字符串，返回 string（匹配 condition_key）或 boolean
  sub_def_id      仅 SUB_WORKFLOW 节点：引用的子工作流 WorkflowDefinition.id
  input_mapping   仅 SUB_WORKFLOW 节点：JSON 对象，将外部 context 字段映射到子工作流入参
                  示例：{"transcribe_text": "%context.transcribe.text%"}
  output_mapping  仅 SUB_WORKFLOW 节点：JSON 对象，将子工作流输出映射回外部 context
                  示例：{"summary": "%sub.analyze_content.summary%"}
  max_retries     此节点对应 Task 的最大重试次数（默认 0，不重试；见 §5.2）
  locked          仅在 source_type=system_fork 的副本中有效：true 时该节点属于骨干，不可编辑
  position        前端画布坐标 {x, y}，仅用于渲染，不影响执行逻辑
}
```

**payload_tpl 变量命名空间：**

| 命名空间 | 示例 | 说明 |
|----------|------|------|
| `material.*` | `%material.source_id%`、`%material.title%` | 关联素材字段 |
| `context.*` | `%context.download.output_path%` | 前序节点的 result_json 输出，按节点 id 索引 |
| `paths.*` | `%paths.media_dir%` | 应用目录路径（`Paths` 工具类） |
| `config.*` | `%config.ffmpeg_path%`、`%config.llm_base_url%` | AppConfig 配置项 |
| `secrets.*` | `%secrets.openai_api_key%` | 凭据（Phase 1.5 从 AppConfig 读取；Phase 2+ 加密存储） |

**payload_tpl 示例（TRANSCODE_MP4）：**

```json
{
  "bvid": "material.source_id",
  "output_path": "paths.media_dir/video.mp4",
  "hw_accel": "auto"
}
```

实例化时，WorkflowEngine 将 `%...%` 替换为运行时上下文中的实际值，生成最终的 `Task.payload`。

### 2.4 边定义（EdgeDef）

```
EdgeDef {
  id              边的唯一标识
  source_node_id  出发节点的 NodeDef.id
  target_node_id  目标节点的 NodeDef.id
  condition_key   仅用于 CONDITION 节点的出边：标识此边对应哪个求值分支
                  （CONDITION 节点的每条出边各有一个 condition_key）
}
```

普通 TASK 节点可以有多条出边（并行分支），所有出边同时激活。
CONDITION 节点的出边只有满足条件的那条（或几条）才被激活。

---

## 3. 运行时：WorkflowRun

### 3.1 过渡期模型与完整模型

**当前（Phase 1.5 前）**：`WorkflowRun` 是轻量过渡模型，已在代码库中实现，schema 如下：

```sql
-- 当前 workflow_run 表实际 schema（过渡期）
CREATE TABLE IF NOT EXISTS workflow_run (
    id            TEXT PRIMARY KEY,
    material_id   TEXT NOT NULL,
    template      TEXT NOT NULL,    -- 工作流模板标识字符串，如 "manual_download_bilibili_video"
    status        TEXT NOT NULL DEFAULT 'pending',
    total_tasks   INTEGER NOT NULL DEFAULT 0,
    done_tasks    INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    completed_at  INTEGER
);
```

template 字段是过渡期的标识方式（字符串 key），Phase 1.5 起替换为指向 `workflow_definition.id` 的外键，并通过表重建追加其余字段。

**Phase 1.5 完整 WorkflowRun** 新增以下字段：

```sql
workflow_def_id   TEXT NOT NULL,        -- 关联的 WorkflowDefinition.id
workflow_def_ver  INTEGER NOT NULL,     -- 实例化时的版本号快照（与 definition 解耦）
target_node_id    TEXT,                 -- 运行到此节点后停止（null = 完整运行）
start_node_id     TEXT,                 -- 从此节点开始（null = 从头开始）
context_json      TEXT NOT NULL DEFAULT '{}'  -- 各节点执行后的 result 累积
```

历史数据迁移：旧记录填充 `workflow_def_id = "legacy"`（预置占位值），`context_json = '{}'`。

### 3.2 WorkflowRun（完整运行实例，Phase 1.5）

```
WorkflowRun {
  id                唯一标识（UUID）
  workflow_def_id   关联的 WorkflowDefinition.id
  workflow_def_ver  实例化时的 WorkflowDefinition.version（版本快照，版本升级不影响已有运行）
  material_id       关联素材的 ID
  status            运行状态（见 §3.5）
  target_node_id    目标节点：运行到此节点完成后停止，null 表示运行到最后
  start_node_id     起始节点：从此节点开始运行，null 表示从头开始
  context_json      JSON 对象，各节点执行后的输出结果累积在此（见 §3.4）
  total_nodes       应执行的节点总数（实例化时计算，受 target_node_id 影响，见 §3.5）
  done_nodes        已完成的节点数（由 WorkflowEngine.recalculate 维护）
  created_at        创建时间（Unix 秒）
  completed_at      进入终态的时间（Unix 秒），未结束时为 null
}
```

### 3.3 WorkflowNodeRun（节点运行记录）

每个被激活的节点都有一条对应的运行记录：

```
WorkflowNodeRun {
  id                唯一标识（UUID）
  workflow_run_id   所属 WorkflowRun.id
  node_def_id       对应 NodeDef.id（如 "download"、"transcode"）
  task_id           关联的 Task.id（仅 TASK 节点有值；CONDITION/MERGE 节点为 null）
  status            节点状态：pending | running | completed | failed | skipped | cancelled
  result_json       节点输出的 JSON 结果（成功后写入，供后续节点的 payload_tpl 引用）
  created_at        节点被激活的时间（Unix 秒）
  completed_at      节点进入终态的时间（Unix 秒）
}
```

**节点状态说明：**

| 状态 | 含义 |
|------|------|
| `pending` | 节点已被激活，等待 Task 被 WorkerEngine 认领 |
| `running` | Task 正在执行（Task.status = running） |
| `completed` | Task 成功完成 |
| `failed` | Task 失败且重试耗尽 |
| `skipped` | 条件分支中未被选中的节点，直接跳过 |
| `cancelled` | WorkflowRun 被用户取消 |

**重试与 WorkflowNodeRun 的关系：**

WorkerEngine 的重试机制在 Task 层面运作，对 WorkflowNodeRun 透明：

- Task 失败且 `retry_count < max_retries` → Task 回到 `pending`，WorkflowNodeRun 保持 `running`
- Task 最终成功 → WorkflowNodeRun 更新为 `completed`，result 写入 context_json
- Task 重试耗尽仍失败 → WorkflowNodeRun 更新为 `failed`

`max_retries` 由 `NodeDef.max_retries` 在创建 Task 时赋值（默认 0，不重试）。

### 3.4 执行上下文（Context）

`WorkflowRun.context_json` 是一个可累积的 JSON 对象，结构为：

```json
{
  "节点id": {
    "output_path": "/path/to/video.mp4",
    "duration_sec": 3600,
    "...其他输出字段": "..."
  },
  "另一个节点id": {  }
}
```

每个节点完成后，其 `result_json` 会被合并到 `context_json["节点id"]` 中。
后续节点的 `payload_tpl` 通过 `%context.节点id.字段名%` 引用前序结果。

### 3.5 WorkflowRun 状态机

```
pending
  │
  ├─ WorkflowEngine.start() 激活首批节点
  ▼
running
  │
  ├─ 所有激活节点均 completed   → completed
  ├─ 任意节点 failed（重试耗尽）→ failed
  └─ 用户调用 cancel()          → cancelled
```

WorkflowRun 的状态由 `WorkflowEngine.recalculate()` 在每次节点状态变更后自动推导，
基于 `workflow_node_run` 的聚合状态（与现有 `WorkflowRunDb.recalculate()` 类似，但基于 `workflow_node_run` 表而非 `task` 表）。

**`total_nodes` 计算规则：** 当设置 `target_node_id` 时，`total_nodes` 仅计入从起始节点到目标节点（含）的路径上的节点总数，不包含目标节点之后的后继节点。CONDITION 节点和 MERGE 节点也计入（它们的 `WorkflowNodeRun` 在激活后立即求值并置为 `completed`/`skipped`）。

---

## 4. 关键特性

### 4.1 启动中间节点（目标节点）

**场景：** 用户只需要下载和转码，不需要后续的转录和 AI 分析。

**实现方式：**

1. 实例化 WorkflowRun 时，设置 `target_node_id = "transcode"`。
2. WorkflowEngine 在每次节点完成后检查：该节点是否是 `target_node_id`？
   - 是 → 将 WorkflowRun 标记为 `completed`，**不再调度任何后继节点**。
   - 否 → 继续调度满足依赖的下一批节点。

**效果：** 工作流"执行到指定节点后停止"，无需删除或修改 WorkflowDefinition。

**进阶用法（从中间节点继续）：** 若之后用户想继续执行，只需创建一个新的 WorkflowRun，将 `start_node_id` 设置为上次停止节点的后继节点，沿用已有的 `context_json`。

```
WorkflowRun（第一次）: target_node_id = "transcode"
  → 完成后停止

WorkflowRun（第二次）: start_node_id = "extract_audio"，继承 context_json
  → 从提取音轨继续往后跑
```

### 4.2 条件分支（JS 引擎求值）

CONDITION 节点在完成后，WorkflowEngine 对其 `condition_expr` 求值，根据结果激活对应出边的目标节点。

**求值引擎：** JVM 侧集成 JS 脚本引擎（GraalVM JavaScript；无 GraalVM 时降级为 Rhino），在沙箱环境中执行表达式，无需引入外部 DSL。

**上下文绑定：** 表达式执行时注入两个全局变量：

- `ctx`：等同于 `WorkflowRun.context_json`（各节点的输出结果）
- `material`：关联素材的字段对象

**表达式语法（标准 JS）：**

```javascript
// 返回 string → 激活 condition_key == 该字符串 的出边
ctx.detect_language.lang_code === "zh" ? "zh_path" : "other_path"

// 返回 boolean → true 激活 condition_key="true"，false 激活 condition_key="false"
ctx.download.has_subtitle === true

// 支持标准 JS 运算符
ctx.transcribe.word_count > 100 && ctx.detect_language.lang_code === "zh"
```

**求值结果处理：**

| 返回值类型 | 行为 |
|-----------|------|
| `string` | 激活 `condition_key == 该字符串` 的出边 |
| `boolean` | 激活 `condition_key == "true"` 或 `"false"` 的出边 |
| 抛出异常 | WorkflowNodeRun 置为 `failed`，WorkflowRun 转为 `failed` |

**示例：**

```
下载节点（TASK: download）
    ↓
语言检测节点（TASK: detect_language）
    ↓
[CONDITION: ctx.detect_language.lang_code === "zh" ? "zh_path" : "other_path"]
    ├─ condition_key="zh_path"    → 中文字幕合并节点
    └─ condition_key="other_path" → Whisper 转录节点
```

### 4.3 并行分支与 MERGE 节点

当一个 TASK 节点有多条出边指向不同节点时，所有出边同时激活（并行执行）。
MERGE 节点等待所有入边对应的节点都完成后，才继续激活其出边。

**示例（视频处理并行化）：**

```
下载（download）
    ├──→ 转码（transcode）
    └──→ 提取音轨（extract_audio）
               ↓
           转录（transcribe）
    [MERGE: transcode + transcribe 都完成]
               ↓
           合并字幕（burn_subtitle）
```

**MERGE 节点的并发安全：**

多个并行 Task 可能几乎同时完成，并发调用 `WorkflowEngine.onTaskCompleted()`，存在重复激活 MERGE 下游节点的风险。解决方案如下：

1. `WorkflowEngine` 内部持有一把 `Mutex`，`scheduleNext()` 在 Mutex 保护下串行执行。
2. 激活新节点（`INSERT INTO workflow_node_run`）使用 `ON CONFLICT(workflow_run_id, node_def_id) DO NOTHING` 保证幂等性。

```kotlin
// WorkflowEngine 内部
private val mutex = Mutex()

suspend fun onTaskCompleted(task: Task) = mutex.withLock {
    // 1. 更新 WorkflowNodeRun 状态
    // 2. 将 Task.result 写入 context_json["node_def_id"]
    // 3. 检查 MERGE 节点的所有入边是否都已 completed
    // 4. scheduleNext()（激活满足依赖的下一批节点）
    // 5. recalculate()（更新 WorkflowRun.status / done_nodes）
}
```

### 4.4 LLM_CALL 节点（AI 分析）

`LLM_CALL` 是一种特殊的 TASK 节点，对应 Kotlin/JVM 实现的 `LlmCallExecutor`，用于在音视频处理流程后期调用 OpenAI 兼容 API 执行 AI 分析。支持 JSON Schema 结构化输出（OpenAI `response_format: json_schema`），确保 LLM 响应格式可预测、可机器读取。

**在流水线中的位置：**

```
[TRANSCRIBE_CHUNK]
       ↓
   [MERGE]
       ↓
[LLM_CALL: analyze_content]   ← 分析转录文本，输出 summary/topics/chapters
       ↓
[LLM_CALL: generate_srt]      ← 基于分析结果生成字幕时间轴
```

**实现要点（完整设计见 `docs/dev/plans/llm-call-design.md`）：**

- 执行侧：**Kotlin/JVM**（`jvmMain`），未来 Android 可直接复用 `commonMain` 的 SSE 工具层
- **不依赖任何 LLM SDK**：使用 Ktor HttpClient + 自实现 SSE 客户端（`SseLineParser`，逐行解析 `data: {...}`），原始 JSON 透传，不受强类型绑定限制
- 支持所有 OpenAI 兼容接口（OpenAI / DeepSeek / Moonshot / OpenRouter / Ollama）
- 支持**多模型配置**（`LlmModelConfig`），每个模型携带能力标签（`VISION` / `JSON_SCHEMA` / `MCP` / `FUNCTION_CALLING` 等），WorkflowEngine 在渲染节点时自动校验所需能力
- `payload_tpl` 中通过 `model_id` 引用已配置模型，`base_url` / `api_key` 由 WorkflowEngine 注入，不硬编码在工作流定义中
- `json_schema` 字段原样透传给 API；完成后校验 `required` 字段存在性

### 4.5 系统工作流与用户工作流

**背景：** Fredica 内置若干系统工作流（如"Bilibili 完整处理流程"），其骨干节点（下载、转码、转录等）不允许用户随意改动，但 AI 分析部分（LLM 决策树）需要支持个性化定制。

#### 工作流来源分类

| `source_type` | 含义 | 可编辑性 |
|---------------|------|----------|
| `system` | 系统内置工作流（随应用版本发布）| 完全只读，不可修改、不可删除 |
| `user` | 用户自建工作流 | 完全可编辑 |
| `system_fork` | 从系统工作流派生的用户副本 | 骨干节点锁定（`locked=true`）；插槽节点（`SUB_WORKFLOW`）可替换 |

#### 核心设计：插槽节点（SUB_WORKFLOW）

系统工作流将**可定制区域**建模为 `SUB_WORKFLOW` 插槽节点，指向一个默认的子工作流实现（`source_type=system`）。插槽节点是 `system_fork` 副本中**唯一允许修改**的节点——用户可以替换 `sub_def_id`，将其指向自己的子工作流。

这样实现了"骨干不变、中间可换"的设计目标：

```text
系统工作流（source_type=system，完全只读）：

[DOWNLOAD]      骨干（locked）
    ↓
[TRANSCODE]     骨干（locked）
    ↓
[EXTRACT_AUDIO] 骨干（locked）
    ↓
[TRANSCRIBE]    骨干（locked）
    ↓
[SUB_WORKFLOW: "ai_analysis"]   ← 插槽，默认指向系统子工作流 "sys_llm_analysis"
    sub_def_id    = "sys_llm_analysis"
    input_mapping = {"text": "%context.transcribe.text%"}
    output_mapping = {"summary": "%sub.analyze.summary%",
                      "chapters": "%sub.analyze.chapters%"}
    ↓
[FINALIZE]      骨干（locked）
```

**默认子工作流 `sys_llm_analysis`（source_type=system）：**

```
[LLM_CALL: analyze]
    ↓
[CONDITION: ctx.analyze.lang_code === "zh" ? "zh" : "en"]
    ├─ "zh" → [LLM_CALL: generate_zh_srt]
    └─ "en" → [LLM_CALL: generate_en_srt]
```

#### 派生（Fork）流程

用户在工作流列表页点击「自定义」，触发 `POST /WorkflowDefForkRoute`，服务端：

1. 读取源系统工作流的所有节点和边
2. 深拷贝为新 `WorkflowDefinition`（`source_type=system_fork`，`parent_def_id=源ID`，`version=1`）
3. 所有非 `SUB_WORKFLOW` 节点标记 `locked=true`（骨干，不可编辑）
4. 所有 `SUB_WORKFLOW` 节点标记 `locked=false`（插槽，可替换 `sub_def_id`）
5. 返回新副本的 `id`，前端跳转到编辑器

用户随后可以：

- 对插槽节点选择不同的 `sub_def_id`（下拉列出所有 `source_type=user` 的子工作流）
- 点击「新建子工作流」创建 `source_type=user` 的工作流并绑定到插槽
- 修改插槽的 `input_mapping` / `output_mapping`（调整上下文字段映射）
- 保存后以此副本实例化 WorkflowRun

#### 更新校验规则（`POST /WorkflowDefUpdateRoute`）

服务端在保存 `system_fork` 副本时执行以下校验，任一失败则拒绝保存：

| 校验项 | 规则 |
|--------|------|
| 骨干节点不可修改 | `locked=true` 的节点，除 `position`（画布坐标）外所有字段不得变更 |
| 骨干节点不可删除 | 请求中不得缺少任何 `locked=true` 的节点 |
| 骨干节点连线不可改变 | `locked` 节点之间的边不得增删 |
| 插槽节点不可删除 | `SUB_WORKFLOW` 节点本身不得删除（只能替换 `sub_def_id`） |
| `sub_def_id` 合法性 | 替换后的 `sub_def_id` 必须存在且 `source_type` 为 `user` 或 `system` |
| `system` 类型不可更新 | `source_type=system` 的工作流拒绝任何更新请求 |

#### SUB_WORKFLOW 的执行方式（内联展开）

WorkflowEngine 在创建 WorkflowRun 时**内联展开**所有 `SUB_WORKFLOW` 节点：将子工作流的节点以 `"{slot_id}.{sub_node_id}"` 为前缀注入父工作流，得到一张无 `SUB_WORKFLOW` 节点的纯平 DAG，再按常规逻辑执行。

```
展开前：[TRANSCRIBE] → [SUB_WORKFLOW: ai_analysis] → [FINALIZE]

展开后：[TRANSCRIBE] → [ai_analysis.analyze]
                              ↓
                       [ai_analysis.condition]
                          ↙           ↘
              [ai_analysis.zh_srt]  [ai_analysis.en_srt]
                          ↘           ↙
                        [FINALIZE]
```

`input_mapping` / `output_mapping` 在展开时重命名 context 键名，确保子工作流内部的 `%context.*%` 引用与父工作流命名空间无冲突。

**展开深度限制：** 子工作流内部不允许再嵌套 `SUB_WORKFLOW` 节点（最多一层嵌套），WorkflowEngine 在展开时检测到嵌套则拒绝创建 WorkflowRun 并报错。

#### 编辑器中的行为差异

| 场景 | 骨干节点（`locked=true`） | 插槽节点（`locked=false` SUB_WORKFLOW） | 用户工作流节点 |
|------|--------------------------|----------------------------------------|--------------|
| 属性面板 | 全部只读 | 可修改 `sub_def_id`、`input_mapping`、`output_mapping` | 完全可编辑 |
| 删除按钮 | 隐藏 | 隐藏 | 显示 |
| 连线拖拽 | 禁用 | 禁用 | 启用 |
| 节点标识 | 🔒 锁形图标 | 🔧 可配置图标 | 无特殊标识 |
| 节点背景色 | 灰色（只读感） | 蓝色边框（可配置感） | 默认白色 |

#### 子工作流的独立管理

`source_type=user` 的子工作流是独立的 `WorkflowDefinition` 记录，可以：

- 被多个 `system_fork` 副本的不同插槽复用
- 在「我的工作流」列表中单独查看和编辑
- 删除前检查是否有 `system_fork` 副本正在引用（有则警告，不强制阻止）

---

## 5. 与现有系统的关系

### 5.1 WorkflowEngine 与 WorkerEngine 的集成机制

WorkflowEngine **不替换** WorkerEngine，而是在其上层编排：

```
WorkflowEngine（编排层）
  负责：DAG 推进、payload_tpl 渲染、条件求值（JS 引擎）、节点激活、状态汇总

  ↓ 为每个 TASK 节点创建 Task 记录
    Task.payload   = payload_tpl 渲染结果
    Task.maxRetries = NodeDef.max_retries（默认 0）

WorkerEngine（执行层）
  负责：认领 Task、分发给 Executor、更新状态

  ↓ 任务状态变更后（完成/失败/取消）

WorkflowEngineService.onTaskCompleted(task)
  → 查找对应 WorkflowNodeRun（按 task_id）
  → 将 Task.result 写入 context_json["node_def_id"]
  → 更新 WorkflowNodeRun 状态（completed / failed）
  → recalculate() 推进 WorkflowRun.status / done_nodes
  → scheduleNext() 激活下一批节点（Mutex 保护）
```

**关键设计：Task 与 WorkflowNodeRun 是独立的。** WorkerEngine 不感知 Workflow 概念，只执行 Task；WorkflowEngine 通过同步进程内回调（非消息队列）推进工作流。

**过渡期兼容（Phase 1.5 引入前）：**

当前 `WorkerEngine.afterTaskFinished()` 直接调用 `WorkflowRunService.repo.recalculate()`。Phase 1.5 引入 `WorkflowEngineService` 后，改为：

```kotlin
// Phase 1.5 后的 WorkerEngine.afterTaskFinished(task)
if (WorkflowEngineService.isManaged(task.workflowRunId)) {
    // 该 WorkflowRun 由完整 WorkflowEngine 管理
    WorkflowEngineService.onTaskCompleted(task)
} else {
    // 兼容过渡期轻量 WorkflowRun（无 WorkflowDefinition）
    WorkflowRunService.repo.recalculate(task.workflowRunId)
}
```

### 5.2 与现有 Task 系统的关系

`PipelineInstance → WorkflowRun` 的重命名已在代码库中**完成**：

| 已迁移内容 | 状态 |
|-----------|------|
| `PipelineInstance.kt` 删除，`WorkflowRun.kt` 新建 | ✅ |
| `PipelineDb.kt` 删除，`WorkflowRunDb.kt` 新建 | ✅ |
| `task.pipeline_id` → `task.workflow_run_id` | ✅ |
| `Task.pipelineId` → `Task.workflowRunId`（`@SerialName("workflow_run_id")`） | ✅ |
| `WorkerEngine.afterTaskFinished()` 调用 `WorkflowRunService.repo.recalculate()` | ✅ |

当前 `WorkflowRun` 是轻量过渡模型（§3.1），Phase 1.5 通过表重建（SQLite 不支持 ALTER COLUMN）扩展为完整模型。

**Task 重试策略（重要变更）：**

`Task.maxRetries` 默认值已从 3 改为 **0（不重试）**：

- 音视频处理任务（转码、转录、LLM 调用等）均有副作用（写磁盘、消耗 API 额度），盲目重试可能导致重复产物或重复计费。
- 重试次数由 `NodeDef.max_retries` 在创建 Task 时显式指定，默认 0。
- 例外：无副作用的系统任务（如 `NetworkTestExecutor` 网络测速）可在 Executor 内创建 Task 时将 `maxRetries` 设为 3，不依赖全局默认。

---

## 6. 数据库 Schema

### 6.1 workflow_definition 表

```sql
CREATE TABLE workflow_definition (
    id             TEXT PRIMARY KEY,
    name           TEXT NOT NULL,
    description    TEXT,
    -- nodes: [{id, type, label, task_type, payload_tpl, condition_expr, sub_def_id,
    --          input_mapping, output_mapping, max_retries, locked, position}]
    -- edges: [{id, source_node_id, target_node_id, condition_key}]
    nodes_json     TEXT NOT NULL DEFAULT '[]',
    edges_json     TEXT NOT NULL DEFAULT '[]',
    version        INTEGER NOT NULL DEFAULT 1,
    source_type    TEXT NOT NULL DEFAULT 'user',  -- system | user | system_fork
    parent_def_id  TEXT,                          -- system_fork 时指向源系统工作流 ID
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX idx_workflow_def_source ON workflow_definition(source_type);
```

### 6.2 workflow_run 表（Phase 1.5 完整版）

```sql
CREATE TABLE workflow_run (
    id                  TEXT PRIMARY KEY,
    workflow_def_id     TEXT NOT NULL,
    workflow_def_ver    INTEGER NOT NULL,   -- 快照版本，与 definition 解耦
    material_id         TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending',
    target_node_id      TEXT,               -- 运行到此节点后停止（null = 完整运行）
    start_node_id       TEXT,               -- 从此节点开始（null = 从头开始）
    context_json        TEXT NOT NULL DEFAULT '{}',
    total_nodes         INTEGER NOT NULL DEFAULT 0,
    done_nodes          INTEGER NOT NULL DEFAULT 0,
    created_at          INTEGER NOT NULL,
    completed_at        INTEGER
);

CREATE INDEX idx_workflow_run_material ON workflow_run(material_id);
CREATE INDEX idx_workflow_run_status   ON workflow_run(status);
```

### 6.3 workflow_node_run 表

```sql
CREATE TABLE workflow_node_run (
    id               TEXT PRIMARY KEY,
    workflow_run_id  TEXT NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    node_def_id      TEXT NOT NULL,   -- 对应 WorkflowDefinition 中的 NodeDef.id
    task_id          TEXT,            -- TASK 节点对应的 task.id（CONDITION/MERGE 节点为 null）
    status           TEXT NOT NULL DEFAULT 'pending',
    result_json      TEXT,
    created_at       INTEGER NOT NULL,
    completed_at     INTEGER,
    -- 防止并发重复激活同一节点（scheduleNext 的幂等保证）
    UNIQUE (workflow_run_id, node_def_id)
);

CREATE INDEX idx_node_run_workflow_run ON workflow_node_run(workflow_run_id);
CREATE INDEX idx_node_run_task_id      ON workflow_node_run(task_id);
```

### 6.4 task 表（当前 schema，已完成迁移）

```sql
-- task 表相关字段（当前生效）
workflow_run_id  TEXT NOT NULL,       -- 原 pipeline_id，已重命名完毕
max_retries      INTEGER NOT NULL DEFAULT 0,   -- 已从 3 改为 0（不重试）

-- 建议索引
CREATE INDEX idx_task_workflow_run ON task(workflow_run_id);
CREATE INDEX idx_task_status       ON task(status);
-- 部分索引：仅对 pending 任务建立复合索引，优化 claimNext DAG 查询
CREATE INDEX idx_task_pending_priority ON task(priority DESC, created_at ASC)
    WHERE status = 'pending';
```

---

## 7. API 设计

### 7.1 WorkflowDefinition CRUD

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/WorkflowDefListRoute` | 分页查询工作流设计（可按 source_type 过滤） |
| `GET` | `/WorkflowDefGetRoute` | 按 id 查询单个设计 |
| `POST` | `/WorkflowDefCreateRoute` | 创建用户工作流设计（source_type=user） |
| `POST` | `/WorkflowDefUpdateRoute` | 更新工作流设计（system_fork 只能修改 locked=false 节点，版本号 +1） |
| `POST` | `/WorkflowDefDeleteRoute` | 删除工作流设计（system 类型不可删除；不影响已有运行实例） |
| `POST` | `/WorkflowDefForkRoute` | 从系统工作流派生用户副本（source_type=system_fork） |

### 7.2 WorkflowRun 操作

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/WorkflowRunStartRoute` | 实例化并启动一个 WorkflowRun |
| `GET` | `/WorkflowRunGetRoute` | 查询 WorkflowRun 详情（含节点运行状态） |
| `GET` | `/WorkflowRunListRoute` | 分页查询素材的所有运行历史 |
| `POST` | `/WorkflowRunCancelRoute` | 取消运行中的 WorkflowRun |

**启动参数示例：**

```json5
{
  "workflow_def_id": "uuid-xxx",
  "material_id": "uuid-yyy",
  "target_node_id": "transcode",   // 可选：执行到转码节点后停止
  "start_node_id": null            // null 表示从头开始
}
```

---

## 8. 前端：React Flow

### 8.1 技术选型

选择 **React Flow**（[reactflow.dev](https://reactflow.dev)），而非原生 d3.js：

| 对比项 | React Flow | 原生 d3.js |
|--------|-----------|-----------|
| 接入成本 | 低（React 组件，即装即用） | 高（需自行实现节点/边渲染、交互） |
| 节点拖拽/连线 | 内置 | 手工实现 |
| 与 React 状态的整合 | 原生（组件 props/state） | 需桥接 |
| 布局算法 | 配合 `dagre`/`elkjs` 自动布局 | 需手工调用 |
| 自定义节点渲染 | 支持（React 组件） | 支持（SVG） |

### 8.2 前端页面规划

#### 工作流设计器（WorkflowDefinitionEditor）

- React Flow 画布，可拖拽节点、连线、修改节点配置
- 左侧节点面板：按类型列出可用节点（DOWNLOAD_BILIBILI_VIDEO、TRANSCODE_MP4、LLM_CALL、SUB_WORKFLOW 等）
- 右侧属性面板：
  - 普通 TASK 节点：编辑 label、task_type、payload_tpl、max_retries
  - LLM_CALL 节点：额外提供 JSON Schema 可视化编辑器
  - SUB_WORKFLOW 节点：下拉选择 `sub_def_id`（列出所有 user 工作流），编辑 input/output mapping
  - 锁定节点（system_fork 骨干）：全部只读，显示 🔒 图标
- 顶部工具栏：保存、版本历史、导入/导出 JSON
- 支持自动布局（dagre 自顶向下排列）

**工作流列表页（WorkflowDefListPage）：**

- 分两栏：「系统工作流」（source_type=system）和「我的工作流」（user + system_fork）
- 系统工作流卡片显示「自定义」按钮，点击触发 Fork 操作
- system_fork 卡片显示「基于 xxx 定制」标签与原始系统工作流名称

#### 工作流运行视图（WorkflowRunViewer）

- 只读画布，展示 WorkflowDefinition 的结构
- 节点颜色标识运行状态：
  - 灰色 = pending（未激活）
  - 蓝色 = running（执行中）
  - 绿色 = completed
  - 红色 = failed
  - 黄色 = skipped
- 点击节点查看 Task 详情（进度、日志、错误信息）
- 实时轮询（每 2 秒）或 WebSocket 推送更新

#### 素材处理启动（MaterialRunWorkflowPanel）

- 从素材详情页触发
- 选择 WorkflowDefinition（下拉或卡片列表）
- 可选配置目标节点（"执行到哪一步停止"）
- 确认后调用 `/WorkflowRunStartRoute`，跳转到运行视图

---

## 9. 实现路线图

### Phase 1.5（当前 → Workflow 基础层）

> **前提（已完成）**：Phase 1 核心引擎 + `DownloadBilibiliVideoExecutor`（WebSocket 框架首个实现）+ `TranscodeMp4Executor`（设备检测 + GPU 加速）
>
> **前提（待完成）**：其余 Executor 重写（ExtractAudio / SplitAudio / TranscribeChunk / MergeTranscription / AiAnalyze）

**后端：**

- [ ] 修改 `Task.maxRetries` 默认值为 0（已完成）
- [ ] 建立 `WorkflowDefinition` 数据模型与 `WorkflowDefinitionDb`（含 `source_type`、`parent_def_id`）
- [ ] 建立 `WorkflowNodeRun` 数据模型与 DB（含 UNIQUE 幂等约束）
- [ ] 实现 `WorkflowEngine`（核心调度：scheduleNext / onTaskCompleted / recalculate，内含 Mutex）
- [ ] `WorkflowEngine` 集成 JS 脚本引擎（GraalVM JS 或 Rhino）实现 CONDITION 节点求值
- [ ] SUB_WORKFLOW 节点内联展开（WorkflowRun 创建时预处理）
- [ ] 支持 `target_node_id` 中间节点停止，以及 `total_nodes` 路径计算
- [ ] 修改 `WorkerEngine.afterTaskFinished()` 以支持 `WorkflowEngineService.onTaskCompleted()`
- [ ] payload_tpl 变量渲染引擎（支持 `material.*`、`context.*`、`paths.*`、`config.*`、`secrets.*`）
- [ ] WorkflowDefinition CRUD + Fork API（6 个路由）
- [ ] WorkflowRun API（4 个路由）
- [ ] 内置 `source_type=system` 的"Bilibili 完整处理流程" + "默认 LLM 分析子工作流"
- [ ] `LlmCallExecutor`（Kotlin/JVM）+ `SseLineParser`（commonMain）+ `LlmModelConfig`（commonMain）：自实现 SSE 客户端 + 多模型配置，详见 `llm-call-design.md`
- [ ] `AppConfig` 新增 `llmModelsJson` 字段；`AppConfigDb` 同步；`LlmModelListRoute` / `LlmModelSaveRoute` / `LlmModelDeleteRoute`（3 个路由）

**前端：**

- [ ] 安装 React Flow + dagre
- [ ] 工作流列表页（系统/用户分栏，Fork 按钮）
- [ ] WorkflowRunViewer（只读状态展示，SUB_WORKFLOW 展开分组，轮询刷新）
- [ ] MaterialRunWorkflowPanel（从素材页启动工作流，选系统或自定义副本）
- [ ] WorkflowDefinitionEditor（拖拽编辑器，支持 locked 节点只读，Phase 1.5 末期）

### Phase 2+

- MERGE 节点支持（并行分支汇聚）
- 复杂条件表达式（GraalVM JS 完整支持，可访问辅助函数库）
- WorkflowRun 从中间节点继续（`start_node_id`）
- 工作流版本对比与历史回溯
- `MaterialRunTaskRoute` 完整迁移到 WorkflowRun（废弃轻量单任务模式）
- Secret Store 加密存储（替代 AppConfig 明文存储 API Key）

---

## 10. 关键设计决策汇总

| 决策 | 选择 | 理由 |
|------|------|------|
| 前端图形库 | React Flow | 专为 React 的 DAG 编辑器，接入成本低，内置交互 |
| 条件表达式求值 | JVM 侧 JS 引擎（GraalVM JS / Rhino） | 语法熟悉（JS），无需自研 DSL，可逐步扩展至完整 JS 功能 |
| 节点 payload 模板 | `%命名空间.字段%` 替换 | 简洁直观，支持多命名空间（material/context/config/secrets） |
| WorkflowDefinition 存储 | 整体 JSON 存入 nodes_json/edges_json | 避免过多表关联，Schema 灵活，便于版本快照 |
| 与 Task 的关系 | WorkflowEngine 在上层编排，WorkerEngine 不变 | 最小改动现有系统，关注点分离 |
| 中间节点停止 | `target_node_id` 字段，完成后不调度后继 | 声明式，不需要修改 WorkflowDefinition |
| MERGE 并发安全 | Kotlin Mutex + DB UNIQUE 约束幂等 | 简单可靠，SQLite 单文件无需分布式锁 |
| WorkflowEngine 通知机制 | 同步进程内回调（非消息队列） | Phase 1 单节点模式足够；Phase 3 分布式时再引入消息总线 |
| Task 重试默认值 | `max_retries = 0`（不重试） | 音视频任务有副作用，应由 NodeDef.max_retries 显式声明 |
| LLM_CALL 执行侧 | Kotlin/JVM（`jvmMain`），自实现 SSE 客户端 | Android 可复用 `commonMain` SSE 工具层；不受强类型 SDK 约束，原始 JSON 透传更灵活 |
| 多模型配置 | `LlmModelConfig` + 能力标签（`LlmCapability` 枚举） | 不同模型能力差异大（vision/mcp/json_schema），需在调度时校验；详见 `llm-call-design.md` |
| LLM API Key 存储 | Phase 1.5 从 AppConfig 读取；Phase 2+ 加密 Secret Store | 本地单用户场景先简化，再逐步加固 |
| PipelineInstance 迁移 | 已完成 | `WorkflowRun` 完全替代，无遗留代码 |
