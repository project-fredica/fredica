---
title: 去中心化任务管理架构设计
order: 1
---

# 去中心化任务管理架构设计

> **文档状态**：草案（Draft）
> **创建日期**：2026-03-02
> **适用模块**：`server`、`shared`、`fredica-webui`

---

## 1. 背景与动机

### 现状问题

当前 Fredica 是单机架构：所有素材处理（下载、转录、AI 分析）都在运行桌面应用的同一台机器上串行执行。这带来以下痛点：

| 痛点 | 说明 |
|------|------|
| 资源竞争 | AI 转录任务占满 CPU/GPU，桌面应用卡顿 |
| 单点处理 | 只能在开机且运行 App 的机器上处理 |
| 处理瓶颈 | 批量导入大量素材时队列阻塞 |
| 算力浪费 | 家中/公司有多台机器，但算力无法汇聚 |

### 目标

构建一套**去中心化任务调度系统**，让多台设备（桌面主机、NAS、远程服务器、云机器）能够：

1. **协同分担**素材处理任务（下载、转录、AI 分析）
2. **任意节点**都可以创建任务或消费任务，无固定主从关系
3. **弹性伸缩**：随时增减节点，系统自适应
4. **离线容错**：节点掉线后，其任务自动被其他节点接管

---

## 2. 核心概念

### 2.1 任务（Task）

**任务**是系统的最小工作单元，描述"对某个素材执行某种处理"。

```
Task {
  id                  唯一标识（UUID）
  type                任务类型（见下表）
  pipeline_id         所属流水线实例 ID（同一批联动任务共享）
  material_id         关联的素材 ID（material_video.id）
  status              生命周期状态（见 §5.2）
  priority            优先级（0-10，越大越优先）
  depends_on          前置任务 ID 列表（JSON array）——所有前置任务 completed 后才可调度
  cache_policy        缓存/恢复策略：NONE | CHECK_EXISTING | PREFER_ORIGINAL（见 §7.2）
  payload             任务入参（JSON，类型相关）
  result              任务出参（JSON，成功后写入）
  result_acked_at     发起节点确认收到结果的时间（NULL 表示尚未确认，见 §7.4）
  error               错误信息（失败时写入）
  error_type          错误分类：TRANSIENT | RESOURCE | PERMANENT | RATE_LIMITED（见 §7.6）
  excluded_nodes      不应再重试此任务的节点 ID 列表（JSON array，Resource 错误时追加，见 §7.6）
  idempotency_key     任务去重键（UNIQUE，见 §4.4）
  retry_count         当前重试次数（含因 heartbeat 超时触发的重试）
  max_retries         最大重试次数（默认 3）
  created_by          发起节点 ID
  claimed_by          当前认领节点 ID
  original_claimed_by 首次认领节点 ID（任务被抢占后保留，用于缓存恢复判断）
  file_node_id        持有本地文件的节点 ID（FFMPEG/本地TRANSCRIBE 任务用）
  node_affinity       调度亲和约束："SOFT:<node_id>" 或 "HARD:<node_id>"（见 §2.6）
  timestamps          created_at / claimed_at / started_at / completed_at
                      heartbeat_at / stale_at / reclaimed_at
}
```

**任务类型：**

| 类型 | 说明 | 所需能力函数 | 节点亲和 | 缓存策略 | 预估耗时 |
|------|------|-------------|---------|---------|---------|
| `DOWNLOAD_VIDEO` | 将视频文件下载到节点本地磁盘 | `DOWNLOAD_BILIBILI` / `DOWNLOAD_YOUTUBE` / `DOWNLOAD_HTTP` / `DOWNLOAD_HLS`（由 payload.source_platform 决定） | 无 | `CHECK_EXISTING` | 1–30 min |
| `EXTRACT_AUDIO` | 从本地视频文件中提取音轨（WAV/FLAC） | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 0.5–3 min |
| `SPLIT_AUDIO` | 将长音频切分为定长块（≤10 min/块） | `FFMPEG` | **HARD** | `PREFER_ORIGINAL` | 0.5–2 min |
| `DETECT_LANGUAGE` | 对音频采样（前 30s）进行语言检测，输出语言代码 | `TRANSCRIBE` | **SOFT** | `CHECK_EXISTING` | <30s |
| `TRANSCRIBE_CHUNK` | 对单个音频块进行语音转文字 | `TRANSCRIBE` | **SOFT** | `NONE` | 1–15 min |
| `MERGE_TRANSCRIPTION` | 合并各块 JSON 转录结果为完整文稿 | 无 | 无 | `CHECK_EXISTING` | <1 min |
| `AI_ANALYZE` | AI 内容分析/摘要/标签（输入为纯文本） | `AI_ANALYZE` | 无 | `NONE` | 0.1–5 min |
| `TRANSCODE` | 视频格式转换/压制 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 0.5–10 min |
| `GENERATE_THUMBNAIL` | 生成封面/缩略图序列 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 0.5–3 min |
| `EXTRACT_FRAMES` | 从视频中按固定间隔抽帧，输出 PNG 序列 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 0.5–5 min |
| `DETECT_SCENES` | 检测视频镜头切换点，输出场景时间轴 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 0.5–5 min |
| `OCR_FRAME_BATCH` | 对一批图像帧并行进行 OCR，输出逐帧文字 | `OCR` | **SOFT** | `NONE` | 0.5–10 min |
| `MERGE_OCR_RESULTS` | 合并各批次 OCR 结果，去重并按时间轴排序 | 无 | 无 | `CHECK_EXISTING` | <1 min |
| `DETECT_FRAME_BATCH` | 对一批图像帧并行目标检测，输出逐帧物体标签 | `OBJECT_DETECT` | **SOFT** | `NONE` | 0.5–10 min |
| `MERGE_DETECT_RESULTS` | 合并各批次检测结果，按时间轴排序并去重短暂出现的误检 | 无 | 无 | `CHECK_EXISTING` | <1 min |
| `TRANSLATE_TEXT` | 将文本（转录稿 / OCR 结果）翻译为目标语言 | `TRANSLATE` | **SOFT** | `CHECK_EXISTING` | 0.1–2 min |
| `UPSCALE_VIDEO` | 视频/图像超分辨率（2×/4×放大） | `SUPER_RESOLUTION` | **HARD** | `CHECK_EXISTING` | 10–120 min |
| `ENHANCE_AUDIO` | 语音增强/降噪，提升音频质量（TRANSCRIBE/DIARIZE 前处理） | `SPEECH_ENHANCE` | **HARD** | `CHECK_EXISTING` | 0.5–5 min |
| `DIARIZE_AUDIO` | 说话人日志：将音频按说话人切分，输出带时间戳的说话人分段 | `SPEAKER_DIARIZE` | **SOFT** | `CHECK_EXISTING` | 1–30 min |
| `DETECT_EMOTION` | 对音频段进行情感识别，输出逐段情感标签（喜/悲/愤/中性等） | `EMOTION_DETECT` | **SOFT** | `NONE` | 0.1–2 min |
| `EXTRACT_SUBTITLE` | 从 MKV/MP4 中提取内嵌字幕轨，输出 SRT/ASS 文件 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | <1 min |
| `FORMAT_SUBTITLE` | 优化字幕断行/时序、SRT↔ASS↔VTT 格式互转 | 无 | 无 | `CHECK_EXISTING` | <1 min |
| `SYNC_SUBTITLE` | 将外部字幕文件自动对齐到视频音轨（时间轴校正） | `SUBTITLE_SYNC` | **SOFT** | `CHECK_EXISTING` | 0.5–3 min |
| `DETECT_HARDCODED_SUBTITLE` | 采样视频帧，通过 Vision LLM / OCR 检测是否存在硬字幕及其区域 | `AI_ANALYZE` | **SOFT** | `CHECK_EXISTING` | <1 min |
| `BLUR_SUBTITLE_REGION` | 对视频中已有硬字幕的区域进行模糊遮盖 | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 1–20 min |
| `BURN_SUBTITLE` | 将 SRT/ASS 字幕硬编码进视频（支持字体/颜色/位置） | `FFMPEG` | **HARD** | `CHECK_EXISTING` | 1–30 min |
| `GENERATE_TTS` | 将文本（摘要/字幕/解说稿）合成为语音文件 | `TTS` | 无 | `CHECK_EXISTING` | 0.1–5 min |
| `FINGERPRINT_AUDIO` | 生成音频声学指纹（chromaprint），可用于版权检测/重复识别 | `AUDIO_FINGERPRINT` | **SOFT** | `CHECK_EXISTING` | <1 min |
| `FINGERPRINT_VIDEO` | 对视频抽样帧计算感知哈希（pHash），用于重复视频检测 | `FFMPEG` | **SOFT** | `CHECK_EXISTING` | 0.5–3 min |

任务仅声明所需的**能力函数**（做什么），不规定具体实现方式。调度器从满足该能力函数的节点中按**能力分数**择优分配，实现透明的后端解耦。节点亲和约束见 §2.4。

### 2.2 节点（Node）

**节点**是运行 Fredica 服务进程的一台设备实例。每个节点有：

```
Node {
  id              节点唯一 ID（启动时生成，持久化到本地）
  hostname        主机名
  capabilities    能力集合（Set<Capability>）
  status          online | busy | draining | offline
  max_workers     最大并行任务数
  last_seen_at    最近心跳时间（用于死亡检测）
  current_tasks   正在执行的任务 ID 列表

  -- 网络属性（影响下载任务调度打分）
  network_region  "CN" | "GLOBAL" | "UNKNOWN"
                  CN = 大陆网络（YouTube 等国际内容需 proxy）
                  GLOBAL = 国际 / 海外出口（GFW 不受限）
  has_proxy       是否配置了出口代理（影响 DOWNLOAD_YOUTUBE 能力声明）
  bandwidth_mbps  估算/配置的下行带宽（Mbps），0 = 未知

  -- 存储属性（影响下载任务的节点偏好）
  is_storage_node 是否为长期存储节点（如 NAS），true 时下载任务优先落此节点
  disk_total_gb   总磁盘容量（GB）
  disk_free_gb    当前可用磁盘（GB，由 heartbeat 动态更新）

  -- 硬件性能评分（影响 GPU/CPU 密集型任务调度打分，见 §2.7）
  gpu_score       GPU 综合推理性能评分（0–100），启动时通过内置表 / LLM 查询 / 手动配置生成
  cpu_score       CPU 综合计算性能评分（0–100），依据逻辑核心数估算或手动配置

  -- 实时负载（由 heartbeat 动态更新，影响负载修正，见 §2.7）
  pending_tasks   本节点本地排队中（status=pending/claimed，尚未 running）的任务数
}
```

**节点类型（按部署场景）：**

| 节点类型 | 典型场景 | 能力示例 |
|----------|----------|----------|
| 桌面主力机 | 运行 composeApp，RTX 4070 | **DOWNLOAD_BILIBILI**(pyutil·自动检测) · **DOWNLOAD_HTTP**(aria2c) · FFMPEG(GPU) · TRANSCRIBE(faster-whisper/GPU) · AI_ANALYZE(CloudAPI) |
| GPU 工作站 | 独立 GPU 服务器 | FFMPEG(GPU) · TRANSCRIBE(faster-whisper/GPU/large-v3) · AI_ANALYZE(ollama/GPU) |
| NAS / 存储节点 | 无显卡，7×24，is_storage_node=true | **DOWNLOAD_BILIBILI**(pyutil·手动安装) · **DOWNLOAD_HTTP**(aria2c) · FFMPEG(CPU) |
| Apple Silicon Mac | MacBook / Mac Mini | **DOWNLOAD_HTTP**(curl) · FFMPEG(VideoToolbox) · TRANSCRIBE(whisper.cpp/MPS) · AI_ANALYZE(CloudAPI) |
| 海外/代理节点 | 能访问 YouTube / 国际内容 | **DOWNLOAD_YOUTUBE**(yt-dlp) · **DOWNLOAD_HTTP**(aria2c) · **DOWNLOAD_HLS**(ffmpeg) |
| 轻量节点 | 树莓派 | **DOWNLOAD_HTTP**(curl) |

### 2.3 能力系统（Capability）

能力不是一个简单的布尔开关，而是一个**三维结构体**：做什么（Function）× 用什么实现（Backend）× 用什么硬件/服务（Accelerator），并附带一个**分数（Score）**用于调度优先级排序。

```kotlin
// ── 能力函数：任务声明"需要做什么" ──────────────────────────────
enum class CapabilityFunction {
    // 下载类：按来源平台 / 协议细分，因工具依赖和网络限制差异显著
    DOWNLOAD_BILIBILI,  // 哔哩哔哩（需 fredica-pyutil；无 GFW 限制）
    DOWNLOAD_YOUTUBE,   // YouTube / 通用平台（需 yt-dlp；CN 节点需代理）
    DOWNLOAD_HTTP,      // 通用 HTTP/HTTPS 直链（aria2c / curl；无特殊限制）
    DOWNLOAD_HLS,       // HLS / DASH 流媒体（ffmpeg -i m3u8；无特殊限制）

    FFMPEG,             // 视频/音频编解码处理（含 DETECT_SCENES）
    TRANSCRIBE,         // 语音转文字
    TTS,                // 文字转语音（语音合成）
    AI_ANALYZE,         // AI 内容理解/摘要/标签
    OCR,                // 图像文字识别（字幕/文档/截图）
    OBJECT_DETECT,      // 视频帧目标检测（YOLO / RT-DETR 等）
    TRANSLATE,          // 文本翻译（NLLB 本地 / 云端翻译 API）
    SUPER_RESOLUTION,   // 视频/图像超分辨率（Real-ESRGAN / EDSR 等）

    // 语音分析扩展（SpeechBrain 框架为主要本地支撑）
    SPEECH_ENHANCE,     // 语音增强/降噪（DeepFilterNet / SpeechBrain MetricGAN+）
    SPEAKER_DIARIZE,    // 说话人日志/声纹分类（pyannote / SpeechBrain / NeMo）
    EMOTION_DETECT,     // 语音情感识别（SpeechBrain / wav2vec2-emotion）

    // 字幕与内容指纹
    SUBTITLE_SYNC,      // 字幕自动时间轴对齐（alass / ffsubsync / subsync）
    AUDIO_FINGERPRINT,  // 音频声学指纹（chromaprint / AcoustID）
}

// ── 加速器类型：区分本地硬件、网络下载和云端 API ─────────────────
sealed class Accelerator {
    /** 本地 GPU 执行 */
    data class LocalGPU(val name: String, val vramMb: Int) : Accelerator()
    /** 本地 CPU 执行 */
    object LocalCPU : Accelerator()
    /** 网络下载——以带宽/可达性为核心资源，不依赖本地算力 */
    data class LocalNetwork(
        val bandwidthMbps: Int?,   // null = 未知；调度时用于分数修正
        val region: String,        // "CN" | "GLOBAL" | "UNKNOWN"
        val hasProxy: Boolean,     // 是否配置出口代理（影响 DOWNLOAD_YOUTUBE）
    ) : Accelerator()
    /** 云端 API —— 不消耗本地算力，但需要网络与配额 */
    data class CloudAPI(val provider: String, val model: String) : Accelerator()
    // provider: "openai" | "deepseek" | "anthropic" | "openai-compatible" | ...
}

// ── 能力声明：节点向集群注册时提交 ──────────────────────────────
data class Capability(
    val function: CapabilityFunction,
    val backend: String,        // 具体实现名称（见各函数的后端清单）
    val accelerator: Accelerator,
    val models: List<String>,   // 可用模型列表（如 ["large-v3", "medium"]）
    val score: Int,             // 调度优先级分数，越高越优先（见评分规则）
    val metadata: Map<String, String> = emptyMap()
)
```


> 各能力函数支持的后端实现、评分规则及调度示例，详见 **§3 能力后端清单与评分规则**。


---

### 2.4 节点配置文件（worker.yaml）

每台机器有一份独立的 `worker.yaml`，位于节点数据目录（`.data/`）下。集群内各节点**分别维护自己的配置**，不存在中央配置分发；超时阈值等集群行为参数建议各节点保持一致（通过运维规范约定，不强制同步）。

```yaml
# ── 节点身份 ──────────────────────────────────────────────────
node:
  id: ""                  # 留空则首次启动时自动生成并写回，勿改动已生成的值
  hostname: ""            # 留空则取系统 hostname
  max_workers: 2          # 最大并行任务数（建议 ≤ CPU 核数 / 2）

  # 网络属性（影响下载任务调度打分，见 §2.3）
  network_region: "CN"    # CN | GLOBAL | UNKNOWN
  bandwidth_mbps: 100     # 下行带宽估算（Mbps）；填 0 = 未知

  # 存储属性
  is_storage_node: false  # 是否为长期存储节点（NAS 等）
                          # true 时，发布者声明 storage_preference=nas 的 pipeline
                          # 下载任务会优先调度到此节点（+20 分加成）

# ── 集群 ──────────────────────────────────────────────────────
cluster:
  mode: "auto"            # auto（mDNS 优先）| manual（仅 seeds）
  seeds:                  # 跨网段时填写，局域网内留空即可
    - "192.168.1.10:7631"
  cluster_secret: ""      # 集群共享密钥，所有节点必须一致

  # 超时阈值（家用环境可放宽；同一集群建议各节点设置相同值）
  heartbeat_interval_sec: 30
  stale_after_sec: 180        # heartbeat 断联 3 min → stale
  reclaim_after_sec: 900      # 断联 15 min → 任务被回收
  originator_wait_sec: 1800   # 执行节点等待发起节点恢复的最长时间（30 min）

  # 集群级 API 并发上限（汇总所有节点的活跃调用数）
  api_rate_limits:
    deepseek-api:
      cluster_max_concurrent: 5
    openai-api:
      cluster_max_concurrent: 3

# ── 能力声明 ───────────────────────────────────────────────────
capabilities:

  # 下载能力——各子能力独立开关，节点只声明自己实际具备的
  download:
    disk_min_free_gb: 20      # 所有下载任务的前置磁盘检查（GB）
    max_concurrent: 2         # 同时进行的下载任务上限

    bilibili:
      enabled: true
      backend: "bilibili-pyutil"
      # Desktop 节点留空 = 从 desktop_assets 自动检测（随 composeApp 自动启动）
      # Server/NAS 节点填写手动部署的 fredica-pyutil 服务地址；留空则不声明此能力
      pyutil_path: ""
      # B 站账号 Cookie（可选，无 Cookie 只能下载免登录内容）
      sessdata: ""

    youtube:
      enabled: false           # CN 节点且无代理时建议关闭
      backend: "youtube-ytdlp"
      ytdlp_path: ""           # 留空 = 从 PATH 自动检测
      # 指定代理（has_proxy 会自动设为 true）
      proxy: ""                # 例："socks5://127.0.0.1:7890"

    http:
      enabled: true
      backend: "http-aria2c"   # http-aria2c | http-ytdlp | http-curl
      aria2c_path: ""          # 留空 = PATH 自动检测
      aria2c_connections: 16   # 多连接数（aria2c 专用）

    hls:
      enabled: true
      backend: "hls-ffmpeg"    # hls-ffmpeg | hls-ytdlp

  ffmpeg:
    enabled: true
    backend: "ffmpeg-nvenc"   # ffmpeg-nvenc | ffmpeg-vaapi | ffmpeg-videotoolbox | ffmpeg-cpu
    score: 90

    # 镜头检测（DETECT_SCENES 任务，属于 FFMPEG 能力，无需单独 CapabilityFunction）
    scene_detect:
      backend: "pyscenedetect"  # pyscenedetect | ffmpeg-scene（内置，零依赖）
      threshold: 0.4            # 场景切换灵敏度（0–1，越低越灵敏）

  transcribe:
    enabled: true
    backend: "faster-whisper" # faster-whisper | whisper.cpp | openai-whisper-api
    accelerator: "gpu"        # gpu | cpu
    models:
      - name: "large-v3"
        score: 95
      - name: "medium"
        score: 72
    max_concurrent: 1         # GPU 节点通常只能跑 1 个推理
    # 若 backend 为 openai-whisper-api，改为：
    # accelerator: "cloud_api"
    # api_key: "sk-..."
    # score: 62

  ai_analyze:
    enabled: true
    providers:
      - backend: "deepseek-api"
        api_key: "sk-..."
        model: "deepseek-v3"
        score: 75
        max_concurrent: 2     # 本节点对该 provider 的最大并发数
      - backend: "ollama"
        endpoint: "http://localhost:11434"
        model: "qwen2.5:72b"
        accelerator: "gpu"
        score: 88
        max_concurrent: 1

  ocr:
    enabled: false
    max_concurrent: 2         # 同时处理的帧批次数
    providers:
      - backend: "paddleocr-gpu"   # paddleocr-gpu | paddleocr-cpu | rapidocr | surya | easyocr | tesseract
        accelerator: "gpu"
        models: ["PP-OCRv4"]       # 本地 OCR 无需显式指定，由后端自动选择
        score: 90
      # 云端示例（填写 api_key 后取消注释）：
      # - backend: "baidu-ocr-api"
      #   api_key: "..."
      #   secret_key: "..."
      #   score: 65

  object_detect:
    enabled: false
    max_concurrent: 1         # GPU 目标检测通常限 1 个并行推理
    providers:
      - backend: "yolo-gpu"   # yolo-gpu | yolo-cpu | yolo-onnx | rtdetr-gpu
        accelerator: "gpu"
        models:
          - name: "yolov8n"   # nano：速度最快，精度稍低
            score: 80
          - name: "yolov8m"   # medium：速度/精度均衡
            score: 88
        default_model: "yolov8n"
      # 云端示例：
      # - backend: "google-vision-detect-api"
      #   api_key: "..."
      #   score: 62

  translate:
    enabled: false
    max_concurrent: 2
    providers:
      - backend: "nllb-gpu"   # nllb-gpu | nllb-cpu | opus-mt | ollama-translate
        accelerator: "gpu"
        models: ["nllb-200-distilled-600M"]  # 600M | 1.3B | 3.3B
        score: 85
      # 云端示例：
      # - backend: "deepl-api"
      #   api_key: "..."
      #   score: 82
      # - backend: "deepseek-translate-api"  # 与 ai_analyze 共享 API Key
      #   api_key: "sk-..."
      #   score: 75

  super_resolution:
    enabled: false
    max_concurrent: 1         # 超分极耗 VRAM，强制限 1 个并行任务
    providers:
      - backend: "realesrgan-gpu"  # realesrgan-gpu | hat-gpu | edsr-gpu | swinir-gpu | realesrgan-ncnn
        accelerator: "gpu"
        default_scale: 2           # 2 | 4
        tile_size: 512             # 显存不足时分块处理；0 = 不分块（需 ≥16GB VRAM）
        score: 90
    # 超分无云端后端，cloud_policy.super_resolution.cloud_allowed 固定 false

  speech_enhance:
    enabled: false
    max_concurrent: 2
    providers:
      - backend: "deepfilternet-gpu"  # deepfilternet-gpu | deepfilternet-cpu | speechbrain-enhance
                                      # demucs-gpu | demucs-cpu | rnnoise | noisereduce
        accelerator: "gpu"
        score: 88
        default_mode: "denoise"       # denoise | dereverberate | separate | full

  speaker_diarize:
    enabled: false
    max_concurrent: 1         # 日志分析通常单任务占满 GPU
    providers:
      - backend: "pyannote-gpu"  # pyannote-gpu | pyannote-cpu | speechbrain-diarize
                                 # nemo-diarize-gpu | resemblyzer-cpu
        accelerator: "gpu"
        score: 90
        # pyannote 需要 HuggingFace Token（免费申请后填入）
        hf_token: ""
      # 云端示例（cloud_policy.speaker_diarize.cloud_allowed 需设为 true）：
      # - backend: "assemblyai-diarize-api"
      #   api_key: "..."
      #   score: 70

  emotion_detect:
    enabled: false
    max_concurrent: 2
    providers:
      - backend: "speechbrain-emotion"  # speechbrain-emotion | speechbrain-emotion-cpu
                                        # wav2vec2-emotion | ser-acrnn
        accelerator: "gpu"
        score: 80
    # 情感识别无云端后端，cloud_policy.emotion_detect.cloud_allowed 固定 false

  subtitle_sync:
    enabled: false
    max_concurrent: 4             # 轻量 CPU 任务，可高并发
    providers:
      - backend: "alass"          # alass | ffsubsync | subsync
        accelerator: "cpu"
        score: 85
        alass_path: ""            # 留空 = PATH 自动检测
        # ffsubsync 示例：
        # - backend: "ffsubsync"
        #   score: 76

  audio_fingerprint:
    enabled: true                 # 轻量无依赖，默认开启
    max_concurrent: 8
    providers:
      - backend: "chromaprint"    # chromaprint | chromaprint-py | essentia-fingerprint
        accelerator: "cpu"
        score: 88
        fpcalc_path: ""           # 留空 = PATH 自动检测
      # AcoustID 查询（指纹本地生成，仅上传 hash，隐私风险低）：
      # - backend: "acoustid-api"
      #   api_key: ""             # AcoustID 免费 API Key
      #   score: 65

# ── 云端 API 权限（运营者控制，优先级高于发布者的 cloud_preference）──
cloud_policy:
  # 对每种能力，运营者可独立决定是否允许云端后端
  transcribe:
    cloud_allowed: true
    # 留空 = 允许所有已配置的云端后端；列出具体 backend = 白名单限制
    allowed_backends: []          # 例：["xunfei-iat-api", "groq-whisper-api"]
    max_cost_per_task_usd: null   # null = 无上限；填数字 = 单任务费用上限
  ai_analyze:
    cloud_allowed: true
    allowed_backends: []
    max_cost_per_task_usd: null
  ocr:
    cloud_allowed: false      # OCR 图片可能含敏感内容，默认禁止云端
    allowed_backends: []
    max_cost_per_task_usd: null
  object_detect:
    cloud_allowed: false      # 视频帧含人脸等隐私内容，默认禁止云端
    allowed_backends: []
    max_cost_per_task_usd: null
  translate:
    cloud_allowed: true
    allowed_backends: []      # 留空 = 允许所有已配置的云端翻译后端
    max_cost_per_task_usd: null
  super_resolution:
    cloud_allowed: false      # 超分无云端后端，固定禁止
  speech_enhance:
    cloud_allowed: false      # 音频体积大且隐私敏感，禁止云端
  speaker_diarize:
    cloud_allowed: false      # 音频可能含敏感对话，默认禁止；需显式开启
    allowed_backends: []
    max_cost_per_task_usd: null
  emotion_detect:
    cloud_allowed: false      # 情感音频属极敏感个人数据，固定禁止
  subtitle_sync:
    cloud_allowed: false      # 字幕对齐纯本地工具，无云端后端
  audio_fingerprint:
    cloud_allowed: true       # AcoustID 仅上传指纹 hash，无音频原文，隐私风险低
    allowed_backends: ["acoustid-api"]
    max_cost_per_task_usd: null
  # 若某能力未列出，默认 cloud_allowed: true（由能力声明中的 api_key 控制实际可用性）

# ── 鉴权（见 §12）─────────────────────────────────────────────
auth:
  # open：不鉴权，适合纯局域网私有部署（默认）
  # token：所有 /api/v1/* 请求须携带 Bearer Token，适合公网暴露
  mode: "open"

  # 以下仅 mode=token 时有效
  # 发布者令牌列表（运营者通过 `fredica token create` 生成）
  access_tokens:
    - id: "tok-alice"
      name: "Alice（家庭成员）"
      token_hash: "sha256:aabbcc..."   # 存储 hash，不明文存储原始 token
      expires_at: null                 # null = 永不过期
      permissions:
        allow_cloud_transcribe: true
        allow_cloud_ai_analyze: true
        max_cloud_cost_per_day_usd: 2.00
        max_tasks_per_day: 200
        # allowed_pipeline_templates: []   # 留空 = 允许所有模板

# ── 硬件性能评分（见 §2.7）──────────────────────────────────────
hardware:
  # 手动覆盖（填写则跳过自动评分流程）
  gpu_score_override: null    # 0–100；null = 自动
  cpu_score_override: null    # 0–100；null = 自动（依据逻辑核心数估算）

  # LLM 硬件评分查询（GPU 不在内置表时触发）
  hw_score_llm:
    enabled: true             # false = 不查询 LLM，退回到 VRAM 估算公式
    # auto = 复用 capabilities.ai_analyze 中第一个可用后端（本地优先）
    provider: "auto"

# ── 调度偏好（见 §2.7）──────────────────────────────────────────
scheduling:
  # 节点队列深度对打分的惩罚（满载时最多扣分 max_penalty 分）
  load_penalty:
    enabled: true
    max_penalty: 20           # 满载（pending_tasks ≥ max_workers）时的最大扣分

  # 各任务类型的 quality_mode 默认值（发布者未指定时生效）
  # quality：不接受质量降级，忽略负载惩罚
  # balanced：中等负载惩罚（× 0.5）
  # speed：完整负载惩罚（× 1.0），优先选空闲节点
  quality_defaults:
    TRANSCRIBE_CHUNK:              "quality"    # 转录质量敏感，不因负载降级
    UPSCALE_VIDEO:                 "quality"    # 超分输出质量敏感
    SUPER_RESOLUTION:              "quality"
    DIARIZE_AUDIO:                 "quality"    # 声纹分类直接影响转录标签准确性
    AI_ANALYZE:                    "balanced"
    OCR_FRAME_BATCH:               "balanced"
    TRANSLATE_TEXT:                "balanced"
    ENHANCE_AUDIO:                 "balanced"   # 增强质量影响后续转录，允许轻度权衡
    DETECT_EMOTION:                "balanced"   # 情感识别为辅助标注，轻度负载权衡可接受
    BLUR_SUBTITLE_REGION:          "balanced"   # 视频处理，质量可见，适度权衡
    SYNC_SUBTITLE:                 "balanced"
    BURN_SUBTITLE:                 "balanced"
    DETECT_FRAME_BATCH:            "speed"      # 目标检测结果可接受近似
    GENERATE_THUMBNAIL:            "speed"      # 缩略图质量要求低
    DETECT_SCENES:                 "speed"
    FORMAT_SUBTITLE:               "speed"      # 纯文本处理，无质量差异
    DETECT_HARDCODED_SUBTITLE:     "speed"      # 采样帧检测，轻量，优先空闲节点
    FINGERPRINT_AUDIO:             "speed"      # 极轻量，优先空闲节点
    FINGERPRINT_VIDEO:             "speed"

# ── 存储 ──────────────────────────────────────────────────────
storage:
  data_dir: ".data"
  results_retain_days: 7     # result_acked_at 为 NULL 时的最长保留天数
  intermediate_cleanup: "on_pipeline_complete"

# ── 由程序自动维护，请勿手动修改 ──────────────────────────────
_runtime:
  last_shutdown_type: ""  # CLEAN | CRASH（App 模式专用）
  gpu_score_cached: null  # 自动评分结果缓存，随 GPU 驱动版本更新失效
  cpu_score_cached: null
```

**自动探测补充：** 节点启动时，若 `capabilities` 某项 `enabled: true` 但未配置 `backend`，自动探测逻辑会尝试填充：

| 探测命令 / 条件 | 触发的自动配置 |
|----------------|--------------|
| `nvidia-smi` 可用 | `ffmpeg-nvenc` · `faster-whisper+gpu` · `yolo-gpu` · `paddleocr-gpu` · `realesrgan-gpu` · `nllb-gpu` |
| `system_profiler SPDisplaysDataType`（macOS）| `ffmpeg-videotoolbox` · `whisper.cpp+mps` |
| `ollama list` 有结果 | 列出本地可用模型，注册为 `ai_analyze`（ollama）能力 |
| `python -c "import paddleocr"` 成功 | 注册 `paddleocr-cpu`（无 GPU 时）|
| `python -c "from ultralytics import YOLO"` 成功 | 注册 `yolo-cpu` / `yolo-onnx` |
| `python -c "import transformers"` 成功 | 注册 `nllb-cpu`（无 GPU 时）|
| `realesrgan-ncnn-vulkan` 在 PATH 中 | 注册 `realesrgan-ncnn`（无 CUDA 的超分兜底）|
| `pyscenedetect` / `scenedetect` 在 PATH 中 | `scene_detect.backend` 自动设为 `pyscenedetect`，否则降级 `ffmpeg-scene` |
| `python -c "import deepfilter"` 成功 | 注册 `deepfilternet-cpu`（GPU 版需 CUDA）|
| `python -c "import speechbrain"` 成功 | 注册 `speechbrain-diarize` · `speechbrain-enhance` · `speechbrain-emotion`（视已安装配方而定）|
| `python -c "import pyannote.audio"` 成功 | 注册 `pyannote-gpu` / `pyannote-cpu`（需 `speaker_diarize.hf_token` 非空）|
| `alass` 在 PATH 中 | 注册 `alass` 后端（subtitle_sync）|
| `python -c "import ffsubsync"` 成功 | 注册 `ffsubsync` 后端（subtitle_sync 兜底）|
| `fpcalc` 在 PATH 中 | 注册 `chromaprint` 后端（audio_fingerprint，默认开启）|
| `python -c "import acoustid"` 成功 | 注册 `chromaprint-py` 后端（无 fpcalc 时的兜底）|

---

### 2.5 云端 API 偏好与双层权限

#### 问题背景

云端 API（转写、AI 分析）涉及两个独立角色的决策：

- **运营者（Deployer）**：部署并维护节点，拥有 API Key，决定**本节点是否开放云端能力**
- **发布者（Publisher）**：使用 Fredica App 发起任务，决定**本次任务是否倾向使用云端**

两者的意志必须同时得到尊重，且运营者具有更高优先级。

#### `cloud_preference` — 发布者的偏好声明

发布者在创建流水线时，可在请求体中声明 `cloud_preference`，该字段传播到流水线内的所有任务：

| 值 | 语义 |
|----|------|
| `"auto"` | 不干预，执行节点按基础分数自主选择（默认）|
| `"prefer_local"` | 优先本地，云端后端分数 −30 |
| `"prefer_cloud"` | 优先云端，云端后端分数 +30 |
| `"local_only"` | 强制本地，云端后端完全排除 |
| `"cloud_only"` | 强制云端，本地后端完全排除；无云端节点则等待/失败 |

发布者也可对流水线中某个特定任务类型单独覆盖：
```json
{
  "template": "bilibili_full_process",
  "cloud_preference": "prefer_local",
  "overrides": {
    "TRANSCRIBE_CHUNK": { "cloud_preference": "cloud_only" }
  }
}
```

#### `cloud_policy` — 运营者的权限上限

运营者在 `worker.yaml` 中通过 `cloud_policy` 设置**本节点**对各能力的云端使用上限（见 §2.4）。

**两层决策的优先级规则（执行节点认领任务时）：**

```
运营者 cloud_policy.transcribe.cloud_allowed = false
  → 无论发布者如何声明，本节点不使用云端后端（不认领 cloud_only 任务）

运营者 cloud_allowed = true，且 allowed_backends 有限制
  → 发布者的 cloud_preference 在允许的 backend 范围内生效

运营者 cloud_allowed = true，无限制
  → 发布者的 cloud_preference 完全生效
```

```
┌───────────────────────────────────────────────────────────────┐
│                     任务认领时的决策矩阵                        │
├─────────────────┬─────────────────┬────────────────────────────┤
│ 发布者偏好       │ 运营者 cloud_allowed │ 结果                   │
├─────────────────┼─────────────────┼────────────────────────────┤
│ auto            │ true            │ 本地优先（分数决定）         │
│ prefer_cloud    │ true            │ 云端优先（+30分修正）        │
│ cloud_only      │ true            │ 只用云端                    │
│ prefer_cloud    │ false           │ 退回 auto（忽略云端偏好）    │
│ cloud_only      │ false           │ 本节点拒绝认领，任务等其他节点│
│ prefer_local    │ any             │ 本地优先（-30分修正云端）    │
│ local_only      │ any             │ 只用本地                    │
└─────────────────┴─────────────────┴────────────────────────────┘
```

> **关键设计原则**：`cloud_only` 的任务如果没有允许云端的节点，会一直等待（`pending`），而不是报错，因为用户可能稍后添加具备云端能力的节点。超时后（`max_retries` 耗尽）才进入 `failed`。

---

### 2.6 处理流水线与任务依赖

#### 为什么需要任务 DAG

视频素材的处理步骤天然具有**先后依赖**和**能力联动**关系，不能简单建模为独立任务：

1. **顺序依赖**：必须先下载完才能提取音频，提取完才能切分，切分完才能转录
2. **文件本地性**：ffmpeg 和本地 Whisper 只能操作本机磁盘上的文件，不能跨节点直接访问
3. **Whisper 输入限制**：Whisper 系列模型（无论 faster-whisper / whisper.cpp）对单次输入时长有实际上限（本地模型约 30 min 以内效果最佳，OpenAI API 有 25MB 文件大小限制），长视频必须先切分

因此任务之间通过 `depends_on` 构成 **DAG（有向无环图）**，并携带 `node_affinity` 约束保证文件可达性。

#### 标准流水线模板

每次触发"完整处理"时，系统根据模板实例化一组相互依赖的任务，共享同一个 `pipeline_id`：

**短视频（≤ 30 min，language 已知）**

```
DOWNLOAD_VIDEO [node_affinity: 无]
    │  完成后：file_node_id = 下载节点 A
    ▼
EXTRACT_AUDIO [HARD:A，需 FFMPEG]
    │  完成后：产出单个音频文件，file_node_id 仍为 A
    ▼
TRANSCRIBE_CHUNK [SOFT:A，payload.language = "zh"]
    │  也可被 GPU 节点 B 通过文件服务拉取后处理（见"文件服务"小节）
    ▼
AI_ANALYZE [无亲和，输入为纯文本]
```

**长视频（> 30 min，language = "auto"，需先检测语言）**

```
DOWNLOAD_VIDEO [无亲和]
    │
    ▼
EXTRACT_AUDIO [HARD:A，需 FFMPEG]
    │  完成后，并行触发以下两条链：
    │
    ├─► DETECT_LANGUAGE [SOFT:A]          ← 仅采样前 30s，<30s 完成
    │       │ 输出: { language:"zh", confidence:0.97 }
    │       │ 结果写入 pipeline_instance.detected_language
    │
    └─► SPLIT_AUDIO [HARD:A，需 FFMPEG]   ← 与语言检测并行进行
            │  完成后动态创建 N 个 TRANSCRIBE_CHUNK 任务
            │  每个 chunk 同时 depends_on: [SPLIT_AUDIO, DETECT_LANGUAGE]
            │
            ├─► TRANSCRIBE_CHUNK/1 [SOFT:A, language←detected]  ─┐
            ├─► TRANSCRIBE_CHUNK/2 [SOFT:A, language←detected]   │ 可并行
            ├─► TRANSCRIBE_CHUNK/3 [SOFT:A, language←detected]   │
            └─► TRANSCRIBE_CHUNK/N [SOFT:A, language←detected]  ─┘
                                                                   │
                                                                   ▼
                                                      MERGE_TRANSCRIPTION [无亲和]
                                                                   │
                                                                   ▼
                                                             AI_ANALYZE [无亲和]
```

> **`DETECT_LANGUAGE` 与 `SPLIT_AUDIO` 并行**：两者均在 `EXTRACT_AUDIO` 完成后立即触发，互不依赖。TRANSCRIBE_CHUNK 任务在两者都完成后才能被认领，此时 payload 中已有检测到的语言代码，调度器据此进行语言感知的后端评分。

> **动态任务创建**：`SPLIT_AUDIO` 执行完成后，由执行节点负责向任务队列写入 N 个 `TRANSCRIBE_CHUNK` 任务（依赖关系 `depends_on=[SPLIT_AUDIO.id]`），`MERGE_TRANSCRIPTION` 的 `depends_on` 则包含所有 chunk 任务 ID。这种"任务完成时产生子任务"的模式统一由 `TaskSpawnPolicy` 处理。

#### 节点亲和（Node Affinity）约束

| 约束类型 | 含义 | 调度行为 |
|----------|------|---------|
| `HARD:<node_id>` | 文件仅存在于该节点，任务**必须**在此节点执行 | 其他节点完全不可认领；若该节点离线，任务阻塞直到其上线 |
| `SOFT:<node_id>` | 优先在该节点执行（文件在此），但允许其他节点认领 | 其他节点认领时，须先通过节点间文件服务拉取文件后再执行 |
| 无 | 无文件依赖（输入为文本、URL 或云端） | 任意有能力的节点均可认领 |

`HARD` 亲和的隐含前提：**承担 `DOWNLOAD_VIDEO` 的节点必须同时具备 `FFMPEG` 能力**，否则后续 `EXTRACT_AUDIO`（`HARD` 约束）将永远无法被认领。

为避免这一死锁，流水线调度器在创建 `DOWNLOAD_VIDEO` 任务时，会**过滤掉只有 `DOWNLOAD` 而没有 `FFMPEG` 的节点**：

```
流水线需要: DOWNLOAD → EXTRACT_AUDIO → ...
调度器选取 DOWNLOAD_VIDEO 的候选节点时，额外要求节点具备 [DOWNLOAD, FFMPEG]
→ 只有全能节点（桌面机、NAS+ffmpeg）才会认领 DOWNLOAD_VIDEO
→ 纯 GPU 节点（无 DOWNLOAD）自动排除，不会造成死锁
```

#### 节点间文件服务（File Serving）

`SOFT` 亲和的 `TRANSCRIBE_CHUNK` 任务允许 GPU 节点 B（无 DOWNLOAD 能力）认领，前提是 B 能从 file_node（节点 A）拉取音频块：

```
节点 B 认领 TRANSCRIBE_CHUNK 时：
  1. 读取 task.file_node_id = A，task.payload.chunk_path = "/data/chunks/chunk_2.wav"
  2. 向节点 A 请求：GET http://A:7631/files/serve?path=<encoded_path>
     （使用 cluster_secret 认证，A 校验路径在允许的 .data/ 目录内）
  3. B 将文件缓存到本地临时目录
  4. B 执行 faster-whisper/whisper.cpp，完成后清理临时文件
```

> 文件服务接口只暴露受限路径（`.data/` 下），不允许任意路径遍历。节点间传输走 HTTPS 或 VPN，与用户 API 使用相同的 cluster_secret 认证。

#### 流水线状态追踪

`pipeline_instance` 表汇总整个流水线的进度，WebUI 展示时从此聚合，无需逐任务轮询：

```
pipeline_instance {
  id            流水线实例 ID
  material_id   素材 ID
  template      模板名（如 "bilibili_full_process"）
  status        pending | running | cancelling | cancelled | completed | failed | partial_failed
  total_tasks   任务总数（动态更新，SPLIT_AUDIO 后增加）
  done_tasks    已完成任务数
  created_at
  completed_at
}
```

#### 条件路由（Conditional Routing）

部分任务的结果决定了后续流程**走哪条分支**，而非简单的"执行 / 不执行"。系统通过**结果驱动的动态任务创建**实现条件路由：

> 发起节点在某任务 `completed` 后读取其 `result`，根据业务逻辑**动态创建**后续任务（或将不需要的任务标记为 `skipped`）。这与 `DIARIZE_AUDIO` 完成后动态生成 `TRANSCRIBE_CHUNK` 任务列表是同一模式。

**典型案例：`BURN_SUBTITLE` 前的硬字幕检测**

字幕烧制前需要先判断原视频是否已有硬字幕（burned-in subtitle），结合 `audio_mode`（原声 / 配音）决定后续操作：

```
pipeline 参数: audio_mode = "original" | "dubbed"
               subtitle_path = "/data/subs/abc.srt"

DETECT_HARDCODED_SUBTITLE（采样帧 → Vision LLM / OCR 检测）
    │
    ▼  发起节点读取 result，根据 has_subtitle + audio_mode 分支
    │
    ├─── Case A：has_subtitle = false（原视频无硬字幕）
    │         ↓
    │    BURN_SUBTITLE（直接烧制，无需处理）
    │
    ├─── Case B：has_subtitle = true，audio_mode = "original"（原声 + 已有字幕）
    │         ↓
    │    BURN_SUBTITLE → status: skipped（原字幕已存在，无需重复添加）
    │
    └─── Case C：has_subtitle = true，audio_mode = "dubbed"（配音 + 已有外语字幕）
              ↓
         BLUR_SUBTITLE_REGION（模糊原字幕区域，region 从检测结果中读取）
              ↓
         BURN_SUBTITLE（在净空画面上烧制配音对应语言的字幕）
```

**`DETECT_HARDCODED_SUBTITLE` 详细设计：**

采样策略：从视频中均匀采样 8 帧（跳过片头/片尾各 5%），发送给 Vision LLM 进行判断。

```json
// payload
{
  "video_path": "/data/videos/abc.mp4",
  "sample_frames": 8,
  "sample_skip_pct": 0.05
}

// result
{
  "has_subtitle": true,
  "confidence": 0.94,
  "language_hint": "zh",
  "region": {
    "y_top_pct": 0.84,
    "y_bottom_pct": 0.96
  },
  "detection_method": "vision_llm"  // "vision_llm" | "ocr_fallback"
}
```

Vision LLM prompt（自动生成）：
> "这是视频的 8 帧截图。请判断：① 画面中是否存在硬字幕（烧制在画面上的文字，非字幕轨）？② 如果有，字幕文字所在区域占画面高度的百分比范围是多少（例如底部 84%–96%）？只返回 JSON，格式：`{\"has_subtitle\": bool, \"y_top_pct\": float, \"y_bottom_pct\": float}`"

后备方案（无 Vision LLM 时）：对采样帧运行 `OCR`（`mode: subtitle`），若底部区域检测出文字且置信度 > 0.7，认为有硬字幕，`detection_method` 标记为 `"ocr_fallback"`。

**`BLUR_SUBTITLE_REGION` payload（由编排器从上游 result 自动填充）：**

```json
{
  "video_path": "/data/videos/abc.mp4",
  "y_top_pct": 0.84,
  "y_bottom_pct": 0.96,
  "blur_method": "gaussian",   // gaussian | pixelate | solid_fill
  "blur_strength": 30,         // 高斯半径；pixelate 时为块大小
  "output_path": "/data/videos/abc_blurred.mp4"
}
```

FFMPEG 实现：`-vf "crop=iw:ih*0.12:0:ih*0.84,boxblur=30:30[blur]; [0:v][blur]overlay=0:ih*0.84"`

**条件路由的实现约定：**

1. 发起节点在 `DETECT_HARDCODED_SUBTITLE` 进入 `completed` 后，由 pipeline 模板的 **post-completion hook** 触发路由逻辑
2. `skipped` 任务由发起节点直接写入，不经过工作节点认领流程
3. pipeline 最终状态计算时，`skipped` 视同 `completed`（非错误）
4. 条件路由触发的新任务（`BLUR_SUBTITLE_REGION`、`BURN_SUBTITLE`）追加到同一 pipeline 的任务列表中，`depends_on` 动态设置

---

#### 流水线取消传播

用户取消一个进行中的流水线时，其任务可能已散布在多个执行节点上，取消必须有序地传播。

```
用户操作 → POST /api/v1/PipelineCancelRoute { pipeline_id }
               │
               ▼
         发起节点（本地）
           │
           ├─ 1. pipeline_instance.status = 'cancelling'（过渡状态）
           │
           ├─ 2. 批量取消 pending 任务（纯 SQL，无需通知其他节点）
           │      UPDATE task SET status='cancelled'
           │      WHERE pipeline_id=? AND status='pending'
           │
           ├─ 3. 收集 running/claimed/stale 任务 → 按执行节点分组
           │
           └─ 4. 对每个执行节点广播取消：
                  POST <executor>:7631/cluster/tasks/cancel
                  { task_ids: [...] }
                       │
                       ▼
                 执行节点收到后：
                   ① 向正在运行的推理进程发送中断信号
                   ② UPDATE task SET status='cancelled'
                   ③ 清理该 pipeline 在本节点的中间产物（见下）
                   ④ 响应 OK → 发起节点将该批任务标记为 cancelled
```

**中间产物的清理策略：**

| 文件类型 | 取消时处理 |
|---------|----------|
| `pending` 任务尚未产生的文件 | 无需处理 |
| `running` 任务的临时输出（未完成） | 立即删除 |
| 已 `completed` 任务的输出文件（如提取好的音频） | 延迟删除：标记为"待清理"，在流水线全部 cancelled 后统一清理 |
| `result_acked_at` 有值的结果文件 | 不清理（发起节点已持有，与流水线取消无关） |

**流水线最终进入 `cancelled` 状态的条件：**
- 所有任务均为终态（`cancelled` / `completed` / `failed` / `skipped`）
- 若存在执行节点不可达（无法广播取消），则保持 `cancelling`，待节点上线后补发取消指令

---

### 2.7 统一调度打分模型

调度器在为每个待处理任务选择执行节点时，对每个候选节点计算**有效分（effective_score）**，取最高分节点优先认领。有效分由多个修正项叠加：

```
effective_score = max(10,
    capability_base_score       // 后端/模型固有质量分（见 §3 各小节）
    + hardware_modifier         // 节点硬件性能修正（本节）
    + cloud_preference_modifier // 发布者 cloud_preference 修正（见 §2.5）
    + language_modifier         // 语言感知修正，仅 TRANSCRIBE/OCR（见 §3.2 / §3.6）
    + load_modifier             // 节点队列深度修正（本节）
)
```

> 下限 `max(10, ...)` 保证任何节点至少以 10 分参与竞争，避免因负向修正过多导致所有节点有效分 ≤ 0 而无人认领。

---

#### 2.7.1 硬件性能修正（hardware_modifier）

`capability_base_score` 衡量的是"该后端/模型的质量上限"，但两台机器可以声明相同的 `faster-whisper + large-v3`，GPU 性能却天差地别。**硬件修正** 在此基础上叠加节点硬件实力：

```
// GPU 密集型后端（LocalGPU 加速器）
hardware_modifier = (node.gpu_score - 70) × 0.4
    // gpu_score=100（RTX 4090）  → +12
    // gpu_score=85 （RTX 4070）  → +6
    // gpu_score=58 （RTX 3060）  → -5
    // gpu_score=40 （GTX 1070）  → -12

// CPU 密集型后端（LocalCPU 加速器）
hardware_modifier = (node.cpu_score - 50) × 0.3
    // cpu_score=90（32+ 核服务器）→ +12
    // cpu_score=55（8 核中端 CPU）→ +1.5
    // cpu_score=15（树莓派）       → -10.5

// 云端后端（CloudAPI 加速器）
hardware_modifier = 0  // 云端不受本地硬件影响
```

**`gpu_score` 的获取流程（节点启动时执行一次，结果缓存到 `_runtime`）：**

```
1. 若 hardware.gpu_score_override 非 null → 直接使用，跳过后续步骤
2. 通过 nvidia-smi / system_profiler / rocm-smi 获取 GPU 名称和 VRAM
3. 查内置 GPU 天梯表（覆盖约 40 款主流 GPU，见下表）
   → 命中 → 使用表中分数
4. 未命中 → 若 hw_score_llm.enabled=true 且 ai_analyze 能力可用
   → 向本地/云端 LLM 发送评分请求（prompt 见下方）
   → 解析返回的 0–100 整数
5. LLM 不可用 → 退回 VRAM 估算公式：
   gpu_score = min(100, max(10, vram_gb × 7))
```

**LLM 硬件评分 prompt：**
```
你是一个 AI 推理性能专家。请评估以下 GPU 用于本地 AI 推理任务（语音转文字、目标检测、图像生成等）
的综合性能，给出 0–100 的整数分数。100 = RTX 4090 水平，50 = RTX 3060 水平，25 = GTX 1070 水平。
GPU: {{gpu_name}}，VRAM: {{vram_gb}}GB
只需返回一个整数，不要任何解释。
```

**内置 GPU 天梯表（部分）：**

| GPU | Score | GPU | Score |
|-----|-------|-----|-------|
| RTX 4090 | 100 | RTX 3090 / 3090 Ti | 88 |
| RTX 4080 Super | 95 | RTX 3080 Ti | 85 |
| RTX 4070 Ti Super | 90 | RTX 3080 | 80 |
| RTX 4070 Super | 87 | RTX 3070 Ti | 73 |
| RTX 4070 | 83 | RTX 3070 | 68 |
| RTX 4060 Ti | 76 | RTX 3060 Ti | 63 |
| RTX 4060 | 68 | RTX 3060 | 58 |
| RTX 2080 Ti | 70 | GTX 1080 Ti | 52 |
| RTX 2070 Super | 60 | GTX 1070 | 40 |
| Apple M4 Max | 92 | AMD RX 7900 XTX | 90 |
| Apple M3 Max | 85 | AMD RX 7800 XT | 72 |
| Apple M3 Pro | 72 | AMD RX 6800 XT | 65 |
| Apple M2 Ultra | 88 | Intel Arc A770 | 55 |

**`cpu_score` 的获取（基于逻辑核心数估算，无需 LLM）：**

| 逻辑核心数 | cpu_score | 典型场景 |
|-----------|-----------|---------|
| ≥ 32 | 90 | 服务器 / 工作站 |
| 16–31 | 75 | 高端桌面（i9 / Ryzen 9） |
| 8–15 | 55 | 主流桌面（i7 / Ryzen 7） |
| 4–7 | 35 | 入门桌面（i5 / Ryzen 5） |
| < 4 | 15 | 低功耗设备（树莓派等） |

> `cpu_score_override` 可手动指定，适合特殊架构（如 Apple Silicon 的 CPU 侧）。

---

#### 2.7.2 负载修正（load_modifier）

节点排队任务越多，响应新任务的速度越慢。**负载修正** 让调度器偏向空闲节点，但力度受 `quality_mode` 控制：

```
load_ratio    = min(1.0, node.pending_tasks / node.max_workers)

quality_weight = {
    "quality":  0.0,   // 完全忽略负载，始终选最优质量节点
    "balanced": 0.5,   // 中等惩罚
    "speed":    1.0,   // 完整惩罚，优先选最空闲节点
}

load_modifier = -load_ratio × scheduling.load_penalty.max_penalty × quality_weight
    // 示例（max_penalty=20，节点半载 load_ratio=0.5）：
    //   quality  → 0
    //   balanced → -5
    //   speed    → -10
```

**`quality_mode` 的优先级（从高到低）：**

```
1. 任务级 overrides.{TASK_TYPE}.quality_mode（pipeline 创建时单独覆盖）
2. pipeline 级 quality_mode 字段
3. 节点默认 scheduling.quality_defaults[task_type]（worker.yaml）
4. 全局默认：balanced
```

**WebUI 质量降级确认：**

当 pipeline `quality_mode` 设为 `"speed"`，但其中包含默认为 `"quality"` 的任务类型（如 `TRANSCRIBE_CHUNK`、`UPSCALE_VIDEO`）时，WebUI 在发布前弹出确认对话框：

> **允许质量降级？**
> 以下任务通常需要最优节点，但当前设置为优先选择空闲节点，可能影响输出质量：
> - `TRANSCRIBE_CHUNK`（转录，默认：quality）
> - `UPSCALE_VIDEO`（超分，默认：quality）
>
> ☐ 我接受为了更快响应而使用较低质量的节点
>
> [确认] [返回调整]

用户勾选确认后，pipeline 创建请求中附带 `quality_tradeoff_acknowledged: true`，服务端据此允许覆盖 `quality_defaults`。

---

#### 2.7.3 综合调度示例

**场景**：一个 `TRANSCRIBE_CHUNK` 任务，`cloud_preference=auto`，`language=zh`，`quality_mode=balanced`。

集群中有三个节点，各自能力如下：

| 节点 | 后端 | 基础分 | gpu_score | hardware_modifier | cloud_modifier | language_modifier | load（0.5 载，balanced） | 有效分 |
|------|------|-------|-----------|-------------------|----------------|-------------------|-----------------------|-------|
| A | faster-whisper / RTX 4070 / large-v3 | 95 | 83 | +5 | 0 | +5（zh 中文专用类）| -5 | **100** |
| B | faster-whisper / RTX 3060 / medium | 72 | 58 | -5 | 0 | +5 | 0（空载）| **72** |
| C | xunfei-iat-api / CloudAPI | 68 | — | 0 | 0 | +5 | 0 | **73** |

→ 节点 A 有效分 100，优先认领。节点 A 满载后，C（云端 73）略优于 B（本地但有负载 72），体现"云端在本地满载时自然补位"。

---

## 3. 能力后端清单与评分规则

本章列出每种能力函数支持的后端实现及其评分规则。所有分数均为同类能力内部比较，**不同能力函数的分数不跨类比较**。`cloud_preference` 对云端后端的分数修正规则见 §2.5。

---

### 3.1 DOWNLOAD_* — 文件下载

*① `DOWNLOAD_BILIBILI` — 哔哩哔哩下载*

| 后端 | 工具依赖 | 说明 |
|------|---------|------|
| `bilibili-pyutil` | `fredica-pyutil`（Python 包）| 支持 Cookie/SESSDATA、多清晰度、番剧、付费内容 |

> **`fredica-pyutil` 的部署说明：**
> - **Desktop 节点**：Python 运行时与 `fredica-pyutil` 均嵌入安装包（`desktop_assets/common/fredica-pyutil/`），随 `composeApp` 启动时自动以子进程方式运行，无需额外操作。
> - **Server / NAS 节点**：需从源码目录手动启动 `fredica-pyutil` 服务（`uvicorn fredica_pyutil_server.app:app --port 7632`），并在 `worker.yaml` 中通过 `pyutil_path` 指定服务地址；未运行则不声明此能力。
> - GFW：Bilibili 对 CN 网络无限制，`network_region = "CN"` 节点可正常使用。

*② `DOWNLOAD_YOUTUBE` — YouTube 及通用视频平台下载*

| 后端 | 工具依赖 | GFW | 说明 |
|------|---------|-----|------|
| `youtube-ytdlp` | `yt-dlp` | 🔴 CN 受限 | 支持 YouTube、Twitter/X、Instagram 等上百个平台 |

> `network_region = "CN"` 且 `has_proxy = false` 的节点**不声明**此能力。

*③ `DOWNLOAD_HTTP` — 通用 HTTP/HTTPS 直链下载*

| 后端 | 工具依赖 | 说明 |
|------|---------|------|
| `http-aria2c` | `aria2c` | 多连接（最高 16 线程），大文件最快 |
| `http-ytdlp` | `yt-dlp` | 兼顾平台识别，比 aria2c 稍慢 |
| `http-curl` | `curl`（系统自带）| 单连接，无额外依赖，兜底 |

*④ `DOWNLOAD_HLS` — HLS / DASH 流媒体下载*

| 后端 | 工具依赖 | 说明 |
|------|---------|------|
| `hls-ffmpeg` | `ffmpeg` | `ffmpeg -i "*.m3u8" -c copy`，稳定可靠 |
| `hls-ytdlp` | `yt-dlp` | 自动识别 HLS 源，支持加密流 |

**综合打分（`有效分 = 工具基础分 + 带宽加成 + 存储亲和加成`）：**

| 因素 | 细则 | 分值 |
|------|------|------|
| **工具基础分** | `bilibili-pyutil` | 70 |
| | `youtube-ytdlp` | 65 |
| | `http-aria2c` | 60 |
| | `http-ytdlp` | 55 |
| | `hls-ffmpeg` | 50 |
| | `http-curl` | 40 |
| **带宽加成** | ≥ 500 Mbps | +15 |
| | 100–499 Mbps | +8 |
| | 20–99 Mbps | 0 |
| | < 20 Mbps | −10 |
| **存储亲和加成** | `is_storage_node=true` + `storage_preference="nas"` | +20 |

---

### 3.2 TRANSCRIBE — 语音转文字

*本地后端：*

| 后端 | 加速器 | 典型模型 | 基础分数 | 说明 |
|------|--------|----------|----------|------|
| `faster-whisper` | LocalGPU (≥8GB) | large-v3 | 90–100 | CTranslate2 优化，速度最快 |
| `faster-whisper` | LocalGPU (4–8GB) | medium / large-v2 | 70–85 | |
| `whisper.cpp` | LocalGPU (MPS/CUDA) | large-v3 | 65–80 | Apple Silicon MPS 表现优秀 |
| `faster-whisper` | LocalCPU | large / medium | 30–50 | 速度慢，但准确率不损失 |
| `whisper.cpp` | LocalCPU | medium / small | 20–40 | |
| `faster-whisper` | LocalCPU | small / base | 10–20 | 低配兜底 |

*云端 API 后端（基础分数已计入隐私/成本折扣，可被 `cloud_preference` 修正）：*

| 后端 | 适用语言 | 基础分数 | 说明 |
|------|----------|----------|------|
| `xunfei-iat-api` | 中文最优 | 65–72 | 科大讯飞，中文识别业界顶级 |
| `aliyun-nls-api` | 中文优秀 | 62–70 | 阿里云 NLS |
| `volcengine-asr-api` | 中文优秀 | 60–68 | 火山引擎（字节跳动） |
| `tencent-asr-api` | 中文良好 | 58–65 | 腾讯云 ASR |
| `groq-whisper-api` | 多语言 | 60–68 | Groq LPU，Whisper 质量 + 极速 |
| `openai-whisper-api` | 多语言 | 58–65 | OpenAI whisper-1 |
| `deepgram-api` | 多语言 | 55–63 | 低延迟，支持实时流式 |
| `assemblyai-api` | 多语言 | 55–63 | 支持说话人分离 |
| `azure-speech-api` | 多语言 | 52–60 | Azure Cognitive Services |
| `google-speech-api` | 多语言 | 52–60 | Google Cloud STT |
| `baidu-asr-api` | 中文 | 50–58 | 百度语音 |
| `aws-transcribe-api` | 多语言 | 48–56 | Amazon Transcribe |

#### 语言感知打分修正

TRANSCRIBE_CHUNK 任务的 payload 携带 `language` 字段（来自流水线创建参数或 `DETECT_LANGUAGE` 任务的输出）。调度器在评估各节点的有效分时，在基础分之上叠加**语言修正量**：

```
有效分 = max(10, 基础分 + cloud_preference 修正 + language 修正)
```

后端按语言专长分为四类：

| 类别 | 后端 |
|------|------|
| **中文专用云端** | `xunfei-iat-api` · `aliyun-nls-api` · `volcengine-asr-api` · `tencent-asr-api` · `baidu-asr-api` |
| **Whisper 系**（本地 + 云端）| `faster-whisper` · `whisper.cpp` · `groq-whisper-api` · `openai-whisper-api` |
| **英文优先云端** | `deepgram-api` · `assemblyai-api` |
| **多语言均衡云端** | `azure-speech-api` · `google-speech-api` · `aws-transcribe-api` |

**语言修正量（`language_modifier`）：**

| 语言 | 中文专用 | Whisper 系（large 模型）| Whisper 系（small/medium）| 英文优先 | 多语言均衡 |
|------|---------|----------------------|--------------------------|---------|-----------|
| `zh` 普通话 | **+15 ~ +20** | +5 | +2 | −8 | 0 |
| `zh-yue` 粤语 | +5（仅讯飞支持粤语模式；其余−5）| +3 | 0 | −8 | 0 |
| `en` 英文 | **−15 ~ −20** | +8 | +5 | **+10** | +5 |
| `ja` 日语 | **−15 ~ −20** | **+12** | +5 | +5 | +5 |
| `ko` 韩语 | −12 ~ −18 | +5 | +2 | +3 | +5 |
| `auto` 未知 | 0 | 0 | 0 | 0 | 0 |

> **`auto` 为何不调整**：语言未知时用基础分兜底，发布者若未声明语言且未指定 `DETECT_LANGUAGE`，则退化为与语言无关的通用调度，不惩罚任何后端。

**典型调度场景（`zh`，cloud_preference = "auto"）：**

```
节点 A: faster-whisper/LocalGPU(RTX 4070)/large-v3 → 基础 95 + zh +5   = 有效分 100
节点 B: faster-whisper/LocalGPU(RTX 3060)/medium   → 基础 72 + zh +2   = 有效分  74
节点 C: xunfei-iat-api/CloudAPI                    → 基础 68 + zh +20  = 有效分  88  ← 云端此时超过 B
节点 D: deepgram-api/CloudAPI                      → 基础 55 + zh −8   = 有效分  47
节点 E: faster-whisper/LocalCPU/medium             → 基础 38 + zh +2   = 有效分  40

调度顺序：A(100) > C(88) > B(74) > D(47) > E(40)
讯飞 API 因中文专长跃升至第二，在 B 满载时优先于本地 medium 节点。
```

**典型调度场景（`ja`，cloud_preference = "auto"）：**

```
节点 A: faster-whisper/LocalGPU/large-v3  → 基础 95 + ja +12  = 有效分 107
节点 C: xunfei-iat-api/CloudAPI           → 基础 68 + ja −18  = 有效分  50  ← 大幅下降
节点 F: assemblyai-api/CloudAPI           → 基础 55 + ja +5   = 有效分  60
节点 G: azure-speech-api/CloudAPI         → 基础 52 + ja +5   = 有效分  57

调度顺序：A(107) > F(60) > G(57) > C(50)
中文专用 API 在日语任务中被压到末位。
```

#### DETECT_LANGUAGE 任务设计

`DETECT_LANGUAGE` 使用与 `TRANSCRIBE` 相同的后端（`faster-whisper` 或 `whisper.cpp`），但仅对音频**前 30 秒**执行语言检测模式，不进行完整转录：

```
payload:
  audio_path    要检测的音频文件路径（来自 EXTRACT_AUDIO 的输出）
  sample_sec    采样秒数（默认 30，不超过音频总时长）

result:
  language      检测到的语言代码（如 "zh"、"en"、"ja"）
  confidence    置信度（0.0 ~ 1.0）
  alternatives  [ { language, confidence }, ... ]   ← 备选语言
```

- **执行速度**：30s 音频的语言检测通常在 2s 内完成（GPU）或 10s 内完成（CPU），几乎不阻塞后续流程
- **结果写入**：检测完成后，pipeline 调度器读取 result，将 `language` 填入所有待创建/待认领的 `TRANSCRIBE_CHUNK` 任务 payload
- **置信度阈值**：若 `confidence < 0.6`，视为检测失败，退回 `auto`（无语言修正）而不是报错

---

### 3.3 AI_ANALYZE — AI 内容分析

云端 API 是平等甚至首选的后端，不需要 GPU，配置 API Key 即可让任意节点具备此能力。

| 后端 | 加速器 | 典型模型 | 分数区间 | 说明 |
|------|--------|----------|----------|------|
| `ollama` / `lm-studio` | LocalGPU (≥16GB) | Qwen2.5-72B 等 | 80–100 | 本地大模型，无网络依赖 |
| `deepseek-api` | CloudAPI | deepseek-v3 / r1 | 70–85 | 性价比高，适合批量分析 |
| `openai-api` | CloudAPI | gpt-4o / gpt-4o-mini | 65–85 | |
| `anthropic-api` | CloudAPI | claude-3-5-haiku 等 | 65–85 | |
| `openai-compatible` | CloudAPI | 任意兼容端点 | 60–80 | 兼容各类自建/第三方服务 |
| `ollama` | LocalCPU | Qwen2.5-7B / 14B | 20–40 | 无 GPU 时的本地兜底 |

**Vision LLM 扩展（图像输入）：**

`AI_ANALYZE` 的输入不限于纯文本——当后端支持多模态时，任务 payload 可携带图像路径列表，由后端内部将图像编码为 base64 嵌入 API 请求。`DETECT_HARDCODED_SUBTITLE` 即为此模式：编排器采样若干视频帧后，将帧路径写入 payload，由 AI_ANALYZE 节点发送给 Vision LLM 进行判断。

| Vision LLM 后端 | 说明 |
|----------------|------|
| `openai-api` (gpt-4o / gpt-4o-mini) | 原生视觉能力，图像理解最强 |
| `anthropic-api` (claude-3-5-haiku) | 视觉能力强，对布局/区域描述准确 |
| `ollama` (qwen2-vl / llava) | 本地 Vision LLM，需 ≥8GB VRAM |
| `deepseek-api` (deepseek-vl2) | 性价比高，中文图像理解优秀 |

> 若集群内无 Vision LLM 可用，`DETECT_HARDCODED_SUBTITLE` 自动降级为 OCR 后备检测（`detection_method: "ocr_fallback"`）。

---

### 3.4 FFMPEG — 视频/音频处理

| 后端 | 加速器 | 分数 | 说明 |
|------|--------|------|------|
| `ffmpeg-nvenc` | LocalGPU (NVIDIA) | 90 | 硬件编码，速度约为 CPU 的 10–50× |
| `ffmpeg-videotoolbox` | LocalGPU (Apple Silicon) | 85 | |
| `ffmpeg-vaapi` | LocalGPU (Intel/AMD, Linux) | 80 | |
| `ffmpeg-cpu` | LocalCPU | 40 | 软件编码，兜底 |

**字幕相关任务（均属 FFMPEG 能力）：**

| 任务类型 | ffmpeg 命令要点 | 说明 |
|---------|--------------|------|
| `EXTRACT_SUBTITLE` | `-map 0:s:0 output.srt` | 提取 MKV/MP4 内嵌字幕轨；支持多轨选择 |
| `BLUR_SUBTITLE_REGION` | `-vf "boxblur=...` + `overlay` | 模糊原字幕区域（配音场景）；区域坐标来自 `DETECT_HARDCODED_SUBTITLE` result |
| `BURN_SUBTITLE` | `-vf "subtitles=sub.srt"` / `ass=sub.ass` | 硬编码字幕；ASS 格式保留字体/颜色/特效；需经条件路由（见 §2.6）|
| `FINGERPRINT_VIDEO` | 低帧率抽帧（1fps）→ pHash 计算 | 感知哈希用于重复视频检测；输出帧哈希列表 |

> `BURN_SUBTITLE` 的完整决策流程（是否需要先 `BLUR_SUBTITLE_REGION`、是否需要执行）由 `DETECT_HARDCODED_SUBTITLE` 结果驱动，详见 **§2.6 条件路由**。

`FORMAT_SUBTITLE` 是纯文本处理（断行算法 + 格式转换），不依赖 FFMPEG，无需能力声明，在调度节点本地直接执行。

**DETECT_SCENES（镜头切换检测）：**

`DETECT_SCENES` 任务属于 `FFMPEG` 能力，无需独立 CapabilityFunction。实现方式：

| 后端实现 | 说明 |
|---------|------|
| `ffmpeg-scene`（内置） | `ffmpeg -i input.mp4 -vf "select='gt(scene,0.4)',showinfo" ...`，零额外依赖 |
| `pyscenedetect` | PySceneDetect 库，支持硬切（ContentDetector）和渐变（ThresholdDetector），精度更高 |

输出场景时间轴（JSON），可替代 `SPLIT_AUDIO` 固定切片，实现语义化分段。`cache_policy: CHECK_EXISTING`，同一视频重复运行直接复用。

---

### 3.5 TTS — 语音合成

TTS 能力用于将文本（AI 分析摘要、字幕、解说稿等）转换为语音。在 Fredica 的使用场景中，**中文合成质量**和**声音克隆**是两个关键评估维度。

#### 关联任务类型

`GENERATE_TTS`：输入文本（或 SRT 字幕文件）→ 输出合成语音文件（WAV/MP3）。

**典型流水线：**

```
MERGE_TRANSCRIPTION → TRANSLATE_TEXT → GENERATE_TTS（配音合成）
```

也可独立使用，将 AI_ANALYZE 生成的摘要/解说稿合成为播报音频：

```
AI_ANALYZE → GENERATE_TTS（摘要播报）
```

#### payload 设计

```json
{
  "text": "需要合成的文字内容……",      // 与 srt_path 二选一
  "srt_path": "/data/subs/abc.srt",   // SRT 文件路径（逐条合成并对齐时间轴）
  "language": "zh",                   // zh | en | ja | ko | auto
  "voice": "default",                 // 声音标识符（后端相关，见各后端文档）
  "voice_clone": false,               // true = 需要声音克隆（仅路由到支持克隆的后端）
  "reference_audio": "",             // 声音克隆参考音频路径（voice_clone=true 时必填）
  "speed": 1.0,                       // 语速（0.5–2.0）
  "output_format": "wav"              // wav | mp3 | flac
}
```

*本地后端（优先，无网络依赖）：*

| 后端 | 加速器 | 基础分数 | 中文质量 | 声音克隆 | 说明 |
|------|--------|----------|----------|----------|------|
| `cosyvoice` | LocalGPU (≥6GB) | 88–95 | ⭐⭐⭐⭐⭐ | ✅ | 阿里开源，中文最优，支持 zero-shot 克隆 |
| `gpt-sovits` | LocalGPU (≥4GB) | 82–90 | ⭐⭐⭐⭐⭐ | ✅ | 社区活跃，中文声线克隆主流方案 |
| `f5-tts` | LocalGPU (≥4GB) | 80–88 | ⭐⭐⭐⭐ | ✅ | 推理速度快，质量接近 SOTA |
| `fish-speech` | LocalGPU (≥4GB) | 78–85 | ⭐⭐⭐⭐ | ✅ | 多语言，中英文均优秀，推理高效 |
| `xtts` | LocalGPU (≥4GB) | 72–80 | ⭐⭐⭐ | ✅ | Coqui XTTS v2，多语言，克隆效果稳定 |
| `kokoro` | LocalGPU / LocalCPU | 65–75 | ⭐⭐⭐ | ❌ | 轻量级，CPU 可用，Apache 2.0 开源 |
| `chattts` | LocalGPU (≥4GB) | 62–72 | ⭐⭐⭐⭐ | ❌ | 对话风格自然，中文表现好 |
| `edge-tts` | LocalCPU* | 55–65 | ⭐⭐⭐ | ❌ | 微软神经语音，免费但实际调用微软云端 |
| `bark` | LocalGPU (≥8GB) | 50–62 | ⭐⭐ | ❌ | 表现力强但速度极慢，仅适合离线批量 |
| `piper` | LocalCPU | 38–50 | ⭐⭐ | ❌ | 极轻量，树莓派可用，质量一般 |

> `edge-tts` 虽作为 LocalCPU 后端运行（无 GPU 要求、无 API Key），但实际调用微软 Edge 云端 TTS 服务，需要网络连接且受 GFW 限制。

*云端 API 后端（按字符计费，基础分数已计入成本折扣）：*

| 后端 | 基础分数 | 中文质量 | 声音克隆 | 说明 |
|------|----------|----------|----------|------|
| `elevenlabs-api` | 72–82 | ⭐⭐⭐⭐ | ✅ | 云端最高质量，支持声音克隆，价格较高 |
| `xunfei-tts-api` | 65–75 | ⭐⭐⭐⭐⭐ | ❌ | 科大讯飞，中文云端 TTS 顶级 |
| `minimax-tts-api` | 62–72 | ⭐⭐⭐⭐ | ✅ | MiniMax（海螺），中文好，支持克隆 |
| `openai-tts-api` (hd) | 65–75 | ⭐⭐⭐ | ❌ | tts-1-hd，质量高，延迟稍大 |
| `openai-tts-api` | 58–68 | ⭐⭐⭐ | ❌ | tts-1，标准质量 |
| `fish-audio-api` | 60–70 | ⭐⭐⭐⭐ | ✅ | Fish Speech 云端版，中文优秀 |
| `volcengine-tts-api` | 58–68 | ⭐⭐⭐⭐ | ❌ | 火山引擎（字节跳动） |
| `aliyun-tts-api` | 55–65 | ⭐⭐⭐ | ❌ | 阿里云 TTS |
| `azure-tts-api` | 55–65 | ⭐⭐⭐ | ❌ | Azure 神经语音，音色选择多 |
| `google-tts-api` | 50–60 | ⭐⭐ | ❌ | Google Cloud TTS |

**特殊调度考量：**
- **声音克隆任务**：需要在任务 payload 中声明 `voice_clone: true`，调度器仅将任务路由到支持克隆的后端（标注 ✅ 的节点）
- **GPU 依赖**：本地高质量 TTS（cosyvoice / gpt-sovits / f5-tts）均需 GPU；GPU 节点不可用时，退回 `kokoro`（CPU 可用）或云端 API
- **cloud_preference** 修正规则与其他能力相同（见 §2.5）

---

### 3.6 OCR — 图像文字识别

OCR 能力用于从视频帧或图片中提取文字（硬字幕、屏录文档、幻灯片、水印等）。与 TRANSCRIBE 不同，OCR 的输入是**图像序列**而非音频，因此需要先通过 FFMPEG 抽帧再批量识别。

#### 关联任务类型

| 任务类型 | 说明 | 所需能力 |
|---------|------|---------|
| `EXTRACT_FRAMES` | 按固定间隔（默认 1fps）抽取 PNG 帧，输出帧文件列表 | `FFMPEG`（HARD 亲和） |
| `OCR_FRAME_BATCH` | 对一批帧文件并行 OCR，输出逐帧文字结构 | `OCR`（SOFT 亲和） |
| `MERGE_OCR_RESULTS` | 合并所有批次结果，去重相邻重复帧，按时间轴输出完整文字流 | 无 |

> **EXTRACT_FRAMES** 在视频已抽帧的场景下可与 `GENERATE_THUMBNAIL` 复用结果（`cache_policy: CHECK_EXISTING`）。

**典型流水线（含硬字幕提取）：**

```
DOWNLOAD_VIDEO
    └─→ EXTRACT_AUDIO ──┬─→ DETECT_LANGUAGE ─┐
    │                   └─→ SPLIT_AUDIO ──────┴─→ TRANSCRIBE_CHUNK × N → MERGE_TRANSCRIPTION
    └─→ EXTRACT_FRAMES → OCR_FRAME_BATCH × M → MERGE_OCR_RESULTS
                                                       ↓
                                               AI_ANALYZE（融合转录 + OCR 双路文本）
```

#### payload 设计

**EXTRACT_FRAMES payload：**
```json
{
  "video_path": "/data/videos/abc.mp4",
  "fps": 1,                   // 抽帧率（frames per second）；默认 1；硬字幕可调至 2
  "output_dir": "/data/frames/abc/",
  "format": "png",            // png | jpg
  "start_sec": 0,             // 可选：起始时间（秒）
  "end_sec": null             // 可选：结束时间（null = 全片）
}
```

**OCR_FRAME_BATCH payload：**
```json
{
  "frame_paths": [            // 本批次帧文件路径列表（由调度器拆分批次）
    "/data/frames/abc/0001.png",
    "/data/frames/abc/0002.png"
  ],
  "language": "auto",        // auto | zh | en | ja | ko | 混合("zh+en")
  "mode": "subtitle",        // subtitle（硬字幕，优先底部区域）| document | auto
  "region_hint": "bottom"    // 可选：优先识别区域 top | bottom | full
}
```

**OCR_FRAME_BATCH result（逐帧）：**
```json
{
  "frames": [
    {
      "path": "/data/frames/abc/0001.png",
      "timestamp_sec": 1.0,
      "texts": [
        { "text": "这是一段硬字幕", "confidence": 0.97, "bbox": [120, 680, 760, 720] }
      ]
    }
  ]
}
```

#### 后端清单与评分规则

**本地后端：**

| 后端标识 | 基础分 | 特点 | GPU 要求 |
|---------|-------|------|---------|
| `paddleocr-gpu` | 88–95 | PaddleOCR v4，中文最优，GPU 加速推理极快 | ✅ 需要 |
| `paddleocr-cpu` | 60–70 | 同上，CPU 降级，中文仍优于其他 CPU 方案 | ❌ |
| `rapidocr` | 55–65 | RapidOCR（ONNX），零依赖，跨平台，CPU 快 | ❌ |
| `surya` | 72–82 | Surya，多语言文档 OCR 效果好，GPU 优先 | ⚠️ 可选 |
| `easyocr` | 58–68 | EasyOCR，多语言支持广，GPU 加速 | ⚠️ 可选 |
| `tesseract` | 35–45 | Tesseract 5，纯 CPU，精度低但零依赖 | ❌ |

**云端后端：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `baidu-ocr-api` | 62–70 | 百度智能云 OCR，中文识别精准，按页计费 |
| `tencent-ocr-api` | 60–68 | 腾讯云 OCR，通用文字识别 |
| `aliyun-ocr-api` | 58–66 | 阿里云 OCR，接入简单 |
| `volcengine-ocr-api` | 58–66 | 火山引擎（字节跳动），中文好 |
| `google-vision-api` | 65–75 | Google Vision，多语言最强，价格适中 |
| `azure-vision-api` | 63–72 | Azure Computer Vision，Read API |
| `aws-textract-api` | 60–68 | AWS Textract，文档结构识别好 |

> **云端 OCR 默认关闭**（`cloud_policy.ocr.cloud_allowed: false`）：视频帧可能含有私密内容（人脸、隐私文字），运营者需显式开启。

#### 语言修正规则

OCR 的语言先验知识对精度影响显著（尤其是中日韩混排和小字号场景）：

| 语言声明 | PaddleOCR 系 | Surya / EasyOCR | Tesseract | 云端 API |
|---------|-------------|-----------------|-----------|---------|
| `zh` | +15 | +5 | +3 | +5（国内优先）|
| `en` | −5 | +8 | +10 | 0 |
| `ja` | −8 | +10 | +5 | +3 |
| `ko` | −10 | +8 | +3 | +3 |
| `zh+en` | +8 | +5 | −5 | +3 |
| `auto` | 0 | 0 | 0 | 0 |

> `language` 字段由 pipeline 发布时的 `language` 参数传播，或由 `DETECT_LANGUAGE` 的结果自动填充。

#### 特殊调度考量

- **字幕 vs 文档模式**：`mode: subtitle` 仅识别画面底部约 20% 区域，显著降低计算量；`mode: document` 全帧识别，适合屏录 / 幻灯片。
- **批次拆分**：调度器按 `batch_size`（默认 50 帧/批）拆分，多节点并行 `OCR_FRAME_BATCH`；`MERGE_OCR_RESULTS` 依赖所有批次完成。
- **文件亲和**：`EXTRACT_FRAMES` 输出帧位于执行节点本地，后续 `OCR_FRAME_BATCH` 应以 **SOFT** 亲和调度到同一节点，避免跨节点传输大量图片。
- **有效分计算**：`max(10, base_score + cloud_preference_modifier + language_modifier)`，规则与其他能力相同（见 §2.5）。

---

### 3.7 OBJECT_DETECT — 目标检测

目标检测能力对视频帧运行物体识别模型（YOLO 系列、RT-DETR 等），输出带时间戳的物体标签流，可直接融入 AI_ANALYZE 做内容摘要与自动打标。

#### 关联任务类型

与 OCR 同构：`EXTRACT_FRAMES`（FFMPEG/HARD）→ `DETECT_FRAME_BATCH`（OBJECT_DETECT/SOFT）× N → `MERGE_DETECT_RESULTS`

**典型流水线（含目标检测）：**

```
DOWNLOAD_VIDEO
    └─→ EXTRACT_FRAMES → DETECT_FRAME_BATCH × N → MERGE_DETECT_RESULTS
                                                          ↓
                                              AI_ANALYZE（视觉标签 + 转录文本融合）
```

#### payload 设计

**DETECT_FRAME_BATCH payload：**
```json
{
  "frame_paths": ["/data/frames/abc/0001.png", "..."],
  "model": "yolov8n",           // yolov8n | yolov8s | yolov8m | yolov8l | rtdetr-l | ...
  "conf_threshold": 0.25,       // 置信度阈值（低于此值丢弃）
  "classes": [],                // 留空 = 检测所有类别；填写 COCO 类别名 = 过滤
  "mode": "object"              // object（通用目标）| face（人脸）| pose（姿态估计）
}
```

**DETECT_FRAME_BATCH result：**
```json
{
  "frames": [
    {
      "path": "/data/frames/abc/0001.png",
      "timestamp_sec": 1.0,
      "detections": [
        { "label": "person", "confidence": 0.91, "bbox": [120, 80, 400, 600] },
        { "label": "laptop", "confidence": 0.87, "bbox": [200, 300, 500, 520] }
      ]
    }
  ]
}
```

#### 后端清单与评分规则

**本地后端：**

| 后端标识 | 基础分 | 模型 | GPU 要求 | 说明 |
|---------|-------|------|---------|------|
| `yolo-gpu` | 88–95 | YOLOv8n/s/m/l/x | ✅ 需要 | Ultralytics YOLO，GPU 推理极快，中文社区活跃 |
| `rtdetr-gpu` | 85–92 | RT-DETR-L/X | ✅ 需要 | Transformer 检测器，精度高于 YOLOv8 |
| `yolo-cpu` | 45–60 | YOLOv8n/s | ❌ | CPU 推理，仅推荐 nano/small 模型 |
| `yolo-onnx` | 55–68 | YOLOv8n/s (ONNX) | ❌ | ONNXRuntime，跨平台，免 PyTorch 依赖 |

**云端后端：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `google-vision-detect-api` | 60–68 | Google Vision Object Localization，支持通用物体 |
| `azure-vision-detect-api` | 58–66 | Azure Computer Vision 4.0，Dense Captions + 目标检测 |
| `aws-rekognition-api` | 56–64 | AWS Rekognition，人脸/物体/内容审核 |

> **隐私提示**：目标检测帧中可能含人脸等敏感信息，`cloud_policy.object_detect.cloud_allowed` 默认 `false`。

#### 特殊调度考量

- **模型大小 vs 速度**：`yolov8n`（nano）在 CPU 可用，`yolov8l/x` 需要 ≥8GB VRAM；`model` 字段由 pipeline 发布时指定或使用节点默认值。
- **批次拆分**：同 OCR，默认 50 帧/批，`MERGE_DETECT_RESULTS` 依赖所有批次完成。
- **文件亲和**：`EXTRACT_FRAMES` 输出帧与后续 `DETECT_FRAME_BATCH` 应 SOFT 亲和到同一节点，避免跨节点传输图片。
- **与 OCR 复用帧**：若同一视频同时需要 OCR 和目标检测，`EXTRACT_FRAMES` 仅执行一次（`CHECK_EXISTING`），两个能力共用帧文件。

---

### 3.8 TRANSLATE — 文本翻译

TRANSLATE 能力将文本（转录稿、OCR 结果、AI 摘要）翻译为目标语言。与 AI_ANALYZE 的区别：翻译是结构确定的 1-to-1 变换，不需要 prompt 工程，适合本地离线模型与专用翻译 API 分离管理。

#### 关联任务类型

`TRANSLATE_TEXT`：输入纯文本 / SRT 字幕，输出目标语言文本 / SRT。

**典型流水线位置：**

```
MERGE_TRANSCRIPTION ──→ TRANSLATE_TEXT（zh → en）──→ AI_ANALYZE（英文摘要）
                    └──→ AI_ANALYZE（中文摘要，原文）
```

#### payload 设计

```json
{
  "text": "这里是原始文本……",     // 或
  "srt_path": "/data/sub.srt",   // 传 SRT 文件路径（保留时间轴格式）
  "source_lang": "zh",           // auto | zh | en | ja | ko | ...
  "target_lang": "en",           // 目标语言
  "style": "subtitle"            // subtitle（精简，适合字幕）| formal | casual
}
```

#### 后端清单与评分规则

**本地后端：**

| 后端标识 | 基础分 | 加速器 | 说明 |
|---------|-------|--------|------|
| `nllb-gpu` | 82–90 | LocalGPU | Meta NLLB-200，支持 200 种语言，离线翻译最优 |
| `nllb-cpu` | 45–58 | LocalCPU | NLLB-200 CPU 推理，速度慢但完全离线 |
| `opus-mt` | 55–65 | LocalCPU | Helsinki-NLP Opus-MT，轻量，特定语言对精度好 |
| `ollama-translate` | 60–72 | LocalGPU | 通过 LLM（如 qwen2.5）进行指令式翻译，灵活但非专用 |

**云端后端：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `deepl-api` | 78–85 | DeepL，公认最高质量，中欧语言对优秀，按字符计费 |
| `google-translate-api` | 72–80 | Google Cloud Translation，200+ 语言，性价比高 |
| `deepseek-translate-api` | 70–78 | DeepSeek 指令翻译，中英日韩质量优秀，与 AI_ANALYZE 共享 key |
| `openai-translate-api` | 68–76 | GPT-4o 指令翻译，质量高，成本较高 |
| `tencent-translate-api` | 62–70 | 腾讯云翻译，中文出色，适合 CN 节点 |
| `baidu-translate-api` | 58–66 | 百度翻译，中文场景常用，API 简单 |
| `aliyun-translate-api` | 58–66 | 阿里云机器翻译 |

#### 特殊调度考量

- **SRT 格式保持**：传入 `srt_path` 时，执行节点逐条翻译字幕块并保留时间轴，输出 SRT 文件（不合并成段落）。
- **语言对专用 vs 通用**：`opus-mt` 在特定语言对（如 zh→en、en→de）精度很好且极轻量；`nllb` 在多语言场景更通用；LLM 类后端在"意译/润色"场景有优势。
- **cloud_preference** 修正规则与其他能力相同（见 §2.5）。
- **成本控制**：翻译通常按字符计费；`max_cost_per_task_usd` 建议在 `cloud_policy.translate` 中设置上限。

---

### 3.9 SUPER_RESOLUTION — 视频超分辨率

超分能力将低分辨率视频/图像放大为高分辨率（2×/4×），适合老视频修复、压制素材提升画质。GPU 密集型，任务耗时最长，HARD 亲和到具备独立 GPU 的节点。

#### 关联任务类型

`UPSCALE_VIDEO`：输入视频文件（或图片序列），输出超分后视频，通常接 `TRANSCODE` 压制。

**典型流水线：**

```
DOWNLOAD_VIDEO → UPSCALE_VIDEO（2×/4× SR）→ TRANSCODE（H.265 压制）
```

#### payload 设计

```json
{
  "input_path": "/data/videos/input_720p.mp4",
  "scale": 2,                  // 2 | 4（放大倍数）
  "model": "realesrgan-x4plus",// realesrgan-x4plus | realesrgan-anime | edsr-x4 | hat-l
  "output_format": "mp4",      // mp4 | image_sequence（输出图片序列再由 TRANSCODE 合并）
  "tile_size": 512             // 显存不足时分块处理（0 = 不分块）
}
```

#### 后端清单与评分规则

**本地后端（仅本地，无云端）：**

| 后端标识 | 基础分 | VRAM 需求 | 说明 |
|---------|-------|----------|------|
| `realesrgan-gpu` | 88–95 | ≥4GB | Real-ESRGAN，通用超分 SOTA，视频/图像均优，支持动漫模型 |
| `hat-gpu` | 85–92 | ≥8GB | HAT（Hybrid Attention Transformer），精度最高，速度较慢 |
| `edsr-gpu` | 80–88 | ≥4GB | EDSR，老牌经典，稳定，无动漫专用 |
| `swinir-gpu` | 78–85 | ≥6GB | SwinIR，Transformer 架构，综合性能好 |
| `realesrgan-ncnn` | 55–68 | 无需 CUDA | ncnn 版 Real-ESRGAN，支持 CPU/集显，速度显著低于 GPU 版 |

> **超分无云端后端**：超分涉及完整视频传输（数据量极大），云端 API 无法经济可行地支持视频超分；`cloud_policy.super_resolution.cloud_allowed` 固定 `false`。

#### 特殊调度考量

- **VRAM 管理**：4K 输入 × 4× 放大的单帧占用可达数 GB；务必在 `worker.yaml` 中配置 `tile_size`（如 512）；调度前检查 VRAM 可用量。
- **耗时预估**：1080p 视频 2× 超分约 1–3fps（RTX 3060），60 分钟视频约需数小时；建议仅在专用 GPU 节点运行，`max_workers: 1`。
- **与 TRANSCODE 串联**：超分输出通常是无损图片序列或高码率中间文件，需要接 `TRANSCODE`（H.265/AV1 压制）才能存储；两者应 HARD 亲和到同一节点（均需文件本地可用）。
- **动漫模式**：`model: "realesrgan-anime"` 对动漫/插画效果远优于通用模型，由 pipeline 发布时的 `content_type: anime` 参数触发。

---

### 3.10 SPEECH_ENHANCE — 语音增强

语音增强能力在 TRANSCRIBE / DIARIZE_AUDIO 之前对原始音频进行降噪、去混响、语音分离处理，可显著提升后续转录与说话人分类的准确率。对录音环境差（机械噪音、风噪、多人嘈杂）的素材效果尤为明显。

**流水线位置：**

```
EXTRACT_AUDIO → ENHANCE_AUDIO → DETECT_LANGUAGE
                             ├→ DIARIZE_AUDIO
                             └→ SPLIT_AUDIO → TRANSCRIBE_CHUNK × N
```

> `ENHANCE_AUDIO` 为可选步骤；对录音质量良好的素材可跳过（`cache_policy: CHECK_EXISTING` 保证同一文件不重复处理）。

#### payload 设计

```json
{
  "audio_path": "/data/audio/abc.wav",
  "mode": "denoise",          // denoise（降噪）| dereveb（去混响）| separate（人声分离）| full
  "output_format": "wav",     // wav | flac
  "strength": 0.8             // 增强强度（0.0–1.0），过高可能导致语音失真
}
```

#### 后端清单与评分规则

**本地后端（无云端，音频体积大且隐私敏感）：**

| 后端标识 | 基础分 | 加速器 | 说明 |
|---------|-------|--------|------|
| `deepfilternet-gpu` | 85–92 | LocalGPU | DeepFilterNet 3，实时噪声抑制 SOTA，低延迟，效果出色 |
| `deepfilternet-cpu` | 58–68 | LocalCPU | 同上 CPU 版，速度约 GPU 的 1/10，仍可接受 |
| `speechbrain-enhance` | 78–85 | LocalGPU | SpeechBrain MetricGAN+，语音质量提升指标优异 |
| `demucs-gpu` | 80–88 | LocalGPU（≥6GB）| Facebook Demucs v4，人声/乐器分离最强，适合 `mode: separate` |
| `demucs-cpu` | 42–55 | LocalCPU | Demucs CPU 版，速度极慢，仅用于无 GPU 兜底 |
| `rnnoise` | 48–58 | LocalCPU | Mozilla RNNoise，极轻量（C 库），适合 NAS / 树莓派节点 |
| `noisereduce` | 38–48 | LocalCPU | 基于谱减法，零模型依赖，效果一般但速度极快 |

#### 特殊调度考量

- **节点亲和 HARD**：输入文件在本地，不跨节点传输大型音频文件。
- **模式选择**：`mode: separate`（人声分离）需要 Demucs，仅在有 GPU 的节点可高质量完成；`mode: denoise` 多种后端均可。
- **quality_mode 默认 `balanced`**：增强质量影响后续转录，但不是最终输出，允许轻度权衡。
- **cloud_policy 默认 `cloud_allowed: false`**：音频可能含敏感内容。

---

### 3.11 SPEAKER_DIARIZE — 声纹分类与说话人识别

说话人日志（Speaker Diarization）回答"谁在什么时候说话"的问题，输出带时间戳的说话人分段列表。结合 `TRANSCRIBE_CHUNK`，可生成带说话人标签的完整转录稿，适合访谈、播客、会议录音等多人对话场景。

#### 流水线集成

```
EXTRACT_AUDIO
    └→ [ENHANCE_AUDIO]（可选，强烈建议嘈杂场景开启）
    └→ DIARIZE_AUDIO ──────────────────────────────────────────────┐
                                                                   ↓
                                         编排器读取 speaker_segments，
                                         按说话人轮次创建 TRANSCRIBE_CHUNK 任务
                                         （payload 携带 speaker_id 和时间范围）
                                                                   ↓
                                               MERGE_TRANSCRIPTION
                                               （输出含 speaker 标签的转录稿）
                                                                   ↓
                                                              AI_ANALYZE
```

> `DIARIZE_AUDIO` 与 `DETECT_LANGUAGE` 可并行执行（均依赖 `ENHANCE_AUDIO`/`EXTRACT_AUDIO` 完成）。

#### payload 设计

**DIARIZE_AUDIO payload：**
```json
{
  "audio_path": "/data/audio/abc.wav",
  "min_speakers": 1,          // 已知说话人数下限（0 = 自动推断）
  "max_speakers": 10,         // 已知说话人数上限（0 = 自动推断）
  "min_segment_sec": 0.5      // 小于此时长的片段合并到相邻说话人
}
```

**DIARIZE_AUDIO result：**
```json
{
  "num_speakers": 2,
  "segments": [
    { "speaker": "SPEAKER_00", "start_sec": 0.0,  "end_sec": 12.4 },
    { "speaker": "SPEAKER_01", "start_sec": 12.4, "end_sec": 28.7 },
    { "speaker": "SPEAKER_00", "start_sec": 28.7, "end_sec": 45.2 }
  ]
}
```

编排器将 `segments` 展平为 `TRANSCRIBE_CHUNK` 任务列表，每个 chunk 的 payload 包含 `speaker_id` 字段，`MERGE_TRANSCRIPTION` 据此生成带说话人标注的最终文稿。

#### 后端清单与评分规则

**本地后端：**

| 后端标识 | 基础分 | 加速器 | 说明 |
|---------|-------|--------|------|
| `pyannote-gpu` | 88–95 | LocalGPU | pyannote/speaker-diarization-3.1，当前 SOTA；需 HuggingFace Token（免费申请）|
| `pyannote-cpu` | 48–58 | LocalCPU | 同上 CPU 版，速度慢，准确率与 GPU 版一致 |
| `speechbrain-diarize` | 75–83 | LocalGPU | SpeechBrain ECAPA-TDNN 说话人嵌入 + 聚类，无需 HF Token，效果接近 pyannote |
| `nemo-diarize-gpu` | 78–86 | LocalGPU（≥8GB）| NVIDIA NeMo ConfD，多说话人场景精度优异，模型较大 |
| `resemblyzer-cpu` | 52–62 | LocalCPU | Resemblyzer（GE2E），轻量嵌入 + 谱聚类，CPU 可用 |

**云端后端：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `assemblyai-diarize-api` | 68–76 | AssemblyAI，说话人分离效果业内领先，按分钟计费 |
| `google-speech-diarize-api` | 62–70 | Google Cloud Speech-to-Text v2，支持说话人分离，同步转录 |
| `azure-speech-diarize-api` | 60–68 | Azure Speech，说话人识别 + 转录一体 |

> **隐私说明**：音频可能包含敏感对话，`cloud_policy.speaker_diarize.cloud_allowed` 默认 `false`。

#### 特殊调度考量

- **与 TRANSCRIBE 的关系**：`DIARIZE_AUDIO` 替代 `SPLIT_AUDIO` 决定切分点，两者互斥（同一 pipeline 选其一）；选用 diarization 的 pipeline 模板应将 `SPLIT_AUDIO` 依赖移除。
- **说话人数量**：`min/max_speakers` 设为 0 时模型自动推断；已知说话人数可显著提升准确率。
- **SpeechBrain 框架**：`speechbrain-diarize` 和 `resemblyzer-cpu` 均依赖 SpeechBrain 或其生态，启动时自动探测 `python -c "import speechbrain"` 是否可用。
- **quality_mode 默认 `quality`**：说话人分类结果直接影响转录标签准确性，不接受因节点负载降级。

---

### 3.12 EMOTION_DETECT — 语音情感识别

情感识别对音频段（通常 1–30 秒）进行分类，输出情感标签（中性/喜悦/悲伤/愤怒/惊讶/恐惧等）及置信度。适合内容情绪分析、访谈情绪时间轴、直播片段剪辑标注等场景。

**流水线位置：**

```
DIARIZE_AUDIO → speaker segments → DETECT_EMOTION × N（每个说话人轮次）
                                         ↓
                              带情感标签的说话人分段
                                         ↓
                                    AI_ANALYZE（融合情感 + 转录）
```

也可在无 diarization 的场景下，直接对 `TRANSCRIBE_CHUNK` 的对应音频段运行情感识别。

#### payload 设计

```json
{
  "audio_path": "/data/audio/abc.wav",
  "start_sec": 0.0,
  "end_sec": 12.4,
  "speaker_id": "SPEAKER_00",  // 可选，用于关联说话人标签
  "emotion_set": "basic"       // basic（6 类基础）| extended（含厌恶/蔑视等）
}
```

#### 后端清单与评分规则

**本地后端（仅本地，情感音频数据高度敏感）：**

| 后端标识 | 基础分 | 加速器 | 说明 |
|---------|-------|--------|------|
| `speechbrain-emotion` | 78–85 | LocalGPU | SpeechBrain wav2vec2-based emotion，在 IEMOCAP/RAVDESS 上效果优异 |
| `speechbrain-emotion-cpu` | 48–58 | LocalCPU | 同上 CPU 版 |
| `wav2vec2-emotion` | 75–83 | LocalGPU | HuggingFace `ehcalabres/wav2vec2-lg-xlsr-en-speech-emotion-recognition`，英文最强 |
| `ser-acrnn` | 55–65 | LocalCPU | 轻量 CNN-RNN 架构，无大模型依赖，适合边缘节点 |

> **云端后端：无。** 情感音频属于极敏感个人数据，不提供云端路由；`cloud_policy.emotion_detect.cloud_allowed` 固定 `false`。

#### 特殊调度考量

- **多语言局限**：当前主流情感识别模型在中文上效果远弱于英文；`speechbrain-emotion` 有中文微调版，但准确率仍有差距，发布时应在 UI 提示"中文情感识别准确率有限"。
- **短音频最佳**：情感识别在 3–15 秒片段上效果最好；过长的段落（>30s）建议先通过 `DIARIZE_AUDIO` 细化切分。
- **SpeechBrain 框架复用**：`speechbrain-emotion` 与 `speechbrain-diarize` 共享框架安装，若节点已部署 SpeechBrain，两者均可启用。
- **quality_mode 默认 `balanced`**：情感识别为辅助标注，轻度负载权衡可接受。

---

### 3.13 SUBTITLE_SYNC — 字幕自动对齐

字幕自动对齐将用户已有的外挂字幕文件（从字幕网站下载、或基于其他版本制作的 SRT/ASS）重新对齐到当前视频的音轨时间轴，解决"字幕滞后/超前"或"帧率不匹配"等问题，无需重新转录。

#### 关联任务类型

`SYNC_SUBTITLE`：输入视频/音频 + 原始字幕文件 → 输出时间轴校正后的字幕文件。

**典型流水线：**

```
用户上传 .srt → SYNC_SUBTITLE（对齐到已有视频）→ FORMAT_SUBTITLE（断行优化）→ BURN_SUBTITLE
```

也可在 TRANSCRIBE 流程之后使用，对机器转录结果进行二次校正：

```
MERGE_TRANSCRIPTION → FORMAT_SUBTITLE → [用户手动修正] → SYNC_SUBTITLE → BURN_SUBTITLE
```

#### payload 设计

```json
{
  "video_path": "/data/videos/abc.mp4",   // 视频文件（用于提取参考音轨）
  "subtitle_path": "/data/subs/abc.srt",  // 待对齐的字幕文件
  "output_path": "/data/subs/abc_synced.srt",
  "method": "audio",                      // audio（音频波形对齐）| scene（场景切换对齐）
  "max_offset_sec": 60                    // 最大允许偏移量（超出则任务失败，人工介入）
}
```

#### 后端清单与评分规则

**本地后端（仅本地，无云端）：**

| 后端标识 | 基础分 | 加速器 | 说明 |
|---------|-------|--------|------|
| `alass` | 80–88 | LocalCPU | Rust 实现，极快（秒级完成），支持音频波形 + 场景切换双模式 |
| `ffsubsync` | 72–80 | LocalCPU | Python，基于 VAD + DTW 音频对齐，准确率高，稍慢 |
| `subsync` | 65–75 | LocalCPU | ML 辅助对齐，支持多语言，依赖较重 |

> 字幕对齐不涉及模型推理，仅需 CPU；三个工具均轻量，任何节点均可执行，`hardware_modifier` 影响极小（±2 以内）。

#### 特殊调度考量

- **SOFT 亲和**：对齐工具仅需读取视频音轨和字幕文本，可通过 `/cluster/files` 跨节点拉取，不强制 HARD 亲和。
- **字幕格式支持**：输入支持 SRT/ASS/SSA/VTT；输出默认与输入格式一致，可通过 `output_format` 参数覆盖。
- **对齐失败处理**：若偏移量超过 `max_offset_sec`，任务标记 `PERMANENT` 错误并附带诊断信息，由用户手动修正。
- **quality_mode 默认 `balanced`**。

---

### 3.14 AUDIO_FINGERPRINT — 音频声学指纹

音频指纹对音频内容生成紧凑的声学特征码，用于版权检测（识别 BGM/歌曲）、重复素材合并、内容去重等场景。生成速度极快（实时速率数十倍），几乎无计算成本。

#### 关联任务类型

`FINGERPRINT_AUDIO`：输入音频文件 → 输出指纹哈希（+ 可选 AcoustID 在线查询结果）。

配合 `FINGERPRINT_VIDEO`（FFMPEG 任务，对视频抽帧计算 pHash），可构成完整的内容去重流水线：

```
DOWNLOAD_VIDEO ──→ FINGERPRINT_VIDEO（视频感知哈希）
               └─→ EXTRACT_AUDIO → FINGERPRINT_AUDIO（音频声学指纹）
                                         ↓
                              与本地素材库对比 → 发现重复 → 提示用户
```

#### payload 设计

```json
{
  "audio_path": "/data/audio/abc.wav",
  "duration_sec": 120,          // 仅对前 N 秒生成指纹（0 = 全曲）
  "lookup_acoustid": false,     // 是否向 AcoustID API 查询曲目信息
  "acoustid_api_key": ""        // AcoustID 免费 API Key（lookup 时必填）
}
```

**result：**
```json
{
  "fingerprint": "AQAAkEm...",   // chromaprint Base64 指纹
  "duration_sec": 120.4,
  "acoustid_result": {           // 仅 lookup_acoustid=true 时存在
    "score": 0.97,
    "title": "Song Title",
    "artist": "Artist Name",
    "recordings": [{ "id": "..." }]
  }
}
```

#### 后端清单与评分规则

**本地后端：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `chromaprint` | 85–92 | fpcalc（chromaprint CLI），C 实现，极快，业界标准 |
| `chromaprint-py` | 75–82 | `pyacoustid` Python 绑定，功能同上，无需二进制依赖 |
| `essentia-fingerprint` | 68–76 | Essentia 库，支持更多指纹算法（MFP / HPCPFingerprint） |

**云端后端（仅查询，指纹本地生成）：**

| 后端标识 | 基础分 | 说明 |
|---------|-------|------|
| `acoustid-api` | 60–70 | AcoustID 开放数据库，免费，仅用于曲目元数据查询，指纹计算仍在本地 |

> **注意**：`cloud_policy.audio_fingerprint.cloud_allowed` 默认 `true`，因为 AcoustID 查询仅上传指纹哈希（不含音频原文），隐私风险极低。

#### 特殊调度考量

- **几乎无计算量**：生成 3 分钟音频的指纹通常 <100ms，`hardware_modifier` 可忽略；调度器优先选最空闲节点（`quality_mode: "speed"`）。
- **去重逻辑**：指纹生成是计算任务，跨素材的相似度比对由上层应用逻辑完成（不在任务系统内，避免任务间耦合）。
- **`FINGERPRINT_VIDEO` 补充**：视频感知哈希（pHash）由 FFMPEG 低帧率抽帧后 CPU 计算，与 `FINGERPRINT_AUDIO` 互补（视频哈希对重新编码鲁棒，音频指纹对音轨替换鲁棒）。

---

### 3.15 分数计算汇总

本节汇总统一调度打分公式（详见 **§2.7**）中各修正项与 §3 各能力的关系：

```
effective_score = max(10,
    base_score            // §3 各小节的后端基础分
    + hardware_modifier   // §2.7.1：gpu_score / cpu_score 偏差 × 系数
    + cloud_modifier      // §2.5：cloud_preference 修正（±30 或过滤）
    + language_modifier   // §3.2（TRANSCRIBE）/ §3.6（OCR）语言感知修正
    + load_modifier       // §2.7.2：-load_ratio × max_penalty × quality_weight
)
```

**各修正项适用范围速查：**

| 修正项 | 适用能力 | 最大幅度 |
|-------|---------|---------|
| `hardware_modifier` | FFMPEG · TRANSCRIBE · TTS · AI_ANALYZE（本地 GPU 后端）· OCR · OBJECT_DETECT · SUPER_RESOLUTION · TRANSLATE（本地）· SPEECH_ENHANCE · SPEAKER_DIARIZE · EMOTION_DETECT | ±12（GPU）/ ±10（CPU）|
| `hardware_modifier` = 0 | AI_ANALYZE（CloudAPI 后端）· 所有 CloudAPI 后端 | 0（云端不受本地硬件影响）|
| `hardware_modifier`（极小）| SUBTITLE_SYNC · AUDIO_FINGERPRINT | ±2（CPU 仅轻微影响）|
| `cloud_modifier` | 所有含云端后端的能力（SPEAKER_DIARIZE / AUDIO_FINGERPRINT 需显式开启）| ±30 / 完全过滤 |
| `language_modifier` | TRANSCRIBE · OCR | ±20 |
| `load_modifier` | 全部能力 | 0 ~ −20（由 quality_mode 控制）|

**cloud_preference 修正速查（见 §2.5）：**

| cloud_preference | 本地后端 | 云端后端 |
|-----------------|---------|---------|
| `auto` | ±0 | ±0 |
| `prefer_local` | ±0 | −30 |
| `prefer_cloud` | ±0 | +30 |
| `local_only` | ±0 | 排除 |
| `cloud_only` | 排除 | ±0 |

完整综合示例见 **§2.7.3**。

---

## 4. 去中心化策略

### 4.1 节点发现

采用**双轨发现机制**，按优先级：

```
优先级 1：mDNS/DNS-SD（局域网自动发现）
  └── 每个节点在局域网广播服务名 _fredica._tcp.local.
  └── 节点间通过 mDNS 自动发现彼此，无需手动配置

优先级 2：配置静态种子节点（跨网段 / 互联网）
  └── 在 app_config 表中配置 peer_seeds: "192.168.1.10:7631,remote.server.com:7631"
  └── 从种子节点拉取完整节点列表，形成 gossip 网络
```

每个节点维护一份**节点注册表**（存在本地 SQLite），记录已知的所有节点及其最后活跃时间。

### 4.2 任务存储与同步

**核心设计决策：** 采用"共享任务队列 + 乐观锁认领"模式，而非消息推送。

```
方案对比：

❌ 中心化消息队列（如 RabbitMQ）
   → 引入外部依赖，单点故障风险

❌ 纯 P2P 广播（每次都全网广播任务）
   → 网络开销大，状态同步复杂

✅ 分布式 SQLite（任务 DB 跨节点同步）+ 乐观锁认领
   → 借助 CR-SQLite 或自定义 CRDT 同步，每节点有完整副本
   → 认领时通过 CAS（Compare-And-Swap）保证唯一认领
```

**任务同步流程：**

```mermaid
sequenceDiagram
    participant A as 节点 A（创建者）
    participant DB_A as A 的本地 DB
    participant DB_B as B 的本地 DB
    participant B as 节点 B（工作节点）

    A->>DB_A: INSERT task (status=pending)
    A->>B: 推送变更 (HTTP POST /sync/tasks)
    B->>DB_B: 合并变更
    B->>DB_B: UPDATE task SET status='claimed', claimed_by='B'\nWHERE id=? AND status='pending'
    Note over B: 若 UPDATE 影响 0 行 → 被抢先认领，放弃
    B->>DB_B: UPDATE task SET status='running'
    B->>A: 推送状态变更
    B-->>B: 执行任务...
    B->>DB_B: UPDATE task SET status='completed', result=?
    B->>A: 推送完成
```

### 4.3 一致性保证

| 一致性目标 | 实现方式 |
|-----------|---------|
| 任务唯一认领 | SQLite `UPDATE ... WHERE status='pending'` 的原子性保证单节点唯一；跨节点用逻辑时钟向量解冲突 |
| 死亡节点任务回收 | 任何节点检测到 heartbeat_at 超时（>2 分钟）时，将任务状态重置为 `pending` |
| 最终一致性 | 节点重连后主动拉取全量 diff，合并本地状态 |
| 重复执行保护 | 任务结果的写入是幂等操作（覆盖写），已完成任务不会被再次认领 |

### 4.4 任务去重（Task Deduplication）

**问题场景：**
- 用户将同一个 BV 号的视频重复加入素材库（触发两条完全相同的流水线）
- 网络重试导致 `PipelineCreateRoute` 被调用两次
- 节点崩溃后重启，重复创建了已存在的任务

**方案：幂等键（Idempotency Key）**

每个任务在创建时计算 `idempotency_key`，SQLite `UNIQUE` 约束保证同一键只能插入一次：

```kotlin
fun computeIdempotencyKey(type: TaskType, materialId: String, payload: JsonObject): String {
    val canonical = "$type|$materialId|${payload.toCanonicalString()}"
    return SHA256(canonical).toHex()
}

// 插入时：
db.execute("""
    INSERT OR IGNORE INTO task (id, idempotency_key, ...)
    VALUES (?, ?, ...)
""", newId, key, ...)
// INSERT OR IGNORE：若 key 已存在，静默忽略（不报错，不覆盖）
```

**各任务类型的幂等键组成：**

| 任务类型 | 幂等键构成 |
|---------|----------|
| `DOWNLOAD_VIDEO` | `type + material_id + source_url` |
| `EXTRACT_AUDIO` | `type + material_id` |
| `SPLIT_AUDIO` | `type + material_id + chunk_duration_sec` |
| `TRANSCRIBE_CHUNK` | `type + material_id + chunk_index + model_name` |
| `MERGE_TRANSCRIPTION` | `type + pipeline_id` |
| `AI_ANALYZE` | `type + material_id + prompt_hash` |

**流水线级别去重：**

创建流水线前，先检查是否已有活跃流水线处理同一素材：

```sql
SELECT id, status FROM pipeline_instance
WHERE material_id = ? AND template = ?
  AND status NOT IN ('completed', 'failed', 'cancelled')
LIMIT 1
```

- 若存在 → 返回已有流水线 ID（幂等响应），不重复创建
- 若不存在 → 正常创建

---

## 5. 数据模型

### 5.1 新增数据表

```sql
-- 流水线实例
CREATE TABLE IF NOT EXISTS pipeline_instance (
    id            TEXT PRIMARY KEY,
    material_id   TEXT NOT NULL,
    template      TEXT NOT NULL,           -- 模板名，如 "bilibili_full_process"
    status        TEXT NOT NULL DEFAULT 'pending',
    total_tasks   INTEGER NOT NULL DEFAULT 0,
    done_tasks    INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    completed_at  INTEGER
);

-- 任务队列
CREATE TABLE IF NOT EXISTS task (
    id               TEXT PRIMARY KEY,
    type             TEXT NOT NULL,          -- TaskType enum
    pipeline_id      TEXT,                   -- FK → pipeline_instance.id
    material_id      TEXT,                   -- FK → material_video.id
    status           TEXT NOT NULL DEFAULT 'pending',
    priority         INTEGER NOT NULL DEFAULT 5,
    depends_on       TEXT NOT NULL DEFAULT '[]',  -- JSON array of task IDs
    payload          TEXT NOT NULL DEFAULT '{}',  -- JSON，类型相关入参
    result           TEXT,                        -- JSON，成功后写入
    error            TEXT,
    cache_policy         TEXT NOT NULL DEFAULT 'NONE',  -- NONE|CHECK_EXISTING|PREFER_ORIGINAL
    cloud_preference     TEXT NOT NULL DEFAULT 'auto', -- auto|prefer_local|prefer_cloud|local_only|cloud_only
    publisher_id         TEXT,                         -- 发布者令牌 ID（auth.mode=token 时必填）
    idempotency_key      TEXT UNIQUE,                  -- 任务去重键（§4.4）
    error_type           TEXT,                         -- TRANSIENT|RESOURCE|PERMANENT|RATE_LIMITED
    excluded_nodes       TEXT NOT NULL DEFAULT '[]',   -- JSON array，Resource 错误时追加节点 ID
    retry_count          INTEGER NOT NULL DEFAULT 0,
    max_retries          INTEGER NOT NULL DEFAULT 3,
    retry_after          INTEGER,                      -- 最早可重试的 unix 时间戳（RateLimited 用）
    created_by           TEXT NOT NULL,
    claimed_by           TEXT,
    original_claimed_by  TEXT,                        -- 首次认领节点，reclaim 后保留
    file_node_id         TEXT,
    node_affinity        TEXT,                        -- "HARD:<id>" | "SOFT:<id>" | NULL
    result_acked_at      INTEGER,                     -- 发起节点确认收到结果的时间
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL,
    claimed_at           INTEGER,
    started_at           INTEGER,
    completed_at         INTEGER,
    heartbeat_at         INTEGER,
    stale_at             INTEGER,                     -- 进入 stale 状态的时间
    reclaimed_at         INTEGER                      -- 被集群回收的时间
);

-- 节点注册表
CREATE TABLE IF NOT EXISTS worker_node (
    id            TEXT PRIMARY KEY,
    hostname      TEXT NOT NULL,
    capabilities  TEXT NOT NULL DEFAULT '[]',   -- JSON array of NodeCapability
    status        TEXT NOT NULL DEFAULT 'online',
    max_workers   INTEGER NOT NULL DEFAULT 2,
    last_seen_at  INTEGER NOT NULL,
    address       TEXT                           -- http://host:port（用于直连）
);

-- 任务与节点的日志记录
CREATE TABLE IF NOT EXISTS task_event (
    id            TEXT PRIMARY KEY,
    task_id       TEXT NOT NULL,
    node_id       TEXT NOT NULL,
    event_type    TEXT NOT NULL,   -- created|claimed|started|completed|failed|reclaimed
    message       TEXT,
    occurred_at   INTEGER NOT NULL
);
```

### 5.2 任务状态机

```
  创建任务
     │
     ▼
 ┌─────────┐   工作节点轮询 + 依赖/亲和检查    ┌──────────┐
 │ pending │ ─────────────────────────────► │ claimed  │ 短暂过渡，确认占有后立即进入 running
 └─────────┘                                └──────────┘
     ▲                                           │
     │ retry_count < max_retries                 ▼
     │                                      ┌─────────┐
     │                                      │ running │ ◄── 每 30s 更新 heartbeat_at
     │                                      └─────────┘
     │                                     /     │     \
     │                          heartbeat       │      执行出错
     │                          断联 >stale     │          │
     │                          阈值（3 min）   │          ▼
     │                               │          │      ┌────────┐
     │                               ▼    成功完成     │ failed │──► retry_count≥max → 终态
     │                          ┌───────┐    │         └────────┘
     │                          │ stale │    ▼              │
     │                          └───────┘  ┌───────────┐   │ retry_count < max
     │                         /        \  │ completed │   │
     │             原节点恢复          超过  └───────────┘   │
     │             heartbeat到达    reclaim                  │
     │                 │          阈值（15 min）             │
     │                 ▼               │                     │
     │             running（继续）      ▼                     │
     │                          ┌──────────┐                 │
     │                          │reclaimed │ ◄── 原节点被告知 │
     │                          └──────────┘  任务已被抢占   │
     └─────────────────────────────────────────────────────┘
                           重新入队（pending），缓存策略决定是否回到原节点

 注：任何状态均可被手动取消 → cancelled（终态）
 注：条件路由判断为"不需要执行"时 → skipped（终态，见 §2.6 条件路由）
```

**状态说明：**

| 状态 | 含义 | 其他节点可认领？ |
|------|------|----------------|
| `pending` | 等待认领，依赖已满足 | 是 |
| `claimed` | 被某节点锁定，即将开始执行 | 否 |
| `running` | 执行中，heartbeat 活跃 | 否 |
| `stale` | heartbeat 中断，原节点仍"拥有"任务，等待恢复 | **否**（宽限期内） |
| `reclaimed` | 宽限期已过，任务被集群回收，原节点收到通知 | — （瞬态，立即→pending）|
| `completed` | 执行成功，结果已写入 | 否（终态）|
| `failed` | 重试耗尽，永久失败 | 否（终态）|
| `cancelled` | 手动取消或流水线取消 | 否（终态）|
| `skipped` | 条件路由判断此任务不必执行（主动跳过，非错误）| 否（终态）|

> **`skipped` vs `cancelled`**：`cancelled` 表示用户/系统中止了原本应该执行的任务；`skipped` 表示根据前置任务的结果，编排器主动判断此任务不需要运行（预期行为，不触发重试，不计入失败率）。流水线中所有任务终态（`completed` / `failed` / `cancelled` / `skipped`）时，pipeline 视为结束。

---

## 6. 节点通信协议

### 6.1 API 端点（每个节点暴露）

```
节点健康与发现：
  GET  /cluster/info       返回本节点信息（id, capabilities, status）
  GET  /cluster/peers      返回已知的节点列表

任务同步：
  GET  /cluster/tasks/diff?since={timestamp}  拉取指定时间后的任务变更
  POST /cluster/tasks/sync                    推送本节点产生的任务变更（批量）
  POST /cluster/tasks/verify                  执行节点重启后，向发起节点核对任务归属与状态
                                              请求：{ task_ids: [...], verifier_node_id: "..." }
                                              响应：{ tasks: [{ id, status, should_resume, reason }] }

心跳：
  POST /cluster/heartbeat                     节点存活广播（每 30s 由各节点发送）
                                              响应中携带 task_status_feedback（见 §7.3）

文件服务：
  GET  /files/serve?path={encoded_path}       向其他节点提供本地文件访问（见 §2.4）
```

所有集群 API 均使用与现有 API 相同的 Bearer Token 认证。集群内部节点使用共享的集群密钥（`cluster_secret`），与用户 Token 隔离。

### 6.2 通信模式

```
平时（节点在线）：
  ┌──────┐   每 30s POST /cluster/heartbeat   ┌──────┐
  │节点 A│ ─────────────────────────────────► │节点 B│
  │      │ ◄───────────────────────────────── │      │
  └──────┘       200 OK（含 B 的状态摘要）    └──────┘

任务产生时（Push 优先）：
  创建方立即向所有已知在线节点推送 /cluster/tasks/sync

任务同步恢复（Reconnect Pull）：
  节点重连后，从所有 peer 拉取 /cluster/tasks/diff?since={last_seen}
  合并到本地 DB（以 updated_at 较大者为准）
```

### 6.3 工作节点轮询模型

任务认领时引入**分数门槛机制**：若本节点的分数低于集群中其他在线节点的最高分数，则等待一个退避窗口后再尝试认领，让高分节点优先消费任务。

```kotlin
// Worker 节点内部行为（伪代码）
coroutine WorkerLoop {
    while (true) {
        if (node.activeTaskCount < node.maxWorkers) {
            val task = db.claimNextTask(node)
            if (task != null) {
                launch { executeTask(task) }
                continue
            }
        }
        delay(POLL_INTERVAL)   // 默认 5s，可配置
    }
}

// 原子认领：亲和过滤 + 依赖检查 + 分数门槛 + 乐观锁
fun claimNextTask(node: WorkerNode): Task? {
    return db.transaction {
        val task = db.query("""
            SELECT t.*
            FROM task t
            WHERE t.status = 'pending'
              -- 1. 所有前置任务已完成
              AND NOT EXISTS (
                  SELECT 1 FROM task dep
                  WHERE dep.id IN (SELECT value FROM json_each(t.depends_on))
                    AND dep.status != 'completed'
              )
              -- 2. 本节点具备该任务所需的能力函数
              AND t.type IN (${node.supportedTaskTypes()})
              -- 3. 节点亲和约束：HARD 约束只允许指定节点认领
              AND (
                  t.node_affinity IS NULL
                  OR t.node_affinity LIKE 'SOFT:%'
                  OR t.node_affinity = 'HARD:' || ?   -- ? = node.id
              )
              -- 4. 分数门槛：SOFT/无亲和任务，若有更高分节点在线则礼让
              AND NOT EXISTS (
                  SELECT 1 FROM worker_node w
                  WHERE w.id != ? AND w.status = 'online'
                    AND w.last_seen_at > ?
                    AND w.capability_score_for(t.type) > ?
                    -- HARD 亲和任务不受此限制（只有指定节点能认领）
                    AND (t.node_affinity IS NULL OR t.node_affinity LIKE 'SOFT:%')
              )
            ORDER BY t.priority DESC, t.created_at ASC
            LIMIT 1
        """, node.id, node.id, now() - HEARTBEAT_TIMEOUT, node.scoreFor(task?.type))

        if (task != null) {
            val rows = db.execute("""
                UPDATE task SET status = 'claimed', claimed_by = ?, claimed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'pending'
            """, node.id, now(), now(), task.id)
            if (rows == 1) task else null  // rows=0 → 被其他节点抢先，放弃本轮
        } else null
    }
}

// 任务执行完成后更新 heartbeat（每 30s）
coroutine HeartbeatLoop(taskId: String) {
    while (taskIsRunning(taskId)) {
        db.execute("UPDATE task SET heartbeat_at = ? WHERE id = ?", now(), taskId)
        delay(30_000)
    }
}
```

**分数门槛的实际效果：**

```
场景：TRANSCRIBE 任务，集群中有 A(score=95)、B(score=72)、C(score=38)

A 空闲时：B 和 C 检测到 A 在线且分数更高 → 均不抢先认领 → A 认领
A 满载时：B 检测到 A 满载（activeTask >= maxWorkers）→ 门槛放开 → B 认领
A、B 均满时：C 的门槛解除 → C 认领（可能走云端 API 兜底）
```

---

## 7. 容错、重试与结果持久化

家庭环境的核心特点是：**网络不稳定、服务随时可能重启、设备随时可能关机**。本节从四个维度应对这一现实。

---

### 7.1 分级超时机制（Staged Heartbeat Timeout）

**问题：** 家用 Wi-Fi 下偶发的 30s 断网不应导致一个运行了 2 小时的转录任务被强制重置。

**设计：两个可配置阈值，而非一刀切：**

```
heartbeat_stale_after   （默认 3 min，可配置）
heartbeat_reclaim_after （默认 15 min，可配置；数据中心可设为 5 min）
```

```
running
  │
  │ heartbeat 断联 > stale_after（3 min）
  ▼
stale                     ← 其他节点此时不可认领，原节点仍"拥有"任务
  │                 \
  │ 原节点恢复        \  断联 > reclaim_after（15 min）
  │ heartbeat 到达    \
  ▼                   ▼
running（恢复）      reclaimed → pending（其他节点可认领）
                       └─ retry_count++，并记录 reclaim 事件
```

**防脑裂（同时回收竞争）：**

多个在线节点可能同时检测到同一个 `stale` 任务超时。使用乐观锁：

```sql
UPDATE task
SET status = 'reclaimed', reclaimed_at = ?, updated_at = ?
WHERE id = ? AND status = 'stale' AND claimed_by = <dead_node>
```

只有一个节点能 UPDATE 成功（影响行数 = 1），其他节点检测到 0 行则放弃，避免重复处理。

---

### 7.2 任务可缓存性与恢复策略（Cache Policy）

不同任务类型在崩溃后的恢复成本差异巨大：

| 缓存策略 | 含义 | 适用任务 |
|----------|------|---------|
| `CHECK_EXISTING` | 重试前先检查输出文件/结果是否已存在，存在则跳过重新执行 | `DOWNLOAD_VIDEO`、`EXTRACT_AUDIO`、`TRANSCODE`、`MERGE_TRANSCRIPTION` 等 ffmpeg 确定性操作 |
| `PREFER_ORIGINAL` | 优先将任务重新分配给 `original_claimed_by` 节点，因为中间产物（如切分好的音频块）仍在该节点磁盘上 | `SPLIT_AUDIO`（块文件在原节点） |
| `NONE` | 无缓存，必须完整重新执行 | `TRANSCRIBE_CHUNK`（模型推理）、`AI_ANALYZE`（LLM 输出） |

**`CHECK_EXISTING` 的执行逻辑：**

```
执行器在开始执行前，检查 task.payload 中声明的输出路径是否存在且完整（大小 > 0，可选校验 checksum）
  → 存在：直接写入 result，跳过实际执行，标记 completed
  → 不存在：正常执行
```

**`PREFER_ORIGINAL` 的调度逻辑：**

```
reclaim 后，调度器将 task.node_affinity 临时设为 SOFT:<original_claimed_by>
  → 若原节点在 prefer_window（默认 30 min）内重新上线 → 原节点优先认领
  → 若超出 prefer_window → 退化为普通 SOFT 或无亲和，任意节点认领并重建前置产物
```

**`TRANSCRIBE_CHUNK` 的特殊设计——`initial_prompt`：**

语音转录中，相邻块之间使用上一块末尾的文本作为 `initial_prompt`，可显著减少跨块边界的识别错误和幻觉（hallucination）。这也是将长音频**切分为短块**的重要收益之一。

- 每个 `TRANSCRIBE_CHUNK` 任务的 `payload` 包含 `initial_prompt` 字段（可为 null）
- 当前一个 chunk 完成时，由 `MERGE_TRANSCRIPTION` 的前置逻辑将其末尾文本写入下一 chunk 的 payload
- 因此 `TRANSCRIBE_CHUNK` 之间存在**软顺序依赖**：在质量优先模式下，每个 chunk 等待前一个完成后再开始；在速度优先模式下，全部并行（无 initial_prompt）

```
质量优先（顺序执行）：
  CHUNK_1 → CHUNK_2（用 CHUNK_1 末尾文本作 init_prompt）→ CHUNK_3 → ...
  优点：转录边界质量高，缺点：无法并行

速度优先（并行执行）：
  CHUNK_1 ─┐
  CHUNK_2  ├─► MERGE_TRANSCRIPTION
  CHUNK_3 ─┘
  优点：多节点并行，缺点：块边界可能有少量错误
```

模式由流水线创建时的 `pipeline.quality_mode` 参数控制，默认"速度优先"。

流水线创建请求的完整可选参数：

```json
{
  "template": "bilibili_full_process",
  "material_id": "...",
  "cloud_preference": "auto",
  "quality_mode": "speed",
  "storage_preference": "any",
  "overrides": {
    "TRANSCRIBE_CHUNK": { "cloud_preference": "cloud_only" }
  }
}
```

| 参数 | 可选值 | 说明 |
|------|--------|------|
| `cloud_preference` | `auto` / `prefer_local` / `prefer_cloud` / `local_only` / `cloud_only` | 云端 API 偏好（见 §2.5） |
| `language` | `auto`（默认）/ `zh` / `en` / `ja` / `ko` / `zh-yue` / … | 音频语言；`auto` 时自动插入 `DETECT_LANGUAGE` 任务（见 §3.2）；已知语言时直接写入各 chunk payload，调度器据此做语言感知打分 |
| `quality_mode` | `speed`（默认）/ `quality` | 转录块是否顺序执行以使用 initial_prompt（见 §7.2）|
| `storage_preference` | `any`（默认）/ `nas` | `nas` 时，下载任务优先调度到 `is_storage_node=true` 的节点（+20 分），避免文件跨节点传输 |

---

### 7.3 被抢占节点通知（Zombie Worker Notification）

**问题：** 原节点的 heartbeat 因网络闪断而中断，任务被 reclaim 并分配给新节点。随后原节点网络恢复，仍在继续执行该任务——此时系统出现两个节点同时处理同一任务的"僵尸"情况。

**解决方案：心跳响应携带任务状态反馈**

原节点恢复后，会向集群发送 heartbeat。任意节点收到后，将当前任务状态附在响应中：

```
原节点 POST /cluster/heartbeat  { node_id, running_task_ids: ["task_abc"] }
            ↓
接收节点响应：{
  ok: true,
  task_status_feedback: [
    { task_id: "task_abc", status: "reclaimed", reclaimed_at: 1234567890 }
  ]
}
```

原节点收到 `reclaimed` 反馈后：
1. **立即中止**该任务的执行（interrupt 推理进程）
2. **清理中间产物**（可选，由 `cache_policy` 决定是否保留）
3. 在本地 DB 将该任务标记为 `reclaimed`，不再重试

**若原节点已完成任务但不知道自己被 reclaim 了：**

原节点尝试写入 `status='completed'` 时：

```sql
UPDATE task SET status='completed', result=?
WHERE id=? AND status='running' AND claimed_by=<self>
-- 若此时 DB 中该任务已被 reclaimed（claimed_by 已变更），则影响 0 行
```

写入失败（0 行）→ 原节点检测到被抢占，丢弃本次结果（或尝试通过 task_event 上报结果，供新执行节点参考）。

---

### 7.4 结果持久化与发起节点恢复（Result Durability）

**问题：** 发起节点（`created_by`）在任务执行期间可能崩溃或关机。执行节点完成任务后，发起节点不在线，结果无法立即同步。

**设计：执行节点本地结果缓存 + 双向确认**

```
执行节点（B）完成任务后：
  1. 将 result 写入本地 DB（status=completed）
  2. 尝试向发起节点（A）推送：POST A:7631/cluster/tasks/sync
       → A 在线：A 接收，写入本地 DB，响应 { acked: ["task_id"] }
                 B 收到 ack 后，将 task.result_acked_at 设为当前时间
       → A 离线：推送失败，B 将任务加入"待同步队列"，每 5 min 重试
  3. 若 result_acked_at 为 NULL，B 永远保留该结果（不清理）
  4. 发起节点 A 重新上线时：
       - 通过 /cluster/tasks/diff?since=<last_seen> 拉取所有变更
       - 对每个 completed 任务，向执行节点拉取输出文件（若有）
       - 写入本地 DB 后，发送 ack，执行节点设置 result_acked_at
```

**结果文件的处理：**

对于 `TRANSCRIBE_CHUNK`、`MERGE_TRANSCRIPTION` 等产生文件输出的任务：
- 执行节点在 `result` JSON 中写入 `{ output_path: "/data/results/task_abc.json" }`
- 该路径通过节点间文件服务（§2.4）向发起节点暴露
- 发起节点 ack 前，执行节点不会清理 `/data/results/` 下的结果文件
- 可设置最长保留时间（默认 7 天），超时后无论是否 ack 均清理，防止磁盘耗尽

**`result_acked_at` 的作用：**

| `result_acked_at` | 含义 |
|-------------------|------|
| `NULL` | 发起节点尚未确认，执行节点保留结果文件 |
| 有值 | 发起节点已确认，执行节点可安全清理结果文件缓存 |

**双向同步的实现方式：**

这不需要专门的"双向"连接——仍然基于现有的 HTTP Pull + Push 机制：
- 执行节点每次 heartbeat 顺带推送未 ack 的结果
- 发起节点重连后主动拉取 diff，并对每条 completed 记录发送 ack
- 两者均为无状态 HTTP，不需要持久连接

---

### 7.5 节点重启恢复序列（Startup Recovery Protocol）

节点崩溃后重启，本地 DB 的状态可能已与集群实际状态脱节。不能盲目重跑（任务可能已被 reclaim），也不能直接丢弃（任务可能仍属于自己）。启动时必须先与远端核对，再决定行动。

#### 通用启动序列

```
节点启动
  │
  ├─ 1. 加载本地 DB，读取 node_id、last_shutdown_type
  ├─ 2. 向集群广播上线（POST /cluster/heartbeat）
  ├─ 3. 执行"启动恢复扫描"（角色分支，见下）
  └─ 4. 进入正常工作循环（worker loop + sync loop）
```

#### 执行节点的启动恢复——"先问后做"

执行节点重启后，本地 DB 中可能有 `claimed_by = self`、状态为 `running / claimed / stale` 的任务。这些是崩溃时正在处理的任务。

**核对流程（Verify Before Resume）：**

```
对本地每个 claimed_by=self 的活跃任务，按 created_by 分组：

  ─── 同一发起节点的任务，一次批量 verify ──────────────────────
  POST <originator>:7631/cluster/tasks/verify
  { task_ids: ["t1","t2",...], verifier_node_id: self.id }

  ─── 发起节点的响应 ────────────────────────────────────────────
  {
    tasks: [
      { id:"t1", status:"running",   claimed_by:self,  should_resume:true  },
      { id:"t2", status:"reclaimed", claimed_by:other, should_resume:false },
      { id:"t3", status:"cancelled",                   should_resume:false },
      { id:"t4", status:"completed",                   should_resume:false }
    ]
  }

  ─── 执行节点根据 should_resume 决策 ───────────────────────────
  should_resume: true
    → CHECK_EXISTING：检查输出文件是否已存在，存在则直接 completed，否则重新执行
    → PREFER_ORIGINAL：检查中间产物（如分块文件），从断点处恢复
    → NONE：从头重新执行（无法续跑）

  should_resume: false
    → 清理本地中间产物（可选，按 cache_policy 决定是否保留）
    → 更新本地 DB（sync 发起节点的最新状态）
    → 该任务不再重试
```

**发起节点不可达时的处理：**

```
发起节点离线
  │
  ├─ 进入"等待发起节点"状态
  │    最长等待：originator_wait_timeout（默认 30 min）
  │    期间：每 2 min 重试 verify；UI 显示"等待发起节点恢复"
  │
  ├─ 等待期间发起节点上线 → 正常执行 verify 流程
  │
  └─ 超时（30 min）仍未响应
       → 视为"孤儿任务"，按 stale → reclaim 逻辑处理（retry_count 不增加）
       → 其他节点可重新认领（无需等待原执行节点）
```

> **30 min 等待**的意义：家用环境下，发起节点（桌面）可能只是在睡眠或重启，给它足够的时间恢复而不必立即放弃任务。

#### 发起节点的启动恢复——角色分支

**① 桌面应用（composeApp）——用户在场，由用户决策**

App 重启时，扫描本地 DB 中 `created_by = self` 的活跃任务（`pending / claimed / running / stale`），根据上次退出类型决定行为：

```
last_shutdown_type = CLEAN（正常退出，App 退出时主动写入）
  → 任务可能仍在其他节点运行，无需干预，静默同步 DB 即可

last_shutdown_type = CRASH（异常退出，启动时发现标志未写入）
  → 弹出恢复对话框：

  ┌──────────────────────────────────────────────────┐
  │  上次退出时有未完成的任务                           │
  │                                                   │
  │  ● 正在其他节点处理中：3 个（转录 2、分析 1）        │
  │  ● 等待开始（pending）：2 个                        │
  │                                                   │
  │  [继续全部]  [仅取消 pending]  [全部取消]           │
  └──────────────────────────────────────────────────┘

  继续全部     → 重新与集群同步，不干预执行中的任务，等待结果推送
  仅取消 pending → 将 pending 任务广播 cancelled；执行中任务不受影响
  全部取消     → 广播 cancelled 给所有活跃任务；执行节点收到后中止处理
```

退出时的 `last_shutdown_type` 标志写入 `app_config` 表（key = `last_shutdown_type`），App 正常退出的最后一步写入 `CLEAN`，启动时读取后重置为 `PENDING_CHECK`，恢复扫描完成后置为 `CLEAN`。

**② 纯服务端（server 模块）——无用户，自动恢复**

Server 不主动发起任务，因此重启后不存在"发起节点恢复"问题，只需执行执行节点的恢复流程（verify before resume）即可。

若未来 server 需要支持任务发起（如定时批量任务），采用**自动续跑策略**：直接同步 DB，不弹对话框，pending 任务保持原状等待认领。

#### verify 请求的发起节点处理逻辑

发起节点收到 `/cluster/tasks/verify` 请求后，按如下规则为每个 task_id 生成响应：

| 发起节点 DB 中的任务状态 | claimed_by | 响应 should_resume |
|------------------------|------------|-------------------|
| `running` 或 `stale` | = verifier | `true`（任务仍属于你，继续） |
| `running` 或 `stale` | ≠ verifier | `false`（已被其他节点认领） |
| `reclaimed` | 任意 | `false`（已被回收） |
| `completed` | 任意 | `false`（已完成，可能由其他节点完成） |
| `cancelled` | 任意 | `false`（已取消） |
| `pending` | 任意 | `false`（认领已失效，重新竞争） |

同时，发起节点将 verifier 发来的本地状态（"我还活着，我有这些任务"）作为一次隐式 diff 合并，更新自己的 DB——相当于执行节点的"反向 sync"。

---

### 7.6 错误分类与差异化重试

统一的 `max_retries` 无法应对性质截然不同的失败。错误必须先分类，再决定重试策略。

**错误类型定义：**

```kotlin
sealed class TaskError {
    /** 瞬态错误：稍后重试即可，原节点优先 */
    data class Transient(val message: String, val retryAfterSec: Int = 30) : TaskError()

    /** 资源错误：原节点无力处理，必须换节点重试 */
    data class Resource(val message: String, val hint: ResourceHint) : TaskError()

    /** 永久错误：重试无意义，直接失败 */
    data class Permanent(val message: String) : TaskError()

    /** API 限流：按服务商指定的时间退避 */
    data class RateLimited(val provider: String, val retryAfterSec: Int) : TaskError()
}

enum class ResourceHint {
    OUT_OF_MEMORY,  // 显存/内存不足 → 换节点
    DISK_FULL,      // 磁盘空间不足 → 换节点或等待清理
    GPU_BUSY,       // GPU 被其他进程占用 → 稍后或换节点
}
```

**错误分类映射表：**

| 实际错误 | 类型 | 重试行为 |
|---------|------|---------|
| 网络连接超时 / HTTP 5xx | `Transient` | 30s 后重试，原节点优先 |
| faster-whisper CUDA OOM | `Resource(OUT_OF_MEMORY)` | **原节点加入 `excluded_nodes`**，换节点重试 |
| 磁盘写入失败（No space） | `Resource(DISK_FULL)` | 原节点加入 `excluded_nodes`，换节点重试 |
| 输入文件不存在 / 格式损坏 | `Permanent` | 立即失败，`retry_count` 置为 `max_retries` |
| API 400 / 401 / 422 | `Permanent` | 立即失败（参数错误或密钥无效，重试无意义） |
| API 429 Rate Limited | `RateLimited` | 读取 `Retry-After` 响应头，写入 `task.retry_after`，到期后重试 |
| 进程被 SIGKILL（OOM Killer） | `Resource(OUT_OF_MEMORY)` | 同 CUDA OOM |
| heartbeat 超时（节点崩溃） | 走 §7.1 的 stale/reclaim 流程 | — |

**`excluded_nodes` 的调度集成：**

```sql
-- worker 认领时额外过滤被排除的节点
AND ? NOT IN (SELECT value FROM json_each(t.excluded_nodes))
-- ? = node.id
```

`Resource` 错误发生时，执行节点在写入 `error_type` 的同时，将自身 node_id 追加到 `excluded_nodes`，并通过 sync 广播给其他节点。

**`RateLimited` 的调度集成：**

```sql
-- 仅当 retry_after 已过期或为 NULL 时才可认领
AND (t.retry_after IS NULL OR t.retry_after <= unixepoch())
```

---

### 7.7 磁盘空间管理

视频处理产生的文件量大、生命周期各异，若不主动管理，节点磁盘会在无感知中耗尽。

**数据目录结构与生命周期：**

```
.data/
├── downloads/{material_id}/video.mp4        ← DOWNLOAD_VIDEO 输出
├── audio/{material_id}/audio.wav            ← EXTRACT_AUDIO 输出
├── chunks/{material_id}/chunk_{n}.wav       ← SPLIT_AUDIO 输出
├── results/{task_id}/transcript.json        ← TRANSCRIBE/MERGE 输出
└── cache/images/                            ← 图片代理缓存（已有）
```

**清理触发时机：**

| 目录 | 清理时机 |
|------|---------|
| `chunks/` | 该 material 所有 TRANSCRIBE_CHUNK 任务均 completed 后 |
| `audio/` | SPLIT_AUDIO completed 后（chunks 为工作集），或流水线 completed |
| `downloads/` | 流水线 completed 且 `cache_policy != PREFER_ORIGINAL`，或取消时 |
| `results/` | `result_acked_at` 有值后（或超过 `results_retain_days`，默认 7 天） |

**认领前磁盘检查：**

执行节点在认领 `DOWNLOAD_VIDEO` 或 `FFMPEG` 类任务前，先验证可用磁盘空间：

```kotlin
fun canClaimTask(task: Task): Boolean {
    val required = task.payload.estimatedOutputBytes ?: DEFAULT_ESTIMATE
    val available = File(config.dataDir).usableSpace
    val minFree = config.diskMinFreeGb.toBytes()
    return (available - required) > minFree
}
```

- `estimatedOutputBytes`：由流水线创建时从素材元数据（视频时长 × 码率估算）填入 payload
- `diskMinFreeGb`：在 `worker.yaml` 中配置，建议至少 20 GB
- 磁盘不足时：不认领该任务，等待其他节点或本地清理后再试（不计入 retry_count）

**磁盘预警：**

节点在 heartbeat 中上报当前可用磁盘容量，集群可在"处理中心"UI 中展示各节点的磁盘使用状态，避免用户毫无预兆地遭遇下载失败。

---

### 7.8 外部 API 集群级限流

多个节点共享同一 API Key 时，若同时并发调用，极易触发服务商的限流（429）。

**节点级限流（第一道防线，简单有效）：**

在 `worker.yaml` 中为每个 CloudAPI provider 配置 `max_concurrent`：

```yaml
capabilities:
  ai_analyze:
    providers:
      - backend: "deepseek-api"
        max_concurrent: 2   # 本节点同时最多 2 个并发调用
```

节点认领时检查本地活跃的同类 API 任务数：

```kotlin
val active = db.count("SELECT COUNT(*) FROM task WHERE status='running' AND claimed_by=? AND payload LIKE ?",
    node.id, "%deepseek-api%")
if (active >= provider.maxConcurrent) return null  // 本节点已满，不认领
```

**集群级协调（第二道防线，通过 heartbeat 实现）：**

每个节点在 heartbeat 中上报当前各 provider 的活跃调用数：

```json
{
  "node_id": "node-A",
  "active_api_calls": { "deepseek-api": 2, "openai-api": 0 }
}
```

其他节点汇总后计算集群总并发，若超过 `cluster_max_concurrent`（在 `worker.yaml` 中配置），本节点延迟认领 AI_ANALYZE 任务：

```yaml
cluster:
  api_rate_limits:
    deepseek-api:
      cluster_max_concurrent: 5   # 整个集群合计不超过 5 个并发
```

**429 退避处理：**

收到 429 后，任务进入 `RateLimited` 错误流程（§7.6）：读取 `Retry-After` 响应头写入 `task.retry_after`，调度器在此时间前不认领该任务。同时，收到 429 的节点将该 provider 的本地 `max_concurrent` 临时减半（自适应退避），恢复后逐步还原。

> **共享 API Key 的建议配置**：若 N 个节点共享同一 Key，每个节点的 `max_concurrent` 建议设为 `总配额 / N`，`cluster_max_concurrent` 设为总配额，两道防线共同保障。

---

## 8. 与现有架构的集成

### 8.1 桌面应用（composeApp）

- 应用启动时自动启动节点，注册到集群
- 正常退出时向 `app_config` 写入 `last_shutdown_type=CLEAN`；崩溃后重启检测到缺失则触发恢复对话框（见 §7.5）
- WebUI 中新增"处理中心"页面，展示全局任务队列状态
- 用户在素材库点击"分析"时，创建任务而非直接执行
- App 作为**发起节点**时角色明确：负责创建任务、接收结果、响应 verify 请求；同时也可作为执行节点处理自己创建的任务

### 8.2 后端 API（shared/jvmMain）

- 新增 `TaskRoute`：创建、查询、取消任务
- 新增 `ClusterRoute`：节点发现、心跳、任务同步
- 现有 `pipeline_status` 字段通过任务完成回调自动更新

### 8.3 独立工作节点（server 模块）

```
当前：server 模块仅返回 "Ktor"（实验性）
目标：server 模块成为完整的无头工作节点

能力：
  - 启动 Ktor 服务，暴露 /cluster/* API
  - 注册到已知 peer，加入集群
  - 按配置能力认领并执行任务
  - 无 UI 依赖，适合部署在 NAS/服务器/Docker
```

**角色定位——纯执行节点，不主动发起任务：**

server 模块的职责是**执行**，不是**发起**。任务始终由用户通过桌面 App（或未来的 Web 端）创建，server 只认领和处理。这一边界带来以下简化：

- 重启恢复无需用户交互，直接执行"verify before resume"（§7.5）
- 不需要维护 `last_shutdown_type` 标志
- 崩溃后自动重启（由系统守护进程 / Docker restart policy 负责），重启后自动恢复

> 若将来需要 server 支持定时触发（如每日自动处理新导入素材），应通过独立的定时任务模块实现，创建任务时仍以该 server 节点的 `node_id` 作为 `created_by`，且不弹恢复对话框，直接自动续跑。

### 8.4 AI 能力接入层

节点的 AI 能力（转录、分析）通过**统一接入层**对任务执行器屏蔽后端差异：

```
任务执行器
    │
    ├─ TRANSCRIBE 任务
    │       │
    │       ├── backend=faster-whisper  → 本机 HTTP (:7632, Python 进程)
    │       ├── backend=whisper.cpp     → 本机子进程调用
    │       └── backend=openai-whisper-api → 外部 HTTPS 请求
    │
    └─ AI_ANALYZE 任务
            │
            ├── backend=ollama          → 本机 HTTP (:11434)
            ├── backend=deepseek-api   → 外部 HTTPS 请求
            ├── backend=openai-api     → 外部 HTTPS 请求
            └── backend=openai-compatible → 外部 HTTPS 请求（可配置端点）
```

**关键原则：**
- Python 进程（`:7632`）和 Ollama（`:11434`）**不参与集群协议**，仅作为本机服务被 Ktor 驱动，保持轻量
- API Key 等凭证**存储在本节点的 `app_config` 中**，不随任务传播到其他节点
- 新增服务商只需实现一个接口适配器，任务系统无需修改

### 8.5 数据库策略

```
单节点场景（本地运行）：
  保持现有 SQLite（.data/db/）不变，任务表追加到同一 DB

多节点场景（分布式）：
  选项 A - 共享 SQLite（NAS 网络盘）：
    最简单，适合家庭局域网 NAS 场景
    ⚠️ 注意：SQLite WAL 模式在网络文件系统上存在风险
    → 仅适合低写入频率场景，写入前加分布式文件锁

  选项 B - 各自本地 SQLite + 增量同步：（推荐）
    每台机器有自己的 SQLite，通过 /cluster/tasks/sync API 同步
    → 离线可用，重连后自动合并，健壮性最好

  选项 C（远期）- CR-SQLite：
    基于 CRDT 的 SQLite 扩展，内置冲突解决
    → 最优雅，但引入外部 native 依赖
```

---

## 9. WebUI 设计（新增页面）

### 9.1 处理中心（/processing）

```
┌────────────────────────────────────────────────────────────┐
│  处理中心                                    集群: 3 节点在线 │
├────────────────────────────────────────────────────────────┤
│  待处理  12   进行中  3   已完成  847   失败  2             │
├──────────────────────────┬─────────────────────────────────┤
│  任务队列                │  集群节点                        │
│                          │                                  │
│  [▶ 下载] BV1xx... ●●●○ │  • 主机 (本机)  [online]  2/2   │
│  [⚙ 转写] BV2xx... ●●○○ │    CPU: 45%  下载 转写          │
│  [⏳ 待处理] BV3xx...    │                                  │
│  [✓ 完成] BV4xx...  灰   │  • NAS-Server  [online]  1/4   │
│  [✗ 失败] BV5xx...  红   │    CPU: 12%  下载               │
│                          │                                  │
│  [全部] [进行中] [失败]   │  • GPU-Box     [online]  1/1   │
│                          │    GPU: 87%  转写 分析           │
└──────────────────────────┴─────────────────────────────────┘
```

### 9.2 素材卡片状态联动

素材库卡片上新增处理进度 Badge，取自关联任务状态：

```
[未处理] → [下载中 ▶▶] → [转写中 ⚙] → [分析中 ✨] → [已完成 ✓]
```

---

## 10. 实施路径（分阶段）

> 每个 Phase 以"所有 TDD 测试绿 + 里程碑标准满足"为唯一交付门槛；**TDD 优先**——先写测试用例，再实现功能。
>
> **测试分层约定：**
> - *单元测试*：测试单一函数 / 类的纯逻辑，不依赖数据库或网络，运行时间 < 10 ms/用例
> - *集成测试*：涉及真实 SQLite DB 或模拟 HTTP 节点，验证组件间协作；可借助内存 DB 或 TestContainers 隔离
> - 每个 Phase 的里程碑要求**上一 Phase 的全部测试继续绿**（无回归）

---

### Phase 1：单节点异步任务队列（约 3 周）

**目标**：将现有"立即执行"模式改造为本地异步任务队列，不涉及多节点。单节点可完整运行基础流水线。

**数据模型**
- [ ] 创建 `task`、`pipeline`、`task_event` 数据表（含 `idempotency_key UNIQUE`、`error_type`、`cache_policy` 字段）
- [ ] 实现任务状态机（`pending → claimed → running → completed / failed`）（§5.2）

**任务执行引擎**
- [ ] 实现单节点 Worker 协程池（受 `max_workers` 限制），FIFO + 优先级调度
- [ ] 实现 Pipeline DAG 引擎：解析 `depends_on`，前置任务全部 `completed` 后触发下游
- [ ] 任务去重：`idempotency_key` 的 `INSERT OR IGNORE` 语义；pipeline 级幂等创建（§4.4）
- [ ] 素材导入改为创建 pipeline 而非同步执行

**支持的任务类型（本地基础版，无评分体系）**
- [ ] `DOWNLOAD_VIDEO`（单后端：`http-curl`）
- [ ] `EXTRACT_AUDIO` → `SPLIT_AUDIO`（调用本地 `ffmpeg`）
- [ ] `TRANSCRIBE_CHUNK` → `MERGE_TRANSCRIPTION`（调用本地 faster-whisper / whisper.cpp）
- [ ] `AI_ANALYZE`（调用本地 ollama 或云端 API 直连，无 cloud_preference 体系）

**API**
- [ ] `POST /api/v1/pipelines`（创建流水线）
- [ ] `GET /api/v1/pipelines/{id}`（查询状态）
- [ ] `GET /api/v1/tasks`（列表）
- [ ] `DELETE /api/v1/pipelines/{id}`（取消流水线）

**WebUI**
- [ ] 任务列表页（按 pipeline 分组，轮询刷新，展示状态 + 进度）

**TDD 要点**

*单元测试：*
- [ ] DAG 依赖解析：循环检测（应抛出错误）· 多入度节点（所有前置 `completed` 才触发）· 孤立节点（无依赖立即可认领）
- [ ] 状态机迁移：合法路径全覆盖（`pending→claimed→running→completed`）；非法跳转（如 `pending→completed`）被拒绝并报错
- [ ] 幂等键去重：相同 key 二次 INSERT 返回原记录 ID，pipeline 级：同素材 + 模板有活跃流水线时不重复创建
- [ ] Worker 并行上限：`max_workers=2` 时第 3 个任务等待，前两个之一完成后立即认领
- [ ] 优先级调度：`priority=8` 的任务先于 `priority=3` 的被认领（队列中同时存在时）

*集成测试：*
- [ ] Pipeline 状态联动：全部子任务 `completed` → pipeline `completed`；任一子任务达 `max_retries` → pipeline `failed`
- [ ] 取消传播：`DELETE /api/v1/pipelines/{id}` 后，所有 `pending` 任务进入 `cancelled`；正在运行的任务不受影响
- [ ] 端到端冒烟：本机完整跑通"下载 → 提取音频 → 切分 → 转录 → 合并 → AI 分析"全链路，最终所有任务状态均为 `completed`

**里程碑（Phase 1 Done）**

> 所有 TDD 测试绿；本机端到端跑通基础流水线；WebUI 任务列表正确展示进度与状态。

---

### Phase 2：能力系统与后端矩阵（约 3 周）

**目标**：将 Phase 1 的硬编码后端改造为可声明、可评分的能力系统；`worker.yaml` 驱动后端选择。

**能力系统核心**
- [ ] 实现 `CapabilityFunction` 枚举（全部能力：DOWNLOAD_*/FFMPEG/TRANSCRIBE/TTS/AI_ANALYZE/OCR/OBJECT_DETECT/TRANSLATE/SUPER_RESOLUTION）（§2.3）
- [ ] 实现 `Accelerator` 密封类（LocalGPU / LocalCPU / LocalNetwork / CloudAPI）
- [ ] 实现 `Capability` 评分体系：基础分 + 各修正因子叠加（见 §3 各小节）

**worker.yaml**
- [ ] 实现 `worker.yaml` 解析与字段校验（§2.4）
- [ ] 启动时自动探测：`nvidia-smi` / `ollama list` / `paddleocr` / `cosyvoice` / `yolo` / `realesrgan` 可用性
- [ ] 能力声明写入 `node_capability` 表，每次启动覆盖更新

**下载能力矩阵**（§3.1）
- [ ] `DOWNLOAD_BILIBILI`：bilibili-pyutil，Desktop 节点自动检测 `desktop_assets`，Server/NAS 节点需手动运行 fredica-pyutil 服务
- [ ] `DOWNLOAD_YOUTUBE`：youtube-ytdlp，CN 节点 + 代理检测，`has_proxy` 自动设置
- [ ] `DOWNLOAD_HTTP`：http-aria2c / http-ytdlp / http-curl，多连接数配置
- [ ] `DOWNLOAD_HLS`：hls-ffmpeg / hls-ytdlp
- [ ] 评分因子：带宽加成（±15/±8/−10）+ 存储亲和（`is_storage_node` + `storage_preference=nas` → +20）

**其他核心能力矩阵**
- [ ] FFMPEG：nvenc / vaapi / videotoolbox / cpu 后端评分；`DETECT_SCENES` 任务（pyscenedetect / ffmpeg-scene）（§3.4）
- [ ] TRANSCRIBE：faster-whisper / whisper.cpp / 各模型（large-v3/medium）评分（§3.2）
- [ ] AI_ANALYZE：ollama / 各基础评分（§3.3）

**新增任务类型**
- [ ] `GENERATE_THUMBNAIL`、`TRANSCODE`、`EXTRACT_FRAMES`、`DETECT_SCENES`（§2.1）

**TDD 要点**

*单元测试：*
- [ ] 能力过滤：任务所需 `CapabilityFunction` 节点未声明时，认领 SQL 返回空，不抢先认领
- [ ] `effective_score` 计算：各能力各后端的基础分 + `hardware_modifier` + `load_modifier` 叠加值与预期一致（§3.15）
- [ ] 硬件评分三级回退：`gpu_score_override` 非 null → 直接用；内置表命中 → 用表中分数；未命中 → VRAM 公式 `min(100, vram_gb × 7)`（三种场景各自模拟）
- [ ] 带宽加成边界值：`bandwidth_mbps` = 500（+15）/ 100（+8）/ 20（0）/ 10（−10）
- [ ] `is_storage_node=true` + `storage_preference=nas`：下载任务评分 +20
- [ ] `worker.yaml` 解析全覆盖：合法配置通过 / 缺必填字段启动报错 / 非法枚举值报错 / `enabled=false` 时不注册该能力

*集成测试：*
- [ ] 自动探测 Mock：`nvidia-smi` 可用时注册 `ffmpeg-nvenc`、`faster-whisper+GPU`；不可用时只注册 CPU 后端
- [ ] 多后端竞争：同一能力声明了高分和低分两个后端，无亲和无负载干扰时，调度器选高分后端
- [ ] Phase 1 端到端冒烟在 `worker.yaml` 驱动模式下继续通过

**里程碑（Phase 2 Done）**

> Phase 1 测试继续绿；`worker.yaml` 可驱动后端选择；评分体系单元测试全绿；本机可在不同后端间切换并验证分数差异。

---

### Phase 3：集群节点注册与分布式任务认领（约 3 周）

**目标**：多节点可见彼此、真正协同分担任务；基础死亡检测与任务回收就绪。

**节点发现**
- [ ] `worker_node` 数据表 + 本节点注册逻辑
- [ ] 心跳机制（30 s 间隔），`heartbeat_at` 更新，携带 `disk_free_gb` 上报
- [ ] mDNS/DNS-SD 局域网自动发现（`_fredica._tcp.local.`，`jmdns` 库）
- [ ] 静态 seed 节点支持（跨网段 / 公网）

**集群 API（每节点暴露）**（§6.1）
- [ ] `GET /cluster/info`（节点能力快照）
- [ ] `POST /cluster/heartbeat`（心跳 + 磁盘状态上报）
- [ ] `POST /cluster/tasks/sync`（增量任务同步）
- [ ] `GET /cluster/tasks/diff`（差异查询）
- [ ] `GET /cluster/files/{taskId}/{filename}`（跨节点文件服务）

**分布式认领**
- [ ] 乐观锁认领 CAS：`UPDATE ... WHERE status='pending' AND claimed_by IS NULL`
- [ ] Race condition 处理：认领失败直接放弃，进入下一候选任务
- [ ] 节点亲和约束：HARD（仅指定节点可认领）· SOFT（优先指定节点，满载后他节点可接）（§2.6）
- [ ] 跨节点文件拉取：执行节点通过 `/cluster/files` 获取发起节点的本地文件

**死亡检测与任务回收**
- [ ] 分级超时：`stale_after_sec`（3 min）→ `stale`，`reclaim_after_sec`（15 min）→ `pending` 回收
- [ ] 回收时原节点加入 `excluded_nodes`（RESOURCE 类错误场景）

**WebUI**
- [ ] 集群节点列表（在线 / 离线 / 忙碌状态，磁盘使用量）
- [ ] 处理中心页面（任务 → 归属节点，实时刷新）（§9.1）

**TDD 要点**

*单元测试：*
- [ ] 乐观锁 CAS：两个协程同时 `UPDATE ... WHERE status='pending'`，一个 `rowsAffected=1`，另一个 `rowsAffected=0` 立即放弃
- [ ] HARD 亲和：非指定节点认领时 SQL 过滤后任务不可见（认领结果为空）
- [ ] SOFT 亲和礼让：高分指定节点在线且未满载时，低分他节点不主动认领；指定节点满载后，他节点可认领
- [ ] 分级超时状态机：模拟 heartbeat 断联 → `stale`（3 min）→ `reclaimed`（15 min）→ retry_count +1，重新入队
- [ ] 脑裂保护：两个节点同时对同一 `stale` 任务执行回收 UPDATE，只有一个 `rowsAffected=1`，另一个感知 0 行后放弃

*集成测试（需两个节点或模拟进程）：*
- [ ] 跨节点文件服务：执行节点通过 `/cluster/files` 拉取发起节点文件，内容 byte-for-byte 一致；路径遍历（`../`）返回 403
- [ ] SOFT 亲和跨节点执行：GPU 节点 B 认领 SOFT 亲和到节点 A 的转录任务，通过文件服务拉取音频，正常完成并同步结果
- [ ] 节点重连同步：节点断线再上线，通过 `diff` API 拉取期间变更，本地 DB 与集群状态一致
- [ ] 宕机接管端到端：节点 A 在任务 `running` 时停止进程，节点 B 检测到 `reclaim`，接管任务并完成整条流水线

**里程碑（Phase 3 Done）**

> Phase 1–2 测试继续绿；两台真实设备可协同跑通完整流水线；一台宕机后另一台自动接管并完成；集群 TDD 测试全绿。

---

### Phase 4：云端对接、鉴权与容错强化（约 4 周）

**云端能力接入**
- [ ] `cloud_preference` 字段在 task / pipeline 中传播，认领前执行评分修正（§2.5）
- [ ] `cloud_policy` 从 `worker.yaml` 解析，执行双层优先级规则（§2.5）
- [ ] 云端 TRANSCRIBE 后端：`xunfei-iat-api` · `groq-whisper-api` · `openai-whisper-api`（§3.2）
- [ ] 云端 AI_ANALYZE 后端：`deepseek-api` · `openai-api` · `anthropic-api` · `openai-compatible`（§3.3）
- [ ] 云端 OCR 后端（`cloud_policy.ocr.cloud_allowed` 默认 `false`，需显式开启）：百度 · Google Vision · Azure（§3.6）

**鉴权**（§12）
- [ ] 集群内部：`X-Cluster-Secret` header 校验（所有 `/cluster/*` 端点）（§12.1）
- [ ] 发布者认证：`auth.mode: token` 实现（Bearer Token · SHA-256 存储 · `publisher_id` 绑定）（§12.2）
- [ ] 发布者权限粒度：`allow_cloud_transcribe` · `max_cloud_cost_per_day_usd` · `max_tasks_per_day`

**容错强化**
- [ ] 错误分类（`TRANSIENT` / `RESOURCE` / `PERMANENT` / `RATE_LIMITED`）与差异化重试（§7.6）
- [ ] `excluded_nodes` 机制：RESOURCE 错误后换节点重试（§7.6）
- [ ] `retry_after`：RATE_LIMITED 按服务商 `Retry-After` 退避（§7.6）
- [ ] Cache Policy 完整实现：`CHECK_EXISTING`（复用已有文件）· `PREFER_ORIGINAL`（优先原节点）（§7.2）
- [ ] 僵尸 Worker 通知：`POST /cluster/tasks/zombie-alert`，执行节点主动上报（§7.3）
- [ ] 结果持久化：`result_acked_at` 确认机制，执行节点保留结果直至发起节点确认（§7.4）
- [ ] 节点重启恢复序列："先问后做"（verify before resume）+ App 崩溃恢复对话框 + `last_shutdown_type`（§7.5）
- [ ] 磁盘预检：认领下载 / FFMPEG 任务前检查 `disk_free_gb`；中间产物分阶段清理（§7.7）
- [ ] 流水线取消传播：`/cluster/tasks/cancel` + 执行节点中断 + 中间产物清理（§2.6）

**外部 API 限流**（§7.8）
- [ ] 节点级 `max_concurrent` 控制：认领前检查本地活跃 API 调用数
- [ ] 集群级 `cluster_max_concurrent` 协调：heartbeat 汇总各节点调用数
- [ ] 429 自适应退避：临时减半本节点并发数，指数恢复

**TDD 要点**

*单元测试：*
- [ ] `cloud_preference` × `cloud_policy` 决策矩阵：5 种偏好 × `cloud_allowed=true/false` = 10 组合，预期行为严格对照 §2.5 表格（如 `cloud_only` + `cloud_allowed=false` → 本节点拒绝认领）
- [ ] `cloud_only` 任务无符合节点时保持 `pending` 不报错；符合节点加入集群后任务被认领
- [ ] Token 鉴权四类场景：有效（200）/ 已过期（401）/ 权限不足——超出云端费用上限（403）/ 伪造 Hash 不匹配（401）
- [ ] 错误分类路由：`TRANSIENT`（30 s 后重试原节点）· `RESOURCE`（原节点加入 `excluded_nodes`、调度到他节点）· `PERMANENT`（立即 `failed`，不重试）· `RATE_LIMITED`（写入 `retry_after`，到期前认领 SQL 过滤该任务）
- [ ] `excluded_nodes` 过滤：RESOURCE 错误后原节点 ID 写入列表，认领 SQL 中该节点不再出现
- [ ] `CHECK_EXISTING`：输出文件已存在时跳过执行直接 `completed`；不存在时正常执行
- [ ] `PREFER_ORIGINAL`：reclaim 后原节点在 30 min 内上线，SOFT 亲和优先让其拿回任务；超出 30 min 后亲和降级，他节点可认领

*集成测试：*
- [ ] 僵尸 Worker 止损：节点被 reclaim 后仍在执行，heartbeat 响应携带 `reclaimed` 反馈，执行节点收到后立即中止任务并清理中间产物
- [ ] 结果持久化：发起节点离线期间执行节点保留结果文件；重连后 ack 流程正确执行，`result_acked_at` 写入，执行节点可清理缓存
- [ ] 节点重启 CRASH 路径：`last_shutdown_type=CRASH` 重启时弹出恢复对话框；选"继续全部"后任务恢复正常执行；选"全部取消"后流水线进入 `cancelled`
- [ ] verify-before-resume：执行节点重启后对 `claimed_by=self` 任务发起 verify；`should_resume=false` 时不重跑，本地清理中间产物
- [ ] 磁盘预检：`disk_free_gb` 低于阈值时，节点拒绝认领下载 / FFMPEG 任务，任务保持 `pending`，`retry_count` 不增加
- [ ] 429 退避：收到 429 后同 provider 本地并发减半；`retry_after` 时间前任务不被认领；到期后自动恢复并正常完成任务

**里程碑（Phase 4 Done）**

> Phase 1–3 测试继续绿；云端 API 按 `cloud_preference` / `cloud_policy` 正确介入；Token 鉴权在公网可用；网络闪断、节点崩溃、OOM、429 各类异常下系统可自恢复；Phase 4 TDD 全绿。

---

### Phase 5：高级能力与可观测性（持续迭代）

**语言感知能力**
- [ ] `DETECT_LANGUAGE` 任务（前 30 s 采样 + TRANSCRIBE 能力）（§3.2 / §2.1）
- [ ] Pipeline 中 `DETECT_LANGUAGE` 与 `SPLIT_AUDIO` 并行触发，`TRANSCRIBE_CHUNK` 依赖两者
- [ ] TRANSCRIBE 评分注入 `language_modifier`（zh / en / ja / ko / auto × 各后端分值）（§3.2）

**TTS 能力**
- [ ] `GENERATE_TTS` 任务类型（输入文本 → 音频文件）
- [ ] 本地 TTS 后端：`edge-tts`（CPU）· `kokoro`（CPU）· `cosyvoice`（GPU）· `gpt-sovits`（GPU）
- [ ] 云端 TTS 后端：`xunfei-tts-api` · `openai-tts-api` · `elevenlabs-api` 等（§3.5）
- [ ] 声音克隆路由：`voice_clone: true` 仅路由到支持克隆的后端

**OCR 能力**（§3.6）
- [ ] `OCR_FRAME_BATCH` 批量识别（按 `batch_size=50` 拆分，多节点并行，SOFT 亲和）
- [ ] `MERGE_OCR_RESULTS` 合并去重（相邻重复帧合并，时间轴排序）
- [ ] 本地 OCR 后端：`paddleocr-gpu` · `paddleocr-cpu` · `rapidocr` · `surya` · `tesseract`
- [ ] OCR 语言修正规则（zh/en/ja/ko × 各后端分值）
- [ ] 双路 pipeline 模板（TRANSCRIBE + OCR → AI_ANALYZE 文本融合）

**目标检测能力**（§3.7）
- [ ] `DETECT_FRAME_BATCH` 批量推理（YOLO / RT-DETR，50 帧/批，SOFT 亲和）
- [ ] `MERGE_DETECT_RESULTS` 合并（时间轴排序，过滤低置信度短暂误检）
- [ ] 本地后端：`yolo-gpu`（YOLOv8n/m）· `yolo-onnx`（无 CUDA）
- [ ] 与 OCR 共享 `EXTRACT_FRAMES` 帧文件（`CHECK_EXISTING` 避免重复抽帧）

**翻译能力**（§3.8）
- [ ] `TRANSLATE_TEXT` 任务（支持纯文本 + SRT 字幕格式，保留时间轴）
- [ ] 本地后端：`nllb-gpu`（200 语言离线）· `opus-mt`（轻量特定语言对）· `ollama-translate`
- [ ] 云端后端：`deepl-api` · `google-translate-api` · `deepseek-translate-api`
- [ ] `cloud_policy.translate` 配置与成本上限

**超分辨率能力**（§3.9）
- [ ] `UPSCALE_VIDEO` 任务（2×/4× 放大，HARD 亲和，接 `TRANSCODE` 压制）
- [ ] 本地后端：`realesrgan-gpu`（通用 + 动漫模型）· `hat-gpu` · `realesrgan-ncnn`（无 CUDA）
- [ ] `tile_size` 分块处理（应对显存不足场景）
- [ ] `content_type: anime` 参数触发动漫专用模型路由

**语音增强能力**（§3.10）
- [ ] `ENHANCE_AUDIO` 任务（HARD 亲和，TRANSCRIBE / DIARIZE 前可选前置步骤）
- [ ] 本地后端：`deepfilternet-gpu`（GPU）· `deepfilternet-cpu`（CPU）· `speechbrain-enhance`（MetricGAN+）· `demucs-gpu`（人声分离）· `rnnoise`（轻量）
- [ ] `mode: separate` 触发 Demucs 人声分离，需 GPU
- [ ] 自动探测：`python -c "import deepfilter"` 注册 CPU 后端

**声纹分类与说话人识别能力**（§3.11）
- [ ] `DIARIZE_AUDIO` 任务：输出 speaker_segments，替代 `SPLIT_AUDIO` 决定切分点
- [ ] 编排器将 segments 展平为带 `speaker_id` 的 `TRANSCRIBE_CHUNK` 任务列表
- [ ] 本地后端：`pyannote-gpu`（SOTA，需 HF Token）· `speechbrain-diarize`（ECAPA-TDNN，无需 Token）· `nemo-diarize-gpu` · `resemblyzer-cpu`
- [ ] 云端后端（`cloud_allowed` 默认 false）：`assemblyai-diarize-api`
- [ ] `MERGE_TRANSCRIPTION` 输出含说话人标签的完整转录稿
- [ ] SpeechBrain 自动探测：`python -c "import speechbrain"`

**语音情感识别能力**（§3.12）
- [ ] `DETECT_EMOTION` 任务：对 speaker segments 或 TRANSCRIBE_CHUNK 对应的音频段运行
- [ ] 本地后端：`speechbrain-emotion`（GPU/CPU）· `wav2vec2-emotion` · `ser-acrnn`（轻量 CPU）
- [ ] 无云端后端（情感音频属极敏感数据）
- [ ] WebUI 提示中文情感识别准确率有限

**字幕能力**（§3.4 / §3.13 / §2.6）
- [ ] `EXTRACT_SUBTITLE`：从 MKV/MP4 提取内嵌字幕轨（FFMPEG `-map 0:s:0`）
- [ ] `FORMAT_SUBTITLE`：纯文本处理，断行优化 + SRT/ASS/VTT 格式互转（无能力依赖，本地执行）
- [ ] `BURN_SUBTITLE`：硬编码字幕进视频（FFMPEG `-vf subtitles/ass`，支持 ASS 字体/颜色）
- [ ] `DETECT_HARDCODED_SUBTITLE`（AI_ANALYZE 能力，Vision LLM / OCR 后备）：采样视频帧检测原有硬字幕及区域（§2.6）
- [ ] `BLUR_SUBTITLE_REGION`（FFMPEG 能力）：模糊视频中原字幕区域，区域坐标来自 `DETECT_HARDCODED_SUBTITLE` result（§2.6）
- [ ] 条件路由引擎：post-completion hook 读取 `DETECT_HARDCODED_SUBTITLE` result，按 `has_subtitle` + `audio_mode` 三路分支动态创建/跳过下游任务（§2.6）
- [ ] `skipped` 终态：编排器主动标记，不触发重试，不计入失败率（§5.2）
- [ ] `SYNC_SUBTITLE`（SUBTITLE_SYNC 能力）：外挂字幕自动对齐音轨
  - 本地后端：`alass`（Rust，极快）· `ffsubsync`（Python DTW）
  - 自动探测：PATH 检测 `alass` / `python -c "import ffsubsync"`

**音频指纹与视频去重**（§3.14）
- [ ] `FINGERPRINT_AUDIO`（AUDIO_FINGERPRINT 能力）：chromaprint 声学指纹 + 可选 AcoustID 查询
  - 默认开启（`audio_fingerprint.enabled: true`），轻量，fpcalc 自动探测
- [ ] `FINGERPRINT_VIDEO`（FFMPEG 任务）：低帧率抽帧 + pHash，与音频指纹互补
- [ ] WebUI：素材库重复检测页面（指纹相似度对比，标记候选重复项）

**可观测性**
- [ ] `task_event` 历史日志查看（WebUI 任务详情页）
- [ ] 集群指标仪表盘：处理吞吐、节点负载均衡率、磁盘使用趋势（§9.1）
- [ ] 优先级调度优化（用户手动触发 > 批量后台）
- [ ] `worker.yaml` 可视化配置 UI（能力开关、并行数、API Key 配置）

**TDD 要点**

*语言感知与调度打分：*
- [ ] `DETECT_LANGUAGE` 并行触发：`DETECT_LANGUAGE` 与 `SPLIT_AUDIO` 均 `depends_on EXTRACT_AUDIO`，两者同时进入 `pending`；`TRANSCRIBE_CHUNK` `depends_on` 两者，等待全部 `completed` 后才可认领
- [ ] `language_modifier` 端到端：`language=zh` 时中文专用 API（如讯飞）有效分高于 Whisper medium；`language=ja` 时讯飞有效分最低（−18 修正）
- [ ] 负载修正边界：`quality_mode=speed` + `max_penalty=20` + `load_ratio=1.0` → `load_modifier=-20`；`quality_mode=quality` → `load_modifier=0`

*批量推理：*
- [ ] 批次拆分正确性：N 帧按 `batch_size=50` 拆为 ⌈N/50⌉ 批，`MERGE_*` 任务 `depends_on` 包含全部批次 ID，不多不少
- [ ] `EXTRACT_FRAMES` 复用：同一视频同时启动 OCR 与 OBJECT_DETECT 流水线，`CHECK_EXISTING` 保证抽帧只执行一次（帧文件路径相同，第二次直接 `completed`）
- [ ] OCR 语言修正：`language=zh` 时 PaddleOCR 有效分 +15；`language=en` 时 Tesseract +10，PaddleOCR −5

*TTS 与翻译：*
- [ ] 声音克隆路由：`voice_clone=true` 时，不支持克隆的后端（`edge-tts` / `kokoro`）不出现在候选节点；`reference_audio` 缺失时任务立即 `PERMANENT` 失败
- [ ] TRANSLATE SRT 格式保持：SRT 输入时，输出时间轴行与原文件逐行对齐，行数相同，仅文本内容被翻译

*超分辨率：*
- [ ] `tile_size` 分块：Mock 推理 OOM 后 `tile_size=512` 分块成功完成；小图验证分块与不分块结果一致

*语音分析系列：*
- [ ] `DIARIZE_AUDIO` → `TRANSCRIBE_CHUNK` 展平：2 说话人 3 段输出 3 个 chunk 任务，各自 `payload.speaker_id` 对应正确；`MERGE_TRANSCRIPTION` 输出含 `[SPEAKER_00]` 标注的完整文稿
- [ ] `ENHANCE_AUDIO` → `DIARIZE_AUDIO` 串联：`DIARIZE_AUDIO` 的 `audio_path` 指向 `ENHANCE_AUDIO` 输出路径；两者 HARD 亲和确保在同一节点执行
- [ ] `DETECT_EMOTION` 短片段过滤：< 0.5 s 的说话人 segment 不创建 `DETECT_EMOTION` 任务
- [ ] `SYNC_SUBTITLE` 偏移超限：偏移量 > `max_offset_sec` 时任务标记 `PERMANENT` 错误，不触发重试

*字幕条件路由（关键路径）：*
- [ ] Case A（无硬字幕）：`has_subtitle=false` → 编排器仅创建 `BURN_SUBTITLE`，不创建 `BLUR_SUBTITLE_REGION`
- [ ] Case B（有字幕 + 原声）：`has_subtitle=true` + `audio_mode=original` → `BURN_SUBTITLE` 被标记 `skipped`，不触发重试；pipeline 最终进入 `completed`，不是 `partial_failed`
- [ ] Case C（有字幕 + 配音）：`has_subtitle=true` + `audio_mode=dubbed` → 创建 `BLUR_SUBTITLE_REGION`（region 来自 detection result），其完成后创建 `depends_on` 的 `BURN_SUBTITLE`
- [ ] Vision LLM 降级：`ai_analyze` 无 Vision 后端（仅文本模型）时，`DETECT_HARDCODED_SUBTITLE` 自动回退 OCR，`result.detection_method="ocr_fallback"`

*指纹：*
- [ ] `FINGERPRINT_AUDIO` 幂等性：同一音频文件连续两次运行，返回 byte-for-byte 相同的 chromaprint 指纹

**里程碑（Phase 5 基准门槛）**

> Phase 1–4 测试继续绿；条件路由三路分支（Case A/B/C）均有集成测试覆盖且全绿；语言感知、声纹分类、情感识别、字幕处理完整工作流端到端可跑通；可观测性 UI 可展示实时任务流向与节点负载。

---

## 11. 关键技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 任务存储 | 各节点本地 SQLite + HTTP 增量同步 | 无外部依赖，离线可用，SQLite 原子写入保证单节点认领唯一 |
| 节点发现 | mDNS（局域网）+ 静态 seed（跨网段） | 零配置覆盖最常见场景，保留手动配置兜底 |
| 通信协议 | HTTP/REST（复用现有 Ktor） | 无需新协议，防火墙友好，易调试 |
| 一致性模型 | 最终一致性（Eventual Consistency） | 任务系统允许短暂不一致，最终收敛即可 |
| 死亡检测 | 被动超时检测（任何节点均可触发） | 无需选主，去中心化，简单可靠 |
| AI 能力建模 | CloudAPI 与 LocalGPU/CPU 统一为 Accelerator 类型，分数量化优先级 | 家用环境插 API Key 即可用；有 GPU 则自动优先本地；两者可共存兜底 |
| FFMPEG/Whisper GPU 区分 | 后端名称显式编码硬件路径（ffmpeg-nvenc / faster-whisper+LocalGPU） | GPU 与 CPU 同能力函数，分数差距大，调度器自动择优，无需人工干预 |

---

## 12. 安全考量

Fredica 集群涉及两套独立的认证体系，职责不同，不可混用：

| 认证层 | 对象 | 凭证 | 校验方式 |
|--------|------|------|---------|
| **集群内部认证** | 节点 ↔ 节点 | `cluster_secret` | `X-Cluster-Secret` 请求头 |
| **发布者认证** | App/用户 → 节点 | `access_token` | `Authorization: Bearer` 请求头 |

---

### 12.1 集群内部认证（节点 ↔ 节点）

```
所有 /cluster/* 端点均校验：
  X-Cluster-Secret: <cluster_secret>

cluster_secret 在 worker.yaml 中配置，同一集群所有节点必须一致。
节点间任务转发、heartbeat、sync 均使用此凭证。

部署建议：
  - 仅局域网使用：绑定 LAN 接口，无需额外保护
  - 公网部署：务必在反向代理层（Nginx/Caddy）启用 TLS，
    避免 cluster_secret 在公网明文传输
```

---

### 12.2 发布者认证（Publisher Token）

#### 令牌模式（`auth.mode: "token"`）

适用于节点暴露在公网，或需要多用户权限管控的场景。

**令牌生命周期：**

```
运营者生成令牌：
  fredica token create --name "Alice" --cloud --max-cost 2.0 --expires 90d

  输出：
    Token ID  : tok-a1b2c3
    Token     : fredica_tok_xXxXxXxX...  ← 仅展示一次，之后不可查看
    Expires   : 2026-06-01
    Permissions: allow_cloud=true, max_cost_per_day=$2.00

令牌存储：
  worker.yaml 中存储 SHA-256(token) 而非明文
  原始 token 由运营者通过带外方式（IM / 面对面）告知发布者

发布者配置：
  Fredica App → 设置 → 集群连接
    服务器地址: https://fredica.example.com:7631
    访问令牌:   fredica_tok_xXxXxXxX...
```

**请求鉴权流程：**

```
发布者 App → POST /api/v1/pipelines/create
  Authorization: Bearer fredica_tok_xXxXxXxX...
  Body: { template, material_id, cloud_preference, ... }

发起节点（Originator）：
  1. 提取 Bearer Token，计算 SHA-256
  2. 在 access_tokens 列表中匹配 token_hash
  3. 检查 expires_at（若有）
  4. 检查 permissions（云端权限、每日限额）
  5. 通过 → 创建任务，写入 task.publisher_id = tok-a1b2c3
  6. 失败 → 401 / 403，拒绝创建

执行节点（Executor）：
  - 通过 X-Cluster-Secret 信任 Originator 转发的任务
  - 执行时可用 task.publisher_id 做审计日志
  - 可选：若本地 tokens.yaml 中有该 publisher_id 的配置，额外校验云端权限
```

#### 开放模式（`auth.mode: "open"`，默认）

适用于纯局域网私有部署，不需要多用户管控：

```
/api/v1/* 端点不校验 Authorization 头
任何能访问节点 IP 的请求均可发布任务
依赖网络隔离（路由器防火墙 / VPN）保证安全
```

> **建议**：公网暴露的节点**必须**使用 `auth.mode: "token"`，局域网家用部署使用 `open` 即可。

---

### 12.3 云端 API Key 保护

```
API Key（如 OpenAI、科大讯飞）仅存储在 worker.yaml（运营者设备本地）。
任务 payload 中不携带 API Key——任务只包含后端名称（如 "xunfei-iat-api"），
实际的 Key 在执行节点本地解析。

这意味着：
  - 即使任务数据在节点间同步，泄露的也只是"任务描述"，不含凭证
  - 发布者无法知道运营者使用了哪个 Key，只能知道任务用了哪个后端
```

---

### 12.4 数据隔离

```
B 站 Cookie 等用户凭证：
  仅存储在发起节点（composeApp 所在机器）
  DOWNLOAD_VIDEO 任务由发起节点本地执行，不转发到其他节点（HARD 亲和）

任务 payload 的敏感性：
  包含素材 URL、分析提示词等，属于用户数据
  需要与运营者建立信任后（通过 access_token 机制）才能发布
  executor 节点持有的 payload 在 result_acked_at 后可清理（§7.4）
```

---

## 13. 参考与依赖

| 库 / 技术 | 用途 | 现有/新增 |
|----------|------|----------|
| Ktor Server | 暴露 Cluster API | 现有 |
| Ktorm + SQLite | 任务持久化 | 现有 |
| Kotlinx Coroutines | Worker 协程调度 | 现有 |
| jmdns（`javax.jmdns`） | mDNS 节点发现 | **新增** |
| Kotlinx Serialization | 任务 payload JSON | 现有 |

无需引入 Kafka、Redis、etcd 等重型中间件，全部基于现有技术栈扩展。

---

## 附录 A：网速和延迟测试任务能力（NETWORK_TEST）

### A.1 设计目的

NETWORK_TEST 是一个**轻量级内置工具任务**，主要用途：

1. **开发阶段可视化调试**：提供一个简单、无外部依赖的任务，用于在前端"小工具"页面
   直接触发 Worker Engine 并观察任务从 `pending → claimed → running → completed` 的
   完整状态流转，验证 Worker Engine、Pipeline 状态机、前端轮询等各层协同工作。

2. **网络诊断工具**：测试本机到各服务器（Baidu、Google、GitHub、OpenAI 等）的 HTTP
   连接延迟，帮助用户了解当前网络状况，判断哪些下游服务（如 AI API、Python 转录）
   可正常访问。

### A.2 任务规格

| 属性 | 值 |
|------|-----|
| taskType | `NETWORK_TEST` |
| Executor 位置 | `commonMain/worker/executors/NetworkTestExecutor.kt` |
| 触发方式 | `POST /api/v1/NetworkTestRoute` |
| 平台依赖 | `ktor-client-core`（multiplatform），无需 ffmpeg / Python |

### A.3 Payload 格式

```json
{
  "urls": [
    "https://www.baidu.com",
    "https://www.google.com",
    "https://github.com",
    "https://api.openai.com"
  ],
  "timeout_ms": 5000
}
```

- `urls`：需要测试的 URL 列表，省略时使用内置默认列表
- `timeout_ms`：每个 URL 的请求超时时间（毫秒），默认 5000

### A.4 Result 格式

```json
{
  "results": [
    { "url": "https://www.baidu.com",   "latency_ms": 42,   "status": "ok" },
    { "url": "https://www.google.com",  "latency_ms": 350,  "status": "ok" },
    { "url": "https://github.com",      "latency_ms": 850,  "status": "ok" },
    { "url": "https://api.openai.com",  "latency_ms": null, "status": "timeout", "error": "超时（>5000ms）" }
  ]
}
```

状态值：
- `ok`：请求成功，`latency_ms` 为实测延迟（毫秒）
- `timeout`：请求超时
- `error`：其他错误（DNS 解析失败、连接被拒绝等），`error` 字段含详情

### A.5 NetworkTestRoute API

```
POST /api/v1/NetworkTestRoute
Content-Type: application/json

{
  "urls": [...],        // 可选，省略时用默认列表
  "timeout_ms": 5000   // 可选
}

响应：
{
  "pipeline_id": "uuid",
  "task_id": "uuid"
}
```

调用方通过 `pipeline_id` 轮询 `GET /api/v1/WorkerTaskListRoute?pipeline_id=xxx`
等待任务完成，然后从 `task.result` 中解析测速结果。

### A.6 前端"小工具"页面

路由：`/tools`

提供一个卡片式工具面板，当前包含"网速和延迟测试"工具：
- 展示默认测试目标列表
- "开始测试"按钮触发 NetworkTestRoute
- 实时显示任务状态（轮询，每 1s 一次）
- 任务完成后渲染延迟结果表格（URL | 延迟 | 状态）
- 侧边栏新增"小工具"导航项（`Wrench` 图标）
