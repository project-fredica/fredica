# -*- coding: UTF-8 -*-
"""
torch 下载子进程 worker。

## 运行方式
在独立子进程中运行（由 TaskEndpointInSubProcess 框架 fork），通过
  pip install --target <download_dir>/<variant>/
将 torch 安装到隔离目录，不污染主 Python 环境。

## 调用链路
  前端 startDownload()
    → bridge download_torch（Kotlin DownloadTorchJsMessageHandler）
      → 创建 WorkflowRun + Task，payload 含下载参数
        → DownloadTorchExecutor（Kotlin，读 payload）
          → PythonUtil.websocketTask("/torch/install-task", paramJson)
            → routes/torch.py InstallTorchTaskEndpoint
              → install_torch_worker（本文件，子进程）
                → pip install --target ...

## param 字段说明
  variant           (str)        用户选择的 variant，如 "cu124"；"custom" 时走自定义逻辑
  download_dir      (str)        {dataDir}/download/torch/，由 Kotlin AppUtil.Paths.torchDownloadDir 提供
  torch_version     (str)        可选（非 custom），torch 版本号，如 "2.7.0"；空串时安装最新版
                                 后端调用 resolve_packages(torch_version) 构造完整包列表
  index_url         (str)        必填（非 custom），pip index-url
  index_url_mode    (str)        "replace"（默认，--index-url）或 "extra"（--extra-index-url）
  expected_version  (str)        可选，期望的 torch 版本号；传入时版本不匹配视为未安装
  use_proxy         (bool)       是否启用代理
  proxy             (str)        代理地址（http://host:port），use_proxy=False 时忽略
  custom_packages   (str)        自定义包列表（换行分隔），仅 variant=="custom" 时使用
  custom_index_url  (str)        自定义 index-url，仅 variant=="custom" 时使用
  custom_variant_id (str)        自定义 variant 目录名，仅 variant=="custom" 时使用

## status_queue 消息格式
  {"type": "check_result",   "already_ok": bool, "installed_version": str, "target_dir": str}
      — 下载前先检查目标目录是否已有可用版本；already_ok=True 时直接结束
  {"type": "download_start", "packages": [...], "target_dir": str, "cmd": str}
      — 即将执行的 pip 命令（cmd 字段为完整命令字符串，便于调试）
  {"type": "progress",       "percent": int, "statusText": str}
      — pip 输出的每一行；percent=-1 表示非进度行（如 WARNING/INFO）
  {"type": "done",           "result": {"variant": str, "target_dir": str}}
      — 下载成功
  {"type": "error",          "message": str}
      — 任何错误（参数缺失 / pip 非零退出 / 异常）
"""
import re
import subprocess
import sys
import time
from collections import deque
from pathlib import Path
from typing import TextIO

from loguru import logger


def install_torch_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    下载指定 variant 的 torch 到隔离目录。详见模块 docstring。
    """
    from fredica_pyutil_server.subprocess.python_process_init_util import init_loguru
    init_loguru(logger=logger)

    def _put(msg):
        status_queue.put(msg)

    def _cancelled():
        return cancel_event.is_set()

    logger.info(f"[install_torch_worker] 启动，param={{"
                f"variant={param.get('variant')!r}, "
                f"download_dir={param.get('download_dir')!r}, "
                f"pip_log_file_path={param.get('pip_log_file_path')!r}, "
                f"use_proxy={param.get('use_proxy')}, "
                f"index_url={param.get('index_url')!r}, "
                f"proxy={'***' if param.get('proxy') else ''}}}")

    try:
        variant: str = param.get("variant", "")
        download_dir: str = param.get("download_dir", "")
        use_proxy: bool = bool(param.get("use_proxy", False))
        proxy: str = param.get("proxy", "")
        pip_log_file_path: str = param.get("pip_log_file_path", "")
        index_url_mode: str = param.get("index_url_mode", "replace")
        expected_version: str = param.get("expected_version", "")

        if not variant:
            logger.error("[install_torch_worker] variant 为空，终止")
            _put({"type": "error", "message": "variant 为空，无法下载"})
            return
        if not download_dir:
            logger.error("[install_torch_worker] download_dir 为空，终止")
            _put({"type": "error", "message": "download_dir 为空，无法下载"})
            return

        # ── 解析包列表和 index_url ──────────────────────────────────────────
        if variant == "custom":
            raw_pkgs = param.get("custom_packages", "")
            packages = [p.strip() for p in raw_pkgs.splitlines() if p.strip()]
            index_url = param.get("custom_index_url", "")
            actual_variant = param.get("custom_variant_id", "custom") or "custom"
            logger.info(f"[install_torch_worker] custom variant: actual_variant={actual_variant!r} "
                        f"packages={packages} index_url={index_url!r}")
        else:
            torch_version = param.get("torch_version", "").strip()
            index_url = param.get("index_url", "").strip()
            actual_variant = variant
            from fredica_pyutil_server.util.torch_version_util import resolve_packages
            packages = resolve_packages(torch_version)
            if not index_url:
                # 官方源：index_url 为空时使用 pytorch.org 默认源
                index_url = f"https://download.pytorch.org/whl/{variant}"
                logger.info(f"[install_torch_worker] index_url 为空，使用官方源: {index_url!r}")
            logger.info(f"[install_torch_worker] variant={variant!r} torch_version={torch_version!r} "
                        f"packages={packages} index_url={index_url!r} index_url_mode={index_url_mode!r}")

        target_dir = Path(download_dir)
        logger.info(f"[install_torch_worker] target_dir={target_dir}")

        # ── 检查是否已下载 ──────────────────────────────────────────────────
        from fredica_pyutil_server.util.torch_version_util import check_torch_download
        check = check_torch_download(download_dir, expected_version)
        logger.info(f"[install_torch_worker] check_result: already_ok={check.already_ok} "
                    f"installed_version={check.installed_version!r} target_dir={check.target_dir!r}")
        _put({
            "type": "check_result",
            "already_ok": check.already_ok,
            "installed_version": check.installed_version,
            "target_dir": str(target_dir),
        })
        if check.already_ok:
            logger.info(f"[install_torch_worker] 已存在可用版本 {check.installed_version!r}，跳过下载")
            _put({"type": "done", "result": {"variant": actual_variant, "target_dir": str(target_dir)}})
            return

        if _cancelled():
            logger.info("[install_torch_worker] 已取消（check 后）")
            return

        target_dir.mkdir(parents=True, exist_ok=True)

        # ── 构建 pip 命令 ───────────────────────────────────────────────────
        from fredica_pyutil_server.util.torch_version_util import build_pip_install_cmd
        cmd = build_pip_install_cmd(
            packages=packages,
            target_dir=str(target_dir),
            index_url=index_url,
            index_url_mode=index_url_mode,
            use_proxy=use_proxy,
            proxy=proxy,
        )
        cmd += ["--progress-bar", "raw"]
        cmd_str = " ".join(cmd)

        pip_log_file = Path(pip_log_file_path) if pip_log_file_path else None
        pip_log_fp: TextIO | None = None
        if pip_log_file is not None:
            pip_log_file.parent.mkdir(parents=True, exist_ok=True)
            pip_log_fp = pip_log_file.open("w", encoding="utf-8")

        logger.info(f"[install_torch_worker] 执行 pip 命令: {cmd_str}")
        _put({"type": "download_start", "packages": packages, "target_dir": str(target_dir), "cmd": cmd_str})

        # ── 启动 pip 子进程，逐行解析输出 ───────────────────────────────────
        # pip 进度条用 \r 回到行首覆写，不发 \n，因此不能用 readline()。
        # 改为按字符读取，遇到 \r 或 \n 都视为行结束。
        t_start = time.monotonic()
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )

        def _iter_lines(stream):
            """
            后台线程读取字符，主线程每 0.1s 轮询队列并检查取消信号。
            兼容 Windows（select 不支持 pipe）。
            遇到 \\r 或 \\n 都 yield 一行。
            """
            import queue
            import threading

            char_queue: queue.Queue = queue.Queue()

            def _reader():
                try:
                    while True:
                        ch = stream.read(1)
                        if isinstance(ch, bytes):
                            ch = ch.decode("utf-8", errors="replace")
                        char_queue.put(ch)
                        if not ch:
                            break
                except Exception:
                    char_queue.put("")

            t = threading.Thread(target=_reader, daemon=True)
            t.start()

            buf = []
            char_count = 0
            while True:
                if _cancelled():
                    logger.info("[_iter_lines] 取消信号，退出读取")
                    break
                try:
                    ch = char_queue.get(timeout=0.1)
                except queue.Empty:
                    continue
                char_count += 1
                # if char_count <= 500:
                # pass
                # logger.debug(f"[_iter_lines] #{char_count} ch={ch!r} ord={ord(ch) if ch else 'EOF'}")
                if not ch:
                    # logger.debug(f"[_iter_lines] EOF, buf={(''.join(buf))!r}")
                    if buf:
                        yield "".join(buf).rstrip()
                    break
                if ch in ("\r", "\n"):
                    _line = "".join(buf).rstrip()
                    buf.clear()
                    if _line:
                        # logger.debug(f"[_iter_lines] yield line={_line!r}")
                        yield _line
                else:
                    buf.append(ch)

        # 进度解析：
        #   pip --progress-bar raw 输出格式："Progress <done> of <total>"
        #   同一次 pip 会依次下载多个包，每个包的进度从 0 重新开始，
        #   通过 _PackageProgressTracker 将多包进度合并为单调递增的总进度。
        _progress_pattern = re.compile(r"^Progress\s+(\d+)\s+of\s+(\d+)$")
        _error_lines: list[str] = []  # 收集 pip ERROR 行，失败时附加到 error message
        _recent_lines: deque[str] = deque(maxlen=5)
        _last_pip_summary_ts = 0.0

        class _PackageProgressTracker:
            """
            将 pip 多包下载的分段进度合并为单调递增的总进度（0-100）。

            pip 每下载一个包都会重新从 Progress 0 of <N> 开始。
            满足以下任一条件时视为新包开始：
              1. total_bytes 与当前包不同（不同包大小不同）
              2. done 相比上次出现倒退（同大小的不同包）

            旧包强制标记为 100%，新包作为新槽位加入列表。
            总进度 = 所有槽的平均值
            """

            def __init__(self) -> None:
                self._slots: list[int] = []   # 每个已见包的当前进度 (0-100)
                self._current_total: int = 0   # 当前包的 total_bytes
                self._current_done: int = 0    # 当前包上次的 done_bytes

            def update(self, done: int, total: int) -> int:
                """
                更新进度，返回合并后的总进度 (0-100)。
                total=0 时返回 -1（无法计算）。
                """
                if total <= 0:
                    return -1

                is_new_package = (total != self._current_total) or (done < self._current_done)
                if is_new_package:
                    # 新包出现：把上一个槽标记为 100%（若有），然后开新槽
                    if self._slots:
                        self._slots[-1] = 100
                    self._slots.append(0)
                    self._current_total = total

                # 更新当前槽的进度
                pct = int(done / total * 100)
                self._slots[-1] = pct
                self._current_done = done

                # 总进度 = 所有槽的平均值
                return int(sum(self._slots) / len(self._slots))

        _tracker = _PackageProgressTracker()

        def _append_pip_log_line(line: str) -> None:
            nonlocal _last_pip_summary_ts
            if not line:
                return
            _recent_lines.append(line)
            if pip_log_fp is not None:
                pip_log_fp.write(line)
                pip_log_fp.write("\n")
                pip_log_fp.flush()
            now = time.monotonic()
            if now - _last_pip_summary_ts >= 15:
                _last_pip_summary_ts = now
                log_size = pip_log_file.stat().st_size if pip_log_file is not None and pip_log_file.exists() else 0
                logger.debug(
                    f"[install_torch_worker] pip log summary: path={pip_log_file_path!r} size={log_size} last_lines={list(_recent_lines)!r}"
                )

        def _stop_pip_process() -> None:
            if proc.poll() is not None:
                return
            logger.info(f"[install_torch_worker] stopping pip process pid={proc.pid}")
            proc.terminate()
            try:
                proc.wait(timeout=3)
                logger.info(f"[install_torch_worker] pip terminated pid={proc.pid} returncode={proc.returncode}")
            except subprocess.TimeoutExpired:
                logger.warning(f"[install_torch_worker] pip terminate timeout, kill pid={proc.pid}")
                proc.kill()
                proc.wait()
                logger.info(f"[install_torch_worker] pip killed pid={proc.pid} returncode={proc.returncode}")

        for line in _iter_lines(proc.stdout):
            if not str(line).startswith(" "):
                logger.debug("[install_torch_worker] pip: {}", line)
            if _cancelled():
                logger.info("[install_torch_worker] 已取消（下载中），终止子进程")
                _stop_pip_process()
                return
            # 收集 ERROR 行，供失败时上报
            if line.startswith("ERROR:"):
                _error_lines.append(line)

            _append_pip_log_line(line)

            # 解析进度：匹配 pip --progress-bar raw 的 "Progress <done> of <total>" 格式
            m2 = _progress_pattern.search(line)
            if m2:
                try:
                    done_val = int(m2.group(1))
                    total_val = int(m2.group(2))
                    pct = _tracker.update(done_val, total_val)
                except Exception:
                    pct = -1
                _put({"type": "progress", "percent": pct, "statusText": line})
                continue

            # 非进度行（WARNING / INFO / 包名等），原样上报，percent=-1 表示无进度信息
            if line:
                _put({"type": "progress", "percent": -1, "statusText": line})

        # _iter_lines 因取消信号 break 后，需在此终止子进程
        if _cancelled():
            logger.info("[install_torch_worker] 已取消（读取结束后），终止子进程")
            _stop_pip_process()
            return

        proc.wait()
        elapsed = time.monotonic() - t_start
        logger.info(f"[install_torch_worker] pip 退出，returncode={proc.returncode} 耗时={elapsed:.1f}s")

        if _cancelled():
            logger.info("[install_torch_worker] 已取消（pip 结束后）")
            return

        if proc.returncode != 0:
            # 优先用收集到的 ERROR 行作为错误信息，方便用户定位问题
            detail = "\n".join(_error_lines) if _error_lines else f"pip 退出码 {proc.returncode}"
            logger.error(f"[install_torch_worker] pip 失败，returncode={proc.returncode}\n{detail}")
            _put({"type": "error", "message": detail})
            return

        logger.info(f"[install_torch_worker] 下载完成 variant={actual_variant!r} target_dir={target_dir}")
        _put({"type": "done", "result": {"variant": actual_variant, "target_dir": str(target_dir)}})

    except Exception as e:
        logger.exception(f"[install_torch_worker] 未预期异常: {e}")
        _put({"type": "error", "message": str(e)})
    finally:
        if 'pip_log_fp' in locals() and pip_log_fp is not None:
            pip_log_fp.close()
