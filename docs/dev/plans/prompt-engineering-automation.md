---
title: 提示词工程自动化 — 技术路线评估
order: 530
---

# 提示词工程自动化 — 技术路线评估

> **文档状态**：技术路线探索（非详细设计）
> **创建日期**：2026-04-17
> **废弃前置**：旧 PromptGraph 三表模型（PromptGraphDef / PromptGraphRun / PromptNodeRun）及 DAG 引擎已废弃。本文档也取代 `prompt-builder-design.md`、`prompt-builder-next-phase-plan.md` 的方向。

---

## 1. 痛点与现状

### 1.1 痛点清单

| # | 痛点 | 本质矛盾 |
|---|------|----------|
| P1 | n8n/ComfyUI 式图形编辑器无法"让 AI 帮我写" | 图形化 ↔ AI 可编程性 |
| P2 | Claude Code 等 CLI 能写提示词，但无法对接运行时工作流 | AI 编写 ↔ 运行时集成 |
| P3 | 多模型对接需要 MCP 化，但 prompt 缺乏标准化描述 | 模型多样性 ↔ 接口统一 |
| P4 | n8n 的错误处理/分支/循环体验极差，不如内嵌 JS 引擎 | 图形化简洁 ↔ 逻辑表达力 |
| P5 | 重头功能需要系统默认提示词 + 提示词市场拉取 | 开箱即用 ↔ 社区生态 |
| P6 | 缺少 Schema 编辑器的图形化设计 | 结构化输出 ↔ 可视化编辑 |
| P7 | 用户频繁改提示词/改顺序/加节点，需要版本管理 | 灵活迭代 ↔ 稳定追溯 |

### 1.2 现有系统已经验证的模式

ASR 后处理页面（`subtitle-asr-postprocess.tsx`）已经体现了一个关键分层：

```
┌─────────────────────────────────────────────────────┐
│  用户接触层：PromptBuilder 编辑器                      │
│  ── 用户只编辑提示词文本 + 少量 getVar() 调用          │
│  ── 模板库加载/保存/Fork                              │
├─────────────────────────────────────────────────────┤
│  控制流层：GraalJS 沙箱 (PromptScriptRuntime)         │
│  ── main() 函数处理分批、循环、条件分支                 │
│  ── getVar(key) 拉取运行时数据                        │
│  ── 返回 string | string[]（单段/分段 MapReduce）      │
├─────────────────────────────────────────────────────┤
│  执行层：PromptScriptGenerateRoute                    │
│  ── 逐段调用 LLM → SSE 流式返回                       │
│  ── 错误处理、超时、并发控制                            │
└─────────────────────────────────────────────────────┘
```

**已验证的优点**：
- 提示词工程师只需关心提示词文本，分批/循环等控制流由 `main()` 函数处理
- GraalJS 沙箱安全（无 FS/网络/进程访问），已在生产运行
- 模板库（`PromptTemplatePickerModal` + `SaveTemplateModal`）提供了基础的复用和版本管理

**暴露的问题**：
- 控制流和提示词文本混在同一个编辑器里——提示词工程师改一段 prompt 时，要小心不碰到 `main()` 的逻辑代码
- 没有 Schema 约束——LLM 输出格式全靠提示词文本描述，解析失败只能事后发现
- 模板只是"文本快照"，没有结构化的输入/输出契约
- 无法让 AI 工具（Claude Code / MCP）理解和操作提示词的语义结构

---

## 2. 核心设计问题：提示词工程师应该接触多少代码？

这是整个方案的关键取舍。ASR 后处理的经验告诉我们：**分批、循环等控制流必须依赖代码，但编辑提示词文本时应当接触的代码越少越好。**

### 2.1 三种路线对比

#### 路线 A：纯代码（当前 PromptScript 方向的极端形式）

提示词工程师直接编辑 `.prompt.ts` 文件，所有逻辑（提示词文本 + 控制流 + Schema）都在代码中。

```typescript
// 提示词工程师需要理解的全部内容
export default definePrompt({
  async execute(ctx) {
    const chunks = ctx.input.subtitleChunks;
    const results = [];
    for (const chunk of chunks) {           // ← 控制流
      results.push(await llm.chat({
        messages: [
          { role: "system", content: `...` }, // ← 提示词文本
          { role: "user", content: chunk },
        ],
        responseFormat: { schema: OutputSchema }, // ← Schema
      }));
    }
    return merge(results);                   // ← 后处理
  },
});
```

- 优点：AI 可读写、Git 友好、表达力最强
- 缺点：提示词工程师改一句 prompt 要在代码中定位、小心不破坏逻辑
- 适合：开发者自用

#### 路线 B：分层编辑（推荐探索方向）

将"提示词文本"和"控制流代码"物理分离。提示词工程师编辑的是**纯文本模板**（带变量插值），控制流由独立的"编排脚本"处理。

```
一个 Prompt Unit = 编排脚本(.js) + N 个提示词模板(.prompt.md) + Schema 定义
```

提示词工程师看到的（`.prompt.md`）：
```markdown
---
id: extract-concepts
input_vars: [chunk, chunk_index, total_chunks]
output_schema: ConceptList
---
# System
你是一个知识提取专家。请从以下字幕片段中提取关键概念。

# User
## 字幕片段 {{chunk_index}}/{{total_chunks}}

{{chunk}}

请提取其中出现的人物、地点、事件、概念，以 JSON 格式输出。
```

开发者看到的（编排脚本）：
```javascript
async function main() {
  const chunks = await getVar("subtitle_chunks");
  const results = [];
  for (let i = 0; i < chunks.length; i++) {
    results.push(await runPrompt("extract-concepts", {
      chunk: chunks[i], chunk_index: i + 1, total_chunks: chunks.length,
    }));
  }
  return merge(results);
}
```

- 优点：提示词工程师只编辑 Markdown 文件，零代码接触；开发者控制流程
- 缺点：需要设计模板 ↔ 脚本的绑定机制；两个文件的同步维护
- 适合：有提示词工程师和开发者分工的团队

#### 路线 C：声明式配置 + 代码逃生舱

用 YAML/JSON 声明常见模式（顺序执行、MapReduce、条件分支），复杂场景降级到代码。

```yaml
name: weben-concept-extract
steps:
  - id: extract
    type: map_reduce
    input: ${subtitle_chunks}
    prompt: extract-concepts.prompt.md
    output_schema: ConceptList
  - id: deduplicate
    type: transform
    script: deduplicate.js    # 代码逃生舱
  - id: relations
    type: llm_call
    condition: ${config.extractRelations}
    prompt: infer-relations.prompt.md
    input: ${steps.deduplicate.output}
```

- 优点：常见模式零代码；声明式易于可视化
- 缺点：本质上是在重新发明 n8n，复杂场景的声明式表达会变得比代码更难读
- 适合：模式固定、变化少的场景

### 2.2 初步判断

**路线 B 最值得探索**，原因：

1. **与现有系统最接近**——当前 ASR 后处理已经是"GraalJS 脚本 + 编辑器中的提示词文本"的雏形，路线 B 只是把这个分层做得更显式
2. **解决核心矛盾**——提示词工程师编辑 `.prompt.md`（纯文本），开发者编辑编排脚本（JS），各自在舒适区工作
3. **AI 友好**——AI 生成/修改 Markdown 模板比生成 DAG 图结构容易得多；编排脚本也是普通 JS
4. **渐进式**——可以从"单模板无编排"开始（等价于当前模板库），逐步引入编排脚本

路线 A 作为路线 B 的"高级模式"保留——对于不需要分层的简单场景或开发者自用，直接写一个 `.prompt.ts` 也应该被支持。

---

## 3. 待验证的关键技术问题

### 3.1 模板格式：Markdown + Frontmatter 是否够用？

当前 PromptBuilder 中的模板是纯 JS 代码（含 `main()` 函数）。如果要分离出纯文本模板，需要确认：

- **变量插值**：<code v-pre>{{var}}</code> 语法是否足够？是否需要条件（<code v-pre>{{#if}}</code>）、循环（<code v-pre>{{#each}}</code>）？
  - 初步判断：Mustache/Handlebars 级别的模板语法应该够用。如果需要更复杂的逻辑，说明应该放到编排脚本里
- **多轮对话**：一个模板如何表达 system/user/assistant 多个 message？
  - 候选方案：用 Markdown 标题分隔（`# System` / `# User`），类似 LangChain 的 ChatPromptTemplate
- **Schema 内联 vs 外置**：输出 Schema 放在 frontmatter 里还是单独文件？
  - 初步判断：简单 Schema 放 frontmatter（`output_schema: ConceptList`），复杂 Schema 引用外部 `.schema.json`

### 3.2 编排脚本与模板的绑定

编排脚本如何引用模板？两种方案：

**方案 1：约定目录结构**
```
prompts/
├── weben-concept-extract/
│   ├── orchestrate.js          # 编排脚本
│   ├── extract-concepts.prompt.md
│   ├── infer-relations.prompt.md
│   └── schemas/
│       ├── ConceptList.schema.json
│       └── RelationList.schema.json
```

**方案 2：单文件 + 分区**（类似 Vue SFC）
```
一个 .prompt 文件内用分隔符划分区域：
--- meta ---
--- template: extract ---
--- template: relations ---
--- script ---
```

初步倾向方案 1——文件系统天然支持 Git diff、AI 工具读写、编辑器分别打开。

### 3.3 GraalJS 沙箱是否继续使用？

当前 `PromptScriptRuntime` 基于 GraalJS，已验证可行。但如果要支持 TypeScript 或更丰富的 SDK：

| 方案 | 现状 | 升级成本 | TS 支持 |
|------|------|----------|---------|
| GraalJS（当前） | 已在生产运行 | 零 | 无（纯 JS） |
| GraalJS + TS 预编译 | — | 低（构建时 tsc/esbuild） | 间接支持 |
| Deno subprocess | — | 中（进程管理、IPC） | 原生 |

初步判断：短期继续用 GraalJS（编排脚本用纯 JS 足够），长期如果需要 TS 类型检查再评估 Deno。

### 3.4 Schema 编辑器的可行性

P6 要求 Schema 可视化编辑。两种路径：

- **JSON Schema 表单编辑器**：已有成熟开源方案（如 react-jsonschema-form 的 schema builder），可直接集成
- **TypeScript interface 双向编辑**：技术上可行（ts-json-schema-generator），但实现复杂度高

初步判断：先做 JSON Schema 表单编辑器（成熟、可控），生成的 Schema 存为 `.schema.json` 文件。TypeScript interface 作为远期目标。

### 3.5 版本管理与提示词市场

P5 和 P7 的解决思路：

- **版本管理**：提示词目录本身就是文件系统，直接用 Git。系统内置提示词随应用发布，用户 Fork 后本地修改
- **提示词市场**：最简方案是一个 Git 仓库索引（类似 Homebrew tap），用户 `安装` = 拉取文件到本地。不需要自建平台
- **系统默认提示词**：打包在 `resources/prompts/` 中，首次使用时复制到用户数据目录，后续用户可自由修改

### 3.6 AI 辅助编写如何对接？

- **MCP 方案**：将 prompt 的 CRUD + 执行暴露为 MCP tools，Claude Code 等工具可直接操作
- **内置 AI 助手**：在前端编辑器中集成"AI 帮我改"按钮，将当前模板 + 运行结果 + 用户反馈发给 LLM
- 两者不冲突，MCP 面向开发者/CLI，内置助手面向 UI 用户

---

## 4. 建议的探索顺序

1. **验证模板分离**：在 ASR 后处理场景中，尝试将当前的 GraalJS 脚本拆分为"编排脚本 + `.prompt.md` 模板"，验证分层是否可行、编辑体验是否改善
2. **验证 Schema 编辑器**：选一个 JSON Schema 表单编辑器库，在前端做 PoC，验证能否生成可用的 `.schema.json`
3. **验证 MCP 集成**：写一个最小 MCP server，暴露 `prompt_list / prompt_read / prompt_run`，验证 Claude Code 能否端到端操作提示词
4. **根据验证结果决定**：是走路线 B（分层编辑）还是需要调整方向

---

## 5. 核心矛盾：提示词管理如何脱离工程代码管理？

路线 B 的分层编辑看似解决了"提示词工程师不碰代码"的问题，但引入了一个更深层的矛盾：

### 5.1 多轮对话上下文传递需要代码

实际场景中，多节点链式调用的上下文传递（如把前一个节点的对话历史 flat 进当前节点的 messages）本质上是编程问题——模板变量插值无法表达"从上一轮结果中提取字段、拼接到 messages 数组"这种逻辑。这意味着**编排脚本不可避免地要处理 LLM 对话上下文**，而不仅仅是"分批循环"这种纯控制流。

```javascript
// 编排脚本不可避免地要处理对话上下文拼接
async function main() {
  const concepts = await runPrompt("extract-concepts", { chunk });
  // 前一轮的输出要作为下一轮的上下文——这不是模板能表达的
  const relations = await runPrompt("infer-relations", {
    concepts: concepts.output,
    prior_messages: concepts.messages, // 需要访问完整对话历史
  });
}
```

这进一步确认了：**编排脚本是真正的代码，不是配置**。

### 5.2 代码 = Git，但提示词不应该跟工程代码走同一个 Git 流程

一旦编排脚本是代码，它就天然属于 Git 管理。但问题在于：

| 维度 | 工程代码 | 提示词（模板 + 编排脚本） |
|------|---------|------------------------|
| 变更频率 | 低（按 sprint/版本） | 高（每天多次迭代） |
| 变更者 | 开发者 | 提示词工程师 / 运营 / 甚至用户 |
| 审核流程 | PR → Code Review → CI | 不需要也不应该走 PR 流程 |
| 部署节奏 | 跟随应用发版 | 即时生效 |
| 回滚粒度 | 整个应用版本 | 单个提示词的某次修改 |

如果提示词和工程代码混在同一个 Git 仓库、同一个 PR 流程中，会出现：
- 提示词工程师改一句 prompt 要等开发者 review、CI 通过、合并发版
- 提示词的高频迭代污染工程代码的 Git 历史
- 无法给提示词单独做 A/B 测试或灰度发布

### 5.3 可能的解耦方案

#### 方案 1：提示词存数据库，编辑器即版本管理

提示词（模板 + 编排脚本）不存文件系统，而是存在数据库中，通过 Web 编辑器管理。版本历史由数据库记录，不依赖 Git。

```
┌─────────────────────────────────────────────────┐
│  数据库（prompt_version 表）                      │
│  ── id / prompt_unit_id / version / content      │
│  ── created_at / author / parent_version         │
│  ── status: draft | active | archived            │
├─────────────────────────────────────────────────┤
│  Web 编辑器                                      │
│  ── 模板编辑（Markdown）+ 编排脚本编辑（JS）       │
│  ── 版本对比 / 回滚 / Fork                       │
│  ── 即时预览 + 即时生效                           │
└─────────────────────────────────────────────────┘
```

- 优点：完全脱离 Git 流程；版本管理粒度精确到单个提示词；即时生效
- 缺点：编排脚本在数据库中 = 失去 IDE 支持（类型检查、自动补全）；AI 工具操作需要走 API 而非文件系统
- 与现有系统的关系：当前 `PromptTemplatePickerModal` 已经是数据库存储模板的雏形

#### 方案 2：独立 Git 仓库 + 热加载

提示词目录是一个独立的 Git 仓库（或 Git submodule），有自己的提交历史和发布节奏。应用运行时从该目录热加载。

```
fredica/                    # 工程代码仓库
fredica-prompts/            # 提示词仓库（独立 Git）
├── asr-postprocess/
│   ├── orchestrate.js
│   └── extract-concepts.prompt.md
└── weben-concept-extract/
    ├── orchestrate.js
    └── ...
```

- 优点：Git 的全部能力（diff、blame、branch）；AI 工具直接读写文件；IDE 支持完整
- 缺点：仍然是 Git 流程，对非开发者不友好；"独立仓库"的运维成本
- 适合：团队中有专职提示词工程师且熟悉 Git 的场景

#### 方案 3：混合模式——数据库为主，Git 导出为辅

日常编辑和版本管理在数据库 + Web 编辑器中完成。需要时可以导出为文件（供 AI 工具操作或备份），也可以从文件导入。

```
日常流程：Web 编辑器 → 数据库 → 即时生效
AI 辅助：导出为文件 → AI 修改 → 导入回数据库
备份/迁移：导出为 Git 仓库快照
```

- 优点：兼顾非开发者的易用性和开发者/AI 的文件系统操作需求
- 缺点：导入/导出的同步机制需要设计；两套"真相来源"可能冲突

### 5.4 初步判断

**方案 1（数据库为主）最符合实际需求**，原因：

1. **与现有系统一致**——当前模板库已经是数据库存储，扩展为"模板 + 编排脚本 + 版本历史"是自然演进
2. **目标用户画像**——提示词的主要编辑者不是开发者，不应该要求他们学 Git
3. **即时生效**——提示词迭代的核心诉求是"改了就能试"，数据库方案天然支持
4. **AI 对接**——通过 MCP tools 暴露 CRUD API，AI 工具不需要文件系统访问也能操作

方案 3 作为方案 1 的增强——当需要批量操作或备份时，提供导出/导入能力。

**编排脚本在数据库中丢失 IDE 支持的问题**，可以通过 Web 编辑器内置 Monaco Editor（VS Code 的编辑器内核）+ 自定义 Language Service 来缓解，但这是后续的工程投入。

### 5.5 默认提示词的快速迭代问题

方案 1（数据库为主）解决了用户侧的提示词管理，但引入了一个新问题：**APP 的主打功能（如 ASR 后处理、Weben 概念提取）需要开箱即用的默认提示词**。这些默认提示词：

- 必须随应用发布——用户首次使用时不能面对空白编辑器
- 需要开发者高频迭代——功能开发阶段，默认提示词和代码一起改
- 升级时可能需要更新——新版本改进了默认提示词，老用户应该能获得更新

这本质上是一个**"种子数据的版本管理"**问题，类似于数据库 migration 中的 seed data。

#### 思路：代码内嵌 + 数据库覆盖的两层模型

```
┌─────────────────────────────────────────────────────────┐
│  用户层（数据库）                                         │
│  ── 用户修改过的提示词，优先级最高                          │
│  ── 用户可以"重置为默认"                                  │
├─────────────────────────────────────────────────────────┤
│  默认层（代码内嵌 / resources）                            │
│  ── 随应用打包，跟工程代码一起走 Git                       │
│  ── 每个默认提示词有 builtin_version 标记                  │
│  ── 应用启动时：如果用户未修改 → 静默升级到最新默认版本      │
│  ──            如果用户已修改 → 保留用户版本，标记有新默认可用│
└─────────────────────────────────────────────────────────┘
```

**关键机制：**

1. **默认提示词跟代码走 Git**——开发阶段，默认提示词就是代码的一部分（`resources/prompts/` 或 Kotlin 常量），开发者用正常的 Git 流程迭代，这是合理的，因为默认提示词的变更节奏和代码一致
2. **首次使用时 seed 到数据库**——应用启动检测到数据库中没有该提示词 → 从 resources 复制一份到数据库
3. **升级时条件更新**——每个默认提示词带一个 `builtin_version`（整数递增）。启动时比较：
   - 数据库中的 `builtin_version` < 代码中的 → 用户未改过则静默更新；用户改过则提示"有新默认版本可用"
   - 用户可以选择"查看差异并合并"或"忽略"
4. **"重置为默认"**——用户随时可以丢弃自己的修改，回到当前版本的默认提示词

**这个模型解决了快速迭代的问题：**

- 开发阶段：默认提示词在 `resources/` 中，改了就能测，跟代码一起提交
- 发布后：用户拿到的是数据库副本，可以自由修改，不影响下次升级
- 升级时：未修改的默认提示词自动更新；已修改的给用户选择权

**类比：** 这和 VS Code 的 `defaultSettings.json`（只读，随版本更新）+ `settings.json`（用户覆盖）是同一个模式。

但需要注意：**提示词天然带有个人推断和意图色彩**（"你是一个知识提取专家"这种措辞本身就是主观判断），它和工程代码在性质上不同——代码是确定性逻辑，提示词是意图表达。把提示词放进工程代码目录，只是因为"必须给个默认的"这一个理由，不应该让提示词的管理制度被工程代码的管理制度（Git PR、Code Review、CI）绑架。

---

## 6. 业界方案调研

在决定 Fredica 的技术路线之前，有必要看看业界对上述问题已经有哪些成熟或有启发性的解法。

### 6.1 提示词管理平台（SaaS / 自托管）

这类平台的核心定位是**将提示词作为独立于代码的一等公民来管理**，提供 Web 编辑器、版本管理、评估测试、可观测性等完整工具链。

#### PromptLayer

| 维度 | 功能 |
|------|------|
| 编辑体验 | Web 编辑器 + 语法高亮；Mustache 变量语法 `{variable}` |
| 版本管理 | 每次保存自动创建版本；版本标签（production / staging）；side-by-side diff |
| 评估测试 | A/B 测试（按流量分配版本）；每版本的成本/延迟追踪；自定义指标 |
| 部署模型 | SDK 运行时按标签拉取版本；自动缓存；版本不可用时 fallback |
| 集成 | Python / JS SDK；LangChain 插件；REST API |
| 可观测性 | 请求/响应对绑定到版本；成本按版本聚合；延迟直方图 |

#### Humanloop

| 维度 | 功能 |
|------|------|
| 编辑体验 | Web UI + Playground（实时调用模型）；Function Calling Schema 编辑器（JSON） |
| 版本管理 | 自动版本化；Active 版本标记；dev → staging → production 部署流水线 |
| 评估测试 | **自定义评分函数**（JavaScript）；数据集管理（上传测试用例 + 期望输出）；跨版本评分对比 |
| 部署模型 | 代码中引用版本 ID；所有请求自动关联版本用于回溯分析 |
| 集成 | Python / JS SDK；REST API；Webhook（版本变更通知） |
| 可观测性 | 自动记录所有执行的输入/输出；版本维度的性能仪表盘；自定义指标注入 |

#### Langfuse（开源，可自托管）

| 维度 | 功能 |
|------|------|
| 编辑体验 | Web UI + Playground；版本 diff 对比 |
| 版本管理 | 递增版本号（v1, v2, v3...）；设置 active production 版本；**Variant 支持**（多个"活跃"版本用于 A/B 测试） |
| 评估测试 | 数据集 + Runs（对数据集执行版本）；自定义评分函数；评分结果关联到 trace |
| 部署模型 | 按 name + version 拉取（active / production / 指定版本号）；SDK 缓存 + 失效策略 |
| 集成 | Python / JS / OpenTelemetry 兼容；LangChain 集成；REST API |
| 可观测性 | **统一 tracing 平台**：trace → span → generation 全链路关联到 prompt 版本；延迟/token/成本按版本聚合 |
| 自托管 | Docker Compose；PostgreSQL + Redis；Apache 2.0 开源 |

#### LangSmith（LangChain 生态）

| 维度 | 功能 |
|------|------|
| 编辑体验 | Prompt Hub：Web 编辑器 + 实时 Playground；支持切换模型（Claude / GPT-4 等） |
| 版本管理 | **Commit 快照**（类 Git）；Tag 标记发布版本；Fork / Branch 并行实验；手动合并 |
| 模板语法 | Python f-string 风格 `{variable_name}`；部分模式支持 Jinja2 条件 |
| 评估测试 | **数据集 + 评估器 + 实验**三件套：上传测试集 → 跑评估器（内置 + 自定义 Python 函数）→ 跨版本对比指标；Benchmark 模式追踪指标趋势 |
| 部署模型 | 按 repo ID + commit/tag 拉取；SDK 缓存管理 |
| 集成 | LangChain 原生集成；Python / JS SDK；REST API；Webhook |
| 可观测性 | 详细执行 trace（所有模型调用）；延迟/token/成本按版本分解；评估结果聚合仪表盘 |

#### Braintrust

| 维度 | 功能 |
|------|------|
| 编辑体验 | Web Playground + 实时执行；多模态输入（文本/图片/音频）；中间结果调试器 |
| 版本管理 | **以实验（Experiment）为核心**：每个变体独立追踪；快照对比；Production 版本标记 |
| 模板语法 | Jinja2 风格（含条件、过滤器） |
| 评估测试 | **多维评分**（准确性/相关性/语气等）；数值 + 分类评分；跨实验统计显著性检验；Human-in-the-loop 手动评分 |
| 部署模型 | 实验 ID 解析到版本；SDK 拉取 + TTL 缓存 |
| 可观测性 | 每次评估的完整 trace；评分分布可视化；历史实验对比 |

#### Portkey（网关模式）

| 维度 | 功能 |
|------|------|
| 编辑体验 | 提示词工作区（云 UI）；请求检查 + 重放 |
| 版本管理 | 配置（Config）版本化；语义版本号；dev/staging/prod 部署 |
| **独特之处** | **网关代理架构**：所有 LLM 请求经过 Portkey 网关 → 在网关层应用提示词版本 → 支持运行时路由规则（fallback、负载均衡）。提示词版本切换不需要改应用代码 |
| 集成 | Python / JS SDK；OpenAI 兼容 API 接口 |
| 可观测性 | 网关层请求/响应日志；按配置版本的延迟/成本/错误追踪 |

### 6.2 提示词即代码框架（Prompt-as-Code）

这类框架不提供 Web 管理平台，而是将提示词定义嵌入代码中，通过类型系统和编译器保证正确性。

#### BAML（Boundary AI Markup Language）

| 维度 | 功能 |
|------|------|
| 核心理念 | 专用 DSL 定义提示词 + 类型，**编译**为类型安全的 SDK |
| 类型系统 | 内置类型（string / int / float / bool / json）；自定义 type；枚举；可选类型 `?`；数组 `[]`；泛型 |
| 编译目标 | Python（sync/async）、TypeScript、Go（实验性） |
| 模板/代码分离 | `.baml` 文件定义模板 + 类型（声明式）；宿主语言处理控制流（命令式）——**语法层面强制分离** |
| 高级特性 | 函数组合（链式调用）；Fallback 链（模型 A 失败 → 模型 B）；动态提示（模板内条件逻辑） |
| IDE 支持 | VS Code 扩展：语法高亮、Schema 校验、内联测试运行、错误提示 |
| Playground | Web UI 测试函数；实时模型反馈；测试历史 |

```
// BAML 示例：类型 + 模板在同一个 .baml 文件中声明
type Person { name: string; age: int; emails: string[] }

function ExtractPersonInfo(text: string) -> Person {
    client.gpt4o
    prompt #"Extract person info from: {text}"#
}
```

#### DSPy（签名 + 模块 + 自动优化）

| 维度 | 功能 |
|------|------|
| 核心理念 | **提示词从签名自动生成**，开发者只定义输入/输出契约 |
| 签名系统 | `dspy.Signature`：声明 InputField / OutputField + 描述；框架自动生成提示词文本 |
| 模块类型 | `ChainOfThought`（多步推理）；`ReAct`（推理 + 工具调用）；`MultiChainComparison`（多输出对比）；`ProgramOfThought`（先生成程序再执行）；自定义 Module |
| **自动优化** | Teleprompter 系列：`BootstrapFewShot`（从数据生成 few-shot 示例）；`MIPRO`（多跳优化）；`COPRO`（用小模型优化提示词）——**提示词不是人写的，是优化出来的** |
| 评估框架 | 自定义 metric 函数 + 数据集 → 自动评估 + 多线程并行 |
| 类型安全 | 输出按签名校验；校验失败自动重试 |

```python
# DSPy 示例：只定义签名，不写提示词
class ExtractFacts(dspy.Signature):
    """Answer questions with short factoid answers."""
    context = dspy.InputField(desc="may contain relevant facts")
    question = dspy.InputField()
    answer = dspy.OutputField(desc="often between 5-10 words")
```

#### Instructor（Pydantic 驱动）

| 维度 | 功能 |
|------|------|
| 核心理念 | **Pydantic 模型即提示词的结构化约束**，框架自动处理 Schema 注入和输出解析 |
| 校验 + 重试 | Pydantic 校验失败 → 自动重试（含退避策略）；自定义 validator；可配置最大重试次数 |
| 流式支持 | 流式返回 + **增量校验**（每个 chunk 部分校验） |
| 多模型 | OpenAI / Anthropic / Cohere / Ollama / 自定义端点 |
| 高级特性 | 嵌套类型组合；Union 类型（多种合法输出）；自定义序列化策略 |
| 异步 | 完整 async/await 支持；批量并行请求 |

```python
# Instructor 示例：类型即约束
class Concept(BaseModel):
    name: str
    type: Literal["person", "place", "event"]
    description: str = Field(..., max_length=200)

result = client.chat.completions.create(
    response_model=list[Concept],  # 自动注入 Schema 约束
    messages=[{"role": "user", "content": chunk}],
)
```

#### Mirascope（装饰器驱动 + 多 Provider 抽象）

| 维度 | 功能 |
|------|------|
| 核心理念 | 装饰器标注函数 → 自动绑定到 LLM Provider；**换 Provider 只改装饰器** |
| Provider 抽象 | `@openai_call` / `@anthropic_call` / `@ollama_call` 等——同一函数体，换装饰器即换模型 |
| 流式 | `.stream()` 方法；逐 chunk 返回 |
| 工具调用 | 装饰器定义工具；自动绑定到调用；内置结果处理 |
| 与 Instructor 的区别 | Mirascope 侧重 Provider 抽象和装饰器模式；Instructor 侧重 Pydantic 校验和重试 |

### 6.3 提示词评估与测试

#### Promptfoo（CLI 评估框架）

| 维度 | 功能 |
|------|------|
| 配置格式 | YAML 定义：prompts × models × test cases 的矩阵 |
| 断言类型 | 字符串匹配（contains / exact / regex / similarity）；**JavaScript 自定义断言**；**LLM 评分**（用另一个 LLM 按 rubric 评分）；外部 API 断言；复合断言（AND/OR） |
| 模型对比 | Side-by-side 矩阵：prompt × model 的通过率/成本/延迟 |
| CI 集成 | CLI 工具 → 退出码表示通过/失败；JSON 输出；GitHub Actions 集成 |
| 高级特性 | 变量模板 + 数据生成器；缓存减少 API 调用；限流控制；批量处理 |

```yaml
# Promptfoo 配置示例
prompts:
  - "You are a helpful assistant. Answer: {{query}}"
  - "Be concise. Query: {{query}}"
tests:
  - vars: { query: "What is 2+2?" }
    assert:
      - type: contains
        value: "4"
      - type: llm-rubric
        value: "Is the response accurate and concise?"
models: [gpt-4o, claude-sonnet]
```

#### Phoenix（Arize）

| 维度 | 功能 |
|------|------|
| 定位 | LLM 评估 + 可观测性平台（提示词评估是其中一部分） |
| 评估类型 | 幻觉检测；毒性检测；相关性评分；**无参考评估**（不需要 gold standard）；自定义 LLM 链评估器 |
| 集成 | Python 库 + Web UI；支持多 Provider |
| 可观测性 | Trace 收集分析；延迟分解；Token 用量追踪；错误分类 |

### 6.4 功能维度对比矩阵

| 功能 | PromptLayer | Humanloop | Langfuse | LangSmith | Braintrust | BAML | DSPy | Instructor | Promptfoo |
|------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Web 编辑器 | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — |
| 版本管理 | ✓ | ✓ | ✓ | ✓✓ | ✓ | Git | — | — | 配置文件 |
| A/B 测试 | ✓ | ✓ | ✓ | ✓ | ✓✓ | — | — | — | ✓ |
| 数据集评估 | ✓ | ✓ | ✓ | ✓✓ | ✓ | 有限 | ✓ | 有限 | ✓✓ |
| 自定义评分 | ✓ | ✓✓ | ✓ | ✓✓ | ✓✓ | — | ✓ | ✓ | ✓✓ |
| 类型安全 | — | — | — | — | — | ✓✓ | ✓ | ✓✓ | — |
| 自动优化 | — | — | — | — | — | — | ✓✓ | — | — |
| Tracing | 有限 | 有限 | ✓✓ | ✓✓ | ✓ | — | — | — | — |
| 开源 | — | — | ✓ | — | — | ✓ | ✓ | ✓ | ✓ |
| 可自托管 | — | — | ✓ | — | — | ✓ | ✓ | ✓ | ✓ |

### 6.5 业界共识模式

**版本推进流水线**：几乎所有平台都遵循 **Development → Staging → Production** 的三阶段模型，提示词版本独立于代码版本推进。

**评估驱动的版本决策**：
1. 创建测试数据集（输入 + 期望输出）
2. 对数据集执行提示词版本
3. 评分（自动 + 人工）
4. 跨版本对比指标
5. 将胜出版本推进到 Production

**默认 + 覆盖的 resolve 链**：
```
resolve(prompt_name, tenant?) →
    用户覆盖版本 ?? 租户默认版本 ?? 系统默认版本
```

**类型安全的光谱**：
- 管理平台（SaaS）：类型校验薄弱，依赖下游代码
- 代码框架（BAML / Instructor）：编译时或运行时完整类型校验
- 两者互补而非互斥——管理平台负责版本/评估，代码框架负责类型安全

### 6.6 尚无统一标准

目前**没有**类似 REST/OpenAPI 那样的提示词交换标准。原因：

- 提示词高度领域特定，难以标准化格式
- 各框架的模板语法差异大（Mustache / Jinja2 / BAML / 纯字符串）
- MCP 协议面向工具/资源集成，不专门处理提示词定义

Promptfoo 等评估工具在推动 YAML 格式的提示词定义，但远未成为标准。

### 6.7 DSPy 深入解读：提示词从"手写"到"自动生成"

DSPy 的核心思路与其他框架截然不同：**开发者不写提示词文本，只定义输入/输出契约（签名），框架自动生成并优化提示词。**

#### 传统方式 vs DSPy

```
传统：手写 prompt → 跑一下 → 效果不好 → 改 prompt → 再跑 → 反复调
DSPy：定义签名 → 给数据集 + 评估指标 → 框架自动搜索最优 prompt
```

类比：传统 prompt 工程 ≈ 手写汇编；DSPy ≈ 写高级语言让编译器优化。

#### 三步工作流

**第一步：定义签名（Signature）**——只声明输入输出的语义，不写任何 prompt 文本：

```python
class SummarizeSubtitle(dspy.Signature):
    """将视频字幕压缩为结构化摘要"""
    subtitle: str = dspy.InputField(desc="完整字幕文本")
    summary: str = dspy.OutputField(desc="JSON 格式摘要，含主题和关键点")
```

**第二步：组合模块（Module）**——像写普通程序一样组合多步调用：

```python
class ExtractAndSummarize(dspy.Module):
    def __init__(self):
        self.extract = dspy.ChainOfThought("subtitle -> concepts")
        self.summarize = dspy.ChainOfThought(SummarizeSubtitle)

    def forward(self, subtitle):
        concepts = self.extract(subtitle=subtitle)
        return self.summarize(subtitle=subtitle, concepts=concepts)
```

**第三步：自动优化（Teleprompter/Optimizer）**——给一组示例数据 + 评估指标，DSPy 自动尝试不同的 prompt 写法（包括 few-shot 示例选择、指令措辞、CoT 策略），找到在评估指标上得分最高的 prompt：

```python
optimizer = dspy.BootstrapFewShot(metric=my_metric, max_bootstrapped_demos=4)
optimized_program = optimizer.compile(ExtractAndSummarize(), trainset=examples)
# optimized_program 内部的 prompt 文本是优化器搜索出来的，不是人写的
```

#### 对 Fredica 的意义

DSPy 的理念和当前 PromptScript 方向是**互补而非对立**的：

- **PromptScript**：让用户精细控制 prompt 文本——适合有明确意图和个人风格的场景
- **DSPy 式自动优化**：隐藏 prompt 文本，用数据驱动搜索——适合结构化提取等"效果可量化"的场景

可借鉴的点：如果 Fredica 有评估数据集（如 Weben 概念提取的标注数据），可以用类似 Teleprompter 的机制自动搜索更好的 prompt 变体，而不是完全依赖人工迭代。

### 6.8 自定义 LLM 链评估器：具体实现

"自定义 LLM 链评估器"的本质是：**用一个 LLM 当裁判，按开发者定义的评分标准给另一个 LLM 的输出打分。**

#### 最简实现：Promptfoo 的 `llm-rubric`

```yaml
# promptfoo 配置
tests:
  - vars:
      subtitle: "（一段字幕文本）"
    assert:
      - type: llm-rubric
        value: "摘要必须包含所有主要话题，不能遗漏超过20%的内容，且不能包含字幕中没有的信息"
```

背后发生的事：

1. **被评估的 LLM** 拿到 subtitle，生成 summary
2. **评估器构造 judge prompt**：
   ```
   你是一个评估专家。请根据以下标准评估这段输出：

   评分标准：摘要必须包含所有主要话题，不能遗漏超过20%的内容，
   且不能包含字幕中没有的信息

   原始输入：{subtitle}
   模型输出：{summary}

   请给出 pass/fail 判定和理由。输出 JSON：{"pass": true/false, "reason": "..."}
   ```
3. **Judge 模型**（通常用 GPT-4 级别）执行这个 prompt，返回判定结果
4. 框架解析 JSON，汇总到评估报告

#### 多维链式评估：Phoenix/Langfuse 风格

实际项目中，一个评估往往拆成多个维度，每个维度一个 judge call：

```python
def evaluate_summary(input_subtitle, model_output):
    # 维度1：完整性（有没有遗漏关键信息）—— LLM 评估
    completeness = llm_judge(
        prompt=COMPLETENESS_TEMPLATE,
        vars={"input": input_subtitle, "output": model_output},
        score_range=(0, 5)
    )

    # 维度2：忠实度（有没有编造信息）—— LLM 评估
    faithfulness = llm_judge(
        prompt=FAITHFULNESS_TEMPLATE,
        vars={"input": input_subtitle, "output": model_output},
        score_range=(0, 5)
    )

    # 维度3：格式合规（JSON 结构是否正确）—— 代码检查，不需要 LLM
    format_ok = validate_json_schema(model_output, expected_schema)

    return {
        "completeness": completeness,
        "faithfulness": faithfulness,
        "format": format_ok,
        "overall": weighted_average(...)
    }
```

其中忠实度评估模板（Phoenix 实际模板简化版）：

```
给定以下文本和基于该文本生成的摘要，判断摘要中的每个陈述是否能从原文中找到依据。

原文：{input}
摘要：{output}

对摘要中的每个陈述：
1. 提取该陈述
2. 在原文中寻找支持证据
3. 判定：SUPPORTED / NOT_SUPPORTED

最终给出忠实度分数（0-5），其中 5 = 所有陈述都有原文支持。
```

#### 对 Fredica 的实际应用场景

Weben 概念提取是一个天然的评估场景：

| 评估维度 | 方法 | 说明 |
|---------|------|------|
| 覆盖率 | LLM judge | 字幕中的关键概念是否都被提取了？ |
| 准确性 | LLM judge | 提取的概念描述是否与字幕内容一致？ |
| 格式合规 | 代码检查 | JSON 是否合法、字段是否完整？ |
| 去重 | 代码检查 | 有没有重复概念？ |

#### 关键实现细节

- **Judge 模型要比被评估模型强**（或至少同级），否则评估不可靠
- **评估 prompt 本身也需要迭代**——这是个递归问题，但实践中 judge prompt 比业务 prompt 稳定得多，因为"判断好坏"比"生成好内容"简单
- **成本**：每条测试用例的评估要额外调 1-N 次 LLM，数据集大时成本不低，一般只在关键版本迭代时跑全量评估
- **混合评估最实用**：代码能检查的（格式、去重、长度）用代码；只有主观质量维度才用 LLM judge

### 6.9 对 Fredica 的启发

综合调研，几个关键判断：

1. **Prompt-as-Data 方向正确**——5.4 节选择数据库为主的方案与业界趋势一致
2. **不必自创 DSL**——BAML 的 DSL 路线虽然优雅，但学习成本和生态风险不值得。Fredica 已有 GraalJS 沙箱，用 JS/TS 作为编排语言是更务实的选择
3. **Schema 驱动值得引入**——Instructor/DSPy 的类型驱动思路可以减少提示词中的样板文本。具体做法：编排脚本中声明输出 Schema，运行时自动注入"请按以下 JSON Schema 输出"的约束，提示词工程师不需要手写格式说明
4. **签名式声明可作为高级模式**——对于结构化提取类任务，可以提供"只定义输入/输出类型，自动生成提示词"的快捷方式，但不强制
5. **评估/对比能力是刚需**——所有成熟平台都强调 prompt 版本间的效果对比（A/B 测试、评分）。这比版本管理本身更重要——能回滚不够，要知道**该不该回滚**
6. **Tracing 关联是差异化能力**——Langfuse / LangSmith 的核心优势不是编辑器，而是"每次 LLM 调用都能追溯到具体的 prompt 版本"。Fredica 已有 Task/WorkflowRun 体系，天然具备关联基础
7. **Portkey 的网关模式值得关注**——在网关层切换提示词版本，应用代码完全无感。Fredica 的 Ktor API 层天然可以充当这个网关角色

---

## 7. 业界框架的五大关注领域

综合 6.1–6.8 的调研，业界框架大多专注于以下五个方向：

| # | 领域 | 代表方案 | Fredica 现状 |
|---|------|---------|-------------|
| 1 | 提示词模板版本管理 | PromptLayer / Langfuse / LangSmith | 有模板库（DB 存储），无版本 diff |
| 2 | 提示词跑数据集测试评估 | Promptfoo / LangSmith Experiments | 无 |
| 3 | 类型检查 / 重试 / 纠错 | BAML / Instructor / DSPy | 无（输出格式靠 prompt 文本描述） |
| 4 | 幻觉检测 / 毒性检测 | Phoenix / Langfuse Evals | 无 |
| 5 | 以上过程的可观测性 | Langfuse Tracing / LangSmith | 有 Task/WorkflowRun 体系，但未关联 prompt 版本 |

其中 DSPy 独树一帜地走了"自动生成 + 优化"路线（6.7 节），LLM 链评估器（6.8 节）是领域 2 和 4 的核心实现手段。

---

## 8. 其他开放问题

- 编排脚本中的 `runPrompt("template-id", vars)` 调用，是同步渲染模板再调 LLM，还是返回渲染后的文本让脚本自己决定何时调 LLM？
- 多个 Prompt Unit 之间如何组合？是在 WorkflowRun 层编排（每个 Unit 是一个 Task），还是允许 Unit 之间直接调用？
- 提示词模板的"预设变量"（如 <code v-pre>{{subtitle}}</code>）如何发现和文档化？当前 `getVar(key)` 的 key 是硬编码字符串，缺乏自动补全和校验
- 数据库存储的编排脚本如何做安全校验？当前 GraalJS 沙箱已验证可行，但脚本来源从"开发者提交的代码"变成"用户在 Web 编辑器中输入的代码"，信任边界发生了变化
