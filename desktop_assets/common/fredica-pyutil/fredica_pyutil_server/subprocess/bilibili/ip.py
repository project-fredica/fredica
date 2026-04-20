# -*- coding: UTF-8 -*-
"""bilibili IP 检测子进程 worker 函数。"""
import asyncio


def check_ip_worker(param: dict) -> dict:
    """子进程：通过 curl_cffi 检测出口 IP。"""
    from fredica_pyutil_server.subprocess.bilibili._common import init_worker, setup_from_context
    from loguru import logger
    init_worker()

    ctx = param.get("context", {})
    setup_from_context(ctx)

    from bilibili_api import get_client, request_settings
    from bilibili_api.exceptions import NetworkException

    logger.debug("[bilibili] check_ip context: proxy={!r} impersonate={!r}", ctx.get("proxy", ""), ctx.get("impersonate", ""))
    logger.debug("[bilibili] check_ip request_settings: proxy={!r} impersonate={!r}", request_settings.get("proxy"), request_settings.get("impersonate"))

    ZONE_URL = "https://api.bilibili.com/x/web-interface/zone"

    async def _fetch():
        client = get_client()
        session = client.get_wrapped_session()
        logger.debug("[bilibili] check_ip session: proxies={!r} impersonate={!r}", getattr(session, 'proxies', None), getattr(session, 'impersonate', None))
        return await client.request(method="GET", url=ZONE_URL)

    try:
        resp = asyncio.run(_fetch())
        data = resp.json()
        addr = data.get("data", {}).get("addr", "")
        if not addr:
            return {"error": "B站接口未返回 IP 信息"}
        return {"ip": addr}
    except NetworkException as e:
        logger.warning("[bilibili] check_ip NetworkException status={}", e.status)
        return {"error": str(e), "status": e.status}
    except Exception as e:
        logger.warning("[bilibili] check_ip failed: {}", e)
        return {"error": str(e)}
