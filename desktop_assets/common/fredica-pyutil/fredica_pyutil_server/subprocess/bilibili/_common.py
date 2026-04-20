# -*- coding: UTF-8 -*-
"""bilibili 子进程公共初始化 + 每请求独立子进程执行器。"""

import asyncio
import multiprocessing
import os
import queue as queue_mod

_mp_ctx = multiprocessing.get_context("spawn")


def _subprocess_wrapper(result_queue, fn, *a):
    """模块级 wrapper，供 spawn 子进程 pickle 序列化。"""
    from loguru import logger as _logger
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    init_loguru(logger=_logger)
    try:
        result = fn(*a)
        result_queue.put(result)
    except Exception as e:
        from bilibili_api.exceptions import ApiException
        if isinstance(e, ApiException):
            _logger.warning("[bilibili-subprocess] worker {} bilibili ApiException: {}", fn.__name__, e)
        else:
            _logger.exception("[bilibili-subprocess] worker {} raised", fn.__name__)
        result_queue.put({"error": str(e)})


async def run_in_subprocess(fn, *args, timeout: float = 180.0) -> dict:
    """每次调用 spawn 独立子进程执行 fn(*args)，通过 Queue 回传结果。"""
    from loguru import logger

    result_queue = _mp_ctx.Queue()

    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    os.environ.setdefault("PYTHONUTF8", "1")
    proc = _mp_ctx.Process(target=_subprocess_wrapper, args=(result_queue, fn, *args), daemon=True)

    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, proc.start)
    logger.debug("bilibili subprocess started, pid={}, fn={}", proc.pid, fn.__name__)

    deadline = loop.time() + timeout
    while proc.is_alive():
        if loop.time() > deadline:
            proc.terminate()
            proc.join(timeout=2)
            result_queue.close()
            result_queue.join_thread()
            logger.warning("bilibili subprocess timeout ({}s), pid={}", timeout, proc.pid)
            return {"error": f"子进程超时 ({timeout}s)"}
        await asyncio.sleep(0.05)

    proc.join(timeout=1)
    try:
        result = result_queue.get_nowait()
    except queue_mod.Empty:
        exitcode = proc.exitcode
        logger.warning("bilibili subprocess exited with no result, exitcode={}, pid={}", exitcode, proc.pid)
        result = {"error": f"子进程异常退出 (exitcode={exitcode})"}
    finally:
        result_queue.close()
        result_queue.join_thread()

    if isinstance(result, dict) and "error" in result:
        logger.warning("bilibili subprocess returned error, pid={}, fn={}, error={}", proc.pid, fn.__name__, result["error"])
    else:
        logger.debug("bilibili subprocess done, pid={}, fn={}", proc.pid, fn.__name__)
    return result


def setup_from_context(ctx: dict):
    """从 BilibiliSubprocessContext dict 初始化子进程环境。"""
    import os
    from bilibili_api import select_client, request_settings
    from loguru import logger

    os.environ.pop("HTTPS_PROXY", None)
    os.environ.pop("HTTP_PROXY", None)

    select_client("curl_cffi")

    impersonate = ctx.get("impersonate", "")
    if impersonate:
        request_settings.set("impersonate", impersonate)

    proxy = ctx.get("proxy", "")
    if proxy:
        os.environ["HTTPS_PROXY"] = proxy
        os.environ["HTTP_PROXY"] = proxy
        request_settings.set_proxy(proxy)

    logger.debug("[bilibili] setup_from_context: proxy={} impersonate={}", proxy[:30] if proxy else "(none)", impersonate or "(default)")


def make_credential_from_context(ctx: dict):
    """从 context 构建 Credential，匿名时返回 None。"""
    from bilibili_api import Credential
    from loguru import logger

    cred = ctx.get("credential")
    if not cred or not any(cred.values()):
        logger.debug("[bilibili] make_credential_from_context: anonymous (no credential)")
        return None
    logger.debug("[bilibili] make_credential_from_context: sessdata={}", (cred.get("sessdata") or "")[:8] + "...")
    return Credential(
        sessdata=cred.get("sessdata") or None,
        bili_jct=cred.get("bili_jct") or None,
        buvid3=cred.get("buvid3") or None,
        buvid4=cred.get("buvid4") or None,
        dedeuserid=cred.get("dedeuserid") or None,
        ac_time_value=cred.get("ac_time_value") or None,
        proxy=ctx.get("proxy") or None,
    )


def init_worker():
    """子进程通用初始化：loguru 重绑定。"""
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    from loguru import logger
    init_loguru(logger=logger)
