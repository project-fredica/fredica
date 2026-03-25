---
title: PromptBuilder 最小落地实施计划
order: 520
---

# PromptBuilder 最小落地实施计划

> 目标：先为 `summary.weben` 场景打通一条最小可用闭环：
> 1. `summary` 改成布局路由
> 2. 落地 PromptBuilder 单栏工作台壳
> 3. 打通 prompt 变量解析与预览/提交链路
> 4. 在 `summary.weben` 页面完成预览 → 生成 → 解析展示
>
> 本计划优先追求**稳定闭环**，不追求一次性实现全部高级能力。

---

## 1. 范围与原则

### 1.1 本轮目标

本轮只实现以下能力：

- `summary` 路由改为布局路由，并增加子页面宿主
- PromptBuilder 最小工作台：
  - `编辑器`
  - `预览`
  - `LLM 输出`
  - `组件渲染`
- Tab 切换时内容区**保活**，不卸载隐藏 pane
- Prompt 变量异步解析与缓存
- `buildPrompt(..., { mode: "preview" | "submit" })`
- `summary.weben` 最小信息源接入（先只做字幕）
- `llmChat()` 流式生成接入
- LLM 输出 JSON 解析后渲染基础结果

### 1.2 本轮不做

以下内容明确后移：

- Monaco
- 浏览器端执行用户 JS / `AsyncFunction`
- 复杂 DSL / AST 模板语言
- slot widget 的完整富交互形态
- 变量自动补全状态徽标的完整体验
- PromptBuilder 双栏 split 布局正式版
- 完整模板管理与后端持久化
- “保存到 Weben DB” 能力（如开发顺利可后续补）

### 1.3 实施原则

- 先完成最小闭环，再追加增强能力
- 优先复用现有 `llmChat()`、`useAppFetch()`、`useWorkspaceContext()`
- PromptBuilder 只负责工作台与 prompt 构建，不耦合具体业务结果结构
- `summary.weben` 只做 PromptBuilder 的首个业务接入页

---

## 2. Phase 1：重构 summary 路由结构

### 2.1 目标

把当前 `material.$materialId.summary.tsx` 从叶子页改为布局路由。

### 2.2 修改文件

- `fredica-webui/app/routes/material.$materialId.summary.tsx`
- `fredica-webui/app/routes/material.$materialId.summary._index.tsx`（新建）
- `fredica-webui/app/routes/material.$materialId.summary.weben.tsx`（新建）

### 2.3 实施内容

- `summary.tsx` 提供子导航与 `<Outlet />`
- 现有 summary mock 内容迁移到 `_index.tsx`
- 新增 `summary.weben.tsx` 页面骨架
- 子导航至少包含：
  - 概览
  - Weben 知识提取

### 2.4 复用点

- `fredica-webui/app/routes/material.$materialId.tsx`
  - 复用 `useWorkspaceContext()` 获取 material 与刷新能力
- 现有工作区导航样式

### 2.5 验证

- `/material/:id/summary` 正常进入 `_index`
- `/material/:id/summary/weben` 正常进入新页面
- 子导航切换正常

---

## 3. Phase 2：落地 PromptBuilder 最小工作台壳

### 3.1 新增文件

- `fredica-webui/app/components/prompt-builder/PromptBuilder.tsx`
- `fredica-webui/app/components/prompt-builder/PromptWorkbenchTabs.tsx`
- `fredica-webui/app/components/prompt-builder/PromptPaneShell.tsx`
- `fredica-webui/app/components/prompt-builder/PromptPreviewPane.tsx`
- `fredica-webui/app/components/prompt-builder/PromptStreamPane.tsx`
- `fredica-webui/app/components/prompt-builder/promptBuilderTypes.ts`

### 3.2 实施内容

- 先做**单栏 tabbar** 工作台
- 四个 pane：
  - editor
  - preview
  - stream
  - render
- 隐藏 pane 只做 `display: none` / `hidden`，不卸载
- `PromptBuilder` 内置 editor + preview
- `streamPane` / `renderPane` 由业务页注入
- 当 render 尚无内容时，可禁用对应 tab

### 3.3 参考实现

- `fredica-webui/app/components/material-library/MaterialActionModal.tsx`
  - 参考其 tab 切换但保留内容的模式

### 3.4 验证

- 切换 tab 后编辑器内容不丢失
- 切换 tab 后预览/流式输出不丢失
- 基础 a11y：`tablist` / `tab` / `tabpanel`

---

## 4. Phase 3：实现 PromptBuilder 变量解析基础设施

### 4.1 新增文件

- `fredica-webui/app/util/prompt-builder/types.ts`
- `fredica-webui/app/util/prompt-builder/buildPrompt.ts`
- `fredica-webui/app/util/prompt-builder/createVariableResolver.ts`
- `fredica-webui/app/util/prompt-builder/VariableResolverCache.ts`

### 4.2 最小类型

```ts
export interface VariableMeta {
  key: string
  label: string
  description: string
  kind: "text" | "slot"
  required?: boolean
}

export interface VariableResolution {
  kind: "text" | "slot"
  status: "ok" | "unavailable" | "unimplemented"
  value?: string
  preview?: string
  charCount?: number
  unavailableReason?: string
}

export interface BuildPromptResult {
  text: string
  charCount: number
  blocked: boolean
  warnings: Array<{ key: string; reason: string }>
}
```

### 4.3 实施内容

- 定义 `PromptResolver -> VariableResolver` 适配层
- 实现 `VariableResolverCache`
  - TTL
  - inflight dedup
  - invalidate
- 实现 `buildPrompt()`：
  - `preview`：保留不可用变量提示
  - `submit`：warnings 存在时 `blocked = true`
- 不做输入中实时预览；只在点击“预览”或“生成”时构建

### 4.4 第一阶段变量

- `material.title`
- `material.duration`
- `subtitle`
- `weben_schema_hint`

### 4.5 复用点

- `fredica-webui/app/routes/material.$materialId.tsx`
  - `material.*` 变量来源
- `fredica-webui/app/util/weben.ts`
  - Weben schema 说明来源

### 4.6 验证

- preview 模式可显示缺失变量提示
- submit 模式在关键变量不可用时阻止发送
- 相同 key 并发解析仅触发一次实际请求

---

## 5. Phase 4：接入 summary.weben 最小业务闭环

### 5.1 修改/新增文件

- `fredica-webui/app/routes/material.$materialId.summary.weben.tsx`
- `fredica-webui/app/util/materialWebenApi.ts`（新建）

### 5.2 页面状态

页面维护最小状态：

- `template`
- `previewResult`
- `previewLoading`
- `selectedModelId`
- `streamText`
- `streamError`
- `parsedResult`
- `stage`

建议状态流：

```txt
editing -> previewed -> generating -> parsed
                     \-> parse_error
```

### 5.3 工作流

1. 点击“预览”
   - `buildPrompt(..., { mode: "preview" })`
2. 点击“生成”
   - `buildPrompt(..., { mode: "submit" })`
3. 若 `blocked = false`
   - 调用 `llmChat()`
4. 累积 SSE 原始文本到 `streamText`
5. 流结束后尝试解析 JSON
6. `renderPane` 显示解析结果或解析失败信息

### 5.4 最小信息源

- 先只接字幕
- 若无字幕：
  - preview 中显示 warning
  - generate 被阻止，不向 LLM 发送无效 prompt

### 5.5 复用点

- `fredica-webui/app/util/llm.ts`
  - 复用 `llmChat()`
- `fredica-webui/app/util/app_fetch.ts`
  - 统一 API 请求
- `fredica-webui/app/util/weben.ts`
  - 复用 Weben 类型/枚举/辅助函数

### 5.6 验证

- 有字幕素材时可完成预览 → 生成 → 解析展示
- 无字幕素材时生成被阻止
- LLM 输出解析失败时仍保留原始输出文本

---

## 6. Phase 5：视进展决定是否追加保存能力

### 6.1 追加前提

仅在“预览 + 生成 + 解析预览”已稳定后，才考虑追加保存。

### 6.2 可能涉及文件

- `fredica-webui/app/util/materialWebenApi.ts`
- `shared/.../routes/WebenConceptBatchImportRoute.kt`
- `shared/.../routes/all_routes.kt`

### 6.3 原则

- 保存逻辑独立封装
- 不把保存能力绑死在 PromptBuilder 基础设施里
- 若后端尚未准备好，本轮可以不做

---

## 7. 关键复用文件

- `fredica-webui/app/routes/material.$materialId.tsx`
  - `useWorkspaceContext()`
- `fredica-webui/app/util/llm.ts`
  - `llmChat()`
- `fredica-webui/app/util/app_fetch.ts`
  - `useAppFetch`
- `fredica-webui/app/util/weben.ts`
  - schema/类型辅助
- `fredica-webui/app/components/material-library/MaterialActionModal.tsx`
  - tab 保活切换参考

---

## 8. 测试与验证

### 8.1 自动化测试

优先补以下测试：

- `buildPrompt`
- `createVariableResolver`
- `VariableResolverCache`
- PromptBuilder tab 切换与 pane 保活

若已有前端测试命令，直接运行对应 vitest。

### 8.2 手工验证

1. 打开 `/material/:id/summary`
   - 确认 summary 布局和 `_index` 正常
2. 打开 `/material/:id/summary/weben`
   - 确认页面骨架正常
3. 对有字幕素材：
   - 预览可展开 prompt
   - 生成可看到流式输出
   - 结束后可看到解析结果
4. 对无字幕素材：
   - 预览可看到 warning
   - 生成被阻止
5. 在 editor / preview / stream / render 间切换
   - 内容不丢失

---

## 9. 建议开发顺序

```txt
1. 写入本实施计划文档
2. 重构 summary 路由结构
3. 落地 PromptBuilder 最小工作台壳
4. 实现变量解析与 buildPrompt
5. 接入 summary.weben 最小闭环
6. 补测试与手工验证
7. 视进展决定是否追加保存能力
```

---

## 10. 关联文档

- [PromptBuilder 设计](./prompt-builder-design.md)
- [Summary × Weben 分析集成计划（v2）](./summary-weben-integration.md)
