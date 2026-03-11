# -*- coding: UTF-8 -*-
"""
B 站账号凭据管理路由。

路由前缀（自动推导）：/bilibili/credential
"""
from pathlib import Path
from typing import Optional

from loguru import logger
from bilibili_api import Credential
from fastapi import APIRouter
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


@_router.post("/check")
async def check_credential(body: _CredentialBody):
    """
    检测 B 站账号登录态是否仍然有效。

    响应字段：
      configured: bool  — 是否提供了账号信息（sessdata 非空）
      valid: bool       — 账号是否仍有效（bilibili-api check_valid 返回 True）
      message: str      — "ok" / "未配置账号" / "账号已失效" / 异常描述
    """
    logger.debug("[bilibili] credential/check start has_sessdata={}", bool(body.sessdata))

    # 所有凭据字段均为空 → 未配置
    if not any([body.sessdata, body.bili_jct, body.buvid3, body.buvid4,
                body.dedeuserid, body.ac_time_value]):
        logger.debug("[bilibili] credential/check: no credential configured")
        return {"configured": False, "valid": False, "message": "未配置账号"}

    try:
        credential = Credential(
            sessdata=body.sessdata or None,
            bili_jct=body.bili_jct or None,
            buvid3=body.buvid3 or None,
            buvid4=body.buvid4 or None,
            dedeuserid=body.dedeuserid or None,
            ac_time_value=body.ac_time_value or None,
            proxy=body.proxy or None,
        )
        is_valid = await credential.check_valid()
        msg = "ok" if is_valid else "账号已失效"
        logger.info("[bilibili] credential/check: configured=True valid={} message={}", is_valid, msg)
        return {"configured": True, "valid": is_valid, "message": msg}
    except Exception as e:
        logger.warning("[bilibili] credential/check failed: {}", e)
        return {"configured": True, "valid": False, "message": repr(e)}


@_router.post("/try-refresh")
async def try_refresh_credential(body: _CredentialBody):
    """
    尝试刷新 B 站账号凭据（使用 bilibili-api Credential.refresh()）。

    B 站凭据存在有效期，定期调用此接口可延续登录态。
    bilibili-api 的 check_refresh() 返回 True 时代表需要刷新；
    刷新完成后 Credential 对象内部的 sessdata/bili_jct/ac_time_value 会更新，
    本接口将更新后的字段值全量返回，供前端展示并由用户确认保存。

    响应字段：
      success:       bool — 是否成功（False 含"未配置"/"无需刷新"/"刷新失败"等情况）
      refreshed:     bool — 是否实际执行了刷新操作（False 时表示账号有效但无需刷新）
      message:       str  — 结果描述
      sessdata:      str? — 刷新后的 SESSDATA（仅 refreshed=True 时存在）
      bili_jct:      str? — 刷新后的 bili_jct（仅 refreshed=True 时存在）
      buvid3:        str? — 刷新后的 buvid3
      buvid4:        str? — 刷新后的 buvid4
      dedeuserid:    str? — 刷新后的 DedeUserID
      ac_time_value: str? — 刷新后的 ac_time_value
    """
    logger.debug("[bilibili] credential/try-refresh start has_sessdata={}", bool(body.sessdata))

    # 未配置 → 无法刷新
    if not any([body.sessdata, body.bili_jct, body.buvid3, body.buvid4,
                body.dedeuserid, body.ac_time_value]):
        logger.debug("[bilibili] credential/try-refresh: no credential configured")
        return {"success": False, "refreshed": False, "message": "未配置账号"}

    try:
        credential = Credential(
            sessdata=body.sessdata or None,
            bili_jct=body.bili_jct or None,
            buvid3=body.buvid3 or None,
            buvid4=body.buvid4 or None,
            dedeuserid=body.dedeuserid or None,
            ac_time_value=body.ac_time_value or None,
            proxy=body.proxy or None,
        )

        # 先检测是否需要刷新；若无需刷新则直接返回，避免不必要的网络请求
        need_refresh = await credential.check_refresh()
        logger.info("[bilibili] credential/try-refresh: need_refresh={}", need_refresh)

        if not need_refresh:
            return {"success": True, "refreshed": False, "message": "账号有效，无需刷新"}

        # 执行刷新；成功后 credential 对象内部字段会更新
        await credential.refresh()
        logger.info("[bilibili] credential/try-refresh: refresh completed")

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
    except Exception as e:
        logger.warning("[bilibili] credential/try-refresh failed: {}", e)
        return {"success": False, "refreshed": False, "message": repr(e)}
