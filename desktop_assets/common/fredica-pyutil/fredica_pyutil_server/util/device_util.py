# -*- coding: UTF-8 -*-
"""
设备 GPU 能力检测工具。

detect_gpu_info() -> DeviceGpuInfo

按平台检测：
  - CUDA       (Win/Linux): pynvml → nvidia-smi fallback
  - ROCm       (Linux):     rocm-smi
  - Intel QSV  (Win):       注册表检测; (Linux): /dev/dri/renderD* + vainfo
  - VideoToolbox (macOS):   platform.system() == 'Darwin'
  - D3D11VA    (Windows):   d3d11.dll 加载检测
  - VAAPI      (Linux):     /dev/dri/renderD128 存在性检测

所有检测均 try/except，探测失败不抛出，仅标记 available=False。
"""

import platform
import subprocess
import time
from dataclasses import dataclass, field, asdict
from typing import List

from loguru import logger


# ---------------------------------------------------------------------------
# 子进程执行辅助（带 debug 日志）
# ---------------------------------------------------------------------------

def _run(cmd: List[str], *, timeout: int, label: str) -> subprocess.CompletedProcess:
    """运行子进程，以 debug 日志记录 args、exit_code 和耗时。"""
    t0 = time.monotonic()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except Exception as exc:
        elapsed = time.monotonic() - t0
        logger.debug(
            "[device_util] {} | args={} | error={!r} | elapsed={:.3f}s",
            label, cmd, exc, elapsed,
        )
        raise
    elapsed = time.monotonic() - t0
    logger.debug(
        "[device_util] {} | args={} | exit={} | elapsed={:.3f}s",
        label, cmd, r.returncode, elapsed,
    )
    return r


@dataclass
class CudaDevice:
    index: int
    name: str
    vram_mb: int


@dataclass
class CudaInfo:
    available: bool
    devices: List[CudaDevice] = field(default_factory=list)


@dataclass
class RocmInfo:
    available: bool


@dataclass
class QsvInfo:
    available: bool
    device: str = ""


@dataclass
class VideotoolboxInfo:
    available: bool


@dataclass
class D3d11vaInfo:
    available: bool


@dataclass
class VaapiInfo:
    available: bool


@dataclass
class GpuInfo:
    cuda: CudaInfo
    rocm: RocmInfo
    qsv: QsvInfo
    videotoolbox: VideotoolboxInfo
    d3d11va: D3d11vaInfo
    vaapi: VaapiInfo


@dataclass
class DeviceGpuInfo:
    detected_at: int
    platform: str
    gpu: GpuInfo

    def to_dict(self) -> dict:
        return asdict(self)


def _detect_cuda() -> CudaInfo:
    # 优先使用 pynvml（无需 PyTorch）
    try:
        import pynvml
        pynvml.nvmlInit()
        count = pynvml.nvmlDeviceGetCount()
        devices = []
        for i in range(count):
            handle = pynvml.nvmlDeviceGetHandleByIndex(i)
            name = pynvml.nvmlDeviceGetName(handle)
            if isinstance(name, bytes):
                name = name.decode()
            mem = pynvml.nvmlDeviceGetMemoryInfo(handle)
            devices.append(CudaDevice(index=i, name=name, vram_mb=mem.total // (1024 * 1024)))
        pynvml.nvmlShutdown()
        return CudaInfo(available=len(devices) > 0, devices=devices)
    except Exception as e:
        logger.debug("[device_util] pynvml detection failed: {}", e)

    # Fallback: nvidia-smi
    try:
        result = _run(
            ["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"],
            timeout=5, label="nvidia-smi",
        )
        if result.returncode == 0 and result.stdout.strip():
            devices = []
            for i, line in enumerate(result.stdout.strip().splitlines()):
                parts = line.split(",")
                if len(parts) >= 2:
                    name = parts[0].strip()
                    try:
                        vram_mb = int(parts[1].strip())
                    except ValueError:
                        vram_mb = 0
                    devices.append(CudaDevice(index=i, name=name, vram_mb=vram_mb))
            if devices:
                return CudaInfo(available=True, devices=devices)
    except Exception as e:
        logger.debug("[device_util] nvidia-smi detection failed: {}", e)

    return CudaInfo(available=False)


def _detect_rocm() -> RocmInfo:
    if platform.system() != "Linux":
        return RocmInfo(available=False)
    try:
        result = _run(["rocm-smi", "--showproductname"], timeout=5, label="rocm-smi")
        return RocmInfo(available=result.returncode == 0)
    except Exception as e:
        logger.debug("[device_util] rocm-smi detection failed: {}", e)
        return RocmInfo(available=False)


def _detect_qsv() -> QsvInfo:
    sys = platform.system()
    if sys == "Windows":
        try:
            import winreg  # type: ignore
            key = winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Intel\MediaSDK",
                access=winreg.KEY_READ | winreg.KEY_WOW64_64KEY,
            )
            winreg.CloseKey(key)
            return QsvInfo(available=True, device="Intel GPU")
        except Exception as e:
            logger.debug("[device_util] QSV MediaSDK registry check failed: {}", e)
        # Secondary: check for Intel display driver registry key
        try:
            import winreg  # type: ignore
            key = winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Intel",
                access=winreg.KEY_READ | winreg.KEY_WOW64_64KEY,
            )
            winreg.CloseKey(key)
            return QsvInfo(available=True, device="Intel GPU")
        except Exception as e:
            logger.debug("[device_util] QSV Intel registry check failed: {}", e)
        return QsvInfo(available=False)
    elif sys == "Linux":
        import os
        try:
            dri_devices = [f for f in os.listdir("/dev/dri") if f.startswith("renderD")]
            if not dri_devices:
                return QsvInfo(available=False)
            result = _run(["vainfo"], timeout=5, label="vainfo")
            if result.returncode == 0 and "VAProfileH264" in result.stdout:
                return QsvInfo(available=True)
        except Exception as e:
            logger.debug("[device_util] QSV Linux detection failed: {}", e)
        return QsvInfo(available=False)
    return QsvInfo(available=False)


def _detect_videotoolbox() -> VideotoolboxInfo:
    return VideotoolboxInfo(available=platform.system() == "Darwin")


def _detect_d3d11va() -> D3d11vaInfo:
    if platform.system() != "Windows":
        return D3d11vaInfo(available=False)
    try:
        import ctypes
        ctypes.windll.LoadLibrary("d3d11.dll")  # type: ignore
        return D3d11vaInfo(available=True)
    except Exception as e:
        logger.debug("[device_util] d3d11va detection failed: {}", e)
        return D3d11vaInfo(available=False)


def _detect_vaapi() -> VaapiInfo:
    if platform.system() != "Linux":
        return VaapiInfo(available=False)
    import os
    return VaapiInfo(available=os.path.exists("/dev/dri/renderD128"))


def detect_gpu_info() -> DeviceGpuInfo:
    """检测本机 GPU 能力，返回 DeviceGpuInfo（可序列化为 JSON）。"""
    sys_name = platform.system().lower()
    return DeviceGpuInfo(
        detected_at=int(time.time()),
        platform=sys_name,
        gpu=GpuInfo(
            cuda=_detect_cuda(),
            rocm=_detect_rocm(),
            qsv=_detect_qsv(),
            videotoolbox=_detect_videotoolbox(),
            d3d11va=_detect_d3d11va(),
            vaapi=_detect_vaapi(),
        ),
    )


if __name__ == "__main__":
    import json
    print(json.dumps(detect_gpu_info().to_dict(), indent=2, ensure_ascii=False))
