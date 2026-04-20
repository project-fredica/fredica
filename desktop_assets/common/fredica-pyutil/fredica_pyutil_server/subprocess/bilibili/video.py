# -*- coding: UTF-8 -*-
"""bilibili video 子进程 worker 函数。"""
import asyncio


def _make_credential_with_refresh(ctx: dict):
    """从 context 构建 Credential 并自动刷新（如需要）。"""
    from fredica_pyutil_server.subprocess.bilibili._common import make_credential_from_context
    from loguru import logger

    credential = make_credential_from_context(ctx)
    if not credential:
        return None

    cred = ctx.get("credential", {})
    ac_time_value = (cred.get("ac_time_value") or "") if cred else ""
    if ac_time_value and asyncio.run(credential.check_refresh()):
        logger.info("credential refresh ... old value is {}", str(credential))
        asyncio.run(credential.refresh())
        logger.info("credential refresh success , new value is {}", str(credential))

    if not asyncio.run(credential.check_valid()):
        logger.error("credential invalid , value is {}", str(credential))
        return None

    return credential


def get_info_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    bvid = param["bvid"]
    try:
        logger.debug("[bilibili] get_info: bvid={}", bvid)
        credential = _make_credential_with_refresh(ctx)
        v = bilibili_api.video.Video(bvid=bvid, credential=credential)
        info = asyncio.run(v.get_info())
        title = info.get("title", "")
        logger.debug("[bilibili] get_info: bvid={} title={}", bvid, title)
        owner = info.get("owner", {})
        stat = info.get("stat", {})
        pages = info.get("pages", [])
        return {
            "bvid": bvid,
            "title": title,
            "cover": info.get("pic", ""),
            "desc": info.get("desc", ""),
            "duration": info.get("duration", 0),
            "owner": {
                "mid": owner.get("mid", 0),
                "name": owner.get("name", ""),
                "face": owner.get("face", ""),
            },
            "stat": {
                "view": stat.get("view", 0),
                "danmaku": stat.get("danmaku", 0),
                "favorite": stat.get("favorite", 0),
                "coin": stat.get("coin", 0),
                "like": stat.get("like", 0),
                "share": stat.get("share", 0),
            },
            "pages": [
                {
                    "page": p.get("page", 0),
                    "part": p.get("part", ""),
                    "duration": p.get("duration", 0),
                    "first_frame": p.get("first_frame", ""),
                }
                for p in pages
            ],
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] get_info ResponseCodeException bvid={} code={} msg={}", bvid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] get_info NetworkException bvid={} status={}", bvid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] get_info failed bvid={}: {}", bvid, e)
        return {"error": str(e)}


def get_pages_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    bvid = param["bvid"]
    try:
        logger.debug("[bilibili] get_pages: bvid={}", bvid)
        credential = _make_credential_with_refresh(ctx)
        v = bilibili_api.video.Video(bvid=bvid, credential=credential)
        pages = asyncio.run(v.get_pages())
        logger.debug("[bilibili] get_pages: bvid={} pageCount={}", bvid, len(pages))
        return {"pages": pages}
    except ResponseCodeException as e:
        logger.warning("[bilibili] get_pages ResponseCodeException bvid={} code={} msg={}", bvid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] get_pages NetworkException bvid={} status={}", bvid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] get_pages failed bvid={}: {}", bvid, e)
        return {"error": str(e)}


def get_ai_conclusion_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    bvid = param["bvid"]
    page_index = param["page_index"]
    credential = _make_credential_with_refresh(ctx)
    try:
        v = bilibili_api.video.Video(bvid=bvid, credential=credential)
        result = asyncio.run(v.get_ai_conclusion(page_index=page_index))
        return result
    except bilibili_api.exceptions.ResponseCodeException as e:
        logger.warning("[bilibili] get_ai_conclusion ResponseCodeException bvid={} page_index={} code={} msg={}", bvid, page_index, e.code, e.msg)
        return e.raw
    except bilibili_api.exceptions.NetworkException as e:
        logger.warning("[bilibili] get_ai_conclusion NetworkException bvid={} page_index={} status={}", bvid, page_index, e.status)
        return {"code": -1, "message": str(e), "model_result": None}
    except Exception as e:
        logger.warning("[bilibili] get_ai_conclusion failed bvid={} page_index={}: {}", bvid, page_index, e)
        return {"code": -1, "message": str(e), "model_result": None}


def get_subtitle_meta_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    bvid = param["bvid"]
    page_index = param["page_index"]
    cred_dict = ctx.get("credential", {})
    has_creds = bool(cred_dict and any(cred_dict.values()))
    proxy = ctx.get("proxy", "")
    logger.debug("[bilibili] subtitle-meta start bvid={} page_index={} has_creds={} proxy={}", bvid, page_index, has_creds, proxy[:30] if proxy else "(none)")
    credential = _make_credential_with_refresh(ctx)
    try:
        v = bilibili_api.video.Video(bvid=bvid, credential=credential)
        cid = asyncio.run(v.get_cid(page_index=page_index))
        logger.debug("[bilibili] subtitle-meta got cid={} bvid={} page_index={}", cid, bvid, page_index)
        subtitle_info = asyncio.run(v.get_subtitle(cid=cid))
        subtitles_meta = subtitle_info.get("subtitles", [])
        logger.debug("[bilibili] subtitle-meta done bvid={} page_index={} tracks={}", bvid, page_index, len(subtitles_meta))
        return {
            "code": 0,
            "message": "ok",
            "allow_submit": subtitle_info.get("allow_submit", False),
            "subtitles": subtitles_meta,
        }
    except bilibili_api.exceptions.ResponseCodeException as e:
        logger.warning("[bilibili] subtitle-meta ResponseCodeException bvid={} code={} msg={}", bvid, e.code, e.msg)
        return {"code": e.code, "message": e.msg, "subtitles": None}
    except bilibili_api.exceptions.NetworkException as e:
        logger.warning("[bilibili] subtitle-meta NetworkException bvid={} status={}", bvid, e.status)
        return {"code": -1, "message": str(e), "subtitles": None}
    except Exception as e:
        logger.warning("[bilibili] get_subtitle_meta failed bvid={} page_index={}: {}", bvid, page_index, e)
        return {"code": -1, "message": str(e), "subtitles": None}
