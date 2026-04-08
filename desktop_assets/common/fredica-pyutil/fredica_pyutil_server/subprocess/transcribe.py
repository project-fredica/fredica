# -*- coding: UTF-8 -*-
"""
faster-whisper 转录服务。

提供 FasterWhisperTranscribeTaskEndpoint：在子进程中运行 faster-whisper，
每转录完一个片段立即通过 WebSocket 推送给客户端。
"""
import os
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
        audio_path    (str)       必填，音频文件路径
        model_name    (str)       模型名称，默认 "large-v3"
        language      (str|None)  语言代码，None 为自动检测，默认 None
        device        (str)       "auto" | "cuda" | "cpu"，默认 "auto"
        compute_type  (str)       "float16" | "int8" 等，默认 "float16"
        allow_download (bool)     是否允许在线下载模型，默认 False

    向 status_queue 推送的消息格式：
        {"type": "log",      "level": str, "message": str}   # 进度日志（父进程写 logger）
        {"type": "progress", "progress": int}                 # 进度百分比（0-100）
        {"type": "segment",  "start": float, "end": float, "text": str}
        {"type": "done",     "text": str, "language": str}
        {"type": "error",    "error": str}
    """
    # 在子进程内延迟导入，避免占用主进程内存
    from faster_whisper import WhisperModel

    audio_path: str = param["audio_path"]
    model_name: str = param.get("model_name", "large-v3")
    language = param.get("language", None)
    device: str = param.get("device", "auto")
    compute_type: str = param.get("compute_type", "float16")
    allow_download: bool = bool(param.get("allow_download", False))

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    def _progress(pct: int):
        status_queue.put({"type": "progress", "progress": pct})

    _log("info", f"[whisper] 加载模型 model={model_name} device={device} compute_type={compute_type} allow_download={allow_download}")
    t_load = time.monotonic()

    # ── 模型加载（占单 chunk 总进度的前 15%）──────────────────────────────────
    def _load_model(local_only: bool) -> "WhisperModel":
        return WhisperModel(model_name, device=device, compute_type=compute_type, local_files_only=local_only)

    def _install_download_patch(proxy: str):
        """
        挂载 monkey-patch 到 huggingface_hub.file_download.http_get，
        用于在 allow_download=True 时拦截下载流并推送进度。
        """
        import huggingface_hub.file_download as _fd
        _orig = _fd.http_get
        _log("info", f"[whisper] monkey-patch 挂载: huggingface_hub.file_download.http_get id={id(_orig)}")

        def _patched(url, temp_file, *, proxies=None, resume_size=0,
                     headers=None, expected_size=None, filename=None,
                     displayed_filename=None, **kwargs):
            _log("info", f"[whisper] [patch] 拦截到下载请求 url={url!r} expected_size={expected_size} resume_size={resume_size}")
            downloaded = [resume_size or 0]
            orig_write = temp_file.write
            _last_log_pct = [-1]

            def _write(data):
                if cancel_event.is_set():
                    raise InterruptedError("download cancelled")
                resume_event.wait()
                if cancel_event.is_set():
                    raise InterruptedError("download cancelled")
                downloaded[0] += len(data)
                if expected_size and expected_size > 0:
                    pct = min(14, int(downloaded[0] / expected_size * 14))  # 映射到 0-14%
                    _progress(pct)
                    if pct // 3 > _last_log_pct[0] // 3:
                        _log("info", f"[whisper] [patch] 下载进度 {downloaded[0]}/{expected_size} bytes")
                        _last_log_pct[0] = pct
                return orig_write(data)

            temp_file.write = _write
            return _orig(url, temp_file, proxies=proxies, resume_size=resume_size,
                         headers=headers, expected_size=expected_size, filename=filename,
                         displayed_filename=displayed_filename, **kwargs)

        _fd.http_get = _patched
        _log("info", f"[whisper] monkey-patch 已生效: _fd.http_get id={id(_fd.http_get)}")

        if proxy:
            os.environ["HTTPS_PROXY"] = proxy
            os.environ["HTTP_PROXY"] = proxy
            _log("info", f"[whisper] 使用代理 {proxy}")

    try:
        try:
            model = _load_model(local_only=True)
        except Exception as e:
            err_str = str(e)
            # 判断是否为"本地无缓存"错误
            is_missing = (
                "local_files_only" in err_str
                or "cached snapshot" in err_str
                or "Cannot find an appropriate" in err_str
            )
            if is_missing and allow_download:
                proxy: str = param.get("proxy", "")
                _log("warning", f"[whisper] 本地未找到模型 {model_name!r}，allow_download=True，开始在线下载… proxy={proxy!r}")
                _install_download_patch(proxy)
                _progress(0)
                model = _load_model(local_only=False)
                _log("info", f"[whisper] 模型下载并加载完成 耗时={time.monotonic() - t_load:.1f}s")
            else:
                # 本地无模型且不允许下载，或其他加载错误，直接上报
                _log("error", f"[whisper] 模型加载失败: {e}")
                status_queue.put({"type": "error", "error": err_str})
                return

        _log("info", f"[whisper] 模型加载完成 耗时={time.monotonic() - t_load:.1f}s  文件={audio_path!r}")
        # 模型加载完成，进度推进到 15%
        _progress(15)

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

            # 转录进度映射到 15%–100%
            raw_pct = int(seg.end / duration * 100) if duration > 0 else 0
            mapped_pct = 15 + int(raw_pct * 85 / 100)
            if raw_pct // 10 > last_pct // 10 or seg_count % 20 == 1:
                elapsed = time.monotonic() - t_trans
                _log("info",
                     f"[whisper] 进度 {raw_pct}%  [{seg.start:.1f}s → {seg.end:.1f}s]  "
                     f"已处理 {seg_count} 段  耗时 {elapsed:.0f}s")
                last_pct = raw_pct
            _progress(mapped_pct)

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
