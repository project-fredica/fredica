---
title: Weben 模块概览
order: 300
---

# Weben 模块概览

Weben（**W**ord **E**mbedded **B**rain **E**xpansion **N**etwork）是 Fredica 的知识网络模块，负责从视频/文章等素材中提取概念，构建个人知识图谱。

---

## 模块职责

| 层 | 位置 | 职责 |
|----|------|------|
| 后端数据库 | `shared/commonMain/db/weben/` | SQLite 数据模型 + JDBC 实现 |
| 后端 API 路由 | `shared/commonMain/api/routes/Weben*` | REST 接口（见下表） |
| 前端工具函数 | `fredica-webui/app/util/weben.ts` | 数据类型定义 + 格式化工具 |
| 前端 API 调用 | `fredica-webui/app/util/materialWebenApi.ts` | API 封装 + 响应类型 |
| 前端守卫 | `fredica-webui/app/util/materialWebenGuards.ts` | 响应归一化（防御 legacy 数据） |
| 前端路由页面 | `fredica-webui/app/routes/weben.*` | UI 路由（见下节） |

---

## 数据模型

### WebenSource（`weben_source` 表）

来源代表一个可产出概念的知识来源，可选关联 `material_id`。

```kotlin
data class WebenSource(
    val id: String,
    val materialId: String?,          // 关联素材库 material.id，外部 URL 时为 null
    val url: String,                  // 资源地址；无外部 URL 时为 "material://<id>"
    val title: String,
    val sourceType: String,           // bilibili_video | local_file | web_article
    val bvid: String?,
    val durationSec: Double?,
    val qualityScore: Double,         // 0–1，用于图谱置信度加权
    val analysisStatus: String,       // pending | analyzing | completed | failed
    val workflowRunId: String?,       // 关联 WorkflowRun（状态对账用）
    val progress: Int,                // 0–100，由任务图动态计算
    val createdAt: Long,
)
```

> **已知 legacy 问题**：旧版数据可能将 `material_id` 存为空字符串 `""`。
> `WebenSourceDb.toSource()` 已修正为 `takeIf { it.isNotBlank() }`，前端 `normalizeWebenSource()` 也做了等效守卫。

### WebenConcept（`weben_concept` 表）

```kotlin
data class WebenConcept(
    val id: String,
    val canonicalName: String,
    val conceptType: String,          // 逗号分隔的多类型，如 "术语,数据结构"
    val briefDefinition: String?,
    val confidence: Double,           // 0–1
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)
```

关联表：`WebenConceptAlias`（别名）、`WebenConceptSource`（来源摘录）、`WebenNote`（用户笔记）。

### WebenExtractionRun（`weben_extraction_run` 表）

一次概念提取操作的完整上下文，供审计/复现用。

| 字段 | 说明 |
|------|------|
| `source_id` | 关联来源 |
| `material_id` | 冗余存储，方便查询 |
| `llm_model_id` | 使用的 LLM 模型 |
| `prompt_script` | GraalJS 脚本（生成 prompt 用） |
| `prompt_text` | 实际发给 LLM 的 prompt |
| `llm_input_json` | LLM 请求体 |
| `llm_output_raw` | LLM 原始输出 |
| `concept_count` | 本次导入的概念总数 |

---

## API 路由

### 来源

| 路由 | 方法 | 说明 |
|------|------|------|
| `WebenSourceListRoute` | GET | 列表（可按 `material_id` 过滤），含双层状态对账 |
| `WebenSourceGetRoute` | GET | 单条来源详情（含 `concept_count`） |

### 概念

| 路由 | 方法 | 说明 |
|------|------|------|
| `WebenConceptListRoute` | GET | 分页列表（可按 `source_id`/`material_id` 过滤） |
| `WebenConceptGetRoute` | GET | 详情（含 aliases / sources / notes） |
| `WebenConceptUpdateRoute` | POST | 更新定义/类型 |
| `WebenConceptTypeHintsRoute` | GET | 可用类型 + 颜色标签 |
| `WebenConceptBatchImportRoute` | POST | 批量导入（legacy，新代码用 ExtractionRunSave） |

### 提取历史

| 路由 | 方法 | 说明 |
|------|------|------|
| `WebenExtractionRunSaveRoute` | POST | 保存提取运行（同时 upsert 概念） |
| `WebenExtractionRunListRoute` | GET | 按 `source_id` 列表（摘要，无大字段） |
| `WebenExtractionRunGetRoute` | GET | 单条完整详情（含 prompt/output） |

### 笔记

| 路由 | 方法 | 说明 |
|------|------|------|
| `WebenNoteSaveRoute` | POST | 创建/更新笔记 |
| `WebenNoteDeleteRoute` | POST | 删除笔记 |

---

## 状态对账（Reconcile）

`WebenSourceListRoute` 在每次查询时对非终态（`pending`/`analyzing`）来源进行状态对账：

1. `workflowRunId` 为 null → `failed`
2. WorkflowRun 已删除 → `failed`
3. WorkflowRun 终态（`failed`/`cancelled`/`completed`）→ 同步
4. WorkflowRun 非终态，读 Task 实际状态推导

**首次启动保护**（`WebenSourceReconcileGuard`）：按 `material_id` 查询时，每个 material 每次 APP 启动只对账一次，后续轮询走零开销快速路径。

---

## 前端路由结构

```
/weben                          weben._index.tsx        知识网络首页（来源 + 概念总览）
/weben/sources/:id              weben.sources.$id.tsx   来源详情（单页滚动，含提取记录）
/weben/concepts                 weben.concepts._index.tsx  概念库列表
/weben/concepts/:id             weben.concepts.$id.tsx  概念详情（定义编辑 + 笔记 + 来源）
```

> 来源概念筛选应作为 `/weben/concepts` 页面的过滤器功能实现（`source_id` query param），
> 而不是在来源详情页中重复展示概念列表。

---

## 前端守卫函数

`materialWebenGuards.ts` 中的 `normalizeWebenSource(data: unknown): WebenSource | null` 用于对 API 响应做防御性归一化：

- `material_id: "" | "  "` → `null`（legacy 空串问题）
- 入参非法（null / 非对象 / 缺少 `id`）→ 返回 `null`

**使用约定**：凡是从 API 接收 `WebenSource` 对象处，均应经过此函数，不直接 `as WebenSource` 强转。

---

## 常见陷阱

- `WebenExtractionRunSaveRoute.ensureSource()` 收到 `material_id: ""` 时会创建 URL 为 `"material://"` 的来源记录。已修复：调用前用 `takeIf { it.isNotBlank() }` 归一化。
- `WebenConceptListRoute` 按 `source_id` 过滤时概念数可能与 `WebenSourceGetRoute` 的 `concept_count` 不一致（后者是实时 count，前者依赖分页 limit）。
- 提取记录列表接口（`WebenExtractionRunListRoute`）只返回摘要字段，展开详情需再调 `WebenExtractionRunGetRoute`。

---

## 相关文档

- [概念提取工作台](./concept-extraction.md) — 前端提取页面的完整数据流、设计决策与陷阱
