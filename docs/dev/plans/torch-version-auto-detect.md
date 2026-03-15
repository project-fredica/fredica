---
title: 自动检测合适的 torch 版本方案
order: 9
---

# 自动检测合适的 torch 版本方案

> **文档状态**：草稿（Draft）
> **创建日期**：2026-03-14
> **适用模块**：`fredica_pyutil_server`、`shared`、`fredica-webui`

---

## 1. 背景与问题

`faster-whisper` 依赖 `torch`，而 torch 的安装包因 CUDA 版本不同差异极大：

| 包名 | 适用场景 | 包大小 |
|------|---------|--------|
| `torch` (CPU-only) | 无 GPU / 不需要 CUDA | ~200 MB |
| `torch` + CUDA 11.8 | GTX 10xx / RTX 20xx / 30xx（旧驱动） | ~2.5 GB |
| `torch` + CUDA 12.1 | RTX 30xx / 40xx（主流） | ~2.5 GB |
| `torch` + CUDA 12.4 | RTX 40xx / 50xx（新驱动） | ~2.5 GB |
| `torch` + ROCm 6.x | AMD GPU（Linux） | ~2.5 GB |

**当前问题**：`requirements.txt` 直接写 `faster-whisper`，pip 会拉取 CPU-only 的 `torch`。
用户有 NVIDIA GPU 时，faster-whisper 仍以 CPU 模式运行，性能差 10-50x。

**打包污染问题**：torch 体积达 2.5 GB，若安装到 `desktop_assets/` 内嵌 Python 的
site-packages，Compose Desktop 打包时会将其一并打入安装包，导致安装包体积爆炸。

**目标**：
1. 自动探测本机 GPU 环境，向用户推荐合适的 torch 版本
2. 由用户在设置页确认版本后手动触发下载
3. torch 下载到 `{dataDir}/download/torch/{variant}/`（用户数据目录，不在 `desktop_assets/` 内）
4. App 启动时将该目录符号链接到 Python 的 site-packages，退出时删除链接
5. 打包产物中不含 torch，用户首次运行时按需下载

---

## 2. 整体流程

两条独立流程，互不阻塞：

### 2.1 检测流程（自动，写 `torchRecommendedVariant`）

```
App 启动 → Python 服务就绪
        ↓
  POST /torch/resolve-spec（异步，不阻塞启动）
        ↓
  写入 AppConfig.torchRecommendedVariant（推荐版本，仅供展示）
        ↓
  用户打开设置页 → 展示推荐版本 + 可选列表
        ↓
  用户选择版本 → 写入 AppConfig.torchVariant（用户选择，用于下载和链接）
```

### 2.2 下载流程（用户手动触发，读 `torchVariant`）

```
用户点击"下载"→ DownloadTorchJsMessageHandler 创建 WorkflowRun + Task
        ↓
  WorkerEngine 调度 DownloadTorchExecutor
        ↓
  读取 AppConfig.torchVariant
  ├─ 非空 → 直接执行下载
  └─ 为空 → 任务暂停（AWAITING_TORCH_VARIANT），等待用户在设置页选择版本后手动恢复
        ↓（恢复后）
  再次检查 torchVariant，非空则继续下载
        ↓
  下载到 {dataDir}/download/torch/{variant}/
        ↓
  写入 AppConfig.torchVariant（确认），提示重启 Python 服务
        ↓
  下次 Python 服务启动时 setup_links() 生效
```

> **关键区分**：
> - `torchRecommendedVariant`：检测结果，只读，供 UI 展示推荐
> - `torchVariant`：用户主动选择并确认的版本，用于下载目录命名和符号链接

---

## 3. 阶段 A：GPU 探测 → 推荐版本

### 3.1 探测逻辑

复用已有的 `device_util.detect_gpu_info()`，不引入新依赖。
在 `/torch/resolve-spec` HTTP 接口中调用，结果缓存到 AppConfig。

```python
# util/torch_version_util.py

def resolve_recommended_spec(device_info: dict) -> TorchRecommendation:
    """
    根据 detect_gpu_info() 的结果，推荐合适的 torch 版本。
    同时返回所有可选版本列表，供用户在 UI 中选择。
    """
```

### 3.2 NVIDIA 驱动版本 → CUDA toolkit 对照

NVIDIA 驱动与 CUDA toolkit 的最低版本对应关系（来源：NVIDIA CUDA Release Notes）：

| CUDA toolkit | Linux 最低驱动 | Windows 最低驱动 |
|-------------|--------------|----------------|
| 12.8        | ≥ 570.00     | ≥ 572.16       |
| 12.6        | ≥ 560.28     | ≥ 561.09       |
| 12.4        | ≥ 550.54     | ≥ 551.61       |
| 12.1        | ≥ 530.30     | ≥ 531.14       |
| 11.8        | ≥ 520.61     | ≥ 522.06       |

决策逻辑（取保守值，优先选最新兼容版本）：

```
NVIDIA GPU 存在？
├─ 是 → 读取驱动版本（pynvml 或 nvidia-smi）
│        ├─ Linux ≥ 570 / Windows ≥ 572 → 推荐 cu128
│        ├─ Linux ≥ 560 / Windows ≥ 561 → 推荐 cu126
│        ├─ Linux ≥ 550 / Windows ≥ 551 → 推荐 cu124
│        ├─ Linux ≥ 530 / Windows ≥ 531 → 推荐 cu121
│        ├─ Linux ≥ 520 / Windows ≥ 522 → 推荐 cu118
│        └─ 驱动过旧 / 无法读取          → 推荐 cpu，提示升级驱动
└─ 否 → ROCm 可用？（Linux only）
         ├─ 是 → 推荐 rocm6.2
         └─ 否 → 推荐 cpu
```

### 3.3 TorchRecommendation 数据结构

```python
@dataclass
class TorchVariantOption:
    variant: str        # "cpu" | "cu118" | "cu121" | "cu124" | "cu126" | "cu128" | "rocm6.2"
    label: str          # 展示名，如 "CUDA 12.8（推荐）"
    index_url: str      # 官方 whl 源 URL
    packages: list[str] # 固定版本包列表，如 ["torch==2.6.0+cu128"]
    is_recommended: bool

@dataclass
class TorchRecommendation:
    recommended_variant: str          # 推荐的 variant
    reason: str                       # 推荐原因，如 "检测到 NVIDIA RTX 4090，驱动 572.xx"
    driver_version: str               # 探测到的驱动版本
    options: list[TorchVariantOption] # 所有可选版本（含推荐项）
```

### 3.4 可选版本列表与下载源

无论探测结果如何，UI 始终展示全部可选项，用户可覆盖推荐。

**torch / torchvision / torchaudio 版本对应关系：**

| torch | torchvision | torchaudio |
|-------|------------|-----------|
| 2.7.0 | 0.22.0 | 2.7.0 |
| 2.6.0 | 0.21.0 | 2.6.0 |
| 2.5.1 | 0.20.1 | 2.5.1 |

**各 variant 支持的 torch 版本与下载源：**

| variant | 锁定 torch 版本 | 适用驱动（Win / Linux） | 官方 whl index-url |
|---------|--------------|----------------------|-------------------|
| `cu128` | **2.7.0** | ≥ 572 / ≥ 570；RTX 50xx 必须 | `https://download.pytorch.org/whl/cu128` |
| `cu126` | 2.7.0 | ≥ 561 / ≥ 560 | `https://download.pytorch.org/whl/cu126` |
| `cu124` | 2.6.0 | ≥ 551 / ≥ 550 | `https://download.pytorch.org/whl/cu124` |
| `cu121` | 2.6.0 | ≥ 531 / ≥ 530 | `https://download.pytorch.org/whl/cu121` |
| `cu118` | 2.7.0 | ≥ 522 / ≥ 520 | `https://download.pytorch.org/whl/cu118` |
| `rocm6.3` | 2.7.0 | — / AMD GPU Linux（ROCm 6.3） | `https://download.pytorch.org/whl/rocm6.3` |
| `rocm6.2` | 2.6.0 | — / AMD GPU Linux（ROCm 6.2） | `https://download.pytorch.org/whl/rocm6.2` |
| `cpu` | 2.7.0 | 无 GPU | `https://download.pytorch.org/whl/cpu` |
| `custom` | 用户自定义 | — | 用户自行输入 |

**锁定版本对应的完整 packages 列表：**

| variant | packages |
|---------|---------|
| `cu128` | `torch==2.7.0+cu128`, `torchvision==0.22.0+cu128`, `torchaudio==2.7.0+cu128` |
| `cu126` | `torch==2.7.0+cu126`, `torchvision==0.22.0+cu126`, `torchaudio==2.7.0+cu126` |
| `cu124` | `torch==2.6.0+cu124`, `torchvision==0.21.0+cu124`, `torchaudio==2.6.0+cu124` |
| `cu121` | `torch==2.6.0+cu121`, `torchvision==0.21.0+cu121`, `torchaudio==2.6.0+cu121` |
| `cu118` | `torch==2.7.0+cu118`, `torchvision==0.22.0+cu118`, `torchaudio==2.7.0+cu118` |
| `rocm6.3` | `torch==2.7.0+rocm6.3`, `torchvision==0.22.0+rocm6.3`, `torchaudio==2.7.0+rocm6.3` |
| `rocm6.2` | `torch==2.6.0+rocm6.2`, `torchvision==0.21.0+rocm6.2`, `torchaudio==2.6.0+rocm6.2` |
| `cpu` | `torch==2.7.0+cpu`, `torchvision==0.22.0+cpu`, `torchaudio==2.7.0+cpu` |
| `custom` | 用户自行填写 |

> **注意**：
> - `cu128` 在 torch 2.7.0 中正式支持（Blackwell GPU / RTX 50xx 系列必须使用）
> - `cu124`、`cu121` 最高为 2.6.0（torch 2.7.0 未提供这两个 variant 的 wheel）
> - faster-whisper 不依赖 torchvision，但一并安装可避免其他组件报缺失
> - ROCm 7.x 系列已出现，但 pytorch 官方 whl 尚无对应，暂不纳入

### 3.5 自定义版本选项

UI 提供"自定义"选项，允许高级用户完全手动控制：

```
┌─ 自定义版本 ────────────────────────────────────────────────────────┐
│                                                                     │
│  packages（每行一个）                                                │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ torch==2.7.0+cu128                                          │   │
│  │ torchvision==0.22.0+cu128                                   │   │
│  │ torchaudio==2.7.0+cu128                                     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  index-url                                                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ https://download.pytorch.org/whl/cu128                      │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  variant 标识（用于目录名和链接管理）                                  │
│  ┌──────────────┐                                                   │
│  │ cu128        │                                                   │
│  └──────────────┘                                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

对应 `TorchVariantOption` 中 `variant = "custom"` 时，`packages` 和 `index_url`
由用户输入覆盖，`AppConfig` 中额外存储：

```kotlin
@SerialName("torch_custom_packages")  val torchCustomPackages: String = ""
// 换行分隔的包列表，如 "torch==2.7.0+cu128\ntorchvision==0.22.0+cu128"
@SerialName("torch_custom_index_url") val torchCustomIndexUrl: String = ""
// 自定义 index-url
@SerialName("torch_custom_variant_id") val torchCustomVariantId: String = ""
// 用于目录名，如 "cu128-custom"
```

### 3.6 关于国内镜像源与代理

**清华、阿里等国内镜像不同步 pytorch 官方 whl 源**（CUDA wheel 发布在
`download.pytorch.org/whl/` 独立索引，不在 PyPI 上）。

不同 index-url 对代理需求不同：

| index-url 类型 | 示例 | 是否需要代理 |
|--------------|------|------------|
| 官方源 | `download.pytorch.org/whl/cu128` | 国内通常需要 |
| 自定义国内源 | 用户自行填写的国内镜像 | 通常不需要 |
| 其他第三方源 | 用户自行判断 | 不确定 |

因此在下载面板中提供**独立的代理开关**，而不是强制复用全局代理：

```
┌─ 下载选项 ──────────────────────────────────────────────────────────┐
│                                                                     │
│  使用代理    ● 是   ○ 否                                             │
│  代理地址    [http://127.0.0.1:7890          ]                       │
│             （留空则读取全局代理设置）                                 │
│                                                                     │
│                                          [开始下载]                  │
└─────────────────────────────────────────────────────────────────────┘
```

交互逻辑：
- 默认值：若 `AppConfig.proxyUrl` 非空则预填并勾选"是"，否则默认"否"
- 选"否"时代理地址框置灰，pip 命令不加 `--proxy`
- 选"是"且地址非空时，透传给 pip `--proxy {addr}`
- 选"是"但地址为空时，提示"请填写代理地址"，阻止下载

对应 `install_torch_worker` 的 `param` 新增字段：

```python
# param 字段补充
use_proxy: bool   # 是否启用代理
proxy:     str    # 代理地址，use_proxy=False 时忽略
```

```python
if param.get("use_proxy") and param.get("proxy"):
    cmd += ["--proxy", param["proxy"]]
```

AppConfig 新增字段，记住用户上次的代理选择：

```kotlin
@SerialName("torch_download_use_proxy")  val torchDownloadUseProxy: Boolean = false
@SerialName("torch_download_proxy_url")  val torchDownloadProxyUrl: String = ""
// 空串表示跟随全局代理
```

---

## 4. 目录结构与符号链接方案

### 4.1 目录布局

```
{dataDir}/download/torch/
└── cu124/                            ← variant 子目录，pip --target 安装到此
    ├── torch/                        ← torch 包本体
    ├── torch-2.6.0+cu124.dist-info/
    ├── torchaudio/
    └── ...

{python_site_packages}/               ← 内嵌 Python 的 site-packages
    ├── torch     →  {dataDir}/download/torch/cu124/torch      ← 符号链接
    ├── torchaudio →  {dataDir}/download/torch/cu124/torchaudio
    └── ...（其他正常安装的包）
```

`{dataDir}` 对应 `AppConfig.dataDir`（用户在设置页配置的数据目录）。
`{python_site_packages}` 通过 `sysconfig.get_path("purelib")` 在运行时获取。

### 4.2 为什么用 `--target`

`pip install --target <dir>` 将包直接平铺到指定目录，每个顶层包目录（`torch/`、`torchaudio/`）
可以单独符号链接到 site-packages，粒度精确，不污染其他包。

### 4.3 Windows 符号链接权限 fallback

Windows 默认需要开发者模式或管理员权限才能创建符号链接。
若 `os.symlink()` 抛出 `OSError`，fallback 为在 `main.py` 中
`sys.path.insert(0, str(variant_dir))`，效果等同，无需权限。

---

## 5. 阶段 B：下载子进程（用户手动触发）

### 5.1 检查是否已下载

```python
def check_torch_download(variant: str, download_dir: str) -> TorchCheckResult:
    """
    检查 {download_dir}/{variant}/ 中是否已有匹配版本的 torch。
    读取 torch-*.dist-info/METADATA 中的 Version 字段与锁定版本比对。
    """
```

### 5.2 subprocess worker

**暂停信号透传机制**：

Python 子进程的暂停/恢复由 `resume_event`（`multiprocessing.Event`）控制：
- 父进程收到 `{"command":"pause"}` WebSocket 消息 → `_resume_mp_event.clear()` → 子进程在 `resume_event.wait()` 处阻塞
- 父进程收到 `{"command":"resume"}` WebSocket 消息 → `_resume_mp_event.set()` → 子进程继续执行

**variant 更新问题**：`param` 在子进程启动时已序列化传入，恢复信号不携带新数据。
解决方案：`DownloadTorchExecutor` 在检测到 `torchVariant` 为空时，**不启动 websocketTask**，
而是先在 Kotlin 层循环等待，直到 `torchVariant` 非空后再以完整 param 启动 websocketTask：

```kotlin
// DownloadTorchExecutor.kt（伪代码）
override suspend fun executeWithSignals(...): ExecuteResult {
    // 检查 variant，为空则暂停等待
    var variant = AppConfigService.repo.getConfig().torchVariant
    if (variant.isEmpty()) {
        // 标记任务为 AWAITING_TORCH_VARIANT，前端展示"等待选择版本"
        TaskService.repo.updateErrorType(task.id, "AWAITING_TORCH_VARIANT")
        // 发送暂停信号（让 WorkflowInfoPanel 显示暂停状态）
        TaskPauseResumeService.pause(task.id)  // 内部投递到 pauseChannel

        // 循环等待：每次收到 resume 信号后重新检查 torchVariant
        while (variant.isEmpty()) {
            pauseResumeChannels.resume.receive()   // 阻塞等待恢复信号
            if (cancelSignal.isCompleted) return ExecuteResult(error = "已取消", errorType = "CANCELLED")
            variant = AppConfigService.repo.getConfig().torchVariant
            if (variant.isEmpty()) {
                // 仍未选择，再次暂停
                TaskPauseResumeService.pause(task.id)
            }
        }
        // variant 已就绪，清除等待状态
        TaskService.repo.updateErrorType(task.id, null)
    }

    // 以确定的 variant 启动下载
    val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
        pth = "/torch/install-task",
        paramJson = buildValidJson {
            kv("variant", variant)
            kv("download_dir", downloadDir)
            kv("use_proxy", useProxy)
            kv("proxy", proxy)
        },
        onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
        cancelSignal = cancelSignal,
    )
    ...
}
```

这样 Python worker 本身不需要处理 variant 为空的情况，始终以非空 variant 启动，逻辑更简单：

```python
# subprocess/install_torch_worker.py

def install_torch_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    param 字段：
        variant      (str)  用户选择的 variant，如 "cu124"（由 Kotlin 层保证非空）
        download_dir (str)  {dataDir}/download/torch/
        use_proxy    (bool) 是否启用代理
        proxy        (str)  代理地址，use_proxy=False 时忽略

    向 status_queue 推送：
        {"type": "check_result",   "already_ok": bool, "installed_version": str}
        {"type": "download_start", "packages": [...], "target_dir": str}
        {"type": "progress",       "percent": int, "line": str}
        {"type": "done",           "result": {"variant": str, "target_dir": str}}
        {"type": "error",          "message": str}
    """
```

下载命令（`--target` 模式，不写入 site-packages）：

```python
option = VARIANT_OPTIONS[variant]   # 从固定表中取 packages + index_url
target_dir = Path(download_dir) / variant
target_dir.mkdir(parents=True, exist_ok=True)

cmd = [
    sys.executable, "-m", "pip", "install",
    "--target", str(target_dir),
    *option.packages,
    "--extra-index-url", option.index_url,
    "--progress-bar", "on",
]
if proxy:
    cmd += ["--proxy", proxy]
```

### 5.3 Python 路由

下载任务**不经过 Kotlin Worker**，直接由 kmpJsBridge 触发（见 §7）。
Python 侧只需暴露路由，不关心调用方是谁：

```
WS   /torch/install-task    ← 下载指定 variant 到隔离目录（长任务）
POST /torch/resolve-spec    ← 探测 GPU，返回 TorchRecommendation
GET  /torch/check           ← 检查各 variant 的下载状态
```

---

## 6. 阶段 C：符号链接生命周期管理

### 6.1 `util/torch_link_manager.py`

```python
def setup_links(download_dir: str, variant: str) -> bool:
    """
    在 site-packages 中为 {download_dir}/{variant}/ 下的所有顶层包创建符号链接。
    已存在且指向正确目标的链接直接跳过；指向旧目标的链接先删除再重建。
    返回 False 表示 variant 目录不存在（torch 尚未下载）。
    Windows 符号链接失败时 fallback 到 sys.path.insert。
    """

def teardown_links(download_dir: str, variant: str) -> None:
    """
    删除 site-packages 中由 setup_links 创建的符号链接。
    仅删除指向 {download_dir}/{variant}/ 内部的链接，不误删其他包。
    """
```

### 6.2 main.py 集成

Python 服务入口在 FastAPI 启动前同步执行链接，注册 atexit 钩子在退出时解链：

```python
# 从 Kotlin PythonUtil 注入的环境变量读取
data_dir = os.environ.get("FREDICA_DATA_DIR", "")
torch_variant = os.environ.get("FREDICA_TORCH_VARIANT", "")  # 如 "cu124"

if data_dir and torch_variant:
    download_dir = str(Path(data_dir) / "download" / "torch")
    setup_links(download_dir, torch_variant)
    atexit.register(teardown_links, download_dir, torch_variant)

uvicorn.run(app, ...)
```

### 6.3 Kotlin 端传参

`PythonUtil.kt` 启动 Python 子进程时注入环境变量：

```kotlin
// 从 AppConfig 读取用户已选定的 variant
val variant = AppConfigService.repo.getConfig().torchVariant  // 如 "cu124"，空串表示未选择
val env = mapOf(
    "FREDICA_DATA_DIR" to AppUtil.Paths.appDataDir.absolutePath,
    "FREDICA_TORCH_VARIANT" to variant,
)
```

---

## 7. AppConfig 持久化 + 前端展示

### 7.1 AppConfig 新增字段

```kotlin
// shared/src/commonMain/.../db/AppConfig.kt

// ── 检测结果（只读，启动时自动写入）──────────────────────────────────────
@SerialName("torch_recommended_variant") val torchRecommendedVariant: String = ""
// 检测推荐的 variant，如 "cu128"；仅供 UI 展示，不用于下载或链接

@SerialName("torch_recommendation_json") val torchRecommendationJson: String = ""
// TorchRecommendation 完整 JSON；含推荐原因、驱动版本、所有可选项

// ── 用户选择（用户主动写入）──────────────────────────────────────────────
@SerialName("torch_variant") val torchVariant: String = ""
// 用户在设置页选定的 variant，如 "cu124"
// 空串 = 未选择：Python 服务不链接 torch；DownloadTorchExecutor 暂停等待
```

两个字段的写入时机：

| 字段 | 写入时机 | 写入方 |
|------|---------|--------|
| `torchRecommendedVariant` | App 启动后 Python 服务就绪时 | `FredicaApi.jvm.kt` 异步写入 |
| `torchRecommendationJson` | 同上 | `FredicaApi.jvm.kt` 异步写入 |
| `torchVariant` | 用户在设置页选择版本并保存时 | 前端调 `AppConfigSaveRoute` 写入 |

### 7.2 启动时自动探测（仅探测，不下载）

`FredicaApi.jvm.kt` 在 Python 服务就绪后异步触发探测，结果写入 AppConfig：

```kotlin
launch(Dispatchers.IO) {
    try {
        val result = PythonUtil.Py314Embed.PyUtilServer
            .requestText(HttpMethod.Post, "/torch/resolve-spec")
        val recommendation = decodeJson<TorchRecommendation>(result).getOrNull()
        val config = AppConfigService.repo.getConfig()
        AppConfigService.repo.updateConfig(config.copy(
            torchRecommendedVariant = recommendation?.recommendedVariant ?: "",
            torchRecommendationJson = result,
        ))
    } catch (e: Throwable) {
        logger.warn("torch spec resolve failed: ${e.message}")
    }
}
```

注意：此处只写 `torchRecommendedVariant` 和 `torchRecommendationJson`，**不修改** `torchVariant`（用户选择）。

### 7.3 kmpJsBridge Handler（用户触发下载）

下载走 **Worker/Executor** 体系，kmpJsBridge handler 只负责创建 `WorkflowRun` + `Task`，
立即回调 `workflowRunId`，实际下载由 `WorkerEngine` 调度 `DownloadTorchExecutor` 执行。

**variant 可为空**：handler 创建任务时不要求 `torchVariant` 已选定。若用户点击"下载"时尚未选择版本，
任务照常创建，`DownloadTorchExecutor` 会在 Kotlin 层自行等待（见 §5.2）。
通常情况下用户会先在 UI 选好版本再点下载，variant 为空属于防御性处理。

```kotlin
// composeApp/.../bridge/DownloadTorchJsMessageHandler.kt
class DownloadTorchJsMessageHandler : MyJsMessageHandler {
    override val name = "download_torch"

    override fun handle(payload: String, callback: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val param = decodeJson<DownloadTorchParam>(payload)
                // param 字段：use_proxy, proxy（及 custom 扩展字段）
                // 注意：不含 variant，variant 由 Executor 从 AppConfig.torchVariant 读取

                // 保存代理设置
                val config = AppConfigService.repo.getConfig()
                AppConfigService.repo.updateConfig(config.copy(
                    torchDownloadUseProxy = param.useProxy,
                    torchDownloadProxyUrl = param.proxy,
                ))

                // 创建 WorkflowRun + Task，交给 WorkerEngine 调度
                // torchVariant 由 DownloadTorchExecutor 在执行时从 AppConfig 读取
                val workflowRunId = TorchDownloadWorkflowService.createAndStart(param)

                // 立即回调 workflowRunId，前端用 WorkflowInfoPanel 接管进度展示
                callback(buildValidJson {
                    kv("type", "started")
                    kv("workflow_run_id", workflowRunId)
                })
            } catch (e: Throwable) {
                callback(buildValidJson { kv("type", "error"); kv("message", e.message ?: "") })
            }
        }
    }
}
```

注册到 `AppWebViewMessages.all`：`DownloadTorchJsMessageHandler`、`GetTorchInfoJsMessageHandler`、`RunTorchDetectJsMessageHandler`。

取消通过现有的 `TaskCancelService` 机制处理，前端调用取消任务的通用接口即可。

### 7.4 前端展示（设置页）

在"硬件加速"分区下新增"PyTorch 环境"子分区：

```
┌─ PyTorch 环境 ──────────────────────────────────────────────────────┐
│                                                                     │
│  GPU 检测        NVIDIA RTX 4090 · 驱动 572.xx                      │
│  推荐版本        CUDA 12.8                                           │
│  推荐原因        驱动 572.xx 支持 CUDA 12.8，RTX 50xx 必须           │
│                                                                     │
│  选择版本        ● CUDA 12.8（推荐）  已下载 ✓                       │
│                  ○ CUDA 12.6         未下载                         │
│                  ○ CUDA 12.4         未下载                         │
│                  ○ CUDA 12.1         未下载                         │
│                  ○ CUDA 11.8         未下载                         │
│                  ○ ROCm 6.3          未下载（仅 Linux）              │
│                  ○ ROCm 6.2          未下载（仅 Linux）              │
│                  ○ CPU only          未下载                         │
│                  ○ 自定义版本  ↓展开填写 packages / index-url        │
│                                                                     │
│  当前生效版本    CUDA 12.8（重启后生效）                              │
│                                                                     │
│  使用代理        ● 是  ○ 否                                          │
│  代理地址        [http://127.0.0.1:7890          ]                  │
│                                                                     │
│                    [重新检测]              [下载选中版本]              │
│                                                                     │
│  ┌─ 下载进度（WorkflowInfoPanel）──────────────────────────────────┐ │
│  │  DOWNLOAD_TORCH   ████████░░  80%  下载中...                   │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

交互逻辑：
- 页面加载时调用 `callNative('get_torch_info')` 获取 `torchRecommendationJson` + `torchVariant`
- 单选框选中某 variant 后，"下载选中版本"按钮激活
- 点击下载 → `callNative('download_torch', {variant, useProxy, proxy, ...})` → 回调返回 `workflowRunId`
- 拿到 `workflowRunId` 后渲染 `WorkflowInfoPanel`，由其自带轮询接管进度展示与取消操作
- 任务完成（`WorkflowRun` 状态变为 `completed`）→ 提示重启 Python 服务
- "重新检测" → `callNative('run_torch_detect')` → 刷新推荐

---

## 8. 与现有 EvaluateFasterWhisperCompat 的关系

```
启动流程（FredicaApi.jvm.kt）：

  Python 服务就绪（main.py 已完成符号链接，torch 可 import）
       ↓
  POST /torch/resolve-spec（异步，仅探测写 AppConfig，不阻塞）
       ↓
  EVALUATE_FASTER_WHISPER_COMPAT（已有 Worker 任务）
  ├─ torchVariant 非空且已下载 → 在 GPU torch 上测试 compute_type
  └─ torchVariant 为空         → 跳过或以 CPU torch 测试
```

torch 下载走 Worker 体系（`DownloadTorchExecutor`），由用户在设置页通过 kmpJsBridge 触发创建任务，
`WorkflowInfoPanel` 接管进度展示。下载完成后需重启 Python 服务才能使新链接生效，
重启后 `EVALUATE_FASTER_WHISPER_COMPAT` 会在下次启动时自动重新评估。

---

## 9. 开发顺序

```
Step 1  util/torch_version_util.py
        - VARIANT_OPTIONS 固定表（variant → packages + index_url）
        - resolve_recommended_spec(device_info) → TorchRecommendation
        - check_torch_download(variant, download_dir) → TorchCheckResult

Step 2  util/torch_link_manager.py
        - setup_links(download_dir, variant) → bool（含 Windows fallback）
        - teardown_links(download_dir, variant)

Step 3  main.py 集成
        - 读取 FREDICA_DATA_DIR / FREDICA_TORCH_VARIANT 环境变量
        - 启动前 setup_links，atexit 注册 teardown_links

Step 4  subprocess/install_torch_worker.py
        - pip install --target {download_dir}/{variant}/ + 进度解析
        - 支持取消（cancel_event）

Step 5  routes/torch.py
        - WS   /torch/install-task
        - POST /torch/resolve-spec
        - GET  /torch/check

Step 6  AppConfig.kt + AppConfigDb.kt
        - 新增 torchVariant、torchRecommendationJson
        - 新增 torchDownloadUseProxy、torchDownloadProxyUrl
        - 新增 torchCustomPackages、torchCustomIndexUrl、torchCustomVariantId

Step 7  DownloadTorchExecutor.kt（jvmMain）
        - 读取 AppConfig.torchVariant；为空则标记 AWAITING_TORCH_VARIANT 并暂停
        - 循环 pauseResumeChannels.resume.receive() 等待，每次恢复后重新读取 torchVariant
        - 仍为空则再次调用 TaskPauseResumeService.pause()；非空则清除 errorType 继续
        - 以确定的 variant 启动 websocketTask 调用 /torch/install-task
        - 完成后写 AppConfig.torchVariant（确认）

Step 8  PythonUtil.kt
        - 启动子进程时注入 FREDICA_DATA_DIR + FREDICA_TORCH_VARIANT

Step 9  FredicaApi.jvm.kt
        - 启动时异步调用 /torch/resolve-spec，写 torchRecommendationJson

Step 10 kmpJsBridge（composeApp）
        - GetTorchInfoJsMessageHandler
        - RunTorchDetectJsMessageHandler
        - DownloadTorchJsMessageHandler（创建 WorkflowRun + Task，回调 workflowRunId）
        - AppWebViewMessages.all 注册以上 3 个 handler

Step 11 app-desktop-setting.tsx
        - "PyTorch 环境"子分区：推荐展示 + 版本单选 + 自定义展开 + 代理开关
        - 点击下载后渲染 WorkflowInfoPanel 展示进度
```

---

## 10. 待完成清单

### Step 1-5：Python 层
- [ ] `util/torch_version_util.py`：固定版本表 + `resolve_recommended_spec` + `check_torch_download`
- [ ] `util/torch_link_manager.py`：`setup_links`（含 Windows fallback）+ `teardown_links`
- [ ] `main.py`：启动前链接，atexit 解链
- [ ] `subprocess/install_torch_worker.py`：`--target` 下载 + 进度解析 + 取消支持
- [ ] `routes/torch.py`：WS `/torch/install-task` + POST `/torch/resolve-spec` + GET `/torch/check`

### Step 6-9：Kotlin 层
- [ ] `AppConfig.kt` + `AppConfigDb.kt`：新增 `torchVariant`、`torchRecommendationJson`、`torchDownloadUseProxy`、`torchDownloadProxyUrl`、`torchCustomPackages`、`torchCustomIndexUrl`、`torchCustomVariantId`
- [ ] `DownloadTorchExecutor.kt`：读取 `torchVariant`；为空则标记 `AWAITING_TORCH_VARIANT` + 暂停循环等待；非空后以确定 variant 启动 `websocketTask`，完成后写 `torchVariant`
- [ ] `PythonUtil.kt`：启动子进程时注入 `FREDICA_DATA_DIR` + `FREDICA_TORCH_VARIANT`
- [ ] `FredicaApi.jvm.kt`：启动时异步调用 `/torch/resolve-spec`，写 `torchRecommendationJson`

### Step 10-11：kmpJsBridge + 前端
- [ ] `GetTorchInfoJsMessageHandler.kt`
- [ ] `RunTorchDetectJsMessageHandler.kt`
- [ ] `DownloadTorchJsMessageHandler.kt`：创建 `WorkflowRun` + `Task`，回调 `workflowRunId`
- [ ] `AppWebViewMessages.all` 注册以上 3 个 handler
- [ ] `app-desktop-setting.tsx`：PyTorch 环境子分区（推荐展示 + 版本单选 + 自定义展开 + 代理开关 + `WorkflowInfoPanel` 进度展示）

---

## 11. 边界情况与注意事项

**下载失败**：`DownloadTorchJsMessageHandler` 捕获异常，回调 `{type: "error", message}`，不阻塞应用；faster-whisper 以 CPU 模式运行（若 CPU torch 已链接）。

**torchVariant 为空**：Python 服务启动时跳过链接，torch 不可用，faster-whisper 降级 CPU。

**AWAITING_TORCH_VARIANT 暂停状态**：用户点击"下载"但尚未在设置页选择版本时，`DownloadTorchExecutor` 将任务 `errorType` 标记为 `AWAITING_TORCH_VARIANT` 并调用 `TaskPauseResumeService.pause()`，任务进入暂停状态。`WorkflowInfoPanel` 展示暂停状态，用户在设置页选好版本后手动点击恢复，Executor 重新读取 `torchVariant`；若仍为空则再次暂停，直到非空为止。取消操作通过 `cancelSignal` 正常退出循环。

**多 variant 共存**：`{dataDir}/download/torch/` 下可同时存在多个 variant 目录，切换时修改 `torchVariant` 并重启即可，旧目录不自动删除（节省重复下载）。

**代理独立开关**：下载时使用 `torchDownloadUseProxy` / `torchDownloadProxyUrl`，与全局代理设置解耦；自定义国内源用户可关闭代理，官方源用户可单独开启。

**重复下载保护**：`check_torch_download` 在下载前执行，版本匹配时直接跳过，回调 `{type: "done"}` 并写入 `torchVariant`。

**ROCm 支持**：ROCm 仅在 Linux 上可用，Windows 的 AMD GPU 用户走 CPU 路径；UI 中 ROCm 选项标注"仅 Linux"。

**打包验证**：`{dataDir}/download/` 路径在用户数据目录，不在 `desktop_assets/` 内，Compose Desktop 打包时不会扫描到。

**自定义 variant 目录名**：`custom` variant 使用用户填写的 `torchCustomVariantId` 作为子目录名（如 `cu128-custom`），避免与内置 variant 目录冲突。

---

## §5 镜像源支持（2026-03）

### 支持的镜像站

| key      | 名称              | CUDA wheel | index_style   | URL 规律 |
|----------|-------------------|------------|---------------|----------|
| official | 官方源             | ✓          | simple_api    | download.pytorch.org/whl/{variant} |
| nju      | 南京大学           | ✓          | dir_listing   | mirrors.nju.edu.cn/pytorch/whl/{variant} |
| sjtu     | 上海交大           | ✓          | dir_listing   | mirror.sjtu.edu.cn/pytorch-wheels/{variant} |
| tuna     | 清华（仅CPU）      | ✗          | simple_api    | pypi.tuna.tsinghua.edu.cn/simple |
| custom   | 自定义             | -          | —             | 用户输入 |

### 探测原理

镜像站分两种格式，探测策略不同：

**simple_api 型**（官方源）：
- 请求 `{index_url}/torch/`，解析 HTML，检查是否含 `+{variant}` 字样的 wheel 文件名
- 官方源直接返回 `_VARIANT_ORDER` 内置列表，不发网络请求

**dir_listing 型**（南京大学、上海交大）：
- 这类镜像是 Nginx Fancyindex 目录列表，根 URL 直接列出 `cu128/`、`cu126/` 等子目录
- 请求根 URL（去掉 `/{variant}` 后缀），检查 HTML 中是否含 `href="{variant}/"` 或 `href="{variant}"` 形式的锚点
- 示例 HTML 片段：`<a href="cu128/" title="cu128">cu128/</a>`

两种探测均超时 8s，失败标记 `available=false`，并在 `debug` 级别日志输出 `html_snippet`（前 2000 字符）供调试。

### 路由

| 路由 | 说明 |
|------|------|
| `GET /torch/mirror-list/` | 返回所有镜像源列表（key/label/supports_cuda） |
| `GET /torch/mirror-check/` | 探测指定 variant 在各镜像的可用性（串行） |
| `GET /torch/mirror-versions/` | 抓取单个镜像支持的所有 variant 列表 |
| `GET /torch/all-mirror-variants/` | 并发查询所有非 custom 镜像，合并去重后按标准顺序返回 |

`all-mirror-variants` 响应格式：
```json
{
  "variants": ["cu128", "cu126", "cu124", "cu121", "cu118", "rocm6.3", "rocm6.2", "cpu"],
  "per_mirror": {
    "nju": ["cu128", "cu126", "cu124", "cu121", "cu118", "cpu"],
    "sjtu": ["cu128", "cu126", "cpu"],
    "official": ["cu128", "cu126", "cu124", "cu121", "cu118", "rocm6.3", "rocm6.2", "cpu"],
    "tuna": ["cpu"]
  }
}
```

### Kotlin Bridge

| bridge name | 说明 |
|-------------|------|
| `get_torch_mirror_check` | 探测各镜像对指定 variant 的可用性 |
| `get_torch_mirror_versions` | 获取单个镜像支持的 variant 列表 |
| `get_torch_all_mirror_variants` | 并发查询所有镜像，返回合并列表 + per_mirror 数据 |

### 前端交互

- **页面进入时**：调用 `get_torch_all_mirror_variants`，获取 `allVariants`（合并去重列表）和 `perMirrorVariants`（各镜像支持情况）
- 左栏版本列表从 `allVariants` 动态渲染，不再使用硬编码的 `BUILTIN_VARIANTS`
- 切换镜像时，用 `perMirrorVariants[mirrorKey]` 判断哪些 variant 不支持，自动 disable 并取消选中
- 点击"探测镜像可用性"触发 `get_torch_mirror_check`，填充右栏可用性徽章（✓ 可用 / ✗ 不可用）
- 两栏布局：左栏版本选择（240px），右栏下载源 + pip 预览 + 代理设置
