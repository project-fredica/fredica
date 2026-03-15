# -*- coding: UTF-8 -*-
"""
torch 管理路由。

路由：
  WS   /torch/install-task   — 下载指定 variant 到隔离目录（长任务）
  POST /torch/resolve-spec   — 探测 GPU，返回 TorchRecommendation JSON
  POST /torch/setup-links    — 建立 torch 符号链接（启动后由 Kotlin 调用）
  GET  /torch/pip-command    — 返回指定 variant 的 pip install 命令字符串
  GET  /torch/check          — 检查各 variant 的下载状态
"""
import json
import os
from typing import Any

from fastapi import APIRouter
from loguru import logger
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.install_torch_worker import install_torch_worker
from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess
from fredica_pyutil_server.util.torch_version_util import VARIANT_OPTIONS, MIRROR_SOURCES, check_mirror_availability, fetch_mirror_supported_variants, _VARIANT_ORDER

_router = APIRouter(prefix="/torch")


# =============================================================================
# install-task：下载 torch 到隔离目录
# =============================================================================

class InstallTorchTaskEndpoint(TaskEndpointInSubProcess):
    """
    下载 torch 的 WebSocket 任务端点。

    init_param_and_run data 格式：
        {
            "variant":           str,   必填，如 "cu124"
            "download_dir":      str,   必填，{dataDir}/download/torch/
            "use_proxy":         bool,  可选
            "proxy":             str,   可选
            "custom_packages":   str,   可选，variant=="custom" 时使用
            "custom_index_url":  str,   可选
            "custom_variant_id": str,   可选
        }
    """

    def _get_process_target(self):
        return install_torch_worker

    async def _on_subprocess_message(self, msg: Any):
        if not isinstance(msg, dict):
            self._current_status = msg
            return
        msg_type = msg.get("type")
        if msg_type == "progress":
            pct = msg.get("percent", -1)
            if pct >= 0:
                self.report_progress(pct)
        self._current_status = msg
        await self.send_json(msg)


@_router.websocket("/install-task")
async def install_torch_task(websocket: WebSocket):
    """
    下载 torch 任务端点（WebSocket）。

    WebSocket 协议：
      1. 发送 init_param_and_run：
         {"command": "init_param_and_run", "data": {"variant": "cu124", "download_dir": "..."}}
      2. 服务端推送 check_result / download_start / progress / done / error
      3. 可随时发送 cancel / pause / resume
    """
    endpoint = InstallTorchTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()


# =============================================================================
# resolve-spec：探测 GPU，返回推荐版本
# =============================================================================

@_router.post("/resolve-spec")
async def resolve_spec():
    """
    探测本机 GPU 环境，返回 TorchRecommendation JSON。
    结果由 FredicaApi.jvm.kt 写入 AppConfig.torchRecommendationJson。
    """
    try:
        from fredica_pyutil_server.util.device_util import detect_gpu_info
        from fredica_pyutil_server.util.torch_version_util import resolve_recommended_spec

        device_info = detect_gpu_info().to_dict()
        recommendation = resolve_recommended_spec(device_info)
        return JSONResponse(content=recommendation.to_dict())
    except Exception as e:
        logger.exception("[torch] resolve-spec failed")
        return JSONResponse(status_code=500, content={"error": str(e)})


# =============================================================================
# check：检查各 variant 的下载状态
# =============================================================================

@_router.post("/setup-links")
async def setup_links_route(request: Request):
    """
    建立 torch 符号链接（或 sys.path fallback）。

    Body JSON:
        { "download_dir": str, "variant": str }

    在 Python 服务启动后由 Kotlin 层调用，时机：FredicaApi.init() 完成后。
    variant 为空时静默跳过（torch 尚未配置）。
    """
    try:
        body = await request.json()
        download_dir = body.get("download_dir", "")
        variant = body.get("variant", "")
        if not download_dir or not variant:
            return JSONResponse(content={"ok": False, "reason": "missing download_dir or variant"})
        from fredica_pyutil_server.util.torch_link_manager import setup_links
        ok = setup_links(download_dir, variant)
        return JSONResponse(content={"ok": ok, "variant": variant})
    except Exception as e:
        logger.exception("[torch] setup-links failed")
        return JSONResponse(status_code=500, content={"error": str(e)})


@_router.get("/pip-command/")
async def pip_command(request: Request):
    """
    返回指定 variant 的 pip install 命令字符串（含 --target 隔离目录）。

    Query params:
        variant       (str)   必填，如 "cu124"；"custom" 时返回空命令
        download_dir  (str)   可选，{dataDir}/download/torch/；传入时命令包含 --target
        use_proxy     (bool)  可选，"true" 时追加 --proxy
        proxy         (str)   可选，代理地址

    响应：{ "command": "pip install --target ... --extra-index-url ..." }
    """
    variant = request.query_params.get("variant", "")
    if not variant:
        logger.warning("[torch] pip-command: missing variant param")
        return JSONResponse(status_code=400, content={"error": "variant is required"})
    if variant == "custom":
        logger.debug("[torch] pip-command: variant=custom, returning empty command")
        return JSONResponse(content={"command": ""})
    opt = VARIANT_OPTIONS.get(variant)
    if opt is None:
        logger.warning(f"[torch] pip-command: unknown variant={variant!r}")
        return JSONResponse(status_code=404, content={"error": f"unknown variant: {variant}"})

    download_dir = request.query_params.get("download_dir", "")
    use_proxy = request.query_params.get("use_proxy", "").lower() == "true"
    proxy = request.query_params.get("proxy", "")
    # 允许前端传入自定义 index_url（如国内镜像），覆盖默认官方源
    custom_index_url = request.query_params.get("index_url", "").strip()
    # index_url_mode: "replace" => --index-url（替换默认源），"extra" => --extra-index-url（追加）
    index_url_mode = request.query_params.get("index_url_mode", "replace")
    effective_index_url = custom_index_url or opt.index_url

    pkgs = " ".join(opt.packages)
    parts = ["pip install"]
    if download_dir:
        target_dir = os.path.join(download_dir, variant)
        parts.append(f"--target {target_dir}")
    parts.append(pkgs)
    index_flag = "--extra-index-url" if index_url_mode == "extra" else "--index-url"
    parts.append(f"{index_flag} {effective_index_url}")
    if use_proxy and proxy:
        parts.append(f"--proxy {proxy}")

    cmd = " ".join(parts)
    logger.debug(f"[torch] pip-command: variant={variant} => {cmd}")
    return JSONResponse(content={"command": cmd})


@_router.get("/check/")
async def check_torch(request: Request):
    """
    检查各 variant 的下载状态。

    Query params:
        download_dir  (str)  必填，{dataDir}/download/torch/
        variant       (str)  可选，指定单个 variant；不传则检查所有内置 variant
    """
    try:
        from fredica_pyutil_server.util.torch_version_util import (
            VARIANT_OPTIONS, check_torch_download, _VARIANT_ORDER,
        )

        download_dir = request.query_params.get("download_dir", "")
        variant = request.query_params.get("variant", "")

        if not download_dir:
            return JSONResponse(status_code=400, content={"error": "download_dir is required"})

        if variant:
            result = check_torch_download(variant, download_dir)
            return JSONResponse(content=result.__dict__)

        results = {}
        for v in _VARIANT_ORDER:
            r = check_torch_download(v, download_dir)
            results[v] = {"already_ok": r.already_ok, "installed_version": r.installed_version}
        return JSONResponse(content=results)

    except Exception as e:
        logger.exception("[torch] check failed")
        return JSONResponse(status_code=500, content={"error": str(e)})


@_router.get("/mirror-list/")
async def mirror_list():
    """返回所有镜像源列表（供前端初始化用）。"""
    return JSONResponse(content={
        "mirrors": [
            {"key": s.key, "label": s.label, "supports_cuda": s.supports_cuda}
            for s in MIRROR_SOURCES
        ]
    })


@_router.get("/mirror-check/")
async def mirror_check(request: Request):
    """
    探测各镜像站对指定 variant 的支持情况。

    Query params:
        variant  (str)  必填
        proxy    (str)  可选，代理地址

    响应：{ "results": [{ "key", "label", "available", "url", "error" }] }
    """
    variant = request.query_params.get("variant", "")
    proxy = request.query_params.get("proxy", "")
    if not variant:
        return JSONResponse(status_code=400, content={"error": "variant is required"})

    import asyncio
    loop = asyncio.get_event_loop()
    results = []
    for src in MIRROR_SOURCES:
        if src.key == "custom":
            results.append({"key": src.key, "label": src.label, "available": None, "url": "", "error": ""})
            continue
        r = await loop.run_in_executor(None, check_mirror_availability, variant, src.key, proxy)
        results.append({"key": src.key, "label": src.label, **r})

    return JSONResponse(content={"results": results})


@_router.get("/mirror-versions/")
async def mirror_versions(request: Request):
    """
    抓取指定镜像站的 torch Simple API 页面，返回该镜像支持的所有 variant 列表。

    Query params:
        mirror_key  (str)  必填，如 "nju"
        proxy       (str)  可选，代理地址

    响应：{ "variants": ["cu128", "cu126", ...], "error": "" }
    """
    mirror_key = request.query_params.get("mirror_key", "")
    proxy = request.query_params.get("proxy", "")
    if not mirror_key:
        return JSONResponse(status_code=400, content={"error": "mirror_key is required"})

    import asyncio
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, fetch_mirror_supported_variants, mirror_key, proxy)
    return JSONResponse(content=result)


@_router.get("/all-mirror-variants/")
async def all_mirror_variants(request: Request):
    """
    并发查询所有非 custom 镜像支持的 variant，合并去重后按标准顺序返回。

    Query params:
        proxy  (str)  可选，代理地址

    响应：{
        "variants": ["cu128", "cu126", ...],
        "per_mirror": {"nju": [...], ...},
        "per_mirror_torch_versions": {"nju": {"cu128": "2.7.0", ...}, ...}
    }
    """
    proxy = request.query_params.get("proxy", "")

    import asyncio
    loop = asyncio.get_event_loop()

    queryable = [s for s in MIRROR_SOURCES if s.key != "custom"]

    async def query_one(src):
        result = await loop.run_in_executor(None, fetch_mirror_supported_variants, src.key, proxy)
        return src.key, result.get("variants", []), result.get("torch_versions", {})

    results = await asyncio.gather(*[query_one(s) for s in queryable])

    per_mirror = {k: v for k, v, _ in results}
    per_mirror_torch_versions = {k: tv for k, _, tv in results}
    # 合并所有 variant，按标准顺序排列
    found = set()
    for variants in per_mirror.values():
        found.update(variants)
    merged = [v for v in _VARIANT_ORDER if v in found]

    logger.info(f"[torch] all-mirror-variants: merged={merged} per_mirror={per_mirror}")
    return JSONResponse(content={
        "variants": merged,
        "per_mirror": per_mirror,
        "per_mirror_torch_versions": per_mirror_torch_versions,
    })
