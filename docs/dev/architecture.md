---
title: 项目架构
order: 2
---

# 项目架构

## 整体架构

Fredica 采用**本地服务 + 内嵌 WebView**的架构，将 Kotlin 后端服务与 React 前端结合在一个桌面应用中。Python 辅助服务随桌面应用一同启动，以子进程形式运行。

```mermaid
graph TB
    subgraph Desktop["桌面应用（composeApp）"]
        CUI[Compose UI 窗口]
        WV[AppWebView 内嵌浏览器]

        subgraph Backend["后端服务（shared/jvmMain）"]
            Ktor[Ktor Server :7631]
            Worker[WorkerEngine 任务调度]
            DB[(SQLite .data/db/)]
        end

        subgraph PyService["Python 辅助服务（fredica-pyutil）"]
            Py[FastAPI :7632]
        end
    end

    subgraph WebUI["前端（fredica-webui）"]
        React[React + React Router]
    end

    CUI --> WV
    WV <--> React
    React <-->|HTTP API| Ktor
    Ktor <--> DB
    Ktor --- Worker
    Worker <-->|内部 HTTP| Py
```

## 模块划分

### `composeApp` — 桌面宿主

- **技术**：Kotlin Compose Multiplatform
- **职责**：
  - 创建桌面窗口，托管 `AppWebView`
  - 通过 JS Bridge 向 WebView 注入应用配置（端口、Token 等）
  - 响应 WebView 发出的 Native 消息（如打开系统浏览器、保存配置）
  - 启动内嵌的 Ktor API 服务器
  - 以子进程方式启动 Python 辅助服务
- **支持平台**：JVM Desktop（Windows / macOS / Linux）、Android

**JS Bridge 消息类型：**

| 消息 Handler | 方向 | 说明 |
|-------------|------|------|
| `GetAppConfig` | Native → Web | Web 启动时获取服务器配置 |
| `SaveAppConfig` | Web → Native | 用户在设置页修改配置后保存 |
| `OpenBrowser` | Web → Native | 在系统默认浏览器中打开 URL |

### `shared` — 跨平台核心

- **技术**：Kotlin Multiplatform（commonMain + jvmMain + androidMain）
- **职责**：
  - 定义所有 API 路由的请求/响应数据模型（`commonMain`）
  - JVM 端实现 Ktor 服务器初始化与路由注册（`jvmMain`）
  - 数据库表结构与 Ktorm ORM 实体定义
  - 异步任务队列引擎（`WorkerEngine` + `TaskExecutor` 体系）
  - 跨平台工具函数

**目录结构：**
```
shared/src/
├── commonMain/kotlin/
│   ├── api/
│   │   ├── FredicaApi.kt          # API 入口与路由注册
│   │   └── routes/                # 各业务路由实现（21 个文件）
│   ├── db/                        # 数据模型 + Ktorm ORM 实体
│   ├── worker/                    # 任务引擎（平台无关）
│   │   ├── TaskExecutor.kt        # Executor 接口 + ExecuteResult
│   │   ├── WorkerEngine.kt        # Semaphore + 协程轮询引擎
│   │   └── executors/             # MergeTranscription / AiAnalyze
│   └── apputil/                   # 工具函数
├── jvmMain/kotlin/
│   ├── api/
│   │   └── FredicaApi.jvm.kt      # Ktor 启动、认证、DB 初始化
│   ├── python/
│   │   └── PythonUtil.kt          # Python 服务 HTTP 客户端
│   └── worker/executors/          # JVM 专属 Executor（FFmpeg / Whisper）
│       ├── DownloadVideoExecutor.kt
│       ├── ExtractAudioExecutor.kt
│       ├── SplitAudioExecutor.kt
│       └── TranscribeChunkExecutor.kt
└── jvmTest/kotlin/                # JVM 单元测试
    ├── db/                        # PipelineDbTest / TaskDbTest
    └── worker/                    # WorkerEngineTest / DagEngineTest
```

### `fredica-webui` — React 前端

- **技术**：React 19 + React Router 7（文件系统路由）+ Tailwind CSS 4
- **构建工具**：Vite
- **职责**：所有用户交互界面

**路由结构（文件系统路由）：**

```
app/routes/
├── _index.tsx                               # 服务器连接配置页
├── library._index.tsx                       # 素材库主页
├── processing._index.tsx                    # 处理中心（任务队列监控）
├── add-resource._index.tsx                  # 添加资源入口
├── add-resource.bilibili._index.tsx         # B 站导入导航
├── add-resource.bilibili.favorite.tsx       # 收藏夹 ID 输入
├── add-resource.bilibili.favorite.fid.$fid.tsx  # 收藏夹视频列表
├── add-resource.bilibili.collection.tsx     # 合集（UI 框架）
├── add-resource.bilibili.multi-part.tsx     # 多 P 视频（UI 框架）
├── add-resource.bilibili.uploader.tsx       # UP 主（UI 框架）
├── app-desktop-home.tsx                     # 桌面主页
├── app-desktop-setting.tsx                  # 桌面设置
└── app-user-setting.tsx                     # 用户设置
```

### `desktop_assets` — 桌面平台资产

嵌入到安装包中、随应用一起分发的平台资产，不通过 Gradle 构建。

```
desktop_assets/
├── common/
│   └── fredica-pyutil/            # Python 辅助服务源码
│       └── fredica_pyutil_server/
│           ├── app.py             # FastAPI 入口（B 站 API、转写路由）
│           └── transcribe.py      # faster-whisper 音频转写
├── windows/
│   └── lfs/                       # Windows LFS 大文件（含 Python 嵌入式运行时）
├── macos/
└── linux/
```

**Python 辅助服务说明：**
- 运行时（Python 3.14 嵌入式）和依赖（`faster-whisper` 等）均打包在安装包中，**无需用户手动安装 Python**
- 由 `composeApp` 在启动时以子进程方式启动，监听 `:7632`
- 目前仅 Windows 平台完整实现；macOS / Linux 尚待适配

**主要依赖（`requirements.txt`）：**
| 包 | 用途 |
|----|------|
| `fastapi` + `uvicorn` | HTTP 服务框架 |
| `bilibili-api-python` | B 站数据接口 |
| `faster-whisper` | 本地语音转写（Whisper 加速版）|

### `docs` — VitePress 文档站

- **技术**：VitePress + vitepress-plugin-mermaid
- **构建命令**：根目录 `npm run docs:build`（对应根 `package.json`）
- **职责**：项目技术文档，与 `fredica-webui` 前端工程**相互独立**

### `server` — 独立服务器（实验性）

- **技术**：Ktor（独立可执行 JAR）
- **当前状态**：仅有 `/` 路由，返回 `"Ktor"`
- **用途**：作为未来独立部署模式的起点，当前无实际功能

## 数据库设计

```mermaid
erDiagram
    material_video {
        TEXT id PK
        TEXT source_type
        TEXT source_id
        TEXT title
        TEXT cover_url
        TEXT description
        INTEGER duration
        TEXT pipeline_status
        TEXT local_video_path
        TEXT local_audio_path
        TEXT transcript_path
        TEXT extra
        INTEGER created_at
        INTEGER updated_at
    }

    material_category {
        TEXT id PK
        TEXT name
        TEXT description
        INTEGER created_at
        INTEGER updated_at
    }

    material_video_category {
        TEXT video_id FK
        TEXT category_id FK
    }

    app_config {
        TEXT key PK
        TEXT value
    }

    pipeline_instance {
        TEXT id PK
        TEXT status
        INTEGER created_at
        INTEGER updated_at
    }

    task {
        TEXT id PK
        TEXT pipeline_id FK
        TEXT type
        TEXT status
        INTEGER priority
        TEXT depends_on
        TEXT idempotency_key
        TEXT claimed_by
        INTEGER claimed_at
        INTEGER created_at
    }

    task_event {
        TEXT id PK
        TEXT task_id FK
        TEXT event_type
        TEXT data
        INTEGER created_at
    }

    material_video ||--o{ material_video_category : "has"
    material_category ||--o{ material_video_category : "has"
    pipeline_instance ||--o{ task : "contains"
    task ||--o{ task_event : "emits"
```

## 服务端口规划

| 端口 | 服务 | 状态 |
|------|------|------|
| `7630` | React WebUI 开发服务器（Vite） | ✅ 可用 |
| `7631` | Ktor API 服务器 | ✅ 可用 |
| `7632` | Python 辅助服务（FastAPI） | ✅ 可用（仅 Windows）|

## 认证机制

所有需要认证的 API 都通过 `Authorization: Bearer <token>` 头验证。Token 生成和管理在桌面应用设置中处理。

认证逻辑位于 `FredicaApi.jvm.kt` 的 `checkAuth()` 函数（返回 `Boolean`，验证失败时直接响应 401）。

## 图片代理与缓存

所有来自外部平台（如 B 站 CDN）的封面图都通过 Fredica 内置的图片代理服务访问：

1. 前端请求 `/api/v1/ImageProxyRoute?url={encodedUrl}`
2. 代理服务检查本地缓存（`.data/cache/images/`），缓存键为 URL 的 SHA-256
3. 缓存命中直接返回；未命中则从外部下载并写入缓存
4. 响应头携带 `Cache-Control: public, max-age=31536000, immutable`

> 图片代理接口无需认证，方便在 `<img>` 标签中直接使用。
