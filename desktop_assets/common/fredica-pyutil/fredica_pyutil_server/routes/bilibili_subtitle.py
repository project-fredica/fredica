# -*- coding: UTF-8 -*-
import json
import os
from pathlib import Path

import aiohttp
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

_BILIBILI_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com",
}

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class DownloadRequest(BaseModel):
    bvid: str
    page: int = 1
    sessdata: str
    output_path: str


@_router.post("/download")
async def download(req: DownloadRequest):
    """
    下载 B 站视频字幕（CC 字幕 / AI 生成字幕）。

    需要 sessdata 登录凭据。优先选择中文字幕，若视频无字幕则写入 {"body": [], "has_subtitle": false}。
    """
    cookies = {"SESSDATA": req.sessdata}
    try:
        async with aiohttp.ClientSession(cookies=cookies, headers=_BILIBILI_HEADERS) as session:
            # Step 1: 获取视频信息（CID）
            async with session.get(
                "https://api.bilibili.com/x/web-interface/view",
                params={"bvid": req.bvid},
            ) as resp:
                view_data = await resp.json(content_type=None)
            if view_data.get("code") != 0:
                raise HTTPException(
                    status_code=500,
                    detail=f"bilibili API error: {view_data.get('message')}",
                )
            pages = view_data["data"]["pages"]
            if req.page < 1 or req.page > len(pages):
                raise HTTPException(status_code=400, detail=f"Page {req.page} out of range (total {len(pages)})")
            cid = pages[req.page - 1]["cid"]

            # Step 2: 获取播放器信息（字幕列表）
            async with session.get(
                "https://api.bilibili.com/x/player/v2",
                params={"bvid": req.bvid, "cid": cid},
            ) as resp:
                player_data = await resp.json(content_type=None)
            subtitles = player_data.get("data", {}).get("subtitle", {}).get("subtitles", [])

            os.makedirs(os.path.dirname(os.path.abspath(req.output_path)), exist_ok=True)

            if not subtitles:
                with open(req.output_path, "w", encoding="utf-8") as f:
                    json.dump({"body": [], "has_subtitle": False}, f, ensure_ascii=False)
                return {"output_path": req.output_path, "has_subtitle": False}

            # Step 3: 优先选择中文字幕
            chosen = next((s for s in subtitles if "zh" in s.get("lan", "")), subtitles[0])

            sub_url = chosen["subtitle_url"]
            if sub_url.startswith("//"):
                sub_url = "https:" + sub_url

            # Step 4: 下载字幕 JSON
            async with session.get(sub_url) as resp:
                sub_data = await resp.json(content_type=None)

            result = {
                "lan": chosen.get("lan", ""),
                "lan_doc": chosen.get("lan_doc", ""),
                "body": sub_data.get("body", []),
                "has_subtitle": True,
            }
            with open(req.output_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)

            return {"output_path": req.output_path, "has_subtitle": True}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
