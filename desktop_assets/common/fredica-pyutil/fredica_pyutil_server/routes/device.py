# -*- coding: UTF-8 -*-
"""
设备能力检测 HTTP 路由。

路由：
  GET  /device/info        — 返回缓存的设备 + FFmpeg 信息（无缓存时触发检测）
  POST /device/detect      — 重新检测设备 GPU 能力与 FFmpeg，返回最新结果
  GET  /device/ffmpeg-find — 仅重新搜索 FFmpeg（不重复 GPU 检测）
"""

from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger

from fredica_pyutil_server.util.device_util import DeviceGpuInfo, detect_gpu_info
from fredica_pyutil_server.util.ffmpeg_util import FfmpegProbeInfo, find_best_ffmpeg

_router = APIRouter(prefix="/" + Path(__file__).stem)

# 模块级缓存（内存，重启后重置）
_device_info_cache: Optional[DeviceGpuInfo] = None
_ffmpeg_probe_cache: Optional[FfmpegProbeInfo] = None


def _run_full_detect() -> dict:
    global _device_info_cache, _ffmpeg_probe_cache
    logger.info("[device] running full device detection ...")
    _device_info_cache = detect_gpu_info()
    _ffmpeg_probe_cache = find_best_ffmpeg()
    logger.info(
        "[device] detection complete: platform={}, ffmpeg_found={}, selected_accel={}",
        _device_info_cache.platform,
        _ffmpeg_probe_cache.found,
        _ffmpeg_probe_cache.selected_accel,
    )
    return {
        "device_info_json": _device_info_cache.to_dict(),
        "ffmpeg_probe_json": _ffmpeg_probe_cache.to_dict(),
    }


@_router.get("/info")
async def device_info():
    """返回缓存的设备信息；若无缓存则先触发检测。"""
    if _device_info_cache is None or _ffmpeg_probe_cache is None:
        return _run_full_detect()
    return {
        "device_info_json": _device_info_cache.to_dict(),
        "ffmpeg_probe_json": _ffmpeg_probe_cache.to_dict(),
    }


@_router.post("/detect")
async def device_detect():
    """强制重新检测设备 GPU 能力与 FFmpeg，返回最新结果。"""
    return _run_full_detect()


@_router.get("/ffmpeg-find")
async def ffmpeg_find():
    """仅重新搜索 FFmpeg（不重复 GPU 检测），返回最新 FFmpeg 探测结果。"""
    global _ffmpeg_probe_cache
    logger.info("[device] running ffmpeg-find ...")
    _ffmpeg_probe_cache = find_best_ffmpeg()
    logger.info(
        "[device] ffmpeg-find complete: found={}, path={}, selected_accel={}",
        _ffmpeg_probe_cache.found,
        _ffmpeg_probe_cache.path,
        _ffmpeg_probe_cache.selected_accel,
    )
    return {"ffmpeg_probe_json": _ffmpeg_probe_cache.to_dict()}
