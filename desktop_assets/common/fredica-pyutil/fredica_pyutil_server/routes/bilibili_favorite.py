# -*- coding: UTF-8 -*-
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _FidBody(BaseModel):
    fid: str
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


class _FidPageBody(BaseModel):
    fid: str
    page: int = 1
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


@_router.post("/get-video-list")
async def get_video_list(body: _FidBody):
    logger.debug("[route] bilibili/favorite/get-video-list fid={}", body.fid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.favorite import favorite_get_video_list_worker
    result = await run_in_subprocess(favorite_get_video_list_worker, {
        "fid": body.fid,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/favorite/get-video-list fid={} done error={}", body.fid, result.get("error"))
    return result


@_router.post("/get-info")
async def get_info(body: _FidBody):
    logger.debug("[route] bilibili/favorite/get-info fid={}", body.fid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.favorite import favorite_get_info_worker
    result = await run_in_subprocess(favorite_get_info_worker, {
        "fid": body.fid,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/favorite/get-info fid={} done error={}", body.fid, result.get("error"))
    return result


@_router.post("/get-page")
async def get_page(body: _FidPageBody):
    logger.debug("[route] bilibili/favorite/get-page fid={} page={}", body.fid, body.page)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.favorite import favorite_get_page_worker
    result = await run_in_subprocess(favorite_get_page_worker, {
        "fid": body.fid,
        "page": body.page,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/favorite/get-page fid={} page={} done error={}", body.fid, body.page, result.get("error"))
    return result
