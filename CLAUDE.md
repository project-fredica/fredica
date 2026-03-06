# Fredica 项目开发指南

> 本文件面向 AI 辅助开发场景，汇总架构、开发规范与当前开发计划。

---

## 1. 项目概况

Kotlin Multiplatform (KMP) 桌面应用，采用**本地 Ktor 服务 + 内嵌 WebView** 架构。

| 模块                                     | 技术                        | 职责                              |
|----------------------------------------|---------------------------|---------------------------------|
| `composeApp`                           | Compose Multiplatform     | 桌面窗口、WebView 宿主、JS Bridge       |
| `shared/commonMain`                    | KMP（纯 Kotlin）             | API 路由模型、DB 模型、Worker 引擎、工具函数   |
| `shared/jvmMain`                       | Ktor + Ktorm              | HTTP 服务启动、SQLite、JVM Executor   |
| `fredica-webui`                        | React 19 + React Router 7 | 前端界面（独立 Node 工程）                |
| `desktop_assets/common/fredica-pyutil` | Python FastAPI            | Python 辅助服务（随 composeApp 子进程启动） |

**端口规划：** `7630` React 开发服务器 · `7631` Ktor API · `7632` Python 服务（FastAPI）

**关键依赖版本：** Kotlin `2.3.0` · Compose MP `1.9.3` · Ktor `3.3.3` · Ktorm `4.1.1` · Kotlinx Coroutines `1.10.2` ·
React `19` · Tailwind CSS `4`

---

## 2. 目录结构速览

```
shared/src/
├── commonMain/kotlin/.../
│   ├── api/routes/          # 所有 API 路由（all_routes.kt 集中注册，~27 个文件）
│   ├── db/                  # Task / WorkflowRun + DB 实现
│   ├── llm/                 # LLM 多模型配置（LlmModelConfig、LlmCallPayload、LlmDefaultRoles）
│   ├── worker/              # WorkerEngine + TaskExecutor 接口
│   │   ├── TaskCancelService.kt       # 运行中任务取消信号注册表
│   │   └── TaskPauseResumeService.kt  # 暂停/恢复信号注册表
│   └── apputil/             # 工具函数（含 SseLineParser）
├── jvmMain/kotlin/.../
│   ├── api/FredicaApi.jvm.kt  # Ktor 启动、认证、DB/Worker 初始化
│   ├── llm/LlmSseClient.kt    # SSE 流式 LLM 客户端（Ktor + SseLineParser）
│   ├── python/PythonUtil.kt   # Python 服务 HTTP/WebSocket 客户端
│   └── worker/executors/      # JVM 专属 Executor（DownloadBilibiliVideoExecutor、TranscodeMp4Executor）
└── jvmTest/kotlin/            # 所有单元测试（SQLite 临时文件隔离）
commonTest/kotlin/             # 平台无关测试（JsonsTest）

fredica-webui/                          # React 前端
desktop_assets/common/fredica-pyutil/   # Python 服务源代码
└── fredica_pyutil_server/
    ├── routes/         # 自动扫描加载的 API 路由（bilibili_*, device, transcode, ping）
    ├── subprocess/     # 子进程封装（transcribe, transcode）
    └── util/           # TaskEndpoint 基类 + device_util + ffmpeg_util
```

---

## 3. 构建与测试命令

```shell
# 运行桌面应用（开发入口）
./gradlew :composeApp:run

# 构建 shared 模块（修改 kotlin 代码后必须执行）
./gradlew :shared:build

# 运行全部单元测试
./gradlew :shared:jvmTest

# 运行单个测试类
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.TaskDbTest"

# 前端开发（独立 Node 工程，在 fredica-webui/ 目录执行）
cd fredica-webui && npm install && npm run dev
```

**测试文件一览：**

| 文件（所在目录）                     | 覆盖范围                                                                 |
|----------------------------------|----------------------------------------------------------------------|
| `jvmTest/db/TaskDbTest.kt`          | CRUD、幂等键去重、claimNext 原子性、updateStatus、listByWorkflowRun |
| `jvmTest/db/WorkflowRunDbTest.kt`   | WorkflowRun 生命周期、recalculate() 状态联动（全完成/部分完成/有失败） |
| `jvmTest/worker/DagEngineTest.kt`    | DAG 调度：无依赖/阻塞/解锁/AND 语义/自环检测                                     |
| `jvmTest/worker/WorkerEngineTest.kt` | 正常流、重试、重试耗尽、优先级调度、并发限制                                         |
| `commonTest/apputil/JsonsTest.kt`    | JSON 序列化/反序列化工具函数（平台无关）                                         |
| `jvmTest/llm/LlmSseClientTest.kt`   | SSE 流式请求 + 取消信号（无测试令牌时自动跳过）                                     |

---

## 4. 工具函数速查

### Kotlin（`shared/src/commonMain/.../apputil/`）

| 文件                 | 主要 API                                                               |
|--------------------|----------------------------------------------------------------------|
| `jsons.kt`         | `encodeJson<T>()` · `decodeJson<T>()` · `buildValidJson { }` DSL     |
| `commonUtil.kt`    | `Result.wrap { }` · `wrapAsync { }` · `Double.toFixed(n)`            |
| `logger.kt`        | `createLogger("name")` → 调用 `logger.error(msg, throwable)` 需显式导入扩展函数 |
| `AppUtil.kt`       | `Paths`（应用路径）· `GlobalVars`（全局 HTTP 客户端）· 时间工具                       |
| `stringUtil.kt`    | `convertCase(str, CaseFormat.SNAKE_CASE)`                            |
| `SseLineParser.kt` | `SseLineParser.parseLine(line)` → 解析 SSE `data:` 行，返回 null/`[DONE]`/内容 |

### JVM 专属（`shared/src/jvmMain/.../`）

| 文件                     | 主要 API                                                                                                                                                         |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `python/PythonUtil.kt` | HTTP 请求: `Py314Embed.PyUtilServer.requestText(method, path, body?, timeoutMs?)`、`requestModel(...)` |
| `python/PythonUtil.kt` | WebSocket 长任务: `Py314Embed.PyUtilServer.websocketTask(path, paramJson, onProgress?, cancelSignal?, pauseChannel?, resumeChannel?)` → `String?`（取消时返回 null）|
| `llm/LlmSseClient.kt`  | `LlmSseClient.streamChat(modelConfig, requestBody, onChunk?, cancelSignal?)` → `String?`（取消时返回 null）；非流式自动降级 |

### 前端（`fredica-webui/app/`）

| 文件                      | 主要 API                                           |
|-------------------------|--------------------------------------------------|
| `util/app_fetch.ts`     | `useAppFetch`、`useImageProxyUrl`、`parseJsonBody`、`buildAuthHeaders`（导出，供 llm.ts 复用） |
| `context/appConfig.tsx` | 应用配置 Context（后端连接参数，持久化到 localStorage；WebView 环境通过 bridge `get_server_info` 初始化） |
| `util/utils.ts`         | `cn(...)` Tailwind 类名合并                          |
| `util/bridge.ts`        | `getBridge()` 获取 JsBridge（WebView 环境返回实例，浏览器返回 null） |
| `util/llm.ts`           | `llmChat(params: LlmChatParams): AsyncGenerator<string>`（direct/router/bridge 三模式 SSE 流式） |

---

## 5. 关键技术约定

### 测试隔离

- **SQLite 测试必须用临时文件**，不能用 `:memory:`（ktorm 连接池每次开新连接，内存库各连接独立）
- **WorkerEngine 测试**：`@AfterTest` 中取消所有通过 `activeScopes` 记录的 CoroutineScope，否则旧协程会抢占新测试的任务

### 常见陷阱

- `logger.error(msg, throwable)` 需显式导入 `import com.github.project_fredica.apputil.error`
- `TaskDb` 只操作 task 表，**不含 recalculate()**；`WorkflowRunDb` 管理 workflow_run 表，含 recalculate()
- `WorkerEngine` 任务完成后调用 `WorkflowRunService.repo.recalculate()`，**不是** `TaskService`

---

## 6. Phase 1 继续开发计划

> **当前状态（2026-03-06）**：Phase 1 核心已完成，正在进行架构升级——从 FFmpeg 子进程 Executor 迁移到 WebSocket 长任务框架。
> 以下为 Phase 1 里程碑达成前的剩余工作。

### 里程碑标准（Phase 1 Done）

> 所有 TDD 测试绿；本机端到端跑通基础流水线；WebUI 任务列表正确展示进度与状态。

---

### 6.1 已完成 ✅

| 类别            | 内容                                                                                                                                                                                                                                     |
|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 数据模型          | `task` / `workflow_run` 表 + 状态机（pending→claimed→running→completed/failed/cancelled）；`task` 含 `is_paused` 字段；`Task.workflowRunId` 关联 `WorkflowRun.id` |
| 引擎            | WorkerEngine（Semaphore + 协程轮询）+ DAG claimNext（depends_on）                                                                                                                                                                |
| 幂等            | `idempotency_key` INSERT OR IGNORE（task 级）                                                                                                                                                                 |
| 任务控制服务        | `TaskCancelService`（运行中任务取消信号注册表）+ `TaskPauseResumeService`（暂停/恢复 Channel 注册表）                                                                                                                                                       |
| Executor      | `DownloadBilibiliVideoExecutor`（WebSocket 下载，实时进度 0-100%，支持取消/暂停/恢复）；其余 executor 已从代码库移除，待用新框架重写                                                                                                                                     |
| API           | `GET /WorkerTaskListRoute`（分页+过滤）· `POST /TaskCancelRoute` · `POST /TaskPauseRoute` · `POST /TaskResumeRoute` · `GET /MaterialActiveTasksRoute` · `POST /MaterialDownloadStatusRoute` · `POST /MaterialRunTaskRoute` |
| API（已删除）      | ~~`POST /pipelines` · `GET /pipelines/{id}` · `GET /pipelines` · `DELETE /pipelines/{id}`~~（Pipeline 路由已删，由以上 Task 级路由替代）                                                                                                              |
| 路由注册          | `all_routes.kt` 集中注册全部 common 路由（按字母序），`FredicaApi.getAllRoutes()` 合并 common + native                                                                                                                                                  |
| WebUI         | 任务中心页（`tasks._index.tsx`，全局任务列表，支持暂停/恢复/取消，分页，状态/素材/分组过滤）+ 素材库页（`material-library._index.tsx`）+ `error_handler.ts` 统一错误上报                                                                                                          |
| WebUI（已删除）    | ~~`processing._index.tsx`~~（旧处理中心） · ~~`library._index.tsx`~~（旧素材库），已被新页面替代                                                                                                                                                            |
| TDD           | TaskDbTest · WorkflowRunDbTest · DagEngineTest · WorkerEngineTest（全绿）；JsonsTest 移至 commonTest |
| Python 服务架构   | `app.py` 改为自动扫描 `routes/` 目录；新增 `util/task_endpoint_util.py`（`TaskEndpoint` + `TaskEndpointInEventLoopThread` 基类，WebSocket 长任务协议：init_param_and_run / cancel / pause / resume / status）；`subprocess/transcribe.py` 子进程化 Whisper        |
| Python 路由     | `routes/bilibili_video.py`（GET /bilibili/video/get-pages + WS /bilibili/video/download-task）· `routes/bilibili_subtitle.py` · `routes/bilibili_favorite.py` · `routes/ping.py`                                                          |
| PythonUtil    | 新增 `PyUtilServer.websocketTask()` API（WebSocket 通道，支持 cancel/pause/resume 信号传递）                                                                                                                                                        |
| 素材导入 Pipeline | `MaterialImportRoute` 导入后自动创建 bilibili 任务流水线；`DownloadBilibiliVideoExecutor` 通过 WebSocket 下载                                                                                                                                          |
| 设备检测与 GPU 加速  | `util/device_util.py`（CUDA/ROCm/QSV/VideoToolbox/D3D11VA/VAAPI 检测）· `util/ffmpeg_util.py`（多路径搜索 + 能力探测 + `TranscodeCommandBuilder`）· `routes/device.py`（GET /device/info · POST /device/detect）· `routes/transcode.py`（WS /transcode/mp4-task）· `subprocess/transcode.py`（FFmpeg 子进程 + 进度解析） |
| AppConfig 扩展  | 新增 5 字段：`ffmpegPath` · `ffmpegHwAccel` · `ffmpegAutoDetect` · `deviceInfoJson` · `ffmpegProbeJson`；`AppConfigDb` 同步                                                                                                                    |
| 启动自动检测        | `FredicaApi.jvm.kt` 启动后等待 Python ping 通，异步调用 `/device/detect` 写入 AppConfig；若 `ffmpegPath` 为空且探测到有效路径则自动填充                                                                                                                             |
| JS Bridge     | `GetDeviceInfoJsMessageHandler` · `RunFfmpegDetectJsMessageHandler` 注册到 `AppWebViewMessages.all`；`MyJsMessageHandler.handle()` 改用 `CoroutineScope(Dispatchers.IO).launch`（修复 UI 线程阻塞）                                               |
| TranscodeMp4  | `TranscodeMp4Executor`（jvmMain）：WebSocket 转码 executor，读 `ffmpegProbeJson` 自动解析最优加速方案；`MaterialRunTaskRoute` 支持 TRANSCODE_MP4 payload 构建                                                                                               |
| 硬件加速设置页       | `app-desktop-setting.tsx` 新增：GPU 能力全量展示（支持彩色/不支持灰色）· FFmpeg 最优加速 GPU 着色 + 优先级说明 · FFmpeg 路径竖排单选列表（含 `all_paths`）                                                                                                                    |
| LLM 多模型配置     | `LlmModelConfig.kt`（commonMain）：`LlmProviderType` + `LlmCapability` 枚举 + `LlmModelConfig` + `LlmDefaultRoles` 数据类；`LlmCallPayload.kt`（commonMain）；`SseLineParser.kt`（commonMain）；`LlmSseClient.kt`（jvmMain）：流式 SSE + 取消 + 非流式降级 |
| AppConfig 扩展（LLM）| 新增 5 字段：`llmModelsJson` · `llmDefaultRolesJson` · `llmTestApiKey` · `llmTestBaseUrl` · `llmTestModel`；`AppConfigDb` 同步 |
| LLM API 路由      | `GET /LlmModelListRoute` · `POST /LlmModelSaveRoute`（按 id 幂等）· `POST /LlmModelDeleteRoute` · `GET /LlmDefaultRolesGetRoute` · `POST /LlmDefaultRolesSaveRoute`（5 个路由，已注册到 `all_routes.kt`） |
| LLM 设置页        | `app-desktop-setting.llm-model-config.tsx`：测试令牌区块（折叠）+ 默认角色下拉 + 模型列表 + 编辑弹窗（能力标签多选）；`app-desktop-setting.tsx` 新增入口卡片 |
| LLM JS Bridge  | `GetServerInfoJsMessageHandler`（WebView 获取服务器连接信息含 auth token，解决 router 模式 401）；`GetLlmModelsJsMessageHandler` · `SaveLlmModelJsMessageHandler` · `DeleteLlmModelJsMessageHandler` · `ReorderLlmModelsJsMessageHandler` · `GetLlmDefaultRolesJsMessageHandler` · `SaveLlmDefaultRolesJsMessageHandler` · `LlmProxyChatJsMessageHandler`；均注册到 `AppWebViewMessages.all` |
| LLM 前端工具       | `util/llm.ts`：`llmChat(params)` 为 `AsyncGenerator<string>`（三模式：direct/router/bridge）；`util/bridge.ts`：`getBridge()` 获取 JsBridge；`appConfig.tsx` 新增 bridge 初始化逻辑（`get_server_info`）；`LlmProxyChatRoute` 每行 SSE 后 `flush()` 保证实时推送 |

---

### 6.2 待完成

#### ① 剩余 Executor 重写（WebSocket 框架）

旧 executor 已删除，需用新 WebSocket 框架（`TaskEndpoint` + `websocketTask()`）重写：

- [ ] `ExtractAudioExecutor` — Python WS 端点 + Kotlin executor（ffmpeg 提取音轨）
- [ ] `SplitAudioExecutor` — Python WS 端点 + Kotlin executor（ffmpeg 切割音频）
- [ ] `TranscribeChunkExecutor` — Python WS 端点 + Kotlin executor（Whisper 语音识别）
- [ ] `MergeTranscriptionExecutor` — 纯 Kotlin，逻辑简单，直接重写
- [ ] `AiAnalyzeExecutor` — stub 实现，直接重写

#### ② 集成测试补充（TDD 要点）

**单元测试（已由现有测试覆盖，无需新增）：**

- ✅ DAG 依赖解析（DagEngineTest）
- ✅ 状态机迁移（TaskDbTest）
- ✅ 幂等键去重（TaskDbTest）
- ✅ Worker 并发上限（WorkerEngineTest）
- ✅ 优先级调度（WorkerEngineTest）

**尚缺的集成测试：**

- [ ] **取消传播测试**（补充到 TaskDbTest 或新建测试文件）
    - 调用 `TaskCancelService.cancel(taskId)` 后，断言 DB 状态变为 `cancelled`
    - 对 pending 任务直接更新 DB；对 running 任务通过 CompletableDeferred 信号通知

- [ ] **端到端冒烟测试**（`worker/E2eSmokeTest.kt`）
    - 使用 FakeExecutor 模拟全链路执行
    - 断言完整工作流运行实例最终全部 `completed`，WorkflowRun 状态联动变为 `completed`

---

### 6.3 开发顺序建议

```
① DownloadBilibiliVideoExecutor ✅（已完成，WebSocket 框架已验证）
    ↓
② 重写其余 Executor（ExtractAudio / SplitAudio / TranscribeChunk / Merge / AiAnalyze）
    ↓
③ 集成测试：取消传播 + 端到端冒烟（TDD 验证）
    ↓
④ 全套测试 ./gradlew :shared:jvmTest 全绿 → Phase 1 Done
```

---

## 7. 后续计划（Phase 1.5 + Phase 2+）

Phase 1.5：工作流系统基础层，详见 `docs/dev/plans/workflow-design.md`。

- **Phase 1.5**：WorkflowDefinition + WorkflowNodeRun + WorkflowEngine，React Flow 前端，接入素材处理业务

详见 `docs/dev/plans/decentralized-task-management.md`。

- **Phase 2**：`worker.yaml` 驱动能力系统（CapabilityFunction + 评分体系），多后端矩阵
- **Phase 3**：集群节点注册、mDNS 发现、分布式任务认领、死亡检测与回收
- **Phase 4**：云端 API 对接、双层鉴权、容错强化（重试分类、Cache Policy、磁盘预检）
- **Phase 5**：高级能力（TTS/OCR/目标检测/超分/字幕/声纹）+ 可观测性仪表盘
