# -*- coding: UTF-8 -*-
"""
torch 符号链接生命周期管理。

setup_links(download_dir, variant)   在 site-packages 中为 variant 目录下的顶层包创建符号链接。
teardown_links(download_dir, variant) 删除由 setup_links 创建的符号链接。

Windows 符号链接权限 fallback：若 os.symlink() 失败，改用 sys.path.insert()。
"""
import os
import sys
import sysconfig
from pathlib import Path

from loguru import logger


def _site_packages() -> Path:
    return Path(sysconfig.get_path("purelib"))


def setup_links(download_dir: str, variant: str) -> bool:
    """
    在 site-packages 中为 {download_dir}/{variant}/ 下的所有顶层包目录/文件创建符号链接。

    - 已存在且指向正确目标的链接直接跳过
    - 指向旧目标的链接先删除再重建
    - Windows 符号链接失败时 fallback 到 sys.path.insert

    返回 False 表示 variant 目录不存在（torch 尚未下载）。
    """
    variant_dir = Path(download_dir) / variant
    if not variant_dir.exists():
        logger.info("[torch_link] variant dir not found, skip linking: {}", variant_dir)
        return False

    site_pkgs = _site_packages()
    use_path_fallback = False

    for item in variant_dir.iterdir():
        # 只链接包目录和 .dist-info 目录（跳过 __pycache__ 等）
        if item.name.startswith("_") and item.name != "__future__":
            continue
        if item.suffix in (".py",):
            # 顶层 .py 文件也需要链接（如 torchgen 等）
            pass
        link_path = site_pkgs / item.name
        target = item.resolve()

        if link_path.exists() or link_path.is_symlink():
            if link_path.is_symlink() and link_path.resolve() == target:
                logger.debug("[torch_link] already linked: ", link_path)
                continue
            # 指向旧目标，删除重建
            try:
                if link_path.is_symlink() or link_path.is_file():
                    link_path.unlink()
                else:
                    import shutil
                    shutil.rmtree(link_path)
            except Exception as e:
                logger.warning("[torch_link] failed to remove old link {}: {}", link_path, e)
                continue

        if not use_path_fallback:
            try:
                os.symlink(str(target), str(link_path))
                logger.debug("[torch_link] linked: {} -> {}", link_path, target)
            except OSError as e:
                logger.warning("[torch_link] symlink failed ({}), fallback to sys.path: {}", e, variant_dir)
                use_path_fallback = True

    if use_path_fallback:
        _path_insert(str(variant_dir))

    logger.info("[torch_link] setup done for variant={}", variant)
    return True


def teardown_links(download_dir: str, variant: str) -> None:
    """
    删除 site-packages 中由 setup_links 创建的符号链接。
    仅删除指向 {download_dir}/{variant}/ 内部的链接，不误删其他包。
    """
    variant_dir = Path(download_dir) / variant
    if not variant_dir.exists():
        return

    site_pkgs = _site_packages()
    variant_dir_resolved = variant_dir.resolve()

    for link_path in site_pkgs.iterdir():
        if not link_path.is_symlink():
            continue
        try:
            target = link_path.resolve()
            # 判断链接目标是否在 variant_dir 内
            target.relative_to(variant_dir_resolved)
            link_path.unlink()
            logger.debug("[torch_link] removed link: {}", link_path)
        except ValueError:
            pass  # 不在 variant_dir 内，跳过
        except Exception as e:
            logger.warning("[torch_link] failed to remove link {}: {}", link_path, e)

    # 清理 sys.path fallback
    _path_remove(str(variant_dir))
    logger.info("[torch_link] teardown done for variant={}", variant)


def _path_insert(path: str) -> None:
    if path not in sys.path:
        sys.path.insert(0, path)
        logger.info("[torch_link] sys.path.insert: {}", path)


def _path_remove(path: str) -> None:
    while path in sys.path:
        sys.path.remove(path)
