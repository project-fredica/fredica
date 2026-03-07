# -*- coding: UTF-8 -*-
import datetime
import os
from pathlib import Path
from typing import Annotated, Any, Optional

from loguru import logger
import bilibili_api
from bilibili_api import Credential
from bilibili_api.video import VideoDownloadURLDataDetecter
from fastapi import APIRouter, Query
from pydantic import BaseModel
from starlette.websockets import WebSocket

from fredica_pyutil_server.util.task_endpoint_util import TaskEndpointInEventLoopThread


def _make_credential(
    sessdata: Optional[str] = None,
    bili_jct: Optional[str] = None,
    buvid3: Optional[str] = None,
    buvid4: Optional[str] = None,
    dedeuserid: Optional[str] = None,
    ac_time_value: Optional[str] = None,
    proxy: Optional[str] = None,
) -> Optional[Credential]:
    """有任意字段非空时构建 Credential，否则返回 None（匿名请求）。"""
    if any([sessdata, bili_jct, buvid3, buvid4, dedeuserid, ac_time_value]):
        return Credential(
            sessdata=sessdata or None,
            bili_jct=bili_jct or None,
            buvid3=buvid3 or None,
            buvid4=buvid4 or None,
            dedeuserid=dedeuserid or None,
            ac_time_value=ac_time_value or None,
            proxy=proxy or None,
        )
    return None

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


_CredQ = Annotated[Optional[str], Query(default=None)]


class _BvidBody(BaseModel):
    sessdata: Optional[str] = None
    bili_jct: Optional[str] = None
    buvid3: Optional[str] = None
    buvid4: Optional[str] = None
    dedeuserid: Optional[str] = None
    ac_time_value: Optional[str] = None
    proxy: Optional[str] = None


@_router.post("/get-pages/{bvid}")
async def get_pages(bvid: str, body: _BvidBody):
    credential = _make_credential(
        body.sessdata, body.bili_jct, body.buvid3, body.buvid4,
        body.dedeuserid, body.ac_time_value, body.proxy,
    )
    v = bilibili_api.video.Video(bvid=bvid, credential=credential)
    return await v.get_pages()


@_router.post("/ai-conclusion/{bvid}/{page_index}")
async def get_ai_conclusion(bvid: str, page_index: int, body: _CredentialBody):
    try:
        credential = _make_credential(
            body.sessdata, body.bili_jct, body.buvid3, body.buvid4,
            body.dedeuserid, body.ac_time_value, body.proxy,
        )
        v = bilibili_api.video.Video(bvid=bvid, credential=credential)
        return await v.get_ai_conclusion(page_index=page_index)
    except bilibili_api.exceptions.ResponseCodeException as e:
        return e.raw
    except Exception as e:
        logger.warning("[bilibili] get_ai_conclusion failed bvid={} page_index={}: {}", bvid, page_index, e)
        return {"code": -1, "message": repr(e), "model_result": None}


@_router.websocket("/download-task/{bvid}/{page}")
async def download_task_endpoint(websocket: WebSocket, bvid: str, page: int):
    """
    下载 B 站视频到指定本地路径。

    WebSocket 协议：
      1. 连接建立后发送 init_param_and_run 启动下载：
         {"command": "init_param_and_run", "data": {"output_dir": "/path/to/dir"}}
      2. 服务端实时推送 progress / done / error 消息
      3. 可随时发送 cancel / pause / resume / status 命令
    """
    endpoint = BilibiliVideoDownloadTaskEndpoint(websocket=websocket, bvid=bvid, page=page)
    await endpoint.start_and_wait()


class BilibiliVideoDownloadTaskEndpoint(TaskEndpointInEventLoopThread):
    """
    下载 B 站视频（视频流 + 音频流）到本地，通过 WebSocket 实时推送进度。

    init_param_and_run data 格式：
        {"output_dir": str}   # 本地输出目录（自动创建）

    WebSocket 推送消息格式：
        # 各阶段下载进度
        {"type": "progress", "stage": "video"|"audio",
         "bytes_done": int, "total_bytes": int}
        # 完成
        {"type": "done", "video_path": str, "audio_path": Optional[str]}
        # 出错
        {"type": "error", "message": str}
    """

    def __init__(self, *, websocket: WebSocket, bvid: str, page: int):
        super().__init__(tag=f"bilibili-download-{bvid}-p{page}", websocket=websocket)
        self._bvid = bvid
        self._page = page

    async def _run(self, param: Any) -> None:
        output_dir: str = os.path.abspath(param["output_dir"])
        os.makedirs(output_dir, exist_ok=True)
        logger.debug("[{}] output_dir is {}", self.tag, output_dir)

        # 获取下载地址
        video = bilibili_api.video.Video(bvid=self._bvid)
        try:
            url_info = await video.get_download_url(page_index=self._page - 1, html5=True)
        except Exception as e:
            await self.send_json({"type": "error", "message": f"获取下载地址失败: {e}"})
            return

        detector = VideoDownloadURLDataDetecter(url_info)
        streams = detector.detect_best_streams()

        if detector.check_flv_mp4_stream():
            # FLV 流下载
            video_stream = streams[0]
            video_path = os.path.join(output_dir, "video.flv")
            # 下载视频流
            ok = await self._download_stream(video_stream.url, video_path, "video",
                                             percent_start=0,
                                             percent_weight=100)
            if not ok or self._cancel_flag:
                return
            await self.send_json({"type": "done", "video_path": video_path, "audio_path": None})
            with open(os.path.join(output_dir, "download_flv.done"), mode='wt'):
                pass
        else:
            # MP4 流下载
            video_stream, audio_stream = streams[0], streams[1]
            video_path = os.path.join(output_dir, "video.m4s")
            audio_path = os.path.join(output_dir, "audio.m4s")

            logger.debug("[{}] video stream: codec={}, quality={}", self.tag,
                         video_stream.video_codecs, video_stream.video_quality)
            logger.debug("[{}] audio stream: codec={}, quality={}", self.tag,
                         audio_stream.video_codecs, audio_stream.video_quality)

            # 下载视频流
            ok = await self._download_stream(video_stream.url, video_path, "video",
                                             percent_start=0,
                                             percent_weight=50)
            if not ok or self._cancel_flag:
                return

            # 下载音频流
            ok = await self._download_stream(audio_stream.url, audio_path, "audio",
                                             percent_start=50,
                                             percent_weight=100)
            if not ok or self._cancel_flag:
                return

            await self.send_json({"type": "done", "video_path": video_path, "audio_path": audio_path})
            with open(os.path.join(output_dir, "download_m4s.done"), mode='wt'):
                pass
        logger.info("[{}] download finished", self.tag)

    async def _download_stream(self,
                               url: str,
                               out_path: str,
                               stage: str,
                               percent_start: int,
                               percent_weight: int) -> bool:
        """
        下载单条流（视频或音频），每个 chunk 推送一次进度。
        返回 True 表示下载完成，False 表示出错。
        """
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
