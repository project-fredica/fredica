# -*- coding: UTF-8 -*-
"""
TaskEndpointInSubProcess 子进程崩溃兜底测试。

验证：子进程以非零 exitcode 退出时，_run_queue_reader 会向 WebSocket 发送
{"type": "error"} 消息，而不是静默卡住。

运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_task_endpoint_subprocess_crash.py -v

测试矩阵：
  C1. 子进程正常退出（exitcode=0）→ 不发送 error 消息
  C2. 子进程异常崩溃（exitcode=1）→ 发送 {"type": "error"} 消息
  C3. 子进程异常崩溃（exitcode=-11，SIGSEGV）→ 发送 error 消息，exitcode 包含在消息中
  C4. send_json 本身抛异常时不影响 queue reader 正常退出（不二次崩溃）
"""
import asyncio
import multiprocessing
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess


# ---------------------------------------------------------------------------
# 最小具体子类（满足抽象方法要求）
# ---------------------------------------------------------------------------

class _MinimalSubProcessEndpoint(TaskEndpointInSubProcess):
    def _get_process_target(self):
        return lambda *a: None  # 不会真正被调用

    async def _on_subprocess_message(self, msg: Any):
        # 记录所有发出的消息，供断言使用
        self._sent_messages.append(msg)
        await self.send_json(msg)


def _make_endpoint(exitcode: int | None, sent_messages: list) -> _MinimalSubProcessEndpoint:
    """构造一个 mock WebSocket 的端点实例，子进程已死且 exitcode 固定。"""
    ws = MagicMock()
    ws.send_json = AsyncMock()
    endpoint = _MinimalSubProcessEndpoint(websocket=ws)
    endpoint._sent_messages = sent_messages

    # 模拟已死亡的子进程
    mock_process = MagicMock()
    mock_process.is_alive.return_value = False
    mock_process.exitcode = exitcode
    endpoint._process = mock_process

    # 空队列（立即抛 Empty）
    mp_ctx = multiprocessing.get_context("spawn")
    endpoint._status_queue = mp_ctx.Queue()

    return endpoint


async def _run_queue_reader(endpoint: _MinimalSubProcessEndpoint):
    """运行 _run_queue_reader 并等待其完成（最多 2 秒）。"""
    await asyncio.wait_for(endpoint._run_queue_reader(), timeout=2.0)


# ---------------------------------------------------------------------------
# C1：正常退出（exitcode=0）→ 不发送 error
# ---------------------------------------------------------------------------

class TestSubprocessNormalExit:
    def test_C1_no_error_sent_on_clean_exit(self):
        """exitcode=0 时不应向 WebSocket 发送 error 消息。"""
        sent = []
        endpoint = _make_endpoint(exitcode=0, sent_messages=sent)

        asyncio.run(_run_queue_reader(endpoint))

        # send_json 不应被调用（队列为空，进程已死，exitcode=0）
        endpoint.websocket.send_json.assert_not_called()


# ---------------------------------------------------------------------------
# C2：异常崩溃（exitcode=1）→ 发送 error
# ---------------------------------------------------------------------------

class TestSubprocessCrashExitcode1:
    def test_C2_error_sent_on_nonzero_exitcode(self):
        """exitcode=1 时应向 WebSocket 发送 {"type": "error"} 消息。"""
        sent_jsons = []
        ws = MagicMock()

        async def capture_send_json(data):
            sent_jsons.append(data)

        ws.send_json = capture_send_json
        endpoint = _MinimalSubProcessEndpoint(websocket=ws)
        endpoint._sent_messages = []

        mock_process = MagicMock()
        mock_process.is_alive.return_value = False
        mock_process.exitcode = 1
        endpoint._process = mock_process

        mp_ctx = multiprocessing.get_context("spawn")
        endpoint._status_queue = mp_ctx.Queue()

        asyncio.run(_run_queue_reader(endpoint))

        assert len(sent_jsons) == 1, f"应发送 1 条消息，实际发送了 {len(sent_jsons)} 条"
        msg = sent_jsons[0]
        assert msg.get("type") == "error", f"消息 type 应为 error，实际为 {msg.get('type')}"
        assert "error" in msg, "消息应包含 error 字段"
        assert "1" in msg["error"], f"error 字段应包含 exitcode，实际为 {msg['error']}"


# ---------------------------------------------------------------------------
# C3：SIGSEGV（exitcode=-11）→ 发送 error，exitcode 包含在消息中
# ---------------------------------------------------------------------------

class TestSubprocessCrashSigsegv:
    def test_C3_error_message_contains_exitcode(self):
        """exitcode=-11 时 error 消息应包含该 exitcode 值。"""
        sent_jsons = []
        ws = MagicMock()

        async def capture_send_json(data):
            sent_jsons.append(data)

        ws.send_json = capture_send_json
        endpoint = _MinimalSubProcessEndpoint(websocket=ws)
        endpoint._sent_messages = []

        mock_process = MagicMock()
        mock_process.is_alive.return_value = False
        mock_process.exitcode = -11
        endpoint._process = mock_process

        mp_ctx = multiprocessing.get_context("spawn")
        endpoint._status_queue = mp_ctx.Queue()

        asyncio.run(_run_queue_reader(endpoint))

        assert len(sent_jsons) == 1
        msg = sent_jsons[0]
        assert msg.get("type") == "error"
        assert "-11" in msg["error"], f"error 字段应包含 -11，实际为 {msg['error']}"


# ---------------------------------------------------------------------------
# C4：send_json 抛异常时 queue reader 仍正常退出
# ---------------------------------------------------------------------------

class TestSubprocessCrashSendJsonFails:
    def test_C4_queue_reader_exits_even_if_send_json_raises(self):
        """send_json 抛异常时，_run_queue_reader 不应二次崩溃，应正常退出。"""
        ws = MagicMock()
        ws.send_json = AsyncMock(side_effect=RuntimeError("WebSocket already closed"))
        endpoint = _MinimalSubProcessEndpoint(websocket=ws)
        endpoint._sent_messages = []

        mock_process = MagicMock()
        mock_process.is_alive.return_value = False
        mock_process.exitcode = 1
        endpoint._process = mock_process

        mp_ctx = multiprocessing.get_context("spawn")
        endpoint._status_queue = mp_ctx.Queue()

        # 不应抛异常，应正常完成
        asyncio.run(_run_queue_reader(endpoint))
        # 到这里说明没有崩溃
