# -*- coding: UTF-8 -*-
import asyncio
from functools import partial
from typing import Any, Callable, TypeVar

T = TypeVar('T')


async def run_blocking(func: Callable[..., T], /, *args: Any, **kwargs: Any) -> T:
    """
    在默认线程池（ThreadPoolExecutor）中执行阻塞函数，避免阻塞 asyncio 事件循环。

    适用于：
      - 子进程操作（process.start / terminate / is_alive）
      - multiprocessing 跨进程同步原语（Event.set / clear，Queue.get / put）
      - 任何阻塞 I/O 或系统调用

    用法：
        result = await run_blocking(blocking_func, arg1, arg2)
        result = await run_blocking(obj.method, key=value)
        await run_blocking(mp_event.set)
    """
    loop = asyncio.get_running_loop()
    if args or kwargs:
        func = partial(func, *args, **kwargs)
    return await loop.run_in_executor(None, func)
