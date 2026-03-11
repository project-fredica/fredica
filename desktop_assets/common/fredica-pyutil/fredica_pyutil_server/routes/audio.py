# -*- coding: UTF-8 -*-
"""
音频提取与 ASR 转录 WebSocket 路由（通用基建）。

路由：
  WS /audio/extract-split-audio-task — FFmpeg 提取音频并按时长切段（约 5 分钟/段）
  WS /audio/transcribe-chunk-task    — Whisper 逐段语音转录
"""

import os
import queue
import re
import subprocess
import threading
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter
from loguru import logger
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.transcribe import FasterWhisperTranscribeTaskEndpoint
from fredica_pyutil_server.util.ffmpeg_util import find_best_ffmpeg
from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess

_router = APIRouter(prefix="/audio")


# =============================================================================
# extract-split-audio-task：提取音频并按时长切段
# =============================================================================

def _extract_split_audio_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中运行 FFmpeg，将视频提取音频并按固定时长切段。

    param 支持的字段：
        video_path          (str)  必填，输入视频文件路径
        output_dir          (str)  必填，音频 chunk 输出目录
        chunk_duration_sec  (int)  每段时长（秒），默认 300
        ffmpeg_path         (str)  FFmpeg 可执行文件路径，默认自动搜索
        hw_accel            (str)  硬件加速类型（"cuda"|"d3d11va"|"qsv"|""），默认 ""

    向 status_queue 推送的消息格式：
        {"type": "log",      "level": str, "message": str}
        {"type": "progress", "percent": int, "time": str}
        {"type": "done",     "chunks": [{"path": str, "index": int}, ...]}
        {"type": "error",    "message": str}
    """
    import time

    video_path: str = param["video_path"]
    output_dir: str = param["output_dir"]
    chunk_duration_sec: int = int(param.get("chunk_duration_sec", 300))
    ffmpeg_path: str = param.get("ffmpeg_path") or find_best_ffmpeg().path or "ffmpeg"
    hw_accel: str = param.get("hw_accel", "").strip()

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    os.makedirs(output_dir, exist_ok=True)
    chunk_pattern = os.path.join(output_dir, "chunk_%04d.m4a")

    # 先探测视频总时长，用于计算进度
    duration_sec = _probe_duration(ffmpeg_path, video_path)
    _log("info", f"[extract-audio] 视频时长={duration_sec:.1f}s  hw_accel={hw_accel!r}  文件={video_path!r}")

    # 构建硬件加速参数（仅加速输入解码，不影响音频编码）
    # -hwaccel 加速的是视频流解码阶段，即使 -vn 丢弃视频，也能减少 demux 时的 CPU 开销
    hwaccel_args = []
    if hw_accel and hw_accel not in ("", "none", "cpu"):
        hwaccel_args = ["-hwaccel", hw_accel]

    cmd = [
        ffmpeg_path,
        "-y",
        *hwaccel_args,
        "-i", video_path,
        "-vn",                          # 丢弃视频流，只提取音频
        "-acodec", "aac",
        "-f", "segment",
        "-segment_time", str(chunk_duration_sec),
        "-reset_timestamps", "1",
        chunk_pattern,
    ]

    _log("info", f"[extract-audio] 执行命令: {' '.join(cmd)}")
    t_start = time.monotonic()

    try:
        proc = subprocess.Popen(
            cmd,
            stderr=subprocess.PIPE,
            stdin=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

        stderr_lines = queue.Queue()

        def _read_stderr():
            for line in proc.stderr:
                stderr_lines.put(line)
            stderr_lines.put(None)  # 结束哨兵

        stderr_thread = threading.Thread(target=_read_stderr, daemon=True)
        stderr_thread.start()

        _time_re = re.compile(r"time=(\d{2}):(\d{2}):(\d{2})\.(\d+)")
        last_pct = -1

        while True:
            if cancel_event.is_set():
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                status_queue.put({"type": "cancelled"})
                return

            resume_event.wait()

            try:
                line = stderr_lines.get(timeout=0.1)
            except queue.Empty:
                if proc.poll() is not None:
                    break
                continue

            if line is None:
                break

            m = _time_re.search(line)
            if m and duration_sec > 0:
                h, mn, s, cs = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
                elapsed = h * 3600 + mn * 60 + s + cs / 100.0
                pct = min(99, int(elapsed / duration_sec * 100))
                status_queue.put({"type": "progress", "percent": pct, "time": m.group(0).split("=")[1]})
                # 每 10% 输出一条日志
                if pct // 10 > last_pct // 10:
                    _log("info", f"[extract-audio] 进度 {pct}%  已处理 {elapsed:.0f}s / {duration_sec:.0f}s")
                    last_pct = pct

        proc.wait()

        if proc.returncode != 0 and not cancel_event.is_set():
            status_queue.put({"type": "error", "message": f"FFmpeg exited with code {proc.returncode}"})
            return

        if cancel_event.is_set():
            status_queue.put({"type": "cancelled"})
            return

        # 收集生成的 chunk 文件（按名称排序）
        chunks = []
        for entry in sorted(Path(output_dir).glob("chunk_*.m4a")):
            idx = int(re.search(r"chunk_(\d+)\.m4a", entry.name).group(1))
            chunks.append({"path": str(entry.absolute()), "index": idx})

        elapsed_total = time.monotonic() - t_start
        _log("info", f"[extract-audio] 完成 共 {len(chunks)} 段  总耗时={elapsed_total:.1f}s")
        status_queue.put({"type": "done", "chunks": chunks})

    except Exception as e:
        status_queue.put({"type": "error", "message": str(e)})


def _probe_duration(ffmpeg_path: str, video_path: str) -> float:
    """用 ffmpeg -i 探测视频时长（秒）。"""
    try:
        result = subprocess.run(
            [ffmpeg_path, "-i", video_path, "-f", "null", "-"],
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
        m = re.search(r"Duration:\s*(\d{2}):(\d{2}):(\d{2})\.(\d+)", result.stderr)
        if m:
            h, mn, s, cs = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
            return h * 3600 + mn * 60 + s + cs / 100.0
    except Exception as e:
        logger.debug("[audio] probe_duration failed for {!r}: {}", video_path, e)
    return 0.0


class ExtractSplitAudioTaskEndpoint(TaskEndpointInSubProcess):
    """
    提取视频音频并按时长切段的 WebSocket 任务端点。

    init_param_and_run data 格式：
        {
            "video_path":         str,   必填，输入视频路径
            "output_dir":         str,   必填，chunk 输出目录
            "chunk_duration_sec": int,   每段时长（秒），默认 300
            "ffmpeg_path":        str,   可选，FFmpeg 路径，默认自动搜索
            "hw_accel":           str,   可选，硬件加速（"cuda"|"d3d11va"|"qsv"|""）
        }

    WebSocket 推送消息格式：
        {"type": "progress", "percent": int, "time": str}
        {"type": "done",     "chunks": [{"path": str, "index": int}, ...]}
        {"type": "error",    "message": str}
        （"log" 类型消息由父进程写入 logger，不转发给客户端）
    """

    def _get_process_target(self):
        return _extract_split_audio_worker

    async def _on_subprocess_message(self, msg: Any):
        if msg.get("type") == "log":
            level = msg.get("level", "info")
            getattr(logger, level, logger.info)(msg.get("message", ""))
            return  # log 消息不推送给 WebSocket 客户端
        self._current_status = msg
        await self.send_json(msg)


@_router.websocket("/extract-split-audio-task")
async def extract_split_audio_task(websocket: WebSocket):
    """
    FFmpeg 音频提取+切段任务端点（WebSocket）。

    WebSocket 协议：
      1. 连接建立后发送 init_param_and_run 启动任务：
         {
           "command": "init_param_and_run",
           "data": {
             "video_path":         "/path/to/video.m4s",
             "output_dir":         "/path/to/audio_chunks/",
             "chunk_duration_sec": 300,
             "hw_accel":           "cuda"   // 可选
           }
         }
      2. 服务端实时推送 progress / done / error 消息
      3. 可随时发送 cancel / pause / resume / status 命令
    """
    endpoint = ExtractSplitAudioTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()


# =============================================================================
# transcribe-chunk-task：Whisper 单段转录（直接复用 FasterWhisperTranscribeTaskEndpoint）
# =============================================================================

@_router.websocket("/transcribe-chunk-task")
async def transcribe_chunk_task(websocket: WebSocket):
    """
    Whisper 单段音频转录任务端点（WebSocket）。

    WebSocket 协议：
      1. 连接建立后发送 init_param_and_run：
         {
           "command": "init_param_and_run",
           "data": {
             "audio_path":   "/path/to/chunk.m4a",
             "language":     "zh",       // 可选
             "model_name":   "large-v3", // 可选
             "device":       "auto"      // 可选
           }
         }
      2. 服务端逐片段推送 segment / done / error 消息
      3. 可随时发送 cancel / pause / resume / status 命令
    """
    endpoint = FasterWhisperTranscribeTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()
