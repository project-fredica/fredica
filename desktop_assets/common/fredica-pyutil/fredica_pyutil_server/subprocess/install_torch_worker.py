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
  {"type": "progress",       "percent": int, "line": str}
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
from pathlib import Path

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
                f"use_proxy={param.get('use_proxy')}, "
                f"index_url={param.get('index_url')!r}, "
                f"proxy={'***' if param.get('proxy') else ''}}}")

    try:
        variant: str = param.get("variant", "")
        download_dir: str = param.get("download_dir", "")
        use_proxy: bool = bool(param.get("use_proxy", False))
        proxy: str = param.get("proxy", "")
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
                logger.error("[install_torch_worker] index_url 为空，终止")
                _put({"type": "error", "message": "index_url 为空，无法下载"})
                return
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
        cmd += ["--progress-bar", "on"]

        cmd_str = " ".join(cmd)
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
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=0,  # 无缓冲，确保字符实时到达
        )

        def _iter_lines(stream):
            """按字符读取，遇到 \\r 或 \\n 都 yield 一行（去掉末尾空白）。"""
            buf = []
            while True:
                ch = stream.read(1)
                if not ch:
                    if buf:
                        yield "".join(buf).rstrip()
                    break
                if ch in ("\r", "\n"):
                    line = "".join(buf).rstrip()
                    buf.clear()
                    if line:
                        yield line
                else:
                    buf.append(ch)

        # 进度解析：
        #   "Downloading torch-2.7.0+cu128-... (2.5 GB)" → 只匹配 torch 主包，记录总大小
        #   "   ━━━━━ 0.7/2.9 GB 4.6 MB/s eta 0:10:32"  → 解析已下载/总量，计算百分比
        _download_size_mb = 0.0
        # 只匹配 torch 主包行（"Downloading torch-"），避免把依赖包的小文件大小覆盖掉
        _size_pattern = re.compile(r"^Downloading torch-[^\s].*\((\d+(?:\.\d+)?)\s*(MB|GB|kB|B)\)")
        # pip 新版进度条格式：数字/数字 单位，如 "0.7/2.9 GB" 或 "693.0/2886.1 MB"
        _progress_pattern = re.compile(r"(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)\s*(MB|GB|kB|B)")
        _error_lines: list[str] = []  # 收集 pip ERROR 行，失败时附加到 error message

        for line in _iter_lines(proc.stdout):
            if _cancelled():
                logger.info("[install_torch_worker] 已取消（下载中），终止子进程")
                proc.terminate()
                return

            # 收集 ERROR 行，供失败时上报
            if line.startswith("ERROR:"):
                _error_lines.append(line)

            # 解析文件大小（仅记录日志，不上报）
            m = _size_pattern.search(line)
            if m:
                size_val = float(m.group(1))
                unit = m.group(2)
                if unit == "GB":
                    _download_size_mb = size_val * 1024
                elif unit == "MB":
                    _download_size_mb = size_val
                elif unit == "kB":
                    _download_size_mb = size_val / 1024
                logger.debug(f"[install_torch_worker] torch 主包大小: {size_val}{unit} ({_download_size_mb:.1f} MB)")

            # 解析进度：匹配 "已下载/总量 单位" 格式，计算百分比上报给 Kotlin 层
            m2 = _progress_pattern.search(line)
            if m2:
                try:
                    done_val = float(m2.group(1))
                    total_val = float(m2.group(2))
                    pct = int(done_val / total_val * 100) if total_val > 0 else -1
                except Exception:
                    pct = -1
                _put({"type": "progress", "percent": pct, "line": line})
                continue

            # 非进度行（WARNING / INFO / 包名等），原样上报，percent=-1 表示无进度信息
            if line:
                logger.debug(f"[install_torch_worker] pip: {line}")
                _put({"type": "progress", "percent": -1, "line": line})

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
