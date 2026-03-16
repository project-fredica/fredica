# -*- coding: UTF-8 -*-
"""
torch 版本推荐与下载检查工具。

主要 API：
  resolve_recommended_spec(device_info) -> TorchRecommendation
  check_torch_download(variant, download_dir) -> TorchCheckResult
"""
import platform
import re
import sysconfig
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import List, Optional, TypedDict

from loguru import logger


# ---------------------------------------------------------------------------
# 固定版本表
# ---------------------------------------------------------------------------

@dataclass
class TorchVariantOption:
    variant: str
    label: str
    index_url: str
    packages: List[str]
    is_recommended: bool = False


@dataclass
class TorchRecommendation:
    recommended_variant: str
    reason: str
    driver_version: str
    options: List[TorchVariantOption]

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class TorchCheckResult:
    variant: str
    already_ok: bool
    installed_version: str = ""
    target_dir: str = ""


# variant -> (packages, index_url)
VARIANT_OPTIONS: dict[str, TorchVariantOption] = {
    "cu128": TorchVariantOption(
        variant="cu128",
        label="CUDA 12.8",
        index_url="https://download.pytorch.org/whl/cu128",
        packages=["torch==2.7.0+cu128", "torchvision==0.22.0+cu128", "torchaudio==2.7.0+cu128"],
    ),
    "cu126": TorchVariantOption(
        variant="cu126",
        label="CUDA 12.6",
        index_url="https://download.pytorch.org/whl/cu126",
        packages=["torch==2.7.0+cu126", "torchvision==0.22.0+cu126", "torchaudio==2.7.0+cu126"],
    ),
    "cu124": TorchVariantOption(
        variant="cu124",
        label="CUDA 12.4",
        index_url="https://download.pytorch.org/whl/cu124",
        packages=["torch==2.6.0+cu124", "torchvision==0.21.0+cu124", "torchaudio==2.6.0+cu124"],
    ),
    "cu121": TorchVariantOption(
        variant="cu121",
        label="CUDA 12.1",
        index_url="https://download.pytorch.org/whl/cu121",
        packages=["torch==2.6.0+cu121", "torchvision==0.21.0+cu121", "torchaudio==2.6.0+cu121"],
    ),
    "cu118": TorchVariantOption(
        variant="cu118",
        label="CUDA 11.8",
        index_url="https://download.pytorch.org/whl/cu118",
        packages=["torch==2.7.0+cu118", "torchvision==0.22.0+cu118", "torchaudio==2.7.0+cu118"],
    ),
    "rocm6.3": TorchVariantOption(
        variant="rocm6.3",
        label="ROCm 6.3（仅 Linux）",
        index_url="https://download.pytorch.org/whl/rocm6.3",
        packages=["torch==2.7.0+rocm6.3", "torchvision==0.22.0+rocm6.3", "torchaudio==2.7.0+rocm6.3"],
    ),
    "rocm6.2": TorchVariantOption(
        variant="rocm6.2",
        label="ROCm 6.2（仅 Linux）",
        index_url="https://download.pytorch.org/whl/rocm6.2",
        packages=["torch==2.6.0+rocm6.2", "torchvision==0.21.0+rocm6.2", "torchaudio==2.6.0+rocm6.2"],
    ),
    "cpu": TorchVariantOption(
        variant="cpu",
        label="CPU only",
        index_url="https://download.pytorch.org/whl/cpu",
        packages=["torch==2.7.0+cpu", "torchvision==0.22.0+cpu", "torchaudio==2.7.0+cpu"],
    ),
}

# 展示顺序
_VARIANT_ORDER = ["cu128", "cu126", "cu124", "cu121", "cu118", "rocm6.3", "rocm6.2", "cpu"]


# ---------------------------------------------------------------------------
# 驱动版本解析
# ---------------------------------------------------------------------------

def _parse_driver_version(version_str: str) -> float:
    """从 '572.16' 或 '572' 等字符串中提取主版本号（float）。"""
    m = re.match(r"(\d+(?:\.\d+)?)", version_str.strip())
    return float(m.group(1)) if m else 0.0


def _get_nvidia_driver_version(device_info: dict) -> str:
    """从 device_info 中提取 NVIDIA 驱动版本字符串，失败返回空串。"""
    try:
        import pynvml
        pynvml.nvmlInit()
        ver = pynvml.nvmlSystemGetDriverVersion()
        pynvml.nvmlShutdown()
        if isinstance(ver, bytes):
            ver = ver.decode()
        return ver
    except Exception:
        pass

    # fallback: nvidia-smi
    try:
        import subprocess
        r = subprocess.run(
            ["nvidia-smi", "--query-gpu=driver_version", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=5,
        )
        if r.returncode == 0 and r.stdout.strip():
            return r.stdout.strip().splitlines()[0].strip()
    except Exception:
        pass

    return ""


# ---------------------------------------------------------------------------
# 推荐逻辑
# ---------------------------------------------------------------------------

def resolve_recommended_spec(device_info: dict) -> TorchRecommendation:
    """
    根据 detect_gpu_info().to_dict() 的结果推荐合适的 torch variant。
    返回 TorchRecommendation，含推荐 variant、原因、驱动版本、所有可选项。
    """
    sys_name = platform.system()
    is_windows = sys_name == "Windows"
    is_linux = sys_name == "Linux"

    gpu = device_info.get("gpu", {})
    cuda_info = gpu.get("cuda", {})
    rocm_info = gpu.get("rocm", {})

    recommended_variant = "cpu"
    reason = "未检测到 GPU，使用 CPU 模式"
    driver_version = ""

    if cuda_info.get("available"):
        devices = cuda_info.get("devices", [])
        gpu_name = devices[0]["name"] if devices else "NVIDIA GPU"
        driver_version = _get_nvidia_driver_version(device_info)
        drv = _parse_driver_version(driver_version) if driver_version else 0.0

        if is_windows:
            if drv >= 572:
                recommended_variant = "cu128"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥572），支持 CUDA 12.8"
            elif drv >= 561:
                recommended_variant = "cu126"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥561），支持 CUDA 12.6"
            elif drv >= 551:
                recommended_variant = "cu124"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥551），支持 CUDA 12.4"
            elif drv >= 531:
                recommended_variant = "cu121"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥531），支持 CUDA 12.1"
            elif drv >= 522:
                recommended_variant = "cu118"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥522），支持 CUDA 11.8"
            else:
                recommended_variant = "cpu"
                reason = f"检测到 {gpu_name}，但驱动 {driver_version or '未知'} 过旧，建议升级驱动后重新检测"
        elif is_linux:
            if drv >= 570:
                recommended_variant = "cu128"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥570），支持 CUDA 12.8"
            elif drv >= 560:
                recommended_variant = "cu126"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥560），支持 CUDA 12.6"
            elif drv >= 550:
                recommended_variant = "cu124"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥550），支持 CUDA 12.4"
            elif drv >= 530:
                recommended_variant = "cu121"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥530），支持 CUDA 12.1"
            elif drv >= 520:
                recommended_variant = "cu118"
                reason = f"检测到 {gpu_name}，驱动 {driver_version}（≥520），支持 CUDA 11.8"
            else:
                recommended_variant = "cpu"
                reason = f"检测到 {gpu_name}，但驱动 {driver_version or '未知'} 过旧，建议升级驱动后重新检测"
        else:
            recommended_variant = "cpu"
            reason = f"检测到 {gpu_name}，但当前平台（{sys_name}）不支持 CUDA，使用 CPU 模式"

    elif rocm_info.get("available") and is_linux:
        recommended_variant = "rocm6.2"
        reason = "检测到 AMD GPU（ROCm），推荐 ROCm 6.2"

    # 构建选项列表，标记推荐项
    options = []
    for v in _VARIANT_ORDER:
        opt = VARIANT_OPTIONS[v]
        options.append(TorchVariantOption(
            variant=opt.variant,
            label=opt.label + ("（推荐）" if v == recommended_variant else ""),
            index_url=opt.index_url,
            packages=list(opt.packages),
            is_recommended=(v == recommended_variant),
        ))

    return TorchRecommendation(
        recommended_variant=recommended_variant,
        reason=reason,
        driver_version=driver_version,
        options=options,
    )


# ---------------------------------------------------------------------------
# 下载检查
# ---------------------------------------------------------------------------

def check_torch_download(variant: str, download_dir: str) -> TorchCheckResult:
    """
    检查 {download_dir}/{variant}/ 中是否已有匹配版本的 torch。
    读取 torch-*.dist-info/METADATA 中的 Version 字段与锁定版本比对。
    """
    target_dir = Path(download_dir) / variant
    if not target_dir.exists():
        logger.debug(f"[torch] check_download: variant={variant} dir not found => not_ok")
        return TorchCheckResult(variant=variant, already_ok=False, target_dir=str(target_dir))

    # 从 VARIANT_OPTIONS 取锁定版本（custom variant 跳过检查）
    opt = VARIANT_OPTIONS.get(variant)
    if opt is None:
        # custom variant：目录存在即视为已下载
        logger.debug(f"[torch] check_download: variant={variant} is custom, dir exists => ok")
        return TorchCheckResult(variant=variant, already_ok=True, target_dir=str(target_dir))

    # 取 torch 包的锁定版本，如 "torch==2.6.0+cu124" -> "2.6.0+cu124"
    locked_torch_pkg = next((p for p in opt.packages if p.startswith("torch==")), None)
    if locked_torch_pkg is None:
        logger.warning(f"[torch] check_download: variant={variant} has no torch== entry in packages")
        return TorchCheckResult(variant=variant, already_ok=False, target_dir=str(target_dir))
    locked_version = locked_torch_pkg.split("==", 1)[1]

    # 扫描 dist-info，找到 torch-*.dist-info/METADATA 中的 Version 字段
    try:
        for dist_info in target_dir.glob("torch-*.dist-info"):
            metadata_file = dist_info / "METADATA"
            if not metadata_file.exists():
                continue
            for line in metadata_file.read_text(encoding="utf-8", errors="ignore").splitlines():
                if line.startswith("Version:"):
                    installed = line.split(":", 1)[1].strip()
                    if installed == locked_version:
                        logger.debug(f"[torch] check_download: variant={variant} installed={installed} matches locked => ok")
                        return TorchCheckResult(
                            variant=variant,
                            already_ok=True,
                            installed_version=installed,
                            target_dir=str(target_dir),
                        )
                    logger.debug(f"[torch] check_download: variant={variant} installed={installed} != locked={locked_version} => not_ok")
                    return TorchCheckResult(
                        variant=variant,
                        already_ok=False,
                        installed_version=installed,
                        target_dir=str(target_dir),
                    )
    except Exception:
        logger.exception(f"[torch] check_download: failed to read dist-info for variant={variant}")
        return TorchCheckResult(variant=variant, already_ok=False, target_dir=str(target_dir))

    logger.debug(f"[torch] check_download: variant={variant} no dist-info found => not_ok")
    return TorchCheckResult(variant=variant, already_ok=False, target_dir=str(target_dir))


# ---------------------------------------------------------------------------
# 镜像源
# ---------------------------------------------------------------------------

@dataclass
class MirrorSource:
    key: str
    label: str
    url_fn: object  # callable: (variant: str) -> str
    supports_cuda: bool = True
    # "simple_api"：标准 PyPI Simple API，{base}/torch/ 页面含 wheel 文件名
    # "stable_html"：静态 HTML 文件，{base}/torch_stable.html，含所有 wheel 链接，检查 "+{variant}" 字样
    # "dir_listing"：目录列表型，{base}/ 页面直接列出 cu128/ 等 variant 目录，从链接提取 variant
    # "sjtu_s3"：上海交大 S3 存储，{base}/?mirror_intel_list 列出顶层目录
    index_style: str = "simple_api"
    # stable_html / dir_listing / sjtu_s3 型专用：基础 URL（不含 variant 路径）
    stable_html_base: str = ""


MIRROR_SOURCES: list = [
    MirrorSource(
        key="official",
        label="官方源（pytorch.org）",
        url_fn=lambda v: VARIANT_OPTIONS[v].index_url if v in VARIANT_OPTIONS else "",
        supports_cuda=True,
    ),
    MirrorSource(
        key="nju",
        label="南京大学镜像",
        url_fn=lambda v: f"https://mirrors.nju.edu.cn/pytorch/whl/{v}",
        supports_cuda=True,
        index_style="dir_listing",
        stable_html_base="https://mirrors.nju.edu.cn/pytorch/whl",
    ),
    MirrorSource(
        key="sjtu",
        label="上海交大镜像",
        url_fn=lambda v: f"https://mirror.sjtu.edu.cn/pytorch-wheels/{v}",
        supports_cuda=True,
        index_style="sjtu_s3",
        stable_html_base="https://mirror.sjtu.edu.cn/pytorch-wheels",
    ),
    MirrorSource(
        key="aliyun",
        label="阿里云镜像",
        url_fn=lambda v: f"https://mirrors.aliyun.com/pytorch-wheels/{v}",
        supports_cuda=True,
        index_style="dir_listing",
        stable_html_base="https://mirrors.aliyun.com/pytorch-wheels",
    ),
    MirrorSource(
        key="tuna",
        label="清华 PyPI 镜像（仅 CPU，不含 CUDA wheel）",
        url_fn=lambda v: "https://pypi.tuna.tsinghua.edu.cn/simple" if v == "cpu" else "",
        supports_cuda=False,
    ),
    MirrorSource(
        key="custom",
        label="自定义…",
        url_fn=lambda v: "",
        supports_cuda=True,
    ),
]


def _fetch_html(url: str, proxy: str, timeout: int = 8) -> str:
    """抓取 URL 返回 HTML 字符串，失败抛出异常。"""
    import urllib.request
    opener = urllib.request.build_opener()
    if proxy:
        opener.add_handler(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    req = urllib.request.Request(url, headers={"User-Agent": "fredica/1.0"})
    with opener.open(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def _find_variants_in_dir_listing(html: str) -> list:
    """
    从目录列表型镜像（如南京大学）的根目录 HTML 中提取支持的 variant 目录名。
    格式：<a href="cu128/" title="cu128">cu128/</a>
    只返回在 _VARIANT_ORDER 中的 variant。
    """
    found = []
    for v in _VARIANT_ORDER:
        # 匹配 href="cu128/" 或 href="cu128"
        if re.search(rf'href="{re.escape(v)}/?["/ ]', html):
            found.append(v)
    logger.debug(f"[torch] _find_variants_in_dir_listing: found={found}")
    return found


def _find_variants_in_sjtu_s3(html: str) -> list:
    """
    从上海交大 S3 目录列表（?mirror_intel_list）HTML 中提取支持的 variant 目录名。
    格式：<a href="/pytorch-wheels/cu128/">cu128/</a> 或 <td><a href="...">cu128/</a></td>
    只返回在 _VARIANT_ORDER 中的 variant。
    """
    found = []
    for v in _VARIANT_ORDER:
        if re.search(rf'>{re.escape(v)}/?<', html):
            found.append(v)
    logger.debug(f"[torch] _find_variants_in_sjtu_s3: found={found}")
    return found


def _find_variants_in_stable_html(html: str) -> dict:
    """
    从 torch_stable.html 的内容中提取支持的 variant 及对应的最新 torch 版本。
    文件格式：每行一个 <a href="torch-2.7.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
    只匹配 torch- 开头（非 torchvision-/torchaudio- 等）的文件名。

    返回 dict[variant, torch_version]，如 {"cu128": "2.7.0", "cu126": "2.7.0", ...}
    版本号取该 variant 下出现的最大版本（按字符串比较，通常足够）。
    """
    # 正则：匹配 torch-{version}+{variant}- 形式的文件名
    # 示例：torch-2.7.0+cu128-cp311-cp311-linux_x86_64.whl
    pattern = re.compile(r'href="torch-([^"]+?\+(' + '|'.join(re.escape(v) for v in _VARIANT_ORDER) + r')-[^"]+\.whl)"')
    found: dict[str, str] = {}  # variant -> latest torch version
    for m in pattern.finditer(html):
        filename = m.group(1)   # e.g. "2.7.0+cu128-cp311-..."
        variant = m.group(2)    # e.g. "cu128"
        # 从文件名中提取版本号：{version}+{variant}-...
        ver_match = re.match(r'^([^+]+)\+', filename)
        if ver_match:
            ver = ver_match.group(1)  # e.g. "2.7.0"
            # 保留最大版本（字符串比较，对 semver 通常足够）
            if variant not in found or ver > found[variant]:
                found[variant] = ver
    return found


class MirrorAvailabilityResult(TypedDict):
    """
    镜像可用性探测结果。
    available: 该镜像是否有指定 variant 的 torch wheel
    url:       实际请求的 URL（用于调试）
    error:     失败原因，成功时为空字符串
    """
    available: bool
    url: str
    error: str


def check_mirror_availability(variant: str, mirror_key: str, proxy: str = "") -> MirrorAvailabilityResult:
    """抓取镜像站页面，判断该镜像是否有指定 variant 的 torch wheel。不抛异常，失败时 error 字段非空。"""
    src = next((s for s in MIRROR_SOURCES if s.key == mirror_key), None)
    if src is None or src.key == "custom":
        logger.warning(f"[torch] check_mirror_availability: unsupported mirror_key={mirror_key!r}")
        return {"available": False, "url": "", "error": "unsupported mirror"}

    index_url = src.url_fn(variant)
    if not index_url:
        logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} does not support variant={variant}")
        return {"available": False, "url": "", "error": "mirror does not support this variant"}

    if src.index_style == "stable_html":
        # stable_html 型：请求 {base}/torch_stable.html，检查 "+{variant}" 字样
        probe_url = src.stable_html_base.rstrip("/") + "/torch_stable.html"
        logger.debug(f"[torch] check_mirror_availability: stable_html mirror={mirror_key} variant={variant} url={probe_url}")
        try:
            html = _fetch_html(probe_url, proxy)
            logger.debug(f"[torch] check_mirror_availability: stable_html mirror={mirror_key} html_snippet={html[:2000]!r}")
            available = f"+{variant}" in html or variant == "cpu"
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": probe_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": probe_url, "error": str(e)}
    elif src.index_style == "dir_listing":
        # dir_listing 型（如南京大学）：请求根目录，检查是否有 variant 子目录链接
        probe_url = src.stable_html_base.rstrip("/") + "/"
        logger.debug(f"[torch] check_mirror_availability: dir_listing mirror={mirror_key} variant={variant} url={probe_url}")
        try:
            html = _fetch_html(probe_url, proxy)
            logger.debug(f"[torch] check_mirror_availability: dir_listing mirror={mirror_key} html_snippet={html[:2000]!r}")
            available = bool(re.search(rf'href="{re.escape(variant)}/?["/ ]', html)) or variant == "cpu"
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": probe_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": probe_url, "error": str(e)}
    elif src.index_style == "sjtu_s3":
        # sjtu_s3 型（上海交大）：请求 {base}/?mirror_intel_list，检查是否有 variant 目录
        probe_url = src.stable_html_base.rstrip("/") + "/?mirror_intel_list"
        logger.debug(f"[torch] check_mirror_availability: sjtu_s3 mirror={mirror_key} variant={variant} url={probe_url}")
        try:
            html = _fetch_html(probe_url, proxy)
            logger.debug(f"[torch] check_mirror_availability: sjtu_s3 mirror={mirror_key} html_snippet={html[:2000]!r}")
            available = bool(re.search(rf'>{re.escape(variant)}/?<', html)) or variant == "cpu"
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": probe_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": probe_url, "error": str(e)}
    else:
        # simple_api 型：{index_url}/torch/ 页面含 wheel 文件名
        simple_url = index_url.rstrip("/") + "/torch/"
        logger.debug(f"[torch] check_mirror_availability: simple_api mirror={mirror_key} variant={variant} url={simple_url}")
        try:
            html = _fetch_html(simple_url, proxy)
            logger.debug(f"[torch] check_mirror_availability: simple_api mirror={mirror_key} html_snippet={html[:2000]!r}")
            available = f"+{variant}" in html or variant == "cpu"
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": simple_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": simple_url, "error": str(e)}


class MirrorVariantsResult(TypedDict):
    """
    镜像站支持的 variant 列表查询结果。
    variants:       该镜像支持的 variant 列表（如 ["cu128", "cu126", "cpu"]）
    torch_versions: variant -> torch 版本号的映射（部分镜像可解析，否则为空 dict）
    error:          失败原因，成功时为空字符串
    """
    variants: List[str]
    torch_versions: dict
    error: str


def fetch_mirror_supported_variants(mirror_key: str, proxy: str = "") -> MirrorVariantsResult:
    """
    抓取镜像站页面一次，返回该镜像支持的所有 variant 列表。不抛异常，失败时 error 字段非空。
    - stable_html 型：请求 {base}/torch_stable.html，检查 "+{variant}" 字样
    - simple_api 型：请求 {base}/torch/，检查 "+{variant}" 字样
    """
    src = next((s for s in MIRROR_SOURCES if s.key == mirror_key), None)
    if src is None or src.key == "custom":
        logger.warning(f"[torch] fetch_mirror_variants: unsupported mirror_key={mirror_key!r}")
        return {"variants": [], "error": "unsupported mirror"}

    # tuna 等纯 PyPI 镜像不含 CUDA wheel，直接返回仅 cpu，无需网络请求
    if not src.supports_cuda:
        logger.debug(f"[torch] fetch_mirror_variants: mirror={mirror_key} is CPU-only, skip fetch")
        return {"variants": ["cpu"], "error": ""}

    # 官方源：直接从 VARIANT_OPTIONS 返回已知 variant，无需网络请求
    if src.key == "official":
        variants = list(_VARIANT_ORDER)
        logger.debug(f"[torch] fetch_mirror_variants: mirror=official returning builtin variants={variants}")
        return {"variants": variants, "error": ""}

    if src.index_style == "stable_html":
        # stable_html 型：请求 {base}/torch_stable.html，用正则精确匹配 torch 包文件名
        probe_url = src.stable_html_base.rstrip("/") + "/torch_stable.html"
        logger.debug(f"[torch] fetch_mirror_variants: stable_html mirror={mirror_key} fetching {probe_url}")
        try:
            html = _fetch_html(probe_url, proxy, timeout=10)
            logger.debug(f"[torch] fetch_mirror_variants: stable_html mirror={mirror_key} html_snippet={html[:2000]!r}")
            torch_versions = _find_variants_in_stable_html(html)  # dict[variant, torch_version]
            variants = [v for v in _VARIANT_ORDER if v in torch_versions]
            logger.info(f"[torch] fetch_mirror_variants: mirror={mirror_key} found variants={variants} torch_versions={torch_versions}")
            return {"variants": variants, "torch_versions": torch_versions, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] fetch_mirror_variants: mirror={mirror_key} fetch failed: {e}")
            return {"variants": [], "torch_versions": {}, "error": str(e)}
    elif src.index_style == "dir_listing":
        # dir_listing 型（如南京大学）：请求根目录，从目录列表链接提取 variant 子目录名
        probe_url = src.stable_html_base.rstrip("/") + "/"
        logger.debug(f"[torch] fetch_mirror_variants: dir_listing mirror={mirror_key} fetching {probe_url}")
        try:
            html = _fetch_html(probe_url, proxy, timeout=10)
            logger.debug(f"[torch] fetch_mirror_variants: dir_listing mirror={mirror_key} html_snippet={html[:2000]!r}")
            variants = _find_variants_in_dir_listing(html)
            logger.info(f"[torch] fetch_mirror_variants: mirror={mirror_key} found variants={variants}")
            return {"variants": variants, "torch_versions": {}, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] fetch_mirror_variants: mirror={mirror_key} fetch failed: {e}")
            return {"variants": [], "torch_versions": {}, "error": str(e)}
    elif src.index_style == "sjtu_s3":
        # sjtu_s3 型（上海交大）：请求 {base}/?mirror_intel_list，从 S3 目录列表提取 variant
        probe_url = src.stable_html_base.rstrip("/") + "/?mirror_intel_list"
        logger.debug(f"[torch] fetch_mirror_variants: sjtu_s3 mirror={mirror_key} fetching {probe_url}")
        try:
            html = _fetch_html(probe_url, proxy, timeout=10)
            logger.debug(f"[torch] fetch_mirror_variants: sjtu_s3 mirror={mirror_key} html_snippet={html[:2000]!r}")
            variants = _find_variants_in_sjtu_s3(html)
            logger.info(f"[torch] fetch_mirror_variants: mirror={mirror_key} found variants={variants}")
            return {"variants": variants, "torch_versions": {}, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] fetch_mirror_variants: mirror={mirror_key} fetch failed: {e}")
            return {"variants": [], "torch_versions": {}, "error": str(e)}
    else:
        # simple_api 型：{base}/torch/ 页面含 wheel 文件名
        sample_variant = "cu128"
        index_url = src.url_fn(sample_variant)
        if not index_url:
            logger.warning(f"[torch] fetch_mirror_variants: mirror={mirror_key} returned empty url for sample variant")
            return {"variants": [], "torch_versions": {}, "error": "mirror returned empty url"}
        base_url = index_url.rstrip("/")
        if base_url.endswith(f"/{sample_variant}"):
            base_url = base_url[: -len(f"/{sample_variant}")]
        simple_url = base_url.rstrip("/") + "/torch/"
        logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={mirror_key} fetching {simple_url}")
        try:
            html = _fetch_html(simple_url, proxy, timeout=10)
            logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={mirror_key} html_snippet={html[:2000]!r}")
            # simple_api 页面同样可用 _find_variants_in_stable_html 解析（格式相同）
            torch_versions = _find_variants_in_stable_html(html)
            variants = [v for v in _VARIANT_ORDER if v in torch_versions]
            logger.info(f"[torch] fetch_mirror_variants: mirror={mirror_key} found variants={variants} torch_versions={torch_versions}")
            return {"variants": variants, "torch_versions": torch_versions, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] fetch_mirror_variants: mirror={mirror_key} fetch failed: {e}")
            return {"variants": [], "torch_versions": {}, "error": str(e)}
