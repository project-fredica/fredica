# -*- coding: UTF-8 -*-
"""
torch 管理路由。

## 路由一览
  WS   /torch/install-task          — 下载指定 variant 到隔离目录（长任务，WebSocket）
  POST /torch/resolve-spec          — 探测 GPU，返回 TorchRecommendation JSON
  POST /torch/setup-links           — 建立 torch 符号链接（启动后由 Kotlin 调用）
  GET  /torch/pip-command/          — 返回指定 variant 的 pip install 命令字符串（前端预览用）
  GET  /torch/check/                — 检查各 variant 的下载状态
  GET  /torch/mirror-list/          — 返回所有镜像源列表
  GET  /torch/mirror-check/         — 探测各镜像站对指定 variant 的支持情况
  GET  /torch/mirror-versions/      — 抓取指定镜像站支持的 variant 及版本列表
  GET  /torch/all-mirror-variants/  — 并发查询所有镜像，合并返回 variant 列表

## 关键设计说明

### pip-command 与 install-task 的关系
  - /torch/pip-command/ 仅用于前端预览，不实际执行下载
  - /torch/install-task 通过 install_torch_worker.py 子进程执行实际下载
  - 两者的命令构造逻辑**目前不完全一致**（已知问题，待重构修复）：
      * pip-command 支持 --index-url（替换模式）和 --extra-index-url（追加模式）
      * install_torch_worker 固定使用 --extra-index-url（追加模式）
      * pip-command 支持 torch_version 参数（用户选择的具体版本号）
      * install_torch_worker 不支持 torch_version，固定使用 VARIANT_OPTIONS 中的默认版本
  - 重构目标：提取 build_pip_install_cmd() 函数，两者共用同一实现

### index_url_mode 参数（pip-command 专用）
  - "replace"（默认）：使用 --index-url，替换 pip 默认的 PyPI 源
    适用场景：镜像站同时托管 torch 和其他依赖（如 NJU/SJTU）
  - "extra"：使用 --extra-index-url，在默认 PyPI 源基础上追加
    适用场景：镜像站只托管 torch wheel，其他包仍从 PyPI 下载

### 动态 variant 支持
  - VARIANT_OPTIONS 只包含固定的 8 个内置 variant
  - 镜像查询可发现 cu129/cu130 等新版本（动态 variant）
  - pip-command 对动态 variant 有 fallback 逻辑（构造默认 TorchVariantOption）
  - install_torch_worker 对动态 variant 直接报错（待重构修复）
"""
import json
import os
from typing import Any, Literal

from fastapi import APIRouter
from loguru import logger
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.install_torch_worker import install_torch_worker
from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInSubProcess
from fredica_pyutil_server.util.torch_version_util import MIRROR_SOURCES, check_mirror_availability, fetch_mirror_supported_variants, _sort_variants

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

    async def _does_support_pause(self) -> Literal[
        "supported_always", "unsupported_always", "supported_current_time", "unsupported_current_time"]:
        return "unsupported_always"

    def _get_cancel_cleanup_wait_config(self) -> tuple[int, float]:
        # torch 下载取消时需要留更多时间，让 worker 先清理内部 pip 子进程。
        return 20, 0.2

    def _get_process_target(self):
        return install_torch_worker

    async def _on_subprocess_message(self, msg: Any):
        if not isinstance(msg, dict):
            self._current_status = msg
            return
        self._current_status = msg
        await self.send_json(msg)


@_router.websocket("/install-task")
async def install_torch_task(websocket: WebSocket):
    """
    下载 torch 任务端点（WebSocket）。

    由 Kotlin DownloadTorchExecutor 通过 PythonUtil.websocketTask("/torch/install-task") 调用。
    实际下载逻辑在 subprocess/install_torch_worker.py 子进程中执行。

    WebSocket 协议：
      1. 客户端发送 init_param_and_run：
         {"command": "init_param_and_run", "data": {
             "variant":           str,   必填，如 "cu124"
             "download_dir":      str,   必填，{dataDir}/download/torch/
             "use_proxy":         bool,  可选
             "proxy":             str,   可选，代理地址
             "index_url":         str,   可选，自定义镜像 index-url（覆盖默认官方源）
             "custom_packages":   str,   可选，variant=="custom" 时使用
             "custom_index_url":  str,   可选，variant=="custom" 时使用
             "custom_variant_id": str,   可选，variant=="custom" 时使用
         }}
      2. 服务端推送（来自 install_torch_worker.py）：
         {"type": "check_result",   "already_ok": bool, "installed_version": str, "target_dir": str}
         {"type": "download_start", "packages": [...], "target_dir": str, "cmd": str}
         {"type": "progress",       "percent": int, "statusText": str}
         {"type": "done",           "result": {"variant": str, "target_dir": str}}
         {"type": "error",          "message": str}
      3. 客户端可随时发送 cancel / pause / resume

    已知问题（与 /torch/pip-command/ 不一致，待重构修复）：
      - install_torch_worker 固定用 --extra-index-url，pip-command 支持 --index-url 替换模式
      - install_torch_worker 不支持 torch_version 参数，固定使用 VARIANT_OPTIONS 默认版本
      - install_torch_worker 不支持动态 variant（cu129/cu130 等），会直接报错
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
    返回指定 variant 的 pip install 命令字符串（前端预览用）。

    Query params:
        torch_version  (str)   可选，torch 版本号，如 "2.7.0"；空串时安装最新版
        index_url      (str)   可选，pip index-url；空串时使用官方源
        download_dir   (str)   可选，{dataDir}/download/pip/
        variant        (str)   可选，官方源为空时用于构造默认 index-url
        index_url_mode (str)   可选，"replace"（默认）或 "extra"
        use_proxy      (bool)  可选，"true" 时追加 --proxy
        proxy          (str)   可选，代理地址

    响应：{ "command": "pip install --target ... --index-url ..." }
    """
    torch_version = request.query_params.get("torch_version", "").strip()
    variant = request.query_params.get("variant", "")
    download_dir = request.query_params.get("download_dir", "")
    index_url_mode = request.query_params.get("index_url_mode", "replace")
    use_proxy = request.query_params.get("use_proxy", "").lower() == "true"
    proxy = request.query_params.get("proxy", "")

    index_url = request.query_params.get("index_url", "").strip()
    if not index_url and variant:
        index_url = f"https://download.pytorch.org/whl/{variant}"

    from fredica_pyutil_server.util.torch_version_util import build_pip_install_cmd, resolve_packages
    packages = resolve_packages(torch_version)
    cmd_parts = build_pip_install_cmd(
        packages=packages,
        target_dir=download_dir,
        index_url=index_url,
        index_url_mode=index_url_mode,
        use_proxy=use_proxy,
        proxy=proxy,
    )
    # cmd_parts = [sys.executable, "-m", "pip", "install", ...]
    # 转为可读字符串（前端展示用），去掉 sys.executable -m
    cmd = "pip " + " ".join(cmd_parts[3:])
    logger.debug(f"[torch] pip-command: variant={variant} torch_version={torch_version!r} => {cmd}")
    return JSONResponse(content={"command": cmd})


@_router.get("/check/")
async def check_torch(request: Request):
    """
    检查 pip 安装目录中是否已有可用的 torch。

    Query params:
        download_dir      (str)  必填，{dataDir}/download/pip/
        expected_version  (str)  可选，期望的 torch 版本号（如 "2.7.0"）；
                                 传入时版本不匹配视为未安装；不传则有 dist-info 即视为已安装
    """
    try:
        from fredica_pyutil_server.util.torch_version_util import check_torch_download

        download_dir = request.query_params.get("download_dir", "")
        expected_version = request.query_params.get("expected_version", "")

        if not download_dir:
            return JSONResponse(status_code=400, content={"error": "download_dir is required"})

        result = check_torch_download(download_dir, expected_version)
        return JSONResponse(content=result.__dict__)

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
        "per_mirror_torch_versions": {"nju": {"cu128": ["2.7.0", "2.6.0"], ...}, ...}
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
    # 合并所有 variant，按标准顺序排列（_sort_variants 处理已知+未知 variant）
    found = set()
    for variants in per_mirror.values():
        found.update(variants)
    merged = _sort_variants(list(found))

    logger.info(f"[torch] all-mirror-variants: merged={merged} per_mirror={per_mirror}")
    return JSONResponse(content={
        "variants": merged,
        "per_mirror": per_mirror,
        "per_mirror_torch_versions": per_mirror_torch_versions,
    })
