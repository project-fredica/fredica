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

import queue
import re
import subprocess
import threading
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
    :param resume_event:  multiprocessing.Event FFmpeg 子进程不支持暂停，因此这里没用。

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

        # 在独立线程中读取 stderr，避免 readline() 阻塞主循环，
        # 使 cancel_event 能被立即响应。
        stderr_queue: queue.Queue = queue.Queue()

        def _stderr_reader():
            try:
                for line in proc.stderr:
                    stderr_queue.put(line)
            finally:
                stderr_queue.put(None)  # 哨兵：表示 stderr 已读完

        reader_thread = threading.Thread(target=_stderr_reader, daemon=True)
        reader_thread.start()

        while True:
            if cancel_event.is_set():
                # 立即终止 FFmpeg 进程，不等待 readline() 返回
                try:
                    proc.terminate()
                except Exception as e:
                    logger.debug("[transcode] proc.terminate() failed: {}", e)
                status_queue.put({"type": "error", "message": "cancelled"})
                return

            # 非阻塞地取一行；若暂时没有则继续轮询
            try:
                line = stderr_queue.get(timeout=0.1)
            except queue.Empty:
                if proc.poll() is not None:
                    # 进程已退出且队列为空，退出循环
                    break
                continue

            if line is None:
                # 哨兵：stderr 已读完
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
            # 写入 transcode.done 标志文件，供 Kotlin 侧 canSkip() 检测
            try:
                import os
                done_path = os.path.join(os.path.dirname(output_path), "transcode.done")
                open(done_path, "w").close()
            except Exception as e:
                logger.warning("[transcode] failed to write transcode.done: {}", e)
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
    except Exception as e:
        logger.debug("[transcode] probe_duration failed for {!r}: {}", video_path, e)
        return 0.0


def _parse_time(time_str: str) -> float:
    """将 HH:MM:SS.ms 格式的时间字符串转换为秒数。"""
    try:
        parts = time_str.split(":")
        h, m, s = int(parts[0]), int(parts[1]), float(parts[2])
        return h * 3600 + m * 60 + s
    except Exception as e:
        logger.debug("[transcode] parse_time failed for {!r}: {}", time_str, e)
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

    async def _does_support_pause(self):
        # FFmpeg 本身仍在后台持续编码，因此暂停无实际意义。
        return "unsupported_always"

    async def _on_subprocess_message(self, msg: Any):
        self._current_status = msg
        # 转码子进程不支持暂停（FFmpeg 子进程无法真正挂起），
        # 在 progress 消息中透传 pausable=False，前端据此禁用暂停按钮。
        if isinstance(msg, dict) and msg.get("type") == "progress":
            msg = {**msg, "pausable": await self.is_pausable()}
        await self.send_json(msg)


if __name__ == "__main__":
    pass
