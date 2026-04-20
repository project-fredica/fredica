# -*- coding: UTF-8 -*-
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _SeriesPageBody(BaseModel):
    series_id: str
    mid: str
    page: int = 1
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


class _SeriesMetaBody(BaseModel):
    series_id: str
    mid: str
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


@_router.post("/get-meta")
async def get_meta(body: _SeriesMetaBody):
    logger.debug("[route] bilibili/series/get-meta series_id={} mid={}", body.series_id, body.mid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.series import series_get_meta_worker
    result = await run_in_subprocess(series_get_meta_worker, {
        "series_id": body.series_id,
        "mid": body.mid,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/series/get-meta series_id={} done error={}", body.series_id, result.get("error"))
    return result


@_router.post("/get-page")
async def get_page(body: _SeriesPageBody):
    logger.debug("[route] bilibili/series/get-page series_id={} mid={} page={}", body.series_id, body.mid, body.page)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.series import series_get_page_worker
    result = await run_in_subprocess(series_get_page_worker, {
        "series_id": body.series_id,
        "mid": body.mid,
        "page": body.page,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/series/get-page series_id={} page={} done error={}", body.series_id, body.page, result.get("error"))
    return result
