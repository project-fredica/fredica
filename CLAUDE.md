# Fredica 项目开发指南

> 本文件面向 AI 辅助开发场景，汇总架构、开发规范与关键约定。

---

## 1. 项目概况

Kotlin Multiplatform (KMP) 桌面应用，采用**本地 Ktor 服务 + 内嵌 WebView** 架构。

| 模块 | 技术 | 职责 |
|------|------|------|
| `composeApp` | Compose Multiplatform | 桌面窗口、WebView 宿主、JS Bridge |
| `shared/commonMain` | KMP（纯 Kotlin） | API 路由模型、DB 模型、Worker 引擎、工具函数 |
| `shared/jvmMain` | Ktor + Ktorm | HTTP 服务启动、SQLite、JVM Executor |
| `fredica-webui` | React 19 + React Router 7 | 前端界面（独立 Node 工程） |
| `desktop_assets/common/fredica-pyutil` | Python FastAPI | Python 辅助服务（随 composeApp 子进程启动） |

**端口：** `7630` React 开发服务器 · `7631` Ktor API · `7632` Python 服务

**关键依赖：** Kotlin `2.3.0` · Compose MP `1.9.3` · Ktor `3.3.3` · Ktorm `4.1.1` · React `19` · Tailwind CSS `4`

---

## 2. 目录结构速览

```
shared/src/
├── commonMain/kotlin/.../
│   ├── api/routes/       # 所有 API 路由（all_routes.kt 集中注册）
│   ├── db/               # Task / WorkflowRun + DB 实现
│   ├── llm/              # LLM 多模型配置
│   ├── worker/           # WorkerEngine + TaskExecutor 接口
│   └── apputil/          # 工具函数（含 SseLineParser、PyCallGuard）
├── jvmMain/kotlin/.../
│   ├── api/FredicaApi.jvm.kt   # Ktor 启动、认证、DB/Worker 初始化
│   ├── python/PythonUtil.kt    # Python 服务 HTTP/WebSocket 客户端
│   ├── llm/LlmSseClient.kt     # SSE 流式 LLM 客户端
│   └── worker/executors/       # JVM 专属 Executor
└── jvmTest/kotlin/             # 所有单元测试（SQLite 临时文件隔离）

fredica-webui/                         # React 前端
desktop_assets/common/fredica-pyutil/  # Python 服务源代码
└── fredica_pyutil_server/
    ├── routes/     # 自动扫描加载的 API 路由
    ├── subprocess/ # 子进程封装（transcribe, transcode）
    └── util/       # TaskEndpoint 基类 + device_util + ffmpeg_util

docs/               # VitePress 文档站（sortMenusByFrontmatterOrder）
├── dev/            # 开发文档（order 分段：0-99 入门 · 100-199 规范 · 200+ 专题）
│   ├── setup.md / architecture.md / frontend-backend-bridge.md / task-model.md / build.md
│   ├── error-handling.md / json-handling.md / testing.md / git-commit-style.md / vitepress-guide.md
│   ├── image-proxy.md
│   └── plans/      # 设计文档（order 500+）
├── guide/          # 用户文档
└── product/        # 产品文档
```

---

## 3. 构建与测试命令

```shell
# 运行桌面应用（开发入口）
./gradlew :composeApp:run

# 构建 shared 模块（修改 Kotlin 代码后必须执行）
./gradlew :shared:build

# 运行全部单元测试
./gradlew :shared:jvmTest

# 运行单个测试类
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.TaskDbTest"

# 前端开发（在 fredica-webui/ 目录执行）
cd fredica-webui && npm install && npm run dev
```

测试文件位于 `shared/src/jvmTest/kotlin/`，详见 [docs/dev/testing.md](docs/dev/testing.md)。

---

## 4. 工具函数速查

### Kotlin（`shared/src/commonMain/.../apputil/`）

| 文件 | 主要 API |
|------|---------|
| `jsons.kt` | `buildJsonObject { put() }.toValidJson()` · `loadJsonModel<T>()` · `dumpJsonStr()` · `asT<T>()` · 详见 [docs/dev/json-handling.md](docs/dev/json-handling.md) |
| `commonUtil.kt` | `Result.wrap { }` · `wrapAsync { }` · `Double.toFixed(n)` |
| `logger.kt` | `createLogger("name")` → `logger.error(msg, throwable)` 需显式导入扩展函数 |
| `AppUtil.kt` | `Paths`（应用路径）· `GlobalVars`（全局 HTTP 客户端）· 时间工具 |
| `SseLineParser.kt` | `SseLineParser.parseLine(line)` → 解析 SSE `data:` 行 |
| `PyCallGuard.kt` | `PyCallGuard().withLock(key) { }` → per-resource 两级锁，防并发重复调用 Python |

### JVM 专属（`shared/src/jvmMain/.../`）

| 文件 | 主要 API |
|------|---------|
| `python/PythonUtil.kt` | HTTP: `Py314Embed.PyUtilServer.requestText(method, path, body?, timeoutMs?)` |
| `python/PythonUtil.kt` | WebSocket 长任务: `websocketTask(path, paramJson, onProgress?, cancelSignal?, pauseChannel?, resumeChannel?)` → `String?` |
| `llm/LlmSseClient.kt` | `LlmSseClient.streamChat(modelConfig, requestBody, onChunk?, cancelSignal?)` → `String?` |

### 前端（`fredica-webui/app/`）

| 文件 | 主要 API |
|------|---------|
| `util/app_fetch.ts` | `useAppFetch`、`useImageProxyUrl`、`parseJsonBody`、`buildAuthHeaders` |
| `context/appConfig.tsx` | 应用配置 Context（持久化到 localStorage；WebView 通过 bridge `get_server_info` 初始化） |
| `util/bridge.ts` | `callBridge(method, params)` · `callBridgeOrNull(method, params)` · 详见 [docs/dev/frontend-backend-bridge.md](docs/dev/frontend-backend-bridge.md) |
| `util/llm.ts` | `llmChat(params): AsyncGenerator<string>`（direct/router/bridge 三模式） |

---

## 5. 关键技术约定

### 测试隔离

- **SQLite 测试必须用临时文件**，不能用 `:memory:`（ktorm 连接池每次开新连接，内存库各连接独立）
- **WorkerEngine 测试**：`@AfterTest` 中取消所有 `activeScopes` 记录的 CoroutineScope

### 职责边界

- `TaskDb`：只操作 task 表，**不含 recalculate()**
- `WorkflowRunDb`：管理 workflow_run 表，含 recalculate()
- `WorkerEngine`：任务完成后调用 `WorkflowRunService.repo.recalculate()`，**不是** `TaskService`

### 常见陷阱

- `logger.error(msg, throwable)` 需显式导入 `import com.github.project_fredica.apputil.error`
- `logger.warn` 只有单参重载，Throwable 要手动提取 `.message`
- GET 路由 param：`Map<String, List<String>>` JSON，用 `query["key"]?.firstOrNull()`
- `FredicaApi.PyUtil.post()` 在 jvmMain 需 `import com.github.project_fredica.api.post`
- `kv()` / `obj()` 已删除，改用 `buildJsonObject { put(...) }`
- `PromptGraphDb` / `PromptGraphEngine` 在 commonMain（非 jvmMain）
- `assertNotNull(x)` 返回 `T`（非 Unit），作为 runBlocking 末尾语句需加 `Unit`

### 错误处理约定

详见 [docs/dev/error-handling.md](docs/dev/error-handling.md)，核心规则：

- **Python**：预期失败用 `logger.warning`；意外异常用 `logger.exception`；工具函数不抛异常，用 `TypedDict` 标注返回类型并写 docstring，统一含 `"error"` 字段；路由层 `except` 返回 `JSONResponse({"error": ...})`
- **Kotlin JsBridge**：Python 调用失败用 `logger.warn` + `callback({"error": "..."})`；发起调用前打一条入参日志
- **Kotlin Route API**：`try/catch` 包裹业务逻辑，异常时 `respond({"error": ...})`
- **前端 catch 块**：无响应信息用 `print_error`，有 HTTP 响应用 `reportHttpError`（自动附加状态码）；`BridgeUnavailableError` 静默；bridge 返回后检查 `res.error` 字段

### Git Commit 约定

详见 [docs/dev/git-commit-style.md](docs/dev/git-commit-style.md)，核心规则：

- **格式**：`type(scope): subject`，正文按模块分组，每条变更 `- 类名: 做了什么`
- **wip**：开发中随时用 `wip: ...` 暂存，不必等功能完整
- **常用 type**：`feat` · `fix` · `refactor` · `docs` · `test` · `wip`
- **跨层 scope**：用 `+` 连接，如 `feat(asr+webui):` · `refactor(json+error):`

### JSON 处理约定

详见 [docs/dev/json-handling.md](docs/dev/json-handling.md)，核心规则：

- **Kotlin 构建 JSON**：用 `buildJsonObject { put("k", v) }.toValidJson()`（路由返回）或 `.toString()`（paramJson 等），禁止字符串插值拼接 JSON
- **Kotlin 序列化**：优先用扩展函数 `elem.dumpJsonStr()` / `AppUtil.dumpJsonStr(obj)`，不直接调 `AppUtil.GlobalVars.json.encodeToString(...)`
- **Kotlin 反序列化**：优先用扩展函数 `str.loadJson()` / `str.loadJsonModel<T>()`，不直接调 `AppUtil.GlobalVars.json.decodeFromString(...)`
- **Kotlin 提取字段**：`jsonObj["key"].asT<String>()`
- 全局实例 `AppUtil.GlobalVars.json` 需显式 import `com.github.project_fredica.apputil.json`，仅在无扩展函数可用时直接使用
- **Python 工具函数**：返回值用 `TypedDict` 标注，字段含义写在 docstring 中
- **前端**：`JSON.parse(raw) as T` 解析 bridge 返回；`reportHttpError` 处理 HTTP 错误响应
