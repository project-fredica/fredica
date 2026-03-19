---
order: 520
---

# Torch 下载功能重构计划

## 1. 现状与问题

### 1.1 当前流程

```
前端点击"下载"
  → save_torch_config（写入 AppConfig.torchVariant）
  → download_torch（创建 WorkflowRun + Task，返回 workflow_run_id）
  → 前端用 workflow_run_id 渲染 WorkflowInfoPanel 轮询进度
```

`DownloadTorchExecutor` 启动后直接读 `AppConfig.torchVariant`，因为 `save_torch_config` 已先写入，所以 AWAITING_TORCH_VARIANT 暂停逻辑实际上永远不会触发（variant 已非空）。

### 1.2 已知问题

**问题 0：下载参数从 AppConfig 读取，与前端页面状态脱节**

`DownloadTorchExecutor` 从 `AppConfigService` 读取 `torchVariant`、`torchDownloadIndexUrl` 等参数。这意味着下载行为取决于"上次保存的配置"，而非用户当前在页面上选择的值（variant、torch 版本号、镜像源）。若用户修改了选项但未点保存，或 `save_torch_config` 与 `download_torch` 之间存在竞态，实际下载的内容可能与预览不符。

**问题 0b：实际下载命令与 pip 命令预览不一致**

前端 pip 命令预览通过 `get_torch_pip_command` bridge 构造，包含 `torch_version`（用户选择的具体版本号）和 `index_url`。但 `install_torch_worker.py` 在执行时独立构造 pip 命令，存在以下三处不一致：

1. **`torch_version` 不支持**：worker 固定使用 `VARIANT_OPTIONS` 中的默认版本号，忽略用户选择的具体版本，导致实际下载版本与预览不符。
2. **`index_url` 模式不一致**：`pip-command` 路由支持 `--index-url`（替换模式）和 `--extra-index-url`（追加模式）两种；worker 固定使用 `--extra-index-url`，官方源始终保留，可能导致从官方源而非镜像下载。
3. **动态 variant 不支持**：`pip-command` 路由对 `cu129/cu130` 等不在 `VARIANT_OPTIONS` 中的 variant 有 fallback 构造逻辑；worker 遇到动态 variant 直接报错 `"未知 variant"`。

**问题 0c：调用链路日志不足**

从前端发起下载到 Python pip 执行，中间经过 bridge → Kotlin Executor → Python WebSocket → subprocess，缺少关键节点的入参/出参日志，排查问题困难。

**问题 1：页面刷新后 `workflow_run_id` 丢失**

`downloadWorkflowRunId` 仅存在 React state，刷新页面后变为 `null`，`WorkflowInfoPanel` 消失，用户无法看到正在进行的下载进度。

**问题 2：无法从后端查询活跃的下载任务**

没有 bridge 接口可以查询"当前是否有活跃的 DOWNLOAD_TORCH 任务"及其 `workflow_run_id`，导致刷新后无法恢复面板。

**问题 3：AWAITING_TORCH_VARIANT 机制是死代码**

`save_torch_config` 总在 `download_torch` 之前调用，variant 永远非空，暂停等待逻辑从未执行，但代码仍保留，增加维护负担。

**问题 4：`DownloadTorchJsMessageHandler` 去重逻辑不精确**

用 `TaskStatusService.listAll(pageSize = 200)` 全量扫描判断是否有活跃任务，性能差，且 pageSize 硬编码有上限风险。

**问题 5：`GetTorchCheckJsMessageHandler` 手动拼接 JSON 字符串**

`items` 数组用 `buildString { append(...) }` 手动拼接，违反 JSON 处理规范（应用 `buildValidJson`）。

---

## 2. 重构方案

### 2.0 下载参数改为从 JsMessage 传入

**目标**：`download_torch` bridge 直接接收前端当前页面状态，不再依赖 AppConfig 中的 torch 相关字段。

**`DownloadTorchJsMessageHandler`** 新增参数解析：

```kotlin
@Serializable
private data class Param(
    @SerialName("variant")           val variant: String = "",
    @SerialName("torch_version")     val torchVersion: String = "",       // 用户选择的版本号，空串=最高版本
    @SerialName("index_url")         val indexUrl: String = "",           // 镜像 index_url，空串=官方源
    @SerialName("use_proxy")         val useProxy: Boolean = false,
    @SerialName("proxy")             val proxy: String = "",
    // custom variant 专用
    @SerialName("custom_packages")   val customPackages: String = "",
    @SerialName("custom_index_url")  val customIndexUrl: String = "",
    @SerialName("custom_variant_id") val customVariantId: String = "",
)
```

参数写入 Task `payload`（JSON），`DownloadTorchExecutor` 从 `task.payload.loadJsonModel<Param>()` 读取，不再调用 `AppConfigService`。

**`DownloadTorchExecutor`** 改动：
- 删除所有 `AppConfigService.repo.getConfig()` 调用
- 从 payload 解析参数，直接构造 `paramJson` 传给 Python
- `torch_version` 和 `index_url` 透传给 Python `install_torch_worker`

**前端 `startDownload`** 改动：
- 不再先调 `save_torch_config`，直接把当前页面状态作为参数传给 `download_torch`
- 下载完成后（`handleActiveState` 检测到任务结束）再调 `save_torch_config` 持久化配置

```ts
// 下载时直接传参，不预先保存配置
const raw = await callBridge("download_torch", JSON.stringify({
    variant: selectedVariant,
    torch_version: selectedTorchVersions[selectedVariant] || mirrorBestVer,
    index_url: effectiveIndexUrl,
    use_proxy: useProxy,
    proxy: proxyUrl.trim(),
    custom_packages: selectedVariant === "custom" ? customPackages : "",
    custom_index_url: selectedVariant === "custom" ? customIndexUrl : "",
    custom_variant_id: selectedVariant === "custom" ? customVariantId.trim() : "",
}));
```

### 2.0b 移除所有硬编码版本表（`VARIANT_OPTIONS` / `_VARIANT_ORDER` / `_VARIANT_INDEX_URLS`）

**核心原则**：Python 后端不应硬编码任何 variant、包列表、版本号或 index_url。所有这些信息由前端（用户选择）通过参数传入，Python 只负责执行。

**各硬编码表的移除方案**：

| 常量 | 当前用途 | 移除方案 |
|------|---------|---------|
| `VARIANT_OPTIONS` | 查包列表、默认 index_url、锁定版本 | 全部改为参数传入 |
| `_VARIANT_ORDER` | 控制展示顺序、遍历内置 variant | 排序逻辑移到前端；`/check/` 路由改为单 variant 查询 |
| `_VARIANT_INDEX_URLS`（计划中的精简版） | 供 `resolve_recommended_spec` 构建选项 | 同样移除，推荐结果只返回 `recommended_variant` + `reason`，不附带 index_url |

**`build_pip_install_cmd` 工具函数**（提取自 `pip-command` 路由，纯参数驱动）：

```python
def build_pip_install_cmd(
    packages: list[str],       # 完整包列表，如 ["torch==2.7.0+cu128", "torchvision==0.22.0+cu128"]
    target_dir: str,           # --target 目录，空串时不加 --target（仅预览场景）
    index_url: str,            # pip index-url
    index_url_mode: str = "replace",  # "replace" → --index-url；"extra" → --extra-index-url
    use_proxy: bool = False,
    proxy: str = "",
) -> list[str]:
    """
    构造 pip install 命令列表。纯函数，无任何硬编码 variant 知识。
    供 /pip-command/ 路由（预览）和 install_torch_worker（实际执行）共用，保证两者完全一致。
    """
```

**`/pip-command/` 路由改造**：移除所有 `VARIANT_OPTIONS` 查询和 `_apply_version` 逻辑，改为直接接收前端传入的 `packages` 参数（JSON 数组），调用 `build_pip_install_cmd()` 返回命令字符串。

**`install_torch_worker` param 新增字段**：

```
packages       (list[str])  必填，完整包列表（含版本号），由 Kotlin Executor 从前端参数构造后传入
index_url      (str)        必填，pip index-url
index_url_mode (str)        可选，"replace"（默认）或 "extra"
```

**`check_torch_download` 签名变更**：

```python
# 旧：从 VARIANT_OPTIONS 取锁定版本，只支持内置 variant
def check_torch_download(variant: str, download_dir: str) -> TorchCheckResult

# 新：expected_version 由调用方传入；不传则只检查目录是否存在
def check_torch_download(variant: str, download_dir: str, expected_version: str = "") -> TorchCheckResult
```

**`resolve_recommended_spec` 简化**：只返回 `recommended_variant` 和 `reason`，不再附带 `options` 列表（前端已从镜像查询获得完整 variant 列表，不需要后端提供）。

### 2.0c 确保实际下载命令与 pip 预览一致（Python 层）

`/pip-command/` 路由和 `install_torch_worker` 均调用 `build_pip_install_cmd()`，入参完全相同，保证预览与实际执行一致。前端在调用 `download_torch` bridge 时传入与预览时相同的 `packages`、`index_url`、`index_url_mode`，Kotlin Executor 原样写入 Task payload 传给 Python。

### 2.0c 补充调用链路日志

各层补充以下日志（均用 `logger.info` / `logger.debug`）：

| 位置 | 日志内容 |
|------|---------|
| `DownloadTorchJsMessageHandler.handle2` | 入参：`variant`, `torch_version`, `index_url`, `use_proxy` |
| `DownloadTorchExecutor.executeWithSignals` | 开始执行：`variant`, `torch_version`, `index_url`, `downloadDir` |
| `DownloadTorchExecutor.executeWithSignals` | 构造的 `paramJson`（脱敏 proxy） |
| `install_torch_worker.py` 入口 | 收到参数：`variant`, `torch_version`, `index_url` |
| `install_torch_worker.py` | 实际执行的 pip 命令（完整字符串） |
| `install_torch_worker.py` | pip 退出码 + 耗时 |

### 2.1 新增 bridge：`get_active_torch_download`

**目标**：前端 mount 时查询后端，恢复活跃下载任务的 `workflow_run_id`。

**Kotlin**（新建 `GetActiveTorchDownloadJsMessageHandler.kt`）：

```kotlin
// 查询活跃的 DOWNLOAD_TORCH 任务，返回其 workflow_run_id（无则返回空串）
class GetActiveTorchDownloadJsMessageHandler : MyJsMessageHandler() {
    override suspend fun handle2(...) {
        val activeStatuses = setOf("pending", "claimed", "running")
        val task = TaskService.repo.listByType("DOWNLOAD_TORCH")
            .firstOrNull { it.status in activeStatuses }
        callback(buildValidJson {
            kv("workflow_run_id", task?.workflowRunId ?: "")
            kv("task_id", task?.id ?: "")
            kv("status", task?.status ?: "")
        }.str)
    }
}
```

需要在 `TaskDb` / `TaskService` 补充 `listByType(type: String): List<Task>` 查询方法（按 type 过滤，不全量扫描）。

**前端**：在 `useEffect([], [])` 中调用，若返回非空 `workflow_run_id` 则恢复 `downloadWorkflowRunId` state：

```ts
const raw = await callBridgeOrNull("get_active_torch_download");
if (raw) {
    const res = JSON.parse(raw);
    if (res.workflow_run_id) {
        setDownloadWorkflowRunId(res.workflow_run_id);
        setDownloading(true);
    }
}
```

### 2.2 移除 AWAITING_TORCH_VARIANT 死代码

`DownloadTorchExecutor` 中删除 variant 为空时的暂停等待逻辑，直接读取 `AppConfig.torchVariant`，若为空则立即返回错误：

```kotlin
val variant = AppConfigService.repo.getConfig().torchVariant
if (variant.isEmpty()) {
    return@withContext ExecuteResult(error = "torchVariant 未配置", errorType = "MISSING_TORCH_VARIANT")
}
```

`DownloadTorchJsMessageHandler` 的注释同步更新，删除 AWAITING_TORCH_VARIANT 相关说明。

### 2.3 优化 `DownloadTorchJsMessageHandler` 去重逻辑

将全量扫描改为按 type 查询：

```kotlin
val task = TaskService.repo.listByType("DOWNLOAD_TORCH")
    .firstOrNull { it.status in activeStatuses }
if (task != null) {
    // 返回已有任务的 workflow_run_id，方便前端直接恢复面板
    callback(buildValidJson {
        kv("error", "TASK_ALREADY_ACTIVE")
        kv("workflow_run_id", task.workflowRunId)
    }.str)
    return
}
```

前端收到 `TASK_ALREADY_ACTIVE` 时，若响应中含 `workflow_run_id`，直接恢复面板而非显示错误。

### 2.4 修复 `GetTorchCheckJsMessageHandler` JSON 拼接

将手动 `buildString` 拼接改为规范的 `buildValidJson`：

```kotlin
// 用 ValidJsonString 包装每个 item，再组成数组
val itemsJson = obj.entries.joinToString(",", "[", "]") { (variant, v) ->
    val vObj = v as? JsonObject
    val alreadyOk = (vObj?.get("already_ok") as? JsonPrimitive)?.booleanOrNull ?: false
    val version = (vObj?.get("installed_version") as? JsonPrimitive)?.contentOrNull
    buildValidJson {
        kv("variant", variant)
        kv("downloaded", alreadyOk)
        kv("version", version)
    }.str
}
callback(buildValidJson { kv("items", ValidJsonString(itemsJson)) }.str)
```

---

## 3. 涉及文件清单

| 文件 | 改动 |
|------|------|
| `composeApp/.../messages/GetActiveTorchDownloadJsMessageHandler.kt` | **新建**：查询活跃 DOWNLOAD_TORCH 任务 |
| `composeApp/.../messages/DownloadTorchJsMessageHandler.kt` | 接收下载参数（含 `packages`/`index_url`）；去重改为 `listByType`；`TASK_ALREADY_ACTIVE` 时附带 `workflow_run_id`；补充入参日志 |
| `composeApp/.../messages/GetTorchCheckJsMessageHandler.kt` | 修复 JSON 手动拼接；传入 `expected_version` 参数 |
| `shared/.../worker/executors/DownloadTorchExecutor.kt` | 从 payload 读参数；构造 `packages` 列表传给 Python；删除 AWAITING_TORCH_VARIANT 逻辑；删除 AppConfigService 调用；补充日志 |
| `shared/.../db/TaskDb.kt` | 新增 `listByType(type)` 查询方法 |
| `composeApp/.../AppWebView.kt`（或注册入口） | 注册 `GetActiveTorchDownloadJsMessageHandler` |
| `fredica_pyutil_server/util/torch_version_util.py` | 新增 `build_pip_install_cmd()` 纯函数；`check_torch_download` 新增 `expected_version` 参数；删除 `VARIANT_OPTIONS` / `_VARIANT_ORDER` / `_VARIANT_INDEX_URLS`；`resolve_recommended_spec` 简化为只返回 `recommended_variant` + `reason` |
| `fredica_pyutil_server/routes/torch.py` | `pip-command` 路由改为接收 `packages` 参数，调用 `build_pip_install_cmd()`，移除所有 `VARIANT_OPTIONS`/`_apply_version` 逻辑；`/check/` 路由移除 `VARIANT_OPTIONS` 遍历，改为单 variant 查询 |
| `fredica_pyutil_server/subprocess/install_torch_worker.py` | 从 `param` 直接读 `packages`/`index_url`/`index_url_mode`；调用 `build_pip_install_cmd()`；移除 `VARIANT_OPTIONS` 查询 |
| `fredica-webui/.../app-desktop-setting-torch-config.tsx` | `download_torch` 直接传参（含 `packages`）；mount 时恢复 state；下载完成后再 `save_torch_config` |

---

## 4. 实施顺序

1. `TaskDb` 新增 `listByType` 方法 + 单元测试
2. Python：
   a. `torch_version_util.py`：提取 `build_pip_install_cmd()`；`check_torch_download` 新增 `expected_version` 参数；`VARIANT_OPTIONS` 精简为 `_VARIANT_INDEX_URLS`
   b. `routes/torch.py`：`pip-command` 改用 `build_pip_install_cmd()`；`/check/` 移除 `VARIANT_OPTIONS` 遍历
   c. `install_torch_worker.py`：改为从 param 读 `packages`；改用 `build_pip_install_cmd()`
3. 更新 `DownloadTorchJsMessageHandler`（接收参数 + 去重 + 日志）
4. 更新 `DownloadTorchExecutor`（从 payload 读参数 + 构造 packages + 删除 AWAITING + 日志）
5. 新建 `GetActiveTorchDownloadJsMessageHandler` + 注册
6. 修复 `GetTorchCheckJsMessageHandler` JSON 拼接
7. 前端：`download_torch` 直传参数（含 packages）；mount 恢复 state；完成后保存配置

---

## 5. 不在本次范围内

- WorkflowRun 持久化到 localStorage（当前方案用后端查询替代，更可靠）
- 下载完成后自动重启 Python 服务（现有 `showRestartHint` 提示用户手动操作，暂不改动）
- 多并发下载支持（当前设计只允许一个活跃下载任务）
