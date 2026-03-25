---
title: PromptBuilder 下一阶段实施计划
order: 521
---

# PromptBuilder 下一阶段实施计划

> 目标：把 `summary.weben` 从“前端插值模板 + 页面内最小生成闭环”升级为 **脚本化 Prompt 模板 + 后端沙箱执行 + DB 持久化模板管理 + 更完整的模型选择与请求链路**。
>
> 本阶段不再继续扩“审阅区字段级编辑”，修改入口继续收敛到 **编辑区（Template / Script Editor）**。

---

## 1. 当前状态

目前已经完成：

- `summary.weben` 路由与 PromptBuilder 最小工作台
- Prompt 预览、流式生成、JSON 解析
- 保存到 Weben
- 保存前审阅、校验、删除、save gate
- 基础回归测试与 typecheck

但当前方案仍有几个根本限制：

1. 模板仍偏向“前端字符串插值”，扩展性不足
2. `variables` 与模板结构耦合过强，不利于长期演进
3. 模板持久化仍未进入后端 DB 生命周期
4. 模型选择与请求发送只完成了最小闭环，尚未对齐已有模型配置体系
5. `prompt-builder-design.md` 中关于工作台、编辑器与模板管理的基础设施仍未真正落地
6. 若未来允许更强模板能力，浏览器内执行脚本会带来 XSS 与上下文能力受限问题

---

## 2. 本轮目标

### 2.1 必做目标

本轮聚焦以下能力：

- 脚本化 Prompt 模板模型
- 系统模板 / 用户模板 双来源管理
- 模板后端 DB 持久化
- 后端云函数沙箱执行模板脚本
- 统一上下文读取 API（而不是 `${var}` 插值）
- Schema hint / 业务上下文按运行时函数注入
- 模型选择与请求链路对齐既有 LLM 配置体系
- PromptBuilder 设计稿中真正必要的编辑器与布局基础设施
- 对应测试与文档同步

### 2.2 明确不做

以下内容本轮明确不做：

- 审阅区字段级编辑 concept / relation / flashcard
- 把 review pane 做成结构化表单编辑器
- 在浏览器内执行用户脚本
- 自定义复杂 DSL / AST 语言
- 多信息源大规模扩展（如 scenes / OCR / videoContent 全量接入）
- 把 PromptBuilder 做成独立工作流引擎产品

### 2.3 产品约束

本轮沿用以下约束：

- **改生成逻辑，就改模板脚本**；不在审阅区做字段级修补
- review pane 只负责：查看、删除、校验、保存
- PromptBuilder 继续保持“通用工作台”，业务状态仍由父页面维护
- 模板本身只是一段脚本源码，不再把 `variables` 作为模板元数据核心
- 模板运行时通过统一函数访问上下文，而不是靠字符串插值约定
- 模板脚本执行必须在后端沙箱中完成，不能在前端运行

---

## 3. 核心架构调整

### 3.1 从“变量插值模板”转向“脚本模板”

旧方向的问题：

- `${var}` 只能表达简单拼接
- 一旦需要条件分支、格式化、读取后端上下文，就会迅速失控
- `VariableMeta[]` 容易把“模板语言”和“上下文来源”绑死

新方向：模板正文统一改为脚本形式，例如：

```ts
<script>
export async function main() {
  const subtitle = await getVar("material/current/subtitles/default_text")
  const schemaHint = await getSchemaHint("weben/summary")

  return `请根据以下字幕抽取 Weben 知识：\n\n${schemaHint}\n\n字幕：\n${subtitle}`
}
</script>
```

实际落地时不要求 HTML 语义完整，关键是确立这几个点：

- 模板主体是源码字符串
- 约定入口是 `main()`
- `main()` 返回最终 prompt 文本
- 运行时能力由宿主注入，而不是模板自行 import 浏览器环境

### 3.2 统一上下文访问 API

模板中不再强调 `${weben_schema_hint}` / `${subtitle_text}` 这类插值变量，而是收敛为统一 API。

首批建议支持：

```ts
getVar(path: string): Promise<string>
getSchemaHint(target: string): Promise<string>
readRoute(route: string, params?: Record<string, unknown>): Promise<unknown>
```

示例：

```ts
const subtitle = await getVar("material/current/subtitles/default_text")
const hint = await getSchemaHint("weben/summary")
const modelInfo = await readRoute("/api/v1/LlmModelListRoute", {})
```

其中职责划分应明确：

- `getVar()`：提供稳定、语义化、可缓存的高层上下文入口
- `getSchemaHint()`：提供 schema / domain constraint 的专用入口
- `readRoute()`：提供必要的底层 route 读取能力，作为扩展兜底

原则：

- 优先提供高层 API，避免模板直接依赖大量内部路由细节
- `readRoute()` 保留，但要做白名单与权限边界控制
- 模板语言不负责决定数据从哪里来，由宿主 runtime 决定映射

### 3.3 从“前端执行”转向“后端沙箱执行”

本轮明确要做脚本执行，但执行位置必须在后端。

原因：

- 前端执行用户脚本会引入 XSS 与宿主泄露风险
- 模板需要访问受控上下文与后端路由，天然更适合服务端提供 runtime
- 后端更容易统一做超时、资源限制、白名单、日志与审计

建议方向：

- 增加一层 **Prompt Script Runtime / Cloud Function Sandbox**
- 前端只负责：编辑脚本、选择模板、发起“预览 / 生成”请求、展示结果
- 后端负责：
  - 加载模板脚本
  - 注入 `getVar()` / `getSchemaHint()` / `readRoute()` 等宿主 API
  - 执行 `main()`
  - 返回最终 prompt 文本或执行错误

### 3.4 模板来源收敛为两类

本轮不再使用“三类模板来源”的叙述，只保留：

- **系统模板**：随代码发布，默认只读
- **用户模板**：用户在系统模板基础上复制创建，或从零创建

说明：

- 若用户想修改系统模板，不再把它建模成“system override”这种单独来源
- 用户修改系统模板时，保存结果就是一个新的用户模板
- 是否记录 `originTemplateId` 可作为内部元数据保留，但不作为面向用户的第三种来源概念

---

## 4. Phase 6：脚本模板模型与 DB 持久化

### 4.1 目标

把当前“页面常量模板 + 临时编辑”升级为：

- 系统模板 / 用户模板 双来源
- 模板元数据可查询、可保存、可版本化
- 模板正文是脚本源码
- 模板存储进入后端 DB

### 4.2 建议数据结构

建议将模板定义为：

```ts
interface PromptScriptTemplate {
  id: string
  name: string
  description?: string
  category: PromptCategory
  sourceType: "system" | "user"
  scriptLanguage: "javascript"
  scriptCode: string
  schemaTarget?: string
  basedOnTemplateId?: string
  createdAt: number
  updatedAt: number
}
```

要点：

- 去掉 `variables: VariableMeta[]`
- `scriptCode` 是模板核心
- `schemaTarget` 表示该模板面向的业务 schema 版本
- `basedOnTemplateId` 仅用于追踪来源，不新增第三种模板类型

### 4.3 建议新增/修改文件

建议新增：

- `shared/src/commonMain/kotlin/com/github/project_fredica/db/PromptTemplateDb.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/db/PromptTemplate.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplateListRoute.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplateSaveRoute.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplateDeleteRoute.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplateGetRoute.kt`
- `fredica-webui/app/util/prompt-builder/promptTemplateApi.ts`
- `fredica-webui/app/components/prompt-builder/PromptTemplateSelector.tsx`

建议修改：

- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/all_routes.kt`
- `fredica-webui/app/routes/material.$materialId.summary.weben.tsx`
- `fredica-webui/app/components/prompt-builder/PromptBuilder.tsx`

### 4.4 实施内容

#### A. 后端建模板表

模板表至少包含：

- `id`
- `name`
- `category`
- `source_type`
- `script_language`
- `script_code`
- `schema_target`
- `based_on_template_id`
- `created_at`
- `updated_at`
- `deleted_at`（如项目当前习惯需要软删）

#### B. 系统模板装载策略

系统模板建议继续由代码或资源文件内置，但通过统一服务层暴露为模板列表：

- 前端查询模板列表时，看到“系统模板 + 用户模板”的合并视图
- 系统模板不允许直接覆盖写回 DB
- 用户若从系统模板修改并保存，则创建用户模板

#### C. 用户模板完整 CRUD

至少支持：

- 查询模板列表
- 读取模板详情
- 新建用户模板
- 从系统模板另存为用户模板
- 更新用户模板
- 删除用户模板
- 记录最近使用模板

#### D. 前端不再把模板持久化放在 localStorage

可以保留少量 UI 偏好到 localStorage，例如：

- 当前 pane
- layout 模式
- 最近选中的模板 id

但模板正文与元数据应以后端 DB 为准。

### 4.5 验证

- 系统模板可读、不可删
- 用户可基于系统模板创建新模板
- 用户模板刷新后仍保留
- 模板列表能正确区分“系统 / 用户”
- 页面切换后仍能恢复最近使用模板

---

## 5. Phase 7：后端 Prompt Script Runtime / 沙箱执行

### 5.1 目标

建立一个最小可用、可扩展的后端模板执行运行时，让 Prompt 生成不再依赖前端插值。

### 5.2 运行时职责

后端 runtime 负责：

- 接收模板 id 或脚本源码
- 构造受控执行上下文
- 注入宿主函数：`getVar()` / `getSchemaHint()` / `readRoute()`
- 执行 `main()`
- 返回最终 prompt 文本
- 记录执行错误与必要日志
- 在需要时把结构化运行日志以 SSE 增量回传前端

### 5.3 建议新增/修改文件

建议新增：

- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplatePreviewRoute.kt`
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/PromptTemplateRunRoute.kt`
- `shared/src/jvmMain/kotlin/com/github/project_fredica/prompt/PromptScriptRuntime.kt`
- `shared/src/jvmMain/kotlin/com/github/project_fredica/prompt/PromptRuntimeContextProvider.kt`
- `shared/src/jvmMain/kotlin/com/github/project_fredica/prompt/PromptRouteReader.kt`
- `shared/src/jvmMain/kotlin/com/github/project_fredica/prompt/PromptSandboxPolicy.kt`

建议修改：

- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/all_routes.kt`
- `fredica-webui/app/util/materialWebenApi.ts`
- `fredica-webui/app/routes/material.$materialId.summary.weben.tsx`

### 5.4 沙箱实现建议

本轮重点是先把架构边界定对，不要求一步做到重量级平台化。

建议路线：

#### A. 引擎选型：GraalJS

**确定选 GraalJS**（`org.graalvm.polyglot:js` / `org.graalvm.js:js-scriptengine`），由 Kotlin 在同一 JVM 进程内直接托管运行时。

项目已在 `workflow-design.md` 中提过 GraalVM JavaScript 方案，技术方向与现有架构一致。

架构分层：

```txt
Kotlin Route (Ktor)
  -> PromptSandboxService
      -> Engine（单例复用，线程安全）
          -> Context（每次执行独立创建，执行完立即 close）
              -> 注入受控宿主 API（ProxyObject / ProxyExecutable）
              -> 执行模板脚本 main()
              -> 返回 SandboxResult { prompt, logs, error }
```

关键点：

- `Engine` 全局共享（JIT 热身缓存可复用，减少开销）
- `Context` 每次执行独立 new + `close()`，不跨请求复用脚本状态
- Deno 作为未来更强隔离的备选方向，但不是本轮前提

#### B. 第一版目标

满足以下能力即可：

- 只允许执行模板约定脚本
- 只暴露受控 runtime API
- 有超时
- 有资源保护措施（见 D 节）
- 不允许访问宿主 Java 类、网络、文件系统

#### C. 全局对象与禁用项策略

模板运行环境应采用 **默认不暴露、按需注入** 的策略，而不是先给完整 JS 环境再逐个删除。

建议：

- 只注入：
  - `getVar`
  - `getSchemaHint`
  - `readRoute`
  - `console`
  - `setTimeout` / `clearTimeout`（如确有需要）
  - 基础标准对象：`Array` / `Object` / `String` / `Number` / `Boolean` / `JSON` / `Math` / `Date` / `Promise`
- 明确不提供：
  - `window`
  - `document`
  - `fetch`
  - `XMLHttpRequest`
  - `WebSocket`
  - `localStorage` / `sessionStorage`
  - `process`
  - `require`
  - `import()`
  - `Function`
  - `eval`
  - `globalThis.confirm` / `alert` / `prompt`

这里你的例子里提到的 `confirm`，我建议和 `eval` 一样都明确禁用：

- `confirm` / `alert` / `prompt` 在服务端语义本来就不成立
- `eval` / `Function` 会显著放大逃逸面与静态检查难度

原则上应让模板作者只能使用：

- 普通 JS 语法
- 受控宿主 API
- `main()` 返回值

#### D. 资源耗尽与执行控制

GraalJS 运行在同一 JVM 进程内，**不存在操作系统级进程隔离边界**，因此资源控制完全靠应用层实现。需要同时从三个层面着手。

##### D-1. 超时：`context.interrupt()` + 外部 watchdog

GraalJS `Context` 支持 `context.interrupt(timeout)` 或从外部线程调用 `context.close(true)`，但有一个重要限制：

- 只在脚本执行到安全检查点时才能响应中断
- **纯 CPU 死循环 `while(true) {}` 在某些 JVM/GraalJS 版本下可能不会立即中断**

因此建议同时做两层保护：

```
1. context.interrupt(Duration.ofSeconds(5))  // 软超时，GraalJS 内置
2. 外部 Kotlin coroutine watchdog
     -> delay(MAX_TIMEOUT)
     -> context.close(true)                  // 强制关闭，最后兜底
```

超时触发后，`close(true)` 会引发 `PolyglotException`，需捕获并返回 `SandboxResult.timeout`。

建议默认值：

| 场景 | 软超时 | 强制超时 |
|------|--------|----------|
| Preview（编辑时预览）| 3s | 5s |
| Generate（正式执行）| 5s | 8s |

##### D-2. CPU 死循环防护

GraalJS 本身不提供语句计数器限制（不像 V8 的 `--max-old-space-size`），纯 CPU 死循环只能靠外部线程强制 `close(true)`。

建议：

- 把脚本执行放在**独立线程**（而非协程线程池），方便从外部 watchdog 强制中断
- watchdog 使用 `Dispatchers.IO` 上独立的 CoroutineScope，不与业务逻辑共用 dispatcher
- 如 JVM 线程未能及时响应中断，watchdog 最终调用 `thread.interrupt()` 后记录告警日志

##### D-3. 内存膨胀防护

GraalJS 没有内置的”单次脚本最大内存用量”控制，内存溢出会直接影响主 JVM 进程。

缓解策略：

- **在宿主函数层限制输入大小**：`getVar()` / `readRoute()` 返回值超过上限时直接截断并记录 warning，不把超大文本传入脚本
- **限制 `main()` 返回值长度**：在拿到返回字符串后，超过最大 prompt 长度时拒绝并报错（建议上限 64KB）
- **限制 `console` 输出**：每条日志最大 1KB，累计不超过 50 条 / 10KB
- 不允许模板脚本自己读取超大输入（字幕原文应由宿主预处理后以摘要形式注入）
- 在 JVM 启动参数中为整体服务设置合理堆上限，避免单个请求拖垮整个 Ktor 服务

##### D-4. 宿主 API 调用次数限制

在 `PromptRuntimeContextProvider` 内部为每次 Context 维护计数器：

```kotlin
var apiCallCount = 0
val MAX_API_CALLS = 20

fun guardedGetVar(path: String): String {
    if (++apiCallCount > MAX_API_CALLS) throw SandboxQuotaExceeded(“getVar 调用次数超限”)
    return contextProvider.getVar(path)
}
```

适用于 `getVar` / `getSchemaHint` / `readRoute` 全部宿主函数。

##### D-5. 并发控制

- 使用 `Semaphore` 或 `Channel` 限制同时运行的 Context 数量（建议 Preview 最大 3，Generate 最大 2）
- 排队超时时返回 `503 / sandbox_busy` 而非无限等待
- 预览请求应可被新请求抢占（编辑器防抖后只保留最新一次）

##### D-6. 明确的 scope 定位

第一版要把模板脚本的能力**明确收敛在**：

- 读取少量上下文变量
- 字符串格式化与拼接
- 简单条件判断
- 返回一段 prompt 文本

**不支持**：

- 长时间异步任务
- 大规模数据处理
- 多轮递归或深层循环
- 任何绕过宿主函数的外部访问

#### E. 日志与 console 设计

建议保留 `console.log`，但不要直接透传原始 stdout 文本，而是转为 **结构化事件**。

例如内部事件模型：

```json
{ "type": "log", "level": "info", "args": ["subtitle length", 1234], "ts": 1742800000000 }
{ "type": "result", "prompt": "..." }
{ "type": "error", "message": "readRoute not allowed: /api/v1/xxx" }
```

这样做的好处：

- 前端可以决定是否展示 log pane
- 不依赖脚本引擎的原始 stdout/stderr 约定
- 后端可以更容易限制日志大小、过滤敏感字段、做审计

在 GraalJS 方案下，`console` 应由宿主注入一个受控对象，而不是把宿主标准输出直接暴露给脚本。

#### F. 是否通过 SSE 向前端传 `console.log`

建议：**支持，但默认只在 Preview / Debug 场景开启**。

更具体地说：

- `PromptTemplatePreviewRoute` 适合支持 SSE，逐步回传：
  - `log`
  - `warning`
  - `error`
  - `result`
- 正式“生成并调用 LLM”的主链路，不一定要把模板运行日志完整暴露给用户；默认可只在调试模式下显示

原因：

- 模板调试时，`console.log` 很有价值
- 但正常用户生成时，大量日志会干扰主流程
- 如果未来要复用既有 SSE 解析逻辑，项目里已有 `SseLineParser` 可作为基础协议处理能力

因此建议采用：

- Preview：SSE 日志流 + 最终结果
- Run/Generate：默认只返回必要错误；如开启 debug，再附带日志流

#### G. GraalJS Context 初始化配置要点

每次执行使用如下配置创建 Context：

```kotlin
val context = Context.newBuilder(“js”)
    .allowAllAccess(false)                    // 关闭所有宿主访问
    .allowHostAccess(HostAccess.NONE)         // 禁止访问 Java 对象
    .allowHostClassLookup { false }           // 禁止 Java.type()
    .allowIO(IOAccess.NONE)                   // 禁止文件 IO
    .allowCreateProcess(false)                // 禁止子进程
    .allowNativeAccess(false)                 // 禁止 native 扩展
    .allowEnvironmentAccess(EnvironmentAccess.NONE) // 禁止读取环境变量
    .allowExperimentalOptions(false)
    .option(“js.strict”, “true”)
    .build()
```

然后通过 `context.getBindings(“js”).putMember(...)` 只注入：

- `getVar`（ProxyExecutable）
- `getSchemaHint`（ProxyExecutable）
- `readRoute`（ProxyExecutable）
- `console`（ProxyObject，内部做日志限额）

不要调用 `context.eval()` 注入完整的 `globalThis` 扩展，避免意外暴露宿主能力。

#### H. Deno 定位

确定不在当前阶段引入 Deno。

若未来 PromptBuilder 演进到需要真正的进程级隔离或细粒度网络权限（如允许 `fetch` 特定白名单 host），再评估内置 Deno 作为独立 runtime。当前阶段 API 设计与引擎无关，迁移成本可控。

### 5.5 验证

- 脚本可成功返回 prompt 文本
- `getVar()` / `getSchemaHint()` / `readRoute()` 可在受控范围内工作
- `eval` / `Function` / `confirm` / `fetch` / `Java.type` / host class lookup 不可用
- CPU 死循环在外部 watchdog 强制 `close(true)` 后正确中断，返回 timeout 错误
- 超大返回值被截断并报错，不影响主服务
- 宿主 API 调用次数超限时返回 quota 错误
- 并发超限时返回 503 / sandbox_busy
- `console` 日志条数 / 字节数超限后静默截断，不导致执行失败
- Preview 模式下可按需通过 SSE 展示结构化 `console` 日志
- 脚本抛错时返回结构化错误，前端可展示错误位置
- 前端无需再自己拼插值 prompt

---

## 6. Phase 8：Schema hint 与上下文读取规范化

### 6.1 目标

让模板和业务 schema 的关系继续可检查、可提示，但注入方式从字符串插值改为 runtime API。

### 6.2 实施内容

#### A. 保留 schema registry

继续保留 schema 注册表，例如：

```ts
const WEBEN_SCHEMA_VERSIONS = {
  weben_v1: {
    conceptTypes: [...],
    predicates: [...],
    hint: "...",
  },
}
```

但使用方式改为：

```ts
const schemaHint = await getSchemaHint("weben/summary")
```

#### B. 模板声明 `schemaTarget`

- 系统模板必须声明 `schemaTarget`
- 用户模板创建时继承或显式设置 `schemaTarget`
- 当模板面向旧 schema 时，UI 给出 warning，但不阻止编辑和生成

#### C. 定义 `getVar()` 路径约定

建议尽早确定稳定路径前缀，例如：

```txt
material/current/subtitles/default_text
material/current/meta/title
material/current/meta/source_id
weben/current/schema_target
weben/current/source_summary
```

要求：

- 路径语义稳定，不直接暴露底层组件实现细节
- 同一路径可映射到后端 provider / route / cache
- 前端与后端共享一份路径规范文档

#### D. 区分“推荐 API”与“底层兜底 API”

- 推荐：`getVar()` / `getSchemaHint()`
- 兜底：`readRoute()`

这样可以避免模板大量耦合后端 route 名称。

### 6.3 验证

- 系统模板可显示 schema 版本信息
- 旧模板可被识别并提示
- 模板不再依赖 `${weben_schema_hint}` 这类插值语法
- `getSchemaHint("weben/summary")` 始终返回当前版本约束文本

---

## 7. Phase 9：模型选择与请求链路对齐既有 LLM 配置体系

### 7.1 目标

把 `summary.weben` 当前最小版“选择 model id 并请求 LLM”，升级为更接近 `app-common-setting-llm-model-config.tsx` 的一致体验与数据来源。

### 7.2 设计参考

重点参考：

- `fredica-webui/app/routes/app-common-setting-llm-model-config.tsx`

尤其要对齐以下事实：

- 模型有完整配置实体，而不只是 `app_model_id`
- 系统内已有默认角色（如 `chat_model_id`）
- 已有 direct / router / bridge 三种测试模式与 `llmChat()` 能力边界

### 7.3 实施内容

#### A. 统一模型来源

`summary.weben` 不应再维护一套孤立的“模型 id 下拉框”逻辑，而应：

- 读取系统模型列表
- 读取默认角色（至少 `chat_model_id`）
- 在默认模型失效时给出回退或提示

#### B. 模型选择 UI 改进

至少支持：

- 显示当前默认聊天模型
- 切换到其他候选模型
- 当模型缺失 / 已删除 / 不可用时给出 inline 提示
- 允许跳转到模型配置页做修复

#### C. 请求链路错误分层

明确区分：

- 模板脚本执行失败
- Prompt 预览失败
- LLM 请求失败
- HTTP 非 2xx
- SSE 中途失败
- 输出 parse 失败

并分别在：

- Editor / Preview pane
- Stream pane
- Render pane

给出对应反馈。

#### D. 保留 partial output

若流式请求中途失败：

- 保留已收到的 `streamText`
- 在 Stream Pane 顶部展示错误
- 不清空已有内容

#### E. 支持取消生成

- 生成中显示取消按钮
- 使用 `AbortController` 或既有取消机制
- 取消后 UI 回到可继续操作状态

#### F. 尽量收敛到 `llmChat()`

若 Prompt script runtime 输出的是最终 prompt 文本，则前端发起 LLM 调用部分应尽量复用既有 `llmChat()`，避免 `summary.weben` 自己维护独立协议。

### 7.4 验证

- 默认模型缺失时不会把无效 id 发给后端
- 可识别并提示模型配置缺失
- HTTP / runtime / SSE 错误都能正确提示
- 中途失败时保留 partial output
- 取消生成后页面状态恢复正常

---

## 8. Phase 10：落实 PromptBuilder 设计稿剩余基础设施

### 8.1 目标

把 `prompt-builder-design.md` 中对当前场景真正有价值的工作台能力补齐，但适配“脚本模板 + 后端运行时”的新架构。

### 8.2 建议新增/修改文件

建议新增：

- `fredica-webui/app/components/prompt-builder/PromptSplitLayout.tsx`
- `fredica-webui/app/components/prompt-builder/PromptEditor.tsx`
- `fredica-webui/app/components/prompt-builder/PromptEditorPane.tsx`
- `fredica-webui/app/components/prompt-builder/promptEditorExtensions.ts`
- `fredica-webui/app/components/prompt-builder/promptEditorKeymaps.ts`
- `fredica-webui/app/components/prompt-builder/promptScriptLanguage.ts`

建议修改：

- `fredica-webui/app/components/prompt-builder/PromptBuilder.tsx`
- `fredica-webui/app/components/prompt-builder/PromptPreviewPane.tsx`
- `fredica-webui/app/components/prompt-builder/PromptStreamPane.tsx`

### 8.3 实施内容

#### A. split layout

- 单栏 / 双栏切换
- 左栏固定 editor
- 右栏切换 preview / stream / render
- layout 状态按 category 持久化

#### B. PromptEditor 升级为脚本编辑器

从当前最小编辑区升级为统一的 PromptEditor：

- 基于 `@uiw/react-codemirror`
- 优先服务脚本模板场景
- 强调 `main()` 入口、宿主 API 提示、错误定位

#### C. 语法高亮与宿主 API 补全

重点不再是 `${var}` 补全，而是：

- `main()` 模板骨架
- `getVar()` / `getSchemaHint()` / `readRoute()` 自动补全
- 常见路径片段提示
- 基础 lint / 诊断信息

#### D. expert mode 重新定义

- expert mode 不再意味着“浏览器内执行更强能力”
- expert mode 表示“脚本编辑能力更强、信息更密、可见更多 runtime 细节”
- 安全边界始终由后端 runtime 保证

### 8.4 验证

- 单栏 / 双栏切换后 pane 不卸载
- CodeMirror 编辑器内容、光标、滚动状态保持稳定
- 脚本编辑器能清晰展示 runtime API
- PromptBuilder 仍然不接管业务状态，仅负责工作台编排

---

## 9. 测试计划

### 9.1 Kotlin / backend tests

新增或补充：

- `PromptTemplateDb`
- 模板 CRUD routes
- 系统模板 + 用户模板合并逻辑
- Prompt script runtime 成功执行
- runtime 超时 / 抛错 / 非法 route 访问
- `getVar()` / `getSchemaHint()` / `readRoute()` provider

### 9.2 前端 util / component / route tests

补充：

- 模板切换 / 新建 / 保存 / 删除
- 系统 / 用户来源标识
- schema 兼容 warning
- 模型列表为空 / 失效 / 请求失败
- split layout 切换
- tab 切换不卸载 pane
- 脚本编辑器基础补全与提示
- partial output 保留

### 9.3 自动化验证命令

在 `fredica-webui/` 下运行：

- `npm test -- SummaryWebenPage.test.tsx`
- `npm test -- tests/util/*.test.ts`
- `npm run typecheck`

在仓库根目录运行：

- `./gradlew :shared:build`
- `./gradlew :shared:jvmTest`

---

## 10. 建议开发顺序

```txt
1. 定义 PromptScriptTemplate 数据模型与 DB 表
2. 建模板 CRUD routes + 系统模板装载机制
3. 建 Prompt script runtime 与受控宿主 API
4. 定义 getVar / getSchemaHint / readRoute 路径与白名单
5. summary.weben 接入模板列表、模板保存、脚本预览
6. 生成链路改为：模板脚本 -> runtime -> 最终 prompt -> llmChat()
7. 模型选择与错误处理对齐 llm-model-config 页面体系
8. PromptBuilder split layout + CodeMirror 脚本编辑器
9. 补 Kotlin / 前端测试与文档同步
```

---

## 11. 关键复用文件

- `docs/dev/plans/prompt-builder-design.md`
  - PromptBuilder 整体形态与工作台设计依据
- `docs/dev/plans/prompt-builder-implementation-plan.md`
  - 最小闭环的实现边界与当前基线
- `fredica-webui/app/routes/material.$materialId.summary.weben.tsx`
  - 当前业务接入页
- `fredica-webui/app/routes/app-common-setting-llm-model-config.tsx`
  - 模型配置、默认角色、测试模式的参考实现
- `fredica-webui/app/components/prompt-builder/*`
  - 现有 PromptBuilder 工作台壳
- `fredica-webui/app/util/materialWebenApi.ts`
  - 当前模型列表、字幕内容、保存入口
- `shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/*`
  - 新模板 CRUD / preview / run routes 的落点

---

## 12. 成功标准

本轮完成后，应满足：

1. 模板来源清晰收敛为：系统模板、用户模板
2. 模板正文已从插值字符串升级为脚本源码，入口统一为 `main()`
3. 模板运行时通过 `getVar()` / `getSchemaHint()` / `readRoute()` 获取上下文，而不是靠 `${var}`
4. 模板持久化进入后端 DB，而不是继续停留在前端 localStorage
5. 脚本执行发生在后端受控沙箱中，而不是浏览器内
6. `summary.weben` 的模型选择与请求链路更接近现有 LLM 配置体系
7. PromptBuilder 从“最小壳子”升级为支持脚本模板的通用工作台基础设施
8. 审阅区继续保持轻量，不演化成字段级编辑器
