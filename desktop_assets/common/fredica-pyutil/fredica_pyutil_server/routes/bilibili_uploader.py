# -*- coding: UTF-8 -*-
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel
from starlette.websockets import WebSocket

from fredica_pyutil_server.subprocess.bilibili_uploader_sync import (
    BilibiliUploaderSyncTaskEndpoint,
)

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _UploaderPageBody(BaseModel):
    mid: str
    page: int = 1
    order: str = "pubdate"
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


class _UploaderInfoBody(BaseModel):
    mid: str
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


@_router.post("/get-info")
async def get_info(body: _UploaderInfoBody):
    logger.debug("[bilibili] uploader get-info: mid={} proxy={}", body.mid, body.proxy or "")
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.uploader import uploader_get_info_worker
    return await run_in_subprocess(uploader_get_info_worker, {
        "mid": body.mid,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })


@_router.post("/get-page")
async def get_page(body: _UploaderPageBody):
    logger.debug("[bilibili] uploader get-page: mid={} page={} order={}", body.mid, body.page, body.order)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.uploader import uploader_get_page_worker
    return await run_in_subprocess(uploader_get_page_worker, {
        "mid": body.mid,
        "page": body.page,
        "order": body.order,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })


@_router.websocket("/sync-task")
async def uploader_sync_task(websocket: WebSocket):
    endpoint = BilibiliUploaderSyncTaskEndpoint(websocket=websocket)
    await endpoint.start_and_wait()
