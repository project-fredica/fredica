---
title: 视频预览组件设计方案
order: 520
---

# 视频预览组件设计方案

> 本文档讨论通用"视频预览组件"的架构设计，涵盖 Kotlin 视频流 API、MP4 文件策略、前端三种使用模式、转码状态机、跨实例协调机制，以及与字幕时间轴的联动。

---

## 1. 需求全景

| 维度 | 说明 |
|------|------|
| **使用场景** | 素材工作区各子页面（概览、字幕、声纹、帧分析）均可打开视频预览 |
| **字幕联动** | subtitle-bilibili 点击字幕行 → 跳转视频进度 |
| **转码前置** | 若 MP4 尚未转码，先走转码流程再播放 |
| **生命周期** | 三种模式：inline 标签、全局悬浮、独立新标签 |
| **并发协调** | 多模式可能导致多播放器并存，需自动管理播放/暂停 |

---

## 2. Kotlin 端：视频资源 API

### 2.1 新增 Route 一览

需新增两个 Route，在 `all_routes.kt` 注册：

```
MaterialVideoCheckRoute    GET  /api/v1/MaterialVideoCheckRoute?material_id=xxx
MaterialVideoStreamRoute   GET  /api/v1/MaterialVideoStreamRoute?material_id=xxx
```

### 2.2 `MaterialVideoCheckRoute`

**目的**：前端轮询用，检查 MP4 是否已就绪，避免直接请求流导致的 404 状态难以区分。

**响应 JSON**：
```json
{
  "ready": true,
  "file_size": 1234567,
  "file_mtime": 1710000000000
}
```

**实现逻辑**（`shared/commonMain/api/routes/`）：
```kotlin
val dir = AppUtil.Paths.materialMediaDir(materialId)
val mp4 = dir.resolve("video.mp4")
val done = dir.resolve("transcode.done")
val ready = mp4.exists() && done.exists()
buildValidJson {
    kv("ready", ready)
    if (ready) {
        kv("file_size", mp4.length())
        kv("file_mtime", mp4.lastModified())
    }
}
```

> `transcode.done` 由 Python FFmpeg 转码完成后写入。双重校验：文件存在 + done 标记，防止转码中途返回不完整文件。

### 2.3 `MaterialVideoStreamRoute`

**目的**：向 `<video src>` 提供本地 MP4 字节流，**必须支持 HTTP Range 请求**，否则浏览器无法 seek。

**实现位置**：jvmMain 专属路由，在 `FredicaApi.jvm.kt` 的 `getNativeRoutes()` 中注册（同 `TorchInstallCheckRoute`）。

```kotlin
// jvmMain 专属
call.respondFile(mp4File)
// Ktor respondFile 已内置 Range 支持（206 Partial Content）
```

**鉴权方式：Cookie**：
- `<video src>` 标签发出的 HTTP 请求**自动携带同源 Cookie**，但无法携带自定义 Authorization header。
- 因此本路由采用 **Cookie 鉴权**，而非 URL query param（token 出现在 URL 中会随 token 变更导致缓存 key 变化，破坏跨 session 缓存）。
- Ktor 端：从 `Cookie` 请求头中读取 `fredica_token`，与其他路由的 Bearer token 共享同一验证逻辑。
- 前端端：app 启动时（`get_server_info` bridge 返回 token 后）执行一次：
  ```ts
  // appConfig.tsx 或 bridge 初始化处
  document.cookie = `fredica_token=${token}; path=/; SameSite=Strict`;
  ```
- `<video src>` URL 保持简洁，不含 token：
  ```
  /api/v1/MaterialVideoStreamRoute?material_id=xxx
  ```
  Cache key 仅含 `material_id`，**跨 session 缓存完全有效**。
- Ktor 正常监听外部接口（与其他路由一致）。
- 文件不存在时返回 404（JSON body：`{"error": "MP4_NOT_FOUND"}`）。

---

## 3. MP4 文件 ID 策略与路由策略

### 3.1 核心矛盾

| 方案 | 优点 | 缺点 |
|------|------|------|
| 基于 materialId（路径固定） | 简单，路径可预测 | 重新转码后浏览器可能缓存旧版本 |
| 基于内容哈希 | 缓存永久有效 | 数百 MB 视频计算哈希代价过高 |
| materialId + `?v=mtime` + 重定向 | 链接可分享、可缓存 | Range 请求需多一次 302 往返 |
| `Last-Modified` / `ETag` 条件请求 | 最标准、链接无版本号 | 每次 session 开始需一次验证往返 |

### 3.2 推荐方案：`Last-Modified` + `ETag`，无版本号参数

**路由 URL**：`/api/v1/MaterialVideoStreamRoute?material_id=<id>`（无版本号参数，无 token；鉴权走 Cookie）

**HTTP 缓存策略**：

```
服务端响应头：
  Cache-Control: no-cache          ← "每次先验证"，不是"不缓存"
  Last-Modified: <mp4 mtime RFC>
  ETag: "<mtime_hex>"
  Accept-Ranges: bytes
  Content-Type: video/mp4

浏览器条件请求（已有缓存时）：
  If-Modified-Since: <上次 mtime>
  If-None-Match: "<上次 etag>"
  → 未变更 → 304（无 body，极快）→ 继续用缓存
  → 已变更 → 200（新文件体）
```

**工作流程**：
1. 用户打开播放器 → `MaterialVideoCheckRoute` 返回 `file_mtime`
2. 前端渲染 `<video key={file_mtime} src="...?material_id=xxx&token=...">`
3. `key` 绑定 `file_mtime`，重新转码后 key 变化 → React 重建 DOM → 浏览器发新的条件请求
4. 服务端 mtime 已更新 → 200（新文件）；未更新 → 304（复用缓存）

**为什么不用 `?v=mtime` + 重定向**：

`?v=wrong → 302 → ?v=correct` 在概念上没有问题（302 不破坏缓存，视频字节缓存在 versioned URL），但 `<video>` 在 seek 时会发出大量 Range 请求，每个 Range 请求都要先走 302（额外往返），即使是 localhost 也增加不必要的复杂度。`Last-Modified` 方案下，Range 请求在同一 session 内复用同一缓存条目，只有 session 首次请求需要一次验证往返，远更高效。

### 3.3 MP4 存储路径约定（已有约定，维持不变）

```
{appDataDir}/media/{materialId}/
├── video.m4s        # 下载原始文件（Bilibili DASH）
├── audio.m4s
├── video.mp4        # 转码输出（唯一目标）
├── transcode.done   # 转码完成标记（Python 写入）
└── download_m4s.done
```

---

## 4. 前端组件：转码状态机

`MaterialVideoPlayer` 组件在渲染前需走完状态机：

```
idle
  └─[挂载/materialId变更]→ checking
       ├─[ready=true]→ ready（渲染 <video>）
       └─[ready=false]→ needs_transcode
              ├─[用户点击"开始转码"]→ transcoding（启动 TRANSCODE_MP4 任务）
              │         └─[WorkflowInfoPanel 检测全部完成]→ checking（重新轮询）
              └─[TASK_ALREADY_ACTIVE]→ transcoding（已有任务，直接显示 WorkflowInfoPanel）
```

### 4.1 UI 状态对应渲染

```
checking         → 骨架屏 + Loader
needs_transcode  → TabBar: [▶ 视频预览（disabled）] [⚡ 转码]
                   "转码"标签：说明文字 + "开始转码 MP4"按钮
transcoding      → TabBar: [▶ 视频预览（disabled）] [⚡ 转码进度]
                   "转码进度"标签：WorkflowInfoPanel（workflowRunId）
                   + 每 3s 轮询 MaterialVideoCheckRoute，ready 后进入 ready 状态
ready            → TabBar: [▶ 视频预览（active）] [✓ 已转码（灰色）]
                   "视频预览"标签：<video key={mtime} src="..." controls />
```

### 4.2 检测 TASK_ALREADY_ACTIVE

调用 `MaterialVideoTranscodeMp4Route` 时若返回 `TASK_ALREADY_ACTIVE`，通过 `WorkerTaskListRoute?material_id=xxx` 找出最近一个 `TRANSCODE_MP4` 的 `workflow_run_id`，直接进入 `transcoding` 状态显示 WorkflowInfoPanel。

---

## 5. 三种使用模式

### 模式 A：Inline 标签页模式（嵌入子页面）

适用场景：概览页、帧分析页内嵌视频，与内容并排显示。

```tsx
<MaterialVideoPlayer materialId={material.id} mode="inline" />
```

- 生命周期随子路由，路由离开时销毁
- 同一主窗口内可能同时存在多个 inline 播放器（如概览页与帧分析页并排渲染时）；多个 inline 之间通过 BroadcastChannel 的 `instanceId` 机制调度——某 inline 开始播放 → 广播 `playing(instanceId=自身ID)` → 同 channel 内其他 inline 收到消息，`instanceId` 不匹配 → 若在播 → 强制暂停；与跨标签页协调逻辑完全一致

### 模式 B：App 级单例悬浮播放器（Global Floating Singleton）

适用场景：全局持久播放；字幕/声纹联动跳转时视频不因路由跳转消失；跨素材持续播放。

**实现位置**：挂在 `SidebarLayout`（App 级根布局），**全应用唯一实例，贯穿整个 App 生命周期**，与当前路由无关。

**不同于旧设计的关键点**：
- 不绑定任何素材路由，可以播放任意 materialId
- materialId 由外部调用方显式传入（见 `FloatingPlayerCtx`），而非从 URL 读取
- materialId 切换时动态离开旧 channel、加入新 channel

**全局 Context（挂在 SidebarLayout 或 root.tsx）**：
```tsx
// app/context/floatingPlayer.tsx
export interface FloatingPlayerCtx {
    openFloatingPlayer: (materialId: string, seekTo?: number) => void;
    closeFloatingPlayer: () => void;
    currentMaterialId: string | null;
    isVisible: boolean;   // 是否已有素材被加载（UNLOADED 时 false）
}
```

`MaterialWorkspaceCtx` 中**移除** `openVideoPlayer` / `seekVideoTo` / `floatingPlayerMounted`，字幕等子页面改用 `useFloatingPlayerCtx()` 直接操作 Mode B。

**UI 形态**（三态，支持拖拽）：

```
HIDDEN     → 应用启动后、尚未加载任何素材时不可见
MINIMIZED  → 右下角悬浮胶囊：[封面缩略图] [素材标题（截断）] [▶/⏸] [展开↑]
OPEN       → 右下角展开卡片（w-80）：完整视频播放器 + 控制栏 + 素材标题
```

- 首次调用 `openFloatingPlayer()` 时从 HIDDEN → MINIMIZED/OPEN
- 用户可手动折叠/展开（MINIMIZED ↔ OPEN）
- 用户点击 ✕ → HIDDEN（清空 materialId，离开 channel）
- 拖拽时临时 `z-60`，常态 `z-50`

### 模式 C：独立新标签页

适用场景：用户希望分屏（视频 + 字幕对照阅读）。

```ts
// app/util/bridge.ts 扩展：openInternalUrl
// 类比 openExternalUrl，但打开内部路由而非外部链接
export function openInternalUrl(path: string) {
    if (typeof window === "undefined") return;
    if (window.kmpJsBridge) {
        // 约定 Kotlin 端实现 "open_internal_tab" bridge 方法
        // 在新的 WebView Tab/窗口中打开内部路径（Ktor 本地地址 + 路径）
        callBridge("open_internal_tab", JSON.stringify({ path })).catch(() => {});
    } else {
        // 浏览器开发环境：新标签打开
        window.open(path, "_blank", "noopener,noreferrer");
    }
}

// 使用方
openInternalUrl(`/material/${materialId}/video-standalone`);
```

**需要 Kotlin 端新增 Bridge 方法 `open_internal_tab`**：在新 WebView 窗口/Tab 中打开 `<本地地址>:<端口><path>`。

新增路由 `material.$materialId.video-standalone.tsx`：全屏播放器（无 SubNav，含素材标题和基础信息）。

---

## 6. 跨实例协调：BroadcastChannel

BroadcastChannel（channel 名：`fredica-video-player-{materialId}`）负责协调所有播放器实例，防止多路音频同时播出。

**架构前提**（影响全部设计决策）：

| 约束 | 说明 |
|------|------|
| Mode B 由 Context 直接控制 | 字幕行点击 → `floatingCtx.openFloatingPlayer(materialId, seekTo)` 直接驱动 Mode B，不经过 channel；channel 的 `seek-and-play` 消息**只服务 Mode C** |
| Mode A 不响应 seek 命令 | Mode A 是纯内嵌查看器，外部无法通过 channel 驱动它 seek；它只受本地用户操作控制 |
| `seek-and-play` 有 tabId 过滤 | 防止 Mode C 的字幕行点击误控制主窗口的 Mode A/B |
| channel 按 materialId 隔离 | 不同素材的实例天然不互相干扰 |
| Mode B materialId 可动态切换 | 切换时离开旧 channel，加入新 channel |

---

### 6.0 实例标识与消息过滤规则

每条 channel 消息携带两个 ID，接收方据此判断是否响应：

| 标识符 | 生成时机 | 存储位置 | 作用 |
|--------|---------|---------|------|
| `instanceId` | 每个播放器组件挂载时生成的 UUID | React ref（组件内存，unmount 后消失） | 区分"谁发的消息"。BroadcastChannel 本身不回发给发送方，但显式比对可防止边界 bug |
| `tabId` | 每个浏览器标签页首次加载时生成的 UUID | `sessionStorage['fredica-tab-id']`（标签页内持久，页面刷新后重新生成） | 用于 `seek-and-play`/`seek-passive` 的目标定向：只有 `tabId` 匹配的独立标签播放器才响应 |

**消息过滤规则**：

| 消息类型 | 携带字段 | 接收方响应条件 |
|---------|---------|--------------|
| `playing` | `instanceId`、`tabId`、`currentTime` | `instanceId` ≠ 自身 → 若正在播放则强制暂停，广播 `paused` |
| `paused` / `destroyed` | `instanceId`、`tabId` | `instanceId` ≠ 自身 → 更新对等方记录 |
| `seek-and-play` | `instanceId`、`tabId`、`seconds`、`seekId` | `tabId` == 自身 `tabId` **且** 自身为独立标签播放器（Mode C） |
| `seek-passive` | `instanceId`、`tabId`、`seconds` | 同上 |
| `status-request` | `requestId` | 所有收到该消息的实例均回复 `status-reply` |
| `status-reply` | `requestId`、`instanceId`、`state`、`currentTime` | 仅等待对应 `requestId` 的发起方处理 |
| `time-sync` | `instanceId`、`currentTime` | `instanceId` ≠ 自身 → 用于其他标签页的进度条 UI 同步（可选） |

**materialId 隔离说明**：channel 名为 `fredica-video-player-{materialId}`，不同素材的播放器处于完全独立的 channel，彼此不会收到对方的任何消息。若两个标签页分别播放**不同素材**，则两套播放器互不感知，允许同时出声（与 R2 一致）。若需跨素材禁止双音频（如"全局同一时刻只能有一路音频"），需在 App 层额外跟踪所有活跃 channel，当前设计不作此处理。

---

### 6.1 消息格式

```ts
// app/util/videoPlayerChannel.ts

// 每个浏览器 Tab 启动时生成，存入 sessionStorage
// const TAB_ID = sessionStorage.getItem('fredica-tab-id') ?? crypto.randomUUID()

export type VideoPlayerState = 'playing' | 'paused' | 'not-ready';

export type VideoPlayerMessage =
  // ── 状态广播（全体，无 tabId 过滤）────────────────────────────────────
  | {
      type: 'playing';
      materialId: string;
      instanceId: string;   // 发送者实例 ID（UUID，组件挂载时生成）
      tabId: string;        // 发送者 Tab ID
      currentTime: number;
    }
    // 含义：我开始播放。其他相同 materialId 实例应暂停（无论哪个 Tab）。

  | {
      type: 'paused';
      materialId: string;
      instanceId: string;
      tabId: string;
    }
    // 含义：我暂停了。纯信息广播，其他实例无需响应（不触发状态变更）。

  | {
      type: 'destroyed';
      materialId: string;
      instanceId: string;
      tabId: string;
    }
    // 含义：我 unmount 了。接收方从已知 peer 列表中移除该实例。

  // ── 存活探测（全体）────────────────────────────────────────────────
  | {
      type: 'status-request';
      materialId: string;
      requestId: string;    // UUID，用于匹配 status-reply
    }
    // 含义：新实例挂载，询问当前 channel 内是否有实例在播放。

  | {
      type: 'status-reply';
      materialId: string;
      instanceId: string;
      tabId: string;
      requestId: string;
      state: VideoPlayerState;
      currentTime: number;
    }
    // 含义：对 status-request 的回应。

  // ── seek 命令（仅 Mode C 内部使用，有 tabId 过滤）────────────────────
  | {
      type: 'seek-and-play';
      materialId: string;
      seconds: number;
      seekId: string;       // UUID，用于去重（快速多次点击）
      tabId: string;        // 只有 tabId 匹配的实例响应
    }
    // 发送方：Mode C 的字幕面板。
    // 响应方：仅 Mode C 播放器（tabId 匹配且不是 Mode A）。

  | {
      type: 'seek-passive';
      materialId: string;
      seconds: number;
      tabId: string;        // 同上，tabId 过滤
    }
    // 发送方：Mode C 的字幕面板（hover 预览帧）。
    // 响应方：仅 Mode C 播放器。仅 seek，不改变播放状态。

  // ── 进度同步（可选，低频）────────────────────────────────────────────
  | {
      type: 'time-sync';
      materialId: string;
      instanceId: string;
      tabId: string;
      currentTime: number;  // PLAYING 状态下每秒广播一次，供其他 Tab 同步进度条显示
    }
```

---

### 6.2 操作行为矩阵

> **角色说明**（同一素材的实例才处于同一 channel，不同素材天然隔离）：
>
> | 角色 | 说明 | 所在标签页 |
> |------|------|-----------|
> | **inline 播放器** | 嵌入子页面（Mode A） | 主窗口标签页；多个主窗口标签页可各自存在一个 |
> | **悬浮播放器** | App 级单例悬浮（Mode B） | 主窗口标签页；每个主窗口标签页各有一个单例 |
> | **独立标签播放器** | 通过 `open_internal_tab` 打开（Mode C） | 独立标签页；可存在多个 |
>
> **操作发生点** = 用户实际操作的那个实例。
> **channel 消息接收方** = 同一素材 channel 内所有其他实例（跨标签页均可收到）。

#### 6.2.1 播放 / 暂停操作

| # | 操作发生点 | 该实例的行为 | 同素材的其他 inline 播放器（同主窗口或其他主窗口） | 同素材的悬浮播放器 | 同素材的独立标签播放器 |
|---|-----------|-------------|--------------------------------------------------|-------------------|----------------------|
| P1 | **inline 播放器** 用户点击 ▶ | 开始播放；广播 `playing(instanceId=自身ID)` | 收到 `playing`，`instanceId` ≠ 自身 → 若在播 → 强制暂停，广播 `paused`（无论在同一主窗口还是其他主窗口标签页，BroadcastChannel 均可送达） | 若正在播 → 收到 `playing` → 强制暂停，广播 `paused` | 若正在播 → 收到 `playing` → 强制暂停 |
| P2 | **悬浮播放器** 用户点击 ▶ | 开始播放；广播 `playing` | 若正在播 → 强制暂停 | 同一主窗口内不存在第二个悬浮播放器（单例）；其他主窗口标签页若有同素材悬浮播放器且在播 → 强制暂停 | 若正在播 → 强制暂停 |
| P3 | **独立标签播放器** 用户点击 ▶ | 开始播放；广播 `playing` | 若正在播 → 强制暂停 | 若正在播 → 强制暂停 | 其他同素材独立标签播放器若在播 → 强制暂停 |
| P4 | **任意播放器** 用户点击 ⏸ | 暂停；广播 `paused` | 收到 `paused`：纯信息，无动作 | 同上 | 同上 |
| P5 | **任意播放器** 视频自然播放结束 | 暂停；广播 `paused` | 同 P4 | 同 P4 | 同 P4 |

> **防环说明**：收到 `playing` 被强制暂停后，也要广播 `paused`，以便三方及以上实例都感知到状态变化。`paused` 是纯信息消息，没有实例会因收到 `paused` 而改变自身状态，不产生循环。

#### 6.2.2 字幕行点击（seek + 播放）

| # | 操作发生点 | 操作发生点的行为 | inline 播放器（同素材） | 悬浮播放器（同素材） | 独立标签播放器（同素材） |
|---|-----------|----------------|----------------------|-------------------|----------------------|
| S1 | **主窗口字幕面板** 点击字幕行 | 调用 `floatingCtx.openFloatingPlayer(素材ID, 目标秒数)` | 收到悬浮播放器随后广播的 `playing` → 若在播 → 强制暂停 | React Context 直接同步驱动（无 channel 消息）：seek 到目标时间 → 开始播放 → 广播 `playing` | 收到 `playing` → 若在播 → 强制暂停 |
| S2 | **独立标签字幕面板** 点击字幕行 | 广播 `seek-and-play(目标秒数, seekId, 本标签页ID)` | 收到 `seek-and-play`：`tabId` 不匹配 → 忽略；随后收到独立标签播放器广播的 `playing` → 若在播 → 强制暂停 | 收到 `seek-and-play`：`tabId` 不匹配 → 忽略；随后收到 `playing` → 若在播 → 强制暂停 | `tabId` 匹配：seek 到目标时间 → 开始播放 → 广播 `playing` |
| S3 | **主窗口字幕面板** 快速连续点击多行 | 多次调用 `openFloatingPlayer`，目标秒数各不同 | 不受影响 | Context 中 `pendingSeek` 以最后一次调用的值覆盖，只 seek 一次到最终值 | 不受影响 |
| S4 | **独立标签字幕面板** 快速连续点击多行 | 广播多条 `seek-and-play`，各携带不同 `seekId` | `tabId` 不匹配，全部忽略 | `tabId` 不匹配，全部忽略 | 维护最近 20 个 `seekId` 的环形缓冲；已见过的 → 忽略；最新一条 seek 最终生效 |
| S5 | **主窗口字幕面板** 点击字幕行，但悬浮播放器**视频尚未就绪**（未转码 / 转码中 / 检查中） | 调用 `openFloatingPlayer(素材ID, 目标秒数)` | 不受影响 | 变为可见（折叠或展开）；目标秒数存入 `pendingSeek{autoPlay: true}`；转码完成进入 PAUSED → 读取 pendingSeek → seek → 开始播放 → 广播 `playing` | 不受影响 |
| S6 | **主窗口字幕面板** 点击字幕行，但悬浮播放器**正在播放另一个素材 Y**（Y ≠ 当前字幕页素材 X） | 调用 `openFloatingPlayer(素材X的ID, 目标秒数)` | 不受影响（处于素材X的 channel，悬浮播放器仍未加入） | 在素材Y的 channel 广播 `destroyed` → 离开素材Y channel，加入素材X channel，广播 `status-request` → seek 到目标时间 → 开始播放 → 广播 `playing` | 素材Y的独立标签播放器：收到 `destroyed` → 移除悬浮播放器记录。素材X的独立标签播放器（若存在）：收到 `playing` → 若在播 → 强制暂停 |

#### 6.2.3 实例生命周期

| # | 事件 | 操作发生点的行为 | inline 播放器（同素材） | 悬浮播放器（同素材） | 独立标签播放器（同素材） |
|---|------|----------------|----------------------|-------------------|----------------------|
| L1 | **inline 播放器挂载**（进入含内嵌播放器的子页面） | 广播 `status-request`；200ms 内收到任意 `status-reply(playing)` → 自身初始化为 PAUSED；否则按默认逻辑初始化 | —（自身） | 若在播 → 回复 `status-reply(playing, 当前时间)` | 若在播 → 回复 `status-reply(playing, 当前时间)` |
| L2 | **inline 播放器卸载**（用户离开子页面） | 广播 `destroyed` | —（自身） | 从对等方列表移除该 inline 播放器，继续当前状态 | 从对等方列表移除该 inline 播放器，继续当前状态 |
| L3 | **悬浮播放器切换素材**（从素材A 切换到素材B） | 在素材A 的 channel 广播 `destroyed`；加入素材B 的 channel，广播 `status-request` | 素材A 的 inline：移除悬浮播放器记录，继续当前状态。素材B 的 inline（若存在）：若在播 → 回复 `status-reply(playing)` → 悬浮播放器据此初始化为 PAUSED | —（自身） | 素材A 的独立标签：移除悬浮播放器记录。素材B 的独立标签（若存在）：若在播 → 回复 `status-reply(playing)` |
| L4 | **悬浮播放器关闭**（用户点击 ✕） | 广播 `destroyed`，自身进入 HIDDEN / UNLOADED | 从对等方列表移除悬浮播放器，继续当前状态 | —（自身） | 从对等方列表移除悬浮播放器，继续当前状态 |
| L5 | **独立标签页关闭** | 广播 `destroyed` | 从对等方列表移除该独立标签播放器，继续当前状态 | 同左 | —（自身） |
| L6 | **独立标签播放器挂载**（新独立标签页打开） | 广播 `status-request`；200ms 内收到任意 `status-reply(playing)` → 自身初始化为 PAUSED；否则按默认逻辑初始化 | 若在播 → 回复 `status-reply(playing, 当前时间)` | 若在播 → 回复 `status-reply(playing, 当前时间)` | —（自身） |

#### 6.2.4 竞争与边界条件

| # | 场景 | 结果 | 说明 |
|---|------|------|------|
| R1 | 悬浮播放器和独立标签播放器在毫秒内几乎同时点击 ▶ | 两者均最终停止播放 | 各自收到对方广播的 `playing` → 各自强制暂停并广播 `paused`。用户再点一次即可恢复。单用户桌面场景中此竞争极罕见，接受此行为。 |
| R2 | 同一主窗口中，inline 播放器播放素材A，悬浮播放器播放素材B（A≠B） | 两者互不干扰，可同时输出音频 | 不同素材对应不同 channel，天然隔离。若未来需要禁止双声道，可在全局层面监听所有活跃 channel，但当前不作此处理。 |
| R3 | 悬浮播放器处于折叠（胶囊）状态时，独立标签播放器点击 ▶ | 悬浮播放器收到 `playing` → 强制暂停；UI 保持折叠，仅停止音频 | 折叠/展开是纯 UI 显示状态，不影响 channel 逻辑 |
| R4 | 独立标签页刷新 | 旧播放器实例广播 `destroyed`；刷新完成后新实例广播 `status-request` | 等同于 unmount + mount，由生命周期事件正常处理 |
| R5 | 用户通过 `open_internal_tab` 打开同一素材的两个独立标签页 | 其中一个播放 → 另一个被强制暂停；悬浮播放器也被强制暂停 | 符合预期：同一素材的所有实例中最多同时一个在播放 |
| R6 | 同一主窗口标签页内同时存在多个 inline 播放器（同素材，如概览页与帧分析页并排渲染） | 某 inline 开始播放 → 广播 `playing(instanceId=发起方ID)` → 同主窗口内其他 inline 收到消息，`instanceId` 不匹配 → 若在播 → 强制暂停 | 正常场景，调度机制与跨标签页完全一致，均依赖 `instanceId` 过滤，无需特殊处理 |
| R7 | 用户开了两个完整主窗口标签页（Tab1/Tab2），均导航到**同一素材**页面（各有 inline 播放器和悬浮播放器，共 4 个实例同处一个 channel） | Tab1 任意播放器点击 ▶ → 广播 `playing(instanceId=发送方自身ID)` → 其余 3 个实例各自检查 `instanceId` ≠ 自身 → 若在播则强制暂停（过滤规则见 §6.0） | BroadcastChannel 跨标签页生效，是防双音频的核心机制。**若两个标签页打开的是不同素材**，则处于不同 channel，互不感知，允许同时播放（见 §6.0 materialId 隔离说明及 R2）。 |

### 6.3 单实例播放状态机

每个 `MaterialVideoPlayer` 实例独立维护以下状态机。

> **模式差异说明**：
> - **Mode A**：不订阅 `seek-and-play` / `seek-passive`（handler 不注册），仅响应 `playing`（强制暂停）
> - **Mode B**：seek 命令由 `FloatingPlayerCtx.openFloatingPlayer(M, s)` 直接驱动，不经 channel；仅响应 `playing` / `paused` / `destroyed`
> - **Mode C**：订阅所有消息，`seek-and-play` / `seek-passive` 须 tabId 匹配才响应

#### 状态定义

| 状态 | 含义 |
|------|------|
| `CHECKING` | 正在查询 `MaterialVideoCheckRoute`，视频是否就绪 |
| `NEEDS_ENCODE` | 视频未就绪，展示"开始转码"按钮 |
| `ENCODING` | 转码任务进行中，展示 `WorkflowInfoPanel` |
| `PAUSED` | 视频就绪，暂停中 |
| `PLAYING` | 视频就绪，播放中 |

（不单独建模 `SEEKING`：scrub 是 `<video>` 元素内部的瞬态，不影响 channel 逻辑状态。）

#### 状态转移图

```
              ┌──────────────────────────────────────────────────┐
              │ 任意状态 → unmount → broadcast(destroyed) → 终止  │
              └──────────────────────────────────────────────────┘

  [mount]
     │
     ▼
 CHECKING ──check ok──────────────────────────────────────► PAUSED
     │                                                          │
     │  check fail                              apply pendingSeek if any:
     ▼                                          ├─ autoPlay=true → PLAYING
 NEEDS_ENCODE ──用户点击"开始转码"──► ENCODING        └─ autoPlay=false → 保持PAUSED
                                         │
                                         │ workflow 完成（重新检查）
                                         ▼
                                      CHECKING

  PAUSED ◄──────────────────────────── PLAYING
    │   LOCAL:pause / LOCAL:ended / CH:playing(other)   │
    │                                                    │
    │  LOCAL:play                                        │  CH:playing(other)
    │  CH:seek-and-play(s,id) [seek first]               │  → 强制暂停，broadcast(paused)
    ▼                                                    │
  PLAYING ─────────────────────────────────────────────►┘

  PAUSED ──CH:seek-passive(s)──► seek video, 留在 PAUSED
  PLAYING ──CH:seek-passive(s)──► seek video, 留在 PLAYING
```

#### 完整转移表

| 当前状态 | 事件 | 动作 | 新状态 |
|---------|------|------|--------|
| `CHECKING` | check ok | apply pendingSeek（如有） | `PAUSED` 或 `PLAYING` |
| `CHECKING` | check fail | — | `NEEDS_ENCODE` |
| `NEEDS_ENCODE` | LOCAL: 开始转码 | 调用 `MaterialVideoTranscodeMp4Route` | `ENCODING` |
| `ENCODING` | workflow 完成 | 重新轮询 | `CHECKING` |
| `PAUSED` | LOCAL: play | broadcast `playing(t)` | `PLAYING` |
| `PAUSED` | CH: `seek-and-play(s,id)` **[Mode C 专用]** | seekId 已见过 → ignore；否则 seek(s) + broadcast `playing(t)` | `PLAYING` |
| `PAUSED` | CH: `seek-passive(s)` **[Mode C 专用]** | seek(s)，不播放 | `PAUSED` |
| `PAUSED` | CH: `playing(other)` | 已暂停，no-op | `PAUSED` |
| `PAUSED` | CH: `status-request` | broadcast `status-reply(paused, t)` | `PAUSED` |
| `PLAYING` | LOCAL: pause / ended | broadcast `paused` | `PAUSED` |
| `PLAYING` | CH: `playing(other)` | 强制暂停，broadcast `paused` | `PAUSED` |
| `PLAYING` | CH: `seek-and-play(s,id)` **[Mode C 专用]** | seekId 已见过 → ignore；否则 seek(s)，继续播放（已在播，无需 rebroadcast） | `PLAYING` |
| `PLAYING` | CH: `seek-passive(s)` **[Mode C 专用]** | seek(s)，继续播放 | `PLAYING` |
| `PLAYING` | CH: `status-request` | broadcast `status-reply(playing, t)` | `PLAYING` |
| `NEEDS_ENCODE`/`ENCODING`/`CHECKING` | CH: `seek-and-play(s,id)` **[Mode C 专用]** | 存入 `pendingSeek{s, autoPlay:true}`（最新覆盖旧） | 不变 |
| `NEEDS_ENCODE`/`ENCODING`/`CHECKING` | CH: `seek-passive(s)` **[Mode C 专用]** | ignore（未就绪无意义） | 不变 |
| 任意 | unmount | broadcast `destroyed` | 终止 |

#### 广播规则与防环

- `PAUSED → PLAYING`：broadcast `playing`
- `PLAYING → PAUSED`（无论本地还是被动强制）：broadcast `paused`
- `CH: playing` 触发强制暂停后 **也广播 `paused`**：在三方实例场景下让其他实例感知状态变化，不会产生环路（`paused` 消息是纯信息，没有实例会因收到 `paused` 而改变状态）
- `seekId` 去重：维护最近 20 个 seekId 的环形缓冲，避免快速点击/网络延迟造成重复 seek

#### 初始化探测

挂载后立即发 `status-request`，设 200ms 超时：
- 收到 `status-reply(playing, t)` → 初始化为 `PAUSED`（避免双播）
- 超时无回复 → 正常初始化（可自动播放）

---

### 6.4 高频 Seek 处理

所有 seek 统一走 channel，无需双路径。高频问题通过**发送端限速 + 接收端 RAF 批处理**解决：

#### 发送端：限速（100ms）

字幕行点击是单次事件，无频率问题。仅 scrubber 拖拽场景需要限速：

```ts
// 发送端：trailing throttle，100ms 最小间隔
// 拖拽 1 秒最多发 10 条消息，完全可以接受
function useSendSeekPassive(materialId: string) {
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const latestRef = useRef<number>(0);

    return useCallback((seconds: number) => {
        latestRef.current = seconds;
        if (timerRef.current !== null) return;         // 节流窗口内，只记录最新值
        timerRef.current = setTimeout(() => {
            timerRef.current = null;
            channel.postMessage({ type: 'seek-passive', materialId, seconds: latestRef.current });
        }, 100);
    }, [materialId]);
}
```

#### 接收端：RAF 批处理

```ts
// 接收端：同一帧内的多条 seek 消息只应用最后一条
const rafRef = useRef<number | null>(null);
const pendingSeekRef = useRef<number | null>(null);

function applySeekFromChannel(seconds: number) {
    pendingSeekRef.current = seconds;
    if (rafRef.current !== null) return;           // 已有待执行帧，更新值即可
    rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null;
        if (pendingSeekRef.current !== null && videoRef.current) {
            videoRef.current.currentTime = pendingSeekRef.current;
            pendingSeekRef.current = null;
        }
    });
}
```

**为什么不需要直连 ref**：BroadcastChannel 的 postMessage 在同一 Tab 内也走异步（microtask 级别，~0.1ms），远低于 RAF 周期（16ms）。100ms 节流 + RAF 批处理后，接收端每帧最多执行一次 `currentTime` 赋值，性能无忧。

---

### 6.5 Channel 接口与生命周期封装

channel 的生命周期、去重、限速逻辑全部封装在 hook 内，调用方只看业务接口：

```ts
// app/util/videoPlayerChannel.ts

export interface UseVideoPlayerChannelOptions {
    materialId: string;
    instanceId: string;           // 组件挂载时生成的 UUID，整个生命周期不变
    getPlaybackState: () => 'playing' | 'paused' | 'not-ready';  // 用于 status-reply
    getCurrentTime: () => number;
    // ── 接收回调 ──────────────────────────────────────────────────
    onForcePause: () => void;                   // CH: playing(other) → 强制暂停
    onSeekAndPlay: (seconds: number) => void;   // CH: seek-and-play（Mode C 专用；Mode B 经 Context 驱动，不注册此回调）
    onSeekPassive: (seconds: number) => void;   // CH: seek-passive（已 RAF 批处理）
    onPeerDestroyed: (instanceId: string) => void; // CH: destroyed
}

export interface UseVideoPlayerChannelResult {
    // ── 发送 API ──────────────────────────────────────────────────
    broadcastPlaying: (currentTime: number) => void;
    broadcastPaused: () => void;
    broadcastDestroyed: () => void;         // unmount 时调用（hook cleanup 也会自动调用）
    broadcastSeekAndPlay: (seconds: number) => void;  // 自动生成 seekId
    broadcastSeekPassive: (seconds: number) => void;  // 内置 100ms 节流
    broadcastTimeSync: (currentTime: number) => void; // 内置 1s 节流（可选，进度条同步）
    // ── 探测 API ─────────────────────────────────────────────────
    requestPeerStatus: () => Promise<StatusReply[]>;  // 200ms 超时，收集所有回复
}

export function useVideoPlayerChannel(
    options: UseVideoPlayerChannelOptions
): UseVideoPlayerChannelResult {
    // 内部维护：
    //   channel: BroadcastChannel（materialId 变化时重建）
    //   seenSeekIds: string[]（环形缓冲，容量 20）
    //   throttle timer（seek-passive 发送端）
    //   RAF handle（seek-passive 接收端）
    //   cleanup: unmount 时 broadcast destroyed + close channel
}
```

**生命周期保证**：
- `materialId` 变化 → 旧 channel 关闭（broadcast `destroyed`），新 channel 开启（broadcast `status-request`）
- 组件 unmount → hook cleanup 自动 broadcast `destroyed` + 关闭 channel，调用方无需手动处理
- channel name 格式：`fredica-video-player-${materialId}`（不同素材天然隔离）

---

## 7. 字幕时间轴联动接口

### 7.1 联动流程

主 Tab 和 Mode C Tab 的字幕联动路径**不同**：Mode B 经 Context 直接驱动，Mode C 经 channel。

**主 Tab（任意子路由字幕面板）→ Mode B**：

```
字幕行点击（主 Tab）
  └─ floatingCtx.openFloatingPlayer(materialId, seconds)
       ├─ Mode B（Context 调用，同步，零延迟）：
       │    ├─ 若 materialId 变更 → leave 旧 channel，join 新 channel
       │    ├─ seek(seconds) + PLAYING
       │    └─ broadcastPlaying(M) → Mode A/C 强制暂停
       └─ Mode A / Mode C：不受影响（Context 调用不走 channel）
```

**Mode C Tab（独立标签字幕面板）→ Mode C 自身播放器**：

```
字幕行点击（Mode C Tab）
  └─ channel.broadcastSeekAndPlay(materialId, seconds, seekId, tab_C)
       └─ Mode C 播放器（tabId=tab_C 匹配）：seek(s) + PLAYING
            broadcastPlaying(M) → Mode B 强制暂停；Mode A 强制暂停
```

**竞态保护**：`openFloatingPlayer(M, s)` 中的 `seekTo` 被存为 `pendingSeek` 在 Context state 中。若 Mode B 此时处于 NEEDS_ENCODE/ENCODING/CHECKING，进入 PAUSED 后自动应用 pendingSeek → PLAYING + broadcastPlaying（见 §6.2 S5 行）。

字幕子页面调用示例：

```ts
// 主 Tab 字幕行点击
function handleSubtitleClick(seconds: number) {
    floatingCtx.openFloatingPlayer(material.id, seconds);
    // Mode B 直接 seek + play，无需 channel
}

// Mode C Tab 字幕行点击
function handleSubtitleClick(seconds: number) {
    channel.broadcastSeekAndPlay(seconds);
    // Mode C 播放器（同 tab）响应，tabId 匹配
}
```

`MaterialWorkspaceCtx` 不再包含 `openVideoPlayer` / `seekVideoTo`；字幕等子页面通过 `useFloatingPlayerCtx()` 直接操作 Mode B。

### 7.2 `BilibiliSubtitlePanel` 扩展

`SubtitleBodyPanel` 接受新 prop：

```tsx
interface SubtitleBodyPanelProps {
    metaItem: SubtitleMetaItem;
    isUpdate: boolean;
    onSeek?: (seconds: number) => void;  // 点击字幕行回调
}

// VirtualSubtitleList 的每行添加点击处理
<div
    onClick={() => onSeek?.(item.from)}
    className={`... ${onSeek ? 'cursor-pointer hover:bg-violet-50' : ''}`}
>
```

---

## 8. 前端组件树结构

```
root.tsx / SidebarLayout（App 级根布局）
├── FloatingVideoPlayerSingleton（fixed，全应用唯一，可拖拽）  ← 模式 B
│   ├── FloatingPlayerCtx Provider
│   └── MaterialVideoPlayer（mode="floating"）
│       ├── useVideoPlayerState（状态机 §6.3）
│       └── useVideoPlayerChannel（channel 封装 §6.5，materialId 动态切换）
│
└── <Outlet />（全部页面路由）
    └── material.$materialId.tsx（素材工作区父路由）
        ├── MaterialHeader
        ├── MaterialSubNav
        ├── <Outlet />（子路由）
        │   └── SubtitleBilibiliPage
        │       └── BilibiliSubtitlePanel
        │           └── VirtualSubtitleList
        │               └── 字幕行 onClick → floatingCtx.openFloatingPlayer(id, s)
        └── MaterialSwitcherDrawer
```

`material.$materialId.video-standalone.tsx`（模式 C，独立新标签）：

```
material.$materialId.video-standalone.tsx（无 SubNav，全屏）
├── MaterialVideoPlayer（mode="standalone"）
│   ├── useVideoPlayerState（状态机 §6.3）
│   └── useVideoPlayerChannel（channel 封装 §6.5，tabId=tab_C）
└── BilibiliSubtitlePanel（可选侧栏）
    └── 字幕行 onClick → channel.broadcastSeekAndPlay(s)（tabId 过滤）
```

`MaterialVideoPlayer` 内部结构：

```
MaterialVideoPlayer
├── [checking]          → Loader 骨架屏
├── [needs_transcode]
│   └── TabBar: [▶ 视频（disabled）] [⚡ 转码]
│       └── 开始转码按钮
├── [transcoding]
│   └── TabBar: [▶ 视频（disabled）] [⚡ 进度]
│       └── WorkflowInfoPanel（workflowRunId）
└── [ready]
    └── TabBar: [▶ 视频（active）] [✓ 已转码]
        └── <video key={materialId + '_' + file_mtime} src="?material_id=xxx" controls />
```

---

## 9. 补充问题

### 9.1 平台 WebView 兼容性

| 平台 | WebView | H.264 支持 | Range 请求 |
|------|---------|-----------|-----------|
| **Windows** | WebView2（Chromium） | 内置 | ✓ |
| **macOS** | WebKit | 内置 | ✓ |
| **Android** | Android WebView（Chromium） | 内置 | ✓，但需注意：Android WebView 对 `Cache-Control: no-cache` 的处理有时强制重新下载而非条件请求（个别版本 bug）。若出现此问题，回退方案是在 src 加 `?_t=<session_id>`（每次 session 相同，仅 session 间不同）避免重复下载。 |

转码时统一输出 H.264 + AAC（已由 Python ffmpeg 实现），覆盖全平台。

### 9.2 `-movflags +faststart`（**需修改 Python 转码命令**）

若 MP4 的 `moov` atom 在文件末尾，浏览器需下载完整文件才能开始播放/seek。

**现状**：`transcode/mp4_task.py` 中需核查是否已包含 `-movflags +faststart`，若未加则补充。无此参数将导致 seek 体验极差（需等待完整下载）。

### 9.3 多窗口/多路由下的 src 刷新

模式 B 全局悬浮播放器在 `materialId` 切换时需重置：
- `useEffect([materialId])` → 重新进入 `checking` 状态
- `<video key={materialId + '_' + file_mtime}>` 确保 DOM 完全重建

### 9.4 安全性

`MaterialVideoStreamRoute` **需要鉴权**（与其他 API 路由一致），监听外部接口。

`<video src>` 无法携带 Authorization header，且 **token 放 URL 会破坏跨 session 缓存**（token 随 app 重启变化 → URL 变化 → 每次重启都缓存 miss，重新读取数百 MB 视频文件）。

改为 **Cookie 鉴权**，URL 中不含 token：
- app 启动时设置 Cookie → Ktor 从 Cookie header 读取 `fredica_token` 验证
- Cache key 仅为 `?material_id=xxx`，跨 session 稳定

### 9.5 进度记忆

所有模式在暂停时将播放进度保存到 `localStorage`，下次打开同一素材可恢复。

**保存时机**：
- 用户手动暂停（点击 ⏸）
- 收到其他实例的 `playing` 消息被强制暂停
- 视频自然播放结束（保存位置：0 或末尾可按需决定，建议保存末尾让用户重看需手动拖回）
- 每 5 秒定时保存（兜底，防止意外崩溃丢失进度）

**存储键**：`fredica-video-progress-{materialId}`

**存储格式**：
```json
{ "currentTime": 123.4, "savedAt": 1710000000000 }
```

**恢复优先级**（高 → 低）：
1. `openFloatingPlayer` / `pendingSeek` 传入的精确目标时间（如字幕行点击）
2. `localStorage` 保存的进度（若 `savedAt` 不超过 30 天）
3. 从头播放（0s）

**各模式恢复时机**：

| 模式 | 恢复时机 |
|------|---------|
| 悬浮播放器（Mode B） | `openFloatingPlayer(materialId)` 被调用且无显式 `seekTo` 参数时，从 localStorage 读取 |
| inline 播放器（Mode A） | 组件挂载后，若无外部 seek 指令则从 localStorage 读取 |
| 独立标签播放器（Mode C） | 组件挂载后，从 localStorage 读取 |

> 使用 `localStorage` 而非 `sessionStorage`：`sessionStorage` 随标签页关闭消失，对长视频用户体验差；`localStorage` 跨 session 保留。进度信息不含敏感数据，落盘无安全顾虑。

---

## 10. 实现顺序

各 Phase 内部可并行；Phase 间按依赖顺序推进。

### Phase 1 — 后端先决条件（可全部并行）

| 任务 | 位置 | 说明 |
|------|------|------|
| `MaterialVideoCheckRoute` | `shared/commonMain/api/routes/` | GET，检查 MP4 + done 标记，返回 ready/file_mtime/file_size |
| `MaterialVideoStreamRoute` | `shared/jvmMain/`，`getNativeRoutes()` | GET，`respondFile` + Range 支持 + Cookie 鉴权（从 Cookie 读 `fredica_token`） |
| app 启动时写入 Cookie | `fredica-webui/app/context/appConfig.tsx` | `get_server_info` 返回后执行 `document.cookie = fredica_token=...` |
| `mp4_task.py` 补充参数 | `desktop_assets/.../subprocess/transcode/mp4_task.py` | 补充 `-movflags +faststart`，并在转码成功后写 `transcode.done` |
| bridge `open_internal_tab` | `composeApp` Kotlin 端 | 新增 Bridge 方法，在新 WebView 窗口打开内部路径 |

### Phase 2 — 前端 Hook 基础层（无 UI，可独立测试）

依赖：Phase 1 后端 API 已可调用（或用 MSW mock）

| 任务 | 文件 | 说明 |
|------|------|------|
| `useVideoPlayerChannel` | `app/util/videoPlayerChannel.ts` | BroadcastChannel 封装：instanceId/tabId 管理、seekId 去重环形缓冲（容量 20）、seek-passive 节流（100ms）、接收端 RAF 批处理、unmount 自动 cleanup |
| `useVideoPlayerState` | `app/hooks/useVideoPlayerState.ts` | 状态机（CHECKING/NEEDS_ENCODE/ENCODING/PAUSED/PLAYING）、pendingSeek 管理、localStorage 进度读写 |
| `tabId` 初始化 | `app/root.tsx` 或 `app/util/videoPlayerChannel.ts` | 首次加载时 `sessionStorage.getItem('fredica-tab-id') ?? crypto.randomUUID()` |

### Phase 3 — Mode A：inline 播放器

依赖：Phase 2

| 任务 | 文件 | 说明 |
|------|------|------|
| `MaterialVideoPlayer` 组件 | `app/components/video/MaterialVideoPlayer.tsx` | 集成 `useVideoPlayerState` + `useVideoPlayerChannel`；渲染转码状态机 UI（TabBar：视频预览 / 转码 / 转码进度） |
| 集成到概览页 | `app/routes/material.$materialId._index.tsx` | `<MaterialVideoPlayer materialId={...} mode="inline" />` |

### Phase 4 — Mode B：全局悬浮播放器

依赖：Phase 3（`MaterialVideoPlayer` 组件可复用）

| 任务 | 文件 | 说明 |
|------|------|------|
| `FloatingPlayerCtx` | `app/context/floatingPlayer.tsx` | `openFloatingPlayer(id, seekTo?)` / `closeFloatingPlayer` / `currentMaterialId` / `isVisible`；Provider 挂在 `SidebarLayout` |
| `FloatingVideoPlayerSingleton` | `app/components/video/FloatingVideoPlayerSingleton.tsx` | fixed 定位，三态（HIDDEN/MINIMIZED/OPEN），支持拖拽（drag handle），z-50/z-60；内含 `MaterialVideoPlayer mode="floating"` |
| 接入 `SidebarLayout` | `app/components/layout/SidebarLayout.tsx`（或 `root.tsx`） | 挂载单例组件 + Provider |

### Phase 5 — 字幕时间轴联动（主窗口）

依赖：Phase 4

| 任务 | 文件 | 说明 |
|------|------|------|
| `BilibiliSubtitlePanel` 扩展 | `app/components/bilibili/BilibiliSubtitlePanel.tsx` | `SubtitleBodyPanel` 新增 `onSeek?(seconds: number)` prop；每行 `onClick → onSeek?.(item.from)` |
| subtitle-bilibili 页接入 | `app/routes/material.$materialId.subtitle-bilibili.tsx` | `floatingCtx.openFloatingPlayer(material.id, seconds)` |

### Phase 6 — Mode C：独立标签页

依赖：Phase 2、Phase 5

| 任务 | 文件 | 说明 |
|------|------|------|
| `openInternalUrl` 工具函数 | `app/util/bridge.ts` | 类比 `openExternalUrl`；WebView 调 `open_internal_tab` bridge，浏览器 dev 环境 `window.open` |
| `video-standalone` 路由 | `app/routes/material.$materialId.video-standalone.tsx` | 全屏播放器 + 可选字幕侧栏；`MaterialVideoPlayer mode="standalone"` |
| Mode C 字幕联动 | 同上 | `channel.broadcastSeekAndPlay(seconds)` |

---

## 11. 自动化测试方案

### 11.1 Kotlin 单元测试

位置：`shared/src/jvmTest/kotlin/`，参照现有测试模式（SQLite 临时文件、Ktor TestApplication）。

**`MaterialVideoCheckRoute`**：

| 测试用例 | 预期结果 |
|---------|---------|
| `video.mp4` 存在，`transcode.done` 存在 | `ready: true`，响应含 `file_size` / `file_mtime` |
| `video.mp4` 存在，`transcode.done` 缺失 | `ready: false`（防返回转码中的不完整文件） |
| 两者均不存在 | `ready: false` |
| 缺少 `material_id` 参数 | 400 或 `{"error": "..."}` |

**`MaterialVideoStreamRoute`**：

| 测试用例 | 预期结果 |
|---------|---------|
| 文件存在，无 Range 头 | 200，`Content-Type: video/mp4`，`Accept-Ranges: bytes` |
| 文件存在，带 `Range: bytes=0-1023` | 206 Partial Content，`Content-Range` 头正确 |
| 文件不存在 | 404，`{"error": "MP4_NOT_FOUND"}` |
| Cookie 缺失 / 无效 | 401（与其他路由鉴权行为一致） |
| `Last-Modified` / `ETag` 响应头 | 存在且值与文件 mtime 对应 |
| 带 `If-None-Match` 且文件未变更 | 304，无 body |

### 11.2 Python 单元测试

位置：`desktop_assets/common/fredica-pyutil/tests/`，使用 pytest。

**`transcode/mp4_task.py`**：

| 测试用例 | 说明 |
|---------|------|
| FFmpeg 命令参数检查 | 断言命令列表中含 `-movflags` 和 `+faststart` |
| 转码成功后 `transcode.done` 被写入 | mock subprocess 返回码 0，验证文件存在 |
| 转码失败后 `transcode.done` 不被写入 | mock subprocess 返回码非 0 |
| `video.mp4` 输出路径正确 | 验证 `-output_path` 对应路径 |

### 11.3 前端单元测试（Vitest + @testing-library/react）

JSDOM 不原生支持 `BroadcastChannel`；使用内存 mock 实现，同进程内以 channel name 路由消息：

```ts
// tests/mocks/broadcastChannel.ts
class MockBroadcastChannel {
    static buses = new Map<string, Set<MockBroadcastChannel>>();
    onmessage: ((e: MessageEvent) => void) | null = null;
    constructor(public name: string) {
        if (!MockBroadcastChannel.buses.has(name))
            MockBroadcastChannel.buses.set(name, new Set());
        MockBroadcastChannel.buses.get(name)!.add(this);
    }
    postMessage(data: unknown) {
        MockBroadcastChannel.buses.get(this.name)?.forEach(ch => {
            if (ch !== this) ch.onmessage?.(new MessageEvent('message', { data }));
        });
    }
    close() { MockBroadcastChannel.buses.get(this.name)?.delete(this); }
    static reset() { MockBroadcastChannel.buses.clear(); }
}
```

**`useVideoPlayerChannel` 测试用例**：

| # | 测试场景 | 断言 |
|---|---------|------|
| C1 | 收到 `playing(instanceId=自身)` | `onForcePause` 不调用（BroadcastChannel 本身不回发，显式检查兜底） |
| C2 | 收到 `playing(instanceId=他人)` | `onForcePause` 被调用一次 |
| C3 | 收到 `seek-and-play(tabId=不匹配)` | `onSeekAndPlay` 不调用 |
| C4 | 收到 `seek-and-play(tabId=自身)` | `onSeekAndPlay` 被调用，参数为 `seconds` |
| C5 | 同一 `seekId` 发送两次 | `onSeekAndPlay` 只调用一次（去重） |
| C6 | 第 21 个不同 `seekId` 后重发第 1 个 | `onSeekAndPlay` 再次被调用（环形缓冲溢出，旧 id 被淘汰） |
| C7 | `materialId` 变更 | 旧 channel `destroyed` 广播，旧 channel 关闭；新 channel 开启，`status-request` 广播 |
| C8 | 组件 unmount | 自动广播 `destroyed`，channel 关闭 |
| C9 | `broadcastSeekPassive` 100ms 内连续调用 5 次 | channel 只收到 1 条消息（节流） |

**`useVideoPlayerState` 状态机测试用例**：

| # | 初始状态 | 事件 | 期望新状态 | 期望副作用 |
|---|---------|------|----------|---------|
| V1 | — | mount | CHECKING | — |
| V2 | CHECKING | check ok，无 pendingSeek | PAUSED | — |
| V3 | CHECKING | check ok，`pendingSeek{autoPlay:true, s:30}` | PLAYING | `video.currentTime = 30`，`broadcastPlaying` |
| V4 | CHECKING | check ok，`pendingSeek{autoPlay:false, s:30}` | PAUSED | `video.currentTime = 30` |
| V5 | CHECKING | check fail | NEEDS_ENCODE | — |
| V6 | NEEDS_ENCODE | 用户点击"开始转码" | ENCODING | 调用 `MaterialVideoTranscodeMp4Route` |
| V7 | ENCODING | workflow 完成 | CHECKING | — |
| V8 | PAUSED | LOCAL: play | PLAYING | `broadcastPlaying` |
| V9 | PLAYING | LOCAL: pause | PAUSED | `broadcastPaused` |
| V10 | PLAYING | CH: `playing(other)` | PAUSED | `broadcastPaused`（防环，通知其他实例） |
| V11 | PAUSED | CH: `playing(other)` | PAUSED | no-op（已暂停，不再广播） |
| V12 | — | 暂停事件 | — | 写入 `localStorage['fredica-video-progress-{id}']` |
| V13 | CHECKING | check ok，有 localStorage 记录（30天内） | PAUSED | `video.currentTime = 保存值` |
| V14 | CHECKING | check ok，localStorage 记录超过 30 天 | PAUSED | `video.currentTime = 0` |

**`FloatingPlayerCtx` 测试用例**：

| # | 操作 | 断言 |
|---|------|------|
| F1 | 初始状态 | `isVisible=false`，`currentMaterialId=null` |
| F2 | `openFloatingPlayer('mat-1')` | `isVisible=true`，`currentMaterialId='mat-1'` |
| F3 | `openFloatingPlayer('mat-1', 42)` | `pendingSeek = {seconds:42, autoPlay:true}` |
| F4 | `closeFloatingPlayer()` | `isVisible=false`，`currentMaterialId=null` |

### 11.4 前端集成测试（多实例协调）

渲染两个 `MaterialVideoPlayer` 实例，共享同一 materialId（通过 MockBroadcastChannel 联通）：

| # | 场景 | 断言 |
|---|------|------|
| I1 | 实例 A 播放 → 实例 B 原来也在播 | 实例 B 进入 PAUSED，`broadcastPaused` 被调用 |
| I2 | 实例 A/B 几乎同时播放（R1 竞争） | 两者均最终处于 PAUSED |
| I3 | 实例 A 播放中，实例 B 挂载（status-request/reply） | 实例 B 在 200ms 内收到 `status-reply(playing)` → 初始化为 PAUSED |
| I4 | 200ms 内无任何 status-reply | 实例 B 按默认状态初始化（不强制 PAUSED） |
| I5 | 悬浮播放器从素材X 切换到素材Y（L3） | 素材X channel 收到 `destroyed`；素材Y channel 加入并发出 `status-request` |

### 11.5 E2E 测试（Playwright，`npm run dev` 模式）

Playwright 支持多 Page（标签页）操作，可测试真实 BroadcastChannel 跨 tab 行为。

**前提**：
- `npm run dev` 运行前端 dev server（localhost:7630）
- 测试素材已有 `video.mp4`（预置 fixture），或用 MSW mock `MaterialVideoCheckRoute` / `MaterialVideoStreamRoute`

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| E1 | 跨标签页播放互斥（R7） | Tab1 + Tab2 打开同一素材；Tab1 点击 ▶；等待 | Tab2 的播放器 `data-state="paused"` |
| E2 | 字幕行点击唤起悬浮播放器并 seek（S1） | 主窗口打开字幕页，点击某行字幕 | 悬浮播放器出现，`video.currentTime` ≈ 字幕行时间戳 |
| E3 | 悬浮播放器拖拽 | 拖拽 drag handle | 位置变化，仍可播放 |
| E4 | 独立标签页 seek-and-play 不影响主窗口（tabId 过滤） | 打开 Mode C 标签，点击字幕行 | 主窗口 inline 播放器不触发 seek（`currentTime` 不变） |
| E5 | 进度恢复（localStorage） | 播放到 60s 后关闭；重新打开同一素材 | 播放器从 ≈60s 开始 |

### 11.6 测试基础设施汇总

| 层级 | 框架 | Mock 策略 | 运行命令 |
|------|------|---------|---------|
| Kotlin 单元 | JUnit5 + Ktor TestApplication | 临时 SQLite 文件，临时媒体目录 | `./gradlew :shared:jvmTest` |
| Python 单元 | pytest | `subprocess` mock，临时目录 | `pytest desktop_assets/.../tests/` |
| 前端单元/集成 | Vitest + @testing-library/react | MockBroadcastChannel，localStorage mock，HTMLVideoElement mock | `cd fredica-webui && npm test` |
| E2E | Playwright | dev server + 预置测试素材 / MSW | `cd fredica-webui && npx playwright test` |
