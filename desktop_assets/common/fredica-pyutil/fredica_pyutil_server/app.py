# -*- coding: UTF-8 -*-
import json

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import bilibili_api

from fredica_pyutil_server.transcribe import transcribe_chunk as _transcribe_chunk

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


@app.get("/bilibili/favorite/get-page/{fid}/{page}")
async def bilibili_favorite_get_page(fid: str, page: int):
    f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
    content = await f.get_content(page=page)
    return {
        "fid": fid,
        "page": page,
        "medias": content.get("medias", []),
        "has_more": content.get("has_more", False),
    }


@app.get("/bilibili/video/get-pages/{bvid}")
async def bilibili_video_get_pages(bvid: str):
    v = bilibili_api.video.Video(bvid=bvid)
    pages = await v.get_pages()
    return [
        p for p in pages
    ]


class TranscribeChunkRequest(BaseModel):
    audio_path: str
    model: str = "large-v3"
    language: str | None = None
    device: str = "auto"
    compute_type: str = "float16"


@app.post("/transcribe/chunk")
async def transcribe_chunk(req: TranscribeChunkRequest):
    try:
        result = await _transcribe_chunk(
            audio_path=req.audio_path,
            model_name=req.model,
            language=req.language,
            device=req.device,
            compute_type=req.compute_type,
        )
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == '__main__':
    pass
