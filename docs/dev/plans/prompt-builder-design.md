---
title: Prompt 构建器 & 模板管理 — 设计预研
order: 511
---

# Prompt 构建器 & 模板管理 — 设计预研

> 本文档是 `summary-weben-integration.md` 的前置设计文档，专注于两个基础问题：
> 1. **Prompt 中的变量如何表达与解析**（变量系统 DSL）
> 2. **Prompt 模板如何统一管理**（模板存储与 Schema 兼容性）
>
> 这两个问题解决后，weben 分析页的 Prompt 构建器才能有清晰的实现路径。

---

## 1. 问题拆解

一个"Prompt 构建器"要做的事情本质上是：

```
[用户定义的模板文本]
      +
[运行时从 App 各处异步拉取的数据]
      ↓
[最终拼合的完整 Prompt 字符串]
      ↓
[发送给 LLM]
```

难点在于：
- 数据来源是**异步的**（需要调 API）
- 数据来源的种类会**随 App 版本增长**（字幕 → 说话人 → 场景 → 图像 …）
- 模板本身需要**版本化**，以兼容后续 Schema 变更
- UI 要足够**直觉友好**，同时对高阶用户保留灵活性

---

## 2. 变量系统设计

### 2.1 用户侧表达式 vs 内部解析 API

用户在编辑器里看到的语法，和内部数据拉取的 API 可以是两层：

```
用户层（模板文本，simple 模式）   内部层（解析器调用）
────────────────────────────    ──────────────────────────────────
${subtitle}              →      resolver.subtitle()
${subtitle.zh}           →      resolver.subtitle({ lang: "zh" })
${speakers}              →      resolver.speakers()
${material.title}        →      resolver.material.title
```

用户不需要写 `await`，内部解析器统一负责异步拉取和错误处理。

### 2.2 内部解析器接口（`PromptResolver`）

设计为一个**按素材实例化**的对象，所有数据拉取方法返回 `Promise<string>`：

```ts
interface PromptResolver {
    // 基础素材信息
    material: {
        title: string;           // 同步，来自 workspace context
        duration: string;        // 同步，格式化时长
        sourceId: string;        // 同步
    };

    // 字幕全文（异步）
    subtitle(options?: { lang?: string; format?: "plain" | "timestamped" }): Promise<string>;

    // 以下方法后续随功能迭代实现
    speakers(options?: { format?: "summary" | "full" }): Promise<string>;    // 说话人信息
    scenes(options?: { format?: "list" | "timeline" }): Promise<string>;     // 场景跳转
    ocr(options?: { page?: number }): Promise<string>;                           // 图像识别
    videoContent(): Promise<string>;                                             // 视频内容识别
}
```

**关键约定**：
- `PromptResolver` **只负责返回有效文本**；遇到不可用状态时，**抛异常**，不返回带错误文案的字符串
- 数据不存在（如未提取字幕）时抛出普通 `Error("字幕尚未提取")`
- 功能未实现时抛出 `NotImplementedError`（或等价错误类型）
- 上层的 `createVariableResolver()` 负责把异常翻译为 `status: "unavailable" | "unimplemented"`
- 解析器在组件初始化时工厂创建：`createPromptResolver(material, apiFetch)`

这样可以保证：
- `PromptResolver` 的返回值始终可直接用于拼接最终 Prompt
- 错误状态不会混入最终 Prompt 文本
- UI 层可以准确区分"不可用"与"未实现"

### 2.3 模板语法方案对比

#### 方案 A：简单 `${varName}` （无参数）

```
视频标题：${material.title}
字幕内容：${subtitle}
说话人：${speakers}
```

- ✅ 易读易写，视觉上接近 JS 模板字面量
- ✅ 实现简单（正则替换）
- ❌ 无法传参（如"只要中文字幕"）
- ❌ 不支持条件判断

#### 方案 B：带参数的点号路径（`${varName.option}`）

```
${subtitle.zh.plain}
${subtitle.zh.timestamped}
${speakers.summary}
```

- ✅ 支持参数，仍然简洁
- ✅ 无需 JS 运行时
- ✅ 与 `resolver.subtitle({ lang: "zh" })` 的映射关系清晰
- ❌ 多级参数时路径较长

#### 方案 C：专家模式（编辑体验增强，而非直接执行用户 JS）

这里需要重构思路：**专家模式不等于在前端执行用户写的 JS**。

Phase 1 中，专家模式只解决两个问题：

1. **更强的编辑体验**
   - JS/TS 风格高亮
   - 更丰富的自动补全
   - 更清晰的多行模板结构编辑

2. **更强的表达能力预留**
   - 为后续条件块、片段组合、模板 AST 预留空间
   - 但**不在浏览器中直接执行用户提供的任意 JS**

因此，专家模式在 Phase 1 的定位应是：

```text
simple 模式：
  使用 `${subtitle.zh}` 这类点号路径变量

expert 模式：
  仍然编辑"模板"，但提供更强编辑体验；
  不允许直接写可执行 JS，不使用 AsyncFunction 运行用户代码
```

可以把它理解为：
- **simple**：面向一般用户的模板文本模式
- **expert**：面向高级用户的“结构化模板编辑模式”

#### 为什么不直接执行 JS

- 浏览器内 `AsyncFunction` 不是安全沙盒
- 即便只注入 `resolver/material`，仍存在逃逸与越权风险
- 安全边界太重，不应在 PromptBuilder Phase 1 引入

#### 专家模式的可行重构方向

后续如果确实需要更强表达能力，建议沿以下方向演进，而不是直接执行任意 JS：

**方向 A：受限模板 AST / 内置控制结构**

例如只支持少量受限语法：

```text
@if subtitle.zh
== 中文字幕 ==
${subtitle.zh}
@endif

@if speakers.summary
== 说话人摘要 ==
${speakers.summary}
@endif
```

优势：
- 可控
- 易做静态分析
- 易做迁移与兼容性检查

**方向 B：片段组合（Fragments）**

用户不写逻辑，而是启用/禁用预定义片段：

```text
[✓] 基础说明片段
[✓] 字幕片段（中文）
[ ] 说话人片段
[✓] Schema 说明片段
```

优势：
- 对高级用户足够灵活
- 不引入执行环境

**方向 C：服务端受限执行（远期）**

如果未来确实需要可编程模板能力，应考虑：
- 在受控环境执行
- 使用专门 DSL / AST，而非用户任意 JS
- 明确资源访问白名单与超时限制

#### Phase 1 结论

- `PromptTemplate.mode` 先保留 `"simple" | "expert"`
- `expert` 仅表示 **编辑体验增强模式**，不表示执行任意 JS
- 文档内不再采用 `AsyncFunction` 作为推荐实现路径

#### 方案 D：可视化块构建器（Block-based，无代码）

用户不直接编辑文本，而是**拖拽/添加块**：

```
[素材标题块]
[字幕块] ──── 语言: 中文  格式: 纯文本
[自定义文本块] ──── "请根据以上内容输出 JSON..."
[Schema 说明块] ──── 自动附加 weben schema 约束
```

- ✅ 对非技术用户友好
- ✅ 每个块的元数据清晰（便于版本迁移）
- ❌ 开发成本最高
- ❌ 灵活性受限于块类型枚举

### 2.4 推荐方案：方案 B + 重构后的专家模式

**基础层（方案 B）**：对所有用户默认开放

```
${material.title}
${subtitle}
${subtitle.zh}
${speakers.summary}
${weben_schema_hint}
```

解析器将 `${varName.param1.param2}` 拆分为变量名 + 参数数组，dispatch 到对应
`PromptResolver` 方法。

**专家层（expert，可选）**：

- 不执行用户提供的任意 JS
- 仅提供更强编辑体验与未来扩展点
- 远期如需更强逻辑能力，优先考虑受限 DSL / AST，而非 `AsyncFunction`

因此两层共享的仍然是同一套 `PromptResolver` / `VariableResolver` 体系；
区别只在于编辑体验和未来的受限语法扩展，而不是执行模型不同。

---

## 3. 模板管理

### 3.1 模板的生命周期

```
内置模板（代码中写死）
    ↓ 用户编辑
草稿（localStorage，会话级）
    ↓ 用户保存
已保存模板（后端 DB 或 localStorage 持久化）
    ↓ 分配 schema_version
版本化模板（可迁移）
```

### 3.2 存储方案

| 方案 | 存储位置 | 跨设备 | 离线 | 复杂度 |
|------|----------|--------|------|--------|
| A: 纯 localStorage | 客户端 | ❌ | ✅ | 低 |
| B: 后端 DB（新表） | Kotlin/SQLite | ✅ | ✅（本地桌面） | 中 |
| C: 代码内置 + 用户覆盖 | 混合 | ❌ | ✅ | 低 |

推荐**方案 C 起步，方案 B 演进**：
- 短期：内置模板列表 + localStorage 用户草稿/覆盖
- 中期：新建 `prompt_template` 表，CRUD 路由

### 3.3 模板数据结构

```ts
interface PromptTemplate {
    id: string;                    // UUID
    name: string;                  // 展示名，如"Weben 知识提取（默认）"
    description?: string;
    category: PromptCategory;      // 见下
    schemaTarget?: string;         // 如 "weben_v1"，用于兼容性检查
    mode: "simple" | "expert";   // 默认模板模式 / 专家编辑模式
    body: string;                  // 模板文本
    variables: VariableMeta[];     // 声明了哪些变量（用于 UI 展示可用性）
    builtIn: boolean;              // true = 代码内置，不可删除
    createdAt: number;
    updatedAt: number;
}

type PromptCategory =
    | "weben_extract"     // weben 知识提取
    | "summary"           // 内容摘要
    | "translation"       // 翻译
    | "qa"                // 问答生成
    | "custom";           // 用户自定义

interface VariableMeta {
    key: string;               // e.g. "subtitle.zh"
    label: string;             // e.g. "字幕（中文）"
    description: string;
    kind: "text" | "slot";
    required?: boolean;
}
```

**localStorage key 规则（Phase 1）**：

```ts
prompt_template_draft:${category}:${templateId}
prompt_builder_layout:${category}
prompt_builder_last_tab:${category}
```

这样可避免不同业务场景（如 `weben_extract` / `summary`）之间互相覆盖草稿与 UI 状态。

### 3.4 Schema 兼容性

**问题**：Weben schema 会随版本迭代变更（新增 concept_type、修改 predicate 枚举）。
已保存的模板中硬编码了 schema 枚举值，版本升级后可能输出非法字段。

**方案**：

1. **Schema 注入分离**：模板 body 里不硬编码枚举值，而是使用特殊变量：

   ```
   ${weben_schema_hint}
   ```

   App 运行时把当前版本的 schema 约束文本注入进去。
   模板本身不感知具体枚举，只知道"此处放 schema 说明"。

2. **`schemaTarget` 字段**：模板声明它针对的 schema 版本。
   若 App 当前 schema 版本 > 模板的 `schemaTarget`，在 UI 展示警告：
   "此模板基于旧版 Schema，输出结果可能包含已废弃字段，建议更新模板"。

3. **Schema 版本注册表**（代码内维护）：

   ```ts
   const WEBEN_SCHEMA_VERSIONS = {
       "weben_v1": {
           conceptTypes: [...],    // 11 种
           predicates: [...],      // 7 种
           hint: "...",            // 注入到 ${weben_schema_hint} 的文本
       },
       // 未来 "weben_v2": { ... }
   };
   ```

---

## 4. Prompt 构建器 UI 设计

### 4.1 设计目标

第 4 章只回答三个问题：

1. **页面如何布局**：既省空间，又能查看编辑/预览/输出
2. **编辑器如何实现**：基于 `react-codemirror` 提供模板模式与对话模式
3. **各区域如何协作**：Tab 切换不销毁、可切换为分栏视图

核心原则：

- **默认使用 Tabbar** 节省页面空间，而不是同时堆出四块区域
- **切换 Tab 时不销毁内容区**，避免丢失滚动位置、编辑状态、流式输出状态
- **桌面端支持分栏视图**，用于同时对照编辑器与预览/输出
- **PromptBuilder 只负责通用工作台能力**，具体业务结果渲染由父页面决定

---

### 4.2 视图模型

整个页面抽象为四个 View：

| View | 职责 | 何时可用 |
|------|------|----------|
| `editor` | Prompt 模板编辑 | 始终可用 |
| `preview` | 变量替换后的最终 Prompt | 用户点预览后可用 |
| `stream` | LLM 原始流式输出 | 开始生成后可用 |
| `render` | 解析后的可视化结果 | 解析成功后可用 |

这四个 View 是**逻辑分区**，不是要求始终同时出现在页面上。

---

### 4.3 默认布局：单栏 Tabbar

默认采用单栏 + 顶部 Tabbar：

```
┌──────────────────────────────────────────────────────┐
│ [编辑器] [预览] [LLM输出] [组件渲染]   [分栏视图 ▢]   │
├──────────────────────────────────────────────────────┤
│                                                      │
│                 当前激活的 View 内容                  │
│                                                      │
└──────────────────────────────────────────────────────┘
```

优点：
- 页面高度稳定，不会被四块内容同时撑爆
- 用户能聚焦当前任务
- 移动端天然适配

#### Tab 切换不销毁

切换 Tab 时，只隐藏，不卸载：

```tsx
<div className={activeTab === "editor" ? "block" : "hidden"}>
    <EditorViewPane />
</div>
<div className={activeTab === "preview" ? "block" : "hidden"}>
    <PreviewViewPane />
</div>
<div className={activeTab === "stream" ? "block" : "hidden"}>
    <StreamViewPane />
</div>
<div className={activeTab === "render" ? "block" : "hidden"}>
    <RenderViewPane />
</div>
```

必须保留的状态：
- CodeMirror 光标位置 / undo stack
- 预览区滚动位置
- 流式输出文本与滚动位置
- 渲染区局部展开/折叠状态

---

### 4.4 分栏视图

桌面端支持切换到双栏视图，用于同时编辑和观察结果。

#### 双栏规则

- 左栏固定为 `editor`
- 右栏在 `preview / stream / render` 间切换
- 不采用四宫格，避免信息密度过高

```
┌─────────────────────────────────────────────────────────────────┐
│ 左栏：编辑器                │ 右栏 Tabbar                          │
│                            │ [预览] [LLM输出] [组件渲染]         │
├────────────────────────────┼──────────────────────────────────────┤
│                            │                                      │
│  PromptEditor              │  当前激活右栏内容                    │
│                            │                                      │
└────────────────────────────┴──────────────────────────────────────┘
```

#### 响应式策略

- **移动端 `<768px`**：只允许单栏 Tabbar
- **桌面端 `>=768px`**：允许双栏
- **超宽屏 `>=1440px`**：仍保持双栏，不再增加额外栏位

#### 尺寸建议

- 左栏编辑器：`minmax(420px, 1.2fr)`
- 右栏视图：`minmax(360px, 1fr)`
- 外层 grid：`grid-cols-[minmax(420px,1.2fr)_minmax(360px,1fr)]`

#### 状态模型

```ts
type MainTab = "editor" | "preview" | "stream" | "render";
type SideTab = "preview" | "stream" | "render";

interface PromptWorkbenchState {
    layout: "single" | "split";
    activeMainTab: MainTab;
    activeSideTab: SideTab;
    lastAuxTab: SideTab;
}
```

切换规则：
- 单栏 → 双栏：左栏固定 editor，右栏优先显示 `lastAuxTab`
- 双栏 → 单栏：回到最近活跃的 tab
- 若 `lastAuxTab` 当前不可用，则按 `render → stream → preview` 的反向可用性回退到最近可用项
- 生成中切换布局时，右栏优先保持 `stream`
- `render` 不可用时，不允许激活；自动回退到 `stream` 或 `preview`

---

### 4.5 组件边界

#### PromptBuilder 的职责

`<PromptBuilder>` 是通用工作台容器，负责：

- Tabbar / 分栏布局
- PromptEditor 挂载
- **内置 PromptPreviewPane**（预览属于通用基础设施）
- LLM 输出区 / 结果渲染区的容器编排

更实际的 API：

```tsx
<PromptBuilder
    mode="template"
    value={template}
    onChange={setTemplate}
    variableCache={variableCache}
    variables={PROMPT_VARIABLES}
    slotRegistry={slotRegistry}
    previewResult={previewResult}
    previewLoading={previewLoading}
    onPreview={handlePreview}
    streamPane={<LlmStreamPane text={streamText} error={streamError} />}
    renderPane={<ResultRenderPane result={parsedResult} />}
/>
```

#### 父页面的职责

父页面负责具体业务：
- 调 `llmChat()` 发起请求
- 保存流式文本
- 解析 LLM 输出
- 决定 `renderPane` 的具体渲染组件
- 维护 `previewResult / parsedResult / streamText` 等业务状态

也就是说：
- **editor / preview 是通用基础设施**
- **stream / render 是业务注入内容**

---

### 4.6 PromptEditor：基于 `react-codemirror`

#### 框架选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| `<textarea>` | 最简单 | 无 widget / 无补全 / 无高亮 | ❌ |
| Monaco | IDE 能力强 | 包体积大，聊天输入场景过重 | ❌ |
| Tiptap / ProseMirror | 富文本强 | 不适合文本模板 + 变量占位符模型 | ❌ |
| **`@uiw/react-codemirror`** | React 集成自然，支持 CodeMirror 6 全扩展能力 | 需要自己组装扩展 | ✅ |

结论：统一基于 **`@uiw/react-codemirror` + CodeMirror 6 extensions**。

#### 两种工作模式

同一个 `<PromptEditor>` 组件支持两种用途：

| 维度 | `mode="template"` | `mode="chat"` |
|------|------------------|---------------|
| 用途 | AI 分析提示词编辑 | 多轮对话输入框 |
| Enter | 换行 | 发送 |
| Shift+Enter | 换行 | 换行 |
| Slot Widget | ✅ | ❌ |
| 变量补全 | ✅ | ✅ |
| 历史消息导航 | ❌ | ✅ |
| 专家编辑模式 | ✅ | ❌ |

**边界说明**：
- `PromptEditor` 同时支持 `template/chat` 两种输入模式
- `PromptBuilder` 在 Phase 1 **主要服务于 template/workbench 场景**
- chat 页面短期优先复用 `PromptEditor`，不强制复用完整的 PromptBuilder 工作台
- 若未来聊天页确实需要 tab/workbench 能力，再抽象轻量版 `ChatWorkbench`

#### 组件 API

```tsx
interface PromptEditorProps {
    value: string;
    onChange: (value: string) => void;

    mode?: "template" | "chat";
    expertMode?: boolean;

    variableCache?: VariableResolverCache;
    variables?: VariableMeta[];
    slotRegistry?: Record<string, PromptSlotBlock>;

    onSubmit?: (value: string) => void;
    history?: string[];

    placeholder?: string;
    minHeight?: string;
    maxHeight?: string;
}
```

#### 扩展组装原则

```ts
function buildExtensions(props: PromptEditorProps): Extension[] {
    const extensions: Extension[] = [
        EditorView.lineWrapping,
        variableHighlightExtension(new Set(Object.keys(props.slotRegistry ?? {}))),
    ];

    if (props.variables && props.variableCache) {
        extensions.push(variableCompletionExtension(props.variables, props.variableCache));

        if (props.mode === "template") {
            extensions.push(slotWidgetExtension(props.variableCache));
        }
    }

    if (props.mode === "template" && props.expertMode) {
        // Phase 1: 只增强编辑体验，不执行用户 JS
        extensions.push(expertEditingExtension());
    }

    if (props.mode === "chat") {
        extensions.push(chatKeymapExtension(props));
    }

    return extensions;
}
```

这里要特别注意：
- `VariableResolverCache` 的 owner 是外层 hook / 父组件，不在 `buildExtensions()` 内部临时 new
- 否则每次 render 都会丢失缓存与 inflight dedup 状态
- `expertEditingExtension()` 仅表示更强的编辑体验（高亮/补全/片段辅助），不执行用户代码

---

### 4.7 编辑器内交互

#### 变量补全

输入 `${` 时弹出：

```
${material.title}
${subtitle}
${subtitle.zh}
${speakers.summary}
${weben_schema_hint}
```

补全项带状态：
- ✅ 可直接使用
- ⚠ 资源缺失，需要先提取
- 🔜 尚未实现

#### Slot Widget

在 `template` 模式下，已注册 slot 变量会在编辑器中替换为可视化块：

```
请根据以下内容输出结构化结果：

┌─ Schema 说明块 ─────────────────────────┐
│ 当前版本：weben_v1                      │
│ 概念类型：术语 / 理论 / 算法 / …         │
│ 关系谓词：包含 / 依赖 / 用于 / …         │
└─────────────────────────────────────────┘
```

slot 仍保留底层文本占位符 `${weben_schema_hint}`，只是显示层替换成 widget。

**Phase 1 交互约定**：
- slot 在文档模型中本质仍是 `${var}` 文本
- 删除 slot 时按整个变量整体删除
- 复制/粘贴时复制的是原始 `${var}` 文本，而不是组件快照
- 不支持直接编辑 slot 的变量名文本；只能通过插入/删除变量完成

#### Chat 模式快捷键

- `Enter`：提交
- `Shift+Enter`：换行
- `ArrowUp/ArrowDown`：历史消息导航

---

### 4.8 三个辅助视图

#### Preview（最终 Prompt）

- 用户点击 **[预览]** 后生成
- 内容来自 `buildPrompt(template, resolver)`
- 展示完整最终 prompt、字符数、warnings
- 在单栏模式下作为一个 tab
- 在双栏模式下作为右栏 tab

#### Stream（LLM 原始输出）

- 父页面调用 `llmChat()` 后写入
- 保持原始流式文本，不参与 PromptBuilder 内部业务判断
- 生成完成后不自动清空，便于回看

#### Render（组件渲染）

- 父页面在解析 LLM 输出成功后提供 `renderPane`
- PromptBuilder 不关心渲染什么，只负责把它挂到 tab / split 视图里
- 这样以后既可渲染 weben 组件，也可渲染摘要卡、标签树、时间轴等

---

### 4.10 组件拆分草图

为了让这一设计可以直接进入实现，建议组件拆分如下：

#### 目录建议

```text
app/components/prompt-builder/
├── PromptBuilder.tsx              // 总入口，编排单栏/双栏布局
├── PromptWorkbenchTabs.tsx        // 顶部 tabbar
├── PromptSplitLayout.tsx          // 双栏布局壳
├── PromptEditorPane.tsx           // 编辑器面板（标题栏 + toolbar + PromptEditor）
├── PromptPreviewPane.tsx          // 最终 prompt 预览（内置基础设施）
├── PromptStreamPane.tsx           // LLM 原始输出显示
├── PromptPaneShell.tsx            // 通用 panel 壳（title / toolbar / body）
├── PromptEditor.tsx               // react-codemirror 封装
├── promptEditorExtensions.ts      // CM6 扩展组装
├── promptEditorKeymaps.ts         // chat 模式快捷键
└── promptBuilderTypes.ts          // UI 层类型定义
```

如果后续 slot 变多，再拆：

```text
app/components/prompt-builder/slots/
├── WebenSchemaSlot.tsx
└── ...
```

#### `PromptBuilder.tsx`

职责：
- 管理 `layout`（single / split）
- 管理 `activeMainTab / activeSideTab / lastAuxTab`
- 内置 `PromptPreviewPane`
- 接收外部注入的 `streamPane / renderPane`
- 决定哪些 pane 可点击（例如 render 在未解析成功前禁用）

```tsx
interface PromptBuilderProps {
    value: string;
    onChange: (value: string) => void;

    mode?: "template";
    expertMode?: boolean;

    variableCache?: VariableResolverCache;
    variables?: VariableMeta[];
    slotRegistry?: Record<string, PromptSlotBlock>;

    previewResult?: BuildPromptResult | null;
    previewLoading?: boolean;
    onPreview?: () => void;

    streamPane?: React.ReactNode;
    renderPane?: React.ReactNode;

    canPreview?: boolean;
    canStream?: boolean;
    canRender?: boolean;
}

function PromptBuilder(props: PromptBuilderProps) {
    // 1. 维护布局状态
    // 2. 渲染 single/split
    // 3. 内置 editor/preview，挂载外部 stream/render
}
```

#### `PromptWorkbenchTabs.tsx`

职责：
- 纯展示型 tabbar
- 支持 disabled / badge / icon
- 不关心 pane 内容

```tsx
interface WorkbenchTabItem {
    key: "editor" | "preview" | "stream" | "render";
    label: string;
    disabled?: boolean;
    badge?: string | number;
}

interface PromptWorkbenchTabsProps {
    items: WorkbenchTabItem[];
    activeKey: WorkbenchTabItem["key"];
    onChange: (key: WorkbenchTabItem["key"]) => void;
    rightActions?: React.ReactNode; // 比如“分栏视图”开关
}
```

#### `PromptSplitLayout.tsx`

职责：
- 只负责双栏布局壳，不涉及业务
- 左栏固定插槽 `left`
- 右栏 `tabs + content`

```tsx
interface PromptSplitLayoutProps {
    left: React.ReactNode;
    rightTabs: React.ReactNode;
    rightContent: React.ReactNode;
}
```

#### `PromptPaneShell.tsx`

统一所有 pane 的视觉外壳，避免 preview/stream/render 各写一套 header 样式：

```tsx
interface PromptPaneShellProps {
    title: string;
    toolbar?: React.ReactNode;
    children: React.ReactNode;
    className?: string;
}
```

所有 pane 都套这一层：
- `PromptEditorPane`
- `PromptPreviewPane`
- `PromptStreamPane`
- 父页面传入的 `renderPane` 也建议自行套 `PromptPaneShell`

#### `PromptEditorPane.tsx`

职责：
- 包一层标题栏
- 放置 toolbar（插入变量、预览按钮、模式切换）
- 内部挂 `<PromptEditor />`

```tsx
interface PromptEditorPaneProps extends PromptEditorProps {
    variables?: VariableMeta[];
    onInsertVariable?: (key: string) => void;
    onPreview?: () => void;
    extraToolbar?: React.ReactNode;
}
```

建议 toolbar 分为三块：
- 左：变量插入
- 中：模式切换（simple / js）
- 右：预览 / 生成

#### `PromptPreviewPane.tsx`

职责：
- 只负责展示 `buildPrompt()` 的结果
- 不参与变量解析逻辑（解析逻辑外置）

```tsx
interface PromptPreviewPaneProps {
    loading?: boolean;
    result?: BuildPromptResult | null;
    onRefresh?: () => void;
}
```

#### `PromptStreamPane.tsx`

职责：
- 原样展示流式文本
- 自动滚动到底部
- 可折叠 raw output

```tsx
interface PromptStreamPaneProps {
    text: string;
    loading?: boolean;
    error?: string | null;
    onRetry?: () => void;
}
```

#### `PromptEditor.tsx`

职责：
- 仅封装 `@uiw/react-codemirror`
- 接收 `extensions`
- 不关心 tab / pane / 业务状态

这是最底层、最可复用的组件。

---

### 4.11 组件装配示例

#### 单栏模式装配

```tsx
<PromptBuilder
    value={template}
    onChange={setTemplate}
    mode="template"
    variableCache={variableCache}
    variables={PROMPT_VARIABLES}
    slotRegistry={slotRegistry}
    previewResult={previewResult}
    previewLoading={previewLoading}
    onPreview={handlePreview}
    streamPane={
        <PromptStreamPane
            text={streamText}
            loading={generating}
            error={streamError}
            onRetry={handleGenerate}
        />
    }
    renderPane={<ResultRenderPane result={parsedResult} />}
    canPreview
    canStream={streamText.length > 0 || generating}
    canRender={parsedResult != null}
/>
```

#### chat 页面建议装配

chat 页面短期不强制复用完整的 `PromptBuilder`，优先只复用 `PromptEditor`：

```tsx
<PromptEditor
    mode="chat"
    value={input}
    onChange={setInput}
    onSubmit={handleSend}
    history={history}
    variableCache={variableCache}
    variables={CHAT_VARIABLES}
/>
```

如果未来聊天场景也需要 tab/workbench 能力，再单独抽象 `ChatWorkbench`。

---


### 4.12 状态流转与数据流

这一节补清楚：谁触发谁、谁负责写状态、哪个组件是纯展示。

#### 模板分析模式状态机

```ts
type PromptBuilderStage =
    | "editing"       // 正在编辑模板
    | "previewing"    // 正在计算最终 prompt
    | "previewed"     // 已得到最终 prompt
    | "generating"    // 正在请求 LLM，流式输出中
    | "generated"     // 流结束，尚未解析 / 或等待解析
    | "parsed"        // 已解析为结构化结果
    | "parse_error";  // LLM 输出无法解析
```

#### 事件流

```text
用户编辑模板
  → setTemplate(value)
  → stage 保持 editing

用户点击 [预览]
  → handlePreview()
  → buildPrompt(template, resolver, { mode: "preview" })
  → previewResult 写入
  → stage = previewed
  → UI 切到 preview tab（单栏）/ 右栏切到 preview（双栏）

用户点击 [生成]
  → 若没有 previewResult，先隐式 buildPrompt(template, resolver, { mode: "submit" })
  → 若存在阻止发送的 warnings，则停留在 preview 并提示用户
  → 调 llmChat()
  → stage = generating
  → streamText 累积追加
  → UI 切到 stream tab
  → 流结束后 stage = generated
  → 尝试 parseLlmOutput(fullText)
     → 成功：parsedResult 写入，stage = parsed
     → 失败：parseError 写入，stage = parse_error
```

#### 状态归属

| 状态 | 所属组件 | 说明 |
|------|----------|------|
| `template` | 父页面 / PromptBuilder | 编辑器文本本身 |
| `layout` / `activeTab` | PromptBuilder | 纯 UI 状态 |
| `previewResult` | 父页面 | 由 `buildPrompt()` 生成 |
| `streamText` | 父页面 | LLM 流式输出 |
| `parsedResult` | 父页面 | 业务解析结果 |
| `parseError` | 父页面 | 业务解析错误 |
| `editor selection` / `undo stack` | CodeMirror 内部 | 不提升到 React state |

**原则**：
- PromptBuilder 只维护 **布局状态**
- 与 LLM / 解析结果相关的状态由父页面维护
- PromptEditor 不感知 preview / stream / render 的业务状态

---

### 4.13 实际装配的数据流草图

#### template 分析页

```tsx
function SummaryWebenPage() {
    const [template, setTemplate] = useState(DEFAULT_TEMPLATE);
    const [stage, setStage] = useState<PromptBuilderStage>("editing");

    const [previewResult, setPreviewResult] = useState<BuildPromptResult | null>(null);
    const [streamText, setStreamText] = useState("");
    const [streamError, setStreamError] = useState<string | null>(null);
    const [parsedResult, setParsedResult] = useState<WebenLlmResult | null>(null);

    const variableCache = useMemo(
        () => new VariableResolverCache(createVariableResolver(promptResolver, slotRegistry)),
        [promptResolver, slotRegistry],
    );

    async function handlePreview() {
        setStage("previewing");
        const result = await buildPrompt(template, (key) => variableCache.resolve(key), { mode: "preview" });
        setPreviewResult(result);
        setStage("previewed");
    }

    async function handleGenerate() {
        const prompt = previewResult ?? await buildPrompt(template, (key) => variableCache.resolve(key), { mode: "submit" });
        if (!previewResult) setPreviewResult(prompt);
        if (prompt.blocked) {
            setStage("previewed");
            return;
        }

        setStreamText("");
        setStreamError(null);
        setParsedResult(null);
        setStage("generating");

        try {
            let fullText = "";
            for await (const chunk of llmChat({
                mode: "router",
                app_model_id: selectedModelId,
                message: prompt.text,
            })) {
                fullText += chunk;
                setStreamText(fullText);
            }

            setStage("generated");
            setParsedResult(parseWebenResult(fullText));
            setStage("parsed");
        } catch (e) {
            setStreamError(e instanceof Error ? e.message : String(e));
            setStage("parse_error");
        }
    }

    return (
        <PromptBuilder
            value={template}
            onChange={setTemplate}
            variableCache={variableCache}
            variables={PROMPT_VARIABLES}
            slotRegistry={slotRegistry}
            previewResult={previewResult}
            onPreview={handlePreview}
            streamPane={<PromptStreamPane text={streamText} error={streamError} onRetry={handleGenerate} />}
            renderPane={<WebenResultPane result={parsedResult} />}
            canPreview
            canStream={stage === "generating" || streamText.length > 0}
            canRender={parsedResult != null}
        />
    );
}
```

这里的关键点：
- PromptBuilder 本身不直接调用 `llmChat`
- PromptBuilder 内置 editor/preview，并接收外部 `streamPane / renderPane`
- 具体业务页面自行处理 preview / generate / parse

---

### 4.14 性能与缓存策略

#### 1. 变量状态预热与全文懒加载分离

- mount 时：只查变量 status，用于补全图标和 toolbar 状态
- 点击 [预览] 时：才去拉全文并构建最终 prompt
- **禁止**在每次输入后自动调用 `buildPrompt()` 进行实时预览
- 点击 [生成] 时，若 preview 已过期，可隐式重建一次 submit 版本的 prompt

#### 2. CodeMirror 扩展必须稳定

`extensions` 数组必须 `useMemo`，否则每次 render 都会重建编辑器内部状态：

```tsx
const extensions = useMemo(
    () => buildExtensions({
        mode, expertMode, variableCache, variables, slotRegistry,
        onSubmit, history,
    }),
    [mode, expertMode, variableCache, variables, slotRegistry, onSubmit, history],
);
```

`VariableResolverCache` 也必须由外层长期持有，不要在 `buildExtensions()` 里临时创建。
#### 3. slot widget 只处理可见区

`slotWidgetExtension` 和 `variableHighlightExtension` 只遍历 `view.visibleRanges`，避免长文档卡顿。

#### 4. stream pane 节流刷新

SSE chunk 很碎时，不要每个 chunk 都触发 React 重渲染。建议：
- 先写入 `ref` buffer
- 每 50~100ms 批量 `setStreamText`

```ts
const pendingRef = useRef("");
useEffect(() => {
    const id = window.setInterval(() => {
        if (!pendingRef.current) return;
        setStreamText(prev => prev + pendingRef.current);
        pendingRef.current = "";
    }, 80);
    return () => window.clearInterval(id);
}, []);
```

#### 5. 预览结果缓存

相同 `template + resolver cache version` 下，`buildPrompt()` 结果可复用。

简单做法：
- 父页面记录 `lastPreviewTemplate`
- 如果 template 未变化，点击 [生成] 时直接复用 `previewResult`

---

### 4.15 可访问性与交互细节

#### Tabbar 可访问性

- 外层：`role="tablist"`
- 每个 tab：`role="tab"`
- 内容区：`role="tabpanel"`
- 使用 `aria-selected` / `aria-controls` / `id` 关联

#### 键盘交互

- `Ctrl/Cmd + Enter`：template 模式下触发 [预览] 或 [生成]（由父页面配置）
- `Alt + 1/2/3/4`：切换到 editor / preview / stream / render tab
- `Esc`：关闭补全菜单 / 退出当前 slot 的内部浮层

#### 空状态文案

- preview 未生成：`尚未预览，点击“预览”生成最终 Prompt。`
- stream 未开始：`尚未开始生成。`
- render 未解析：`尚无可渲染结果。`
- render 解析失败：`输出无法解析为目标结构，请查看“LLM输出”。`

#### 错误展示原则

- 变量不可用：在 Preview 顶部集中告警，不在编辑器里频繁打断
- LLM 请求失败：展示在 Stream Pane 顶部，并保留已有输出
- 解析失败：Render Pane 不报红屏，只展示说明并引导回 Stream Pane

---

### 4.16 测试策略

#### 组件单测

1. `PromptWorkbenchTabs`
   - tab 切换回调正确
   - disabled tab 不可点击
   - badge 正常显示

2. `PromptBuilder`
   - single / split 布局切换
   - 切换 tab 时 pane 不卸载
   - `canRender=false` 时 render tab disabled

3. `PromptEditor`
   - template 模式有 slot widget
   - chat 模式 Enter 触发 `onSubmit`
   - Shift+Enter 插入换行

4. `PromptPreviewPane`
   - loading / result / warnings 三态正确

5. `PromptStreamPane`
   - 文本追加时自动滚到底
   - error 展示正确

#### hook / 工具函数测试

1. `buildPrompt()`
   - 文本变量替换正确
   - slot 变量走 `serialize()`
   - 不可用变量进入 warnings

2. `createVariableResolver()`
   - key 路由正确：`subtitle.zh` → `resolver.subtitle({ lang: "zh" })`
   - slot 变量优先
   - 异常被转为 `status="unavailable"`

3. `VariableResolverCache`
   - 缓存命中
   - inflight dedup
   - invalidate 后重新请求

#### 推荐测试阶段

- **Phase 1**：先测 `buildPrompt` / `createVariableResolver` / `VariableResolverCache`
- **Phase 2**：再测 `PromptEditor` chat/template 两模式
- **Phase 3**：最后测 `PromptBuilder` 的布局与 tab 保活

---

### 4.17 分阶段实施建议

#### Phase A：最小可用工作台

目标：先把工作台结构搭起来。

- `PromptBuilder`
- `PromptWorkbenchTabs`
- `PromptSplitLayout`
- `PromptPaneShell`
- `PromptPreviewPane`
- `PromptStreamPane`
- 单栏 / 双栏切换
- tab 保活

此阶段可先用普通 textarea 占位 `PromptEditor`，不做 slot。

#### Phase B：CodeMirror 编辑器

目标：完成通用输入组件。

- `PromptEditor`
- `variableHighlightExtension`
- `variableCompletionExtension`
- chat/template 两模式
- `chatKeymapExtension`

#### Phase C：变量解析与预览闭环

目标：跑通 Prompt 构建能力。

- `createVariableResolver`
- `VariableResolverCache`
- `buildPrompt`
- `PromptPreviewPane` 接实际数据

#### Phase D：slot widget

目标：实现富组件变量。

- `slotWidgetExtension`
- `WebenSchemaSlot`
- slot → serialize 闭环

#### Phase E：接入具体业务页

目标：接到 summary.weben / chat 页面中。

- summary.weben：template 模式 + preview/stream/render 全量接入
- chat：chat 模式 + conversation pane 接入

这样能保证：
- 先把工作台壳做对
- 再做编辑器能力
- 最后才接具体业务，不会一开始就耦合死

---

## 5. 变量解析接口设计

### 5.1 两层架构

编辑器与应用数据之间设计两层，职责隔离：

```
┌──────────────────────────────────────────────────────────┐
│  PromptEditor / CodeMirror                               │
│  只依赖 VariableResolver：async (key) => VariableResolution │
└──────────────────────┬───────────────────────────────────┘
                       │ createVariableResolver()（适配层）
┌──────────────────────┴───────────────────────────────────┐
│  PromptResolver（有类型的应用实现层）                     │
│  subtitle(opts?) · speakers(opts?) · material.title …    │
└──────────────────────────────────────────────────────────┘
```

**为什么不直接把 `PromptResolver` 传给编辑器**：

- 编辑器通过字符串 key（`"subtitle.zh"`）动态查询变量，而非调用固定方法
- `PromptResolver` 方法签名随功能迭代会增长，编辑器不应感知
- 适配层集中处理 `"subtitle.zh"` → `resolver.subtitle({ lang: "zh" })` 的路由逻辑，改路由只改一处

**为什么不用同步对象**：

- 大部分变量值（字幕内容、说话人信息）是异步 API 拉取，无法同步
- 同步对象要求调用方在传入前串行 await 所有数据，造成瀑布阻塞
- 异步函数允许编辑器按需懒加载：mount 时只查 status，点击预览时才拉全文

### 5.2 核心类型定义

```ts
// 编辑器侧唯一依赖的接口
type VariableResolver = (key: string) => Promise<VariableResolution>;

interface VariableResolution {
    kind: "text" | "slot";
    status: "ok" | "unavailable" | "unimplemented";

    // kind="text", status="ok"
    value?: string;               // 构建最终 prompt 用的完整文本
    preview?: string;             // Frame 2 展示用（可截断，不超过 500 字）
    charCount?: number;

    // status="unavailable"
    unavailableReason?: string;   // 如"字幕尚未提取"
    actionUrl?: string;           // 指引用户去处理的路由，如"/material/xxx/subtitle"

    // kind="slot", status="ok"
    SlotComponent?: React.FC<SlotEditorProps>;
    serialize?: () => string;     // 序列化为最终 prompt 文本
}

// 变量元数据，用于工具栏展示和自动补全候选列表
interface VariableMeta {
    key: string;               // e.g. "subtitle.zh"
    label: string;             // e.g. "字幕（中文）"
    description: string;       // 补全弹窗详细说明
    kind: "text" | "slot";
    required?: boolean;
}

// 应用级变量注册表（静态声明，不含运行时状态）
const PROMPT_VARIABLES: VariableMeta[] = [
    { key: "material.title",    label: "素材标题",    description: "视频标题（同步读取）",         kind: "text" },
    { key: "material.duration", label: "视频时长",    description: "格式化时长，如 1:23:45",        kind: "text" },
    { key: "subtitle",          label: "字幕全文",    description: "所有字幕轨合并的纯文本",        kind: "text" },
    { key: "subtitle.zh",       label: "字幕（中文）",description: "中文字幕轨纯文本",              kind: "text" },
    { key: "speakers.summary",  label: "说话人摘要",  description: "说话人信息摘要（需先提取）",    kind: "text" },
    { key: "scenes",            label: "场景信息",    description: "场景跳转信息（功能尚未实现）",  kind: "text" },
    { key: "weben_schema_hint", label: "Schema 说明", description: "Weben 知识图谱 Schema 约束说明",kind: "slot"  },
];
```

key 格式规则：

| 用户写的变量 | resolver 收到的 key |
|-------------|-------------------|
| `${subtitle}` | `"subtitle"` |
| `${subtitle.zh}` | `"subtitle.zh"` |
| `${subtitle.zh.timestamped}` | `"subtitle.zh.timestamped"` |
| `${material.title}` | `"material.title"` |
| `${weben_schema_hint}` | `"weben_schema_hint"` |

### 5.3 缓存层（`VariableResolverCache`）

resolver 会被多处重复调用（mount 时查 status、用户点预览时拉全文、发送前序列化），需要缓存避免重复请求。同时对同一 key 的并发调用做 dedup（请求合并）：

```ts
class VariableResolverCache {
    private cache = new Map<string, {
        resolution: VariableResolution;
        timestamp: number;
        ttl: number;
    }>();

    // 正在飞行中的请求，key → Promise，防止并发重复请求
    private inflight = new Map<string, Promise<VariableResolution>>();

    constructor(
        private inner: VariableResolver,
        private defaultTtlMs = 5 * 60 * 1000,  // 默认 5 分钟
    ) {}

    async resolve(key: string): Promise<VariableResolution> {
        // 1. 命中缓存且未过期
        const entry = this.cache.get(key);
        if (entry && Date.now() - entry.timestamp < entry.ttl) {
            return entry.resolution;
        }

        // 2. 已有飞行中请求，直接复用
        if (this.inflight.has(key)) {
            return this.inflight.get(key)!;
        }

        // 3. 发起新请求
        const promise = this.inner(key)
            .then(resolution => {
                // 大文本（字幕等）缓存更长时间，避免频繁拉取
                const ttl = (resolution.charCount ?? 0) > 200
                    ? this.defaultTtlMs * 6   // 30 分钟
                    : this.defaultTtlMs;
                this.cache.set(key, { resolution, timestamp: Date.now(), ttl });
                this.inflight.delete(key);
                return resolution;
            })
            .catch(e => {
                this.inflight.delete(key);
                throw e;
            });

        this.inflight.set(key, promise);
        return promise;
    }

    /** 同步读取已缓存值（用于补全列表初始渲染，不触发异步请求） */
    getCached(key: string): VariableResolution | undefined {
        const entry = this.cache.get(key);
        if (entry && Date.now() - entry.timestamp < entry.ttl) return entry.resolution;
        return undefined;
    }

    /** 手动失效（如用户重新提取了字幕） */
    invalidate(key?: string) {
        if (key) this.cache.delete(key);
        else this.cache.clear();
    }
}
```

### 5.4 适配层（`createVariableResolver`）

将类型化的 `PromptResolver` 路由到统一的 `VariableResolver` 接口：

```ts
function createVariableResolver(
    resolver: PromptResolver,
    slotRegistry: Record<string, PromptSlotBlock> = {},
): VariableResolver {
    return async (key: string): Promise<VariableResolution> => {
        // ① Slot 变量优先
        if (slotRegistry[key]) {
            const slot = slotRegistry[key];
            return {
                kind: "slot", status: "ok",
                SlotComponent: slot.EditorComponent,
                serialize: () => slot.serialize({}),
            };
        }

        // ② 按 "base.param1.param2" 格式路由
        const [base, p1, p2] = key.split(".");
        try {
            switch (base) {
                case "material": {
                    // material.* 字段同步读取，包装为 Promise
                    const val = String(resolver.material[p1 as keyof typeof resolver.material] ?? "");
                    return { kind: "text", status: "ok", value: val, preview: val };
                }
                case "subtitle": {
                    // subtitle / subtitle.zh / subtitle.zh.timestamped
                    const text = await resolver.subtitle({
                        lang:   p1 as "zh" | undefined,
                        format: p2 as "plain" | "timestamped" | undefined,
                    });
                    return {
                        kind: "text", status: "ok",
                        value: text,
                        preview: text.length > 500 ? text.slice(0, 500) + "…" : text,
                        charCount: text.length,
                    };
                }
                case "speakers": {
                    const text = await resolver.speakers({ format: p1 as any });
                    return {
                        kind: "text", status: "ok",
                        value: text,
                        preview: text.slice(0, 300),
                        charCount: text.length,
                    };
                }
                case "scenes":
                case "ocr":
                case "videoContent": {
                    return {
                        kind: "text",
                        status: "unimplemented",
                        unavailableReason: "功能尚未实现",
                    };
                }
                default:
                    return {
                        kind: "text",
                        status: "unimplemented",
                        unavailableReason: `未知变量 ${key}`,
                    };
            }
        } catch (e) {
            const reason = e instanceof Error ? e.message : String(e);
            const isNotImplemented = e instanceof NotImplementedError || /not implemented|尚未实现/i.test(reason);
            return {
                kind: "text",
                status: isNotImplemented ? "unimplemented" : "unavailable",
                unavailableReason: reason,
            };
        }
    };
}
```

### 5.5 React 集成（`useVariableResolver`）

```ts
function useVariableResolver(
    promptResolver: PromptResolver,
    slotRegistry: Record<string, PromptSlotBlock>,
    variables: VariableMeta[],
) {
    // 缓存层持久化，不随 render 重建
    const cacheRef = useRef(
        new VariableResolverCache(createVariableResolver(promptResolver, slotRegistry))
    );

    // 各变量的 status，用于工具栏图标和补全列表
    const [statuses, setStatuses] = useState<Record<string, VariableResolution["status"]>>({});

    // mount 时批量预热：只查 status，不拉全文
    // 全文拉取推迟到用户点击[预览]时
    useEffect(() => {
        const cache = cacheRef.current;
        Promise.allSettled(
            variables.map(v =>
                cache.resolve(v.key)
                    .then(r => ({ key: v.key, status: r.status }))
                    .catch(() => ({ key: v.key, status: "unavailable" as const }))
            )
        ).then(results => {
            const map: Record<string, VariableResolution["status"]> = {};
            for (const r of results) {
                if (r.status === "fulfilled") map[r.value.key] = r.value.status;
            }
            setStatuses(map);
        });
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    return {
        /** 查询变量（带缓存） */
        resolve: (key: string) => cacheRef.current.resolve(key),
        /** 当前各变量的 status（可能尚未加载完，初始为 {}） */
        statuses,
        /** 手动失效缓存（如字幕重新提取后调用 invalidate("subtitle")） */
        invalidate: (key?: string) => {
            cacheRef.current.invalidate(key);
            // 触发重新查 status
            setStatuses({});
        },
        cache: cacheRef.current,
    };
}
```

### 5.6 CodeMirror 扩展实现

#### 变量高亮扩展

```ts
const varMark  = Decoration.mark({ class: "cm-prompt-var" });   // text 变量 → 紫色
const slotMark = Decoration.mark({ class: "cm-prompt-slot" });  // slot 变量 → 橙色

function variableHighlightExtension(slotKeys: Set<string>): Extension {
    return ViewPlugin.fromClass(class {
        decorations: DecorationSet = Decoration.none;

        constructor(view: EditorView) { this.decorations = this.build(view); }

        update(u: ViewUpdate) {
            if (u.docChanged || u.viewportChanged) this.decorations = this.build(u.view);
        }

        build(view: EditorView): DecorationSet {
            const builder = new RangeSetBuilder<Decoration>();
            // 只处理可见区域，避免大文档性能问题
            for (const { from, to } of view.visibleRanges) {
                const text = view.state.doc.sliceString(from, to);
                for (const match of text.matchAll(/\$\{([\w.]+)\}/g)) {
                    const start = from + match.index!;
                    const end   = start + match[0].length;
                    builder.add(start, end, slotKeys.has(match[1]) ? slotMark : varMark);
                }
            }
            return builder.finish();
        }
    }, { decorations: p => p.decorations });
}
```

CSS（Tailwind `@layer` 内）：

```css
.cm-prompt-var  { @apply bg-violet-100 text-violet-700 rounded px-0.5 font-mono; }
.cm-prompt-slot { @apply bg-orange-100 text-orange-700 rounded px-0.5 font-mono; }
```

#### 自动补全扩展

```ts
function variableCompletionExtension(
    variables: VariableMeta[],
    cache: VariableResolverCache,
): Extension {
    const source: CompletionSource = (ctx) => {
        // 匹配 "${" + 可选的已输入前缀
        const match = ctx.matchBefore(/\$\{[\w.]*/);
        if (!match || (!ctx.explicit && match.from === match.to)) return null;

        const prefix = match.text.slice(2); // 去掉 "${"

        const options: Completion[] = variables
            .filter(v => v.key.startsWith(prefix))
            .map(v => {
                const cached = cache.getCached(v.key);
                const status = cached?.status ?? "unimplemented";
                const icon   = status === "ok" ? "✅" : status === "unavailable" ? "⚠️" : "🔜";
                return {
                    label:        `\${${v.key}}`,
                    displayLabel: v.key,
                    detail:       `${icon} ${v.label}`,
                    info:         v.description,
                    type:         v.kind === "slot" ? "function" : "variable",
                    // 将光标从 "${" 开始整体替换（包括用户已输入的前缀）
                    apply: (view: EditorView, _: Completion, from: number, to: number) => {
                        view.dispatch({
                            changes: { from: match.from, to, insert: `\${${v.key}}` },
                        });
                    },
                    boost: status === "ok" ? 10 : status === "unavailable" ? 0 : -5,
                };
            });

        return { from: match.from, options, validFor: /^\$\{[\w.]*$/ };
    };

    return autocompletion({ override: [source], activateOnTyping: true });
}
```

#### Slot Widget 扩展

```ts
class SlotWidget extends WidgetType {
    constructor(
        readonly varKey: string,
        private resolution: VariableResolution,
    ) { super(); }

    toDOM(): HTMLElement {
        const el = document.createElement("span");
        el.className = "cm-slot-widget";
        const root = createRoot(el);
        root.render(<this.resolution.SlotComponent! onChange={() => {}} />);
        (el as any).__cmRoot = root;
        return el;
    }

    destroy(dom: HTMLElement) { (dom as any).__cmRoot?.unmount(); }
    eq(other: SlotWidget)     { return other.varKey === this.varKey; }
    ignoreEvent()             { return false; }
}

function slotWidgetExtension(cache: VariableResolverCache): Extension {
    return ViewPlugin.fromClass(class {
        decorations: DecorationSet = Decoration.none;

        constructor(view: EditorView) { this.decorations = this.build(view); }

        update(u: ViewUpdate) {
            if (u.docChanged || u.viewportChanged) this.decorations = this.build(u.view);
        }

        build(view: EditorView): DecorationSet {
            const builder = new RangeSetBuilder<Decoration>();
            for (const { from, to } of view.visibleRanges) {
                const text = view.state.doc.sliceString(from, to);
                for (const match of text.matchAll(/\$\{([\w.]+)\}/g)) {
                    const key  = match[1];
                    const res  = cache.getCached(key);
                    if (res?.kind !== "slot" || res.status !== "ok") continue;
                    const start = from + match.index!;
                    const end   = start + match[0].length;
                    // Decoration.replace 隐藏原始文本，widget 填充其位置
                    builder.add(start, end, Decoration.replace({
                        widget: new SlotWidget(key, res),
                        block: false,
                    }));
                }
            }
            return builder.finish();
        }
    }, { decorations: p => p.decorations });
}
```

### 5.7 `buildPrompt`（模板序列化）

Frame 2 预览和最终发送共用同一个函数：

```ts
interface BuildPromptResult {
    text: string;
    charCount: number;
    blocked: boolean;   // submit 模式下是否应阻止发送
    /** 无法解析的变量（用于在 Preview 顶部展示警告） */
    warnings: Array<{ key: string; reason: string }>;
}

async function buildPrompt(
    template: string,
    resolver: VariableResolver,
    options: { mode: "preview" | "submit" },
): Promise<BuildPromptResult> {
    // 收集模板中所有变量 key（去重）
    const keys = [...new Set(
        [...template.matchAll(/\$\{([\w.]+)\}/g)].map(m => m[1])
    )];

    // 并行解析所有变量
    const resolved = new Map<string, VariableResolution>();
    await Promise.allSettled(
        keys.map(key => resolver(key).then(r => resolved.set(key, r)))
    );

    const warnings: BuildPromptResult["warnings"] = [];

    const text = template.replace(/\$\{([\w.]+)\}/g, (_, key) => {
        const r = resolved.get(key);
        if (!r || r.status !== "ok") {
            const reason = r?.unavailableReason ?? "不可用";
            warnings.push({ key, reason });
            return options.mode === "preview"
                ? `[${key}: ${reason}]`
                : "";
        }
        return r.kind === "slot" ? (r.serialize?.() ?? "") : (r.value ?? "");
    });

    return {
        text,
        charCount: text.length,
        blocked: options.mode === "submit" && warnings.length > 0,
        warnings,
    };
}
```

**约定**：
- `preview` 模式：保留 `[key: reason]` 占位，帮助用户定位问题
- `submit` 模式：默认不把错误提示文本发送给 LLM；若存在 warnings，则 `blocked = true`，交由上层阻止发送

Frame 2 预览组件调用示例：

```tsx
function PromptPreviewFrame({ template, resolver }: {
    template: string;
    resolver: VariableResolver;
}) {
    const [result, setResult] = useState<BuildPromptResult | null>(null);
    const [loading, setLoading] = useState(false);

    // 由父组件触发（用户点击[预览]按钮）
    useImperativeHandle(ref, () => ({
        async load() {
            setLoading(true);
            try { setResult(await buildPrompt(template, resolver)); }
            finally { setLoading(false); }
        },
    }));

    return (
        <div className="space-y-2">
            {loading && <div className="text-sm text-gray-400">计算中…</div>}
            {result && <>
                {result.warnings.length > 0 && (
                    <div className="text-xs text-amber-600 bg-amber-50 rounded p-2">
                        {result.warnings.map(w => `⚠ \${${w.key}}: ${w.reason}`).join(" · ")}
                    </div>
                )}
                <div className="text-xs text-gray-400 text-right">
                    共 {result.charCount.toLocaleString()} 字符
                </div>
                <pre className="text-sm whitespace-pre-wrap bg-gray-50 rounded p-3 overflow-y-auto max-h-96">
                    {result.text}
                </pre>
            </>}
        </div>
    );
}
```

---

## 6. 开放问题 & 决策点

| # | 问题 | 当前倾向 | 影响范围 |
|---|------|----------|----------|
| 1 | 变量语法选方案 B 还是直接上更强表达能力？ | 方案 B 起步；expert 模式先只做编辑体验增强，后续再评估受限 DSL/AST | PromptBuilder 核心实现 |
| 2 | 模板存储短期用 localStorage 还是立刻建 DB 表？ | localStorage 起步 | 后端工作量 |
| 3 | `${weben_schema_hint}` 是内置变量还是用户手动插入？ | ✅ 已决策：Slot Block，直接渲染 schema 管理 UI 组件；模板默认含此块，用户也可手动插入 | 模板设计 |
| 4 | PromptBuilder 是否支持多步骤（先生成，再检查，再补充）？ | Phase 2，暂不实现 | 复杂度 |
| 5 | expert 模式最终采用受限 DSL、片段组合，还是远期服务端执行？ | 待后续专题设计，不在 Phase 1 决策 | 安全/表达能力 |

---

## 7. 与 summary-weben-integration.md 的关系

本文档解决基础设施问题后，`summary.weben.tsx` 的 Prompt 构建器部分可以：

1. 直接使用 `<PromptBuilder category="weben_extract" ... />` 组件
2. 内置 `weben_extract` 默认模板（含 `${weben_schema_hint}`）
3. 后端 `WebenConceptBatchImportRoute` 负责解析 + 保存 LLM 输出
4. Schema 版本由 App 代码统一管理，模板不感知具体枚举

依赖关系：

```
prompt-builder-design.md（本文档）
    ↓ 解决后
summary-weben-integration.md 的 Phase 3（summary.weben.tsx）才可进入实现
```
