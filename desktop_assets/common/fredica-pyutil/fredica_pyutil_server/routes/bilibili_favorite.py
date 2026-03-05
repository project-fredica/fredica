# -*- coding: UTF-8 -*-
from pathlib import Path

import bilibili_api
from fastapi import APIRouter

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


@_router.get("/get-video-list/{fid}")
async def get_video_list(fid: str):
    f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
    info = await f.get_info()
    first_page = await f.get_content()
    ids_list = await f.get_content_ids_info()
    return {
        "fid": fid,
        "info": info,
        "ids_list": ids_list,
        "first_page": first_page,
    }


@_router.get("/get-page/{fid}/{page}")
async def get_page(fid: str, page: int):
    f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
    content = await f.get_content(page=page)
    return {
        "fid": fid,
        "page": page,
        "medias": content.get("medias", []),
        "has_more": content.get("has_more", False),
    }
