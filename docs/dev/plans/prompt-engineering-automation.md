---
title: 提示词工程自动化 — 技术路线评估
order: 530
---

# 提示词工程自动化 — 技术路线评估

> **文档状态**：技术路线评估（已整理）+ 架构设计讨论（2026-04-18 更新）
> **创建日期**：2026-04-17
> **整理日期**：2026-04-18
> **废弃前置**：旧 PromptGraph 三表模型（PromptGraphDef / PromptGraphRun / PromptNodeRun）及 DAG 引擎已废弃。旧 PromptTemplate（文本快照模式）已废弃，统一使用 PromptChain。本文档也取代 `prompt-builder-design.md`、`prompt-builder-next-phase-plan.md` 的方向。

---

## 1. 问题定义

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

## 2. 设计决策

### 2.1 三种路线对比（设计探索背景）

> **说明**：本节是架构探索阶段的分析记录，不代表最终实现方案。最终方案见 §2.2。

这是整个方案的关键取舍。ASR 后处理的经验告诉我们：**分批、循环等控制流必须依赖代码，但编辑提示词文本时应当接触的代码越少越好。**

#### 路线 A：纯代码

提示词工程师直接编辑 `.prompt.ts` 文件，所有逻辑（提示词文本 + 控制流 + Schema）都在代码中。

- 优点：AI 可读写、Git 友好、表达力最强
- 缺点：提示词工程师改一句 prompt 要在代码中定位、小心不破坏逻辑
- 最终舍弃原因：无法为提示词文本提供独立编辑体验，不适合多角色协作

#### 路线 B：文件分层（.prompt.md + 编排脚本）

将"提示词文本"和"控制流代码"物理分离为两类文件：提示词工程师编辑 `.prompt.md`，开发者编辑独立的编排脚本。

- 优点：提示词工程师只编辑 Markdown 文件，零代码接触
- 缺点：需要设计模板 ↔ 脚本的文件绑定机制；两套文件的同步维护负担
- 最终舍弃原因：文件绑定机制引入额外复杂度；提示词内联到节点后这个问题天然消失

#### 路线 C：声明式配置（YAML/JSON）

用 YAML/JSON 声明常见模式（顺序执行、MapReduce、条件分支），复杂场景降级到代码逃生舱。

- 优点：常见模式零代码；声明式易于可视化
- 缺点：本质上是在重新发明 n8n，复杂场景的声明式表达比代码更难读
- 最终舍弃原因：无法优雅表达任意控制流；Fredica 已有 GraalJS 沙箱，代码层已解决

### 2.2 最终架构：PromptChain 双层模型

三条路线各有不足，最终采用的设计是**将声明层的结构化优势与执行层的代码表达力合并到同一个数据单元（PromptChain）中**：

```
┌─────────────────────────────────────────────────────────────────┐
│  声明层（PromptChain.nodes + edges）                              │
│  ── 节点列表：LLM_CALL / MAP / REDUCE / SWITCH / TRANSFORM ...   │
│  ── 每个 LLM_CALL 节点直接内嵌 promptContent（文本，无独立文件）   │
│  ── 边上携带 schema?: JSONSchema（Schema-on-Edge，类型约束来源）   │
│  ── ReactFlow 从此层渲染 DAG 可视化图                             │
│  ── generateHeader() 从此层推导 TypeScript 类型定义               │
├─────────────────────────────────────────────────────────────────┤
│  执行层（PromptChain.userScript）                                 │
│  ── 唯一的 main() 函数，控制整条链的执行逻辑                        │
│  ── runPrompt<T>(id, vars) 调用声明层节点（泛型类型绑定）           │
│  ── 分批/循环/条件分支/MapReduce 全部写在 main() 里               │
│  ── Monaco Editor 中 readonlyHeader 区域由 generateHeader() 生成 │
│  ── 只有 userScript 部分提交保存，header 永不存储                  │
└─────────────────────────────────────────────────────────────────┘
```

**核心设计原则**：

1. **提示词内联，不引用外部文件**——`PromptChainNode.promptContent` 直接存储提示词文本。路线 B 的文件绑定问题彻底消失；Monaco 模型池（`inmemory://chain-{id}/node-{nodeId}/prompt`）只是编辑器层的内存镜像，不对应磁盘文件。

2. **声明层是结构，执行层是代码**——声明层的节点/边提供了机器可读的结构（Schema 类型推导、DAG 可视化、运行态高亮的 hook 点）；执行层的 `main()` 保留了代码的全部表达力（任意控制流，无 DSL 限制）。声明层不存储条件表达式——SWITCH 的 if/else 直接写在 `main()` 里，声明层只标注节点类型和出边 label。

3. **类型安全由 generateHeader() 桥接**——`generateHeader(nodes, edges)` 是纯函数，从声明层的 Schema-on-Edge 推导出 `NodeVarMap` / `NodeResultMap` 类型映射，注入 Monaco 作为只读 header。`runPrompt<T extends NodeId>(id, vars: NodeVarMap[T])` 的泛型约束由此获得 TypeScript 类型检查。

4. **PromptTemplate 废弃**——旧的"文本快照"模式（`PromptTemplate` 表）不再新建；迁移期保留旧路由兼容现有数据，新功能全部使用 PromptChain。详细数据结构见 §5.4。

### 2.3 提示词存储策略

PromptChain 的提示词文本（`promptContent`）和编排脚本（`userScript`）都内嵌在同一个数据单元里，但这引出了一个更深层的问题：**PromptChain 是应用数据，不是工程代码，不应该跟工程代码走同一个 Git 流程**。

| 维度 | 工程代码 | PromptChain（提示词 + 编排脚本） |
|------|---------|------------------------|
| 变更频率 | 低（按 sprint/版本） | 高（每天多次迭代） |
| 变更者 | 开发者 | 提示词工程师 / 运营 / 甚至用户 |
| 审核流程 | PR → Code Review → CI | 不需要也不应该走 PR 流程 |
| 部署节奏 | 跟随应用发版 | 即时生效 |
| 回滚粒度 | 整个应用版本 | 单个提示词的某次修改 |

**结论：方案 1（数据库为主）最符合实际需求**：

1. **与现有系统一致**——当前模板库已经是数据库存储，扩展为"PromptChain + 版本历史"是自然演进
2. **目标用户画像**——提示词的主要编辑者不是开发者，不应该要求他们学 Git
3. **即时生效**——提示词迭代的核心诉求是"改了就能试"，数据库方案天然支持
4. **AI 对接**——通过 MCP tools 暴露 CRUD API，AI 工具不需要文件系统访问也能操作

方案 3（混合模式：数据库为主，Git 导出为辅）作为增强——当需要批量操作或备份时，提供导出/导入能力。

### 2.4 默认提示词的 builtin_version 两层模型

方案 1 引入了一个新问题：**APP 的主打功能需要开箱即用的默认提示词**，这些默认提示词必须随应用发布、需要开发者高频迭代、升级时可能需要更新。

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

1. **默认提示词跟代码走 Git**——开发阶段，默认提示词就是代码的一部分（`resources/prompts/` 或 Kotlin 常量），开发者用正常的 Git 流程迭代
2. **首次使用时 seed 到数据库**——应用启动检测到数据库中没有该提示词 → 从 resources 复制一份到数据库
3. **升级时条件更新**——每个默认提示词带一个 `builtin_version`（整数递增）。启动时比较：
   - 数据库中的 `builtin_version` < 代码中的 → 用户未改过则静默更新；用户改过则提示"有新默认版本可用"
   - 用户可以选择"查看差异并合并"或"忽略"
4. **"重置为默认"**——用户随时可以丢弃自己的修改，回到当前版本的默认提示词

**类比：** 这和 VS Code 的 `defaultSettings.json`（只读，随版本更新）+ `settings.json`（用户覆盖）是同一个模式。

**业界共识的 resolve 链**：
```
resolve(prompt_name, tenant?) →
    用户覆盖版本 ?? 租户默认版本 ?? 系统默认版本
```

### 2.5 关键技术决策汇总

| 决策点 | 结论 |
|--------|------|
| 模板格式 | Markdown + Frontmatter（Mustache/Handlebars 变量语法），多轮对话用 `# System` / `# User` 标题分隔 |
| 提示词存储 | 内嵌于 `PromptChainNode.promptContent`，不引用外部 PromptTemplate；PromptTemplate 废弃 |
| 编排脚本绑定 | 声明层（nodes/edges）与执行层（userScript）物理分离。节点声明是 ReactFlow 渲染源；main() 通过 `runPrompt<T>(id, vars)` 引用声明层节点。只读 header 由 `generateHeader()` 纯函数从声明层推导，不存储 |
| SWITCH 条件逻辑 | 写在 main() 中（if/switch 语句），声明层只标注节点类型和出边 label，不存储条件表达式 |
| Schema 编辑器位置 | `app/components/schema/SchemaFormEditor.tsx`（共用底层组件，三处 Schema UI 均引用此处）|
| PromptBuilder 嵌入 | `externalModel?: monaco.editor.ITextModel` prop；有值时 DAG 嵌入模式，无值时独立模式 |
| Monaco 实例管理 | DAG 场景：单一实例 + `inmemory://` URI model 池，切换节点 = setModel()；独立场景：每 PromptBuilder 实例独立 |
| 沙箱引擎 | 短期继续用 GraalJS（编排脚本用纯 JS 足够），长期如需 TS 类型检查再评估 Deno |
| AI 辅助对接 | MCP 面向开发者/CLI（**需另开独立设计文档**）；内置 AI 助手面向 UI 用户；两者不冲突 |
| 不必自创 DSL | BAML 的 DSL 路线学习成本和生态风险不值得，Fredica 已有 GraalJS 沙箱，用 JS/TS 作为编排语言更务实 |

---

## 3. 行业调研

### 3.1 SaaS 平台类

| 产品 | 核心定位 | 关键特性 | 局限 |
|------|---------|---------|------|
| PromptLayer | 提示词版本管理 + 可观测性 | 请求日志、A/B 测试、版本 diff | 无编排能力，纯管理层 |
| Humanloop | 提示词协作 + 评估 | 多角色协作、评估数据集、部署管道 | SaaS 锁定，无本地化 |
| Langfuse | LLM 可观测性（开源） | Trace/Span 追踪、评估、数据集 | 侧重观测，编排能力弱 |
| LangSmith | LangChain 生态可观测性 | 与 LangChain 深度集成、评估 | 强依赖 LangChain |
| Braintrust | 评估驱动的提示词开发 | 数据集管理、评分函数、CI 集成 | 评估为主，编排为辅 |
| Portkey | LLM 网关 + 可观测性 | 多模型路由、fallback、缓存 | 网关层，不涉及提示词结构 |

**共同模式**：所有 SaaS 平台都把"版本管理 + 评估数据集 + 可观测性"作为核心三角，但编排能力普遍薄弱——它们假设用户已经有编排框架（LangChain/自研），只做管理层。

### 3.2 Prompt-as-Code 工具类

| 工具 | 核心理念 | 关键特性 | 局限 |
|------|---------|---------|------|
| BAML | 提示词专用 DSL | 强类型输入输出、自动解析、测试框架 | 新 DSL 学习成本，生态小 |
| DSPy | 声明式提示词优化 | 自动优化 few-shot、模块化组合 | 学术向，生产落地复杂 |
| Instructor | Python 结构化输出 | Pydantic 模型驱动、自动重试 | Python 专属，无 UI |
| Mirascope | 类型安全提示词构建 | 装饰器 API、多模型统一接口 | 代码库，无可视化 |

**BAML 的启发**：将提示词的输入/输出契约（Schema）与提示词文本分离，用类型系统约束 LLM 输出格式。Fredica 不采用 BAML 的 DSL 路线，但借鉴其"Schema-on-Edge"思想——在 DAG 的边上绑定 JSON Schema，而非在提示词文本里描述格式。

**DSPy 的启发**：提示词优化不应该是手工调参，而是可以半自动化——给定评估数据集和评分函数，优化器自动搜索更好的 few-shot 示例或指令措辞。Fredica 的优化器模块（§5.3）借鉴此思路。

### 3.3 评估框架类

| 工具 | 定位 | 关键特性 |
|------|------|---------|
| Promptfoo | 提示词测试 CLI | YAML 配置测试用例、多模型对比、CI 集成 |
| Phoenix (Arize) | LLM 可观测性 + 评估 | OpenTelemetry 兼容、嵌入可视化、评估数据集 |

**Promptfoo 的启发**：测试用例应该是声明式的（YAML/JSON），可以版本化、可以在 CI 中运行。Fredica 的评估器（§5.3）采用类似的数据集 + 评分函数模式。

### 3.4 业界方案对比矩阵

| 维度 | PromptLayer | Humanloop | BAML | DSPy | Fredica 目标 |
|------|------------|-----------|------|------|-------------|
| 版本管理 | ✓ | ✓ | Git | Git | 数据库 + builtin_version |
| 可视化编排 | ✗ | 部分 | ✗ | ✗ | React Flow DAG |
| Schema 约束 | ✗ | 部分 | ✓ | ✓ | Schema-on-Edge |
| AI 辅助编写 | 部分 | 部分 | ✗ | ✓ | MCP + 内置助手 |
| 本地/私有化部署 | ✗ | ✗ | ✓ | ✓ | ✓（本地部署 Web 服务 + 多租户）|
| 多角色协作 | ✓ | ✓ | ✗ | ✗ | 声明层/执行层分离（PromptChain 双层模型）|
| 自动优化 | ✗ | 部分 | ✗ | ✓ | 半自动优化器 |

### 3.5 五大关注领域与 Fredica 启发

1. **版本管理**：业界共识是数据库存储 + Git 导出辅助。Fredica 采用 `builtin_version` 两层模型（§2.4），与 VS Code settings 同构。

2. **Schema 约束**：BAML 证明了强类型输出的价值。Fredica 在 DAG 边上绑定 JSON Schema，比在提示词文本里描述格式更可靠。

3. **可观测性**：Langfuse/LangSmith 的 Trace/Span 模型值得借鉴。Fredica 的 `PromptNodeRun` 记录每次执行的输入输出，是可观测性的基础。

4. **评估驱动**：Braintrust/DSPy 证明了"数据集 + 评分函数"是提示词迭代的正确姿势。Fredica 优化器模块将此内化为工作流的一部分。

5. **AI 辅助**：所有工具都在往"AI 帮你写提示词"方向走。Fredica 的差异化在于：AI 不只是写文本，还能操作 DAG 结构（通过 MCP tools）。MCP 集成需另开独立文档设计。

#### 五大启发对各模块的满足度验证

以下矩阵验证 §5 各模块是否覆盖了五大关注领域的需求。"✅ 满足"代表模块设计直接覆盖该启发，"△ 部分"代表有基础但不完整，"— 不涉及"代表该模块不负责此领域。

| 模块 | 版本管理 | Schema 约束 | 可观测性 | 评估驱动 | AI 辅助 |
|------|---------|------------|---------|---------|---------|
| **§4.2 DAG 编辑器** | △ 存取 PromptChain 版本，但历史 diff 在 §5.4 实现 | ✅ Schema-on-Edge、节点类型化输出 Handle | △ 运行态节点高亮（调试可视），不记录 Trace | — 不直接执行评估 | △ 可视化结构供 AI 读写，但 MCP 接口另文设计 |
| **§5.1 Monaco Editor** | — 编辑器不管版本 | △ 编排脚本诊断捕获（编译错误）；提示词 Markdown 无 Schema | △ 保存前 getModelMarkers 校验，非运行时观测 | — 不涉及 | ✅ d.ts 动态注入为 AI 写编排脚本提供类型补全基础 |
| **§5.2 TypedVarRegistry** | — 不管版本 | ✅ 变量类型声明（PromptVarDescriptor）+ 生成 .d.ts；变量拼写错误编译期发现 | △ 间接支持：变量集合变化可感知上下文切换 | — | ✅ 生成 .d.ts 是 AI（Monaco 语言服务 / Copilot）能理解变量意图的前提 |
| **§5.3 评估系统** | ✅ EvalRun 绑定 chain_version，跨版本分数趋势图；版本推进到 staging 自动触发评估 | △ JsonSchemaMatch 作为评分维度之一，但 Schema 约束本身在 §4.2/§5.2 | ✅ PromptNodeRun 记录每次真实调用（latency/cost/token）；用户反馈可回溯到具体版本 | ✅ 核心模块：EvalDataset + EvalRubric + LlmJudge + 半自动优化器 | △ 优化器调用辅助 LLM 生成改写版本（指令变体），但需要人工确认 |
| **§5.4 链模板管理** | ✅ prompt_chain_version 表；draft→staging→production→archived 流水线；A/B 实验；diff 视图；一键回滚 | △ outputSchema 字段存储链出口 Schema，但 Schema 编辑器在 §5.5/schema-editor/ 包 | △ 版本状态变更有记录（saved_by、change_note），但无 Trace/Span 层 | ✅ 版本推进依赖评估通过；PromptExperiment 绑定 EvalSummary 决定胜出版本 | △ 市场拉取机制（§8.3）允许引入社区 chain，但 AI 写 chain 依赖 MCP |
| **§5.5 PromptBuilder 组件** | — 组件不直接管版本（依赖 §5.4） | ✅ 输出 Schema 面板（`app/components/schema/SchemaFormEditor`）；字段 CRUD；复制到提示词文本 | — | — | △ d.ts 补全（依赖 §5.2）；编辑提示词文本本身不感知 AI 意图 |

**差距分析**：

- **可观测性的盲点**：§5.1 Monaco 和 §5.4 链模板均无运行时 Trace，可观测性完全依赖 §5.3 的 `PromptNodeRun`。若 §5.3 未实现，整个系统的可观测层为零。**建议**：§5.3 应列为高优先级，而非 Phase 4 才开始。
- **AI 辅助的瓶颈**：AI 辅助提示词编写的最后一公里（DAG 结构操作 + 批量生成提示词）依赖 MCP 集成，而该模块被标记为另文设计。§5.1 的 d.ts 注入和 §5.2 的 TypedVarRegistry 是 AI 辅助的基础设施，应先行实现。
- **Schema 约束的连贯性**：§4.2 边上 Schema → §5.5 组件内 Schema 面板 → §5.2 变量类型 三处都涉及 Schema，但当前设计中它们是独立的。需要在实现时明确：`SchemaFormEditor`（`app/components/schema/`）是三处共用的底层组件，避免三套 Schema UI。
- **版本管理的完整性**：`PromptTemplate` 已废弃，统一使用 `PromptChain`——版本历史只有一套 UI，即 §5.4 的链版本管理页面。
---

## 4. 核心架构

### 4.1 两套系统的断裂分析

当前系统存在两套并行但断裂的机制：

```
现状：
┌─────────────────────────────────┐    ┌──────────────────────────────────┐
│  PromptBuilder（ASR 后处理）      │    │  PromptGraph（Weben 概念提取）     │
│  ── GraalJS 沙箱执行              │    │  ── DAG 三表模型（已废弃）          │
│  ── 模板库（文本快照）             │    │  ── 节点类型固定                   │
│  ── 无 Schema 约束               │    │  ── 无可视化编辑器                 │
│  ── 无可视化                     │    │  ── 无版本管理                    │
└─────────────────────────────────┘    └──────────────────────────────────┘
         ↓ 统一为                              ↓ 废弃，迁移到
┌─────────────────────────────────────────────────────────────────────────┐
│  PromptChain + DAG 编辑器（目标架构）                                     │
│  ── React Flow 可视化 DAG                                               │
│  ── Schema-on-Edge 类型约束                                              │
│  ── 节点声明层（nodes/edges）+ 执行层（userScript + generateHeader()）     │
│  ── PromptTemplate 废弃，提示词文本内嵌于 PromptChainNode.promptContent   │
│  ── 数据库版本管理 + builtin_version 两层模型                              │
│  ── MCP tools 暴露 CRUD API                                             │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 React Flow DAG 编辑器

#### 整体布局

```
┌──────────────────────────────────────────────────────────────────────┐
│  工具栏：节点类型选择 | 运行 | 保存 | 版本历史 | 导出                    │
├────────────────────────────────┬─────────────────────────────────────┤
│                                │  右侧面板（上下分割）                  │
│  DAG 画布（React Flow）         ├─────────────────────────────────────┤
│  ── 节点拖拽/连线               │  节点属性编辑器                       │
│  ── 边类型可视化                │  ── PromptBuilder（提示词文本）        │
│  ── 运行态高亮                  │  ── Schema 编辑器（JSON Schema 表单）  │
│  ── minimap                   │  ── 变量绑定（TypedVarRegistry）       │
│                                ├─────────────────────────────────────┤
│                                │  运行日志 / 输出预览                   │
└────────────────────────────────┴─────────────────────────────────────┘
```

#### 节点类型

| 节点类型 | 用途 | 输入 Handle | 输出 Handle |
|---------|------|------------|------------|
| `INPUT` | DAG 入口，绑定运行时变量 | 无 | 1+ 个类型化输出 |
| `LLM_CALL` | 单次 LLM 调用 | 1+ 个输入 | 1 个结构化输出 |
| `MAP` | 对列表并行执行子 DAG | 1 个列表输入 | 1 个列表输出 |
| `REDUCE` | 聚合多个输入为单一输出 | N 个输入 | 1 个输出 |
| `SWITCH` | 条件分支 | 1 个输入 | N 个条件输出 |
| `TRANSFORM` | JS 代码转换（无 LLM） | 1+ 个输入 | 1+ 个输出 |
| `OUTPUT` | DAG 出口，收集最终结果 | 1+ 个输入 | 无 |

#### 边渲染与 PromptBuilder 嵌入

- 边颜色编码：`string` → 蓝色，`string[]` → 橙色，`object` → 绿色，`unknown` → 灰色（类型推断失败）
- 边悬停显示 Schema tooltip（JSON Schema 摘要）
- 选中 `LLM_CALL` 节点时，右侧面板展开 PromptBuilder——"提示词"标签页编辑节点内嵌的 `promptContent`（Markdown 格式），"编排"标签页显示链级 `userScript`（含只读 header）

#### 运行态可视化

节点状态颜色：
- `pending` → 灰色边框
- `running` → 蓝色边框 + 脉冲动画
- `completed` → 绿色边框 + 输出预览 tooltip
- `failed` → 红色边框 + 错误信息 tooltip
- `cached` → 绿色边框 + 闪电图标（命中缓存）

#### 技术实现要点

节点数据结构（TypeScript）：

```typescript
// DAG 声明层 —— ReactFlow 的渲染数据源
// 与 main() 编排脚本物理分离；ReactFlow 从此声明推导节点图
interface PromptChainNode {
  id: string;                   // main() 中 runPrompt(id, vars) 的引用键
  type: "INPUT" | "LLM_CALL" | "MAP" | "REDUCE" | "SWITCH" | "TRANSFORM" | "OUTPUT";
  label: string;                // ReactFlow 显示名
  promptContent?: string;       // LLM_CALL 节点的提示词模板文本（内嵌，不引用外部模板）
  modelOverride?: string;       // 可选：覆盖链级默认模型
  cachePolicy?: CachePolicy;
  contextPolicy?: NodeContextPolicy;
  position: { x: number; y: number };  // ReactFlow 布局坐标
}

interface PromptChainEdge {
  id: string;
  source: string;               // 源节点 id
  target: string;               // 目标节点 id
  schema?: JSONSchema;          // Schema-on-Edge：声明数据契约，用于类型检查
  portType?: FlowPortType;
  label?: string;
}

// ReactFlow 内部使用的运行态扩展（不存入 DB）
interface PromptNodeData extends PromptChainNode {
  runState?: "pending" | "running" | "completed" | "failed" | "cached";
}
```

### 4.3 Schema-on-Edge 详细设计

#### 三种边类型

**原始数据边（Raw）**：传递 `string / number / boolean / string[]`，Schema 简单，通常由 INPUT 节点定义。

**结构化数据边（Structured）**：传递 JSON object，Schema 由上游 `LLM_CALL` 的 `outputSchema` 定义，下游节点可通过 JSONPath 提取字段。

**流式边（Streaming）**：传递 `AsyncIterable<string>`（SSE 流），仅用于 `LLM_CALL → OUTPUT` 的直接连接，不经过中间节点。

#### Handle 设计

```typescript
interface TypedHandle {
  id: string;
  direction: "input" | "output";
  portType: FlowPortType;
  schema?: JSONSchema;
  required: boolean;
  description?: string;
}
```

#### 连线校验规则

| 上游输出类型 | 下游输入类型 | 是否允许 | 说明 |
|------------|------------|---------|------|
| `string` | `string` | ✓ | 直接兼容 |
| `string[]` | `string[]` | ✓ | 直接兼容 |
| `string` | `string[]` | ✓ | 自动包装为单元素数组 |
| `object{A}` | `object{B}` | 条件 | B 是 A 的子集时允许（结构兼容） |
| `string` | `object` | ✗ | 需要显式 TRANSFORM 节点 |
| `object` | `string` | ✗ | 需要显式 TRANSFORM 节点 |

#### 类型推断传播

DAG 中的类型推断从 INPUT 节点向下游传播：

```
INPUT(subtitle_chunks: string[])
  → MAP：自动推断 item 类型为 string
    → LLM_CALL(extract-concepts)：输出 Schema 为 ConceptList
      → REDUCE：输入类型为 ConceptList[]，输出类型为 ConceptList
        → OUTPUT：最终类型为 ConceptList
```

类型推断失败时（灰色边），编辑器显示警告但不阻止保存——允许"先连线后定义 Schema"的工作流。

#### MCP 集成（待独立文档）

> **注意**：MCP 集成涉及 tool 注册、权限模型、与 Claude Code / 第三方 AI 工具的协议对接等复杂设计，需要新开独立文档详细设计。本节仅列出预期暴露的接口方向，不作为实施依据。

预期暴露的 Schema 操作接口方向：`prompt_dag_get_node_schema`、`prompt_dag_set_edge_schema`、`prompt_dag_validate_connections`、`prompt_dag_infer_types`。

### 4.4 FlowPortType 分层类型系统

**Level 0 — Primitive（原始类型）**：`string | number | boolean | null | string[] | number[]`

**Level 1 — Domain（领域类型，Fredica 内置）**：

```typescript
SubtitleChunk  = { text: string; start: number; end: number }
ConceptItem    = { name: string; type: string; description: string }
ConceptList    = ConceptItem[]
FlashcardItem  = { front: string; back: string; tags: string[] }
TranscriptLine = { speaker?: string; text: string; timestamp: number }
```

**Level 2 — Versioned（版本化 Schema，用户自定义）**：`schema_id`（全局唯一）+ `version`（整数递增）+ `json_schema`（完整 JSON Schema）。

类型系统的核心价值：Level 0 处理简单数据流无需定义；Level 1 提供开箱即用的领域类型；Level 2 支持用户自定义复杂输出结构并可跨 DAG 复用。

---

## 5. 模块详细设计

### 5.1 Monaco Editor 迁移

#### 选型结论

当前 PromptBuilder 使用原生 `<textarea>`。迁移到 Monaco Editor 的核心动机：

- Monaco 原生支持 TypeScript 语言服务（类型检查、自动补全、跳转定义）
- 与 VS Code 体验一致，提示词工程师学习成本低
- 支持动态注入 `.d.ts` 类型声明，可将 `TypedVarRegistry` 的变量类型实时反映到编辑器补全中

#### 前端组件结构与包结构

Monaco、ReactFlow DAG 编辑器、d.ts 动态生成三者需要协同工作，因此需要明确包结构避免循环依赖：

```
app/components/
├── prompt-builder/           # 现有组件包（单节点编辑器）
│   ├── PromptBuilder.tsx     # 现有主体（textarea → 替换为 MonacoPromptEditor）
│   │                         # 新增 prop: externalModel?: monaco.editor.ITextModel
│   │                         # 有值时用外部 model（DAG 嵌入模式），无值时自建（独立模式）
│   ├── MonacoPromptEditor.tsx   # ← 新增：Monaco 实例封装（提示词 Markdown 模式）
│   ├── MonacoOrchEditor.tsx     # ← 新增：Monaco 实例封装（编排脚本 JS 模式，含只读 header 保护）
│   ├── usePromptVarDts.ts       # ← 新增：拉取 PromptVarDtsRoute，管理 addExtraLib 生命周期
│   ├── PromptWorkbenchTabs.tsx  # 现有（无需改动）
│   ├── PromptPaneShell.tsx      # 现有（无需改动）
│   ├── PromptPreviewPane.tsx    # 现有（无需改动）
│   ├── PromptStreamPane.tsx     # 现有（无需改动）
│   ├── SaveTemplateModal.tsx    # 现有（迁移至 PromptChain 存储，接口不变）
│   ├── PromptTemplatePickerModal.tsx  # 现有（迁移期保留，长期废弃）
│   └── promptBuilderTypes.ts   # 现有类型（扩展 contextId、externalModel 等字段）
│
├── prompt-dag/               # ← 新增包：DAG 编辑器（Phase 3）
│   ├── PromptDagEditor.tsx      # 主容器：ReactFlow 画布 + 右侧面板
│   ├── PromptDagNodeLlmCall.tsx # LLM_CALL 节点自定义渲染
│   ├── PromptDagNodeMap.tsx     # MAP 节点
│   ├── PromptDagNodeTransform.tsx
│   ├── PromptDagRightPanel.tsx  # 右侧面板：节点属性 + 内嵌 PromptBuilder
│   ├── PromptDagEdge.tsx        # 带 Schema tooltip 的自定义边
│   ├── useMonacoShared.ts       # ← 关键：共享单一 Monaco 实例（model 切换），供 DAG 右侧面板使用
│   ├── useDagTypeInference.ts   # 类型推断传播逻辑（前端计算，不请求后端）
│   └── promptDagTypes.ts        # PromptNodeData, PromptEdgeData 等
│
└── schema/                   # ← 新增：JSON Schema 表单编辑器（Phase 3）
    ├── SchemaFormEditor.tsx     # JSON Schema 可视化表单（字段列表 CRUD）
    └── schemaEditorTypes.ts
```

**关键约束**：
- `prompt-builder/` 不依赖 `prompt-dag/`（单向依赖：DAG → PromptBuilder，不反向）
- Monaco 实例管理分两处：`usePromptVarDts.ts`（单节点模式，每个 PromptBuilder 实例独立注入 d.ts）；`useMonacoShared.ts`（DAG 模式，整个 DAG 编辑器共享一个 Monaco 实例，通过 model URI 切换节点）
- `prompt-dag/` 通过 props 接收 `apiFetch`，不直接引用 context

#### .d.ts 动态生成流程

后端 `TypedVarRegistry` 暴露 `PromptVarDtsRoute` 端点（`GET /api/v1/PromptVarDtsRoute?context=...`），返回当前运行上下文的类型声明：

```typescript
// 生成示例
declare function getVar(key: "subtitle_chunks"): string[];
declare function getVar(key: "material_id"): string;
declare function getVar(key: "config"): {
  extractRelations: boolean;
  maxConcepts: number;
};

// runPrompt 泛型映射：NodeId → 输入变量类型、输出类型
// 每个节点的 Vars/Result 类型由 generateHeader() 从 PromptChainEdge.schema 推导生成
type NodeId = "extract-concepts" | "infer-relations";
interface NodeVarMap {
  "extract-concepts": { chunk: string; chunk_index: number; total_chunks: number };
  "infer-relations": { concepts: ConceptList };
}
interface NodeResultMap {
  "extract-concepts": ConceptList;
  "infer-relations": RelationList;
}
declare function runPrompt<T extends NodeId>(id: T, vars: NodeVarMap[T]): Promise<NodeResultMap[T]>;

declare function merge(results: unknown[]): unknown;
```

`usePromptVarDts(contextId, apiFetch)` Hook 负责拉取并注入：

```typescript
// usePromptVarDts.ts（单节点模式）
export function usePromptVarDts(contextId: string | undefined, apiFetch: ApiFetchFn | undefined) {
  useEffect(() => {
    if (!contextId || !apiFetch) return;
    let cancelled = false;
    apiFetch(`/api/v1/PromptVarDtsRoute?context=${encodeURIComponent(contextId)}`).then(({ resp, data }) => {
      if (cancelled || !resp.ok || typeof data !== "string") return;
      monaco.languages.typescript.javascriptDefaults.addExtraLib(data, "fredica-vars.d.ts");
    });
    return () => { cancelled = true; };
  }, [contextId, apiFetch]);
}
```

**DAG 模式**：`useMonacoShared` 内部统一管理 d.ts，切换节点时更新 `getVar` 重载为当前节点输入端口类型。

#### Monaco 模型复用（DAG 场景）

DAG 中节点数量可能达到 10–20 个，每个节点有两个编辑器（提示词 + 编排脚本）。全部实例化为独立 Monaco Editor 会导致内存过高。解决方案：

```
PromptDagEditor
  └── useMonacoShared：持有一个 monaco.editor 实例 + 一组 monaco.editor.ITextModel
        每个节点对应两个 model（均为内存字符串，不对应磁盘文件）：
          URI: inmemory://chain-{chainId}/node-{nodeId}/prompt    ← PromptChainNode.promptContent
          URI: inmemory://chain-{chainId}/orch                    ← PromptChain.userScript（链级共享）
        点击节点 → setModel(node 对应的 model) → 切换显示内容
        model 变更会 dirty-track，保存时 flush 到 PromptChain 数据结构
```

这样整个 DAG 编辑器只有一个 Monaco 实例，model 数量等于节点数 × 2，内存开销可控。

#### 诊断捕获（编排脚本保存前校验）

```typescript
const markers = monaco.editor.getModelMarkers({ resource: orchModel.uri });
const errors = markers.filter(m => m.severity === monaco.MarkerSeverity.Error);
if (errors.length > 0) {
  // 阻止保存，展示错误列表
}
```

#### ReactFlow 集成要点

```
// PromptDagEditor.tsx 骨架
<ReactFlow
  nodes={nodes}               // 来自 PromptChain.nodes
  edges={edges}               // 来自 PromptChain.edges
  nodeTypes={customNodeTypes} // LLM_CALL / MAP / REDUCE / SWITCH / TRANSFORM / INPUT / OUTPUT
  edgeTypes={customEdgeTypes} // 带 Schema tooltip 的自定义边
  onNodeClick={(_, node) => setSelectedNode(node.id)}
  onConnect={handleConnect}   // 连线时校验 Schema 兼容性
>
  <MiniMap />
  <Controls />
</ReactFlow>

{/* 右侧面板：节点属性 + 嵌入 PromptBuilder（共享 Monaco 实例） */}
<PromptDagRightPanel
  selectedNode={selectedNode}
  sharedMonaco={monacoInstance}
  apiFetch={apiFetch}
/>
```

节点自定义组件（如 `PromptDagNodeLlmCall`）通过 ReactFlow 的 `NodeProps<PromptNodeData>` 接收数据，不持有编辑状态——编辑状态全部在 `useMonacoShared` 中统一管理。

---

### 5.2 TypedVarRegistry

#### 现状问题

当前 `getVar(key)` 调用是字符串 key，无类型约束：
- 提示词工程师不知道有哪些变量可用
- 拼写错误只在运行时发现
- 不同页面的变量集合没有统一注册机制

#### PromptVarDescriptor

```kotlin
@Serializable
data class PromptVarDescriptor(
    val key: String,
    val type: PromptVarType,       // STRING, STRING_LIST, NUMBER, BOOLEAN, OBJECT
    val description: String,
    val jsonSchema: JsonObject? = null,  // type=OBJECT 时的详细 Schema
    val example: JsonElement? = null,
    val required: Boolean = true,
)

enum class PromptVarType { STRING, STRING_LIST, NUMBER, BOOLEAN, OBJECT }
```

#### Registry 接口

```kotlin
interface TypedVarRegistry {
    fun register(contextId: String, vars: List<PromptVarDescriptor>)
    fun resolve(contextId: String): List<PromptVarDescriptor>
    fun generateDts(contextId: String): String  // 生成 .d.ts 内容
}
```

`contextId` 对应页面/功能上下文，如 `"asr-postprocess"`、`"weben-extract"`。

#### API 端点

端点命名遵循项目约定（`/api/v1/<RouteClassName>`），GET 路由参数以 query string 传入，后端解析为 `Map<String, List<String>>`：

```
GET /api/v1/PromptVarDtsRoute?context={contextId}
  → 返回该上下文的 .d.ts 字符串（`text/plain`）

GET /api/v1/PromptVarListRoute?context={contextId}
  → 返回 List<PromptVarDescriptor> JSON（供 UI 展示变量文档）
```

对应 Kotlin Route 对象：`PromptVarDtsRoute`、`PromptVarListRoute`，均为 `Mode.Get`，注册到 `all_routes.kt`。

#### 前端 .d.ts 动态生成流程

```
页面加载
  → useEffect: fetch /api/v1/PromptVarDtsRoute?context=xxx
  → monaco.languages.typescript.javascriptDefaults.addExtraLib(dts, "fredica-vars.d.ts")
  → 编辑器自动补全 getVar("...") 时提示可用 key 及类型
```

#### 废弃路径

现有 `getVar(key: string): any` 签名保持兼容，但在编辑器中标记为 `@deprecated`，引导用户使用类型化版本。

---

### 5.3 评估系统

提示词版本管理的核心价值不是"能存历史"，而是"知道该不该回滚"——这需要一套完整的评估体系，回答"哪个版本更好"这个问题。

#### 5.3.1 评估体系总览

```
┌─────────────────────────────────────────────────────────────────┐
│  评估触发层                                                       │
│  ── 手动触发（用户在编辑器点击"跑评估"）                            │
│  ── 自动触发（版本推进到 staging 时自动跑）                         │
│  ── 用户反馈触发（用户标记某次运行结果好/坏）                        │
├─────────────────────────────────────────────────────────────────┤
│  评分引擎层                                                       │
│  ── 代码检查（格式合规、字段完整）                                  │
│  ── LLM Judge（多维主观质量）                                     │
│  ── 人工评分（用户直接打分）                                        │
├─────────────────────────────────────────────────────────────────┤
│  数据层                                                          │
│  ── EvalDataset（测试用例集合）                                    │
│  ── EvalRun（对某版本跑一次评估的完整记录）                          │
│  ── EvalResult（单条测试用例的评分详情）                            │
│  ── UserFeedback（用户对真实运行结果的反馈）                         │
├─────────────────────────────────────────────────────────────────┤
│  可观测层                                                        │
│  ── 每次真实 LLM 调用关联 prompt 版本（PromptNodeRun）             │
│  ── 跨版本指标仪表盘（分数趋势、成本、延迟）                          │
│  ── 版本对比报告（A/B 实验结果）                                    │
└─────────────────────────────────────────────────────────────────┘
```

#### 5.3.2 数据模型

```kotlin
@Serializable
data class PromptEvalDataset(
    val id: String,
    val name: String,
    val chainId: String,
    val description: String,
    val cases: List<EvalCase>,
    val createdAt: Long,
)

@Serializable
data class EvalCase(
    val id: String,
    val inputVars: JsonObject,         // 提示词模板的输入变量
    val expectedOutput: JsonElement?,  // 期望输出（可为 null，仅做 LLM judge 评估时不需要）
    val tags: List<String> = emptyList(),
    val source: EvalCaseSource,        // MANUAL / FROM_RUN / FROM_FEEDBACK
)

enum class EvalCaseSource { MANUAL, FROM_RUN, FROM_FEEDBACK }

@Serializable
data class PromptEvalRun(
    val id: String,
    val datasetId: String,
    val chainId: String,
    val chainVersion: Int,             // 被评估的版本
    val status: EvalRunStatus,         // RUNNING / COMPLETED / FAILED
    val results: List<EvalCaseResult>,
    val summary: EvalSummary,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
data class EvalCaseResult(
    val caseId: String,
    val actualOutput: JsonElement?,
    val scores: Map<String, EvalScore>,  // 维度名 → 分数
    val overallScore: Double,            // 加权平均
    val latencyMs: Long,
    val tokenCount: Int,
    val cost: Double,
)

@Serializable
data class EvalSummary(
    val caseCount: Int,
    val avgScore: Double,
    val passRate: Double,             // 超过阈值的比例
    val avgLatencyMs: Long,
    val totalCost: Double,
    val scoresByDimension: Map<String, Double>,
)
```

#### 5.3.3 评分引擎

```kotlin
sealed class EvalRubric {
    // 代码检查（零成本，最快）
    data class JsonSchemaMatch(val schema: JsonObject) : EvalRubric()
    data class FieldExists(val fields: List<String>) : EvalRubric()
    data class CustomScript(val script: String) : EvalRubric()  // GraalJS 检查

    // LLM Judge（有成本，适合主观质量维度）
    data class LlmJudge(
        val judgePrompt: String,       // 评分标准自然语言描述
        val judgeModel: String,        // 通常用比被评估模型更强的模型
        val scoreRange: IntRange = 0..5,
        val passMark: Int = 3,
    ) : EvalRubric()

    // 与期望输出对比（需要 EvalCase.expectedOutput 非 null）
    data class ExactMatch(val field: String? = null) : EvalRubric()
    data class SemanticSimilarity(val threshold: Double = 0.85) : EvalRubric()
}
```

**混合评估最实用**：格式合规 + 字段完整用代码检查（快、零成本），主观质量维度用 LLM Judge。Weben 概念提取示例：

| 评估维度 | 方法 | 权重 |
|---------|------|------|
| 格式合规（JSON 结构是否正确） | `JsonSchemaMatch` | 0.2 |
| 字段完整性（name/type/description 都有） | `FieldExists` | 0.1 |
| 覆盖率（是否遗漏关键概念） | `LlmJudge` | 0.4 |
| 准确性（概念描述是否与原文一致） | `LlmJudge` | 0.3 |

**Judge 模型选择原则**：Judge 模型应比被评估模型强（或同级）——用弱模型评估强模型产出不可靠。评估 prompt 本身应稳定，`judge_prompt_version` 单独版本化，避免"评估尺子变了"导致跨版本数据不可比。

#### 5.3.4 用户反馈收集

业界"评估驱动的提示词开发"的关键是：让真实用户的反馈成为评估数据的持续来源，而不是只靠开发者手动标注。

**Fredica 的具体设计**：

```
用户使用某个提示词功能的运行结果
  → 在结果页面看到 [👍 好] [👎 不好] 按钮
  → 点击后弹出快速反馈：
    "哪里不好？" → [内容遗漏] [信息有误] [格式问题] [其他（可输入）]
  → 反馈写入 UserFeedback 表，关联到本次 PromptNodeRun（含 chain 版本）
  → 后台将该条运行的输入/输出 + 用户标注自动加入"待审查的 EvalCase 候选"
  → 开发者定期在"评估数据集管理"页面审查候选，确认有效的加入数据集
```

**激励机制（"用户评估结果质量送 100 token" 场景）**：

```kotlin
data class UserFeedback(
    val id: String,
    val promptNodeRunId: String,  // 关联到具体的运行记录
    val chainId: String,
    val chainVersion: Int,
    val rating: FeedbackRating,   // GOOD / BAD
    val dimension: FeedbackDimension?,  // MISSING_CONTENT / WRONG_INFO / FORMAT_ISSUE / OTHER
    val comment: String? = null,
    val userId: String,
    val createdAt: Long,
    val tokenRewardGranted: Boolean = false,  // 奖励是否已发放
)
```

奖励发放逻辑（在 `UserFeedbackService` 中处理）：
- 用户提交反馈 → 标记为 `pending`
- 开发者/管理员审核该条反馈为"有效标注"（加入了 EvalDataset） → 触发奖励
- 奖励额度可配置（100 token / 有效反馈），防刷：同一用户对同一运行结果只能提交一次反馈

#### 5.3.5 EvalRun 与 PromptNodeRun 关联

真实生产运行也应关联到 prompt 版本，使生产数据成为天然的评估样本：

```
PromptNodeRun（每次真实 LLM 调用）
  chain_id, chain_version, node_id
  input_vars_hash, output_json
  latency_ms, token_count, cost
  eval_run_id（如果是评估触发的调用，关联到 EvalRun）
  user_feedback_id（如果用户后续提交了反馈）
```

这样"生产数据"和"评估数据"共享同一个 `PromptNodeRun` 表，实现：
- 某个版本在生产中的真实表现（延迟/成本分布）直接可查
- 用户标记的"坏结果"可追溯到具体版本
- 评估分数趋势图同时包含手动评估跑分 + 生产反馈率

#### 5.3.6 半自动优化器（DSPy 启发）

在完整评估体系的基础上，优化器才有意义——它消费 EvalDataset，产出"更好的版本"建议：

```
优化循环：
1. 从 EvalDataset 采样 N 个 case
2. 用当前 production 版本跑，收集 EvalRun（作为基准）
3. 优化策略（选其一或组合）：
   a. Few-shot 搜索：从历史 PromptNodeRun 中找高分案例，提取为 few-shot 示例插入提示词
   b. 指令变体：调用辅助 LLM 生成 K 个改写版本，各自跑 EvalRun，取最高分版本
4. 生成对比报告（variant_a = baseline，variant_b = 候选优化版本）
5. 如果 variant_b 平均分 > baseline + threshold，在 UI 中提示"发现潜在更优版本"
6. 用户在评估结果对比页面审查逐条差异，手动确认是否推进到 staging
```

优化器是**辅助工具**，最终决策权在用户手中。

---

### 5.4 链模板管理（prompt_chain）

#### 数据模型

```kotlin
@Serializable
data class PromptChain(
    val id: String,
    val name: String,
    val description: String,
    val builtinVersion: Int? = null,  // null 表示用户创建，非 null 表示系统内置

    // ── 声明层（DAG / ReactFlow 渲染数据源） ──────────────────────────
    val nodes: List<PromptChainNode>,     // 节点声明（含 promptContent 内嵌文本）
    val edges: List<PromptChainEdge>,     // 边声明（含 Schema-on-Edge）

    // ── 执行层（GraalJS 沙箱） ──────────────────────────────────────
    val userScript: String,              // 用户编写的编排脚本（含 main() 函数）
    // header（.d.ts 约束 + runPrompt 泛型映射）由 generateHeader(chain) 实时生成，
    // 不存储，后端执行时重新生成，避免客户端篡改

    val inputVarDescriptors: List<PromptVarDescriptor>,
    val outputSchema: JsonObject? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,                    // 每次保存递增
)
```

**只读 header 的生成机制**：

```kotlin
// 后端执行时：fullScript = generateHeader(chain) + chain.userScript
// generateHeader() 是纯函数，从 nodes/edges 推导出类型声明
fun generateHeader(chain: PromptChain): String {
    // 1. 为每个节点生成 NodeVarMap/NodeResultMap 条目（来自 edge.schema）
    // 2. 生成 runPrompt<T extends NodeId>() 泛型重载
    // 3. 生成 getVar() 重载（来自 chain.inputVarDescriptors）
    // 返回完整的 TypeScript .d.ts 头部
}
```

Monaco 展示时同样调用 `generateHeader()` 生成只读区域（通过 `createDecorationsCollection()` + 按键拦截保护），用户只能编辑 `userScript` 部分。保存时只提交 `userScript`，不提交 header。

#### 节点提示词存储策略

`PromptChainNode.promptContent` 直接内嵌提示词文本（Markdown 格式）。这是唯一的存储模式——**不引用独立的 PromptTemplate 记录**。

> **PromptTemplate 废弃说明**：原系统的 `PromptTemplate`（文本快照模式）被 `PromptChain` 统一替代。现有代码中的 `PromptTemplateListRoute`、`PromptTemplateGetRoute` 等路由在迁移完成前保留兼容，新功能不再使用。

#### 版本管理与版本推进流水线

每次保存创建新版本记录（`prompt_chain_version` 表），保留完整历史：

```
prompt_chain_version
  chain_id, version, snapshot_json, saved_at, saved_by, change_note,
  status: draft | staging | production | archived
```

版本推进流水线（参考 Langfuse / LangSmith 的三阶段模型）：

```
draft（本地实验）→ staging（跑评估数据集对比）→ production（上线使用）
                        ↑
           评估通过（分数 > 阈值）或人工确认
```

- **draft**：默认状态，每次保存都是新的 draft 版本。可在 Playground 即时测试。
- **staging**：标记为候选上线版本。系统自动对该版本跑评估数据集（§5.3），生成与当前 production 版本的对比报告。
- **production**：当前线上生效版本，同一时刻只有一个。多租户场景下各租户共享同一 production 版本（租户级覆盖见 §2.4 三层 resolve 链）。
- **archived**：被替换的历史 production 版本，保留记录不删除。

UI 提供版本 diff 视图（提示词文本 diff + DAG 结构 diff）、评估结果对比、一键回滚。

#### A/B 测试

**单用户 vs 多用户场景**：Fredica 是本地部署的多租户 Web 服务，传统按流量拆分的 A/B 测试在同一租户下同样可行（按 `user_id` 百分比路由）。更轻量的替代方案是**数据集维度的对比实验**：

```
实验（PromptExperiment）：
  chain_id = "weben-concept-extract"
  variant_a = version 12（当前 production）
  variant_b = version 13（新 draft）
  dataset_id = "weben-eval-20240418"
  status: running | completed
  result_a: EvalSummary
  result_b: EvalSummary
  winner: "variant_b" | "variant_a" | "draw" | null（尚未决定）
```

用户可在评估结果页面看到两个版本在同一数据集上的逐条对比，手动或按阈值规则选择胜出版本推进到 production。

**多租户场景**：按 `user_id % 100` 的百分比将请求路由到不同版本，实现真实流量 A/B 测试。Ktor API 层天然可充当版本路由网关（参考 Portkey 网关模式）。

#### 分享市场

`builtin_version` 非 null 的 chain 来自系统内置（随应用打包）。未来扩展：
- 用户可将自己的 chain 导出为 JSON，分享给他人
- 官方维护"提示词市场"，提供常用场景的 chain 模板
- 导入时自动检测与现有 chain 的冲突（同名 id 处理策略：fork / 覆盖 / 跳过）

---

### 5.5 PromptBuilder 组件优化

#### Schema 可视化面板

在 PromptBuilder 右侧新增"输出 Schema"面板，替代在提示词文本里描述格式：

```
┌─────────────────────────────────────────────────────┐
│  输出 Schema                              [+ 添加字段] │
├─────────────────────────────────────────────────────┤
│  name        string    必填   "概念名称"              │
│  type        string    必填   "人物/地点/事件/概念"    │
│  description string    可选   "简短描述"              │
│  confidence  number    可选   0.0–1.0               │
├─────────────────────────────────────────────────────┤
│  [预览 JSON Schema]  [复制到提示词]  [绑定到边]        │
└─────────────────────────────────────────────────────┘
```

"复制到提示词"：将 Schema 转换为自然语言描述插入提示词文本（辅助功能，不强制）。
"绑定到边"：将 Schema 绑定到 DAG 中该节点的输出边（Schema-on-Edge）。

#### 模型加载优化

当前问题：PromptBuilder 每次打开都重新加载模型列表，有明显延迟。

优化方案：
- 模型列表在应用启动时预加载，缓存到 React context
- PromptBuilder 从 context 读取，无需额外请求
- 模型配置变更时（用户在设置页修改），广播更新到所有 PromptBuilder 实例

---

## 6. 案例分析：视频分类树 DAG

### 6.1 场景描述

用户上传一批视频素材，需要按内容自动分类到多级分类树（如"教育 > 数学 > 高中代数"）。分类树由用户自定义，可能有 3–5 层、每层 5–20 个节点。

**挑战**：
- 分类树结构动态变化（用户随时增删节点）
- 单次 LLM 调用无法处理整棵树（token 限制）
- 需要逐层缩小候选范围（先判断一级分类，再判断二级，以此类推）
- 同一视频可能属于多个分类（多标签）

### 6.2 DAG 拓扑

```
INPUT(video_summary: string, taxonomy: TaxonomyTree)
  │
  ▼
TRANSFORM(flatten-level1)          ← 提取一级分类列表
  │
  ▼
LLM_CALL(classify-level1)          ← 判断属于哪些一级分类（多选）
  │  outputSchema: { matches: string[] }
  ▼
SWITCH(has-level2?)                ← 判断命中的分类是否有子节点
  │ yes                  │ no
  ▼                      ▼
MAP(per-matched-l1)    OUTPUT(leaf-result)
  │
  ▼
LLM_CALL(classify-level2)          ← 对每个命中的一级分类，判断二级
  │  outputSchema: { matches: string[] }
  ▼
REDUCE(merge-results)
  │
  ▼
OUTPUT(final-classification)
```

### 6.3 SWITCH 节点设计

SWITCH 节点的条件逻辑**写在 main() 编排脚本里**（`if/switch` 语句），声明层只标注节点类型为 `SWITCH` + 出边 label。DAG 渲染层不执行条件脚本，仅展示分支结构。

```javascript
// main() 中的 SWITCH 逻辑（用户在 userScript 中编写）
async function main() {
  const level1Result = await runPrompt("classify-level1", {
    video_summary: getVar("video_summary"),
    level1_list: getVar("taxonomy").level1,
  });

  const taxonomy = getVar("taxonomy");
  const hasChildren = level1Result.matches.some(m => taxonomy.hasChildren(m));

  if (hasChildren) {
    // yes 分支
    const level2Results = await Promise.all(
      level1Result.matches.map(m => runPrompt("classify-level2", { parent: m, taxonomy }))
    );
    return merge(level2Results);
  } else {
    // no 分支（叶节点，直接返回）
    return level1Result;
  }
}
```

声明层中 SWITCH 节点只需标注出边 label（`"yes"` / `"no"`），供 ReactFlow 渲染分支示意图；不存储也不执行条件表达式。

### 6.4 缓存策略

`DagCacheManager` 为每个节点计算缓存 key：

```
节点缓存 key = hash(节点 id + 提示词模板版本 + 所有输入值)
```

对于视频分类场景：
- `classify-level1` 节点：输入是 `video_summary + level1_list`，分类树一级节点不变时命中缓存
- `classify-level2` 节点：输入包含具体的一级分类 id，每个一级分类独立缓存
- 用户修改分类树后，受影响的节点缓存自动失效（通过版本号追踪）

缓存存储在 SQLite `prompt_node_cache` 表，TTL 默认 7 天，可按节点配置。

### 6.5 上下文策略（NodeContextPolicy）

`LLM_CALL(classify-level2)` 需要知道"当前处理的是哪个一级分类"，但不需要其他节点的输出。配置：

```kotlin
NodeContextPolicy.EXPLICIT(
  includeNodeIds = listOf("classify-level1"),  // 只引入一级分类结果
  includeVars = listOf("taxonomy"),            // 引入分类树定义
)
```

三种策略：
- `DIRECT_ONLY`：只使用直接上游节点的输出（默认）
- `ANCESTORS`：使用所有祖先节点的输出（适合需要完整上下文的场景）
- `EXPLICIT`：显式指定引入哪些节点和变量（最精确，避免 token 浪费）

### 6.6 版本迭代成本分析

| 变更类型 | 受影响范围 | 缓存失效范围 | 重新运行成本 |
|---------|---------|------------|------------|
| 修改 `classify-level1` 提示词文本 | 该节点及所有下游 | 该节点缓存失效 | 重跑 level1 + level2 |
| 修改分类树（增加一级节点） | `flatten-level1` + `classify-level1` | 两个节点缓存失效 | 重跑受影响节点 |
| 修改 `classify-level2` 提示词文本 | 仅该节点 | 仅该节点缓存失效 | 只重跑 level2 |
| 修改 SWITCH 条件脚本 | SWITCH 及下游 | SWITCH 缓存失效 | 重跑 SWITCH + 下游 |

节点级缓存的价值：在大批量视频处理场景中，修改一个下游节点不需要重跑所有上游 LLM 调用。

### 6.7 与现有系统集成

视频分类 DAG 通过 `WebenSourceAnalyzeRoute` 触发，复用现有的 `WorkflowRun` + `TaskExecutor` 框架：

```
WebenSourceAnalyzeRoute
  → 创建 WorkflowRun
  → Task 1: FETCH_SUBTITLE（已有）
  → Task 2: VIDEO_CLASSIFY（新增）
    → PromptChainExecutor.execute(chainId="video-classify", vars={...})
      → DagCacheManager 检查缓存
      → 逐节点执行，SSE 流式返回进度
      → 写入 VideoClassification 表
```

---

## 7. 痛点完整地图与方案满意度

> 本章从产品视角审视：原始痛点是否真的被方案覆盖？覆盖质量如何？还有哪些痛点尚未被识别？

### 7.1 原始痛点再评估（P1–P7）

满意度定义：**✅ 高** = 方案直接且完整解决；**△ 中** = 解决核心场景但有明显残留；**❌ 低** = 方案存在但体验缺口大或依赖未落地模块。

| # | 痛点 | 方案 | 满意度 | 残留问题 |
|---|------|------|--------|---------|
| P1 | 图形编辑器无法让 AI 帮写 | React Flow DAG + MCP tools | △ 中 | MCP 集成在 Phase 6，需另文设计；Phase 1-3 内 AI 只能辅助写 userScript，无法操作 DAG 结构 |
| P2 | CLI 能写提示词但无法对接运行时 | MCP tools 暴露 DAG CRUD API | ❌ 低 | 完全依赖 Phase 6（MCP）；在此之前 CLI 与运行时的集成为零；且 MCP 设计文档尚未启动 |
| P3 | 多模型对接缺乏标准化描述 | TypedVarRegistry + Schema-on-Edge | △ 中 | Schema-on-Edge 解决了节点间数据契约，但"模型切换时 prompt 的差异适配"（如 Claude 和 GPT 对 system prompt 格式要求不同）尚无覆盖 |
| P4 | n8n 式图形化控制流体验差 | GraalJS userScript + DAG 可视化 | ✅ 高 | 核心矛盾已解决：复杂控制流用代码，结构用 DAG 可视化；已有 GraalJS 沙箱生产验证 |
| P5 | 需要系统默认提示词 + 市场拉取 | builtin_version 两层模型 + 提示词市场 | △ 中 | "系统默认"（Phase 4）相对简单；"市场拉取"涉及服务端基础设施，复杂度远超其他 Phase，落地时间不确定 |
| P6 | 缺少 Schema 编辑器的图形化设计 | Schema-on-Edge + SchemaFormEditor | ✅ 高 | `SchemaFormEditor` 作为共用底层组件覆盖三处 Schema 编辑入口；JSON Schema 表单避免手写 JSON |
| P7 | 频繁改提示词需要版本管理 | DB 版本历史 + diff 视图 + 回滚 | △ 中 | 版本存储在 Phase 3 实现，但 diff 视图和回滚 UI 的易用性高度依赖实现质量；当前无草稿自动保存设计，修改未保存时切换页面有丢失风险 |

**总体评估**：P4 和 P6 被方案扎实覆盖；P1/P3/P5/P7 均有明显残留；P2 实质上是 Phase 6 才能解决的问题，当前方案对它的覆盖是"设计预留"而非"已解决"。

### 7.2 新发现痛点（P8–P15）

除原始 P1–P7 外，以下痛点在架构设计过程中被识别，但在 §1.1 中未列出：

| # | 痛点 | 本质矛盾 | 方案 | 满意度 |
|---|------|---------|------|--------|
| P8 | 提示词调试困难：不知道哪个节点输出有问题 | 黑盒执行 ↔ 可观测性 | 运行态节点高亮 + `PromptNodeRun` 记录每次执行的输入输出 | △ 中（需 Phase 3；§5.3 的 PromptNodeRun 是基础，但 UI 层的调试体验需专门设计） |
| P9 | 多人协作时提示词改动冲突，不知道谁改了什么 | 并发编辑 ↔ 变更追溯 | 版本历史 + `change_note` + `saved_by` 字段 | △ 中（追溯有了，但冲突预防缺失——两人同时编辑同一 chain 时无锁机制） |
| P10 | 大批量处理时 LLM 调用成本高，重复调用浪费 | 迭代成本 ↔ 成本控制 | `DagCacheManager` 节点级缓存（输入哈希 → 跳过重复调用） | ✅ 高（设计完整，是为批处理场景专门引入的机制） |
| P11 | 提示词工程师不懂 JSON Schema，无法定义输出格式 | 结构化输出 ↔ 低门槛编辑 | `SchemaFormEditor` 表单式编辑（字段 CRUD，无需手写 JSON） | ✅ 高（`SchemaFormEditor` 就是为解决这个问题而设计的底层组件） |
| P12 | 不同功能模块的提示词孤立，无法跨模块复用 | 模块化 ↔ 复用性 | PromptChain 节点内嵌 + 提示词市场导入 | △ 中（市场是复用的理想路径，但 Phase 6 才有；Phase 1-5 内复用只能靠手动 Fork） |
| P13 | 提示词输出格式变更后，下游解析代码需要同步修改 | Schema 演进 ↔ 向后兼容 | Schema-on-Edge 作为契约，版本锁定 | ❌ 低（目前无 Schema 版本迁移机制；变更 Schema 会直接破坏依赖该节点输出的下游代码，无 migration guide） |
| P14 | 编排脚本逻辑复杂后难以测试，控制流缺少单元测试手段 | 代码灵活性 ↔ 可测试性 | 复用评估引擎的 GraalJS 沙箱 mock `runPrompt()`（见 §9 Q1） | △ 中（方向可行，但需专门实现；当前无现成工具，测试只能靠人工触发真实执行） |
| P15 | 用户在"提示词"标签页编辑后，不清楚改动对下游节点类型的影响 | 实时编辑 ↔ 类型一致性反馈 | Monaco + `generateHeader()` + d.ts 动态注入 | △ 中（Phase 2 后有类型补全和编译错误提示；但 `promptContent` 是 Markdown 文本，Markdown 编辑器本身无类型感知） |

### 7.3 尚未被方案覆盖的痛点区域

以下三个领域目前整体缺乏覆盖，不属于某一具体 Phase 的遗漏，而是系统性空白：

**① 提示词的"投产审批"流程缺失**

当前版本流水线（`draft → staging → production`）描述了状态机，但没有设计"谁有权把 staging 推到 production"的审批机制。对于团队使用场景，提示词误上线的风险是真实的。可能的解法：引入审批角色 + 推送通知，但这与多租户权限模型强耦合，复杂度较高。

**② 提示词与业务数据的绑定可观测性为零**

`PromptNodeRun` 记录了"这次执行输入了什么、输出了什么"，但不知道"这条记录对应哪个业务对象"（如哪个视频、哪条字幕）。当用户说"这个视频的分析结果有问题"时，没有办法直接从提示词执行日志反查。需要在 Executor 层补充业务关联 ID（`entity_id` / `entity_type`），才能做到双向追溯。

**③ 无运行时限流与成本上限设置**

P10 引入了 `DagCacheManager` 解决重复调用问题，但对"一次批量任务花多少钱"没有上限控制。用户误触大批量任务、LLM API Key 被滥用等场景，目前完全无防护。建议 Phase 3 或 Phase 5 时同步引入：per-chain 调用次数/Token 预算上限，超限暂停并通知用户。

### 7.4 P5 补充方案：提示词市场拉取机制

`builtin_version` 两层模型解决了"系统默认提示词"问题，但"社区市场拉取"需要额外机制：

```
市场拉取流程：
1. 用户在"提示词市场"页面浏览官方/社区 chain
2. 点击"安装"→ 下载 chain JSON（含所有节点和编排脚本）
3. 本地检测冲突（同名 chain_id）：
   - 无冲突 → 直接导入，标记来源为"市场"
   - 有冲突 → 提示用户选择：覆盖 / Fork（新 id）/ 跳过
4. 安装后的 chain 进入用户层，可自由修改
5. 原始市场版本保留为"基准版本"，支持"查看与市场版本的差异"
```

注意：提示词市场涉及服务端基础设施（托管、审核、CDN 分发）和安全问题（防止恶意 chain 注入），是 Phase 6 中复杂度最高的子系统，应在 MCP 集成完成后单独评估其可行性，不应与 Phase 6 其他内容捆绑交付。

### 7.5 满意度 △/❌ 痛点的深度设计索引

> 以下痛点的详细架构设计见 §8（各痛点深度设计）。本节仅汇总满意度预期。

| # | 痛点 | 原满意度 | 方案落地后 | 深度设计 | 落地 Phase |
|---|------|---------|-----------|---------|-----------|
| P1 | AI 辅助 DAG 编辑 | △ | △（MCP 未落地前上限在此） | — | Phase 6 |
| P2 | CLI ↔ 运行时集成 | ❌ | △ → ✅ | §8.1 | Phase 3 / Phase 6 |
| P3 | 多模型 prompt 适配 | △ | ✅ | §8.2 | Phase 3 |
| P5 | 提示词市场拉取 | △ | △+ → ✅ | §8.3 | Phase 3 / Phase 6 |
| P7 | 草稿自动保存 | △ | ✅ | §8.4 | Phase 2 |
| P8 | 调试 UI | △ | ✅ | §8.5 | Phase 3 |
| P9 | 并发编辑冲突 | △ | △+ → ✅ | §8.6 | Phase 2 / Phase 3 |
| P12 | 跨模块复用 | △ | △+ → ✅ | §8.7 | Phase 3 / Phase 6 |
| P13 | Schema 演进兼容性 | ❌ | ✅ | §8.8 | Phase 3（必须同步） |
| P14 | 编排脚本可测试性 | △ | ✅ | §8.9 | Phase 3 |
| P15 | 类型一致性反馈 | △ | ✅ | §8.10 | Phase 2 |

---

## 8. 各痛点深度设计

> 本章对 §7.5 中满意度为 △ 或 ❌ 的痛点逐一展开深度设计。每节包含：业界方案调研、选型理由、数据模型/API 契约、UX 方案、实施建议。

### 8.1 P2（❌）CLI ↔ 运行时集成

#### 8.1.1 业界方案调研

| 产品 | 做法 | 局限 |
|------|------|------|
| **LangChain / LangSmith** | Python SDK 直接操作 chain 对象；LangSmith 提供 REST API（`/public/runs`、`/public/traces`）供外部工具查询执行结果 | SDK 绑定 Python 生态，非 Python 用户无法使用；REST API 只读，不能修改 chain |
| **Dify** | `/api/workflows/{workflow_id}/run` HTTP 端点支持运行时参数覆盖，但不改变存储的 workflow 定义 | 无 CLI 工具生态；API 只能"运行"不能"编辑" |
| **n8n** | REST API 全量操作 workflow（CRUD + 执行 + 导出 JSON），设计目标是自动化编排而非 AI 辅助 | API 设计面向机器人/脚本，无 AI 工具集成方向 |
| **Claude Code / MCP** | 通过 Model Context Protocol 将业务系统暴露为 `resources`（可读数据）和 `tools`（可执行操作），AI 通过 `tool_call` 程序化操作 | 协议仍在演进中；需要为每个业务领域定义专用 tools |

#### 8.1.2 选型理由

Fredica 的核心约束：**本地部署的多租户 Web 服务，Ktor API（端口 7631）对外暴露，外网可访问**。这意味着：
- 可以依赖 HTTP REST API，且该 API 不仅仅是"本机 localhost" 调用
- 已有 Ktor HTTP 服务（端口 7631），已有 Bearer Token 认证体系
- REST API 即为正式 API，不是"过渡"而是持久基础设施
- MCP 是额外的 AI 专用集成层，而非 REST API 的"终态替代"

因此采用 **正式化 REST API（Phase 3）+ MCP 作为 AI 工具集成层（Phase 6）** 的两阶段策略。关键洞察：**MCP tools 本质上是 REST API 的语义包装**，两者长期共存，互不替代。

#### 8.1.3 过渡方案：PromptChain REST API（Phase 3）

新增 4 个路由，覆盖"读 → 改 → 运行 → 查询"完整循环：

```
GET  /api/v1/PromptChainGetRoute?chain_id=xxx
  → 返回完整 PromptChain JSON（含 nodes、edges、userScript）

POST /api/v1/PromptChainSaveRoute
  Body: { chain_id, nodes, edges, userScript, lock_version }
  → 创建或更新 chain；lock_version 用于乐观锁（§8.6）

POST /api/v1/PromptChainRunRoute
  Body: { chain_id, input_vars: { ... }, model_override?: string }
  → 创建 WorkflowRun，返回 { run_id, status: "queued" }

GET  /api/v1/PromptChainRunStatusRoute?run_id=xxx
  → 返回 { status, node_runs: [...], result?, error? }
```

**使用场景示例**（用户在终端中操作，支持本地或远程访问）：

```bash
# 设置 API 基地址（本地或远程均可）
API_BASE="https://my-fredica.example.com/api/v1"  # 或 http://localhost:7631/api/v1
AUTH_HEADER="Authorization: Bearer <session_token>"

# 1. 读取当前 chain
curl -H "$AUTH_HEADER" "$API_BASE/PromptChainGetRoute?chain_id=weben-extract" > chain.json

# 2. 本地修改 chain.json（手动或用 jq/AI 工具）
jq '.nodes[0].promptContent = "新的提示词..."' chain.json > chain_modified.json

# 3. 写回
curl -X POST -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  "$API_BASE/PromptChainSaveRoute" -d @chain_modified.json

# 4. 触发运行
curl -X POST -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  "$API_BASE/PromptChainRunRoute" \
  -d '{"chain_id":"weben-extract","input_vars":{"material_id":"test-001"}}'
# → {"run_id":"run-abc123","status":"queued"}

# 5. 轮询结果
curl -H "$AUTH_HEADER" "$API_BASE/PromptChainRunStatusRoute?run_id=run-abc123"
```

> **注意**：所有 API 均需 Bearer Token 认证（已有 `session_token` 体系），权限受 `minRole` 控制。外网访问时需配合 HTTPS 反向代理。

#### 8.1.4 终态方案：MCP 集成（Phase 6）

MCP 集成将上述 REST API 包装为 AI 可调用的 tools：

```typescript
// MCP tool 定义（概念示意）
tools: [
  {
    name: "prompt_chain_get",
    description: "获取指定 PromptChain 的完整定义",
    inputSchema: { type: "object", properties: { chain_id: { type: "string" } } }
  },
  {
    name: "prompt_chain_update_node",
    description: "更新 PromptChain 中某个节点的提示词内容",
    inputSchema: {
      type: "object",
      properties: {
        chain_id: { type: "string" },
        node_id: { type: "string" },
        prompt_content: { type: "string" }
      }
    }
  },
  {
    name: "prompt_chain_run",
    description: "触发 PromptChain 执行并返回结果",
    inputSchema: { ... }
  },
  {
    name: "prompt_chain_validate",
    description: "校验 PromptChain 的类型一致性和连线合法性",
    inputSchema: { ... }
  }
]
```

MCP 集成的详细设计（tool 注册、权限模型、协议对接）需另开独立设计文档，不在本文范围内。

#### 8.1.5 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | ❌ | CLI 与运行时零集成 |
| Phase 3（REST API） | △ | 脚本可操作，但非 AI 原生 |
| Phase 6（MCP） | ✅ | AI 工具可直接操作 DAG |

---

### 8.2 P3（△）多模型 Prompt 适配

#### 8.2.1 业界方案调研

| 产品 | 做法 | 局限 |
|------|------|------|
| **Humanloop** | Prompt 存储层内嵌 `model_config` 字段，不同模型族的提示词分别存储为"变体"（Variant）。编辑时可选择"当前编辑的是哪个模型族的版本"，评估时可跨变体对比 | 变体数量随模型增长线性膨胀；维护成本高 |
| **PromptLayer** | "统一提示词 + 模型适配器"模式。提示词是通用格式，API 调用时指定 `model`，系统自动应用模型特定的转换（如 temperature 范围映射：GPT 0–2 → Claude 0–1） | 自动转换只覆盖参数差异，无法处理 system prompt 语义差异 |
| **LangChain** | 框架层抽象：`ChatPromptTemplate` 统一接口，底层 `ChatOpenAI` / `ChatAnthropic` 各自处理消息格式差异（如 Claude 的 `system` 参数 vs GPT 的 `messages[0].role="system"`） | 抽象层隐藏了差异，但也阻止了针对特定模型的精细优化 |
| **DSPy** | 完全不同的思路：提示词由框架自动生成（Signature → Prompt），用户只定义输入/输出类型。模型切换时框架重新生成最优提示词 | 放弃了人工精调提示词的能力；不适合需要精确控制 prompt 的场景 |

#### 8.2.2 选型理由

Fredica 的场景是**用户手写提示词 + 可能切换模型**。DSPy 的全自动生成不适用。核心矛盾是：

- **大多数时候**用户只用一个模型，不需要多模型适配（不应增加默认复杂度）
- **偶尔**需要切换模型时，system prompt 格式差异是最大痛点

因此采用 **LangChain 式自动适配（覆盖 80% 场景）+ Humanloop 式手动变体（覆盖 20% 精调场景）** 的分层策略。

#### 8.2.3 方案设计

**Layer 1：自动适配（默认行为，用户无感知）**

在 `PromptChainExecutor` 中引入 `ModelAdapter` 接口，执行时根据目标模型自动转换消息格式：

```kotlin
interface ModelAdapter {
    fun adaptMessages(
        messages: List<ChatMessage>,
        modelFamily: ModelFamily
    ): List<ChatMessage>
}

enum class ModelFamily { CLAUDE, OPENAI, GEMINI, DEEPSEEK, OTHER }

// 内置适配规则示例
object DefaultModelAdapter : ModelAdapter {
    override fun adaptMessages(messages: List<ChatMessage>, family: ModelFamily): List<ChatMessage> {
        return when (family) {
            ModelFamily.CLAUDE -> {
                // Claude：system 消息提取为独立参数，不放在 messages 数组中
                // 多轮 system 消息合并为一条
                mergeSystemMessages(messages)
            }
            ModelFamily.OPENAI -> {
                // GPT：system 消息必须是 messages[0]，且只能有一条
                ensureSingleSystemMessage(messages)
            }
            ModelFamily.GEMINI -> {
                // Gemini：不支持 system role，转为 user 消息前缀
                convertSystemToUserPrefix(messages)
            }
            else -> messages
        }
    }
}
```

**Layer 2：手动变体（可选，用户显式启用）**

在 `PromptChainNode` 上新增可选字段：

```kotlin
@Serializable
data class PromptChainNode(
    // ...现有字段...
    val promptContent: String,                          // 默认提示词（所有模型通用）
    val modelVariants: Map<String, PromptVariant>? = null,  // 模型特定变体（可选）
)

@Serializable
data class PromptVariant(
    val promptContent: String? = null,     // 覆盖默认提示词
    val systemPrompt: String? = null,      // 覆盖默认 system prompt
    val temperature: Double? = null,       // 覆盖默认 temperature
    val maxTokens: Int? = null,            // 覆盖默认 max_tokens
)
```

执行时的 resolve 链：`modelVariants[currentFamily] ?? 默认 promptContent` → `ModelAdapter.adaptMessages()`。

#### 8.2.4 UX 设计

DAG 节点右侧面板的"提示词"Tab 默认显示通用版本。底部新增折叠区域"模型特定配置"：

```
┌── 提示词 ──────────────────────────────────────────────┐
│ [通用版本 ▼]  [Claude] [GPT] [Gemini]                   │
│                                                         │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 你是一个视频内容分析专家。请从以下字幕文本中提取...    │ │
│ │ ...                                                 │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ ▶ 模型特定配置（可选）                                    │
│   当前未配置任何模型变体。                                │
│   [+ 添加 Claude 变体] [+ 添加 GPT 变体]                │
└─────────────────────────────────────────────────────────┘
```

点击"添加 Claude 变体"后，编辑器切换为 Claude 专用版本，初始内容从通用版本复制。用户可针对 Claude 的特性（如更长的 system prompt 支持）进行精调。

#### 8.2.5 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | Schema-on-Edge 解决了数据契约，但 prompt 格式差异无覆盖 |
| Phase 3（自动适配 + 手动变体） | ✅ | 80% 场景自动处理，20% 精调场景有手动入口 |

---

### 8.3 P5（△）提示词市场与模板复用

#### 8.3.1 业界方案调研

| 产品 | 做法 | 局限 |
|------|------|------|
| **Dify** | 官方 Marketplace 提供 100+ 开源 workflow 模板。导入时物理复制（新建副本），与原始模板完全解耦。无 Fork 追溯，无"原始版本有更新"通知 | 导入后与上游断开，无法获取更新 |
| **Flowise** | 集成 LangChain templates（30+ 预置场景）。导入后是完整独立 workflow，支持导出为 JSON 供他人导入 | 同 Dify，无版本追踪 |
| **Humanloop** | 重点是团队内部的 prompt library，支持从私有库导入并绑定到原始版本（知道谁推送了新版本）。无公开市场 | 仅限团队内部，无社区生态 |
| **Hugging Face Hub** | Dataset / Model 可 Fork；Fork 后独立存储、独立修改。无自动同步，Fork 方手动 `git pull` 原始 repo | Git 语义对非开发者不友好 |

#### 8.3.2 选型理由

Fredica 是本地部署的多租户 Web 服务，有 Ktor 服务端（端口 7631）对外暴露，"市场"的实现路径与纯离线桌面应用截然不同：

- **有服务端**：Phase 6 可搭建中心化 Marketplace 服务（带 CDN 托管和审核流程）
- **内置模板仍需打包**：离线/断网场景下必须保证 Layer 1 可用，系统默认模板随应用打包
- **租户级隔离**：多租户场景下，不同租户的自定义模板互不可见（除非显式发布到市场）
- **用户可修改**：导入后的模板必须可自由编辑，Fork 谱系追踪上游更新

因此采用 **三层模板体系（内置 / 租户私有 / 中央市场）+ Dify 式物理复制 + Hugging Face 式 Fork 谱系追踪** 的混合方案。Phase 6 中央市场可演进为真正的托管服务（带审核、CDN 分发、版本追踪），而非仅仅是"静态 JSON 清单"。

#### 8.3.3 三层模板体系

```
Layer 1: 系统内置模板（resources/prompt_chains/）
  ├── weben-extract.chain.json      ← 随应用打包，不可修改
  ├── video-classify.chain.json
  └── ...

Layer 2: 用户模板（DB prompt_chain 表，source_type = "user"）
  ├── my-custom-extract.chain       ← 用户从零创建
  ├── weben-extract-fork.chain      ← Fork 自 Layer 1，forked_from = "weben-extract@v3"
  └── ...

Layer 3: 市场模板（Phase 6，远程 JSON 清单）
  ├── community-summarize.chain     ← 从市场下载，导入为 Layer 2
  └── ...
```

#### 8.3.4 Fork 谱系数据模型

```kotlin
@Serializable
data class PromptChain(
    // ...现有字段...
    val sourceType: String,                    // "builtin" | "user" | "market"
    val forkedFromChainId: String? = null,     // Fork 来源 chain ID
    val forkedFromVersion: Int? = null,        // Fork 时的版本号
    val forkedAt: Long? = null,                // Fork 时间戳
)
```

#### 8.3.5 Fork 操作流程

```
用户在 Chain 列表页点击 "..." → "Fork"
  ↓
系统深拷贝 chain JSON（含所有 nodes、edges、userScript）
  ↓
分配新 chain_id（格式："{original_id}-fork-{timestamp}"）
  ↓
写入 forkedFromChainId / forkedFromVersion
  ↓
用户进入编辑页，可自由修改
```

**上游更新检测**：当系统内置模板随应用升级更新时，UI 在 Fork 链的详情页显示提示：

```
ℹ️ 此 chain 基于系统模板 "weben-extract" v3 创建。
   系统模板已更新至 v5。[查看差异] [合并更新] [忽略]
```

"查看差异"展示 v3 → v5 的变更摘要（新增/删除/修改的节点）。"合并更新"将上游变更应用到用户版本（仅更新用户未修改的节点，用户已修改的节点保持不变）。

#### 8.3.6 新增路由

```
POST /api/v1/PromptChainForkRoute
  Body: { source_chain_id }
  → 返回 { new_chain_id, forked_from_version }

GET  /api/v1/PromptChainUpstreamDiffRoute?chain_id=xxx
  → 返回 { upstream_version, diff: { added_nodes, removed_nodes, modified_nodes } }
```

#### 8.3.7 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | builtin_version 两层模型解决了系统默认，但无 Fork 和复用机制 |
| Phase 3（Fork 谱系） | △+ | 一键 Fork + 上游更新检测 |
| Phase 6（市场） | ✅ | 远程模板清单 + 下载导入 |

---

### 8.4 P7（△）草稿自动保存与丢失防护

#### 8.4.1 业界方案调研

| 产品 | 做法 | 局限 |
|------|------|------|
| **Notion** | 编辑内容每次变更后 debounce 1s 持久化到服务端（带指数退避重试）。`window.beforeunload` 拦截离开。冲突时 last-write-wins | 依赖服务端持久化；离线场景丢失 |
| **VS Code** | 未保存文件写入 `~/.config/Code/User/backups/`。切换文件时检查 `isModified` 标志，弹出"丢弃/保存/取消"对话框。Hot Exit 功能：关闭窗口时自动保存所有未保存文件的状态 | Hot Exit 是桌面应用特有能力 |
| **Linear** | React Router `useBeforeUnload` hook + `isDirty` 状态追踪。编辑时标记 dirty，切换页面前提示确认 | 无本地草稿恢复机制 |
| **Google Docs** | 每次按键后 ~3s 自动保存到服务端。无"未保存"状态概念——所有内容实时持久化 | 需要持续网络连接 |

#### 8.4.2 选型理由

Fredica 兼具 Web 服务端（Ktor :7631）与内嵌 WebView 的特性，两者均可利用：

- **有 Ktor 服务端**：可实现 Google Docs 式服务端实时自动保存（debounce → persist to DB），且天然支持多租户草稿隔离
- **有 localStorage**：可作为离线/弱网时的本地兜底草稿
- **有 `beforeunload` + React Router**：可拦截意外离开
- **多租户草稿隔离**：localStorage key 必须含 `{user_id}:{chain_id}:{node_id}`，避免同浏览器不同登录用户草稿互串；服务端草稿同样按 `user_id` 隔离

因此采用 **服务端自动保存（主）+ localStorage 本地草稿（兜底）+ 路由离开拦截** 的三级保护方案。与原"无服务端"方案的关键区别：Level 1 由 Ktor API 持久化，localStorage 降为 Level 2 离线兜底，而非主力。

#### 8.4.3 方案设计

**三级保护机制**：

```
Level 1: 服务端自动保存（主力，防数据丢失）
  ├── 触发条件：用户停止输入 1.5s 后（debounce）→ PATCH /api/prompt_chain/{id}/draft
  ├── 存储位置：DB prompt_chain_draft 表（按 user_id + chain_id 隔离）
  ├── 存储内容：{ user_id, chain_id, node_id, content, orchScript, updated_at, db_version }
  └── 恢复逻辑：页面加载时调用 GET /api/prompt_chain/{id}/draft，若 updated_at > chain.updated_at 则提示恢复

Level 2: 本地草稿（离线兜底，防服务端不可达）
  ├── 触发条件：Level 1 请求失败 OR 网络不可达时降级写入
  ├── 存储位置：localStorage["prompt_draft:{user_id}:{chain_id}:{node_id}"]（含 user_id 避免多用户串草稿）
  ├── 存储内容：{ content, orchScript, savedAt, dbVersion }
  └── 恢复逻辑：Level 1 恢复失败时检查 localStorage，若 savedAt > DB.updated_at 则提示恢复

Level 3: 离开拦截（防误操作）
  ├── 路由切换：React Router useBlocker → 弹出确认弹窗
  ├── 标签页关闭：window.beforeunload → 浏览器原生确认
  └── 触发条件：isDirty = true（本地内容 ≠ 已保存内容）
```

#### 8.4.4 前端实现

```typescript
// hooks/useAutoSaveDraft.ts
interface DraftData {
  promptContent: string;
  orchScript: string;
  savedAt: number;
  dbVersion: number;  // 保存草稿时的 DB 版本，用于恢复时判断是否过期
}

export function useAutoSaveDraft(chainId: string, nodeId: string, userId: string, dbVersion: number) {
  // localStorage key 含 userId，避免同浏览器多账户草稿互串
  const localDraftKey = `prompt_draft:${userId}:${chainId}:${nodeId}`;
  const [isDirty, setIsDirty] = useState(false);
  const [serverSaveStatus, setServerSaveStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

  // 服务端自动保存（主力）
  const saveToServer = useDebouncedCallback(async (content: string, orchScript: string) => {
    setServerSaveStatus("saving");
    try {
      const resp = await fetch(`/api/prompt_chain/${chainId}/draft`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", ...buildAuthHeaders() },
        body: JSON.stringify({ node_id: nodeId, content, orch_script: orchScript, db_version: dbVersion }),
      });
      if (resp.ok) {
        setServerSaveStatus("saved");
        localStorage.removeItem(localDraftKey);  // 服务端保存成功则清理本地兜底
      } else {
        throw new Error(`status ${resp.status}`);
      }
    } catch {
      setServerSaveStatus("error");
      // 服务端失败则降级写本地兜底
      const draft: DraftData = { promptContent: content, orchScript, savedAt: Date.now(), dbVersion };
      localStorage.setItem(localDraftKey, JSON.stringify(draft));
    }
  }, 1500);

  // 恢复检测（Level 1: 服务端草稿，Level 2: localStorage 兜底）
  const checkRecovery = useCallback(async (): Promise<DraftData | null> => {
    // 优先服务端草稿
    try {
      const resp = await fetch(`/api/prompt_chain/${chainId}/draft?node_id=${nodeId}`, {
        headers: buildAuthHeaders(),
      });
      if (resp.ok) {
        const data = await resp.json();
        if (data.updated_at > dbVersion) return data;
      }
    } catch { /* 降级本地 */ }
    // 本地兜底
    const raw = localStorage.getItem(localDraftKey);
    if (!raw) return null;
    const draft: DraftData = JSON.parse(raw);
    if (draft.dbVersion === dbVersion && draft.savedAt > Date.now() - 7 * 86400_000) return draft;
    localStorage.removeItem(localDraftKey);
    return null;
  }, [chainId, nodeId, localDraftKey, dbVersion]);

  const clearDraft = useCallback(async () => {
    localStorage.removeItem(localDraftKey);
    setIsDirty(false);
    setServerSaveStatus("idle");
    await fetch(`/api/prompt_chain/${chainId}/draft?node_id=${nodeId}`, {
      method: "DELETE", headers: buildAuthHeaders(),
    }).catch(() => {});
  }, [chainId, nodeId, localDraftKey]);

  useBlocker(({ currentLocation, nextLocation }) => {
    return isDirty && currentLocation.pathname !== nextLocation.pathname;
  });

  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (isDirty) { e.preventDefault(); e.returnValue = ""; }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [isDirty]);

  return { isDirty, setIsDirty, serverSaveStatus, saveToServer, clearDraft, checkRecovery };
}
```

#### 8.4.5 恢复 UI

页面加载时，若检测到未保存草稿，在编辑器顶部显示恢复横幅：

```
┌─────────────────────────────────────────────────────────┐
│ ⚠ 检测到未保存的草稿（2 小时前）。[恢复草稿] [丢弃]      │
└─────────────────────────────────────────────────────────┘
```

点击"恢复草稿"将编辑器内容替换为草稿内容，并标记 `isDirty = true`。点击"丢弃"清除 localStorage 中的草稿。

#### 8.4.6 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | 无任何草稿保存机制 |
| Phase 2（localStorage + useBlocker） | ✅ | 覆盖崩溃恢复 + 误操作拦截两大场景 |

---

### 8.5 P8（△）执行调试 UI

#### 8.5.1 业界方案调研

| 产品 | 做法 | 核心 UI 模式 |
|------|------|-------------|
| **LangSmith** | 每次执行生成 Trace，内含层级 Span 树（Parent Call → Child Calls）。每个 Span 显示 inputs/outputs/tokens/latency/cost。支持搜索、过滤、跨 Trace 性能对比 | **层级树 + 指标聚合** |
| **Flowise** | Execution Logs 标签页：列表展示历次执行记录。点击记录展开详情面板，展示节点拓扑图 + 各节点的 input/output JSON（可折叠） | **列表 + 拓扑图叠加** |
| **Dify** | Log 侧栏面板：实时流式显示执行进度。当前运行的节点高亮，已完成节点显示输出摘要。LLM 调用显示 `[streaming...]` 或完整 token count | **实时流式 + 节点高亮** |
| **n8n** | Execution View：整个 workflow 的节点运行状态热力图（绿/红/灰）。点击节点弹出输入/输出 JSON 预览窗口 | **热力图 + 弹窗预览** |

#### 8.5.2 选型理由

Fredica 已有 DAG 可视化（React Flow）和 `PromptNodeRun` 数据模型。最自然的方案是 **Dify 式节点高亮（运行态）+ LangSmith 式 Trace 树（历史查看）** 的组合：

- **运行态**：DAG 画布上节点边框变色（pending → running → completed/failed），实时反映执行进度
- **历史态**：底部面板展示 Trace 树，支持展开查看每个节点的详细输入/输出

#### 8.5.3 数据模型扩展

`PromptNodeRun` 需要补充以下字段以支持调试 UI：

```kotlin
@Serializable
data class PromptNodeRun(
    val id: String,
    val runId: String,                    // 所属的 chain 执行 ID
    val nodeId: String,
    val chainId: String,
    val chainVersion: Int,
    val status: String,                   // "pending" | "running" | "completed" | "failed" | "cached"
    val inputSnapshot: JsonObject?,       // 节点输入变量快照（用于调试重放）
    val outputJson: JsonElement?,         // 节点输出
    val rawLlmResponse: String?,          // LLM 原始返回文本（未经 Schema 解析）
    val parseError: String?,              // JSON/Schema 解析错误信息
    val startedAt: Long,
    val finishedAt: Long?,
    val latencyMs: Long?,
    val llmMetrics: LlmCallMetrics?,      // token 用量 + 估算成本
    val cacheHit: Boolean = false,        // 是否命中 DagCacheManager 缓存
)

@Serializable
data class LlmCallMetrics(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCostUsd: Double?,        // 基于模型定价估算
    val modelId: String,                  // 实际使用的模型
)
```

#### 8.5.4 新增路由

```
GET /api/v1/PromptChainRunDetailRoute?run_id=xxx
  → 返回 {
      run_id, chain_id, status, started_at, finished_at,
      total_tokens, total_cost_usd,
      node_runs: [PromptNodeRun, ...]  // 按执行顺序排列
    }

GET /api/v1/PromptChainRunListRoute?chain_id=xxx&limit=20
  → 返回最近 N 次执行的摘要列表（不含 rawLlmResponse，节省传输）
```

#### 8.5.5 前端 UI 设计

**运行态（DAG 画布叠加层）**：

```
节点状态映射：
  pending   → 灰色边框
  running   → 蓝色边框 + 脉冲动画
  completed → 绿色边框 + ✓ 角标
  failed    → 红色边框 + ✗ 角标
  cached    → 绿色边框 + ⚡ 角标（闪电表示缓存命中）
```

**历史态（底部可收起面板）**：

```
┌── 执行历史 ─────────────────────────────────────────────┐
│ [最近 20 次 ▼]  [仅失败]  [搜索...]           [收起 ▲]  │
├─────────────────────────────────────────────────────────┤
│ ▼ #42  2026-04-18 14:23  ✓ 成功  3.2s  842 tokens $0.01│
│   ├─ extract-concepts  ✓  1.8s  198+47 tokens  缓存:否  │
│   │  ├─ 输入: { chunk: "字幕文本片段..." }               │
│   │  ├─ 提示词: "你是一个视频内容分析专家..."             │
│   │  ├─ LLM 原始返回: "```json\n{\"concepts\":..."      │
│   │  └─ 解析结果: ✓ 符合 ConceptList Schema             │
│   └─ infer-relations   ✓  1.4s  340+155 tokens          │
│                                                         │
│ ▶ #41  2026-04-18 14:22  ✗ 失败  1.1s                   │
│   ├─ extract-concepts  ✓                                │
│   └─ infer-relations   ✗ JSON parse failed: Unexpected  │
│      token at position 42                               │
└─────────────────────────────────────────────────────────┘
```

点击失败节点时，DAG 画布自动聚焦到该节点并高亮，右侧面板切换到该节点的提示词编辑器——形成"发现问题 → 定位节点 → 修改提示词"的闭环。

#### 8.5.6 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | PromptNodeRun 有数据但无专用 UI |
| Phase 3（运行态高亮 + 历史面板） | ✅ | 完整的调试闭环 |

---

### 8.6 P9（△）并发编辑冲突预防

#### 8.6.1 业界方案调研

| 产品 | 做法 | 适用场景 |
|------|------|---------|
| **Figma** | CRDT + OT 混合。每个编辑操作是独立的 transformation，可无冲突地应用于任意顺序。支持毫秒级实时协作 | 高频实时协作（设计工具） |
| **Notion** | 乐观锁 + 版本向量（Vector Clock）。提交时检查版本号，冲突时三路 merge（旧版 + 我的修改 + 服务端新版） | 中频协作（文档编辑） |
| **Linear** | 简化乐观锁。Draft 状态允许并发（last-write-wins）；发布后返回 409 Conflict。假定团队规模小、编辑频率低 | 低频协作（项目管理） |
| **Git** | 三路 merge（common ancestor + ours + theirs）。冲突时标记 `<<<<<<<` 由用户手动解决 | 异步协作（代码） |

#### 8.6.2 选型理由

Fredica 是多租户 Web 服务，并发编辑是真实且常见的场景。核心矛盾在于：

- **多用户同时编辑**：同一租户下多个用户可能同时打开相同 chain 编辑
- **MCP 工具 vs 人工**：Phase 6 后 MCP agent 可通过 API 自动修改 chain，与用户手动编辑形成真实并发
- **跨设备/多标签页**：同一用户在多个标签页同时打开同一 chain

与其在保存时处理冲突（乐观锁 + 事后 diff），不如在**进入编辑时就排他性持有锁**，在编辑期间实时感知"是否有人抢锁"。

**方案：WebSocket 排他锁**

用户打开 chain 编辑器时，客户端建立 WebSocket 连接 `/ws/prompt_chain/{id}/lock`。服务端维护 in-memory 锁表（`chainId → {userId, acquiredAt, wsConn}`）：

- **抢锁成功**：返回 `{ "status": "acquired" }`，当前用户可编辑
- **抢锁失败（已有人在编辑）**：返回 `{ "status": "occupied", "by": username, "since": timestamp }`，前端显示"只读查看模式"
- **心跳续期**：客户端每 30s 发送 `ping`，服务端回 `pong` 并刷新 TTL（默认 2min）；超时未续期则自动释放锁
- **主动释放**：用户关闭编辑器/标签页时，WebSocket 断开即自动释放（无需显式 DELETE）
- **强制夺锁（ROOT）**：ROOT 角色可通过 API 强制释放他人持有的锁（紧急情况处理）

这是 JetBrains Space、Linear 等工具对"谁在编辑"的通用做法：**连接即持锁，断连即释锁**，天然利用 WebSocket 生命周期，无需轮询，无需 TTL 手动管理额外逻辑。

**不引入 CRDT/OT**：WebSocket 锁保证了同一时刻最多一人在编辑，消除了实时协作冲突场景，CRDT/OT 的复杂性完全不需要。

#### 8.6.3 方案设计

**Phase 2：WebSocket 排他锁**

```kotlin
// jvmMain: WsChainLockRoute.kt
data class ChainLockEntry(
    val userId: String,
    val displayName: String,
    val acquiredAt: Long,
    var lastHeartbeat: Long,
    val session: DefaultWebSocketSession,
)

object ChainLockManager {
    private val locks = ConcurrentHashMap<String, ChainLockEntry>()

    fun tryAcquire(chainId: String, userId: String, displayName: String, session: DefaultWebSocketSession): Boolean {
        val existing = locks[chainId]
        if (existing != null && existing.userId != userId && 
            System.currentTimeMillis() - existing.lastHeartbeat < 120_000) {
            return false  // 锁仍有效
        }
        locks[chainId] = ChainLockEntry(userId, displayName, System.currentTimeMillis(), System.currentTimeMillis(), session)
        return true
    }

    fun release(chainId: String, userId: String) {
        locks.remove(chainId) { _, v -> v.userId == userId }
    }

    fun heartbeat(chainId: String, userId: String) {
        locks[chainId]?.takeIf { it.userId == userId }?.lastHeartbeat = System.currentTimeMillis()
    }

    fun getHolder(chainId: String): ChainLockEntry? = locks[chainId]
        ?.takeIf { System.currentTimeMillis() - it.lastHeartbeat < 120_000 }
}
```

WebSocket 消息协议（JSON）：

```
// 客户端 → 服务端
{ "type": "ping" }           // 心跳续期（每 30s）

// 服务端 → 客户端
{ "type": "acquired" }                              // 抢锁成功
{ "type": "occupied", "by": "Alice", "since": 1745000000000 }  // 抢锁失败
{ "type": "pong" }                                  // 心跳回应
{ "type": "released" }                              // 锁被 ROOT 强制释放
```

前端进入编辑器时：

```typescript
// hooks/useChainEditLock.ts
export function useChainEditLock(chainId: string) {
  const [lockState, setLockState] = useState<
    | { status: "acquiring" }
    | { status: "acquired" }
    | { status: "occupied"; by: string; since: number }
    | { status: "disconnected" }
  >({ status: "acquiring" });

  useEffect(() => {
    const ws = new WebSocket(`/ws/prompt_chain/${chainId}/lock`);
    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      if (msg.type === "acquired") setLockState({ status: "acquired" });
      else if (msg.type === "occupied") setLockState({ status: "occupied", by: msg.by, since: msg.since });
      else if (msg.type === "released") setLockState({ status: "acquiring" });  // 重新尝试抢锁
    };
    ws.onclose = () => setLockState({ status: "disconnected" });

    // 心跳
    const heartbeat = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "ping" }));
    }, 30_000);

    return () => { clearInterval(heartbeat); ws.close(); };
  }, [chainId]);

  const isEditable = lockState.status === "acquired";
  return { lockState, isEditable };
}
```

编辑器根据 `isEditable` 决定是否只读：

```
┌── 编辑器 ──────────────────────────────────────────────┐
│                                                        │
│  [isEditable=false 时顶部横幅]                          │
│  ⚠ Alice 正在编辑（3 分钟前）     [申请编辑权限]         │
│                                                        │
│  [Monaco Editor，readOnly={!isEditable}]               │
└────────────────────────────────────────────────────────┘
```

**Phase 3 增强：锁转让**

持有锁的用户可以主动"转让"给申请者（前端通过 WebSocket 推送通知），而不必等待 TTL 超时。适用于"Alice 编辑完了，Bob 等待接力"的工作流场景。

#### 8.6.4 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | ❌ | 后保存者静默覆盖（多租户场景不可接受） |
| Phase 2（WebSocket 排他锁 + 只读查看） | ✅ | 根治并发冲突；非编辑者只读查看不受影响 |
| Phase 3（锁转让 + 申请通知） | ✅+ | 协作流程更顺滑，减少等待超时 |

---

### 8.7 P12（△）跨模块复用与 Fork 谱系

#### 8.7.1 业界方案调研

| 复用模型 | 语义 | 版本同步 | 适用场景 |
|---------|------|---------|---------|
| **GitHub Fork** | 复制整个仓库，可独立修改；与上游通过 Pull Request 同步 | 手动 `git pull upstream`，用户显式合并 | 代码协作 |
| **npm Dependencies** | 声明语义化版本范围（`^1.2.3`），`npm install` 时自动解析 | 随 install 自动更新（受 lock 文件约束） | 包依赖 |
| **Figma Components** | 逻辑引用（非复制）。修改主组件，所有引用即时更新 | 无版本概念，即时同步 | UI 设计 |
| **Hugging Face Hub** | Fork 后独立存储、独立修改。无自动同步，Fork 方手动 pull | 异步协作 |

#### 8.7.2 选型理由

Prompt Chain 的复用需求介于 GitHub Fork 和 npm Dependencies 之间：
- 不能像 Figma 那样即时同步（上游变更可能破坏下游逻辑）
- 不需要 npm 那样的自动版本解析（chain 不是"依赖"，是"参考"）
- 最接近 **GitHub Fork**：复制后独立修改，上游更新时用户手动决定是否合并

但 GitHub Fork 的 Git 操作对非开发者不友好。Fredica 需要将 Fork 语义简化为 UI 操作。

#### 8.7.3 方案设计

**数据模型**（已在 §8.3.4 定义 `forkedFromChainId` 等字段）。

**三种复用模式**：

| 模式 | 语义 | 数据关系 | 适用场景 |
|------|------|---------|---------|
| **Clone** | 完全复制，与上游断开 | `forkedFromChainId` 仅记录来源，无版本追踪 | 一次性参考 |
| **Fork** | 复制 + 上游追踪 | `forkedFromChainId` + `forkedFromVersion`，可检测上游更新 | 基于模板定制 |
| **Reference**（Phase 6） | 逻辑引用，不复制 | 节点内嵌 `ref: "chain_id@version"`，执行时动态解析 | 跨 chain 节点复用 |

Phase 3 实现 Clone 和 Fork；Phase 6 实现 Reference。

**Fork 后的上游更新合并**：

```
用户 Fork 了 "weben-extract@v3"，本地修改了节点 A 和节点 B。
上游发布 v5，变更了节点 B 和节点 C。

合并策略：
  节点 A：用户已修改，上游未变 → 保留用户版本
  节点 B：双方都修改 → 标记冲突，用户选择
  节点 C：用户未修改，上游已变 → 自动采用上游版本
  节点 D（新增）：上游新增 → 自动添加
```

这本质上是 §8.6 节点级三路 merge 的复用。

#### 8.7.4 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | 复用只能手动复制 JSON |
| Phase 3（Clone + Fork） | △+ | 一键 Fork + 上游更新检测 |
| Phase 6（Reference + 市场） | ✅ | 跨 chain 节点引用 + 社区模板 |

---

### 8.8 P13（❌）Schema 演进兼容性

#### 8.8.1 业界方案调研

| 工具 | 演进策略 | 核心机制 |
|------|---------|---------|
| **Protobuf 3** | 字段号永久绑定。新增字段必须 optional；删除字段标记 `reserved`；类型变更极其受限（仅允许 `int32` → `int64` 等安全扩展） | **字段号 = 永久标识**，解耦字段名与序列化格式 |
| **Avro** | Schema Registry 存储 subject 的完整 schema 历史。支持向后兼容 / 向前兼容 / 全兼容三种模式。客户端可指定拉取特定版本 | **兼容性模式可选**，Registry 集中管理 |
| **GraphQL** | `@deprecated(reason: "...")` directive 标记废弃字段。工具链扫描客户端代码，警告使用已废弃字段。无强制移除机制 | **显式废弃标记**，渐进式迁移 |
| **Flyway / Liquibase** | SQL 脚本版本化（`V001__initial.sql`、`V002__add_column.sql`）。每次部署执行未执行过的脚本。支持回滚脚本 | **有序迁移脚本链**，可审计可回滚 |

#### 8.8.2 选型理由

LLM 输出 Schema 的演进与数据库 Schema 演进有本质相似性：
- 都需要向后兼容（旧数据能被新代码解析）
- 都需要迁移机制（旧格式数据转换为新格式）
- 都需要版本追踪（知道当前数据是哪个版本的 Schema 产生的）

但也有关键差异：
- LLM 输出不像数据库那样有"存量数据迁移"的强需求（旧的 `PromptNodeRun` 记录是历史快照，不需要全量迁移）
- 主要需求是**运行时兼容**：当 chain 的某个节点 Schema 升级后，下游节点能正确解析新旧两种格式

因此采用 **Protobuf 式字段标识 + GraphQL 式废弃标记 + Flyway 式迁移脚本** 的三合一方案。

#### 8.8.3 方案设计

**Step 1：Schema 版本化注册表**

```kotlin
@Serializable
data class PromptSchemaVersion(
    val schemaId: String,              // 全局唯一，如 "ConceptList"
    val version: Int,                  // 递增整数
    val jsonSchema: JsonObject,        // 完整 JSON Schema
    val changelog: String,             // 变更说明（人类可读）
    val compatibility: Compatibility,  // 兼容性级别
    val createdAt: Long,
)

enum class Compatibility {
    FULL,              // 完全兼容（纯新增可选字段）
    BACKWARD,          // 向后兼容（新代码能读旧数据，旧代码不能读新数据）
    BREAKING,          // 破坏性变更（需要迁移脚本）
}
```

**Step 2：字段级变更检测**

当用户在 `SchemaFormEditor` 中修改 Schema 时，系统自动检测变更类型：

```typescript
function detectSchemaChange(oldSchema: JSONSchema, newSchema: JSONSchema): ChangeType {
  const addedFields = findAddedFields(oldSchema, newSchema);
  const removedFields = findRemovedFields(oldSchema, newSchema);
  const modifiedFields = findModifiedFields(oldSchema, newSchema);

  // 纯新增可选字段 → FULL 兼容
  if (removedFields.length === 0 && modifiedFields.length === 0
      && addedFields.every(f => !f.required)) {
    return "FULL";
  }

  // 新增必填字段（有默认值）→ BACKWARD 兼容
  if (removedFields.length === 0 && modifiedFields.length === 0
      && addedFields.every(f => f.default !== undefined)) {
    return "BACKWARD";
  }

  // 其他情况 → BREAKING
  return "BREAKING";
}
```

**Step 3：迁移脚本机制**

当检测到 BREAKING 变更时，引导用户编写迁移脚本：

```kotlin
@Serializable
data class SchemaMigration(
    val schemaId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val script: String,          // GraalJS 脚本：(oldData) => newData
    val testCases: List<MigrationTestCase>,  // 迁移脚本的测试用例
)

@Serializable
data class MigrationTestCase(
    val input: JsonElement,      // 旧格式数据
    val expectedOutput: JsonElement,  // 期望的新格式数据
)
```

迁移脚本在 GraalJS 沙箱中执行（复用已有基础设施）。编写完成后，系统自动对 `PromptNodeRun` 历史数据运行迁移脚本，展示转换结果预览：

```
┌── Schema 迁移预览 ──────────────────────────────────────┐
│ ConceptList v2 → v3                                     │
│                                                         │
│ 变更摘要：                                               │
│   + 新增字段 "confidence" (number, required)             │
│   ~ 重命名 "type" → "category"                          │
│   - 删除字段 "legacy_id"                                │
│                                                         │
│ 迁移脚本：                                               │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ (old) => ({                                         │ │
│ │   ...old,                                           │ │
│ │   confidence: old.confidence ?? 0.5,  // 默认值     │ │
│ │   category: old.type,                 // 重命名     │ │
│ │ })                                                  │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ 历史数据验证（最近 10 条 PromptNodeRun）：                │
│   ✓ run-001: 迁移成功                                   │
│   ✓ run-002: 迁移成功                                   │
│   ✗ run-003: 字段 "type" 为 null，迁移后 "category"     │
│              也为 null（不符合 required 约束）            │
│                                                         │
│ [修改迁移脚本]  [确认发布 v3]  [取消]                     │
└─────────────────────────────────────────────────────────┘
```

**Step 4：运行时兼容层**

执行器在解析节点输出时，检查数据的 Schema 版本：

```kotlin
fun resolveOutput(nodeOutput: JsonElement, edgeSchema: PromptSchemaVersion): JsonElement {
    val dataVersion = nodeOutput.jsonObject["_schema_version"]?.jsonPrimitive?.intOrNull ?: 1
    if (dataVersion == edgeSchema.version) return nodeOutput

    // 查找迁移路径：v2 → v3 → v4（链式迁移）
    val migrations = SchemaMigrationService.findMigrationPath(
        schemaId = edgeSchema.schemaId,
        fromVersion = dataVersion,
        toVersion = edgeSchema.version
    )

    var data = nodeOutput
    for (migration in migrations) {
        data = GraalJsSandbox.execute(migration.script, data)
    }
    return data
}
```

#### 8.8.4 GraphQL 式废弃标记

对于渐进式迁移（不立即删除旧字段），在 Schema 中支持 `@deprecated` 标记：

```json
{
  "type": "object",
  "properties": {
    "category": { "type": "string" },
    "type": {
      "type": "string",
      "deprecated": {
        "since": "v3",
        "replacement": "category",
        "reason": "renamed to match upstream API convention"
      }
    }
  }
}
```

`SchemaFormEditor` 中废弃字段显示为灰色删除线，hover 显示废弃原因和替代字段。

#### 8.8.5 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | ❌ | Schema 变更直接破坏下游，无任何迁移机制 |
| Phase 3（版本化 + 迁移脚本 + 废弃标记） | ✅ | 完整的 Schema 生命周期管理 |

**重要**：此设计必须在 Phase 3 与 Schema-on-Edge 同步落地。若 Phase 3 只实现 Schema 绑定而不实现版本化，Phase 4 的 builtin_version 升级将面临无法迁移的困境。

---

### 8.9 P14（△）编排脚本可测试性

#### 8.9.1 业界方案调研

| 工具 | 测试策略 | 核心机制 |
|------|---------|---------|
| **LangChain** | `FakeListLLM`：返回预设列表中的下一个值。`FakeChatModel`：返回固定消息。用户创建 test fixtures，mock LLM 响应，单元测试无需真实 API 调用 | **预设返回值列表** |
| **n8n** | Workflow Test（付费功能）：记录一次真实执行的所有节点输出，后续以其作为 mock 重复运行，验证逻辑是否改变（类似 snapshot testing） | **录制回放** |
| **Jest / Vitest** | `jest.mock()` 模块替换或 `vi.spyOn()` 函数拦截。异步工作流用 `async/await` + Promise chaining | **函数级 mock** |
| **DSPy** | 内置 `dspy.Example` 数据类 + `dspy.evaluate()` 评估框架。测试时用 `dspy.settings.configure(lm=DummyLM())` 替换真实模型 | **全局 LM 替换** |

#### 8.9.2 选型理由

Fredica 已有 GraalJS 沙箱（§5.3 评估引擎），且编排脚本的 `main()` 函数通过 `runPrompt()` 和 `getVar()` 两个注入函数与外部交互。这意味着：
- 不需要像 Jest 那样做模块级 mock（沙箱天然隔离）
- 只需替换 `runPrompt()` 和 `getVar()` 的实现即可完成 mock

因此采用 **LangChain FakeListLLM 思路 + n8n 录制回放思路** 的组合，复用 GraalJS 沙箱基础设施。

#### 8.9.3 方案设计

**TestSandbox：mock 注入的 GraalJS 执行环境**

```kotlin
class PromptScriptTestSandbox(
    private val scriptBody: String,
    private val mockNodeResults: Map<String, List<JsonElement>>,  // nodeId → 返回值队列
    private val mockVars: Map<String, JsonElement>,               // varName → mock 值
) {
    data class TestResult(
        val returnValue: JsonElement?,
        val callSequence: List<NodeCall>,    // runPrompt 调用记录
        val error: String?,
        val executionTimeMs: Long,
        val schemaValidation: SchemaValidationResult?,
    )

    data class NodeCall(
        val nodeId: String,
        val inputVars: JsonObject,
        val returnedValue: JsonElement,
        val callIndex: Int,                  // 第几次调用该 nodeId
    )

    suspend fun execute(): TestResult {
        val callSequence = mutableListOf<NodeCall>()
        val callCounters = mutableMapOf<String, Int>()

        val context = GraalJsContext.create()

        // 注入 mock runPrompt
        context.bind("runPrompt") { nodeId: String, vars: JsonObject ->
            val counter = callCounters.getOrPut(nodeId) { 0 }
            callCounters[nodeId] = counter + 1

            val results = mockNodeResults[nodeId]
                ?: error("runPrompt('$nodeId') called but no mock configured")
            val result = results.getOrElse(counter) {
                error("runPrompt('$nodeId') called ${counter + 1} times but only ${results.size} mock values configured")
            }

            callSequence.add(NodeCall(nodeId, vars, result, counter))
            result
        }

        // 注入 mock getVar
        context.bind("getVar") { key: String ->
            mockVars[key] ?: error("getVar('$key') called but no mock configured")
        }

        // 执行脚本
        val startTime = System.currentTimeMillis()
        val result = context.evaluate(scriptBody)
        val elapsed = System.currentTimeMillis() - startTime

        return TestResult(
            returnValue = result,
            callSequence = callSequence,
            error = null,
            executionTimeMs = elapsed,
            schemaValidation = null,  // 由调用方根据输出 Schema 校验
        )
    }
}
```

**n8n 式录制回放**：用户可以"录制"一次真实执行，系统自动将每个节点的真实输出保存为 mock fixture：

```kotlin
// 从真实执行记录生成 mock 配置
fun generateMockFromRun(runId: String): Map<String, List<JsonElement>> {
    val nodeRuns = PromptNodeRunService.getByRunId(runId)
    return nodeRuns.groupBy { it.nodeId }
        .mapValues { (_, runs) -> runs.sortedBy { it.startedAt }.map { it.outputJson!! } }
}
```

#### 8.9.4 新增路由

```
POST /api/v1/PromptScriptTestRoute
  Body: {
    script: "async function main() { ... }",
    mock_node_results: { "extract-concepts": [{ ... }] },
    mock_vars: { "material_id": "test-001" },
    output_schema_id?: "ConceptList",     // 可选：校验返回值是否符合 Schema
  }
  → 返回 TestResult JSON

POST /api/v1/PromptScriptTestFromRunRoute
  Body: { run_id: "run-abc123", script: "..." }
  → 从历史执行记录自动生成 mock，执行脚本，返回 TestResult
```

#### 8.9.5 前端 UI 设计

在 DAG 编辑器右侧面板新增"测试"Tab：

```
┌── 测试工作台 ────────────────────────────────────────────┐
│                                                         │
│ ─ Mock 配置 ──────────────────────────────────────────── │
│ [从历史执行 #42 导入 ▼]  [手动配置]                       │
│                                                         │
│ runPrompt mock:                                         │
│ ┌─ extract-concepts ──────────────────────────────────┐ │
│ │ 调用 1: { "concepts": [{"name":"Python",...}] }     │ │
│ │ 调用 2: { "concepts": [{"name":"Algorithm",...}] }  │ │
│ │ [+ 添加调用]                                        │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ getVar mock:                                            │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ material_id: "test-001"                             │ │
│ │ subtitle_chunks: ["chunk1", "chunk2"]               │ │
│ └─────────────────────────────────────────────────────┘ │
│                                                         │
│ [▶ 运行测试]                                              │
│                                                         │
│ ─ 执行结果 ──────────────────────────────────────────── │
│ 状态: ✓ 成功  耗时: 12ms                                │
│ 调用序列:                                               │
│   1. runPrompt("extract-concepts", {chunk:"chunk1"})   │
│   2. runPrompt("extract-concepts", {chunk:"chunk2"})   │
│   3. merge([result1, result2])                          │
│ 最终返回: { "concepts": [...], "count": 5 }             │
│ Schema 校验: ✓ 符合 ConceptList v3                      │
└─────────────────────────────────────────────────────────┘
```

#### 8.9.6 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | 只能靠人工触发真实执行来测试 |
| Phase 3（TestSandbox + 录制回放） | ✅ | mock 测试 + 历史数据回放 |

---

### 8.10 P15（△）提示词编辑时的类型一致性反馈

#### 8.10.1 业界方案调研

| 工具 | 做法 | 局限 |
|------|------|------|
| **Jinja2 + IDE 插件** | `pylint-jinja` 静态分析模板，检出未定义变量。运行时 `UndefinedError` 异常 | 仅 Python 生态；无类型信息，只能检测"是否存在" |
| **Handlebars** | 未定义变量沉默失败（输出空字符串）。`eslint-plugin-hbs` 可检测未使用的 helper | 沉默失败是最差的开发体验 |
| **LiquidJS** | 支持 `strict_variables` 模式（未定义变量抛异常而非输出空字符串）。TypeScript 类型声明支持 Filter 返回类型 | 仅运行时检查，无编辑时反馈 |
| **VS Code .env 插件** | `DotENV` 插件提供语法高亮。`Env Syntax` + LSP 支持"跳转到定义"（查找代码中的 `.env` 读取） | 仅高亮，无类型推断 |
| **Cursor / GitHub Copilot** | 基于 LSP 在非 TypeScript 文件中注入虚拟 `.d.ts`。如 `.env.d.ts` 声明 `declare const process.env.API_KEY: string` | 需要 LSP 基础设施 |

#### 8.10.2 选型理由

Fredica 的提示词编辑器将迁移到 Monaco（Phase 2），Monaco 原生支持：
- `addExtraLib()`：动态注入 `.d.ts` 类型声明
- `registerCompletionItemProvider()`：自定义自动补全
- `setModelMarkers()`：自定义诊断标记（波浪线 + hover 提示）

这三个 API 组合起来，可以在不实现完整 LSP 的情况下，为提示词模板变量提供编辑时反馈。

#### 8.10.3 方案设计

**三层反馈机制**：

```
Layer 1: 变量存在性检查（Monaco Markers）
  ├── 解析提示词中的 {{ var_name }} 插值
  ├── 与 TypedVarRegistry 已知变量对比
  └── 未知变量 → 黄色波浪线 + hover 提示

Layer 2: 变量自动补全（CompletionItemProvider）
  ├── 在 {{ 后触发补全
  ├── 列出所有已知变量名 + 类型信息
  └── 选择后自动插入变量名 + 关闭 }}

Layer 3: 变量类型预览（Hover Provider）
  ├── hover 在 {{ var_name }} 上时
  └── 显示变量类型 + 来源节点信息
```

#### 8.10.4 前端实现

```typescript
// promptVarDiagnostics.ts

const TEMPLATE_VAR_REGEX = /\{\{\s*(\w+)\s*\}\}/g;

export function registerPromptVarDiagnostics(
  editor: monaco.editor.IStandaloneCodeEditor,
  knownVars: Map<string, VarTypeInfo>,  // varName → { type, sourceNodeId, description }
) {
  const model = editor.getModel()!;

  // Layer 1: 变量存在性检查
  const updateMarkers = () => {
    const content = model.getValue();
    const markers: monaco.editor.IMarkerData[] = [];

    let match;
    while ((match = TEMPLATE_VAR_REGEX.exec(content)) !== null) {
      const varName = match[1];
      if (!knownVars.has(varName)) {
        const startPos = model.getPositionAt(match.index);
        const endPos = model.getPositionAt(match.index + match[0].length);
        markers.push({
          severity: monaco.MarkerSeverity.Warning,
          message: `变量 "${varName}" 未在当前上下文中定义。已知变量：${[...knownVars.keys()].join(", ")}`,
          startLineNumber: startPos.lineNumber,
          startColumn: startPos.column,
          endLineNumber: endPos.lineNumber,
          endColumn: endPos.column,
        });
      }
    }
    monaco.editor.setModelMarkers(model, "prompt-vars", markers);
  };

  model.onDidChangeContent(updateMarkers);
  updateMarkers();  // 初始检查

  // Layer 2: 变量自动补全
  monaco.languages.registerCompletionItemProvider("markdown", {
    triggerCharacters: ["{"],
    provideCompletionItems(model, position) {
      const textUntilPosition = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: Math.max(1, position.column - 3),
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      });

      if (!textUntilPosition.endsWith("{{")) return { suggestions: [] };

      const suggestions = [...knownVars.entries()].map(([name, info]) => ({
        label: name,
        kind: monaco.languages.CompletionItemKind.Variable,
        detail: `${info.type}${info.sourceNodeId ? ` (from ${info.sourceNodeId})` : ""}`,
        documentation: info.description,
        insertText: ` ${name} }}`,
        range: {
          startLineNumber: position.lineNumber,
          startColumn: position.column,
          endLineNumber: position.lineNumber,
          endColumn: position.column,
        },
      }));

      return { suggestions };
    },
  });

  // Layer 3: 变量类型预览
  monaco.languages.registerHoverProvider("markdown", {
    provideHover(model, position) {
      const word = model.getWordAtPosition(position);
      if (!word) return null;

      // 检查是否在 {{ }} 内
      const lineContent = model.getLineContent(position.lineNumber);
      const beforeWord = lineContent.substring(0, word.startColumn - 1);
      const afterWord = lineContent.substring(word.endColumn - 1);
      if (!beforeWord.includes("{{") || !afterWord.includes("}}")) return null;

      const info = knownVars.get(word.word);
      if (!info) return null;

      return {
        contents: [
          { value: `**${word.word}**: \`${info.type}\`` },
          { value: info.description || "" },
          { value: info.sourceNodeId ? `来源节点: ${info.sourceNodeId}` : "" },
        ].filter(c => c.value),
      };
    },
  });
}
```

#### 8.10.5 满意度预期

| 阶段 | 满意度 | 说明 |
|------|--------|------|
| 当前 | △ | 变量拼错只能在运行时发现 |
| Phase 2（三层反馈机制） | ✅ | 编辑时即时发现未定义变量 + 自动补全 + 类型预览 |

---

## 9. 实施路线图

以下将原散落在各节的阶段计划合并为统一的分阶段表。每个 Phase 可独立交付，后续 Phase 依赖前序完成。

| Phase | 目标 | 关键交付物 | 前置依赖 |
|-------|------|---------|---------|
| **Phase 1** | PromptChain 基础数据模型 | `PromptChain` / `PromptChainNode` / `PromptChainEdge` 数据结构；DB 表 + CRUD 路由；PromptBuilder 适配节点内嵌存储；`generateHeader()` 纯函数实现 | 无 |
| **Phase 2** | TypedVarRegistry + Monaco 迁移 | `PromptVarDescriptor` + Registry 接口；`/api/v1/PromptVarDtsRoute` 端点；Monaco Editor 替换 textarea；`.d.ts` 动态注入；只读 header 保护机制 | Phase 1 |
| **Phase 3** | Schema-on-Edge + DAG 编辑器 MVP | React Flow DAG 画布；7 种节点类型；边 Schema 绑定；类型推断传播；运行态可视化 | Phase 2 |
| **Phase 4** | builtin_version 两层模型 | 默认 PromptChain 随代码打包（`resources/prompts/`）；启动时 seed 到数据库；升级时条件更新逻辑；"重置为默认"UI | Phase 1 |
| **Phase 5** | 优化器 + 评估数据集 | `PromptEvalDataset` 数据模型；评估器（ExactMatch + LlmJudge）；Few-shot 搜索优化循环；优化结果 UI（不自动覆盖） | Phase 3 |
| **Phase 6** | MCP 集成 + 提示词市场 | MCP tools 暴露 DAG CRUD（**需另开独立设计文档**）；chain 导出/导入 JSON；官方内置 chain 市场（只读）；用户 chain 分享（导出） | Phase 3, Phase 4 |

### 各 Phase 详细说明

**Phase 1（PromptChain 基础数据模型）** 是最小可用版本，解决 P1（图形化 vs AI 可编程性）和 P4（控制流表达力）。建立 PromptChain 数据结构（声明层 nodes/edges + 执行层 userScript），实现 `generateHeader()` 纯函数，PromptBuilder 适配节点内嵌存储模式。

**Phase 2（TypedVarRegistry + Monaco 迁移）** 解决 P3（模型多样性 vs 接口统一）和编辑器体验问题。Monaco 替换 textarea 后，`getVar()` 调用有类型补全，拼写错误在编辑时发现。只读 header 保护机制确保用户无法修改 `generateHeader()` 生成的类型声明区域。

**Phase 3（DAG 编辑器 MVP）** 解决 P1（图形化编辑）和 P6（Schema 可视化）。React Flow DAG 是整个方案的核心 UI，Schema-on-Edge 替代提示词文本中的格式描述。注意：§7.2 识别的 P13（Schema 演进兼容性）和 P14（编排脚本可测试性）应在 Phase 3 设计时一并考虑，避免后期破坏性变更。

**Phase 4（builtin_version）** 解决 P5（开箱即用 vs 社区生态）。系统默认 PromptChain 随版本发布，用户修改后保留，升级时提示差异。可与 Phase 2/3 并行推进（只依赖 Phase 1 数据模型）。

**Phase 5（优化器）** 解决 P7（灵活迭代 vs 稳定追溯）。评估驱动的半自动优化，减少人工调参成本。同步引入 §7.3 中识别的运行时成本上限控制（per-chain Token 预算）和业务关联 ID（`entity_id`），补全可观测性盲点。

**Phase 6（MCP + 市场）** 解决 P2（AI 编写 vs 运行时集成）。AI 工具通过 MCP 操作 DAG（**MCP 集成部分需另开独立设计文档**，涉及 tool 注册、权限模型、协议对接）。提示词市场是 Phase 6 中复杂度最高的子系统（涉及服务端托管、审核、安全），应在 MCP 集成后单独评估，不与其他内容捆绑交付。

---

## 10. 开放问题

以下问题尚无定论，需要在实施过程中进一步探索：

**Q1：编排脚本的测试策略**

编排脚本（GraalJS）目前没有单元测试框架。如何在不触发真实 LLM 调用的情况下测试 `main()` 函数的控制流逻辑？

§5.3.3 的 `CustomScript` rubric（GraalJS 检查）已提供一个可行方向：评估系统本身就是在 GraalJS 沙箱内运行测试脚本。复用该机制，可以 mock `runPrompt()` 返回固定值，在沙箱内运行 `main()` 并断言返回结果——相当于复用评估引擎的基础设施作为单元测试框架，无需另造轮子。

**Q2：DAG 的循环/递归支持**

当前 DAG 设计是有向无环图，不支持循环。但某些场景需要"反复调用 LLM 直到满足条件"（如自我修正循环）。是否引入特殊的 `LOOP` 节点，还是通过编排脚本的 `while` 循环处理？

**Q3：流式输出与 DAG 中间节点的兼容性**

SSE 流式输出目前只支持 `LLM_CALL → OUTPUT` 的直接连接。如果中间有 `TRANSFORM` 节点（如流式后处理），如何保持流式体验？

**Q4：多租户场景下的提示词隔离**

Fredica 已有 `GUEST / TENANT / ROOT` 三级角色体系（见 `AuthModels.kt`）。多租户下 `builtin_version` 的 resolve 链为：`用户覆盖 ?? 租户默认 ?? 系统默认`。当前设计（§2.4）以单一全局覆盖表为基础，需要补充租户级覆盖层：新增 `prompt_chain_tenant_default` 表（`tenant_id + builtin_chain_id → chain_id`），ROOT 可为整个租户配置默认提示词，TENANT 用户可在此基础上进一步覆盖。此 resolve 链在 Phase 4 落地时一并实现。

**Q5：DAG 编辑器的移动端适配**

React Flow 的拖拽交互在触摸屏上体验较差。Fredica 是本地部署的 Web 服务，外网浏览器访问意味着用户可能在平板或移动设备上打开管理界面。DAG 编辑器（Phase 3）需要专门的触摸适配方案：触摸拖拽节点、双指缩放画布、长按节点弹出操作菜单。React Flow 已有部分触摸支持（`touchAction: none`），但复杂交互需额外测试。

