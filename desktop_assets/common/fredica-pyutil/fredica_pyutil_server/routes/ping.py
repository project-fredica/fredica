# -*- coding: UTF-8 -*-
from pathlib import Path

from fastapi import APIRouter

_router = APIRouter(prefix="/" + Path(__file__).stem.replace("_", "/"))


@_router.get("")
def ping():
    return {"msg": "pong"}
