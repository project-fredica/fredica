# -*- coding: UTF-8 -*-
"""bilibili credential 子进程 worker 函数。"""
import asyncio


def check_credential_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import (
        init_worker, setup_from_context, make_credential_from_context,
    )
    from loguru import logger
    init_worker()

    ctx = param["context"]
    logger.debug("[bilibili] check_credential_worker: proxy={} impersonate={}", ctx.get("proxy", ""), ctx.get("impersonate", ""))
    setup_from_context(ctx)

    credential = make_credential_from_context(ctx)
    if not credential:
        return {"configured": False, "valid": False, "message": "未配置账号"}

    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    try:
        is_valid = asyncio.run(credential.check_valid())
        msg = "ok" if is_valid else "账号已失效"
        return {"configured": True, "valid": is_valid, "message": msg}
    except ResponseCodeException as e:
        logger.warning("[bilibili] check_credential ResponseCodeException code={} msg={}", e.code, e.msg)
        return {"configured": True, "valid": False, "message": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] check_credential NetworkException status={}", e.status)
        return {"configured": True, "valid": False, "message": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] check_credential failed: {}", e)
        return {"configured": True, "valid": False, "message": str(e)}


def try_refresh_credential_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import (
        init_worker, setup_from_context, make_credential_from_context,
    )
    from loguru import logger
    init_worker()

    ctx = param["context"]
    logger.debug("[bilibili] try_refresh_credential_worker: proxy={} impersonate={}", ctx.get("proxy", ""), ctx.get("impersonate", ""))
    setup_from_context(ctx)

    credential = make_credential_from_context(ctx)
    if not credential:
        return {"success": False, "refreshed": False, "message": "未配置账号"}

    from bilibili_api.exceptions import (
        ResponseCodeException, NetworkException, CookiesRefreshException,
    )
    try:
        need_refresh = asyncio.run(credential.check_refresh())
        logger.debug("[bilibili] try_refresh_credential_worker: need_refresh={}", need_refresh)
        if not need_refresh:
            return {"success": True, "refreshed": False, "message": "账号有效，无需刷新"}

        asyncio.run(credential.refresh())
        return {
            "success": True,
            "refreshed": True,
            "message": "刷新成功",
            "sessdata": credential.sessdata,
            "bili_jct": credential.bili_jct,
            "buvid3": credential.buvid3,
            "buvid4": credential.buvid4,
            "dedeuserid": credential.dedeuserid,
            "ac_time_value": credential.ac_time_value,
        }
    except CookiesRefreshException as e:
        logger.warning("[bilibili] try_refresh_credential CookiesRefreshException: {}", e.msg)
        return {"success": False, "refreshed": False, "message": e.msg}
    except ResponseCodeException as e:
        logger.warning("[bilibili] try_refresh_credential ResponseCodeException code={} msg={}", e.code, e.msg)
        return {"success": False, "refreshed": False, "message": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] try_refresh_credential NetworkException status={}", e.status)
        return {"success": False, "refreshed": False, "message": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] try_refresh_credential failed: {}", e)
        return {"success": False, "refreshed": False, "message": str(e)}


def get_account_info_worker(param: dict) -> dict:
    from fredica_pyutil_server.subprocess.bilibili._common import (
        init_worker, setup_from_context, make_credential_from_context,
    )
    from loguru import logger
    init_worker()

    ctx = param["context"]
    logger.debug("[bilibili] get_account_info_worker: proxy={}", ctx.get("proxy", ""))
    setup_from_context(ctx)

    cred = ctx.get("credential", {})
    dedeuserid = (cred.get("dedeuserid") or "") if cred else ""
    if not dedeuserid:
        return {"error": "未提供 dedeuserid"}

    credential = make_credential_from_context(ctx)
    from bilibili_api.user import User
    from bilibili_api.exceptions import ResponseCodeException, NetworkException
    try:
        u = User(uid=int(dedeuserid), credential=credential)
        info = asyncio.run(u.get_user_info())
        result = {
            "mid": dedeuserid,
            "name": info.get("name", ""),
            "face": info.get("face", ""),
            "level": info.get("level", 0),
            "sign": info.get("sign", ""),
            "coins": info.get("coins", 0),
            "fans": info.get("fans", 0),
            "following": info.get("attention", 0),
        }
        logger.info("[bilibili] get-account-info: mid={} name={}", dedeuserid, result["name"])
        return result
    except ResponseCodeException as e:
        logger.warning("[bilibili] get-account-info ResponseCodeException mid={} code={} msg={}", dedeuserid, e.code, e.msg)
        return {"error": e.msg, "code": e.code}
    except NetworkException as e:
        logger.warning("[bilibili] get-account-info NetworkException mid={} status={}", dedeuserid, e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] get-account-info failed: {}", e)
        return {"error": str(e)}
