---
title: Summary + Weben 重设计计划（v3）
order: 520
---

# Summary + Weben 重设计计划（v3）

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-30
> **适用模块**：`fredica-webui`（前端）· `shared/commonMain`（路由模型）· `shared/jvmMain`（路由实现）
> **上游计划**：`summary-weben-integration.md`（已完成的 Phase 1-4）

---

## 1. 目标概述

| 大块 | 目标 |
|------|------|
| **A. Summary 页** | 去掉冗余外框 · 概览页接真实数据 · 新增"B站AI总结"子路由 · 完整化 Weben 提取流水线 |
| **B. Weben 重设计** | 以"碎片视频学习者"为核心用户重新组织信息架构，新增来源维度导航、上下文感知复习等 |

---

## Part A：Material Summary 页重设计

### A-1  布局清理（`summary.tsx`）

**当前问题：** 整个 Summary 区域套了一层 `bg-white rounded-xl border`，内部再有 `bg-gray-50/50` 内容区，视觉上厚重、冗余，且与工作区其他分组（Tasks、Player 等）风格不统一。

**改动：**

- 移除最外层的 `bg-white rounded-xl border overflow-hidden` 包裹 div
- 移除页头的 `<h2>内容总结</h2>` + 描述文本（信息量低，占空间）
- Tab 导航条直接铺在页面顶部，样式与工作区主导航一致（不再内嵌在白卡中）
- `<Outlet />` 包裹 div 的 `min-h-[320px] bg-gray-50/50` 去掉，让子页面自己管理背景

改后 `summary.tsx` 结构示意：

```tsx
// 整体：贴边 + 最大宽度限制，无外框
<div className="max-w-5xl mx-auto">
    {/* Tab 导航 */}
    <nav className="border-b border-gray-100 overflow-x-auto ...">
        {SUMMARY_TABS.map(...)}
    </nav>
    {/* 子页面内容直接展开 */}
    <Outlet />
</div>
```

**新增 Tab（同步更新 SUMMARY_TABS）：**

| id | to | label | icon | 显示条件 |
|---|---|---|---|---|
| `overview` | `.` | 概览 | `FileText` | 始终 |
| `bilibili` | `bilibili` | B站AI总结 | `Tv2` | 仅 Bilibili 素材 |
| `weben` | `weben` | 知识提取 | `BrainCircuit` | 始终 |

B站总结 Tab 的显示条件：在 `SummaryLayoutPage` 中通过 `useWorkspaceContext()` 取 `material`，判断 `material.source_type === 'bilibili_video'` 决定是否渲染该 Tab。

---

### A-2  概览页改版（`summary._index.tsx`）

**当前问题：** 全是 `MOCK_*` 常量，无实际数据，`SectionCard` 折叠组件增加操作成本，"翻译"功能和模型选择是孤立的 UI stub 没有业务逻辑。

**目标：** 做成一个高信息密度的扼要概览，数据驱动，无折叠层级。

#### 数据来源

| 数据 | API |
|------|-----|
| 字幕列表（判断是否有字幕可用） | `GET /api/v1/MaterialSubtitleListRoute?material_id=` |
| Weben 来源状态（判断是否已分析） | `GET /api/v1/WebenSourceListRoute?material_id=` |
| Weben 概念列表（已提取的概念） | `GET /api/v1/WebenConceptListRoute?source_id=&limit=8` |

#### 布局设计（无外框，间距紧凑）

```
p-4 space-y-5

┌── 状态行 ──────────────────────────────────────────┐
│  ● 已分析  · 8 个概念  · 12 张闪卡  · 来自字幕     │
│                              [跳转到知识提取 →]     │
└──────────────────────────────────────────────────┘

┌── 核心概念（横向滚动 chip 列表）──────────────────┐
│  [GPIO] [PWM] [定时器] [中断] [DMA] … +3 更多    │
└──────────────────────────────────────────────────┘

┌── 字幕信息 ─────────────────────────────────────┐
│  🎬 CC 中文字幕  ·  3,420 字  ·  B站提取         │
│  [在字幕面板查看 →]                               │
└──────────────────────────────────────────────────┘

── 未分析状态 ──────────────────────────────────────
│  暂无分析结果                                     │
│  [前往知识提取 →]（跳转到 ./weben）               │
─────────────────────────────────────────────────
```

**去除：**
- `SectionCard`（折叠组件）
- "内容摘要"大段文本区（不接入 LLM，此处不做 AI 摘要，由 weben 页处理）
- "翻译"功能（本版本不实现）
- "配置并开始分析"模态模型选择（引导到 `./weben` 子页即可）

---

### A-3  B站AI总结子路由（新建）

**新文件：** `fredica-webui/app/routes/material.$materialId.summary.bilibili.tsx`

#### 功能

调用 `BilibiliVideoAiConclusionRoute`（已有后端路由），展示 B站 AI 生成的视频总结。

**B站 API 返回结构（`model_result` 字段）：**

```jsonc
{
  "code": 0,
  "model_result": {
    "result_type": 1,             // 1=摘要, 2=思维导图 等
    "summary": "...",             // 正文摘要
    "outline": [                  // 章节纲要（含时间戳）
      {
        "title": "GPIO 基础",
        "part_outline": [
          { "timestamp": 30, "content": "GPIO 输入输出模式介绍" }
        ]
      }
    ]
  }
}
```

#### 页面布局

```
状态区：[加载中 / 已加载 / 错误 / 不支持（非B站素材）]

当成功时：
  ┌── B站 AI 总结 ───────────────────────────────┐
  │  正文摘要（纯文本，prose 样式）              │
  └──────────────────────────────────────────────┘

  ┌── 章节纲要 ──────────────────────────────────┐
  │  # GPIO 基础                                 │
  │    00:30  GPIO 输入输出模式介绍              │
  │    01:15  推挽与开漏                          │
  │  # 定时器配置                                 │
  │    03:00  预分频寄存器                        │
  └──────────────────────────────────────────────┘

  [强制刷新]（isUpdate=true 重新拉取）
```

纲要中的时间戳可点击 → 调用 `openFloatingPlayer(materialId, timestamp)` 跳转播放器。

#### 前端逻辑要点

```ts
// bvid 和 page_index 从 workspaceContext.material 中取
const { bvid, page_index } = parseMaterialId(materialId);
POST /api/v1/BilibiliVideoAiConclusionRoute { bvid, page_index, is_update: false }
```

`parseMaterialId` 是已有的工具函数，负责从 `bilibili_bvid__BV1xx__P1` 格式的 materialId 中解析出 bvid 和 P 序号。

---

### A-4  Weben 知识提取完整化（`summary.weben.tsx`）

**当前状态（基于代码审查）：**
- PromptBuilder 集成 ✅（编辑、预览、流式生成）
- LLM 流式调用 ✅（`streamLlmRouterText` → `LlmProxyChatRoute`）
- 结果解析 ✅（`parseWebenResult` + `normalizeWebenResult`）
- 保存到 DB ✅（`importWebenResult` → `WebenConceptBatchImportRoute`）
- 字幕加载 ✅（`fetchMaterialSubtitles`）

**待补全 / 已知缺口：**

#### A-4-1  字幕内容拉取断链

`fetchMaterialSubtitles` 获取字幕**元数据列表**，但 Weben 提取需要字幕**全文**注入 Prompt。检查 `materialWebenApi.ts` 中是否存在 `fetchSubtitleContent` 调用，若有则确认后端 `MaterialSubtitleContentRoute` 是否正常工作（注意：git status 显示该路由的测试文件已被删除，需核查路由本体是否存在）。

**修复方案：** 若 `MaterialSubtitleContentRoute` 已删除或不存在，需重建：

```
GET /api/v1/MaterialSubtitleContentRoute?material_id=&subtitle_id=
响应: { text: string, word_count: number }
```

路由读取 `AppUtil.Paths.subtitleDir(materialId)/{subtitle_id}.txt`（或对应格式文件）返回纯文本。

#### A-4-2  来源信息回写

保存成功后（`importWebenResult` 返回 `source_id`），需在页面状态中缓存该 `source_id`，并在页面顶部显示"已保存到 Weben，共 N 个概念"的常驻提示条（非 toast），提供"查看来源"链接跳转到 `/weben/sources/{source_id}`（需新建，见 Part B）。

#### A-4-3  结果渲染区改善

当前 `parsed` 状态下的结果展示（Concepts / Relations / Flashcards）：

- 每个概念卡上补充 `type` badge（当前有，确认颜色与 weben.concepts._index.tsx 中 `getConceptTypeInfo` 一致）
- Relations 列表：`subject → predicate → object` 三列布局，过长文本截断
- Flashcards：Question / Answer 两行，紧凑展示
- 在"保存到 Weben"按钮旁增加"重新生成"按钮（清空结果，回到 `editing` 状态）

#### A-4-4  `WebenSourceAnalyzeRoute` 取消注释（可选，Phase B）

该路由（全文被注释掉）负责自动化的 `FETCH_SUBTITLE → WEBEN_CONCEPT_EXTRACT` WorkflowRun 流程。当前 `FetchSubtitleExecutor.kt` 已被删除（git status 显示 D 标记）。

**本计划不恢复此自动化流程**，保留手动 Prompt Builder 路径作为主要入口。如需恢复，单独开立计划。

---

## Part B：Weben 知识网络重设计

### B-0  设计背景：碎片化视频学习者的思维习惯

目标用户：每天刷 B站、YouTube，通过平台算法随机接触各类教程、技术分享、科普内容。

**他们的思维模式：**

| 特征 | 含义 |
|------|------|
| **来源锚定** | 记住的是"那个讲 GPIO 的视频"，而不是"GPIO 这个概念" |
| **时间感强** | 关心"今天/这周学了什么"，而不是"知识库总量" |
| **低意愿整理** | 不愿主动归档，期待系统自动整理 |
| **随机探索** | 被推荐到某个视频 → 发现相关概念 → 想"再看看同类" |
| **碎片巩固** | 愿意利用空闲时间刷几张复习卡，但不愿坐下来"学习" |

**当前设计的主要问题：**

1. **以概念为核心**：weben._index 的"待加强概念"和 weben.concepts 都是以知识为维度，缺少"来源维度"
2. **缺少时间感**：没有"最近"视角，不知道什么时候学了什么
3. **复习缺上下文**：复习卡片不显示"来自哪个视频"，无法唤起记忆
4. **来源管理分散**：WebenSource 只在 weben._index 一个小角落出现，缺少专门的来源浏览视图

---

### B-1  信息架构调整

**当前路由：**

```
/weben             → weben._index.tsx（仪表板）
/weben/concepts    → weben.concepts._index.tsx（概念列表）
/weben/concepts/:id → weben.concepts.$id.tsx（概念详情）
/weben/review      → weben.review._index.tsx（复习队列）
```

**新增路由：**

```
/weben/sources              → weben.sources._index.tsx（来源列表）【新建】
/weben/sources/:id          → weben.sources.$id.tsx（来源详情）【新建】
```

---

### B-2  修改：weben._index.tsx（仪表板）

**目标：** 从"数据仪表板"变成"今日学习主页"，突出时间维度和来源维度。

#### 删除 / 简化

- 去掉 3 个 `StatCard`（占空间但信息量低）→ 改为一行简洁数字摘要
- "最近来源" `SourceRow` 链接改为跳到 `/weben/sources/{source.id}`（不再跳 tasks 页）

#### 新增

**① 今日数字摘要（替换 StatCard 网格）**

```
┌──────────────────────────────────────────────────────┐
│  概念库 42 个  ·  本周新增 7 个  ·  今日待复习 5 张   │
└──────────────────────────────────────────────────────┘
```

一行文字，链接到对应页面，不占用大面积空间。

**② "最近摄入"时间线（新增，替换或扩展"最近来源"）**

从 `WebenSourceListRoute` 取最近 5 条来源，按 `created_at` 降序展示，每条显示：

```
┌──────────────────────────────────────────────────────┐
│  3 天前  · 《STM32 定时器深度解析》                   │
│  提取了 8 个概念  ·  [PWM] [预分频器] [ARR 寄存器]   │
│                                          [查看详情 →] │
├──────────────────────────────────────────────────────┤
│  1 周前  · 《GPIO 入门教程》                          │
│  提取了 5 个概念  ·  [推挽输出] [开漏] [上拉]         │
│                                          [查看详情 →] │
└──────────────────────────────────────────────────────┘
```

每行概念 chip 最多显示 3 个，超出显示 `+N`。

实现：`WebenSourceListRoute?limit=5` + 对每条 source 调用 `WebenConceptListRoute?source_id=&limit=3`（并行请求）。

**③ "待复习"快捷操作（保留，位置上移）**

维持原有的全宽蓝/紫色 Banner，但调整文案：

```
┌──────────────────────────────────────────────────────┐
│  📚 今日复习  ·  5 张闪卡待复习                       │
│  预计 3 分钟  ·  [开始 →]                            │
└──────────────────────────────────────────────────────┘
```

**④ "待加强概念"（保留，小幅改进）**

每个 `LowMasteryConceptRow` 右侧补充"来自 X 个视频"的小字标注（调用已有 `weben_concept_source` 表数据）。

---

### B-3  新建：weben.sources._index.tsx（来源列表）

一个专门的来源管理页，让用户"按视频"浏览学到的东西。

```
/weben/sources

┌── 已分析的视频来源 ────────────────────────────────┐
│  [搜索来源标题...]    [全部状态 ▾]                  │
└──────────────────────────────────────────────────┘

每条 SourceCard：
┌──────────────────────────────────────────────────────┐
│  🟢 《STM32 定时器深度解析》                          │
│  bilibili · BV1xx · 2026-03-27                       │
│  8 个概念  ·  12 张闪卡  ·  掌握度 42%               │
│                                  [查看详情 →]         │
└──────────────────────────────────────────────────────┘
```

**数据来源：** `WebenSourceListRoute`（返回 `WebenSource` 列表）

**后端需要扩展：** `WebenSourceListRoute` 响应中增加 `concept_count` 和 `flashcard_count` 聚合字段，或在前端并发请求。

---

### B-4  新建：weben.sources.$id.tsx（来源详情）

**进入方式：** 从来源列表 / weben._index 时间线 / summary 页保存成功后的跳转链接

```
/weben/sources/{source_id}

← 来源列表    《STM32 定时器深度解析》
bilibili · BV1xx · 2026-03-27  ·  ●已完成分析

┌── 掌握度总览 ──────────────────────────────────────┐
│  [=======---]  68%  ·  8 个概念  ·  12 张闪卡      │
│  [复习本来源的闪卡 →]                               │
└──────────────────────────────────────────────────┘

┌── 从本视频提取的概念 ──────────────────────────────┐
│  [PWM 输出]  术语  掌握度 85%                       │
│  [预分频器]  理论  掌握度 60%                       │
│  [ARR 寄存器] 术语  掌握度 30%        [查看 →]      │
└──────────────────────────────────────────────────┘

┌── 相关闪卡 ────────────────────────────────────────┐
│  Q: PWM 的占空比如何用 ARR 和 CCR 配置？            │
│  A: (点击展开)                                      │
└──────────────────────────────────────────────────┘
```

**数据来源：**
- 来源详情：`WebenSourceListRoute?material_id=`（过滤）或单独 `WebenSourceGetRoute`（如不存在需新增）
- 概念列表：`WebenConceptListRoute?source_id={id}&limit=50`
- 闪卡：`WebenFlashcardListRoute?source_id={id}`（当前 API 支持 `concept_id` 过滤，需确认是否支持 `source_id`）

---

### B-5  修改：weben.review._index.tsx（复习队列）

**改进：增加来源上下文**

每张复习卡片在概念名 chip 下方增加来源信息：

```
[概念：GPIO]
来自：《STM32 入门教程》· B站 · 2026-03-20
```

实现：`WebenReviewQueueResponse` 中增加 `concept_sources` 字段，返回每个 `concept_id` 对应的第一个来源标题（后端扩展 `WebenReviewQueueRoute`）；或前端在加载 queue 后并行查询。

**改进：支持来源过滤复习**

从 `weben.sources.$id.tsx` 点"复习本来源的闪卡"时，携带 `?source_id=` query param，复习队列页读取该参数，调用 `WebenReviewQueueRoute?source_id=` 过滤（后端需支持该参数）。

---

### B-6  修改：weben.concepts.$id.tsx（概念详情）

**增加"出现在哪里"区块：**

```
┌── 来源视频 ───────────────────────────────────────┐
│  • 《STM32 定时器》  2026-03-27  [跳转素材 →]     │
│  • 《PWM 实战》      2026-03-20  [跳转素材 →]     │
└──────────────────────────────────────────────────┘
```

数据来源：`weben_concept_source` 表，后端通过扩展概念详情接口或新建 `WebenConceptSourceListRoute` 返回。

---

### B-7  后端补充

| 接口 | 操作 | 说明 |
|------|------|------|
| `WebenSourceListRoute` | 修改 | 响应增加 `concept_count: Int` + `flashcard_count: Int` 聚合字段 |
| `WebenReviewQueueRoute` | 修改 | 支持 `source_id` query param 过滤；响应增加 `concept_sources` 字段（概念ID → 来源标题映射） |
| `WebenFlashcardListRoute` | 确认 | 是否支持 `source_id` 过滤（若不支持则扩展） |
| `WebenConceptSourceListRoute` | 新建 | `GET /api/v1/WebenConceptSourceListRoute?concept_id=` → `[{ source_id, title, material_id, created_at }]` |
| `MaterialSubtitleContentRoute` | 确认/新建 | 若已删除则重建（见 A-4-1） |

---

## 开发顺序

### Phase 1：Summary 布局（低风险，纯前端）

1. `summary.tsx` 去外框，更新 SUMMARY_TABS（含条件渲染 B 站 Tab）
2. `summary._index.tsx` 接真实数据，去 mock，简化布局
3. `summary.bilibili.tsx` 新建 B站AI总结页

### Phase 2：Weben 提取完整化（验证现有流水线）

4. 确认 `MaterialSubtitleContentRoute` 可用性；必要时重建
5. 验证 `importWebenResult` → `WebenConceptBatchImportRoute` 全链路
6. `summary.weben.tsx` 保存后显示来源跳转链接
7. 结果渲染区小幅改善（type badge、relations 三列布局、"重新生成"按钮）

### Phase 3：Weben 来源维度（新页面）

8. 后端：`WebenSourceListRoute` 聚合字段扩展
9. 前端：`weben.sources._index.tsx`（来源列表）
10. 前端：`weben.sources.$id.tsx`（来源详情）

### Phase 4：Weben 仪表板重构

11. `weben._index.tsx` 改版（数字摘要行 + 时间线 + 保留复习 Banner）
12. `weben.concepts.$id.tsx` 增加"来源视频"区块（需 `WebenConceptSourceListRoute`）

### Phase 5：复习上下文增强（可选，优先级较低）

13. 后端：`WebenReviewQueueRoute` 增加来源上下文 + `source_id` 过滤
14. 前端：`weben.review._index.tsx` 显示来源信息，支持 `?source_id=` 过滤

---

## 文件变更清单

| 文件 | 操作 | 所属 Phase |
|------|------|-----------|
| `fredica-webui/app/routes/material.$materialId.summary.tsx` | 修改 | 1 |
| `fredica-webui/app/routes/material.$materialId.summary._index.tsx` | 修改 | 1 |
| `fredica-webui/app/routes/material.$materialId.summary.bilibili.tsx` | 新建 | 1 |
| `fredica-webui/app/routes/material.$materialId.summary.weben.tsx` | 修改 | 2 |
| `shared/.../routes/MaterialSubtitleContentRoute.kt` | 确认/新建 | 2 |
| `shared/.../routes/WebenSourceListRoute.kt` | 修改（聚合字段） | 3 |
| `fredica-webui/app/routes/weben.sources._index.tsx` | 新建 | 3 |
| `fredica-webui/app/routes/weben.sources.$id.tsx` | 新建 | 3 |
| `fredica-webui/app/routes/weben._index.tsx` | 修改 | 4 |
| `fredica-webui/app/routes/weben.concepts.$id.tsx` | 修改 | 4 |
| `shared/.../routes/WebenConceptSourceListRoute.kt` | 新建 | 4 |
| `shared/.../routes/WebenReviewQueueRoute.kt` | 修改 | 5 |
| `fredica-webui/app/routes/weben.review._index.tsx` | 修改 | 5 |
