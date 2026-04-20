# -*- coding: UTF-8 -*-
"""bilibili series (系列) 子进程 worker 函数。"""
import asyncio


def series_get_meta_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    series_id = param["series_id"]
    mid = param["mid"]
    try:
        logger.debug("[bilibili] series_get_meta: series_id={} mid={}", series_id, mid)
        u = User(uid=int(mid))
        result = asyncio.run(u.get_channel_videos_series(sid=int(series_id), pn=1, ps=1))
        meta = result.get("meta", {})
        name = meta.get("name", "")
        logger.debug("[bilibili] series_get_meta: series_id={} name={}", series_id, name)
        return {
            "series_id": series_id,
            "name": name,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] series_get_meta ResponseCodeException series_id={} code={} msg={}", series_id, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] series_get_meta NetworkException series_id={} status={}", series_id, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] series_get_meta failed series_id={}: {}", series_id, e)
        return {"error": str(e)}


def series_get_page_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    series_id = param["series_id"]
    mid = param["mid"]
    page = param.get("page", 1)
    try:
        logger.debug("[bilibili] series_get_page: series_id={} mid={} page={}", series_id, mid, page)
        u = User(uid=int(mid))
        result = asyncio.run(u.get_channel_videos_series(sid=int(series_id), pn=page, ps=30))
        archives = result.get("archives", [])
        page_info = result.get("page", {})
        has_more = page * page_info.get("size", 30) < page_info.get("total", 0)
        logger.debug("[bilibili] series_get_page: series_id={} page={} videoCount={} hasMore={}", series_id, page, len(archives), has_more)
        return {
            "series_id": series_id,
            "mid": mid,
            "page": page,
            "videos": archives,
            "has_more": has_more,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] series_get_page ResponseCodeException series_id={} page={} code={} msg={}", series_id, page, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] series_get_page NetworkException series_id={} page={} status={}", series_id, page, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] series_get_page failed series_id={} page={}: {}", series_id, page, e)
        return {"error": str(e)}
