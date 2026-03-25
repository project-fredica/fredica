---
title: Summary × Weben 分析集成计划（v2）
order: 510
---

# Summary × Weben 分析集成计划（v2）

> 目标：在素材工作区 **Summary 分组**下新建 Weben 子路由，让用户自主选择已提取的
> "信息来源"、自由构建 Prompt，通过 `LlmProxyChatRoute` 驱动 LLM 生成符合
> Weben Schema 的知识结构，并持久化到 DB。

---

## 1. 路由结构

当前 `summary.tsx` 是叶子路由。改为**布局路由 + 子路由**：

```
material.$materialId.summary.tsx          ← 布局（顶部子导航 + Outlet）
material.$materialId.summary._index.tsx   ← 概览（可暂时保留 mock，后续扩展）
material.$materialId.summary.weben.tsx    ← Weben 知识提取（本计划核心）
```

URL 示例：
```
/material/bilibili_bvid__BV1xx__P1/summary           → _index
/material/bilibili_bvid__BV1xx__P1/summary/weben     → weben 分析页
```

子导航 Tab 设计（位于 summary 布局内，风格与工作区主导航一致）：
| Tab | 路径 | 说明 |
|-----|------|------|
| 概览 | `.summary` | 保留现有 mock，后续接真实数据 |
| Weben 知识提取 | `.summary/weben` | 本计划实现 |
| （翻译）| `.summary/translation` | 预留，暂不实现 |

---

## 2. 核心设计：信息来源 × Prompt 构建 × Weben Schema

### 2.1 信息来源（Info Sources）

用户可勾选已提取的"信息块"注入到 Prompt 上下文中。
信息来源随功能迭代**渐进扩展**，当前只有字幕可用：

| 信息来源 | 状态 | 数据来源（API） |
|----------|------|----------------|
| 字幕文本 | ✅ 可用 | `MaterialSubtitleListRoute` → 字幕内容接口 |
| 场景跳转信息 | 🔜 未实现 | 预留 |
| 说话人信息 | 🔜 未实现（diarize 页已有 UI 占位） | 预留 |
| 图像识别信息 | 🔜 未实现 | 预留 |
| 视频内容识别信息 | 🔜 未实现 | 预留 |

**可扩展约定**：每种信息来源实现为独立的 `InfoSourceBlock`，统一接口：
```ts
interface InfoSourceBlock {
    id: string;                        // 唯一标识，如 "subtitle"
    label: string;                     // 展示名
    available: boolean;                // 是否有数据可用
    unavailableReason?: string;        // 不可用原因（如"需先完成字幕提取"）
    fetchContent: () => Promise<string>; // 拉取文本内容
}
```

### 2.2 Prompt 构建器

页面提供一个可编辑的 Prompt 模板区域：

```
[信息来源选择区]
  ☑ 字幕文本（已提取，3,420 字）
  ☐ 说话人信息（未提取）

[Prompt 编辑器]
  ┌────────────────────────────────────┐
  │ 以下是视频《{{title}}》的相关信息：  │
  │                                    │
  │ == 字幕文本 ==                     │
  │ {{subtitle}}                       │
  │                                    │
  │ 请根据以上内容，以 JSON 格式输出     │
  │ 符合 Weben Schema 的知识结构……      │
  └────────────────────────────────────┘
  [重置为默认模板]

[模型选择]  [生成]
```

`{{title}}` / `{{subtitle}}` 等占位符在提交前由前端替换为真实内容。

### 2.3 Weben Schema 输出格式

要求 LLM 输出的 JSON 结构：

```jsonc
{
  "concepts": [
    {
      "name": "Transformer",          // canonical_name
      "type": "算法",                  // concept_type（11 种枚举之一）
      "description": "...",
      "aliases": ["transformer", "变换器"]  // 可选
    }
  ],
  "relations": [
    {
      "subject": "Transformer",       // 概念名
      "predicate": "依赖",             // 7 种枚举之一
      "object": "注意力机制",
      "excerpt": "..."                // 可选，来源摘录
    }
  ],
  "flashcards": [
    {
      "question": "Transformer 的核心创新是什么？",
      "answer": "...",
      "concept": "Transformer"        // 关联概念名
    }
  ]
}
```

Prompt 模板末尾须附带完整 Schema 说明（concept_type 枚举 + predicate 枚举），
以确保 LLM 输出合法值。默认模板由前端硬编码，用户可自由修改。

### 2.4 结果流程

```
[生成] 按钮
  → 前端拼 Prompt（替换占位符）
  → llmChat({ mode: "router", app_model_id, message: prompt })
  → SSE 流式渲染（展示在代码块区域）
  → 流结束后：解析 JSON → 展示"结果预览"（概念卡片 + 关系列表 + 闪卡）
  → [保存到 Weben DB] 按钮 → POST WebenConceptBatchImportRoute
  → 保存成功 → 展示"已保存"，提供跳转到 /weben/concepts 的链接
```

用户可在"结果预览"阶段**编辑/删除**条目再保存（Phase 2 可选）。

---

## 3. 后端改动

### 3.1 LlmModelListRoute.kt — 新建

```
GET /api/v1/LlmModelListRoute
响应：[{ app_model_id, label, notes }]  ← 不含 api_key / base_url
```

从 `AppConfigService.repo.getConfig().llmModelsJson` 读取，只暴露安全字段。
注册到 `all_routes.kt`。

### 3.2 获取字幕文本内容的接口

`MaterialSubtitleListRoute` 目前返回字幕**元数据**（轨道列表）。
需要一个能返回**字幕全文**（纯文本，时间戳可选）的接口：

**方案 A**（推荐）：扩展或新建 `MaterialSubtitleContentRoute`
```
GET /api/v1/MaterialSubtitleContentRoute?material_id=&subtitle_id=
响应：{ text: string, word_count: number }
```

**方案 B**：直接使用现有 `BilibiliVideoSubtitleBodyRoute`（已有，但耦合 Bilibili）。

推荐方案 A：解耦 source type，后续也能用于本地字幕文件。

### 3.3 WebenConceptBatchImportRoute.kt — 新建

```
POST /api/v1/WebenConceptBatchImportRoute
请求：{
  material_id: string,
  concepts:   [{ name, type, description, aliases? }],
  relations:  [{ subject, predicate, object, excerpt? }],
  flashcards: [{ question, answer, concept }]
}
响应：{ ok: true, concept_count, relation_count, flashcard_count }
```

后端逻辑：
1. 按 `canonical_name` upsert `WebenConcept`（相同名字合并）
2. 批量写 `WebenConceptAlias`
3. 批量写 `WebenRelation`（subject/object 需先找到对应 concept_id）
4. 批量写 `WebenFlashcard`
5. 所有写操作在一个事务中（失败整体回滚）

注册到 `all_routes.kt`。

---

## 4. 前端改动

### 4.1 路由文件

| 文件 | 操作 |
|------|------|
| `material.$materialId.summary.tsx` | 改为布局路由（加 sub-tab 导航 + `<Outlet />`） |
| `material.$materialId.summary._index.tsx` | 新建，承接现有 mock 内容 |
| `material.$materialId.summary.weben.tsx` | 新建，本计划核心 |

### 4.2 新工具 `app/util/materialWebenApi.ts`

```ts
// 拉取可用 LLM 模型列表
fetchLlmModels(apiFetch): Promise<LlmModelMeta[]>

// 拉取字幕全文
fetchSubtitleContent(apiFetch, materialId, subtitleId): Promise<string>

// 批量保存 Weben 分析结果
batchImportWebenConcepts(apiFetch, materialId, result: WebenLlmResult): Promise<Response>
```

### 4.3 `material.$materialId.summary.weben.tsx` 页面结构

该页面不再采用"信息来源 / 编辑器 / 输出 / 结果"四块纵向堆叠，而是采用 **PromptBuilder 工作台**：

- **默认：单栏 Tabbar**
  - `编辑器`
  - `预览`
  - `LLM输出`
  - `组件渲染`
- **桌面端可切换为双栏**
  - 左栏固定 `编辑器`
  - 右栏在 `预览 / LLM输出 / 组件渲染` 间切换
- **Tab 切换不销毁内容区**，保留编辑器光标、预览滚动位置、流式输出内容

页面上仍保留以下业务区块，但由工作台内部组织：

1. **信息来源选择**：用户勾选要注入 Prompt 的来源（如字幕）
2. **Prompt 编辑**：基于 `PromptBuilder` / `PromptEditor`
3. **Prompt 预览**：展示变量替换后的最终 Prompt
4. **LLM 输出**：SSE 原始文本流
5. **结果渲染**：将解析结果渲染为概念卡片、关系列表、闪卡等 UI

建议的页面关系：

```tsx
<SummaryWebenPage>
  <InfoSourcePanel />
  <ModelSelector />
  <PromptBuilder
    value={template}
    onChange={setTemplate}
    variableResolver={resolver}
    previewPane={<PromptPreviewPane ... />}
    streamPane={<LlmStreamPane ... />}
    renderPane={<WebenResultPane ... />}
  />
</SummaryWebenPage>
```

**详细设计**（组件拆分、tab/split 状态、CodeMirror 集成、变量解析）见：
- [prompt-builder-design.md](./prompt-builder-design.md)

**状态机**（业务层）保持不变：
```
editing → previewed → generating → parsed → saving → saved
                           └──────→ parse_error
```

**容错**：若 LLM 输出无法解析为合法 JSON，保留"LLM输出"原始文本，并在"组件渲染"视图展示解析失败提示。

---

## 5. 默认 Prompt 模板

默认模板不在本文档中展开详细语法设计。

这里仅保留集成层约束：
- 模板由 `PromptBuilder` 管理
- 变量语法、slot 变量、专家模式、模板存储策略见专门文档
- `summary.weben.tsx` 只负责提供 `weben_extract` 场景下的默认模板、默认变量列表、slot 注册表

建议：
- 默认模板作为前端常量提供
- 用户编辑结果短期存 `localStorage`
- 模板体内包含 schema 说明 slot（如 `${weben_schema_hint}`），具体序列化逻辑由 PromptBuilder 基础设施处理

详见：
- [prompt-builder-design.md](./prompt-builder-design.md)

---

## 6. 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `shared/.../routes/LlmModelListRoute.kt` | 新建 | 暴露模型元数据 |
| `shared/.../routes/MaterialSubtitleContentRoute.kt` | 新建 | 字幕全文 API |
| `shared/.../routes/WebenConceptBatchImportRoute.kt` | 新建 | 批量导入 weben 结果 |
| `shared/.../routes/all_routes.kt` | 修改 | 注册以上三个路由 |
| `fredica-webui/app/routes/material.$materialId.summary.tsx` | 改为布局路由 | 加子导航 + Outlet |
| `fredica-webui/app/routes/material.$materialId.summary._index.tsx` | 新建 | 承接现有内容 |
| `fredica-webui/app/routes/material.$materialId.summary.weben.tsx` | 新建 | 核心实现 |
| `fredica-webui/app/util/materialWebenApi.ts` | 新建 | 前端 API 封装 |

---

## 7. 开发顺序

```
Phase 1：后端 API
  1. LlmModelListRoute（简单，无依赖）
  2. MaterialSubtitleContentRoute（读取已存字幕文件）
  3. WebenConceptBatchImportRoute（事务写入，最复杂）

Phase 2：前端路由重构
  4. summary.tsx → 布局路由
  5. summary._index.tsx（移入现有内容）

Phase 3：PromptBuilder 基础设施
  6. PromptBuilder / PromptEditor / Tabbar / SplitLayout
  7. VariableResolver / buildPrompt / slot 注册机制
  8. 单栏 tab + 双栏工作台打通

Phase 4：weben 分析页集成
  9. materialWebenApi.ts
  10. summary.weben.tsx（信息来源 + 模型选择 + PromptBuilder 接入）
  11. SSE 流式生成 + 结果解析
  12. 保存到 DB（WebenConceptBatchImportRoute）
```

PromptBuilder 的详细拆分、状态流、缓存与测试策略见：
- [prompt-builder-design.md](./prompt-builder-design.md)

---

## 8. 待确认问题

| # | 问题 | 影响 |
|---|------|------|
| 1 | 字幕文本以哪种格式存储在本地？（纯文本 / SRT / JSON）路由如何读取？ | MaterialSubtitleContentRoute 设计 |
| 2 | `WebenConceptBatchImportRoute` 是否需要关联 `WebenSource`？（当前设计不创建 WebenSource） | DB 写入逻辑 |
| 3 | 同一素材可以多次"生成并保存"吗？是追加还是覆盖？ | upsert 策略 |
| 4 | `material.$materialId.summary.tsx` 在主导航中对应 Tab 名是否保持"内容总结"？还是改为更宽泛的名字？ | UI |
