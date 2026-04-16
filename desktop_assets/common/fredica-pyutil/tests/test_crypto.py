# -*- coding: UTF-8 -*-
"""
crypto 路由单元测试（C1–C10）。

运行（参见 docs/dev/testing.md）：
    cd desktop_assets/common/fredica-pyutil
    ../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_crypto.py -v
"""
import asyncio
import base64

from fredica_pyutil_server.routes.crypto import (
    _HashPasswordBody,
    _VerifyPasswordBody,
    _DeriveImkBody,
    _GenerateKeypairBody,
    _ReencryptPrivateKeyBody,
    hash_password,
    verify_password,
    derive_imk,
    generate_keypair,
    reencrypt_private_key,
)


def _run(coro):
    """运行 async 函数的辅助。"""
    return asyncio.run(coro)


def _make_imk() -> str:
    """生成一个随机 32 字节 IMK 的 base64 编码。"""
    import os
    return base64.b64encode(os.urandom(32)).decode()


def _make_salt() -> str:
    """生成一个随机 16 字节 salt 的 base64 编码。"""
    import os
    return base64.b64encode(os.urandom(16)).decode()


def _make_salt_urlsafe_no_padding() -> str:
    """模拟 Kotlin Base64.getUrlEncoder().withoutPadding() 的输出：URL-safe base64，无 '=' padding。"""
    import os
    raw = os.urandom(32)  # Kotlin 用 32 字节
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


# ---------------------------------------------------------------------------
# C1: hash_password 返回 Argon2id 格式
# ---------------------------------------------------------------------------
class TestHashPassword:
    def test_c1_returns_argon2_format(self):
        result = _run(hash_password(_HashPasswordBody(password="test-password-123")))
        assert "hash" in result
        assert result["hash"].startswith("$argon2id$")

    def test_c10_empty_password(self):
        result = _run(hash_password(_HashPasswordBody(password="")))
        assert "hash" in result
        assert result["hash"].startswith("$argon2id$")


# ---------------------------------------------------------------------------
# C2–C3: verify_password
# ---------------------------------------------------------------------------
class TestVerifyPassword:
    def test_c2_correct_password(self):
        pw = "correct-horse-battery-staple"
        hash_result = _run(hash_password(_HashPasswordBody(password=pw)))
        h = hash_result["hash"]

        result = _run(verify_password(_VerifyPasswordBody(password=pw, hash=h)))
        assert result["valid"] is True

    def test_c3_wrong_password(self):
        pw = "correct-horse-battery-staple"
        hash_result = _run(hash_password(_HashPasswordBody(password=pw)))
        h = hash_result["hash"]

        result = _run(verify_password(_VerifyPasswordBody(password="wrong-password", hash=h)))
        assert result["valid"] is False

    def test_invalid_hash_format(self):
        result = _run(verify_password(_VerifyPasswordBody(password="test", hash="not-a-valid-hash")))
        assert result["valid"] is False


# ---------------------------------------------------------------------------
# C4–C5: derive_imk
# ---------------------------------------------------------------------------
class TestDeriveImk:
    def test_c4_deterministic(self):
        salt = _make_salt()
        pw = "my-secret-password"

        r1 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt)))
        r2 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt)))

        assert "imk_b64" in r1
        assert r1["imk_b64"] == r2["imk_b64"]
        # IMK 应为 32 字节 = 44 字符 base64
        raw = base64.b64decode(r1["imk_b64"])
        assert len(raw) == 32

    def test_c5_different_salt(self):
        pw = "my-secret-password"
        salt1 = _make_salt()
        salt2 = _make_salt()

        r1 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt1)))
        r2 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt2)))

        assert r1["imk_b64"] != r2["imk_b64"]

    def test_c4b_urlsafe_no_padding_salt(self):
        """复现 bug：Kotlin 发来的 salt 是 URL-safe base64 无 padding，应能正常派生 IMK。"""
        salt = _make_salt_urlsafe_no_padding()
        pw = "StrongPass123!"

        result = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt)))

        assert "error" not in result, f"derive_imk failed: {result.get('error')}"
        assert "imk_b64" in result
        raw = base64.b64decode(result["imk_b64"] + "==")
        assert len(raw) == 32

    def test_c4c_urlsafe_no_padding_deterministic(self):
        """URL-safe 无 padding salt 派生结果应确定性一致。"""
        salt = _make_salt_urlsafe_no_padding()
        pw = "StrongPass123!"

        r1 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt)))
        r2 = _run(derive_imk(_DeriveImkBody(password=pw, salt_imk_b64=salt)))

        assert "error" not in r1
        assert r1["imk_b64"] == r2["imk_b64"]


# ---------------------------------------------------------------------------
# C6–C7: generate_keypair
# ---------------------------------------------------------------------------
class TestGenerateKeypair:
    def test_c6_format(self):
        imk = _make_imk()
        result = _run(generate_keypair(_GenerateKeypairBody(imk_b64=imk)))

        assert "public_key_pem" in result
        assert "encrypted_private_key" in result
        assert result["public_key_pem"].startswith("-----BEGIN PUBLIC KEY-----")
        assert result["encrypted_private_key"].startswith("encrypted:")

    def test_c7_unique(self):
        imk = _make_imk()
        r1 = _run(generate_keypair(_GenerateKeypairBody(imk_b64=imk)))
        r2 = _run(generate_keypair(_GenerateKeypairBody(imk_b64=imk)))

        assert r1["public_key_pem"] != r2["public_key_pem"]
        assert r1["encrypted_private_key"] != r2["encrypted_private_key"]


# ---------------------------------------------------------------------------
# C8–C9: reencrypt_private_key
# ---------------------------------------------------------------------------
class TestReencryptPrivateKey:
    def test_c8_roundtrip(self):
        old_imk = _make_imk()
        new_imk = _make_imk()

        # 生成密钥对
        kp = _run(generate_keypair(_GenerateKeypairBody(imk_b64=old_imk)))
        encrypted_old = kp["encrypted_private_key"]

        # 用旧 IMK → 新 IMK 重加密
        result = _run(reencrypt_private_key(_ReencryptPrivateKeyBody(
            encrypted_private_key=encrypted_old,
            old_imk_b64=old_imk,
            new_imk_b64=new_imk,
        )))

        assert "encrypted_private_key" in result
        assert "error" not in result
        assert result["encrypted_private_key"].startswith("encrypted:")
        # 重加密后的密文应与原密文不同（不同 nonce）
        assert result["encrypted_private_key"] != encrypted_old

        # 验证新 IMK 可以解密：再次重加密回旧 IMK 应成功
        result2 = _run(reencrypt_private_key(_ReencryptPrivateKeyBody(
            encrypted_private_key=result["encrypted_private_key"],
            old_imk_b64=new_imk,
            new_imk_b64=old_imk,
        )))
        assert "encrypted_private_key" in result2
        assert "error" not in result2

    def test_c9_wrong_old_imk(self):
        real_imk = _make_imk()
        wrong_imk = _make_imk()
        new_imk = _make_imk()

        kp = _run(generate_keypair(_GenerateKeypairBody(imk_b64=real_imk)))

        result = _run(reencrypt_private_key(_ReencryptPrivateKeyBody(
            encrypted_private_key=kp["encrypted_private_key"],
            old_imk_b64=wrong_imk,
            new_imk_b64=new_imk,
        )))

        assert "error" in result
