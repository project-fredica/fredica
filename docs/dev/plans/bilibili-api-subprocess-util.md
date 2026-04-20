---
title: bilibili-api 子进程封装工具
order: 530
---

# bilibili-api 子进程封装工具

## 背景

bilibili-api-python 的 `select_client()`、`request_settings.set()`、`request_settings.set_proxy()` 均为**进程级全局状态**。在 FastAPI 单进程 async 模型中，并发请求会互相污染。

### 演进历程

1. **Phase 1**：将所有 bilibili-api 调用从主进程 async 直调改为子进程执行，使用 `ProcessPoolExecutor` + `curl_cffi`
2. **Phase 2**：统一参数传递为 `BilibiliSubprocessContext` TypedDict 模式，消除样板代码
3. **Phase 3（当前）**：发现 `ProcessPoolExecutor` 复用子进程导致全局状态泄漏，改为**每请求独立 spawn 子进程**

### ProcessPoolExecutor 的问题

`ProcessPoolExecutor` 维护固定数量的 worker 进程并复用它们。当 worker A 设置了 `impersonate="chrome"` + `proxy="http://a:8080"`，下一个被分配到同一 worker 进程的请求 B 会继承这些全局状态，即使 B 的 `setup_from_context()` 会重新设置，但如果 B 是匿名请求（无 proxy），`os.environ["HTTPS_PROXY"]` 仍残留 A 的值。

### bilibili-api 官方文档要点

**配置项**（`request_settings`）：

| 设置 | 类型 | 默认值 | curl_cffi | aiohttp | httpx |
|------|------|--------|-----------|---------|-------|
| proxy | str | `""` | ✅ | ✅ | ✅ |
| timeout | float | `30.0` | ✅ | ✅ | ✅ |
| impersonate | str | `""` | ✅ | ❌ | ❌ |
| http2 | bool | `False` | ✅ | ❌ | ✅ |
| wbi_retry_times | int | `3` | — | — | — |
| enable_auto_buvid | bool | `True` | — | — | — |

**切换请求库**：`select_client("curl_cffi")` — 全局生效，必须在子进程中调用以实现隔离。

**代理设置**：两种方式等价：
- `request_settings.set_proxy("http://...")` — 全局
- `Credential(..., proxy="http://...")` — 跟随凭据

**impersonate 设置**：`request_settings.set("impersonate", "chrome")` — 仅 curl_cffi 支持。

---

## 设计目标

1. **每请求独立子进程**：每次 `run_in_subprocess` 调用 spawn 全新子进程，确保进程级完全隔离
2. **子进程生命周期管理**：超时 terminate + join 兜底，防止僵尸进程
3. 提供 `BilibiliSubprocessContext` TypedDict，统一描述子进程执行上下文
4. worker 函数只需关注业务逻辑，环境初始化由 `setup_from_context()` 完成
5. 所有 B 站 API 请求路径统一走此框架

---

## 架构概览

### 子进程执行模型

```
FastAPI 主进程（async）
  │
  ├─ POST /bilibili/credential/check
  │    └─ run_in_subprocess(check_credential_worker, param)
  │         └─ spawn Process → worker 执行 → Queue 回传结果 → join
  │
  ├─ POST /bilibili/video/get-info/{bvid}
  │    └─ run_in_subprocess(get_info_worker, param)
  │         └─ spawn Process → ...
  │
  └─ WebSocket /bilibili/uploader/sync-task
       └─ TaskEndpointInSubProcess（独立生命周期管理）
            └─ spawn Process → Queue 双向通信 → cancel/resume Event
```

### 两种子进程模式

| 模式 | 适用场景 | 实现 |
|------|---------|------|
| `run_in_subprocess` | 简单请求-响应（POST 端点） | 每请求 spawn → Queue 回传 → timeout terminate |
| `TaskEndpointInSubProcess` | WebSocket 长任务（sync-task） | spawn → Queue 双向 → cancel/resume Event → 流式推送 |

---

## 核心模块：`_common.py`

**文件：** `fredica_pyutil_server/subprocess/bilibili/_common.py`

### `run_in_subprocess(fn, *args, timeout=180.0)`

每次调用 spawn 独立子进程执行 `fn(*args)`，通过 `multiprocessing.Queue` 回传结果 dict。

```python
_mp_ctx = multiprocessing.get_context("spawn")

async def run_in_subprocess(fn, *args, timeout: float = 180.0) -> dict:
    result_queue = _mp_ctx.Queue()

    def _wrapper(*a):
        try:
            result = fn(*a)
            result_queue.put(result)
        except Exception as e:
            result_queue.put({"error": repr(e)})

    proc = _mp_ctx.Process(target=_wrapper, args=args, daemon=True)
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, proc.start)

    deadline = loop.time() + timeout
    while proc.is_alive():
        if loop.time() > deadline:
            proc.terminate()
            proc.join(timeout=2)
            return {"error": f"子进程超时 ({timeout}s)"}
        await asyncio.sleep(0.05)

    proc.join(timeout=1)
    try:
        return result_queue.get_nowait()
    except queue_mod.Empty:
        return {"error": f"子进程异常退出 (exitcode={proc.exitcode})"}
```

**设计决策：**
- `daemon=True`：父进程退出时自动清理
- `Queue` 而非 `Pipe`：子进程崩溃时不会 broken pipe
- `_wrapper` 捕获异常：确保崩溃时仍有 error 信息
- 默认 180s 超时：弱网环境下 B 站 API 响应可能较慢
- `proc.start()` 在 executor 中：spawn 模式启动需数百毫秒，不阻塞事件循环

### `setup_from_context(ctx)`

从 `BilibiliSubprocessContext` dict 初始化子进程环境：清除残留代理 → `select_client("curl_cffi")` → 设置 impersonate → 设置 proxy。

### `make_credential_from_context(ctx)`

从 context 构建 `Credential` 对象，匿名时返回 `None`。

### `init_worker()`

子进程通用初始化：loguru 重绑定。

---

## Worker 函数模式

所有 worker 遵循统一模式：

```python
def some_worker(param: dict) -> dict:
    init_worker()
    ctx = param["context"]
    setup_from_context(ctx)
    credential = make_credential_from_context(ctx)
    # ... 业务逻辑，使用 asyncio.run() 调用 bilibili-api async 方法
```

---

## 端点清单

| 路由文件 | 端点 | 执行模式 | 状态 |
|---------|------|---------|------|
| `bilibili_credential.py` | check, try-refresh, get-account-info | `run_in_subprocess` | ✅ 完成 |
| `bilibili_video.py` | get-info, get-pages, ai-conclusion, subtitle-meta | `run_in_subprocess` | ✅ 完成 |
| `bilibili_video.py` | subtitle-body | httpx 直连 CDN | 不需要子进程 |
| `bilibili_video.py` | download-task | `TaskEndpointInEventLoopThread` | 保持不变 |
| `bilibili_uploader.py` | get-info, get-page | `run_in_subprocess` | ✅ 完成 |
| `bilibili_uploader.py` | sync-task | `TaskEndpointInSubProcess` (L1/L2) | ✅ 完成 |
| `bilibili_season.py` | get-meta, get-page | `run_in_subprocess` | ✅ 完成 |
| `bilibili_series.py` | get-meta, get-page | `run_in_subprocess` | ✅ 完成 |
| `bilibili_favorite.py` | get-video-list, get-info, get-page | `run_in_subprocess` | ✅ 完成 |
| `bilibili_ip.py` | check | `run_in_subprocess` | ✅ 完成 |

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `subprocess/bilibili/_common.py` | 公共初始化 + `run_in_subprocess` 执行器 |
| `subprocess/bilibili/_context.py` | `BilibiliSubprocessContext` TypedDict 定义 |
| `subprocess/bilibili/credential.py` | 凭据相关 worker（3 个） |
| `subprocess/bilibili/video.py` | 视频相关 worker（4 个） |
| `subprocess/bilibili/uploader.py` | UP主相关 worker（2 个） |
| `subprocess/bilibili/season.py` | 合集相关 worker（2 个） |
| `subprocess/bilibili/series.py` | 系列相关 worker（2 个） |
| `subprocess/bilibili/favorite.py` | 收藏夹相关 worker（3 个） |
| `subprocess/bilibili/ip.py` | IP 检测 worker（1 个） |
| `subprocess/bilibili_uploader_sync.py` | UP主同步 L1/L2 子进程架构 |

---

## 验证

1. Python 服务启动无报错
2. 所有端点功能正常（credential check / video info / uploader sync 等）
3. 并发测试：同时发起多个不同代理配置的请求，确认无全局状态污染
4. 前端账号池页面：各按钮正常工作
5. Kotlin 编译通过：`./gradlew :shared:build`
