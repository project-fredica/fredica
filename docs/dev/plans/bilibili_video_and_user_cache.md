---
title: Bilibili 视频信息 & 用户信息缓存设计
order: 520
---

# Bilibili 视频信息 & 用户信息缓存设计

## 1. 背景

当前 `BilibiliVideoGetInfoRoute` 和 `BilibiliVideoGetPagesRoute` 每次请求直接穿透到 Python 子进程调用 B 站 API，无任何缓存。同一 bvid 的重复请求浪费 API 配额并增加风控风险。用户信息（UP 主）同理——`uploader_get_info_worker` 和 `get_account_info_worker` 每次都发起网络请求。

参照已有的 `BilibiliSubtitleMetaCacheService`（两级锁）和 `BilibiliAiConclusionCacheService`（两级锁 + 令牌桶），为视频信息和用户信息各建一套 DB 级缓存。

---

## 2. BVID / AID / CID 层级

```
Video (BVID / AID)
├── Page 1 (CID-1)
├── Page 2 (CID-2)
└── Page N (CID-N)
```

- **BVID/AID**：视频唯一标识（BVID 公开 ID，AID 内部数字 ID，一一对应）
- **CID**：每个分 P 的唯一标识，字幕/弹幕/下载等操作的必要参数
- `Video.get_info()` 返回完整视频元数据 + pages 数组（含每个分 P 的 cid）
- `Video.get_pages()` 返回 pages 数组（同上）
- `Video.get_cid(page_index)` 从 pages 中提取单个 CID

**当前问题：** Python `get_info_worker` 在构建返回值时丢弃了 `cid` 字段，导致后续操作无法从缓存获取 CID。

---

## 3. BilibiliVideoInfoCacheService

### 3.1 缓存键

| 字段 | 类型 | 说明 |
|------|------|------|
| **bvid** | `TEXT` | 主缓存键，B 站视频 BV 号 |

缓存粒度为 bvid。一次 `get_info` 调用返回所有分 P 信息，不需要按分 P 单独缓存。

### 3.2 缓存字段分类

#### 顶层字段

| 字段 | Python 来源 | 分类 | 说明 |
|------|------------|------|------|
| `bvid` | `bvid` | **键** | 视频 BV 号，缓存查询键 |
| `aid` | `info["aid"]` | **冷** | 视频 AID（内部数字 ID），上传后永不变。**当前 worker 未返回，需新增** |
| `title` | `info["title"]` | **冷** | 视频标题，上传后极少修改 |
| `cover` | `info["pic"]` | **冷** | 封面 URL，上传后极少修改 |
| `desc` | `info["desc"]` | **冷** | 视频简介 |
| `duration` | `info["duration"]` | **冷** | 总时长（秒） |

#### owner 子对象（UP 主基本信息）

| 字段 | Python 来源 | 分类 | 说明 |
|------|------------|------|------|
| `owner.mid` | `info["owner"]["mid"]` | **冷** | UP 主 UID，永不变 |
| `owner.name` | `info["owner"]["name"]` | **冷** | UP 主昵称，极少修改 |
| `owner.face` | `info["owner"]["face"]` | **冷** | UP 主头像 URL，偶尔修改 |

#### stat 子对象（统计数据）

| 字段 | Python 来源 | 分类 | 说明 |
|------|------------|------|------|
| `stat.view` | `info["stat"]["view"]` | **热** | 播放量，实时变化 |
| `stat.danmaku` | `info["stat"]["danmaku"]` | **热** | 弹幕数 |
| `stat.favorite` | `info["stat"]["favorite"]` | **热** | 收藏数 |
| `stat.coin` | `info["stat"]["coin"]` | **热** | 投币数 |
| `stat.like` | `info["stat"]["like"]` | **热** | 点赞数 |
| `stat.share` | `info["stat"]["share"]` | **热** | 分享数 |

#### pages 数组（分 P 信息，每个元素）

| 字段 | Python 来源 | 分类 | 说明 |
|------|------------|------|------|
| `pages[].cid` | `p["cid"]` | **冷** | 分 P 的 CID，永不变。**当前 worker 未返回，需新增** |
| `pages[].page` | `p["page"]` | **冷** | 分 P 序号（1-based），永不变 |
| `pages[].part` | `p["part"]` | **冷** | 分 P 标题 |
| `pages[].duration` | `p["duration"]` | **冷** | 分 P 时长（秒） |
| `pages[].first_frame` | `p["first_frame"]` | **冷** | 分 P 首帧截图 URL |

### 3.3 缓存策略

- **整体缓存**：将 `get_info` 的完整 JSON 结果存入 `raw_result` 字段，不拆分字段
- **冷数据为主**：title/desc/cover/pages/CID 等上传后基本不变，缓存命中率高
- **热数据附带**：stat 字段作为附带信息一并缓存，但不作为缓存有效性判据
- **不设过期时间**：冷数据不过期，通过 `is_update` 参数支持手动强制刷新
- **不需要令牌桶**：`get_info` 接口无严格频率限制（与 AI 总结不同）
- **不区分凭据状态**：视频基本信息不依赖登录状态（与字幕不同）

### 3.4 isSuccess 判定

```
1. 无 error 字段
2. title 非空字符串
```

比字幕缓存简单——不需要考虑凭据状态对结果的影响。

### 3.5 Python Worker 修改

`get_info_worker` 需要保留两个当前被丢弃的字段：

```python
# 当前（丢弃 cid 和 aid）
return {
    "bvid": bvid,
    "title": title,
    ...
    "pages": [
        {"page": p.get("page", 0), "part": p.get("part", ""), "duration": p.get("duration", 0), "first_frame": p.get("first_frame", "")}
        for p in pages
    ],
}

# 修改后（保留 cid 和 aid）
return {
    "bvid": bvid,
    "aid": info.get("aid", 0),          # 新增
    "title": title,
    ...
    "pages": [
        {"cid": p.get("cid", 0), "page": p.get("page", 0), "part": p.get("part", ""), "duration": p.get("duration", 0), "first_frame": p.get("first_frame", "")}
        for p in pages                    # ^^^ 新增 cid
    ],
}
```

### 3.6 DB 表结构

```sql
CREATE TABLE IF NOT EXISTS bilibili_video_info_cache (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    bvid        TEXT NOT NULL,
    queried_at  INTEGER NOT NULL,
    raw_result  TEXT NOT NULL,
    is_success  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_bvic_bvid
ON bilibili_video_info_cache(bvid, queried_at DESC);
```

### 3.7 Repo 接口

```kotlin
interface BilibiliVideoInfoCacheRepo {
    suspend fun insert(entry: BilibiliVideoInfoCache)
    suspend fun queryBest(bvid: String): BilibiliVideoInfoCache?
}
```

`queryBest`：`WHERE bvid=? AND is_success=1 ORDER BY queried_at DESC LIMIT 1`

### 3.8 两级锁缓存流程

```
请求 fetchVideoInfo(bvid, isUpdate=false)
│
├─ L1（锁外快速路径）
│   └─ repo.queryBest(bvid) → 命中？→ 返回 rawResult
│
├─ guard.withLock(bvid)
│   ├─ L2（锁内 double-check）
│   │   └─ repo.queryBest(bvid) → 命中？→ 返回 rawResult
│   │
│   └─ 缓存未命中
│       ├─ 构建凭据 body（BilibiliAccountPoolService）
│       ├─ FredicaApi.PyUtil.post("/bilibili/video/get-info/$bvid", body)
│       ├─ computeIsSuccess(raw)
│       ├─ repo.insert(cache entry)
│       └─ 返回 raw
│
└─ isUpdate=true 时跳过 L1 和 L2 的缓存查询，直接穿透到 Python
```

锁键为 `bvid`（不含 pageIndex，因为视频信息是整体缓存）。

### 3.9 路由改造

#### BilibiliVideoGetInfoRoute

改为调用 `BilibiliVideoInfoCacheService.fetchVideoInfo(bvid, isUpdate)`。请求参数新增 `is_update: Boolean = false`。

#### BilibiliVideoGetPagesRoute

改为从缓存的 `get_info` 结果中提取 `pages` 字段（含 cid），不再单独调用 Python `get_pages_worker`。

### 3.10 前端影响

**无需修改前端代码。** 后端缓存对前端完全透明：
- `BilibiliVideoList.expandPages` 调用 `BilibiliVideoGetPagesRoute`，该路由改为从缓存读取
- `add-resource.bilibili.multi-part.tsx` 的 `buildMediaItems` 从 `BilibiliVideoGetInfoRoute` 构建 `MediaItem[]`，返回结构不变

---

## 4. BilibiliUserInfoCacheService

### 4.1 缓存键

| 字段 | 类型 | 说明 |
|------|------|------|
| **mid** | `TEXT` | 主缓存键，B 站用户 UID |

### 4.2 数据来源

项目中有两个获取用户信息的 worker：

| Worker | 文件 | 调用方式 | 返回字段 |
|--------|------|---------|---------|
| `uploader_get_info_worker` | `subprocess/bilibili/uploader.py` | `User(uid).get_user_info()` 无凭据 | mid, name |
| `get_account_info_worker` | `subprocess/bilibili/credential.py` | `User(uid).get_user_info()` 有凭据 | mid, name, face, level, sign, coins, fans, following |

两者底层都调用 `bilibili_api.user.User.get_user_info()`，返回的原始数据相同，只是 worker 层面提取的字段不同。

**统一方案：** 缓存服务统一调用 `get_account_info_worker`（或新建一个统一的 `user_get_info_worker`），返回完整字段集。`uploader_get_info_worker` 的调用方改为从缓存读取。

### 4.3 缓存字段分类

| 字段 | Python 来源 | 分类 | 说明 |
|------|------------|------|------|
| `mid` | `dedeuserid` / `param["mid"]` | **键** | 用户 UID，缓存查询键 |
| `name` | `info["name"]` | **冷** | 用户昵称，极少修改 |
| `face` | `info["face"]` | **冷** | 头像 URL，偶尔修改 |
| `level` | `info["level"]` | **冷** | 等级（0-6），变化极慢 |
| `sign` | `info["sign"]` | **冷** | 个性签名，偶尔修改 |
| `coins` | `info["coins"]` | **热** | 硬币数，消费/充值时变化 |
| `fans` | `info["fans"]` | **热** | 粉丝数，实时变化 |
| `following` | `info["attention"]` | **热** | 关注数，关注/取关时变化 |

### 4.4 缓存策略

- **整体缓存**：将完整 JSON 结果存入 `raw_result`
- **冷数据为主**：name/face/level/sign 变化极慢
- **热数据附带**：coins/fans/following 一并缓存但不作为有效性判据
- **不设过期时间**：通过 `is_update` 支持手动刷新
- **不需要令牌桶**：用户信息接口无严格频率限制
- **不需要凭据**：公开用户信息不需要登录（`uploader_get_info_worker` 已验证）

### 4.5 isSuccess 判定

```
1. 无 error 字段
2. name 非空字符串
```

### 4.6 DB 表结构

```sql
CREATE TABLE IF NOT EXISTS bilibili_user_info_cache (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    mid         TEXT NOT NULL,
    queried_at  INTEGER NOT NULL,
    raw_result  TEXT NOT NULL,
    is_success  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_buic_mid
ON bilibili_user_info_cache(mid, queried_at DESC);
```

### 4.7 Repo 接口

```kotlin
interface BilibiliUserInfoCacheRepo {
    suspend fun insert(entry: BilibiliUserInfoCache)
    suspend fun queryBest(mid: String): BilibiliUserInfoCache?
}
```

`queryBest`：`WHERE mid=? AND is_success=1 ORDER BY queried_at DESC LIMIT 1`

### 4.8 两级锁缓存流程

```
请求 fetchUserInfo(mid, isUpdate=false)
│
├─ L1（锁外快速路径）
│   └─ repo.queryBest(mid) → 命中？→ 返回 rawResult
│
├─ guard.withLock(mid)
│   ├─ L2（锁内 double-check）
│   │   └─ repo.queryBest(mid) → 命中？→ 返回 rawResult
│   │
│   └─ 缓存未命中
│       ├─ FredicaApi.PyUtil.post("/bilibili/user/get-info/$mid", "{}")
│       ├─ computeIsSuccess(raw)
│       ├─ repo.insert(cache entry)
│       └─ 返回 raw
│
└─ isUpdate=true 时跳过 L1 和 L2 的缓存查询，直接穿透到 Python
```

锁键为 `mid`。

### 4.9 Python Worker

新建统一的 `user_get_info_worker`（或复用 `get_account_info_worker` 的逻辑），不需要凭据即可获取公开用户信息：

```python
def user_get_info_worker(param: dict) -> dict:
    """获取 B 站用户公开信息（无需凭据）。"""
    mid = param["mid"]
    u = User(uid=int(mid))
    info = asyncio.run(u.get_user_info())
    return {
        "mid": mid,
        "name": info.get("name", ""),
        "face": info.get("face", ""),
        "level": info.get("level", 0),
        "sign": info.get("sign", ""),
        "coins": info.get("coins", 0),
        "fans": info.get("fans", 0),
        "following": info.get("attention", 0),
    }
```

对应新增 Python 路由 `POST /bilibili/user/get-info/{mid}`。

### 4.10 路由改造

新建 `BilibiliUserGetInfoRoute`，调用 `BilibiliUserInfoCacheService.fetchUserInfo(mid)`。

现有 `BilibiliUploaderGetPageRoute` 中如果需要 UP 主名称，可从用户缓存获取而非每次调用 Python。

---

## 5. 两级锁（PyCallGuard）设计对比

| 维度 | VideoInfoCache | UserInfoCache | SubtitleMetaCache（已有） | AiConclusionCache（已有） |
|------|---------------|---------------|------------------------|------------------------|
| 锁键 | `bvid` | `mid` | `$bvid:$pageIndex` | `$bvid:$pageIndex` |
| 令牌桶 | 不需要 | 不需要 | 不需要 | 需要（3 tokens / 1.5s） |
| 凭据依赖 | 不依赖 | 不依赖 | 依赖（影响 isSuccess） | 依赖 |
| 过期时间 | 无（手动刷新） | 无（手动刷新） | 无 | 24h（失败记录） |
| 失败回退 | 不回退 | 不回退 | 不回退 | 回退到历史成功记录 |
| isSuccess 判定 | 无 error + title 非空 | 无 error + name 非空 | code=0 + subtitles 非 null + 凭据校验 | 自定义 |

### 5.1 PyCallGuard 工作原理

```kotlin
class PyCallGuard {
    private val guardMutex = Mutex()           // 短持有：保护 locks map
    private val locks = mutableMapOf<String, Mutex>()  // per-key 长持有锁

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val mutex = guardMutex.withLock { locks.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}
```

两级锁的完整流程：

1. **L1（锁外）**：直接查 DB，命中即返回——零锁开销的快速路径
2. **获取 per-key Mutex**：通过 `guardMutex` 短暂持有获取/创建 per-key 锁
3. **L2（锁内 double-check）**：再查一次 DB，防止前一个持锁者已写入缓存
4. **Python 调用**：缓存未命中，调用 Python 子进程
5. **写入缓存**：将结果写入 DB
6. **释放锁**：后续等锁的请求进入 L2 时命中缓存

---

## 6. 文件清单

### VideoInfoCache

| 文件 | 操作 |
|------|------|
| `desktop_assets/.../subprocess/bilibili/video.py` | **修改** — `get_info_worker` 保留 `cid` 和 `aid` |
| `shared/.../bilibili_video_info/model/BilibiliVideoInfoCache.kt` | **新建** — 数据模型 |
| `shared/.../bilibili_video_info/db/BilibiliVideoInfoCacheRepo.kt` | **新建** — Repo 接口 |
| `shared/.../bilibili_video_info/db/BilibiliVideoInfoCacheDb.kt` | **新建** — SQLite 实现 |
| `shared/.../bilibili_video_info/service/BilibiliVideoInfoCacheService.kt` | **新建** — 缓存服务 |
| `shared/.../api/routes/BilibiliVideoGetInfoRoute.kt` | **修改** — 改用缓存服务 |
| `shared/.../api/routes/BilibiliVideoGetPagesRoute.kt` | **修改** — 从缓存提取 pages |
| `shared/.../api/FredicaApi.jvm.kt` | **修改** — 追加初始化 |

### UserInfoCache

| 文件 | 操作 |
|------|------|
| `desktop_assets/.../subprocess/bilibili/user.py` | **新建** — 统一的用户信息 worker |
| `desktop_assets/.../routes/bilibili_user.py` | **新建** — 用户信息路由 |
| `shared/.../bilibili_user_info/model/BilibiliUserInfoCache.kt` | **新建** — 数据模型 |
| `shared/.../bilibili_user_info/db/BilibiliUserInfoCacheRepo.kt` | **新建** — Repo 接口 |
| `shared/.../bilibili_user_info/db/BilibiliUserInfoCacheDb.kt` | **新建** — SQLite 实现 |
| `shared/.../bilibili_user_info/service/BilibiliUserInfoCacheService.kt` | **新建** — 缓存服务 |
| `shared/.../api/routes/BilibiliUserGetInfoRoute.kt` | **新建** — Kotlin 路由 |
| `shared/.../api/routes/all_routes.kt` | **修改** — 注册新路由 |
| `shared/.../api/FredicaApi.jvm.kt` | **修改** — 追加初始化 |
