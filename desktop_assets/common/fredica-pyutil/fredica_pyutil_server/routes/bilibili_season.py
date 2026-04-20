# -*- coding: UTF-8 -*-
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _SeasonPageBody(BaseModel):
    season_id: str
    mid: str
    page: int = 1
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


class _SeasonMetaBody(BaseModel):
    season_id: str
    mid: str
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


@_router.post("/get-meta")
async def get_meta(body: _SeasonMetaBody):
    logger.debug("[route] bilibili/season/get-meta season_id={} mid={}", body.season_id, body.mid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.season import season_get_meta_worker
    result = await run_in_subprocess(season_get_meta_worker, {
        "season_id": body.season_id,
        "mid": body.mid,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/season/get-meta season_id={} done error={}", body.season_id, result.get("error"))
    return result


@_router.post("/get-page")
async def get_page(body: _SeasonPageBody):
    logger.debug("[route] bilibili/season/get-page season_id={} mid={} page={}", body.season_id, body.mid, body.page)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.season import season_get_page_worker
    result = await run_in_subprocess(season_get_page_worker, {
        "season_id": body.season_id,
        "mid": body.mid,
        "page": body.page,
        "context": {"proxy": body.proxy or "", "impersonate": body.impersonate or ""},
    })
    logger.debug("[route] bilibili/season/get-page season_id={} page={} done error={}", body.season_id, body.page, result.get("error"))
    return result
