# -*- coding: UTF-8 -*-
"""
FFmpeg 发现、能力探测与转码命令构建工具。

主要 API：
  probe_ffmpeg(path)   -> FfmpegProbeInfo   探测单个 FFmpeg 可执行文件
  find_best_ffmpeg()   -> FfmpegProbeInfo   搜索所有候选路径，选出加速等级最高的
  TranscodeCommandBuilder.build(...)        根据 hw_accel 构建完整 FFmpeg 命令列表

硬件加速优先级：
  CUDA (h264_nvenc) > AMD AMF (h264_amf) > Intel QSV (h264_qsv)
    > Apple VideoToolbox (h264_videotoolbox) > CPU (libx264)
"""

import os
import platform
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional

from loguru import logger

# ---------------------------------------------------------------------------
# 加速等级
# ---------------------------------------------------------------------------

_ACCEL_PRIORITY: List[str] = ["cuda", "amf", "qsv", "videotoolbox", "cpu"]


def _accel_rank(accel: Optional[str]) -> int:
    """数值越小优先级越高；None / 未知 放到末尾。"""
    try:
        return _ACCEL_PRIORITY.index(accel)  # type: ignore[arg-type]
    except (ValueError, TypeError):
        return len(_ACCEL_PRIORITY)


# ---------------------------------------------------------------------------
# 数据模型
# ---------------------------------------------------------------------------

@dataclass
class FfmpegProbeInfo:
    probed_at: int
    found: bool
    path: str = ""
    version: str = ""
    hwaccels: List[str] = field(default_factory=list)
    encoders: Dict[str, bool] = field(default_factory=dict)
    selected_accel: Optional[str] = None
    all_paths: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)


# ---------------------------------------------------------------------------
# FFmpeg 候选路径搜索
# ---------------------------------------------------------------------------

def _find_ffmpeg_candidates() -> List[str]:
    """收集本机所有可能的 FFmpeg 可执行文件路径（去重、按发现顺序排列）。"""
    candidates: List[str] = []
    seen: set = set()

    def add(_p: str) -> None:
        _p = os.path.normpath(_p)
        if not _p or not os.path.isfile(_p):
            return
        # 用 realpath 解析符号链接，Windows 大小写不敏感
        real = os.path.realpath(_p)
        key = real.lower() if platform.system() == "Windows" else real
        if key not in seen:
            seen.add(key)
            candidates.append(_p)

    # 1. PATH via shutil.which
    w = shutil.which("ffmpeg")
    if w:
        add(w)

    # 2. where / whereis 命令
    sys = platform.system()
    if sys == "Windows":
        try:
            r = subprocess.run(["where", "ffmpeg"], capture_output=True, text=True, timeout=5)
            for line in r.stdout.strip().splitlines():
                add(line.strip())
        except Exception:
            pass
    else:
        try:
            r = subprocess.run(
                ["whereis", "-b", "ffmpeg"], capture_output=True, text=True, timeout=5
            )
            # Format: "ffmpeg: /usr/bin/ffmpeg /usr/local/bin/ffmpeg ..."
            parts = r.stdout.strip().split(":", 1)
            if len(parts) == 2:
                for p in parts[1].split():
                    add(p.strip())
        except Exception:
            pass

    # 3. 常见安装路径
    if sys == "Windows":
        pf = os.environ.get("ProgramFiles", r"C:\Program Files")
        pf86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
        local_app = os.environ.get("LOCALAPPDATA", "")
        user_profile = os.environ.get("USERPROFILE", "")
        hardcoded = [
            os.path.join(pf, "ffmpeg", "bin", "ffmpeg.exe"),
            os.path.join(pf86, "ffmpeg", "bin", "ffmpeg.exe"),
            r"C:\ffmpeg\bin\ffmpeg.exe",
            os.path.join(local_app, "Programs", "ffmpeg", "bin", "ffmpeg.exe"),
            os.path.join(user_profile, "scoop", "shims", "ffmpeg.exe"),
            r"C:\ProgramData\chocolatey\bin\ffmpeg.exe",
        ]
    elif sys == "Darwin":
        hardcoded = [
            "/usr/local/bin/ffmpeg",  # Homebrew Intel
            "/opt/homebrew/bin/ffmpeg",  # Homebrew Apple Silicon
            "/usr/bin/ffmpeg",
        ]
    else:  # Linux
        hardcoded = [
            "/usr/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/snap/bin/ffmpeg",
        ]

    for p in hardcoded:
        add(p)

    return candidates


# ---------------------------------------------------------------------------
# 子进程执行辅助（带 debug 日志）
# ---------------------------------------------------------------------------

def _run(cmd: List[str], *, timeout: int, label: str, text: bool = True) -> subprocess.CompletedProcess:
    """运行子进程，以 debug 日志记录 args、exit_code 和耗时。"""
    t0 = time.monotonic()
    try:
        r = subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)
    except Exception as exc:
        elapsed = time.monotonic() - t0
        logger.debug(
            "[ffmpeg_util] {} | args={} | error={!r} | elapsed={:.3f}s",
            label, cmd, exc, elapsed,
        )
        raise
    elapsed = time.monotonic() - t0
    logger.debug(
        "[ffmpeg_util] {} | args={} | exit={} | elapsed={:.3f}s",
        label, cmd, r.returncode, elapsed,
    )
    return r


# ---------------------------------------------------------------------------
# 能力探测辅助函数
# ---------------------------------------------------------------------------

def _get_version(ffmpeg_path: str) -> str:
    try:
        r = _run([ffmpeg_path, "-version"], timeout=5, label="version")
        m = re.search(r"ffmpeg version (\S+)", r.stdout)
        return m.group(1) if m else ""
    except Exception:
        return ""


def _get_hwaccels(ffmpeg_path: str) -> List[str]:
    try:
        r = _run([ffmpeg_path, "-hwaccels"], timeout=5, label="hwaccels")
        result = []
        for line in r.stdout.strip().splitlines():
            line = line.strip()
            if line and line != "Hardware acceleration methods:":
                result.append(line)
        return result
    except Exception:
        return []


_ENCODERS_TO_CHECK = [
    "h264_nvenc", "hevc_nvenc",
    "h264_amf",
    "h264_qsv",
    "h264_videotoolbox",
    "libx264",
]


def _get_encoders(ffmpeg_path: str) -> Dict[str, bool]:
    try:
        r = _run([ffmpeg_path, "-encoders"], timeout=10, label="encoders")
        return {enc: (enc in r.stdout) for enc in _ENCODERS_TO_CHECK}
    except Exception:
        return {enc: False for enc in _ENCODERS_TO_CHECK}


def _test_encoder(ffmpeg_path: str, encoder: str) -> bool:
    """静默测试编码器实际可用性（软件 YUV420P 帧输入，编码器自行上传到 GPU），超时 8 秒。

    不传硬件解码参数（-hwaccel 等），避免与 lavfi 软件源的帧格式冲突。
    h264_nvenc / h264_amf / h264_qsv / h264_videotoolbox 均支持接受软件帧并自行上传。
    """
    cmd = [
        ffmpeg_path, "-y",
        "-f", "lavfi", "-i", "color=c=black:s=320x240:r=1",
        "-vf", "format=yuv420p",
        "-c:v", encoder,
        "-frames:v", "1",
        "-f", "null", "-",
    ]
    try:
        r = _run(cmd, timeout=8, label=f"test_encoder({encoder})", text=False)
        return r.returncode == 0
    except Exception:
        return False


def _select_accel(
        ffmpeg_path: str,
        hwaccels: List[str],
        encoders: Dict[str, bool],
) -> Optional[str]:
    """按优先级选出实际可用的加速方案：设备支持 AND FFmpeg 支持 AND 测试通过。"""
    sys = platform.system()

    logger.debug("start _select_accel : ffmpeg_path = {} , hwaccels = {} , encoders = {} ",
                 ffmpeg_path, hwaccels, encoders)

    # CUDA
    if encoders.get("h264_nvenc") and "cuda" in hwaccels:
        if _test_encoder(ffmpeg_path, "h264_nvenc"):
            return "cuda"

    # AMD AMF
    if sys == "Windows" and encoders.get("h264_amf"):
        if _test_encoder(ffmpeg_path, "h264_amf"):
            return "amf"

    # Intel QSV
    if encoders.get("h264_qsv") and "qsv" in hwaccels:
        if _test_encoder(ffmpeg_path, "h264_qsv"):
            return "qsv"

    # Apple VideoToolbox
    if sys == "Darwin" and encoders.get("h264_videotoolbox"):
        if _test_encoder(ffmpeg_path, "h264_videotoolbox"):
            return "videotoolbox"

    # CPU fallback
    if encoders.get("libx264"):
        return "cpu"

    return None


# ---------------------------------------------------------------------------
# 公开 API
# ---------------------------------------------------------------------------

def probe_ffmpeg(ffmpeg_path: str) -> FfmpegProbeInfo:
    """探测单个 FFmpeg 可执行文件的能力。"""
    if not os.path.isfile(ffmpeg_path):
        return FfmpegProbeInfo(probed_at=int(time.time()), found=False)

    version = _get_version(ffmpeg_path)
    if not version:
        return FfmpegProbeInfo(probed_at=int(time.time()), found=False)

    hwaccels = _get_hwaccels(ffmpeg_path)
    encoders = _get_encoders(ffmpeg_path)
    selected_accel = _select_accel(ffmpeg_path, hwaccels, encoders)

    return FfmpegProbeInfo(
        probed_at=int(time.time()),
        found=True,
        path=ffmpeg_path,
        version=version,
        hwaccels=hwaccels,
        encoders=encoders,
        selected_accel=selected_accel,
    )


def find_best_ffmpeg() -> FfmpegProbeInfo:
    """
    搜索本机所有 FFmpeg 候选路径，逐一探测，返回硬件加速等级最高的结果。
    若未找到任何有效 FFmpeg，返回 found=False 的空结果。
    """
    candidates = _find_ffmpeg_candidates()
    if not candidates:
        return FfmpegProbeInfo(probed_at=int(time.time()), found=False)

    best: Optional[FfmpegProbeInfo] = None
    valid_paths: List[str] = []
    for path in candidates:
        info = probe_ffmpeg(path)
        if not info.found:
            continue
        valid_paths.append(path)
        if best is None or _accel_rank(info.selected_accel) < _accel_rank(best.selected_accel):
            best = info

    if best is not None:
        best.all_paths = valid_paths
        return best
    return FfmpegProbeInfo(probed_at=int(time.time()), found=False)


# ---------------------------------------------------------------------------
# 转码命令构建器
# ---------------------------------------------------------------------------

class TranscodeCommandBuilder:
    """
    根据 hw_accel 选择对应 FFmpeg 命令模板，返回完整 argv 列表。

    支持的 hw_accel 值：
      "cuda"        — NVIDIA NVENC
      "amf"         — AMD AMF (Windows: D3D11VA; Linux: VAAPI)
      "qsv"         — Intel Quick Sync Video
      "videotoolbox"— Apple VideoToolbox (macOS)
      "cpu"         — libx264 软件编码（通用降级）
      "auto"        — 退化为 "cpu"（应由调用方在传入前解析为具体值）
    """

    _COMMON_SUFFIX = ["-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-y"]

    def build(
            self,
            *,
            ffmpeg_path: str,
            input_video: str,
            input_audio: Optional[str],
            output_path: str,
            hw_accel: str,
    ) -> List[str]:
        sys = platform.system()

        if hw_accel == "auto":
            hw_accel = "cpu"

        if hw_accel == "cuda":
            pre = ["-hwaccel", "cuda", "-hwaccel_output_format", "cuda"]
            enc = ["h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "23", "-b:v", "0"]
        elif hw_accel == "amf":
            if sys == "Windows":
                pre = ["-hwaccel", "d3d11va"]
                enc = ["h264_amf", "-quality", "balanced", "-rc", "vbr_peak"]
            else:
                pre = [
                    "-hwaccel", "vaapi",
                    "-hwaccel_device", "/dev/dri/renderD128",
                    "-hwaccel_output_format", "vaapi",
                ]
                enc = ["h264_vaapi", "-qp", "23"]
        elif hw_accel == "qsv":
            if sys == "Windows":
                pre = ["-hwaccel", "qsv", "-hwaccel_output_format", "qsv"]
            else:
                pre = ["-hwaccel", "qsv", "-hwaccel_device", "/dev/dri/renderD128"]
            enc = ["h264_qsv", "-preset", "medium", "-global_quality", "23"]
        elif hw_accel == "videotoolbox":
            pre = []
            enc = ["h264_videotoolbox", "-b:v", "4M", "-allow_sw", "1"]
        else:  # cpu / fallback
            pre = []
            enc = ["libx264", "-preset", "medium", "-crf", "23"]

        # 输入部分（硬件解码参数放在 -i 之前）
        if input_audio:
            inputs = pre + ["-i", input_video, "-i", input_audio]
        else:
            inputs = pre + ["-i", input_video]

        return [ffmpeg_path] + inputs + ["-c:v"] + enc + self._COMMON_SUFFIX + [output_path]


def _test():
    import json

    result = find_best_ffmpeg()
    print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    _test()
