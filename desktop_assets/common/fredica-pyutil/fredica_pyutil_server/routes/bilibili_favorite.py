# -*- coding: UTF-8 -*-
from pathlib import Path
from typing import Optional

import bilibili_api
from fastapi import APIRouter
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _FidBody(BaseModel):
    fid: str


class _FidPageBody(BaseModel):
    fid: str
    page: int = 1


@_router.post("/get-video-list")
async def get_video_list(body: _FidBody):
    f = bilibili_api.favorite_list.FavoriteList(media_id=int(body.fid))
    info = await f.get_info()
    first_page = await f.get_content()
    ids_list = await f.get_content_ids_info()
    return {
        "fid": body.fid,
        "info": info,
        "ids_list": ids_list,
        "first_page": first_page,
    }


@_router.post("/get-page")
async def get_page(body: _FidPageBody):
    f = bilibili_api.favorite_list.FavoriteList(media_id=int(body.fid))
    content = await f.get_content(page=body.page)
    return {
        "fid": body.fid,
        "page": body.page,
        "medias": content.get("medias", []),
        "has_more": content.get("has_more", False),
    }
