# -*- coding: UTF-8 -*-
"""
FFmpeg 转码任务 WebSocket 路由。

路由：
  WS /transcode/mp4-task — FFmpeg 转码任务端点
"""

from pathlib import Path

from fastapi import APIRouter
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.transcode import FfmpegTranscodeMp4TaskEndpoint

_router = APIRouter(prefix="/" + Path(__file__).stem)


@_router.websocket("/mp4-task")
async def transcode_mp4_task(websocket: WebSocket):
    """
    FFmpeg 转码任务端点（WebSocket）。

    WebSocket 协议：
      1. 连接建立后发送 init_param_and_run 启动转码：
         {
           "command": "init_param_and_run",
           "data": {
             "input_video":  "/path/to/video.m4s",
             "input_audio":  "/path/to/audio.m4s",  // 或 null
             "output_path":  "/path/to/output.mp4",
             "hw_accel":     "cuda",                // 或 "amf"/"qsv"/"cpu" 等
             "ffmpeg_path":  "/usr/bin/ffmpeg"
           }
         }
      2. 服务端实时推送 progress / done / error 消息
      3. 可随时发送 cancel / pause / resume / status 命令
    """
    endpoint = FfmpegTranscodeMp4TaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()
