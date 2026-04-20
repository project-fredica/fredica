# -*- coding: UTF-8 -*-
"""
Phase 2: L2 抓取子子进程 _bilibili_fetch_worker 单元测试。

运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_bilibili_fetch_worker.py -v

测试矩阵：
  fw1. 匿名账号单页抓取 → fetch_queue 收到 page + done
  fw2. 多页翻页 → 每页收到 page 消息，最后收到 done
  fw3. 增量同步 old_cursor → 到达 cursor 的视频被过滤，提前 done
  fw4. 模拟 412 → fetch_queue 收到 {"type": "error", "status": 412}
  fw5. 非 412 网络错误 → fetch_queue 收到 {"type": "error", "status": 500}
  fw6. cancel_event → worker 退出，不再发送消息
  fw7. 匿名 + proxy → os.environ["HTTPS_PROXY"] 被设置
  fw8. 登录态账号 → Credential 构造参数正确
  fw9. 页间间隔 ≥ rate_limit_sec

  fw_real_1. 真实网络匿名抓取第 1 页（需 @pytest.mark.network）
"""
import multiprocessing
import os
import time
from unittest.mock import patch, MagicMock, AsyncMock

import pytest


def _drain_queue(q, timeout=2.0):
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


def _make_vlist(bvids_and_dates):
    """构造 bilibili API 格式的 vlist。"""
    return [
        {
            "bvid": bvid,
            "title": f"视频 {bvid}",
            "created": created,
            "length": "05:00",
            "pic": f"https://example.com/{bvid}.jpg",
            "description": "",
            "author": "测试UP主",
            "mid": 12345,
        }
        for bvid, created in bvids_and_dates
    ]


def _make_api_result(vlist, total_count, pn=1, ps=30):
    """构造 User.get_videos() 返回格式。"""
    return {
        "list": {"vlist": vlist},
        "page": {"count": total_count, "pn": pn, "ps": ps},
    }


def _anonymous_account(proxy="", rate_limit_sec=0.0):
    return {
        "label": "匿名",
        "credential": None,
        "proxy": proxy,
        "rate_limit_sec": rate_limit_sec,
    }


def _login_account(sessdata="test_sessdata", bili_jct="test_jct",
                   proxy="", rate_limit_sec=0.0):
    return {
        "label": "测试账号",
        "credential": {
            "sessdata": sessdata,
            "bili_jct": bili_jct,
            "buvid3": "buvid3_val",
            "buvid4": "buvid4_val",
            "dedeuserid": "12345",
            "ac_time_value": "ac_val",
        },
        "proxy": proxy,
        "rate_limit_sec": rate_limit_sec,
    }


# ---------------------------------------------------------------------------
# fw1: 匿名账号单页抓取
# ---------------------------------------------------------------------------

class TestFw1SinglePageAnonymous:
    def test_single_page_anonymous(self):
        """匿名账号单页抓取，fetch_queue 收到 page + done。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        vlist = _make_vlist([("BV1aaa", 1000), ("BV1bbb", 900)])
        api_result = _make_api_result(vlist, total_count=2)

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = AsyncMock(return_value=api_result)
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue)
        assert len(msgs) == 2
        assert msgs[0]["type"] == "page"
        assert msgs[0]["page"] == 1
        assert len(msgs[0]["videos"]) == 2
        assert msgs[0]["has_more"] is False
        assert msgs[1]["type"] == "done"


# ---------------------------------------------------------------------------
# fw2: 多页翻页
# ---------------------------------------------------------------------------

class TestFw2MultiPagePagination:
    def test_multi_page(self):
        """多页翻页，每页收到 page 消息，最后收到 done。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        page1_vlist = _make_vlist([("BV1p1a", 1000), ("BV1p1b", 900)])
        page2_vlist = _make_vlist([("BV1p2a", 800)])

        call_count = {"n": 0}

        async def mock_get_videos(pn=1, ps=30, order=None):
            call_count["n"] += 1
            if pn == 1:
                return _make_api_result(page1_vlist, total_count=31, pn=1)
            else:
                return _make_api_result(page2_vlist, total_count=31, pn=2)

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = mock_get_videos
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue)
        page_msgs = [m for m in msgs if m["type"] == "page"]
        assert len(page_msgs) == 2
        assert page_msgs[0]["page"] == 1
        assert page_msgs[0]["has_more"] is True
        assert page_msgs[1]["page"] == 2
        assert page_msgs[1]["has_more"] is False
        assert msgs[-1]["type"] == "done"


# ---------------------------------------------------------------------------
# fw3: 增量同步 old_cursor
# ---------------------------------------------------------------------------

class TestFw3IncrementalCursor:
    def test_cursor_filters_old_videos(self):
        """old_cursor > 0 时，到达 cursor 的视频被过滤，提前 done。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        vlist = _make_vlist([
            ("BV1new", 1000),
            ("BV1old", 800),   # <= cursor=900, should be filtered
            ("BV1older", 700),
        ])
        api_result = _make_api_result(vlist, total_count=10)

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = AsyncMock(return_value=api_result)
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 900,
            "account": _anonymous_account(),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue)
        page_msg = msgs[0]
        assert page_msg["type"] == "page"
        assert len(page_msg["videos"]) == 1  # only BV1new
        assert page_msg["videos"][0]["bvid"] == "BV1new"
        assert page_msg["has_more"] is False
        assert msgs[-1]["type"] == "done"


# ---------------------------------------------------------------------------
# fw4: 412 风控
# ---------------------------------------------------------------------------

class TestFw4Error412:
    def test_412_sends_error_with_status(self):
        """模拟 NetworkException(status=412)，fetch_queue 收到 error。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )
        from bilibili_api.exceptions import NetworkException

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = AsyncMock(
            side_effect=NetworkException(status=412, msg="风控")
        )
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue)
        assert len(msgs) == 1
        assert msgs[0]["type"] == "error"
        assert msgs[0]["status"] == 412


# ---------------------------------------------------------------------------
# fw5: 非 412 网络错误
# ---------------------------------------------------------------------------

class TestFw5OtherNetworkError:
    def test_non_412_error(self):
        """非 412 网络错误，fetch_queue 收到 error。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )
        from bilibili_api.exceptions import NetworkException

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = AsyncMock(
            side_effect=NetworkException(status=500, msg="Server Error")
        )
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue)
        assert len(msgs) == 1
        assert msgs[0]["type"] == "error"
        assert msgs[0]["status"] == 500


# ---------------------------------------------------------------------------
# fw6: cancel_event 停止
# ---------------------------------------------------------------------------

class TestFw6CancelEvent:
    def test_cancel_stops_worker(self):
        """设置 cancel_event 后，worker 退出，不发送 page 消息。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()
        cancel_event.set()  # pre-set cancel

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(),
        }

        # No mock needed — should exit before any API call
        _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue, timeout=0.5)
        assert len(msgs) == 0


# ---------------------------------------------------------------------------
# fw7: 匿名 + proxy → 环境变量
# ---------------------------------------------------------------------------

class TestFw7ProxyEnvSet:
    def test_proxy_env_set_for_anonymous(self):
        """匿名 + proxy → os.environ["HTTPS_PROXY"] 被设置。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        captured_env = {}

        vlist = _make_vlist([("BV1env", 500)])
        api_result = _make_api_result(vlist, total_count=1)

        original_get_videos = None

        async def capture_env_get_videos(pn=1, ps=30, order=None):
            captured_env["HTTPS_PROXY"] = os.environ.get("HTTPS_PROXY", "")
            captured_env["HTTP_PROXY"] = os.environ.get("HTTP_PROXY", "")
            return api_result

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = capture_env_get_videos
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(proxy="http://127.0.0.1:7890"),
        }

        old_https = os.environ.get("HTTPS_PROXY")
        old_http = os.environ.get("HTTP_PROXY")
        try:
            with patch(
                "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
                mock_user_cls,
            ):
                _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

            assert captured_env["HTTPS_PROXY"] == "http://127.0.0.1:7890"
            assert captured_env["HTTP_PROXY"] == "http://127.0.0.1:7890"
        finally:
            # restore env
            if old_https is None:
                os.environ.pop("HTTPS_PROXY", None)
            else:
                os.environ["HTTPS_PROXY"] = old_https
            if old_http is None:
                os.environ.pop("HTTP_PROXY", None)
            else:
                os.environ["HTTP_PROXY"] = old_http


# ---------------------------------------------------------------------------
# fw8: 登录态账号 → Credential 构造
# ---------------------------------------------------------------------------

class TestFw8CredentialCreated:
    def test_credential_created_for_login(self):
        """登录态账号 → Credential 构造参数正确。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        captured_cred_args = {}

        vlist = _make_vlist([("BV1cred", 500)])
        api_result = _make_api_result(vlist, total_count=1)

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = AsyncMock(return_value=api_result)
        mock_user_cls.return_value = mock_user_instance

        original_credential = None

        def mock_credential(**kwargs):
            captured_cred_args.update(kwargs)
            return MagicMock()

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _login_account(
                sessdata="my_sessdata",
                bili_jct="my_jct",
                proxy="http://proxy:8080",
            ),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ), patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.Credential",
            mock_credential,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        assert captured_cred_args["sessdata"] == "my_sessdata"
        assert captured_cred_args["bili_jct"] == "my_jct"
        assert captured_cred_args["buvid3"] == "buvid3_val"
        assert captured_cred_args["proxy"] == "http://proxy:8080"


# ---------------------------------------------------------------------------
# fw9: 速率限制
# ---------------------------------------------------------------------------

class TestFw9RateLimit:
    def test_rate_limit_respected(self):
        """验证页间间隔 ≥ rate_limit_sec。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        call_times = []

        async def timed_get_videos(pn=1, ps=30, order=None):
            call_times.append(time.monotonic())
            vlist = _make_vlist([(f"BV{pn}a", 1000 - pn * 100)])
            total = 90  # 3 pages
            return _make_api_result(vlist, total_count=total, pn=pn)

        mock_user_cls = MagicMock()
        mock_user_instance = MagicMock()
        mock_user_instance.get_videos = timed_get_videos
        mock_user_cls.return_value = mock_user_instance

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        rate = 0.2  # 200ms between pages
        param = {
            "mid": "12345",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(rate_limit_sec=rate),
        }

        with patch(
            "fredica_pyutil_server.subprocess.bilibili_uploader_sync.User",
            mock_user_cls,
        ):
            _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        # At least 3 calls (3 pages of 1 video each, total=90 means has_more for pages 1,2)
        assert len(call_times) >= 3
        for i in range(1, len(call_times)):
            gap = call_times[i] - call_times[i - 1]
            assert gap >= rate * 0.9, f"Gap between page {i} and {i+1} was {gap:.3f}s, expected >= {rate}s"


# ---------------------------------------------------------------------------
# fw_real_1: 真实网络匿名抓取（需 @pytest.mark.network）
# ---------------------------------------------------------------------------

@pytest.mark.network
class TestFwReal1AnonymousFetch:
    def test_real_anonymous_fetch_page1(self):
        """真实匿名抓取 mid=3493116105460316 第 1 页。"""
        from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
            _bilibili_fetch_worker,
        )

        mp_ctx = multiprocessing.get_context("spawn")
        fetch_queue = mp_ctx.Queue()
        cancel_event = mp_ctx.Event()
        resume_event = mp_ctx.Event()
        resume_event.set()

        param = {
            "mid": "3493116105460316",
            "start_page": 1,
            "old_cursor": 0,
            "account": _anonymous_account(rate_limit_sec=2.0),
        }

        _bilibili_fetch_worker(param, fetch_queue, cancel_event, resume_event)

        msgs = _drain_queue(fetch_queue, timeout=30.0)
        assert len(msgs) >= 1, f"Expected at least 1 message, got {len(msgs)}"

        first = msgs[0]
        if first["type"] == "error":
            pytest.skip(f"Network error (expected in CI): {first['error']}")

        assert first["type"] == "page"
        assert first["page"] == 1
        assert isinstance(first["videos"], list)
        assert first["total_count"] > 0
        print(f"  Fetched {len(first['videos'])} videos, total={first['total_count']}, has_more={first['has_more']}")

        for v in first["videos"][:3]:
            print(f"    {v.get('bvid')} | {v.get('title', '')[:40]} | created={v.get('created')}")
