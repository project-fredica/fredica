# -*- coding: UTF-8 -*-
"""
torch 路由工具函数测试。

运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_torch_route_util.py -v
"""
from fredica_pyutil_server.routes.torch import _apply_version


class TestApplyVersion:
    def test_replaces_version_with_cuda_suffix(self):
        assert _apply_version("torch==2.7.0+cu128", "2.9.1") == "torch==2.9.1+cu128"

    def test_replaces_version_with_cpu_suffix(self):
        assert _apply_version("torch==2.7.0+cpu", "2.9.1") == "torch==2.9.1+cpu"

    def test_replaces_version_with_rocm_suffix(self):
        assert _apply_version("torch==2.7.0+rocm6.3", "2.9.1") == "torch==2.9.1+rocm6.3"

    def test_replaces_torchvision(self):
        assert _apply_version("torchvision==0.22.0+cu128", "0.24.0") == "torchvision==0.24.0+cu128"

    def test_replaces_torchaudio(self):
        assert _apply_version("torchaudio==2.7.0+cu128", "2.9.1") == "torchaudio==2.9.1+cu128"

    def test_no_version_spec_unchanged(self):
        # 没有 == 的包名不变
        assert _apply_version("torch", "2.9.1") == "torch"

    def test_no_suffix(self):
        # 没有 + 后缀的包（纯版本号）
        assert _apply_version("somelib==1.0.0", "2.0.0") == "somelib==2.0.0"

    def test_semantic_version_with_dots(self):
        # 新版本号含多段
        assert _apply_version("torch==2.7.0+cu128", "2.10.0") == "torch==2.10.0+cu128"

    def test_same_version_idempotent(self):
        assert _apply_version("torch==2.7.0+cu128", "2.7.0") == "torch==2.7.0+cu128"

    def test_full_package_list(self):
        """模拟 pip-command 路由对整个 packages 列表的处理。"""
        packages = [
            "torch==2.7.0+cu128",
            "torchvision==0.22.0+cu128",
            "torchaudio==2.7.0+cu128",
        ]
        result = [_apply_version(p, "2.9.1") for p in packages]
        assert result == [
            "torch==2.9.1+cu128",
            "torchvision==2.9.1+cu128",
            "torchaudio==2.9.1+cu128",
        ]
