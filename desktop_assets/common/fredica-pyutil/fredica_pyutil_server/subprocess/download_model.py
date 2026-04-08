# -*- coding: UTF-8 -*-
"""
Whisper 模型下载子进程 worker。

在独立子进程中运行，通过 monkey-patch 实现逐字节进度回传。
由 routes/asr.py 的 DownloadWhisperModelTaskEndpoint 调用。
"""

import os


def download_model_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中下载 faster-whisper 模型，通过 monkey-patch 实现逐字节进度回传。

    param 支持的字段：
        model_name  (str)  必填，模型名称（如 "large-v3"）
        proxy       (str)  可选，HTTP 代理地址
        models_dir  (str)  可选，本地缓存目录

    向 status_queue 推送的消息格式：
        {"type": "log",      "level": str, "message": str}
        {"type": "progress", "percent": int}   // 0-100，基于当前文件已下载字节
        {"type": "done",     "model_name": str, "path": str}
        {"type": "error",    "message": str}
    """
    from faster_whisper import download_model

    model_name: str = param["model_name"]
    proxy: str = param.get("proxy", "")
    models_dir: str = param.get("models_dir", "")

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    _log("info", f"[asr-download] 开始下载模型 model={model_name} proxy={proxy!r}")

    try:
        # 代理：设置环境变量（子进程内生效，不影响主进程）
        if proxy:
            os.environ["HTTPS_PROXY"] = proxy
            os.environ["HTTP_PROXY"] = proxy
            _log("info", f"[asr-download] 使用代理 {proxy}")

        # Monkey-patch huggingface_hub.file_download.http_get
        # 拦截每个文件的下载流，通过包装 temp_file.write 计算进度
        import huggingface_hub.file_download as _fd
        _orig_http_get = _fd.http_get
        _log("info", f"[asr-download] monkey-patch 挂载: huggingface_hub.file_download.http_get id={id(_orig_http_get)}")

        def _patched_http_get(url, temp_file, *, proxies=None, resume_size=0,
                              headers=None, expected_size=None, filename=None,
                              displayed_filename=None, **kwargs):
            _log("info", f"[asr-download] [patch] 拦截到下载请求 url={url!r} expected_size={expected_size} resume_size={resume_size}")
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
                    pct = min(99, int(downloaded[0] / expected_size * 100))
                    status_queue.put({"type": "progress", "percent": pct})
                    # 每 10% 打一条日志，避免刷屏
                    if pct // 10 > _last_log_pct[0] // 10:
                        _log("info", f"[asr-download] [patch] 写入进度 {pct}%  ({downloaded[0]}/{expected_size} bytes)")
                        _last_log_pct[0] = pct
                return orig_write(data)

            temp_file.write = _write
            return _orig_http_get(url, temp_file, proxies=proxies,
                                  resume_size=resume_size, headers=headers,
                                  expected_size=expected_size, filename=filename,
                                  displayed_filename=displayed_filename, **kwargs)

        _fd.http_get = _patched_http_get
        _log("info", f"[asr-download] monkey-patch 已生效: _fd.http_get id={id(_fd.http_get)}")

        status_queue.put({"type": "progress", "percent": 0})

        kwargs = {}
        if models_dir:
            kwargs["cache_dir"] = models_dir

        model_path = download_model(model_name, **kwargs)

        if cancel_event.is_set():
            status_queue.put({"type": "cancelled"})
            return

        status_queue.put({"type": "progress", "percent": 100})
        _log("info", f"[asr-download] 下载完成 path={model_path!r}")
        status_queue.put({"type": "done", "model_name": model_name, "path": str(model_path)})

    except InterruptedError:
        status_queue.put({"type": "cancelled"})
    except Exception as e:
        _log("error", f"[asr-download] 下载失败: {e}")
        status_queue.put({"type": "error", "message": str(e)})
