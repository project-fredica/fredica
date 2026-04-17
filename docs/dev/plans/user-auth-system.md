---
title: 用户体系 & 联邦鉴权系统设计方案
order: 540
---

# 用户体系 & 联邦鉴权系统设计方案

> 本文档设计 Fredica 的多角色用户体系、本地认证、以及面向未来联邦的跨实例身份认证架构。
> 认证模型参考 Mastodon/ActivityPub 的 **服务端托管密钥 + HTTP Signatures** 方案。

---

## 一、现状分析

### 1.1 当前鉴权机制

```
前端 → Authorization: Bearer <webserver_auth_token> → Ktor checkAuth()
```

- `checkAuth()`（`FredicaApi.jvm.kt:430`）仅检查 `Authorization` 头是否存在且以 `Bearer ` 开头，**不校验 token 值**
- `webserver_auth_token` 在 WebView 环境由 `GetServerInfoJsMessageHandler` 硬编码返回 `"114514"`
- 前端 `appConfig.tsx` 将 token 存入 `localStorage` + `fredica_token` Cookie
- `MaterialVideoStreamRoute` 通过 Cookie `fredica_token` 认证，同样只检查存在性
- `ImageProxyRoute` 设置 `requiresAuth = false`，无需鉴权
- jsBridge 通道无需 token，进程内信任

### 1.2 问题

1. **无身份区分**：所有持有 token 的用户权限相同
2. **token 无签名**：任何字符串都能通过校验
3. **无用户管理**：没有用户注册/签发/吊销机制
4. **无权限分级**：无法区分只读游客与可读写租户
5. **无联邦能力**：无法与其他 Fredica 实例互信

---

## 二、设计目标

### 2.1 分层架构

```
┌─────────────────────────────────────────────────┐
│  Layer 3: 联邦（跨实例互信）                       │
│  HTTP Signatures + Actor 公钥发现                  │
│  参考 Mastodon/ActivityPub 模型                    │
├─────────────────────────────────────────────────┤
│  Layer 2: 本地认证（用户登录自己的实例）             │
│  用户名/密码 → 服务端验证 → Session Token           │
│  可选：WebAuthn (Passkey) 作为无密码替代            │
├─────────────────────────────────────────────────┤
│  Layer 1: 游客访问（轻量只读）                      │
│  webserver_auth_token（随机 UUID，分享链接用）       │
└─────────────────────────────────────────────────┘
```

### 2.2 核心原则

1. **服务端托管密钥**（Custodial Trust Model）：与 Mastodon 一致，用户的非对称密钥对由服务端生成和保管，用户不需要管理私钥
2. **密码登录为主**：本地认证使用用户名/密码，简单可靠
3. **联邦预留**：数据模型和密钥体系从一开始就为跨实例互信做好准备，但联邦协议本身可以后续实现
4. **渐进式实现**：先做本地认证（Phase 1-3），再做联邦（Phase 4+）

---

## 三、用户角色定义

| 角色 | 标识方式 | 权限范围 | 典型场景 |
|------|---------|---------|---------|
| **游客** | 仅持有 `webserver_auth_token` | 只读轻量级业务（浏览素材、查看字幕） | 分享链接给朋友看视频 |
| **租户** | 用户名/密码登录，持有 session token | 绝大多数可读写业务 | 日常使用者、协作者 |
| **根用户** | 首次启动时创建的管理员账户 | 全部业务 + 用户管理 | 服主（部署者） |
| **运维** | 仅通过 jsBridge | jsBridge 范畴内的运维操作 | 服主通过桌面 APP 管理 |

### 3.1 角色层级关系

```
运维（jsBridge 进程内信任，不走 HTTP）
  │
根用户（实例管理员）
  ├── 租户 A（根用户创建）
  ├── 租户 B（根用户创建）
  └── ...
  
游客（仅持 webserver_auth_token，无账户）
```

---

## 四、密钥体系设计（联邦预留）

### 4.1 为什么需要非对称密钥？

> **不是为了本地登录**（密码就够了），而是为了**联邦场景下的跨实例身份证明**。
>
> 参考 Mastodon：每个 Actor（用户）有一对 RSA/Ed25519 密钥。
> 当实例 A 的用户向实例 B 发送请求时，用私钥签名 HTTP 请求头，
> 实例 B 通过获取 Actor 的公钥来验证签名，从而确认"这个请求确实来自实例 A 的某用户"。
>
> **服务端持有私钥**是 Mastodon 的标准做法（custodial trust model），
> 用户不需要接触私钥，这是合理的——因为你已经信任了你注册的实例。

### 4.2 密钥存储设计

#### 4.2.1 Fredica 的特殊威胁面

Mastodon 通常部署在 VPS/云服务器上，有专业运维、全盘加密、SSH 密钥登录。Fredica 的部署环境截然不同：

| 威胁 | VPS/云服务器 | 家用电脑（Fredica 典型场景） |
|------|-------------|--------------------------|
| 物理接触 | 机房管控，极难 | 家庭成员、访客可能接触 |
| 多用户共用 | 通常单用途 | 可能多人共用同一台电脑 |
| 全盘加密 | 通常启用 | 很多用户未启用 BitLocker/FileVault |
| 文件权限 | Linux 严格的 uid/gid | Windows 家庭版权限模型较弱 |
| 备份泄露 | 运维控制备份链路 | 用户可能把整个文件夹拷到 U 盘/网盘 |
| 恶意软件 | 最小化安装，攻击面小 | 日常使用电脑，攻击面大 |

**核心矛盾**：Mastodon 的"私钥明文存数据库"方案在专业服务器上可接受，但在家用电脑上风险显著升高。

#### 4.2.2 私钥泄露的实际影响

如果私钥泄露，攻击者可以：

1. **伪造联邦签名**：以该用户身份向任何远程实例发送签名请求
2. **冒充实例**（如果泄露的是实例级私钥）：以整个实例的身份与其他实例通信
3. **不可撤销**：远程实例缓存了公钥，即使本地轮换密钥，缓存窗口内仍可伪造

但也要注意**私钥泄露不能做什么**：
- 不能登录本地账户（登录用密码，不用私钥）
- 不能读取本地数据（私钥只用于联邦签名）
- 不能解密任何数据（Ed25519 是签名算法，不是加密算法）

所以私钥泄露的影响范围**仅限于联邦场景**。对于 Phase 1-3（纯本地认证），私钥只是预留字段，泄露无实际影响。

#### 4.2.3 分层防护策略

根据威胁面和影响分析，采用**三层递进防护**：

```
Layer 0: 基线（Phase 1 实现）
  │  私钥存 SQLite 数据库，与 Mastodon 存 PostgreSQL 等价
  │  文件系统权限限制（仅当前用户可读）
  │
Layer 1: 应用级加密（Phase 1 实现，默认启用）
  │  用实例主密钥（Instance Master Key）加密私钥后存储
  │  主密钥派生自根用户密码 + 随机盐
  │
Layer 2: 操作系统密钥库（Phase 3+ 可选）
     主密钥存入 OS 密钥库（Windows Credential Manager / macOS Keychain）
     而非文件系统
```

#### 4.2.4 Layer 0：基线存储

**存储位置**：统一存入 SQLite 数据库（`user` 表的 `private_key_pem` 字段），不再使用独立的 `.data/keys/` 文件目录。

理由：
- 减少需要保护的攻击面（一个 DB 文件 vs 多个 PEM 文件散落在文件系统）
- SQLite 文件可以整体设置文件权限，比目录树更容易管控
- 与 Mastodon 存 PostgreSQL 的做法一致
- 备份/迁移时只需处理一个数据库文件

**文件权限**（应用启动时设置）：

```kotlin
// Windows: 通过 icacls 限制为当前用户
Runtime.getRuntime().exec("icacls ${dbPath} /inheritance:r /grant:r ${username}:F")

// Linux/macOS: chmod 600
File(dbPath).setReadable(false, false)  // 移除所有人读权限
File(dbPath).setReadable(true, true)    // 仅 owner 可读
File(dbPath).setWritable(false, false)
File(dbPath).setWritable(true, true)
```

#### 4.2.5 Layer 1：应用级加密（Instance Master Key）

即使数据库文件被复制走（U 盘、网盘备份、恶意软件），私钥也不能直接读取。

**密钥派生链：**

```
根用户密码
  │
  ├── Argon2id(password, salt_auth) → password_hash    （用于登录验证，存 user 表）
  │
  └── Argon2id(password, salt_imk)  → Instance Master Key（用于加密私钥，不存储）
                                        │
                                        ├── AES-256-GCM(IMK, user_private_key) → encrypted_private_key
                                        │     存入 user.private_key_pem（密文 + nonce + tag）
                                        │
                                        └── AES-256-GCM(IMK, instance_private_key) → encrypted_instance_key
                                              存入 AppConfig
```

**关键设计点：**

- **两个不同的盐**：`salt_auth`（用于登录验证）和 `salt_imk`（用于派生主密钥），确保 `password_hash` 和 IMK 之间无法互推
- **IMK 不落盘**：Instance Master Key 仅在内存中存在，进程退出即消失
- **IMK 缓存**：应用启动时根用户登录后派生 IMK，缓存在内存中，后续签名操作直接使用
- **盐存储**：`salt_auth` 和 `salt_imk` 存入 AppConfig 表（盐不是秘密，可以明文存储）

**加密存储格式：**

```
private_key_pem 字段内容（加密后）：
  "encrypted:" + base64(nonce ‖ ciphertext ‖ tag)

private_key_pem 字段内容（未加密，兼容模式）：
  "-----BEGIN PRIVATE KEY-----\n..."
```

前缀 `encrypted:` 用于区分加密和明文格式，支持从未加密状态平滑迁移。

**根用户密码变更时的重加密：**

```
旧密码 → 派生旧 IMK → 解密所有私钥
新密码 → 派生新 IMK → 重新加密所有私钥
更新 salt_imk
```

#### 4.2.6 Layer 2：操作系统密钥库（可选增强）

Layer 1 的弱点：IMK 派生自根用户密码，如果密码强度不够或被社工获取，加密形同虚设。

操作系统密钥库提供了硬件/OS 级别的保护：

| 平台 | 密钥库 | 保护机制 |
|------|--------|---------|
| Windows | Credential Manager (DPAPI) | 绑定 Windows 用户登录会话，其他用户无法读取 |
| macOS | Keychain | 绑定用户登录 + 可选 Touch ID |
| Linux | libsecret (GNOME Keyring) | 绑定用户会话 |

**实现方式**：将 IMK 本身存入 OS 密钥库，而非从密码派生。

```
首次初始化：
  1. 生成随机 IMK（32 字节）
  2. 存入 OS 密钥库（key = "fredica_instance_master_key"）
  3. 用 IMK 加密所有私钥

后续启动：
  1. 从 OS 密钥库读取 IMK（需要用户已登录 OS）
  2. 解密私钥到内存
```

这样即使数据库文件被复制到另一台电脑，没有 OS 密钥库中的 IMK 就无法解密。

**Phase 3+ 实现**，因为需要平台相关的 JNI/JNA 调用。Phase 1 先用 Layer 1（密码派生 IMK）。

### 4.3 密钥生成与操作（Python cryptography 库）

#### 4.3.1 为什么密钥操作放在 Python 侧？

项目已有 pyutil 子进程框架，Python `cryptography` 库是成熟的密码学实现（基于 OpenSSL），复用现有基础设施比在 Kotlin/JVM 侧引入 Bouncy Castle 更简单。

> **关于内存安全**：如果攻击者已经能读取进程内存（heap dump、调试器 attach），说明操作系统层面已经沦陷，应用层的内存擦除无实际收益。Fredica 的实际威胁面是**磁盘泄露**（数据库文件被拷走），Layer 1 的 AES-256-GCM 加密已经覆盖了这个场景。不做过度设计。

#### 4.3.2 密钥操作 API（pyutil 路由）

```python
# fredica-pyutil: routes/crypto.py

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt  # 或 argon2
import os, base64

@_router.post("/crypto/generate-keypair/")
async def generate_keypair(req: Request):
    """生成 Ed25519 密钥对，返回公钥明文 + 私钥密文。
    
    请求: { "imk_b64": "base64编码的IMK" }
    响应: { "public_key_pem": "...", "encrypted_private_key": "encrypted:base64..." }
    """
    body = await req.json()
    imk = base64.b64decode(body["imk_b64"])
    
    # 生成密钥对（私钥在 C 层，Python 层不可见）
    private_key = Ed25519PrivateKey.generate()
    
    # 公钥：明文 PEM（公开信息）
    public_pem = private_key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()
    
    # 私钥：序列化后立即加密，明文 PEM 仅在局部变量中短暂存在
    private_pem = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()
    )
    
    # AES-256-GCM 加密
    nonce = os.urandom(12)
    aesgcm = AESGCM(imk)
    ciphertext = aesgcm.encrypt(nonce, private_pem, None)
    encrypted = "encrypted:" + base64.b64encode(nonce + ciphertext).decode()
    
    # 擦除局部变量（尽力而为）
    del private_key, private_pem
    
    return {"public_key_pem": public_pem, "encrypted_private_key": encrypted}


@_router.post("/crypto/sign/")
async def sign_message(req: Request):
    """用加密存储的私钥签名数据。
    
    请求: { "encrypted_private_key": "encrypted:...", "imk_b64": "...", "data_b64": "..." }
    响应: { "signature_b64": "base64编码的签名" }
    """
    body = await req.json()
    imk = base64.b64decode(body["imk_b64"])
    data = base64.b64decode(body["data_b64"])
    
    # 解密私钥
    encrypted = body["encrypted_private_key"]
    raw = base64.b64decode(encrypted.removeprefix("encrypted:"))
    nonce, ciphertext = raw[:12], raw[12:]
    aesgcm = AESGCM(imk)
    private_pem = aesgcm.decrypt(nonce, ciphertext, None)
    
    # 加载私钥到 C 层 → 签名 → 释放
    private_key = serialization.load_pem_private_key(private_pem, password=None)
    signature = private_key.sign(data)  # Ed25519 签名在 OpenSSL C 层完成
    
    del private_key, private_pem
    
    return {"signature_b64": base64.b64encode(signature).decode()}


@_router.post("/crypto/derive-imk/")
async def derive_imk(req: Request):
    """从根用户密码派生 Instance Master Key。
    
    请求: { "password": "...", "salt_imk_b64": "..." }
    响应: { "imk_b64": "base64编码的32字节IMK" }
    """
    body = await req.json()
    salt = base64.b64decode(body["salt_imk_b64"])
    
    # Argon2id 派生（复用 argon2-cffi）
    from argon2.low_level import hash_secret_raw, Type
    imk = hash_secret_raw(
        secret=body["password"].encode(),
        salt=salt,
        time_cost=3, memory_cost=65536, parallelism=4,
        hash_len=32, type=Type.ID
    )
    
    return {"imk_b64": base64.b64encode(imk).decode()}
```

#### 4.3.3 Kotlin 侧调用模式

Kotlin 持有加密后的私钥密文和 IMK（base64），需要签名时调用 pyutil：

```kotlin
// 联邦签名：Kotlin 构建待签名数据，Python 完成解密+签名
suspend fun signFederationRequest(
    encryptedPrivateKey: String,  // 从 user 表读取的密文
    imkB64: String,               // 内存中缓存的 IMK（base64）
    signatureInput: ByteArray     // HTTP Signature 待签名字符串
): String {
    val result = Py314Embed.PyUtilServer.requestText(
        method = "POST",
        path = "/crypto/sign/",
        body = buildJsonObject {
            put("encrypted_private_key", encryptedPrivateKey)
            put("imk_b64", imkB64)
            put("data_b64", Base64.getEncoder().encodeToString(signatureInput))
        }.toString()
    )
    return result.loadJsonModel<SignResult>().getOrThrow().signatureB64
}
```

#### 4.3.4 IMK 在 Kotlin 侧的内存驻留

IMK（32 字节的 base64 编码）在 Kotlin 内存中缓存（否则每次签名都要输入根用户密码）。这是可接受的：

- IMK 是派生密钥，不是根用户密码本身
- 即使进程内存泄露 IMK，攻击者还需要数据库文件才能解密私钥
- 进程退出后 IMK 消失

### 4.3a 密钥操作总结

```
                    Kotlin (JVM)                    Python (pyutil)
                    ────────────                    ───────────────
生成密钥对          POST /crypto/generate-keypair/  Ed25519PrivateKey.generate()
                    ← 收到: 公钥明文 + 私钥密文       私钥加密后返回

联邦签名            POST /crypto/sign/              load_pem_private_key() → .sign()
                    发送: 私钥密文 + IMK + 数据       解密 + 签名
                    ← 收到: 签名结果

派生 IMK           POST /crypto/derive-imk/         argon2id(password, salt)
                    ← 收到: IMK (base64)

密码哈希            POST /crypto/hash-password/      argon2-cffi
                    ← 收到: password_hash
```

### 4.4 联邦身份发现（预留设计）

联邦身份发现是两步协议：**WebFinger** 将用户标识解析为 URL，**Actor JSON-LD** 在该 URL 上承载完整身份信息（含公钥）。

#### 4.4.1 第一步：WebFinger — 用户标识 → Actor URL

WebFinger（RFC 7033）提供了一个所有实例都遵守的统一查询入口，屏蔽了各实例内部 URL 结构的差异（Mastodon 用 `/users/alice`，Pleroma 可能用 `/@alice`，Fredica 可以用任何路径）。

```
远程实例搜索 alice@my-fredica.example.com
  │
  GET https://my-fredica.example.com/.well-known/webfinger
      ?resource=acct:alice@my-fredica.example.com
  │
  ↓ 响应
```

```json
{
  "subject": "acct:alice@my-fredica.example.com",
  "links": [
    {
      "rel": "self",
      "type": "application/activity+json",
      "href": "https://my-fredica.example.com/users/alice"
    }
  ]
}
```

- **路径固定**：`/.well-known/webfinger` 是 RFC 规定的，所有实例都一样
- **输入**：`acct:` URI（类似 email 格式的用户标识）
- **输出**：该用户的 Actor 文档 URL
- **无需鉴权**：公开端点，远程实例需要匿名访问

#### 4.4.2 第二步：Actor JSON-LD — 完整身份信息 + 公钥

拿到 Actor URL 后，远程实例获取完整的用户资料：

```
GET https://my-fredica.example.com/users/alice
Accept: application/activity+json
```

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://my-fredica.example.com/users/alice",
  "type": "Person",
  "preferredUsername": "alice",
  "name": "Alice",
  "inbox": "https://my-fredica.example.com/users/alice/inbox",
  "outbox": "https://my-fredica.example.com/users/alice/outbox",
  "publicKey": {
    "id": "https://my-fredica.example.com/users/alice#main-key",
    "owner": "https://my-fredica.example.com/users/alice",
    "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
  }
}
```

Actor 文档承载三类关键信息：

| 类别 | 字段 | 用途 |
|------|------|------|
| **身份** | `preferredUsername`, `name` | 显示名、用户名 |
| **端点** | `inbox`, `outbox` | 接收消息、发送历史 |
| **公钥** | `publicKey.publicKeyPem` | 验证该用户签名的 HTTP 请求 |

#### 4.4.3 为什么分两步？

| | WebFinger | Actor JSON-LD |
|---|---|---|
| **解决的问题** | 用户标识（`user@domain`）→ URL 的映射 | URL → 完整身份信息 + 公钥 |
| **标准** | RFC 7033 | W3C ActivityPub / ActivityStreams |
| **路径** | 固定 `/.well-known/webfinger` | 实例自定义（`/users/x`、`/@x` 等） |
| **必要性** | 不同实例 URL 结构不同，需要统一入口 | 承载实际的身份数据和密钥材料 |

分两步的核心原因是 **URL 结构不统一**。WebFinger 是"电话簿"，Actor 是"名片"。

#### 4.4.4 身份发现的两种触发场景

**场景 A：验证签名请求（从 keyId 直接获取 Actor）**

```
instance-b 收到 alice 的签名请求
  │
  1. 从 Signature 头提取 keyId:
  │   keyId="https://instance-a.com/users/alice#main-key"
  │
  2. GET https://instance-a.com/users/alice
  │   → 提取 publicKeyPem → 验证签名
  │
  （跳过 WebFinger，因为已经有 Actor URL）
```

**场景 B：搜索/关注用户（从 acct: URI 开始）**

```
用户在 instance-b 搜索 "alice@instance-a.com"
  │
  1. WebFinger: GET instance-a.com/.well-known/webfinger
  │   ?resource=acct:alice@instance-a.com
  │   → 拿到 Actor URL
  │
  2. GET Actor URL → 完整身份信息
  │
  3. 展示给用户，可以关注/互动
```

### 4.5 HTTP Signatures（联邦认证，预留设计）

跨实例请求签名流程（参考 Mastodon）：

```
实例 A 的用户 alice 向实例 B 发送请求
  │
  1. 构建签名字符串：
  │   (request-target): post /api/v1/inbox
  │   host: instance-b.example.com
  │   date: Mon, 15 Apr 2026 12:00:00 GMT
  │   digest: SHA-256=base64(sha256(body))
  │
  2. 用 alice 的私钥签名 → base64 编码
  │
  3. 添加 Signature 请求头：
  │   Signature: keyId="https://instance-a.example.com/users/alice#main-key",
  │              algorithm="ed25519",
  │              headers="(request-target) host date digest",
  │              signature="base64..."
  │
  4. 实例 B 收到请求：
  │   ├── 从 keyId URL 获取 alice 的 Actor 文档
  │   ├── 提取 publicKeyPem
  │   ├── 验证签名
  │   ├── 检查 date 在 ±5 分钟内（防重放）
  │   └── 验证 digest 匹配 body（防篡改）
  │
  5. 验证通过 → 信任此请求来自 instance-a 的 alice
```

---

## 五、本地认证设计（Phase 1 实现）

### 5.1 身份模型

```kotlin
/**
 * 认证结果，描述当前请求的调用者身份。
 */
sealed class AuthIdentity {
    /** 游客：仅持有 webserver_auth_token */
    data object Guest : AuthIdentity()
    
    /** 本地用户：通过密码登录，持有 session token */
    data class User(
        val userId: String,
        val username: String,
        val displayName: String,
        val role: AuthRole,        // TENANT 或 ROOT
        val permissions: Set<String>,
        val sessionId: String,
    ) : AuthIdentity()
}

enum class AuthRole {
    GUEST,      // 游客（只读）
    TENANT,     // 租户（读写）
    ROOT,       // 根用户（全部权限）
}
```

### 5.2 Token 格式

```
游客：  Bearer <webserver_auth_token>          （与现有兼容）
租户：  Bearer fredica_session:<session_token>  （服务端签发的不透明令牌）
```

- `webserver_auth_token`：随机 UUID，存储在 AppConfig 中
- `session_token`：`java.security.SecureRandom` 生成 32 字节 → Base64 URL-safe 编码（43 字符），前缀 `fredica_session:` 区分类型

### 5.3 输入校验规则

所有用户输入在**后端统一校验**，前端做等价校验仅用于即时反馈，不可作为安全边界。

#### 5.3.1 用户名（username）

| 规则 | 值 | 说明 |
|------|---|------|
| 允许字符 | `[a-zA-Z0-9_-]` | 字母、数字、下划线、连字符 |
| 长度 | 2–32 字符 | 过短无意义，过长影响 UI 布局 |
| 大小写 | 存储保留原始大小写，**唯一性检查忽略大小写** | `SELECT ... WHERE LOWER(username) = LOWER(?)` |
| 首字符 | 必须为字母 | 避免与数字 ID 混淆 |
| 保留名 | `admin`, `root`, `system`, `guest`, `anonymous`, `api`, `null`, `undefined` | 注册/创建时拒绝（大小写不敏感匹配） |
| 不可变 | 创建后**不可修改** | 用户名用于登录凭据和联邦标识（`@user@instance`），修改会破坏外部引用 |

```kotlin
private val USERNAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")
private val RESERVED_NAMES = setOf("admin", "root", "system", "guest", "anonymous", "api", "null", "undefined")

fun validateUsername(username: String): String? {
    if (!USERNAME_REGEX.matches(username)) return "用户名只能包含字母、数字、下划线和连字符，2-32 字符，且以字母开头"
    if (username.lowercase() in RESERVED_NAMES) return "该用户名为系统保留名"
    return null // 通过
}
```

#### 5.3.2 显示名（display_name）

| 规则 | 值 | 说明 |
|------|---|------|
| 长度 | 1–64 字符（trim 后） | 不允许空白 |
| 禁止字符 | 控制字符（`\u0000-\u001F`、`\u007F`）、零宽字符（`\u200B-\u200F`、`\uFEFF`） | 防止不可见字符干扰 |
| 前后空白 | 自动 trim | 存储前去除首尾空白 |
| HTML 转义 | 存储原始值，**渲染时转义** | 防止 XSS（前端 React 默认转义，后端 JSON 序列化不受影响） |
| 可修改 | 用户可修改自己的显示名，ROOT 可修改任意用户的显示名 | 通过 `UserUpdateDisplayNameRoute` |

```kotlin
private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200F\\uFEFF]")

fun validateDisplayName(displayName: String): String? {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty() || trimmed.length > 64) return "显示名长度须为 1-64 字符"
    if (CONTROL_CHAR_REGEX.containsMatchIn(trimmed)) return "显示名不能包含控制字符"
    return null
}
```

#### 5.3.3 密码

| 规则 | 值 | 说明 |
|------|---|------|
| 最小长度 | 8 字符 | 后端强制，不依赖前端 |
| 最大长度 | 128 字符 | 防止 Argon2 DoS（超长密码增加哈希计算时间） |
| 强度检查 | zxcvbn score ≥ 3 | **仅前端**提示，后端不运行 zxcvbn（避免引入额外依赖） |
| 后端强制 | 长度检查 + 非全空白 | 后端只做基础校验，强度由前端引导 |

```kotlin
fun validatePassword(password: String): String? {
    if (password.length < 8) return "密码长度不能少于 8 个字符"
    if (password.length > 128) return "密码长度不能超过 128 个字符"
    if (password.isBlank()) return "密码不能为全空白字符"
    return null
}
```

> **设计决策**：后端不引入 zxcvbn 等强度评估库。Argon2id 本身对弱密码提供了足够的哈希保护，前端 zxcvbn 引导用户选择强密码，后端只做底线校验。这避免了在 Kotlin/JVM 侧引入额外依赖。

### 5.4 注册与登录

#### 首次启动（根用户创建）

```
APP 首次启动
  │
  ├── 检测 user 表是否为空
  │     └── 是 → 进入初始化向导
  │
  └── 初始化向导
        1. 输入用户名 + 密码（zxcvbn ≥ 3）
        │   ├── 前端校验：用户名格式（§5.3.1）+ 密码强度
        │   └── 后端校验：用户名格式 + 保留名 + 密码长度（§5.3.3）
        2. 服务端创建根用户：
        │   ├── 密码 → Argon2id 哈希存储
        │   ├── 生成 Ed25519 密钥对（联邦预留）
        │   └── role = ROOT
        3. 生成 webserver_auth_token（随机 UUID）存入 AppConfig
        4. 自动登录，返回 session token
```

#### 租户创建（根用户操作）

```
根用户
  │
  1. POST /api/v1/UserCreateRoute { username, display_name, password, permissions[] }
  2. 服务端：
  │   ├── 校验 username（§5.3.1）：格式 + 保留名 + 大小写不敏感唯一性
  │   ├── 校验 display_name（§5.3.2）：长度 + 禁止字符
  │   ├── 校验 password（§5.3.3）：长度
  │   ├── 密码 → Argon2id 哈希
  │   ├── 生成 Ed25519 密钥对（联邦预留）
  │   └── 创建 user 记录（role = TENANT）
  3. 返回 { user_id, username, display_name }
```

#### 密码登录

```
登录页
  │
  1. POST /api/v1/AuthLoginRoute { username, password }
  │   （requiresAuth = false）
  │
  2. 服务端：
  │   ├── 查找用户（大小写不敏感）→ 检查 status ≠ disabled
  │   ├── Argon2id 验证密码
  │   ├── 检查并执行会话数量限制（§5.5）
  │   ├── 生成 session_token（java.security.SecureRandom 32 字节 → Base64 URL-safe）
  │   ├── 创建 AuthSession 记录
  │   └── 返回 { session_token, user_role, display_name, permissions }
  │
  3. 前端存入 localStorage + Cookie
  │
  4. 后续请求携带 Bearer fredica_session:<token>
```

> **登录失败响应**：无论是用户名不存在、密码错误还是用户已禁用，统一返回 `{"error": "用户名或密码错误"}`（HTTP 401）。不区分失败原因，防止用户名枚举攻击。唯一例外：频率限制触发时返回 429。

### 5.5 会话机制

```kotlin
data class AuthSession(
    val sessionId: String,          // UUID
    val token: String,              // session_token
    val userId: String,
    val createdAt: Long,            // epoch ms
    val expiresAt: Long,            // 默认 7 天
    val lastAccessedAt: Long,       // 滑动过期
    val userAgent: String,          // 审计用
    val ipAddress: String,          // 登录时的客户端 IP（审计用）
)
```

- 默认有效期：7 天
- 滑动过期：超过 24 小时无活动则过期
- 主动登出：`POST /api/v1/AuthLogoutRoute`

#### 并发会话限制

每个用户最多持有 **5 个**活跃 session。登录时若已达上限，自动销毁最早创建的 session（FIFO 淘汰）：

```kotlin
fun createSession(userId: String, userAgent: String, ipAddress: String): AuthSession {
    val existing = AuthSessionDb.findByUserId(userId)
    
    // FIFO 淘汰：超出上限时销毁最早的 session
    val maxSessions = 5
    if (existing.size >= maxSessions) {
        val toEvict = existing
            .sortedBy { it.createdAt }
            .take(existing.size - maxSessions + 1)
        toEvict.forEach { AuthSessionDb.delete(it.sessionId) }
    }
    
    // 创建新 session
    val session = AuthSession(
        sessionId = UUID.randomUUID().toString(),
        token = generateSecureToken(),  // SecureRandom 32 bytes → Base64 URL-safe
        userId = userId,
        createdAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 7 * 24 * 3600_000,
        lastAccessedAt = System.currentTimeMillis(),
        userAgent = userAgent,
        ipAddress = ipAddress,
    )
    AuthSessionDb.insert(session)
    return session
}
```

> **设计说明**：5 个 session 上限覆盖常见多设备场景（桌面 + 手机 + 平板 + WebView + 备用浏览器）。FIFO 淘汰策略简单可预测，用户无需手动管理 session。被淘汰的 session 对应的客户端在下次请求时收到 401，自动跳转登录页。

### 5.6 认证流程（重构后的 checkAuth）

```
前端请求
  │
  ├── 无 Authorization 头 → 401
  │
  ├── Bearer <不含 fredica_session: 前缀>
  │     ├── 匹配 webserver_auth_token → AuthIdentity.Guest
  │     └── 不匹配 → 401
  │
  └── Bearer fredica_session:<token>
        ├── 查 session 缓存/表 → 有效 → AuthIdentity.User(...)
        ├── 不存在或过期 → 401
        └── 异步更新 lastAccessedAt
```

### 5.7 路由权限控制

扩展 `FredicaApi.Route` 接口：

```kotlin
interface Route {
    // ... 现有字段 ...
    
    /** 允许访问此路由的最低角色。默认 GUEST（向后兼容）。 */
    val minRole: AuthRole get() = AuthRole.GUEST
    
    /** 需要的额外权限标签（仅对 TENANT/ROOT 生效）。 */
    val requiredPermissions: Set<String> get() = emptySet()
}
```

权限标签：

```
READ_WRITE          # 常规读写（所有租户默认）
LLM_CALL            # 调用 LLM 接口（涉及计费）
ASR_TASK            # 启动 ASR 转录任务
ADMIN_USER_MGMT     # 管理用户
WORKFLOW_MANAGE      # 管理工作流定义
```

### 5.8 权限检查（重构后的 handleRoute）

```kotlin
suspend fun RoutingContext.handleRoute(route: FredicaApi.Route, getBody: ...) {
    if (!route.requiresAuth) {
        handleRouteResult(route) { route.handler(getBody()) }
        return
    }
    
    val identity = resolveIdentity() ?: run {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }
    
    // 角色检查
    val identityRole = when (identity) {
        is AuthIdentity.Guest -> AuthRole.GUEST
        is AuthIdentity.User -> identity.role
    }
    if (identityRole < route.minRole) {
        call.respond(HttpStatusCode.Forbidden, """{"error":"权限不足"}""")
        return
    }
    
    // 权限标签检查
    if (route.requiredPermissions.isNotEmpty() && identity is AuthIdentity.User) {
        val missing = route.requiredPermissions - identity.permissions
        if (missing.isNotEmpty()) {
            call.respond(HttpStatusCode.Forbidden,
                """{"error":"缺少权限: ${missing.joinToString()}"}""")
            return
        }
    }
    
    call.attributes.put(AuthIdentityKey, identity)
    handleRouteResult(route) { route.handler(getBody()) }
}
```

### 5.9 密码修改流程

密码修改涉及 IMK 重新派生和私钥重加密，流程较复杂：

```
用户（TENANT 或 ROOT）
  │
  1. POST /api/v1/UserChangePasswordRoute { old_password, new_password }
  │
  2. 服务端：
  │   ├── 校验 new_password（§5.3.3）：长度 8-128、非全空白
  │   ├── 验证旧密码（Argon2id verify）
  │   ├── 新密码 → Argon2id 哈希
  │   ├── 旧密码 → 派生旧 IMK → 解密私钥
  │   ├── 新密码 → 派生新 IMK → 重新加密私钥
  │   ├── 更新 user 表：password_hash + private_key_pem（事务内）
  │   ├── 更新内存中缓存的 IMK（如果是当前登录用户）
  │   └── 销毁该用户的所有其他 session（强制其他设备重新登录）
  │
  3. 返回 { success: true }
```

```mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端
    participant K as Ktor
    participant P as Python (pyutil)
    participant DB as SQLite

    U->>F: 输入旧密码 + 新密码
    F->>F: 前端校验新密码（§5.3.3）
    F->>K: POST /api/v1/UserChangePasswordRoute
    K->>K: 后端校验新密码（§5.3.3）
    K->>P: POST /crypto/verify-password/
    P-->>K: { valid: true }
    K->>P: POST /crypto/derive-imk/ (旧密码)
    P-->>K: { imk_b64: old_imk }
    K->>P: POST /crypto/hash-password/ (新密码)
    P-->>K: { hash: new_hash }
    K->>P: POST /crypto/derive-imk/ (新密码)
    P-->>K: { imk_b64: new_imk }
    K->>P: POST /crypto/reencrypt-private-key/
    Note over K,P: 传入密文 + old_imk + new_imk，返回新密文
    K->>DB: UPDATE user SET password_hash, private_key_pem (事务)
    K->>DB: DELETE FROM auth_session WHERE user_id=? AND session_id≠当前
    Note over K,DB: 销毁其他 session，强制其他设备重新登录
    K-->>F: { success: true }
    F-->>U: 密码修改成功
```

> **注意**：密码修改需要新增一个 Python 端点 `/crypto/reencrypt-private-key/`，接收私钥密文 + 旧 IMK + 新 IMK，返回用新 IMK 加密的私钥密文。此端点在 Phase 1 Step 1.2 中一并实现。

### 5.10 用户管理流程

#### 创建租户

根用户通过管理界面创建新租户：

```
根用户
  │
  1. POST /api/v1/UserCreateRoute { username, display_name, password, permissions[] }
  │
  2. 服务端校验：
  │   ├── 校验 username（§5.3.1）：[a-zA-Z][a-zA-Z0-9_-]{1,31}，大小写不敏感唯一性，不在保留名单中
  │   ├── 校验 display_name（§5.3.2）：trim 后 1-64 字符，无控制字符/零宽字符
  │   ├── 校验 password（§5.3.3）：长度 8-128、非全空白
  │   ├── 检查 username 唯一性（含已禁用用户，见下方说明）
  │   ├── 密码 → Argon2id 哈希
  │   ├── 派生 IMK → 生成 Ed25519 密钥对（联邦预留）
  │   └── 创建 user 记录（role = TENANT, status = active）
  │
  3. 返回 { user_id, username, display_name }
```

> **用户名占用规则**：已禁用的用户仍保留其用户名。创建新用户时，即使目标用户名属于已禁用用户，也视为"已占用"并拒绝创建。这避免了身份混淆和审计日志歧义。如需释放用户名，必须先彻底删除旧用户（当前版本不提供删除功能）。

#### 禁用用户

```
根用户
  │
  1. POST /api/v1/UserDisableRoute { user_id }
  │
  2. 服务端：
  │   ├── 检查目标用户存在
  │   ├── 检查目标用户不是 ROOT 角色（ROOT 用户不可被禁用）
  │   ├── 检查操作者不是目标用户本人（不能禁用自己）
  │   ├── UPDATE user SET status = 'disabled'
  │   └── DELETE FROM auth_session WHERE user_id = ?（联动销毁所有 session）
  │
  3. 返回 { success: true }
```

**禁用校验规则**：

| 校验项 | 错误响应 |
|--------|---------|
| 目标用户不存在 | `{ "error": "user_not_found" }` |
| 目标用户是 ROOT 角色 | `{ "error": "cannot_disable_root" }` |
| 操作者 == 目标用户 | `{ "error": "cannot_disable_self" }` |
| 目标用户已处于 disabled 状态 | `{ "error": "user_already_disabled" }` |

禁用后的行为：
- 该用户的所有活跃 session 立即失效（DELETE 在同一事务内）
- 下次请求时 `resolveIdentity()` 检查 `user.status`，disabled 用户返回 401
- 禁用不删除用户数据，可由根用户重新启用（见下方"启用用户"）
- **登录时的错误提示**：已禁用用户尝试登录时，返回与"用户名或密码错误"相同的通用错误消息（`{ "error": "invalid_credentials" }`），不暴露账户禁用状态，防止用户名枚举攻击

#### 启用用户

```
根用户
  │
  1. POST /api/v1/UserEnableRoute { user_id }
  │
  2. 服务端：
  │   ├── 检查目标用户存在
  │   ├── 检查目标用户当前状态为 disabled
  │   ├── UPDATE user SET status = 'active'
  │   └── 记录审计日志（§5.13）
  │
  3. 返回 { success: true }
```

**启用校验规则**：

| 校验项 | 错误响应 |
|--------|---------|
| 目标用户不存在 | `{ "error": "user_not_found" }` |
| 目标用户已处于 active 状态 | `{ "error": "user_already_active" }` |

> 启用后用户需重新登录获取新 session，不会自动恢复之前被销毁的 session。

#### 修改显示名

```
用户（TENANT 或 ROOT）
  │
  1. POST /api/v1/UserUpdateDisplayNameRoute { display_name }
     （ROOT 管理他人时额外传 user_id）
  │
  2. 服务端：
  │   ├── 校验 display_name（§5.3.2）：trim 后 1-64 字符，无控制字符/零宽字符
  │   ├── 若传了 user_id：检查操作者为 ROOT，且目标用户存在
  │   ├── 若未传 user_id：修改当前登录用户自己的显示名
  │   └── UPDATE user SET display_name = ?
  │
  3. 返回 { success: true, display_name: "（更新后的值）" }
```

> TENANT 用户只能修改自己的显示名；ROOT 用户可修改任意用户的显示名。

#### 用户列表

```
根用户
  │
  1. GET /api/v1/UserListRoute
  │
  2. 服务端：
  │   └── 查询所有用户（不含 password_hash、private_key_pem 等敏感字段）
  │
  3. 返回 [{ user_id, username, display_name, role, status, permissions, created_at, last_login_at }]
```

> `last_login_at` 字段便于管理员识别长期未活跃的账户。

### 5.11 会话清理策略

过期 session 的清理采用**双重策略**：

1. **惰性清理**：每次 `resolveIdentity()` 查到 session 时检查 `expires_at` 和 `last_accessed_at`，过期则删除并返回 401
2. **定时清理**：应用启动时清理一次 + 每小时批量删除过期记录，防止 `auth_session` 表无限增长

```kotlin
// AuthService 启动时注册定时清理
fun startSessionCleanup(scope: CoroutineScope) {
    // 启动时立即清理一次
    AuthSessionDb.deleteExpired()
    // 每小时清理
    scope.launch {
        while (isActive) {
            delay(3600_000)
            AuthSessionDb.deleteExpired()
        }
    }
}
```

### 5.12 登录频率限制

防止密码暴力破解，采用**内存计数器**（单实例部署，无需 Redis）：

```
登录请求
  │
  ├── 检查 IP 维度：同一 IP 5 分钟内失败 ≥ 10 次 → 429 Too Many Requests
  ├── 检查用户名维度：同一用户名 5 分钟内失败 ≥ 5 次 → 429
  │
  └── 通过 → 正常登录流程
```

实现方式：`ConcurrentHashMap<String, MutableList<Long>>`，key 为 `"ip:$ip"` 或 `"user:$username"`，value 为失败时间戳列表。每次检查时清理 5 分钟前的记录。

### 5.13 审计日志

所有安全相关操作记录到 `audit_log` 表，用于事后追溯和安全审查。

#### 记录的事件类型

| 事件类型 | 触发时机 | 记录的额外信息 |
|---------|---------|--------------|
| `LOGIN_SUCCESS` | 用户登录成功 | username, ip_address, user_agent |
| `LOGIN_FAILED` | 登录失败（密码错误、用户不存在、账户已禁用） | username（输入值）, ip_address, failure_reason |
| `LOGOUT` | 用户主动登出 | session_id |
| `SESSION_EXPIRED` | session 过期被清理 | session_id, user_id |
| `SESSION_EVICTED` | 因并发上限被 FIFO 淘汰（§5.5） | session_id, user_id |
| `USER_CREATED` | 根用户创建新租户 | target_user_id, target_username |
| `USER_DISABLED` | 根用户禁用用户 | target_user_id, sessions_destroyed_count |
| `USER_ENABLED` | 根用户启用用户 | target_user_id |
| `PASSWORD_CHANGED` | 用户修改密码 | sessions_destroyed_count |
| `DISPLAY_NAME_CHANGED` | 修改显示名 | target_user_id, old_display_name, new_display_name |
| `PERMISSION_CHANGED` | 根用户修改用户权限 | target_user_id, old_permissions, new_permissions |
| `RATE_LIMIT_TRIGGERED` | 触发登录频率限制（§5.12） | ip_address, username（如有）, dimension（ip/user） |

#### 数据模型

```kotlin
data class AuditLogEntry(
    val id: String,              // UUID
    val timestamp: Long,         // epoch ms
    val eventType: String,       // 上表中的事件类型
    val actorUserId: String?,    // 操作者 user_id（未登录时为 null，如 LOGIN_FAILED）
    val actorUsername: String?,   // 操作者 username（冗余存储，用户删除后仍可追溯）
    val targetUserId: String?,   // 被操作的目标用户（如有）
    val ipAddress: String?,      // 客户端 IP
    val userAgent: String?,      // 客户端 User-Agent
    val details: String?,        // JSON 格式的额外信息（各事件类型不同）
)
```

#### 写入策略

- **同步写入**：安全关键事件（LOGIN_FAILED、USER_DISABLED、PASSWORD_CHANGED）在业务操作的同一事务内写入，确保不丢失
- **异步写入**：低优先级事件（LOGIN_SUCCESS、SESSION_EXPIRED）可异步写入，避免影响请求延迟
- **不可变**：审计日志只追加，不允许 UPDATE 或 DELETE

#### 敏感信息处理

- **不记录密码**：LOGIN_FAILED 的 `details` 中只记录 `failure_reason`（如 `"invalid_password"`、`"user_not_found"`、`"user_disabled"`），不记录输入的密码
- **IP 地址**：记录客户端 IP，用于异常登录检测
- **统一错误消息**：虽然审计日志内部区分失败原因，但返回给客户端的错误消息始终为 `invalid_credentials`（§5.10 禁用用户登录说明）

#### 保留策略

- 默认保留 **90 天**
- 清理由 §5.11 的定时任务一并执行：`DELETE FROM audit_log WHERE timestamp < ?`
- ROOT 用户可通过管理界面查看审计日志（只读）

#### 查询接口

```
根用户
  │
  1. GET /api/v1/AuditLogListRoute?page=1&size=50&event_type=LOGIN_FAILED&user_id=xxx
  │
  2. 服务端：
  │   ├── 仅 ROOT 角色可访问
  │   └── 支持按 event_type、user_id、时间范围筛选
  │
  3. 返回 { items: AuditLogEntry[], total: number, page: number }
```

---

## 六、数据库变更

### 6.1 新增 `user` 表

```sql
CREATE TABLE IF NOT EXISTS user (
    id              TEXT PRIMARY KEY,          -- UUID
    username        TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    password_hash   TEXT NOT NULL,             -- Argon2id
    role            TEXT NOT NULL DEFAULT 'tenant',  -- root / tenant
    permissions     TEXT NOT NULL DEFAULT '',  -- 逗号分隔
    public_key_pem  TEXT NOT NULL DEFAULT '',  -- Ed25519 公钥（联邦预留）
    private_key_pem TEXT NOT NULL DEFAULT '',  -- Ed25519 私钥（服务端保管，联邦预留）
    status          TEXT NOT NULL DEFAULT 'active',  -- active / disabled
    created_at      TEXT NOT NULL,             -- ISO 8601
    updated_at      TEXT NOT NULL,
    last_login_at   TEXT,                      -- ISO 8601，最近一次登录成功时间（可为 null）

    -- 约束
    CHECK (length(username) >= 2 AND length(username) <= 32),
    CHECK (username GLOB '[a-zA-Z][a-zA-Z0-9_-]*'),
    CHECK (length(trim(display_name)) >= 1 AND length(display_name) <= 64),
    CHECK (role IN ('root', 'tenant')),
    CHECK (status IN ('active', 'disabled'))
);
```

### 6.2 新增 `auth_session` 表

```sql
CREATE TABLE IF NOT EXISTS auth_session (
    session_id       TEXT PRIMARY KEY,
    token            TEXT NOT NULL UNIQUE,     -- session token（索引）
    user_id          TEXT NOT NULL REFERENCES user(id),
    created_at       INTEGER NOT NULL,         -- epoch ms
    expires_at       INTEGER NOT NULL,
    last_accessed_at INTEGER NOT NULL,
    user_agent       TEXT NOT NULL DEFAULT '',
    ip_address       TEXT NOT NULL DEFAULT ''  -- 登录时的客户端 IP（审计用）
);
```

### 6.3 新增 `audit_log` 表

```sql
CREATE TABLE IF NOT EXISTS audit_log (
    id              TEXT PRIMARY KEY,          -- UUID
    timestamp       INTEGER NOT NULL,          -- epoch ms
    event_type      TEXT NOT NULL,             -- LOGIN_SUCCESS / LOGIN_FAILED / LOGOUT / ...
    actor_user_id   TEXT,                      -- 操作者 user_id（未登录时为 null）
    actor_username  TEXT,                      -- 冗余存储，用户删除后仍可追溯
    target_user_id  TEXT,                      -- 被操作的目标用户（如有）
    ip_address      TEXT,
    user_agent      TEXT,
    details         TEXT                       -- JSON 格式的额外信息
);

-- 常用查询索引
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_event_type ON audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit_log(actor_user_id);
```

### 6.4 AppConfig 新增键

```kotlin
"webserver_auth_token" to "",           // 游客令牌（首次启动时随机生成）
"instance_initialized" to "false",      // 是否已完成初始化向导
```

---

## 七、前端改造

### 7.1 页面导航总流程

用户从打开浏览器到进入主界面的完整导航流程：

```mermaid
flowchart TD
    Start([用户访问 /]) --> StorageLoad{localStorage\n加载完成?}
    StorageLoad -- 否 --> ShowLoading[显示 Connecting...]
    ShowLoading --> StorageLoad

    StorageLoad -- 是 --> HasConfig{有已保存的\n连接配置?}
    HasConfig -- 否 --> ConnForm[显示连接表单\nschema / domain / port]
    HasConfig -- 是 --> AutoPing[自动 ping 服务器]

    ConnForm -- 用户提交 --> ManualPing[ping 服务器]
    ManualPing -- 失败 --> ConnForm
    ManualPing -- 成功 --> CheckAuth

    AutoPing -- 失败 --> ConnForm
    AutoPing -- 成功 --> CheckAuth

    CheckAuth{有有效的\nsession_token 或\nwebserver_auth_token?}
    CheckAuth -- 是 --> MainApp[进入 SidebarLayout\n主界面]
    CheckAuth -- 否 --> NavLogin[跳转 /login]

    NavLogin --> LoginPage[登录页]
    LoginPage --> InstanceCheck[GET /api/v1/InstanceStatusRoute]
    InstanceCheck -- 未初始化 --> NavSetup[跳转 /setup]
    InstanceCheck -- 已初始化 --> ShowLogin[显示登录表单]

    NavSetup --> SetupPage[首次启动向导\n创建根用户]
    SetupPage -- 创建成功 --> AutoLogin[自动登录\n写入 session]
    AutoLogin --> MainApp

    ShowLogin --> UserLogin[用户名 + 密码登录]
    ShowLogin --> GuestLogin[游客令牌登录]
    UserLogin -- 成功 --> WriteSession[写入 session_token\n+ user_role 等]
    GuestLogin -- 成功 --> WriteToken[写入 webserver_auth_token]
    WriteSession --> MainApp
    WriteToken --> MainApp
    UserLogin -- 失败 --> ShowLogin
    GuestLogin -- 失败 --> ShowLogin

    style Start fill:#e1f5fe
    style MainApp fill:#c8e6c9
    style LoginPage fill:#fff3e0
    style SetupPage fill:#fce4ec
```

### 7.2 WebView 环境认证流程

桌面端 WebView 通过 JS Bridge 自动获取连接信息，无需手动输入：

```mermaid
sequenceDiagram
    participant WV as WebView 前端
    participant Bridge as JS Bridge
    participant Ktor as Ktor 服务端

    Note over WV: AppConfigProvider 挂载
    WV->>WV: useLayoutEffect: 读取 localStorage
    WV->>Bridge: callBridge("get_server_info")

    alt Bridge 可用（WebView 环境）
        Bridge-->>WV: { webserver_domain, webserver_port,<br/>webserver_schema, webserver_auth_token }
        WV->>WV: setAppConfig(info)
        WV->>WV: 写入 fredica_token Cookie
        Note over WV: _index.tsx 挂载
        WV->>Ktor: ping /api/v1/ping
        Ktor-->>WV: 200 OK
        WV->>WV: 检查 appConfig 有 webserver_auth_token
        WV->>WV: 进入 SidebarLayout（游客身份）
    else Bridge 不可用（浏览器环境）
        Bridge-->>WV: catch → 忽略
        Note over WV: 走正常连接表单流程
    end
```

> **WebView 兼容策略**：bridge 返回的 `webserver_auth_token` 继续作为游客令牌使用。未来可扩展 bridge 返回 `session_token`，使 WebView 用户自动以根用户身份登录。

### 7.3 密码登录时序

```mermaid
sequenceDiagram
    participant User as 用户
    participant Login as /login 页面
    participant API as Ktor API
    participant DB as SQLite

    User->>Login: 输入用户名 + 密码
    User->>Login: 点击 [登录]
    Login->>API: POST /api/v1/AuthLoginRoute<br/>{ username, password }

    API->>DB: 查找 user 记录
    DB-->>API: user 记录

    alt 用户不存在或密码错误
        API-->>Login: 401 { error: "用户名或密码错误" }
        Login->>User: 显示错误提示
    else 验证通过
        API->>API: Argon2id 验证密码
        API->>API: 生成 session_token
        API->>DB: 创建 AuthSession 记录
        API-->>Login: 200 { session_token, user_role,<br/>display_name, permissions }

        Login->>Login: setAppConfig({<br/>  session_token,<br/>  user_role,<br/>  user_display_name,<br/>  user_permissions<br/>})
        Note over Login: setAppConfig 内部自动：<br/>1. 写 localStorage<br/>2. 写 fredica_token Cookie
        Login->>Login: navigate("/")
    end
```

### 7.4 游客令牌登录时序

```mermaid
sequenceDiagram
    participant User as 用户
    participant Login as /login 页面
    participant API as Ktor API

    User->>Login: 输入访问令牌
    User->>Login: 点击 [进入]

    Login->>API: GET /api/v1/ping<br/>Authorization: Bearer <令牌>

    alt 令牌无效
        API-->>Login: 401
        Login->>User: 显示"令牌无效"
    else 令牌有效
        API-->>Login: 200
        Login->>Login: setAppConfig({<br/>  webserver_auth_token: 令牌,<br/>  user_role: "guest"<br/>})
        Login->>Login: navigate("/")
    end
```

### 7.5 首次启动向导时序

```mermaid
sequenceDiagram
    participant User as 用户
    participant Setup as /setup 页面
    participant API as Ktor API
    participant PyUtil as Python pyutil

    Note over Setup: 挂载时检查实例状态
    Setup->>API: GET /api/v1/InstanceStatusRoute
    API-->>Setup: { initialized: false }

    User->>Setup: 输入用户名 + 密码
    Note over Setup: 前端 zxcvbn 校验密码强度 ≥ 3

    User->>Setup: 点击 [创建并开始]
    Setup->>API: POST /api/v1/InstanceInitRoute<br/>{ username, password }

    API->>PyUtil: POST /crypto/hash-password/<br/>{ password }
    PyUtil-->>API: { hash: "argon2id$..." }

    API->>PyUtil: POST /crypto/generate-keypair/
    PyUtil-->>API: { public_key_pem, encrypted_private_key_pem }

    API->>API: 创建 user 记录 (role=ROOT)
    API->>API: 生成 webserver_auth_token (UUID)
    API->>API: 生成 session_token
    API->>API: 创建 AuthSession 记录

    API-->>Setup: 200 { session_token, user_role: "root",<br/>display_name, permissions,<br/>webserver_auth_token }

    Setup->>Setup: setAppConfig({ 全部字段 })
    Setup->>Setup: navigate("/")
```

### 7.6 Session 过期 & 401 全局处理

```mermaid
sequenceDiagram
    participant Page as 任意页面
    participant Fetch as useAppFetch
    participant API as Ktor API
    participant Config as AppConfig

    Page->>Fetch: apiFetch("/api/v1/SomeRoute")
    Fetch->>API: GET /api/v1/SomeRoute<br/>Authorization: Bearer fredica_session:<token>

    alt Session 有效
        API-->>Fetch: 200 { data }
        Fetch-->>Page: { resp, data }
    else Session 过期 / 无效
        API-->>Fetch: 401

        Fetch->>Fetch: 检测到 401
        Fetch->>Config: setAppConfig({<br/>  session_token: null,<br/>  user_role: null,<br/>  user_display_name: null,<br/>  user_permissions: null<br/>})
        Note over Config: 自动清除 Cookie
        Fetch->>Fetch: toast.warn("登录已过期，请重新登录")
        Fetch->>Page: window.location.href = "/login"
    end
```

> **401 处理要点**：
> - 仅在 `session_token` 存在时才触发自动清除（游客 401 不清除 `webserver_auth_token`，可能是 token 本身错误）
> - 使用 `window.location.href` 而非 React Router `navigate()`，确保完全重置页面状态
> - 声明式模式（`useEffect` 内）和命令式模式（`apiFetch`）均需处理

### 7.7 登出流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant Sidebar as Sidebar 组件
    participant API as Ktor API
    participant Config as AppConfig

    User->>Sidebar: 点击 [登出]
    Sidebar->>API: POST /api/v1/AuthLogoutRoute<br/>Authorization: Bearer fredica_session:<token>
    API->>API: 销毁 AuthSession 记录
    API-->>Sidebar: 200

    Sidebar->>Config: setAppConfig({<br/>  session_token: null,<br/>  user_role: null,<br/>  user_display_name: null,<br/>  user_permissions: null<br/>})
    Note over Config: 自动清除 Cookie<br/>（保留 webserver_auth_token）
    Sidebar->>Sidebar: navigate("/login")
```

### 7.8 登录页

```
┌─────────────────────────────┐
│  Fredica 登录                │
│                             │
│  ┌─ 租户/根用户登录 ────────┐ │
│  │ 用户名: [________]      │ │
│  │ 密  码: [________]      │ │
│  │ [登录]                  │ │
│  └─────────────────────────┘ │
│                             │
│  ── 或 ──                    │
│                             │
│  ┌─ 游客访问 ──────────────┐ │
│  │ 访问令牌: [________]    │ │
│  │ [进入]                  │ │
│  └─────────────────────────┘ │
└─────────────────────────────┘
```

路由文件：`app/routes/login.tsx`

- 挂载时调 `GET /api/v1/InstanceStatusRoute`，未初始化则跳转 `/setup`
- 已有有效 session 则跳转 `/`
- 密码输入复用现有 `PasswordInput` 组件（`app/components/ui/PasswordInput.tsx`）

### 7.9 首次启动向导页

```
┌─────────────────────────────┐
│  Fredica 初始化               │
│                             │
│  欢迎！请创建管理员账户。      │
│                             │
│  用户名: [________]          │
│  密  码: [________]          │
│  确认密码: [________]         │
│                             │
│  密码强度: ████░░ 中等        │
│  (需要"强"以上才能继续)       │
│                             │
│  [创建并开始使用]             │
└─────────────────────────────┘
```

路由文件：`app/routes/setup.tsx`

- 挂载时调 `GET /api/v1/InstanceStatusRoute`，已初始化则跳转 `/login`
- 密码强度使用 `zxcvbn` 库前端校验（score ≥ 3）
- 创建成功后自动登录并跳转 `/`

### 7.10 AppConfig 扩展

```typescript
interface AppConfig {
    // 现有字段
    webserver_domain: string | null;
    webserver_port: string | null;
    webserver_schema: WebserverSchema | null;
    webserver_auth_token: string | null;

    // 新增：用户认证状态
    session_token: string | null;
    user_role: "guest" | "tenant" | "root" | null;
    user_display_name: string | null;
    user_permissions: string[] | null;
}
```

**Cookie 写入集中化**：当前 `setAppConfig` 不写 Cookie（只有 `useLayoutEffect` 恢复时写）。改造后 `setAppConfig` 内部统一处理：

```typescript
const setAppConfig = (config: Partial<AppConfig>) => {
    setAppConfigState(prev => {
        const next = { ...prev, ...config };
        // 写 localStorage
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(next)); } catch {}
        // 写 Cookie（供 <video src> 等浏览器自动携带）
        const cookieToken = next.session_token
            ? `fredica_session:${next.session_token}`
            : next.webserver_auth_token;
        if (cookieToken) {
            document.cookie = `fredica_token=${cookieToken}; path=/; SameSite=Strict`;
        } else {
            document.cookie = `fredica_token=; path=/; max-age=0`;
        }
        return next;
    });
};
```

### 7.11 认证 Header 构建

重载 `buildAuthHeaders`，保持向后兼容：

```typescript
// 新签名：传 AppConfig 对象，自动选择 session_token 优先
// 旧签名：传 string，直接作为 token（向后兼容）
export function buildAuthHeaders(
    configOrToken?: AppConfig | string | null
): Record<string, string> {
    let token: string | null | undefined;
    if (configOrToken && typeof configOrToken === "object") {
        // AppConfig 对象：session_token 优先
        token = configOrToken.session_token
            ? `fredica_session:${configOrToken.session_token}`
            : configOrToken.webserver_auth_token;
    } else {
        // 字符串：直接使用（向后兼容）
        token = configOrToken;
    }
    if (!token) return {};
    return { Authorization: `Bearer ${token}` };
}
```

**向后兼容策略**：现有 8 处直接调用 `buildAuthHeaders(string)` 的代码无需立即修改。`useAppFetch` 内部改为传 `appConfig` 对象，自动使用 session_token。外部调用方可逐步迁移。

### 7.12 RequireAuth 权限守卫

```typescript
function RequireAuth({ minRole, children }: {
    minRole?: AuthRole;  // 默认 "guest"
    children: React.ReactNode;
}) {
    const { appConfig, isStorageLoaded } = useAppConfig();

    if (!isStorageLoaded) return <LoadingSpinner />;

    // 未登录 → 跳转登录页
    if (!appConfig.session_token && !appConfig.webserver_auth_token) {
        return <Navigate to="/login" replace />;
    }

    // 权限不足 → 403 页面
    if (!meetsMinRole(appConfig.user_role ?? "guest", minRole ?? "guest")) {
        return <ForbiddenPage />;
    }

    return <>{children}</>;
}
```

**使用位置**：包裹 `SidebarLayout`，而非放在 layout route 或 `root.tsx`。因为连接表单（`_index.tsx` 的 ping 失败状态）不需要认证。

```tsx
// _index.tsx 中 ping 成功后
if (pingStatus === "ok") {
    return (
        <RequireAuth>
            <SidebarLayout>...</SidebarLayout>
        </RequireAuth>
    );
}
```

### 7.13 组件架构总览

```mermaid
graph TB
    subgraph "root.tsx"
        ACP[AppConfigProvider]
        Outlet[Outlet]
        Toast[ToastContainer]
    end

    subgraph "路由层"
        Index["/ _index.tsx<br/>连接表单 → ping"]
        Login["/login<br/>登录页"]
        Setup["/setup<br/>首次启动向导"]
        AdminUsers["/admin/users<br/>用户管理（ROOT）"]
        Material["/material/:id<br/>素材详情"]
        Other["其他路由..."]
    end

    subgraph "守卫层"
        RA[RequireAuth<br/>minRole?]
    end

    subgraph "布局层"
        SL[SidebarLayout]
        SB[Sidebar<br/>+ 用户信息 + 登出]
        FP[FloatingPlayerProvider]
    end

    ACP --> Outlet
    Outlet --> Index
    Outlet --> Login
    Outlet --> Setup
    Index -- "ping 成功" --> RA
    RA -- "已认证" --> SL
    RA -- "未认证" --> Login
    RA -- "ROOT" --> AdminUsers
    SL --> SB
    SL --> FP

    style ACP fill:#e3f2fd
    style RA fill:#fff3e0
    style Login fill:#fce4ec
    style Setup fill:#fce4ec
    style AdminUsers fill:#fff9c4
    style SL fill:#c8e6c9
```

### 7.14 Sidebar 用户信息 & 登出

Sidebar 底部（当前被注释掉的 footer 区域）改造为：

- **已登录用户**：显示 `user_display_name` + 角色 badge（ROOT / TENANT）+ 登出按钮
- **游客**：显示 "游客" + 简化 footer（无登出，因为游客没有 session）
- 登出按钮已有 `LogOut` 图标 import（`Sidebar.tsx` line 7）

### 7.15 用户管理页面（仅 ROOT）

用户管理页面供根用户查看、创建和禁用/启用租户用户。页面整体被 `RequireAuth minRole="root"` 守卫包裹，非 ROOT 用户访问时显示 403。

#### 路由文件

`app/routes/admin.users.tsx`

命名遵循现有 `app-*-setting.tsx` 模式，`admin.` 前缀表示管理功能。Sidebar 中仅当 `appConfig.user_role === "root"` 时显示入口。

#### 页面 Mockup

```
┌─────────────────────────────────────────────────────────────────┐
│  用户管理                                        [+ 创建用户]   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 用户名       显示名       角色     状态     创建时间   操作 ││
│  ├─────────────────────────────────────────────────────────────┤│
│  │ admin        管理员       ROOT    ● 活跃   2026-01-01  —   ││
│  │ alice        Alice        TENANT  ● 活跃   2026-03-15  ⚙   ││
│  │ bob          Bob          TENANT  ○ 禁用   2026-03-20  ⚙   ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  ⚙ 操作菜单（TENANT 行）：                                      │
│  ┌──────────────┐                                               │
│  │ 禁用用户      │  ← 活跃用户显示                               │
│  │ 启用用户      │  ← 禁用用户显示                               │
│  └──────────────┘                                               │
│                                                                 │
│  ROOT 行无操作按钮（不能禁用自己）                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**创建用户对话框**（点击 `[+ 创建用户]` 弹出）：

```
┌──────────────── 创建租户用户 ────────────────┐
│                                              │
│  用户名 *     [___________________________]  │
│  显示名 *     [___________________________]  │
│  密码 *       [___________________________]  │
│  确认密码 *   [___________________________]  │
│                                              │
│  权限         □ material:write               │
│               □ weben:write                  │
│               □ task:write                   │
│                                              │
│              [取消]          [创建]           │
└──────────────────────────────────────────────┘
```

#### Sidebar 入口

在 `Sidebar.tsx` 的设置区域（`Settings` 图标附近）添加条件渲染的管理入口：

```tsx
import { Users } from 'lucide-react';

// Sidebar 内部，仅 ROOT 可见
{appConfig.user_role === "root" && (
    <SideBarItem uid="admin-users" title="用户管理" Icon={Users} routeTo="/admin/users" />
)}
```

#### 组件结构

```
admin.users.tsx
├── RequireAuth minRole="root"
│   └── AdminUsersPage
│       ├── UserListTable              ← 用户列表表格
│       │   ├── UserRow                ← 单行（含状态 badge + 操作按钮）
│       │   └── UserActionMenu         ← 禁用/启用下拉菜单
│       └── CreateUserDialog           ← 创建用户模态框
│           ├── 表单字段（username, display_name, password, confirm_password）
│           ├── 权限复选框组
│           └── 提交 → POST UserCreateRoute
```

#### 创建用户时序

```mermaid
sequenceDiagram
    participant R as 根用户
    participant FE as admin.users.tsx
    participant API as Ktor API

    R->>FE: 点击 [+ 创建用户]
    FE->>FE: 打开 CreateUserDialog

    R->>FE: 填写表单，点击 [创建]
    FE->>FE: 前端校验（用户名非空、密码≥8位、两次密码一致）

    FE->>API: POST /api/v1/UserCreateRoute<br/>{ username, display_name, password, permissions[] }
    API->>API: 检查 username 唯一性
    API->>API: Argon2id 哈希密码
    API->>API: 派生 IMK → 生成 Ed25519 密钥对
    API-->>FE: 200 { user_id, username, display_name }

    FE->>FE: 关闭对话框 + toast 成功提示
    FE->>API: GET /api/v1/UserListRoute
    API-->>FE: 200 [{ user_id, username, ... }]
    FE->>FE: 刷新用户列表

    Note over FE,API: 用户名重复时
    API-->>FE: 409 { error: "用户名已存在" }
    FE->>FE: 表单内显示错误提示
```

#### 禁用/启用用户时序

```mermaid
sequenceDiagram
    participant R as 根用户
    participant FE as admin.users.tsx
    participant API as Ktor API

    R->>FE: 点击 ⚙ → 禁用用户
    FE->>FE: 弹出确认对话框<br/>"确定禁用用户 alice？<br/>该用户的所有会话将被立即销毁。"

    R->>FE: 确认
    FE->>API: POST /api/v1/UserDisableRoute<br/>{ user_id, action: "disable" }
    API->>API: 检查目标非 ROOT
    API->>API: UPDATE user SET status='disabled'
    API->>API: DELETE FROM auth_session WHERE user_id=?
    API-->>FE: 200 { success: true }

    FE->>FE: toast "用户 alice 已禁用"
    FE->>API: GET /api/v1/UserListRoute
    API-->>FE: 200 [...]
    FE->>FE: 刷新列表（alice 状态变为 ○ 禁用）

    Note over FE,API: 启用用户流程相同
    R->>FE: 点击 ⚙ → 启用用户
    FE->>API: POST /api/v1/UserDisableRoute<br/>{ user_id, action: "enable" }
    API->>API: UPDATE user SET status='active'
    API-->>FE: 200 { success: true }
    FE->>FE: 刷新列表（状态变为 ● 活跃）
```

#### 关键交互细节

| 场景 | 行为 |
|------|------|
| ROOT 行 | 无操作按钮，tooltip 提示"根用户不可禁用" |
| 禁用操作 | 必须二次确认（确认对话框），提示 session 联动销毁 |
| 创建成功 | 关闭对话框 + toast + 自动刷新列表 |
| 用户名重复 | 409 → 表单内 inline 错误，不关闭对话框 |
| 密码校验 | 前端：≥8 位 + 两次一致；后端：Argon2id 哈希（不做额外复杂度要求） |
| 权限字段 | 复选框组，当前可选：`material:write`、`weben:write`、`task:write`；未勾选则为空数组 |
| 空列表 | 不会出现（至少有 ROOT 用户自身） |
| 加载状态 | 列表加载中显示骨架屏（Skeleton），操作按钮 loading 时 disabled |

#### 数据获取

```tsx
// admin.users.tsx
const { data: users, loading, apiFetch } = useAppFetch<UserInfo[]>({
    appPath: '/api/v1/UserListRoute',
});

// UserInfo 类型（与 UserListRoute 响应一致）
interface UserInfo {
    user_id: string;
    username: string;
    display_name: string;
    role: "ROOT" | "TENANT";
    status: "active" | "disabled";
    permissions: string[];
    created_at: string;  // ISO 8601
}
```

### 7.16 文件变更清单

| 操作 | 文件路径 | 说明 |
|------|---------|------|
| **新建** | `app/util/auth.ts` | `AuthRole` 类型、`meetsMinRole()` 工具函数 |
| **新建** | `app/components/auth/RequireAuth.tsx` | 权限守卫组件（含内联 Loading / Forbidden） |
| **新建** | `app/routes/login.tsx` | 登录页（密码登录 + 游客令牌） |
| **新建** | `app/routes/setup.tsx` | 首次启动向导（创建根用户） |
| **新建** | `app/routes/admin.users.tsx` | 用户管理页面（ROOT 专属，含列表 + 创建 + 禁用/启用） |
| **修改** | `app/context/appConfig.tsx` | AppConfig 扩展 4 字段 + Cookie 写入集中化 |
| **修改** | `app/util/app_fetch.ts` | `buildAuthHeaders` 重载 + `useAppFetch` 适配 + 401 全局处理 |
| **修改** | `app/routes/_index.tsx` | 移除 Auth Token 表单字段 + 未登录跳转 `/login` |
| **修改** | `app/components/sidebar/Sidebar.tsx` | 底部用户信息 + 登出按钮 + ROOT 条件渲染"用户管理"入口 |

### 7.17 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| `buildAuthHeaders` 签名 | 重载（接受 `AppConfig \| string`） | 8 处旧调用方无需改动，渐进迁移 |
| RequireAuth 位置 | 包裹 `SidebarLayout` | 连接表单不需要认证；`/login`、`/setup` 是独立路由 |
| `/login` 与 `/setup` | 分离为两个路由 | setup 仅首次可用，login 是常规入口；各自挂载时检测实例状态互相跳转 |
| 401 跳转方式 | `window.location.href` | 完全重置页面状态，避免残留的 React 状态导致异常 |
| Cookie 值格式 | 与 Authorization header 一致 | session 用户写 `fredica_session:<token>`，游客写 `<token>`；后端 `MaterialVideoStreamRoute` 统一解析 |
| WebView 兼容 | bridge 返回 `webserver_auth_token` 继续生效 | 游客身份自动进入，未来可扩展返回 `session_token` |
| 用户管理页面入口 | Sidebar 条件渲染（`user_role === "root"`） | 非 ROOT 用户完全不可见入口，双重保护（前端隐藏 + RequireAuth 守卫 + 后端 minRole 校验） |
| 禁用操作确认 | 二次确认对话框 | 禁用会联动销毁所有 session，属于高危操作，防止误触 |

---

## 八、API 路由清单

### 8.1 新增路由

| 路由 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `AuthLoginRoute` | POST | 无需鉴权 | 用户名/密码登录 → session token |
| `AuthLogoutRoute` | POST | GUEST+ | 销毁当前 session |
| `AuthMeRoute` | GET | TENANT+ | 查询当前用户信息 |
| `UserCreateRoute` | POST | ROOT | 创建租户（校验 §5.3） |
| `UserListRoute` | GET | ROOT | 用户列表（含 last_login_at） |
| `UserDisableRoute` | POST | ROOT | 禁用用户（联动销毁 session） |
| `UserEnableRoute` | POST | ROOT | 启用已禁用的用户 |
| `UserChangePasswordRoute` | POST | TENANT+ | 修改自己的密码（联动销毁其他 session） |
| `UserUpdateDisplayNameRoute` | POST | TENANT+ | 修改显示名（ROOT 可改他人） |
| `InstanceInitRoute` | POST | 无需鉴权* | 首次启动初始化（仅未初始化时可用） |
| `InstanceStatusRoute` | GET | 无需鉴权 | 查询实例是否已初始化 |
| `AuditLogListRoute` | GET | ROOT | 审计日志查询（分页 + 筛选） |

### 8.2 现有路由权限分配

```kotlin
// 只读 → GUEST
MaterialListRoute, MaterialDetailRoute, MaterialCategoryListRoute,
BilibiliVideoSubtitleBodyRoute, BilibiliVideoSubtitleMetaRoute,
WebenSourceListRoute, WebenConceptListRoute, ...

// 读写 → TENANT
MaterialImportRoute, MaterialCategoryCreateRoute, MaterialCategoryDeleteRoute,
WebenSourceAnalyzeRoute, WebenConceptSaveRoute, ...

// LLM → TENANT + LLM_CALL
LlmProxyChatRoute, PromptScriptGenerateRoute, ...

// ASR → TENANT + ASR_TASK
AsrSpawnChunksRoute, TranscribeRoute, ...

// 管理 → ROOT
AppConfigUpdateRoute, UserCreateRoute, UserDisableRoute, UserEnableRoute,
UserListRoute, AuditLogListRoute, ...
```

---

## 九、webserver_auth_token 重构

| 组件 | 现状 | 重构后 |
|------|------|--------|
| `GetServerInfoJsMessageHandler` | 硬编码 `"114514"` | 从 AppConfig 读取；若根用户已登录，额外返回 session_token |
| `checkAuth()` | 只检查 header 存在 | 解析 token 类型，返回 `AuthIdentity` |
| `MaterialVideoStreamRoute` | Cookie 存在即通过 | Cookie 值必须匹配有效 token |
| `AppConfig` | 无 `webserver_auth_token` 字段 | 新增，首次启动时随机生成 UUID |

---

## 十、联邦设计预留（Phase 4+ 实现）

### 10.1 联邦制核心思想

#### 为什么要联邦？

传统中心化服务（如 YouTube、Bilibili）的身份系统依赖单一权威：一个中心服务器持有所有用户数据，所有客户端信任这个服务器。这在自部署场景下不可行——每个 Fredica 实例是独立部署的，没有"中心"可以信任。

联邦制解决的核心问题是：**在没有中心权威的前提下，多个独立实例如何互相识别和信任对方的用户？**

#### 联邦 vs 中心化 vs 完全去中心化

```
中心化                    联邦制                     完全去中心化
(YouTube)                (Mastodon/Fredica)         (Nostr/SSB)
                         
┌─────────┐              ┌───────┐  ←互信→  ┌───────┐     每个用户独立
│ 中心服务器 │              │实例 A  │         │实例 B  │     持有密钥
│ 持有一切  │              │管自己的 │         │管自己的 │     无服务器依赖
└────┬────┘              │用户    │         │用户    │
     │                   └───┬───┘         └───┬───┘
  所有用户                 alice, bob         charlie
```

| 模型 | 信任根 | 用户体验 | 适合场景 |
|------|--------|---------|---------|
| 中心化 | 单一服务商 | 最简单（注册即用） | 商业平台 |
| **联邦制** | **各实例域名** | **中等（注册自己的实例）** | **自部署互信** |
| 完全去中心化 | 用户自己的密钥 | 最复杂（管理密钥） | 抗审查 |

Fredica 选择联邦制，因为：
- 用户已经信任了自己部署的实例（否则不会部署）
- 不需要用户管理密钥文件（服务端托管）
- 实例间通过域名建立信任，简单直观

#### 域名即信任根

联邦制的信任模型极其简洁：

> **你信任一个域名 → 你信任该域名发布的公钥 → 你信任该公钥签名的消息**

这与 HTTPS 的信任模型一致——浏览器信任 CA 签发的证书，联邦实例信任域名持有者发布的公钥。区别在于联邦不需要额外的 CA 层，HTTPS 本身已经提供了域名验证。

```
HTTPS 保证：instance-a.com 的响应确实来自 instance-a.com 的服务器
  └→ 所以 instance-a.com/users/alice 返回的公钥是可信的
      └→ 所以用这个公钥验证通过的签名是可信的
          └→ 所以签名请求确实来自 instance-a 的 alice
```

#### 服务端托管密钥（Custodial Trust Model）

与完全去中心化方案（如 Nostr，用户自己持有私钥）不同，联邦制采用**服务端托管密钥**：

- 用户的 Ed25519 密钥对由实例服务端生成和保管
- 用户不需要接触私钥，甚至不需要知道密钥的存在
- 跨实例签名由服务端自动完成

这是合理的，因为用户已经将数据托管在自己的实例上——如果你不信任自己的实例，你不会在上面注册。Mastodon 验证了这个模型在数百万用户规模下是可行的。

### 10.2 Mastodon 联邦模型参考

Mastodon/ActivityPub 的联邦认证非常简洁：

1. **每个用户有密钥对**：服务端生成和保管（custodial trust model）
2. **公钥通过 Actor 文档发布**：远程实例通过 HTTP GET 获取
3. **跨实例请求用 HTTP Signatures 签名**：私钥签名请求头，远程实例用公钥验证
4. **信任基于域名**：你信任某个域名 → 信任该域名发布的公钥 → 信任该公钥签名的消息
5. **没有 CA、没有证书链**：比 X.509 简单得多

### 10.3 Fredica 联邦架构（预留）

```
实例 A (my-fredica.com)              实例 B (friend-fredica.com)
┌──────────────────────┐            ┌──────────────────────┐
│ alice (Ed25519 密钥对) │            │ bob (Ed25519 密钥对)   │
│ - 私钥：服务端保管     │            │ - 私钥：服务端保管     │
│ - 公钥：Actor 文档发布 │            │ - 公钥：Actor 文档发布 │
└──────────┬───────────┘            └──────────┬───────────┘
           │                                   │
           │  HTTP Signature 签名请求            │
           │ ─────────────────────────────────→ │
           │                                   │
           │  1. 从 keyId URL 获取 alice 公钥    │
           │  2. 验证签名                        │
           │  3. 检查 date ±5min                │
           │  4. 信任此请求来自 alice@实例A       │
           │                                   │
```

### 10.4 身份发现完整流程

联邦的基础是身份发现——一个实例如何找到并验证另一个实例上的用户。Fredica 采用与 Mastodon 相同的两步协议：

#### 10.4.1 信任链推导

```
域名信任（HTTPS）
  └→ 信任该域名返回的 WebFinger 响应
      └→ 信任 WebFinger 指向的 Actor URL
          └→ 信任 Actor 文档中的公钥
              └→ 信任该公钥验证通过的签名请求
```

**没有 CA、没有证书链、没有预共享密钥**。整个信任模型建立在 HTTPS + 域名之上——如果你信任 `instance-a.com` 这个域名（即它的 TLS 证书有效），你就信任它发布的所有公钥。这是 Mastodon 验证过的、足够简洁的信任模型。

#### 10.4.2 Fredica 身份发现实现（Phase 4）

**WebFinger 路由实现：**

```kotlin
// GET /.well-known/webfinger?resource=acct:alice@my-fredica.example.com
// requiresAuth = false（公开端点）
object WebFingerRoute : FredicaApi.Route {
    override val requiresAuth = false
    
    override suspend fun handler(body: String): String {
        val resource = query["resource"]?.firstOrNull()
            ?: return """{"error":"missing resource param"}"""
        
        // 解析 acct:alice@domain 格式
        val acctRegex = Regex("^acct:(.+)@(.+)$")
        val match = acctRegex.matchEntire(resource)
            ?: return """{"error":"invalid resource format"}"""
        val (username, domain) = match.destructured
        
        // 验证域名是否是本实例
        if (domain != instanceDomain) return """{"error":"unknown domain"}"""
        
        // 查 user 表
        val user = UserService.findByUsername(username)
            ?: return """{"error":"user not found"}"""
        
        // 返回 JRD (JSON Resource Descriptor)
        return buildJsonObject {
            put("subject", "acct:$username@$domain")
            putJsonArray("links") {
                addJsonObject {
                    put("rel", "self")
                    put("type", "application/activity+json")
                    put("href", "https://$domain/users/$username")
                }
            }
        }.toString()
    }
}
```

**Actor 路由实现：**

```kotlin
// GET /users/{username}
// requiresAuth = false（公开端点）
// Accept: application/activity+json 时返回 Actor JSON-LD
object ActorRoute : FredicaApi.Route {
    override val requiresAuth = false
    
    override suspend fun handler(body: String): String {
        val username = pathParams["username"]
            ?: return """{"error":"missing username"}"""
        val user = UserService.findByUsername(username)
            ?: return """{"error":"user not found"}"""
        
        return buildJsonObject {
            put("@context", "https://www.w3.org/ns/activitystreams")
            put("id", "https://$instanceDomain/users/$username")
            put("type", "Person")
            put("preferredUsername", username)
            put("name", user.displayName)
            put("inbox", "https://$instanceDomain/users/$username/inbox")
            put("outbox", "https://$instanceDomain/users/$username/outbox")
            putJsonObject("publicKey") {
                put("id", "https://$instanceDomain/users/$username#main-key")
                put("owner", "https://$instanceDomain/users/$username")
                put("publicKeyPem", user.publicKeyPem)
            }
        }.toString()
    }
}
```

#### 10.4.3 公钥缓存策略

远程实例获取到的公钥应当缓存，避免每次验证签名都发起 HTTP 请求：

```
收到签名请求，提取 keyId
  │
  ├── 本地缓存命中（federation_remote_user 表）
  │     ├── fetched_at < 24h → 直接用缓存公钥验证
  │     └── fetched_at ≥ 24h → 后台刷新，本次仍用缓存
  │
  └── 缓存未命中
        ├── GET Actor URL → 提取公钥
        ├── 写入 federation_remote_user 表
        └── 用公钥验证签名
```

- **缓存有效期**：24 小时内直接使用，超过后后台异步刷新
- **密钥轮换**：如果缓存公钥验证失败，立即重新获取 Actor 文档（可能是密钥轮换了）
- **存储位置**：`federation_remote_user` 表（见 §10.5）

### 10.5 联邦所需的路由与中间件（Phase 4 实现清单）

Phase 1 创建用户时已经生成了 Ed25519 密钥对并存储在 `user` 表中。联邦实现时只需：

| 组件 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| **WebFinger 路由** | `GET /.well-known/webfinger` | 无需鉴权 | `acct:` URI → Actor URL |
| **Actor 路由** | `GET /users/{username}` | 无需鉴权 | 返回 Actor JSON-LD（含 publicKeyPem） |
| **Inbox 路由** | `POST /users/{username}/inbox` | HTTP Signature | 接收远程实例的签名请求 |
| **HTTP Signature 验证中间件** | — | — | 验证入站请求签名（提取 keyId → 获取公钥 → 验签） |
| **HTTP Signature 签名发送器** | — | — | 出站请求时用用户私钥签名 |

### 10.6 联邦信任管理（预留）

```sql
-- 已知的远程实例
CREATE TABLE IF NOT EXISTS federation_instance (
    domain          TEXT PRIMARY KEY,
    status          TEXT NOT NULL DEFAULT 'allowed',  -- allowed / blocked
    first_seen_at   TEXT NOT NULL,
    last_seen_at    TEXT NOT NULL,
    note            TEXT NOT NULL DEFAULT ''
);

-- 已知的远程用户（缓存其公钥）
CREATE TABLE IF NOT EXISTS federation_remote_user (
    actor_url       TEXT PRIMARY KEY,         -- https://remote.com/users/bob
    domain          TEXT NOT NULL,
    username        TEXT NOT NULL,
    public_key_pem  TEXT NOT NULL,
    fetched_at      TEXT NOT NULL             -- 公钥获取时间（用于刷新）
);
```

---

## 十一、实施阶段

> 每个 Phase 内部按 Step 编号，Step 之间存在依赖关系（后续 Step 依赖前序 Step 的产物）。
> 每个 Step 标注了**涉及文件**、**产出物**和**验证方式**，便于逐步推进和 code review。

---

### Phase 1：Python 密码学服务

> **目标**：在 pyutil 中实现密码哈希、IMK 派生、密钥对生成等密码学原语，为后续 Kotlin 侧调用提供基础。
> **前置条件**：无（独立于其他 Phase）。

#### Step 1.1：添加 Python 依赖

添加 `argon2-cffi` 和 `cryptography` 到 pyutil 依赖。

| 涉及文件 | 操作 |
|---------|------|
| `desktop_assets/common/fredica-pyutil/requirements.txt` | 新增 `argon2-cffi>=23.1.0`、`cryptography>=44.0.0` |
| `desktop_assets/common/fredica-pyutil/pyproject.toml`（如有） | 同步更新 |

**验证**：`pip install -r requirements.txt` 成功，`python -c "import argon2; import cryptography"` 无报错。

#### Step 1.2：实现 crypto 路由

新建 `routes/crypto.py`，实现五个端点。遵循现有 pyutil 路由模式（`APIRouter` + `_router` 变量 + 文件名自动注册）。

| 端点 | 方法 | 功能 | 输入 | 输出 |
|------|------|------|------|------|
| `/crypto/hash-password/` | POST | Argon2id 密码哈希 | `{ password }` | `{ hash }` |
| `/crypto/verify-password/` | POST | 验证密码与哈希 | `{ password, hash }` | `{ valid: bool }` |
| `/crypto/derive-imk/` | POST | 从密码派生 IMK | `{ password, salt_imk_b64 }` | `{ imk_b64 }` |
| `/crypto/generate-keypair/` | POST | Ed25519 密钥对 + 私钥加密 | `{ imk_b64 }` | `{ public_key_pem, encrypted_private_key }` |
| `/crypto/reencrypt-private-key/` | POST | 用新 IMK 重加密私钥 | `{ encrypted_private_key, old_imk_b64, new_imk_b64 }` | `{ encrypted_private_key }` |

> `/crypto/sign/` 端点（联邦签名）在 Phase 4 实现，Phase 1 不需要。
> `/crypto/reencrypt-private-key/` 用于密码修改流程（§5.9），需在 Phase 1 一并实现。

| 涉及文件 | 操作 |
|---------|------|
| `desktop_assets/common/fredica-pyutil/fredica_pyutil_server/routes/crypto.py` | **新建** |

**实现要点**：
- 路由前缀：文件名 `crypto.py` → 自动注册为 `/crypto`
- Argon2id 参数：`time_cost=3, memory_cost=65536, parallelism=4`（与 §4.3.2 一致）
- `hash-password` 使用 `argon2-cffi` 高级 API（`PasswordHasher`），返回完整的 Argon2 编码字符串
- `verify-password` 使用 `PasswordHasher.verify()`，捕获 `VerifyMismatchError`
- `generate-keypair` 中私钥用 AES-256-GCM 加密后返回（格式见 §4.2.5）
- `reencrypt-private-key`：用 old_imk 解密 → 用 new_imk 重新加密，返回新密文（密码修改流程 §5.9 使用）
- 错误处理遵循项目约定：返回 `{"error": "..."}` 而非抛异常

**验证**：
- 启动 pyutil 服务，`curl` 调用各端点验证响应格式
- 编写 `pytest` 测试：`tests/test_crypto.py`

#### Step 1.3：Kotlin 侧 pyutil 调用封装

在 jvmMain 中封装 Python crypto 调用，提供类型安全的 Kotlin API。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/CryptoService.kt` | **新建** |

```kotlin
object CryptoService {
    /** 密码哈希（注册/修改密码时调用） */
    suspend fun hashPassword(password: String): String

    /** 验证密码（登录时调用） */
    suspend fun verifyPassword(password: String, hash: String): Boolean

    /** 派生 Instance Master Key */
    suspend fun deriveImk(password: String, saltImkB64: String): String

    /** 生成 Ed25519 密钥对（私钥已加密） */
    suspend fun generateKeypair(imkB64: String): KeypairResult

    /** 用新 IMK 重加密私钥（密码修改时调用，§5.9） */
    suspend fun reencryptPrivateKey(
        encryptedPrivateKey: String, oldImkB64: String, newImkB64: String
    ): String
}
```

**验证**：单元测试 `CryptoServiceTest.kt`（需 pyutil 服务运行）。

---

### Phase 2：数据库层

> **目标**：创建 `user` 表和 `auth_session` 表，实现 CRUD 操作。
> **前置条件**：Phase 1（密码哈希依赖 CryptoService）。

#### Step 2.1：定义数据模型（commonMain）

在 commonMain 中定义认证相关的数据类和枚举，供前后端共享。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../auth/AuthModels.kt` | **新建** |

**产出物**：
- `AuthRole` enum：`GUEST`, `TENANT`, `ROOT`（含 `operator fun compareTo`）
- `AuthIdentity` sealed class：`Guest`, `User(userId, username, displayName, role, permissions, sessionId)`
- `AuthSession` data class（对应 `auth_session` 表）
- `UserRecord` data class（对应 `user` 表，不含密码哈希等敏感字段的公开版本）

#### Step 2.2：实现 UserDb（jvmMain）

创建 `user` 表，实现用户 CRUD。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/UserDb.kt` | **新建** |

**DDL**（与 §6.1 一致）：
```sql
CREATE TABLE IF NOT EXISTS user (
    id              TEXT PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'tenant',
    permissions     TEXT NOT NULL DEFAULT '',
    public_key_pem  TEXT NOT NULL DEFAULT '',
    private_key_pem TEXT NOT NULL DEFAULT '',
    status          TEXT NOT NULL DEFAULT 'active',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL,
    last_login_at   TEXT,

    CHECK (length(username) >= 2 AND length(username) <= 32),
    CHECK (username GLOB '[a-zA-Z][a-zA-Z0-9_-]*'),
    CHECK (length(trim(display_name)) >= 1 AND length(display_name) <= 64),
    CHECK (role IN ('root', 'tenant')),
    CHECK (status IN ('active', 'disabled'))
);
```

**方法**：
- `initialize(db: Database)`：建表
- `createUser(username, displayName, passwordHash, role, publicKeyPem, encryptedPrivateKeyPem): String`（返回 userId）
- `findByUsername(username): UserEntity?`（大小写不敏感查询）
- `findById(userId): UserEntity?`
- `listAll(): List<UserEntity>`
- `updateStatus(userId, status)`
- `updatePassword(userId, newPasswordHash)`
- `updateDisplayName(userId, displayName)`
- `updateLastLoginAt(userId)`
- `hasAnyUser(): Boolean`（判断是否已初始化）

**验证**：`UserDbTest.kt`（SQLite 临时文件隔离）。

#### Step 2.3：实现 AuthSessionDb（jvmMain）

创建 `auth_session` 表，实现会话管理。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/AuthSessionDb.kt` | **新建** |

**DDL**（与 §6.2 一致）：
```sql
CREATE TABLE IF NOT EXISTS auth_session (
    session_id       TEXT PRIMARY KEY,
    token            TEXT NOT NULL UNIQUE,
    user_id          TEXT NOT NULL REFERENCES user(id),
    created_at       INTEGER NOT NULL,
    expires_at       INTEGER NOT NULL,
    last_accessed_at INTEGER NOT NULL,
    user_agent       TEXT NOT NULL DEFAULT '',
    ip_address       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_auth_session_token ON auth_session(token);
```

**方法**：
- `initialize(db: Database)`：建表 + 索引
- `createSession(userId, userAgent, ipAddress): AuthSessionEntity`（生成 token + sessionId，含并发上限 FIFO 淘汰 §5.5）
- `findByToken(token): AuthSessionEntity?`
- `findByUserId(userId): List<AuthSessionEntity>`
- `updateLastAccessed(sessionId)`
- `deleteBySessionId(sessionId)`
- `deleteByUserId(userId)`（登出所有设备）
- `deleteByUserIdExcept(userId, currentSessionId)`（密码修改时销毁其他 session §5.9）
- `deleteExpired()`（清理过期 session）

**会话策略**：
- token 生成：`java.security.SecureRandom` 生成 32 字节 → Base64 URL-safe 编码（43 字符）
- 默认有效期：7 天
- 滑动过期：每次访问更新 `last_accessed_at`，超过 24 小时无活动视为过期

**验证**：`AuthSessionDbTest.kt`（SQLite 临时文件隔离）。

#### Step 2.4：AppConfig 新增键

在 AppConfigDb 的默认 KV map 中新增认证相关配置。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../db/AppConfig.kt` | **修改**：默认 map 新增键 |

**新增键**：
- `"webserver_auth_token"` → `""`（首次初始化时生成随机 UUID）
- `"instance_initialized"` → `"false"`
- `"salt_imk_b64"` → `""`（IMK 派生盐，首次初始化时生成）
- `"salt_auth_b64"` → `""`（密码哈希盐，预留，Argon2 自带盐）

**验证**：现有 `AppConfigDb` 测试不受影响（`INSERT OR IGNORE` 不覆盖已有值）。

#### Step 2.5：注册到 FredicaApi 初始化链

将 UserDb 和 AuthSessionDb 加入 `FredicaApiJvmService.init()` 的初始化序列。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../api/FredicaApi.jvm.kt` | **修改**：`init()` 中新增 UserDb、AuthSessionDb、AuditLogDb 初始化 |

**位置**：在 AppConfigDb 之后、MaterialDb 之前初始化（认证表优先级高）。

**初始化顺序**：
1. `UserDb.initialize(db)` — 用户表（被 AuthSessionDb 外键引用）
2. `AuthSessionDb.initialize(db)` — 会话表
3. `AuditLogDb.initialize(db)` — 审计日志表

**验证**：`./gradlew :composeApp:run` 启动无报错，SQLite 中可见 `user`、`auth_session`、`audit_log` 三张新表。

---

### Phase 3：认证核心逻辑

> **目标**：重构 `checkAuth()` 为 `resolveIdentity()`，实现 token 解析和身份识别。
> **前置条件**：Phase 2（依赖 UserDb、AuthSessionDb）。

#### Step 3.1：实现 AuthService

集中管理认证逻辑：token 解析、session 验证、身份解析。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/AuthService.kt` | **新建** |

**核心方法**：

```kotlin
object AuthService {
    /** 内存缓存的 IMK（应用运行期间有效） */
    private var cachedImkB64: String? = null

    /** 从 Authorization header 解析身份 */
    suspend fun resolveIdentity(authHeader: String?): AuthIdentity?

    /** 密码登录 → 创建 session */
    suspend fun login(username: String, password: String, userAgent: String): LoginResult

    /** 登出 → 销毁 session */
    suspend fun logout(sessionId: String)

    /** 首次初始化：创建根用户 + 生成 webserver_auth_token */
    suspend fun initializeInstance(username: String, password: String): InitResult

    /** 检查实例是否已初始化 */
    suspend fun isInstanceInitialized(): Boolean
}
```

**`resolveIdentity` 逻辑**（与 §5.6 一致）：
1. 无 header → `null`
2. 不以 `Bearer ` 开头 → `null`
3. `Bearer fredica_session:<token>` → 查 AuthSessionDb → 有效则返回 `AuthIdentity.User`
4. `Bearer <其他>` → 匹配 AppConfig 中的 `webserver_auth_token` → 匹配则返回 `AuthIdentity.Guest`
5. 不匹配 → `null`

**验证**：`AuthServiceTest.kt`（集成测试，需 SQLite + pyutil）。

#### Step 3.2：重构 checkAuth → resolveIdentity

替换 `FredicaApi.jvm.kt` 中的 `checkAuth()` 为新的 `resolveIdentity()` 调用。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../api/FredicaApi.jvm.kt` | **修改**：`checkAuth()` → `resolveIdentity()`，`handleRoute()` 适配 |

**改动范围**：
- 删除旧 `checkAuth()` 函数（lines 430-437）
- `handleRoute()` 中：调用 `AuthService.resolveIdentity()`，将 `AuthIdentity` 存入 `call.attributes`
- 保持 `requiresAuth = false` 的路由不走认证（如 `ImageProxyRoute`）
- 新增 `AuthIdentityKey` attribute key

**向后兼容**：此步骤仅改变认证逻辑内部实现，不改变 Route 接口。所有现有路由的 `requiresAuth = true` 行为不变——只要携带有效的 `webserver_auth_token` 或 `session_token` 即可通过。

**验证**：
- 现有功能不受影响：携带旧 token 仍可访问所有路由
- 新增：`fredica_session:` 前缀的 token 可正确解析为 `AuthIdentity.User`

#### Step 3.3：实现认证 API 路由

新增认证相关的 HTTP 路由。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../api/routes/auth/AuthLoginRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/AuthLogoutRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/AuthMeRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/InstanceInitRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/InstanceStatusRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/all_routes.kt` | **修改**：注册新路由 |

**路由详情**：

| 路由 | 方法 | `requiresAuth` | 说明 |
|------|------|----------------|------|
| `InstanceStatusRoute` | GET | `false` | 返回 `{ initialized: bool }`，前端据此决定显示 setup 还是 login |
| `InstanceInitRoute` | POST | `false` | 首次初始化（创建根用户），仅 `instance_initialized == false` 时可用 |
| `AuthLoginRoute` | POST | `false` | 用户名/密码登录，返回 session_token + 用户信息 |
| `AuthLogoutRoute` | POST | `true` | 销毁当前 session |
| `AuthMeRoute` | GET | `true` | 返回当前用户信息（用于前端恢复登录状态） |

**`InstanceInitRoute` 流程**：
1. 检查 `instance_initialized`，已初始化则返回 400
2. 调用 `CryptoService.hashPassword()` 哈希密码
3. 生成 IMK 盐 → 调用 `CryptoService.deriveImk()` → 调用 `CryptoService.generateKeypair()`
4. 创建 user 记录（role=ROOT）
5. 生成 `webserver_auth_token`（UUID）写入 AppConfig
6. 设置 `instance_initialized = true`
7. 创建 session → 返回 session_token + 用户信息 + webserver_auth_token

**验证**：
- `AuthLoginRouteTest.kt`：正确登录、错误密码、不存在用户
- `InstanceInitRouteTest.kt`：首次初始化成功、重复初始化拒绝
- `InstanceStatusRouteTest.kt`：初始化前后状态变化

#### Step 3.4：GetServerInfoJsMessageHandler 改造

从 AppConfig 读取真实的 `webserver_auth_token`，替换硬编码 `"114514"`。

| 涉及文件 | 操作 |
|---------|------|
| `composeApp/src/commonMain/kotlin/.../appwebview/messages/GetServerInfoJsMessageHandler.kt` | **修改** |
| `composeApp/src/commonMain/kotlin/.../appwebview/messages/OpenBrowserJsMessageHandler.kt` | **修改** |

**改动**：
- `GetServerInfoJsMessageHandler`：`put("webserver_auth_token", "114514")` → `put("webserver_auth_token", AppConfigService.get("webserver_auth_token"))`
- `OpenBrowserJsMessageHandler`：同理

**验证**：WebView 环境启动后，前端通过 bridge 获取的 token 与 AppConfig 中一致。

#### Step 3.5：用户管理路由

新增用户管理相关的 HTTP 路由（与 §5.10 设计一致）。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserCreateRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserListRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserDisableRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserEnableRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserUpdateDisplayNameRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/all_routes.kt` | **修改**：注册新路由 |

**路由详情**：

| 路由 | 方法 | `requiresAuth` | `minRole` | 说明 |
|------|------|----------------|-----------|------|
| `UserCreateRoute` | POST | `true` | `ROOT` | 创建租户用户 |
| `UserListRoute` | GET | `true` | `ROOT` | 列出所有用户（排除敏感字段，含 last_login_at） |
| `UserDisableRoute` | POST | `true` | `ROOT` | 禁用用户 + 联动销毁 session |
| `UserEnableRoute` | POST | `true` | `ROOT` | 启用已禁用的用户 |
| `UserUpdateDisplayNameRoute` | POST | `true` | `TENANT` | 修改显示名（ROOT 可改他人） |

**`UserCreateRoute` 流程**：
1. 验证请求者为 ROOT
2. 校验 username（§5.3.1）：格式、长度、保留名单
3. 校验 display_name（§5.3.2）：trim 后 1-64 字符，无控制字符
4. 校验 password（§5.3.3）：长度 8-128、非全空白
5. 检查 username 唯一性（含已禁用用户）
6. 调用 `CryptoService.hashPassword()` 哈希密码
7. 派生 IMK → 调用 `CryptoService.generateKeypair()` 生成密钥对
8. 创建 user 记录（role=TENANT, status=active）
9. 写入审计日志（`USER_CREATED`，§5.13）
10. 返回 `{ user_id, username, display_name }`

**`UserListRoute` 响应字段**：
- 返回：`user_id`, `username`, `display_name`, `role`, `status`, `permissions`, `created_at`, `last_login_at`
- **排除**：`password_hash`, `private_key_pem`（敏感字段不暴露给前端）

**`UserDisableRoute` 流程**：
1. 验证请求者为 ROOT
2. 检查目标用户存在
3. 检查目标用户不是 ROOT 角色（ROOT 用户不可被禁用）
4. 检查操作者不是目标用户本人
5. 检查目标用户当前状态为 active
6. `UPDATE user SET status = 'disabled'`
7. `DELETE FROM auth_session WHERE user_id = ?`（联动销毁所有 session）
8. 写入审计日志（`USER_DISABLED`，含 sessions_destroyed_count）
9. 返回 `{ success: true }`

**`UserEnableRoute` 流程**：
1. 验证请求者为 ROOT
2. 检查目标用户存在且当前状态为 disabled
3. `UPDATE user SET status = 'active'`
4. 写入审计日志（`USER_ENABLED`）
5. 返回 `{ success: true }`

**`UserUpdateDisplayNameRoute` 流程**：
1. 校验 display_name（§5.3.2）
2. 若传了 `user_id`：检查操作者为 ROOT，且目标用户存在
3. 若未传 `user_id`：修改当前登录用户自己的显示名
4. `UPDATE user SET display_name = ?`
5. 写入审计日志（`DISPLAY_NAME_CHANGED`，含 old/new 值）
6. 返回 `{ success: true, display_name }`

**验证**：
- `UserCreateRouteTest.kt`：创建成功、用户名重复拒绝、非 ROOT 拒绝、用户名格式校验、保留名拒绝
- `UserListRouteTest.kt`：返回字段不含敏感信息、含 last_login_at
- `UserDisableRouteTest.kt`：禁用成功 + session 联动销毁、禁用 ROOT 拒绝、禁用自己拒绝、重复禁用拒绝
- `UserEnableRouteTest.kt`：启用成功、启用 active 用户拒绝、非 ROOT 拒绝
- `UserUpdateDisplayNameRouteTest.kt`：修改自己成功、ROOT 修改他人成功、TENANT 修改他人拒绝、格式校验

#### Step 3.6：密码修改路由

新增密码修改路由（与 §5.9 设计一致）。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../api/routes/auth/UserChangePasswordRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/all_routes.kt` | **修改**：注册新路由 |

**路由详情**：

| 路由 | 方法 | `requiresAuth` | `minRole` | 说明 |
|------|------|----------------|-----------|------|
| `UserChangePasswordRoute` | POST | `true` | `TENANT` | 修改自己的密码（TENANT 和 ROOT 均可） |

**流程**（与 §5.9 时序图一致）：
1. 从 `call.attributes` 获取当前用户身份
2. 校验 `newPassword`（§5.3.3）：长度 8-128、非全空白
3. 调用 `CryptoService.verifyPassword(oldPassword, user.passwordHash)` 验证旧密码
4. 调用 `CryptoService.hashPassword(newPassword)` 生成新哈希
5. 调用 `CryptoService.deriveImk(oldPassword, saltImkB64)` 获取旧 IMK
6. 调用 `CryptoService.deriveImk(newPassword, saltImkB64)` 获取新 IMK
7. 调用 `CryptoService.reencryptPrivateKey(encryptedPrivateKey, oldImk, newImk)` 重加密私钥
8. 事务内更新 `user` 表：`password_hash` + `private_key_pem`
9. 销毁该用户的所有其他 session（`AuthSessionDb.deleteByUserIdExcept(userId, currentSessionId)`），强制其他设备重新登录
10. 写入审计日志（`PASSWORD_CHANGED`，含 sessions_destroyed_count）
11. 返回 `{ success: true }`

**验证**：
- `UserChangePasswordRouteTest.kt`：修改成功、旧密码错误拒绝、新密码后可正常登录、旧密码不可登录、新密码格式校验

#### Step 3.7：登录频率限制

实现内存级登录频率限制，防止暴力破解（与 §5.12 设计一致）。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/LoginRateLimiter.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/AuthLoginRoute.kt` | **修改**：集成频率限制 |

**实现要点**：
- 使用 `ConcurrentHashMap<String, MutableList<Long>>` 记录失败时间戳
- 两个维度独立计数：
  - IP 维度：同一 IP 5 分钟内最多 10 次失败
  - 用户名维度：同一用户名 5 分钟内最多 5 次失败
- 任一维度超限 → 返回 429 Too Many Requests + `Retry-After` header
- 登录成功后清除该 IP + 用户名的失败记录
- 定期清理过期记录（每 10 分钟清理 5 分钟前的记录，防止内存泄漏）

```kotlin
object LoginRateLimiter {
    private val ipFailures = ConcurrentHashMap<String, MutableList<Long>>()
    private val usernameFailures = ConcurrentHashMap<String, MutableList<Long>>()

    /** 检查是否被限流，返回 null 表示允许，非 null 表示剩余等待秒数 */
    fun check(ip: String, username: String): Int?

    /** 记录一次失败 */
    fun recordFailure(ip: String, username: String)

    /** 登录成功后清除记录 */
    fun clearOnSuccess(ip: String, username: String)
}
```

**集成位置**：`AuthLoginRoute.handler()` 开头调用 `LoginRateLimiter.check()`，密码验证失败后调用 `recordFailure()`，成功后调用 `clearOnSuccess()`。

**验证**：
- `LoginRateLimiterTest.kt`：IP 超限返回 429、用户名超限返回 429、成功后清除、过期记录自动清理

#### Step 3.8：审计日志基础设施

实现审计日志写入和查询（与 §5.13 设计一致）。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/AuditLogDb.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/auth/AuditLogListRoute.kt` | **新建** |
| `shared/src/commonMain/kotlin/.../api/routes/all_routes.kt` | **修改**：注册新路由 |

**AuditLogDb 方法**：
- `initialize(db: Database)`：建表 + 索引（与 §6.3 DDL 一致）
- `insert(entry: AuditLogEntry)`：写入一条审计日志
- `insertAsync(entry: AuditLogEntry)`：异步写入（低优先级事件）
- `query(eventType?, userId?, startTime?, endTime?, page, size): Pair<List<AuditLogEntry>, Int>`
- `deleteOlderThan(timestamp: Long)`：清理过期记录（90 天保留策略）

**AuditLogListRoute**：
- 方法：GET
- `requiresAuth = true`，`minRole = ROOT`
- 支持 query 参数：`event_type`、`user_id`、`start_time`、`end_time`、`page`、`size`
- 返回 `{ items: AuditLogEntry[], total, page }`

**集成位置**：在 Step 3.3（AuthLoginRoute）、Step 3.5（用户管理路由）、Step 3.6（密码修改路由）中调用 `AuditLogDb.insert()`。

**验证**：
- `AuditLogDbTest.kt`：写入、查询、过期清理
- `AuditLogListRouteTest.kt`：ROOT 可查询、非 ROOT 拒绝、筛选条件生效

---

### Phase 4：路由权限分配

> **目标**：扩展 Route 接口支持角色和权限检查，为每个路由标注最低角色要求。
> **前置条件**：Phase 3（依赖 `resolveIdentity()` 和 `AuthIdentity`）。

#### Step 4.1：扩展 Route 接口

在 commonMain 的 `FredicaApi.Route` 接口中新增权限字段。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../api/FredicaApi.kt` | **修改**：Route 接口新增字段 |

**新增字段**（均有默认值，不破坏现有路由）：

```kotlin
interface Route {
    // ... 现有字段 ...

    /** 允许访问此路由的最低角色。默认 GUEST（向后兼容）。 */
    val minRole: AuthRole get() = AuthRole.GUEST

    /** 需要的额外权限标签（仅对 TENANT/ROOT 生效）。 */
    val requiredPermissions: Set<String> get() = emptySet()
}
```

**向后兼容**：所有现有路由默认 `minRole = GUEST`，行为与改造前一致（只要有 token 就能访问）。

#### Step 4.2：重构 handleRoute 权限检查

在 `handleRoute` 中加入角色和权限检查逻辑。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../api/FredicaApi.jvm.kt` | **修改**：`handleRoute()` 加入权限检查 |

**逻辑**（与 §5.8 一致）：
1. `requiresAuth = false` → 直接放行
2. `resolveIdentity()` → null → 401
3. 角色检查：`identityRole < route.minRole` → 403
4. 权限标签检查：`route.requiredPermissions - identity.permissions` 非空 → 403
5. 存入 `call.attributes` → 执行 handler

#### Step 4.3：逐个路由标注权限

为所有 65+ 路由标注 `minRole` 和 `requiredPermissions`。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/commonMain/kotlin/.../api/routes/` 下所有路由文件 | **修改**：添加 `minRole` 覆写 |

**权限分配原则**（与 §8.2 一致）：

| 类别 | minRole | requiredPermissions | 路由示例 |
|------|---------|---------------------|---------|
| 只读浏览 | `GUEST` | — | MaterialListRoute, MaterialDetailRoute, BilibiliVideoSubtitle* |
| 读写操作 | `TENANT` | — | MaterialImportRoute, MaterialCategoryCreateRoute |
| LLM 调用 | `TENANT` | `LLM_CALL` | LlmProxyChatRoute, PromptScriptGenerateRoute |
| ASR 任务 | `TENANT` | `ASR_TASK` | TranscribeRoute |
| 管理操作 | `ROOT` | — | AppConfigUpdateRoute |
| 用户管理 | `ROOT` | `ADMIN_USER_MGMT` | UserCreateRoute, UserDisableRoute |

**分批策略**：
- 第一批：所有路由保持 `GUEST`（与现状一致），确保不破坏功能
- 第二批：将写操作路由提升为 `TENANT`
- 第三批：将管理路由提升为 `ROOT`
- 每批之间运行全量测试

**验证**：
- 集成测试：游客访问 TENANT 路由 → 403
- 集成测试：租户访问 ROOT 路由 → 403
- 回归测试：根用户访问所有路由 → 正常

#### Step 4.4：MaterialVideoStreamRoute Cookie 认证改造

改造视频流路由的 Cookie 认证，支持新的 token 格式。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../api/routes/MaterialVideoStreamRoute.kt`（或其注册位置） | **修改** |

**改动**：
- 当前：Cookie `fredica_token` 存在即通过
- 改造后：Cookie 值必须匹配有效 token（`webserver_auth_token` 或 `fredica_session:<token>`）
- 复用 `AuthService.resolveIdentity()` 逻辑，传入 `Bearer ${cookieValue}` 格式

---

### Phase 5：前端认证 — 基础设施

> **目标**：扩展前端 AppConfig、改造 buildAuthHeaders、实现 401 全局处理。
> **前置条件**：Phase 3（后端 API 已就绪）。
> **注意**：Phase 5 和 Phase 4 可并行开发（前端不依赖路由权限分配）。

#### Step 5.1：添加前端依赖

添加密码强度校验库。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/package.json` | `npm install zxcvbn` |

**验证**：`import zxcvbn from "zxcvbn"` 可用。

#### Step 5.2：扩展 AppConfig 类型

在前端 AppConfig 中新增认证相关字段。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/context/appConfig.tsx` | **修改** |

**新增字段**（与 §7.10 一致）：

```typescript
interface AppConfig {
    // 现有字段（不变）
    webserver_domain: string | null;
    webserver_port: string | null;
    webserver_schema: WebserverSchema | null;
    webserver_auth_token: string | null;

    // 新增
    session_token: string | null;
    user_role: "guest" | "tenant" | "root" | null;
    user_display_name: string | null;
    user_permissions: string[] | null;
}
```

**Cookie 写入集中化**：改造 `setAppConfig`，在写 localStorage 的同时写 Cookie（与 §7.10 代码一致）。

**`defaultConfig` 更新**：新增字段默认值均为 `null`。

**验证**：现有功能不受影响（新字段均为 null，不影响现有逻辑）。

#### Step 5.3：新建 auth 工具模块

创建认证相关的类型定义和工具函数。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/util/auth.ts` | **新建** |

**产出物**：
- `AuthRole` 类型：`"guest" | "tenant" | "root"`
- `ROLE_LEVEL` 映射：`{ guest: 0, tenant: 1, root: 2 }`
- `meetsMinRole(currentRole, requiredRole): boolean`
- `isLoggedIn(appConfig): boolean`（有 session_token 或 webserver_auth_token）
- `isSessionUser(appConfig): boolean`（有 session_token，非游客）

#### Step 5.4：改造 buildAuthHeaders

重载 `buildAuthHeaders`，支持传入 AppConfig 对象（与 §7.11 一致）。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/util/app_fetch.ts` | **修改** |

**改动**：
- `buildAuthHeaders` 新增 `AppConfig` 对象重载
- `useAppFetch` 内部的 `fetchWithAuth` 改为传 `appConfig` 对象
- 现有 8 处直接传 `string` 的调用方**不需要修改**（向后兼容）

#### Step 5.5：401 全局处理

在 `useAppFetch` 中加入 401 检测和自动清除逻辑。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/util/app_fetch.ts` | **修改** |

**改动位置**：
- 声明式模式（`useEffect` 内）：`resp.ok` 检查之后，新增 `resp.status === 401` 分支
- 命令式模式（`apiFetch`）：同理

**401 处理逻辑**（与 §7.6 一致）：
1. 仅在 `session_token` 存在时触发（游客 401 不自动清除）
2. `setAppConfig({ session_token: null, user_role: null, user_display_name: null, user_permissions: null })`
3. `toast.warn("登录已过期，请重新登录")`
4. `window.location.href = "/login"`

**验证**：
- 单元测试：mock 401 响应 → 验证 appConfig 被清除
- 手动测试：session 过期后访问任意页面 → 自动跳转登录页

---

### Phase 6：前端认证 — 页面与组件

> **目标**：实现登录页、首次启动向导、RequireAuth 守卫、Sidebar 用户信息。
> **前置条件**：Phase 5（AppConfig 扩展、auth 工具模块）。

#### Step 6.1：实现 RequireAuth 守卫组件

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/components/auth/RequireAuth.tsx` | **新建** |

**实现**（与 §7.12 一致）：
- 未加载 → `<LoadingSpinner />`
- 未登录 → `<Navigate to="/login" replace />`
- 权限不足 → `<ForbiddenPage />`（内联简单 403 页面）
- 通过 → 渲染 `children`

**使用位置**：包裹 `_index.tsx` 中 ping 成功后的 `SidebarLayout`。

#### Step 6.2：实现登录页

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/routes/login.tsx` | **新建** |

**功能**（与 §7.3、§7.4、§7.8 一致）：
- 挂载时调 `GET /api/v1/InstanceStatusRoute`
  - 未初始化 → 跳转 `/setup`
  - 已有有效 session → 跳转 `/`
- 两个登录区域：
  - **密码登录**：用户名 + 密码（复用 `PasswordInput` 组件）→ `POST /api/v1/AuthLoginRoute`
  - **游客登录**：访问令牌 → ping 验证 → 写入 `webserver_auth_token`
- 登录成功后 `setAppConfig(...)` + `navigate("/")`
- 错误提示：用户名或密码错误、令牌无效

**前端输入校验**（提交前拦截，减少无效请求）：
- 用户名：非空即可提交（详细格式校验由后端 §5.3.1 执行，前端不重复正则）
- 密码：非空即可提交（后端 §5.3.3 校验长度和格式）
- 错误消息直接展示后端返回的 `error` 字段（如 `invalid_credentials`、`user_disabled`），**不区分"用户不存在"和"密码错误"**（§5.10 反枚举）

**UI 风格**：与现有 `_index.tsx` 连接表单保持一致（Tailwind CSS，居中卡片布局）。

#### Step 6.3：实现首次启动向导页

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/routes/setup.tsx` | **新建** |

**功能**（与 §7.5、§7.9 一致）：
- 挂载时调 `GET /api/v1/InstanceStatusRoute`
  - 已初始化 → 跳转 `/login`
- 表单：用户名 + 密码 + 确认密码
- 密码强度：`zxcvbn` 实时校验，score ≥ 3 才能提交
- 密码强度条：视觉反馈（颜色 + 文字）
- 提交 → `POST /api/v1/InstanceInitRoute`
- 成功后自动登录 + 跳转 `/`

**前端输入校验**（实时反馈，提交前拦截）：
- 用户名格式（§5.3.1）：`/^[a-zA-Z][a-zA-Z0-9_-]{1,31}$/` 实时校验，不匹配时禁用提交按钮并显示提示
- 密码长度（§5.3.3）：8-128 字符，不满足时禁用提交
- 密码确认：两次输入必须一致
- 密码强度：`zxcvbn` score ≥ 3（仅前端建议，后端不校验强度）
- 后端返回的错误（如 `username_taken`、`invalid_username_format`）展示在对应字段下方

#### Step 6.4：改造 _index.tsx

移除 Auth Token 表单字段，集成 RequireAuth 守卫。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/routes/_index.tsx` | **修改** |

**改动**：
- 移除 Auth Token 输入框（lines 163-172）
- ping 成功后包裹 `<RequireAuth>` → `<SidebarLayout>`
- 未登录时 RequireAuth 自动跳转 `/login`

#### Step 6.5：改造 Sidebar 用户信息

在 Sidebar 底部显示当前用户信息和登出按钮。

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/app/components/sidebar/Sidebar.tsx` | **修改** |

**改动**（与 §7.14 一致）：
- 取消注释底部 footer 区域（lines 201-215）
- 已登录用户：显示 `user_display_name` + 角色 badge + 登出按钮（`LogOut` 图标已 import）
- 游客：显示 "游客" 标签
- 登出：`POST /api/v1/AuthLogoutRoute` → 清除 appConfig → `navigate("/login")`

#### Step 6.6：前端测试

| 涉及文件 | 操作 |
|---------|------|
| `fredica-webui/tests/components/auth/RequireAuth.test.tsx` | **新建** |
| `fredica-webui/tests/routes/login.test.tsx` | **新建** |
| `fredica-webui/tests/routes/setup.test.tsx` | **新建** |
| `fredica-webui/tests/util/auth.test.ts` | **新建** |

**测试覆盖**：
- `RequireAuth`：未登录跳转、权限不足显示 403、正常渲染 children
- `login.tsx`：密码登录成功/失败、游客登录成功/失败、未初始化跳转 setup
- `setup.tsx`：密码强度校验、创建成功自动登录、已初始化跳转 login
- `auth.ts`：`meetsMinRole` 各种组合

---

### Phase 7：端到端集成与打磨

> **目标**：全链路验证、边界情况处理、文档更新。
> **前置条件**：Phase 4 + Phase 6 均完成。

#### Step 7.1：端到端测试场景

手动验证以下完整流程：

| # | 场景 | 预期结果 |
|---|------|---------|
| E1 | 全新实例首次启动 | 访问 `/` → ping → 未登录 → `/login` → 未初始化 → `/setup` → 创建根用户 → 自动登录 → 主界面 |
| E2 | 根用户密码登录 | `/login` → 输入用户名密码 → 登录成功 → 主界面 |
| E3 | 游客令牌登录 | `/login` → 输入访问令牌 → 验证通过 → 主界面（只读） |
| E4 | Session 过期 | 正常使用中 → 后端 session 过期 → 下次请求 401 → toast 提示 → 跳转 `/login` |
| E5 | 主动登出 | Sidebar 点击登出 → 清除 session → 跳转 `/login` |
| E6 | WebView 环境 | 桌面端启动 → bridge 获取 token → 自动以游客身份进入主界面 |
| E7 | 游客权限限制 | 游客访问写操作 → 403 → 提示权限不足 |
| E8 | 重复初始化防护 | 已初始化后访问 `/setup` → 跳转 `/login` |
| E9 | 视频流 Cookie 认证 | 登录后播放视频 → Cookie 携带正确 token → 视频正常加载 |
| E10 | 多标签页登出 | 标签页 A 登出 → 标签页 B 下次请求 401 → 跳转登录 |
| E11 | 禁用用户登录 | 用户被禁用后尝试登录 → 返回 `invalid_credentials`（与密码错误相同，§5.10 反枚举） |
| E12 | 用户名格式校验 | Setup 页输入非法用户名（如 `1abc`、`a`、含空格）→ 前端实时提示格式错误，提交按钮禁用 |
| E13 | 密码强度校验 | Setup 页输入弱密码（如 `12345678`）→ zxcvbn 评分 < 3 → 提交按钮禁用 |
| E14 | 并发会话上限 | 同一用户在 6 个浏览器登录 → 第 6 次登录成功，最早的 session 被踢出（FIFO，§5.4） |
| E15 | 审计日志记录 | 执行登录/登出/创建用户/禁用用户 → ROOT 查询 AuditLogListRoute → 对应事件已记录 |
| E16 | 修改密码后会话清除 | 用户修改密码 → 其他标签页/设备的 session 全部失效 → 401 → 跳转登录 |
| E17 | ROOT 自我禁用防护 | ROOT 尝试禁用自己 → 返回 `cannot_disable_self` 错误 |

#### Step 7.2：CORS 配置更新

确保 `/login`、`/setup` 页面的 API 请求不被 CORS 拦截。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../api/FredicaApi.jvm.kt` | **检查**：CORS 配置是否覆盖新路由 |

当前 CORS 配置（lines 309-332）允许 `localhost:7630`（React dev server），新增的认证路由应自动被覆盖。

#### Step 7.3：Session 与审计日志清理定时任务

定期清理过期的 session 记录和审计日志，避免表无限增长。

| 涉及文件 | 操作 |
|---------|------|
| `shared/src/jvmMain/kotlin/.../auth/AuthService.kt` | **修改**：添加定时清理 |

**策略**：应用启动时清理一次 + 每小时清理一次（`CoroutineScope` + `delay`）。

**清理内容**：
1. `AuthSessionDb.deleteExpired()` — 删除已过期的 session 记录
2. `AuditLogDb.deleteOlderThan(90 days)` — 删除超过 90 天的审计日志（§5.13 保留策略）

#### Step 7.4：文档更新

| 涉及文件 | 操作 |
|---------|------|
| `docs/dev/plans/user-auth-system.md` | **修改**：标注已完成的 Phase |
| `CLAUDE.md` | **修改**：更新工具函数速查（新增 AuthService、CryptoService） |

---

### Phase 8：联邦基础（未来）

> **前置条件**：Phase 1-7 全部完成。
> **优先级**：低，按需启动。

1. `/crypto/sign/` 端点（pyutil）：私钥解密 + Ed25519 签名
2. `WebFingerRoute`：`GET /.well-known/webfinger`（公开端点）
3. `ActorRoute`：`GET /users/{username}`（返回 Actor JSON-LD + 公钥）
4. HTTP Signatures 签名中间件（出站请求签名）
5. HTTP Signatures 验证中间件（入站请求验签）
6. `InboxRoute`：`POST /users/{username}/inbox`（接收远程实例消息）
7. `federation_instance` + `federation_remote_user` 表
8. 远程公钥缓存与刷新策略
9. 实例屏蔽/允许列表管理 UI

### Phase 9：联邦业务（未来）

> **前置条件**：Phase 8 完成。

1. 跨实例素材分享协议
2. 跨实例字幕协作
3. 跨实例用户关注与动态流

---

### 实施阶段依赖关系总览

```mermaid
graph LR
    P1[Phase 1<br/>Python 密码学] --> P2[Phase 2<br/>数据库层]
    P2 --> P3[Phase 3<br/>认证核心逻辑]
    P3 --> P4[Phase 4<br/>路由权限分配]
    P3 --> P5[Phase 5<br/>前端基础设施]
    P5 --> P6[Phase 6<br/>前端页面组件]
    P4 --> P7[Phase 7<br/>端到端集成]
    P6 --> P7
    P7 --> P8[Phase 8<br/>联邦基础]
    P8 --> P9[Phase 9<br/>联邦业务]

    style P1 fill:#e3f2fd
    style P2 fill:#e3f2fd
    style P3 fill:#e3f2fd
    style P4 fill:#fff3e0
    style P5 fill:#c8e6c9
    style P6 fill:#c8e6c9
    style P7 fill:#fce4ec
    style P8 fill:#f3e5f5
    style P9 fill:#f3e5f5
```

**并行机会**：
- Phase 4（路由权限）和 Phase 5-6（前端）可并行开发，两者仅在 Phase 7 汇合
- Phase 1（Python）可独立于其他所有 Phase 先行开发和测试

### 文件变更总览

| Phase | 新建文件 | 修改文件 |
|-------|---------|---------|
| **Phase 1** | `pyutil/routes/crypto.py`、`CryptoService.kt` | `requirements.txt` |
| **Phase 2** | `AuthModels.kt`、`UserDb.kt`、`AuthSessionDb.kt` | `AppConfig.kt`、`FredicaApi.jvm.kt` |
| **Phase 3** | `AuthService.kt`、5 个认证路由、6 个用户管理路由、1 个审计日志路由、`LoginRateLimiter.kt`、`AuditLogDb.kt` | `FredicaApi.jvm.kt`、`all_routes.kt`、2 个 JsMessageHandler、`AuthLoginRoute.kt` |
| **Phase 4** | — | `FredicaApi.kt`、`FredicaApi.jvm.kt`、65+ 路由文件 |
| **Phase 5** | `auth.ts` | `appConfig.tsx`、`app_fetch.ts`、`package.json` |
| **Phase 6** | `RequireAuth.tsx`、`login.tsx`、`setup.tsx`、4 个测试文件 | `_index.tsx`、`Sidebar.tsx` |
| **Phase 7** | — | `FredicaApi.jvm.kt`、`AuthService.kt`、文档 |
| **合计** | **~28 个新文件** | **~80+ 个修改文件**（含路由批量标注） |

### 预估工作量

| Phase | 预估 | 说明 |
|-------|------|------|
| Phase 1 | 0.5 天 | Python 路由 + Kotlin 封装，模式成熟 |
| Phase 2 | 0.5 天 | 数据库 CRUD，遵循现有 Db 模式 |
| Phase 3 | 2-3 天 | 核心认证逻辑 + 12 个路由 + 频率限制 + 审计日志 + 测试 |
| Phase 4 | 0.5-1 天 | 接口扩展简单，路由标注量大但机械 |
| Phase 5 | 0.5 天 | 前端基础设施改造 |
| Phase 6 | 1-2 天 | 两个新页面 + 组件改造 + 测试 |
| Phase 7 | 0.5-1 天 | 集成测试 + 边界情况 |
| **合计** | **5-8 天** | 不含联邦（Phase 8-9） |

### 测试通过计划

> 参考 [docs/dev/testing.md](../testing.md) 的约定，为每个 Phase 制定测试用例清单。
> 每个 Phase 的所有测试通过后方可进入下一 Phase。

#### 测试文件总览

```
desktop_assets/common/fredica-pyutil/tests/
└── test_crypto.py                          # Phase 1：Python 密码学端点

shared/src/jvmTest/kotlin/.../auth/
├── CryptoServiceTest.kt                    # Phase 1：Kotlin 封装
├── UserDbTest.kt                           # Phase 2：用户表 CRUD
├── AuthSessionDbTest.kt                    # Phase 2：会话表 CRUD
├── AuthServiceTest.kt                      # Phase 3：认证核心逻辑
├── LoginRateLimiterTest.kt                 # Phase 3：频率限制

shared/src/jvmTest/kotlin/.../api/routes/auth/
├── InstanceStatusRouteTest.kt              # Phase 3
├── InstanceInitRouteTest.kt                # Phase 3
├── AuthLoginRouteTest.kt                   # Phase 3
├── AuthMeRouteTest.kt                      # Phase 3
├── UserCreateRouteTest.kt                  # Phase 3
├── UserListRouteTest.kt                    # Phase 3
├── UserDisableRouteTest.kt                 # Phase 3
├── UserChangePasswordRouteTest.kt          # Phase 3
├── UserEnableRouteTest.kt                  # Phase 3
├── UserUpdateDisplayNameRouteTest.kt       # Phase 3
├── AuditLogDbTest.kt                       # Phase 3
├── AuditLogListRouteTest.kt                # Phase 3
└── RoutePermissionTest.kt                  # Phase 4：权限矩阵

fredica-webui/tests/
├── util/auth.test.ts                       # Phase 5：auth 工具函数
├── util/app_fetch_401.test.ts              # Phase 5：401 全局处理
├── components/auth/RequireAuth.test.tsx     # Phase 6：守卫组件
├── routes/login.test.tsx                   # Phase 6：登录页
└── routes/setup.test.tsx                   # Phase 6：首次启动向导
```

---

#### Phase 1 测试：Python 密码学服务

**文件**：`desktop_assets/common/fredica-pyutil/tests/test_crypto.py`

**运行命令**：
```shell
cd desktop_assets/common/fredica-pyutil
../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_crypto.py -v
```

| # | 用例 | 说明 |
|---|------|------|
| C1 | `test_hash_password_returns_argon2_format` | `/crypto/hash-password/` 返回 `$argon2id$` 前缀的编码字符串 |
| C2 | `test_verify_password_correct` | `/crypto/verify-password/` 正确密码 → `{ valid: true }` |
| C3 | `test_verify_password_wrong` | 错误密码 → `{ valid: false }` |
| C4 | `test_derive_imk_deterministic` | `/crypto/derive-imk/` 相同密码 + 相同 salt → 相同 IMK |
| C5 | `test_derive_imk_different_salt` | 不同 salt → 不同 IMK |
| C6 | `test_generate_keypair_format` | `/crypto/generate-keypair/` 返回 PEM 公钥 + Base64 加密私钥 |
| C7 | `test_generate_keypair_unique` | 两次调用生成不同密钥对 |
| C8 | `test_reencrypt_private_key_roundtrip` | `/crypto/reencrypt-private-key/` 旧 IMK 加密 → 新 IMK 重加密 → 新 IMK 可解密 |
| C9 | `test_reencrypt_private_key_wrong_old_imk` | 错误的 old_imk → 返回 `{ error: "..." }` |
| C10 | `test_hash_password_empty_string` | 空密码 → 正常返回哈希（不拒绝，由业务层校验） |

**mark 策略**：全部为纯逻辑测试（无 `@pytest.mark.network`），直接调用路由处理函数，不需要启动 FastAPI 服务。

**Mock 策略**：无需 mock，直接调用 `argon2-cffi` 和 `cryptography` 库。

---

**文件**：`shared/src/jvmTest/kotlin/.../auth/CryptoServiceTest.kt`

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.auth.CryptoServiceTest"
```

| # | 用例 | 说明 |
|---|------|------|
| CS1 | `hashPassword returns non-empty string` | 调用 pyutil → 返回 Argon2 哈希 |
| CS2 | `verifyPassword correct password returns true` | hash → verify 往返 |
| CS3 | `verifyPassword wrong password returns false` | 错误密码 → false |
| CS4 | `deriveImk deterministic` | 相同输入 → 相同 IMK |
| CS5 | `generateKeypair returns PEM and encrypted key` | 返回值格式校验 |
| CS6 | `reencryptPrivateKey roundtrip` | 旧 IMK → 新 IMK → 新 IMK 可用 |

**前置条件**：需 pyutil 服务运行。使用 `assumeTrue` 检测 pyutil 可达性，不可达时自动跳过。

**预期输出**：
```
 ✓ CryptoServiceTest (6 tests)
 ✓ test_crypto.py   (10 tests)
```

---

#### Phase 2 测试：数据库层

**文件**：`shared/src/jvmTest/kotlin/.../auth/UserDbTest.kt`

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.auth.UserDbTest"
```

| # | 用例 | 说明 |
|---|------|------|
| U1 | `createUser and findById returns correct entity` | 创建 → 查询 → 字段一致 |
| U2 | `findByUsername returns correct entity` | 按用户名查询 |
| U3 | `findByUsername nonexistent returns null` | 不存在的用户名 → null |
| U4 | `createUser duplicate username throws` | 重复用户名 → 异常 |
| U5 | `listAll returns all users` | 创建多个 → listAll 数量正确 |
| U6 | `updateStatus changes status` | active → disabled → 查询确认 |
| U7 | `updatePassword changes hash` | 更新密码哈希 → 新哈希生效 |
| U8 | `hasAnyUser empty database returns false` | 空表 → false |
| U9 | `hasAnyUser after create returns true` | 创建后 → true |
| U10 | `updateDisplayName changes display_name` | 更新显示名 → 查询确认新值生效 |
| U11 | `updateLastLoginAt changes timestamp` | 更新最后登录时间 → 查询确认时间戳变化 |

**隔离策略**：SQLite 临时文件（`File.createTempFile("test_user_", ".db").also { it.deleteOnExit() }`）。

---

**文件**：`shared/src/jvmTest/kotlin/.../auth/AuthSessionDbTest.kt`

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.auth.AuthSessionDbTest"
```

| # | 用例 | 说明 |
|---|------|------|
| S1 | `createSession and findByToken returns entity` | 创建 → token 查询 → 字段一致 |
| S2 | `findByToken nonexistent returns null` | 不存在的 token → null |
| S3 | `updateLastAccessed changes timestamp` | 更新 → last_accessed_at 变化 |
| S4 | `deleteBySessionId removes session` | 删除 → findByToken → null |
| S5 | `deleteByUserId removes all sessions` | 同一用户多个 session → 全部删除 |
| S6 | `deleteExpired removes only expired` | 创建过期 + 未过期 session → 只删过期的 |
| S7 | `createSession token is URL-safe Base64` | token 格式校验：43 字符、无 `+/=` |
| S8 | `createSession generates unique tokens` | 连续创建 100 个 → 全部唯一 |

**隔离策略**：SQLite 临时文件。需先初始化 `UserDb`（`auth_session.user_id` 外键依赖 `user` 表）。

**预期输出**：
```
 ✓ UserDbTest        (11 tests)
 ✓ AuthSessionDbTest (8 tests)
```

---

#### Phase 3 测试：认证核心逻辑

**文件**：`shared/src/jvmTest/kotlin/.../auth/AuthServiceTest.kt`

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.auth.AuthServiceTest"
```

| # | 用例 | 说明 |
|---|------|------|
| A1 | `resolveIdentity null header returns null` | 无 header → null |
| A2 | `resolveIdentity invalid format returns null` | `Basic xxx` → null |
| A3 | `resolveIdentity valid guest token returns Guest` | `Bearer <webserver_auth_token>` → `AuthIdentity.Guest` |
| A4 | `resolveIdentity wrong guest token returns null` | `Bearer wrong` → null |
| A5 | `resolveIdentity valid session token returns User` | `Bearer fredica_session:<token>` → `AuthIdentity.User` |
| A6 | `resolveIdentity expired session returns null` | 过期 session → null |
| A7 | `resolveIdentity disabled user returns null` | 用户 status=disabled → null |
| A8 | `login correct credentials returns session` | 正确用户名密码 → LoginResult.Success |
| A9 | `login wrong password returns failure` | 错误密码 → LoginResult.InvalidCredentials |
| A10 | `login nonexistent user returns failure` | 不存在用户 → LoginResult.InvalidCredentials |
| A11 | `login disabled user returns failure` | 禁用用户 → LoginResult.UserDisabled |
| A12 | `logout destroys session` | 登出 → session 不再有效 |
| A13 | `initializeInstance creates root user` | 首次初始化 → 根用户 + webserver_auth_token |
| A14 | `initializeInstance twice returns error` | 重复初始化 → 拒绝 |
| A15 | `isInstanceInitialized before and after init` | 初始化前 false → 初始化后 true |
| A16 | `resolveIdentity updates lastAccessed sliding window` | 访问 → last_accessed_at 更新 |

**前置条件**：需 pyutil 服务运行（密码哈希依赖 CryptoService）。`assumeTrue` 检测。

**隔离策略**：SQLite 临时文件，`@BeforeTest` 初始化 UserDb + AuthSessionDb + AppConfigDb。

---

**文件**：`shared/src/jvmTest/kotlin/.../auth/LoginRateLimiterTest.kt`

| # | 用例 | 说明 |
|---|------|------|
| R1 | `check allows first attempt` | 首次 → null（允许） |
| R2 | `check blocks after IP limit exceeded` | 同 IP 10 次失败 → 返回等待秒数 |
| R3 | `check blocks after username limit exceeded` | 同用户名 5 次失败 → 返回等待秒数 |
| R4 | `clearOnSuccess resets counters` | 成功后 → 计数器清零 |
| R5 | `expired records are cleaned up` | 5 分钟后 → 旧记录不再计数 |
| R6 | `different IPs are independent` | IP-A 超限不影响 IP-B |
| R7 | `different usernames are independent` | user-A 超限不影响 user-B |

**Mock 策略**：使用 fake timers（手动控制时间戳）测试过期清理，避免真实等待。

---

**路由测试文件**（`shared/src/jvmTest/kotlin/.../api/routes/auth/`）：

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.api.routes.auth.*"
```

**`InstanceStatusRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| IS1 | `returns initialized false before setup` | 空实例 → `{ initialized: false }` |
| IS2 | `returns initialized true after setup` | 初始化后 → `{ initialized: true }` |
| IS3 | `no auth required` | 无 Authorization header → 200（非 401） |

**`InstanceInitRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| II1 | `first init succeeds` | 创建根用户 → 200 + session_token + webserver_auth_token |
| II2 | `second init returns 400` | 重复初始化 → 400 |
| II3 | `init creates root role user` | 初始化后 UserDb 中有 role=ROOT 的用户 |
| II4 | `init sets instance_initialized to true` | AppConfig 中 `instance_initialized` = `"true"` |

**`AuthLoginRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| AL1 | `login with correct credentials returns session` | 200 + `{ session_token, user }` |
| AL2 | `login with wrong password returns 401` | 401 + `{ error }` |
| AL3 | `login with nonexistent user returns 401` | 401（不泄露用户是否存在） |
| AL4 | `login with disabled user returns 403` | 403 + `{ error }` |
| AL5 | `login rate limited returns 429` | 超限后 → 429 + `Retry-After` header |
| AL6 | `successful login clears rate limit` | 成功后 → 同 IP 可再次尝试 |

**`AuthMeRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| AM1 | `returns user info with valid session` | 200 + `{ user_id, username, role, permissions }` |
| AM2 | `returns guest info with guest token` | 200 + `{ role: "guest" }` |
| AM3 | `returns 401 without token` | 无 header → 401 |

**`UserCreateRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| UC1 | `root creates tenant user` | ROOT token → 200 + `{ user_id }` |
| UC2 | `duplicate username returns 409` | 重复用户名 → 409 |
| UC3 | `non-root rejected with 403` | TENANT token → 403 |
| UC4 | `guest rejected with 403` | guest token → 403 |

**`UserListRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| UL1 | `returns all users without sensitive fields` | 响应不含 `password_hash`、`private_key_pem` |
| UL2 | `non-root rejected` | TENANT token → 403 |

**`UserDisableRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| UD1 | `disable user succeeds` | status → disabled |
| UD2 | `disable cascades session deletion` | 被禁用用户的 session 全部删除 |
| UD3 | `disable root user rejected` | 不能禁用根用户 → 400 |
| UD4 | `non-root rejected` | TENANT token → 403 |

**`UserChangePasswordRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| CP1 | `change password succeeds` | 200 + `{ success: true }` |
| CP2 | `wrong old password returns 401` | 旧密码错误 → 401 |
| CP3 | `new password works for login` | 修改后 → 新密码可登录 |
| CP4 | `old password no longer works` | 修改后 → 旧密码不可登录 |
| CP5 | `private key re-encrypted correctly` | 修改后 → 新 IMK 可解密私钥 |

**`UserEnableRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| UE1 | `enable disabled user succeeds` | disabled → active，200 |
| UE2 | `enable already active user is idempotent` | active → active，200（幂等） |
| UE3 | `non-root rejected with 403` | TENANT token → 403 |
| UE4 | `enable nonexistent user returns 404` | 不存在的 user_id → 404 |

**`UserUpdateDisplayNameRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| DN1 | `update display_name succeeds` | 合法显示名 → 200 + 查询确认 |
| DN2 | `display_name with control chars returns 400` | 含零宽字符 → 400 |
| DN3 | `display_name too long returns 400` | 超过 64 字符 → 400 |
| DN4 | `empty display_name after trim returns 400` | 全空格 → trim 后为空 → 400 |
| DN5 | `non-root rejected with 403` | TENANT token → 403 |

**`AuditLogDbTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| AL_D1 | `writeLog and listByUser returns entry` | 写入 → 按 user_id 查询 → 字段一致 |
| AL_D2 | `listByUser pagination works` | 写入多条 → offset/limit 分页正确 |
| AL_D3 | `deleteOlderThan removes only old entries` | 创建新旧记录 → 只删除超龄记录 |
| AL_D4 | `all 12 event types can be written` | 遍历所有 AuditEventType → 均可写入和查询 |
| AL_D5 | `async batch write flushes correctly` | 批量异步写入 → 全部持久化 |

**`AuditLogListRouteTest.kt`**：

| # | 用例 | 说明 |
|---|------|------|
| ALR1 | `root can list audit logs` | ROOT token → 200 + 返回日志列表 |
| ALR2 | `non-root rejected with 403` | TENANT token → 403 |
| ALR3 | `pagination params work` | `?offset=0&limit=10` → 正确分页 |

**Phase 3 预期输出**：
```
 ✓ AuthServiceTest              (16 tests)
 ✓ LoginRateLimiterTest          (7 tests)
 ✓ InstanceStatusRouteTest       (3 tests)
 ✓ InstanceInitRouteTest         (4 tests)
 ✓ AuthLoginRouteTest            (6 tests)
 ✓ AuthMeRouteTest               (3 tests)
 ✓ UserCreateRouteTest           (4 tests)
 ✓ UserListRouteTest             (2 tests)
 ✓ UserDisableRouteTest          (4 tests)
 ✓ UserChangePasswordRouteTest   (5 tests)
 ✓ UserEnableRouteTest           (4 tests)
 ✓ UserUpdateDisplayNameRouteTest (5 tests)
 ✓ AuditLogDbTest                (5 tests)
 ✓ AuditLogListRouteTest         (3 tests)
```

---

#### Phase 4 测试：路由权限分配

**文件**：`shared/src/jvmTest/kotlin/.../api/routes/auth/RoutePermissionTest.kt`

**运行命令**：
```shell
./gradlew :shared:jvmTest --tests "com.github.project_fredica.api.routes.auth.RoutePermissionTest"
```

| # | 用例 | 说明 |
|---|------|------|
| P1 | `guest can access GUEST routes` | 游客 → MaterialListRoute → 200 |
| P2 | `guest cannot access TENANT routes` | 游客 → MaterialImportRoute → 403 |
| P3 | `guest cannot access ROOT routes` | 游客 → AppConfigUpdateRoute → 403 |
| P4 | `tenant can access GUEST routes` | 租户 → MaterialListRoute → 200 |
| P5 | `tenant can access TENANT routes` | 租户 → MaterialImportRoute → 200 |
| P6 | `tenant cannot access ROOT routes` | 租户 → UserCreateRoute → 403 |
| P7 | `root can access all routes` | 根用户 → 所有路由 → 200 |
| P8 | `tenant without LLM_CALL cannot access LLM routes` | 无权限标签 → LlmProxyChatRoute → 403 |
| P9 | `tenant with LLM_CALL can access LLM routes` | 有权限标签 → 200 |
| P10 | `no auth on requiresAuth=false routes` | ImageProxyRoute 无 header → 200 |
| P11 | `all routes have explicit minRole` | 反射遍历 `all_routes` → 每个路由的 `minRole` 非默认值（确认已标注） |
| P12 | `video stream cookie auth with session token` | Cookie `fredica_token=fredica_session:<token>` → 视频流 200 |
| P13 | `video stream cookie auth with guest token` | Cookie `fredica_token=<webserver_auth_token>` → 视频流 200 |

**测试策略**：
- 创建三种身份的 token（guest / tenant / root）作为 `@BeforeTest` fixture
- 每个用例直接调用 `handleRoute()` 或通过 Ktor `testApplication` 发 HTTP 请求
- P11 使用反射遍历 `all_routes` 列表，确保没有遗漏标注

**预期输出**：
```
 ✓ RoutePermissionTest (13 tests)
```

---

#### Phase 5 测试：前端基础设施

**文件**：`fredica-webui/tests/util/auth.test.ts`

**运行命令**：
```shell
cd fredica-webui && npx vitest run tests/util/auth.test.ts
```

| # | 用例 | 说明 |
|---|------|------|
| AU1 | `meetsMinRole guest >= guest` | `meetsMinRole("guest", "guest")` → true |
| AU2 | `meetsMinRole guest < tenant` | `meetsMinRole("guest", "tenant")` → false |
| AU3 | `meetsMinRole tenant >= tenant` | true |
| AU4 | `meetsMinRole root >= root` | true |
| AU5 | `meetsMinRole root >= guest` | true |
| AU6 | `isLoggedIn with session_token` | `{ session_token: "xxx" }` → true |
| AU7 | `isLoggedIn with webserver_auth_token only` | `{ webserver_auth_token: "xxx" }` → true |
| AU8 | `isLoggedIn with nothing` | 全 null → false |
| AU9 | `isSessionUser with session_token` | true |
| AU10 | `isSessionUser with only guest token` | false |

---

**文件**：`fredica-webui/tests/util/app_fetch_401.test.ts`

| # | 用例 | 说明 |
|---|------|------|
| F1 | `401 with session_token clears auth state` | mock 401 响应 + session_token 存在 → setAppConfig 被调用清除 session |
| F2 | `401 with session_token shows toast` | → `toast.warn` 被调用 |
| F3 | `401 with session_token redirects to login` | → `window.location.href` = `/login` |
| F4 | `401 without session_token does not clear` | 游客 401 → 不触发自动清除 |
| F5 | `non-401 error does not trigger auth clear` | 500 → 不触发 |

**Mock 策略**：

```typescript
// useAppConfig mock（与 testing.md 约定一致）
vi.mock("~/context/appConfig", () => ({
    useAppConfig: () => ({
        appConfig: {
            webserver_auth_token: "guest-token",
            session_token: "test-session",
            // ...
        },
        setAppConfig: vi.fn(),
        isStorageLoaded: true,
    }),
}));

// fetch mock
vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok: false, status: 401, text: () => Promise.resolve(""),
}));

// toast mock
vi.mock("react-toastify", () => ({
    toast: { warn: vi.fn() },
}));
```

**预期输出**：
```
 ✓ tests/util/auth.test.ts          (10 tests)
 ✓ tests/util/app_fetch_401.test.ts  (5 tests)
```

---

#### Phase 6 测试：前端页面与组件

**文件**：`fredica-webui/tests/components/auth/RequireAuth.test.tsx`

**运行命令**：
```shell
cd fredica-webui && npx vitest run tests/components/auth/RequireAuth.test.tsx
```

| # | 用例 | 说明 |
|---|------|------|
| RA1 | `renders children when logged in` | session_token 存在 → children 可见 |
| RA2 | `redirects to login when not logged in` | 全 null → Navigate to="/login" |
| RA3 | `shows loading when storage not loaded` | isStorageLoaded=false → LoadingSpinner |
| RA4 | `shows 403 when role insufficient` | guest + minRole=tenant → ForbiddenPage |
| RA5 | `guest with guest token can access guest routes` | webserver_auth_token 存在 + minRole=guest → children 可见 |

**Mock 策略**：`vi.mock("~/context/appConfig")` 按用例切换 appConfig 值；`vi.mock("react-router")` mock `Navigate` 组件。

---

**文件**：`fredica-webui/tests/routes/login.test.tsx`

| # | 用例 | 说明 |
|---|------|------|
| L1 | `redirects to setup when not initialized` | InstanceStatusRoute → `{ initialized: false }` → Navigate to="/setup" |
| L2 | `redirects to home when already logged in` | 有效 session → Navigate to="/" |
| L3 | `password login success` | 填写用户名密码 → 提交 → setAppConfig + navigate("/") |
| L4 | `password login wrong credentials shows error` | 401 → 错误提示可见 |
| L5 | `guest login success` | 填写访问令牌 → ping 验证 → setAppConfig |
| L6 | `guest login invalid token shows error` | ping 失败 → 错误提示可见 |
| L7 | `password field toggles visibility` | 点击眼睛图标 → type 切换 |

**Mock 策略**：`vi.stubGlobal("fetch")` 按 URL 路径返回不同响应；`vi.mock("react-router")` mock `useNavigate`。

---

**文件**：`fredica-webui/tests/routes/setup.test.tsx`

| # | 用例 | 说明 |
|---|------|------|
| SP1 | `redirects to login when already initialized` | InstanceStatusRoute → `{ initialized: true }` → Navigate to="/login" |
| SP2 | `submit disabled when password too weak` | zxcvbn score < 3 → 提交按钮 disabled |
| SP3 | `submit disabled when passwords mismatch` | 密码 ≠ 确认密码 → disabled |
| SP4 | `password strength bar updates` | 输入密码 → 强度条颜色/文字变化 |
| SP5 | `successful setup auto-login` | 提交 → InstanceInitRoute 200 → setAppConfig + navigate("/") |
| SP6 | `setup failure shows error` | InstanceInitRoute 400 → 错误提示可见 |

**Mock 策略**：`vi.mock("zxcvbn")` 控制返回的 score 值；fetch mock 同上。

**Phase 6 预期输出**：
```
 ✓ tests/components/auth/RequireAuth.test.tsx  (5 tests)
 ✓ tests/routes/login.test.tsx                 (7 tests)
 ✓ tests/routes/setup.test.tsx                 (6 tests)
```

---

#### Phase 7 测试：端到端集成

Phase 7 以**手动验证**为主（见 Step 7.1 的 E1-E17 场景表），不新增自动化测试文件。

**全量回归命令**：

```shell
# 1. Python 测试
cd desktop_assets/common/fredica-pyutil
../../windows/lfs/python-314-embed/python.exe -m pytest tests/test_crypto.py -v

# 2. Kotlin 全量测试（含认证相关 + 现有测试）
./gradlew :shared:jvmTest --rerun-tasks

# 3. 前端全量测试
cd fredica-webui && npm test

# 4. 类型检查
cd fredica-webui && npx tsc --noEmit
```

**Phase 7 通过标准**：

| 检查项 | 通过条件 |
|--------|---------|
| Python 测试 | `test_crypto.py` 10 tests passed |
| Kotlin 测试 | 全部 pass（含新增 ~109 tests + 现有 ~40 tests） |
| 前端测试 | 全部 pass（含新增 ~33 tests + 现有 ~26 tests） |
| 前端类型检查 | `tsc --noEmit` 无错误 |
| E2E 手动验证 | E1-E17 全部通过 |

**预期最终输出**：

```
# Python
 ✓ test_crypto.py (10 tests)

# Kotlin
 ✓ CryptoServiceTest              (6 tests)
 ✓ UserDbTest                    (11 tests)
 ✓ AuthSessionDbTest              (8 tests)
 ✓ AuthServiceTest               (16 tests)
 ✓ LoginRateLimiterTest           (7 tests)
 ✓ InstanceStatusRouteTest        (3 tests)
 ✓ InstanceInitRouteTest          (4 tests)
 ✓ AuthLoginRouteTest             (6 tests)
 ✓ AuthMeRouteTest                (3 tests)
 ✓ UserCreateRouteTest            (4 tests)
 ✓ UserListRouteTest              (2 tests)
 ✓ UserDisableRouteTest           (4 tests)
 ✓ UserChangePasswordRouteTest    (5 tests)
 ✓ UserEnableRouteTest            (4 tests)
 ✓ UserUpdateDisplayNameRouteTest (5 tests)
 ✓ AuditLogDbTest                 (5 tests)
 ✓ AuditLogListRouteTest          (3 tests)
 ✓ RoutePermissionTest           (13 tests)
 + 现有测试全部通过

# 前端
 ✓ tests/util/auth.test.ts                    (10 tests)
 ✓ tests/util/app_fetch_401.test.ts             (5 tests)
 ✓ tests/components/auth/RequireAuth.test.tsx    (5 tests)
 ✓ tests/routes/login.test.tsx                  (7 tests)
 ✓ tests/routes/setup.test.tsx                  (6 tests)
 + 现有测试全部通过

 Test Files  24 new + existing all passed
       Tests  ~152 new tests
```

---

## 十二、安全考量

### 12.1 威胁模型

| 威胁 | 缓解措施 |
|------|---------|
| 密码暴力破解 | Argon2id 高内存参数 + 登录频率限制 |
| Session 劫持 | HTTPS（远程部署）/ localhost（本地）；session token 通过 `Authorization` header 传输，不存入 Cookie；`fredica_token` Cookie 仅用于 `<video src>` 等无法设置 header 的场景，由 JS 写入（无法 HttpOnly），但 `SameSite=Strict` 防 CSRF |
| 重放攻击（联邦） | HTTP Signature 中 date ±5 分钟窗口 |
| 伪造签名（联邦） | 从 keyId URL 获取公钥验证，域名必须匹配 |
| 私钥泄露（磁盘） | AES-256-GCM 加密存储 + IMK 派生自根用户密码（§4.2.5）；可选 OS 密钥库（§4.2.6） |
| 私钥泄露（内存） | 不做过度设计——若攻击者能读进程内存，OS 已沦陷，应用层擦除无实际收益 |
| XSS 窃取 token | session token 存 localStorage（非 Cookie），XSS 可读取；缓解：CSP 策略 + 输入消毒；`fredica_token` Cookie 设 `SameSite=Strict` 防 CSRF |

### 12.2 与 Mastodon 模型的对齐

| 方面 | Mastodon | Fredica |
|------|---------|---------|
| 密钥托管 | 服务端（数据库） | 服务端（数据库，`user` 表 `private_key_pem` 字段） |
| 密钥算法 | RSA-2048 | Ed25519（更现代） |
| 跨实例认证 | HTTP Signatures | HTTP Signatures（预留） |
| 身份发现 | WebFinger + Actor JSON-LD | WebFinger + Actor JSON（预留） |
| 本地登录 | 邮箱/密码 + OAuth | 用户名/密码 |
| 信任模型 | 域名即信任根 | 域名即信任根 |

### 12.3 各防护层 vs 恶意软件能力矩阵

实际威胁不是抽象的"攻击者"，而是**具体能力等级的恶意软件**。下面按恶意软件的权限/能力从低到高分级，分析每层防护的实际效果。

#### 恶意软件能力分级

| 等级 | 名称 | 典型场景 | 具备的能力 |
|------|------|---------|-----------|
| **T0** | 物理/离线访问 | U 盘拷走数据库文件、网盘备份泄露、电脑送修 | 只能读到磁盘上的文件，无法执行代码 |
| **T1** | 其他用户进程 | 同一台电脑的其他 Windows/macOS 用户运行的程序 | 以另一个 OS 用户身份运行，受文件权限限制 |
| **T2** | 同用户非特权进程 | 用户安装的流氓浏览器插件、捆绑软件、广告SDK | 以当前用户身份运行，可读该用户所有文件，但无 admin/root 权限 |
| **T3** | 同用户特权进程 | 用户点了"以管理员身份运行"的恶意程序、提权漏洞 | admin/root 权限，可读所有文件、注入其他进程、安装驱动 |
| **T4** | 内核级 | Rootkit、恶意驱动、Bootkit | 内核态执行，可绕过所有用户态保护，隐藏自身 |

#### 防护矩阵

| | T0 物理/离线 | T1 其他用户 | T2 同用户非特权 | T3 同用户特权 | T4 内核级 |
|---|:---:|:---:|:---:|:---:|:---:|
| **Layer 0**<br>文件权限 | — | ✅ 防住 | ❌ 同用户可读 | ❌ | ❌ |
| **Layer 1**<br>IMK 加密 | ✅ 防住 | ✅ 防住 | ⚠️ 部分 | ❌ | ❌ |
| **Layer 2**<br>OS 密钥库 | ✅ 防住 | ✅ 防住 | ⚠️ 部分 | ❌ | ❌ |
| **内存中的 IMK** | ✅ 不在磁盘 | ✅ 进程隔离 | ⚠️ 见下文 | ❌ | ❌ |

#### 逐级详解

**T0 物理/离线访问** — Layer 1 是核心防线

攻击者拿到了数据库文件（`.db`），但无法运行代码。

- Layer 0（文件权限）：**无效**。文件已经在攻击者手上，权限不适用。
- Layer 1（IMK 加密）：**有效**。私钥字段是 AES-256-GCM 密文，没有根用户密码就无法派生 IMK，无法解密。攻击者需要暴力破解 Argon2id，高内存参数（64MB+）使 GPU 并行攻击成本极高。
- Layer 2（OS 密钥库）：**有效**。IMK 是随机生成的 32 字节密钥（不是从密码派生），即使密码弱也无法暴力破解。但 Layer 2 是 Phase 3+ 才实现的。

**这是 Fredica 最常见的威胁场景**（U 盘拷贝、网盘备份、电脑送修），Layer 1 已经覆盖。

**T1 其他 OS 用户** — Layer 0 就够了

同一台电脑的其他用户账户运行的程序。

- Layer 0（文件权限）：**有效**。`chmod 600` / `icacls` 限制了只有当前用户可读数据库文件。
- Layer 1 + Layer 2：**有效**，但 Layer 0 已经足够。

**T2 同用户非特权进程** — 最复杂的场景

这是**最现实也最危险**的威胁等级。用户日常使用电脑时，流氓软件以同一用户身份运行：

- Layer 0（文件权限）：**无效**。同用户进程天然有权读取该用户的所有文件。
- Layer 1（IMK 加密）：**部分有效**。
  - ✅ 如果恶意软件只是**静态读取文件**（拷走数据库），密文无法解密。
  - ❌ 如果恶意软件能**监控进程通信**，可以拦截 Kotlin↔Python 之间传递的 IMK（通过 HTTP localhost:7632）。
  - ❌ 如果恶意软件能**读取进程内存**（`ReadProcessMemory` / `/proc/pid/mem`），可以从 Fredica 进程的堆中提取 IMK。注意：在 Windows 上，同用户非特权进程默认就有 `PROCESS_VM_READ` 权限，**不需要管理员权限**。
- Layer 2（OS 密钥库）：**部分有效**。
  - ✅ 防止静态文件窃取（IMK 不在文件系统上）。
  - ❌ **DPAPI 的致命弱点**：Windows Credential Manager 底层使用 DPAPI，而 DPAPI 的解密密钥派生自当前用户的登录凭据。**同用户进程可以直接调用 `CryptUnprotectData()` 解密任何该用户存储的 DPAPI 数据**。这就是为什么 Chrome 的 Cookie 加密（也用 DPAPI）能被 infostealer 恶意软件轻松窃取。Chrome 在 2024 年引入了 App-Bound Encryption 试图缓解，但很快被绕过。
  - ❌ macOS Keychain 稍好：首次访问会弹出授权对话框，但恶意软件可以通过 AppleScript 或 Accessibility API 自动点击"允许"。

**结论**：对于 T2 级别的恶意软件，Layer 1 和 Layer 2 都只能防"拷走文件后离线分析"的场景，**无法防止运行时窃取**。这不是 Fredica 的设计缺陷，而是操作系统安全模型的固有限制——同用户进程之间没有强隔离。

**T2.5 UAC 提权：被用户亲手放行的恶意软件** — 现实中 T2→T3 的主要通道

严格来说 T2 和 T3 之间有一道 UAC（User Account Control）屏障，但在实际家用电脑场景中，这道屏障**形同虚设**：

- **UAC 疲劳**：普通用户每天面对大量 UAC 弹窗（安装软件、更新驱动、修改设置），早已养成"条件反射式点击'是'"的习惯。恶意软件只需要触发一次 UAC 弹窗，用户大概率会放行。
- **社工伪装**：恶意软件可以伪装成"显卡驱动更新"、"系统补丁"、"游戏运行库"等用户认为合理的提权请求。用户看到弹窗上写着看似正常的程序名，几乎不会拒绝。
- **捆绑安装链**：用户在安装某个正常软件时，安装器本身就需要管理员权限。捆绑在其中的恶意组件**继承了安装器的特权上下文**，甚至不需要单独触发 UAC。
- **UAC 绕过漏洞**：Windows 历史上有大量 UAC bypass 技术（auto-elevate 白名单滥用、DLL 劫持、COM 对象劫持等），部分至今仍可用。恶意软件可以完全静默提权，用户毫无感知。

**这意味着什么**：对于 Fredica 的典型用户（非安全专业人员的家用电脑用户），**T2 和 T3 之间的边界几乎不存在**。一个 T2 级别的恶意软件想要升级到 T3，成本极低。因此：

- Layer 2（OS 密钥库）在理论上比 Layer 1 多了一层 DPAPI 保护，但 T3 恶意软件可以直接 dump LSASS 获取 DPAPI master key，或者以 SYSTEM 身份调用 `CryptUnprotectData()`，效果等价于无保护。
- 即使引入更高级的保护（如 TPM 绑定），T3 恶意软件也可以通过注入 Fredica 进程本身来"借用"已解密的密钥。

**对设计的影响**：不要假设 UAC 能阻止恶意软件获得管理员权限。在家用电脑场景下，**T2 的实际威胁等级应按 T3 来评估**。这进一步确认了"不做过度设计"的正确性——如果 T2≈T3，那么 Layer 1 和 Layer 2 对运行时攻击的防护差异就更不重要了，两者的核心价值都是防 T0（离线窃取）。

**T3 特权进程** — 全面沦陷

获得管理员/root 权限后，恶意软件可以：

- 读取任何用户的任何文件（Layer 0 无效）
- 读取任何进程的内存（`SeDebugPrivilege`），提取运行时的 IMK（Layer 1 无效）
- 以 SYSTEM 身份调用 DPAPI 解密，或直接 dump DPAPI master key（Layer 2 无效）
- 注入 DLL 到 Fredica 进程，直接调用已加载的解密函数
- 安装服务/计划任务实现持久化
- Hook 系统 API（`NtReadFile`、`CryptUnprotectData`）拦截所有加解密操作

**T4 内核级** — 超出讨论范围

- 内核态执行，可绕过所有用户态保护机制，隐藏自身进程和文件
- 现代 Windows 有 Secure Boot + 驱动签名强制 + HVCI 等缓解措施，但这属于 OS 安全范畴
- **任何应用层防护在 T3/T4 面前都是无效的**

#### 实际收益总结

```
                    T0 离线    T1 他用户   T2 同用户   T3 特权    T4 内核
                   ─────────  ─────────  ─────────  ────────  ────────
无防护（明文存储）    泄露       泄露       泄露       泄露      泄露
+ Layer 0 权限       泄露       安全       泄露       泄露      泄露
+ Layer 1 IMK        安全       安全       运行时泄露  泄露      泄露
+ Layer 2 OS密钥库   安全       安全       运行时泄露  泄露      泄露
```

**Layer 1 的核心价值**：防 T0（离线/物理访问），这是 Fredica 家用电脑场景下最常见的威胁。

**Layer 2 相对 Layer 1 的增量价值**：
- 消除了对用户密码强度的依赖（随机 IMK vs 密码派生 IMK）
- 但对 T2 运行时攻击的防护并没有本质提升（DPAPI 同用户可解密）

**不做过度设计的理由再次确认**：T2 以上的威胁需要的是**操作系统级别的防护**（杀毒软件、沙箱、进程隔离），不是应用层能解决的。Fredica 的防护重心放在 T0/T1 是正确的。

---

## 十三、关键设计决策 FAQ

### Q1：为什么不用 X.509 证书链？

之前的设计考虑过 X.509，但分析后发现：
- Mastodon 证明了**不需要 CA 和证书链**也能做联邦——HTTP Signatures + 域名信任就够了
- X.509 增加了大量复杂度（CA 管理、证书签发/吊销/续期），对 Fredica 的场景没有额外收益
- 用户不需要管理证书文件，降低了 UX 负担

### Q2：为什么不用 WebAuthn？

WebAuthn 的 `rpId` 绑定域名，用户在实例 A 注册的 Passkey 无法在实例 B 使用。
对于联邦场景（自部署实例，域名各不相同），WebAuthn 无法解决跨实例身份问题。
但 WebAuthn 可以作为**本地登录的可选增强**（替代密码），Phase 3+ 可以加入。

### Q3：为什么服务端持有用户私钥？

这是 Mastodon 的标准做法（custodial trust model）。理由：
- 用户已经信任了自己注册的实例（否则不会注册）
- 用户不需要管理密钥文件，UX 简单
- 联邦签名由服务端自动完成，用户无感知
- 如果用户想迁移到其他实例，可以导出数据（包括密钥对），与 Mastodon 的 account export 类似

### Q4：webserver_auth_token 还有必要保留吗？

保留。它作为最轻量的访问方式，适合"分享链接给朋友看个视频"的场景。不是所有访问者都需要注册账户。

### Q5：密码哈希为什么用 Python 而不是 Kotlin？

项目已有 pyutil 子进程框架，`argon2-cffi` 是 Python 生态中最成熟的 Argon2 实现。
Kotlin/JVM 也有 Bouncy Castle 可以做，但复用现有 Python 基础设施更简单。
如果性能成为瓶颈（登录是低频操作，通常不会），可以迁移到 Kotlin 侧。

---

## 十四、后续补丁（Phase 1-7 完成后）

> Phase 1-7 全部实施完成后，在实际使用中发现的遗留问题和增强需求。

### 14.1 Step 8.1：游客令牌验证 + 401 处理 + 管理 UI <Badge type="tip" text="已完成" />

#### 问题清单

| # | 问题 | 根因 |
|---|------|------|
| Bug 1 | 游客令牌验证完全失效 | `login.tsx` 的 `handleGuestLogin` 调用 `/api/v1/ping` 验证令牌，但 ping 不检查 auth，任何字符串都能通过 |
| Bug 2 | 游客 401 无感知 | `app_fetch.ts` 的 401 处理只检查 `session_token`，游客（`webserver_auth_token`）收到 401 时无 toast、无跳转 |
| Bug 3 | 游客令牌管理 UI 缺失 | `_index.tsx` 的 Guest Token 字段仅在连接表单中（`pingStatus !== "ok"` 时显示），连接成功后不可见 |

#### 已实施变更

**Step 8.1.5：后端 — AuthGuestValidateRoute**

新建 `shared/.../api/routes/AuthGuestValidateRoute.kt`：
- `mode = Get`，`minRole = GUEST`
- 有效 token → `{ valid: true, role: "guest" }`
- 无效 token → `checkAuth()` 返回 401
- 已注册到 `all_routes.kt`

**Step 8.1.6：前端 — login.tsx 使用 AuthGuestValidateRoute**

`handleGuestLogin` 中将 `/api/v1/ping` 替换为 `/api/v1/AuthGuestValidateRoute`，逻辑不变。

**Step 8.1.7：前端 — app_fetch.ts 401 处理覆盖游客令牌**

- Session 用户 401：保持原有行为（清除 session_token + toast "登录已过期" + 跳转 /login）
- 游客 401：不自动跳转，弹出交互式 toast（`guest_401_toast.tsx`）
  - "去登录" → `window.location.href = "/login"`
  - "不，且本次访问不再弹出" → 写入 `sessionStorage`
- 去重：模块级 `_shown` 标志 + `sessionStorage("fredica_guest_401_dismissed")`
- 桌面端（`isBridgeAvailable()`）：直接跳过 toast，桌面用户即服务主人

新建文件：`fredica-webui/app/util/guest_401_toast.tsx`

**Step 8.1.8：前端 — _index.tsx ServerSettingsPanel + Sidebar 主页入口**

- `_index.tsx`：`pingStatus === "ok"` 分支渲染 `ServerSettingsPanel` 组件
  - 显示当前连接信息 + "断开连接"按钮
  - ROOT 或桌面端（`isBridgeAvailable()`）：显示游客令牌管理区域（GET/POST `WebserverAuthTokenRoute`）
- `Sidebar.tsx`：添加"主页"入口（`Home` 图标，`routeTo='/'`）

**Step 8.1.9：bridge.ts — isBridgeAvailable() 封装**

新增 `isBridgeAvailable()` 同步工具函数到 `fredica-webui/app/util/bridge.ts`：
```typescript
export function isBridgeAvailable(): boolean {
    return typeof window !== "undefined" && !!window.kmpJsBridge;
}
```

所有需要检测桌面环境的位置统一使用此函数，不再散落 `window.kmpJsBridge` 裸检查：
- `guest_401_toast.tsx`：桌面端跳过游客 401 toast
- `_index.tsx` `ServerSettingsPanel`：`isRoot = appConfig.user_role === "root" || isBridgeAvailable()`

#### 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `shared/.../routes/AuthGuestValidateRoute.kt` | **新建** | 游客令牌验证路由 |
| `shared/.../routes/all_routes.kt` | **修改** | 注册 AuthGuestValidateRoute |
| `fredica-webui/app/routes/login.tsx` | **修改** | handleGuestLogin 改用 AuthGuestValidateRoute |
| `fredica-webui/app/util/app_fetch.ts` | **修改** | 401 处理覆盖游客令牌 |
| `fredica-webui/app/util/guest_401_toast.tsx` | **新建** | 游客 401 交互式 toast + 桌面端禁用 |
| `fredica-webui/app/util/bridge.ts` | **修改** | 新增 `isBridgeAvailable()` |
| `fredica-webui/app/routes/_index.tsx` | **修改** | ServerSettingsPanel + isBridgeAvailable |
| `fredica-webui/app/components/sidebar/Sidebar.tsx` | **修改** | 添加"主页"入口 |

---

### 14.2 邀请链接管理功能

> 设计目标：为 ROOT 管理员提供两类邀请链接的完整 CRUD 管理能力，并为被邀请者提供对应的前端落地页。

#### 14.2.1 功能概述

| 链接类型 | 目的 | 访问者行为 | 管理能力 |
|----------|------|-----------|----------|
| **游客邀请链接** | 分享只读访问入口 | 访问落地页 → 自动记录 IP/时间 → 跳转到主应用（以游客身份） | 查看访问统计、启用/禁用链接 |
| **租户邀请链接** | 邀请他人注册账户 | 访问落地页 → 填写注册表单 → 创建租户账户 → 自动登录 | 设置失效时间、邀请人数上限、启用/禁用、查看已注册租户 |

两类链接完全独立，各有独立的数据表。

#### 14.2.2 数据库设计

##### 表 1：`guest_invite_link`（游客邀请链接）

```sql
CREATE TABLE IF NOT EXISTS guest_invite_link (
    id          TEXT PRIMARY KEY,                -- UUID
    path_id     TEXT NOT NULL UNIQUE,             -- URL 路径标识（短码，如 "abc123"）
    label       TEXT NOT NULL DEFAULT '',         -- 管理员备注（如 "分享给同学"）
    status      TEXT NOT NULL DEFAULT 'active',   -- 'active' | 'disabled'
    created_by  TEXT NOT NULL REFERENCES user(id),-- 创建者（ROOT）
    created_at  TEXT NOT NULL,                    -- ISO 8601
    updated_at  TEXT NOT NULL,

    CHECK (status IN ('active', 'disabled')),
    CHECK (length(path_id) >= 4 AND length(path_id) <= 64),
    CHECK (path_id GLOB '[a-zA-Z0-9_-]*')
);
```

##### 表 2：`guest_invite_visit`（游客访问记录）

```sql
CREATE TABLE IF NOT EXISTS guest_invite_visit (
    id          TEXT PRIMARY KEY,                -- UUID
    link_id     TEXT NOT NULL REFERENCES guest_invite_link(id),
    ip_address  TEXT NOT NULL DEFAULT '',
    user_agent  TEXT NOT NULL DEFAULT '',
    visited_at  TEXT NOT NULL,                   -- ISO 8601

    -- 不设 UNIQUE 约束：同一 IP 多次访问均记录
);
CREATE INDEX IF NOT EXISTS idx_guest_invite_visit_link_id ON guest_invite_visit(link_id);
CREATE INDEX IF NOT EXISTS idx_guest_invite_visit_visited_at ON guest_invite_visit(visited_at);
```

##### 表 3：`tenant_invite_link`（租户邀请链接）

```sql
CREATE TABLE IF NOT EXISTS tenant_invite_link (
    id          TEXT PRIMARY KEY,                -- UUID
    path_id     TEXT NOT NULL UNIQUE,             -- URL 路径标识（短码）
    label       TEXT NOT NULL DEFAULT '',         -- 管理员备注
    status      TEXT NOT NULL DEFAULT 'active',   -- 'active' | 'disabled'
    max_uses    INTEGER NOT NULL,                 -- 最大邀请人数（≥1）
    expires_at  TEXT NOT NULL,                    -- 失效时间，ISO 8601（必须设置）
    created_by  TEXT NOT NULL REFERENCES user(id),
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,

    CHECK (status IN ('active', 'disabled')),
    CHECK (max_uses >= 1),
    CHECK (length(path_id) >= 4 AND length(path_id) <= 64),
    CHECK (path_id GLOB '[a-zA-Z0-9_-]*')
);
```

##### 表 4：`tenant_invite_registration`（通过邀请链接注册的租户记录）

```sql
CREATE TABLE IF NOT EXISTS tenant_invite_registration (
    id          TEXT PRIMARY KEY,                -- UUID
    link_id     TEXT NOT NULL REFERENCES tenant_invite_link(id),
    user_id     TEXT NOT NULL REFERENCES user(id),-- 注册创建的用户
    ip_address  TEXT NOT NULL DEFAULT '',
    user_agent  TEXT NOT NULL DEFAULT '',
    registered_at TEXT NOT NULL,                  -- ISO 8601

    UNIQUE (link_id, user_id)                    -- 同一链接同一用户只能注册一次
);
CREATE INDEX IF NOT EXISTS idx_tenant_invite_reg_link_id ON tenant_invite_registration(link_id);
```

##### 表关系图

```
guest_invite_link  1 ──── N  guest_invite_visit
                              （访问记录）

tenant_invite_link 1 ──── N  tenant_invite_registration
                              （注册记录，关联 user 表）
                                    │
                                    N
                                    │
                              user  1
```

##### 可用性判定逻辑

**游客邀请链接**：`status === 'active'`

**租户邀请链接**：
```
可用 = status === 'active'
     AND now() < expires_at
     AND COUNT(tenant_invite_registration WHERE link_id = ?) < max_uses
```

#### 14.2.3 Kotlin 模型定义（commonMain）

新建 `shared/src/commonMain/kotlin/com/github/project_fredica/auth/InviteModels.kt`：

```kotlin
package com.github.project_fredica.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- 游客邀请链接 --

@Serializable
data class GuestInviteLink(
    val id: String,
    @SerialName("path_id") val pathId: String,
    val label: String = "",
    val status: String = "active",
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // 聚合字段（查询时填充）
    @SerialName("visit_count") val visitCount: Int = 0,
)

@Serializable
data class GuestInviteVisit(
    val id: String,
    @SerialName("link_id") val linkId: String,
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("visited_at") val visitedAt: String,
)

// -- 租户邀请链接 --

@Serializable
data class TenantInviteLink(
    val id: String,
    @SerialName("path_id") val pathId: String,
    val label: String = "",
    val status: String = "active",
    @SerialName("max_uses") val maxUses: Int,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // 聚合字段（查询时填充）
    @SerialName("used_count") val usedCount: Int = 0,
)

@Serializable
data class TenantInviteRegistration(
    val id: String,
    @SerialName("link_id") val linkId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("ip_address") val ipAddress: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("registered_at") val registeredAt: String,
    // 联查字段（可选填充）
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)
```

##### Repo 接口

```kotlin
// GuestInviteLinkRepo
interface GuestInviteLinkRepo {
    suspend fun initialize()
    suspend fun create(pathId: String, label: String, createdBy: String): String
    suspend fun findById(id: String): GuestInviteLink?
    suspend fun findByPathId(pathId: String): GuestInviteLink?
    suspend fun listAll(): List<GuestInviteLink>  // 含 visit_count 聚合
    suspend fun updateStatus(id: String, status: String)
    suspend fun updateLabel(id: String, label: String)
    suspend fun delete(id: String)
}

// GuestInviteVisitRepo
interface GuestInviteVisitRepo {
    suspend fun initialize()
    suspend fun record(linkId: String, ipAddress: String, userAgent: String): String
    suspend fun listByLinkId(linkId: String, limit: Int = 100, offset: Int = 0): List<GuestInviteVisit>
    suspend fun countByLinkId(linkId: String): Int
}

// TenantInviteLinkRepo
interface TenantInviteLinkRepo {
    suspend fun initialize()
    suspend fun create(pathId: String, label: String, maxUses: Int, expiresAt: String, createdBy: String): String
    suspend fun findById(id: String): TenantInviteLink?
    suspend fun findByPathId(pathId: String): TenantInviteLink?
    suspend fun listAll(): List<TenantInviteLink>  // 含 used_count 聚合
    suspend fun updateStatus(id: String, status: String)
    suspend fun updateLabel(id: String, label: String)
    suspend fun delete(id: String)
    /** 检查链接是否可用（active + 未过期 + 未满额） */
    suspend fun isUsable(pathId: String): Boolean
}

// TenantInviteRegistrationRepo
interface TenantInviteRegistrationRepo {
    suspend fun initialize()
    suspend fun record(linkId: String, userId: String, ipAddress: String, userAgent: String): String
    suspend fun listByLinkId(linkId: String): List<TenantInviteRegistration>  // 含 username/display_name 联查
    suspend fun countByLinkId(linkId: String): Int
}
```

##### Service 单例

```kotlin
object GuestInviteLinkService {
    private var _repo: GuestInviteLinkRepo? = null
    val repo: GuestInviteLinkRepo get() = _repo ?: error("GuestInviteLinkService 未初始化")
    fun initialize(repo: GuestInviteLinkRepo) { _repo = repo }
}

object GuestInviteVisitService {
    private var _repo: GuestInviteVisitRepo? = null
    val repo: GuestInviteVisitRepo get() = _repo ?: error("GuestInviteVisitService 未初始化")
    fun initialize(repo: GuestInviteVisitRepo) { _repo = repo }
}

object TenantInviteLinkService {
    private var _repo: TenantInviteLinkRepo? = null
    val repo: TenantInviteLinkRepo get() = _repo ?: error("TenantInviteLinkService 未初始化")
    fun initialize(repo: TenantInviteLinkRepo) { _repo = repo }
}

object TenantInviteRegistrationService {
    private var _repo: TenantInviteRegistrationRepo? = null
    val repo: TenantInviteRegistrationRepo get() = _repo ?: error("TenantInviteRegistrationService 未初始化")
    fun initialize(repo: TenantInviteRegistrationRepo) { _repo = repo }
}
```

#### 14.2.4 后端 API 路由

##### 游客邀请链接管理（ROOT）

| 路由 | Method | minRole | 说明 |
|------|--------|---------|------|
| `GuestInviteLinkListRoute` | GET | ROOT | 列出所有游客邀请链接（含 visit_count） |
| `GuestInviteLinkCreateRoute` | POST | ROOT | 创建游客邀请链接（参数：label, path_id?） |
| `GuestInviteLinkUpdateRoute` | POST | ROOT | 更新链接（参数：id, label?, status?） |
| `GuestInviteLinkDeleteRoute` | POST | ROOT | 删除链接及其访问记录（参数：id） |
| `GuestInviteVisitListRoute` | GET | ROOT | 查看某链接的访问记录（参数：link_id, limit?, offset?） |

##### 租户邀请链接管理（ROOT）

| 路由 | Method | minRole | 说明 |
|------|--------|---------|------|
| `TenantInviteLinkListRoute` | GET | ROOT | 列出所有租户邀请链接（含 used_count） |
| `TenantInviteLinkCreateRoute` | POST | ROOT | 创建租户邀请链接（参数：label, path_id?, max_uses, expires_at） |
| `TenantInviteLinkUpdateRoute` | POST | ROOT | 更新链接（参数：id, label?, status?） |
| `TenantInviteLinkDeleteRoute` | POST | ROOT | 删除链接（参数：id）；已有注册记录时拒绝删除 |
| `TenantInviteRegistrationListRoute` | GET | ROOT | 查看某链接的注册记录（参数：link_id） |

##### 公开路由（无需认证）

| 路由 | Method | requiresAuth | 说明 |
|------|--------|-------------|------|
| `GuestInviteLandingRoute` | GET | **false** | 游客落地页数据（参数：path_id）→ 记录访问 + 返回连接信息 |
| `TenantInviteLandingRoute` | GET | **false** | 租户落地页数据（参数：path_id）→ 返回链接状态（可用/已满/已过期/已禁用） |
| `TenantInviteRegisterRoute` | POST | **false** | 通过邀请链接注册（参数：path_id, username, password, display_name?） |

##### 路由实现要点

**GuestInviteLinkCreateRoute**：
```kotlin
object GuestInviteLinkCreateRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.ROOT

    @Serializable
    data class Param(
        val label: String = "",
        @SerialName("path_id") val pathId: String = "",  // 留空则自动生成
    )

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrThrow()
        val pathId = p.pathId.ifBlank { generateShortId(8) }
        // 校验 pathId 格式 + 唯一性
        val identity = RouteAuthContext.current() as? AuthIdentity.User
            ?: return buildJsonObject { put("error", "未登录") }.toValidJson()
        val id = GuestInviteLinkService.repo.create(
            pathId = pathId, label = p.label, createdBy = identity.userId
        )
        return buildJsonObject {
            put("success", true)
            put("id", id)
            put("path_id", pathId)
        }.toValidJson()
    }
}
```

**GuestInviteLandingRoute**（公开，无需认证）：
```kotlin
object GuestInviteLandingRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST

    override suspend fun handler(param: String): ValidJsonString {
        val query = param.loadJson().asT<Map<String, List<String>>>()
        val pathId = query["path_id"]?.firstOrNull()
            ?: return buildJsonObject { put("error", "缺少 path_id") }.toValidJson()

        val link = GuestInviteLinkService.repo.findByPathId(pathId)
            ?: return buildJsonObject { put("error", "链接不存在") }.toValidJson()

        if (link.status != "active") {
            return buildJsonObject { put("error", "链接已禁用") }.toValidJson()
        }

        // 记录访问（IP/UA 由 Ktor 请求上下文提供）
        val ip = RouteRequestContext.clientIp() ?: ""
        val ua = RouteRequestContext.userAgent() ?: ""
        GuestInviteVisitService.repo.record(link.id, ip, ua)

        // 返回连接信息（webserver_auth_token 由 InstanceStatus 提供）
        return buildJsonObject {
            put("success", true)
            put("label", link.label)
        }.toValidJson()
    }
}
```

**TenantInviteRegisterRoute**（公开，无需认证）：
```kotlin
object TenantInviteRegisterRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val requiresAuth = false
    override val minRole = AuthRole.GUEST

    @Serializable
    data class Param(
        @SerialName("path_id") val pathId: String,
        val username: String,
        val password: String,
        @SerialName("display_name") val displayName: String = "",
    )

    override suspend fun handler(param: String): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrThrow()

        // 1. 查找链接
        val link = TenantInviteLinkService.repo.findByPathId(p.pathId)
            ?: return buildJsonObject { put("error", "邀请链接不存在") }.toValidJson()

        // 2. 检查可用性
        if (link.status != "active") {
            return buildJsonObject { put("error", "邀请链接已禁用") }.toValidJson()
        }
        if (Instant.parse(link.expiresAt) <= Clock.System.now()) {
            return buildJsonObject { put("error", "邀请链接已过期") }.toValidJson()
        }
        val usedCount = TenantInviteRegistrationService.repo.countByLinkId(link.id)
        if (usedCount >= link.maxUses) {
            return buildJsonObject { put("error", "邀请链接已达使用上限") }.toValidJson()
        }

        // 3. 创建用户（复用 AuthService）
        val result = AuthServiceHolder.instance.createUser(
            username = p.username,
            displayName = p.displayName.ifBlank { p.username },
            password = p.password,
        )
        if (!result.success || result.user == null) {
            return buildJsonObject { put("error", result.error ?: "创建用户失败") }.toValidJson()
        }

        // 4. 记录注册
        val ip = RouteRequestContext.clientIp() ?: ""
        val ua = RouteRequestContext.userAgent() ?: ""
        TenantInviteRegistrationService.repo.record(link.id, result.user.id, ip, ua)

        // 5. 自动登录（创建 session）
        val loginResult = AuthServiceHolder.instance.login(
            username = p.username, password = p.password,
            userAgent = ua, ipAddress = ip,
        )

        return buildJsonObject {
            put("success", true)
            put("token", loginResult.token)
            put("user", AppUtil.GlobalVars.json.encodeToJsonElement(loginResult.user))
        }.toValidJson()
    }
}
```

##### 请求上下文扩展

需要在 `FredicaApi.jvm.kt` 的 Ktor 路由处理中，将客户端 IP 和 User-Agent 注入到 `RouteRequestContext`（新增 ThreadLocal 或 CoroutineContext Element），使 commonMain 路由可以访问：

```kotlin
// 新增 RouteRequestContext（commonMain）
object RouteRequestContext {
    private val _clientIp = ThreadLocal<String?>()
    private val _userAgent = ThreadLocal<String?>()

    fun clientIp(): String? = _clientIp.get()
    fun userAgent(): String? = _userAgent.get()

    fun <T> withContext(clientIp: String?, userAgent: String?, block: () -> T): T {
        _clientIp.set(clientIp)
        _userAgent.set(userAgent)
        try { return block() }
        finally { _clientIp.remove(); _userAgent.remove() }
    }
}
```

在 Ktor 路由处理中包裹：
```kotlin
RouteRequestContext.withContext(
    clientIp = call.request.origin.remoteAddress,
    userAgent = call.request.userAgent(),
) {
    route.handler(paramJson)
}
```

> **注意**：`RouteAuthContext` 已有类似的 ThreadLocal 模式，`RouteRequestContext` 遵循相同设计。

#### 14.2.5 前端路由重构

##### 当前结构

```
app/routes/
├── admin.users.tsx          → /admin/users
```

##### 目标结构

```
app/routes/
├── admin.tsx                → /admin（布局组件，含子路由 Outlet）
├── admin.users.tsx          → /admin/users（用户管理，原有功能）
├── admin.invites.tsx        → /admin/invites（邀请链接管理，新增）
├── invite.guest.$pathId.tsx → /invite/guest/:pathId（游客落地页，公开）
├── invite.tenant.$pathId.tsx→ /invite/tenant/:pathId（租户注册落地页，公开）
```

React Router v7 `flatRoutes()` 约定：
- `admin.tsx` 作为 `/admin` 的布局路由（渲染 `<Outlet />`）
- `admin.users.tsx` 和 `admin.invites.tsx` 作为子路由自动嵌套
- `invite.guest.$pathId.tsx` 和 `invite.tenant.$pathId.tsx` 是独立路由

##### `admin.tsx`（新建 — 管理后台布局）

```tsx
import { Outlet, NavLink } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { RequireAuth } from "~/components/auth/RequireAuth";

function AdminNav() {
    const linkClass = (isActive: boolean) =>
        `px-4 py-2 text-sm font-medium rounded-lg transition-colors ${
            isActive
                ? "bg-slate-100 text-slate-900"
                : "text-slate-500 hover:text-slate-700 hover:bg-slate-50"
        }`;

    return (
        <div className="flex gap-1 px-6 pt-6 pb-2">
            <NavLink to="/admin/users" className={({ isActive }) => linkClass(isActive)}>
                用户管理
            </NavLink>
            <NavLink to="/admin/invites" className={({ isActive }) => linkClass(isActive)}>
                邀请链接
            </NavLink>
        </div>
    );
}

export default function AdminLayout() {
    return (
        <RequireAuth minRole="root">
            <SidebarLayout>
                <AdminNav />
                <Outlet />
            </SidebarLayout>
        </RequireAuth>
    );
}
```

##### `admin.users.tsx` 改造

移除外层的 `<RequireAuth>` 和 `<SidebarLayout>` 包裹（已由 `admin.tsx` 布局提供），只保留内容部分：

```tsx
// 之前
export default function AdminUsersPage() {
    return (
        <RequireAuth minRole="root">
            <SidebarLayout>
                <AdminUsersContent />
            </SidebarLayout>
        </RequireAuth>
    );
}

// 之后
export default function AdminUsersPage() {
    return <AdminUsersContent />;
}
```

##### Sidebar 更新

将侧边栏的"用户管理"入口从 `/admin/users` 改为 `/admin/users`（不变），但确保 `admin.tsx` 布局中的 tab 导航能正确高亮。

#### 14.2.6 前端页面设计

##### A. 邀请链接管理页（`admin.invites.tsx`）

**页面结构**：

```
┌─────────────────────────────────────────────────────┐
│  [用户管理]  [邀请链接]  ← admin.tsx 提供的 tab 导航  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  游客邀请链接                          [+ 新建]      │
│  ┌─────────────────────────────────────────────┐    │
│  │ 标签        路径ID      访问次数  状态  操作  │    │
│  │ 分享给同学  abc123      42       ●活跃  ⋯   │    │
│  │ 测试链接    test01       3       ○禁用  ⋯   │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  租户邀请链接                          [+ 新建]      │
│  ┌─────────────────────────────────────────────┐    │
│  │ 标签    路径ID   已用/上限  失效时间  状态 操作│    │
│  │ 团队A   team-a   2/5      2026-05-01 ●活跃 ⋯│    │
│  │ 临时    tmp123   1/1      2026-04-20 ●已满 ⋯│    │
│  │ 过期    old456   0/3      2026-03-01 ○过期 ⋯│    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**操作菜单（⋯）**：
- 复制链接：复制完整的邀请 URL 到剪贴板
- 查看详情：展开访问记录/注册记录
- 编辑标签
- 启用/禁用
- 删除（租户链接有注册记录时不可删除）

**状态徽章**：
- `●活跃`（绿色）：status=active 且未过期且未满额
- `○禁用`（灰色）：status=disabled
- `○过期`（橙色）：已过期（仅租户链接）
- `●已满`（红色）：已达使用上限（仅租户链接）

**新建游客邀请链接对话框**：
```
┌──────────────────────────────────┐
│  新建游客邀请链接                  │
│                                  │
│  备注标签                         │
│  ┌──────────────────────────┐    │
│  │ 分享给同学                │    │
│  └──────────────────────────┘    │
│                                  │
│  路径 ID（留空自动生成）           │
│  ┌──────────────────────────┐    │
│  │ abc123                   │    │
│  └──────────────────────────┘    │
│                                  │
│           [取消]  [创建]          │
└──────────────────────────────────┘
```

**新建租户邀请链接对话框**：
```
┌──────────────────────────────────┐
│  新建租户邀请链接                  │
│                                  │
│  备注标签                         │
│  ┌──────────────────────────┐    │
│  │ 团队 A 成员               │    │
│  └──────────────────────────┘    │
│                                  │
│  路径 ID（留空自动生成）           │
│  ┌──────────────────────────┐    │
│  │ team-a                   │    │
│  └──────────────────────────┘    │
│                                  │
│  最大邀请人数                     │
│  ┌──────────────────────────┐    │
│  │ 5                        │    │
│  └──────────────────────────┘    │
│                                  │
│  失效时间                         │
│  ┌──────────────────────────┐    │
│  │ 2026-05-01T00:00         │    │
│  └──────────────────────────┘    │
│                                  │
│           [取消]  [创建]          │
└──────────────────────────────────┘
```

**详情展开（游客链接）**：
```
┌─────────────────────────────────────────────┐
│  访问记录 — abc123（共 42 次）               │
│  ┌─────────────────────────────────────┐    │
│  │ IP 地址          访问时间            │    │
│  │ 192.168.1.100    2026-04-15 14:30   │    │
│  │ 10.0.0.5         2026-04-15 13:22   │    │
│  │ ...                                  │    │
│  └─────────────────────────────────────┘    │
│  [加载更多]                                  │
└─────────────────────────────────────────────┘
```

**详情展开（租户链接）**：
```
┌─────────────────────────────────────────────┐
│  注册记录 — team-a（2/5）                    │
│  ┌─────────────────────────────────────┐    │
│  │ 用户名    显示名    注册时间          │    │
│  │ alice     Alice     2026-04-10 09:00│    │
│  │ bob       Bob       2026-04-12 15:30│    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

##### B. 游客邀请落地页（`invite.guest.$pathId.tsx`）

**公开页面**，无需登录。

**流程**：
1. 页面加载 → 调用 `GuestInviteLandingRoute?path_id=xxx`
2. 后端记录访问 IP/UA/时间
3. 前端显示欢迎信息 + 自动跳转倒计时
4. 跳转到 `/?webserver_schema=...&webserver_domain=...&webserver_port=...`（携带连接参数，复用现有连接流程）

```
┌──────────────────────────────────┐
│                                  │
│         🎉 欢迎访问 Fredica       │
│                                  │
│    你已被邀请以游客身份访问         │
│    「分享给同学」                  │
│                                  │
│    3 秒后自动跳转...              │
│                                  │
│         [立即进入]                │
│                                  │
└──────────────────────────────────┘
```

**错误状态**：
- 链接不存在 → "邀请链接无效"
- 链接已禁用 → "此邀请链接已被管理员禁用"

##### C. 租户注册落地页（`invite.tenant.$pathId.tsx`）

**公开页面**，无需登录。

**流程**：
1. 页面加载 → 调用 `TenantInviteLandingRoute?path_id=xxx` 检查链接状态
2. 链接可用 → 显示注册表单
3. 用户填写 → 调用 `TenantInviteRegisterRoute` 注册
4. 注册成功 → 自动写入 session_token + 跳转到主应用

```
┌──────────────────────────────────┐
│                                  │
│      注册 Fredica 账户            │
│      邀请来源：「团队 A 成员」     │
│                                  │
│  用户名                           │
│  ┌──────────────────────────┐    │
│  │                          │    │
│  └──────────────────────────┘    │
│                                  │
│  显示名称（可选）                  │
│  ┌──────────────────────────┐    │
│  │                          │    │
│  └──────────────────────────┘    │
│                                  │
│  密码                             │
│  ┌──────────────────────────┐    │
│  │                          │    │
│  └──────────────────────────┘    │
│                                  │
│  确认密码                         │
│  ┌──────────────────────────┐    │
│  │                          │    │
│  └──────────────────────────┘    │
│                                  │
│           [注册]                  │
│                                  │
│  已有账户？ [去登录]               │
│                                  │
└──────────────────────────────────┘
```

**不可用状态**：
- 链接已禁用 → "此邀请链接已被管理员禁用"
- 链接已过期 → "此邀请链接已过期"
- 名额已满 → "此邀请链接的注册名额已用完"
- 链接不存在 → "邀请链接无效"

**注册错误**：
- 用户名已存在 → "用户名已被占用"
- 密码不符合要求 → "密码长度需 8-128 位"
- 两次密码不一致 → 前端校验

#### 14.2.7 邀请链接 URL 格式

**游客邀请链接**：
```
http://{webui_host}:{webui_port}/invite/guest/{path_id}
```
示例：`http://192.168.1.100:7630/invite/guest/abc123`

**租户邀请链接**：
```
http://{webui_host}:{webui_port}/invite/tenant/{path_id}
```
示例：`http://192.168.1.100:7630/invite/tenant/team-a`

落地页需要知道后端 API 地址。两种方案：

**方案 A（推荐）**：落地页 URL 携带连接参数
```
/invite/guest/{path_id}?webserver_schema=http&webserver_domain=192.168.1.100&webserver_port=7631
```
管理员在"复制链接"时自动拼接当前连接参数。

**方案 B**：落地页从 localStorage 读取已保存的连接配置（仅适用于已访问过的用户）。

采用**方案 A**，因为邀请链接的目标用户通常是首次访问者，没有 localStorage 配置。

#### 14.2.8 前端 TypeScript 类型定义

新建 `fredica-webui/app/util/invite_types.ts`：

```typescript
// -- 游客邀请链接 --

export interface GuestInviteLink {
    id: string;
    path_id: string;
    label: string;
    status: "active" | "disabled";
    created_by: string;
    created_at: string;
    updated_at: string;
    visit_count: number;
}

export interface GuestInviteVisit {
    id: string;
    link_id: string;
    ip_address: string;
    user_agent: string;
    visited_at: string;
}

// -- 租户邀请链接 --

export interface TenantInviteLink {
    id: string;
    path_id: string;
    label: string;
    status: "active" | "disabled";
    max_uses: number;
    expires_at: string;
    created_by: string;
    created_at: string;
    updated_at: string;
    used_count: number;
}

export interface TenantInviteRegistration {
    id: string;
    link_id: string;
    user_id: string;
    ip_address: string;
    user_agent: string;
    registered_at: string;
    username?: string;
    display_name?: string;
}
```

#### 14.2.9 文件变更清单

##### 后端（commonMain）

| 文件 | 操作 | 说明 |
|------|------|------|
| `auth/InviteModels.kt` | **新建** | 4 个数据模型 + 4 个 Repo 接口 + 4 个 Service 单例 |
| `api/routes/GuestInviteLinkListRoute.kt` | **新建** | 列出游客邀请链接 |
| `api/routes/GuestInviteLinkCreateRoute.kt` | **新建** | 创建游客邀请链接 |
| `api/routes/GuestInviteLinkUpdateRoute.kt` | **新建** | 更新游客邀请链接 |
| `api/routes/GuestInviteLinkDeleteRoute.kt` | **新建** | 删除游客邀请链接 |
| `api/routes/GuestInviteVisitListRoute.kt` | **新建** | 查看游客访问记录 |
| `api/routes/GuestInviteLandingRoute.kt` | **新建** | 游客落地页数据（公开） |
| `api/routes/TenantInviteLinkListRoute.kt` | **新建** | 列出租户邀请链接 |
| `api/routes/TenantInviteLinkCreateRoute.kt` | **新建** | 创建租户邀请链接 |
| `api/routes/TenantInviteLinkUpdateRoute.kt` | **新建** | 更新租户邀请链接 |
| `api/routes/TenantInviteLinkDeleteRoute.kt` | **新建** | 删除租户邀请链接 |
| `api/routes/TenantInviteRegistrationListRoute.kt` | **新建** | 查看注册记录 |
| `api/routes/TenantInviteLandingRoute.kt` | **新建** | 租户落地页数据（公开） |
| `api/routes/TenantInviteRegisterRoute.kt` | **新建** | 通过邀请注册（公开） |
| `api/routes/all_routes.kt` | **修改** | 注册以上 14 个新路由 |
| `api/RouteRequestContext.kt` | **新建** | 请求上下文（clientIp, userAgent） |

##### 后端（jvmMain）

| 文件 | 操作 | 说明 |
|------|------|------|
| `auth/GuestInviteLinkDb.kt` | **新建** | guest_invite_link 表 + Ktorm 实现 |
| `auth/GuestInviteVisitDb.kt` | **新建** | guest_invite_visit 表 + Ktorm 实现 |
| `auth/TenantInviteLinkDb.kt` | **新建** | tenant_invite_link 表 + Ktorm 实现 |
| `auth/TenantInviteRegistrationDb.kt` | **新建** | tenant_invite_registration 表 + Ktorm 实现 |
| `api/FredicaApi.jvm.kt` | **修改** | 初始化 4 个 Service + RouteRequestContext 包裹 |

##### 前端

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/util/invite_types.ts` | **新建** | TypeScript 类型定义 |
| `app/routes/admin.tsx` | **新建** | 管理后台布局（RequireAuth + tab 导航 + Outlet） |
| `app/routes/admin.users.tsx` | **修改** | 移除外层 RequireAuth/SidebarLayout 包裹 |
| `app/routes/admin.invites.tsx` | **新建** | 邀请链接管理页 |
| `app/routes/invite.guest.$pathId.tsx` | **新建** | 游客邀请落地页（公开） |
| `app/routes/invite.tenant.$pathId.tsx` | **新建** | 租户注册落地页（公开） |

##### 测试

| 文件 | 操作 | 说明 |
|------|------|------|
| `jvmTest/.../auth/GuestInviteLinkDbTest.kt` | **新建** | 游客邀请链接 CRUD + 数据完整性 + 安全边界测试 |
| `jvmTest/.../auth/TenantInviteLinkDbTest.kt` | **新建** | 租户邀请链接 CRUD + 可用性判定 + 竞态条件测试 |
| `jvmTest/.../api/routes/GuestInviteLinkRouteTest.kt` | **新建** | 游客管理路由权限 + 输入验证测试 |
| `jvmTest/.../api/routes/TenantInviteLinkRouteTest.kt` | **新建** | 租户管理路由权限 + 输入验证测试 |
| `jvmTest/.../api/routes/GuestInviteLandingRouteTest.kt` | **新建** | 游客落地页路由 + 访问记录测试 |
| `jvmTest/.../api/routes/TenantInviteRegisterRouteTest.kt` | **新建** | 注册流程 + 竞态条件 + 速率限制 + 安全绕过测试 |

#### 14.2.10 实施阶段

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **Phase A** | 数据模型 + Repo 接口 + DB 实现 + 单元测试 | 无 |
| **Phase B** | 管理路由（CRUD）+ all_routes 注册 + RouteRequestContext | Phase A |
| **Phase C** | 公开路由（Landing + Register）| Phase A, B |
| **Phase D** | 前端路由重构（admin.tsx 布局 + admin.users.tsx 改造） | 无（可与 Phase A 并行） |
| **Phase E** | 前端管理页（admin.invites.tsx）| Phase B, D |
| **Phase F** | 前端落地页（invite.guest/tenant）| Phase C, D |
| **Phase G** | 安全专项测试 + 集成测试 + 手动验证 | Phase E, F |

---

##### Phase A 测试：数据模型 + Repo + DB

**测试文件**：`GuestInviteLinkDbTest.kt`、`TenantInviteLinkDbTest.kt`

**A-1 基础 CRUD 测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| A-1.1 | `create` → `findById` 返回一致数据 | 所有字段匹配 |
| A-1.2 | `create` → `findByPathId` 返回一致数据 | pathId 查询正常 |
| A-1.3 | `listAll` 返回含 `visit_count`/`used_count` 聚合 | 聚合值 = 实际记录数 |
| A-1.4 | `updateStatus("disabled")` → `findById` 状态已变 | status = "disabled" |
| A-1.5 | `updateLabel("新标签")` → `findById` 标签已变 | label = "新标签" |
| A-1.6 | `delete` → `findById` 返回 null | 记录已删除 |
| A-1.7 | 游客链接 `delete` 同时删除关联 `guest_invite_visit` 记录 | 级联删除 |

**A-2 租户链接可用性判定测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| A-2.1 | active + 未过期 + 0/5 已用 → `isUsable` | true |
| A-2.2 | active + 未过期 + 5/5 已用 → `isUsable` | false（已满额） |
| A-2.3 | active + 已过期 + 0/5 已用 → `isUsable` | false（已过期） |
| A-2.4 | disabled + 未过期 + 0/5 已用 → `isUsable` | false（已禁用） |
| A-2.5 | active + 未过期 + 4/5 已用 → `isUsable` | true（还剩 1 个名额） |
| A-2.6 | 过期时间恰好等于 `now()` → `isUsable` | false（边界：等于视为过期） |

**A-3 删除保护测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| A-3.1 | 租户链接无注册记录 → `delete` | 成功删除 |
| A-3.2 | 租户链接有 1 条注册记录 → `delete` | 抛异常或返回错误，记录仍存在 |
| A-3.3 | 租户链接有注册记录 → `updateStatus("disabled")` | 成功（禁用不受限） |

**A-4 安全：数据完整性与边界**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| A-4.1 | `pathId` 含 SQL 注入 payload `'; DROP TABLE guest_invite_link; --` | SQL 注入 | Ktorm 参数化查询，pathId 被安全存储，表未被删除 |
| A-4.2 | `pathId` 含路径穿越 `../../etc/passwd` | 路径穿越 | 正常存储为字符串，不影响文件系统 |
| A-4.3 | `label` 含 XSS payload `<script>alert(1)</script>` | 存储型 XSS | 正常存储（DB 层不过滤），前端渲染时由 React 自动转义 |
| A-4.4 | `pathId` 为空字符串 → `create` | 输入验证 | 应自动生成 pathId 或拒绝 |
| A-4.5 | `pathId` 超长（1000 字符）→ `create` | 资源耗尽 | 应拒绝或截断 |
| A-4.6 | `pathId` 含 Unicode 特殊字符（零宽字符 `\u200B`、RTL 标记 `\u202E`）| 视觉欺骗 | 应拒绝或过滤不可见字符 |
| A-4.7 | 重复 `pathId` → 第二次 `create` | 唯一约束 | 应返回错误"路径 ID 已存在" |
| A-4.8 | `max_uses` = 0 → `create` | 逻辑边界 | 应拒绝（0 名额无意义） |
| A-4.9 | `max_uses` = -1 → `create` | 负数绕过 | 应拒绝（负数不合法） |
| A-4.10 | `max_uses` = `Integer.MAX_VALUE` → `create` | 整数溢出 | 应接受或设上限 |
| A-4.11 | `expires_at` = 过去时间 → `create` | 逻辑绕过 | 应拒绝（创建即过期无意义） |
| A-4.12 | `expires_at` = 非法格式 `"not-a-date"` → `create` | 格式注入 | 应返回解析错误 |
| A-4.13 | `GuestInviteVisitRepo.record` 并发 100 次同一 linkId | 并发写入 | 所有记录均成功写入，`countByLinkId` = 100 |

---

##### Phase B 测试：管理路由 + RouteRequestContext

**测试文件**：`GuestInviteLinkRouteTest.kt`、`TenantInviteLinkRouteTest.kt`

**B-1 权限控制测试**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| B-1.1 | 无 token → `GuestInviteLinkListRoute` | 未认证访问 | 401 Unauthorized |
| B-1.2 | Guest token → `GuestInviteLinkListRoute` | 权限提升 | 401 或 403（GUEST < ROOT） |
| B-1.3 | Tenant token → `GuestInviteLinkCreateRoute` | 权限提升 | 401 或 403（TENANT < ROOT） |
| B-1.4 | ROOT token → `GuestInviteLinkListRoute` | 正常访问 | 200 + 数据 |
| B-1.5 | 过期 session token → `TenantInviteLinkCreateRoute` | 过期凭证 | 401 |
| B-1.6 | 伪造 JWT/token 格式 → 管理路由 | Token 伪造 | 401 |
| B-1.7 | ROOT 用户被 `updateStatus("disabled")` 后 → 管理路由 | 已禁用账户 | 401（session 应失效） |

**B-2 安全：输入验证与注入**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| B-2.1 | `GuestInviteLinkCreateRoute` body 为空 JSON `{}` | 缺失参数 | 成功（label 默认空，pathId 自动生成） |
| B-2.2 | `GuestInviteLinkCreateRoute` body 为非 JSON 字符串 | 格式错误 | 400 或 error 响应 |
| B-2.3 | `GuestInviteLinkCreateRoute` body 含额外字段 `{"label":"x","admin":true}` | 参数污染 | 额外字段被忽略 |
| B-2.4 | `GuestInviteLinkUpdateRoute` 传入不存在的 `id` | IDOR | 返回错误"链接不存在" |
| B-2.5 | `GuestInviteLinkDeleteRoute` 传入其他类型的 ID（如 user ID） | IDOR 跨表 | 返回错误"链接不存在"（不会误删其他表数据） |
| B-2.6 | `TenantInviteLinkCreateRoute` 的 `expires_at` 传入 `"2000-01-01T00:00:00Z"` | 过去时间 | 应拒绝 |
| B-2.7 | `TenantInviteLinkCreateRoute` 的 `max_uses` 传入字符串 `"abc"` | 类型混淆 | JSON 反序列化失败，返回错误 |
| B-2.8 | `GuestInviteLinkUpdateRoute` 的 `status` 传入 `"root"` | 非法枚举值 | 应拒绝（只接受 "active"/"disabled"） |

**B-3 RouteRequestContext 测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| B-3.1 | 请求携带 `X-Forwarded-For: 1.2.3.4` → `clientIp()` | 返回 `1.2.3.4`（或 remoteAddress，取决于信任策略） |
| B-3.2 | 请求无 User-Agent → `userAgent()` | 返回 null 或空字符串 |
| B-3.3 | 并发请求 → 各自的 `clientIp()` 互不干扰 | ThreadLocal 隔离正确 |
| B-3.4 | `X-Forwarded-For` 含多个 IP `"1.2.3.4, 5.6.7.8"` | 取第一个（最左侧客户端 IP） |
| B-3.5 | `X-Forwarded-For` 含伪造 IP `"127.0.0.1"` | 记录原样存储（不做信任判断，仅记录） |

**B-4 安全：IP 伪造与隐私**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| B-4.1 | 伪造 `X-Forwarded-For` 头注入超长 IP 字符串（10KB） | Header 注入 / 资源耗尽 | 应截断或拒绝 |
| B-4.2 | TENANT 用户调用 `GuestInviteVisitListRoute` | 隐私泄露 | 403（IP 数据仅 ROOT 可见） |
| B-4.3 | `User-Agent` 含 XSS payload | 存储型 XSS | 正常存储，前端渲染时 React 转义 |

---

##### Phase C 测试：公开路由（Landing + Register）

**测试文件**：`TenantInviteRegisterRouteTest.kt`、`GuestInviteLandingRouteTest.kt`

**C-1 游客落地页路由测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| C-1.1 | 有效 pathId → `GuestInviteLandingRoute` | 200 + `{ success: true }` + 访问记录 +1 |
| C-1.2 | 不存在的 pathId → `GuestInviteLandingRoute` | `{ error: "链接不存在" }` |
| C-1.3 | 已禁用链接的 pathId → `GuestInviteLandingRoute` | `{ error: "链接已禁用" }` |
| C-1.4 | 同一 IP 连续访问 10 次 → 访问记录 | 10 条记录（不去重，忠实记录） |

**C-2 租户落地页路由测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| C-2.1 | 可用链接 → `TenantInviteLandingRoute` | `{ usable: true, label: "..." }` |
| C-2.2 | 已禁用链接 → `TenantInviteLandingRoute` | `{ usable: false, reason: "disabled" }` |
| C-2.3 | 已过期链接 → `TenantInviteLandingRoute` | `{ usable: false, reason: "expired" }` |
| C-2.4 | 已满额链接 → `TenantInviteLandingRoute` | `{ usable: false, reason: "full" }` |

**C-3 租户注册路由 — 正常流程**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| C-3.1 | 有效链接 + 合法参数 → 注册 | 200 + `{ success: true, token: "..." }` + 用户已创建 + 注册记录 +1 |
| C-3.2 | 注册后 `isUsable` 更新 | `used_count` 增加，达到 `max_uses` 后 `isUsable` = false |
| C-3.3 | 注册返回的 token 可用于后续 API 调用 | 用 token 调用需认证路由 → 200 |

**C-4 安全：注册路由绕过攻击**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| C-4.1 | 已禁用链接 → 直接 POST `TenantInviteRegisterRoute` | 绕过前端禁用检查 | `{ error: "邀请链接已禁用" }` |
| C-4.2 | 已过期链接 → 直接 POST 注册 | 绕过前端过期检查 | `{ error: "邀请链接已过期" }` |
| C-4.3 | 已满额链接 → 直接 POST 注册 | 绕过前端满额检查 | `{ error: "邀请链接已达使用上限" }` |
| C-4.4 | **竞态条件**：链接剩余 1 名额，2 个请求同时 POST 注册 | Race condition | 只有 1 个成功，另一个返回"已达上限"。**实现要点**：`countByLinkId` + `createUser` + `record` 需在事务内执行，或使用乐观锁/SELECT FOR UPDATE |
| C-4.5 | **竞态条件**：链接剩余 1 名额，10 个请求并发 POST | Race condition 放大 | 最多 1 个成功注册 |
| C-4.6 | 注册后立即禁用链接 → 已注册用户的 session 是否仍有效 | 链接禁用不溯及 | 已注册用户不受影响（禁用只阻止新注册） |
| C-4.7 | `username` 含 SQL 注入 `admin'--` | SQL 注入 | Ktorm 参数化，安全存储或被用户名格式校验拒绝 |
| C-4.8 | `username` 含 XSS `<img src=x onerror=alert(1)>` | 存储型 XSS | 存储后前端 React 自动转义 |
| C-4.9 | `username` 为空字符串 | 输入验证 | 应拒绝 |
| C-4.10 | `username` 超长（10000 字符） | 资源耗尽 | 应拒绝或截断 |
| C-4.11 | `password` 为空字符串 | 弱密码 | 应拒绝（复用 AuthService 的密码策略） |
| C-4.12 | `password` 超长（1MB 字符串） | 哈希 DoS | 应在哈希前截断或拒绝（bcrypt 有 72 字节限制） |
| C-4.13 | `display_name` 含 Unicode 零宽字符 + RTL 覆盖 | 视觉欺骗 | 存储原样，管理页面显示时不会误导 ROOT |
| C-4.14 | `path_id` 传入有效游客链接的 pathId → 租户注册路由 | 跨类型混淆 | 返回"邀请链接不存在"（查的是 tenant_invite_link 表） |
| C-4.15 | 不带 Content-Type header → POST 注册 | 格式绕过 | 应返回解析错误或 400 |
| C-4.16 | body 为 URL-encoded 而非 JSON → POST 注册 | 格式绕过 | 应返回解析错误 |

**C-5 安全：速率限制**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| C-5.1 | 同一 IP 1 分钟内 POST 注册 50 次（不同用户名） | 暴力注册 | 触发 `LoginRateLimiterApi` 限流，后续请求返回 429 |
| C-5.2 | 同一 IP 1 分钟内 POST 注册 50 次（相同用户名） | 暴力猜测 | 前几次返回"用户名已占用"，后续触发限流 |
| C-5.3 | 不同 IP 同时注册同一链接 | 分布式攻击 | 各 IP 独立限流，但总注册数不超过 `max_uses` |
| C-5.4 | `GuestInviteLandingRoute` 被高频访问（1000 次/分钟） | 访问记录膨胀 | 应考虑是否对落地页也做速率限制，或对 visit 记录做采样 |

**C-6 安全：路径 ID 枚举**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| C-6.1 | 遍历 `a`-`z` 单字符 pathId → `TenantInviteLandingRoute` | 短 ID 枚举 | 全部返回"链接不存在"（自动生成 8 位，手动设置也应有最小长度） |
| C-6.2 | 字典攻击：常见词 `test`, `admin`, `invite` 等 → Landing | 可预测 ID | 返回"链接不存在"（除非 ROOT 手动设置了这些 pathId） |
| C-6.3 | 自动生成的 pathId 熵检验：生成 1000 个 → 无重复 + 无可预测模式 | 弱随机数 | `SecureRandom` 保证不可预测 |
| C-6.4 | 时序攻击：比较"链接不存在"与"链接已禁用"的响应时间差 | 时序侧信道 | 两种情况响应时间应无显著差异（避免泄露 pathId 是否存在） |

---

##### Phase D 测试：前端路由重构

**测试方式**：`npx tsc --noEmit` + 浏览器手动验证

**D-1 路由结构测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| D-1.1 | 访问 `/admin/users` → 显示 tab 导航 + 用户管理内容 | admin.tsx 布局 + admin.users.tsx 子路由 |
| D-1.2 | 访问 `/admin/invites` → 显示 tab 导航 + 邀请链接内容 | admin.tsx 布局 + admin.invites.tsx 子路由 |
| D-1.3 | 访问 `/admin` → 重定向到 `/admin/users` | 默认子路由 |
| D-1.4 | 非 ROOT 用户访问 `/admin/users` → 403 | RequireAuth minRole="root" 生效 |
| D-1.5 | 非 ROOT 用户访问 `/admin/invites` → 403 | RequireAuth minRole="root" 生效 |
| D-1.6 | 未登录用户访问 `/admin/invites` → 跳转 /login | RequireAuth 未登录检查 |

**D-2 安全：前端路由权限绕过**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| D-2.1 | 手动修改 localStorage 中 `user_role` 为 `"root"` → 访问 `/admin/invites` | 客户端篡改 | 页面可能渲染，但所有 API 调用返回 401/403（后端校验） |
| D-2.2 | 直接在浏览器地址栏输入 `/admin/invites` | URL 直接访问 | RequireAuth 正常拦截 |

---

##### Phase E 测试：前端管理页

**测试方式**：浏览器手动验证 + 可选 vitest 组件测试

**E-1 功能测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| E-1.1 | 新建游客邀请链接（留空 pathId）→ 列表刷新 | 自动生成 pathId，列表新增一行 |
| E-1.2 | 新建游客邀请链接（自定义 pathId）→ 列表刷新 | 使用自定义 pathId |
| E-1.3 | 新建租户邀请链接（设置 max_uses + expires_at）→ 列表刷新 | 显示正确的上限和失效时间 |
| E-1.4 | 复制链接 → 粘贴到地址栏 | URL 格式正确，可访问 |
| E-1.5 | 编辑标签 → 保存 → 列表刷新 | 标签已更新 |
| E-1.6 | 禁用链接 → 状态徽章变为"○禁用" | 即时反馈 |
| E-1.7 | 启用链接 → 状态徽章变为"●活跃" | 即时反馈 |
| E-1.8 | 删除游客链接 → 列表移除 | 确认对话框 → 删除成功 |
| E-1.9 | 删除有注册记录的租户链接 → 拒绝 | 显示"有注册记录，不可删除" |
| E-1.10 | 展开游客链接详情 → 显示访问记录表 | IP、UA、时间列表 |
| E-1.11 | 展开租户链接详情 → 显示注册记录表 | 用户名、显示名、注册时间 |

**E-2 安全：前端输入验证**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| E-2.1 | 新建对话框中 pathId 输入 `<script>alert(1)</script>` | XSS | 前端应校验 pathId 格式（仅 alphanumeric + 连字符），拒绝特殊字符 |
| E-2.2 | 新建对话框中 label 输入超长文本（10000 字符） | UI 破坏 | 前端应限制长度或截断显示 |
| E-2.3 | 新建租户链接 max_uses 输入 0 | 逻辑错误 | 前端校验拒绝（最小值 1） |
| E-2.4 | 新建租户链接 expires_at 选择过去时间 | 逻辑错误 | 前端校验拒绝 |
| E-2.5 | 通过浏览器 DevTools 修改 disabled 按钮为 enabled 后点击 | 绕过前端禁用 | 后端仍然校验，返回错误 |

---

##### Phase F 测试：前端落地页

**测试方式**：浏览器手动验证

**F-1 游客落地页测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| F-1.1 | 访问有效游客邀请链接 → 显示欢迎页 | 显示标签 + 自动跳转倒计时 |
| F-1.2 | 访问已禁用链接 → 显示错误 | "此链接已禁用" |
| F-1.3 | 访问不存在的 pathId → 显示错误 | "链接不存在" |
| F-1.4 | 自动跳转后 → 进入主应用 | URL 参数携带连接信息 |

**F-2 租户注册落地页测试**

| 编号 | 测试用例 | 预期 |
|------|---------|------|
| F-2.1 | 可用链接 → 显示注册表单 | 用户名 + 密码 + 显示名 + 注册按钮 |
| F-2.2 | 已禁用链接 → 显示错误 | "此邀请链接已禁用" |
| F-2.3 | 已过期链接 → 显示错误 | "此邀请链接已过期" |
| F-2.4 | 已满额链接 → 显示错误 | "此邀请链接名额已用完" |
| F-2.5 | 填写表单 → 注册成功 → 自动登录 → 跳转主应用 | token 写入 appConfig |
| F-2.6 | 用户名已存在 → 显示错误 | "用户名已被占用" |
| F-2.7 | 密码为空 → 前端校验 | 提交按钮禁用或显示提示 |

**F-3 安全：落地页攻击**

| 编号 | 测试用例 | 攻击向量 | 预期 |
|------|---------|---------|------|
| F-3.1 | 在注册表单中用户名输入 `admin` | 特权用户名 | 如果 `admin` 已存在则"已占用"；如果不存在则正常创建为 TENANT（不会获得 ROOT 权限） |
| F-3.2 | 在注册表单中用户名输入 `root` | 特权用户名 | 同上，角色由 `createUser` 决定（默认 TENANT），不受用户名影响 |
| F-3.3 | 使用 curl 直接 POST 注册（绕过前端） | 无前端校验 | 后端独立校验所有参数 |
| F-3.4 | 注册成功后修改返回的 token 中的 role 字段 | Token 篡改 | 后端 session 校验以服务端存储为准，篡改无效 |
| F-3.5 | 注册页面 URL 中 pathId 含 `../admin/users` | 路径穿越 | React Router 路由匹配，不会穿越到管理页 |
| F-3.6 | 在注册表单提交时拦截请求，修改 `path_id` 为另一个有效链接 | 请求篡改 | 使用被篡改的 pathId 对应链接的配额（如果该链接可用则成功，否则失败） |

---

##### Phase G 测试：安全专项 + 集成测试 + 手动验证

**G-1 端到端集成测试**

| 编号 | 测试场景 | 步骤 | 预期 |
|------|---------|------|------|
| G-1.1 | 完整游客邀请流程 | ROOT 创建链接 → 复制 URL → 新浏览器访问 → 自动跳转 → 管理页查看访问记录 | 全流程通畅，记录准确 |
| G-1.2 | 完整租户邀请流程 | ROOT 创建链接(max=2) → 用户 A 注册 → 用户 B 注册 → 用户 C 注册失败 → 管理页查看 | 注册数正确，满额后拒绝 |
| G-1.3 | 链接生命周期 | 创建 → 使用 → 禁用 → 尝试使用 → 启用 → 使用 → 删除 | 各状态转换正确 |
| G-1.4 | 多链接隔离 | 创建链接 A(max=1) 和链接 B(max=1) → 各注册 1 人 → 各自满额 | 链接间配额独立 |

**G-2 安全专项：认证与授权**

| 编号 | 测试场景 | 攻击向量 | 步骤 | 预期 |
|------|---------|---------|------|------|
| G-2.1 | 水平越权 — 管理路由 | IDOR | TENANT 用户直接调用 `GuestInviteLinkListRoute`（通过 curl 携带 TENANT 的 token） | 403 |
| G-2.2 | 垂直越权 — 公开路由提权 | 权限提升 | 通过 `TenantInviteRegisterRoute` 注册后，检查新用户角色 | 角色 = TENANT（不是 ROOT） |
| G-2.3 | 注册用户权限边界 | 权限验证 | 新注册 TENANT 用户尝试访问 `/admin/users` API | 403 |
| G-2.4 | Session 固定攻击 | Session fixation | 注册前设置一个自定义 session token → 注册后检查返回的 token | 返回新生成的 token，旧 token 无效 |
| G-2.5 | 注册后 token 作用域 | Token 泄露 | 用注册返回的 token 调用 `TenantInviteRegisterRoute` 再次注册 | 注册路由 `requiresAuth = false`，token 不影响；但配额检查仍生效 |

**G-3 安全专项：竞态条件深度测试**

| 编号 | 测试场景 | 攻击向量 | 步骤 | 预期 |
|------|---------|---------|------|------|
| G-3.1 | 注册竞态（精确边界） | TOCTOU | 链接 max_uses=1，使用 `CountDownLatch` 让 10 个线程同时发起注册请求 | 恰好 1 个成功，9 个失败 |
| G-3.2 | 禁用竞态 | TOCTOU | 线程 A 正在注册（已通过 isUsable 检查），线程 B 同时禁用链接 | 取决于事务隔离级别：理想情况下 A 应失败，但如果 A 已进入事务则可能成功（可接受） |
| G-3.3 | 删除竞态 | TOCTOU | 线程 A 正在通过游客链接记录访问，线程 B 同时删除该链接 | A 应失败或记录被级联删除（不应崩溃） |
| G-3.4 | 并发创建相同 pathId | 唯一约束竞态 | 两个 ROOT 同时创建 pathId="same" | 一个成功，一个返回"路径 ID 已存在" |

**G-4 安全专项：信息泄露**

| 编号 | 测试场景 | 攻击向量 | 步骤 | 预期 |
|------|---------|---------|------|------|
| G-4.1 | 错误消息信息泄露 | 信息收集 | 对各路由发送畸形请求，检查错误响应 | 不泄露内部实现细节（无堆栈跟踪、无 SQL 语句、无文件路径） |
| G-4.2 | 链接存在性探测 | 枚举 | 比较"链接不存在"和"链接已禁用"的响应 | 两者应返回相同的通用错误消息（如统一为"链接不可用"），或接受信息泄露风险（当前设计分别返回不同消息，需评估是否可接受） |
| G-4.3 | 注册记录隐私 | 数据泄露 | TENANT 用户调用 `TenantInviteRegistrationListRoute` | 403（注册记录含 IP，仅 ROOT 可见） |
| G-4.4 | 落地页响应头 | 指纹识别 | 检查公开路由响应头是否泄露服务器版本 | 无 `Server`、`X-Powered-By` 等敏感头 |
| G-4.5 | 访问记录中的 User-Agent | 隐私 | 检查 UA 字符串是否被完整存储 | 完整存储（ROOT 需要用于分析），但不暴露给非 ROOT |

**G-5 安全专项：输入边界与异常**

| 编号 | 测试场景 | 攻击向量 | 步骤 | 预期 |
|------|---------|---------|------|------|
| G-5.1 | 超大 JSON body（1MB） | 资源耗尽 | POST 注册路由发送 1MB JSON | Ktor 应有 body size 限制，返回 413 或截断 |
| G-5.2 | 嵌套 JSON（深度 1000 层） | JSON 解析 DoS | POST 注册路由发送深度嵌套 JSON | 解析失败或超时，不导致 OOM |
| G-5.3 | Content-Type: multipart/form-data | 格式混淆 | POST 注册路由使用 multipart 而非 JSON | 返回解析错误 |
| G-5.4 | HTTP Method 混淆 | 方法绕过 | 用 GET 请求 `TenantInviteRegisterRoute`（POST 路由） | 405 Method Not Allowed 或路由不匹配 |
| G-5.5 | 重复注册（同一用户名 + 同一链接） | 幂等性 | 第一次成功，第二次用相同用户名 | 第二次返回"用户名已占用" |
| G-5.6 | `pathId` 含 URL 编码字符 `%2F%2E%2E` | URL 编码绕过 | 访问 `/invite/tenant/%2F%2E%2E` | React Router 解码后传给 API，API 查询 `/../` 返回"不存在" |

**G-6 手动验证清单**

```shell
# 后端编译
./gradlew :shared:compileKotlinJvm

# 全部单元测试
./gradlew :shared:jvmTest --tests "*.GuestInviteLinkDbTest"
./gradlew :shared:jvmTest --tests "*.TenantInviteLinkDbTest"
./gradlew :shared:jvmTest --tests "*.TenantInviteRegisterRouteTest"
./gradlew :shared:jvmTest --tests "*.GuestInviteLandingRouteTest"
./gradlew :shared:jvmTest --tests "*.GuestInviteLinkRouteTest"
./gradlew :shared:jvmTest --tests "*.TenantInviteLinkRouteTest"

# 前端 typecheck
cd fredica-webui && npx tsc --noEmit
```

**浏览器手动测试**：

1. **游客邀请链接 CRUD**：ROOT 创建 → 列表显示 → 复制链接 → 编辑标签 → 禁用 → 启用 → 删除
2. **游客落地页**：访问链接 → 显示欢迎页 → 自动跳转 → 后台访问记录 +1
3. **游客落地页（禁用）**：禁用链接后访问 → 显示"已禁用"
4. **租户邀请链接 CRUD**：ROOT 创建（设置 max_uses=2, expires_at=明天）→ 列表显示 → 复制链接
5. **租户注册**：访问链接 → 填写表单 → 注册成功 → 自动登录 → 进入主应用
6. **租户注册（名额已满）**：注册 2 人后 → 第 3 人访问 → 显示"名额已用完"
7. **租户注册（已过期）**：设置过期时间为过去 → 访问 → 显示"已过期"
8. **租户注册（用户名冲突）**：注册已存在的用户名 → 显示"用户名已被占用"
9. **管理页面 tab 切换**：用户管理 ↔ 邀请链接 tab 切换正常
10. **详情展开**：点击游客链接详情 → 显示访问记录；点击租户链接详情 → 显示注册记录
11. **curl 直接调用管理路由**（无 token / Guest token / Tenant token）→ 全部 401/403
12. **curl 直接 POST 注册**（绕过前端）→ 后端独立校验生效

#### 14.2.11 安全考量

1. **路径 ID 不可预测**：自动生成时使用 `SecureRandom` 生成 8 位 alphanumeric 短码（62^8 ≈ 2×10^14 种组合）
2. **注册速率限制**：`TenantInviteRegisterRoute` 应复用 `LoginRateLimiterApi`，按 IP 限制注册频率
3. **访问记录隐私**：IP 地址仅 ROOT 可见，不暴露给普通用户
4. **链接禁用即时生效**：禁用后所有后续请求立即返回错误，不需要等待缓存过期
5. **删除保护**：租户邀请链接有注册记录时不可删除（避免孤儿用户记录），只能禁用
6. **CSRF 防护**：注册路由为 POST，浏览器同源策略 + SameSite Cookie 提供基本防护
7. **竞态条件防护**：租户注册的配额检查 + 用户创建 + 记录写入应在数据库事务内完成，防止 TOCTOU 攻击导致超额注册
8. **输入长度限制**：pathId（最大 32 字符）、label（最大 200 字符）、username（最大 64 字符）、password（最大 128 字符）、display_name（最大 128 字符）
9. **pathId 格式校验**：仅允许 `[a-zA-Z0-9_-]`，最小长度 3 字符，拒绝不可见字符和特殊符号
10. **错误消息一致性**：公开路由对"不存在"和"已禁用"可返回不同消息（可接受的信息泄露，因为 pathId 本身不可预测）；但不应泄露内部实现细节
11. **密码哈希安全**：复用 AuthService 的 bcrypt 哈希，注意 bcrypt 72 字节截断——超长密码应在哈希前校验长度

#### 14.2.12 验证场景

> 已合并至 14.2.10 各 Phase 测试中，此处保留命令速查。

```shell
# 后端编译
./gradlew :shared:compileKotlinJvm

# 全部邀请链接相关测试
./gradlew :shared:jvmTest --tests "*.GuestInviteLinkDbTest"
./gradlew :shared:jvmTest --tests "*.TenantInviteLinkDbTest"
./gradlew :shared:jvmTest --tests "*.TenantInviteRegisterRouteTest"
./gradlew :shared:jvmTest --tests "*.GuestInviteLandingRouteTest"

# 前端 typecheck
cd fredica-webui && npx tsc --noEmit
```

---

## 十五、缓存层 + 路由权限强制化 + 任务提交角色限制 + AuthIdentity 类型重构 <Badge type="tip" text="已完成" />

> Phase 1-8（14.x）全部完成后，针对认证体系中发现的 4 个架构级问题进行重构。

### 15.0 问题分析

| # | 问题 | 根因 | 影响 |
|---|------|------|------|
| 1 | 后端热路径无缓存 | `AuthService.resolveIdentity()` 每次请求调用 `AppConfigService.repo.getConfig()` 全表扫描 SQLite（70+ 行 KV），仅为比对一个 `webserverAuthToken` 字段 | 每个 API 请求都产生一次不必要的 DB 查询 |
| 2 | 前端无 sessionStorage 缓存 | `appConfig` 仅存 localStorage，无快速读取层 | 经分析：**不构成问题**（见 15.2） |
| 3 | 路由 minRole 默认值隐患 | `FredicaApi.Route.minRole` 默认 `GUEST`，新增路由时容易遗漏权限设置 | 目前 ~40 个路由依赖默认值，无编译期保障 |
| 4 | 游客可提交重型任务 | `BilibiliVideoDownloadRoute`、`NetworkTestRoute` 等无 minRole 限制 | 游客可触发下载/转码等消耗服务器资源的操作 |

---

### 15.1 后端 — WebserverAuthToken 内存缓存服务 <Badge type="tip" text="已完成" />

**目的**：消除 `resolveIdentity()` 热路径上的 SQLite 全表扫描。

#### 15.1.1 设计思路

`resolveIdentity()` 是每个 API 请求的必经路径。当前实现中，游客令牌验证需要调用 `AppConfigService.repo.getConfig()` 读取完整的 AppConfig（70+ 个 KV 字段），仅为比对其中一个 `webserverAuthToken` 字段。

解决方案：引入 `WebserverAuthTokenCache` 单例，采用 **invalidation + lazy reload** 模式：
- 使用 `@Volatile` 注解保证线程间可见性
- 启动时通过 `init()` 预热
- DB 写入时自动 `invalidate()`（清除缓存），下次 `get()` 从 DB 重新加载
- 缓存失效逻辑集中在 `AppConfigDb.updateConfig()` 和 `updateConfigPartial()` 中，无需在各写入点手动管理

**失效点集中化**：

缓存失效由 `AppConfigDb` 统一管理，而非分散在各业务写入点：

| 失效触发点 | 条件 |
|------------|------|
| `AppConfigDb.updateConfig()` | 每次调用后无条件 `invalidate()` |
| `AppConfigDb.updateConfigPartial()` | 仅当 `kvPatch` 包含 `"webserver_auth_token"` 键时 `invalidate()` |

这样无论 `webserverAuthToken` 从哪里被修改（`initializeInstance()`、`WebserverAuthTokenUpdateRoute` 或未来新增的写入点），缓存都会自动失效。

#### 15.1.2 新建 `WebserverAuthTokenCache`

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/auth/WebserverAuthTokenCache.kt`（新建）

```kotlin
package com.github.project_fredica.auth

import com.github.project_fredica.db.AppConfigService
import kotlin.concurrent.Volatile

/**
 * webserver_auth_token 的内存缓存（invalidation + lazy reload）。
 *
 * 失效点：AppConfigDb.updateConfig()、AppConfigDb.updateConfigPartial()。
 * 读取点：AuthService.resolveIdentity()（每次 API 请求）。
 * 启动时通过 init() 预热；DB 写入时自动 invalidate()，下次 get() 重新加载。
 */
object WebserverAuthTokenCache {
    @Volatile
    private var _token: String? = null

    /** 预热缓存（启动时调用一次） */
    fun init(token: String) { _token = token }

    /** 读取缓存，cache miss 时从 DB 重新加载 */
    suspend fun get(): String {
        _token?.let { return it }
        val token = AppConfigService.repo.getConfig().webserverAuthToken
        _token = token
        return token
    }

    /** 清除缓存，下次 get() 将重新从 DB 加载 */
    fun invalidate() { _token = null }
}
```

#### 15.1.3 修改 `AuthService.resolveIdentity()`

**文件**：`shared/src/jvmMain/kotlin/com/github/project_fredica/auth/AuthService.kt`

将：
```kotlin
val config = AppConfigService.repo.getConfig()
if (config.webserverAuthToken.isNotBlank() && token == config.webserverAuthToken) {
    return AuthIdentity.Guest
}
```
改为：
```kotlin
val cachedToken = WebserverAuthTokenCache.get()
if (cachedToken.isNotBlank() && token == cachedToken) {
    return AuthIdentity.Guest
}
```

注意：`get()` 现在是 `suspend` 函数（cache miss 时需从 DB 加载），`resolveIdentity()` 本身已是 `suspend`，无需额外改动。

#### 15.1.4 缓存预热

**文件**：`shared/src/jvmMain/kotlin/com/github/project_fredica/api/FredicaApi.jvm.kt`

在 `FredicaApi.init()` 中，AppConfigService 初始化之后、路由注册之前：

```kotlin
// 预热 webserver_auth_token 缓存（避免 resolveIdentity 首次读 SQLite）
val config = AppConfigService.repo.getConfig()
WebserverAuthTokenCache.init(config.webserverAuthToken)
```

#### 15.1.5 缓存失效 — 集中在 AppConfigDb

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/db/AppConfigDb.kt`

在 `AppConfigDb` 的两个写入方法中添加缓存失效调用：

1. **`updateConfig()`**：DB 写入完成后无条件调用 `WebserverAuthTokenCache.invalidate()`
2. **`updateConfigPartial()`**：DB 写入完成后，仅当 `"webserver_auth_token" in kvPatch` 时调用 `WebserverAuthTokenCache.invalidate()`

这样 `AuthService.initializeInstance()` 和 `WebserverAuthTokenUpdateRoute.handler()` 无需手动管理缓存，DB 层自动处理。

#### 15.1.6 AppConfig.kt 添加注释

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/db/AppConfig.kt`

在 `webserverAuthToken` 字段上方添加注释：
```kotlin
// ⚠️ webserverAuthToken 由 AppConfigDb.updateConfig/updateConfigPartial 自动触发 WebserverAuthTokenCache.invalidate()
@SerialName("webserver_auth_token") val webserverAuthToken: String = "",
```

---

### 15.2 前端 — sessionStorage 缓存层分析（结论：不需要） <Badge type="tip" text="已完成" />

经分析，前端 `appConfig` 的数据流已经是高效的：

```
页面加载 → useLayoutEffect 从 localStorage 读取 → 写入 React state（内存）
         → 后续所有读取都从 React Context（内存）获取
         → setAppConfig() 同时更新 React state + localStorage + Cookie
```

- `localStorage` 仅在页面加载时读取一次
- 运行时所有组件通过 `useAppConfig()` 从 React Context（内存）读取
- `sessionStorage` 的生命周期（标签页级别）反而比 `localStorage`（跨标签页持久化）更短，不适合作为缓存层

**结论**：前端不需要额外的 sessionStorage 缓存层，当前架构已经是最优的。**不做任何改动。**

---

### 15.3 移除 `minRole` 默认值 + 强制所有路由显式声明 <Badge type="tip" text="已完成" />

#### 15.3.1 问题分析

当前 `FredicaApi.Route` 接口中 `minRole` 有默认值：

```kotlin
/** 允许访问此路由的最低角色。默认 GUEST（向后兼容）。 */
val minRole: AuthRole get() = AuthRole.GUEST
```

这意味着新增路由时如果忘记声明 `minRole`，编译器不会报错，路由会默认以 GUEST 权限暴露。这是一个安全隐患——开发者可能无意中将管理操作暴露给游客。

#### 15.3.2 移除默认值

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/api/FredicaApi.kt`

将：
```kotlin
/** 允许访问此路由的最低角色。默认 GUEST（向后兼容）。 */
val minRole: AuthRole get() = AuthRole.GUEST
```
改为：
```kotlin
/** 允许访问此路由的最低角色。每个路由必须显式声明。 */
val minRole: AuthRole
```

移除默认值后，所有未显式声明 `minRole` 的路由将导致编译失败，从而强制逐一补全。

#### 15.3.3 路由权限审计 — 已有显式声明的路由

以下路由已在 Phase 1-8 中正确设置了 `minRole`，无需改动：

**已声明 GUEST（5 个）：**

| 路由 | 说明 |
|------|------|
| `AuthGuestValidateRoute` | 游客令牌验证 |
| `GuestInviteLandingRoute` | 游客邀请落地页（requiresAuth=false） |
| `TenantInviteLandingRoute` | 租户邀请落地页（requiresAuth=false） |
| `TenantInviteRegisterRoute` | 租户注册（requiresAuth=false） |
| `MaterialVideoStreamRoute` | 视频流（Cookie 鉴权，独立于 minRole） |

**已声明 TENANT（26 个）：**

| 路由 | 说明 |
|------|------|
| `AuthLogoutRoute` | 登出 |
| `AuthMeRoute` | 查询当前用户信息 |
| `LlmCacheInvalidateRoute` | 清除 LLM 缓存 |
| `LlmModelProbeRoute` | 探测模型可用性 |
| `MaterialCategoryCreateRoute` | 创建分类 |
| `MaterialCategoryDeleteRoute` | 删除分类 |
| `MaterialDeleteRoute` | 删除素材 |
| `MaterialImportRoute` | 导入素材 |
| `MaterialSetCategoriesRoute` | 设置素材分类 |
| `MaterialVideoTranscodeMp4Route` | 视频转码（重型任务） |
| `MaterialWorkflowRoute` | 提交工作流（重型任务） |
| `PostprocessSubtitleSaveRoute` | 保存后处理字幕 |
| `PromptTemplateDeleteRoute` | 删除模板 |
| `PromptTemplateSaveRoute` | 保存模板 |
| `TaskCancelRoute` | 取消任务 |
| `TaskPauseRoute` | 暂停任务 |
| `TaskResumeRoute` | 恢复任务 |
| `UserChangePasswordRoute` | 修改密码 |
| `UserUpdateDisplayNameRoute` | 修改显示名 |
| `WebenConceptBatchImportRoute` | 批量导入概念 |
| `WebenConceptUpdateRoute` | 更新概念 |
| `WebenExtractionRunSaveRoute` | 保存提取运行 |
| `WebenNoteDeleteRoute` | 删除笔记 |
| `WebenNoteSaveRoute` | 保存笔记 |
| `WebenSourceAnalyzeRoute` | 分析来源（提交工作流） |
| `MaterialAsrSubtitleRoute`* | ASR 字幕（POST，提交任务） |

*注：`MaterialAsrSubtitleRoute` 为 POST 模式，提交 ASR 任务，已设为 TENANT。

**已声明 ROOT（15 个）：**

| 路由 | 说明 |
|------|------|
| `GuestInviteLinkCreateRoute` | 创建游客邀请链接 |
| `GuestInviteLinkDeleteRoute` | 删除游客邀请链接 |
| `GuestInviteLinkListRoute` | 列出游客邀请链接 |
| `GuestInviteLinkUpdateRoute` | 更新游客邀请链接 |
| `GuestInviteVisitListRoute` | 查看游客访问记录 |
| `TenantInviteLinkCreateRoute` | 创建租户邀请链接 |
| `TenantInviteLinkDeleteRoute` | 删除租户邀请链接 |
| `TenantInviteLinkListRoute` | 列出租户邀请链接 |
| `TenantInviteLinkUpdateRoute` | 更新租户邀请链接 |
| `TenantInviteRegistrationListRoute` | 查看租户注册记录 |
| `UserCreateRoute` | 创建用户 |
| `UserDisableRoute` | 禁用用户 |
| `UserEnableRoute` | 启用用户 |
| `UserListRoute` | 列出用户 |
| `WebserverAuthTokenGetRoute` / `WebserverAuthTokenUpdateRoute` | 游客令牌管理 |

#### 15.3.4 路由权限审计 — 需要补全 `minRole` 的路由

以下路由当前依赖默认值（隐式 GUEST），需要显式声明。

##### 应设为 GUEST 的路由（只读查询，游客可访问）

| 路由 | mode | requiresAuth | 理由 |
|------|------|-------------|------|
| `AsrConfigGetRoute` | Get | true | 读取 ASR 配置（公开信息） |
| `BilibiliFavoriteGetPageRoute` | Get | true | 读取收藏夹页（只读） |
| `BilibiliFavoriteGetVideoListRoute` | Get | true | 读取收藏夹视频列表（只读） |
| `BilibiliVideoGetPagesRoute` | Get | true | 读取视频分P列表（只读） |
| `BilibiliVideoSubtitleRoute` | Get | true | 读取字幕列表（只读） |
| `BilibiliVideoSubtitleBodyRoute` | Get | true | 读取字幕内容（只读） |
| `ImageProxyRoute` | Get | **false** | 图片代理（minRole 不生效但需声明） |
| `InstanceStatusRoute` | Get | **false** | 实例状态查询 |
| `InstanceInitRoute` | Post | **false** | 实例初始化（仅未初始化时可用） |
| `AuthLoginRoute` | Post | **false** | 登录（minRole 不生效但需声明） |
| `LlmCacheQueryRoute` | Get | true | 查询 LLM 缓存（只读） |
| `LlmModelAvailabilityRoute` | Get | true | 查询模型可用性（只读） |
| `LlmModelListRoute` | Get | true | 列出模型（只读） |
| `MaterialActiveTasksRoute` | Get | true | 查询活跃任务（只读） |
| `MaterialCategoryListRoute` | Get | true | 列出分类（只读） |
| `MaterialDownloadStatusRoute` | Get | true | 查询下载状态（只读） |
| `MaterialGetRoute` | Get | true | 读取素材详情（只读） |
| `MaterialLanguageGuessRoute` | Get | true | 猜测语言（只读） |
| `MaterialListRoute` | Get | true | 列出素材（只读） |
| `MaterialSubtitleContentRoute` | Get | true | 读取字幕内容（只读） |
| `MaterialSubtitleListRoute` | Get | true | 列出字幕（只读） |
| `MaterialTaskListRoute` | Get | true | 列出任务（只读） |
| `MaterialVideoCheckRoute` | Get | true | 检查视频状态（只读） |
| `MaterialWorkflowStatusRoute` | Get | true | 查询工作流状态（只读） |
| `NetworkTestConfigRoute` | Get | true | 读取网络测试配置（只读） |
| `PromptTemplateGetRoute` | Get | true | 读取模板（只读） |
| `PromptTemplateListRoute` | Get | true | 列出模板（只读） |
| `WebenConceptGetRoute` | Get | true | 读取概念（只读） |
| `WebenConceptListRoute` | Get | true | 列出概念（只读） |
| `WebenExtractionRunGetRoute` | Get | true | 读取提取运行（只读） |
| `WebenExtractionRunListRoute` | Get | true | 列出提取运行（只读） |
| `WebenNoteListRoute` | Get | true | 列出笔记（只读） |
| `WebenSegmentListRoute` | Get | true | 列出片段（只读） |
| `WebenSourceGetRoute` | Get | true | 读取来源（只读） |
| `WebenSourceListRoute` | Get | true | 列出来源（只读） |

> **设计原则**：所有 GET 只读查询路由设为 GUEST，允许游客浏览内容。`requiresAuth=false` 的路由虽然不经过角色检查，但仍需声明 minRole 以满足编译要求。

##### 应设为 TENANT 的路由（写操作 / 提交任务）

| 路由 | mode | 理由 |
|------|------|------|
| `BilibiliVideoDownloadRoute` | Post | **提交下载任务**（重型操作，消耗带宽和存储） |
| `BilibiliVideoAiConclusionRoute` | Post | **触发 AI 处理**（消耗 LLM 资源） |
| `NetworkTestRoute` | Post | **提交网络测试任务**（消耗网络资源） |
| `WorkerTaskListRoute` | Get | 列出所有 Worker 任务（租户需要查看自己提交的任务运行状态） |
| `WorkerTaskWfIdListRoute` | Get | 查询工作流 ID 列表（与 WorkerTaskListRoute 配套） |

> **WorkerTaskListRoute / WorkerTaskWfIdListRoute 设为 TENANT 而非 ROOT 的理由**：
> 租户需要能够查看当前正在运行的任务列表，以了解自己提交的工作流/下载/转码等任务的执行状态。
> 这些路由返回的是 WorkerEngine 中的活跃任务信息，属于业务可见性范畴而非管理功能。
> 游客不应看到任务列表（可能包含其他用户的任务信息），但租户可以。

##### 应设为 ROOT 的路由（管理操作）

| 路由 | mode | 理由 |
|------|------|------|
| `RestartTaskLogListRoute` | Get | 查看服务重启后的任务恢复日志（运维诊断功能） |
| `RestartTaskLogUpdateDispositionRoute` | Post | 更新重启日志的处置状态（运维操作） |

> **设计原则**：`RestartTaskLog` 是服务端运维信息，仅 ROOT 可见。与 WorkerTaskList 不同，重启日志不涉及用户的业务任务，而是系统级的故障恢复记录。

#### 15.3.5 `handleRoute()` 角色检查逻辑

**文件**：`shared/src/jvmMain/kotlin/com/github/project_fredica/api/FredicaApi.jvm.kt`

当前 `handleRoute()` 中的角色检查优化：

```kotlin
if (route.minRole != AuthRole.GUEST) {
    val roleLevel = when (identity) {
        is AuthIdentity.Authenticated -> identity.role.ordinal
        else -> AuthRole.GUEST.ordinal
    }
    if (roleLevel < route.minRole.ordinal) {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "权限不足"))
        return
    }
}
```

移除默认值后，这个优化仍然有效：
- `minRole == GUEST` 的路由跳过角色检查（任何已认证身份都满足）
- `minRole == TENANT` 或 `ROOT` 的路由执行 ordinal 比较
- `requiresAuth == false` 的路由在更早的阶段就跳过了整个认证流程

**不需要改动。**

---

### 15.4 路由 handler 添加用户上下文便捷访问 <Badge type="tip" text="已完成" />

#### 15.4.1 现状

当前路由 handler 中获取用户信息需要手动操作：

```kotlin
// 当前写法
val identity = RouteAuthContext.current()
val user = identity as? AuthIdentity.Authenticated ?: return mapOf("error" to "需要登录")
val userId = user.userId
```

#### 15.4.2 添加便捷方法

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/RouteAuthContext.kt`

在 `RouteAuthContext.Companion` 中添加：

```kotlin
/** 获取当前用户身份，如果未认证则抛异常（用于 requiresAuth=true 的路由） */
fun require(): AuthIdentity =
    current() ?: error("RouteAuthContext not available (route requires auth but identity is null)")

/** 获取当前用户（仅 Authenticated 身份），Guest 返回 null */
fun currentUser(): AuthIdentity.Authenticated? = current() as? AuthIdentity.Authenticated

/** 获取当前用户 ID，Guest/未认证返回 null */
fun currentUserId(): String? = (current() as? AuthIdentity.Authenticated)?.userId
```

使用示例：

```kotlin
// 简化后的写法
val user = RouteAuthContext.currentUser() ?: return mapOf("error" to "需要登录")
val userId = RouteAuthContext.currentUserId()
```

---

### 15.5 任务提交角色限制 <Badge type="tip" text="已完成" />

#### 15.5.1 问题

游客（仅持有 `webserver_auth_token`）理论上可以调用以下路由提交重型任务：

- `BilibiliVideoDownloadRoute`：下载 Bilibili 视频（消耗带宽、存储）
- `BilibiliVideoAiConclusionRoute`：触发 AI 总结（消耗 LLM API 额度）
- `NetworkTestRoute`：执行网络测试（消耗网络资源）

这些操作会消耗服务器资源，不应对游客开放。

#### 15.5.2 解决方案

在 15.3.4 中已将上述路由设为 TENANT。此外，确认以下已有 TENANT 限制的任务提交路由：

| 路由 | 已有 minRole | 任务类型 |
|------|-------------|---------|
| `MaterialVideoTranscodeMp4Route` | TENANT | 视频转码 |
| `MaterialWorkflowRoute` | TENANT | 工作流（ASR + 概念提取等） |
| `MaterialImportRoute` | TENANT | 素材导入 |
| `TaskCancelRoute` | TENANT | 取消任务 |
| `TaskPauseRoute` | TENANT | 暂停任务 |
| `TaskResumeRoute` | TENANT | 恢复任务 |
| `WebenSourceAnalyzeRoute` | TENANT | Weben 来源分析 |

#### 15.5.3 WorkerEngine 不需要角色检查

`WorkerEngine` 从数据库取任务并分发给 Executor 执行，不经过 HTTP 路由层。角色限制在路由层完成即可——只要游客无法通过 API 创建任务记录，WorkerEngine 就不会执行游客提交的任务。

---

### 15.6 AuthIdentity 类型重构：sealed class → sealed interface <Badge type="tip" text="已完成" />

#### 15.6.1 问题分析

原始 `AuthIdentity` 设计为 sealed class，使用单一 `User` 子类 + `role: AuthRole` 字段区分角色：

```kotlin
// 旧设计
sealed class AuthIdentity {
    data class User(
        val userId: String,
        val username: String,
        val displayName: String,
        val role: AuthRole,        // 运行时区分
        val permissions: String,
        val sessionId: String,
    ) : AuthIdentity()
    data object Guest : AuthIdentity()
}
```

这导致两个问题：

1. **ROOT 权限检查依赖运行时字段比较**：`identity !is AuthIdentity.User || identity.role != AuthRole.ROOT`，编译器无法保证覆盖所有分支
2. **无法利用 Kotlin 的 smart-cast**：`when (identity)` 只能匹配 `User` 和 `Guest`，无法按角色分支

#### 15.6.2 新设计：sealed interface + 角色子类型

**文件**：`shared/src/commonMain/kotlin/com/github/project_fredica/auth/AuthModels.kt`

```kotlin
sealed interface AuthIdentity {
    /** 已认证用户的公共接口（TenantUser / RootUser 共享字段） */
    sealed interface Authenticated : AuthIdentity {
        val userId: String
        val username: String
        val displayName: String
        val role: AuthRole
        val permissions: String
        val sessionId: String
    }

    data object Guest : AuthIdentity

    data class TenantUser(
        override val userId: String,
        override val username: String,
        override val displayName: String,
        override val permissions: String,
        override val sessionId: String,
    ) : Authenticated {
        override val role: AuthRole get() = AuthRole.TENANT
    }

    data class RootUser(
        override val userId: String,
        override val username: String,
        override val displayName: String,
        override val permissions: String,
        override val sessionId: String,
    ) : Authenticated {
        override val role: AuthRole get() = AuthRole.ROOT
    }
}
```

**设计要点**：

- `sealed interface`（非 sealed class）：允许 `Authenticated` 作为中间层 sealed interface
- `Authenticated` 聚合所有已认证用户的公共字段，`Guest` 不含这些字段
- `role` 由各子类型硬编码返回（`get() = AuthRole.TENANT`），不再是构造参数
- `TenantUser` / `RootUser` 的 `role` 不在构造函数中，避免构造时传错角色

#### 15.6.3 AuthService 构造点迁移

**文件**：`shared/src/jvmMain/kotlin/com/github/project_fredica/auth/AuthService.kt`

`resolveIdentity()` 中根据数据库 `user.role` 字段构造对应子类型：

```kotlin
val role = AuthRole.fromString(user.role)
return when (role) {
    AuthRole.ROOT -> AuthIdentity.RootUser(
        userId = user.id, username = user.username,
        displayName = user.displayName, permissions = user.permissions,
        sessionId = session.sessionId,
    )
    else -> AuthIdentity.TenantUser(
        userId = user.id, username = user.username,
        displayName = user.displayName, permissions = user.permissions,
        sessionId = session.sessionId,
    )
}
```

#### 15.6.4 路由层迁移模式

**ROOT 权限检查简化**（5 个路由）：

```kotlin
// 旧：运行时字段比较
val identity = RouteAuthContext.current()
if (identity !is AuthIdentity.User || identity.role != AuthRole.ROOT) {
    return buildJsonObject { put("error", "需要管理员权限") }.toValidJson()
}

// 新：类型检查，编译器保证 smart-cast
val identity = RouteAuthContext.current()
if (identity !is AuthIdentity.RootUser) {
    return buildJsonObject { put("error", "需要管理员权限") }.toValidJson()
}
// identity 此处自动 smart-cast 为 RootUser，可直接访问 userId、username 等
```

涉及路由：`UserEnableRoute`、`UserDisableRoute`、`WebserverAuthTokenGetRoute`、`WebserverAuthTokenUpdateRoute`、`UserListRoute`、`UserCreateRoute`

**TENANT 权限检查**（3 个路由）：

```kotlin
// 旧
if (identity !is AuthIdentity.User) { ... }

// 新
if (identity !is AuthIdentity.Authenticated) { ... }
```

涉及路由：`AuthLogoutRoute`、`UserChangePasswordRoute`、`UserUpdateDisplayNameRoute`

**邀请链接路由**（2 个路由）：

```kotlin
// 旧
val identity = RouteAuthContext.current() as? AuthIdentity.User

// 新
val identity = RouteAuthContext.current() as? AuthIdentity.Authenticated
```

涉及路由：`TenantInviteLinkCreateRoute`、`GuestInviteLinkCreateRoute`

**查询路由 when 表达式**（2 个路由）：

```kotlin
// 旧
is AuthIdentity.User -> identity.role.name.lowercase()

// 新
is AuthIdentity.Authenticated -> identity.role.name.lowercase()
```

涉及路由：`AuthMeRoute`、`AuthGuestValidateRoute`

#### 15.6.5 测试迁移

**文件**：`shared/src/jvmTest/kotlin/com/github/project_fredica/auth/AuthServiceTest.kt`

| 测试用例 | 变更 |
|----------|------|
| R3（session token → TenantUser） | `assertIs<AuthIdentity.User>` → `assertIs<AuthIdentity.TenantUser>` |
| R5（webserver_auth_token → Guest） | 添加 `WebserverAuthTokenCache.set("test-webserver-token")` |
| R9（root 角色解析） | `assertIs<AuthIdentity.User>` → `assertIs<AuthIdentity.RootUser>` |

---

### 15.7 jvmMain native routes

`getNativeRoutes()` 中注册的 JVM 专属路由也需要显式声明 `minRole`：

| 路由 | minRole | 理由 |
|------|---------|------|
| `WebenConceptTypeHintsRoute` | GUEST | 只读查询（Weben 模块，将废弃） |
| `TorchInstallCheckRoute` | TENANT | 检查 torch 安装状态（涉及系统环境信息） |

---

### 15.8 路由统计

完成 15.3 + 15.7 后的路由权限分布：

| minRole | commonRoutes | nativeRoutes | 合计 |
|---------|-------------|-------------|------|
| GUEST | 39 | 1 | 40 |
| TENANT | 31 | 1 | 32 |
| ROOT | 17 | 0 | 17 |
| **合计** | **87** | **2** | **89** |

所有 89 个路由均已显式声明 `minRole`，编译器保证不会遗漏。

---

### 15.9 文件变更清单

#### 后端

| 文件 | 变更 | 关联步骤 |
|------|------|----------|
| `shared/.../auth/WebserverAuthTokenCache.kt` | **新建** 内存缓存单例（invalidation + lazy reload） | 15.1.2 |
| `shared/.../auth/AuthService.kt` | `resolveIdentity()` 改用缓存读取 + 构造 `TenantUser`/`RootUser` | 15.1.3, 15.6.3 |
| `shared/.../api/FredicaApi.jvm.kt` | `init()` 中预热缓存 + `handleRoute()` 使用 `Authenticated` | 15.1.4, 15.6.4 |
| `shared/.../db/AppConfigDb.kt` | `updateConfig`/`updateConfigPartial` 中集中管理缓存失效 | 15.1.5 |
| `shared/.../api/routes/WebserverAuthTokenRoute.kt` | `RootUser` 类型检查（缓存失效由 AppConfigDb 自动处理） | 15.6.4 |
| `shared/.../db/AppConfig.kt` | `webserverAuthToken` 字段添加缓存失效注释 | 15.1.6 |
| `shared/.../api/FredicaApi.kt` | 移除 `minRole` 默认值 | 15.3.2 |
| `shared/.../api/routes/RouteAuthContext.kt` | 添加便捷方法 + `User` → `Authenticated` | 15.4.2, 15.6.4 |
| `shared/.../auth/AuthModels.kt` | `AuthIdentity` sealed class → sealed interface + 角色子类型 | 15.6.2 |
| `shared/.../api/routes/AuthMeRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/AuthGuestValidateRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/AuthLogoutRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/UserChangePasswordRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/UserUpdateDisplayNameRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/UserEnableRoute.kt` | `User + role check` → `RootUser` | 15.6.4 |
| `shared/.../api/routes/UserDisableRoute.kt` | `User + role check` → `RootUser` | 15.6.4 |
| `shared/.../api/routes/UserListRoute.kt` | `User + role check` → `RootUser` | 15.6.4 |
| `shared/.../api/routes/UserCreateRoute.kt` | `User + role check` → `RootUser` | 15.6.4 |
| `shared/.../api/routes/TenantInviteLinkCreateRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| `shared/.../api/routes/GuestInviteLinkCreateRoute.kt` | `User` → `Authenticated` | 15.6.4 |
| ~43 个路由文件 | 补全 `override val minRole` | 15.3.4, 15.7 |

#### 测试

| 文件 | 变更 | 关联步骤 |
|------|------|----------|
| `shared/.../auth/AuthServiceTest.kt` | `User` → `TenantUser`/`RootUser` + 缓存初始化 | 15.6.5 |

#### 前端

无改动（15.2 分析结论：不需要）。

---

### 15.10 实施顺序

1. **Step 15.1**（后端缓存）→ 编译验证 ✅
2. **Step 15.3.2**（移除 `minRole` 默认值）→ 编译失败，获取完整的需要修改的路由列表 ✅
3. **Step 15.3.4**（补全所有路由 `minRole`）→ 编译通过 ✅
4. **Step 15.4**（RouteAuthContext 便捷方法）→ 编译验证 ✅
5. **Step 15.5**（确认任务提交路由已覆盖）✅
6. **Step 15.6**（AuthIdentity sealed interface 重构）→ 编译 + 测试通过 ✅

---

### 15.11 验证

#### 编译验证

```shell
# 后端编译（最关键 — 移除默认值后必须全部编译通过）
./gradlew :shared:compileKotlinJvm

# 运行现有测试（确保缓存不破坏现有认证逻辑）
./gradlew :shared:jvmTest
```

#### 自动化测试

##### WebserverAuthTokenCache 单元测试

**文件**：`shared/src/jvmTest/.../auth/WebserverAuthTokenCacheTest.kt`（新建）

| 测试用例 | 验证点 |
|----------|--------|
| C1: 未 init 时 get() 从 DB 加载 | 默认空字符串（lazy reload） |
| C2: init 后 get() 返回预热值 | 基本预热读取 |
| C3: invalidate 后 get() 重新从 DB 加载 | `updateConfigPartial` 自动 invalidate → lazy reload |
| C4: updateConfig 触发 invalidate | 全量更新也触发缓存失效 |
| C5: updateConfigPartial 仅在包含 webserver_auth_token 时 invalidate | 不相关 key 不影响缓存 |
| C6: 多次 init 取最后值 | 覆盖语义 |
| C7: init 空字符串 | 空字符串预热 |

##### 路由 minRole 一致性测试

**文件**：`shared/src/jvmTest/.../api/RouteMinRoleTest.kt`（新建）

| 测试用例 | 验证点 |
|----------|--------|
| M1: 所有 commonRoutes 都有显式 minRole | 编译器已保证，运行时双重验证 |
| M2: 任务提交 POST 路由至少 TENANT | BilibiliVideoDownload、AiConclusion、Transcode、Workflow、Import、NetworkTest |
| M3: requiresAuth=false 的路由应为 GUEST | 无需认证的路由 minRole 必须是 GUEST |
| M4: 管理路由必须 ROOT | RestartTaskLog*、WebserverAuthToken*、User* |
| M5: 写操作路由至少 TENANT | MaterialDelete、CategoryCreate/Delete、SetCategories、PromptTemplate*、PostprocessSubtitleSave、TaskCancel/Pause/Resume、LlmCacheInvalidate、LlmModelProbe |

##### 缓存集成测试

**文件**：`shared/src/jvmTest/.../auth/AuthServiceCacheTest.kt`（新建）

| 测试用例 | 验证点 |
|----------|--------|
| IC1: 缓存命中 — 有效游客令牌 | `resolveIdentity()` 返回 `AuthIdentity.Guest` |
| IC2: 缓存命中 — 无效令牌 | `resolveIdentity()` 返回 null |
| IC3: 缓存失效后 lazy reload | `updateConfigPartial` 更新 token 后旧 token 失效，新 token 通过 lazy reload 生效 |
| IC4: 空缓存时跳过游客检查 | DB 中 token 为空时不匹配任何 token |
| IC5: updateConfig 触发缓存失效 | 全量 `updateConfig` 后 `resolveIdentity` 正确使用新 token |

#### 手动测试场景

1. **缓存生效**：启动服务器 → 游客令牌验证正常工作（走缓存而非 SQLite）
2. **缓存失效**：ROOT 用户修改游客令牌 → 旧令牌立即失效，新令牌立即生效
3. **角色限制**：游客访问 `BilibiliVideoDownloadRoute` → 403 Forbidden
4. **角色限制**：TENANT 用户访问 `BilibiliVideoDownloadRoute` → 正常
5. **只读路由**：游客访问 `MaterialListRoute` → 正常（GUEST 级别）
6. **任务列表**：TENANT 用户访问 `WorkerTaskListRoute` → 正常
7. **任务列表**：游客访问 `WorkerTaskListRoute` → 403 Forbidden
8. **管理路由**：TENANT 用户访问 `RestartTaskLogListRoute` → 403（需要 ROOT）
9. **类型安全**：ROOT 路由中 `identity` smart-cast 为 `RootUser`，可直接访问 `userId`
10. **类型安全**：TENANT 路由中 `identity` smart-cast 为 `Authenticated`，可直接访问 `role`

### Phase 15.12：邀请链接管理路由 Handler 测试

> **状态**：✅ 已完成（2026-04-16）
> **测试文件**：4 个 · **测试用例**：36 个 · **通过率**：100%

#### 覆盖范围

| 测试文件 | 路由 | 用例数 | 说明 |
|----------|------|--------|------|
| `GuestInviteLinkManagementRouteTest` | `GuestInviteLinkListRoute` · `GuestInviteLinkDeleteRoute` · `GuestInviteLinkUpdateRoute` | 13 (GL1-GL13) | 列表/删除/更新 + 级联删除访问记录 |
| `GuestInviteVisitListRouteTest` | `GuestInviteVisitListRoute` | 5 (GV1-GV5) | 访问记录列表 + 分页 + 缺少参数 |
| `TenantInviteLinkManagementRouteTest` | `TenantInviteLinkListRoute` · `TenantInviteLinkDeleteRoute` · `TenantInviteLinkUpdateRoute` | 12 (TL1-TL12) | 列表/删除/更新 + 有注册记录时禁止删除 |
| `TenantInviteRegistrationListRouteTest` | `TenantInviteRegistrationListRoute` | 6 (TR1-TR6) | 注册记录列表 + LEFT JOIN 用户信息 |

#### 关键测试场景

**Guest 邀请链接管理（GL1-GL13）：**
- GL1-GL3：列表（空/多个/含 visit_count 聚合）
- GL4-GL5：删除成功 + 级联删除访问记录（`visitDb.countByLinkId` 验证）
- GL6-GL7：删除不存在/参数无效
- GL8-GL13：更新 label/status/同时更新 + 不存在/参数无效/无效 status

**Guest 访问记录列表（GV1-GV5）：**
- GV1-GV2：正常列出/空记录（验证 total + items 字段）
- GV3：分页参数（limit=2, offset=1，total=5 但 items=2）
- GV4-GV5：缺少 link_id / 参数无效

**Tenant 邀请链接管理（TL1-TL12）：**
- TL1-TL3：列表（空/多个/含 used_count 聚合）
- TL4-TL5：删除成功（无注册记录）/ 有注册记录时删除失败（"注册记录"错误）
- TL6-TL7：删除不存在/参数无效
- TL8-TL12：更新 label/status + 不存在/参数无效/无效 status

**Tenant 注册记录列表（TR1-TR6）：**
- TR1-TR2：正常列出/空记录
- TR3：包含用户信息（LEFT JOIN user 表，验证 username/display_name）
- TR4：用户不存在时 username/display_name 为 null（LEFT JOIN 未匹配）
- TR5-TR6：缺少 link_id / 参数无效

#### 技术要点

- **Guest vs Tenant 删除策略差异**：Guest 链接删除时级联删除访问记录（`visitDb.deleteByLinkId`）；Tenant 链接有注册记录时禁止删除（保护用户数据）
- **TenantInviteRegistrationListRoute 需要 UserDb**：`listByLinkId()` 内部 LEFT JOIN user 表获取 username/display_name，测试 setup 必须初始化 `UserDb`
- **GET 路由参数格式**：`Map<String, List<String>>` JSON，测试中用 `buildJsonObject` 构造

---

## 十六、路由框架重构

> **目标**：消除路由框架中的历史遗留问题——协程上下文滥用、散落的特殊路由、不合理的 Cookie 命名——为后续数据归属（17.x）奠定干净的基础。

### 16.1 移除协程上下文绑定，改用显式 handler 参数

#### 问题

当前 `RouteAuthContext` 和 `RouteRequestContext` 通过 `AbstractCoroutineContextElement` 注入协程上下文，路由 handler 内部通过 `coroutineContext[Key]` 读取。这导致：

1. **编辑器警告**：`'kotlin.coroutine.coroutineContext' 的用法可能存在歧义`
2. **隐式依赖**：handler 签名 `suspend fun handler(param: String): Any` 看不出它依赖身份信息
3. **测试复杂**：测试必须用 `withContext(RouteAuthContext(identity) + RouteRequestContext(ip, ua))` 包裹调用
4. **不必要的复杂性**：IP/UA 只有 2 个路由使用，完全不值得绑定到协程上下文

#### 当前状态

```kotlin
// FredicaApi.kt — 当前签名
interface Route {
    suspend fun handler(param: String): Any
}

// RouteAuthContext.kt — 协程上下文元素
class RouteAuthContext(val identity: AuthIdentity) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RouteAuthContext> {
        suspend fun current(): AuthIdentity? = coroutineContext[RouteAuthContext]?.identity
        suspend fun require(): AuthIdentity = current() ?: error("...")
        suspend fun currentUser(): AuthIdentity.Authenticated? = current() as? AuthIdentity.Authenticated
        suspend fun currentUserId(): String? = (current() as? AuthIdentity.Authenticated)?.userId
    }
}

// RouteRequestContext.kt — 协程上下文元素
class RouteRequestContext(val ip: String?, val ua: String?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RouteRequestContext> {
        suspend fun clientIp(): String? = coroutineContext[RouteRequestContext]?.ip
        suspend fun userAgent(): String? = coroutineContext[RouteRequestContext]?.ua
    }
}
```

#### 目标设计

**新 handler 签名**：

```kotlin
// FredicaApi.kt — 新签名
interface Route {
    suspend fun handler(param: String, context: RouteContext): Any
}

/**
 * 路由执行上下文，由 handleRoute() 构造并传入 handler。
 * 替代原来的 RouteAuthContext + RouteRequestContext 协程上下文注入。
 */
data class RouteContext(
    val identity: AuthIdentity?,
    val clientIp: String?,
    val userAgent: String?,
) {
    /** 获取已认证用户身份，Guest 返回 null */
    val authenticatedUser: AuthIdentity.Authenticated?
        get() = identity as? AuthIdentity.Authenticated

    /** 获取已认证用户 ID，Guest/未认证返回 null */
    val userId: String?
        get() = authenticatedUser?.userId

    /** 要求身份存在，否则抛异常（用于 requiresAuth=true 的路由） */
    fun requireIdentity(): AuthIdentity =
        identity ?: error("RouteContext: identity is null (route requires auth but identity is missing)")
}
```

**`RouteContext` 放置位置**：`shared/src/commonMain/.../api/routes/RouteContext.kt`（新文件，替代 `RouteAuthContext.kt` + `RouteRequestContext.kt`）。

#### 影响范围

**使用 `RouteAuthContext.current()` 的 14 个路由**（全部在 commonMain）：

| 路由 | 用法 |
|------|------|
| `AuthMeRoute` | `RouteAuthContext.current()` → `context.identity` |
| `AuthGuestValidateRoute` | `RouteAuthContext.current()` → `context.identity` |
| `AuthLogoutRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserUpdateDisplayNameRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserListRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserEnableRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserDisableRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserCreateRoute` | `RouteAuthContext.current()` → `context.identity` |
| `UserChangePasswordRoute` | `RouteAuthContext.current()` → `context.identity` |
| `GuestInviteLinkCreateRoute` | `RouteAuthContext.current() as? Authenticated` → `context.authenticatedUser` |
| `TenantInviteLinkCreateRoute` | `RouteAuthContext.current() as? Authenticated` → `context.authenticatedUser` |
| `WebserverAuthTokenGetRoute` | `RouteAuthContext.current()` → `context.identity` |
| `WebserverAuthTokenUpdateRoute` | `RouteAuthContext.current()` → `context.identity` |

**使用 `RouteRequestContext` 的 2 个路由**（全部在 commonMain）：

| 路由 | 用法 |
|------|------|
| `GuestInviteLandingRoute` | `RouteRequestContext.clientIp()` / `.userAgent()` → `context.clientIp` / `context.userAgent` |
| `TenantInviteRegisterRoute` | 同上 |

**不使用任何上下文的路由**：其余 ~70 个路由的 handler 签名也需要加 `context: RouteContext` 参数，但内部不使用。

#### handleRoute() 变更

```kotlin
// FredicaApi.jvm.kt — 变更前
withContext(RouteAuthContext(identity) + reqCtx) { route.handler(getBody()) }

// FredicaApi.jvm.kt — 变更后
val routeContext = RouteContext(
    identity = identity,
    clientIp = clientIp,
    userAgent = call.request.userAgent(),
)
route.handler(getBody(), routeContext)
```

不再需要 `withContext`，直接传参。

#### 测试变更

**受影响的 8 个测试文件**：

| 测试文件 | 变更 |
|----------|------|
| `RouteAuthContextTest.kt` | 删除或重写为 `RouteContextTest.kt`，测试 `RouteContext` 的属性方法 |
| `AuthSessionRouteTest.kt` | `withContext(RouteAuthContext(identity))` → 直接传 `RouteContext(identity, null, null)` |
| `WebserverAuthTokenRouteTest.kt` | 同上 |
| `UserSelfServiceRouteTest.kt` | 同上 |
| `UserManagementRouteTest.kt` | 同上 |
| `GuestInviteLandingRouteTest.kt` | `withContext(RouteAuthContext + RouteRequestContext)` → 传 `RouteContext(identity, ip, ua)` |
| `TenantInviteRegisterRouteTest.kt` | 同上 |
| `InviteIntegrationTest.kt` | 同上 |

**测试代码变更示例**：

```kotlin
// 变更前
val result = withContext(RouteAuthContext(identity) + RouteRequestContext(ip, ua)) {
    SomeRoute.handler(paramJson)
}

// 变更后
val result = SomeRoute.handler(paramJson, RouteContext(identity, ip, ua))
```

#### 删除文件

- `shared/src/commonMain/.../api/routes/RouteAuthContext.kt` — 删除
- `shared/src/commonMain/.../api/routes/RouteRequestContext.kt` — 删除

---

### 16.2 路由归一化：消除散落的特殊路由

#### 问题

`FredicaApi.jvm.kt` 的 `Application.initRoute` 中有 5 个路由绕过通用路由框架，手动注册：

```kotlin
// 当前散落的特殊路由
post("/api/v1/LlmProxyChatRoute") { checkAuth(); LlmProxyChatRoute.handle(this) }
get(MaterialVideoStreamRoute.PATH) { MaterialVideoStreamRoute.handle(this) }
post("/api/v1/PromptTemplateRunRoute") { checkAuth(); PromptTemplateRunRoute.handle(this) }
post("/api/v1/PromptTemplatePreviewRoute") { checkAuth(); PromptTemplatePreviewRoute.handle(this) }
post("/api/v1/PromptScriptGenerateRoute") { checkAuth(); PromptScriptGenerateRoute.handle(this) }
```

它们绕过通用框架的原因各不相同：

| 路由 | 绕过原因 | 实际必要性 |
|------|----------|------------|
| `LlmProxyChatRoute` | SSE 流式响应 | 需要 SSE 支持 |
| `MaterialVideoStreamRoute` | Cookie 认证 + 文件流 | 需要 Cookie 认证 |
| `PromptTemplateRunRoute` | GraalJS 依赖在 jvmMain | **不必要**，可归一化 |
| `PromptTemplatePreviewRoute` | SSE + GraalJS | 需要 SSE 支持 |
| `PromptScriptGenerateRoute` | SSE + LLM 流式 | 需要 SSE 支持 |

#### 归一化策略

##### 策略 A：非 SSE 路由直接归一化

`PromptTemplateRunRoute` 返回普通 JSON，完全不需要特殊处理。只需：

1. 让它实现 `FredicaApi.Route` 接口
2. 将 `handle(ctx)` 改为 `handler(param, context)`
3. 加入 `all_routes.kt` 的 `getCommonRoutes()` 列表
4. 从 `initRoute` 中删除手动注册

但 `PromptTemplateRunRoute` 在 **jvmMain**（依赖 GraalJS），而 `getCommonRoutes()` 在 **commonMain**。因此需要将其加入 `getNativeRoutes()` 而非 `getCommonRoutes()`。

##### 策略 B：SSE 路由归一化

SSE 路由的核心问题是 `handler()` 返回 `Any`（JSON 对象），但 SSE 需要直接操作 `RoutingContext.call.respondBytesWriter()`。

**方案：引入 `Route.Mode.Sse` + `SseRoute` 子接口**

```kotlin
// FredicaApi.kt — 扩展 Mode 枚举
interface Route {
    enum class Mode { Get, Post, Sse }

    val mode: Mode
    suspend fun handler(param: String, context: RouteContext): Any

    companion object
}

/**
 * SSE 流式路由的专用接口。
 * handler() 返回 Unit（流式内容通过 ctx 直接写入），
 * 框架不对返回值做 JSON 序列化。
 */
interface SseRoute : FredicaApi.Route {
    override val mode: FredicaApi.Route.Mode get() = FredicaApi.Route.Mode.Sse

    /**
     * SSE 路由的实际处理方法。
     * 接收 RoutingContext 以便直接调用 call.respondBytesWriter()。
     * 鉴权、角色检查、权限检查由框架在调用前完成。
     */
    suspend fun handleSse(ctx: RoutingContext, context: RouteContext)

    /**
     * 默认 handler 实现：不应被调用，SSE 路由走 handleSse 路径。
     */
    override suspend fun handler(param: String, context: RouteContext): Any {
        error("SSE route should use handleSse(), not handler()")
    }
}
```

**`initRoute` 中的通用 SSE 处理**：

```kotlin
// FredicaApi.jvm.kt — routing 块中
for (route in allRoutes) {
    when (route.mode) {
        FredicaApi.Route.Mode.Get -> get("/api/v1/${route.name}") {
            handleRoute(route) { json.encodeToString(call.queryParameters.toMap()) }
        }
        FredicaApi.Route.Mode.Post -> post("/api/v1/${route.name}") {
            handleRoute(route) { call.receiveText() }
        }
        FredicaApi.Route.Mode.Sse -> post("/api/v1/${route.name}") {
            handleSseRoute(route as SseRoute)
        }
    }
}
```

**`handleSseRoute()` 实现**：

```kotlin
suspend fun RoutingContext.handleSseRoute(route: SseRoute) {
    // 复用 handleRoute 的鉴权 + 角色 + 权限检查逻辑
    if (route.requiresAuth && !checkAuth()) return
    val identity = call.attributes.getOrNull(AuthIdentityKey)
    // ... 角色检查、权限检查（与 handleRoute 相同）...
    val routeContext = RouteContext(
        identity = identity,
        clientIp = extractClientIp(),
        userAgent = call.request.userAgent(),
    )
    route.handleSse(this, routeContext)
}
```

##### 策略 C：Cookie 认证路由归一化

`MaterialVideoStreamRoute` 使用 Cookie 认证（因为 HTML5 `<video src>` 无法携带 Authorization header）。

**方案：引入 `Route.Mode.CookieGet` 或在 `checkAuth()` 中支持 Cookie 回退**

推荐后者——在 `checkAuth()` 中增加 Cookie 回退逻辑：

```kotlin
private suspend fun RoutingContext.checkAuth(): Boolean {
    // 优先读 Authorization header
    var authHead = call.request.headers["Authorization"]
    // 回退：读 Cookie（供 <video src> 等无法携带 header 的场景）
    if (authHead == null) {
        val cookieToken = call.request.cookies["fredica_media_token"]
        if (cookieToken != null) authHead = "Bearer $cookieToken"
    }
    val identity = AuthService.resolveIdentity(authHead)
    if (identity == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return false
    }
    call.attributes.put(AuthIdentityKey, identity)
    return true
}
```

这样 `MaterialVideoStreamRoute` 可以作为普通 `Mode.Get` 路由归一化，不再需要手动 Cookie 读取。

但 `MaterialVideoStreamRoute` 还有文件流响应（`call.respondFile()`）+ ETag/304 处理，这些不适合通用 `handler()` 返回 JSON 的模式。

**方案：引入 `StreamRoute` 子接口**（类似 `SseRoute`）

```kotlin
/**
 * 文件流路由的专用接口。
 * handler() 返回 Unit（文件内容通过 ctx 直接写入），
 * 框架不对返回值做 JSON 序列化。
 */
interface StreamRoute : FredicaApi.Route {
    /**
     * 流式路由的实际处理方法。
     * 接收 RoutingContext 以便直接调用 call.respondFile() 等。
     * 鉴权由框架完成（支持 Cookie 回退）。
     */
    suspend fun handleStream(ctx: RoutingContext, context: RouteContext)

    override suspend fun handler(param: String, context: RouteContext): Any {
        error("Stream route should use handleStream(), not handler()")
    }
}
```

**`initRoute` 中的通用流式处理**：

```kotlin
FredicaApi.Route.Mode.Get -> {
    if (route is StreamRoute) {
        get("/api/v1/${route.name}") { handleStreamRoute(route) }
    } else {
        get("/api/v1/${route.name}") { handleRoute(route) { ... } }
    }
}
```

#### 各路由归一化方案

| 路由 | 当前位置 | 归一化方式 | 注册位置 |
|------|----------|------------|----------|
| `PromptTemplateRunRoute` | jvmMain | 实现 `Route`，`handler()` 返回 JSON | `getNativeRoutes()` |
| `LlmProxyChatRoute` | commonMain | 实现 `SseRoute`，`handleSse()` | `getCommonRoutes()` |
| `PromptTemplatePreviewRoute` | jvmMain | 实现 `SseRoute`，`handleSse()` | `getNativeRoutes()` |
| `PromptScriptGenerateRoute` | jvmMain | 实现 `SseRoute`，`handleSse()` | `getNativeRoutes()` |
| `MaterialVideoStreamRoute` | jvmMain | 实现 `StreamRoute`，`handleStream()` | `getNativeRoutes()` |

#### 涉及文件清单

**新增**：
- `shared/src/commonMain/.../api/routes/RouteContext.kt` — `RouteContext` data class
- `shared/src/commonMain/.../api/SseRoute.kt` — `SseRoute` 接口（或放在 `FredicaApi.kt` 中）
- `shared/src/commonMain/.../api/StreamRoute.kt` — `StreamRoute` 接口

**修改**：
- `shared/src/commonMain/.../api/FredicaApi.kt` — `Route.Mode` 加 `Sse`；`handler()` 签名加 `context`
- `shared/src/jvmMain/.../api/FredicaApi.jvm.kt` — `handleRoute()` 改用 `RouteContext`；新增 `handleSseRoute()` / `handleStreamRoute()`；`routing` 块删除手动注册，改用通用循环
- `shared/src/commonMain/.../api/routes/all_routes.kt` — `LlmProxyChatRoute` 加入列表
- `shared/src/jvmMain/.../api/routes/all_routes.jvm.kt`（新增或已有）— 3 个 jvmMain 路由加入 `getNativeRoutes()`
- `shared/src/commonMain/.../api/routes/LlmProxyChatRoute.kt` — 实现 `SseRoute`
- `shared/src/jvmMain/.../api/routes/PromptTemplateRunRoute.kt` — 实现 `Route`
- `shared/src/jvmMain/.../api/routes/PromptTemplatePreviewRoute.kt` — 实现 `SseRoute`
- `shared/src/jvmMain/.../api/routes/PromptScriptGenerateRoute.kt` — 实现 `SseRoute`
- `shared/src/jvmMain/.../api/routes/MaterialVideoStreamRoute.kt` — 实现 `StreamRoute`

**删除**：
- `shared/src/commonMain/.../api/routes/RouteAuthContext.kt`
- `shared/src/commonMain/.../api/routes/RouteRequestContext.kt`

---

### 16.3 Cookie 重命名：`fredica_token` → `fredica_media_token`

#### 问题

当前 Cookie 名 `fredica_token` 暗示它是主认证 token，但实际上它只是一个**浏览器自动携带的媒体访问凭证**，专门用于 HTML5 `<video src="...">` 等无法手动附加 `Authorization` header 的场景。

#### 命名选择

| 候选名 | 优点 | 缺点 |
|--------|------|------|
| `fredica_media_token` | 明确表达"媒体访问"用途 | 稍长 |
| `fredica_stream_token` | 强调"流式"场景 | 未来可能用于非流式媒体（如图片） |
| `fredica_bearer_cookie` | 技术上准确 | 不直观 |

**选择 `fredica_media_token`**：最准确地描述用途——这是一个用于媒体资源访问的 Cookie 形式 token。

#### 工作流程

```mermaid
sequenceDiagram
    participant KT as Kotlin (composeApp)
    participant WV as WebView (JS)
    participant BR as Browser
    participant KR as Ktor (MaterialVideoStreamRoute)

    Note over WV: 启动时从 localStorage 恢复配置
    WV->>WV: document.cookie = "fredica_media_token=<token>; path=/; SameSite=Strict"

    WV->>KT: callBridge("get_server_info")
    KT-->>WV: { webserver_auth_token: "<token>", ... }
    WV->>WV: document.cookie = "fredica_media_token=<webserver_auth_token>; ..."

    Note over WV: 用户登录后 setAppConfig({ session_token })
    WV->>WV: document.cookie = "fredica_media_token=<session_token>; ..."
    Note over WV: session_token 优先于 webserver_auth_token

    Note over BR: 渲染 <video src="http://localhost:7631/api/v1/MaterialVideoStreamRoute?material_id=xxx" crossOrigin="use-credentials">
    BR->>KR: GET /api/v1/MaterialVideoStreamRoute?material_id=xxx\nCookie: fredica_media_token=<token>
    Note over BR: 浏览器自动携带同域 Cookie，无需手动设置 header

    KR->>KR: token = cookies["fredica_media_token"]
    KR->>KR: AuthService.resolveIdentity("Bearer <token>")
    KR-->>BR: 206 Partial Content (video/mp4, Range 支持)
```

**代码出处：**

| 步骤 | 文件 | 位置 |
|------|------|------|
| localStorage 恢复时写入 Cookie | `fredica-webui/app/context/appConfig.tsx` | `useEffect` 内，`json_parse` 恢复后 |
| bridge 获取 server info 后写入 | `fredica-webui/app/context/appConfig.tsx` | `callBridge("get_server_info").then(...)` 回调 |
| 登录/配置更新时写入 | `fredica-webui/app/context/appConfig.tsx` | `setAppConfig()` 函数内 |
| bridge 消息处理（返回 token） | `composeApp/.../messages/GetServerInfoJsMessageHandler.kt` | `handle()` 方法 |
| `<video>` 标签渲染 | `fredica-webui/app/components/material/MaterialVideoPlayer.tsx` | `crossOrigin="use-credentials"` |
| 服务端读取 Cookie 并验证 | `shared/jvmMain/.../api/routes/MaterialVideoStreamRoute.kt` | `handle()` 方法，`cookies["fredica_media_token"]` |

**关键点：**
- Cookie 由前端 JS `document.cookie` 写入，**不经过 Set-Cookie 响应头**
- `crossOrigin="use-credentials"` 确保跨域 `<video>` 请求也携带 Cookie
- `session_token`（登录后）优先于 `webserver_auth_token`（guest 模式）
- Range 请求（seek）由 Ktor PartialContent 插件自动处理，Cookie 随每次分片请求自动携带

#### 涉及变更

**后端**（1 处读取）：
- `MaterialVideoStreamRoute.kt`：`call.request.cookies["fredica_media_token"]`
- `checkAuth()` 不添加 Cookie fallback——`fredica_media_token` 仅供媒体路由使用

**前端**（3 处写入，`appConfig.tsx`）：
- localStorage 恢复时写入
- bridge 获取 server info 后写入（`webserver_auth_token`）
- `setAppConfig` 更新时写入（`session_token` 优先）

**测试**（1 处）：
- `MaterialVideoStreamRouteTest.kt`：`cookie("fredica_media_token", ...)`

#### 兼容性

无需向后兼容——Cookie 是客户端写入的临时凭证，前后端同步更新即可。旧 Cookie 会自然过期。

---

### 16.4 实施顺序与依赖

```
16.1 handler 签名变更 ──→ 16.2 路由归一化 ──→ 16.3 Cookie 重命名
         │                       │
         │                       ├── SseRoute 接口
         │                       ├── StreamRoute 接口
         │                       └── checkAuth() Cookie 回退
         │
         ├── RouteContext 新建
         ├── RouteAuthContext 删除
         ├── RouteRequestContext 删除
         └── 所有 handler 签名更新
```

**16.1 必须先做**：因为 16.2 的 `SseRoute.handleSse()` 和 `StreamRoute.handleStream()` 都需要接收 `RouteContext` 参数。

**16.3 可以和 16.2 并行**：Cookie 重命名与路由归一化无依赖关系，但如果 16.2 的 `checkAuth()` Cookie 回退先完成，16.3 只需改前端和 `checkAuth()` 中的 Cookie 名。

#### 变更细则

##### 16.1 详细步骤

1. **新建 `RouteContext.kt`**（commonMain）
2. **修改 `FredicaApi.kt`**：`handler(param: String)` → `handler(param: String, context: RouteContext)`
3. **修改所有 ~80 个路由的 handler 签名**：加 `context: RouteContext` 参数
4. **修改 14 个使用 `RouteAuthContext` 的路由**：`RouteAuthContext.current()` → `context.identity`
5. **修改 2 个使用 `RouteRequestContext` 的路由**：`RouteRequestContext.clientIp()` → `context.clientIp`
6. **修改 `FredicaApi.jvm.kt` 的 `handleRoute()`**：构造 `RouteContext` 并传入
7. **修改 8 个测试文件**：去掉 `withContext` 包裹，改为直接传 `RouteContext`
8. **删除 `RouteAuthContext.kt` 和 `RouteRequestContext.kt`**
9. **编译验证**：`./gradlew :shared:build`
10. **运行测试**：`./gradlew :shared:jvmTest`

##### 16.2 详细步骤

1. **修改 `FredicaApi.kt`**：`Mode` 枚举加 `Sse`
2. **新建 `SseRoute.kt`**（commonMain）：`SseRoute` 接口
3. **新建 `StreamRoute.kt`**（commonMain）：`StreamRoute` 接口
4. **修改 `checkAuth()`**：增加 Cookie 回退逻辑
5. **新增 `handleSseRoute()` 和 `handleStreamRoute()`**（jvmMain）
6. **改造 `LlmProxyChatRoute`**：实现 `SseRoute`，`handle(ctx)` → `handleSse(ctx, context)`
7. **改造 `PromptTemplateRunRoute`**：实现 `Route`，`handle(ctx)` → `handler(param, context)`
8. **改造 `PromptTemplatePreviewRoute`**：实现 `SseRoute`
9. **改造 `PromptScriptGenerateRoute`**：实现 `SseRoute`
10. **改造 `MaterialVideoStreamRoute`**：实现 `StreamRoute`，移除手动 Cookie 读取
11. **更新路由注册列表**：`getCommonRoutes()` 加 `LlmProxyChatRoute`；`getNativeRoutes()` 加其余 4 个
12. **清理 `initRoute` routing 块**：删除 5 个手动注册，只保留 `/api/v1/ping` 和通用循环
13. **编译验证 + 测试**

##### 16.3 详细步骤

1. **修改 `MaterialVideoStreamRoute.kt`**：Cookie 名 `fredica_token` → `fredica_media_token`
2. **修改 `appConfig.tsx`**：3 处 Cookie 名更新
3. **修改 `MaterialVideoStreamRouteTest.kt`**：Cookie 名更新
4. **`checkAuth()` 不添加 Cookie fallback**：`fredica_media_token` 仅供 `<video src>` 等无法设置 header 的场景，不作为通用鉴权
5. **测试验证**

---

## 十七、数据归属体系

> 以下章节仅记录规划方向，本次不实施。依赖 16.x 路由框架重构完成后再开始。

### 17.x 素材归属：可选多对多中间表

> **目标**：让素材数据具备用户归属，TENANT 用户只能看到自己的数据，ROOT 可看全部。

#### 核心洞察：Material-User 是可选的多对多关系

素材与用户的关系**不是一对一**：

- **B站素材**：用户无关，任何人（包括 Guest）都可以查看解析结果。同一个 B站视频可以被多个用户导入。
- **本地文件上传**（未来）：独属于上传用户，其他用户不可见。
- **历史数据**：迁移前导入的素材无归属，应保持可见。

因此 Material 对 User 的关联是**可有可无的多对多**，必须通过**中间表**解决，而不是在 material 表加 `user_id` 列。

#### Schema 设计

```sql
-- 新表：素材-用户关联（中间表）
CREATE TABLE IF NOT EXISTS material_user (
    material_id TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    role        TEXT NOT NULL DEFAULT 'owner',   -- owner / viewer / editor（预留）
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (material_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_material_user_user ON material_user(user_id);
```

**不修改 material 表**——material 表保持用户无关。

#### 归属规则

| 场景 | 行为 |
|------|------|
| B站素材导入 | 不写 `material_user`（或可选写入，由用户决定"收藏"） |
| 本地文件上传 | 写入 `material_user(material_id, user_id, 'owner')` |
| TENANT 查询素材列表 | 返回：`material_user` 中有记录的 + 无任何 `material_user` 记录的（公共素材） |
| ROOT 查询素材列表 | 返回全部 |
| Guest 查询素材列表 | 返回无任何 `material_user` 记录的（公共素材） |

#### 查询模式

```sql
-- TENANT 用户查询：自己的 + 公共的
SELECT m.* FROM material m
LEFT JOIN material_user mu ON m.id = mu.material_id
WHERE mu.user_id = ? OR mu.material_id IS NULL
GROUP BY m.id

-- 等价写法（更清晰）
SELECT m.* FROM material m
WHERE m.id IN (SELECT material_id FROM material_user WHERE user_id = ?)
   OR m.id NOT IN (SELECT material_id FROM material_user)
```

#### 数据归属链

`User ←→ Material（通过 material_user）→ WorkflowRun → Task`

- `workflow_run` 和 `task` 不需要加 `user_id`，通过 `material_id` 间接关联
- 归属校验在路由层完成，DB 层只负责查询

#### 不在 17.x 范围内

- `material_category` 用户化 → 18.A
- `prompt_template` 用户化 → 18.C
- LLM 调用计费 → 18.B
- 素材"公开/私有"标记 → `material_user` 的 `role` 字段已预留扩展

---

## 十八、旧接口重构

> 以下章节仅记录规划方向，本次不实施。依赖 16.x 路由框架重构完成后再开始。

### 18.A 分类用户化重构

分类与用户关联，支持公开分类，高级分类（平台数据同步如 Bilibili 收藏夹）。

涉及路由：`MaterialCategory*Route`

### 18.B LLM 调用收税体系

烧 token 的操作需租户上下文，为后期计费做准备。

涉及路由：`MaterialLanguageGuessRoute`、`BilibiliVideoAiConclusionRoute` 等

### 18.C 模板用户化重构

模板与用户关联，支持公开模板。

涉及路由：`PromptTemplate*Route`

### 18.D Weben 模块废弃

整体移除或替换 Weben 模块。

涉及路由：`Weben*Route`

### 18.E MaterialWorkflowRoute 业务逻辑解耦

#### 现状分析

`MaterialWorkflowRoute`（`minRole = AuthRole.TENANT`）目前承担了两类完全不同的业务：

1. **`bilibili_download_transcode`**：触发 Bilibili 视频下载 + 转码双任务流水线，属于"素材采集"语义
2. **`whisper_transcribe`**：触发 Whisper ASR 转录任务，属于"字幕生成"语义

两者共用同一个路由入口，仅靠 `template` 字段区分，导致：
- 路由职责不清晰，难以独立演进（如 ASR 路由未来需要不同的参数校验、权限策略）
- 前端调用方（`useVideoPlayerState.ts`、ASR 相关页面）需要了解 `template` 字段的枚举值，耦合度高
- 测试时必须同时覆盖两条业务路径，测试用例难以聚焦

#### 重构方向

将 `MaterialWorkflowRoute` 拆分为两个独立路由：

| 新路由 | 替代 template | 职责 |
|--------|--------------|------|
| `MaterialBilibiliDownloadTranscodeRoute` | `bilibili_download_transcode` | Bilibili 视频下载 + 转码流水线 |
| `MaterialAsrTranscribeRoute` | `whisper_transcribe` | ASR 转录任务启动 |

`MaterialWorkflowRoute` 本身可保留作为通用工作流触发入口（仅限内部/高级用途），或在迁移完成后废弃。

#### 前端影响

- `useVideoPlayerState.ts`：`startEncode()` 中 bilibili 分支改用 `MaterialBilibiliDownloadTranscodeRoute`
- ASR 相关页面：改用 `MaterialAsrTranscribeRoute`
- `materialWorkflowApi.ts`：`MATERIAL_WORKFLOW_API_PATH` 常量可拆分或废弃

#### 实施前提

依赖 16.x 路由框架重构完成（`RouteContext` 显式参数传递已落地）。
