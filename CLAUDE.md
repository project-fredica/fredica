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
├── dev/            # 开发文档（setup / architecture / task-model / build / testing / plans/）
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
| `jsons.kt` | `encodeJson<T>()` · `decodeJson<T>()` · `buildValidJson { }` DSL |
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
| `util/bridge.ts` | `getBridge()` 获取 JsBridge |
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
- `kv()` / `obj()` 是 `buildValidJson { }` DSL 作用域方法，不可顶层 import
- `PromptGraphDb` / `PromptGraphEngine` 在 commonMain（非 jvmMain）
- `assertNotNull(x)` 返回 `T`（非 Unit），作为 runBlocking 末尾语句需加 `Unit`
