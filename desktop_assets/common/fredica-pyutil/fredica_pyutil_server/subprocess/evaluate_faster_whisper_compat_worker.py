# -*- coding: UTF-8 -*-
"""
Whisper 兼容性评估子进程 worker。

在独立子进程中运行，避免主进程导入 torch / faster_whisper。
由 routes/asr.py 的 EvaluateWhisperCompatTaskEndpoint 调用。
"""
from loguru import logger

_WHISPER_MODELS_ORDER = ["tiny", "base", "small", "medium", "large-v2", "large-v3"]
_COMPUTE_TYPES = ["float16", "int8_float16", "int8", "float32"]


def evaluate_faster_whisper_compat_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    评估本地 GPU 对 Whisper 各 compute_type / 模型的兼容性。

    param 支持的字段：
        proxy       (str)  可选，HTTP 代理地址
        models_dir  (str)  可选，本地模型缓存目录

    向 status_queue 推送的消息格式：
        {"type": "local_models",       "models": [...]}
        {"type": "device_info",        "info": {...}}
        {"type": "compute_type_result","compute_type": str, "supported": bool, "error": str}
        {"type": "download_check",     "reachable": bool, "via_proxy": bool}
        {"type": "model_support",      "model": str, "supported": bool, "vram_mb": int}
        {"type": "done",               "result": {...}}
        {"type": "error",              "message": str}
    """
    import gc
    import os
    import urllib.request
    from pathlib import Path

    proxy: str = param.get("proxy", "")
    models_dir: str = param.get("models_dir", "")

    if proxy:
        logger.info("faster whisper compat worker proxy : {}", proxy)
        os.environ["HTTPS_PROXY"] = proxy
        os.environ["HTTP_PROXY"] = proxy

    def _put(msg):
        status_queue.put(msg)

    def _cancelled():
        return cancel_event.is_set()

    def _hf_cache_dir() -> Path:
        hf_home = os.environ.get("HF_HOME", "").strip()
        if hf_home:
            return Path(hf_home) / "hub"
        xdg_cache = os.environ.get("XDG_CACHE_HOME", "").strip()
        if xdg_cache:
            return Path(xdg_cache) / "huggingface" / "hub"
        return Path.home() / ".cache" / "huggingface" / "hub"

    def _scan_local_models() -> list:
        found = set()
        dirs_to_scan = [_hf_cache_dir()]
        if models_dir:
            dirs_to_scan.append(Path(models_dir))
        for base in dirs_to_scan:
            if not base.exists():
                continue
            for entry in base.iterdir():
                name = entry.name
                if "faster-whisper" in name and entry.is_dir():
                    _model = name.split("faster-whisper-")[-1]
                    if _model in _WHISPER_MODELS_ORDER:
                        found.add(_model)
        return sorted(found, key=lambda m: _WHISPER_MODELS_ORDER.index(m)
        if m in _WHISPER_MODELS_ORDER else 99)

    def _try_load_whisper(model_name, device, compute_type) -> tuple:
        try:
            import torch
            from faster_whisper import WhisperModel

            kwargs = {"device": device, "compute_type": compute_type}
            if models_dir:
                kwargs["download_root"] = models_dir
            _model = WhisperModel(model_name, **kwargs)
            _vram_mb = 0
            if torch.cuda.is_available():
                _vram_mb = int(torch.cuda.memory_allocated() / 1024 / 1024)
            del _model
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            return True, "", _vram_mb
        except Exception:
            logger.exception("error on try load whisper")
            gc.collect()
            try:
                import torch
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
            except Exception:
                logger.exception("error on torch.cuda.empty_cache()")
            return False, str(e), 0

    def _check_download_reachable() -> bool:
        url = "https://huggingface.co"
        try:
            if proxy:
                opener = urllib.request.build_opener(
                    urllib.request.ProxyHandler({"https": proxy, "http": proxy})
                )
            else:
                opener = urllib.request.build_opener()
            req = urllib.request.Request(url, method="HEAD")
            resp = opener.open(req, timeout=10)
            return resp.status < 400
        except Exception:
            return False

    try:
        from fredica_pyutil_server.util.device_util import detect_gpu_info

        result = {"local_models": [], "compute_types": {}, "model_support": {}}

        # Step 1: 扫描本地模型
        local_models = _scan_local_models()
        result["local_models"] = local_models
        _put({"type": "local_models", "models": local_models})
        if _cancelled(): return

        # Step 2: 设备信息
        device_info = detect_gpu_info().to_dict()
        _put({"type": "device_info", "info": device_info})
        if _cancelled(): return

        # Step 3: compute_type 支持性测试（用 tiny 模型）
        has_cuda = device_info.get("gpu", {}).get("cuda", {}).get("available", False)
        test_device = "cuda" if has_cuda else "cpu"
        compute_results = {}
        for ct in _COMPUTE_TYPES:
            if _cancelled(): break
            if test_device == "cpu" and ct in ("float16", "int8_float16"):
                compute_results[ct] = {"supported": False, "error": "cpu 不支持 float16"}
                _put({"type": "compute_type_result", "compute_type": ct,
                      "supported": False, "error": "cpu 不支持 float16"})
                continue
            supported, err, _ = _try_load_whisper("tiny", test_device, ct)
            compute_results[ct] = {"supported": supported, "error": err}
            _put({"type": "compute_type_result", "compute_type": ct,
                  "supported": supported, "error": err})
        result["compute_types"] = compute_results
        if _cancelled(): return

        # Step 4: 下载可达性（若 tiny 不在本地）
        if "tiny" not in local_models:
            reachable = _check_download_reachable()
            _put({"type": "download_check", "reachable": reachable, "via_proxy": bool(proxy)})
        else:
            _put({"type": "download_check", "reachable": True, "via_proxy": False})
        if _cancelled(): return

        # Step 5: 逐级加载模型，得出支持表
        best_ct = next(
            (ct for ct in _COMPUTE_TYPES if compute_results.get(ct, {}).get("supported")),
            "int8"
        )
        model_support = {}
        for model in _WHISPER_MODELS_ORDER:
            if _cancelled(): break
            supported, err, vram_mb = _try_load_whisper(model, test_device, best_ct)
            model_support[model] = {"supported": supported, "vram_mb": vram_mb, "error": err}
            _put({"type": "model_support", "model": model,
                  "supported": supported, "vram_mb": vram_mb})
        result["model_support"] = model_support

        if not _cancelled():
            _put({"type": "done", "result": result})

    except Exception as e:
        logger.exception("evaluate_faster_whisper_compat_worker: unexpected error")
        _put({"type": "error", "message": str(e)})
