# -*- coding: UTF-8 -*-
"""
B 站出口 IP 检测路由。

路由前缀（自动推导）：/bilibili/ip
"""
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


class _CheckIpBody(BaseModel):
    proxy: Optional[str] = None
    impersonate: Optional[str] = None


@_router.post("/check")
async def check_ip(body: _CheckIpBody):
    """
    通过子进程 + curl_cffi 访问 B 站公开端点，获取出口 IP。

    请求字段：
      proxy: str? — HTTP/SOCKS 代理地址，空字符串或 None 表示直连
      impersonate: str? — 浏览器 TLS 指纹

    响应字段：
      ip: str    — 出口 IP 地址（成功时）
      error: str — 错误描述（失败时）
    """
    from loguru import logger
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.ip import check_ip_worker

    context = {
        "proxy": body.proxy or "",
        "impersonate": body.impersonate or "",
    }
    logger.debug("[bilibili-ip] route /check received: proxy={!r} impersonate={!r}", context["proxy"], context["impersonate"])
    return await run_in_subprocess(check_ip_worker, {"context": context})
