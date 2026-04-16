# -*- coding: UTF-8 -*-
"""
密码学服务路由：Argon2id 密码哈希、Ed25519 密钥操作、AES-256-GCM 加密。

路由前缀（自动推导）：/crypto
"""
import base64
import os
from pathlib import Path

from argon2 import PasswordHasher, exceptions as argon2_exceptions
from argon2.low_level import hash_secret_raw, Type
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter
from loguru import logger
from pydantic import BaseModel

_router = APIRouter(prefix="/" + Path(__file__).stem)


# ── 请求模型 ──────────────────────────────────────────────────────────────────

class _HashPasswordBody(BaseModel):
    password: str


class _VerifyPasswordBody(BaseModel):
    password: str
    hash: str


class _DeriveImkBody(BaseModel):
    password: str
    salt_imk_b64: str


class _GenerateKeypairBody(BaseModel):
    imk_b64: str


class _ReencryptPrivateKeyBody(BaseModel):
    encrypted_private_key: str
    old_imk_b64: str
    new_imk_b64: str


# ── 内部工具 ──────────────────────────────────────────────────────────────────

_ph = PasswordHasher()  # Argon2id, time_cost=3, memory_cost=65536, parallelism=4


def _b64decode(s: str) -> bytes:
    """兼容标准和 URL-safe base64，自动补全缺失的 '=' padding。

    Kotlin 的 Base64.getUrlEncoder().withoutPadding() 产生 URL-safe 无 padding 编码，
    Python 的 base64.b64decode 不接受此格式，需要先转换。
    """
    # 替换 URL-safe 字符为标准字符
    s = s.replace("-", "+").replace("_", "/")
    # 补全 padding（base64 长度必须是 4 的倍数）
    remainder = len(s) % 4
    if remainder:
        s += "=" * (4 - remainder)
    return base64.b64decode(s)


def _encrypt_with_imk(imk: bytes, plaintext: bytes) -> str:
    """AES-256-GCM 加密，返回 'encrypted:' + base64(nonce ‖ ciphertext ‖ tag)。"""
    nonce = os.urandom(12)
    aesgcm = AESGCM(imk)
    ciphertext = aesgcm.encrypt(nonce, plaintext, None)
    return "encrypted:" + base64.b64encode(nonce + ciphertext).decode()


def _decrypt_with_imk(imk: bytes, encrypted: str) -> bytes:
    """解密 'encrypted:...' 格式的密文，返回明文 bytes。"""
    raw = base64.b64decode(encrypted.removeprefix("encrypted:"))
    nonce, ciphertext = raw[:12], raw[12:]
    aesgcm = AESGCM(imk)
    return aesgcm.decrypt(nonce, ciphertext, None)


# ── 路由 ──────────────────────────────────────────────────────────────────────

@_router.post("/hash-password/")
async def hash_password(body: _HashPasswordBody):
    """Argon2id 密码哈希。

    请求: { "password": "..." }
    响应: { "hash": "$argon2id$v=19$m=65536,t=3,p=4$..." }
    """
    try:
        logger.debug("[crypto] hash-password: len={}", len(body.password))
        h = _ph.hash(body.password)
        return {"hash": h}
    except Exception as e:
        logger.warning("[crypto] hash-password failed: {}", e)
        return {"error": str(e)}


@_router.post("/verify-password/")
async def verify_password(body: _VerifyPasswordBody):
    """验证密码是否匹配 Argon2id 哈希。

    请求: { "password": "...", "hash": "$argon2id$..." }
    响应: { "valid": true/false }
    """
    try:
        logger.debug("[crypto] verify-password")
        valid = _ph.verify(body.hash, body.password)
        return {"valid": valid}
    except argon2_exceptions.VerifyMismatchError:
        return {"valid": False}
    except Exception as e:
        logger.warning("[crypto] verify-password failed: {}", e)
        return {"valid": False}


@_router.post("/derive-imk/")
async def derive_imk(body: _DeriveImkBody):
    """从密码 + salt 派生 Instance Master Key (IMK)。

    使用 Argon2id (time_cost=3, memory_cost=65536, parallelism=4, hash_len=32)。

    请求: { "password": "...", "salt_imk_b64": "base64..." }
    响应: { "imk_b64": "base64..." }
    """
    try:
        logger.debug("[crypto] derive-imk: salt_len={}", len(body.salt_imk_b64))
        salt = _b64decode(body.salt_imk_b64)
        imk = hash_secret_raw(
            secret=body.password.encode(),
            salt=salt,
            time_cost=3,
            memory_cost=65536,
            parallelism=4,
            hash_len=32,
            type=Type.ID,
        )
        return {"imk_b64": base64.b64encode(imk).decode()}
    except Exception as e:
        logger.warning("[crypto] derive-imk failed: {}", e)
        return {"error": str(e)}


@_router.post("/generate-keypair/")
async def generate_keypair(body: _GenerateKeypairBody):
    """生成 Ed25519 密钥对，私钥用 AES-256-GCM(IMK) 加密。

    请求: { "imk_b64": "base64..." }
    响应: { "public_key_pem": "-----BEGIN PUBLIC KEY-----\\n...", "encrypted_private_key": "encrypted:base64..." }
    """
    try:
        logger.debug("[crypto] generate-keypair")
        imk = _b64decode(body.imk_b64)

        private_key = Ed25519PrivateKey.generate()

        public_pem = private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()

        private_pem = private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )

        encrypted = _encrypt_with_imk(imk, private_pem)

        del private_key, private_pem

        return {"public_key_pem": public_pem, "encrypted_private_key": encrypted}
    except Exception as e:
        logger.warning("[crypto] generate-keypair failed: {}", e)
        return {"error": str(e)}


@_router.post("/reencrypt-private-key/")
async def reencrypt_private_key(body: _ReencryptPrivateKeyBody):
    """用旧 IMK 解密私钥，再用新 IMK 重加密。

    请求: { "encrypted_private_key": "encrypted:...", "old_imk_b64": "...", "new_imk_b64": "..." }
    响应: { "encrypted_private_key": "encrypted:base64..." }
    失败: { "error": "Decryption failed" }
    """
    try:
        logger.debug("[crypto] reencrypt-private-key")
        old_imk = _b64decode(body.old_imk_b64)
        new_imk = _b64decode(body.new_imk_b64)

        plaintext = _decrypt_with_imk(old_imk, body.encrypted_private_key)
        new_encrypted = _encrypt_with_imk(new_imk, plaintext)

        del plaintext

        return {"encrypted_private_key": new_encrypted}
    except Exception as e:
        logger.warning("[crypto] reencrypt-private-key failed: {}", e)
        return {"error": "Decryption failed"}
