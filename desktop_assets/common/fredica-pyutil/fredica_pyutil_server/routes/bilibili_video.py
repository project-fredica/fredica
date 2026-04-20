# -*- coding: UTF-8 -*-
import datetime
import os
from pathlib import Path
from typing import Optional
from urllib.parse import quote, urlsplit, urlunsplit

from loguru import logger
import bilibili_api
from bilibili_api.video import VideoDownloadURLDataDetecter
from fastapi import APIRouter
from pydantic import BaseModel
from starlette.websockets import WebSocket

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInEventLoopThread


_BILIBILI_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com",
}

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _CredentialBody(BaseModel):
    sessdata: Optional[str] = None
    bili_jct: Optional[str] = None
    buvid3: Optional[str] = None
    buvid4: Optional[str] = None
    dedeuserid: Optional[str] = None
    ac_time_value: Optional[str] = None
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


class _BvidBody(BaseModel):
    sessdata: Optional[str] = None
    bili_jct: Optional[str] = None
    buvid3: Optional[str] = None
    buvid4: Optional[str] = None
    dedeuserid: Optional[str] = None
    ac_time_value: Optional[str] = None
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


_CRED_KEYS = ("sessdata", "bili_jct", "buvid3", "buvid4", "dedeuserid", "ac_time_value")


def _build_context(param: dict) -> dict:
    return {
        "credential": {k: param.get(k) for k in _CRED_KEYS},
        "proxy": param.get("proxy") or "",
        "impersonate": param.get("impersonate") or "",
    }


@_router.post("/get-info/{bvid}")
async def get_info(bvid: str, body: _BvidBody):
    logger.debug("[route] bilibili/video/get-info bvid={}", bvid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.video import get_info_worker
    param = body.model_dump()
    result = await run_in_subprocess(get_info_worker, {
        "bvid": bvid,
        "context": _build_context(param),
    })
    logger.debug("[route] bilibili/video/get-info bvid={} done error={}", bvid, result.get("error"))
    return result


@_router.post("/get-pages/{bvid}")
async def get_pages(bvid: str, body: _BvidBody):
    logger.debug("[route] bilibili/video/get-pages bvid={}", bvid)
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.video import get_pages_worker
    param = body.model_dump()
    result = await run_in_subprocess(get_pages_worker, {
        "bvid": bvid,
        "context": _build_context(param),
    })
    logger.debug("[route] bilibili/video/get-pages bvid={} done", bvid)
    return result


@_router.post("/ai-conclusion/{bvid}/{page_index}")
async def get_ai_conclusion(bvid: str, page_index: int, body: _CredentialBody):
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.video import get_ai_conclusion_worker
    param = body.model_dump()
    return await run_in_subprocess(get_ai_conclusion_worker, {
        "bvid": bvid,
        "page_index": page_index,
        "context": _build_context(param),
    })


@_router.post("/subtitle-meta/{bvid}/{page_index}")
async def get_subtitle_meta(bvid: str, page_index: int, body: _CredentialBody):
    logger.debug("[route] bilibili/video/subtitle-meta bvid={} page_index={} has_sessdata={}", bvid, page_index, bool(body.sessdata))
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.video import get_subtitle_meta_worker
    param = body.model_dump()
    result = await run_in_subprocess(get_subtitle_meta_worker, {
        "bvid": bvid,
        "page_index": page_index,
        "context": _build_context(param),
    })
    if isinstance(result, dict) and "error" in result:
        logger.warning("[route] bilibili/video/subtitle-meta bvid={} page_index={} error={}", bvid, page_index, result["error"])
    else:
        logger.debug("[route] bilibili/video/subtitle-meta bvid={} page_index={} done code={}", bvid, page_index, result.get("code") if isinstance(result, dict) else "?")
    return result


class _SubtitleBodyBody(BaseModel):
    subtitle_url: str


def _normalize_subtitle_body_url(url: str) -> str:
    raw = "https:" + url if url.startswith("//") else url
    parsed = urlsplit(raw)
    if not parsed.scheme or not parsed.netloc:
        raise ValueError(f"invalid subtitle_url: {url}")
    normalized_path = quote(parsed.path, safe="/%")
    return urlunsplit((parsed.scheme, parsed.netloc, normalized_path, parsed.query, parsed.fragment))


@_router.post("/subtitle-body")
async def get_subtitle_body(body: _SubtitleBodyBody):
    logger.debug("[bilibili] subtitle-body start url={}", body.subtitle_url)
    try:
        import httpx
        url = _normalize_subtitle_body_url(body.subtitle_url)
        async with httpx.AsyncClient(headers=_BILIBILI_HEADERS, follow_redirects=True) as client:
            resp = await client.get(url)
            logger.debug("[bilibili] subtitle-body http status={} url={}", resp.status_code, url)
            data = resp.json()
            items = data.get("body", [])
            result = [
                {"from": s.get("from", 0), "to": s.get("to", 0), "content": s.get("content", "")}
                for s in items
            ]
        logger.debug("[bilibili] subtitle-body done items={} url={}", len(result), url)
        return {"code": 0, "message": "ok", "body": result}
    except Exception as e:
        logger.warning("[bilibili] get_subtitle_body failed url={}: {}", body.subtitle_url, e)
        return {"code": -1, "message": repr(e), "body": None}


@_router.websocket("/download-task/{bvid}/{page}")
async def download_task_endpoint(websocket: WebSocket, bvid: str, page: int):
    endpoint = BilibiliVideoDownloadTaskEndpoint(websocket=websocket, bvid=bvid, page=page)
    await endpoint.start_and_wait()


class BilibiliVideoDownloadTaskEndpoint(TaskEndpointInEventLoopThread):

    def __init__(self, *, websocket: WebSocket, bvid: str, page: int):
        super().__init__(tag=f"bilibili-download-{bvid}-p{page}", websocket=websocket)
        self._bvid = bvid
        self._page = page

    async def _run(self, param) -> None:
        from bilibili_api import select_client
        select_client("curl_cffi")

        output_dir: str = os.path.abspath(param["output_dir"])
        os.makedirs(output_dir, exist_ok=True)
        logger.debug("[{}] output_dir is {}", self.tag, output_dir)

        video = bilibili_api.video.Video(bvid=self._bvid)
        try:
            url_info = await video.get_download_url(page_index=self._page - 1, html5=True)
        except Exception as e:
            await self.send_json({"type": "error", "message": f"获取下载地址失败: {e}"})
            return

        detector = VideoDownloadURLDataDetecter(url_info)
        streams = detector.detect_best_streams()

        if detector.check_flv_mp4_stream():
            video_stream = streams[0]
            video_path = os.path.join(output_dir, "video.flv")
            ok = await self._download_stream(video_stream.url, video_path, "video",
                                             percent_start=0, percent_weight=100)
            if not ok or self._cancel_flag:
                return
            await self.send_json({"type": "done", "video_path": video_path, "audio_path": None})
            with open(os.path.join(output_dir, "download_flv.done"), mode='wt'):
                pass
        else:
            video_stream, audio_stream = streams[0], streams[1]
            video_path = os.path.join(output_dir, "video.m4s")
            audio_path = os.path.join(output_dir, "audio.m4s")

            logger.debug("[{}] video stream: codec={}, quality={}", self.tag,
                         video_stream.video_codecs, video_stream.video_quality)
            logger.debug("[{}] audio stream: codec={}, quality={}", self.tag,
                         audio_stream.video_codecs, audio_stream.video_quality)

            ok = await self._download_stream(video_stream.url, video_path, "video",
                                             percent_start=0, percent_weight=50)
            if not ok or self._cancel_flag:
                return

            ok = await self._download_stream(audio_stream.url, audio_path, "audio",
                                             percent_start=50, percent_weight=100)
            if not ok or self._cancel_flag:
                return

            await self.send_json({"type": "done", "video_path": video_path, "audio_path": audio_path})
            with open(os.path.join(output_dir, "download_m4s.done"), mode='wt'):
                pass
        logger.info("[{}] download finished", self.tag)

    async def _download_stream(self, url, out_path, stage, percent_start, percent_weight) -> bool:
        stage_offset = 0 if stage == "video" else percent_weight
        try:
            client = bilibili_api.get_client()
            dwn_id = await client.download_create(url, bilibili_api.HEADERS)
            total: int = client.download_content_length(dwn_id)
            logger.debug("[{}] _download_stream start: stage={} total={} out_path={}", self.tag, stage, total, out_path)
            done = 0
            _last_log_time = 0
            with open(out_path, "wb") as f:
                while True:
                    if self._cancel_flag:
                        logger.debug("[{}] _download_stream cancelled before chunk", self.tag)
                        return False
                    await self.wait_if_paused()
                    chunk: bytes = await client.download_chunk(dwn_id)
                    done += f.write(chunk)
                    percent = stage_offset + (int(done / total * percent_weight) if total > 0 else 0)
                    self.report_status({"stage": stage, "bytes_done": done, "total_bytes": total})
                    percent_in_task = percent_start + percent
                    self.report_progress(percent_in_task)
                    _pausable = await self.is_pausable()
                    await self.send_json({
                        "type": "progress",
                        "stage": stage,
                        "bytes_done": done,
                        "total_bytes": total,
                        "percent": percent_in_task,
                        "pausable": _pausable,
                    })
                    _now = datetime.datetime.now().timestamp()
                    if _now - _last_log_time >= 3 or done >= total:
                        _last_log_time = _now
                        logger.debug("[{}] {} [{}/{}] {}% - {}",
                                     self.tag, stage, done, total, percent_in_task, out_path)
                    if done >= total:
                        break
            return True
        except Exception as e:
            logger.exception("[{}] download stream {} failed", self.tag, stage)
            await self.send_json({"type": "error", "message": f"{stage} 流下载失败: {e}"})
            return False
