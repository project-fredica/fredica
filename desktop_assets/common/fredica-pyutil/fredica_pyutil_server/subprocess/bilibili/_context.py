# -*- coding: UTF-8 -*-
"""bilibili 子进程上下文 TypedDict 定义。"""

from typing import TypedDict, Optional


class BilibiliCredentialFields(TypedDict, total=False):
    sessdata: str
    bili_jct: str
    buvid3: str
    buvid4: str
    dedeuserid: str
    ac_time_value: str


class BilibiliSubprocessContext(TypedDict, total=False):
    credential: Optional[BilibiliCredentialFields]
    proxy: str
    impersonate: str
    rate_limit_sec: float
