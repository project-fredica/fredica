# -*- coding: UTF-8 -*-
import importlib
import os
import threading
import time
from pathlib import Path

import psutil
from fastapi import FastAPI
from loguru import logger


def _watch_parent(parent_pid: int, interval: float = 3.0):
    """
    后台线程：定期检测父进程是否还活着。
    父进程退出后本进程变为孤儿进程，立即退出，释放端口。
    使用 psutil.pid_exists() 跨平台检测，兼容 Windows/macOS/Linux。
    """
    while True:
        time.sleep(interval)
        if not psutil.pid_exists(parent_pid):
            logger.warning("Parent process {} is gone, exiting orphan pyutil server (pid={}).",
                           parent_pid, os.getpid())
            os._exit(0)


_parent_pid = os.getppid()
logger.info("fredica-pyutil server starting: pid={}, parent_pid={}", os.getpid(), _parent_pid)
_watcher = threading.Thread(target=_watch_parent, args=(_parent_pid,), daemon=True, name="parent-watcher")
_watcher.start()

app = FastAPI()

_routes_dir = Path(__file__).parent / "routes"
for _f in sorted(_routes_dir.glob("*.py")):
    if _f.stem.startswith("_"):
        continue
    _mod = importlib.import_module(f"fredica_pyutil_server.routes.{_f.stem}")
    if hasattr(_mod, "_router"):
        # noinspection PyProtectedMember
        app.include_router(_mod._router)

if __name__ == '__main__':
    pass
