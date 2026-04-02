---
title: 概念提取工作台
order: 310
---

# 概念提取工作台

本文档描述从视频素材中提取 Weben 概念的完整流程，涵盖前端页面架构、数据流、关键设计决策与常见陷阱。

---

## 入口路由

```
/material/:materialId/summary/weben
```

文件：`fredica-webui/app/routes/material.$materialId.summary.weben.tsx`

---

## 整体数据流

```
用户编写脚本
    │
    ▼
GraalJS 沙箱执行脚本（PromptTemplatePreviewRoute SSE）
    │  生成 Prompt 文本
    ▼
LlmProxyChatRoute（OpenAI 兼容 SSE）
    │  流式返回 LLM 输出文本
    ▼
parseWebenResult()
    │  剥除代码块围栏，json_parse，normalizeWebenResult
    ▼
parsedResult → reviewedResult（可编辑副本）
    │
    ▼  用户点击"保存到 Weben"
fetchConceptsByMaterial()
    │  拉取该素材已有概念
    ▼
resolveConceptDiffBaseline()
    │  优先使用本地快照，避免拉到刚写入的数据
    ▼
computeConceptDiff()
    │  严格字符串分类：added / changed / removed / unchanged
    ▼
ConceptSaveModal（审阅概念变更）
    │  用户逐条审阅，可选"合并已有类型"
    ▼
saveExtractionRun()
    │  WebenExtractionRunSaveRoute — 同时写 ExtractionRun + upsert 概念
    ▼
setSavedConceptBaseline()   ← 下次打开 modal 用此快照，不重新拉 API
```

---

## 前端模块地图

| 文件 | 职责 |
|------|------|
| `routes/material.$materialId.summary.weben.tsx` | 页面主体：状态机、生成流程、保存流程 |
| `components/weben/ConceptSaveEditor.tsx` | 审阅 modal 的编辑器组件 + diff 计算 |
| `util/materialWebenApi.ts` | API 封装：`saveExtractionRun`、`fetchConceptsByMaterial`、`previewPromptScript` 等 |
| `util/materialWebenGuards.ts` | 响应归一化：`normalizeWebenResult`、`validateWebenResult`、`normalizeWebenSource` |
| `util/weben.ts` | 数据类型定义：`WebenConcept`、`WebenSource`、`WebenExtractionRun` |

---

## 关键设计决策

### 1. parsedResult vs reviewedResult 双状态

`parsedResult` 只写一次（LLM 输出解析后），不可变，作为"原始解析"存档。  
`reviewedResult` 是用户可编辑的副本，允许在渲染面板中删除单条概念。  
保存时使用 `reviewedResult`；重新生成时两者同时清空。

### 2. effectiveMaterialId 回退策略

Workspace Context 的 `material.id` 在某些场景下初始化前为空字符串。所有 API 调用和 diff 计算均使用：

```ts
const effectiveMaterialId = material.id.trim() || routeMaterialId.trim();
```

`routeMaterialId` 直接来自 URL 参数，始终非空，作为安全兜底。  
脚本注入头部（`__materialId`）固定使用 `routeMaterialId`，确保 GraalJS 沙箱侧始终拿到正确素材 ID。

### 3. savedConceptBaseline — 避免重复保存的伪 diff

保存成功后，前端在本地构建一份 `savedConceptBaseline`（从 `finalConcepts` 直接映射为 `WebenConcept[]`）。  
下次点击"保存到 Weben"时，`resolveConceptDiffBaseline` 优先使用此快照，而非重新拉取 API。  
这样可以避免：刚写入的数据被拉回来，导致下一次保存 diff 全部变成 unchanged/added 的伪变更。

### 4. computeConceptDiff 的严格性 vs UI 层的集合等价性

`computeConceptDiff` 使用**严格字符串比较**（类型顺序敏感），保证分类结果稳定。  
UI 层的 `changedItems` useMemo 则单独评估"合并已有类型"选项的实际效果：
- 若合并后类型集合（顺序无关）等于已有类型集合，且描述无变化 → 折叠为"合并后无变化"徽标
- 条目仍保留在 `diff.changed`，`handleConfirm` 仍会输出正确的合并 payload

### 5. 删除项（removed）的延迟处理

`deleteRemoved` 仅记录用户的标记意图，当前版本不向后端发送删除指令。  
被标记和未标记的 removed 条目均从本次 result payload 中省略——它们在 DB 中保持不变。  
未来可新增"清理"步骤来处理 `deleteRemoved` 集合。

---

## 脚本执行链路

1. 用户在 `PromptBuilder` 编辑器中写 JS 脚本（`template`）
2. 页面自动注入执行头部（`scriptHeader`），内容为 `var __materialId = "..."`
3. 点击"预览"或"生成"时，完整脚本（`scriptHeader + "\n\n" + template`）发往后端 `PromptTemplatePreviewRoute`
4. 后端在 GraalJS 沙箱中执行脚本，通过 SSE 流式返回执行日志和最终 Prompt 文本
5. 生成流程继续将 Prompt 文本发往 `LlmProxyChatRoute`

> **注意**：预览（Preview）和生成（Generate）共享同一套脚本执行链路，区别仅在于生成流程额外发起 LLM 调用。

---

## SSE 格式约定

### PromptTemplatePreviewRoute（脚本执行）

```
data: {"type":"log","level":"log","args":"...","ts":1234567890}
data: {"type":"result","prompt_text":"..."}
data: {"type":"error","error":"...","error_type":"..."}
```

### LlmProxyChatRoute（LLM 流式输出）

```
data: {"choices":[{"delta":{"content":"..."}}]}
...
data: [DONE]
```

遇到 `event: llm_error` 时，后续 data 行包含错误信息，作为异常抛出。

---

## 常见陷阱

- **初次打开 modal 全部显示为"新增"**：通常是 `effectiveMaterialId` 为空，导致 `filterConceptsForMaterial` 过滤后返回空数组。检查 `resolveConceptDiffBaseline` 的 debug 日志中 `materialId` 是否正确。
- **保存后重新打开 modal 又全部变成"新增"**：说明 `savedConceptBaseline` 未被正确写入，或者使用了错误的 `effectiveMaterialId`。
- **类型比较误判为"变化"**：`computeConceptDiff` 是顺序敏感的。如果后端返回的 `concept_type` 顺序与 LLM 输出顺序不同，会被判为 changed。UI 层通过"合并已有类型"的集合等价检查来折叠这类无实质变化的条目。
- **跨素材概念污染**：旧版 `WebenConceptListRoute` 没有按 `material_id` 过滤，可能返回其他素材的概念。`resolveConceptDiffBaseline` 中的 `filterConceptsForMaterial` 确保只使用当前素材的概念作为基线。空字符串 `material_id` 的 legacy 行也会被排除。
