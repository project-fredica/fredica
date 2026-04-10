# -*- coding: UTF-8 -*-
"""
音频提取与 ASR 转录 WebSocket 路由（通用基建）。

路由：
  WS /audio/extract-split-audio-task — FFmpeg 提取音频并按时长切段（约 5 分钟/段）
  WS /audio/transcribe-chunk-task    — Whisper 逐段语音转录
"""

import math
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
    在子进程中运行 FFmpeg，将视频提取音频并按固定时长切段（带重叠 padding）。

    每个 chunk 的核心区域为 chunk_duration_sec 秒，前后各加 overlap_sec 秒 padding，
    避免 faster-whisper 在 chunk 边界处截断正在进行的语句。合并时只保留核心区域内的 segment。

    param 支持的字段：
        video_path          (str)  必填，输入视频文件路径
        output_dir          (str)  必填，音频 chunk 输出目录
        chunk_duration_sec  (int)  每段核心时长（秒），默认 300
        overlap_sec         (int)  前后重叠 padding（秒），默认 60
        ffmpeg_path         (str)  FFmpeg 可执行文件路径，默认自动搜索
        hw_accel            (str)  硬件加速类型（"cuda"|"d3d11va"|"qsv"|""），默认 ""

    向 status_queue 推送的消息格式：
        {"type": "log",      "level": str, "message": str}
        {"type": "progress", "percent": int}
        {"type": "done",     "chunks": [{"path": str, "index": int,
                                          "core_start_sec": float, "core_end_sec": float,
                                          "actual_start_sec": float}, ...]}
        {"type": "error",    "message": str}
    """
    import time

    video_path: str = param["video_path"]
    output_dir: str = param["output_dir"]
    chunk_duration_sec: int = int(param.get("chunk_duration_sec", 300))
    overlap_sec: int = int(param.get("overlap_sec", 60))
    ffmpeg_path: str = param.get("ffmpeg_path") or find_best_ffmpeg().path or "ffmpeg"
    hw_accel: str = param.get("hw_accel", "").strip()

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    os.makedirs(output_dir, exist_ok=True)

    # 先探测视频总时长，用于计算 chunk 边界
    duration_sec = _probe_duration(ffmpeg_path, video_path)
    if duration_sec <= 0:
        status_queue.put({"type": "error", "message": "无法探测视频时长"})
        return

    _log("info", f"[extract-audio] 视频时长={duration_sec:.1f}s  overlap={overlap_sec}s  hw_accel={hw_accel!r}  文件={video_path!r}")

    # 构建硬件加速参数
    hwaccel_args = []
    if hw_accel and hw_accel not in ("", "none", "cpu"):
        hwaccel_args = ["-hwaccel", hw_accel]

    # 计算 chunk 边界（核心区域 + padding）
    n_chunks = max(1, math.ceil(duration_sec / chunk_duration_sec))
    chunk_specs = []
    for i in range(n_chunks):
        core_start = i * chunk_duration_sec
        core_end = min((i + 1) * chunk_duration_sec, duration_sec)
        actual_start = max(0, core_start - overlap_sec)
        actual_end = min(duration_sec, core_end + overlap_sec)
        chunk_specs.append({
            "index": i,
            "core_start": core_start,
            "core_end": core_end,
            "actual_start": actual_start,
            "actual_end": actual_end,
        })

    _log("info", f"[extract-audio] 共 {n_chunks} 段，核心={chunk_duration_sec}s  padding={overlap_sec}s")
    t_start = time.monotonic()

    try:
        chunks = []
        for spec in chunk_specs:
            if cancel_event.is_set():
                status_queue.put({"type": "cancelled"})
                return

            resume_event.wait()

            i = spec["index"]
            chunk_path = os.path.join(output_dir, f"chunk_{i:04d}.m4a")

            # -ss 放在 -i 前面实现快速 seek
            cmd = [
                ffmpeg_path, "-y",
                *hwaccel_args,
                "-ss", str(spec["actual_start"]),
                "-to", str(spec["actual_end"]),
                "-i", video_path,
                "-vn",
                "-acodec", "aac",
                chunk_path,
            ]

            _log("info", f"[extract-audio] chunk {i}/{n_chunks}: "
                         f"audio [{spec['actual_start']:.0f}s - {spec['actual_end']:.0f}s]  "
                         f"core [{spec['core_start']:.0f}s - {spec['core_end']:.0f}s]")

            proc = subprocess.Popen(
                cmd,
                stderr=subprocess.PIPE,
                stdin=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

            # 读取 stderr 直到进程结束（不需要逐行解析进度，按 chunk 粒度报告即可）
            _, stderr_output = proc.communicate()

            if cancel_event.is_set():
                status_queue.put({"type": "cancelled"})
                return

            if proc.returncode != 0:
                status_queue.put({"type": "error",
                                  "message": f"FFmpeg chunk {i} exited with code {proc.returncode}: {stderr_output[-500:] if stderr_output else ''}"})
                return

            chunks.append({
                "path": str(Path(chunk_path).absolute()),
                "index": i,
                "core_start_sec": spec["core_start"],
                "core_end_sec": spec["core_end"],
                "actual_start_sec": spec["actual_start"],
            })

            # 按已完成 chunk 数报告进度
            pct = min(99, int((i + 1) / n_chunks * 100))
            status_queue.put({"type": "progress", "percent": pct})
            _log("info", f"[extract-audio] chunk {i} 完成  进度 {pct}%")

        elapsed_total = time.monotonic() - t_start
        _log("info", f"[extract-audio] 完成 共 {len(chunks)} 段  总耗时={elapsed_total:.1f}s")
        status_queue.put({"type": "done", "chunks": chunks})

    except Exception as e:
        status_queue.put({"type": "error", "message": str(e)})


def _probe_duration(ffmpeg_path: str, video_path: str) -> float:
    """用 ffmpeg -i 探测视频时长（秒）。

    使用 ffmpeg -i 而非 -f null -，因为后者需要完整解码整个文件，
    对大视频耗时极长甚至超时；-i 只读取容器头部即可获取 Duration。
    ffmpeg -i 在无输出文件时会以非零退出码退出，这是正常行为，不视为错误。
    """
    try:
        result = subprocess.run(
            [ffmpeg_path, "-i", video_path],
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15,
        )
        m = re.search(r"Duration:\s*(\d{2}):(\d{2}):(\d{2})\.(\d+)", result.stderr)
        if m:
            h, mn, s, cs = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
            return h * 3600 + mn * 60 + s + cs / 100.0
        logger.warning("[audio] probe_duration: Duration not found in ffmpeg output for {!r}", video_path)
    except Exception as e:
        logger.warning("[audio] probe_duration failed for {!r}: {}", video_path, e)
    return 0.0


class ExtractSplitAudioTaskEndpoint(TaskEndpointInSubProcess):
    """
    提取视频音频并按时长切段的 WebSocket 任务端点。

    init_param_and_run data 格式：
        {
            "video_path":         str,   必填，输入视频路径
            "output_dir":         str,   必填，chunk 输出目录
            "chunk_duration_sec": int,   每段核心时长（秒），默认 300
            "overlap_sec":        int,   前后重叠 padding（秒），默认 60
            "ffmpeg_path":        str,   可选，FFmpeg 路径，默认自动搜索
            "hw_accel":           str,   可选，硬件加速（"cuda"|"d3d11va"|"qsv"|""）
        }

    WebSocket 推送消息格式：
        {"type": "progress", "percent": int}
        {"type": "done",     "chunks": [{"path": str, "index": int,
                                          "core_start_sec": float, "core_end_sec": float,
                                          "actual_start_sec": float}, ...]}
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
             "overlap_sec":        60,     // 可选，默认 60
             "hw_accel":           "cuda"  // 可选
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
