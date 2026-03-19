# -*- coding: UTF-8 -*-
import abc
import asyncio
import json
import multiprocessing
import os
from typing import *

from starlette.websockets import WebSocket, WebSocketDisconnect
from loguru import logger

from fredica_pyutil_server.util.async_util import run_blocking

_valid_command_name = ("cancel", "pause", "resume", "status", "init_param_and_run")
TaskEndpointCommandName = Literal["cancel", "pause", "resume", "status", "init_param_and_run"]

TaskEndpointCommand = TypedDict('TaskEndpointCommand', {
    "command": TaskEndpointCommandName,
    "data": Any,
})

TaskEndpointReadCommandResult = Union[
    Tuple[Literal[True], TaskEndpointCommand],
    Tuple[Literal[False], str, Optional[BaseException]],
]


class TaskEndpoint(metaclass=abc.ABCMeta):
    """
    基于 WebSocket 的长时间任务控制端点基类。

    客户端连接后通过 WebSocket 发送 JSON 命令来控制任务生命周期。
    所有命令格式：{"command": "<name>", "data": <any>}

    支持的命令：
      - init_param_and_run : 携带初始参数，触发任务启动（必须首先发送）
      - cancel             : 请求取消，等待任务停止后关闭连接
      - pause              : 暂停任务（需先收到 init_param_and_run）
      - resume             : 恢复被暂停的任务
      - status             : 查询当前进度，服务端回复 JSON 状态报文

    status 响应格式：
        {
            "task_endpoint": {"tag": str},
            "stopped": bool,
            "paused":  bool,
            "status":  <_read_status() 返回值，已停止时为 null>
        }

    子类必须实现：
      - _check_is_stopped()    判断后台任务是否已结束
      - _read_status()         返回当前进度数据（可 JSON 序列化）
      - _does_support_pause()  声明暂停支持级别
      - _call_pause()          执行暂停的具体逻辑
      - _call_resume()         执行恢复的具体逻辑

    子类可覆盖：
      - _on_init_param_and_run(param)  参数确认后启动后台任务（默认空实现）
    """

    def __init__(self, *, websocket: WebSocket, tag: Optional[str] = None):
        """
        :param websocket: FastAPI/Starlette WebSocket 连接对象
        :param tag:       任务标识字符串，用于区分日志；默认使用类名
        """
        self.tag = tag if tag is not None else type(self).__name__
        self.websocket = websocket
        self._cancel_flag = False
        self._cancel_lock = asyncio.Lock()
        self._init_param_finish = False
        self._init_param = None
        self._is_starting_and_waiting = False
        self._is_pause = False
        self._pause_lock = asyncio.Lock()
        self._is_stopping = False
        self._is_stopped = False
        # 防止并发 send_json 导致 WebSocket 帧交错
        self._ws_send_lock = asyncio.Lock()

    @property
    def is_stopped(self) -> bool:
        """任务是否已完全停止（包含清理完毕）。"""
        return self._is_stopped

    @abc.abstractmethod
    async def _check_is_stopped(self) -> bool:
        """返回后台任务是否已结束（正常完成、异常或取消均返回 True）。"""
        pass

    @abc.abstractmethod
    async def _read_status(self) -> Any:
        """返回当前任务状态数据，响应 status 命令时调用。"""
        pass

    @abc.abstractmethod
    async def _does_support_pause(self) -> Literal[
        "supported_always", "unsupported_always", "supported_current_time", "unsupported_current_time"]:
        """
        声明暂停支持级别：
          - supported_always         始终支持暂停
          - unsupported_always       不支持暂停
          - supported_current_time   当前时刻支持
          - unsupported_current_time 当前时刻不支持
        """
        pass

    @abc.abstractmethod
    async def _call_pause(self):
        """执行暂停的具体逻辑，由 call_pause() 在持锁后调用。"""
        pass

    @abc.abstractmethod
    async def _call_resume(self):
        """执行恢复的具体逻辑，由 call_resume() 在持锁后调用。"""
        pass

    async def _on_init_param_and_run(self, param: Any):
        """
        收到 init_param_and_run 命令后的钩子，默认空实现。
        子类覆盖此方法以在参数就绪时启动后台任务。
        :param param: 命令携带的 data 字段（已存入 self._init_param）
        """
        pass

    async def send_json(self, data: Any):
        """向 WebSocket 发送 JSON 消息，内置锁防止并发帧交错。"""
        async with self._ws_send_lock:
            await self.websocket.send_json(data)

    async def call_pause(self):
        """
        处理 pause 命令（加锁）：
          - 任务未初始化时忽略
          - 已处于暂停中时忽略
          - 否则调用 _call_pause() 并置位 _is_pause
        """
        async with self._pause_lock:
            if not self._init_param_finish:
                logger.info("[{}] pause command require init_param_and_run command called", self.tag)
                return
            if self._is_pause:
                logger.info("[{}] already in pausing ...", self.tag)
                return
            logger.info("[{}] pausing ...", self.tag)
            await self._call_pause()
            logger.debug("[{}] paused", self.tag)
            self._is_pause = True

    async def call_resume(self):
        """
        处理 resume 命令（加锁）：
          - 任务未初始化时忽略
          - 未处于暂停时忽略
          - 否则调用 _call_resume() 并清位 _is_pause
        """
        async with self._pause_lock:
            if not self._init_param_finish:
                logger.info("[{}] resume command require init_param_and_run command called", self.tag)
                return
            if not self._is_pause:
                logger.info("[{}] resume command ignored , because is not pausing", self.tag)
                return
            logger.info("[{}] resuming ...", self.tag)
            await self._call_resume()
            logger.debug("[{}] resumed", self.tag)
            self._is_pause = False

    async def close(self, reason: str):
        """主动关闭 WebSocket 连接。"""
        await self.websocket.close(reason=reason)

    # noinspection PyUnresolvedReferences
    async def start_and_wait(self):
        """
        接受 WebSocket 连接并进入命令接收主循环，直到任务结束或被取消。

        流程：
          1. accept() 握手
          2. 循环读取并分发 WebSocket 命令
          3. 收到 cancel 或任务自然结束后退出命令循环
          4. finally 块轮询 _check_is_stopped() 直到后台任务完全停止
        """
        if self._is_starting_and_waiting:
            raise ValueError(f'[{self.tag}] already starting !')
        self._is_starting_and_waiting = True
        await self.websocket.accept()
        try:
            while True:
                try:
                    if self._init_param_finish and await self._check_is_stopped():
                        await self.websocket.close(reason="task_finish")
                        break
                    _read_command_result = await self._read_command()
                    # noinspection PyUnresolvedReferences
                    if not _read_command_result[0]:
                        logger.debug("[{}] read invalid data command , continue ...", self.tag)
                        continue
                    # noinspection PyTypeChecker
                    _command: TaskEndpointCommand = _read_command_result[1]
                    if _command["command"] == "cancel":
                        async with self._cancel_lock:
                            self._cancel_flag = True
                            logger.info("[{}] wait cancel finish", self.tag)
                            break
                    elif _command["command"] == "pause":
                        await self.call_pause()
                        continue
                    elif _command["command"] == "resume":
                        await self.call_resume()
                        continue
                    elif _command["command"] == "init_param_and_run":
                        self._init_param = _command.get("data", None)
                        self._init_param_finish = True
                        await self._on_init_param_and_run(self._init_param)
                    elif _command["command"] == "status":
                        _stopped = await self._check_is_stopped()
                        await self.send_json({
                            "task_endpoint": {
                                "tag": self.tag,
                            },
                            "stopped": _stopped,
                            "paused": self._is_pause,
                            "status": None if _stopped else await self._read_status(),
                        })
                        continue
                    else:
                        raise ValueError(f"command value invalid : {_command}")
                except BaseException as err:
                    if isinstance(err, WebSocketDisconnect):
                        logger.debug("[{}] websocket disconnect", self.tag)
                    else:
                        logger.exception("[{}] error on task", self.tag)
                    if not await self._check_is_stopped():
                        self._cancel_flag = True
                    break
        finally:
            self._is_stopping = True
            _count = 0
            while True:
                _wait_time = min(1.0 + _count * 0.5, 10)
                if await self._check_is_stopped():
                    break
                await asyncio.sleep(_wait_time)
                logger.debug('[{}] waiting {} ... count = {}',
                             self.tag,
                             "cancel" if self._cancel_flag else "stop",
                             _count)
            logger.info("[{}] task stopped", self.tag)
            self._is_stopped = True

    async def _read_command(self) -> TaskEndpointReadCommandResult:
        """
        从 WebSocket 读取一条文本消息并解析为 TaskEndpointCommand。

        返回：
          (True, command)           解析成功，command 为 TaskEndpointCommand
          (False, error_msg, exc?)  解析失败，调用方应 continue 跳过
        """
        data_text = await self.websocket.receive_text()
        try:
            data_json = json.loads(data_text)
        except BaseException as err:
            logger.exception("[{}] json decode received text failed ! received text is {}", self.tag, data_text)
            return False, "json decode failed", err
        if not isinstance(data_json, dict):
            logger.error("[{}] data_json is not dict , data_text is {}", self.tag, data_text)
            return False, "json decode failed - not dict", None
        if "command" not in data_json:
            logger.error("[{}] data_json not found key command , data_json is {}", self.tag, data_json)
            return False, "json decode failed - not found key - command", None
        if not isinstance(data_json["command"], str):
            logger.error("[{}] data_json[\"command\"] not string , data_json is {}", self.tag, data_json)
            return False, "json decode failed - command not string", None
        command = data_json["command"]
        if not command in _valid_command_name:
            logger.error("[{}] data_json[\"command\"] invalid , command is {}",
                         self.tag, command)
            return False, "command invalid", None
        command: TaskEndpointCommandName = command
        _command: TaskEndpointCommand = {"command": command, "data": data_json.get("data", None)}
        return True, _command


class TaskEndpointInEventLoopThread(TaskEndpoint, metaclass=abc.ABCMeta):
    """
    在 asyncio 事件循环线程中以协程方式运行后台任务的端点。

    子类实现 _run(param) 协程，该协程与 WebSocket 命令循环并发运行。
    协程内约定：
      - 定期检查 self._cancel_flag，为 True 时尽快返回
      - 调用 await self.wait_if_paused() 支持暂停
      - 调用 self.report_status(data) 更新供 status 命令查询的状态快照
      - 主动推送进度时调用 await self.send_json(data)（已内置锁保护）
    """

    def __init__(self, *, websocket: WebSocket, tag: Optional[str] = None):
        super().__init__(websocket=websocket, tag=tag)
        self._bg_task: Optional[asyncio.Task] = None
        # set = 运行中，clear = 已暂停；wait_if_paused() 阻塞在 clear 状态
        self._not_paused_event: asyncio.Event = asyncio.Event()
        self._not_paused_event.set()
        self._current_status: Any = None
        self._progress: int = 0

    @abc.abstractmethod
    async def _run(self, param: Any) -> None:
        """
        后台任务协程，由 init_param_and_run 命令触发启动。
        :param param: 命令携带的 data 字段
        """
        pass

    async def wait_if_paused(self):
        """
        若当前处于暂停状态则挂起，直到 resume 命令到来后才返回。
        在 _run() 的循环体内定期调用以支持暂停。
        """
        await self._not_paused_event.wait()

    async def request_pause(self, reason: str = ""):
        """
        从 _run() 内部调用，主动请求 Kotlin 侧暂停此 Task。

        发送 pause_request 消息后自身进入等待，直到 Kotlin 回发 resume 命令。
        适用场景：GPU 显存不足、外部资源未就绪等需要等待用户或系统介入的情况。

        :param reason: 暂停原因，透传给 Kotlin 的 onPauseRequest 回调
        """
        await self.send_json({"type": "pause_request", "reason": reason})
        await self.call_pause()
        await self.wait_if_paused()

    async def request_resume(self):
        """
        从 _run() 内部调用，通知 Kotlin 侧任务已自行恢复。

        适用场景：等待的资源已就绪，无需用户介入即可继续执行。
        调用后 Kotlin 的 onResumeRequest 回调会同步 is_paused 状态。
        """
        await self.call_resume()
        await self.send_json({"type": "resume_request"})

    def report_status(self, status: Any):
        """
        从 _run() 内部调用，更新供 status 命令查询的状态快照。
        :param status: 任意可 JSON 序列化的对象
        """
        self._current_status = status

    def report_progress(self, percent: int):
        """
        从 _run() 内部调用，更新当前任务进度（0–100）。
        :param percent: 进度百分比，自动裁剪到 [0, 100]
        """
        self._progress = max(0, min(100, percent))

    async def _on_init_param_and_run(self, param: Any):
        """收到 init_param_and_run 后，创建 asyncio.Task 启动 _run(param)。"""
        self._bg_task = asyncio.create_task(self._run(param))
        logger.info("[{}] background asyncio task created", self.tag)

    async def _check_is_stopped(self) -> bool:
        """
        检查后台协程是否已结束。
        若已收到取消请求且协程仍在运行，则先解除暂停再 cancel asyncio.Task。
        """
        if self._bg_task is None:
            return False
        if self._cancel_flag and not self._bg_task.done():
            # 解除可能的暂停等待，确保协程能响应 CancelledError
            self._not_paused_event.set()
            self._bg_task.cancel()
        return self._bg_task.done()

    async def _read_status(self) -> Any:
        return {"progress": self._progress, "data": self._current_status}

    async def _does_support_pause(self) -> Literal[
        "supported_always", "unsupported_always", "supported_current_time", "unsupported_current_time"]:
        return "supported_always"

    async def is_pausable(self) -> bool:
        """返回当前任务是否可暂停（供 progress 消息携带）。"""
        level = await self._does_support_pause()
        return level in ("supported_always", "supported_current_time")

    async def _call_pause(self):
        """清除事件，使 wait_if_paused() 陷入等待。"""
        self._not_paused_event.clear()

    async def _call_resume(self):
        """设置事件，唤醒所有在 wait_if_paused() 等待的协程。"""
        self._not_paused_event.set()


class TaskEndpointInSubProcess(TaskEndpoint, metaclass=abc.ABCMeta):
    """
    在独立子进程中运行任务的端点。

    通信机制：
      - status_queue  子进程通过 put() 向父进程推送任意可 pickle 的消息
      - cancel_event  父进程 set() 通知子进程取消，子进程检查后尽快退出
      - resume_event  set = 运行，clear = 暂停；子进程调用 wait() 实现暂停

    父进程后台协程 (_run_queue_reader) 持续读取 status_queue，
    对每条消息调用 _on_subprocess_message()，默认仅更新状态快照，
    子类可覆盖以主动向 WebSocket 推送进度。

    子类须实现：
      - _get_process_target()
            返回子进程入口函数（必须为模块级函数或 @staticmethod，确保可 pickle）。
            入口函数签名：
                fn(param, status_queue, cancel_event, resume_event) -> None
      - _on_subprocess_message(msg)  （可选覆盖）处理每条子进程消息

    子进程内约定：
      - 定期检查 cancel_event.is_set()，为 True 时尽快退出
      - 定期调用 resume_event.wait() 支持暂停
      - 通过 status_queue.put(msg) 推送进度
    """

    def __init__(self, *, websocket: WebSocket, tag: Optional[str] = None):
        super().__init__(websocket=websocket, tag=tag)
        self._mp_ctx = multiprocessing.get_context("spawn")
        self._process: Optional[multiprocessing.Process] = None
        self._status_queue = self._mp_ctx.Queue()
        self._cancel_mp_event = self._mp_ctx.Event()
        # set = 运行，clear = 暂停
        self._resume_mp_event = self._mp_ctx.Event()
        self._resume_mp_event.set()
        self._current_status: Any = None
        self._queue_reader_task: Optional[asyncio.Task] = None

    @abc.abstractmethod
    def _get_process_target(self) -> Callable[[Any, Any, Any, Any], None]:
        """
        返回子进程的入口函数（必须为模块级函数或 @staticmethod，确保可 pickle）。

        入口函数签名：
            fn(param:        Any,
               status_queue: multiprocessing.Queue,
               cancel_event: multiprocessing.Event,
               resume_event: multiprocessing.Event) -> None

          - param        来自 init_param_and_run 命令的 data 字段
          - status_queue 子进程 put() 状态；父进程通过 _on_subprocess_message() 处理
          - cancel_event set 时子进程应尽快退出
          - resume_event clear 时子进程应 wait()（暂停）
        """
        pass

    async def _on_subprocess_message(self, msg: Any):
        """
        处理子进程发来的一条消息。

        识别子进程通过 subprocess_request_pause() 发出的特殊消息类型：
          - {"type": "pause_request", "reason": ...}  → 转发给 WebSocket，同步 _is_pause 状态
          - {"type": "resume_request"}                → 转发给 WebSocket，同步 _is_pause 状态

        其他消息默认仅更新状态快照；子类可覆盖以主动推送 WebSocket 消息。
        :param msg: status_queue.put() 的内容
        """
        if isinstance(msg, dict):
            msg_type = msg.get("type")
            if msg_type == "pause_request":
                await self.send_json({"type": "pause_request", "reason": msg.get("reason", "")})
                # 子进程已自行 clear resume_event，这里只同步父进程的 _is_pause 标志位
                self._is_pause = True
                return
            if msg_type == "resume_request":
                await self.send_json({"type": "resume_request"})
                self._is_pause = False
                return
        self._current_status = msg

    async def _on_init_param_and_run(self, param: Any):
        """收到 init_param_and_run 后，spawn 子进程并启动队列读取后台协程。"""
        # spawn 子进程继承父进程 os.environ，在 start() 前设好 UTF-8 编码，
        # 避免 Windows 控制台默认 GBK 导致子进程 loguru 输出中文乱码
        os.environ.setdefault("PYTHONIOENCODING", "utf-8")
        os.environ.setdefault("PYTHONUTF8", "1")
        self._process = self._mp_ctx.Process(
            target=self._get_process_target(),
            args=(param, self._status_queue, self._cancel_mp_event, self._resume_mp_event),
            daemon=True,
        )
        # process.start() 以 spawn 模式启动新 Python 解释器，会阻塞数百毫秒
        await run_blocking(self._process.start)
        logger.info("[{}] subprocess started, pid={}", self.tag, self._process.pid)
        self._queue_reader_task = asyncio.create_task(self._run_queue_reader())

    async def _run_queue_reader(self):
        """
        后台协程：持续从 status_queue 读取子进程消息并调用 _on_subprocess_message()。
        子进程退出且队列排空后自动结束。
        """
        loop = asyncio.get_running_loop()
        while True:
            process_dead = self._process is not None and not self._process.is_alive()
            try:
                # 用线程池执行阻塞的 queue.get，timeout=0.1s 以便定期检查退出条件
                msg = await loop.run_in_executor(
                    None, self._status_queue.get, True, 0.1
                )
                await self._on_subprocess_message(msg)
            except Exception:
                # queue.Empty（超时）或其他异常：若进程已死则退出循环
                if process_dead:
                    break
        logger.debug("[{}] queue reader stopped", self.tag)

    async def _check_is_stopped(self) -> bool:
        """
        检查子进程是否已退出。
        若已收到取消请求且子进程仍在运行，则通知并 terminate()。
        """
        if self._process is None:
            return False
        if self._cancel_flag and self._process.is_alive():
            # multiprocessing.Event.set() 和 process.terminate() 均为阻塞系统调用
            await run_blocking(self._cancel_mp_event.set)
            # 解除子进程可能的 wait() 暂停，确保其能检查到 cancel_event
            await run_blocking(self._resume_mp_event.set)
            await run_blocking(self._process.terminate)
            logger.info("[{}] subprocess terminated, pid={}", self.tag, self._process.pid)
        if not self._process.is_alive():
            if self._queue_reader_task and not self._queue_reader_task.done():
                self._queue_reader_task.cancel()
            return True
        return False

    async def _read_status(self) -> Any:
        return self._current_status

    async def _does_support_pause(self) -> Literal[
        "supported_always", "unsupported_always", "supported_current_time", "unsupported_current_time"]:
        return "supported_always"

    async def is_pausable(self) -> bool:
        """返回当前任务是否可暂停（供 progress 消息携带）。"""
        level = await self._does_support_pause()
        return level in ("supported_always", "supported_current_time")

    async def _call_pause(self):
        """清除 resume_event；子进程调用 resume_event.wait() 时将挂起。"""
        await run_blocking(self._resume_mp_event.clear)

    async def _call_resume(self):
        """设置 resume_event，唤醒子进程。"""
        await run_blocking(self._resume_mp_event.set)


def subprocess_request_pause(
        status_queue: Any,
        resume_event: Any,
        reason: str = "",
) -> None:
    """
    子进程内调用：主动请求父进程（及 Kotlin 侧）暂停此 Task，然后挂起等待恢复。

    时序保证：先 clear resume_event，再 put 消息，再 wait。
    这样即使父进程处理消息有延迟，子进程的 wait() 也一定会阻塞，不会提前返回。

    :param status_queue:  TaskEndpointInSubProcess 传入的 multiprocessing.Queue
    :param resume_event:  TaskEndpointInSubProcess 传入的 multiprocessing.Event（set=运行，clear=暂停）
    :param reason:        暂停原因，透传给 Kotlin 的 onPauseRequest 回调
    """
    resume_event.clear()
    status_queue.put({"type": "pause_request", "reason": reason})
    resume_event.wait()


if __name__ == '__main__':
    pass
