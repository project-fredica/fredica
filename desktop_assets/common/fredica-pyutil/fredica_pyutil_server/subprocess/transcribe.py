# -*- coding: UTF-8 -*-
"""
faster-whisper 转录服务。

提供 FasterWhisperTranscribeTaskEndpoint：在子进程中运行 faster-whisper，
每转录完一个片段立即通过 WebSocket 推送给客户端。
"""
import time
from typing import Any

from loguru import logger

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess


# ---------------------------------------------------------------------------
# 子进程入口函数（必须定义在模块级以确保可 pickle）
# ---------------------------------------------------------------------------

def _faster_whisper_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中运行 faster-whisper 转录，将结果逐片段推送至 status_queue。

    param 支持的字段：
        audio_path   (str)       必填，音频文件路径
        model_name   (str)       模型名称，默认 "large-v3"
        language     (str|None)  语言代码，None 为自动检测，默认 None
        device       (str)       "auto" | "cuda" | "cpu"，默认 "auto"
        compute_type (str)       "float16" | "int8" 等，默认 "float16"

    向 status_queue 推送的消息格式：
        {"type": "log",     "level": str, "message": str}   # 进度日志（父进程写 logger）
        {"type": "segment", "start": float, "end": float, "text": str}
        {"type": "done",    "text": str, "language": str}
        {"type": "error",   "error": str}
    """
    # 在子进程内延迟导入，避免占用主进程内存
    from faster_whisper import WhisperModel

    audio_path: str = param["audio_path"]
    model_name: str = param.get("model_name", "large-v3")
    language = param.get("language", None)
    device: str = param.get("device", "auto")
    compute_type: str = param.get("compute_type", "float16")

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    _log("info", f"[whisper] 加载模型 model={model_name} device={device} compute_type={compute_type}")
    t_load = time.monotonic()

    try:
        model = WhisperModel(model_name, device=device, compute_type=compute_type)
        _log("info", f"[whisper] 模型加载完成 耗时={time.monotonic() - t_load:.1f}s  文件={audio_path!r}")

        t_trans = time.monotonic()
        segments_iter, info = model.transcribe(audio_path, language=language)
        duration = getattr(info, "duration", 0) or 0
        _log("info", f"[whisper] 检测语言={info.language} 音频时长={duration:.1f}s")

        collected_texts = []
        seg_count = 0
        last_pct = -1

        for seg in segments_iter:
            # 响应取消信号
            if cancel_event.is_set():
                _log("info", "[whisper] 收到取消信号，中止转录")
                break
            # 支持暂停
            resume_event.wait()
            if cancel_event.is_set():
                break

            text = seg.text.strip()
            collected_texts.append(text)
            seg_count += 1

            # 每 10% 进度或每 20 段输出一次日志
            pct = int(seg.end / duration * 100) if duration > 0 else 0
            if pct // 10 > last_pct // 10 or seg_count % 20 == 1:
                elapsed = time.monotonic() - t_trans
                _log("info",
                     f"[whisper] 进度 {pct}%  [{seg.start:.1f}s → {seg.end:.1f}s]  "
                     f"已处理 {seg_count} 段  耗时 {elapsed:.0f}s")
                last_pct = pct

            status_queue.put({
                "type": "segment",
                "start": seg.start,
                "end": seg.end,
                "text": text,
            })

        if not cancel_event.is_set():
            total_chars = sum(len(t) for t in collected_texts)
            elapsed = time.monotonic() - t_trans
            _log("info",
                 f"[whisper] 转录完成  共 {seg_count} 段  {total_chars} 字  "
                 f"语言={info.language}  耗时={elapsed:.1f}s")
            status_queue.put({
                "type": "done",
                "text": " ".join(collected_texts),
                "language": info.language,
            })

    except Exception as e:
        _log("error", f"[whisper] 转录出错: {e}")
        status_queue.put({"type": "error", "error": str(e)})


# ---------------------------------------------------------------------------
# TaskEndpointInSubProcess 实现
# ---------------------------------------------------------------------------

class FasterWhisperTranscribeTaskEndpoint(TaskEndpointInSubProcess):
    """
    在子进程中运行 faster-whisper 转录，每片段立即通过 WebSocket 推送给客户端。

    init_param_and_run data 格式：
        {
            "audio_path":    str,        必填，音频文件路径
            "model_name":    str,        模型名称，默认 "large-v3"
            "language":      str|null,   语言代码，null 为自动检测
            "device":        str,        "auto" | "cuda" | "cpu"（默认 "auto"）
            "compute_type":  str,        "float16" | "int8"（默认 "float16"）
        }

    WebSocket 推送消息格式：
        {"type": "segment", "start": float, "end": float, "text": str}
        {"type": "done",    "text": str, "language": str}
        {"type": "error",   "error": str}
        （"log" 类型消息由父进程写入 logger，不转发给客户端）
    """

    def _get_process_target(self):
        return _faster_whisper_worker

    async def _on_subprocess_message(self, msg: Any):
        if msg.get("type") == "log":
            level = msg.get("level", "info")
            getattr(logger, level, logger.info)(msg.get("message", ""))
            return  # log 消息不推送给 WebSocket 客户端
        self._current_status = msg
        await self.send_json(msg)


if __name__ == '__main__':
    pass
