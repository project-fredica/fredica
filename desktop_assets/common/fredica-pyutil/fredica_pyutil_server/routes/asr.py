# -*- coding: UTF-8 -*-
"""
ASR（faster-whisper）管理路由。

路由：
  WS /asr/download-model-task      — 下载指定 Whisper 模型
  WS /asr/evaluate-compat-task     — 评估本地 GPU 对各 compute_type / 模型的兼容性
"""

from typing import Any

from fastapi import APIRouter
from loguru import logger
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.download_model import download_model_worker
from fredica_pyutil_server.subprocess.evaluate_faster_whisper_compat_worker import evaluate_faster_whisper_compat_worker
from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess

_router = APIRouter(prefix="/asr")


# =============================================================================
# download-model-task：下载指定 Whisper 模型
# =============================================================================

class DownloadWhisperModelTaskEndpoint(TaskEndpointInSubProcess):
    """
    下载 Whisper 模型的 WebSocket 任务端点。

    init_param_and_run data 格式：
        {
            "model_name": str,   必填
            "proxy":      str,   可选
            "models_dir": str,   可选
        }
    """

    def _get_process_target(self):
        return download_model_worker

    async def _on_subprocess_message(self, msg: Any):
        if msg.get("type") == "log":
            level = msg.get("level", "info")
            getattr(logger, level, logger.info)(msg.get("message", ""))
            return
        self._current_status = msg
        await self.send_json(msg)


@_router.websocket("/download-model-task")
async def download_model_task(websocket: WebSocket):
    """
    下载 Whisper 模型任务端点（WebSocket）。

    WebSocket 协议：
      1. 发送 init_param_and_run：
         {"command": "init_param_and_run", "data": {"model_name": "large-v3", "proxy": ""}}
      2. 服务端推送 progress / done / error
      3. 可随时发送 cancel
    """
    endpoint = DownloadWhisperModelTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()


# =============================================================================
# evaluate-compat-task：评估 Whisper 兼容性（5 个 Step）
# =============================================================================

class EvaluateFasterWhisperCompatTaskEndpoint(TaskEndpointInSubProcess):
    """
    评估本地 GPU 对 Whisper 各 compute_type / 模型兼容性的 WebSocket 任务端点。
    评估逻辑在子进程中运行，避免主进程导入 torch / faster_whisper。

    init_param_and_run data 格式：
        {"proxy": str, "models_dir": str}
    """

    def _get_process_target(self):
        return evaluate_faster_whisper_compat_worker

    async def _on_subprocess_message(self, msg: Any):
        self._current_status = msg
        await self.send_json(msg)


@_router.websocket("/evaluate-faster-whisper-compat-task")
async def evaluate_faster_whisper_compat_task(websocket: WebSocket):
    """
    Whisper 兼容性评估任务端点（WebSocket）。

    WebSocket 协议：
      1. 发送 init_param_and_run：
         {"command": "init_param_and_run", "data": {"proxy": "", "models_dir": ""}}
      2. 服务端依次推送 local_models / device_info / compute_type_result /
         download_check / model_support / done
      3. 可随时发送 cancel
    """
    endpoint = EvaluateFasterWhisperCompatTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()
