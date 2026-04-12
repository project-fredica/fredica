---
title: 字幕导出 & Faster-Whisper ASR 任务设计方案
order: 530
---

# 字幕导出 & Faster-Whisper ASR 任务设计方案

> 本文档涵盖两个相关功能：
> 1. **字幕导出**：在素材工作台字幕页面提供 SRT 文件下载
> 2. **Faster-Whisper ASR 任务**：在素材工作台启动本地 ASR 转录任务（含结果缓存设计）

---

## 一、字幕导出（SRT Export） <Badge type="tip" text="已完成" />

### 1.1 需求概述

在 `material.$materialId.subtitle-bilibili.tsx` 页面（以及主字幕页）为已加载的 Bilibili 字幕提供"导出 SRT"按钮，点击后下载符合 SRT 格式规范的字幕文件。

### 1.2 数据流现状

```
BilibiliVideoSubtitleBodyRoute (POST)
  ↓ 返回 Bilibili JSON 格式字幕
BilibiliSubtitlePanel.tsx
  ↓ 渲染字幕列表 + 当前显示在前端内存中
material.$materialId.subtitle-bilibili.tsx
```

Bilibili 字幕 JSON 格式（`subtitle_body` 字段）：

```json
{
  "body": [
    { "from": 1.23, "to": 4.56, "content": "字幕文本" },
    ...
  ]
}
```

### 1.3 SRT 格式规范

```
1
00:00:01,230 --> 00:00:04,560
字幕文本

2
00:00:05,100 --> 00:00:08,900
下一条字幕
```

时间戳格式：`HH:MM:SS,mmm`（小时:分钟:秒,毫秒）

### 1.4 实现方案（纯前端）

字幕数据已在前端内存中，**无需新增后端 API**，直接在前端转换并触发浏览器下载。

#### 工具函数（新增）

**文件**：`fredica-webui/app/util/subtitleExport.ts`

```typescript
export interface SrtSegment {
  from: number; // 秒（含小数）
  to: number;
  content: string;
}

/** 秒数 → SRT 时间戳 "HH:MM:SS,mmm" */
function secondsToSrtTime(sec: number): string {
  const ms = Math.round(sec * 1000);
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  const s = Math.floor((ms % 60_000) / 1_000);
  const msPart = ms % 1_000;
  return [
    String(h).padStart(2, "0"),
    String(m).padStart(2, "0"),
    String(s).padStart(2, "0"),
  ].join(":") + "," + String(msPart).padStart(3, "0");
}

/** Bilibili subtitle body → SRT 字符串 */
export function convertToSrt(segments: SrtSegment[]): string {
  return segments
    .map((seg, i) =>
      `${i + 1}\n${secondsToSrtTime(seg.from)} --> ${secondsToSrtTime(seg.to)}\n${seg.content}`
    )
    .join("\n\n") + "\n";
}

/** 触发浏览器下载 SRT 文件 */
export function downloadSrt(content: string, filename: string): void {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
```

#### BilibiliSubtitlePanel 改造

在 `BilibiliSubtitlePanel.tsx` 中，当字幕内容（`subtitleBody`）已加载时，在工具栏新增"导出 SRT"按钮：

```tsx
import { convertToSrt, downloadSrt } from "~/util/subtitleExport";

// 在已有字幕内容时显示导出按钮
{subtitleBody?.body && (
  <button
    onClick={() => {
      const srt = convertToSrt(subtitleBody.body);
      const lan = selectedLan ?? "subtitle";
      downloadSrt(srt, `${materialId}_${lan}.srt`);
    }}
  >
    导出 SRT
  </button>
)}
```

文件名格式：`{materialId}_{lan}.srt`，例如 `BV1xx411c7mD_p1_zh-Hans.srt`。

#### 主字幕页集成

`material.$materialId.subtitle.tsx` 中每个已缓存的字幕项（`SubtitleApiItem`）同样可提供导出入口：点击"导出"时先调用 `MaterialSubtitleContentRoute` 获取内容，再转换为 SRT 下载。

> **注意**：`MaterialSubtitleContentRoute` 目前返回纯文本，缺少分段时间戳。若需完整 SRT 需走 `BilibiliVideoSubtitleBodyRoute`。可先在主字幕页仅提供"纯文本导出"（`.txt`），在 bilibili 详情页提供完整 SRT。

### 1.5 改动文件清单

| 文件 | 改动 |
|------|------|
| `fredica-webui/app/util/subtitleExport.ts` | **新建**：SRT 转换 + 下载工具函数 <Badge type="tip" text="已完成" /> |
| `fredica-webui/app/components/bilibili/BilibiliSubtitlePanel.tsx` | 新增"导出 SRT"按钮 <Badge type="tip" text="已完成" /> |
| `fredica-webui/app/routes/material.$materialId.subtitle.tsx` | 可选：纯文本导出入口 |

---

## 二、Faster-Whisper ASR 任务全流程设计 <Badge type="tip" text="已完成" />

### 2.0 前置条件：faster-whisper 自动安装 <Badge type="tip" text="已完成" />

#### 2.0.1 依赖关系澄清

`faster-whisper` **不依赖 PyTorch**。其完整依赖树为：

```
faster-whisper
├── ctranslate2>=4.0,<5   （依赖 setuptools / numpy / pyyaml，无 torch）
├── huggingface_hub>=0.23
├── tokenizers>=0.13,<1
├── onnxruntime>=1.14,<2
├── av>=11
└── tqdm
```

GPU 推理依赖的是系统级 **cuBLAS 12 + cuDNN 9**（NVIDIA 驱动随附或单独安装），与 PyTorch 无关。`app-desktop-setting-torch-config.tsx` 页面管理的 Torch 环境是为其他功能准备的，ASR 不依赖它。

因此：
- **CPU 模式**：无任何前置条件，直接可用
- **GPU 模式**：需要系统已装 cuBLAS 12 + cuDNN 9（不通过 pip 安装，属于驱动层）

#### 2.0.2 自动安装 faster-whisper

`faster-whisper` 属于懒安装：不加入 `requirements.txt`（避免每次启动都安装），而是在首次启动 ASR 工作流时由 Kotlin 端按需安装。

**调用时机**：`ensureInstalled()` 必须在 **Executor 层**（`DOWNLOAD_WHISPER_MODEL` 或 `EXTRACT_AUDIO`）调用，而不是在 `MaterialWorkflowRoute` 的 HTTP handler 里调用。pip install 可能耗时数分钟，在 Route 层阻塞会导致前端请求超时。

**重启行为**：`@Volatile installed` flag 仅在进程内有效，重启后会重新执行一次 `pip install`。这是有意为之——pip 会检测已安装版本并快速跳过，相当于每次启动做一次轻量验证，无需额外持久化状态。

**安装机制**：`PythonUtil.Py314Embed` 已有 `runPythonSubprocess` + `pipLibDir` 的完整 pip 安装基础设施，只需在其上暴露一个公共的 `installPackage` 方法。并发安全由 `installPackage` 内部的 `Mutex` 负责，调用方无需自行加锁：

```kotlin
// PythonUtil.Py314Embed 新增公共方法
// Mutex 在此作用域内，确保并发调用时 pip install 串行执行
private val pipInstallMutex = Mutex()

suspend fun installPackage(packageSpec: String) {
    pipInstallMutex.withLock {
        runPythonSubprocess(
            listOf("-m", "pip", "install", "--no-input", "--target",
                   AppUtil.Paths.pipLibDir.absolutePath, packageSpec)
        ).await()
    }
}
```

**实现注意**：`runPythonSubprocess` 内部已用 `withContext(Dispatchers.IO) + async` 启动子进程，外层 `withLock` 包裹 `.await()` 是正确的——锁保证串行，`await()` 等待子进程退出。`runPythonSubprocess` 在以下情况会抛出异常（均会被 `FasterWhisperInstallService` 的 catch 块捕获并记录）：
- `IllegalStateException`：pip 进程 exit code 非 0（如网络错误、包名不存在）
- `TimeoutException`：超过 `timeoutMs` 仍未退出（默认无超时，建议调用时传合理上限）

**`FasterWhisperInstallService`（jvmMain 新建）**：

只持有进程内 `@Volatile` flag 用于快速跳过，并发控制完全委托给 `installPackage`：

```kotlin
object FasterWhisperInstallService {
    private val logger = createLogger("FasterWhisperInstallService")

    // 进程内 flag，避免重复安装检查（重启后重新检查一次即可）
    // 并发安全由 PythonUtil.Py314Embed.installPackage 内部的 Mutex 保证
    @Volatile private var installed = false

    /**
     * 确保 faster-whisper 已安装。
     * - 已安装（内存 flag）：直接返回 null（快速路径，无锁）
     * - 未安装：调用 installPackage（内部串行），成功后置 flag
     * @return null 表示成功，非 null 为错误信息（已记录日志，调用方直接透传给前端即可）
     */
    suspend fun ensureInstalled(): String? {
        if (installed) return null
        return try {
            PythonUtil.Py314Embed.installPackage("faster-whisper==1.2.1")
            installed = true
            null
        } catch (e: CancellationException) {
            throw e  // 取消信号必须透传，不能吞掉
        } catch (e: Throwable) {
            logger.error("[FasterWhisperInstallService] pip install failed", e)
            e.message ?: "安装失败"
        }
    }
}
```

固定版本 `faster-whisper==1.2.1`，避免 `ctranslate2` 的 CUDA 版本兼容性问题。

**Task statusText 更新**：

`DOWNLOAD_WHISPER_MODEL` Task 在 `ensureInstalled()` 执行期间，通过 `TaskService.repo.updateStatusText(taskId, "正在安装 faster-whisper...")` 更新状态文本。若该 Task 不存在（已跳过），则在 `EXTRACT_AUDIO` 开始前完成安装，statusText 写在 `EXTRACT_AUDIO` 上。

**安装失败时**：`ensureInstalled()` 返回非 null 错误信息，Executor 应将 Task 标记为 failed 并把错误信息写入 `task.errorMessage`，前端通过 `WorkflowInfoPanel` 展示失败原因并 toast 提示。注意：失败不应在 `MaterialWorkflowRoute` 层处理——Route 层只负责创建工作流，安装失败发生在 Executor 执行阶段。

#### 2.0.3 改动文件清单（前置条件部分）

| 文件 | 改动 |
|------|------|
| `shared/src/jvmMain/.../python/PythonUtil.kt` | `Py314Embed` 新增公共 `installPackage(packageSpec)` 方法 |
| `shared/src/jvmMain/.../python/FasterWhisperInstallService.kt` | **新建**：进程内 `@Volatile` flag（并发锁由 `installPackage` 负责） |
| `shared/src/commonMain/.../db/MaterialWorkflowService.kt` | `startWhisperTranscribe()` 最小线性工作流（EXTRACT_AUDIO → TRANSCRIBE） <Badge type="tip" text="已完成" /> |
| `shared/src/commonMain/.../api/routes/MaterialWorkflowRoute.kt` | `whisper_transcribe` 模板分发 <Badge type="tip" text="已完成" /> |
| `fredica-webui/app/routes/material.$materialId.subtitle.tsx` | ASR 方案入口接入工作流，启动后展示 `WorkflowInfoPanel` <Badge type="tip" text="已完成" /> |

---

### 2.1 需求概述 <Badge type="tip" text="已完成" />

在 `material.$materialId.subtitle.tsx` 的"ASR (Whisper)"方案页面，允许用户：
1. 选择模型大小（tiny / base / small / medium / large-v3）
2. 选择语言（auto / zh / en / ja / ko …）
3. 启动本地转录任务（含进度展示）
4. 复用已有转录结果（不同模型 & 语言组合独立缓存）

### 2.2 缓存路径约定 <Badge type="tip" text="已完成" />

```
{appDataDir}/media/{materialId}/
├── video.mp4                ← TRANSCODE_MP4 产出（transcode.done + hash）
├── transcode.done
├── asr_audio/               ← EXTRACT_AUDIO 产出（5 分钟/段，60 秒重叠）
│   ├── chunk_0000.m4a
│   ├── chunk_0001.m4a
│   ├── ...
│   └── extract_audio.done   ← JSON：含 chunks 列表、时长、hash
└── asr_results/
    └── {model_size}/        ← 按模型尺寸分目录（语言记录在 meta 中）
        ├── chunk_0000.start_info.json  ← 转录启动信息（audio_path, model_size, language, compute_type）
        ├── chunk_0000.jsonl            ← 逐 segment JSONL（{"start","end","text"} 每行一条）
        ├── chunk_0000.srt              ← 标准 SRT 格式（Python 子进程直接落盘）
        ├── chunk_0000.meta.json        ← 元信息（language, segment_count, core_start/end_sec, completed_at）
        ├── chunk_0000.done             ← 完成标记（input_hash, output_hash, model_size, language）
        ├── chunk_0001.start_info.json
        ├── chunk_0001.jsonl
        ├── chunk_0001.srt
        ├── chunk_0001.meta.json
        ├── chunk_0001.done
        ├── ...                         ← 每个 chunk 5 个文件，共 N 组
        ├── transcript.srt              ← 合并后完整 SRT（tryMergeChunks() core region 过滤后拼接）
        ├── transcript.meta.json        ← 合并元信息（model_size, language, total_segments, total_chunks）
        └── transcript.done             ← 完成标记（output_hash, chunk_count）
```

**路径规则**：
- `model_size`：以 `faster-whisper` 当前 `available_models()` 为准，至少覆盖：
  - 官方 Whisper CT2：`tiny` / `tiny.en` / `base` / `base.en` / `small` / `small.en` / `medium` / `medium.en` / `large-v1` / `large-v2` / `large-v3` / `large`
  - distil 系：`distil-small.en` / `distil-medium.en` / `distil-large-v2` / `distil-large-v3` / `distil-large-v3.5`
  - turbo：`large-v3-turbo` / `turbo`
- 前端默认只展示适合普通用户的推荐子集：`tiny` / `base` / `small` / `medium` / `large-v3` / `turbo`；高级模式再展开完整列表
- `language`：`auto` / `zh` / `en` / ...（`auto` 表示让 Whisper 自动检测）；语言不参与目录路径，记录在各 chunk 的 `.meta.json` 和 `.done` 中
- 每个 chunk 产出 5 个文件（`.start_info.json` / `.jsonl` / `.srt` / `.meta.json` / `.done`），由 Python 子进程直接落盘；Kotlin 只负责调度与日志透传，不负责在 JVM 内拼装 segment
- `transcode.done`、`extract_audio.done`、`chunk_XXXX.done`、`transcript.done` 都采用 **`.done + hash / fingerprint`** 模式，不只记录完成时间，还记录关键输入/输出指纹
- 所有这些缓存判断都应受统一开关 `disable_cache` 控制：当 `disable_cache=true` 时，**忽略 `.done`、忽略 hash、强制重新执行**
- `chunk_XXXX.done` 包含：`input_hash`、`output_hash`、`model_size`、`language`
- `transcript.done` 包含：`output_hash`、`chunk_count`

**前端显存建议文案**（新增一份面向用户的说明，供 ASR 页面与设置页复用）：
- `tiny / base`：CPU 可用；低显存设备优先
- `small / medium`：建议中档机器；CPU 可跑但更慢
- `large-v2 / large-v3 / turbo / distil-large-*`：建议独显；根据 faster-whisper 官方 benchmark，`large-v2` 在 RTX 3070 Ti 8GB 上约占 4.5GB VRAM（fp16）/ 2.9GB（int8），批量推理可升到约 6GB+
- 文案定位为 **经验建议**，不是硬门槛；最终是否可加载仍以 §7.2 的“真实加载检查”结论为准

**AppUtil.Paths 新增**（`AppUtil.kt`）：

```kotlin
fun asrOutputDir(materialId: String, modelSize: String): Path =
    materialMediaDir(materialId).resolve("asr_results/${modelSize}")
```

### 2.3 任务链设计 <Badge type="tip" text="已完成" />

#### 2.3.1 完整任务 DAG

```
EXTRACT_AUDIO
    5 分钟/段切割，60 秒重叠
    ↓
ASR_SPAWN_CHUNKS
    读取 EXTRACT_AUDIO 结果，动态创建 N 个 TRANSCRIBE Task
    ↓ 动态创建 N 个独立任务
TRANSCRIBE × N（每个 chunk 一个独立 Task）
    GpuResourceLock（优先级互斥队列）保证同一时刻只有一个 GPU 推理
    每完成一个 chunk 就立刻落盘 chunk_XXXX.srt / chunk_XXXX.done
    最后完成的 TRANSCRIBE 触发 tryMergeChunks() → transcript.srt
```

**关键设计**：
- 每个 chunk 对应一个独立的 `TRANSCRIBE` Task，天然支持 per-chunk 暂停/取消/重试
- `GpuResourceLock`（优先级互斥队列，优先级 1-10）保证 GPU 推理串行，避免显存竞争
- 合并逻辑内嵌在 `TranscribeExecutor.tryMergeChunks()` 中，最后完成的 chunk 触发合并，无需独立的合并 Task
- core region 过滤防止 chunk 边界重叠区域产生重复 segment
- N 在任务开始前未知，因此采用**两阶段创建**：`ASR_SPAWN_CHUNKS` 在运行时动态创建 N 个 `TRANSCRIBE` Task

#### 2.3.2 两阶段任务创建

由于 chunk 数量在 `EXTRACT_AUDIO` 完成前未知，无法提前创建 `TRANSCRIBE` task，因此：

**阶段 1 — 启动 API 调用时创建**（`MaterialWorkflowServiceExt.startWhisperTranscribe2`）：
```
WorkflowRun（template=”whisper_transcribe”）
  Task A: EXTRACT_AUDIO      depends_on=[]
  Task B: ASR_SPAWN_CHUNKS   depends_on=[A]   ← 负责读取 chunk 列表并动态创建 N 个 TRANSCRIBE Task
```

**阶段 2 — `AsrSpawnChunksExecutor` 执行时动态创建**：
```kotlin
// 读取 EXTRACT_AUDIO 的 result 获取实际 chunk 列表
val chunks = extractAudioResult.chunks  // [{path, index, offsetSec, coreStartSec, coreEndSec}, ...]

// 为每个 chunk 创建独立的 TRANSCRIBE Task
for (chunk in chunks) {
    TaskService.repo.create(Task(
        id = TaskId.random(),
        type = “TRANSCRIBE”,
        workflowRunId = workflowRunId,
        dependsOn = listOf(spawnChunksTaskId),
        payload = buildJsonObject {
            put(“chunk_index”, chunk.index)
            put(“total_chunks”, chunks.size)
            put(“chunk_offset_sec”, chunk.offsetSec)
            put(“core_start_sec”, chunk.coreStartSec)
            put(“core_end_sec”, chunk.coreEndSec)
            put(“audio_path”, chunk.path)
            put(“model_size”, modelSize)
            put(“language”, language)
            put(“output_dir”, transcriptDir)
        }.toString(),
        ...
    ))
}

// result 中返回 transcribe_task_ids 数组
```

#### 2.3.3 TRANSCODE_MP4 的必要性判断

`MaterialAsrStartRoute` 在创建任务链时判断：

| 已存在文件 | 处理方式 |
|-----------|---------|
| `transcode.done` + `video.mp4` + hash 匹配 | TRANSCODE_MP4 任务可 `canSkip` |
| `extract_audio.done` + chunk 指纹匹配 | TRANSCODE_MP4 + EXTRACT_AUDIO 均可 `canSkip` |
| 两者都没有 | 返回 `{"error": "素材尚未完成下载/转码，请先完成视频处理"}` |
| `disable_cache=true` | 忽略缓存，强制重跑 |

> 不在启动 API 里直接删缓存，而是统一依赖 `canSkip + done + hash + disable_cache` 机制，保持任务链结构简洁。

#### 2.3.4 各阶段的暂停 / 恢复 / 中止（清理）设计

这里统一复用 `docs/dev/task-model.md` 中已经落地的 Task 控制链：
- 前端按钮 → `TaskPauseRoute` / `TaskResumeRoute` / `TaskCancelRoute`
- Kotlin 侧 → `TaskPauseResumeService` / `TaskCancelService`
- Python WebSocket 长任务 → `pause` / `resume` / `cancel`
- 子进程 worker → `resume_event.wait()` / `cancel_event.is_set()`

**阶段级语义**：

| 阶段 | 暂停 | 恢复 | 中止 | 清理策略 |
|------|------|------|------|---------|
| `EXTRACT_AUDIO` | 复用现有 WebSocket pause/resume | 恢复当前音频切块 | 取消 ffmpeg 子进程 | 删除本轮新产生但未完成的 chunk 临时文件；不写 `extract_audio.done` |
| `ASR_SPAWN_CHUNKS` | 不需要（瞬时任务） | 不需要 | 若尚未落库则直接失败/取消 | 不产生额外清理成本；要求幂等 |
| `TRANSCRIBE` × N | **每个 chunk 独立暂停**；暂停当前 Python 子进程 | 从当前 chunk 继续 | **每个 chunk 独立取消**；停止当前子进程 | 已完成的 `chunk_XXXX.srt/.done` 保留；当前未完成 chunk 的临时文件删除；GpuResourceLock 保证串行 |

**`TRANSCRIBE` Task 的细化要求**：
1. 每个 TRANSCRIBE Task 处理一个 chunk，通过 `GpuResourceLock` 保证同一时刻只有一个 GPU 推理
2. `pause` 到来时：挂起当前 Python 子进程
3. `cancel` 到来时：
   - 向当前 Python 子进程发送 cancel
   - 当前 chunk 若未产生完整 `chunk_XXXX.done`，视为未完成并清理其临时产物
   - 已完成 chunk 全部保留，供前端预览或后续 rerun 复用
4. 某个 TRANSCRIBE 被取消不影响其他 chunk 的 TRANSCRIBE Task（独立任务）

**清理原则**：
- **只清理本轮未完成产物，不回收历史有效缓存**
- `.done` 是“可复用完成标记”，只在完整成功时写入；取消 / 崩溃 / 中止都不得写 `.done`
- 任何清理都必须限定在当前任务受控目录内，避免误删用户文件

### 2.4 后端实现 <Badge type="tip" text="已完成" />

#### 2.4.1 复用的现有 Executor

| Task 类型 | Executor / Service | canSkip 条件 |
|-----------|--------------------|-------------|
| `EXTRACT_AUDIO` | `ExtractAudioExecutor`（已有） | `extract_audio.done` 存在且 hash / fingerprint 校验通过，且 `disable_cache=false` |

#### 2.4.2 新增 Task 类型

| Task 类型 | Executor | canSkip 条件 |
|-----------|----------|-------------|
| `ASR_SPAWN_CHUNKS` | `AsrSpawnChunksExecutor`（新建） | 无（每次都执行，幂等） |
| `TRANSCRIBE` | `TranscribeExecutor`（复用，含 `tryMergeChunks()` 合并逻辑） | `chunk_XXXX.done` 存在且 inputHash + modelSize + language 匹配 |

#### 2.4.3 TranscodeMp4Service.kt（新建，Kotlin service）

`TranscodeMp4Executor.kt` 里已有三类可复用逻辑，不应继续散落在 executor 内：
1. `from_bilibili_download` / `direct` 输入解析
2. `resolveSelectedAccel(...)` / `resolveProbedFfmpegPath(...)`
3. 生成 Python `/transcode/mp4-task` 所需参数 JSON

建议提取 `TranscodeMp4Service`：

```kotlin
object TranscodeMp4Service {
    data class ResolvedJob(
        val inputVideo: String,
        val inputAudio: String?,
        val outputPath: String,
        val resolvedAccel: String,
        val ffmpegPath: String,
    )

    fun resolveJob(payload: Payload, config: AppConfig): ResolvedJob
    fun buildParamJson(job: ResolvedJob, gpuLockPath: String?, ffmpegLockPath: String?): String
}
```

复用方式：
- `TranscodeMp4Executor` 继续调用该 service，保持现有行为不变
- `MaterialAsrStartRoute` / 未来其他视频工作流只依赖 service，不再复制 executor 私有逻辑
- `ASR` 文档中的转码阶段直接复用现有缓存语义与 done/hash 设计，避免再发明一套“ASR 专用转码”逻辑

#### 2.4.4 AsrSpawnChunksExecutor.kt（新建）

```kotlin
@Serializable
private data class Payload(
    @SerialName("material_id")      val materialId: String,
    @SerialName("audio_chunks_dir") val audioChunksDir: String,  // asr_audio/ 目录
    @SerialName("asr_dir")          val asrDir: String,
    @SerialName("model_size")       val modelSize: String,
    val language: String?,          // null = Whisper 自动检测
    @SerialName("workflow_run_id")  val workflowRunId: String,
    @SerialName("chunk_duration_sec") val chunkDurationSec: Int = 300,
)
```

执行逻辑：
1. 枚举 `audioChunksDir` 下 `chunk_*.m4a`，按序排列
2. 若目录为空则返回 error，避免创建空队列
3. 若队列 Task 已存在则不重复创建（防重入）
4. 为每个 chunk 创建独立的 `TRANSCRIBE` task（含 `chunkIndex`、`totalChunks`、`chunkOffsetSec`、`coreStartSec`、`coreEndSec`）
5. 调用 `WorkflowRunService.repo.updateTotalTasks()` 更新总任务数
6. 无 `canSkip`（始终执行，幂等）

**ffmpeg / hw_accel**：本 Executor 不调用 ffmpeg，不需要处理设备差异。

#### 2.4.5 TranscribeExecutor 的 ASR chunk 处理

每个 `TRANSCRIBE` Task 处理一个 chunk，Payload 包含：

```kotlin
@Serializable
private data class Payload(
    @SerialName(“chunk_index”)      val chunkIndex: Int,
    @SerialName(“total_chunks”)     val totalChunks: Int,
    @SerialName(“chunk_offset_sec”) val chunkOffsetSec: Double,
    @SerialName(“core_start_sec”)   val coreStartSec: Double,
    @SerialName(“core_end_sec”)     val coreEndSec: Double,
    @SerialName(“audio_path”)       val audioPath: String,
    @SerialName(“model_size”)       val modelSize: String,
    val language: String?,
    @SerialName(“output_dir”)       val outputDir: String,
)
```

执行逻辑：
1. `canSkip`：检查 `chunk_XXXX.done` 是否存在，且 inputHash + modelSize + language 匹配
2. 获取 `GpuResourceLock`（优先级互斥队列，ASR 优先级 1-10），保证同一时刻只有一个 GPU 推理
3. 调用 Python `/audio/transcribe-chunk-task` WebSocket 长任务
4. Python 子进程直接写 `chunk_XXXX.srt` / `chunk_XXXX.done` / `chunk_XXXX.meta.json`
5. Kotlin 接收 `log/progress/error`，把进度写回 `task.progress`
6. 完成后调用 `tryMergeChunks()` 检查是否所有 chunk 都已完成

与 `docs/dev/task-model.md` 保持一致：
- **取消**：通过 `TaskCancelService` → WebSocket → Python `cancel_event`
- **暂停/恢复**：通过 `TaskPauseResumeService` → WebSocket → Python `resume_event`

#### 2.4.6 TranscribeExecutor.tryMergeChunks() 合并逻辑

合并逻辑内嵌在 `TranscribeExecutor` 中，无需独立的合并 Task。

**触发条件**：每个 TRANSCRIBE 完成后检查 `chunk_XXXX.done` 文件数量 == `totalChunks`

**合并过程**：
1. 按序读取 `chunk_0000.srt` → `chunk_0001.srt` → ... → `chunk_N.srt`
2. 对每个 chunk 的 segment 进行 **core region 过滤**：
   - 最后一个 chunk 用闭区间 `[coreStart, coreEnd]`
   - 其余 chunk 用右开区间 `[coreStart, coreEnd)`
   - 过滤掉重叠区域的重复 segment
3. 对保留的 segment 加时间偏移（`chunkOffsetSec`）
4. 拼接写入 `transcript.srt` + `transcript.meta.json` + `transcript.done`

#### 2.4.7 TranscribeExecutor 的定位

`TranscribeExecutor` **就是** ASR 工作流的核心执行单元：
- 每个 chunk 一个独立的 `TRANSCRIBE` Task
- `GpuResourceLock` 保证 GPU 推理串行，避免显存竞争
- `tryMergeChunks()` 内嵌合并逻辑，最后完成的 chunk 触发合并
- 天然支持 per-chunk 暂停/取消/重试

#### 2.4.8 ExtractAudioExecutor 与 ffmpeg 设备适配

`ExtractAudioExecutor` 调用 Python `/audio/extract-split-audio-task`，Python 侧已支持：
- `ffmpeg_path`：可选，默认 `find_best_ffmpeg().path`
- `hw_accel`：`cuda` / `d3d11va` / `qsv` / `""` / `cpu`

`MaterialAsrStartRoute` 创建 `EXTRACT_AUDIO` Task 时，payload 中传入：
```kotlin
kv("ffmpeg_path", config.ffmpegPath.ifBlank { resolveProbedFfmpegPath(config.ffmpegProbeJson) })
kv("hw_accel", resolveSelectedAccel(config.ffmpegHwAccel, config.ffmpegProbeJson))
```

> `resolveSelectedAccel` / `resolveProbedFfmpegPath` 与 `TranscodeMp4Executor` 中已有的同名私有函数逻辑相同，应提取到 `AppUtil` 或 `FfmpegUtil` 共享。

**注意**：`hw_accel` 在音频提取阶段仅加速视频流解码（`-hwaccel`），不影响音频编码质量。

#### 2.4.9 MaterialAsrStartRoute.kt（新建，commonMain）

```
POST /api/v1/MaterialAsrStartRoute
Body: {
  "material_id": "...",
  "model_size": "medium",
  "language": "auto",       // "auto" 转为 null 传给 TranscribeExecutor
  "disable_cache": false      // true 时忽略所有 canSkip / .done / hash，强制重跑
}
Response（成功）: { "workflow_run_id": "..." }
Response（已完成）: { "status": "already_done" }
Response（前置缺失）: { "error": "素材尚未完成下载/转码..." }
```

路由逻辑：
1. 若 `disable_cache=false`，检查 `transcript.done` → 已存在则返回 `already_done`
2. 检查 `transcode.done` / `extract_audio.done` → 均无则返回 error
3. 读取 `AppConfig`（ffmpeg 路径 + hw_accel + asrAllowDownload）
4. 生成 `workflowRunId` 及各 Task ID
5. 创建 `WorkflowRun`（初始 totalTasks 为 2，后续 `ASR_SPAWN_CHUNKS` 再 +N）
6. 创建 Task 链：`EXTRACT_AUDIO` → `ASR_SPAWN_CHUNKS`
7. 若 `disable_cache=true`，把该标记透传到所有相关 Task payload，使各 Executor 的 `canSkip()` 直接返回 false
8. N 个独立 `TRANSCRIBE` 任务由 `ASR_SPAWN_CHUNKS` 在运行时动态创建

#### 2.4.10 改动文件清单（后端）

| 文件 | 改动 |
|------|------|
| `shared/src/commonMain/.../apputil/AppUtil.kt` | 新增 `Paths.asrOutputDir()` |
| `shared/src/commonMain/.../api/routes/MaterialAsrStartRoute.kt` | **新建** |
| `shared/src/commonMain/.../api/routes/all_routes.kt` | 注册 `MaterialAsrStartRoute` |
| `shared/src/jvmMain/.../worker/executors/AsrSpawnChunksExecutor.kt` | **新建** |
| `shared/src/jvmMain/.../worker/executors/TranscribeExecutor.kt` | 扩展：ASR chunk 处理 + `tryMergeChunks()` 合并逻辑 |
| `shared/src/jvmMain/.../worker/service/TranscodeMp4Service.kt` | **新建**，提取转码复用逻辑 |
| `shared/src/jvmMain/...FredicaApi.jvm.kt` | 注册新 Executor |
| `shared/src/jvmMain/.../worker/executors/TranscodeMp4Executor.kt` | 改为调用 `TranscodeMp4Service` |

### 2.5 Python 侧 <Badge type=”tip” text=”已完成” />

**`/audio/extract-split-audio-task`** 已满足需求，产出 `asr_audio/` 目录下的 `chunk_XXXX.m4a` 文件和 `extract_audio.done`。

**`/audio/transcribe-chunk-task`** 采用”子进程内推理 + Python 直接落盘结果”模式：

1. **任何 faster-whisper 相关代码都只能在子进程中执行**
   - `from faster_whisper import WhisperModel` 只能出现在 subprocess worker 内
   - 模型加载、推理、分段遍历、异常捕获、模型下载补全判断都在子进程里做
   - FastAPI 主进程只负责收发 WebSocket 消息、监测子进程退出码、转发 cancel/pause/resume
2. **Kotlin 不再依赖逐条 `segment` 消息重建字幕**
   - 5 分钟一块的前提下，JVM 侧只需要日志 / 进度 / 错误
   - 每个 chunk 的 5 个产出文件由 Python 直接写到 `asr_results/{modelSize}/`
3. **每个 chunk 由独立的 TRANSCRIBE Task 调度，GpuResourceLock 保证 GPU 串行**
   - 这样既满足”同一时刻只有一个 GPU 推理”，也保留了”主进程不被 faster-whisper 污染”的隔离层

**每个 chunk 产出 5 个文件**（以 `chunk_0003` 为例）：

```json
// chunk_0003.start_info.json — 转录启动信息
{
  “started_at”: “2026-04-13T00:53:12.9924792”,
  “audio_path”: “C:\\...\\asr_audio\\chunk_0003.m4a”,
  “model_size”: “medium”,
  “language”: “zh”,
  “compute_type”: “float32”
}
```

```jsonl
// chunk_0003.jsonl — 逐 segment JSONL，每行一条
{“start”:900.0,”end”:903.2,”text”:”这个是一个非常重要的观点”}
{“start”:903.2,”end”:908.8,”text”:”就是说整个消费结构在发生变化”}
```

```srt
// chunk_0003.srt — 标准 SRT 格式（Python 子进程直接落盘）
1
00:15:00,000 --> 00:15:03,200
这个是一个非常重要的观点

2
00:15:03,200 --> 00:15:08,800
就是说整个消费结构在发生变化
```

```json
// chunk_0003.meta.json — 元信息
{
  “language”: “zh”,
  “model_size”: “medium”,
  “segment_count”: 75,
  “completed_at”: “2026-04-13T00:55:53.9362714”,
  “partial”: false,
  “core_start_sec”: 900.0,
  “core_end_sec”: 1200.0
}
```

```json
// chunk_0003.done — 完成标记（canSkip 判断依据）
{
  “input_hash”: “4e65977a...”,
  “output_hash”: “7f483a7b...”,
  “model_size”: “medium”,
  “language”: “zh”
}
```

**WebSocket 消息收敛**：
- 保留：`log` / `progress` / `done` / `error`
- `done` 只需返回简短摘要（如 `language`、`segment_count`、`output_path`）
- 不再要求把全部 `segments` 通过 WebSocket 回传给 Kotlin

**单 chunk 进度分配**：

每个 chunk 的 `progress` 值（0–100）按以下方式分配：

| 阶段 | 进度范围 | 说明 |
|------|----------|------|
| 模型加载 | 0 → 15 | `WhisperModel(...)` 完成后推送 `{“type”:”progress”,”progress”:15}` |
| 转录 | 15 → 100 | 按 `seg.end / duration` 线性映射到 15–100 |

模型加载阶段若触发在线下载（`allow_download=True`），进度在 0 停留直到下载完成，随后跳至 15。
Kotlin 侧直接将 Python 推送的 `progress` 值写入 `task.progress`，无需额外换算。

**崩溃检测要求**：
- 若子进程异常退出、被底层 C++ 崩溃带走、或因 GPU / DLL 冲突直接退出，父进程必须把它统一转为 `error` 消息
- 日志里要能区分：正常异常 / cancel / 子进程崩溃

`TranscribeExecutor.tryMergeChunks()` 只读磁盘上的 `chunk_XXXX.srt` / `chunk_XXXX.done`，不依赖 JVM 内存态消息。

### 2.6 前端实现 <Badge type="tip" text="已完成" />

#### 2.6.1 ASR 方案页面 UI

```
┌──────────────────────────────────────────────────┐
│  [平台字幕]  [ASR Whisper ✓]  [嵌入字幕]  [OCR]  │
├──────────────────────────────────────────────────┤
│  模型                                             │
│  ○ tiny（最快）  ○ base  ● medium（推荐）         │
│  ○ small  ○ large-v3（最准）                     │
│                                                  │
│  语言  [自动检测 ▼]                               │
│                                                  │
│  [ ] 强制重跑（disable_cache）                    │
│                                                  │
│  [开始转录]                                       │
│                                                  │
│  ── 进行中 ───────────────────────────────────   │
│  ▓▓▓▓▓▓░░░░ 60%  正在转录 chunk 3/5            │
│  [取消]                                          │
│                                                  │
│  ── 已有结果 ──────────────────────────────────  │
│  medium / 自动检测（zh）  完成于 2026-04-01      │
│  [查看字幕]  [导出 SRT]                          │
└──────────────────────────────────────────────────┘
```

**状态机**：
- `idle`：展示配置 + 历史结果列表
- `starting`：调用 `MaterialAsrStartRoute`，防抖禁用按钮
- `running`：轮询 `WorkerTaskListRoute`（每 3s），聚合 WorkflowRun 进度
- `done`：刷新字幕列表，显示"导出 SRT"

#### 2.6.2 进度聚合

轮询 `WorkerTaskListRoute?workflow_run_id=xxx`，展示：
- 整体百分比：`workflowRun.done_tasks / workflowRun.total_tasks`
- 当前正在执行的 Task 类型（EXTRACT_AUDIO / ASR_SPAWN_CHUNKS / TRANSCRIBE）
- `TRANSCRIBE` 阶段：`已完成 X / 共 N 块`

#### 2.6.3 "导出 SRT" 入口

ASR 结果的 SRT 文件（`transcript.srt`）存在磁盘，需通过 API 下载。两个方案：

**方案 A（推荐）**：新增 `MaterialAsrTranscriptRoute`，返回 `transcript.srt` 内容，前端调用后用 `downloadSrt()` 下载。

**方案 B**：直接在 `MaterialSubtitleListRoute` 返回的字幕项中标记 `source=asr_whisper`，前端通过 `MaterialSubtitleContentRoute` 获取内容，再调 `downloadSrt()`。方案 B 不需新增路由，但当前 `MaterialSubtitleContentRoute` 返回纯文本而非 SRT 格式，需扩展。

建议选**方案 B**，统一字幕数据流，`TranscribeExecutor.tryMergeChunks()` 写 `transcript.srt` 时同时存储 SRT 内容（或存路径），`MaterialSubtitleContentRoute` 扩展返回 `srt_content` 字段。

#### 2.6.4 前端文件清单

| 文件 | 改动 |
|------|------|
| `app/routes/material.$materialId.subtitle.tsx` | ASR 方案 UI（模型/语言选择、启动、进度、历史结果） |
| `app/util/subtitleExport.ts` | **新建**（见第一章） |
| `app/util/asr.ts` | **新建**：`startAsrTask()` 封装 |

### 2.7 已有转译结果浏览和管理页面 <Badge type="warning" text="未完成" />

本节补充一个**仅对使用 jsBridge 方案的服主用户开放**的结果管理页，用于查看本机素材目录下已经完成的 ASR / 平台字幕转译结果，并执行删除、重新导入、打开目录等管理操作。

> **权限边界**：
> - **开放对象**：桌面端 / WebView 内、具备 `callBridge()` 能力的服主用户
> - **不开放对象**：仅通过 route API 访问的普通用户、浏览器直连用户
> - **原因**：该页面涉及本地文件系统浏览、删除、目录打开等宿主机能力，不应暴露为通用 HTTP Route API

#### 2.7.1 目标与范围

页面目标：
1. 浏览某个素材已存在的字幕 / ASR 结果
2. 查看结果元信息（来源、模型、语言、完成时间、文件大小、是否有 done 标记）
3. 删除单个结果目录（如 `asr_results/tiny/`）
4. 打开结果所在目录
5. 重新导入某个 `transcript.srt` 到字幕库（当数据库记录丢失但磁盘文件还在时）

**本页不负责**：
- 启动新任务（仍在素材字幕页中操作）
- 对普通 routeApi 用户开放文件系统能力
- 提供跨素材的全局批量删除（先只做单素材范围）

#### 2.7.2 页面入口与可见性

这里需要改成**全局资源管理入口**，而不是挂在素材字幕页下。原因是：用户通常已经在浏览器页面内浏览素材，若每个素材页单独加"管理本地转译结果"入口，会把"任务启动页"和"本地文件系统巡检页"混在一起，也不利于做跨素材遍历。

建议的页面层级：

```
app-desktop-home
  ↓ 入口按钮：本地资源管理
local-resources
  ↓ 子入口：ASR 资源
local-resources/asr
  ↓ 列出所有素材的 ASR 结果（按 materialId 分组）
local-resources/asr/:resultKey
  ↓ 具体结果页面（查看元信息 / 打开目录 / 删除 / 重导入）
```

#### 入口 1：`app-desktop-home` 统一入口

仅在桌面端 / bridge 可用时，在 `app-desktop-home` 放置统一按钮：

```tsx
{bridgeAvailable && (
  <Button
    variant="outline"
    onClick={() => openInternalUrl("/local-resources")}
  >
    本地资源管理
  </Button>
)}
```

#### 入口 2：`local-resources` 页面

该页只负责做本地资源分类导航，例如：
- ASR 资源
- 视频缓存
- 音频切块缓存
- 未来可扩展的 OCR / 嵌入字幕 / 中间文件

本次先实现：
- `local-resources/asr`

#### 入口 3：`local-resources/asr` 页面

这个页面负责：
1. 遍历本地 `media/` 目录下所有素材目录
2. 查找每个素材的 `asr/` 子目录
3. 枚举其中所有 `{model}_{language}` 结果目录
4. 以"素材分组 + 结果卡片"的方式列出
5. 点击进入具体结果页

#### 入口 4：具体结果页

新页面可改为：
- `fredica-webui/app/routes/local-resources.tsx`
- `fredica-webui/app/routes/local-resources.asr.tsx`
- `fredica-webui/app/routes/local-resources.asr.$resultKey.tsx`

其中：
- `resultKey` 不直接暴露原始绝对路径
- 可采用受控编码，例如：`materialId__model_language` 或 bridge 返回的稳定 ID

若 bridge 不可用：
- `app-desktop-home` 不显示"本地资源管理"入口
- 用户手动访问 `/local-resources*` 时，页面显示"该页面仅在桌面端可用"
- 不尝试回退到 Route API

#### 2.7.3 为什么使用 jsBridge 而不是 Route API

本页涉及以下能力：
- 枚举 `materialMediaDir(materialId)` 下的本地目录与文件
- 删除本地结果目录
- 打开系统文件夹
- 读取 `transcript.done` / `transcript.srt` / `chunk_*.done` 的文件元信息

这些都属于**宿主机本地文件系统权限**，若通过 Route API 开放，则：
- HTTP 身份边界会被放大为"任何拿到 token 的客户端都可操作本地文件"
- 普通网页端用户理论上能间接操纵桌面端文件系统
- 容易引入目录遍历 / 越权删除风险

因此本页采用：
- **React 页面负责 UI**
- **Kotlin JsBridge Handler 负责本地文件系统操作**
- 不注册对应 Route API

#### 2.7.4 数据模型设计

Bridge 返回的结果项建议统一为：

```typescript
export interface MaterialSubtitleResultItem {
  id: string;                  // 稳定 ID，例如 "asr:BVxxx:tiny"
  kind: "asr" | "platform";
  source: string;              // "faster-whisper" / "bilibili"
  label: string;               // UI 展示名："ASR / tiny / zh"
  materialId: string;
  materialTitle?: string;      // 便于全局列表中展示素材标题
  language: string;
  modelSize?: string;          // 仅 asr
  resultDir?: string;          // 本地目录绝对路径，仅 bridge 页面可见
  transcriptPath?: string;     // transcript.srt 绝对路径
  donePath?: string;           // transcript.done 或其他 done 标记
  completedAt?: number;        // unix sec
  detectedLanguage?: string;   // transcript.meta.json 内字段
  exists: boolean;             // 目录 / 文件是否存在
  hasDoneMarker: boolean;
  importedToDb: boolean;       // 数据库是否已有对应 subtitle item
  fileSizeBytes?: number;      // transcript.srt 大小
  chunkCount?: number;         // 仅 asr
}
```

因为 `local-resources/asr` 需要做**跨素材遍历**，所以结果项必须自带 `materialId`，最好附带 `materialTitle` 便于分组展示。

#### 2.7.5 Bridge 能力设计

建议新增以下 jsBridge 方法：

##### A. `list_all_asr_results`

```json
{}
```

返回：
```json
{
  "items": [
    {
      "id": "asr:BV1xx411c7mD_p1:tiny",
      "kind": "asr",
      "source": "faster-whisper",
      "label": "ASR / tiny / zh",
      "materialId": "BV1xx411c7mD_p1",
      "materialTitle": "某视频标题",
      "language": "zh",
      "modelSize": "tiny",
      "resultDir": "C:/.../media/BV1xx411c7mD_p1/asr_results/tiny",
      "transcriptPath": "C:/.../media/BV1xx411c7mD_p1/asr_results/tiny/transcript.srt",
      "donePath": "C:/.../media/BV1xx411c7mD_p1/asr_results/tiny/transcript.done",
      "completedAt": 1712300000,
      "detectedLanguage": "zh",
      "exists": true,
      "hasDoneMarker": true,
      "importedToDb": true,
      "fileSizeBytes": 12345,
      "chunkCount": 7
    }
  ]
}
```

Kotlin 侧逻辑：
1. 遍历 `AppUtil.Paths.appDataDir/media/` 下所有素材目录
2. 检查每个素材目录是否存在 `asr_results/`
3. 枚举各 `{model_size}` 子目录
4. 检查 `transcript.srt`、`transcript.done`、`chunk_*.done`
5. 查询 `MaterialSubtitleDb` 看数据库中是否已有对应记录
6. 拼装结构化结果并按 `materialId` 聚合给前端展示

##### B. `get_asr_result_detail`

```json
{
  "result_id": "asr:BV1xx411c7mD_p1:tiny"
}
```

行为：
- 根据受控 `result_id` 解析出 `material_id + result_dir_key`
- 返回该结果的完整元信息
- 可选返回 `transcriptPreview`（前几百字符），方便详情页首屏展示

##### C. `delete_asr_result`

```json
{
  "result_id": "asr:BV1xx411c7mD_p1:tiny"
}
```

行为：
- 仅允许删除 bridge 层解析得到的合法结果目录
- **禁止前端直接传绝对路径并删除**
- 删除前二次确认
- 删除范围：
  - `{materialMediaDir}/asr_results/{model_size}/` 整个目录
  - 可选：同时删除数据库中的对应 subtitle 记录

返回：
```json
{ "ok": true }
```

##### D. `open_asr_result_dir`

```json
{
  "result_id": "asr:BV1xx411c7mD_p1:tiny"
}
```

行为：
- Kotlin 侧解析合法目录后，调用桌面宿主打开该目录
- 仅桌面端可用

##### E. `reimport_asr_result`

```json
{
  "result_id": "asr:BV1xx411c7mD_p1:tiny"
}
```

行为：
1. 读取该结果的 `transcript.srt`
2. 若数据库中记录缺失，则重建一条 `MaterialSubtitle` 记录
3. 标记来源为 `asr_whisper`
4. 供前端刷新列表后继续查看/导出

#### 2.7.6 Kotlin 侧安全边界

为避免 bridge 层变成任意文件操作入口，需遵守：

1. **只接受 `result_id`（或由 bridge 内部解析出的受控 key），不接受前端传绝对路径**
2. `result_id` 必须满足受控格式：
   - `asr:{materialId}:{model_size}`
   - 后续若扩展平台字幕，也需固定前缀格式
3. Kotlin 侧重新拼接目标目录并校验：
   - 目标路径必须位于 `materialMediaDir(materialId)` 下
   - `normalize()` 后再检查 `startsWith(baseDir)`，防目录穿越
4. 删除前检查：
   - 目录确实存在
   - 是预期结构（含 `transcript.srt` 或 `transcript.done`）
   - 非空但不匹配预期结构时拒绝删除
5. bridge 返回错误统一为 `{ "error": "..." }`

建议新增 Kotlin 数据类：
- `MaterialSubtitleResultItem`
- `ListAllAsrResultsResponse`
- `GetAsrResultDetailResponse`
- `DeleteAsrResultRequest`
- `ReimportAsrResultRequest`

#### 2.7.7 页面 UI 设计

页面拆成三层：

##### A. `local-resources` 资源首页

```
┌─ 本地资源管理 ─────────────────────────────────────────────┐
│ [ASR 资源]  [视频缓存]  [音频切块缓存]  [更多待扩展]        │
└───────────────────────────────────────────────────────────┘
```

##### B. `local-resources/asr` ASR 资源列表页

按素材分组，列出所有本地 ASR 结果：

```
┌─ 本地资源 / ASR ──────────────────────────────────────────┐
│ [刷新]                                                    │
│                                                           │
│ 素材：BV1xx411c7mD_p1  某视频标题                          │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ ASR / tiny / zh                                     │   │
│ │ 完成：2026-04-01 12:30   文件：12 KB   chunk: 7     │   │
│ │ 状态：已导入数据库                                   │   │
│ │ [查看详情] [打开目录]                                │   │
│ └─────────────────────────────────────────────────────┘   │
│                                                           │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ ASR / medium / auto（检测到 zh）                    │   │
│ │ 状态：目录存在但结果不完整                            │   │
│ │ [查看详情] [打开目录]                                │   │
│ └─────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

##### C. `local-resources/asr/:resultKey` 具体结果页

```
┌─ ASR 结果详情 ────────────────────────────────────────────┐
│ 素材：BV...                                              │
│ 模型：tiny   语言：zh   来源：faster-whisper             │
│ 完成：2026-04-01 12:30                                   │
│ 文件：transcript.srt (12 KB)  chunk: 7                   │
│ 状态：已导入数据库                                       │
│                                                           │
│ [查看内容] [导出 SRT] [重新导入] [打开目录] [删除]        │
└───────────────────────────────────────────────────────────┘
```

建议分三类 badge：
- **完整**：`exists && hasDoneMarker && transcriptPath exists`
- **仅磁盘存在未导入**：`exists && !importedToDb`
- **残留/损坏**：目录存在但缺 done / transcript

#### 2.7.8 前端交互细节

前端工具建议新增：

```typescript
export async function listAllAsrResults() {
  return callBridge("list_all_asr_results", {});
}

export async function getAsrResultDetail(resultId: string) {
  return callBridge("get_asr_result_detail", { result_id: resultId });
}

export async function deleteAsrResult(resultId: string) {
  return callBridge("delete_asr_result", { result_id: resultId });
}

export async function reimportAsrResult(resultId: string) {
  return callBridge("reimport_asr_result", { result_id: resultId });
}
```

页面状态机：
- `local-resources`：纯导航页
- `local-resources/asr`：`loading` / `ready` / `error`
- `local-resources/asr/:resultKey`：`loading` / `ready` / `action_running` / `error`

删除按钮交互：
1. 在详情页点击删除
2. `window.confirm("确认删除该 ASR 结果？此操作会删除本地结果目录。")`
3. 调 bridge
4. 成功后 toast + 返回 ASR 资源列表页并刷新

#### 2.7.9 与现有字幕页的关系

该页面不是替代 `material.$materialId.subtitle.tsx`，而是补充：

- **字幕页**：偏任务启动、查看、导出
- **本地资源页**：偏跨素材盘点、修复、清理
- **ASR 详情页**：偏单个结果的文件级管理

两页可复用同一个 `subtitleExport.ts`：
- 若 bridge 直接返回 `transcript.srt` 文本，可沿用下载逻辑
- 若只返回文件路径，则仍建议在 bridge 层提供 `read_material_subtitle_result_text`，避免前端处理本地路径

#### 2.7.10 改动文件清单

| 文件 | 改动 |
|------|------|
| `fredica-webui/app/routes/app-desktop-home.tsx` | 新增"本地资源管理"入口（仅 bridge 可用） |
| `fredica-webui/app/routes/local-resources.tsx` | **新建**：本地资源管理首页 |
| `fredica-webui/app/routes/local-resources.asr.tsx` | **新建**：跨素材 ASR 结果列表页 |
| `fredica-webui/app/routes/local-resources.asr.$resultKey.tsx` | **新建**：ASR 结果详情页 |
| `fredica-webui/app/util/asr.ts` | 新增 bridge 调用封装 |
| `shared/src/commonMain/.../jsbridge/...` | **新建/扩展**：全局列表、详情、删除、重导入、打开目录 handler |
| `composeApp` bridge 注册处 | 注册新 jsBridge 方法 |

---

### 2.8 ASR 配置页 UX 改进（Phase 1.5 补充） <Badge type="tip" text="已完成" />

本节记录对 Phase 1.5 已有 ASR 入口的 5 项 UX 与功能改进，均集中在 `material.$materialId.subtitle.tsx` 及相关后端。

#### 2.8.1 模态框高度溢出（Issue 1）

**问题**：17 个模型选项撑破屏幕，无法滚动。

**方案**：
- 模型列表区域改为固定高度 + 滚动容器：`<div className="max-h-48 overflow-y-auto space-y-1 pr-1">`
- 整个 modal 内容区加 `overflow-y-auto max-h-[80vh]`，防止极小屏幕下其他区域也溢出

#### 2.8.2 torch 未安装时的行为修正（Issue 2）

**问题**：torch 未安装时仍显示"推荐"tag、默认选中 `small`，且后端不校验空 model。

**前端改动**：
1. `parseCompatJson`：`torch_missing` 时 `recommended` 改为 `undefined`（不再返回 `"small"`）
2. `AsrSchemeDetail`：`model` 初始值改为 `""`（`useState("")`）
3. `openModal`：加载 compat 后，只有 `parsed.recommended_model` 非空时才 `setModel`
4. `AsrModelPickerModal` footer 的"开始识别"按钮：`disabled={!model}` 时禁用并加 `title="请先选择模型"`
5. `AsrModelPickerModal` 中 `isRecommended` 判断：`torch_missing` 时不渲染"推荐"tag（`!compat.torch_missing && isRecommended`）

**后端校验**（`MaterialWorkflowRoute.kt`）：

```kotlin
// whisper_transcribe 分支，在调用 startWhisperTranscribe 前
if (p.model.isNullOrBlank()) return buildValidJson { kv("error", "MODEL_REQUIRED") }
```

#### 2.8.3 LLM 语言猜测（Issue 3）

**目标**：页面加载时用 LLM 根据素材标题/简介猜测视频语言，自动填入语言下拉框。

**后端：新建 `MaterialLanguageGuessRoute.kt`**（commonMain）

- GET `/api/v1/MaterialLanguageGuessRoute?material_id=...`
- 从 `AppConfigService.repo.getConfig()` 取 `llmModelsJson`，找第一个可用模型（`models.firstOrNull()`）
- 若无模型配置，返回 `{"language": null}`
- 构造 prompt：用 material 的 `title` + `description`（截断到 500 字）请求 LLM 猜测语言
  - prompt：`"以下是一个视频的标题和简介，请判断视频的主要语言，只返回 ISO 639-1 语言代码（如 zh、en、ja），不要解释。\n标题：{title}\n简介：{description}"`
- 调用 `LlmRequestServiceHolder.instance.streamRequest(...)` 收集完整输出
- 解析输出，提取 2 字母语言代码（`Regex("[a-z]{2}")`）
- 结果缓存：`ConcurrentHashMap<String, String?>` 进程级内存缓存（重启清空）
- 返回 `{"language": "zh"}` 或 `{"language": null}`

**注册**：在 `all_routes.kt` 字母序位置（`MaterialGetRoute` 后、`MaterialListRoute` 前）加入。

**前端：`AsrSchemeDetail` 加语言猜测**

新增状态：

```ts
const [langGuessState, setLangGuessState] = useState<
  "idle" | "loading" | { lang: string; label: string } | "failed"
>("idle");
```

mount 时调用（`useEffect`）：

```ts
apiFetch<{ language: string | null }>(
  `/api/v1/MaterialLanguageGuessRoute?material_id=${encodeURIComponent(materialId)}`,
  { method: "GET" }, { silent: true }
).then(({ data }) => {
  if (!data?.language) { setLangGuessState("failed"); return; }
  const label = WHISPER_LANGUAGES.find(l => l.value === data.language)?.label ?? data.language;
  setLangGuessState({ lang: data.language, label });
  if (!language) setLanguage(data.language);  // 只在当前为空时自动设置
});
```

语言下拉框旁展示提示：
- `loading`：`<Loader className="animate-spin" /> LLM 正在猜测语言…`
- `{ lang, label }`：`✓ LLM 猜测为「{label}」`（灰色小字）
- `failed` / `idle`：不显示

#### 2.8.4 语言检测后自动选择模型（Issue 4）

**逻辑**：语言猜测完成后，若 `model === ""`（当前未选），调用 `pickDefaultModel(lang, compat)` 自动选模型。

`pickDefaultModel(lang, compat)` 规则（封装在 `asrConfig.ts`）：
- `torch_missing`：
  - 非英语 → `"large-v2"`
  - 英语 → `"distil-large-v2"`
- torch 正常：
  - 非英语 → `compat.models.filter(m => m.ok && !m.isEnOnly).at(-1)?.name ?? "large-v2"`
  - 英语 → `compat.models.filter(m => m.ok).at(-1)?.name ?? "distil-large-v2"`

在 `AsrSchemeDetail` 中，语言猜测完成后：

```ts
if (!model && compatInfo) {
  const compat = parseCompatJson(compatInfo.compat_json);
  const picked = pickDefaultModel(detectedLang, compat);
  if (picked) setModel(picked);
}
```

#### 2.8.5 业务逻辑封装到工具函数（Issue 5）

**新建文件**：`fredica-webui/app/util/asrConfig.ts`

从 `subtitle.tsx` 中抽出以下内容：

```ts
// 常量
export const ALL_WHISPER_MODELS: WhisperModelInfo[]
export const WHISPER_MODEL_VRAM_HINT: Record<string, string>
export const WHISPER_LANGUAGES: { value: string; label: string }[]

// 工具函数
export function isEnglishLang(lang: string): boolean
export function parseCompatJson(compatJson: string): WhisperCompatParsed
export function pickDefaultModel(lang: string, compat: WhisperCompatParsed): string | null
```

`subtitle.tsx` 改为从 `~/util/asrConfig` import 这些函数和常量。

#### 2.8.6 改动文件清单

| 文件 | 改动 |
|------|------|
| `fredica-webui/app/routes/material.$materialId.subtitle.tsx` | Issues 1-4 前端部分 |
| `fredica-webui/app/util/asrConfig.ts` | **新建**：常量 + 工具函数（Issue 5） |
| `shared/src/commonMain/.../api/routes/MaterialLanguageGuessRoute.kt` | **新建**（Issue 3 后端） |
| `shared/src/commonMain/.../api/routes/all_routes.kt` | 注册 `MaterialLanguageGuessRoute` |
| `shared/src/commonMain/.../api/routes/MaterialWorkflowRoute.kt` | 空 model 校验（Issue 2 后端） |

---

## 三、实施顺序总览 <Badge type="info" text="参考" />

这一章只保留**实现阶段、依赖关系与交付边界**；测试范围总览见 **第五章**，详细 TDD 样例统一放到 **第九章《阶段开发与测试》**。

| Phase | 目标 | 关键产物 | 依赖 | 本阶段边界 |
|------|------|---------|------|-----------|
| 1 | 字幕导出 | `subtitleExport.ts` + Bilibili 字幕页导出按钮 | 无 | 只做前端 SRT 转换与下载，不引入后端改动 <Badge type="tip" text="已完成" /> |
| 1.5 | ASR 工作流最小闭环 + UX 改进 | `MaterialWorkflowService.startWhisperTranscribe()` + `MaterialWorkflowRoute` whisper_transcribe 模板 + `material.$materialId.subtitle.tsx` ASR 入口；`asrConfig.ts` + `MaterialLanguageGuessRoute` + 模态框滚动 + torch 缺失行为修正 + LLM 语言猜测 + 自动选模型 | 1 | 最小线性流程：EXTRACT_AUDIO → TRANSCRIBE（仅 chunk_0000.m4a）；前端可启动并展示进度 <Badge type="tip" text="已完成" />；UX 改进见 §2.8（进行中） |
| 2 | 基础设施 | `AppUtil.Paths.asrOutputDir()`、锁文件路径、`file_lock_util.py`、`TranscodeMp4Service` | 无 | 先把路径、锁、转码复用层稳定下来 <Badge type="tip" text="已完成" /> |
| 3 | Python 子进程与模型检查 | `transcribe.py` 落盘语义、下载 hook、`asr_model_check.py` | 2 | 先解决模型加载、下载补全、进度 hook <Badge type="tip" text="已完成" /> |
| 4 | 队列编排 | N×`TRANSCRIBE` Task + `TranscribeExecutor.tryMergeChunks()` | 2,3 | 落地顺序转录、chunk 级缓存、暂停/恢复/取消 <Badge type="tip" text="已完成" /> |
| 5 | 合并与写库 | `TranscribeExecutor.tryMergeChunks()` | 4 | chunk JSON → SRT 合并在 TranscribeExecutor 内完成 <Badge type="tip" text="已完成" /> |
| 6 | 启动 API 与工作流收口 | `MaterialWorkflowRoute` whisper_transcribe 模板 + `FasterWhisperInstallService` | 3,4,5 | 把完整工作流真正串起来 <Badge type="tip" text="已完成" /> |
| 7 | 前端 ASR 页面 | `asrConfig.ts` + `subtitle.tsx` ASR UI + `AsrModelPickerModal` | 6 | 展示配置、进度、历史结果 <Badge type="tip" text="已完成" /> |
| 8 | 本地资源管理页 | `local-resources*` + jsBridge handlers | 5,6,7 | **最后开发**，集中处理跨素材与 bridge 文件系统能力 |
| 9 | ASR 配置管理页面 | `AsrConfigService` + 桌面设置页 + Bridge Handlers | 7 | 服主权限管理（下载开关、模型白名单）；详见 §10 |
| 10 | 模型预下载与测试 | `AsrModelTestService` + Python `/asr/test-model-task` | 9 | 自定义音频、多模型选择、可配推理波数；详见 §11 |
| 11 | 优先级 GPU 资源锁 | `GpuResourceLock` 优先级队列重写 | 无 | 0-20 优先级 + 时间序；详见 §12 |

**并行建议**：
- Phase 1 ~ 7 已全部完成 <Badge type="tip" text="已完成" />
- Phase 9 依赖 Phase 7（ASR 前端页面已稳定）
- Phase 10 依赖 Phase 9（配置服务就绪后才能做测试）
- Phase 11 独立于 Phase 9/10，可并行开发
- **Phase 8 建议最后开发**：这是跨页面、跨素材、跨 bridge / file-system 边界的整理型模块，依赖前面各阶段先把结果目录结构、字幕写库、ASR 元信息、bridge 基础能力稳定下来；否则很容易边做边返工

---

## 四、关键设计决策 <Badge type="info" text="参考" />

### 4.1 为什么每个模型+语言组合独立缓存？

不同模型精度差异大，用户可能对同一素材跑多次（先 tiny 预览，再 large-v3 精转）。独立缓存避免重新转录，也允许用户比较不同结果。

### 4.2 为什么最终采用 N×独立 `TRANSCRIBE` Task + `GpuResourceLock`？

最终实现采用了 **N×独立 `TRANSCRIBE` Task** 方案：

- **`GpuResourceLock` 优先级队列**已解决 GPU 串行问题：Semaphore(1) 互斥锁 + 优先级 1-10，保证同一时刻只有一个 GPU 推理任务运行
- **独立 Task 天然支持 per-chunk 暂停/取消/重试**：无需在单个队列 Task 内实现复杂的 chunk 级控制
- **`tryMergeChunks()` 内嵌合并**：最后完成的 TRANSCRIBE 触发合并，避免额外 Task 开销
- **中途可预览**：每完成一个 chunk 就落盘 `chunk_XXXX.srt`；即使部分 chunk 取消，已完成结果依然可读

### 4.3 为什么需要 ASR_SPAWN_CHUNKS 而不在启动 API 里创建所有 Task？

chunk 数量在 `EXTRACT_AUDIO` 完成前未知。若在启动时硬编码占位 Task，会导致 `totalTasks` 错误且逻辑复杂。`ASR_SPAWN_CHUNKS` 作为"动态创建器"在运行时读取实际 chunk 文件，优雅地解决了这个问题。

### 4.4 SRT 合并时间偏移

`ExtractAudioExecutor` 使用 FFmpeg `-segment_time 300 -reset_timestamps 1`，每个 chunk 的时间戳**从 0 重置**。合并时必须对第 N 块（从 0 计）的所有 segment 加 `N * 300s` 偏移。

### 4.5 `language="auto"` 的处理

启动 API 接收 `"auto"` 字符串，创建 Task 时传 `language=null`（`TranscribeExecutor` 已有 `if (payload.language != null)` 判断）。检测到的语言记录在各 chunk 的 `chunk_XXXX.meta.json` 的 `language` 字段中，合并后写入 `transcript.meta.json`；同时 `chunk_XXXX.done` 和 `transcript.done` 的 hash 信息供后续 `canSkip` 校验。

### 4.6 ffmpeg 设备适配

`ExtractAudioExecutor` 的 payload 中传入：
- `ffmpeg_path`：从 `AppConfig.ffmpegProbeJson["path"]` 读取（与 `TranscodeMp4Executor` 相同逻辑）
- `hw_accel`：从 `AppConfig.ffmpegProbeJson["selected_accel"]` 读取

Python `extract-split-audio-task` 的 `hw_accel` 仅影响视频流解码（`-hwaccel` 参数），`-vn` 丢弃视频流后不参与输出，因此对音频质量无影响，但可减少 CPU 压力。

不同设备支持情况：

| 平台 | 常见 selected_accel | 备注 |
|------|---------------------|------|
| NVIDIA GPU | `cuda` | NVDEC 解码 |
| AMD GPU（Windows） | `amf` / `d3d11va` | 解码用 d3d11va |
| Intel 核显 | `qsv` | Intel Quick Sync |
| macOS | `videotoolbox` | Apple Silicon / Intel Mac |
| 无 GPU / 兜底 | `cpu` | 不传 `-hwaccel` |

### 4.7 为什么 `.done` 文件应统一带 hash / fingerprint？

仅靠 `*.done` 文件存在与否，无法判断：
- 输出文件是否被用户手动改动
- 输入文件是否变化
- 缓存是否对应当前这一轮的真实产物

因此本方案建议在所有慢任务中统一采用：
- **存在 `.done`** → 表示“曾经完成过”
- **`.done` 内 hash / fingerprint 仍匹配** → 才表示“现在仍可安全跳过”

推荐映射：

| 环节 | `.done` 应记录的关键指纹 |
|------|------------------------|
| `TRANSCODE_MP4` | 源视频文件指纹 + `video.mp4` 指纹 + ffmpeg 参数摘要 |
| `EXTRACT_AUDIO` | `video.mp4` 指纹 + chunk 清单摘要 + 切块参数摘要 |
| `DOWNLOAD_WHISPER_MODEL` / 模型验证 | 模型关键文件指纹 + 选中的 `device/compute_type` |
| `TRANSCRIBE`（tryMergeChunks） | chunk SRT 清单摘要 + `transcript.srt` 指纹 |

这样 `canSkip()` 才能从“布尔标记”升级为“基于指纹的可复用判断”。

同时应提供统一逃生阀：
- `disable_cache=true` 时，所有相关 `canSkip()` 都必须失效
- 这对两类场景尤其重要：
  1. **用户主动要求更新结果**（例如想重新跑更干净的一轮）
  2. **开发者 / 测试运行**（希望覆盖真实执行路径，而不是命中旧缓存）

因此推荐把 `disable_cache` 透传到：
- `MaterialAsrStartRoute`
- `DOWNLOAD_WHISPER_MODEL`
- `TRANSCODE_MP4`
- `EXTRACT_AUDIO`
- `TRANSCRIBE`（每个 chunk 独立 Task，canSkip 检查 `chunk_XXXX.done + hash`）

---

## 五、测试策略总览 <Badge type="info" text="参考" />

本章只保留**测试范围与责任分层**；详细 TDD 顺序、样例代码与运行命令统一收敛到 **第九章《阶段开发与测试》**。

### 5.1 前端测试范围
- `subtitleExport.ts`：SRT 时间格式、边界值、空数据
- `asr.ts`：启动任务、阶段标签、整体进度聚合
- `local-resources*`：桌面端可见性、跨素材列表、bridge 错误处理

### 5.2 Kotlin / JVM 测试范围
- `AsrSpawnChunksExecutor`：枚举 chunk、创建 N 个独立 TRANSCRIBE Task、更新 `totalTasks`
- `TranscribeExecutor`（ASR chunk 处理）：per-chunk 执行、GpuResourceLock 串行、canSkip、暂停/恢复/取消
- `TranscribeExecutor.tryMergeChunks()`：core region 过滤、时间偏移合并、`transcript.done + hash`、原子输出
- `MaterialAsrStartRoute`：启动条件、`already_done`、`disable_cache`、任务链创建
- `DownloadWhisperModelExecutor.canSkip`：`.ready.done + hash` 与 Python 检查接口联动
- jsBridge handler：受控删除、目录穿越防护、重导入

### 5.3 Python 测试范围
- `file_lock_util.py`：锁互斥、异常释放
- `asr_model_check.py`：两阶段真实加载、错误分类、下载可恢复性
- `download_model.py`：HuggingFace monkey-patch 进度上报
- `transcribe.py`：子进程隔离、chunk 落盘、取消/暂停后清理语义

### 5.4 分层原则
- **前端**：验证 UI 状态机、展示文案、bridge / route 调用参数，不验证底层 Python 推理
- **Kotlin / JVM**：验证 DAG 编排、Task 状态推进、`canSkip`、pause/resume/cancel 语义，不验证 faster-whisper 本身
- **Python**：验证模型加载、下载、文件锁、子进程落盘与清理，不验证前端 UI

### 5.5 取消 / 暂停 / 清理专项验证
- `EXTRACT_AUDIO`：取消后不写 `.done`，临时文件清理正确
- `TRANSCRIBE`（每个 chunk 独立 Task）：
  - pause 后暂停当前 Python 子进程
  - resume 后继续当前 chunk
  - cancel 后保留已完成 chunk，删除当前未完成 chunk 临时文件
  - GpuResourceLock 保证串行，某个 chunk 取消不影响其他 chunk
- `TranscribeExecutor.tryMergeChunks()`：仅在所有 chunk 完成后触发，无需独立取消逻辑
---

## 附录 A、模型完整性检查测试用例 <Badge type="info" text="参考" />

本章描述 `_check_model_ready_worker` 及相关逻辑的 Python 单元测试，  
测试文件位于 `desktop_assets/common/fredica-pyutil/tests/test_asr_model_check.py`。

运行方式：
```shell
cd desktop_assets/common/fredica-pyutil
# 仅单元测试（无需 GPU，无网络）
../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_asr_model_check.py -v -m "not network and not download"

# 含实际下载的集成测试（需网络）
../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_asr_model_check.py -v -m "download" -s
```

### A.1 测试夹具（fixtures）

```python
import pytest
import tempfile
import os
import shutil
from pathlib import Path
from unittest import mock

@pytest.fixture
def tmp_models_dir(tmp_path):
    """每个测试用独立临时目录，测试结束后自动清理。"""
    d = tmp_path / "whisper_models"
    d.mkdir()
    return str(d)

@pytest.fixture
def tiny_model_dir(tmp_models_dir):
    """
    将 tiny 模型下载到 tmp_models_dir 并返回路径。
    标记 download，仅在明确需要时运行（慢，需网络）。
    """
    # 让 faster_whisper.download_model 把 tiny 下载到临时目录
    from faster_whisper import download_model
    path = download_model("tiny", cache_dir=tmp_models_dir)
    return path
```

### A.2 模型不存在（目录为空）

```python
class TestModelNotFound:
    def test_empty_dir_returns_not_ready(self, tmp_models_dir):
        """空目录：模型从未下载，应返回 not_found。"""
        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is False
        assert result["error_type"] == "not_found"

    def test_nonexistent_model_name(self, tmp_models_dir):
        """不存在的模型名，应返回 not_found。"""
        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("nonexistent-model-xyz", tmp_models_dir)
        assert result["ready"] is False
        assert result["error_type"] in ("not_found", "unknown")

    def test_wrong_models_dir(self):
        """指向不存在目录时，应返回 not_found 而非抛异常。"""
        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", "/nonexistent/path/xyz/models")
        assert result["ready"] is False
        assert result["error_type"] in ("not_found", "unknown")
```

### A.3 模型文件不完整（模拟下载中断）

```python
class TestModelIncomplete:
    def test_empty_model_dir_structure(self, tmp_models_dir):
        """
        创建正确的 HuggingFace 目录结构，但权重文件为空（0 字节）。
        模拟下载到一半进程被杀的场景。
        """
        # HuggingFace hub 缓存格式：models--Systran--faster-whisper-tiny/snapshots/<hash>/
        model_dir = Path(tmp_models_dir) / "models--Systran--faster-whisper-tiny"
        snapshot_dir = model_dir / "snapshots" / "abc123"
        snapshot_dir.mkdir(parents=True)
        # 创建空的权重文件（模拟下载中断）
        (snapshot_dir / "model.bin").write_bytes(b"")
        (snapshot_dir / "config.json").write_text("{}")

        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is False
        # 空文件加载会报错，error_type 为 incomplete 或 unknown
        assert result["error_type"] in ("incomplete", "unknown", "not_found")

    def test_corrupted_model_bin(self, tmp_models_dir):
        """
        权重文件存在但内容为随机垃圾字节（模拟磁盘写入中途断电）。
        """
        model_dir = Path(tmp_models_dir) / "models--Systran--faster-whisper-tiny"
        snapshot_dir = model_dir / "snapshots" / "abc123"
        snapshot_dir.mkdir(parents=True)
        # 写入随机垃圾数据
        (snapshot_dir / "model.bin").write_bytes(b"\x00" * 1024 + b"\xff" * 512)
        (snapshot_dir / "config.json").write_text('{"model_type": "whisper"}')

        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is False

    def test_missing_config_json(self, tmp_path):
        """
        模型目录结构存在，model.bin 有内容，但 config.json 缺失。
        """
        tmp_models_dir = str(tmp_path / "models")
        model_dir = Path(tmp_models_dir) / "models--Systran--faster-whisper-tiny"
        snapshot_dir = model_dir / "snapshots" / "abc123"
        snapshot_dir.mkdir(parents=True)
        (snapshot_dir / "model.bin").write_bytes(b"x" * 1000)
        # 故意不写 config.json

        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is False
```

### A.4 OOM 场景（mock）

```python
class TestModelOom:
    def test_oom_on_load_returns_oom_type(self, tmp_models_dir):
        """
        模拟 WhisperModel 加载时抛出 CUDA OOM 异常。
        实际 OOM 在真机 GPU 上触发，此处用 mock 模拟。
        """
        from fredica_pyutil_server.subprocess import asr_model_check

        oom_exc = RuntimeError("CUDA out of memory. Tried to allocate 2.00 GiB")
        with mock.patch(
            "faster_whisper.WhisperModel.__init__",
            side_effect=oom_exc,
        ):
            result = asr_model_check._check_model_ready_worker("large-v3", tmp_models_dir)

        assert result["ready"] is False
        assert result["error_type"] == "oom"
        assert "out of memory" in result["error"].lower()

    def test_oom_error_message_preserved(self, tmp_models_dir):
        """OOM 时 error 字段应包含原始异常信息。"""
        from fredica_pyutil_server.subprocess import asr_model_check

        with mock.patch(
            "faster_whisper.WhisperModel.__init__",
            side_effect=RuntimeError("cuda out of memory"),
        ):
            result = asr_model_check._check_model_ready_worker("medium", tmp_models_dir)

        assert result["error"] != ""
```

### A.5 设备不支持（mock）

```python
class TestDeviceUnsupported:
    def test_unsupported_compute_type(self, tmp_models_dir):
        """
        模拟设备不支持 float16（如老显卡），
        WhisperModel 抛出 compute type not supported 异常。
        """
        from fredica_pyutil_server.subprocess import asr_model_check

        with mock.patch(
            "faster_whisper.WhisperModel.__init__",
            side_effect=ValueError("Compute type float16 is not supported"),
        ):
            result = asr_model_check._check_model_ready_worker("small", tmp_models_dir)

        assert result["ready"] is False
        assert result["error_type"] == "device_unsupported"

    def test_cuda_not_available(self, tmp_models_dir):
        """
        模拟 CUDA 不可用时指定 device=cuda 失败的场景。
        注意：实际检查函数统一用 device=cpu，此 mock 测试异常分类逻辑。
        """
        from fredica_pyutil_server.subprocess import asr_model_check

        with mock.patch(
            "faster_whisper.WhisperModel.__init__",
            side_effect=ValueError("device cuda is not supported"),
        ):
            result = asr_model_check._check_model_ready_worker("tiny", tmp_models_dir)

        assert result["ready"] is False
        assert result["error_type"] in ("device_unsupported", "unknown")
```

### A.6 网络问题场景（mock download_model_worker）

```python
class TestDownloadNetworkErrors:
    """
    测试 download_model_worker 在网络异常时的行为。
    不做真实网络请求，用 mock 模拟 huggingface_hub.file_download.http_get 失败。
    """

    def test_connection_timeout_returns_error(self, tmp_models_dir):
        """连接超时时，download_model_worker 应推送 error 消息，不挂死。"""
        import queue, threading
        from fredica_pyutil_server.subprocess.download_model import download_model_worker

        import multiprocessing
        ctx = multiprocessing.get_context("spawn")
        status_q = ctx.Queue()
        cancel_ev = ctx.Event()
        resume_ev = ctx.Event()
        resume_ev.set()

        import socket
        with mock.patch(
            "huggingface_hub.file_download.http_get",
            side_effect=TimeoutError("Connection timed out"),
        ):
            # 在线程中运行（spawn 子进程在单元测试里较重，改用线程模拟）
            t = threading.Thread(
                target=download_model_worker,
                args=({"model_name": "tiny", "models_dir": tmp_models_dir}, status_q, cancel_ev, resume_ev),
                daemon=True,
            )
            t.start()
            t.join(timeout=10)

        messages = []
        while not status_q.empty():
            messages.append(status_q.get_nowait())

        types = [m.get("type") for m in messages]
        assert "error" in types, f"应有 error 消息，实际 types={types}"

    def test_cancel_during_download_stops_cleanly(self, tmp_models_dir):
        """下载过程中触发 cancel_event，应推送 cancelled 消息并退出。"""
        import threading, time
        from fredica_pyutil_server.subprocess.download_model import download_model_worker
        import multiprocessing
        ctx = multiprocessing.get_context("spawn")
        status_q = ctx.Queue()
        cancel_ev = ctx.Event()
        resume_ev = ctx.Event()
        resume_ev.set()

        write_count = [0]

        def _slow_http_get(url, temp_file, *, proxies=None, resume_size=0,
                           headers=None, expected_size=None, **kwargs):
            orig_write = temp_file.write
            def _write(data):
                write_count[0] += 1
                if write_count[0] >= 3:
                    cancel_ev.set()  # 写入 3 次后取消
                return orig_write(data)
            temp_file.write = _write
            # 模拟缓慢写入
            for _ in range(10):
                time.sleep(0.05)
                temp_file.write(b"x" * 1024)

        with mock.patch("huggingface_hub.file_download.http_get", _slow_http_get):
            t = threading.Thread(
                target=download_model_worker,
                args=({"model_name": "tiny", "models_dir": tmp_models_dir}, status_q, cancel_ev, resume_ev),
                daemon=True,
            )
            t.start()
            t.join(timeout=15)

        messages = []
        while not status_q.empty():
            messages.append(status_q.get_nowait())
        types = [m.get("type") for m in messages]
        assert "cancelled" in types or "error" in types
        assert "done" not in types

    def test_proxy_env_set_during_download(self, tmp_models_dir):
        """传入 proxy 时，下载 worker 应设置 HTTPS_PROXY 环境变量。"""
        import threading
        from fredica_pyutil_server.subprocess.download_model import download_model_worker
        import multiprocessing
        ctx = multiprocessing.get_context("spawn")
        status_q = ctx.Queue()
        cancel_ev = ctx.Event()
        resume_ev = ctx.Event()
        resume_ev.set()

        env_captured = {}

        def _capture_env(*args, **kwargs):
            env_captured["HTTPS_PROXY"] = os.environ.get("HTTPS_PROXY", "")
            raise RuntimeError("stop early")

        with mock.patch("faster_whisper.download_model", side_effect=_capture_env):
            t = threading.Thread(
                target=download_model_worker,
                args=(
                    {"model_name": "tiny", "proxy": "http://127.0.0.1:7890", "models_dir": tmp_models_dir},
                    status_q, cancel_ev, resume_ev,
                ),
                daemon=True,
            )
            t.start()
            t.join(timeout=5)

        assert env_captured.get("HTTPS_PROXY") == "http://127.0.0.1:7890"
```

### A.7 完整下载后的完整性检查（集成测试）

```python
@pytest.mark.download
class TestModelDownloadAndCheck:
    """
    真实下载 tiny 模型（~39MB）后验证完整性检查。
    标记 download，仅在明确运行集成测试时执行。
    需要网络，或预先将 tiny 模型放入 tmp_models_dir。
    """

    def test_tiny_model_ready_after_download(self, tiny_model_dir, tmp_models_dir):
        """下载完成后，_check_model_ready_worker 应返回 ready=True。"""
        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        result = _check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is True
        assert result["error"] == ""
        assert result["error_type"] == ""

    def test_tiny_model_ready_without_models_dir(self, tiny_model_dir):
        """
        tiny 模型已下载到默认 HF 缓存，不传 models_dir 也能检测到。
        前提：tiny_model_dir fixture 使用了默认缓存路径。
        """
        from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
        # 不传 models_dir，依赖默认 HF 缓存
        result = _check_model_ready_worker("tiny", "")
        assert result["ready"] is True

    def test_tiny_then_corrupt_then_recheck(self, tiny_model_dir, tmp_models_dir):
        """
        下载完整 → 手动破坏 model.bin → 再次检查应返回 False。
        模拟用户手动删除部分文件或磁盘损坏场景。
        """
        model_bin = Path(tiny_model_dir) / "model.bin"
        assert model_bin.exists(), "tiny 模型应有 model.bin"

        # 备份并破坏
        backup = model_bin.read_bytes()
        model_bin.write_bytes(backup[:100])  # 截断为 100 字节

        try:
            from fredica_pyutil_server.subprocess.asr_model_check import _check_model_ready_worker
            result = _check_model_ready_worker("tiny", tmp_models_dir)
            assert result["ready"] is False
        finally:
            # 恢复文件，避免污染后续测试
            model_bin.write_bytes(backup)

    def test_download_progress_reported(self, tmp_models_dir):
        """下载过程中应有 progress 消息，且 percent 在 0–100 之间。"""
        import threading
        from fredica_pyutil_server.subprocess.download_model import download_model_worker
        import multiprocessing
        ctx = multiprocessing.get_context("spawn")
        status_q = ctx.Queue()
        cancel_ev = ctx.Event()
        resume_ev = ctx.Event()
        resume_ev.set()

        t = threading.Thread(
            target=download_model_worker,
            args=({"model_name": "tiny", "models_dir": tmp_models_dir}, status_q, cancel_ev, resume_ev),
            daemon=True,
        )
        t.start()
        t.join(timeout=120)  # tiny 模型约 39MB，给足时间

        messages = []
        while not status_q.empty():
            messages.append(status_q.get_nowait())

        progress_msgs = [m for m in messages if m.get("type") == "progress"]
        done_msgs = [m for m in messages if m.get("type") == "done"]

        assert len(done_msgs) == 1, f"应有一条 done 消息，实际: {[m.get('type') for m in messages]}"
        assert len(progress_msgs) > 0, "应有进度消息"
        for m in progress_msgs:
            assert 0 <= m["percent"] <= 100
```

### A.8 错误分类边界用例

```python
class TestErrorClassification:
    """_check_model_ready_worker 的 error_type 分类逻辑单元测试（纯 mock，无 IO）。"""

    @pytest.mark.parametrize("exc_msg,expected_type", [
        ("file not found: /path/to/model.bin", "not_found"),
        ("No such file or directory", "not_found"),
        ("Model does not exist", "not_found"),
        ("Corrupt data: CRC mismatch", "incomplete"),
        ("Invalid file format", "incomplete"),
        ("CUDA out of memory. Tried to allocate 4.00 GiB", "oom"),
        ("cuda out of memory", "oom"),
        ("Compute type float16 is not supported on this device", "device_unsupported"),
        ("Operation not supported", "device_unsupported"),
        ("some completely random error xyz", "unknown"),
    ])
    def test_error_type_classification(self, exc_msg, expected_type, tmp_models_dir):
        from fredica_pyutil_server.subprocess import asr_model_check
        with mock.patch(
            "faster_whisper.WhisperModel.__init__",
            side_effect=Exception(exc_msg),
        ):
            result = asr_model_check._check_model_ready_worker("tiny", tmp_models_dir)
        assert result["ready"] is False
        assert result["error_type"] == expected_type, (
            f"异常消息 {exc_msg!r} 应分类为 {expected_type}，实际为 {result['error_type']}"
        )

    def test_successful_load_returns_ready_true(self, tmp_models_dir):
        """mock 加载成功时，应返回 ready=True，error 为空。"""
        from fredica_pyutil_server.subprocess import asr_model_check

        fake_model = mock.MagicMock()
        with mock.patch("faster_whisper.WhisperModel", return_value=fake_model):
            result = asr_model_check._check_model_ready_worker("tiny", tmp_models_dir)

        assert result["ready"] is True
        assert result["error"] == ""
        assert result["error_type"] == ""
```

### A.9 关于新增 Python 源文件

测试所引用的 `fredica_pyutil_server.subprocess.asr_model_check` 模块需新建，  
对应文件：`desktop_assets/common/fredica-pyutil/fredica_pyutil_server/subprocess/asr_model_check.py`

该模块提供 `_check_model_ready_worker(model_name, models_dir) -> dict` 函数（参见 §7.2.2），  
供 `routes/asr.py` 的 `GET /asr/check-model-ready/` 接口在子进程中调用。

---

## 六、显存独占文件锁 <Badge type="tip" text="已完成" />

> **已完成**：`GpuResourceLock` 已升级为 `Mutex` + `PriorityQueue` 优先级锁（0-20 范围），详见 **§12 优先级 GPU 资源锁**。

### 6.1 问题背景

家庭用户 GPU 显存有限，以下进程同时运行会 OOM 或严重降速：
- `faster-whisper` 子进程（加载模型占用大量 VRAM）
- `ffmpeg` 硬件加速转码/解码（NVENC/NVDEC/AMF 等也占用 VRAM）
- `torch`（其他推理任务）

实际采用 JVM 进程内 `Semaphore(1)` 互斥锁（`GpuResourceLock` 单例），而非最初设计的 OS 文件锁。
原因：所有 GPU 密集型任务均由 Kotlin Executor 发起（通过 `PythonUtil.websocketTask()` 调用 Python 子进程），
因此进程内互斥即可保证同一时刻只有一个 GPU 任务在执行，无需跨进程文件锁。

### 6.2 实现：GpuResourceLock 单例

**文件**：`shared/src/commonMain/kotlin/.../worker/GpuResourceLock.kt`

```kotlin
object GpuResourceLock {
    private val semaphore = Semaphore(1)   // permits=1 → 互斥

    suspend fun <T> withGpuLock(taskId: String, block: suspend () -> T): T {
        if (semaphore.availablePermits == 0) {
            // 锁已被占用 → 写入等待提示，让用户在 UI 上看到原因
            TaskService.repo.updateStatusText(taskId, "等待 GPU 资源…")
        }
        return semaphore.withPermit {
            TaskService.repo.updateStatusText(taskId, null)  // 清除等待提示
            block()
        }
    }
}
```

核心语义：
- `Semaphore(1)` 等价于互斥锁，同一时刻最多 1 个协程持有 permit
- `withPermit` 自动获取/释放，即使 `block()` 抛异常也能正确释放
- 等待时写入 `statusText`，获取后清除，让前端实时显示等待原因

### 6.3 各 Executor 使用方式

| Executor | 何时持锁 | 说明 |
|----------|---------|------|
| `TranscribeExecutor` | **始终** | faster-whisper 推理始终占用 GPU |
| `TranscodeMp4Executor` | `hw_accel ≠ "cpu"` 时 | 纯 CPU 转码不需要 GPU 锁 |

> **注**：`EvaluateFasterWhisperCompatExecutor` 已在后续重构中移除，兼容性检测改由 `FasterWhisperInstallService` 处理。

**TranscodeMp4Executor 条件持锁示例**：

```kotlin
return if (resolvedAccel != "cpu") {
    GpuResourceLock.withGpuLock(task.id) { runTask() }
} else {
    runTask()
}
```

### 6.4 注意事项

- **进程内互斥足够**：所有 GPU 任务由 Kotlin Executor 发起，Python 子进程由 `websocketTask()` 管理，
  Executor 持锁期间子进程运行，释放锁后子进程已结束，无需跨进程锁
- **无死锁风险**：`withPermit` 基于 `try/finally`，异常时自动释放；不存在嵌套持锁场景
- **与 WorkerEngine 并发控制的关系**：WorkerEngine 的 Semaphore 限制同时运行的 Task 总数，
  GpuResourceLock 进一步限制 GPU 任务的并发数为 1，两者互补
- **非 GPU 任务不受影响**：`TranscodeMp4Executor` 在 `hw_accel = "cpu"` 时跳过锁，
  `ExtractAudioExecutor` 等纯 CPU Executor 不调用 `withGpuLock`

### 6.5 测试

**文件**：`shared/src/jvmTest/kotlin/.../worker/GpuResourceLockTest.kt`

使用真实 SQLite 临时文件 + `CompletableDeferred` 精确控制时序，4 个测试用例：

| 测试 | 验证目标 |
|------|---------|
| `testMutualExclusion` | 两个 GPU 任务串行执行，`AtomicInteger` 并发数不超过 1 |
| `testWaitingStatusText` | 等待锁时 `statusText` 被设为"等待 GPU 资源…" |
| `testStatusTextClearedOnAcquire` | 获取锁后 `statusText` 被清除为 null |
| `testNoContentionNoStatusText` | 无竞争时不写入等待提示（直接获取锁） |

---

## 七、模型下载选项与完整性检查 <Badge type="warning" text="未完成" />

### 7.1 "允许下载模型"选项

#### 7.1.1 AppConfig 新增字段

```kotlin
// AppConfig.kt 新增
@SerialName("asr_allow_download") val asrAllowDownload: Boolean = true
```

前端设置页面提供开关：
- **开（默认）**：启动 ASR 任务时自动在任务链头部插入 `DOWNLOAD_WHISPER_MODEL` 任务
- **关（仅本地模型）**：`MaterialAsrStartRoute` 先做模型完整性检查，若失败则直接返回 error，不创建任务链

#### 7.1.2 MaterialAsrStartRoute 逻辑调整

```
if (!asrAllowDownload):
    调用 Python 检查模型完整性（见 7.2）
    → 失败则返回 error，告知用户开启自动下载或手动下载
else:
    在任务链头部插入 DOWNLOAD_WHISPER_MODEL（canSkip 在完整性通过时跳过）
    总任务数 +1
```

`DOWNLOAD_WHISPER_MODEL` 已是现有 executor，直接复用。

#### 7.1.3 任务链更新（允许下载时）

```
EXTRACT_AUDIO（5 分钟/段切割，60 秒重叠）
    ↓
ASR_SPAWN_CHUNKS（读取 chunk 列表，动态创建 N 个 TRANSCRIBE Task）
    ↓
TRANSCRIBE × N（每个 chunk 一个独立 Task，GpuResourceLock 保证串行）
    最后完成的 TRANSCRIBE 触发 tryMergeChunks() → transcript.srt
```

### 7.2 模型完整性检查：结合设备分析工具的两阶段加载策略

#### 7.2.1 核心设计原则

> **判断本地模型是否存在且完整，不能靠目录名或文件名扫描，只能靠实际尝试加载。**

但这里需要补充一个重要约束：
- `device_type`、`compute_type` **无法事前静态推断到完全准确**
- 即使已有设备分析工具，也只能给出**候选组合**，最终仍需通过实际 `WhisperModel(...)` 加载来验证

因此本方案采用：
1. **先用现有 pyutil 中设备信息分析工具给出候选 device / compute_type 组合**
2. **再执行两阶段加载检查**：
   - `local_files_only=True`：判断“本地是否已存在且基本可加载”
   - `local_files_only=False`：判断“若允许联网，是否能通过下载/补全后加载成功”
3. **把最终异常分类建立在真实加载结果上，而不是事前猜测上**

这与 `evaluate_faster_whisper_compat_worker.py` 中 `_try_load_whisper()` 的已有模式一致，只是这里把它抽象为统一的加载策略。

#### 7.2.2 为什么不能固定 `compute_type=int8`

此前文档把完整性检查固定成 `device="cpu", compute_type="int8"`，这过于乐观，原因是：
- 某些错误并不是“模型文件损坏”，而是当前设备 / compute type 组合不可用
- `compute_type` 是否支持，经常只能在实际加载时由底层抛错
- 即使是同一模型，CPU / CUDA / 不同 compute_type 下的失败原因也可能不同

因此这里应当提取一个统一策略函数，例如：

```python
def choose_whisper_load_candidates(device_info: dict) -> list[dict]:
    """
    基于现有设备分析工具，返回按优先级排序的加载候选。
    注意：这里只是候选，不保证一定成功。
    """
    # 例如：
    # CUDA 可用 → [{device:"cuda", compute_type:"float16"}, {device:"cuda", compute_type:"int8_float16"}, {device:"cpu", compute_type:"int8"}]
    # 仅 CPU → [{device:"cpu", compute_type:"int8"}, {device:"cpu", compute_type:"float32"}]
```

#### 7.2.3 统一加载策略设计

建议新建：
- `fredica_pyutil_server/subprocess/asr_model_check.py`

其中核心不是单个 `_check_model_ready_worker()`，而是先抽出：

```python
def try_load_whisper_model(
    model_name: str,
    *,
    models_dir: str,
    local_files_only: bool,
    device_info: dict,
) -> dict:
    """
    按设备分析工具给出的候选组合，依次尝试 WhisperModel 加载。

    返回：
    {
      "ok": bool,
      "device": "cpu" | "cuda" | ...,
      "compute_type": "int8" | "float16" | ...,
      "error": str,
      "error_type": str,
      "tried": [
        {"device": "cuda", "compute_type": "float16", "ok": false, "error": "..."},
        ...
      ]
    }
    """
```

加载流程：

**阶段 A：本地检查（`local_files_only=True`）**
- 目的：判断本地模型是否已经存在且可加载
- 若成功：
  - `ready=true`
  - `canSkip=true`
- 若失败：
  - 记录真实异常与 `tried[]`
  - 继续进入阶段 B（仅当允许下载时）

**阶段 B：联网检查（`local_files_only=False`）**
- 目的：判断是否能通过 HuggingFace 自动下载 / 补全后成功加载
- 若成功：
  - 说明不是硬件完全不支持，而是“本地缺失 / 不完整 / 需要下载”
- 若仍失败：
  - 才能更有把握地归因为 `device_unsupported` / `oom` / `unknown`

这样可以区分：
- 本地缺失 / 残缺
- 网络可恢复的缺失
- 设备 / compute_type 硬错误
- 真正的 OOM

#### 7.2.4 设备分析工具如何接入

这里要结合现有 pyutil 中的设备信息分析能力，而不是在 `asr_model_check.py` 里重新猜。

建议：
1. 复用已有设备分析 worker / route 中的设备探测逻辑
2. 统一抽出一个 `device_info` 结构，例如：

```python
{
  "has_cuda": true,
  "cuda_available": true,
  "preferred_device": "cuda",
  "candidate_compute_types": ["float16", "int8_float16", "int8"],
}
```

3. `try_load_whisper_model()` 按这个顺序去试
4. 每次试失败都保留原始错误消息，最终再做 error_type 归类

> **重点**：设备分析工具只负责“缩小尝试范围”，**不负责直接下结论**。最终结论必须来自真实加载结果。

#### 7.2.5 `DownloadWhisperModelExecutor.canSkip` 修改

**现有问题**：只检查 HuggingFace 缓存目录名，无法发现：
- 下载到一半的残缺文件
- 设备 / compute_type 不支持
- 本地不完整但联网可修复
- 即使这次成功加载了，也没有把“本次验证过的状态”持久化下来

**新方案**：调用 Python HTTP 接口，内部执行“设备分析 + 两阶段加载”；并且**当检测结果可 `canSkip` 时，要把本次验证通过的模型文件 hash 写入 `.done` 文件**。

##### `.done + hash` 通用模式

这里应当把 `done 文件 + hash` 上升为一个更通用的设计模式，适用于很多“处理慢”的环节：
- 模型下载 / 模型完整性验证
- 视频转码
- 音频切块
- ASR 合并输出
- 未来的 OCR / embedding / 大文件预处理

通用原则：
1. **慢操作完成后写 `.done`**
2. `.done` 内不仅记录时间，还记录**关键输入或关键产物的 hash / 指纹**
3. `canSkip()` 不只看 `.done` 是否存在，还要校验 hash 是否仍匹配
4. 任一关键文件变化，就视为缓存失效，重新执行

##### 模型检查场景中的 `.done`

建议新增：
- `{models_dir}/checks/faster-whisper-{model_name}.ready.done`

内容例如：

```json
{
  "model_name": "tiny",
  "validated_at": 1712300000,
  "chosen_device": "cuda",
  "chosen_compute_type": "float16",
  "model_hash": "sha256:...",
  "hash_inputs": [
    "model.bin",
    "config.json",
    "tokenizer.json"
  ]
}
```

其中 `model_hash` 不要求覆盖整个 HuggingFace cache 的所有文件，但至少应基于**足以代表模型状态**的关键文件集合生成稳定摘要。建议：
- 优先 hash `model.bin` / `config.json` / tokenizer 相关文件
- 若文件过大，可采用：`文件大小 + mtime + 前后若干字节 + 关键配置文件 hash` 的组合指纹
- 若后续确认性能可接受，再升级为完整 SHA-256

##### `canSkip()` 判定升级

`DownloadWhisperModelExecutor.canSkip` 变成两步：

1. **快路径**：若 `.ready.done` 存在，先校验其中的 `model_hash` 是否与当前模型文件匹配
   - 匹配：直接 `canSkip=true`
   - 不匹配：视为缓存失效，进入第 2 步
2. **慢路径**：调用 `check-model-ready`
   - 若真实加载成功：
     - 回写 / 刷新 `.ready.done`
     - `canSkip=true`
   - 若失败：
     - `canSkip=false`

这样可以避免每次都完整加载模型，也能避免单纯依赖目录名造成误判。

新增 Python HTTP 接口 `GET /asr/check-model-ready/`：

```python
# routes/asr.py 新增
@_router.get("/check-model-ready/")
async def check_model_ready(model_name: str, models_dir: str = "", allow_download: bool = False):
    """
    先用现有设备分析工具生成候选加载策略，再尝试：
    1) local_files_only=True
    2) 若 allow_download=true，再 local_files_only=False

    Response:
      {
        "ready": bool,
        "recoverable_by_download": bool,
        "error": "",
        "error_type": "",
        "chosen_device": "cpu",
        "chosen_compute_type": "int8",
        "model_hash": "sha256:...",
        "tried": [...]
      }
    """
```

Python 逻辑建议：

```python
def _check_model_ready_worker(model_name: str, models_dir: str, allow_download: bool) -> dict:
    device_info = collect_device_info_for_whisper()  # 复用现有 pyutil 设备分析能力

    local_try = try_load_whisper_model(
        model_name,
        models_dir=models_dir,
        local_files_only=True,
        device_info=device_info,
    )
    if local_try["ok"]:
        model_hash = build_model_fingerprint(model_name=model_name, models_dir=models_dir)
        write_model_ready_done(
            model_name=model_name,
            models_dir=models_dir,
            model_hash=model_hash,
            chosen_device=local_try["device"],
            chosen_compute_type=local_try["compute_type"],
        )
        return {
            "ready": True,
            "recoverable_by_download": False,
            "error": "",
            "error_type": "",
            "chosen_device": local_try["device"],
            "chosen_compute_type": local_try["compute_type"],
            "model_hash": model_hash,
            "tried": local_try["tried"],
        }

    if not allow_download:
        return {
            "ready": False,
            "recoverable_by_download": False,
            "error": local_try["error"],
            "error_type": local_try["error_type"],
            "chosen_device": "",
            "chosen_compute_type": "",
            "model_hash": "",
            "tried": local_try["tried"],
        }

    remote_try = try_load_whisper_model(
        model_name,
        models_dir=models_dir,
        local_files_only=False,
        device_info=device_info,
    )
    if remote_try["ok"]:
        model_hash = build_model_fingerprint(model_name=model_name, models_dir=models_dir)
        write_model_ready_done(
            model_name=model_name,
            models_dir=models_dir,
            model_hash=model_hash,
            chosen_device=remote_try["device"],
            chosen_compute_type=remote_try["compute_type"],
        )
        return {
            "ready": True,
            "recoverable_by_download": True,
            "error": "",
            "error_type": "",
            "chosen_device": remote_try["device"],
            "chosen_compute_type": remote_try["compute_type"],
            "model_hash": model_hash,
            "tried": local_try["tried"] + remote_try["tried"],
        }

    return {
        "ready": False,
        "recoverable_by_download": False,
        "error": remote_try["error"],
        "error_type": remote_try["error_type"],
        "chosen_device": "",
        "chosen_compute_type": "",
        "model_hash": "",
        "tried": local_try["tried"] + remote_try["tried"],
    }
```

**Kotlin `DownloadWhisperModelExecutor.canSkip`**：
- 先检查 `.ready.done + hash`
- 快路径命中时直接 `canSkip=true`
- 否则调 `allow_download=false` 的 `check-model-ready`
- 仅当 `ready=true` 时 `canSkip=true`
- 若真实加载成功，Python 侧顺便刷新 `.ready.done`

#### 7.2.6 各错误场景与处理

| 错误类型 | `error_type` | 发生原因 | 处理方式 |
|---------|-------------|---------|---------|
| 目录不存在 / 本地无缓存 | `not_found` | 从未下载 | `canSkip=false` → 触发下载 |
| 文件残缺 | `incomplete` | 下载中断、磁盘满 | `canSkip=false` → 重新下载 / HF 自动补全 |
| 本地失败但联网可成功 | `recoverable_by_download=true` | 缺文件或缓存不完整 | 插入 / 执行下载任务 |
| 加载 OOM | `oom` | 当前 device / compute_type 组合内存不足 | 提示降级模型；必要时 fallback 下一个候选组合 |
| 设备不支持 | `device_unsupported` | 所有候选组合都无法加载 | 提示切 CPU 或更换设备 |
| 其他 | `unknown` | 未知异常 | `canSkip=false`，日志记录 |

> **关键点**：`oom` 与 `device_unsupported` 不应靠字符串提前臆测，而应建立在“候选组合全部尝试后的最终判断”上。

### 7.3 前端设置页面

在应用设置（AppConfig 编辑页面）新增：

```
┌─ ASR / Whisper 设置 ──────────────────────────────────┐
│                                                        │
│  [✓] 允许自动下载模型（关闭后仅使用本地已有模型）          │
│                                                        │
│  模型存储目录  [留空使用默认 ...]                        │
│                                                        │
│  当前可用模型（点击"检测"刷新）：                        │
│  ● tiny     已就绪                                     │
│  ○ medium   未下载 / 不完整                             │
│  ● large-v3 已就绪                                     │
│  [检测]                                                │
└────────────────────────────────────────────────────────┘
```

"已就绪"状态通过调用 `GET /asr/check-model-ready/?model_name=xxx` 查询，由前端逐个检测后展示。这里不再假设固定 `cpu+int8`，而是直接展示 §7.2 的真实加载结论（chosen_device / chosen_compute_type / error_type）。

同时前端补一份 `WhisperModelHint.md` / `asrModelHints.ts` 文案源：
- 展示推荐模型子集与“展开全部模型”入口
- 展示经验性显存/性能建议
- 明确标注“是否可用以实时检测结果为准”

---

## 八、模型下载进度与合并进度展示 <Badge type="warning" text="未完成" />

### 8.1 任务链进度合并策略

ASR 完整任务链的进度阶段：

| 阶段 | Task 类型 | 占比建议 | 说明 |
|------|-----------|---------|------|
| 提取音频 | `EXTRACT_AUDIO` | 动态 | 已有时 canSkip |
| 创建分块任务 | `ASR_SPAWN_CHUNKS` | 忽略（瞬间） | — |
| 语音转录 | `TRANSCRIBE` × N | 主要阶段 | 每个 chunk 一个独立 Task |

前端展示：
1. **Workflow 层**：整体进度 = `done_tasks / total_tasks`
2. **Task 层**：每个 `TRANSCRIBE` Task 独立上报 `progress`；前端展示"已完成 M/N 块"

### 8.2 下载阶段进度展示

这里应当先做**对 faster-whisper / HuggingFace 下载链路的 monkey-patch 设计测试**，再做 UI 展示。

#### 8.2.1 monkey-patch 设计前提

`DOWNLOAD_WHISPER_MODEL` 想拿到细粒度下载进度，不能只在任务完成时拿到一个 done 信号，而要在下载过程中持续观察字节写入进度。

我以前的经验也指向：**可以通过 hook `huggingface_hub` 的下载实现来做到**。当前方案继续沿这个方向：
- 在 `download_model.py` 中 monkey-patch `huggingface_hub.file_download.http_get`
  或其内部 `temp_file.write` 链路
- 在每次写入时累加已下载字节数
- 按 `written / expected_size * 100` 推送 `progress` 消息

建议先做测试，再定最终 patch 点。

#### 8.2.2 monkey-patch 测试设计

建议新增优先级更高的测试组（应早于 UI 进度条测试完成）：

- `test_download_progress_patch_reports_progress_messages`
  - mock `huggingface_hub.file_download.http_get`
  - 模拟分 10 次写入临时文件
  - 断言 `download_model_worker` 推送多条 `progress`
  - 断言 percent 单调递增且最终到 100

- `test_download_progress_patch_handles_unknown_expected_size`
  - 当 `expected_size is None` 时
  - 仍应推送 fallback 进度消息（如 bytes-only 或阶段提示）
  - UI 可降级显示“已下载 X MB”

- `test_download_progress_patch_restores_original_http_get`
  - monkey-patch 结束后恢复原实现
  - 防止污染其他 pyutil 测试

- `test_download_progress_patch_survives_hf_internal_change`
  - 当目标属性不存在时，不应崩溃
  - 应降级为无细粒度进度，但下载仍可继续

> **设计结论**：下载进度功能本质上依赖第三方库内部实现，因此必须把 monkey-patch 当成一个单独可测试模块，而不是把它直接混在 `download_model_worker` 里写死。

#### 8.2.3 运行时进度展示

`DOWNLOAD_WHISPER_MODEL` task 的 `progress` 字段（0–100）由 `DownloadWhisperModelExecutor` 通过 `onProgress` 写入，Python `download_model.py` 的 monkey-patch 负责逐字节上报进度。

前端轮询 `WorkerTaskListRoute` 时，任务列表中的 `DOWNLOAD_WHISPER_MODEL` task 会携带 `progress` 字段，UI 可据此显示：

```
┌─ 进行中 ───────────────────────────────────────────┐
│  ① 下载模型 large-v3   ▓▓▓▓▓▓░░░░  63%           │
│  ② 提取音频            等待中...                    │
│  ③ 转录（共 N 块）      等待中...                    │
└────────────────────────────────────────────────────┘
```

### 8.3 转录阶段进度展示

每个 `TRANSCRIBE` Task 独立上报 `progress`（0–100），由 Python WebSocket 子进程推送。

前端展示：
```
③ 转录   已完成 2/5 块
```

每个 TRANSCRIBE Task 可独立暂停/恢复/取消，符合 `task-model.md` 的 Task 级控制语义。

### 8.4 UI 阶段标签

前端按 task type 映射展示文本：

```typescript
const TASK_PHASE_LABEL: Record<string, string> = {
  EXTRACT_AUDIO:          "提取音频",
  ASR_SPAWN_CHUNKS:       "准备分块",
  TRANSCRIBE:             "语音转录",
};
```

### 8.5 已跳过任务的进度处理

`canSkip` 为 `true` 的 task 由 WorkerEngine 标记为 `completed`（跳过逻辑在 `WorkerEngine` 中），`done_tasks` 正常+1。前端无需特殊处理，进度条自然前进。

> **注意**：需确认 WorkerEngine 对 `canSkip=true` 的 task 确实执行 `done_tasks+1`（recalculate），而非静默忽略。若当前实现不更新 WorkflowRun 的 `done_tasks`，需修复。

### 8.6 整体 UI 进度条设计

```
┌─ ASR 转录进行中 ───────────────────────────────────────┐
│                                                        │
│  ▓▓▓▓▓▓▓▓░░░░░░░░  45%   共 11 步，已完成 5 步        │
│                                                        │
│  当前：语音转录（3/7 块完成）                            │
│  ├ chunk_0000  ✓ 完成                                  │
│  ├ chunk_0001  ✓ 完成                                  │
│  ├ chunk_0002  ✓ 完成                                  │
│  ├ chunk_0003  ▓▓▓▓░░░  58%  进行中                   │
│  └ chunk_0004–0006  等待中                             │
│                                                        │
│  [取消]                                                │
└────────────────────────────────────────────────────────┘
```

chunk 列表在 `ASR_SPAWN_CHUNKS` 完成后（N 个 `TRANSCRIBE` Task 已创建）才展示，此前只显示整体进度条。

---

## 九、阶段开发与测试 <Badge type="info" text="参考" />

本章按实现顺序组织**测试驱动开发**（TDD）用例设计。每个阶段遵循"先写测试，再写实现"原则：
- 用例在实现前定义（红灯），实现后通过（绿灯）
- 测试命名、注释风格参考 `docs/dev/testing.md` 及现有测试文件约定

### 9.1 Phase 1 — 字幕导出（纯前端，无依赖）

**最先实现**，完全独立，可并行于其他 Phase。

#### 9.1.1 测试目标

验证 `subtitleExport.ts` 中 `convertToSrt()` 和 `secondsToSrtTime()` 的正确性，覆盖边界值与精度。

#### 9.1.2 测试文件

`fredica-webui/tests/util/subtitleExport.test.ts`

```typescript
// 运行：cd fredica-webui && npx vitest run tests/util/subtitleExport.test.ts

import { describe, it, expect } from "vitest";
import { convertToSrt, type SrtSegment } from "~/util/subtitleExport";

// ── secondsToSrtTime 边界值 ───────────────────────────────────────────────

describe("secondsToSrtTime", () => {
  // 辅助：从 SRT 字符串中提取第一条时间戳行
  function firstTimeline(srt: string): string {
    return srt.split("\n")[1];
  }

  it("E1 - 0 秒转为 00:00:00,000", () => {
    const srt = convertToSrt([{ from: 0, to: 1, content: "x" }]);
    expect(firstTimeline(srt)).toBe("00:00:00,000 --> 00:00:01,000");
  });

  it("E2 - 超过 1 小时（3661.5 秒）", () => {
    const srt = convertToSrt([{ from: 3661.5, to: 3662, content: "x" }]);
    expect(firstTimeline(srt)).toBe("01:01:01,500 --> 01:01:02,000");
  });

  it("E3 - 毫秒精度：1.001 秒 → ,001", () => {
    const srt = convertToSrt([{ from: 1.001, to: 2.999, content: "x" }]);
    expect(firstTimeline(srt)).toBe("00:00:01,001 --> 00:00:02,999");
  });

  it("E4 - 浮点舍入：0.0005 秒四舍五入至 0ms 或 1ms（不崩溃）", () => {
    expect(() => convertToSrt([{ from: 0.0005, to: 0.001, content: "x" }])).not.toThrow();
  });
});

// ── convertToSrt 结构 ─────────────────────────────────────────────────────

describe("convertToSrt", () => {
  it("E5 - 空数组返回只含换行的字符串", () => {
    const result = convertToSrt([]);
    expect(result).toBe("\n");
  });

  it("E6 - 单条字幕格式正确（序号 / 时间戳 / 内容 / 空行）", () => {
    const segs: SrtSegment[] = [{ from: 1.23, to: 4.56, content: "Hello" }];
    const result = convertToSrt(segs);
    expect(result.trim()).toBe(
      "1\n00:00:01,230 --> 00:00:04,560\nHello"
    );
  });

  it("E7 - 多条字幕序号从 1 递增", () => {
    const segs: SrtSegment[] = [
      { from: 0, to: 1, content: "A" },
      { from: 2, to: 3, content: "B" },
      { from: 4, to: 5, content: "C" },
    ];
    const result = convertToSrt(segs);
    const lines = result.split("\n").filter(Boolean);
    expect(lines[0]).toBe("1");
    expect(lines[3]).toBe("2");
    expect(lines[6]).toBe("3");
  });

  it("E8 - 包含特殊字符（换行符在 content 中）不崩溃", () => {
    const segs: SrtSegment[] = [{ from: 0, to: 1, content: "Line1\nLine2" }];
    expect(() => convertToSrt(segs)).not.toThrow();
  });
});
```

**运行命令**：
```shell
cd fredica-webui && npx vitest run tests/util/subtitleExport.test.ts
```

**TDD 流程**：先建空文件 `util/subtitleExport.ts`（导出空函数），测试红灯；实现 `secondsToSrtTime` + `convertToSrt`，测试绿灯。

---

### 9.1.5 Phase 1.5 — ASR 工作流最小闭环（Kotlin 侧） <Badge type="tip" text="已完成" />

#### 9.1.5.1 测试目标

验证以下两个层次的正确性：

1. **`PythonUtil.Py314Embed.installPackage` + `FasterWhisperInstallService`**：pip 安装基础设施的并发安全、快速路径跳过、异常处理
2. **`MaterialWorkflowService.startWhisperTranscribe`**：最小线性工作流（EXTRACT_AUDIO → TRANSCRIBE）的 payload 路径衔接与幂等性

#### 9.1.5.2 测试文件 A：安装服务

`shared/src/jvmTest/kotlin/com/github/project_fredica/python/FasterWhisperInstallServiceTest.kt`

```kotlin
// =============================================================================
// FasterWhisperInstallServiceTest
// =============================================================================
//
// 测试矩阵：
//   I1. 首次调用 ensureInstalled()：installed flag 为 false，执行 installPackage，成功后 flag 变 true
//   I2. 第二次调用 ensureInstalled()：installed flag 已为 true，直接返回 null（快速路径，不再调 pip）
//   I3. installPackage 抛出非 CancellationException 时：ensureInstalled() 返回非 null 错误信息，flag 保持 false
//   I4. installPackage 抛出 CancellationException 时：异常向上传播，不被吞掉
//   I5. 并发调用 ensureInstalled()：多个协程同时调用，pip install 只执行一次（Mutex 串行化）
// =============================================================================

// 注意：I1/I2/I3/I4 需要对 PythonUtil.Py314Embed.installPackage 进行 mock/stub，
// 或通过反射重置 FasterWhisperInstallService.installed flag。
// I5 可通过计数器验证 installPackage 调用次数。
```

#### 9.1.5.3 测试文件 B：工作流 payload 路径衔接

`shared/src/jvmTest/kotlin/com/github/project_fredica/db/MaterialWorkflowServiceAsrPayloadTest.kt` <Badge type="tip" text="已完成" />

已实现，测试矩阵：

| 编号 | 测试内容 |
|------|---------|
| P1 | TRANSCRIBE `audio_path` 指向 `chunk_0000.m4a`（FFmpeg segment 实际产出的文件名） |
| P2 | TRANSCRIBE `audio_path` 与 EXTRACT_AUDIO `output_dir` 在同一目录下（路径衔接正确） |
| P3 | EXTRACT_AUDIO `output_dir`（`asr_audio/`）与 TRANSCRIBE `output_path` 的父目录（`asr_result/`）不同 |
| P4 | 幂等检查：已有活跃任务时返回 `AlreadyActive`，不重复创建 |

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.MaterialWorkflowServiceAsrPayloadTest"
```

#### 9.1.5.4 关键设计说明

**`installPackage` 的 Mutex 位置**：Mutex 在 `PythonUtil.Py314Embed` 内部，而不是在 `FasterWhisperInstallService` 内部。这样多个不同的 `InstallService`（未来可能有其他包）共享同一个 pip 安装队列，避免并发 pip 进程互相干扰。

**`@Volatile installed` flag**：进程级内存标志，重启后重置。pip 会检测已安装版本并快速跳过，相当于每次启动做一次轻量验证，无需额外持久化。

**`CancellationException` 必须重抛**：协程取消信号不能被 `catch (e: Throwable)` 吞掉，必须在 catch 块最前面显式 `catch (e: CancellationException) { throw e }`。

**调用层次**：`ensureInstalled()` 在 Executor 层调用（`DOWNLOAD_WHISPER_MODEL` 或 `EXTRACT_AUDIO`），不在 Route HTTP handler 里调用，避免前端请求超时。

---

### 9.2 Phase 2 — Python 文件锁工具（`file_lock_util.py`）

**在开发任何依赖锁的 Executor 之前**完成，因为 Python `transcribe.py` 和 `audio.py` 都需要它。

#### 9.2.1 测试目标

验证 `exclusive_file_lock` 的互斥语义：同一进程内两个线程竞争同一锁文件，后者必须等待前者释放。

#### 9.2.2 测试文件

`desktop_assets/common/fredica-pyutil/tests/test_file_lock_util.py`

```python
"""
file_lock_util 单元测试。
运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_file_lock_util.py -v
"""
import threading
import time
from pathlib import Path
from fredica_pyutil_server.util.file_lock_util import exclusive_file_lock


class TestExclusiveFileLock:
    def test_FL1_single_thread_acquires_and_releases(self, tmp_path):
        """
        证明目的：单线程可以正常获取和释放文件锁，锁文件会在 context 内写入 PID。

        证明过程：
          1. 进入 exclusive_file_lock context。
          2. 断言锁文件存在且含当前 PID（或非空）。
          3. 退出 context 后，断言锁已释放（文件可被删除或再次加锁）。
        """
        lock_path = tmp_path / "test.lock"
        with exclusive_file_lock(lock_path):
            assert lock_path.exists()
        # 退出后应可正常再次获取
        with exclusive_file_lock(lock_path):
            pass  # 若阻塞则测试超时

    def test_FL2_mutual_exclusion_between_threads(self, tmp_path):
        """
        证明目的：两个线程竞争同一锁文件时互斥，不出现并发重叠。

        证明过程：
          1. 线程 A 持有锁 300ms。
          2. 线程 B 在 A 持有期间尝试获取锁。
          3. 记录 A 释放时间和 B 获取时间，断言 B 获取时间 >= A 释放时间。

        边界：依赖 OS advisory lock，不测试 Windows msvcrt / Unix fcntl 的具体实现细节。
        """
        lock_path = tmp_path / "mutex.lock"
        events = []  # [(名称, 时间)]

        def thread_a():
            with exclusive_file_lock(lock_path):
                events.append(("A_acquired", time.monotonic()))
                time.sleep(0.3)
                events.append(("A_released", time.monotonic()))

        def thread_b():
            time.sleep(0.05)  # 等 A 先启动
            events.append(("B_trying", time.monotonic()))
            with exclusive_file_lock(lock_path):
                events.append(("B_acquired", time.monotonic()))

        ta = threading.Thread(target=thread_a)
        tb = threading.Thread(target=thread_b)
        ta.start()
        tb.start()
        ta.join(timeout=5)
        tb.join(timeout=5)

        ev = {name: t for name, t in events}
        assert "A_released" in ev and "B_acquired" in ev
        # B 获取锁的时间必须在 A 释放之后（允许 10ms 误差）
        assert ev["B_acquired"] >= ev["A_released"] - 0.01, (
            f"B 在 A 释放前就获取了锁：B={ev['B_acquired']:.3f} A_rel={ev['A_released']:.3f}"
        )

    def test_FL3_lock_dir_created_if_not_exists(self, tmp_path):
        """
        证明目的：锁文件父目录不存在时，exclusive_file_lock 自动创建目录。

        覆盖：AppUtil.Paths.gpuLockFile 在首次运行时 locks/ 目录不存在的场景。
        """
        lock_path = tmp_path / "nested" / "dir" / "gpu.lock"
        assert not lock_path.parent.exists()
        with exclusive_file_lock(lock_path):
            assert lock_path.exists()

    def test_FL4_exception_inside_context_releases_lock(self, tmp_path):
        """
        证明目的：context body 内抛出异常，锁依然正确释放（finally 保证）。

        覆盖：Python worker 崩溃时锁不应死锁后续任务。
        """
        lock_path = tmp_path / "error.lock"
        try:
            with exclusive_file_lock(lock_path):
                raise RuntimeError("simulate worker crash")
        except RuntimeError:
            pass

        # 锁已释放，同一线程再次获取不阻塞
        acquired = False
        with exclusive_file_lock(lock_path):
            acquired = True
        assert acquired
```

**运行命令**：
```shell
cd desktop_assets/common/fredica-pyutil
../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_file_lock_util.py -v
```

---

### 9.3 Phase 3 — 模型完整性检查与下载 hook 设计（Python）

详细测试用例见 **附录 A**，此处补充 TDD 流程说明。

#### 9.3.1 TDD 流程

```
1. 先把 huggingface 下载 monkey-patch 提取成独立模块 / 独立函数
2. 先写 monkey-patch 测试（progress 是否能被持续上报）
3. 再新建 asr_model_check.py，空实现 _check_model_ready_worker
4. 接入现有 pyutil 设备分析工具，生成 device / compute_type 候选列表
5. 实现 try_load_whisper_model(..., local_files_only=True) 本地检查
6. 实现 allow_download=true 时的 local_files_only=False 第二次加载
7. 最后做错误分类归一
8. 运行 @pytest.mark.download 测试（手动触发）
```

#### 9.3.2 TDD 顺序建议

| 步骤 | 实现内容 | 新增测试覆盖 |
|------|---------|------------|
| 3a | 提取 HuggingFace 下载 monkey-patch 包装层 | §8.2.2 的 4 个 monkey-patch 测试 |
| 3b | `_check_model_ready_worker` 空实现 | 附录 A / A.2（空目录 / 不存在） |
| 3c | 接入现有 pyutil 设备分析工具，生成候选 `device + compute_type` | 新增 `choose_whisper_load_candidates` 单测 |
| 3d | 实现 `try_load_whisper_model(..., local_files_only=True)` | 附录 A / A.3、A.5、A.8 |
| 3e | 实现 `allow_download=true` 时 `local_files_only=False` 的第二次尝试 | 附录 A / A.6、A.7 |
| 3f | 汇总 `tried[]` 并做最终错误分类 | 附录 A / A.8 |
| 3g | 新增 FastAPI `GET /asr/check-model-ready/` | Kotlin 集成调用验证 |

#### 9.3.3 本阶段最重要的设计约束

- **不要把 `compute_type` 固定写死为某个值后就当成“完整性结论”**
- **设备分析工具只能给候选，不能代替真实加载**
- **完整性检查必须区分两次加载：**
  - `local_files_only=True`：判断本地是否已有可用模型
  - `local_files_only=False`：判断是否可通过下载/补全恢复
- **下载进度 hook 要先独立测试，再接入 worker**

---

### 9.4 Phase 4 — AsrSpawnChunksExecutor（Kotlin）

这一阶段只保留 `AsrSpawnChunksExecutor` 的**轻量职责**：
- 枚举 chunk 文件
- 为每个 chunk 创建独立的 `TRANSCRIBE` Task（含 `chunkIndex`、`totalChunks`、`chunkOffsetSec`、`coreStartSec`、`coreEndSec`）
- 更新 `WorkflowRun.totalTasks`

因此测试围绕”是否正确创建 N 个独立 TRANSCRIBE task”。

#### 9.4.1 测试目标

- 正确枚举 chunk 文件并创建 N 个独立 `TRANSCRIBE` Task
- 幂等：重复执行不重复创建 task
- 正确更新 `WorkflowRun.totalTasks`
- 每个 `TRANSCRIBE` Task 的 `dependsOn` 正确指向 spawn task
- 0 个 chunk 文件时返回 error

#### 9.4.2 测试文件

`shared/src/jvmTest/kotlin/com/github/project_fredica/worker/executors/AsrSpawnChunksExecutorTest.kt`

```kotlin
// =============================================================================
// AsrSpawnChunksExecutorTest —— 验证”N 个独立 TRANSCRIBE Task 创建”职责
// =============================================================================
//
// 测试矩阵：
//   S1. 3 个 chunk 文件 → 创建 3 个独立 TRANSCRIBE task
//   S2. 幂等：重复执行不重复创建 task
//   S3. WorkflowRun.totalTasks 从 base 更新为 base + N
//   S4. 每个 TRANSCRIBE task 的 dependsOn 指向 spawn task ID
//   S5. 0 个 chunk 文件 → 返回 error
// =============================================================================

@Test
fun `S1 - creates N independent TRANSCRIBE tasks`() = runBlocking {
    // 断言 task type = TRANSCRIBE，数量 = chunk 数量
    // 每个 task 含正确的 chunkIndex / totalChunks / chunkOffsetSec / coreStartSec / coreEndSec
}

@Test
fun `S2 - execute twice does not duplicate tasks`() = runBlocking {
    // 第二次执行后 TRANSCRIBE task 数量不变
}

@Test
fun `S3 - totalTasks increases by N after spawn`() = runBlocking {
    // 初始 2 → 执行后 2 + N
}

@Test
fun `S4 - each TRANSCRIBE task dependsOn spawn task id`() = runBlocking {
    // 每个 TRANSCRIBE task 的 dependsOn 指向 spawn task
}

@Test
fun `S5 - empty chunk dir returns error`() = runBlocking {
    // 无 chunk 时不应创建 TRANSCRIBE task
}
```

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.worker.executors.AsrSpawnChunksExecutorTest"
```

---

### 9.5 Phase 5 — TranscribeExecutor（ASR chunk 处理，Kotlin）

这是 ASR 主链的核心阶段，每个 chunk 一个独立 TRANSCRIBE Task，重点验证 **per-chunk 执行 + GpuResourceLock 串行 + canSkip + 暂停/恢复/取消**。

#### 9.5.1 测试目标

- 每个 TRANSCRIBE Task 独立处理一个 chunk（通过 `chunkIndex` 定位）
- `canSkip`：`chunk_XXXX.done` 存在且 inputHash + modelSize + language 匹配时跳过
- GpuResourceLock 保证同一时刻只有一个 GPU 推理任务运行
- 每个 Task 独立上报 progress
- `pause` 到来时当前 chunk 挂起
- `resume` 后继续当前 chunk
- `cancel` 后当前 chunk 停止，已完成 chunk 保留
- 当前 chunk 取消/失败时不写 `.done`
- 最后完成的 chunk 触发 `tryMergeChunks()`

#### 9.5.2 测试文件

`shared/src/jvmTest/kotlin/com/github/project_fredica/worker/executors/TranscribeExecutorTest.kt`

```kotlin
// =============================================================================
// TranscribeExecutorTest —— per-chunk 执行 / GpuResourceLock / pause-resume-cancel 测试
// =============================================================================
//
// 测试策略：
//   - mock PythonUtil.Py314Embed.PyUtilServer.websocketTask
//   - 使用 tmpDir 构造真实 chunk_*.m4a / chunk_*.srt / chunk_*.done
//   - 不验证 faster-whisper 本身，只验证 Kotlin per-chunk 调度、进度与清理语义
//
// 测试矩阵：
//   Q1. 单个 chunk 正确调用 Python（传入 chunkIndex、modelSize、language）
//   Q2. canSkip 在 chunk_XXXX.done + hash 命中时返回 true
//   Q3. canSkip 在 chunk_XXXX.done 不存在时返回 false
//   Q4. 每个 Task 独立上报 progress（0-100）
//   Q5. pause 后当前 chunk 挂起
//   Q6. resume 后继续当前 chunk
//   Q7. cancel 后当前 chunk 停止，不写 .done
//   Q8. 最后完成的 chunk 触发 tryMergeChunks()（检查 done 文件数 == totalChunks）
// =============================================================================

@Test
fun `Q1 - executes single chunk with correct parameters`() = runBlocking {
    // 验证 websocketTask 调用参数含 chunkIndex、modelSize、language
}

@Test
fun `Q2 - canSkip returns true when chunk done and hash matches`() = runBlocking {
    // 预先写 chunk_XXXX.done + 匹配的 hash，canSkip 应返回 true
}

@Test
fun `Q3 - canSkip returns false when chunk done missing`() = runBlocking {
    // 无 chunk_XXXX.done 时 canSkip 应返回 false
}

@Test
fun `Q4 - updates per-task progress independently`() = runBlocking {
    // 验证 onProgress 回调正确更新当前 Task 的 progress
}

@Test
fun `Q5 - pause suspends current chunk`() = runBlocking {
    // 当前 chunk 执行中收到 pause 后挂起
}

@Test
fun `Q6 - resume continues current chunk`() = runBlocking {
    // pause 后 resume，当前 chunk 继续执行
}

@Test
fun `Q7 - cancel stops chunk and leaves no done marker`() = runBlocking {
    // cancel 后不应写 chunk_XXXX.done
}

@Test
fun `Q8 - last chunk triggers tryMergeChunks`() = runBlocking {
    // 当 done 文件数 == totalChunks 时触发合并
}
```

#### 9.5.3 暂停 / 恢复 / 中止（清理）专项约束

- **暂停**：
  - 每个 TRANSCRIBE Task 独立支持 pause
  - 当前 Python 子进程收到 pause 后挂起
  - GpuResourceLock 在 pause 期间释放，允许其他优先级任务执行
- **恢复**：
  - 恢复当前挂起 chunk，重新获取 GpuResourceLock
- **中止**：
  - 当前 Python 子进程收到 cancel
  - 已完成 chunk 保留（chunk_XXXX.done 不删除）
  - 当前未完成 chunk 的 `.tmp` / 不完整 `.srt` 删除
  - 其他未开始的 TRANSCRIBE Task 由 WorkerEngine 统一取消
- **失败清理**：
  - 若当前 chunk 失败，不写 `chunk_XXXX.done`
  - 单个 TRANSCRIBE Task 失败不影响其他已完成的 chunk

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.worker.executors.TranscribeExecutorTest"
```

---

### 9.6 Phase 6 — TranscribeExecutor.tryMergeChunks()（Kotlin）

#### 9.6.1 测试目标

- 触发条件：`chunk_XXXX.done` 文件数 == `totalChunks` 时触发合并
- 未全部完成时不触发合并
- Core region 过滤：最后一个 chunk 用闭区间 `[coreStart, coreEnd]`，其余用右开区间 `[coreStart, coreEnd)`
- 时间偏移合并：chunk_0001 的 segment 加 `chunkOffsetSec` 偏移
- SRT 输出格式正确
- 成功后写入 `transcript.done` 文件（含 `completed_at` 和 `detected_language`）+ `transcript.srt`

#### 9.6.2 测试文件

`shared/src/jvmTest/kotlin/com/github/project_fredica/worker/executors/TranscribeExecutorTest.kt`（tryMergeChunks 部分）

```kotlin
// =============================================================================
// TranscribeExecutor.tryMergeChunks() —— SRT 合并与 core region 过滤测试
// =============================================================================
//
// 测试策略：
//   - 不依赖真实 Python 进程：直接在 tmpDir 写入模拟的 chunk_*.srt / chunk_*.done 文件
//   - 覆盖触发条件 + core region 过滤 + 合并逻辑 + transcript.done 写入
//
// 测试矩阵：
//   M1. done 文件数 == totalChunks 时触发合并
//   M2. done 文件数 < totalChunks 时不触发合并
//   M3. 单块（chunk_0000）合并后时间戳不变（偏移 0s）
//   M4. 多块合并：chunk_0001 的 segment 时间 + chunkOffsetSec 偏移
//   M5. core region 过滤：overlap 区域的 segment 被排除
//   M6. 合并成功后生成 transcript.srt 和 transcript.done 文件
//   M7. transcript.done 内容含 completed_at / detected_language / transcript_hash
// =============================================================================
package com.github.project_fredica.worker.executors

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class TryMergeChunksTest {

    private lateinit var tmpAsrDir: File

    @BeforeTest
    fun setup() {
        tmpAsrDir = createTempDir("asr_merge_test_")
    }

    @AfterTest
    fun teardown() {
        tmpAsrDir.deleteRecursively()
    }

    private fun writeChunkSrt(index: Int, content: String) {
        tmpAsrDir.resolve("chunk_%04d.srt".format(index)).writeText(content)
    }

    private fun writeChunkDone(index: Int) {
        tmpAsrDir.resolve("chunk_%04d.done".format(index)).writeText("{}")
    }

    // ── M1/M2：触发条件 ──────────────────────────────────────────────────

    @Test
    fun `M1 - triggers merge when all chunks done`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:01,000 --> 00:00:04,500\nHello\n")
        writeChunkDone(0)
        // totalChunks = 1，done 文件数 = 1 → 触发合并
        // 断言 transcript.srt 已生成
        Unit
    }

    @Test
    fun `M2 - does not merge when chunks incomplete`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:01,000 --> 00:00:04,500\nHello\n")
        writeChunkDone(0)
        // totalChunks = 2，done 文件数 = 1 → 不触发合并
        // 断言 transcript.srt 不存在
    }

    // ── M3：单块，时间戳不变 ──────────────────────────────────────────────

    @Test
    fun `M3 - single chunk SRT timestamps are unchanged`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:01,000 --> 00:00:04,500\nHello\n")
        writeChunkDone(0)
        // 触发合并后，transcript.srt 时间戳 = 原始值
    }

    // ── M4：多块，时间偏移 ────────────────────────────────────────────────

    @Test
    fun `M4 - chunk_0001 segments have chunkOffsetSec time offset`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:01,000 --> 00:00:02,000\nFirst\n")
        writeChunkDone(0)
        writeChunkSrt(1, "1\n00:00:00,500 --> 00:00:01,500\nSecond\n")
        writeChunkDone(1)
        // chunk_0001 的 chunkOffsetSec = 300 → 时间 +300s
        // 断言 transcript.srt 含 "00:05:00,500 --> 00:05:01,500"
    }

    // ── M5：core region 过滤 ─────────────────────────────────────────────

    @Test
    fun `M5 - overlap segments outside core region are excluded`() = runBlocking {
        // chunk_0000: coreStart=0, coreEnd=240（右开区间 [0, 240)）
        // chunk_0001: coreStart=0, coreEnd=300（最后一个 chunk，闭区间 [0, 300]）
        // overlap 区域（240-300s of chunk_0000）的 segment 应被排除
    }

    // ── M6/M7：文件输出 ───────────────────────────────────────────────────

    @Test
    fun `M6 - produces transcript-srt and transcript-done files`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:00,000 --> 00:00:01,000\nTest\n")
        writeChunkDone(0)
        // 触发合并
        // 断言 transcript.srt 和 transcript.done 均存在
    }

    @Test
    fun `M7 - transcript-done contains completed_at and detected_language`() = runBlocking {
        writeChunkSrt(0, "1\n00:00:00,000 --> 00:00:01,000\nTest\n")
        writeChunkDone(0)
        // 触发合并
        // 断言 transcript.done 含 completed_at / detected_language / transcript_hash
    }
}
```

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.worker.executors.TryMergeChunksTest"
```

---

### 9.7 Phase 7 — MaterialAsrStartRoute（Kotlin）

#### 9.7.1 测试目标

- `transcript.done` 已存在且 `disable_cache=false` → 返回 `already_done`
- `disable_cache=true` → 即使已有缓存也要继续创建 workflow
- `transcode.done` 和 `extract_audio.done` 均不存在 → 返回 `error`
- 正常启动 → 返回 `workflow_run_id`，创建 WorkflowRun 及初始 3 个 Task
- `asrAllowDownload=true` 时任务链头部有 `DOWNLOAD_WHISPER_MODEL`

#### 9.7.2 测试文件

`shared/src/jvmTest/kotlin/com/github/project_fredica/api/routes/MaterialAsrStartRouteTest.kt`

```kotlin
// =============================================================================
// MaterialAsrStartRouteTest —— MaterialAsrStartRoute handler 集成测试
// =============================================================================
//
// 测试策略：
//   - 直接调用 route handler()（不启动 HTTP 服务器）
//   - 使用 SQLite 临时文件 + tmpDir 模拟素材目录
//   - 断言返回 JSON 字段 + 数据库状态
//
// 测试矩阵：
//   R1. transcript.done 已存在 + disable_cache=false → already_done
//   R2. disable_cache=true + 已有 transcript.done → 仍创建新 workflow
//   R3. 无视频文件 → error（素材未处理）
//   R4. `transcode.done + hash` 命中时 → 成功创建 WorkflowRun，含正确 totalTasks
//   R5. asrAllowDownload=true → 任务链首位有 DOWNLOAD_WHISPER_MODEL task
//   R6. asrAllowDownload=false + 模型缺失 → error（Python check 失败）
// =============================================================================
package com.github.project_fredica.api.routes

import com.github.project_fredica.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.ktorm.database.Database
import java.io.File
import kotlin.test.*

class MaterialAsrStartRouteTest {

    private lateinit var db: Database
    private lateinit var tmpMediaDir: File
    private val testMaterialId = "test-asr-${System.nanoTime()}"

    @BeforeTest
    fun setup() = runBlocking {
        val tmpFile = File.createTempFile("asr_route_test_", ".db").also { it.deleteOnExit() }
        db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
        // 初始化所有 Service（参考 MaterialVideoCheckRouteTest 模式）
        val materialDb = MaterialDb(db)
        materialDb.initialize()
        MaterialService.initialize(materialDb)
        val taskDb = TaskDb(db)
        val workflowRunDb = WorkflowRunDb(db)
        taskDb.initialize()
        workflowRunDb.initialize()
        TaskService.initialize(taskDb)
        WorkflowRunService.initialize(workflowRunDb)

        tmpMediaDir = AppUtil.Paths.materialMediaDir(testMaterialId)
        tmpMediaDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tmpMediaDir.deleteRecursively()
    }

    // ── R1：已完成 ────────────────────────────────────────────────────────

    @Test
    fun `R1 - returns already_done when transcript-done exists`() = runBlocking {
        val asrDir = tmpMediaDir.resolve("asr_results/tiny")
        asrDir.mkdirs()
        asrDir.resolve("transcript.done").writeText("{}")

        val result = MaterialAsrStartRoute.handler(
            """{"material_id":["$testMaterialId"],"model_size":["tiny"],"language":["zh"]}"""
        )
        val json = Json.parseToJsonElement(result.str).jsonObject
        assertEquals("already_done", json["status"]?.jsonPrimitive?.content)
    }

    // ── R2：前置文件缺失 ──────────────────────────────────────────────────

    @Test
    fun `R2 - returns error when no transcode-done or extract-audio-done`() = runBlocking {
        val result = MaterialAsrStartRoute.handler(
            """{"material_id":["$testMaterialId"],"model_size":["tiny"],"language":["zh"]}"""
        )
        val json = Json.parseToJsonElement(result.str).jsonObject
        assertNotNull(json["error"], "应返回 error 字段")
    }

    // ── R3：正常启动 ──────────────────────────────────────────────────────

    /**
     * 证明目的：`transcode.done + hash` 命中时，handler 返回 workflow_run_id，
     *           并在数据库中创建 WorkflowRun 和至少 3 个初始 Task。
     */
    @Test
    fun `R3 - creates WorkflowRun and returns workflow_run_id`() = runBlocking {
        tmpMediaDir.resolve("transcode.done").createNewFile()

        val result = MaterialAsrStartRoute.handler(
            """{"material_id":["$testMaterialId"],"model_size":["tiny"],"language":["zh"]}"""
        )
        val json = Json.parseToJsonElement(result.str).jsonObject
        val runId = json["workflow_run_id"]?.jsonPrimitive?.content
        assertNotNull(runId, "应返回 workflow_run_id")

        val run = WorkflowRunService.repo.getById(runId)
        assertNotNull(run, "WorkflowRun 应已创建")
        assertTrue(run.totalTasks >= 3, "初始 totalTasks 应 >= 3")

        val tasks = TaskService.repo.listByWorkflowRun(runId)
        assertTrue(tasks.isNotEmpty(), "应有任务创建")
    }
}
```

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.api.routes.MaterialAsrStartRouteTest"
```

---

### 9.8 Phase 8 — DownloadWhisperModelExecutor canSkip 修改

#### 9.8.1 测试目标

- `canSkip` 旧逻辑（目录名检查）改为调用 Python HTTP 接口 → 新测试验证 `canSkip` 在 mock Python 返回 `ready=true/false` 时的行为

#### 9.8.2 测试文件

`shared/src/jvmTest/kotlin/com/github/project_fredica/worker/executors/DownloadWhisperModelExecutorCanSkipTest.kt`

```kotlin
// =============================================================================
// DownloadWhisperModelExecutorCanSkipTest —— canSkip 新逻辑测试
// =============================================================================
//
// 测试策略：
//   - mock HTTP 调用（PythonUtil.requestText）返回预设 JSON
//   - 不需要真实 Python 服务
//
// 测试矩阵：
//   D1. Python 返回 {"ready":true}  → canSkip = true（跳过下载）
//   D2. Python 返回 {"ready":false} → canSkip = false（触发下载）
//   D3. Python 调用抛异常（服务未启动）→ canSkip = false（保守策略）
// =============================================================================
package com.github.project_fredica.worker.executors

import com.github.project_fredica.db.Task
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DownloadWhisperModelExecutorCanSkipTest {

    private fun buildTask(modelName: String = "tiny") = Task(
        id = "dl-1", type = "DOWNLOAD_WHISPER_MODEL",
        workflowRunId = "run-dl", materialId = "mat-1",
        priority = 0, dependsOn = "[]",
        createdAt = System.currentTimeMillis() / 1000,
        payload = """{"model_name":"$modelName"}"""
    )

    @Test
    fun `D1 - canSkip true when Python returns ready=true`() = runBlocking {
        // 通过 mockk 或直接替换 PythonUtil 的 requestText 实现
        // （具体 mock 方式取决于 PythonUtil 的可注入性，
        //  若不可 mock 则改为集成测试：启动 Python 服务后测试）
        // 此处给出概念验证，实际实现时根据代码结构调整
        val executor = DownloadWhisperModelExecutor()
        // 预期：若 Python 返回 ready=true，canSkip=true
        // 实际运行需 Python 服务，建议通过 @assumeTrue 跳过未启动的环境
    }

    @Test
    fun `D3 - canSkip false when Python throws exception`() = runBlocking {
        // 断言：PythonUtil 调用失败（连接被拒绝）时 canSkip 应 = false，不抛出异常
        // 实际验证：在 Python 服务未启动时调用，断言返回 false 而非崩溃
        val executor = DownloadWhisperModelExecutor()
        // 使用 assumeTrue 或通过 mock 框架注入异常
    }
}
```

> **注**：`DownloadWhisperModelExecutor.canSkip` 调用 Python HTTP 服务，属于集成测试范畴。若 mock `PythonUtil` 代价较高，可改为：
> - 在 Python 服务启动的 CI 环境中运行集成测试
> - 使用 `@BeforeTest` 中 `assumeTrue(isPythonServiceRunning())` 跳过未启动的环境

---

### 9.9 Phase 9 — 前端 ASR 工具函数

#### 9.9.1 测试目标

- `asr.ts` 中 `startAsrTask()` 封装正确（成功 + 错误响应处理）
- `TASK_PHASE_LABEL` 映射完整（所有 task type 有对应中文标签）
- 进度聚合函数：`computeAsrProgress()` 正确计算整体百分比

#### 9.9.2 测试文件

`fredica-webui/tests/util/asr.test.ts`

```typescript
// 运行：cd fredica-webui && npx vitest run tests/util/asr.test.ts

import { describe, it, expect, vi, beforeEach } from "vitest";
import { TASK_PHASE_LABEL, computeAsrProgress } from "~/util/asr";

// ── TASK_PHASE_LABEL 完整性 ───────────────────────────────────────────────

describe("TASK_PHASE_LABEL", () => {
  const EXPECTED_TYPES = [
    "EXTRACT_AUDIO",
    "ASR_SPAWN_CHUNKS",
    "TRANSCRIBE",
  ];

  it("A1 - 所有已知 task type 都有对应标签", () => {
    for (const type of EXPECTED_TYPES) {
      expect(TASK_PHASE_LABEL[type]).toBeTruthy();
    }
  });

  it("A2 - 所有标签为非空字符串", () => {
    for (const [, label] of Object.entries(TASK_PHASE_LABEL)) {
      expect(typeof label).toBe("string");
      expect(label.length).toBeGreaterThan(0);
    }
  });
});

// ── computeAsrProgress ────────────────────────────────────────────────────

describe("computeAsrProgress", () => {
  /** 构造模拟 task 列表 */
  function makeTasks(specs: Array<{ type: string; status: string; progress?: number }>) {
    return specs.map((s, i) => ({
      id: `t-${i}`,
      type: s.type,
      status: s.status,
      progress: s.progress ?? 0,
    }));
  }

  it("A3 - 全部完成时进度 = 100", () => {
    const tasks = makeTasks([
      { type: "TRANSCODE_MP4", status: "completed" },
      { type: "EXTRACT_AUDIO", status: "completed" },
      { type: "ASR_SPAWN_CHUNKS", status: "completed" },
    ]);
    expect(computeAsrProgress(tasks)).toBe(100);
  });

  it("A4 - 0 个已完成时进度 = 0", () => {
    const tasks = makeTasks([
      { type: "TRANSCRIBE", status: "pending" },
      { type: "TRANSCRIBE", status: "pending" },
    ]);
    expect(computeAsrProgress(tasks)).toBe(0);
  });

  it("A5 - 队列任务 in_progress(60%) 时整体进度可反映中间态", () => {
    const tasks = makeTasks([
      { type: "TRANSCODE_MP4", status: "completed", progress: 100 },
      { type: "EXTRACT_AUDIO", status: "completed", progress: 100 },
      { type: "TRANSCRIBE", status: "in_progress", progress: 60 },
      { type: "TRANSCRIBE", status: "pending", progress: 0 },
    ]);
    const result = computeAsrProgress(tasks);
    expect(result).toBeCloseTo(65, 0);
  });

  it("A6 - 空任务列表返回 0", () => {
    expect(computeAsrProgress([])).toBe(0);
  });
});
```

**运行命令**：
```shell
cd fredica-webui && npx vitest run tests/util/asr.test.ts
```

---

### 9.9.3 本地转译结果管理页（jsBridge only）

本节对应 **§2.7**，强调：
- 仅桌面端 / jsBridge 可用时启用
- 不对 Route API 用户开放
- 测试重点不是 HTTP 鉴权，而是 **bridge 可见性、路径安全、受控删除**

#### 测试目标

分三层：
1. **前端页面测试**：bridge 可用/不可用时入口与页面行为正确
2. **前端 bridge util 测试**：调用参数正确，bridge 返回 `error` 时正确上抛/提示
3. **Kotlin JsBridge handler 测试**：
   - 仅接受 `material_id + result_id`
   - 路径归一化后必须位于 `materialMediaDir(materialId)` 下
   - 删除只作用于受控目录，拒绝目录穿越 / 非法 result_id

#### 测试文件 A：前端页面

`fredica-webui/tests/routes/local-resources.test.tsx`
`fredica-webui/tests/routes/local-resources.asr.test.tsx`

```typescript
// 运行：
// cd fredica-webui && npx vitest run tests/routes/local-resources.test.tsx tests/routes/local-resources.asr.test.tsx

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import LocalResourcesPage from "~/routes/local-resources";
import LocalResourcesAsrPage from "~/routes/local-resources.asr";

function stubBridge(methodImpl: (method: string, params: unknown) => unknown) {
  vi.stubGlobal("window", {
    ...window,
    kmpJsBridge: {
      callNative: vi.fn(async (method: string, raw: string) => {
        const params = JSON.parse(raw);
        return JSON.stringify(await methodImpl(method, params));
      }),
    },
  });
}

describe("local resources pages", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("MG1 - bridge 不可用时 local-resources 显示仅桌面端可用", async () => {
    vi.stubGlobal("window", { ...window, kmpJsBridge: undefined });
    render(<LocalResourcesPage />);
    expect(await screen.findByText("该页面仅在桌面端可用")).toBeInTheDocument();
  });

  it("MG2 - local-resources 首页展示 ASR 资源入口", async () => {
    stubBridge(() => ({}));
    render(<LocalResourcesPage />);
    expect(await screen.findByText("ASR 资源")).toBeInTheDocument();
  });

  it("MG3 - bridge 可用时 local-resources/asr 拉取并展示跨素材结果列表", async () => {
    stubBridge((method) => {
      if (method === "list_all_asr_results") {
        return {
          items: [
            {
              id: "asr:m1:tiny",
              kind: "asr",
              source: "faster-whisper",
              label: "ASR / tiny / zh",
              materialId: "m1",
              materialTitle: "视频 1",
              language: "zh",
              exists: true,
              hasDoneMarker: true,
              importedToDb: true,
            },
          ],
        };
      }
      throw new Error(`unexpected method ${method}`);
    });

    render(<LocalResourcesAsrPage />);
    expect(await screen.findByText("视频 1")).toBeInTheDocument();
    expect(await screen.findByText("ASR / tiny / zh")).toBeInTheDocument();
  });

  it("MG4 - bridge 返回 error 时展示错误提示", async () => {
    stubBridge((method) => {
      if (method === "list_all_asr_results") {
        return { error: "读取失败" };
      }
      return {};
    });

    render(<LocalResourcesAsrPage />);
    expect(await screen.findByText(/读取失败/)).toBeInTheDocument();
  });
});
```

#### 测试文件 B：前端 util

`fredica-webui/tests/util/asr-manage.test.ts`

```typescript
import { describe, it, expect, vi } from "vitest";
import {
  listAllAsrResults,
  getAsrResultDetail,
  deleteAsrResult,
  reimportAsrResult,
} from "~/util/asr";

describe("asr manage bridge util", () => {
  it("MU1 - listAllAsrResults 调用正确 bridge method，且不传 material_id", async () => {
    const callNative = vi.fn(async () => JSON.stringify({ items: [] }));
    vi.stubGlobal("window", { ...window, kmpJsBridge: { callNative } });

    await listAllAsrResults();

    expect(callNative).toHaveBeenCalledTimes(1);
    expect(callNative.mock.calls[0][0]).toBe("list_all_asr_results");
    expect(JSON.parse(callNative.mock.calls[0][1])).toEqual({});
  });

  it("MU2 - deleteAsrResult 只传 result_id，不传路径", async () => {
    const callNative = vi.fn(async () => JSON.stringify({ ok: true }));
    vi.stubGlobal("window", { ...window, kmpJsBridge: { callNative } });

    await deleteAsrResult("asr:m1:tiny");

    const payload = JSON.parse(callNative.mock.calls[0][1]);
    expect(payload).toEqual({ result_id: "asr:m1:tiny" });
    expect(payload.result_dir).toBeUndefined();
    expect(payload.path).toBeUndefined();
    expect(payload.material_id).toBeUndefined();
  });

  it("MU3 - getAsrResultDetail 调用正确 bridge method", async () => {
    const callNative = vi.fn(async () => JSON.stringify({ item: { id: "asr:m1:tiny" } }));
    vi.stubGlobal("window", { ...window, kmpJsBridge: { callNative } });

    await getAsrResultDetail("asr:m1:tiny");

    expect(callNative.mock.calls[0][0]).toBe("get_asr_result_detail");
    expect(JSON.parse(callNative.mock.calls[0][1])).toEqual({ result_id: "asr:m1:tiny" });
  });
});
```

#### 测试文件 C：Kotlin JsBridge handler

`shared/src/jvmTest/kotlin/com/github/project_fredica/jsbridge/AsrResultJsBridgeTest.kt`

```kotlin
// =============================================================================
// AsrResultJsBridgeTest —— 本地 ASR 资源 bridge handler 测试
// =============================================================================
//
// 测试矩阵：
//   J1. list_all_asr_results: 遍历 media/*/asr_results/ 子目录，返回结构化 item
//   J2. get_asr_result_detail: 合法 result_id 返回详情
//   J3. delete_asr_result: 合法 result_id 可删除目标目录
//   J4. delete_asr_result: 非法 result_id（如 ../../x）返回 error
//   J5. delete_asr_result: normalize 后越出 materialMediaDir 时拒绝
//   J6. reimport_asr_result: transcript.srt 存在且 DB 缺失时，补建字幕记录
// =============================================================================
package com.github.project_fredica.jsbridge

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class AsrResultJsBridgeTest {

    @Test
    fun `J1 - list returns ASR result items across all materials`() = runBlocking {
        // 1. 在 media/m1/asr_results/tiny 和 media/m2/asr_results/base 下写 transcript.srt / transcript.done
        // 2. 调 list_all_asr_results handler
        // 3. 断言返回两个 item，且 materialId 分别为 m1 / m2
    }

    @Test
    fun `J4 - delete rejects illegal result_id with path traversal`() = runBlocking {
        // result_id = "../../outside"
        // 断言 bridge 返回 {error: ...}，且不会删除任何文件
    }

    @Test
    fun `J5 - delete rejects normalized path outside material dir`() = runBlocking {
        // 即使字符串经过拼接后可 normalize 到素材目录外，也必须拒绝
    }
}
```

#### 推荐 TDD 顺序

| 步骤 | 先写的测试 | 再写的最小实现 |
|------|-----------|---------------|
| 8.5a | `MG1` | `local-resources` 页面在无 bridge 时直接显示"仅桌面端可用" |
| 8.5b | `MG2` | `local-resources` 资源导航页 |
| 8.5c | `MU1` | `listAllAsrResults()` util |
| 8.5d | `J1` | `list_all_asr_results` Kotlin handler |
| 8.5e | `MU2` / `MU3` | detail/delete util 只传 `result_id` |
| 8.5f | `J4` / `J5` | 删除 handler 的 `normalize + startsWith(baseDir)` 安全校验 |
| 8.5g | `MG3` / `MG4` | `local-resources/asr` 列表页的加载、错误态、分组展示 |
| 8.5h | `J6` | 重导入 handler |

#### 运行命令

```shell
cd fredica-webui && npx vitest run tests/routes/local-resources.test.tsx tests/routes/local-resources.asr.test.tsx tests/util/asr-manage.test.ts
./gradlew :shared:jvmTest --tests "com.github.project_fredica.jsbridge.AsrResultJsBridgeTest"
```

---

### 9.10 各 Phase 测试运行命令汇总

| Phase | 测试文件 | 运行命令 |
|-------|---------|---------|
| 1 字幕导出 | `tests/util/subtitleExport.test.ts` | `cd fredica-webui && npx vitest run tests/util/subtitleExport.test.ts` |
| 2 文件锁 | `tests/test_file_lock_util.py` | `cd desktop_assets/common/fredica-pyutil && ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_file_lock_util.py -v` |
| 3 模型检查 | `tests/test_asr_model_check.py` | `cd desktop_assets/common/fredica-pyutil && ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_asr_model_check.py -v -m "not download"` |
| 3 模型检查（集成） | `tests/test_asr_model_check.py` | `cd desktop_assets/common/fredica-pyutil && ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_asr_model_check.py -v -m "download" -s` |
| 4 队列生成 | `AsrSpawnChunksExecutorTest.kt` | `./gradlew :shared:jvmTest --tests "*.AsrSpawnChunksExecutorTest"` |
| 5 chunk 转录 | `TranscribeExecutorTest.kt` | `./gradlew :shared:jvmTest --tests "*.TranscribeExecutorTest"` |
| 6 合并逻辑 | `TryMergeChunksTest.kt` | `./gradlew :shared:jvmTest --tests "*.TryMergeChunksTest"` |
| 7 启动路由 | `MaterialAsrStartRouteTest.kt` | `./gradlew :shared:jvmTest --tests "*.MaterialAsrStartRouteTest"` |
| 8 模型 canSkip | `DownloadWhisperModelExecutorCanSkipTest.kt` | `./gradlew :shared:jvmTest --tests "*.DownloadWhisperModelExecutorCanSkipTest"` |
| 9 前端 ASR 工具 | `tests/util/asr.test.ts` | `cd fredica-webui && npx vitest run tests/util/asr.test.ts` |
| 9.3 管理页（jsBridge） | `tests/routes/local-resources.test.tsx` + `tests/routes/local-resources.asr.test.tsx` + `tests/util/asr-manage.test.ts` + `AsrResultJsBridgeTest.kt` | `cd fredica-webui && npx vitest run tests/routes/local-resources.test.tsx tests/routes/local-resources.asr.test.tsx tests/util/asr-manage.test.ts` + `./gradlew :shared:jvmTest --tests "*.AsrResultJsBridgeTest"` |
| 全量 Kotlin | — | `./gradlew :shared:jvmTest` |
| 全量前端 | — | `cd fredica-webui && npx vitest run` |

### 9.11 测试驱动开发节奏

每个 Phase 遵循以下节奏：

```
① 写测试（红灯）
   → 运行命令确认测试失败（因为实现尚不存在）

② 写最小实现（绿灯）
   → 只实现当前测试所需的代码，不多写

③ 重构（可选）
   → 消除重复，提取公共逻辑，测试仍应通过

④ 发现边界 → 补充测试用例
   → 例如：发现队列进度聚合有 off-by-one → 补 Q4 变体

⑤ commit（wip 或 feat）
```

---

## 十、ASR 配置管理页面（Phase 9） <Badge type="tip" text="已完成" />

> 对应需求：服主权限管理（允许下载开关、允许模型列表）+ 桌面设置入口。

### 10.1 功能概述

在桌面设置页面新增 **FasterWhisper ASR 配置** 入口，提供以下管理能力：

1. **允许下载开关**（`asrAllowDownload`）：控制运行时是否允许下载新模型
2. **禁用模型黑名单**（`asrDisallowedModels`）：禁止用户使用的模型列表（空 = 全部允许）
3. **模型测试入口**：跳转到模型预下载与测试功能（详见 §11）

### 10.2 数据模型

#### AppConfig 新增字段

| 字段 | SerialName | 类型 | 默认值 | 用途 |
|------|-----------|------|--------|------|
| `asrAllowDownload` | `asr_allow_download` | `Boolean` | `true` | 是否允许运行时下载模型 |
| `asrDisallowedModels` | `asr_disallowed_models` | `String` | `""` | 禁止使用的模型黑名单（逗号分隔，空=全部允许） |
| `asrTestAudioPath` | `asr_test_audio_path` | `String` | `""` | 模型测试用音频文件路径 |
| `asrTestWaveCount` | `asr_test_wave_count` | `Int` | `10` | 模型测试推理波数 |

**3 处 lockstep 更新**：`AppConfig.kt` 字段 + `AppConfigDb.kt` 的 `defaultKv`/`toKvMap()`/`toAppConfig()`

#### ASR 配置响应/请求模型

**文件**：`shared/src/commonMain/.../asr/model/AsrConfigModels.kt`

```kotlin
@Serializable
data class AsrConfigResponse(
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("disallowed_models") val disallowedModels: String,
    @SerialName("test_audio_path") val testAudioPath: String,
    @SerialName("test_wave_count") val testWaveCount: Int,
)

@Serializable
data class AsrConfigSaveParam(
    @SerialName("allow_download") val allowDownload: Boolean? = null,
    @SerialName("disallowed_models") val disallowedModels: String? = null,
    @SerialName("test_audio_path") val testAudioPath: String? = null,
    @SerialName("test_wave_count") val testWaveCount: Int? = null,
)
```

### 10.3 服务层：AsrConfigService

**文件**：`shared/src/commonMain/.../asr/service/AsrConfigService.kt`

**架构原则**：所有 ASR 配置业务逻辑放在 `asr/service/` 下，bridge handler 和 route 只做薄层委托。

```kotlin
object AsrConfigService {
    /** 读取 ASR 配置（从 AppConfigService 提取 ASR 相关字段） */
    fun getAsrConfig(): AsrConfigResponse

    /** 保存 ASR 配置（部分更新） */
    suspend fun saveAsrConfig(param: AsrConfigSaveParam)

    /** 检查模型是否被允许使用（空黑名单=全部允许，非空时黑名单内禁用） */
    fun isModelAllowed(model: String): Boolean

    /** 从候选模型列表中排除黑名单中的模型 */
    fun filterDisallowedModels(allModels: List<String>): List<String>

    /** 检查是否允许下载（服主配置优先） */
    fun isDownloadAllowed(): Boolean
}
```

### 10.4 API 层

#### Route Extension（HTTP API，供 Web 模式使用）

**文件**：`shared/src/commonMain/.../asr/route_ext/AsrConfigRouteExt.kt`

| 方法 | 路径 | 委托 |
|------|------|------|
| GET | `/api/asr/config` | `AsrConfigService.getAsrConfig()` |
| POST | `/api/asr/config` | `AsrConfigService.saveAsrConfig(param)` |

#### Bridge Handlers（桌面端 jsBridge，薄层委托）

**文件**：`composeApp/src/commonMain/.../appwebview/messages/`

| Handler | JS 方法名 | 委托 |
|---------|----------|------|
| `GetAsrConfigJsMessageHandler` | `get_asr_config` | `AsrConfigService.getAsrConfig()` |
| `SaveAsrConfigJsMessageHandler` | `save_asr_config` | `AsrConfigService.saveAsrConfig(param)` |
| `SelectAudioFileJsMessageHandler` | `select_audio_file` | 系统文件选择对话框（expect/actual，纯 UI 操作） |

### 10.5 权限执行点

权限检查通过 `AsrConfigService` 统一执行，分布在以下位置：

#### 后端执行点

1. **`MaterialWorkflowRouteExt.handleWhisperTranscribe()`**：
   - `AsrConfigService.isModelAllowed(model)` → 不允许则返回 error
   - `AsrConfigService.isDownloadAllowed()` → 覆盖请求中的 allowDownload

2. **`TranscribeExecutor`**：
   - 在 `FasterWhisperInstallService.ensureInstalled()` 前检查 `AsrConfigService.isDownloadAllowed()`

#### 前端执行点

3. **`asrConfig.ts`** 新增 `filterDisallowedModels(allModels, disallowedModelsStr)`：
   - 空字符串 → 返回全部；否则按逗号分隔排除黑名单中的模型

4. **`material.$materialId.subtitle.tsx` AsrModelPickerModal**：
   - 从 API/bridge 读取 ASR 配置，过滤模型列表
   - 控制 allowDownload checkbox 可见性

### 10.6 前端页面

#### 设置页入口

**文件**：`fredica-webui/app/routes/app-desktop-setting.tsx`

在 PyTorch 配置卡片之后添加 ASR 配置入口卡片，点击跳转到配置页面。

#### 配置页面

**新建**：`fredica-webui/app/routes/app-desktop-setting-faster-whisper-asr-config.tsx`

参考 `app-desktop-setting-torch-config.tsx` 模式。通过 `callBridgeOrNull("get_asr_config")` 读取配置（bridge 不可用时回退到 HTTP API `GET /api/asr/config`）。

**页面结构**：
1. **权限配置区** — 下载开关 + 允许模型多选
2. **模型测试区** — 音频路径 + 推理波数 + 模型多选 + 测试结果表格（详见 §11）

---

## 十一、模型预下载与测试（Phase 10） <Badge type="warning" text="未完成" />

> 对应需求：自定义音频、可配推理波数、多模型选择、部分退出保留结果。

### 11.1 功能概述

在 ASR 配置页面的"模型测试区"中，用户可以：

1. **指定测试音频**：通过文件选择对话框选择本地音频文件
2. **配置推理波数**：设置每个模型的推理次数（默认 10），用于评估性能稳定性
3. **多模型选择**：从允许列表中选择多个模型进行对比测试
4. **部分退出保留结果**：测试过程中可随时取消，已完成的模型结果保留显示

### 11.2 服务层：AsrModelTestService

**文件**：`shared/src/commonMain/.../asr/service/AsrModelTestService.kt`（expect/actual）

```kotlin
expect object AsrModelTestService {
    /**
     * 启动模型测试任务，返回结果流。
     *
     * @param models 待测试的模型列表
     * @param audioPath 测试音频文件路径
     * @param waveCount 每个模型的推理波数
     * @param onProgress 进度回调（每完成一个模型/一波推理时调用）
     * @param cancelSignal 取消信号（触发后停止后续模型测试，已完成结果保留）
     * @return 所有已完成模型的测试结果列表
     */
    suspend fun startModelTest(
        models: List<String>,
        audioPath: String,
        waveCount: Int,
        onProgress: suspend (AsrModelTestProgress) -> Unit,
        cancelSignal: CompletableDeferred<Unit>?,
    ): List<AsrModelTestResult>
}
```

**jvmMain actual**：调用 `PythonUtil.websocketTask("/asr/test-model-task", ...)` 实现。

### 11.3 数据模型

```kotlin
@Serializable
data class AsrModelTestProgress(
    val model: String,           // 当前测试的模型名
    val wave: Int,               // 当前推理波次（1-based）
    val totalWaves: Int,         // 总推理波数
    val modelIndex: Int,         // 当前模型索引（0-based）
    val totalModels: Int,        // 总模型数
    val status: String,          // "loading" | "inferring" | "done" | "error"
    val message: String? = null, // 状态描述或错误信息
)

@Serializable
data class AsrModelTestResult(
    val model: String,
    val success: Boolean,
    val loadTimeMs: Long? = null,       // 模型加载耗时
    val avgInferenceMs: Long? = null,   // 平均推理耗时
    val minInferenceMs: Long? = null,   // 最小推理耗时
    val maxInferenceMs: Long? = null,   // 最大推理耗时
    val vramUsageMb: Int? = null,       // 显存占用（MB）
    val error: String? = null,          // 失败原因
)
```

### 11.4 Python 端点

#### WebSocket 路由

**文件**：`desktop_assets/.../routes/asr.py` — 新增 `WS /asr/test-model-task`

#### 子进程 Worker

**新建**：`desktop_assets/.../subprocess/test_whisper_model_worker.py`

参考 `evaluate_faster_whisper_compat_worker.py` 模式：

```python
def _test_whisper_model_worker(param: dict, send_progress, is_cancelled) -> dict:
    """
    遍历模型列表，每个模型：
    1. 加载模型（记录加载耗时）
    2. 推理 N 次（记录每次耗时）
    3. 发送进度（每波推理后）
    4. 检查取消信号（每个模型开始前）
    
    已完成模型的结果始终保留，取消只影响后续模型。
    """
```

### 11.5 前端交互

在 ASR 配置页面的模型测试区：

1. **音频选择**：点击按钮 → `callBridge("select_audio_file")` → 显示选中路径
2. **推理波数**：数字输入框，默认 10，范围 1-100
3. **模型选择**：多选列表，从 `filterDisallowedModels()` 过滤后的模型中选择
4. **开始测试**：按钮触发 WebSocket 连接，实时显示进度
5. **结果表格**：模型名 | 加载耗时 | 平均推理耗时 | 最小/最大 | 显存 | 状态
6. **取消按钮**：发送取消信号，已完成结果保留在表格中

---

## 十二、优先级 GPU 资源锁（Phase 11） <Badge type="tip" text="已完成" />

> 对应需求：将 `GpuResourceLock` 从 `Semaphore(1)` 升级为优先级+时间序锁。
>
> 详细优先级策略见 [task-model.md §7.3](../task-model.md#_7-3-优先级策略)。

### 12.1 设计目标

`GpuResourceLock` 已从 `Semaphore(1)` 升级为 `Mutex` + `PriorityQueue` 优先级锁：

- **高优先级任务优先获取锁**：用户手动触发的单个转录应优先于批量任务
- **同优先级按时间序**：先到先得，避免饥饿
- **显式优先级**：`TaskDef.priority` 和 `withGpuLock` 的 `priority` 均无默认值，强制调用方显式指定

### 12.2 优先级映射

优先级范围 **0–20**，数字越大越优先。所有常量集中定义在 `TaskPriority` 对象中。

| 范围   | 用途                     | 示例                                          |
|--------|--------------------------|-----------------------------------------------|
| 0      | 最低优先级               | `DOWNLOAD_TORCH`、测试默认值                  |
| 1–10   | 重型 GPU 任务（ASR/转录）| `TRANSCRIBE`（Low=3, Medium=6, High=9）       |
| 11–20  | 轻型 GPU 任务（转码等）  | `TRANSCODE_MP4`=14, `DOWNLOAD_BILIBILI_VIDEO`=14 |

### 12.3 实现方案

**文件**：`shared/src/commonMain/.../worker/GpuResourceLock.kt`

基于 `Mutex` + `PriorityQueue<WaitEntry>` + `CancellableContinuation`：

```kotlin
object GpuResourceLock {
    private val mutex = Mutex()                    // 保护内部状态
    private val waitQueue = PriorityQueue<WaitEntry>(
        compareByDescending<WaitEntry> { it.priority }.thenBy { it.seq }
    )
    private var occupied = false                   // 锁是否被占用
    private var seqCounter = 0L                    // 单调递增序号

    private class WaitEntry(
        val priority: Int,
        val seq: Long,
        val continuation: CancellableContinuation<Unit>,
        val taskId: String,
    )

    /**
     * 获取 GPU 锁后执行 [block]，结束后自动释放。
     * 高优先级任务优先获取锁；同优先级按到达时间排序。
     *
     * @param taskId 当前任务 ID，用于等待时更新 statusText
     * @param priority 优先级（0-20，越大越优先）
     * @param block 持锁期间执行的挂起函数
     */
    suspend fun <T> withGpuLock(
        taskId: String,
        priority: Int,
        block: suspend () -> T,
    ): T { ... }
}
```

### 12.4 优先级传播链

```
前端 → MaterialWorkflowParam.priority: Int? = null
  → MaterialWorkflowRouteExt.handleWhisperTranscribe()
    → p.priority ?: TaskPriority.TRANSCRIBE_MEDIUM
  → MaterialWorkflowServiceExt.startWhisperTranscribe2(priority: Int)
  → CommonWorkflowService.TaskDef(priority = priority)
  → Task.priority
  → TranscribeExecutor: GpuResourceLock.withGpuLock(task.id, task.priority)
```

动态创建：`ASR_SPAWN_CHUNKS` → `TRANSCRIBE` 任务继承 `task.priority`。

#### 修改文件清单

| 文件 | 变更 |
|------|------|
| `GpuResourceLock.kt` | 重写为优先级锁；`priority` 无默认值 |
| `TaskPriority.kt` | 新增：集中定义所有优先级常量 |
| `Task.kt` | `priority` 无默认值 |
| `CommonWorkflowService.kt` | `TaskDef` 新增 `priority: Int`（无默认值）|
| `MaterialWorkflowRoute.kt` | `MaterialWorkflowParam` 新增 `priority: Int? = null` |
| `MaterialWorkflowRouteExt.kt` | 传递 `p.priority ?: TaskPriority.TRANSCRIBE_MEDIUM` |
| `MaterialWorkflowServiceExt.kt` | `startWhisperTranscribe2()` 新增 `priority: Int` 参数 |
| `MaterialWorkflowService.kt` | `startWhisperTranscribe()` 新增 `priority: Int` 参数 |
| `MaterialVideoTranscodeMp4Route.kt` | `priority = TaskPriority.TRANSCODE_MP4` |
| `BilibiliVideoDownloadRoute.kt` | `priority = TaskPriority.DOWNLOAD_BILIBILI_VIDEO` |
| `NetworkTestRoute.kt` | `priority = TaskPriority.NETWORK_TEST` |
| `TorchService.kt` | `priority = TaskPriority.DOWNLOAD_TORCH` |
| `AsrSpawnChunksExecutor.kt` | TRANSCRIBE 创建处 `priority = task.priority` |
| `TranscribeExecutor.kt` | `withGpuLock(task.id, task.priority)` |
| `TranscodeMp4Executor.kt` | `withGpuLock(task.id, task.priority)` |

### 12.5 测试

**文件**：`shared/src/jvmTest/kotlin/.../worker/GpuResourceLockTest.kt`

| 测试 | 验证点 |
|------|--------|
| 互斥性 | 两个任务不会同时持锁 |
| 等待状态文本 | 等待中的任务 statusText 显示"等待 GPU 资源…" |
| 获取后清除状态文本 | 获取锁后 statusText 被清除 |
| 无竞争无状态文本 | 无竞争时不写入 statusText |
| 高优先级抢占 | 3 个任务（priority 2/10/6）等待，释放后 priority=10 先获取 |
| 同优先级时间序 | 同 priority 的任务按 seq 顺序获取 |
| 取消从队列移除 | 等待中的任务取消后不影响其他等待者 |

---

## 附录 B、自定义模型搜索目录 <Badge type="info" text="参考" />

> 本节为服主（服务器管理员）提供的配置参考，当前版本无需开发，记录备用。

### 背景

faster-whisper 默认将模型缓存在 HuggingFace 标准目录（`~/.cache/huggingface/hub/`）。
若服主希望将模型存放在其他位置（如外置硬盘、共享 NAS、或统一的模型仓库目录），可通过以下方式配置。

### 方案一：环境变量（推荐，无需改代码）

在启动 Python 服务前设置以下任一环境变量：

```shell
# 方式 A：HuggingFace 全局缓存根目录
set HF_HOME=D:\models\huggingface

# 方式 B：仅覆盖 hub 缓存目录
set HUGGINGFACE_HUB_CACHE=D:\models\huggingface\hub
```

faster-whisper 内部使用 `huggingface_hub` 下载模型，会自动读取上述环境变量。
设置后，模型将下载到 `%HF_HOME%\hub\` 或 `%HUGGINGFACE_HUB_CACHE%\` 下，
`WhisperModel` 加载时也会优先从该目录查找。

### 方案二：`download_root` 参数（需改代码）

`WhisperModel` 构造函数支持 `download_root` 参数，可直接指定模型目录：

```python
model = WhisperModel(model_name, device=device, compute_type=compute_type,
                     download_root="/path/to/custom/models")
```

若未来需要在前端或服务器设置页面暴露此选项，可在 `_faster_whisper_worker` 的 `param` 中增加
`models_dir` 字段，并在 `WhisperModel(...)` 调用时传入 `download_root=models_dir`。
对应的 Kotlin payload、路由参数、前端表单均需同步扩展（参考 `allow_download` 的透传方式）。

### 注意事项

- 模型目录结构由 `huggingface_hub` 管理，格式为 `models--Systran--faster-whisper-{name}/`，
  不要手动移动或重命名目录内的文件。
- 若同时设置了 `HF_HOME` 和 `download_root`，`download_root` 优先级更高。
- `local_files_only=True` 时，faster-whisper 只在上述目录中查找，不会发起网络请求。

> **跨 Phase 依赖提示**：Phase 4 先解决 `AsrSpawnChunksExecutor` 的 N 个 TRANSCRIBE Task 创建；Phase 5 再围绕 `TranscribeExecutor` 落地 per-chunk 转录、GpuResourceLock 串行、暂停/恢复/取消；Phase 6 依赖前两个阶段稳定输出 `chunk_XXXX.srt/.done` 后再做 `tryMergeChunks()` 合并。**本地资源管理模块（Phase 8 / §2.7 / §9.9.3）建议最后开发**：它横跨全局导航、bridge 文件系统访问、跨素材遍历、结果详情页与删除/重导入动作，最容易受到前面目录结构和结果元信息变动影响。等前面的 ASR 主链稳定后再做，返工成本最低。