# -*- coding: UTF-8 -*-
from fastapi import FastAPI
import bilibili_api

app = FastAPI()


@app.get("/ping")
def ping():
    return {"msg": "pong"}


@app.get("/bilibili/favorite/get-video-list/{fid}")
async def bilibili_favorite_get_video_list(fid: str):
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


if __name__ == '__main__':
    pass
