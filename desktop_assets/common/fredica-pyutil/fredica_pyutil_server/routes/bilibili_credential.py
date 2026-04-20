# -*- coding: UTF-8 -*-
"""
B 站账号凭据管理路由。

路由前缀（自动推导）：/bilibili/credential
"""
from pathlib import Path
from typing import Optional

from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

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


_CRED_KEYS = ("sessdata", "bili_jct", "buvid3", "buvid4", "dedeuserid", "ac_time_value")


def _build_context(param: dict) -> dict:
    return {
        "context": {
            "credential": {k: param.get(k) for k in _CRED_KEYS},
            "proxy": param.get("proxy") or "",
            "impersonate": param.get("impersonate") or "",
        }
    }


@_router.post("/check")
async def check_credential(body: _CredentialBody):
    logger.debug("[bilibili] check_credential: proxy={} impersonate={}", body.proxy or "", body.impersonate or "")
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.credential import check_credential_worker
    return await run_in_subprocess(check_credential_worker, _build_context(body.model_dump()))


@_router.post("/try-refresh")
async def try_refresh_credential(body: _CredentialBody):
    logger.debug("[bilibili] try_refresh_credential: proxy={} impersonate={}", body.proxy or "", body.impersonate or "")
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.credential import try_refresh_credential_worker
    return await run_in_subprocess(try_refresh_credential_worker, _build_context(body.model_dump()))


@_router.post("/get-account-info")
async def get_account_info(body: _CredentialBody):
    logger.debug("[bilibili] get_account_info: dedeuserid={} proxy={}", body.dedeuserid or "", body.proxy or "")
    from fredica_pyutil_server.subprocess.bilibili._common import run_in_subprocess
    from fredica_pyutil_server.subprocess.bilibili.credential import get_account_info_worker
    return await run_in_subprocess(get_account_info_worker, _build_context(body.model_dump()))
