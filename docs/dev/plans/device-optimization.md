---
title: 设备能力检测与 GPU 加速优化
---

# 设备能力检测与 GPU 加速优化

> **文档状态**：已完成（Done）
> **创建日期**：2026-03-06
> **适用模块**：`fredica_pyutil_server`、`composeApp`、`shared`、`fredica-webui`

---

## 1. 背景与目标

Bilibili 下载的视频文件为 `.m4s`（DASH 流）或 `.flv` 格式，无法直接在多数播放器/编辑器中使用。
需要通过 FFmpeg 将其转码合并为标准 `.mp4`。

同时，Fredica 未来将支持更多 GPU 密集型任务（Whisper 转录、超分辨率、目标检测等），
需要建立统一的**设备能力探测 + 工具链发现**基础设施，避免每个功能重复检测。

### 设计原则

- **Python 优先**：所有设备检测与 FFmpeg 调用均在 Python 服务中实现，复用已有的
  `TaskEndpointInSubProcess` 子进程管理框架
- **kmpJsBridge 展示**：设置页直接通过 JS Bridge 读写配置，不绕道 HTTP API
- **启动时自动探测**：Fredica 启动时静默检测，结果持久化到 `AppConfig`
- **降级策略**：GPU 不可用时自动降级为 CPU，任务不中断

---

## 2. 数据模型扩展

### 2.1 AppConfig 新增字段

**`shared/src/commonMain/.../db/AppConfig.kt`** 新增：

```kotlin
// FFmpeg 配置
@SerialName("ffmpeg_path")        val ffmpegPath: String = "",        // 手动指定路径，空串=自动
@SerialName("ffmpeg_hw_accel")    val ffmpegHwAccel: String = "auto", // auto|cuda|amf|qsv|videotoolbox|cpu
@SerialName("ffmpeg_auto_detect") val ffmpegAutoDetect: Boolean = true,

// 设备检测结果（只读，启动时写入，前端展示用）
@SerialName("device_info_json")   val deviceInfoJson: String = "",    // JSON，见 §3.1
@SerialName("ffmpeg_probe_json")  val ffmpegProbeJson: String = "",   // JSON，见 §3.2
```

同步更新：`AppConfigDb.kt`（defaultKv + toKvMap + toAppConfig）

---

## 3. Python 服务：设备检测模块

### 3.1 `util/device_util.py` — 设备 GPU 能力检测

**职责**：探测本机 GPU 支持情况，与 FFmpeg 路径无关。

```
detect_gpu_info() -> DeviceGpuInfo
```

探测逻辑（按加速类型）：

| 加速类型 | 平台 | 探测方法 |
|---------|------|---------|
| CUDA | Win/Linux | `import torch; torch.cuda.is_available()` 或解析 `nvidia-smi` 输出 |
| ROCm | Linux | `rocm-smi --showproductname` |
| Intel QSV | Win/Linux | Win 检测注册表 `HKLM\...\Intel\...`；Linux 检查 `/dev/dri/renderD*` + `vainfo` |
| Apple VideoToolbox | macOS | `platform.system() == 'Darwin'` 即可用 |
| D3D11VA | Windows | `platform.system() == 'Windows'`（有 GPU 驱动即可用） |
| VAAPI | Linux | 检测 `/dev/dri/renderD128` 是否存在 |

实现要点：
- 所有探测均 `try/except`，探测失败不抛出，仅标记 `available=False`
- CUDA 优先用 `pynvml`（无需 PyTorch）；`pynvml` 不可用则 fallback 到 `subprocess("nvidia-smi")`
- 结果为 `@dataclass DeviceGpuInfo`，可序列化为 JSON

### 3.2 `util/ffmpeg_util.py` — FFmpeg 发现与能力探测

**职责**：在本机查找 FFmpeg 可执行文件，并探测其支持的硬件加速与编码器。

#### 查找策略（按优先级）：

1. **用户手动配置路径**（`AppConfig.ffmpeg_path` 非空时直接使用）
2. **系统命令行工具查找**（依次尝试，取第一个有效结果）：
   - `shutil.which("ffmpeg")` — 扫描 `PATH` 环境变量中的所有目录
   - Windows：`subprocess.run(["where", "ffmpeg"], capture_output=True)` — 可返回多个结果，取第一行
   - Linux/macOS：`subprocess.run(["whereis", "-b", "ffmpeg"], capture_output=True)` — 解析 `ffmpeg: /path/to/ffmpeg ...` 格式，收集所有路径后逐一验证
3. **常见安装路径**（跨平台枚举，覆盖未加入 PATH 的安装场景）：

| 平台 | 搜索路径 |
|------|---------|
| Windows | `%ProgramFiles%\ffmpeg\bin\ffmpeg.exe`<br>`%ProgramFiles(x86)%\ffmpeg\bin\ffmpeg.exe`<br>`C:\ffmpeg\bin\ffmpeg.exe`<br>`%LOCALAPPDATA%\Programs\ffmpeg\bin\ffmpeg.exe`<br>`%USERPROFILE%\scoop\shims\ffmpeg.exe`（Scoop）<br>`C:\ProgramData\chocolatey\bin\ffmpeg.exe`（Choco）<br>winget 安装路径 |
| macOS | `/usr/local/bin/ffmpeg`（Homebrew Intel）<br>`/opt/homebrew/bin/ffmpeg`（Homebrew Apple Silicon）<br>`/usr/bin/ffmpeg` |
| Linux | `/usr/bin/ffmpeg`<br>`/usr/local/bin/ffmpeg`<br>`/snap/bin/ffmpeg`<br>`/var/lib/flatpak/app/.../ffmpeg` |

4. **Fredica 内置路径** — `{appDataDir}/tools/ffmpeg[.exe]`（用户可手动放置）

> **说明**：策略 2 和 3 均可能发现多个 FFmpeg 路径，应对每个候选路径分别做能力探测（§3.2 能力探测），
> 最终选取**硬件加速等级最高**的一个作为首选结果，而非简单取第一个。

#### 能力探测：

```python
# 1. 获取版本和 hwaccel 列表
subprocess.run([ffmpeg, "-hwaccels"], capture_output=True)
subprocess.run([ffmpeg, "-version"], capture_output=True)

# 2. 探测编码器支持
subprocess.run([ffmpeg, "-encoders"], capture_output=True)
# 解析输出中是否包含 h264_nvenc / h264_amf / h264_qsv / h264_videotoolbox / libx264

# 3. 实际可用性验证（静默测试，防止 hwaccel 在列表中但驱动不可用）
#    用一帧空白视频做转码测试，超时 5s
subprocess.run([ffmpeg, "-f", "lavfi", "-i", "color=c=black:s=16x16:d=0.1",
                "-c:v", encoder, "-frames:v", "1", "-f", "null", "-"],
               timeout=5, capture_output=True)
```

#### 加速方案选择优先级：

```
CUDA (h264_nvenc)  >  AMD AMF (h264_amf)  >  Intel QSV (h264_qsv)
  >  Apple VideoToolbox (h264_videotoolbox)  >  CPU (libx264)
```

选择条件：**设备支持** AND **FFmpeg 支持** AND **实际测试通过**

### 3.3 `routes/device.py` — HTTP 路由

```
GET  /device/info        → 返回 device_info_json（读缓存，若无则触发检测）
POST /device/detect      → 重新检测设备 + FFmpeg，返回最新结果
GET  /device/ffmpeg-find → 仅重新搜索 FFmpeg（不重复 GPU 检测）
```

---

## 4. Python 服务：FFmpeg 转码任务

### 4.1 `subprocess/transcode.py` — `FfmpegTranscodeSubprocess`

基于 `TaskEndpointInSubProcess`，在子进程中运行 FFmpeg，
通过 `status_queue` 推送实时进度。

**子进程入口函数**（模块级，可 pickle）：

```python
def _ffmpeg_transcode_worker(param, status_queue, cancel_event, resume_event):
    """
    param 字段：
        input_path   (str)  必填，输入文件路径（.m4s / .flv / video+audio 分离文件）
        output_path  (str)  必填，输出 .mp4 路径
        hw_accel     (str)  "auto"|"cuda"|"amf"|"qsv"|"videotoolbox"|"cpu"
        ffmpeg_path  (str)  FFmpeg 可执行路径
        video_input  (str)  可选，视频流路径（m4s 分离时）
        audio_input  (str)  可选，音频流路径（m4s 分离时）
    """
```

进度解析：解析 FFmpeg stderr 中的 `frame=N fps=N time=HH:MM:SS.ms` 行，
结合输入时长计算百分比，通过 `status_queue.put({"type": "progress", "percent": n})` 推送。

取消处理：监听 `cancel_event.is_set()`，触发时向 FFmpeg 进程发送 `q\n`（stdin）或 `terminate()`。
暂停处理：检查 `resume_event`，暂停时调用 `SIGSTOP`（Linux/macOS）或 `SuspendThread`（Windows）。

### 4.2 `routes/transcode.py` — WebSocket 路由

```
WS /transcode/mp4-task
```

`TranscodeMp4TaskEndpoint(TaskEndpointInSubProcess)`：收到 `init_param_and_run` 后
spawn 子进程运行 `_ffmpeg_transcode_worker`，
监听 status_queue 推送进度给 Kotlin 端。

### 4.3 FFmpeg 命令矩阵

#### 输入处理：M4S 分离流

```bash
# video.m4s + audio.m4s → output.mp4
ffmpeg -i video.m4s -i audio.m4s -c:v [ENCODER] [HW_ARGS] -c:a aac -b:a 192k \
  -movflags +faststart output.mp4
```

#### 输入处理：FLV 单流

```bash
ffmpeg -i video.flv -c:v [ENCODER] [HW_ARGS] -c:a aac -b:a 192k \
  -movflags +faststart output.mp4
```

#### 各硬件加速命令参数：

| 方案 | `[HW_ARGS]` | `[ENCODER]` | 说明 |
|------|------------|-------------|------|
| CUDA (NVENC) | `-hwaccel cuda -hwaccel_output_format cuda` | `h264_nvenc -preset p4 -rc vbr -cq 23 -b:v 0` | NVIDIA GPU，质量/速度均衡 |
| AMD AMF (Windows) | `-hwaccel d3d11va` | `h264_amf -quality balanced -rc vbr_peak` | AMD GPU，Windows 专用 |
| AMD AMF (Linux) | `-hwaccel vaapi -hwaccel_device /dev/dri/renderD128 -hwaccel_output_format vaapi` | `h264_vaapi -qp 23` | AMD/Intel GPU，Linux |
| Intel QSV (Windows) | `-hwaccel qsv -hwaccel_output_format qsv` | `h264_qsv -preset medium -global_quality 23` | Intel 核显/独显，Win |
| Intel QSV (Linux) | `-hwaccel qsv -hwaccel_device /dev/dri/renderD128` | `h264_qsv -preset medium -global_quality 23` | Intel 核显，Linux |
| Apple VideoToolbox | _(无需 hwaccel 参数)_ | `h264_videotoolbox -b:v 4M -allow_sw 1` | macOS，支持软件 fallback |
| CPU (libx264) | _(无)_ | `libx264 -preset medium -crf 23` | 通用降级方案 |

通用后处理参数（所有方案）：
```
-c:a aac -b:a 192k -movflags +faststart -y
```

### 4.4 `TranscodeCommandBuilder`

```python
class TranscodeCommandBuilder:
    def build(self, *, ffmpeg_path, input_video, input_audio,
              output_path, hw_accel) -> list[str]:
        """
        根据 hw_accel 选择对应命令模板，返回完整 argv 列表。
        hw_accel = "auto" 时按优先级自动选择已探测到的最佳方案。
        """
```

---

## 5. Kotlin 端：启动检测 + Executor 集成

### 5.1 启动时自动检测

**`FredicaApi.jvm.kt`** 的 `init()` 中，在 Python 服务启动完成后（ping 通后）异步触发：

```kotlin
// 启动检测（不阻塞启动流程）
launch(Dispatchers.IO) {
    try {
        val result = PythonUtil.Py314Embed.PyUtilServer
            .requestText(HttpMethod.Post, "/device/detect")
        val deviceInfo = result.loadJsonModel<DeviceDetectResult>().getOrNull()
        if (deviceInfo != null) {
            val config = AppConfigService.repo.getConfig()
            AppConfigService.repo.updateConfig(config.copy(
                deviceInfoJson  = deviceInfo.deviceInfoJson,
                ffmpegProbeJson = deviceInfo.ffmpegProbeJson,
            ))
        }
    } catch (e: Throwable) {
        logger.warn("startup device detect failed: ${e.message}")
    }
}
```

### 5.2 `TranscodeMp4Executor`（`jvmMain/worker/executors/`）

基于 `DownloadBilibiliVideoExecutor` 的 WebSocket 模式：

```kotlin
object TranscodeMp4Executor : TaskExecutor {
    override val taskType = "TRANSCODE_MP4"

    override suspend fun execute(task: Task): ExecuteResult {
        // 从 AppConfig 读取 ffmpeg_path 和 ffmpeg_hw_accel
        val config = AppConfigService.repo.getConfig()
        val payload = Json.decodeFromString<TranscodePayload>(task.payload)
        // 通过 PythonUtil.websocketTask 调用 /transcode/mp4-task
        ...
    }
}
```

**Task Payload：**

```json
{
  "input_video": "/data/media/BV1xxx/video.m4s",
  "input_audio": "/data/media/BV1xxx/audio.m4s",
  "output_path": "/data/media/BV1xxx/video.mp4",
  "hw_accel":    "auto"
}
```

### 5.3 Pipeline 集成

`MaterialImportRoute` 的 DAG 在 `DOWNLOAD_BILIBILI_VIDEO` 之后插入 `TRANSCODE_MP4` 任务，
`TRANSCODE_MP4` 完成后再执行 `EXTRACT_AUDIO`（依赖关系更新）：

```
DOWNLOAD_BILIBILI_VIDEO
        ↓
  TRANSCODE_MP4          ← 新增
        ↓
  EXTRACT_AUDIO
        ↓
  SPLIT_AUDIO
        ...
```

---

## 6. kmpJsBridge 集成（设置页）

### 6.1 新增消息处理器

**`GetDeviceInfoJsMessageHandler`**（composeApp）

```kotlin
// kmpJsBridge.callNative('get_device_info', '{}', callback)
// 返回：{ device_info_json, ffmpeg_probe_json, ffmpeg_path, ffmpeg_hw_accel }
```

从 `AppConfigService.repo.getConfig()` 读取并组合返回。

**`RunFfmpegDetectJsMessageHandler`**（composeApp）

```kotlin
// kmpJsBridge.callNative('run_ffmpeg_detect', '{}', callback)
// 触发 Python /device/detect，更新 AppConfig，回调最新结果
```

调用 `PythonUtil.Py314Embed.PyUtilServer.requestText(POST, "/device/detect")`，
将结果更新到 `AppConfig`，回调最新 JSON。

注册到 **`AppWebViewMessages.all`**。

### 6.2 `app-desktop-setting.tsx` 新增"硬件加速"分区

新增 `settingSections` 条目（只读信息面板 + 可编辑配置）：

```
┌─ 硬件加速 ─────────────────────────────────────────────────────────┐
│                                                                    │
│  设备 GPU 能力          [刷新检测]                                   │
│  ├ CUDA (NVIDIA)       ✓ RTX 4090 · 24 GB VRAM                    │
│  ├ Intel QSV           ✓ UHD Graphics 770                         │
│  ├ AMD AMF             ✗ 未检测到                                   │
│  └ 检测时间             2026-03-06 14:32                            │
│                                                                    │
│  FFmpeg                                                            │
│  ├ 路径                 [/usr/bin/ffmpeg          ] [浏览] [检测]   │
│  ├ 版本                 6.1.1（自动发现）                            │
│  ├ 硬件加速支持          CUDA · QSV · D3D11VA                       │
│  └ 选用加速方案          [自动 ▼] (当前最优: CUDA h264_nvenc)        │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

**实现要点：**
- 页面加载时调用 `callNative('get_device_info', ...)` 获取数据
- "刷新检测"按钮调用 `callNative('run_ffmpeg_detect', ...)`，显示加载状态
- `ffmpeg_path` 为可编辑文本框（留空=自动发现），`ffmpeg_hw_accel` 为 select
- 设备 GPU 能力只读展示（刷新后更新显示）
- `ffmpeg_path` 和 `ffmpeg_hw_accel` 修改后调用现有 `save_app_config` 保存

---

## 7. 开发顺序

```
Step 1  util/device_util.py      设备 GPU 检测（CUDA/QSV/AMF/VT/VAAPI）
Step 2  util/ffmpeg_util.py      FFmpeg 发现 + 能力探测 + 命令构建器
Step 3  routes/device.py         /device/detect + /device/info HTTP 路由
Step 4  AppConfig 字段扩展       Kotlin AppConfig + AppConfigDb 新增 4 个字段
Step 5  FredicaApi.jvm.kt        启动时调用 /device/detect，结果写 AppConfig
Step 6  GetDeviceInfoJsMessageHandler    kmpJsBridge 读取配置
        RunFfmpegDetectJsMessageHandler  kmpJsBridge 触发重检测
        AppWebViewMessages.all 注册
Step 7  app-desktop-setting.tsx  新增"硬件加速"分区（读取 + 展示 + 保存）
Step 8  subprocess/transcode.py  FFmpeg 转码子进程（进度解析 + 取消/暂停）
Step 9  routes/transcode.py      WS /transcode/mp4-task
Step 10 TranscodeMp4Executor     Kotlin Executor（WebSocket 模式）
Step 11 Pipeline DAG 更新        MaterialImportRoute 插入 TRANSCODE_MP4
```

---

## 8. 待完成清单

### Step 1-3：Python 设备检测
- ✅ `util/device_util.py`：CUDA / ROCm / QSV / VideoToolbox / D3D11VA / VAAPI 检测
- ✅ `util/ffmpeg_util.py`：多路径搜索 + hwaccel/encoder 探测 + 静默验证测试 + `TranscodeCommandBuilder`
- ✅ `routes/device.py`：`GET /device/info`、`POST /device/detect`、`GET /device/ffmpeg-find`

### Step 4-5：Kotlin AppConfig + 启动检测
- ✅ `AppConfig.kt`：新增 `ffmpegPath`、`ffmpegHwAccel`、`ffmpegAutoDetect`、`deviceInfoJson`、`ffmpegProbeJson`
- ✅ `AppConfigDb.kt`：同步 defaultKv / toKvMap / toAppConfig
- ✅ `FredicaApi.jvm.kt`：启动时异步调用 `/device/detect` 并写入 AppConfig；`ffmpegPath` 为空时自动填充探测到的路径

### Step 6-7：设置页集成（kmpJsBridge）
- ✅ `GetDeviceInfoJsMessageHandler.kt`
- ✅ `RunFfmpegDetectJsMessageHandler.kt`
- ✅ `AppWebViewMessages.all` 注册
- ✅ `app-desktop-setting.tsx`：硬件加速分区（展示 + 刷新 + ffmpeg_path/hw_accel 配置）

### Step 8-11：转码功能
- ✅ `subprocess/transcode.py`：`_ffmpeg_transcode_worker` + 进度解析 + 取消/暂停
- ✅ `routes/transcode.py`：`TranscodeMp4TaskEndpoint`
- ✅ `TranscodeMp4Executor.kt`（jvmMain）：WebSocket 调用转码路由
- ✅ `MaterialImportRoute.kt`：DAG 插入 `TRANSCODE_MP4`，更新依赖链
- ✅ `WorkerTaskListRoute` / 前端 `TASK_TYPE_LABELS`：新增 `TRANSCODE_MP4` 标签

---

## 10. 实现与原设计的差异

### `_test_encoder` 稳定性修复

原设计使用硬件解码参数（`-hwaccel cuda -hwaccel_output_format cuda` 等）测试编码器：

```python
# 原设计（有问题）
subprocess.run([ffmpeg, "-y"] + pre_args + [
    "-f", "lavfi", "-i", "color=c=black:s=16x16:d=0.1",
    "-c:v", encoder, "-frames:v", "1", "-f", "null", "-"])
```

这些是**解码加速**参数，但 `lavfi` 是软件直接生成的帧，没有解码步骤，导致帧格式不匹配（软件帧 vs. 期望 CUDA/QSV 帧）而频繁报错。

**实际实现（稳定方案）**：去掉 `pre_args`，用软件 YUV420P 输入测试编码器。所有硬件编码器（nvenc/amf/qsv/videotoolbox）均支持接受软件帧并自行上传到 GPU：

```python
cmd = [ffmpeg, "-y",
    "-f", "lavfi", "-i", "color=c=black:s=320x240:r=1",
    "-vf", "format=yuv420p",   # 确保进入编码器的像素格式兼容
    "-c:v", encoder,
    "-frames:v", "1", "-f", "null", "-"]
```

### `FfmpegProbeInfo.all_paths` 新增字段

原设计 `FfmpegProbeInfo` 只返回最优单个路径。实际实现新增 `all_paths: List[str]` 字段，
`find_best_ffmpeg()` 将所有探测成功的候选路径填入，供前端 FFmpeg 路径选择器使用。

### `_find_ffmpeg_candidates` 去重增强

原设计使用 `os.path.normpath()` 去重。实际实现改用 `os.path.realpath()` + Windows 大小写归一化：

```python
real = os.path.realpath(p)
key = real.lower() if platform.system() == "Windows" else real
```

修复了符号链接和 Windows 路径大小写不一致导致的重复路径问题。

### `MyJsMessageHandler` 异步修复

原设计未指定 `handle()` 的线程模型。初始实现使用 `runBlocking(Dispatchers.IO)`，
会阻塞 kmpJsBridge 的调用线程（WebView UI 主线程），导致"刷新检测"期间 UI 卡死最多 120s。

**实际修复**：改用 `CoroutineScope(Dispatchers.IO).launch`，`handle()` 立即返回，不阻塞 UI 线程。

### subprocess 调试日志

`device_util.py` 和 `ffmpeg_util.py` 中所有 `subprocess.run` 调用均通过 `_run()` 辅助函数包装，
统一输出 `args / exit_code / elapsed` 到 loguru `DEBUG` 级别，便于排查探测问题。

---

## 9. 未来 GPU 任务扩展（Phase 2+）

本基础设施（`device_util.py` + `ffmpeg_util.py` + AppConfig GPU 字段）将被以下任务复用：

| 任务 | GPU 加速方案 |
|------|------------|
| `TRANSCRIBE_CHUNK` | faster-whisper 的 `device="cuda"` 参数（已有 `subprocess/transcribe.py`） |
| `UPSCALE_VIDEO` | Real-ESRGAN CUDA / CPU fallback |
| `DETECT_SCENES` | PySceneDetect + OpenCV CUDA |
| `DIARIZE_AUDIO` | pyannote.audio CUDA |
| `DETECT_EMOTION` | torch CUDA |

`device_util.py` 的 `detect_gpu_info()` 结果同样供 faster-whisper 的 `device` 参数决策使用，
无需在转录路由中重复检测。
