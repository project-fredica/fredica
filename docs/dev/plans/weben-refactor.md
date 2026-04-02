---
title: Weben 精简 + SchemaHint 包提取重构计划
order: 530
---

# Weben 精简 + SchemaHint 包提取重构计划

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-31
> **适用模块**：`shared/commonMain` · `shared/jvmMain` · `shared/jvmTest` · `fredica-webui`

---

## 1. 目标概述

| 块 | 目标 |
|----|------|
| **A. Weben 精简** | 移除关系（relation）与闪卡（flashcard）功能，专注于概念（concept）提取 |
| **B. SchemaHint 包提取** | 将 LLM 输出 Schema 定义从 `api.routes` 包迁移到独立的 `prompt.schema` 包 |

---

## Part A：Weben 精简

### 背景

当前 Weben 模块包含三层提取结果：概念、关系、闪卡。关系和闪卡的提取质量差，工程复杂度高，决定全部移除，只保留概念提取。

### A-1 删除文件

| 文件路径 | 说明 |
|---------|------|
| `shared/src/commonMain/.../db/weben/WebenRelation.kt` | 关系 DB 模型（`weben_relation` + `weben_relation_source` 表） |
| `shared/src/commonMain/.../db/weben/WebenFlashcard.kt` | 闪卡 DB 模型（`weben_flashcard` + `weben_mastery_record` 表 + SM-2 算法） |
| `shared/src/commonMain/.../api/routes/WebenRelationListRoute.kt` | 关系列表接口 |
| `shared/src/commonMain/.../api/routes/WebenRelationCreateRoute.kt` | 关系创建接口 |
| `shared/src/commonMain/.../api/routes/WebenRelationDeleteRoute.kt` | 关系删除接口 |
| `shared/src/commonMain/.../api/routes/WebenFlashcardCreateRoute.kt` | 闪卡创建接口 |
| `shared/src/commonMain/.../api/routes/WebenFlashcardListRoute.kt` | 闪卡列表接口 |
| `shared/src/commonMain/.../api/routes/WebenFlashcardReviewRoute.kt` | 闪卡复习接口（SM-2） |
| `shared/src/commonMain/.../api/routes/WebenReviewQueueRoute.kt` | 今日复习队列接口 |
| `shared/src/jvmTest/.../db/weben/WebenFlashcardDbTest.kt` | 闪卡 DB 测试 |
| `fredica-webui/app/routes/weben.review._index.tsx` | 复习界面路由 |

### A-2 修改文件：Kotlin 后端

#### `WebenSummarySchema.kt`（`api/routes/` 内，Task B 后迁移）

- 删除 `WebenSummaryRelation` 类
- 删除 `WebenSummaryFlashcard` 类
- `WebenSummary`：移除 `relations` 和 `flashcards` 字段；更新 `@Description` 为"Weben 知识提取结果，顶层对象包含 concepts 数组字段。"

改后：

```kotlin
@Schema(withSchemaObject = true)
@Description("Weben 知识提取结果，顶层对象包含 concepts 数组字段。")
@Serializable
data class WebenSummary(
    @Description("概念列表。")
    val concepts: List<WebenSummaryConcept> = emptyList(),
)
```

#### `WebenConcept.kt`

- `WebenConcept` 数据类：删除 `mastery` 字段（该字段是"所有闪卡 ease_factor 的归一化均值"，闪卡移除后无意义）
- `WebenConceptRepo` 接口：删除 `updateMastery(conceptId, mastery)` 方法
- DB 实现（`jvmMain`）中对应的 `update mastery` SQL 语句一并删除

#### `WebenConceptGetRoute.kt`

- 删除 `WebenRelationService.repo.listByConcept(id)` 调用
- 删除 `WebenFlashcardService.repo.listByConcept(id)` 调用
- 删除响应体中的 `"relations"` 和 `"flashcard_count"` 字段
- 删除对 `WebenRelationService`、`WebenFlashcardService` 的 import
- 更新 docstring

改后响应体：`concept` · `aliases` · `sources` · `notes`

#### `WebenConceptBatchImportRoute.kt`

- 删除 `relations` 处理循环（引用 `WebenRelation`、`WebenRelationService`、`WebenRelationSource`）
- 删除 `flashcards` 处理循环（引用 `WebenFlashcard`、`WebenFlashcardService`）
- 删除 `ImportRelationItem`、`ImportFlashcardItem` 数据类
- `WebenConceptBatchImportParam`：删除 `relations`、`flashcards` 字段
- `handler` 中的日志、返回值移除对应计数字段（`relation_imported`、`flashcard_imported`）
- 更新 `desc`：`"批量导入 Weben concepts"`

#### `WebenSourceListRoute.kt`

- `WebenSourceListItem`：删除 `flashcard_count` 字段
- 删除 `WebenFlashcardService.repo.countBySource(source.id)` 调用
- 删除对 `WebenFlashcardService` 的 import

#### `WebenSourceGetRoute.kt`

- 检查并删除所有 `flashcard_count` 相关逻辑及 `WebenFlashcardService` import

#### `WebenLearningPathRoute.kt`

- 检查并删除所有依赖闪卡/关系数据的逻辑（如按掌握度排序逻辑）；若整个路由仅服务于闪卡学习路径，考虑一并删除并从 `all_routes.kt` 中移除

#### `all_routes.kt`

从 `getCommonRoutes()` 列表中删除以下路由：

```kotlin
// 删除这几行：
WebenFlashcardCreateRoute,
WebenFlashcardListRoute,
WebenFlashcardReviewRoute,
WebenRelationCreateRoute,
WebenRelationDeleteRoute,
WebenRelationListRoute,
WebenReviewQueueRoute,
```

#### `FredicaApi.jvm.kt`

- 删除 `WebenFlashcardService` 初始化代码（建表、服务注入）
- 删除 `WebenRelationService` 初始化代码

### A-3 修改文件：前端

#### `fredica-webui/app/util/weben.ts`

- 删除 `WebenRelation` 接口
- 删除 `WebenFlashcard` 接口
- `WebenConceptDetailResponse`：删除 `relations: WebenRelation[]` 和 `flashcard_count: number`
- `WebenReviewQueueResponse`：整个接口删除
- `WebenSourceListItem`：删除 `flashcard_count: number`
- 删除 `PREDICATES` 常量（关系谓词列表）
- 删除 mastery 相关函数：`masteryBarColor`、`masteryLabel`、`masteryTextColor`
  - 保留 `formatReviewInterval` 的判断：改名或保留为通用时间格式化，视前端实际使用情况决定
- `WebenConcept` 接口：删除 `mastery: number` 字段

#### `fredica-webui/app/routes/weben._index.tsx`

- 删除复习相关 UI（"今日复习"横幅、到期闪卡数量展示等）
- 保留来源列表和概念列表的概览部分

#### `fredica-webui/app/routes/weben.concepts.$id.tsx`

- 删除"关系图谱邻居"区块（`relations` 部分）
- 删除"闪卡"区块（`flashcard_count` 展示及闪卡列表）
- 保留：概念基础信息、别名、来源关联、笔记

#### `fredica-webui/app/routes/weben.sources._index.tsx`

- 表格/列表中删除 `闪卡数` 列

### A-4 DB 处理

以下表在 SQLite 数据库文件中直接 `DROP TABLE`（下次重新使用时不会自动创建，旧数据文件迁移需手动处理）：

```sql
DROP TABLE IF EXISTS weben_relation_source;
DROP TABLE IF EXISTS weben_relation;
DROP TABLE IF EXISTS weben_mastery_record;
DROP TABLE IF EXISTS weben_flashcard;
```

`weben_concept` 表删除 `mastery` 列（SQLite 3.35+ 支持 `ALTER TABLE DROP COLUMN`，测试环境直接重建临时文件即可）：

```sql
ALTER TABLE weben_concept DROP COLUMN mastery;
```

> 注：应用首次启动时表结构已由 Kotlin 代码定义，重新建库时会按新代码创建。线上迁移脚本需在 `FredicaApi.jvm.kt` 或独立的 `DbMigration` 模块中执行。

---

## Part B：SchemaHint 包提取

### 背景

`WebenSummarySchema.kt` 当前放在 `api.routes` 包，与路由处理器放在一起。但它定义的是**LLM 输入/输出格式**，属于 Prompt 基础设施，与路由层耦合是错误的包组织。

目标：将 LLM Schema 定义文件迁移到独立的 `prompt.schema` 包，与 `PromptRuntimeContextProvider` 在同一顶层包下。

### B-1 目标包结构

```
shared/src/commonMain/kotlin/com/github/project_fredica/
├── api/routes/          # 路由处理器（不含 schema 定义）
├── prompt/
│   ├── schema/          # ← 新包：LLM I/O Schema 定义
│   │   └── WebenSummarySchema.kt
│   └── （jvmMain 中）
│       ├── PromptRuntimeContextProvider.kt
│       ├── PromptScriptRuntime.kt
│       └── BuiltInTemplateLoader.kt
```

### B-2 操作步骤

1. **新建** `shared/src/commonMain/kotlin/com/github/project_fredica/prompt/schema/WebenSummarySchema.kt`

   - 将 `WebenSummarySchema.kt` 内容移入新文件
   - 修改包声明：`package com.github.project_fredica.prompt.schema`
   - 内容已在 Part A 中精简为仅含 `WebenSummary` + `WebenSummaryConcept`

2. **删除** 原文件 `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/WebenSummarySchema.kt`

3. **更新 `PromptRuntimeContextProvider.kt`** 的 import：

   ```kotlin
   // 旧
   import com.github.project_fredica.api.routes.WebenSummary
   import com.github.project_fredica.api.routes.jsonSchemaString
   
   // 新
   import com.github.project_fredica.prompt.schema.WebenSummary
   import com.github.project_fredica.prompt.schema.jsonSchemaString
   ```

   > 注：`jsonSchemaString` 是 kotlinx-schema KSP 生成的扩展属性，生成位置与 `WebenSummary` 同包，因此跟随文件迁移，import 路径需同步更新。

4. **更新 `PromptScriptRuntimeTest.kt`**（如有直接 import `WebenSummary`）：同步修改 import 路径。

### B-3 注意事项

- Part A 精简（移除 `WebenSummaryRelation`、`WebenSummaryFlashcard`）应**先于** Part B 迁移执行，避免在新包中残留废弃类
- KSP 生成代码路径跟随源文件包名，迁移后需执行 `./gradlew :shared:build` 触发重新生成

---

## 执行顺序

1. **A-1**：删除全部废弃文件（减少后续修改中的编译噪音）
2. **A-2**：修改 Kotlin 后端（按依赖关系：DB 模型 → 路由 → `all_routes.kt` → `FredicaApi.jvm.kt`）
3. **A-3**：修改前端（`weben.ts` 类型定义先改，再改各页面）
4. **B-1 ~ B-3**：迁移 `WebenSummarySchema.kt` 到 `prompt.schema` 包
5. 执行 `./gradlew :shared:build` 验证编译
6. 执行 `./gradlew :shared:jvmTest` 验证测试
7. 前端执行 `npm run typecheck`（或 `npm run build`）验证类型

---

## 验收标准

- `./gradlew :shared:build` 无错误
- `./gradlew :shared:jvmTest` 全部通过（删除 `WebenFlashcardDbTest` 后其余测试不受影响）
- 前端 `npm run typecheck` 无 weben 相关 TS 错误
- Weben 模块中不再有任何 `WebenRelation`、`WebenFlashcard`、`WebenReviewQueue`、`mastery` 相关引用
- `PromptRuntimeContextProvider` import 路径指向 `prompt.schema` 而非 `api.routes`
