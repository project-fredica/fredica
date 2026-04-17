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

### 6.1 提示词版本管理：Prompt-as-Data 正在胜出

| 平台 | 存储方式 | 版本管理 | 与代码的关系 |
|------|---------|---------|-------------|
| **PromptLayer** | 中心化数据库 | 整数递增版本号，运行时 API 拉取 | 完全脱离代码部署 |
| **Humanloop** | 数据库，每个版本独立追踪评分 | 可序列化为 JSON 存 Git | 数据库为主，Git 为辅 |
| **Langfuse**（开源） | 自托管数据库 | 按 prompt name + version 管理 | 独立于代码仓库 |
| **LangSmith** | 后端数据库 + Hub | UI 编辑 + 可选 Git 同步 | 双轨：DB 快速迭代，Git 审计 |
| **Braintrust** | 数据库 | 带评估指标的版本链 | 独立于代码 |

**共识**：生产环境中，提示词作为**数据**（而非代码）管理正在成为主流。原因很直接——提示词的变更频率、变更者、审核流程都和代码不同，硬塞进同一个 Git 流程只会互相拖累。

### 6.2 模板与控制流分离：三种流派

**流派 1：显式 DSL 分离（BAML）**

BAML（Boundary AI Markup Language）是目前模板/代码分离做得最显式的框架。用专门的 `.baml` 文件定义模板和类型，编译为类型安全的 SDK：

```
// .baml 文件：纯声明，定义模板 + 类型
function ExtractFacts(article: string) -> list<string> {
    client: "gpt-4"
    prompt #"Extract key facts from: {article}"#
}
```

- 优点：模板和控制流在语法层面就是分开的；编译时类型检查
- 缺点：需要学一门新 DSL；生态较小
- 启发：**类型安全的模板定义**是值得借鉴的方向

**流派 2：签名式声明（DSPy）**

DSPy 的激进之处在于：**提示词不是人写的，而是从"签名"自动生成的**。

```python
class ExtractFacts(dspy.Signature):
    context: str = dspy.InputField(desc="article text")
    facts: list[str] = dspy.OutputField(desc="key facts")
```

开发者只定义输入/输出的类型和描述，框架自动生成提示词文本，甚至可以通过 Teleprompter 自动优化。

- 优点：彻底消除了"谁来写提示词"的问题；可自动优化
- 缺点：失去对提示词措辞的精确控制；不适合需要特定领域表达的场景
- 启发：**对于结构化提取类任务**（如 Weben 概念提取），签名式声明可能比手写提示词更高效

**流派 3：类型注解驱动（Instructor / Marvin）**

用 Pydantic 模型（或 TypeScript 类型）作为提示词的"骨架"，框架从类型定义自动生成结构化输出的约束：

```python
class Concept(BaseModel):
    name: str
    type: Literal["person", "place", "event"]
    description: str

result = client.chat.completions.create(
    response_model=list[Concept],  # 类型即约束
    messages=[{"role": "user", "content": chunk}],
)
```

- 优点：零模板代码；类型安全；和现有代码无缝集成
- 缺点：提示词隐式生成，调试困难；复杂提示词仍需手写
- 启发：**Schema 驱动的结构化输出**可以大幅减少提示词中"请以 JSON 格式输出"这类样板文本

### 6.3 默认提示词 + 用户覆盖：已有成熟模式

业界的共识模式：

```
resolve(prompt_name, tenant?) → 
    用户覆盖版本 ?? 租户默认版本 ?? 系统默认版本
```

- **LangSmith Hub**：提供"prompt recipes"，团队可以 Fork 并定制
- **Langfuse**：按 prompt name 查找，支持标记 `production` / `staging` 版本
- **Instructor / Marvin**：从类型定义自动生成默认提示词，用户通过传入 `system_prompt` 参数覆盖

这和 5.5 节提出的"代码内嵌 + 数据库覆盖"模型一致，说明方向是对的。

### 6.4 尚无统一标准

目前**没有**类似 REST/OpenAPI 那样的提示词交换标准。原因：

- 提示词高度领域特定，难以标准化格式
- 各框架的模板语法差异大（Mustache / Jinja2 / BAML / 纯字符串）
- MCP 协议面向工具/资源集成，不专门处理提示词定义

Promptfoo 等评估工具在推动 YAML 格式的提示词定义，但远未成为标准。

### 6.5 对 Fredica 的启发

综合调研，几个关键判断：

1. **Prompt-as-Data 方向正确**——5.4 节选择数据库为主的方案与业界趋势一致
2. **不必自创 DSL**——BAML 的 DSL 路线虽然优雅，但学习成本和生态风险不值得。Fredica 已有 GraalJS 沙箱，用 JS/TS 作为编排语言是更务实的选择
3. **Schema 驱动值得引入**——Instructor/DSPy 的类型驱动思路可以减少提示词中的样板文本。具体做法：编排脚本中声明输出 Schema，运行时自动注入"请按以下 JSON Schema 输出"的约束，提示词工程师不需要手写格式说明
4. **签名式声明可作为高级模式**——对于结构化提取类任务，可以提供"只定义输入/输出类型，自动生成提示词"的快捷方式，但不强制
5. **评估/对比能力是刚需**——所有成熟平台都强调 prompt 版本间的效果对比（A/B 测试、评分）。这比版本管理本身更重要——能回滚不够，要知道**该不该回滚**

---

## 7. 其他开放问题

- 编排脚本中的 `runPrompt("template-id", vars)` 调用，是同步渲染模板再调 LLM，还是返回渲染后的文本让脚本自己决定何时调 LLM？
- 多个 Prompt Unit 之间如何组合？是在 WorkflowRun 层编排（每个 Unit 是一个 Task），还是允许 Unit 之间直接调用？
- 提示词模板的"预设变量"（如 <code v-pre>{{subtitle}}</code>）如何发现和文档化？当前 `getVar(key)` 的 key 是硬编码字符串，缺乏自动补全和校验
- 数据库存储的编排脚本如何做安全校验？当前 GraalJS 沙箱已验证可行，但脚本来源从"开发者提交的代码"变成"用户在 Web 编辑器中输入的代码"，信任边界发生了变化
