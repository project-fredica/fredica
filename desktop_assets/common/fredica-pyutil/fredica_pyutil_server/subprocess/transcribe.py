# -*- coding: UTF-8 -*-
"""
faster-whisper 转录服务。

提供 FasterWhisperTranscribeTaskEndpoint：在子进程中运行 faster-whisper，
每转录完一个片段立即通过 WebSocket 推送给客户端。

路由示例（在 routes/ 下注册）：
    @router.websocket("/transcribe-task")
    async def transcribe_task_endpoint(websocket: WebSocket):
        endpoint = FasterWhisperTranscribeTaskEndpoint(
            tag="whisper", websocket=websocket
        )
        await endpoint.start_and_wait()
"""
from typing import Any

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess


# ---------------------------------------------------------------------------
# 子进程入口函数（必须定义在模块级以确保可 pickle）
# ---------------------------------------------------------------------------

def _faster_whisper_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中运行 faster-whisper 转录，将结果逐片段推送至 status_queue。

    :param param:         init_param_and_run 传入的参数字典，字段见下方
    :param status_queue:  multiprocessing.Queue，用于向父进程推送消息
    :param cancel_event:  multiprocessing.Event，set 时应尽快退出
    :param resume_event:  multiprocessing.Event，clear 时应 wait()（暂停）

    param 支持的字段：
        audio_path   (str)       必填，音频文件路径
        model_name   (str)       模型名称，默认 "large-v3"
        language     (str|None)  语言代码，None 为自动检测，默认 None
        device       (str)       "auto" | "cuda" | "cpu"，默认 "auto"
        compute_type (str)       "float16" | "int8" 等，默认 "float16"

    向 status_queue 推送的消息格式：
        # 每个转录片段
        {"type": "segment", "start": float, "end": float, "text": str}
        # 全部完成
        {"type": "done", "text": str, "language": str}
        # 出错
        {"type": "error", "error": str}
    """
    # 在子进程内延迟导入，避免占用主进程内存
    from faster_whisper import WhisperModel

    audio_path: str = param["audio_path"]
    model_name: str = param.get("model_name", "large-v3")
    language = param.get("language", None)
    device: str = param.get("device", "auto")
    compute_type: str = param.get("compute_type", "float16")

    try:
        model = WhisperModel(model_name, device=device, compute_type=compute_type)
        segments_iter, info = model.transcribe(audio_path, language=language)

        collected_texts = []
        for seg in segments_iter:
            # 响应取消信号
            if cancel_event.is_set():
                break
            # 支持暂停（暂停时阻塞在此直到 resume）
            resume_event.wait()
            if cancel_event.is_set():
                break

            text = seg.text.strip()
            collected_texts.append(text)
            status_queue.put({
                "type": "segment",
                "start": seg.start,
                "end": seg.end,
                "text": text,
            })

        if not cancel_event.is_set():
            status_queue.put({
                "type": "done",
                "text": " ".join(collected_texts),
                "language": info.language,
            })

    except Exception as e:
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

    WebSocket 推送消息格式（由子进程产生，父进程透传）：
        {"type": "segment", "start": float, "end": float, "text": str}
        {"type": "done",    "text": str, "language": str}
        {"type": "error",   "error": str}

    status 命令返回最近一条子进程消息。
    """

    def _get_process_target(self):
        return _faster_whisper_worker

    async def _on_subprocess_message(self, msg: Any):
        """收到子进程消息后：更新状态快照，并主动推送至 WebSocket。"""
        self._current_status = msg
        await self.send_json(msg)


if __name__ == '__main__':
    pass
