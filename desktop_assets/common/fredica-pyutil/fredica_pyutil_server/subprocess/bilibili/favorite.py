# -*- coding: UTF-8 -*-
"""bilibili favorite (收藏夹) 子进程 worker 函数。"""
import asyncio


def favorite_get_video_list_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    fid = param["fid"]
    try:
        logger.debug("[bilibili] favorite_get_video_list: fid={}", fid)
        f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
        info = asyncio.run(f.get_info())
        first_page = asyncio.run(f.get_content())
        ids_list = asyncio.run(f.get_content_ids_info())
        logger.debug("[bilibili] favorite_get_video_list: fid={} ids_count={} first_page_medias={}", fid, len(ids_list) if ids_list else 0, len(first_page.get("medias", [])) if first_page else 0)
        return {
            "fid": fid,
            "info": info,
            "ids_list": ids_list,
            "first_page": first_page,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] favorite_get_video_list ResponseCodeException fid={} code={} msg={}", fid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] favorite_get_video_list NetworkException fid={} status={}", fid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] favorite_get_video_list failed fid={}: {}", fid, e)
        return {"error": str(e)}


def favorite_get_info_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    fid = param["fid"]
    try:
        logger.debug("[bilibili] favorite_get_info: fid={}", fid)
        f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
        info = asyncio.run(f.get_info())
        title = info.get("title", "")
        logger.debug("[bilibili] favorite_get_info: fid={} title={}", fid, title)
        return {
            "fid": fid,
            "title": title,
            "upper_name": info.get("upper", {}).get("name", ""),
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] favorite_get_info ResponseCodeException fid={} code={} msg={}", fid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] favorite_get_info NetworkException fid={} status={}", fid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] favorite_get_info failed fid={}: {}", fid, e)
        return {"error": str(e)}


def favorite_get_page_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param["context"]
    setup_from_context(ctx)

    import bilibili_api
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    fid = param["fid"]
    page = param.get("page", 1)
    try:
        logger.debug("[bilibili] favorite_get_page: fid={} page={}", fid, page)
        f = bilibili_api.favorite_list.FavoriteList(media_id=int(fid))
        content = asyncio.run(f.get_content(page=page))
        medias = content.get("medias", [])
        has_more = content.get("has_more", False)
        logger.debug("[bilibili] favorite_get_page: fid={} page={} mediaCount={} hasMore={}", fid, page, len(medias), has_more)
        return {
            "fid": fid,
            "page": page,
            "medias": medias,
            "has_more": has_more,
        }
    except ResponseCodeException as e:
        logger.warning("[bilibili] favorite_get_page ResponseCodeException fid={} page={} code={} msg={}", fid, page, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] favorite_get_page NetworkException fid={} page={} status={}", fid, page, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] favorite_get_page failed fid={} page={}: {}", fid, page, e)
        return {"error": str(e)}
