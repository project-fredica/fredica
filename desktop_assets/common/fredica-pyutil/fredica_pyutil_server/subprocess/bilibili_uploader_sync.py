# -*- coding: UTF-8 -*-
"""
B站UP主同步 — L1 调度器 + L2 抓取 worker + WebSocket 端点。

L2: _bilibili_fetch_worker()  — 单账号抓取子子进程，进程级全局隔离
L1: _bilibili_uploader_sync_worker() — 调度器子进程，管理账号池和 L2 生命周期
L0: BilibiliUploaderSyncTaskEndpoint — WebSocket 端点（FastAPI 主进程）
"""

import asyncio
import multiprocessing
import queue as queue_mod
import time

from bilibili_api.exceptions import NetworkException
from bilibili_api.user import User


def _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event):
    """
    L2 子子进程：单账号抓取 worker。

    进程启动时一次性配置全局状态，之后只做 API 调用。
    每个进程只服务一个账号，412 触发时由 L1 调度器终止本进程并 spawn 新进程。

    param 字段：
        mid:            str    UP主 UID
        start_page:     int    起始页码
        old_cursor:     int    增量同步 pubdate 阈值
        account:        dict   当前账号配置：
            label:          str    显示名
            credential:     dict?  B站登录凭据（null = 匿名）
            proxy:          str    代理地址（空串 = 直连）
            impersonate:    str    浏览器 TLS 指纹（空串 = 不设置）
            rate_limit_sec: float  请求间隔秒数

    fetch_queue 消息类型（L2 → L1）：
        {"type": "page",  "page": int, "videos": list, "has_more": bool, "total_count": int}
        {"type": "error", "error": str, "status": int?}
        {"type": "done"}
    """
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    from loguru import logger
    init_loguru(logger=logger)

    from fredica_pyutil_server.subprocess.bilibili._common import (
        setup_from_context, make_credential_from_context,
    )

    account = param["account"]
    mid = param["mid"]
    old_cursor = param.get("old_cursor", 0)
    start_page = param.get("start_page", 1)
    rate_limit_sec = account.get("rate_limit_sec", 1.0)

    ctx = {
        "credential": account.get("credential"),
        "proxy": account.get("proxy", ""),
        "impersonate": account.get("impersonate", ""),
    }
    setup_from_context(ctx)
    credential = make_credential_from_context(ctx)

    page = start_page

    logger.debug("[L2] fetch_worker start: mid={} start_page={} old_cursor={} account={} rate_limit={}s",
                 mid, start_page, old_cursor, account.get("label", "?"), rate_limit_sec)

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
                    user.get_videos(pn=page, ps=30)
                )
            finally:
                loop.close()

            vlist = result.get("list", {}).get("vlist", [])
            page_info = result.get("page", {})
            total_count = page_info.get("count", 0)
            has_more = page * 30 < total_count

            logger.debug("[L2] page={} vlist_count={} total_count={} has_more={}", page, len(vlist), total_count, has_more)

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
                    logger.debug("[L2] reached_cursor at page={} filtered_count={}", page, len(filtered))

            fetch_queue.put({
                "type": "page",
                "page": page,
                "videos": vlist,
                "has_more": has_more,
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
            return

        except Exception as e:
            fetch_queue.put({"type": "error", "error": str(e), "status": None})
            return

        page += 1
        time.sleep(rate_limit_sec)


# ---------------------------------------------------------------------------
# L2 runner 抽象：生产用 Process，测试用 Thread
# ---------------------------------------------------------------------------

class _L2Runner:
    """运行 L2 worker 并提供统一的 join/terminate/is_alive 接口。"""

    def __init__(self, target, args, use_thread=False):
        self._use_thread = use_thread
        if use_thread:
            import threading
            self._impl = threading.Thread(target=target, args=args, daemon=True)
        else:
            mp_ctx = multiprocessing.get_context("spawn")
            self._impl = mp_ctx.Process(target=target, args=args)

    def start(self):
        self._impl.start()

    def join(self, timeout=None):
        self._impl.join(timeout=timeout)

    def is_alive(self):
        return self._impl.is_alive()

    def terminate(self):
        if self._use_thread:
            pass
        else:
            self._impl.terminate()


def _make_queue_and_events(use_thread=False):
    """创建 fetch_queue + cancel/resume Event，测试模式用 threading 原语。"""
    if use_thread:
        import threading
        import queue as q
        return q.Queue(), threading.Event(), threading.Event()
    else:
        mp_ctx = multiprocessing.get_context("spawn")
        return mp_ctx.Queue(), mp_ctx.Event(), mp_ctx.Event()


# ---------------------------------------------------------------------------
# L1 调度器子进程
# ---------------------------------------------------------------------------

def _bilibili_uploader_sync_worker(param, status_queue, cancel_event, resume_event,
                                    _fetch_worker_fn=None):
    """
    L1 调度器子进程：管理账号池和 L2 抓取子子进程。

    param 字段：
        mid:          str   UP主 UID
        old_cursor:   int   上次同步的 pubdate 时间戳
        accounts:     list  账号池列表（按优先级排序）

    _fetch_worker_fn: 可选，测试时注入替代 worker（使用 Thread 而非 Process）。
    """
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    from loguru import logger
    init_loguru(logger=logger)

    use_thread = _fetch_worker_fn is not None
    if _fetch_worker_fn is None:
        _fetch_worker_fn = _bilibili_fetch_worker

    mid = param["mid"]
    old_cursor = param.get("old_cursor", 0)
    accounts = param["accounts"]

    logger.debug("[L1] sync_worker start: mid={} old_cursor={} account_count={}", mid, old_cursor, len(accounts))

    if not accounts:
        status_queue.put({"type": "error", "error": "未配置任何B站账号"})
        return

    def _log(level, msg):
        status_queue.put({"type": "log", "level": level, "message": msg})

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

        fetch_queue, fetch_cancel, fetch_resume = _make_queue_and_events(use_thread)
        fetch_resume.set()

        fetch_param = {
            "mid": mid,
            "start_page": current_page,
            "old_cursor": old_cursor,
            "account": acct,
        }

        runner = _L2Runner(
            target=_fetch_worker_fn,
            args=(fetch_param, fetch_queue, fetch_cancel, fetch_resume),
            use_thread=use_thread,
        )
        runner.start()

        got_412 = False
        fetch_done = False

        while not fetch_done:
            if cancel_event.is_set():
                fetch_cancel.set()
                runner.join(timeout=5)
                if runner.is_alive():
                    runner.terminate()
                break

            if not resume_event.is_set():
                fetch_resume.clear()
            else:
                fetch_resume.set()

            try:
                msg = fetch_queue.get(timeout=0.2)
            except queue_mod.Empty:
                if not runner.is_alive():
                    fetch_done = True
                continue

            msg_type = msg.get("type")

            if msg_type == "page":
                videos = msg.get("videos", [])
                total_videos += len(videos)
                if total_count is None:
                    total_count = msg.get("total_count", 0)

                logger.debug("[L1] page msg: page={} video_count={} total_videos={} total_count={}",
                             msg.get("page"), len(videos), total_videos, total_count)

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

        runner.join(timeout=5)
        if runner.is_alive():
            runner.terminate()
            runner.join(timeout=2)

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
                continue
            else:
                _log("error", "所有账号均触发 412 风控")
                retries = _retry_last_account(
                    acct, mid, current_page, old_cursor,
                    status_queue, cancel_event, resume_event,
                    _fetch_worker_fn=_fetch_worker_fn,
                    _use_thread=use_thread,
                )
                if retries is not None:
                    total_videos += retries["added"]
                    current_page = retries["next_page"]
                else:
                    status_queue.put({"type": "error", "error": "所有账号均触发风控，同步终止"})
                    return
                break
        else:
            break

    status_queue.put({
        "type": "done",
        "total_pages": current_page - 1,
        "total_videos": total_videos,
    })
    logger.debug("[L1] sync_worker done: mid={} total_pages={} total_videos={}", mid, current_page - 1, total_videos)


def _retry_last_account(acct, mid, page, old_cursor,
                         status_queue, cancel_event, resume_event,
                         _fetch_worker_fn=None, _use_thread=False):
    """最后一个账号 412 后，等待 10s 重试最多 3 次。成功返回 dict，失败返回 None。"""
    if _fetch_worker_fn is None:
        _fetch_worker_fn = _bilibili_fetch_worker

    for attempt in range(3):
        if cancel_event.is_set():
            return None
        status_queue.put({
            "type": "log", "level": "info",
            "message": f"等待 10s 后重试（第 {attempt + 1}/3 次）",
        })
        logger.debug("[L1] _retry_last_account: attempt={}/3 page={}", attempt + 1, page)
        time.sleep(10)
        if cancel_event.is_set():
            return None

        fetch_queue, fetch_cancel, fetch_resume = _make_queue_and_events(_use_thread)
        fetch_resume.set()

        runner = _L2Runner(
            target=_fetch_worker_fn,
            args=({"mid": mid, "start_page": page, "old_cursor": old_cursor, "account": acct},
                  fetch_queue, fetch_cancel, fetch_resume),
            use_thread=_use_thread,
        )
        runner.start()

        added = 0
        next_page = page
        success = False
        got_412_again = False

        while True:
            if cancel_event.is_set():
                fetch_cancel.set()
                runner.join(timeout=5)
                if runner.is_alive():
                    runner.terminate()
                return None

            try:
                msg = fetch_queue.get(timeout=0.2)
            except queue_mod.Empty:
                if not runner.is_alive():
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

        runner.join(timeout=5)
        if runner.is_alive():
            runner.terminate()

        if success:
            return {"added": added, "next_page": next_page}
        if got_412_again:
            continue

    return None


# ---------------------------------------------------------------------------
# TaskEndpoint 实现
# ---------------------------------------------------------------------------

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess


class BilibiliUploaderSyncTaskEndpoint(TaskEndpointInSubProcess):
    """
    UP主投稿同步 WebSocket 长任务。

    L0（FastAPI 主进程）→ L1（调度器子进程）→ L2（抓取子子进程）。
    """

    def _get_process_target(self):
        return _bilibili_uploader_sync_worker

    async def _on_subprocess_message(self, msg):
        from loguru import logger
        if isinstance(msg, dict):
            msg_type = msg.get("type")
            if msg_type == "log":
                level = msg.get("level", "info")
                getattr(logger, level, logger.info)(msg.get("message", ""))
                return
        self._current_status = msg
        await self.send_json(msg)
