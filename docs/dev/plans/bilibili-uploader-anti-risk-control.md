---
title: B站UP主同步 — 反风控子进程体系设计
order: 530
---

# B站UP主同步 — 反风控子进程体系设计

## 1. 背景与问题

### 1.1 现状

当前 `bilibili/uploader/get-page` 是简单的 REST 端点，Kotlin Executor 通过 `FredicaApi.PyUtil.post()` 逐页请求。对于投稿量大的 UP 主（数百页），短时间内高频请求会触发 B 站 412 风控：

```
bilibili_api.exceptions.NetworkException: 网络错误，状态码：412
```

返回内容为 B 站安全验证 HTML 页面（含 `acw_sc__v2` 反爬脚本），非 JSON，导致同步直接失败。

### 1.2 目标

1. **账号池抓取**：支持多账号（含"匿名"虚拟账号），每个账号独立配置代理和速率。默认使用匿名+代理+快速；触发 412 后自动切换到下一个可用账号（登录态+各自代理/速率设置）
2. **WebSocket 长任务**：降级模式下同步耗时可达数分钟，需支持进度推送、暂停/恢复/取消
3. **子进程隔离**：`bilibili-api-python` 的 `Credential(proxy=...)` 会设置全局代理环境变量，两种模式必须在独立进程中运行

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  Kotlin: MaterialCategorySyncBilibiliUploaderExecutor        │
│  (WebSocketTaskExecutor)                                     │
│                                                              │
│  websocketTask("/bilibili/uploader/sync-task", paramJson,    │
│    onProgress, cancelSignal, pauseChannel, resumeChannel)    │
│                                                              │
│  paramJson 含 accounts: [                                    │
│    { label, credential?, proxy?, rate_limit_sec },           │
│    ...                                                       │
│  ]                                                           │
└──────────────────────┬──────────────────────────────────────┘
                       │ WebSocket
┌──────────────────────▼──────────────────────────────────────┐
│  Python: BilibiliUploaderSyncTaskEndpoint                    │
│  (TaskEndpointInSubProcess)                                  │
│                                                              │
│  子进程（调度器）职责：                                        │
│  - 管理账号池状态和翻页进度                                    │
│  - 为每个账号 spawn 子子进程执行实际 API 调用                   │
│  - 处理 412 → 终止当前子子进程 → spawn 下一个账号的子子进程     │
│  - 汇聚子子进程消息，推送给父进程 → WebSocket → Kotlin          │
└──────────────────────┬──────────────────────────────────────┘
                       │ multiprocessing (spawn)
                       │ IPC: status_queue + cancel_event + resume_event
┌──────────────────────▼──────────────────────────────────────┐
│  子进程（调度器）: _bilibili_uploader_sync_worker()           │
│                                                              │
│  accounts = param["accounts"]                                │
│  current_account_idx = 0                                     │
│  current_page = 1                                            │
│                                                              │
│  while not done:                                             │
│    spawn 子子进程 → _bilibili_fetch_worker(account, page)    │
│    读取 fetch_queue 消息:                                     │
│      page_result → 转发给 status_queue, page++               │
│      412_error   → 终止子子进程, account_idx++, 重新 spawn   │
│      other_error → 转发给 status_queue                       │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ 子子进程: _bilibili_fetch_worker()                     │  │
│  │                                                        │  │
│  │ 进程级全局隔离：                                        │  │
│  │ - select_client() 仅影响本进程                          │  │
│  │ - request_settings.set_proxy() 仅影响本进程             │  │
│  │ - os.environ["HTTPS_PROXY"] 仅影响本进程               │  │
│  │                                                        │  │
│  │ 职责：                                                  │  │
│  │ - 进程启动时一次性配置 client/proxy/credential          │  │
│  │ - 循环抓取指定页码范围的视频列表                         │  │
│  │ - 通过 fetch_queue 推送 page 结果或 412 错误            │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 为什么需要子子进程（全局状态隔离）

`bilibili-api-python` 有三个进程级全局状态：

| 全局 API | 影响范围 | 文档 |
|----------|---------|------|
| `select_client(ClientType)` | 整个进程的所有请求使用的 HTTP 客户端 | [request_client.md](https://nemo2011.github.io/bilibili-api/#/request_client) |
| `request_settings.set_proxy(url)` | 整个进程的所有匿名请求的代理 | [configuration.md](https://nemo2011.github.io/bilibili-api/#/configuration) |
| `os.environ["HTTPS_PROXY"]` | 整个进程的环境变量代理 | Python 标准行为 |

`Credential(proxy=...)` 仅对该 Credential 实例的已认证请求生效，**不影响匿名请求**。因此在同一进程内切换账号（修改环境变量 + 重建 Credential）无法保证全局状态的一致性——上一个账号的 `select_client()` 和 `request_settings` 残留会污染下一个账号的请求。

**结论**：每个账号必须运行在独立进程中，进程启动时一次性配置所有全局状态，整个生命周期内不再修改。

### 2.2 三层进程模型

| 层级 | 进程 | 职责 | 生命周期 |
|------|------|------|---------|
| L0 | FastAPI 主进程 | WebSocket 端点，转发 Kotlin 命令 | 常驻 |
| L1 | 调度器子进程 | 账号池管理、翻页状态、412 切换决策 | 一次同步任务 |
| L2 | 抓取子子进程 | 单账号 API 调用，全局状态隔离 | 一个账号的使用周期（412 触发时终止） |

L1 调度器不直接调用 `bilibili-api-python`，因此不受全局状态影响。L2 抓取进程在启动时配置好全局状态后，只做单一账号的 API 调用。

### 2.3 为什么不用多个 L2 并行

账号池中的账号是串行使用的（当前账号触发 412 后才切换到下一个），不需要并行。L1 调度器同一时刻只运行一个 L2 抓取进程。

---

## 3. Python 端设计

### 3.1 文件结构

```
fredica_pyutil_server/
├── routes/bilibili_uploader.py          # 保留原有 REST 端点 + 新增 WebSocket 端点
└── subprocess/bilibili_uploader_sync.py # 新建：L1 调度器 + L2 抓取 worker
```

### 3.2 L2 抓取子子进程（全局隔离）

`subprocess/bilibili_uploader_sync.py` 中的模块级函数：

```python
def _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event):
    """
    L2 子子进程：单账号抓取 worker。

    进程启动时一次性配置全局状态，之后只做 API 调用。
    每个进程只服务一个账号，412 触发时由 L1 调度器终止本进程并 spawn 新进程。

    param 字段：
        mid:            str    UP主 UID
        start_page:     int    起始页码（从上次中断处继续）
        old_cursor:     int    增量同步 pubdate 阈值
        account:        dict   当前账号配置：
            label:          str    显示名
            credential:     dict?  B站登录凭据（null = 匿名）
            proxy:          str    代理地址（空串 = 直连）
            rate_limit_sec: float  请求间隔秒数

    fetch_queue 消息类型（L2 → L1）：
        {"type": "page",  "page": int, "videos": list, "has_more": bool, "total_count": int}
        {"type": "error", "error": str, "status": int?}   # status=412 表示风控
        {"type": "done"}                                    # 正常结束（无更多页或到达 cursor）
    """
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    from loguru import logger
    init_loguru(logger=logger)

    account = param["account"]
    mid = param["mid"]
    old_cursor = param.get("old_cursor", 0)
    start_page = param.get("start_page", 1)
    proxy = account.get("proxy", "")
    rate_limit_sec = account.get("rate_limit_sec", 1.0)
    cred_dict = account.get("credential")

    # ── 进程启动时一次性配置全局状态 ──
    import os
    os.environ.pop("HTTPS_PROXY", None)
    os.environ.pop("HTTP_PROXY", None)

    if proxy:
        os.environ["HTTPS_PROXY"] = proxy
        os.environ["HTTP_PROXY"] = proxy

    from bilibili_api import Credential, request_settings
    from bilibili_api.user import User, VideoOrder

    if proxy:
        request_settings.set_proxy(proxy)

    credential = None
    if cred_dict:
        credential = Credential(
            sessdata=cred_dict.get("sessdata"),
            bili_jct=cred_dict.get("bili_jct"),
            buvid3=cred_dict.get("buvid3"),
            buvid4=cred_dict.get("buvid4"),
            dedeuserid=cred_dict.get("dedeuserid"),
            ac_time_value=cred_dict.get("ac_time_value"),
            proxy=proxy or None,
        )

    # ── 抓取循环 ──
    import asyncio
    import time
    from bilibili_api.exceptions import NetworkException

    page = start_page

    while True:
        if cancel_event.is_set():
            return
        resume_event.wait()
        if cancel_event.is_set():
            return

        try:
            loop = asyncio.new_event_loop()
            try:
                user = User(uid=int(mid), credential=credential)
                result = loop.run_until_complete(
                    user.get_videos(pn=page, ps=30, order=VideoOrder.PUBDATE)
                )
            finally:
                loop.close()

            vlist = result.get("list", {}).get("vlist", [])
            page_info = result.get("page", {})
            total_count = page_info.get("count", 0)
            has_more = page * 30 < total_count

            # 增量同步：检查是否到达 cursor
            reached_cursor = False
            if old_cursor > 0:
                filtered = []
                for v in vlist:
                    if v.get("created", 0) <= old_cursor:
                        reached_cursor = True
                        break
                    filtered.append(v)
                vlist = filtered
                if reached_cursor:
                    has_more = False

            fetch_queue.put({
                "type": "page", "page": page,
                "videos": vlist, "has_more": has_more,
                "total_count": total_count,
            })

            if not has_more or reached_cursor:
                fetch_queue.put({"type": "done"})
                return

        except NetworkException as e:
            fetch_queue.put({
                "type": "error",
                "error": str(e),
                "status": getattr(e, "status", None),
            })
            if getattr(e, "status", None) == 412:
                return  # L1 调度器会处理账号切换
            return  # 其他网络错误也终止

        except Exception as e:
            fetch_queue.put({"type": "error", "error": str(e), "status": None})
            return

        page += 1
        time.sleep(rate_limit_sec)
```

### 3.3 L1 调度器子进程

```python
def _bilibili_uploader_sync_worker(param, status_queue, cancel_event, resume_event):
    """
    L1 调度器子进程：管理账号池和 L2 抓取子子进程。

    不直接 import bilibili-api-python，不受全局状态影响。

    param 字段：
        mid:          str   UP主 UID
        old_cursor:   int   上次同步的 pubdate 时间戳
        accounts:     list  账号池列表（按优先级排序）

    status_queue 消息类型（L1 → L0/Kotlin）：
        {"type": "log",            "level": str, "message": str}
        {"type": "progress",       "percent": int}
        {"type": "page",           "page": int, "videos": list, "has_more": bool, "total_count": int}
        {"type": "account_switch", "from": str, "to": str, "reason": str}
        {"type": "done",           "total_pages": int, "total_videos": int}
        {"type": "error",          "error": str}
    """
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    from loguru import logger
    init_loguru(logger=logger)

    import multiprocessing
    import queue

    mid = param["mid"]
    old_cursor = param.get("old_cursor", 0)
    accounts = param["accounts"]

    if not accounts:
        status_queue.put({"type": "error", "error": "未配置任何B站账号"})
        return

    def _log(level, msg):
        status_queue.put({"type": "log", "level": level, "message": msg})

    mp_ctx = multiprocessing.get_context("spawn")
    account_idx = 0
    current_page = 1
    total_videos = 0
    total_count = None

    while account_idx < len(accounts):
        if cancel_event.is_set():
            _log("info", "收到取消信号")
            break

        acct = accounts[account_idx]
        _log("info",
             f"使用账号「{acct['label']}」"
             f"({'登录态' if acct.get('credential') else '匿名'}) "
             f"proxy={acct.get('proxy') or '直连'} "
             f"rate={acct.get('rate_limit_sec', 1.0)}s "
             f"从第 {current_page} 页开始")

        # ── spawn L2 子子进程 ──
        fetch_queue = mp_ctx.Queue()
        fetch_cancel = mp_ctx.Event()
        fetch_resume = mp_ctx.Event()
        fetch_resume.set()  # 初始为运行状态

        fetch_param = {
            "mid": mid,
            "start_page": current_page,
            "old_cursor": old_cursor,
            "account": acct,
        }

        proc = mp_ctx.Process(
            target=_bilibili_fetch_worker,
            args=(fetch_param, fetch_queue, fetch_cancel, fetch_resume),
        )
        proc.start()

        got_412 = False
        fetch_done = False

        while not fetch_done:
            # 检查 L0 取消信号
            if cancel_event.is_set():
                fetch_cancel.set()
                proc.join(timeout=5)
                if proc.is_alive():
                    proc.terminate()
                break

            # 传递暂停/恢复信号
            if not resume_event.is_set():
                fetch_resume.clear()
            else:
                fetch_resume.set()

            # 读取 L2 消息
            try:
                msg = fetch_queue.get(timeout=0.2)
            except queue.Empty:
                if not proc.is_alive():
                    fetch_done = True
                continue

            msg_type = msg.get("type")

            if msg_type == "page":
                videos = msg.get("videos", [])
                total_videos += len(videos)
                if total_count is None:
                    total_count = msg.get("total_count", 0)

                # 转发给 Kotlin
                status_queue.put(msg)

                if total_count and total_count > 0:
                    pct = min(99, total_videos * 100 // total_count)
                    status_queue.put({"type": "progress", "percent": pct})

                current_page = msg["page"] + 1

                if not msg.get("has_more", False):
                    fetch_done = True

            elif msg_type == "error":
                status_code = msg.get("status")
                if status_code == 412:
                    got_412 = True
                    fetch_done = True
                    _log("warning",
                         f"账号「{acct['label']}」触发 412 风控 page={current_page}")
                else:
                    status_queue.put(msg)
                    fetch_done = True

            elif msg_type == "done":
                fetch_done = True

        # 等待 L2 进程退出
        proc.join(timeout=5)
        if proc.is_alive():
            proc.terminate()
            proc.join(timeout=2)

        if cancel_event.is_set():
            break

        if got_412:
            next_idx = account_idx + 1
            if next_idx < len(accounts):
                old_label = acct["label"]
                new_label = accounts[next_idx]["label"]
                status_queue.put({
                    "type": "account_switch",
                    "from": old_label, "to": new_label,
                    "reason": "412 风控",
                })
                account_idx = next_idx
                continue  # 从 current_page 继续
            else:
                # 所有账号耗尽，尝试最后一个账号重试 3 次
                _log("error", "所有账号均触发 412 风控")
                retries = _retry_last_account(
                    mp_ctx, acct, mid, current_page, old_cursor,
                    status_queue, cancel_event, resume_event,
                )
                if retries is not None:
                    total_videos += retries["added"]
                    current_page = retries["next_page"]
                else:
                    status_queue.put({"type": "error", "error": "所有账号均触发风控，同步终止"})
                    return
                break
        else:
            break  # 正常完成或非 412 错误

    status_queue.put({
        "type": "done",
        "total_pages": current_page - 1,
        "total_videos": total_videos,
    })


def _retry_last_account(mp_ctx, acct, mid, page, old_cursor,
                         status_queue, cancel_event, resume_event):
    """最后一个账号 412 后，等待 10s 重试最多 3 次。成功返回 dict，失败返回 None。"""
    import queue as queue_mod
    import time

    for attempt in range(3):
        if cancel_event.is_set():
            return None
        status_queue.put({
            "type": "log", "level": "info",
            "message": f"等待 10s 后重试（第 {attempt + 1}/3 次）",
        })
        time.sleep(10)
        if cancel_event.is_set():
            return None

        fetch_queue = mp_ctx.Queue()
        fetch_cancel = mp_ctx.Event()
        fetch_resume = mp_ctx.Event()
        fetch_resume.set()

        proc = mp_ctx.Process(
            target=_bilibili_fetch_worker,
            args=({"mid": mid, "start_page": page, "old_cursor": old_cursor, "account": acct},
                  fetch_queue, fetch_cancel, fetch_resume),
        )
        proc.start()

        added = 0
        next_page = page
        success = False
        got_412_again = False

        while True:
            if cancel_event.is_set():
                fetch_cancel.set()
                proc.join(timeout=5)
                if proc.is_alive():
                    proc.terminate()
                return None

            try:
                msg = fetch_queue.get(timeout=0.2)
            except queue_mod.Empty:
                if not proc.is_alive():
                    break
                continue

            if msg.get("type") == "page":
                added += len(msg.get("videos", []))
                next_page = msg["page"] + 1
                status_queue.put(msg)
                if not msg.get("has_more", False):
                    success = True
            elif msg.get("type") == "error" and msg.get("status") == 412:
                got_412_again = True
                break
            elif msg.get("type") == "done":
                success = True
                break
            elif msg.get("type") == "error":
                status_queue.put(msg)
                break

        proc.join(timeout=5)
        if proc.is_alive():
            proc.terminate()

        if success:
            return {"added": added, "next_page": next_page}
        if got_412_again:
            continue

    return None
```

### 3.4 WebSocket 端点

在 `routes/bilibili_uploader.py` 中新增：

```python
from starlette.websockets import WebSocket
from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
    BilibiliUploaderSyncTaskEndpoint,
)

@_router.websocket("/sync-task")
async def sync_task_endpoint(websocket: WebSocket):
    endpoint = BilibiliUploaderSyncTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()
```

### 3.5 TaskEndpoint 实现

```python
class BilibiliUploaderSyncTaskEndpoint(TaskEndpointInSubProcess):
    """
    UP主投稿同步 WebSocket 长任务。

    L0（FastAPI 主进程）→ L1（调度器子进程）→ L2（抓取子子进程）。
    本类只管理 L1 生命周期，L2 由 L1 内部管理。

    init_param_and_run data 格式：
        {
            "mid":        str,
            "old_cursor": int,
            "accounts":   list[{label, credential?, proxy, rate_limit_sec}],
        }

    WebSocket 推送消息（由 L1 汇聚后转发）：
        {"type": "progress",       "percent": int}
        {"type": "page",           "page": int, "videos": [...], "has_more": bool, "total_count": int}
        {"type": "account_switch", "from": str, "to": str, "reason": str}
        {"type": "done",           "total_pages": int, "total_videos": int}
        {"type": "error",          "error": str}
    """

    async def _on_subprocess_message(self, msg):
        if isinstance(msg, dict):
            msg_type = msg.get("type")
            if msg_type == "log":
                level = msg.get("level", "info")
                getattr(logger, level, logger.info)(msg.get("message", ""))
                return
        self._current_status = msg
        await self.send_json(msg)

    def _get_process_target(self):
        return _bilibili_uploader_sync_worker
```

### 3.6 保留原有 REST 端点

`get-page` 和 `get-info` REST 端点保持不变，供其他场景（如前端预览、单页查询）使用。WebSocket 端点是新增的，不影响现有功能。

---

## 4. Kotlin 端设计

### 4.1 Executor 改造

`MaterialCategorySyncBilibiliUploaderExecutor` 从 `TaskExecutor` 改为继承 `WebSocketTaskExecutor`：

```kotlin
object MaterialCategorySyncBilibiliUploaderExecutor : WebSocketTaskExecutor() {
    override val taskType = "SYNC_BILIBILI_UPLOADER"

    override suspend fun executeWithSignals(
        task: Task,
        cancelSignal: CompletableDeferred<Unit>,
        pauseResumeChannels: TaskPauseResumeChannels,
    ): ExecuteResult = withContext(Dispatchers.IO) {
        // 1. 解析 payload，获取 platformInfo + config
        // 2. 从 BilibiliAccountPoolService 获取账号池
        // 3. 构建 paramJson（含 mid, old_cursor, accounts[]）
        // 4. 调用 websocketTask()
        // 5. 处理返回结果，更新 DB

        val proxyUrl = AppProxyService.readProxyUrl()
        val accountPool = BilibiliAccountPoolService.buildAccountList(proxyUrl)

        val paramJson = buildJsonObject {
            put("mid", config.mid.toString())
            put("old_cursor", oldCursor)
            putJsonArray("accounts") {
                accountPool.forEach { acct ->
                    addJsonObject {
                        put("label", acct.label)
                        put("proxy", acct.proxy)
                        put("rate_limit_sec", acct.rateLimitSec)
                        if (acct.credential != null) {
                            put("credential", buildJsonObject {
                                put("sessdata", acct.credential.sessdata)
                                put("bili_jct", acct.credential.biliJct)
                                put("buvid3", acct.credential.buvid3)
                                put("buvid4", acct.credential.buvid4)
                                put("dedeuserid", acct.credential.dedeuserid)
                                put("ac_time_value", acct.credential.acTimeValue)
                            })
                        }
                    }
                }
            }
        }.toString()

        val result = PythonUtil.Py314Embed.PyUtilServer.websocketTask(
            pth = "/bilibili/uploader/sync-task",
            paramJson = paramJson,
            onProgress = { pct -> TaskService.repo.updateProgress(task.id, pct) },
            onRawMessage = { msg -> handleRawMessage(msg, platformInfo, categoryId, oldCursor) },
            cancelSignal = cancelSignal,
            pauseChannel = pauseResumeChannels.pause,
            resumeChannel = pauseResumeChannels.resume,
        )
        // ... 处理最终结果
    }
}
```

### 4.2 消息处理

Kotlin 端需要处理子进程推送的 `page` 消息，实时将视频数据写入 DB：

```kotlin
private suspend fun handleRawMessage(
    msg: JsonObject,
    platformInfo: MaterialCategorySyncPlatformInfo,
    categoryId: String,
    oldCursor: Long,
) {
    when (msg["type"]?.jsonPrimitive?.content) {
        "page" -> {
            val videos = msg["videos"]?.jsonArray ?: return
            // 解析视频列表，upsert MaterialVideo + SyncItem + linkMaterials
            // （复用现有的解析逻辑）
        }
        "account_switch" -> {
            logger.info("SyncBilibiliUploader: 账号切换 ${msg["from"]} → ${msg["to"]}: ${msg["reason"]}")
        }
        "done" -> {
            // 更新 syncCursor, itemCount, syncState
        }
    }
}
```

### 4.3 数据处理策略

**方案 A（推荐）：子进程只负责抓取，Kotlin 负责数据入库**

子进程通过 `page` 消息将原始视频列表推送给父进程 → WebSocket → Kotlin。Kotlin 端在 `onRawMessage` 回调中解析并写入 DB。

优点：
- 数据入库逻辑保持在 Kotlin 端，与现有代码一致
- 子进程职责单一（只做 HTTP 抓取 + 风控处理）
- cursor 管理、增量判断等逻辑不需要在 Python 端重复实现

缺点：
- WebSocket 消息体较大（每页 30 个视频的完整 JSON）

**方案 B：子进程负责抓取 + 入库**

子进程直接调用 DB 写入。不推荐，因为 Python 端没有 Kotlin 的 DB 访问能力。

**结论：采用方案 A。**

### 4.4 `onRawMessage` 回调

`PythonUtil.websocketTask()` 已有 `onRawMessage: (suspend (String) -> Unit)?` 参数（注意类型是 `String`，非 `JsonObject`），可直接使用。Kotlin 端在回调中自行解析 JSON：

```kotlin
onRawMessage = { raw ->
    val msg = AppUtil.GlobalVars.json.parseToJsonElement(raw).jsonObject
    handleRawMessage(msg, platformInfo, categoryId, oldCursor)
}
```

无需修改 `PythonUtil`。

---

## 5. B站账号池设计

### 5.1 代理来源

从 `AppProxyService.readProxyUrl()` 获取。优先级：AppConfig 用户配置 > 系统代理。每个账号可独立配置代理（覆盖全局代理），也可留空使用直连。

### 5.2 账号池模型

当前 AppConfig 中 B 站凭据是单组字段（`bilibili_sessdata` 等）。需要重构为**账号池列表**，存储在 AppConfig 的新字段 `bilibili_accounts_json` 中：

```kotlin
@Serializable
data class BilibiliAccount(
    val id: String,                    // UUID
    val label: String,                 // 显示名（如 "匿名"、"我的大号"、"小号A"）
    val isAnonymous: Boolean = false,  // true = 无登录态的虚拟账号
    val isDefault: Boolean = false,    // 是否为默认账号（用于非同步场景）
    // 登录凭据（isAnonymous=true 时全部为空）
    val sessdata: String = "",
    val biliJct: String = "",
    val buvid3: String = "",
    val buvid4: String = "",
    val dedeuserid: String = "",
    val acTimeValue: String = "",
    // 独立网络配置
    val proxy: String = "",            // 空串 = 使用全局代理（匿名）或直连（登录态）
    val rateLimitSec: Double = 1.0,    // 请求间隔（匿名默认 0.1，登录态默认 3.0）
)
```

AppConfig 新增字段：

```kotlin
@SerialName("bilibili_accounts_json") val bilibiliAccountsJson: String = "[]",
```

> **迁移兼容**：若 `bilibili_accounts_json` 为空但旧字段 `bilibili_sessdata` 非空，自动迁移为一个匿名账号 + 一个登录态账号。

### 5.3 账号池初始化

#### Proxy 常量

| 常量值 | 含义 |
|--------|------|
| `"USE_APP"` | **运行时由 `AppProxyService.readProxyUrl()` 解析**——取 AppConfig 代理设置，若为空则取系统代理 |
| `""` （空串） | 直连，不使用任何代理 |
| 其他字符串 | 用户显式指定的代理地址（如 `http://127.0.0.1:7890`） |

Kotlin 侧定义常量：

```kotlin
object BilibiliAccountPoolConstants {
    /** 运行时由 AppProxyService 填充的代理占位符 */
    const val PROXY_USE_APP = "USE_APP"
}
```

#### 默认匿名账号

系统默认创建一个"匿名"虚拟账号（`isAnonymous=true`），其行为等同于旧 Mode A：

```json
{
    "id": "anonymous",
    "label": "匿名",
    "isAnonymous": true,
    "proxy": "USE_APP",
    "rateLimitSec": 0.1
}
```

匿名账号的 `proxy` 默认为 `"USE_APP"`，即跟随应用全局代理设置。用户也可在设置页面将其改为空串（直连）或显式代理地址。

#### 登录态账号

用户可在设置页面添加多个登录态账号，每个账号独立配置：
- 登录凭据（sessdata 等）
- 代理地址（`"USE_APP"` / 空串 / 显式地址，默认 `"USE_APP"`）
- 速率限制（默认 3.0s）

### 5.4 BilibiliAccountPoolService

```kotlin
object BilibiliAccountPoolService {
    /**
     * 构建同步任务使用的账号列表（有序）。
     *
     * 顺序：匿名账号 → 各登录态账号（按配置顺序）。
     * proxy 字段中的 PROXY_USE_APP 在此处解析为实际代理地址。
     */
    suspend fun buildAccountList(): List<BilibiliAccountEntry> {
        val accounts = loadAccounts()
        return accounts.map { acct ->
            val resolvedProxy = resolveProxy(acct.proxy)
            if (acct.isAnonymous) {
                BilibiliAccountEntry(
                    label = acct.label,
                    credential = null,
                    proxy = resolvedProxy,
                    rateLimitSec = acct.rateLimitSec,
                )
            } else {
                BilibiliAccountEntry(
                    label = acct.label,
                    credential = BilibiliCredentialEntry(
                        sessdata = acct.sessdata,
                        biliJct = acct.biliJct,
                        buvid3 = acct.buvid3,
                        buvid4 = acct.buvid4,
                        dedeuserid = acct.dedeuserid,
                        acTimeValue = acct.acTimeValue,
                    ),
                    proxy = resolvedProxy,
                    rateLimitSec = acct.rateLimitSec,
                )
            }
        }
    }

    private suspend fun resolveProxy(proxy: String): String = when (proxy) {
        BilibiliAccountPoolConstants.PROXY_USE_APP -> AppProxyService.readProxyUrl() ?: ""
        else -> proxy  // 空串 = 直连，其他 = 显式代理地址
    }
}
```

### 5.5 账号健康检测

每个账号（含匿名）支持两项检测，通过 Python 端点实现：

| 检测项 | 端点 | 说明 |
|--------|------|------|
| **可用性检测** | `POST /bilibili/credential/check` | 登录态：`check_valid()` 返回是否有效；匿名：发一个轻量请求（如 `User.get_user_info()`）验证网络可达 |
| **IP 检测** | `POST /bilibili/credential/check-ip` | 通过该账号的代理设置发请求，返回出口 IP 地址 |

前端设置页面提供：
- **批量检测可用性**：遍历账号池，逐个调用 check，显示 ✅/❌ 状态
- **批量检测 IP**：遍历账号池，逐个调用 check-ip，显示各账号的出口 IP

### 5.6 安全考虑

凭据通过 WebSocket `init_param_and_run` 消息传递，仅在本地 localhost 通信，不经过外部网络。

---

## 6. 速率控制策略

每个账号独立配置 `rateLimitSec`，不再使用全局固定速率。推荐默认值：

| 账号类型 | 默认速率 | 代理 | 凭据 | 说明 |
|---------|---------|------|------|------|
| 匿名 | 0.1s/req (10 req/s) | 全局代理 | 无 | 高速抓取，依赖代理 IP 分散风控 |
| 登录态 | 3.0s/req | 各自配置 | 有 | 保守速率，保护账号安全 |

用户可在设置页面为每个账号单独调整速率。

速率控制在 L2 抓取子子进程内部通过 `time.sleep(rate_limit_sec)` 实现。账号切换时 L1 调度器 spawn 新的 L2 进程，新进程使用新账号的速率设置。

### 6.1 412 重试策略

- 当前账号触发 412 后，**不重试当前页**，直接切换到账号池中的下一个账号并从当前页重新开始
- 最后一个账号触发 412 后，等待 10s 后重试当前页，最多重试 3 次
- 3 次重试均失败后，报错终止

### 6.2 进度计算

首页请求返回的 `page.count` 字段即为该 UP 主的总投稿数，可直接用于精确计算进度：

```python
# 首页获取 total_count
result = _run_async(user.get_videos(pn=1, ps=30, order=VideoOrder.PUBDATE))
total_count = result.get("page", {}).get("count", 0)
total_pages = math.ceil(total_count / 30)

# 每完成一页推送进度
percent = min(99, fetched_videos * 100 // total_count) if total_count > 0 else 0
```

- 每完成一页推送 `{"type": "page", ..., "total_count": total_count}`
- 进度百分比：`percent = min(99, fetched / total_count * 100)`
- `total_count == 0` 时进度固定为 0，直接完成

---

## 7. 错误处理

| 场景 | 处理 |
|------|------|
| 当前账号 412 | L2 进程退出，L1 终止 L2 → spawn 新 L2（下一个账号），推送 `account_switch` 消息 |
| 最后一个账号 412 | L1 等待 10s → spawn 新 L2 重试，最多 3 次 |
| 网络超时 | L2 内部重试 2 次，间隔 5s |
| 凭据无效 | L2 报错退出，L1 跳过该账号尝试下一个；全部无效则报错 `"所有账号凭据均无效"` |
| 账号池为空 | L1 立即报错 `"未配置任何B站账号"` |
| L2 进程崩溃 | L1 检测到 `proc.is_alive() == False`，读取剩余消息后决定是否切换账号 |
| L1 进程崩溃 | `TaskEndpointInSubProcess` 框架自动检测并推送 error |
| 用户取消 | L0 → `cancel_event` → L1 → `fetch_cancel` → L2，逐级终止 |

---

## 8. 实现步骤

### Phase 1: B站账号池基础设施

> **目标**：账号池数据模型 + CRUD 路由 + 前端设置页面。不涉及同步逻辑。

#### 1.1 Kotlin 数据模型（TDD）

**测试文件**：`shared/src/jvmTest/kotlin/.../material_category/BilibiliAccountPoolServiceTest.kt`

| 测试 | 验证点 |
|------|--------|
| `ap1_parse_empty_json` | `"[]"` → 空列表 |
| `ap2_parse_single_anonymous` | 匿名账号解析，`isAnonymous=true`，无 credential |
| `ap3_parse_mixed_accounts` | 匿名 + 登录态混合列表，字段完整性 |
| `ap4_build_account_list_resolves_proxy` | `PROXY_USE_APP` → 实际代理地址；空串 → 空串 |
| `ap5_build_account_list_ordering` | 匿名账号排在前面，登录态按配置顺序 |
| `ap6_migration_from_legacy_fields` | 旧 `bilibili_sessdata` 等字段 → 自动生成一个匿名 + 一个登录态账号 |

**实现文件**：
- `shared/src/commonMain/.../apputil/BilibiliAccount.kt` — `BilibiliAccount` 数据类 + `BilibiliAccountPoolConstants`
- `shared/src/commonMain/.../apputil/BilibiliAccountPoolService.kt` — `buildAccountList()`、`parseAccounts()`、`migrateLegacy()`

#### 1.2 CRUD 路由

**路由**（注册到 `all_routes.kt`）：

| 路由 | 方法 | 功能 |
|------|------|------|
| `BilibiliAccountListRoute` | GET | 返回账号池列表（脱敏：sessdata 只显示前 4 位） |
| `BilibiliAccountSaveRoute` | POST | 新增/更新账号 |
| `BilibiliAccountDeleteRoute` | POST | 删除账号 |

路由实现简单（读写 AppConfig 的 `bilibili_accounts_json` 字段），不需要独立测试——通过前端集成验证。

#### 1.3 Python 端点

| 端点 | 功能 | 备注 |
|------|------|------|
| `POST /bilibili/credential/check-ip` | 通过指定代理发请求，返回出口 IP | 新增 |
| `POST /bilibili/credential/check` | 已有，验证凭据有效性 | 无需修改 |

#### 1.4 前端设置页面

**文件**：`fredica-webui/app/routes/app-desktop-setting-bilibili-account-pool-config.tsx`（已完成）

功能：账号池列表管理、批量检测可用性/IP、旧字段迁移、代理模式选择。

#### 1.5 AppConfig 字段

`AppConfig.kt` 新增 `bilibiliAccountsJson` 字段（`@SerialName("bilibili_accounts_json")`）。

---

### Phase 2: Python L2 抓取子子进程（TDD）

> **目标**：实现 `_bilibili_fetch_worker()`，可独立测试，不依赖 L1 调度器。

**测试文件**：`desktop_assets/common/fredica-pyutil/tests/test_bilibili_fetch_worker.py`

测试策略：mock `bilibili_api.user.User.get_videos()` 返回预设数据，通过 `multiprocessing.Queue` 收集消息验证。

| 测试 | 验证点 |
|------|--------|
| `fw1_single_page_anonymous` | 匿名账号单页抓取，`fetch_queue` 收到 `page` + `done` |
| `fw2_multi_page_pagination` | 多页翻页，每页收到 `page` 消息，最后收到 `done` |
| `fw3_incremental_cursor` | `old_cursor > 0` 时，到达 cursor 的视频被过滤，提前 `done` |
| `fw4_412_sends_error_with_status` | 模拟 `NetworkException(status=412)`，`fetch_queue` 收到 `{"type": "error", "status": 412}` |
| `fw5_other_network_error` | 非 412 网络错误，`fetch_queue` 收到 `{"type": "error", "status": 500}` |
| `fw6_cancel_event_stops` | 设置 `cancel_event` 后，worker 退出，不再发送消息 |
| `fw7_proxy_env_set_for_anonymous` | 匿名 + proxy → 验证 `os.environ["HTTPS_PROXY"]` 被设置 |
| `fw8_credential_created_for_login` | 登录态账号 → 验证 `Credential` 构造参数正确 |
| `fw9_rate_limit_respected` | 验证页间间隔 ≥ `rate_limit_sec` |

**实现文件**：`desktop_assets/common/fredica-pyutil/fredica_pyutil_server/subprocess/bilibili_uploader_sync.py`

实现 `_bilibili_fetch_worker()` 函数（见 Section 3.2）。

---

### Phase 3: Python L1 调度器子进程（TDD）

> **目标**：实现 `_bilibili_uploader_sync_worker()`，验证账号切换和 L2 生命周期管理。

**测试文件**：`desktop_assets/common/fredica-pyutil/tests/test_bilibili_sync_scheduler.py`

测试策略：mock `_bilibili_fetch_worker`（替换为测试用 worker 函数），通过 `status_queue` 收集消息。

| 测试 | 验证点 |
|------|--------|
| `sc1_single_account_full_sync` | 单账号正常同步，`status_queue` 收到 `page` × N + `done` |
| `sc2_412_triggers_account_switch` | 第一个账号 412 → `account_switch` 消息 → 第二个账号继续从同一页 |
| `sc3_412_all_accounts_exhausted` | 所有账号 412 → 重试 3 次 → `error` 消息 |
| `sc4_cancel_terminates_l2` | 设置 `cancel_event` → L2 进程被终止 → 调度器退出 |
| `sc5_progress_aggregation` | 验证 `progress` 消息的百分比计算正确 |
| `sc6_empty_account_pool` | 空账号池 → 立即 `error` |
| `sc7_non_412_error_stops` | L2 返回非 412 错误 → 调度器不切换账号，直接转发错误 |
| `sc8_page_continuity_after_switch` | 账号切换后，`start_page` 从中断页继续 |

**实现文件**：同 `bilibili_uploader_sync.py`

实现 `_bilibili_uploader_sync_worker()` + `_retry_last_account()` + `BilibiliUploaderSyncTaskEndpoint`（见 Section 3.3-3.5）。

---

### Phase 4: WebSocket 端点集成

> **目标**：将 L1 调度器接入 WebSocket 端点，端到端验证 Python 侧。

**实现**：
1. 在 `routes/bilibili_uploader.py` 新增 `@_router.websocket("/sync-task")` 端点
2. `BilibiliUploaderSyncTaskEndpoint` 继承 `TaskEndpointInSubProcess`，`_get_process_target()` 返回 `_bilibili_uploader_sync_worker`

**验证**：手动测试——启动 Python 服务，用 WebSocket 客户端发送 `init_param_and_run` 消息，观察消息流。

---

### Phase 5: Kotlin Executor 改造（TDD）

> **目标**：`MaterialCategorySyncBilibiliUploaderExecutor` 从 REST `TaskExecutor` 改为 `WebSocketTaskExecutor`。

**测试文件**：`shared/src/jvmTest/kotlin/.../material_category/MaterialCategorySyncBilibiliUploaderExecutorTest.kt`（更新现有测试）

现有 8 个测试（eu1-eu8）通过 `ApiClient` 接口 mock REST 调用。改造后需要 mock WebSocket 消息流。

测试策略：引入 `MessageSource` 接口（替代 `ApiClient`），测试通过预设消息序列模拟 Python 端推送。

| 测试 | 验证点 | 对应旧测试 |
|------|--------|-----------|
| `eu1_full_sync_from_messages` | 多页 `page` 消息 → DB 写入 MaterialVideo + SyncItem + linkMaterials | eu1 |
| `eu2_incremental_sync_cursor` | `page` 消息中 `has_more=false` → 更新 syncCursor | eu2 |
| `eu3_sync_success_resets_state` | `done` 消息 → syncState="idle", failCount=0 | eu3 |
| `eu4_error_message_sets_failed` | `error` 消息 → syncState="failed", lastError 记录 | eu4 |
| `eu5_material_records_created` | `page` 消息中的视频 → MaterialVideo 字段正确 | eu5 |
| `eu6_display_name_from_info` | 同步前调用 `get-info` REST 端点获取 UP 主名 | eu6 |
| `eu7_info_failure_non_blocking` | `get-info` 失败不影响同步 | eu7 |
| `eu8_display_name_updated` | 已有 displayName 时仍更新 | eu8 |
| `eu9_account_switch_logged` | `account_switch` 消息 → 日志记录 | 新增 |
| `eu10_empty_account_pool_error` | 账号池为空 → ExecuteResult error | 新增 |

**实现改造**：
1. `MaterialCategorySyncBilibiliUploaderExecutor` 改为 `object : WebSocketTaskExecutor()`
2. 实现 `executeWithSignals()`：构建 paramJson（含 accounts）→ `websocketTask()` → `onRawMessage` 处理 `page`/`account_switch`/`done`/`error`
3. `handleRawMessage()` 复用现有的视频解析 + DB 写入逻辑
4. 同步前仍通过 REST `get-info` 获取 UP 主名（保持现有行为）

---

### Phase 6: 端到端集成验证

> **目标**：真实环境验证完整链路。

**验证清单**：

| 场景 | 验证方式 |
|------|---------|
| 匿名 + 代理全量同步 | 配置匿名账号 + 代理，触发同步，观察进度和 DB 数据 |
| 增量同步 | 全量同步后再次触发，验证只同步新视频 |
| 412 账号切换 | 匿名快速抓取触发 412 → 自动切换到登录态账号 |
| 暂停/恢复 | 同步过程中暂停 → 恢复 → 正常完成 |
| 取消 | 同步过程中取消 → L2 进程终止 → 状态正确 |
| 前端进度展示 | WorkflowInfoPanel 显示实时进度百分比 |

---

### 实现顺序与依赖

```
Phase 1 (账号池) ─────────────────────────────────────────┐
                                                           │
Phase 2 (L2 抓取 worker) ──┐                               │
                            ├─ Phase 3 (L1 调度器) ──┐     │
Phase 4.4 确认 onRawMessage ┘                         │     │
已存在，无需修改                                       │     │
                                                      ├─ Phase 4 (WebSocket 端点)
                                                      │     │
                                                      └─ Phase 5 (Kotlin Executor)
                                                            │
                                                            └─ Phase 6 (集成验证)
```

Phase 1 和 Phase 2 可并行开发。Phase 3 依赖 Phase 2（L2 worker 函数）。Phase 5 依赖 Phase 1（账号池服务）和 Phase 4（WebSocket 端点可用）。

---

## 9. 与现有系统的兼容性

- **其他同步类型不受影响**：`bilibili_favorite`、`bilibili_season`、`bilibili_series`、`bilibili_video_pages` 仍使用现有的 REST + TaskExecutor 模式
- **前端无需修改**：`handleSyncTrigger` 和 `WorkflowInfoPanel` 已支持 WebSocket 长任务的进度展示
- **WorkflowRun 兼容**：`MaterialCategorySyncTriggerService` 创建的 WorkflowRun 不区分 REST/WebSocket 任务类型

---

## 10. 未来扩展

- **其他同步类型的风控处理**：若 `bilibili_favorite` 等也遇到 412，可复用相同的账号池子进程架构
- **自适应速率**：根据连续成功请求数动态调整匿名账号的速率（从 10 req/s 逐步提升）
- **账号健康度评分**：根据历史 412 触发频率、凭据过期次数等自动排序账号优先级
- **定时凭据刷新**：后台定期调用 `try-refresh` 端点，自动续期即将过期的凭据
