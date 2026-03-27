# -*- coding: UTF-8 -*-
"""
Bilibili 视频路由辅助逻辑测试。

运行：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_bilibili_video_route.py -v
"""
from fredica_pyutil_server.routes.bilibili_video import _normalize_subtitle_body_url


class TestNormalizeSubtitleBodyUrl:
    def test_adds_https_for_scheme_relative_url(self):
        url = "//subtitle.bilibili.com/subtitle.json?auth_key=abc"

        result = _normalize_subtitle_body_url(url)

        assert result == "https://subtitle.bilibili.com/subtitle.json?auth_key=abc"

    def test_quotes_raw_ampersand_in_path_but_keeps_query(self):
        url = "//subtitle.bilibili.com/S%13abc&%02tail?auth_key=abc&foo=1"

        result = _normalize_subtitle_body_url(url)

        assert result.startswith("https://subtitle.bilibili.com/S%13abc%26%02tail?")
        assert "auth_key=abc&foo=1" in result

    def test_preserves_existing_percent_encoded_bytes(self):
        url = "//subtitle.bilibili.com/S%13%1BP.%1D%28%29X%2CR%5Ej?auth_key=abc"

        result = _normalize_subtitle_body_url(url)

        assert "%13%1BP.%1D%28%29X%2CR%5Ej" in result
        assert "%2513" not in result
        assert "%251B" not in result
