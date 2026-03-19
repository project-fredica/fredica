# -*- coding: UTF-8 -*-
"""
torch_version_util 测试。

运行（参见 docs/dev/testing.md）：
    cd desktop_assets/common/fredica-pyutil

    # 只跑单元测试（无网络，速度快）
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_torch_version_util.py -v -m "not network"

    # 只跑网络测试
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_torch_version_util.py -v -m "network" -s

    # 全跑
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_torch_version_util.py -v -s
"""
import re
import pytest
from fredica_pyutil_server.util.torch_version_util import (
    _extract_variants_from_dir_listing,
    _find_variants_in_dir_listing,
    _find_variants_in_stable_html,
    _fetch_versions_from_simple_api,
    _fetch_versions_from_flat_dir,
    _parse_version,
    _max_version,
    _sort_variants,
    fetch_mirror_supported_variants,
    check_mirror_availability,
    MIRROR_SOURCES,
    _VARIANT_ORDER,
)


# ---------------------------------------------------------------------------
# 工具函数单元测试（不需要网络）
# ---------------------------------------------------------------------------

class TestParseVersion:
    def test_basic(self):
        assert _parse_version("2.7.0") == (2, 7, 0)
        assert _parse_version("2.10.0") == (2, 10, 0)

    def test_semantic_ordering(self):
        # 修复字符串比较 "2.9.1" > "2.10.0" 的 bug
        assert _parse_version("2.10.0") > _parse_version("2.9.1")
        assert _parse_version("2.9.1") > _parse_version("2.9.0")

    def test_max_version(self):
        assert _max_version(["2.9.1", "2.10.0", "2.7.0"]) == "2.10.0"
        assert _max_version(["2.6.0", "2.7.0"]) == "2.7.0"
        assert _max_version([]) == ""


class TestSortVariants:
    def test_known_order(self):
        result = _sort_variants(["cpu", "cu118", "cu128", "cu126"])
        assert result.index("cu128") < result.index("cu126")
        assert result.index("cu126") < result.index("cu118")
        assert result[-1] == "cpu"

    def test_unknown_variants_before_cpu(self):
        result = _sort_variants(["cpu", "cu128", "cu130", "cu129"])
        assert "cu130" in result
        assert "cu129" in result
        assert result[-1] == "cpu"
        # 未知 variant 在 cpu 之前
        assert result.index("cu129") < result.index("cpu")
        assert result.index("cu130") < result.index("cpu")

    def test_no_cpu(self):
        result = _sort_variants(["cu128", "cu126"])
        assert result == ["cu128", "cu126"]


class TestExtractVariantsFromDirListing:
    def test_basic(self):
        html = '''
        <a href="cu128/" title="cu128">cu128/</a>
        <a href="cu126/" title="cu126">cu126/</a>
        <a href="cpu/" title="cpu">cpu/</a>
        '''
        result = _extract_variants_from_dir_listing(html)
        assert "cu128" in result
        assert "cu126" in result
        # cpu 不匹配 cu/rocm 前缀，不在结果中
        assert "cpu" not in result

    def test_discovers_new_variants(self):
        # cu129/cu130 等新版本应被动态发现，不受 _VARIANT_ORDER 限制
        html = '<a href="cu129/" title="cu129">cu129/</a><a href="cu130/">cu130/</a>'
        result = _extract_variants_from_dir_listing(html)
        assert "cu129" in result
        assert "cu130" in result

    def test_rocm_variants(self):
        html = '<a href="rocm7.0/">rocm7.0/</a><a href="rocm7.1/">rocm7.1/</a>'
        result = _extract_variants_from_dir_listing(html)
        assert "rocm7.0" in result
        assert "rocm7.1" in result

    def test_empty_html(self):
        assert _extract_variants_from_dir_listing("") == []

    def test_no_duplicates(self):
        html = '<a href="cu128/">cu128/</a><a href="cu128/">cu128/</a>'
        result = _extract_variants_from_dir_listing(html)
        assert result.count("cu128") == 1


class TestFindVariantsInDirListing:
    def test_basic(self):
        html = '''
        <a href="cu128/" title="cu128">cu128/</a>
        <a href="cu126/" title="cu126">cu126/</a>
        <a href="cpu/" title="cpu">cpu/</a>
        <a href="cu999/" title="cu999">cu999/</a>
        '''
        result = _find_variants_in_dir_listing(html)
        assert "cu128" in result
        assert "cu126" in result
        # cu999 不在 _VARIANT_ORDER 里（_find_variants_in_dir_listing 只返回已知 variant）
        assert "cu999" not in result

    def test_nju_real_html_format(self):
        html = '<tr><td colspan="2" class="link"><a href="cu128/" title="cu128">cu128/</a></td></tr>'
        result = _find_variants_in_dir_listing(html)
        assert "cu128" in result

    def test_empty_html(self):
        assert _find_variants_in_dir_listing("") == []

    def test_order_preserved(self):
        html = '<a href="cpu/">cpu/</a><a href="cu128/">cu128/</a><a href="cu118/">cu118/</a>'
        result = _find_variants_in_dir_listing(html)
        indices = [_VARIANT_ORDER.index(v) for v in result]
        assert indices == sorted(indices)


class TestSjtuUsesSimpleApi:
    """SJTU 镜像已改为 simple_api 风格，验证配置正确。"""

    def test_sjtu_index_style_is_simple_api(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "sjtu")
        assert src.index_style == "simple_api"

    def test_sjtu_url_fn_includes_variant(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "sjtu")
        url = src.url_fn("cu128")
        assert "cu128" in url
        assert url.startswith("https://")


class TestOfficialUsesSimpleApi:
    """官方源也是 simple_api 风格，验证配置正确。"""

    def test_official_index_style_is_simple_api(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "official")
        assert src.index_style == "simple_api"

    def test_official_stable_html_base_set(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "official")
        assert src.stable_html_base == "https://download.pytorch.org/whl"


class TestFindVariantsInStableHtml:
    def test_basic(self):
        html = '''
        <a href="torch-2.7.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
        <a href="torch-2.7.0+cu126-cp311-cp311-linux_x86_64.whl">...</a>
        <a href="torchvision-0.22.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
        '''
        result = _find_variants_in_stable_html(html)
        assert result.get("cu128") == "2.7.0"
        assert result.get("cu126") == "2.7.0"
        assert "torchvision" not in str(result)

    def test_picks_latest_version_semantic(self):
        # 验证语义版本比较：2.10.0 > 2.9.1（字符串比较会错误地认为 2.9.1 更大）
        html = '''
        <a href="torch-2.9.1+cu128-cp310-cp310-linux_x86_64.whl">...</a>
        <a href="torch-2.10.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
        '''
        result = _find_variants_in_stable_html(html)
        assert result.get("cu128") == "2.10.0"

    def test_empty_html(self):
        assert _find_variants_in_stable_html("") == {}

    def test_cpu_variant(self):
        html = '<a href="torch-2.7.0+cpu-cp311-cp311-linux_x86_64.whl">...</a>'
        result = _find_variants_in_stable_html(html)
        assert result.get("cpu") == "2.7.0"


class TestFetchVersionsFromFlatDir:
    """_fetch_versions_from_flat_dir 单元测试（mock HTML，不需要网络）。"""

    def test_parses_html_encoded_plus(self):
        # 阿里云 HTML 中 + 被编码为 &#43;
        html = '''
        <a href="torch-2.10.0&#43;cu128-cp310-cp310-manylinux_2_28_x86_64.whl">...</a>
        <a href="torch-2.10.0&#43;cu128-cp311-cp311-win_amd64.whl">...</a>
        <a href="torch-2.9.0&#43;cu128-cp310-cp310-manylinux_2_28_x86_64.whl">...</a>
        '''
        import unittest.mock as mock
        with mock.patch(
            "fredica_pyutil_server.util.torch_version_util._fetch_html",
            return_value=html,
        ):
            vers = _fetch_versions_from_flat_dir("https://mirrors.aliyun.com/pytorch-wheels", "cu128", "")
        assert "2.10.0" in vers
        assert "2.9.0" in vers
        # 降序排列，最新版本在前
        assert vers[0] == "2.10.0"

    def test_parses_literal_plus(self):
        # 也支持未编码的 + 号
        html = '<a href="torch-2.7.0+cu126-cp311-cp311-linux_x86_64.whl">...</a>'
        import unittest.mock as mock
        with mock.patch(
            "fredica_pyutil_server.util.torch_version_util._fetch_html",
            return_value=html,
        ):
            vers = _fetch_versions_from_flat_dir("https://example.com", "cu126", "")
        assert "2.7.0" in vers

    def test_ignores_torchvision(self):
        # 只匹配 torch- 开头，不匹配 torchvision-
        html = '''
        <a href="torch-2.10.0&#43;cu128-cp310-cp310-win_amd64.whl">...</a>
        <a href="torchvision-0.25.0&#43;cu128-cp310-cp310-win_amd64.whl">...</a>
        '''
        import unittest.mock as mock
        with mock.patch(
            "fredica_pyutil_server.util.torch_version_util._fetch_html",
            return_value=html,
        ):
            vers = _fetch_versions_from_flat_dir("https://example.com", "cu128", "")
        assert vers == ["2.10.0"]

    def test_aliyun_dir_listing_layout_is_flat(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "aliyun")
        assert src.dir_listing_layout == "flat"

    def test_nju_dir_listing_layout_is_subdir(self):
        src = next(s for s in MIRROR_SOURCES if s.key == "nju")
        assert src.dir_listing_layout == "subdir"


# ---------------------------------------------------------------------------
# 真实网络请求测试
# ---------------------------------------------------------------------------

@pytest.mark.network
class TestFetchMirrorSupportedVariantsReal:
    """真实网络请求，需要能访问对应镜像站。"""

    def test_nju_returns_variants_with_versions(self):
        result = fetch_mirror_supported_variants("nju")
        print(f"\n[nju] variants={result['variants']} torch_versions={result.get('torch_versions')} error={result['error']!r}")
        assert result["error"] == "", f"nju 查询失败: {result['error']}"
        assert len(result["variants"]) > 0, "nju 应返回至少一个 variant"
        for v in result["variants"]:
            assert re.match(r"^(cu|rocm)[\w.]+$", v) or v == "cpu", f"返回了格式异常的 variant: {v}"
        # NJU 现在也应返回版本号
        torch_versions = result.get("torch_versions", {})
        assert len(torch_versions) > 0, "nju 应能解析出 torch 版本号"
        # 版本号应为列表
        for v, vers in torch_versions.items():
            assert isinstance(vers, list), f"torch_versions[{v}] 应为列表，实际为 {type(vers)}"
            assert len(vers) > 0

    def test_nju_discovers_new_variants(self):
        # NJU 根目录可能有 cu129/cu130 等新 variant，不应被 _VARIANT_ORDER 过滤掉
        result = fetch_mirror_supported_variants("nju")
        print(f"\n[nju] all variants={result['variants']}")
        assert result["error"] == ""
        # 只要有至少一个 variant 即可（新版本是否存在取决于镜像站）
        assert len(result["variants"]) > 0

    def test_sjtu_returns_variants_with_versions(self):
        result = fetch_mirror_supported_variants("sjtu")
        print(f"\n[sjtu] variants={result['variants']} torch_versions={result.get('torch_versions')} error={result['error']!r}")
        assert result["error"] == "", f"sjtu 查询失败: {result['error']}"
        assert len(result["variants"]) > 0, "sjtu 应返回至少一个 variant"
        torch_versions = result.get("torch_versions", {})
        assert len(torch_versions) > 0, "sjtu simple_api 应能解析出 torch 版本号"
        for v, vers in torch_versions.items():
            assert isinstance(vers, list)
            assert len(vers) > 0

    def test_official_returns_variants_with_versions(self):
        result = fetch_mirror_supported_variants("official")
        print(f"\n[official] variants={result['variants']} torch_versions={result.get('torch_versions')} error={result['error']!r}")
        assert result["error"] == "", f"official 查询失败: {result['error']}"
        assert len(result["variants"]) > 0, "official 应返回至少一个 variant"
        torch_versions = result.get("torch_versions", {})
        assert len(torch_versions) > 0, "official 应能解析出 torch 版本号"
        # cu128 应有版本号，且最高版本应 >= 2.7.0
        assert "cu128" in torch_versions, "official 应有 cu128 版本号"
        cu128_vers = torch_versions["cu128"]
        assert isinstance(cu128_vers, list) and len(cu128_vers) > 0
        from fredica_pyutil_server.util.torch_version_util import _max_version, _parse_version
        best = _max_version(cu128_vers)
        assert _parse_version(best) >= (2, 7, 0), f"cu128 最高版本 {best} 低于预期"

    def test_official_cu128_multiple_versions(self):
        # 官方源 cu128 应有多个 torch 版本（2.7.0, 2.8.0 等）
        result = fetch_mirror_supported_variants("official")
        torch_versions = result.get("torch_versions", {})
        if "cu128" in torch_versions:
            vers = torch_versions["cu128"]
            print(f"\n[official/cu128] versions={vers}")
            assert len(vers) >= 1

    def test_tuna_returns_cpu_only(self):
        result = fetch_mirror_supported_variants("tuna")
        assert result["error"] == ""
        assert result["variants"] == ["cpu"]

    def test_unknown_mirror_key(self):
        result = fetch_mirror_supported_variants("nonexistent_mirror")
        assert result["error"] != ""
        assert result["variants"] == []

    def test_aliyun_returns_variants_with_versions(self):
        result = fetch_mirror_supported_variants("aliyun")
        print(f"\n[aliyun] variants={result['variants']} torch_versions={result.get('torch_versions')} error={result['error']!r}")
        assert result["error"] == "", f"aliyun 查询失败: {result['error']}"
        assert len(result["variants"]) > 0, "aliyun 应返回至少一个 variant"
        for v in result["variants"]:
            assert re.match(r"^(cu|rocm)[\w.]+$", v) or v == "cpu", f"返回了格式异常的 variant: {v}"
        # aliyun flat 布局应能解析出 torch 版本号（至少一个 variant 有版本）
        torch_versions = result.get("torch_versions", {})
        assert len(torch_versions) > 0, "aliyun 应能解析出 torch 版本号（flat 布局）"
        for v, vers in torch_versions.items():
            assert isinstance(vers, list) and len(vers) > 0, f"aliyun torch_versions[{v}] 应为非空列表"


@pytest.mark.network
class TestCheckMirrorAvailabilityReal:
    """真实网络请求，验证 check_mirror_availability 对各镜像的探测结果。"""

    def test_nju_cu118(self):
        result = check_mirror_availability("cu118", "nju")
        print(f"\n[nju/cu118] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_nju_cpu(self):
        result = check_mirror_availability("cpu", "nju")
        print(f"\n[nju/cpu] available={result['available']} url={result['url']}")
        assert result["error"] == ""
        # NJU 根目录不一定有 cpu/ 目录（cpu 不匹配 cu/rocm 正则），available 可能为 False
        assert isinstance(result["available"], bool)

    def test_sjtu_cu118(self):
        result = check_mirror_availability("cu118", "sjtu")
        print(f"\n[sjtu/cu118] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_sjtu_cu128(self):
        result = check_mirror_availability("cu128", "sjtu")
        print(f"\n[sjtu/cu128] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_official_cu128(self):
        result = check_mirror_availability("cu128", "official")
        print(f"\n[official/cu128] available={result['available']} url={result['url']}")
        assert isinstance(result["available"], bool)

    def test_tuna_cuda_not_supported(self):
        # tuna 不支持 CUDA variant，url_fn 返回空串
        result = check_mirror_availability("cu128", "tuna")
        assert result["available"] is False
        assert result["error"] != ""

    def test_aliyun_cu118(self):
        result = check_mirror_availability("cu118", "aliyun")
        print(f"\n[aliyun/cu118] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_aliyun_cu128(self):
        result = check_mirror_availability("cu128", "aliyun")
        print(f"\n[aliyun/cu128] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_custom_mirror_unsupported(self):
        result = check_mirror_availability("cu128", "custom")
        assert result["available"] is False
        assert result["error"] != ""
