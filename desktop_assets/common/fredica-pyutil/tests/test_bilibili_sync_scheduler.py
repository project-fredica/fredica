# -*- coding: UTF-8 -*-
"""
Phase 3: L1 调度器 _bilibili_uploader_sync_worker 单元测试。

运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_bilibili_sync_scheduler.py -v

测试矩阵：
  sc1. 单账号正常同步 → status_queue 收到 page × N + done
  sc2. 第一个账号 412 → account_switch → 第二个账号继续
  sc3. 所有账号 412 → 重试 3 次 → error
  sc4. cancel_event → L2 终止 → 调度器退出
  sc5. progress 百分比计算正确
  sc6. 空账号池 → 立即 error
  sc7. 非 412 错误 → 不切换账号，转发 error
  sc8. 账号切换后 start_page 从中断页继续
"""
import time
from unittest.mock import patch

import pytest

from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
    _bilibili_uploader_sync_worker,
)


def _drain_queue(q, timeout=5.0):
    """从 queue 中读取所有消息直到超时。"""
    import queue as queue_mod
    msgs = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            msgs.append(q.get(timeout=0.1))
        except queue_mod.Empty:
            if msgs and msgs[-1].get("type") in ("done", "error"):
                break
    return msgs


def _anonymous_account(label="匿名", proxy="", rate_limit_sec=0.0):
    return {
        "label": label,
        "credential": None,
        "proxy": proxy,
        "rate_limit_sec": rate_limit_sec,
    }


def _login_account(label="登录账号", sessdata="sess", bili_jct="jct",
                   proxy="", rate_limit_sec=0.0):
    return {
        "label": label,
        "credential": {
            "sessdata": sessdata,
            "bili_jct": bili_jct,
            "buvid3": "bv3",
            "buvid4": "bv4",
            "dedeuserid": "123",
            "ac_time_value": "ac",
        },
        "proxy": proxy,
        "rate_limit_sec": rate_limit_sec,
    }


# ---------------------------------------------------------------------------
# Test worker functions that simulate L2 behavior
# ---------------------------------------------------------------------------

def _worker_two_pages_ok(param, fetch_queue, cancel_event, resume_event):
    """Simulate L2: 2 pages of videos, then done."""
    for pn in range(1, 3):
        if cancel_event.is_set():
            return
        fetch_queue.put({
            "type": "page",
            "page": pn,
            "videos": [{"bvid": f"BV{pn}_{i}", "created": 1000 - pn * 100 - i}
                        for i in range(3)],
            "has_more": pn < 2,
            "total_count": 6,
        })
    fetch_queue.put({"type": "done"})


def _worker_single_page_ok(param, fetch_queue, cancel_event, resume_event):
    """Simulate L2: 1 page of 2 videos, then done."""
    if cancel_event.is_set():
        return
    fetch_queue.put({
        "type": "page",
        "page": param.get("start_page", 1),
        "videos": [{"bvid": f"BV_sp_{i}", "created": 500 - i} for i in range(2)],
        "has_more": False,
        "total_count": 2,
    })
    fetch_queue.put({"type": "done"})


def _make_worker_412(param, fetch_queue, cancel_event, resume_event):
    """Simulate L2: immediate 412 error."""
    fetch_queue.put({"type": "error", "error": "风控", "status": 412})


def _make_worker_500(param, fetch_queue, cancel_event, resume_event):
    """Simulate L2: immediate 500 error."""
    fetch_queue.put({"type": "error", "error": "Server Error", "status": 500})


def _make_worker_slow_then_cancel(param, fetch_queue, cancel_event, resume_event):
    """Simulate L2: wait until cancelled."""
    while not cancel_event.is_set():
        time.sleep(0.05)


# Factory that returns different workers based on account label
def _make_worker_factory(worker_map):
    """
    Returns a worker function that dispatches to different behaviors
    based on account label.
    """
    def _dispatch_worker(param, fetch_queue, cancel_event, resume_event):
        label = param["account"]["label"]
        worker_fn = worker_map.get(label, _worker_single_page_ok)
        return worker_fn(param, fetch_queue, cancel_event, resume_event)
    return _dispatch_worker


# Worker that sends one page then 412 on second page
def _make_worker_page_then_412(param, fetch_queue, cancel_event, resume_event):
    """Send page 1 successfully, then 412 on page 2 attempt."""
    sp = param.get("start_page", 1)
    if sp == 1:
        fetch_queue.put({
            "type": "page",
            "page": 1,
            "videos": [{"bvid": f"BVp1_{i}", "created": 1000 - i} for i in range(3)],
            "has_more": True,
            "total_count": 6,
        })
        # Simulate: L2 tries page 2 and gets 412
        fetch_queue.put({"type": "error", "error": "风控", "status": 412})
    else:
        # Continuation from page 2 by second account
        fetch_queue.put({
            "type": "page",
            "page": sp,
            "videos": [{"bvid": f"BVp2_{i}", "created": 800 - i} for i in range(3)],
            "has_more": False,
            "total_count": 6,
        })
        fetch_queue.put({"type": "done"})


# ---------------------------------------------------------------------------
# sc1: 单账号正常同步
# ---------------------------------------------------------------------------

class TestSc1SingleAccountFullSync:
    def test_single_account_full_sync(self):
        """单账号正常同步，status_queue 收到 page × 2 + done。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [_anonymous_account()],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=_worker_two_pages_ok,
        )

        msgs = _drain_queue(status_queue)
        page_msgs = [m for m in msgs if m["type"] == "page"]
        done_msgs = [m for m in msgs if m["type"] == "done"]

        assert len(page_msgs) == 2
        assert page_msgs[0]["page"] == 1
        assert page_msgs[1]["page"] == 2
        assert len(done_msgs) == 1
        assert done_msgs[0]["total_videos"] == 6
        assert done_msgs[0]["total_pages"] == 2


# ---------------------------------------------------------------------------
# sc2: 412 触发账号切换
# ---------------------------------------------------------------------------

class TestSc2AccountSwitchOn412:
    def test_412_triggers_account_switch(self):
        """第一个账号 412 → account_switch → 第二个账号继续。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        dispatch = _make_worker_factory({
            "匿名A": _make_worker_412,
            "登录B": _worker_two_pages_ok,
        })

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [
                _anonymous_account(label="匿名A"),
                _login_account(label="登录B"),
            ],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=dispatch,
        )

        msgs = _drain_queue(status_queue)
        switch_msgs = [m for m in msgs if m["type"] == "account_switch"]
        page_msgs = [m for m in msgs if m["type"] == "page"]
        done_msgs = [m for m in msgs if m["type"] == "done"]

        assert len(switch_msgs) == 1
        assert switch_msgs[0]["from"] == "匿名A"
        assert switch_msgs[0]["to"] == "登录B"
        assert len(page_msgs) == 2
        assert len(done_msgs) == 1


# ---------------------------------------------------------------------------
# sc3: 所有账号 412 → 重试 3 次 → error
# ---------------------------------------------------------------------------

class TestSc3AllAccountsExhausted:
    def test_all_accounts_412_then_retry_fails(self):
        """所有账号 412，重试 3 次后 error。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [
                _anonymous_account(label="匿名A"),
                _anonymous_account(label="匿名B"),
            ],
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.time.sleep",
        ):
            _bilibili_uploader_sync_worker(
                param, status_queue, cancel_event, resume_event,
                _fetch_worker_fn=_make_worker_412,
            )

        msgs = _drain_queue(status_queue)
        error_msgs = [m for m in msgs if m["type"] == "error"]
        switch_msgs = [m for m in msgs if m["type"] == "account_switch"]

        assert len(switch_msgs) == 1  # A → B
        assert len(error_msgs) == 1
        assert "风控" in error_msgs[0]["error"]


# ---------------------------------------------------------------------------
# sc4: cancel_event 终止
# ---------------------------------------------------------------------------

class TestSc4CancelTerminatesL2:
    def test_cancel_stops_scheduler(self):
        """设置 cancel_event → 调度器退出。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()
        cancel_event.set()  # pre-set cancel

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [_anonymous_account()],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=_worker_two_pages_ok,
        )

        msgs = _drain_queue(status_queue, timeout=1.0)
        page_msgs = [m for m in msgs if m["type"] == "page"]
        assert len(page_msgs) == 0


# ---------------------------------------------------------------------------
# sc5: progress 百分比计算
# ---------------------------------------------------------------------------

class TestSc5ProgressAggregation:
    def test_progress_percentage(self):
        """验证 progress 消息的百分比计算。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [_anonymous_account()],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=_worker_two_pages_ok,
        )

        msgs = _drain_queue(status_queue)
        progress_msgs = [m for m in msgs if m["type"] == "progress"]

        assert len(progress_msgs) >= 1
        # After page 1: 3 videos out of 6 total = 50%
        assert progress_msgs[0]["percent"] == 50
        # After page 2: 6 videos out of 6 total = min(99, 100) = 99
        if len(progress_msgs) >= 2:
            assert progress_msgs[1]["percent"] == 99


# ---------------------------------------------------------------------------
# sc6: 空账号池
# ---------------------------------------------------------------------------

class TestSc6EmptyAccountPool:
    def test_empty_accounts_immediate_error(self):
        """空账号池 → 立即 error。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=_worker_single_page_ok,
        )

        msgs = _drain_queue(status_queue, timeout=1.0)
        assert len(msgs) == 1
        assert msgs[0]["type"] == "error"
        assert "未配置" in msgs[0]["error"]


# ---------------------------------------------------------------------------
# sc7: 非 412 错误不切换账号
# ---------------------------------------------------------------------------

class TestSc7Non412ErrorStops:
    def test_non_412_error_forwarded(self):
        """非 412 错误 → 不切换账号，转发 error。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        dispatch = _make_worker_factory({
            "匿名A": _make_worker_500,
            "登录B": _worker_two_pages_ok,
        })

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [
                _anonymous_account(label="匿名A"),
                _login_account(label="登录B"),
            ],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=dispatch,
        )

        msgs = _drain_queue(status_queue)
        error_msgs = [m for m in msgs if m["type"] == "error"]
        switch_msgs = [m for m in msgs if m["type"] == "account_switch"]
        page_msgs = [m for m in msgs if m["type"] == "page"]

        # Non-412 error should be forwarded, no account switch
        assert len(error_msgs) >= 1
        assert error_msgs[0]["status"] == 500 or "Server Error" in error_msgs[0].get("error", "")
        assert len(switch_msgs) == 0
        assert len(page_msgs) == 0  # Second account never used


# ---------------------------------------------------------------------------
# sc8: 账号切换后 start_page 从中断页继续
# ---------------------------------------------------------------------------

class TestSc8PageContinuityAfterSwitch:
    def test_page_continuity(self):
        """账号切换后，start_page 从中断页继续。"""
        import queue as q
        import threading

        status_queue = q.Queue()
        cancel_event = threading.Event()
        resume_event = threading.Event()
        resume_event.set()

        # Account A: sends page 1 OK, then 412 on page 2
        # Account B: continues from page 2
        dispatch = _make_worker_factory({
            "匿名A": _make_worker_page_then_412,
            "登录B": _make_worker_page_then_412,  # reuses same fn, dispatches by start_page
        })

        param = {
            "mid": "12345",
            "old_cursor": 0,
            "accounts": [
                _anonymous_account(label="匿名A"),
                _login_account(label="登录B"),
            ],
        }

        _bilibili_uploader_sync_worker(
            param, status_queue, cancel_event, resume_event,
            _fetch_worker_fn=dispatch,
        )

        msgs = _drain_queue(status_queue)
        page_msgs = [m for m in msgs if m["type"] == "page"]
        switch_msgs = [m for m in msgs if m["type"] == "account_switch"]

        # Account A sent page 1, then 412
        # Account B should start from page 2 (current_page was advanced to 2 after page 1)
        assert len(switch_msgs) == 1
        assert len(page_msgs) >= 2
        assert page_msgs[0]["page"] == 1  # from account A
        assert page_msgs[1]["page"] == 2  # from account B, continuing
