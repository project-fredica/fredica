# -*- coding: UTF-8 -*-
"""bilibili season (合集) 子进程 worker 函数。"""
import asyncio


def season_get_meta_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    season_id = param["season_id"]
    mid = param["mid"]
    try:
        logger.debug("[bilibili] season_get_meta: season_id={} mid={}", season_id, mid)
        u = User(uid=int(mid))
        result = asyncio.run(u.get_channel_videos_season(sid=int(season_id), pn=1, ps=1))
        meta = result.get("meta", {})
        name = meta.get("name", "")
        logger.debug("[bilibili] season_get_meta: season_id={} name={}", season_id, name)
        return {
            "season_id": season_id,
            "name": name,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] season_get_meta ResponseCodeException season_id={} code={} msg={}", season_id, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] season_get_meta NetworkException season_id={} status={}", season_id, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] season_get_meta failed season_id={}: {}", season_id, e)
        return {"error": str(e)}


def season_get_page_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    season_id = param["season_id"]
    mid = param["mid"]
    page = param.get("page", 1)
    try:
        logger.debug("[bilibili] season_get_page: season_id={} mid={} page={}", season_id, mid, page)
        u = User(uid=int(mid))
        result = asyncio.run(u.get_channel_videos_season(sid=int(season_id), pn=page, ps=30))
        archives = result.get("archives", [])
        page_info = result.get("page", {})
        has_more = page * page_info.get("page_size", 30) < page_info.get("total", 0)
        logger.debug("[bilibili] season_get_page: season_id={} page={} videoCount={} hasMore={}", season_id, page, len(archives), has_more)
        return {
            "season_id": season_id,
            "mid": mid,
            "page": page,
            "videos": archives,
            "has_more": has_more,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] season_get_page ResponseCodeException season_id={} page={} code={} msg={}", season_id, page, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] season_get_page NetworkException season_id={} page={} status={}", season_id, page, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] season_get_page failed season_id={} page={}: {}", season_id, page, e)
        return {"error": str(e)}
