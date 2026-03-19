# -*- coding: UTF-8 -*-

def init_loguru(*, logger):
    # spawn 子进程的 stdout/stderr 默认用系统编码（Windows 为 GBK），
    # 在此处重新配置为 UTF-8，并重新绑定 loguru sink，避免中文乱码
    import sys
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    logger.remove()
    logger.add(sys.stderr, colorize=True)


if __name__ == '__main__':
    pass
