# -*- coding: UTF-8 -*-
"""
faster-whisper 转录服务。

提供 FasterWhisperTranscribeTaskEndpoint：在子进程中运行 faster-whisper，
每转录完一个片段立即通过 WebSocket 推送给客户端。

# ── 模型下载 monkey-patch 调用路径分析 ──────────────────────────────────────
#
# WhisperModel.__init__（allow_download 时触发在线下载）
#   └─ faster_whisper/utils.py line 116: huggingface_hub.snapshot_download(repo_id, **kwargs)
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
# 为什么 patch 必须在 import faster_whisper 之前：
#   `from faster_whisper import WhisperModel` 会触发 huggingface_hub 的完整加载链。
#   保守做法：先 patch，再 import，确保模块 __dict__ 中的 http_get 始终是我们的版本。
#
# http_get 真实签名（huggingface_hub >= 0.30）：
#   def http_get(url, temp_file, *, resume_size=0, headers=None,
#                expected_size=None, displayed_filename=None,
#                tqdm_class=None, _nb_retries=5, _tqdm_bar=None)
#   注意：无 proxies / filename 参数，_patched 使用纯 **kwargs 透传以与版本解耦。
#
# ── Xet Storage 绕过问题（huggingface_hub >= 1.0）──────────────────────────
#
# huggingface_hub 1.x 引入 Xet Storage 下载路径。当 hf_xet 包已安装且 repo
# 支持 Xet 时，_download_to_tmp_and_move() 走 xet_get() 而非 http_get()，
# 导致我们的 monkey-patch 永远不会被调用。
# 解决方案：在子进程中设置 HF_HUB_DISABLE_XET=1，强制回退到 http_get() 路径。
"""
import os
import time
from typing import Any

# ── 必须在任何 huggingface_hub import 之前禁用 Xet Storage ──────────────────
# huggingface_hub 1.x 的 _download_to_tmp_and_move() 优先走 xet_get()，
# 完全绕过 http_get()，导致我们的 monkey-patch 无法拦截下载流。
os.environ["HF_HUB_DISABLE_XET"] = "1"

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
        {"type": "log",         "level": str, "message": str}   # 进度日志（父进程写 logger）
        {"type": "progress",    "progress": int}                 # 进度百分比（0-100）
        {"type": "status_text", "text": str}                     # 阶段描述（Kotlin 写入 Task.statusText）
        {"type": "segment",     "start": float, "end": float, "text": str}
        {"type": "done",        "text": str, "language": str}
        {"type": "error",       "error": str}
    """
    # ── 读取参数（在任何第三方 import 之前）─────────────────────────────────────
    audio_path: str = param["audio_path"]
    model_name: str = param.get("model_name", "large-v3")
    language_param = param.get("language", None)
    if language_param == "auto":
        language = None  # faster-whisper 期望 None 表示自动检测
    else:
        language = language_param
    device: str = param.get("device", "auto")
    compute_type: str = param.get("compute_type", "float16")
    allow_download: bool = bool(param.get("allow_download", False))
    proxy: str = param.get("proxy", "")

    def _log(level: str, msg: str):
        status_queue.put({"type": "log", "level": level, "message": msg})

    def _progress(pct: int):
        status_queue.put({"type": "progress", "percent": pct})

    def _status(text: str):
        status_queue.put({"type": "status_text", "text": text})

    _log("info", f"[whisper] 收到参数 language={language_param!r} effective={language!r} model={model_name} device={device} compute_type={compute_type}")
    _log("info", f"[whisper] 加载模型 model={model_name} device={device} compute_type={compute_type} allow_download={allow_download} proxy={proxy!r}")

    # ── 关键：monkey-patch 必须在 `from faster_whisper import ...` 之前执行 ────
    # faster_whisper 的 import 链会触发 huggingface_hub 模块加载，
    # 导致 huggingface_hub.file_download.http_get 的内部名称绑定被固定。
    # 如果在 import 之后才 patch，huggingface_hub 内部已经持有原始函数引用，
    # patch 将永远不会被调用。因此必须在 import faster_whisper 之前完成 patch。
    _patch_orig_http_get = None  # 保存原始函数引用，供 _patched 闭包使用

    if allow_download:
        _log("debug", f"[whisper] allow_download=True，在 import faster_whisper 之前安装 monkey-patch 和代理")
        # 1) 设置代理环境变量（huggingface_hub 在 import 时可能读取）
        if proxy:
            os.environ["HTTPS_PROXY"] = proxy
            os.environ["HTTP_PROXY"] = proxy
            _log("info", f"[whisper] 使用代理 {proxy}")

        # 2) 挂载 monkey-patch 到 huggingface_hub.file_download.http_get
        #    拦截下载流以推送进度、支持取消/暂停
        #
        #    实际调用链：snapshot_download → hf_hub_download → _hf_hub_download_to_cache_dir
        #    → _download_to_tmp_and_move → http_get（同模块内 module-level 名称，运行时解析）
        #
        #    http_get 真实签名（huggingface_hub >= 0.30）：
        #      http_get(url, temp_file, *, resume_size=0, headers=None,
        #               expected_size=None, displayed_filename=None,
        #               tqdm_class=None, _nb_retries=5, _tqdm_bar=None)
        #    注意：没有 proxies / filename 参数，传入会导致 TypeError
        import huggingface_hub.file_download as _fd
        _patch_orig_http_get = _fd.http_get
        _log("info", f"[whisper] monkey-patch 挂载: huggingface_hub.file_download.http_get id={id(_patch_orig_http_get)}")

        def _patched(url, temp_file, **kwargs):
            """
            替换 huggingface_hub.file_download.http_get，
            在写入 temp_file 时拦截字节流以推送下载进度、响应取消/暂停。
            使用 **kwargs 透传所有参数，避免与 huggingface_hub 版本签名耦合。
            """
            expected_size = kwargs.get("expected_size")
            resume_size = kwargs.get("resume_size", 0)
            # 从 URL 中提取文件名，更新 statusText
            filename = url.split("/")[-1].split("?")[0] or "文件"
            _log("info", f"[whisper] [patch] 拦截到下载请求 url={url!r} expected_size={expected_size} resume_size={resume_size}")
            if url.endswith("/model.bin"):
                # 若有 resume_size，计算初始百分比并立即上报
                if expected_size and expected_size > 0 and resume_size:
                    _init_pct = min(14, int(resume_size / expected_size * 14))
                    _progress(_init_pct)
                    _status(f"正在下载模型 {model_name} / {filename}（{_init_pct}%）…")
                else:
                    _status(f"正在下载模型 {model_name} / {filename}…")
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
                    pct_download = min(99, int(downloaded[0] / expected_size * 99))
                    pct = min(14, int(downloaded[0] / expected_size * 14))  # 映射到 0-14%
                    _progress(pct)
                    _status(f"正在下载模型 {model_name} / {filename}（{pct_download}%）…")
                    if pct > _last_log_pct[0]:
                        _log("debug", f"[whisper] [patch] 下载进度 {pct_download}%  {downloaded[0]}/{expected_size} bytes")
                        _last_log_pct[0] = pct
                return orig_write(data)

            temp_file.write = _write
            return _patch_orig_http_get(url, temp_file, **kwargs)

        _fd.http_get = _patched
        _log("info", f"[whisper] monkey-patch 已生效: _fd.http_get id={id(_fd.http_get)}")

    # ── 现在才 import faster_whisper（huggingface_hub 已被 patch）──────────────
    _log("debug", "[whisper] 开始 import faster_whisper ...")
    from faster_whisper import WhisperModel
    _log("debug", "[whisper] import faster_whisper 完成")

    t_load = time.monotonic()

    # ── 模型加载（占单 chunk 总进度的前 15%）──────────────────────────────────
    _COMPUTE_TYPE_FALLBACKS = ["float16", "float32", "int8"]

    def _load_model(local_only: bool, ct: str) -> "WhisperModel":
        return WhisperModel(model_name, device=device, compute_type=ct, local_files_only=local_only)

    def _load_model_with_compute_fallback(local_only: bool):
        """
        尝试 compute_type 降级链直到加载成功，返回 (model, actual_compute_type)。

        触发场景（实测错误）：
            Requested float16 compute type, but the target device or backend
            do not support efficient float16 computation.
        降级顺序：float16 → float32 → int8
        """
        types_to_try = _COMPUTE_TYPE_FALLBACKS if compute_type == "float16" else [compute_type] + [
            t for t in _COMPUTE_TYPE_FALLBACKS if t != compute_type
        ]
        _log("debug", f"[whisper] _load_model_with_compute_fallback: local_only={local_only} types_to_try={types_to_try}")
        last_err = None
        for ct in types_to_try:
            try:
                if ct != compute_type:
                    _log("warning", f"[whisper] compute_type={compute_type!r} 不支持，降级尝试 {ct!r}")
                _log("debug", f"[whisper] 调用 WhisperModel({model_name!r}, device={device!r}, compute_type={ct!r}, local_files_only={local_only})")
                m = _load_model(local_only=local_only, ct=ct)
                _log("debug", f"[whisper] WhisperModel 加载成功 compute_type={ct!r}")
                if ct != compute_type:
                    _log("info", f"[whisper] 以 compute_type={ct!r} 加载成功")
                return m, ct
            except Exception as e:
                if "compute type" in str(e).lower() or "float16" in str(e):
                    _log("warning", f"[whisper] compute_type={ct!r} 加载失败: {e}")
                    last_err = e
                    continue
                raise  # 非 compute_type 错误直接抛出
        raise last_err  # type: ignore

    def _is_device_error(err: Exception) -> bool:
        """判断是否为设备相关错误（不可重试）：compute_type / 内存 / 显存 / CUDA。"""
        err_str = str(err).lower()
        device_keywords = [
            "compute type",
            "float16",
            "out of memory",
            "cuda",
            "oom",
            "cublas",
            "cudnn",
            "gpu memory",
            "vram",
        ]
        return any(kw in err_str for kw in device_keywords)

    def _is_incomplete_model(err_str: str) -> bool:
        # 触发场景（实测错误）：
        #   Unable to open file 'model.bin' in model
        #   'C:\Users\...\snapshots\536b0662742c02347bc0e980a01041f333bce120'
        # 原因：上次下载中断导致 snapshot 目录存在但 model.bin 不完整
        return "Unable to open file" in err_str and "model.bin" in err_str

    def _delete_incomplete_snapshot(err_str: str):
        """从报错信息中提取 snapshot 路径并删除，以便重新下载。"""
        import re
        import shutil
        m = re.search(r"model '([^']+)'", err_str)
        if not m:
            _log("warning", "[whisper] 无法从错误信息中提取 snapshot 路径，跳过清理")
            return
        snap_path = m.group(1)
        if not os.path.exists(snap_path):
            _log("warning", f"[whisper] snapshot 路径不存在，跳过清理: {snap_path!r}")
            return
        _log("warning", f"[whisper] 检测到不完整的模型文件，删除 snapshot 目录: {snap_path!r}")
        try:
            shutil.rmtree(snap_path)
            _log("info", f"[whisper] 已清理 snapshot: {snap_path!r}")
        except Exception as e2:
            _log("error", f"[whisper] 清理 snapshot 失败: {e2}")

    try:
        _status(f"正在加载模型 {model_name}…")
        try:
            model, actual_compute_type = _load_model_with_compute_fallback(local_only=True)
        except Exception as e:
            _log("debug", f"[whisper] 模型本地加载初次失败: ${e}")
            err_str = str(e)
            # 判断是否为"本地无缓存"错误
            is_missing = (
                "local_files_only" in err_str
                or "cached snapshot" in err_str
                or "Cannot find an appropriate" in err_str
            )
            # 检测文件不完整（上次下载中断）
            is_incomplete = _is_incomplete_model(err_str)
            if is_incomplete and allow_download:
                _delete_incomplete_snapshot(err_str)
                is_missing = True  # 清理后当作 missing 走下载流程
            if is_missing and allow_download:
                # proxy 由 Kotlin TranscribeExecutor 注入（优先 AppConfig.proxyUrl，为空时回落系统代理）
                # 代理未传递时触发（实测错误）：
                #   Got: ConnectError: [WinError 10061] 由于目标计算机积极拒绝，无法连接。
                #   An error happened while trying to locate the files on the Hub...
                _log("warning", f"[whisper] 本地未找到模型 {model_name!r}，allow_download=True，开始在线下载… proxy={proxy!r}")
                _status(f"正在下载模型 {model_name}…")
                _progress(0)
                _MAX_DOWNLOAD_RETRIES = 3
                last_download_err = None
                for _attempt in range(1, _MAX_DOWNLOAD_RETRIES + 1):
                    try:
                        model, actual_compute_type = _load_model_with_compute_fallback(local_only=False)
                        last_download_err = None
                        break
                    except Exception as download_err:
                        last_download_err = download_err
                        if _is_device_error(download_err):
                            # 设备相关错误（显存/compute_type 等），不可重试，直接抛出
                            raise
                        _log("warning",
                             f"[whisper] 下载/加载失败（第 {_attempt}/{_MAX_DOWNLOAD_RETRIES} 次）: {download_err}")
                        if _attempt < _MAX_DOWNLOAD_RETRIES:
                            # 清理可能残留的不完整 snapshot，避免下次加载失败
                            _delete_incomplete_snapshot(str(download_err))
                            _retry_delay = 2 * _attempt  # 2s, 4s
                            _log("info", f"[whisper] {_retry_delay}s 后重试下载…")
                            _status(f"下载失败，{_retry_delay}s 后重试（{_attempt}/{_MAX_DOWNLOAD_RETRIES}）…")
                            time.sleep(_retry_delay)
                            _progress(0)
                if last_download_err is not None:
                    raise last_download_err
                _log("info", f"[whisper] 模型下载并加载完成 耗时={time.monotonic() - t_load:.1f}s")
            else:
                # 本地无模型且不允许下载，或其他加载错误，直接上报
                _log("error", f"[whisper] 模型加载失败: {e}")
                status_queue.put({"type": "error", "error": err_str})
                return

        _log("info", f"[whisper] 模型加载完成 actual_compute_type={actual_compute_type!r} 耗时={time.monotonic() - t_load:.1f}s  文件={audio_path!r}")
        # 若实际使用的 compute_type 与请求值不同，上报给父进程缓存，下次直接传入省去重试
        if actual_compute_type != compute_type:
            _log("debug", f"[whisper] 上报 effective_compute_type={actual_compute_type!r}（请求值={compute_type!r}）")
            status_queue.put({"type": "effective_compute_type", "value": actual_compute_type, "model_name": model_name})
        # 模型加载完成，进度推进到 15%
        _progress(15)

        _log("debug", f"[whisper] 开始转录 language={language!r} audio={audio_path!r}")
        t_trans = time.monotonic()
        segments_iter, info = model.transcribe(audio_path, language=language)
        duration = getattr(info, "duration", 0) or 0
        _log("info", f"[whisper] 检测语言={info.language} 置信度={getattr(info, 'language_probability', 0):.2f} 音频时长={duration:.1f}s")
        _status(f"正在转录（语言：{info.language}，时长：{duration:.0f}s）…")

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
        {"type": "effective_compute_type", "value": str, "model_name": str}
            子进程降级成功后上报；父进程转发给 Kotlin，Kotlin 写入 AppConfig 持久化缓存。
        （"log" 类型消息由父进程写入 logger，不转发给客户端）
    """

    # 进程级内存缓存：model_name → 实测可用 compute_type
    # 下次同 model_name 的请求直接注入，省去重试时间
    _effective_compute_type_cache: dict[str, str] = {}

    async def _on_init_param_and_run(self, param: Any):
        """收到 init_param_and_run 后，将缓存的 compute_type 注入 param，再启动子进程。"""
        if isinstance(param, dict):
            model_name = param.get("model_name", "large-v3")
            cached_ct = self._effective_compute_type_cache.get(model_name)
            if cached_ct and "compute_type" not in param:
                param = {**param, "compute_type": cached_ct}
                logger.info(
                    "[FasterWhisperTranscribeTaskEndpoint] 使用缓存 compute_type={!r} model={}",
                    cached_ct, model_name,
                )
        await super()._on_init_param_and_run(param)

    async def _on_subprocess_message(self, msg: Any):
        if msg.get("type") == "log":
            level = msg.get("level", "info")
            getattr(logger, level, logger.info)(msg.get("message", ""))
            return  # log 消息不推送给 WebSocket 客户端
        if msg.get("type") == "effective_compute_type":
            # 子进程找到降级后的 compute_type：写入进程缓存，转发给 Kotlin 持久化
            ct = msg.get("value", "")
            model_name = msg.get("model_name", "")
            if ct and model_name:
                FasterWhisperTranscribeTaskEndpoint._effective_compute_type_cache[model_name] = ct
                logger.info(
                    "[FasterWhisperTranscribeTaskEndpoint] 缓存 effective_compute_type={!r} model={}",
                    ct, model_name,
                )
            # 转发给 Kotlin，由 TranscribeExecutor.onRawMessage 写入 AppConfig
            self._current_status = msg
            await self.send_json(msg)
            return
        self._current_status = msg
        await self.send_json(msg)

    def _get_process_target(self):
        return _faster_whisper_worker


if __name__ == '__main__':
    pass
