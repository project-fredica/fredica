# -*- coding: UTF-8 -*-
"""
torch 下载子进程 worker。

在独立子进程中运行，通过 pip install --target 将 torch 安装到隔离目录。
由 routes/torch.py 的 InstallTorchTaskEndpoint 调用。
"""


def install_torch_worker(param: dict, status_queue, cancel_event, resume_event):
    """
    下载指定 variant 的 torch 到隔离目录。

    param 字段：
        variant      (str)  用户选择的 variant，如 "cu124"（由 Kotlin 层保证非空）
        download_dir (str)  {dataDir}/download/torch/
        use_proxy    (bool) 是否启用代理
        proxy        (str)  代理地址，use_proxy=False 时忽略
        custom_packages   (str)  自定义包列表（换行分隔），variant=="custom" 时使用
        custom_index_url  (str)  自定义 index-url，variant=="custom" 时使用
        custom_variant_id (str)  自定义 variant 目录名，variant=="custom" 时使用

    向 status_queue 推送：
        {"type": "check_result",   "already_ok": bool, "installed_version": str, "target_dir": str}
        {"type": "download_start", "packages": [...], "target_dir": str}
        {"type": "progress",       "percent": int, "line": str}
        {"type": "done",           "result": {"variant": str, "target_dir": str}}
        {"type": "error",          "message": str}
    """
    import re
    import subprocess
    import sys
    from pathlib import Path

    def _put(msg):
        status_queue.put(msg)

    def _cancelled():
        return cancel_event.is_set()

    try:
        variant: str = param.get("variant", "")
        download_dir: str = param.get("download_dir", "")
        use_proxy: bool = bool(param.get("use_proxy", False))
        proxy: str = param.get("proxy", "")

        if not variant:
            _put({"type": "error", "message": "variant 为空，无法下载"})
            return
        if not download_dir:
            _put({"type": "error", "message": "download_dir 为空，无法下载"})
            return

        # 解析包列表和 index_url
        if variant == "custom":
            raw_pkgs = param.get("custom_packages", "")
            packages = [p.strip() for p in raw_pkgs.splitlines() if p.strip()]
            index_url = param.get("custom_index_url", "")
            actual_variant = param.get("custom_variant_id", "custom") or "custom"
        else:
            from fredica_pyutil_server.util.torch_version_util import VARIANT_OPTIONS
            opt = VARIANT_OPTIONS.get(variant)
            if opt is None:
                _put({"type": "error", "message": f"未知 variant: {variant}"})
                return
            packages = list(opt.packages)
            # 支持自定义下载源（如国内镜像），覆盖默认官方源
            index_url = param.get("index_url", "").strip() or opt.index_url
            actual_variant = variant

        target_dir = Path(download_dir) / actual_variant

        # 检查是否已下载
        from fredica_pyutil_server.util.torch_version_util import check_torch_download
        check = check_torch_download(actual_variant, download_dir)
        _put({
            "type": "check_result",
            "already_ok": check.already_ok,
            "installed_version": check.installed_version,
            "target_dir": str(target_dir),
        })
        if check.already_ok:
            _put({"type": "done", "result": {"variant": actual_variant, "target_dir": str(target_dir)}})
            return

        if _cancelled():
            return

        target_dir.mkdir(parents=True, exist_ok=True)

        # 构建 pip 命令
        cmd = [
            sys.executable, "-m", "pip", "install",
            "--target", str(target_dir),
            *packages,
            "--extra-index-url", index_url,
            "--progress-bar", "on",
        ]
        if use_proxy and proxy:
            cmd += ["--proxy", proxy]

        _put({"type": "download_start", "packages": packages, "target_dir": str(target_dir)})

        # 启动 pip 子进程，解析进度
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )

        # 进度解析：pip 输出 "Downloading xxx (2.5 GB)" 和进度条
        _download_size_mb = 0
        _downloaded_mb = 0
        _size_pattern = re.compile(r"Downloading .+\((\d+(?:\.\d+)?)\s*(MB|GB|kB|B)\)")
        _progress_pattern = re.compile(r"(\d+)%\|")

        for line in proc.stdout:
            line = line.rstrip()
            if _cancelled():
                proc.terminate()
                return

            # 解析文件大小
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

            # 解析百分比进度
            m2 = _progress_pattern.search(line)
            if m2:
                pct = int(m2.group(1))
                _put({"type": "progress", "percent": pct, "line": line})
                continue

            if line:
                _put({"type": "progress", "percent": -1, "line": line})

        proc.wait()
        if _cancelled():
            return

        if proc.returncode != 0:
            _put({"type": "error", "message": f"pip 退出码 {proc.returncode}"})
            return

        _put({"type": "done", "result": {"variant": actual_variant, "target_dir": str(target_dir)}})

    except Exception as e:
        _put({"type": "error", "message": str(e)})
