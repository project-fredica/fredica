# -*- coding: UTF-8 -*-
"""
torch_version_util 集成测试（真实网络请求）。

运行：
    cd desktop_assets/common/fredica-pyutil
    pytest tests/test_torch_version_util.py -v
"""
import pytest
from fredica_pyutil_server.util.torch_version_util import (
    _find_variants_in_dir_listing,
    _find_variants_in_sjtu_s3,
    _find_variants_in_stable_html,
    _fetch_html,
    fetch_mirror_supported_variants,
    check_mirror_availability,
    MIRROR_SOURCES,
    _VARIANT_ORDER,
)


# ---------------------------------------------------------------------------
# 解析函数单元测试（不需要网络）
# ---------------------------------------------------------------------------

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
        assert "cpu" in result
        assert "cu999" not in result  # 不在 _VARIANT_ORDER 里

    def test_nju_real_html_format(self):
        # 南京大学实际 HTML 格式：href="cu128/" title="cu128"
        html = '<tr><td colspan="2" class="link"><a href="cu128/" title="cu128">cu128/</a></td></tr>'
        result = _find_variants_in_dir_listing(html)
        assert "cu128" in result

    def test_empty_html(self):
        assert _find_variants_in_dir_listing("") == []

    def test_no_matching_variants(self):
        html = '<a href="somepackage/">somepackage/</a>'
        assert _find_variants_in_dir_listing(html) == []

    def test_order_preserved(self):
        html = '<a href="cpu/">cpu/</a><a href="cu128/">cu128/</a><a href="cu118/">cu118/</a>'
        result = _find_variants_in_dir_listing(html)
        # 结果顺序应与 _VARIANT_ORDER 一致
        indices = [_VARIANT_ORDER.index(v) for v in result]
        assert indices == sorted(indices)


class TestFindVariantsInSjtuS3:
    def test_basic(self):
        html = '''
        <td><a href="/pytorch-wheels/cu128/">cu128/</a></td>
        <td><a href="/pytorch-wheels/cu126/">cu126/</a></td>
        <td><a href="/pytorch-wheels/cpu/">cpu/</a></td>
        '''
        result = _find_variants_in_sjtu_s3(html)
        assert "cu128" in result
        assert "cu126" in result
        assert "cpu" in result

    def test_empty_html(self):
        assert _find_variants_in_sjtu_s3("") == []

    def test_no_false_positive(self):
        # "cu1280" 不应匹配 "cu128"
        html = "<td>cu1280/</td>"
        result = _find_variants_in_sjtu_s3(html)
        assert "cu128" not in result

    def test_order_preserved(self):
        html = "<td>>cpu/<</td><td>>cu128/<</td><td>>cu118/<</td>"
        # 用实际格式
        html = ">cpu/<\n>cu128/<\n>cu118/<"
        result = _find_variants_in_sjtu_s3(html)
        indices = [_VARIANT_ORDER.index(v) for v in result]
        assert indices == sorted(indices)


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

    def test_picks_latest_version(self):
        html = '''
        <a href="torch-2.6.0+cu128-cp310-cp310-linux_x86_64.whl">...</a>
        <a href="torch-2.7.0+cu128-cp311-cp311-linux_x86_64.whl">...</a>
        '''
        result = _find_variants_in_stable_html(html)
        assert result.get("cu128") == "2.7.0"

    def test_empty_html(self):
        assert _find_variants_in_stable_html("") == {}

    def test_cpu_variant(self):
        html = '<a href="torch-2.7.0+cpu-cp311-cp311-linux_x86_64.whl">...</a>'
        result = _find_variants_in_stable_html(html)
        assert result.get("cpu") == "2.7.0"


# ---------------------------------------------------------------------------
# 真实网络请求测试
# ---------------------------------------------------------------------------

@pytest.mark.network
class TestFetchMirrorSupportedVariantsReal:
    """真实网络请求，需要能访问对应镜像站。"""

    def test_nju_returns_variants(self):
        result = fetch_mirror_supported_variants("nju")
        print(f"\n[nju] variants={result['variants']} error={result['error']!r}")
        assert result["error"] == "" or len(result["variants"]) == 0
        if result["error"] == "":
            assert isinstance(result["variants"], list)
            # nju 应该至少有 cu118、cu121、cu124 等常见版本
            assert len(result["variants"]) > 0, "nju 应返回至少一个 variant"
            for v in result["variants"]:
                assert v in _VARIANT_ORDER, f"返回了未知 variant: {v}"

    def test_sjtu_returns_variants(self):
        result = fetch_mirror_supported_variants("sjtu")
        print(f"\n[sjtu] variants={result['variants']} error={result['error']!r}")
        assert result["error"] == "" or len(result["variants"]) == 0
        if result["error"] == "":
            assert isinstance(result["variants"], list)
            for v in result["variants"]:
                assert v in _VARIANT_ORDER, f"返回了未知 variant: {v}"

    def test_official_returns_all_variants(self):
        result = fetch_mirror_supported_variants("official")
        assert result["error"] == ""
        assert result["variants"] == list(_VARIANT_ORDER)

    def test_tuna_returns_cpu_only(self):
        result = fetch_mirror_supported_variants("tuna")
        assert result["error"] == ""
        assert result["variants"] == ["cpu"]

    def test_unknown_mirror_key(self):
        result = fetch_mirror_supported_variants("nonexistent_mirror")
        assert result["error"] != ""
        assert result["variants"] == []


    def test_aliyun_returns_variants(self):
        result = fetch_mirror_supported_variants("aliyun")
        print(f"\n[aliyun] variants={result['variants']} error={result['error']!r}")
        assert result["error"] == ""
        assert len(result["variants"]) > 0, "aliyun 应返回至少一个 variant"
        for v in result["variants"]:
            assert v in _VARIANT_ORDER, f"返回了未知 variant: {v}"


@pytest.mark.network
class TestCheckMirrorAvailabilityReal:
    """真实网络请求，验证 check_mirror_availability 对各镜像的探测结果。"""

    def test_nju_cu118(self):
        # nju 目录列表里有 cu118/
        result = check_mirror_availability("cu118", "nju")
        print(f"\n[nju/cu118] available={result['available']} url={result['url']} error={result['error']!r}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_nju_cpu(self):
        result = check_mirror_availability("cpu", "nju")
        print(f"\n[nju/cpu] available={result['available']} url={result['url']}")
        assert result["error"] == ""
        assert result["available"] is True

    def test_sjtu_cu118(self):
        result = check_mirror_availability("cu118", "sjtu")
        print(f"\n[sjtu/cu118] available={result['available']} url={result['url']} error={result['error']!r}")
        # sjtu 的 S3 列表可能分页，只验证不报错
        assert isinstance(result["available"], bool)

    def test_official_cu128(self):
        # official 走 simple_api，直接请求 pytorch.org
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
