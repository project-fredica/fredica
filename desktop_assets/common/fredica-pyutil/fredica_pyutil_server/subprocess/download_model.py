# -*- coding: UTF-8 -*-
"""
Whisper 模型下载子进程 worker。

在独立子进程中运行，通过 monkey-patch 实现逐字节进度回传。
由 routes/asr.py 的 DownloadWhisperModelTaskEndpoint 调用。

# ── 调用路径分析（huggingface_hub 下载链）─────────────────────────────────────
#
# faster_whisper/utils.py : download_model()
#   └─ line 116: huggingface_hub.snapshot_download(repo_id, **kwargs)
#         ↓  （huggingface_hub/_snapshot_download.py : snapshot_download()）
#         └─ line 430: hf_hub_download(repo_id, filename=repo_file, ...)
#               ↓  （huggingface_hub/file_download.py : hf_hub_download()）
#               └─ line 1202 / 1413: _download_to_tmp_and_move(...)
#                     ↓  （同文件 : _download_to_tmp_and_move()）
#                     └─ line 1842: http_get(url, f, resume_size=..., ...)
#
# 为什么 patch huggingface_hub.file_download.http_get 有效：
#   `_download_to_tmp_and_move` 通过"模块全局名"调用 http_get，
#   Python 运行时在 file_download 模块的 __dict__ 中查找该名字。
#   只要在调用发生前把 _fd.http_get 替换掉，就能拦截所有文件的写入流。
#
# 为什么 import 必须在 patch 之前：
#   `from faster_whisper import download_model` 会触发 faster_whisper/__init__.py
#   → faster_whisper/utils.py → import huggingface_hub 的完整加载链。
#   虽然此时尚未发起下载，但若 huggingface_hub 内部在 import 阶段将 http_get
#   绑定到局部变量（未来版本可能发生），patch 将失效。
#   因此保守做法：先 patch，再 import。
#
# http_get 真实签名（huggingface_hub >= 0.30，实测安装版本）：
#   def http_get(url, temp_file, *, resume_size=0, headers=None,
#                expected_size=None, displayed_filename=None,
#                tqdm_class=None, _nb_retries=5, _tqdm_bar=None)
#   注意：无 proxies / filename 参数，传入会导致 TypeError，patch 静默失败。
#   因此 _patched_http_get 使用纯 **kwargs 透传，与版本签名解耦。
#
# ── Xet Storage 绕过问题（huggingface_hub >= 1.0）──────────────────────────
#
# huggingface_hub 1.x 引入 Xet Storage 下载路径。当 hf_xet 包已安装且 repo
# 支持 Xet 时，_download_to_tmp_and_move() 走 xet_get() 而非 http_get()，
# 导致我们的 monkey-patch 永远不会被调用。
# 解决方案：在子进程中设置 HF_HUB_DISABLE_XET=1，强制回退到 http_get() 路径。
"""

import os

# ── 必须在任何 huggingface_hub import 之前禁用 Xet Storage ──────────────────
# huggingface_hub 1.x 的 _download_to_tmp_and_move() 优先走 xet_get()，
# 完全绕过 http_get()，导致我们的 monkey-patch 无法拦截下载流。
os.environ["HF_HUB_DISABLE_XET"] = "1"


def download_model_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    在子进程中下载 faster-whisper 模型，通过 monkey-patch 实现逐字节进度回传。

    param 支持的字段：
        model_name  (str)  必填，模型名称（如 "large-v3"）
        proxy       (str)  可选，HTTP 代理地址
        models_dir  (str)  可选，本地缓存目录

    向 status_queue 推送的消息格式：
        {"type": "log",         "level": str, "message": str}
        {"type": "progress",    "percent": int}   // 0-100，基于当前文件已下载字节
        {"type": "status_text", "text": str}       // 阶段描述（Kotlin 写入 Task.statusText）
        {"type": "done",        "model_name": str, "path": str}
        {"type": "error",       "message": str}
    """
    model_name: str = param["model_name"]
    proxy: str = param.get("proxy", "")
    models_dir: str = param.get("models_dir", "")

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    def _status(text: str):
        status_queue.put({"type": "status_text", "text": text})

    _log("info", f"[asr-download] 开始下载模型 model={model_name} proxy={proxy!r}")

    try:
        # 代理：设置环境变量（子进程内生效，不影响主进程）
        if proxy:
            os.environ["HTTPS_PROXY"] = proxy
            os.environ["HTTP_PROXY"] = proxy
            _log("info", f"[asr-download] 使用代理 {proxy}")

        # ── 关键：monkey-patch 必须在 `from faster_whisper import ...` 之前执行 ──
        # 原因见模块顶部注释。patch 替换 _fd.http_get 属性，
        # _download_to_tmp_and_move 调用时运行时解析模块 __dict__，因此拦截生效。
        _status(f"正在准备下载模型 {model_name}…")
        import huggingface_hub.file_download as _fd
        _orig_http_get = _fd.http_get
        _log("info", f"[asr-download] monkey-patch 挂载: huggingface_hub.file_download.http_get id={id(_orig_http_get)}")

        def _patched_http_get(url, temp_file, **kwargs):
            """
            替换 huggingface_hub.file_download.http_get。
            使用纯 **kwargs 透传避免与版本签名耦合（proxies/filename 等参数在不同
            版本中已被移除，显式传入会导致 TypeError 且 patch 静默失败）。
            """
            expected_size = kwargs.get("expected_size")
            resume_size = kwargs.get("resume_size", 0)
            _log("info", f"[asr-download] [patch] 拦截到下载请求 url={url!r} expected_size={expected_size} resume_size={resume_size}")
            # 从 URL 中提取文件名，更新 statusText 让用户知道当前正在下载哪个文件
            filename = url.split("/")[-1].split("?")[0] or "文件"
            _status(f"正在下载 {model_name} / {filename}…")
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
            return _orig_http_get(url, temp_file, **kwargs)

        _fd.http_get = _patched_http_get
        _log("info", f"[asr-download] monkey-patch 已生效: _fd.http_get id={id(_fd.http_get)}")

        # ── 在 patch 完成后才 import faster_whisper（保守做法，见模块注释）─────
        from faster_whisper import download_model

        status_queue.put({"type": "progress", "percent": 0})
        _status(f"正在下载模型 {model_name}…")

        dl_kwargs = {}
        if models_dir:
            dl_kwargs["cache_dir"] = models_dir

        model_path = download_model(model_name, **dl_kwargs)

        if cancel_event.is_set():
            status_queue.put({"type": "cancelled"})
            return

        status_queue.put({"type": "progress", "percent": 100})
        _status(f"模型 {model_name} 下载完成")
        _log("info", f"[asr-download] 下载完成 path={model_path!r}")
        status_queue.put({"type": "done", "model_name": model_name, "path": str(model_path)})

    except InterruptedError:
        status_queue.put({"type": "cancelled"})
    except Exception as e:
        _log("error", f"[asr-download] 下载失败: {e}")
        status_queue.put({"type": "error", "message": str(e)})
