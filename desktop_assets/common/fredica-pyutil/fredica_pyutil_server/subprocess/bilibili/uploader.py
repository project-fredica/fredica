# -*- coding: UTF-8 -*-
"""bilibili uploader 子进程 worker 函数。"""
import asyncio


def uploader_get_info_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    mid = param["mid"]
    try:
        u = User(uid=int(mid))
        info = asyncio.run(u.get_user_info())
        return {
            "mid": mid,
            "name": info.get("name", ""),
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] uploader_get_info ResponseCodeException mid={} code={} msg={}", mid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] uploader_get_info NetworkException mid={} status={}", mid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] uploader_get_info failed mid={}: {}", mid, e)
        return {"error": str(e)}


def uploader_get_page_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    from bilibili_api.user import User, VideoOrder
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    mid = param["mid"]
    page = param.get("page", 1)
    order_str = param.get("order", "pubdate")
    order_map = {
        "pubdate": VideoOrder.PUBDATE,
        "favorite": VideoOrder.FAVORITE,
        "view": VideoOrder.VIEW,
    }
    order = order_map.get(order_str, VideoOrder.PUBDATE)
    try:
        u = User(uid=int(mid))
        result = asyncio.run(u.get_videos(pn=page, ps=30, order=order))
        vlist = result.get("list", {}).get("vlist", [])
        page_info = result.get("page", {})
        has_more = page * 30 < page_info.get("count", 0)
        return {
            "mid": mid,
            "page": page,
            "videos": vlist,
            "has_more": has_more,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] uploader_get_page ResponseCodeException mid={} page={} code={} msg={}", mid, page, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] uploader_get_page NetworkException mid={} page={} status={}", mid, page, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] uploader_get_page failed mid={} page={}: {}", mid, page, e)
        return {"error": str(e)}
