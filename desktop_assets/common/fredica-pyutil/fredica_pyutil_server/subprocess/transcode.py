# -*- coding: UTF-8 -*-
"""
FFmpeg 转码子进程任务。

提供 FfmpegTranscodeMp4TaskEndpoint：在子进程中运行 FFmpeg，
解析 stderr 中的进度信息并通过 WebSocket 实时推送给客户端。

路由（在 routes/transcode.py 中注册）：
    @router.websocket("/mp4-task")
    async def transcode_mp4_task(websocket: WebSocket):
        endpoint = FfmpegTranscodeMp4TaskEndpoint(tag="transcode", websocket=websocket)
        await endpoint.start_and_wait()
"""

import re
import subprocess
from typing import Any, Optional

from loguru import logger

from fredica_pyutil_server.util.ffmpeg_util import TranscodeCommandBuilder
from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess


# ---------------------------------------------------------------------------
# 子进程入口函数（必须定义在模块级以确保可 pickle）
# ---------------------------------------------------------------------------

def _ffmpeg_transcode_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中运行 FFmpeg 转码，将进度逐帧推送至 status_queue。

    :param param:         init_param_and_run 传入的参数字典，字段见下方
    :param status_queue:  multiprocessing.Queue，用于向父进程推送消息
    :param cancel_event:  multiprocessing.Event，set 时应尽快退出
    :param resume_event:  multiprocessing.Event，clear 时应 wait()（暂停）

    param 支持的字段：
        input_video  (str)  必填，视频流路径（.m4s 或 .flv）
        input_audio  (str)  可选，音频流路径（.m4s；FLV 单流时为 null）
        output_path  (str)  必填，输出 .mp4 路径
        hw_accel     (str)  "cuda"|"amf"|"qsv"|"videotoolbox"|"cpu"|"auto"，默认 "cpu"
        ffmpeg_path  (str)  FFmpeg 可执行文件路径，默认 "ffmpeg"

    向 status_queue 推送的消息格式：
        {"type": "progress", "percent": int, "frame": int, "fps": float, "time": str}
        {"type": "done",     "output_path": str}
        {"type": "error",    "message": str}
    """
    input_video: str = param["input_video"]
    input_audio: Optional[str] = param.get("input_audio") or None
    output_path: str = param["output_path"]
    hw_accel: str = param.get("hw_accel", "cpu")
    ffmpeg_path: str = param.get("ffmpeg_path", "ffmpeg")

    # 获取输入时长（秒），用于计算进度
    duration_sec: float = _probe_duration(ffmpeg_path, input_video)

    builder = TranscodeCommandBuilder()
    cmd = builder.build(
        ffmpeg_path=ffmpeg_path,
        input_video=input_video,
        input_audio=input_audio,
        output_path=output_path,
        hw_accel=hw_accel,
    )

    try:
        proc = subprocess.Popen(
            cmd,
            stderr=subprocess.PIPE,
            stdin=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

        while True:
            if cancel_event.is_set():
                # 发送 'q' 让 FFmpeg 优雅退出
                try:
                    proc.stdin.write("q\n")
                    proc.stdin.flush()
                except Exception:
                    pass
                try:
                    proc.terminate()
                except Exception:
                    pass
                status_queue.put({"type": "error", "message": "cancelled"})
                return

            resume_event.wait()

            line = proc.stderr.readline()
            if not line:
                break

            # 解析进度行：frame=N fps=N.N time=HH:MM:SS.ms
            m = re.search(
                r"frame=\s*(\d+)\s+fps=\s*([\d.]+).*time=(\d{2}:\d{2}:\d{2}\.?\d*)",
                line,
            )
            if m:
                frame = int(m.group(1))
                fps = float(m.group(2))
                time_str = m.group(3)
                current_sec = _parse_time(time_str)
                if duration_sec > 0:
                    percent = min(99, int(current_sec / duration_sec * 100))
                else:
                    percent = 0
                status_queue.put({
                    "type": "progress",
                    "percent": percent,
                    "frame": frame,
                    "fps": fps,
                    "time": time_str,
                })

        proc.wait()
        if proc.returncode == 0:
            status_queue.put({"type": "done", "output_path": output_path})
        elif not cancel_event.is_set():
            status_queue.put({"type": "error", "message": f"ffmpeg exited with code {proc.returncode}"})

    except Exception as e:
        status_queue.put({"type": "error", "message": str(e)})


def _probe_duration(ffmpeg_path: str, video_path: str) -> float:
    """用 ffprobe 获取视频时长（秒）。获取失败返回 0。"""
    try:
        import os
        ffprobe_path = os.path.join(os.path.dirname(ffmpeg_path), "ffprobe")
        if not os.path.isfile(ffprobe_path):
            ffprobe_path = "ffprobe"
        r = subprocess.run(
            [
                ffprobe_path, "-v", "quiet",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video_path,
            ],
            capture_output=True, text=True, timeout=10,
        )
        return float(r.stdout.strip())
    except Exception:
        return 0.0


def _parse_time(time_str: str) -> float:
    """将 HH:MM:SS.ms 格式的时间字符串转换为秒数。"""
    try:
        parts = time_str.split(":")
        h, m, s = int(parts[0]), int(parts[1]), float(parts[2])
        return h * 3600 + m * 60 + s
    except Exception:
        return 0.0


# ---------------------------------------------------------------------------
# TaskEndpointInSubProcess 实现
# ---------------------------------------------------------------------------

class FfmpegTranscodeMp4TaskEndpoint(TaskEndpointInSubProcess):
    """
    在子进程中运行 FFmpeg 转码，通过 WebSocket 实时推送进度。

    init_param_and_run data 格式：
        {
            "input_video":  str,        必填，视频流路径（.m4s 或 .flv）
            "input_audio":  str|null,   音频流路径（.m4s；FLV 单流时为 null）
            "output_path":  str,        必填，输出 .mp4 路径
            "hw_accel":     str,        "cuda"|"amf"|"qsv"|"videotoolbox"|"cpu"（默认 "cpu"）
            "ffmpeg_path":  str,        FFmpeg 路径（默认 "ffmpeg"）
        }

    WebSocket 推送消息格式：
        {"type": "progress", "percent": int, "frame": int, "fps": float, "time": str}
        {"type": "done",     "output_path": str}
        {"type": "error",    "message": str}
    """

    def _get_process_target(self):
        return _ffmpeg_transcode_worker

    async def _on_subprocess_message(self, msg: Any):
        self._current_status = msg
        await self.send_json(msg)


if __name__ == "__main__":
    pass
