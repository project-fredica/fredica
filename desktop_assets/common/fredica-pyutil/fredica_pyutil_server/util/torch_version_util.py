# -*- coding: UTF-8 -*-
"""
torch 版本推荐与下载检查工具。

主要 API：
  resolve_packages(torch_version) -> list[str]
  build_pip_install_cmd(packages, target_dir, index_url, ...) -> list[str]
  resolve_recommended_spec(device_info) -> TorchRecommendation
  check_torch_download(variant, download_dir, expected_version) -> TorchCheckResult
  fetch_mirror_supported_variants(mirror_key, proxy) -> MirrorVariantsResult
  check_mirror_availability(variant, mirror_key, proxy) -> MirrorAvailabilityResult
"""
import platform
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import List, TypedDict

from loguru import logger


# ---------------------------------------------------------------------------
# 数据类
# ---------------------------------------------------------------------------

@dataclass
class TorchRecommendation:
    """GPU 探测推荐结果。options 字段已移除，前端从镜像查询获取完整 variant 列表。"""
    recommended_variant: str
    reason: str
    driver_version: str

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class TorchCheckResult:
    variant: str
    already_ok: bool
    installed_version: str = ""
    target_dir: str = ""


# ---------------------------------------------------------------------------
# pip 命令构造（纯函数，无硬编码 variant 知识）
# ---------------------------------------------------------------------------

def resolve_packages(torch_version: str = "") -> list:
    """
    根据 torch 版本号构造 pip 包列表。
    只指定 torch 版本，torchvision/torchaudio 不指定版本，
    让 pip 根据 index-url 自动推断兼容版本。

    Args:
        torch_version: torch 版本号，如 "2.7.0"；空串时不指定版本（安装最新）

    Returns:
        如 ["torch==2.7.0", "torchvision", "torchaudio"]
    """
    torch_pkg = f"torch=={torch_version}" if torch_version else "torch"
    return [torch_pkg, "torchvision", "torchaudio"]


def build_pip_install_cmd(
    packages: list,
    target_dir: str,
    index_url: str,
    index_url_mode: str = "replace",
    use_proxy: bool = False,
    proxy: str = "",
) -> list:
    """
    构造 pip install 命令列表。纯函数，无任何硬编码 variant 知识。
    供 /pip-command/ 路由（预览）和 install_torch_worker（实际执行）共用，保证两者完全一致。

    Args:
        packages:       完整包列表，如 ["torch==2.7.0+cu128", "torchvision==0.22.0+cu128"]
        target_dir:     --target 目录；空串时不加 --target（仅预览场景）
        index_url:      pip index-url
        index_url_mode: "replace" → --index-url（替换默认源）；"extra" → --extra-index-url（追加）
        use_proxy:      是否启用代理
        proxy:          代理地址（http://host:port）

    Returns:
        命令列表，如 [sys.executable, "-m", "pip", "install", "--target", "...", ...]
    """
    cmd = [sys.executable, "-m", "pip", "install"]
    if target_dir:
        cmd += ["--target", target_dir]
    cmd += packages
    index_flag = "--extra-index-url" if index_url_mode == "extra" else "--index-url"
    cmd += [index_flag, index_url]
    if use_proxy and proxy:
        cmd += ["--proxy", proxy]
    return cmd


def _cu_sort_key(v: str) -> tuple:
    """
    cu variant 排序键：数字部分降序（新版在前），后缀字母升序。
    例：cu130 > cu129 > cu128 > cu128_full > cu121_pypi_cudnn
    """
    m = re.match(r"cu(\d+)(.*)", v)
    if not m:
        return (0, 0, "")
    return (1, -int(m.group(1)), m.group(2))


def _rocm_sort_key(v: str) -> tuple:
    """
    rocm variant 排序键：版本号降序（新版在前）。
    例：rocm7.1 > rocm7.0 > rocm6.4 > rocm6.2.4 > rocm6.2

    tuple 结构固定为 (major, minor, patch, suffix)，不足的位补 0/"" ，
    避免不同长度的 tuple 在比较时出现 int vs str 的 TypeError。
    例：rocm6.2   → (-6, -2,  0, "")
        rocm6.2.4 → (-6, -2, -4, "")
    """
    m = re.match(r"rocm([0-9]+(?:\.[0-9]+)*)(.*)", v)
    if not m:
        return (0, 0, 0, "")
    parts = [int(x) for x in m.group(1).split(".")]
    # 补齐到 3 位（major.minor.patch）
    while len(parts) < 3:
        parts.append(0)
    return (-parts[0], -parts[1], -parts[2], m.group(2))


def _sort_variants(variants: list[str]) -> list[str]:
    """
    按类型分组后各自降序排列：CUDA 新→旧，ROCm 新→旧，cpu 最后。
    带后缀的 variant（如 cu128_full、cu121_pypi_cudnn）紧跟对应主版本之后。
    """
    cu_variants = sorted([v for v in variants if v.startswith("cu")], key=_cu_sort_key)
    rocm_variants = sorted([v for v in variants if v.startswith("rocm")], key=_rocm_sort_key)
    cpu_variants = [v for v in variants if v == "cpu"]
    other = [v for v in variants if not v.startswith("cu") and not v.startswith("rocm") and v != "cpu"]
    return cu_variants + rocm_variants + other + cpu_variants


def _parse_version(ver: str) -> tuple:
    """将版本字符串转为可比较的 tuple，如 '2.10.0' -> (2, 10, 0)。"""
    try:
        return tuple(int(x) for x in ver.split("."))
    except Exception:
        return (0,)


def _max_version(versions: list[str]) -> str:
    """从版本字符串列表中取最大版本（语义版本比较，避免字符串比较的 '2.9.1 > 2.10.0' 问题）。"""
    if not versions:
        return ""
    return max(versions, key=_parse_version)


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
    返回 TorchRecommendation，含推荐 variant、原因、驱动版本。
    不再返回 options 列表——前端从镜像查询获取完整 variant 列表。
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

    return TorchRecommendation(
        recommended_variant=recommended_variant,
        reason=reason,
        driver_version=driver_version,
    )


# ---------------------------------------------------------------------------
# 下载检查
# ---------------------------------------------------------------------------

def check_torch_download(download_dir: str, expected_version: str = "") -> TorchCheckResult:
    """
    检查 download_dir 中是否已有可用的 torch（torch 直接安装到 pipLibDir，不按 variant 分子目录）。
    读取 torch-*.dist-info/METADATA 中的 Version 字段。

    Args:
        download_dir:     pip 安装目录，如 {dataDir}/download/pip/
        expected_version: 期望的 torch 版本号（如 "2.7.0+cu128"）；
                          空串时只检查目录是否存在且有 dist-info，不做版本比对。
    """
    target_dir = Path(download_dir)
    if not target_dir.exists():
        logger.debug(f"[torch] check_download: dir not found => not_ok")
        return TorchCheckResult(variant="", already_ok=False, target_dir=str(target_dir))

    # 扫描 dist-info，找到 torch-*.dist-info/METADATA 中的 Version 字段
    try:
        for dist_info in target_dir.glob("torch-*.dist-info"):
            metadata_file = dist_info / "METADATA"
            if not metadata_file.exists():
                continue
            for line in metadata_file.read_text(encoding="utf-8", errors="ignore").splitlines():
                if line.startswith("Version:"):
                    installed = line.split(":", 1)[1].strip()
                    if not expected_version:
                        logger.debug(f"[torch] check_download: installed={installed} (no version check) => ok")
                        return TorchCheckResult(
                            variant="", already_ok=True,
                            installed_version=installed, target_dir=str(target_dir),
                        )
                    if installed == expected_version:
                        logger.debug(f"[torch] check_download: installed={installed} matches expected => ok")
                        return TorchCheckResult(
                            variant="", already_ok=True,
                            installed_version=installed, target_dir=str(target_dir),
                        )
                    logger.debug(f"[torch] check_download: installed={installed} != expected={expected_version} => not_ok")
                    return TorchCheckResult(
                        variant="", already_ok=False,
                        installed_version=installed, target_dir=str(target_dir),
                    )
    except Exception:
        logger.exception(f"[torch] check_download: failed to read dist-info")
        return TorchCheckResult(variant="", already_ok=False, target_dir=str(target_dir))

    logger.debug(f"[torch] check_download: no dist-info found => not_ok")
    return TorchCheckResult(variant="", already_ok=False, target_dir=str(target_dir))


# ---------------------------------------------------------------------------
# 镜像源
# ---------------------------------------------------------------------------

@dataclass
class MirrorSource:
    key: str
    label: str
    url_fn: object  # callable: (variant: str) -> str
    supports_cuda: bool = True
    # "simple_api"：标准 PyPI Simple API，{base}/{variant}/torch/ 页面含 wheel 文件名
    # "stable_html"：静态 HTML 文件，{base}/torch_stable.html，含所有 wheel 链接
    # "dir_listing"：目录列表型，{base}/ 页面直接列出 cu128/ 等 variant 目录
    index_style: str = "simple_api"
    # stable_html / dir_listing 型专用：基础 URL（不含 variant 路径）
    stable_html_base: str = ""
    # dir_listing 型专用：
    #   "subdir"（默认，如 NJU）：torch wheel 在 {base}/{variant}/torch/ 子目录下
    #   "flat"（如 aliyun）：torch wheel 直接在 {base}/{variant}/ 目录下，无 torch/ 子目录
    dir_listing_layout: str = "subdir"
    # simple_api 型专用：预设 variant 列表。
    # 用于无法从根目录动态发现 variant 的镜像（如 sjtu 是反向代理，根目录无目录结构）。
    # 非空时跳过根目录抓取，直接用此列表作为 candidate_variants。
    preset_variants: list = None


MIRROR_SOURCES: list = [
    MirrorSource(
        key="official",
        label="官方源（pytorch.org）",
        url_fn=lambda v: f"https://download.pytorch.org/whl/{v}",
        supports_cuda=True,
        index_style="simple_api",
        stable_html_base="https://download.pytorch.org/whl",
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
        index_style="simple_api",
        stable_html_base="https://mirror.sjtu.edu.cn/pytorch-wheels",
        # sjtu 是反向代理（S3 后端），根目录无目录结构，无法动态发现 variant。
        # 用预设列表作为 candidate_variants，实际版本号仍通过 simple API 动态查询。
        preset_variants=["cpu", "cu118", "cu121", "cu124", "cu126", "cu128"],
    ),
    MirrorSource(
        key="aliyun",
        label="阿里云镜像",
        url_fn=lambda v: f"https://mirrors.aliyun.com/pytorch-wheels/{v}",
        supports_cuda=True,
        index_style="dir_listing",
        stable_html_base="https://mirrors.aliyun.com/pytorch-wheels",
        dir_listing_layout="flat",  # torch wheel 直接在 {variant}/ 目录下，无 torch/ 子目录
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


def _fetch_html(url: str, proxy: str, timeout: int = 60) -> str:
    """抓取 URL 返回 HTML 字符串，失败抛出异常。"""
    import urllib.request
    opener = urllib.request.build_opener()
    if proxy:
        opener.add_handler(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    req = urllib.request.Request(url, headers={"User-Agent": "fredica/1.0"})
    with opener.open(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")


# 匹配 cu/rocm variant 目录名的正则，用于从 dir_listing 页面动态发现新 variant（如 cu129/cu130）。
# variant 目录名格式：cu128、cu128_full、rocm6.3 等，不含连字符（-），以此排除 .whl 文件名。
_VARIANT_DIR_RE = re.compile(r'href="((cu|rocm)[^/".\-][^/"\-]*)/?[" /]')


def _extract_variants_from_dir_listing(html: str) -> list[str]:
    """
    从目录列表型镜像的根目录 HTML 中动态提取所有 variant 目录名。
    格式：<a href="cu128/" title="cu128">cu128/</a>
    返回所有匹配 cu*/rocm* 的目录名（不限于 _VARIANT_ORDER，可发现 cu129/cu130 等新版本）。
    """
    found = list(dict.fromkeys(m.group(1) for m in _VARIANT_DIR_RE.finditer(html)))
    logger.debug(f"[torch] _extract_variants_from_dir_listing: raw_found={found}")
    return found


def _find_variants_in_dir_listing(html: str) -> list[str]:
    """
    从目录列表型镜像的根目录 HTML 中提取支持的 variant 目录名。
    返回所有动态发现的 variant，按标准顺序排列。
    """
    raw = _extract_variants_from_dir_listing(html)
    found = _sort_variants(raw)
    logger.debug(f"[torch] _find_variants_in_dir_listing: found={found}")
    return found


def _find_variants_in_stable_html(html: str) -> dict[str, str]:
    """
    从 torch_stable.html 的内容中提取支持的 variant 及对应的最新 torch 版本。
    文件格式：每行一个 <a href="torch-2.7.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
    只匹配 torch- 开头（非 torchvision-/torchaudio- 等）的文件名。
    动态匹配所有 cu*/rocm* variant，不依赖硬编码列表。

    返回 dict[variant, torch_version]，如 {"cu128": "2.7.0", "cu126": "2.7.0", ...}
    版本号取该 variant 下出现的最大版本（语义版本比较，修复字符串比较 2.9.1 > 2.10.0 的问题）。
    """
    # 动态匹配所有 cu*/rocm* variant（不限于固定列表）
    pattern = re.compile(r'href="torch-([^"]+?\+((cu|rocm)[^"\-]+))-[^"]+\.whl"')
    all_vers: dict[str, list[str]] = {}
    for m in pattern.finditer(html):
        filename = m.group(1)
        variant = m.group(2)
        ver_match = re.match(r"^([^+]+)\+", filename)
        if ver_match:
            all_vers.setdefault(variant, []).append(ver_match.group(1))
    return {v: _max_version(vers) for v, vers in all_vers.items()}


def _fetch_versions_from_flat_dir(base_url: str, variant: str, proxy: str) -> list[str]:
    """
    flat 布局型镜像（如 aliyun）：torch wheel 直接在 {base_url}/{variant}/ 目录下。
    文件名格式：torch-2.10.0+cu128-cp310-cp310-...whl
    请求该目录页面，从 href 中提取 torch 版本号列表（语义版本降序）。
    失败返回空列表（不抛异常）。
    """
    dir_url = base_url.rstrip("/") + f"/{variant}/"
    logger.debug(f"[torch] _fetch_versions_from_flat_dir: variant={variant} url={dir_url}")
    try:
        html = _fetch_html(dir_url, proxy, timeout=60)
        if variant == "cpu":
            vers = re.findall(r'href="torch-([0-9]+\.[0-9]+\.[0-9]+)[+\-]', html)
        else:
            # 部分镜像（如阿里云）HTML 中 + 被编码为 &#43;，需同时匹配两种形式
            vers = re.findall(
                rf'href="torch-([0-9]+\.[0-9]+\.[0-9]+)(?:\+|&#43;){re.escape(variant)}-', html
            )
        if not vers:
            logger.debug(f"[torch] _fetch_versions_from_flat_dir: variant={variant} no versions found")
            return []
        unique = sorted(set(vers), key=_parse_version, reverse=True)
        logger.debug(f"[torch] _fetch_versions_from_flat_dir: variant={variant} versions={unique}")
        return unique
    except Exception as e:
        logger.debug(f"[torch] _fetch_versions_from_flat_dir: variant={variant} failed: {e}")
        return []


def _fetch_versions_from_simple_api(base_url: str, variant: str, proxy: str) -> list[str]:
    """
    请求 {base_url}/{variant}/torch/ 页面，返回该 variant 下所有 torch 版本列表（语义版本降序）。
    失败返回空列表（不抛异常）。
    """
    simple_url = base_url.rstrip("/") + f"/{variant}/torch/"
    logger.debug(f"[torch] _fetch_versions_from_simple_api: variant={variant} url={simple_url}")
    try:
        html = _fetch_html(simple_url, proxy, timeout=60)
        if variant == "cpu":
            # cpu wheel 文件名：torch-2.7.0+cpu- 或 torch-2.7.0-（无 +variant 后缀）
            vers = re.findall(r"torch-([0-9]+\.[0-9]+\.[0-9]+)[+\-]", html)
        else:
            vers = re.findall(rf"torch-([0-9]+\.[0-9]+\.[0-9]+)\+{re.escape(variant)}-", html)
        if not vers:
            return []
        unique = sorted(set(vers), key=_parse_version, reverse=True)
        logger.debug(f"[torch] _fetch_versions_from_simple_api: variant={variant} versions={unique}")
        return unique
    except Exception as e:
        logger.debug(f"[torch] _fetch_versions_from_simple_api: variant={variant} failed: {e}")
        return []


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

    if src.index_style == "dir_listing":
        # dir_listing 型：请求根目录，检查是否有 variant 子目录链接
        probe_url = src.stable_html_base.rstrip("/") + "/"
        logger.debug(f"[torch] check_mirror_availability: dir_listing mirror={mirror_key} variant={variant} url={probe_url}")
        try:
            html = _fetch_html(probe_url, proxy)
            available = bool(re.search(rf'href="{re.escape(variant)}/?[" /]', html))
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": probe_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": probe_url, "error": str(e)}
    else:
        # simple_api 型：{base}/{variant}/torch/ 页面含 wheel 文件名
        simple_url = src.stable_html_base.rstrip("/") + f"/{variant}/torch/"
        logger.debug(f"[torch] check_mirror_availability: simple_api mirror={mirror_key} variant={variant} url={simple_url}")
        try:
            html = _fetch_html(simple_url, proxy)
            available = f"+{variant}" in html or (variant == "cpu" and "torch-" in html)
            logger.debug(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} available={available}")
            return {"available": available, "url": simple_url, "error": ""}
        except Exception as e:
            logger.warning(f"[torch] check_mirror_availability: mirror={mirror_key} variant={variant} failed: {e}")
            return {"available": False, "url": simple_url, "error": str(e)}


class MirrorVariantsResult(TypedDict):
    """
    镜像站支持的 variant 列表查询结果。
    variants:       该镜像支持的 variant 列表（如 ["cu128", "cu126", "cpu"]），按标准顺序排列
    torch_versions: variant -> 版本号列表（降序），如 {"cu128": ["2.7.0", "2.6.0"], ...}
                    部分镜像可解析，否则为空 dict
    error:          失败原因，成功时为空字符串
    """
    variants: List[str]
    torch_versions: dict
    error: str


def fetch_mirror_supported_variants(mirror_key: str, proxy: str = "") -> MirrorVariantsResult:
    """
    抓取镜像站页面，返回该镜像支持的所有 variant 列表及各 variant 的 torch 版本列表。
    不抛异常，失败时 error 字段非空。

    - dir_listing 型（NJU/aliyun）：
        1. 请求根目录，动态发现所有 cu*/rocm* variant 目录（不限于 _VARIANT_ORDER）
        2. 逐个请求 {base}/{variant}/torch/ 获取版本号列表
    - simple_api 型（official/sjtu）：
        先尝试抓取根目录发现新 variant，再逐个请求 {base}/{variant}/torch/ 获取版本号
    """
    src = next((s for s in MIRROR_SOURCES if s.key == mirror_key), None)
    if src is None or src.key == "custom":
        logger.warning(f"[torch] fetch_mirror_variants: unsupported mirror_key={mirror_key!r}")
        return {"variants": [], "torch_versions": {}, "error": "unsupported mirror"}

    # tuna 等纯 PyPI 镜像不含 CUDA wheel，直接返回仅 cpu，无需网络请求
    if not src.supports_cuda:
        logger.debug(f"[torch] fetch_mirror_variants: mirror={mirror_key} is CPU-only, skip fetch")
        return {"variants": ["cpu"], "torch_versions": {}, "error": ""}

    if src.index_style == "dir_listing":
        return _fetch_dir_listing_variants(src, proxy)
    else:
        return _fetch_simple_api_variants(src, proxy)


def _fetch_dir_listing_variants(src: "MirrorSource", proxy: str) -> MirrorVariantsResult:
    """
    dir_listing 型镜像（NJU/aliyun）：
    1. 请求根目录，动态发现所有 variant 目录（包含 cu129/cu130 等新版本）
    2. 逐个请求 {base}/{variant}/torch/ 获取版本号列表
    """
    probe_url = src.stable_html_base.rstrip("/") + "/"
    logger.debug(f"[torch] fetch_mirror_variants: dir_listing mirror={src.key} fetching root {probe_url}")
    try:
        html = _fetch_html(probe_url, proxy, timeout=60)
    except Exception as e:
        logger.warning(f"[torch] fetch_mirror_variants: dir_listing mirror={src.key} root fetch failed: {e}")
        return {"variants": [], "torch_versions": {}, "error": str(e)}

    # 动态发现所有 variant 目录（不限于 _VARIANT_ORDER）
    raw_variants = _extract_variants_from_dir_listing(html)
    if not raw_variants:
        logger.warning(f"[torch] fetch_mirror_variants: dir_listing mirror={src.key} no variants found in root page")
        return {"variants": [], "torch_versions": {}, "error": "no variants found"}

    logger.debug(f"[torch] fetch_mirror_variants: dir_listing mirror={src.key} discovered variants={raw_variants}")

    # 根据布局选择版本获取函数：
    #   flat（aliyun）：torch wheel 直接在 {variant}/ 目录下
    #   subdir（NJU）：torch wheel 在 {variant}/torch/ 子目录下
    fetch_fn = _fetch_versions_from_flat_dir if src.dir_listing_layout == "flat" else _fetch_versions_from_simple_api

    # 并发请求各 variant 的版本号
    from concurrent.futures import ThreadPoolExecutor, as_completed
    torch_versions: dict[str, list[str]] = {}
    with ThreadPoolExecutor(max_workers=min(len(raw_variants), 8)) as pool:
        future_to_v = {
            pool.submit(fetch_fn, src.stable_html_base, v, proxy): v
            for v in raw_variants
        }
        for fut in as_completed(future_to_v):
            v = future_to_v[fut]
            try:
                vers = fut.result()
                if vers:
                    torch_versions[v] = vers
            except Exception as e:
                logger.debug(f"[torch] fetch_mirror_variants: dir_listing mirror={src.key} variant={v} version fetch error: {e}")

    # 目录存在即计入 variant 列表（即使 /torch/ 页面无内容）
    sorted_variants = _sort_variants(raw_variants)
    logger.info(f"[torch] fetch_mirror_variants: mirror={src.key} variants={sorted_variants} torch_versions={torch_versions}")
    return {"variants": sorted_variants, "torch_versions": torch_versions, "error": ""}


def _fetch_simple_api_variants(src: "MirrorSource", proxy: str) -> MirrorVariantsResult:
    """
    simple_api 型镜像（official/sjtu）：
    先尝试抓取根目录发现新 variant（如 cu129/cu130），再逐个请求 {base}/{variant}/torch/ 获取版本号。

    重试策略：
    - 网络异常（超时、连接失败）：最多重试 2 次，间隔 3s，因为可能是临时网络抖动
    - 页面解析为空（HTML 正常返回但无 variant 目录）：不重试，格式不匹配重试也无意义
    """
    import time

    # 第一阶段：构建候选 variant 列表
    # - preset_variants 非空（如 sjtu 反向代理）：直接使用，跳过根目录抓取
    # - 否则：抓取根目录 HTML 动态发现（不依赖硬编码列表）
    candidate_variants: list[str] = []
    _fetch_error: Exception | None = None
    if src.preset_variants:
        candidate_variants = list(src.preset_variants)
        logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} using preset_variants={candidate_variants}")
    elif src.stable_html_base:
        for attempt in range(3):  # 最多尝试 3 次（1 次 + 2 次重试）
            try:
                root_html = _fetch_html(src.stable_html_base.rstrip("/") + "/", proxy, timeout=60)
                discovered = _extract_variants_from_dir_listing(root_html)
                for v in discovered:
                    if v not in candidate_variants:
                        candidate_variants.append(v)
                logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} discovered from root: {discovered}")
                _fetch_error = None
                break  # 成功，退出重试循环
            except Exception as e:
                _fetch_error = e
                if attempt < 2:
                    logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} root fetch failed (attempt {attempt+1}/3), retrying in 3s: {e}")
                    time.sleep(3)
                else:
                    logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} root fetch failed after 3 attempts: {e}")

    # 第二阶段：并发请求各 candidate variant 的 /torch/ 子页面，确认版本号
    from concurrent.futures import ThreadPoolExecutor, as_completed
    torch_versions: dict[str, list[str]] = {}
    confirmed_variants: list[str] = []
    if not candidate_variants:
        # 网络异常导致的空：_fetch_error 非 None；页面解析为空：_fetch_error 为 None
        reason = f"network error: {_fetch_error}" if _fetch_error else "no variant dirs found in root page"
        logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} no candidate variants ({reason}), skip")
        return MirrorVariantsResult(variants=[], torch_versions={}, error="")
    with ThreadPoolExecutor(max_workers=min(len(candidate_variants), 8)) as pool:
        future_to_v = {
            pool.submit(_fetch_versions_from_simple_api, src.stable_html_base, v, proxy): v
            for v in candidate_variants
        }
        for fut in as_completed(future_to_v):
            v = future_to_v[fut]
            try:
                vers = fut.result()
                if vers:
                    torch_versions[v] = vers
                    confirmed_variants.append(v)
            except Exception as e:
                logger.debug(f"[torch] fetch_mirror_variants: simple_api mirror={src.key} variant={v} version fetch error: {e}")

    sorted_variants = _sort_variants(confirmed_variants)
    logger.info(f"[torch] fetch_mirror_variants: mirror={src.key} variants={sorted_variants} torch_versions={torch_versions}")
    if not sorted_variants:
        return {"variants": [], "torch_versions": {}, "error": "no variants found"}
    return {"variants": sorted_variants, "torch_versions": torch_versions, "error": ""}
