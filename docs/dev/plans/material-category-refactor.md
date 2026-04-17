---
title: 素材分类用户化重构（18.A）
order: 530
---

# 素材分类用户化重构（18.A）

> 本文档设计素材分类系统的用户化重构：将全局共享分类改为用户关联分类，引入公开/私有可见性，并新增"公开信息同步分类"支持 Bilibili 收藏夹、UP 主投稿列表等外部数据源的自动同步与跨用户共享。

---

## 1. 现状分析

### 1.1 当前数据模型

```
material_category (全局共享，无用户隔离)
├── id          TEXT PK
├── name        TEXT UNIQUE    ← 全局唯一，多用户冲突
├── description TEXT
├── created_at  INTEGER
└── updated_at  INTEGER

material_category_rel (M:N 关联)
├── material_id TEXT
└── category_id TEXT
    └── PK(material_id, category_id)
```

### 1.2 当前问题

| 问题 | 说明 |
|------|------|
| 无用户隔离 | 所有用户共享同一套分类，A 创建的分类 B 也能看到和删除 |
| name 全局唯一 | 不同用户无法创建同名分类（如"学习"） |
| 无可见性控制 | 没有公开/私有概念 |
| 无外部数据源 | Bilibili 收藏夹等外部信息源没有对应的分类抽象 |
| 素材无归属 | `material` 表无 `owner_id`，素材本身也是全局共享 |

### 1.3 现有路由

| 路由 | 方法 | minRole | 说明 |
|------|------|---------|------|
| `MaterialCategoryListRoute` | POST | GUEST | 分页+条件查询分类 |
| `MaterialCategorySimpleCreateRoute` | POST | TENANT | 创建简易分类（name 去重） |
| `MaterialCategorySimpleDeleteRoute` | POST | TENANT | 删除简易分类及关联 |
| `MaterialCategorySimpleUpdateRoute` | POST | TENANT | 更新简易分类 |
| `MaterialCategorySyncUpdateRoute` | POST | TENANT | 更新同步分类（仅 name/description） |
| `MaterialCategorySyncDeleteRoute` | POST | TENANT | 删除同步分类（级联清理同步元数据） |
| `MaterialSetCategoriesRoute` | POST | TENANT | 替换素材的分类关联 |

### 1.4 现有前端组件

| 组件 / 路由 | 职责 | 状态 |
|-------------|------|------|
| `MaterialCategoryPanel.tsx` | 素材库左侧分类筛选面板（pill 标签 + 新建输入框） | ✅ 完整 |
| `CategoryPickerModal.tsx` | 导入/编辑时的分类选择弹窗（checkbox 列表 + 新建） | ✅ 完整（无用户过滤） |
| `add-resource._index.tsx` | 添加资源入口（4 个来源选项，仅 Bilibili 可用） | ✅ 完整 |
| `add-resource.bilibili.tsx` | Bilibili 子页面布局（4 tab：收藏夹/合集/分P/UP主） | ✅ 完整（3 tab 标记 todo） |
| `add-resource.bilibili.favorite.tsx` | 收藏夹 fid 输入 + URL 解析 | ✅ 完整 |
| `add-resource.bilibili.favorite.fid.$fid.tsx` | 收藏夹视频列表浏览 + 导入 | ✅ 完整（无分类支持） |
| `add-resource.bilibili.multi-part.tsx` | 多 P 视频 BVID 输入 + 分 P 选择 + CategoryPicker | ✅ 完整 |
| `add-resource.bilibili.uploader.tsx` | UP 主 UID 输入表单 | ⚠️ 纯 UI 桩（handleSubmit = console.log） |
| `add-resource.bilibili.collection.tsx` | 合集/列表 ID 输入表单 | ⚠️ 纯 UI 桩（handleSubmit = console.log） |

---

## 2. 设计目标

1. **用户关联**：每个分类有明确的 `owner_id`，用户只能管理自己的分类
2. **公开/私有**：分类可标记为 `public`，其他用户可以看到公开分类及其素材（只读）
3. **同步分类**：支持绑定外部数据源（Bilibili 收藏夹、UP 主投稿列表等），自动/手动同步内容
4. **信源公开**：同步分类默认对所有用户可见，任何用户都能浏览同步到的素材
5. **向后兼容**：旧数据直接迁移，不保留旧接口

---

## 3. 数据模型设计

### 3.1 `material_category` 表（重构）

```sql
CREATE TABLE material_category (
    id                  TEXT PRIMARY KEY,
    owner_id            TEXT NOT NULL,              -- 创建者 user_id
    name                TEXT NOT NULL,              -- 同一用户下唯一（非全局唯一）
    description         TEXT NOT NULL DEFAULT '',
    visibility          TEXT NOT NULL DEFAULT 'private',  -- 'private' | 'public'
    allow_others_view   INTEGER NOT NULL DEFAULT 0, -- 允许他人查看分类内素材列表
    allow_others_add    INTEGER NOT NULL DEFAULT 0, -- 允许他人向分类添加素材
    allow_others_delete INTEGER NOT NULL DEFAULT 0, -- 允许他人从分类移除素材
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL,
    UNIQUE(owner_id, name)                         -- 同一用户下名称唯一
);
```

**变更要点：**
- 新增 `owner_id`：关联 `auth_user.id`
- `UNIQUE` 约束从 `(name)` 改为 `(owner_id, name)`：不同用户可创建同名分类
- 新增 `visibility`：`private`（仅自己可见）或 `public`（所有用户可见）
- 新增三个细粒度权限列：`allow_others_view`、`allow_others_add`、`allow_others_delete`
  - 仅在 `visibility = 'public'` 时生效（private 分类他人不可见，权限无意义）
  - 同步分类自动创建时默认 `visibility='public'` + `allow_others_view=1`

### 3.2 `material_category_rel` 表（不变）

```sql
CREATE TABLE material_category_rel (
    material_id TEXT NOT NULL,
    category_id TEXT NOT NULL,
    PRIMARY KEY (material_id, category_id)
);
```

关联表结构不变。素材与分类的 M:N 关系保持不变。

### 3.3 `material_category_sync_platform_info` 表（新增）

平台公开信息表。每条记录代表一个**全局唯一的平台数据源**（如某个 Bilibili 收藏夹、某个 UP 主），与用户无关。

```sql
CREATE TABLE material_category_sync_platform_info (
    id              TEXT PRIMARY KEY,              -- UUID
    sync_type       TEXT NOT NULL,                 -- 鉴别器（sealed interface 类型标识）
    platform_id     TEXT NOT NULL,                 -- 平台原生 ID（从 sync_type 特定字段派生）
    platform_config TEXT NOT NULL DEFAULT '{}',    -- JSON，平台侧不可变参数（见 §3.3.2）
    display_name    TEXT NOT NULL DEFAULT '',       -- 数据源显示名（如收藏夹名、UP 主昵称）
    category_id     TEXT NOT NULL,                 -- 关联的 material_category.id
    last_synced_at  INTEGER,                       -- 上次同步完成时间（epoch sec）
    sync_cursor     TEXT NOT NULL DEFAULT '',       -- 增量同步游标（平台相关）
    item_count      INTEGER NOT NULL DEFAULT 0,    -- 已同步条目数
    sync_state      TEXT NOT NULL DEFAULT 'idle',  -- 同步状态机（见 §3.3.3）
    last_error      TEXT NOT NULL DEFAULT '',       -- 最近一次同步失败的错误信息
    fail_count      INTEGER NOT NULL DEFAULT 0,    -- 连续失败次数（成功后归零）
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE(sync_type, platform_id)                 -- 同一平台数据源全局唯一
);
```

#### 3.3.1 设计要点

- **平台信息与用户配置解耦**：此表只存储平台公开信息（"这是哪个收藏夹"），不含任何用户偏好设置。用户的同步策略存储在 `material_category_sync_user_config` 中（§3.4）
- **`UNIQUE(sync_type, platform_id)` 去重**：`platform_id` 由各子类型的平台原生标识符派生，而非 JSON 去重。例如：
  - `bilibili_favorite` → `platform_id = "{media_id}"`（如 `"12345678"`）
  - `bilibili_uploader` → `platform_id = "{mid}"`（如 `"456789"`）
  - `bilibili_season` → `platform_id = "{mid}:{season_id}"`（如 `"456789:1001"`）
  - `bilibili_series` → `platform_id = "{mid}:{series_id}"`（如 `"456789:2001"`）
  - `bilibili_video_pages` → `platform_id = "{bvid}"`（如 `"BV1xx411c7mD"`）
- **一个 platform_info 对应一个 category**：首次添加时自动创建 `material_category`（`visibility='public'`, `allow_others_view=1`），`category_id` 指向它
- **`sync_state`**：同步状态机，管理同步生命周期和失败恢复（见 §3.3.3）
- **`platform_config`**：JSON 存储平台侧不可变参数（如 `media_id`、`mid`、`season_id` 等），用于构造 API 请求。与旧设计的 `sync_config` 不同，此字段仅包含平台标识信息，不含用户偏好
- **`sync_cursor`**：增量同步游标，语义由 `sync_type` 决定。属于平台信息（所有订阅者共享同一游标）

#### 3.3.2 `SyncPlatformIdentity` sealed interface（Kotlin 模型）

平台身份标识对象，负责：(1) 提供 `platformId` 用于去重；(2) 序列化为 `platform_config` JSON。

```kotlin
@Serializable
sealed interface SyncPlatformIdentity {
    val syncType: String     // 用于 DB sync_type 列
    val platformId: String   // 用于 DB platform_id 列（UNIQUE 约束的一部分）

    // ── Bilibili 收藏夹 ──────────────────────────────────────────────
    @Serializable
    @SerialName("bilibili_favorite")
    data class BilibiliFavorite(
        @SerialName("media_id") val mediaId: Long,
    ) : SyncPlatformIdentity {
        override val syncType get() = "bilibili_favorite"
        override val platformId get() = mediaId.toString()
    }

    // ── Bilibili UP 主投稿 ───────────────────────────────────────────
    @Serializable
    @SerialName("bilibili_uploader")
    data class BilibiliUploader(
        val mid: Long,
    ) : SyncPlatformIdentity {
        override val syncType get() = "bilibili_uploader"
        override val platformId get() = mid.toString()
    }

    // ── Bilibili 合集（新版 season）─────────────────────────────────
    @Serializable
    @SerialName("bilibili_season")
    data class BilibiliSeason(
        @SerialName("season_id") val seasonId: Long,
        val mid: Long,
    ) : SyncPlatformIdentity {
        override val syncType get() = "bilibili_season"
        override val platformId get() = "$mid:$seasonId"
    }

    // ── Bilibili 列表（旧版 series）─────────────────────────────────
    @Serializable
    @SerialName("bilibili_series")
    data class BilibiliSeries(
        @SerialName("series_id") val seriesId: Long,
        val mid: Long,
    ) : SyncPlatformIdentity {
        override val syncType get() = "bilibili_series"
        override val platformId get() = "$mid:$seriesId"
    }

    // ── Bilibili 多 P 视频（分 P 作为独立素材）─────────────────────
    @Serializable
    @SerialName("bilibili_video_pages")
    data class BilibiliVideoPages(
        val bvid: String,
    ) : SyncPlatformIdentity {
        override val syncType get() = "bilibili_video_pages"
        override val platformId get() = bvid
    }
}
```

**Bilibili API 对应关系：**

| sealed 子类 | Bilibili API | 参数 | `platformId` | 分页 | 增量游标 |
|-------------|-------------|------|-------------|------|---------|
| `BilibiliFavorite` | `favorite_list.get_video_favorite_list_content(media_id, pn)` | `media_id` | `"{media_id}"` | pn/ps | `fav_time` |
| `BilibiliUploader` | `User(mid).get_videos(pn, ps, order)` | `mid` | `"{mid}"` | pn/ps | `pubdate` |
| `BilibiliSeason` | `User(mid).get_channel_videos_season(sid, pn, ps)` | `season_id` + `mid` | `"{mid}:{season_id}"` | pn/ps | 无（全量，有限集合） |
| `BilibiliSeries` | `User(mid).get_channel_videos_series(sid, pn, ps)` | `series_id` + `mid` | `"{mid}:{series_id}"` | pn/ps | 无（全量，有限集合） |
| `BilibiliVideoPages` | `Video(bvid).get_pages()` | `bvid` | `"{bvid}"` | 无分页 | 无（全量，有限集合） |

**未来可扩展：**
- `BilibiliCollectedFavorites`：收藏的合集（`get_favorite_collected(uid)`），需要登录凭据
- `YoutubePlaylist`、`YoutubeChannel` 等其他平台

#### 3.3.3 同步状态机

`sync_state` 字段管理平台信息的同步生命周期：

```
                    ┌─────────────────────────────────┐
                    │                                 │
                    ▼                                 │
    ┌──────┐   trigger   ┌─────────┐   success   ┌───────┐
    │ idle │ ──────────→ │ syncing │ ──────────→ │ idle  │
    └──────┘             └─────────┘             └───────┘
        ▲                    │
        │                    │ failure
        │                    ▼
        │              ┌──────────┐
        │   cooldown   │  failed  │
        │   expired    │          │
        │              └──────────┘
        │                    │
        │                    │ retry (manual or auto)
        └────────────────────┘
```

| 状态 | 含义 | 转换条件 |
|------|------|---------|
| `idle` | 空闲，可接受同步请求 | 初始状态；同步成功后回到此状态 |
| `syncing` | 正在执行同步任务 | 收到同步触发（手动/cron/freshness 过期） |
| `failed` | 最近一次同步失败 | 同步任务执行出错 |

**失败处理策略：**
- 同步失败时：`sync_state='failed'`，`last_error` 记录错误信息，`fail_count++`
- 退避重试：下次自动同步间隔 = `base_interval * min(2^fail_count, 32)`（指数退避，上限 32 倍）
- 手动重试：用户可随时手动触发，不受退避限制，但 `fail_count` 不归零（成功后才归零）
- 同步成功时：`sync_state='idle'`，`fail_count=0`，`last_error=''`

### 3.4 `material_category_sync_user_config` 表（新增）

用户同步配置表。每条记录代表一个用户对某个平台数据源的**个人订阅设置**。一个 `platform_info` 可以有多个 `user_config`（多用户订阅同一数据源）。

```sql
CREATE TABLE material_category_sync_user_config (
    id                   TEXT PRIMARY KEY,              -- UUID
    platform_info_id     TEXT NOT NULL,                 -- FK → material_category_sync_platform_info.id
    user_id              TEXT NOT NULL,                 -- 订阅者 user_id
    cron_expr            TEXT NOT NULL DEFAULT '',      -- cron 表达式（空 = 不自动同步）
    freshness_window_sec INTEGER NOT NULL DEFAULT 0,    -- "视为最新"窗口（秒）。0 = 不使用
    enabled              INTEGER NOT NULL DEFAULT 1,    -- 是否启用此用户的自动同步
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL,
    UNIQUE(platform_info_id, user_id)                  -- 同一用户对同一数据源只能有一条配置
);
```

#### 3.4.1 设计要点

- **一对多关系**：一个 `platform_info` 可被多个用户订阅，每人有独立的同步策略
- **`cron_expr`**：标准 5 字段 cron 表达式，控制自动同步频率。空字符串表示不自动同步（仅手动触发）
- **`freshness_window_sec`**：核心多用户协作字段。含义："如果此数据源在 N 秒内已被任何人同步过，则视为最新，跳过本次同步"
  - 例：用户 A 设置 `freshness_window_sec=3600`（1 小时），用户 B 在 30 分钟前触发了同步 → 用户 A 的 cron 触发时检查 `platform_info.last_synced_at`，发现在窗口内，跳过同步
  - 设为 `0` 表示不使用此功能，每次 cron 触发都执行同步
- **`enabled`**：用户级开关。与 `platform_info` 的 `sync_state` 独立——即使平台信息处于 `failed` 状态，用户仍可保持 `enabled=1`（等待自动恢复）
- **首次添加同步源时**：同时创建 `platform_info`（如不存在）和 `user_config`。如果 `platform_info` 已存在（他人先添加），则只创建 `user_config`

#### 3.4.2 `SyncUserConfig` 数据类（Kotlin 模型）

```kotlin
data class SyncUserConfig(
    val id: String,
    val platformInfoId: String,
    val userId: String,
    val cronExpr: String,
    val freshnessWindowSec: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
```

#### 3.4.3 Freshness 判定逻辑

```kotlin
fun shouldSkipSync(platformInfo: SyncPlatformInfo, userConfig: SyncUserConfig): Boolean {
    if (userConfig.freshnessWindowSec <= 0) return false
    val lastSynced = platformInfo.lastSyncedAt ?: return false
    val now = System.currentTimeMillis() / 1000
    return (now - lastSynced) < userConfig.freshnessWindowSec
}
```

### 3.5 `material_category_sync_item` 表（新增）

```sql
CREATE TABLE material_category_sync_item (
    platform_info_id TEXT NOT NULL,             -- FK → material_category_sync_platform_info.id
    material_id      TEXT NOT NULL,             -- 对应 material.id（确定性 ID）
    platform_item_id TEXT NOT NULL DEFAULT '',  -- 平台侧原始 ID（如 bvid），便于去重
    synced_at        INTEGER NOT NULL,          -- 本条同步时间
    PRIMARY KEY (platform_info_id, material_id)
);
```

**用途：**
- 记录每个平台数据源拉取了哪些素材，用于增量同步去重和统计
- FK 指向 `platform_info`（而非 `user_config`），因为同步结果是平台级共享的
- 与 `material_category_rel` 独立：`sync_item` 记录"这条素材是从这个数据源来的"，`material_category_rel` 记录"这条素材属于这个分类"。同步时两张表同时写入

### 3.6 `material_category_audit_log` 表（新增）

分类操作审计日志表。记录所有对分类权限、信息的修改操作。

```sql
CREATE TABLE material_category_audit_log (
    id          TEXT PRIMARY KEY,              -- UUID
    category_id TEXT NOT NULL,                 -- 被操作的分类 ID
    user_id     TEXT NOT NULL,                 -- 操作者 user_id
    action      TEXT NOT NULL,                 -- 操作类型（见下方枚举）
    detail      TEXT NOT NULL DEFAULT '{}',    -- JSON，操作详情（变更前后值）
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_audit_log_category ON material_category_audit_log(category_id);
CREATE INDEX idx_audit_log_user ON material_category_audit_log(user_id);
```

**`action` 枚举值：**

| action | 含义 | detail 示例 |
|--------|------|------------|
| `create` | 创建分类 | `{"name":"...", "visibility":"private"}` |
| `update_name` | 修改分类名称 | `{"old":"旧名", "new":"新名"}` |
| `update_description` | 修改分类描述 | `{"old":"...", "new":"..."}` |
| `update_visibility` | 修改可见性 | `{"old":"private", "new":"public"}` |
| `update_permission` | 修改细粒度权限 | `{"field":"allow_others_add", "old":0, "new":1}` |
| `delete` | 删除分类 | `{"name":"被删除的分类名"}` |
| `add_material` | 向分类添加素材 | `{"material_id":"..."}` |
| `remove_material` | 从分类移除素材 | `{"material_id":"..."}` |
| `sync_subscribe` | 用户订阅同步源 | `{"platform_info_id":"...", "sync_type":"bilibili_favorite"}` |
| `sync_unsubscribe` | 用户取消订阅同步源 | `{"platform_info_id":"..."}` |

### 3.7 实体关系图

```
auth_user
    │
    ├──< material_category (owner_id)
    │       │
    │       ├──< material_category_rel >── material
    │       │
    │       └──< material_category_audit_log (category_id)
    │
    ├──< material_category_sync_user_config (user_id)
    │       │
    │       └──> material_category_sync_platform_info (platform_info_id)
    │               │
    │               ├──> material_category (category_id)
    │               │
    │               └──< material_category_sync_item >── material
    │
    └──< material (owner_id, 未来扩展)
```

**关键关系说明：**
- `platform_info` → `category`：一对一，每个平台数据源对应一个自动创建的分类
- `user_config` → `platform_info`：多对一，多个用户可订阅同一平台数据源
- `user_config` → `auth_user`：多对一，一个用户可订阅多个数据源
- `sync_item` → `platform_info`：多对一，同步结果属于平台数据源（所有订阅者共享）
- `audit_log` → `category`：多对一，一个分类可有多条操作日志

---

## 4. 权限模型

### 4.1 分类细粒度权限

分类权限由 `visibility` + 三个 `allow_others_*` 列共同决定。所有权限变更和分类信息修改均写入 `material_category_audit_log`（§3.6）。

#### 4.1.1 权限矩阵

| 操作 | Owner | 他人（public + allow） | 他人（public, !allow） | 他人（private） | ROOT | GUEST |
|------|-------|----------------------|----------------------|----------------|------|-------|
| 查看分类存在 | ✅ | ✅ | ✅ | ❌ | ✅ | ✅（仅 public） |
| 查看分类内素材列表 | ✅ | ✅（需 `allow_others_view`） | ❌ | ❌ | ✅ | ✅（需 `allow_others_view`） |
| 向分类添加素材 | ✅ | ✅（需 `allow_others_add`） | ❌ | ❌ | ✅ | ❌ |
| 从分类移除素材 | ✅ | ✅（需 `allow_others_delete`） | ❌ | ❌ | ✅ | ❌ |
| 编辑分类信息（名称/描述） | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 修改可见性/权限设置 | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 删除分类 | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 创建分类 | ✅（TENANT+） | — | — | — | ✅ | ❌ |

#### 4.1.2 权限判定逻辑

```kotlin
fun canViewMaterials(category: MaterialCategory, userId: String?, role: AuthRole): Boolean {
    if (role == AuthRole.ROOT) return true
    if (category.ownerId == userId) return true
    if (category.visibility != "public") return false
    return category.allowOthersView
}

fun canAddMaterial(category: MaterialCategory, userId: String?, role: AuthRole): Boolean {
    if (role == AuthRole.ROOT) return true
    if (category.ownerId == userId) return true
    if (category.visibility != "public") return false
    return category.allowOthersAdd
}

fun canDeleteMaterial(category: MaterialCategory, userId: String?, role: AuthRole): Boolean {
    if (role == AuthRole.ROOT) return true
    if (category.ownerId == userId) return true
    if (category.visibility != "public") return false
    return category.allowOthersDelete
}
```

#### 4.1.3 审计日志写入时机

所有以下操作在执行成功后写入 `material_category_audit_log`：
- 创建分类（`create`）
- 修改分类名称（`update_name`）、描述（`update_description`）、可见性（`update_visibility`）
- 修改权限设置（`update_permission`）
- 删除分类（`delete`）
- 添加/移除素材（`add_material` / `remove_material`）
- 订阅/取消订阅同步源（`sync_subscribe` / `sync_unsubscribe`）

审计日志为追加写入，不可修改或删除（ROOT 也不行）。

### 4.2 同步数据源权限与多用户策略

由于平台信息（`platform_info`）与用户配置（`user_config`）已解耦（§3.3 / §3.4），同步权限模型围绕"共享数据源 + 个人订阅"设计。

#### 4.2.1 同步操作权限

| 操作 | 条件 |
|------|------|
| 添加同步源（创建 platform_info + user_config） | TENANT+。如 platform_info 已存在，仅创建 user_config |
| 订阅已有同步源（仅创建 user_config） | TENANT+。可查看其他租户的订阅策略 |
| 修改自己的同步策略（cron、freshness_window） | `user_config.user_id` = 当前用户 |
| 启用/禁用自己的订阅 | `user_config.user_id` = 当前用户 |
| 取消自己的订阅（删除 user_config） | `user_config.user_id` = 当前用户 |
| 触发手动同步 | 任何订阅者（TENANT+），或 ROOT |
| 查看同步源信息和所有订阅者策略 | 所有已认证用户（TENANT+） |
| 删除 platform_info（级联删除所有 user_config） | 仅 ROOT |

#### 4.2.2 多用户同步策略

**核心原则：** 平台数据源的同步结果是共享的（所有订阅者看到相同的素材列表），但同步触发策略是个人的。

**Cron 调度合并逻辑：**
- `SyncScheduler` 遍历所有 `enabled=1` 的 `user_config`，按 `platform_info_id` 分组
- 对每个 `platform_info`，取所有订阅者中最早到期的 cron 触发时间作为下次同步时间
- 触发同步前，检查 freshness：如果 `platform_info.last_synced_at` 在任一订阅者的 `freshness_window_sec` 内，该订阅者的本次触发被跳过

**跨租户可见性：**
- 所有 TENANT+ 用户可查看任意 `platform_info` 的完整信息（包括 `sync_state`、`last_synced_at`、`fail_count`）
- 所有 TENANT+ 用户可查看同一 `platform_info` 下所有 `user_config` 的策略设置（cron、freshness_window、enabled）
- 用户只能修改自己的 `user_config`

**同步失败对订阅者的影响：**
- `platform_info.sync_state = 'failed'` 时，所有订阅者的 UI 显示失败状态
- 任何订阅者可手动触发重试（不受退避限制）
- 自动重试遵循指数退避（§3.3.3），由 `SyncScheduler` 统一管理

### 4.3 GUEST 权限

Guest 用户（未登录/访客 token）：
- 可以浏览公开分类列表（只读）
- 可以查看公开分类中的素材（需 `allow_others_view=1`）
- 不能创建、编辑、删除任何分类
- 不能添加/移除素材
- 不能添加同步源或管理订阅

---

## 5. 路由设计

### 5.1 分类 CRUD 路由（重构）

#### `MaterialCategoryListRoute`（重构）

```
POST /api/v1/MaterialCategoryListRoute
minRole: GUEST
```

**请求模型（`MaterialCategoryListRequest`）：**

```kotlin
@Serializable
data class MaterialCategoryListRequest(
    val filter: CategoryFilter = CategoryFilter.ALL,
    val search: String? = null,
    val offset: Int = 0,
    val limit: Int = 50,
) {
    @Serializable
    enum class CategoryFilter {
        @SerialName("all")    ALL,      // 自己的全部分类 + 他人的 public 分类
        @SerialName("mine")   MINE,     // 仅自己的简易分类（不含同步分类）
        @SerialName("sync")   SYNC,     // 仅同步分类（自己创建的 + 他人 public 的）
        @SerialName("public") PUBLIC,   // 仅 public 分类（含同步分类）
    }
}
```

**响应模型（`MaterialCategoryListResponse`）：**

```kotlin
@Serializable
data class MaterialCategoryListResponse(
    val items: List<MaterialCategory>,  // §9.1 模型
    val total: Int,                     // 符合条件的总数（用于前端分页 UI）
    val offset: Int,
    val limit: Int,
)
```

**请求示例：**

```json
{ "filter": "all", "search": "bilibili", "offset": 0, "limit": 20 }
```

**响应示例（基于 §9.1 `MaterialCategory` 模型）：**

```json
{
    "items": [
        {
            "id": "cat-abc",
            "owner_id": "user-1",
            "name": "Bilibili 收藏夹",
            "description": "自动同步的收藏夹",
            "visibility": "public",
            "allow_others_view": true,
            "allow_others_add": false,
            "allow_others_delete": false,
            "material_count": 42,
            "is_mine": true,
            "sync": {
                "id": "pi-xyz",
                "sync_type": "bilibili_favorite",
                "platform_config": { "type": "bilibili_favorite", "media_id": 12345 },
                "display_name": "我的收藏夹",
                "last_synced_at": 1700000000,
                "item_count": 42,
                "sync_state": "idle",
                "last_error": null,
                "fail_count": 0,
                "subscriber_count": 2,
                "my_subscription": {
                    "id": "uc-001",
                    "enabled": true,
                    "cron_expr": "0 */6 * * *",
                    "freshness_window_sec": 3600
                }
            },
            "created_at": 1700000000,
            "updated_at": 1700000000
        },
        {
            "id": "cat-def",
            "owner_id": "user-1",
            "name": "学习资料",
            "description": "",
            "visibility": "private",
            "allow_others_view": false,
            "allow_others_add": false,
            "allow_others_delete": false,
            "material_count": 5,
            "is_mine": true,
            "sync": null,
            "created_at": 1700000000,
            "updated_at": 1700000000
        }
    ],
    "total": 15,
    "offset": 0,
    "limit": 20
}
```

**字段说明：**

- `is_mine`：布尔值，前端据此决定是否显示编辑/删除按钮
- `sync`：如果该分类关联了同步源，附带 `SyncPlatformInfoSummary`（§9.3）；简易分类此字段为 `null`
- `search`：按分类名称模糊匹配（`LIKE '%keyword%'`），可选
- **GUEST 身份**：`filter` 参数被忽略，始终只返回 `visibility = 'public'` 的分类（含同步分类），`is_mine` 始终为 `false`

**分类类型区分策略：**

所有分类类型（同步分类、简易分类）统一在一个分页接口返回，通过以下方式区分：
1. **`sync` 字段是否为 `null`**：`null` 表示简易分类，非 `null` 表示同步分类
2. **`is_mine` 字段**：区分自己的分类和他人的分类
3. **`filter` 参数**：前端可按需筛选特定类型

前端 `groupCategories()` 函数可据此三维度分组：
- 我的同步分类：`is_mine && sync != null`
- 我的简易分类：`is_mine && sync == null`
- 他人的分类：`!is_mine`

**`material_count` 计算**：LEFT JOIN `material_category_rel` 后 COUNT(DISTINCT material_id)，与当前实现一致。

**实现要点（`MaterialCategoryDb.listForUser` 重构）：**

当前实现是无参 `listAll()` 返回全部分类。重构后签名变为：

```kotlin
fun listForUser(
    userId: String?,
    filter: CategoryFilter = CategoryFilter.ALL,
    search: String? = null,
    offset: Int = 0,
    limit: Int = 50,
): Pair<List<MaterialCategory>, Int>  // (分页结果, 总数)
```

SQL 查询逻辑（WHERE 子句按 filter 动态拼接）：

1. `filter = ALL`：`WHERE owner_id = :userId OR visibility = 'public'`
2. `filter = MINE`：`WHERE owner_id = :userId AND id NOT IN (SELECT category_id FROM material_category_sync_platform_info)`
3. `filter = SYNC`：`WHERE id IN (SELECT category_id FROM material_category_sync_platform_info) AND (owner_id = :userId OR visibility = 'public')`
4. `filter = PUBLIC`：`WHERE visibility = 'public'`
5. `userId = null`（Guest）：无论 filter 值，等同于 `WHERE visibility = 'public'`

搜索条件追加：`AND name LIKE '%' || :search || '%'`（search 非 null 时）。

分页：`ORDER BY created_at DESC LIMIT :limit OFFSET :offset`。

总数查询：同条件 `SELECT COUNT(*) ...`（不含 LIMIT/OFFSET）。

`is_mine` 字段在 SQL 中计算：`CASE WHEN owner_id = :userId THEN 1 ELSE 0 END AS is_mine`。

`sync` 字段通过 LEFT JOIN `material_category_sync_platform_info` 获取，非 null 时构造 `SyncPlatformInfoSummary`（§9.3）。

#### `MaterialCategorySimpleCreateRoute`（重构，原 `MaterialCategoryCreateRoute`）

```
POST /api/v1/MaterialCategorySimpleCreateRoute
minRole: TENANT
```

**请求模型（`MaterialCategorySimpleCreateRequest`）：**

```kotlin
@Serializable
data class MaterialCategorySimpleCreateRequest(
    val name: String,
    val description: String = "",
    val visibility: String = "private",  // "private" | "public"
)
```

**响应**：`MaterialCategory`（§9.1 模型，`sync` 字段为 `null`）

**请求示例：**

```json
{ "name": "学习资料", "description": "前端学习", "visibility": "private" }
```

**响应示例：**

```json
{
    "id": "cat-new",
    "owner_id": "user-1",
    "name": "学习资料",
    "description": "前端学习",
    "visibility": "private",
    "allow_others_view": false,
    "allow_others_add": false,
    "allow_others_delete": false,
    "material_count": 0,
    "is_mine": true,
    "sync": null,
    "created_at": 1700000000,
    "updated_at": 1700000000
}
```

**错误：**
- `"分类名称不能为空"`
- `"分类名称过长"`（>64 字符）
- `"同名分类已存在"`（同一用户下）

**实现要点：**
- `owner_id` 自动从 `context.userId!!` 获取，前端不传
- `visibility` 默认 `private`
- **当前 `createOrGet` 语义变更**：旧实现用 `INSERT OR IGNORE` + 按 name 查回，全局唯一。重构后改为 `INSERT OR IGNORE` + 按 `(owner_id, name)` 查回，同一用户下唯一。如果 name 已存在，返回已有分类（幂等）

#### `MaterialCategorySimpleUpdateRoute`（新增）

```
POST /api/v1/MaterialCategorySimpleUpdateRoute
minRole: TENANT
```

**请求模型（`MaterialCategorySimpleUpdateRequest`）：**

```kotlin
@Serializable
data class MaterialCategorySimpleUpdateRequest(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,  // "private" | "public"
)
```

**响应模型：**

```kotlin
@Serializable
data class MaterialCategoryUpdateResponse(
    val success: Boolean = true,
    val category: MaterialCategory,
)
```

**请求示例：**

```json
{ "id": "cat-abc", "name": "新名称", "visibility": "public" }
```

**响应示例：**

```json
{
    "success": true,
    "category": {
        "id": "cat-abc",
        "owner_id": "user-1",
        "name": "新名称",
        "description": "",
        "visibility": "public",
        "allow_others_view": false,
        "allow_others_add": false,
        "allow_others_delete": false,
        "material_count": 5,
        "is_mine": true,
        "sync": null,
        "created_at": 1700000000,
        "updated_at": 1700000001
    }
}
```

**错误：**
- `"分类不存在"`
- `"权限不足"`（非 owner 且非 ROOT）
- `"同名分类已存在"`（同一用户下改名冲突）
- `"该分类是同步分类，请使用 MaterialCategorySyncUpdateRoute"`（目标分类关联了 `material_category_sync_platform_info`）
- `"分类名称不能为空"`
- `"分类名称过长"`（>64 字符）

**实现要点：**
- 只更新请求中提供的字段（partial update），未提供的字段保持不变
- 权限检查：`category.ownerId == context.userId || context.identity is RootUser`
- **同步分类拦截**：`SELECT COUNT(*) FROM material_category_sync_platform_info WHERE category_id = :id`，如果 > 0，直接拒绝并提示使用 `MaterialCategorySyncUpdateRoute`
- 改名时检查 `UNIQUE(owner_id, name)` 约束：先查询是否存在同名分类（排除自身 id）

#### `MaterialCategorySyncUpdateRoute`（新增）

```
POST /api/v1/MaterialCategorySyncUpdateRoute
minRole: TENANT
```

**请求模型（`MaterialCategorySyncUpdateRequest`）：**

```kotlin
@Serializable
data class MaterialCategorySyncUpdateRequest(
    val id: String,                    // 分类 ID（非 platform_info ID）
    val name: String? = null,
    val description: String? = null,
    // visibility 不可修改：同步分类强制 public
)
```

**响应模型**：同 `MaterialCategoryUpdateResponse`

**请求示例：**

```json
{ "id": "cat-sync-1", "name": "B站收藏夹（重命名）" }
```

**响应示例：**

```json
{
    "success": true,
    "category": {
        "id": "cat-sync-1",
        "owner_id": "user-1",
        "name": "B站收藏夹（重命名）",
        "description": "",
        "visibility": "public",
        "allow_others_view": true,
        "allow_others_add": false,
        "allow_others_delete": false,
        "material_count": 42,
        "is_mine": true,
        "sync": { "..." },
        "created_at": 1700000000,
        "updated_at": 1700000001
    }
}
```

**错误：**
- `"分类不存在"`
- `"权限不足"`（非 owner 且非 ROOT）
- `"该分类不是同步分类"`（目标分类未关联 `material_category_sync_platform_info`）
- `"同名分类已存在"`（同一用户下改名冲突）
- `"分类名称不能为空"`
- `"分类名称过长"`（>64 字符）

**实现要点：**
- **同步分类校验**：`SELECT COUNT(*) FROM material_category_sync_platform_info WHERE category_id = :id`，如果 = 0，拒绝并提示使用 `MaterialCategorySimpleUpdateRoute`
- **visibility 不可修改**：同步分类强制 `public`，请求中不接受 `visibility` 字段
- 权限检查：`category.ownerId == context.userId || context.identity is RootUser`
- 只更新 `name` 和 `description`（partial update）

#### `MaterialCategorySimpleDeleteRoute`（重构，原 `MaterialCategoryDeleteRoute`）

```
POST /api/v1/MaterialCategorySimpleDeleteRoute
minRole: TENANT

请求：{ "id": "..." }
响应：{ "success": true }

错误：
  - "分类不存在"
  - "权限不足"（非 owner 且非 ROOT）
  - "该分类是同步分类，请使用 MaterialCategorySyncDeleteRoute"
```

**实现要点：**

当前 `deleteById(id)` 无权限检查，直接删除。重构后：

```kotlin
fun simpleDeleteById(id: String, userId: String, isRoot: Boolean) {
    // 1. 查询分类是否存在
    // 2. 权限检查：owner_id == userId || isRoot
    // 3. 同步分类拦截：SELECT COUNT(*) FROM material_category_sync_platform_info WHERE category_id = :id
    //    如果 > 0，拒绝并提示使用 MaterialCategorySyncDeleteRoute
    // 4. 删除 material_category_rel WHERE category_id = :id
    // 5. 删除 material_category WHERE id = :id
    // 注意：不删除 material 本身
}
```

#### `MaterialCategorySyncDeleteRoute`（新增）

```
POST /api/v1/MaterialCategorySyncDeleteRoute
minRole: TENANT

请求：{ "id": "..." }
响应：{ "success": true }

错误：
  - "分类不存在"
  - "权限不足"（非 owner 且非 ROOT）
  - "该分类不是同步分类"
```

**实现要点：**

同步分类删除需要级联清理同步相关数据：

```kotlin
fun syncDeleteById(id: String, userId: String, isRoot: Boolean) {
    // 1. 查询分类是否存在
    // 2. 权限检查：owner_id == userId || isRoot
    // 3. 同步分类校验：SELECT COUNT(*) FROM material_category_sync_platform_info WHERE category_id = :id
    //    如果 = 0，拒绝并提示使用 MaterialCategorySimpleDeleteRoute
    // 4. 删除 material_category_sync_item WHERE platform_info_id IN (SELECT id FROM material_category_sync_platform_info WHERE category_id = :id)
    // 5. 删除 material_category_sync_user_config WHERE platform_info_id IN (SELECT id FROM material_category_sync_platform_info WHERE category_id = :id)
    // 6. 删除 material_category_sync_platform_info WHERE category_id = :id
    // 7. 删除 material_category_rel WHERE category_id = :id
    // 8. 删除 material_category WHERE id = :id
    // 注意：不删除 material 本身，仅解除关联
}
```

**与 `MaterialCategorySimpleDeleteRoute` 的区别：**
- Simple 版本拒绝删除同步分类（引导用户使用 Sync 版本）
- Sync 版本级联删除 `sync_platform_info`、`sync_user_config`、`sync_item` 等同步元数据
- 两者都删除 `material_category_rel` 关联和 `material_category` 记录本身

#### `MaterialSetCategoriesRoute`（重构）

```
POST /api/v1/MaterialSetCategoriesRoute
minRole: TENANT

请求：{ "material_id": "...", "category_ids": ["..."] }
响应：{ success: true }

权限检查：category_ids 中的每个分类必须是当前用户拥有的普通分类
错误：
  - "分类不存在或无权操作"（category_ids 中包含不属于当前用户的分类）
  - "不能手动添加到同步分类"（category_ids 中包含有 platform_info 关联的分类）
```

**关键行为变更 — 保留同步分类关联：**

当前 `setMaterialCategories` 实现是 **delete-all + insert-new**：

```sql
DELETE FROM material_category_rel WHERE material_id = ?;
INSERT OR IGNORE INTO material_category_rel (material_id, category_id) VALUES (?, ?);
```

这会**删除同步引擎写入的关联**。重构后必须改为**只替换用户自己的普通分类关联**：

```sql
-- 只删除用户自己的非同步分类关联
DELETE FROM material_category_rel
WHERE material_id = :materialId
  AND category_id IN (
      SELECT id FROM material_category
      WHERE owner_id = :userId
        AND id NOT IN (SELECT category_id FROM material_category_sync_platform_info)
  );

-- 插入新的关联
INSERT OR IGNORE INTO material_category_rel (material_id, category_id) VALUES (?, ?);
```

**时序图：**

```
用户点击"修改分类" → CategoryPickerModal（仅显示"我的分类"）
  → 用户勾选/取消 → onConfirm(categoryIds)
    → POST MaterialSetCategoriesRoute { material_id, category_ids }
      → 后端校验：每个 category_id 的 owner_id == currentUserId
      → 后端校验：每个 category_id 不在 material_category_sync_platform_info 中
      → 删除该素材在当前用户普通分类中的旧关联
      → 插入新关联
      → 保留：同步分类关联、他人分类关联（不动）
```

### 5.2 同步源路由（重构：platform_info + user_config 分离）

路由围绕"平台数据源"和"用户订阅"两层设计。添加同步源时自动创建/复用 `platform_info`，并为当前用户创建 `user_config`。

#### `MaterialCategorySyncCreateRoute`（重构）

添加同步源。如果 `platform_info` 已存在（他人先添加），仅创建当前用户的 `user_config`（订阅）。

```
POST /api/v1/MaterialCategorySyncCreateRoute
minRole: TENANT

请求：{
    "sync_type": "bilibili_favorite",       // sealed interface 鉴别器
    "platform_config": { "media_id": 12345 }, // 平台参数（JSON）
    "display_name"?: "我的收藏夹",
    "cron_expr"?: "0 */6 * * *",            // 可选，自动同步 cron
    "freshness_window_sec"?: 3600           // 可选，视为最新窗口
}

响应：{
    platform_info: { id, sync_type, platform_id, display_name, category_id, sync_state, ... },
    user_config: { id, platform_info_id, user_id, cron_expr, freshness_window_sec, enabled, ... },
    category: { id, name, visibility: "public", ... },
    is_new_platform: true | false           // 是否新创建了 platform_info
}

错误：
  - "请求参数无效"（sync_type 不在 sealed 子类列表中，或 platform_config 反序列化失败）
  - "已订阅该数据源"（当前用户已有 user_config）+ existing_user_config_id
  - "display_name 过长"（>64 字符）
```

**实现要点：**

```kotlin
override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
    val p = param.loadJsonModel<Param>().getOrThrow()
    val userId = context.userId!!

    // 1. 反序列化 platform_config → SyncPlatformIdentity 子类
    val identity: SyncPlatformIdentity = try {
        json.decodeFromJsonElement(
            SyncPlatformIdentity.serializer(),
            buildJsonObject {
                put("type", p.syncType)
                p.platformConfig.forEach { (k, v) -> put(k, v) }
            }
        )
    } catch (e: Exception) {
        return buildJsonObject { put("error", "请求参数无效") }.toValidJson()
    }

    // 2. 查找已有 platform_info（按 UNIQUE(sync_type, platform_id) 去重）
    val existingPlatform = SyncPlatformInfoService.repo
        .findByTypeAndPlatformId(identity.syncType, identity.platformId)

    // 3. 如果已有 platform_info，检查当前用户是否已订阅
    if (existingPlatform != null) {
        val existingConfig = SyncUserConfigService.repo
            .findByPlatformInfoAndUser(existingPlatform.id, userId)
        if (existingConfig != null) {
            return buildJsonObject {
                put("error", "已订阅该数据源")
                put("existing_user_config_id", existingConfig.id)
            }.toValidJson()
        }
    }

    // 4. 事务：创建 platform_info（如不存在）+ user_config + category（如不存在）
    val isNewPlatform = existingPlatform == null
    val platformInfo = existingPlatform ?: run {
        val categoryId = generateId()
        val platformInfoId = generateId()
        val autoName = generateSyncCategoryName(identity)
        SyncPlatformInfoService.repo.createWithCategory(
            platformInfoId = platformInfoId,
            categoryId = categoryId,
            syncType = identity.syncType,
            platformId = identity.platformId,
            platformConfig = json.encodeToString(SyncPlatformIdentity.serializer(), identity),
            displayName = p.displayName ?: autoName,
            categoryName = autoName,
            categoryOwnerId = userId,
        )
        SyncPlatformInfoService.repo.findById(platformInfoId)!!
    }

    val userConfigId = generateId()
    SyncUserConfigService.repo.create(
        id = userConfigId,
        platformInfoId = platformInfo.id,
        userId = userId,
        cronExpr = p.cronExpr ?: "",
        freshnessWindowSec = p.freshnessWindowSec ?: 0,
    )

    // 5. 写审计日志
    AuditLogService.repo.insert(
        categoryId = platformInfo.categoryId,
        userId = userId,
        action = "sync_subscribe",
        detail = buildJsonObject {
            put("platform_info_id", platformInfo.id)
            put("sync_type", identity.syncType)
        }.toString(),
    )

    // 6. 返回结果（不立即触发同步，由前端决定是否调用 TriggerRoute）
}
```

**`generateSyncCategoryName` 命名规则：**

| sync_type | 自动名称格式 | 示例 |
|-----------|-------------|------|
| `bilibili_favorite` | `[B站收藏夹] {media_id}` | `[B站收藏夹] 12345` |
| `bilibili_uploader` | `[B站UP主] {mid}` | `[B站UP主] 67890` |
| `bilibili_season` | `[B站合集] {season_id}` | `[B站合集] 111` |
| `bilibili_series` | `[B站列表] {series_id}` | `[B站列表] 222` |
| `bilibili_video_pages` | `[B站分P] {bvid}` | `[B站分P] BV1xx` |

首次同步完成后，Executor 可用平台返回的真实名称（收藏夹标题、UP 主昵称等）更新 `platform_info.display_name`。

**事务设计 — `createWithCategory`：**

`platform_info` 和 `category` 必须在同一事务中创建，避免孤立记录：

```sql
BEGIN;
INSERT INTO material_category (id, owner_id, name, description, visibility,
    allow_others_view, allow_others_add, allow_others_delete, created_at, updated_at)
VALUES (:categoryId, :ownerId, :autoName, '', 'public', 1, 0, 0, :now, :now);

INSERT INTO material_category_sync_platform_info (id, sync_type, platform_id, platform_config,
    display_name, category_id, sync_state, created_at, updated_at)
VALUES (:platformInfoId, :syncType, :platformId, :platformConfig,
    :displayName, :categoryId, 'idle', :now, :now);
COMMIT;
```

**去重保证：** `UNIQUE(sync_type, platform_id)` 基于平台原生 ID 去重，不依赖 JSON 字符串比较。`platform_id` 由 `SyncPlatformIdentity.platformId` 属性派生（见 §3.3.2），保证确定性。

#### `MaterialCategorySyncSubscribeRoute`（新增）

订阅已有的平台数据源。与 `MaterialCategorySyncCreateRoute` 不同，此路由不创建 `platform_info`，仅创建 `user_config`。

```
POST /api/v1/MaterialCategorySyncSubscribeRoute
minRole: TENANT

请求：{
    "platform_info_id": "...",
    "cron_expr"?: "0 */6 * * *",
    "freshness_window_sec"?: 3600
}

响应：{
    user_config: { id, platform_info_id, user_id, cron_expr, freshness_window_sec, enabled, ... }
}

错误：
  - "数据源不存在"
  - "已订阅该数据源"
```

**实现要点：**

```kotlin
override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
    val p = param.loadJsonModel<Param>().getOrThrow()
    val userId = context.userId!!

    val platformInfo = SyncPlatformInfoService.repo.findById(p.platformInfoId)
        ?: return buildJsonObject { put("error", "数据源不存在") }.toValidJson()

    val existing = SyncUserConfigService.repo.findByPlatformInfoAndUser(platformInfo.id, userId)
    if (existing != null) {
        return buildJsonObject {
            put("error", "已订阅该数据源")
            put("existing_user_config_id", existing.id)
        }.toValidJson()
    }

    val configId = generateId()
    SyncUserConfigService.repo.create(
        id = configId,
        platformInfoId = platformInfo.id,
        userId = userId,
        cronExpr = p.cronExpr ?: "",
        freshnessWindowSec = p.freshnessWindowSec ?: 0,
    )

    AuditLogService.repo.insert(
        categoryId = platformInfo.categoryId,
        userId = userId,
        action = "sync_subscribe",
        detail = buildJsonObject {
            put("platform_info_id", platformInfo.id)
            put("sync_type", platformInfo.syncType)
        }.toString(),
    )

    val config = SyncUserConfigService.repo.findById(configId)!!
    return buildJsonObject { put("user_config", json.encodeToJsonElement(config)) }.toValidJson()
}
```

#### `MaterialCategorySyncListRoute`（重构）

列出同步数据源及其订阅者信息。所有 TENANT+ 用户可查看全部数据源和所有订阅者的策略设置。

```
POST /api/v1/MaterialCategorySyncListRoute
minRole: GUEST

请求：{ "filter"?: "all" | "mine" }
响应：[{
    platform_info: {
        id, sync_type, platform_id, platform_config, display_name,
        category_id, category_name, last_synced_at, item_count,
        sync_state, last_error, fail_count
    },
    subscribers: [{
        user_config_id, user_id, username, display_name,
        cron_expr, freshness_window_sec, enabled
    }],
    my_config?: {
        user_config_id, cron_expr, freshness_window_sec, enabled
    },
    is_subscribed: true | false
}]
```

**实现要点：**

```sql
-- 查询所有 platform_info + 关联的 category 名称
SELECT p.*, c.name AS category_name
FROM material_category_sync_platform_info p
JOIN material_category c ON c.id = p.category_id
ORDER BY p.created_at DESC;

-- 对每个 platform_info，查询所有 subscriber
SELECT uc.*, u.username, u.display_name AS user_display_name
FROM material_category_sync_user_config uc
JOIN auth_user u ON u.id = uc.user_id
WHERE uc.platform_info_id = :platformInfoId
ORDER BY uc.created_at ASC;
```

- `filter = "mine"`：仅返回当前用户已订阅的数据源（`is_subscribed = true`）
- `filter = "all"`（默认）：返回全部数据源
- GUEST 身份：返回全部数据源的基本信息，但 `subscribers` 列表为空（隐私保护）
- `my_config`：当前用户的订阅配置快捷引用（如已订阅），前端用于显示"我的设置"
- `platform_config` 字段在响应中反序列化为 JSON 对象（非字符串），前端可直接读取 `media_id`、`mid` 等字段

#### `MaterialCategorySyncUnsubscribeRoute`（新增，替代旧 DeleteRoute）

取消当前用户对某个数据源的订阅。删除 `user_config`，不影响 `platform_info` 和其他订阅者。

```
POST /api/v1/MaterialCategorySyncUnsubscribeRoute
minRole: TENANT

请求：{ "user_config_id": "..." }
响应：{ success: true }

错误：
  - "订阅不存在"
  - "权限不足"（非本人订阅且非 ROOT）
```

**实现要点：**

```kotlin
override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
    val p = param.loadJsonModel<Param>().getOrThrow()
    val userId = context.userId!!
    val isRoot = context.identity is AuthIdentity.RootUser

    val config = SyncUserConfigService.repo.findById(p.userConfigId)
        ?: return buildJsonObject { put("error", "订阅不存在") }.toValidJson()
    if (config.userId != userId && !isRoot)
        return buildJsonObject { put("error", "权限不足") }.toValidJson()

    // 删除 user_config
    SyncUserConfigService.repo.deleteById(config.id)

    // 写审计日志
    val platformInfo = SyncPlatformInfoService.repo.findById(config.platformInfoId)!!
    AuditLogService.repo.insert(
        categoryId = platformInfo.categoryId,
        userId = userId,
        action = "sync_unsubscribe",
        detail = buildJsonObject { put("platform_info_id", config.platformInfoId) }.toString(),
    )

    return buildJsonObject { put("success", true) }.toValidJson()
}
```

**注意：** 取消订阅不删除 `platform_info`。即使最后一个订阅者取消，`platform_info` 和关联的 `category` 仍保留（数据不丢失）。ROOT 可通过 `MaterialCategorySyncPlatformDeleteRoute` 彻底删除。

#### `MaterialCategorySyncPlatformDeleteRoute`（新增，仅 ROOT）

彻底删除平台数据源。级联删除所有 `user_config`、`sync_item`、`category_rel`、`category`。

```
POST /api/v1/MaterialCategorySyncPlatformDeleteRoute
minRole: ROOT

请求：{ "platform_info_id": "..." }
响应：{ success: true, deleted_subscribers: 3 }

错误：
  - "数据源不存在"
  - "同步任务正在运行，请等待完成后再删除"（sync_state = 'syncing'）
```

**实现要点 — 级联删除顺序：**

```kotlin
fun deletePlatformInfo(platformInfoId: String) {
    val info = SyncPlatformInfoService.repo.findById(platformInfoId)
        ?: error("数据源不存在")
    if (info.syncState == "syncing")
        error("同步任务正在运行，请等待完成后再删除")

    // 事务内级联删除（顺序重要）
    //    a. DELETE FROM material_category_sync_user_config WHERE platform_info_id = :id
    //    b. DELETE FROM material_category_sync_item WHERE platform_info_id = :id
    //    c. DELETE FROM material_category_rel WHERE category_id = :categoryId
    //    d. DELETE FROM material_category_sync_platform_info WHERE id = :id
    //    e. DELETE FROM material_category WHERE id = :categoryId
    //    注意：不删除 material 本身
}
```

**为什么不删除 material：** 同一个 bilibili 视频可能被多个分类引用（用户手动导入 + 同步分类），删除同步源只应解除分类关联，不应删除素材数据。素材的生命周期由 `MaterialDeleteRoute` 单独管理。

#### `MaterialCategorySyncTriggerRoute`（重构）

手动触发同步。任何订阅者或 ROOT 可触发。

```
POST /api/v1/MaterialCategorySyncTriggerRoute
minRole: TENANT

请求：{ "platform_info_id": "..." }
响应：{ workflow_run_id: "..." }

错误：
  - "数据源不存在"
  - "未订阅该数据源"（非订阅者且非 ROOT）
  - "同步任务正在运行"（幂等保护，返回已有 workflow_run_id）
```

**实现要点：**

```kotlin
override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
    val p = param.loadJsonModel<Param>().getOrThrow()
    val userId = context.userId!!
    val isRoot = context.identity is AuthIdentity.RootUser

    // 1. 查询 platform_info
    val platformInfo = SyncPlatformInfoService.repo.findById(p.platformInfoId)
        ?: return buildJsonObject { put("error", "数据源不存在") }.toValidJson()

    // 2. 权限检查：必须是订阅者或 ROOT
    if (!isRoot) {
        val myConfig = SyncUserConfigService.repo
            .findByPlatformInfoAndUser(platformInfo.id, userId)
        if (myConfig == null)
            return buildJsonObject { put("error", "未订阅该数据源") }.toValidJson()
    }

    // 3. 幂等检查：sync_state == 'syncing' 时返回已有 workflow
    if (platformInfo.syncState == "syncing") {
        val activeWorkflows = fetchActiveWorkflows(platformInfoId = platformInfo.id)
        if (activeWorkflows.isNotEmpty()) {
            return buildJsonObject {
                put("workflow_run_id", activeWorkflows.first().id)
                put("already_running", true)
            }.toValidJson()
        }
    }

    // 4. 更新状态机：idle/failed → syncing
    SyncPlatformInfoService.repo.updateSyncState(platformInfo.id, "syncing")

    // 5. 创建 WorkflowRun（单任务）
    val taskType = syncTaskType(platformInfo.syncType)
    val workflowRunId = CommonWorkflowService.createWorkflow(
        template = "sync_${platformInfo.syncType}",
        materialId = platformInfo.categoryId,
        tasks = listOf(TaskDef(
            type = taskType,
            materialId = platformInfo.categoryId,
            payload = buildJsonObject {
                put("platform_info_id", platformInfo.id)
                put("category_id", platformInfo.categoryId)
                put("sync_type", platformInfo.syncType)
                put("platform_config", json.parseToJsonElement(platformInfo.platformConfig))
                put("sync_cursor", platformInfo.syncCursor)
            }.toString(),
            priority = TaskPriority.SYNC_CATEGORY,
            maxRetries = 2,
        )),
    )

    return buildJsonObject { put("workflow_run_id", workflowRunId) }.toValidJson()
}
```

**时序图 — 手动触发同步完整流程：**

```
用户点击"立即同步"
  → POST MaterialCategorySyncTriggerRoute { platform_info_id: "pi-123" }
    → 后端查询 platform_info + 权限检查（是否为订阅者或 ROOT）
    → 幂等检查：sync_state == 'syncing' → 返回已有 workflow_run_id
    → 更新 sync_state: idle/failed → syncing
    → CommonWorkflowService.createWorkflow(
        template = "sync_bilibili_favorite",
        tasks = [TaskDef(type = "SYNC_BILIBILI_FAVORITE", payload = {
            platform_info_id, category_id, sync_type, platform_config, sync_cursor
        })]
      )
    → 返回 { workflow_run_id: "wfr-xxx" }
  → 前端收到 workflow_run_id，开始轮询进度

WorkerEngine 轮询 → 领取 SYNC_BILIBILI_FAVORITE 任务
  → SyncBilibiliFavoriteExecutor.execute(task)
    → 解析 payload 获取 platform_info_id, sync_cursor
    → 调用 Python: POST /bilibili/favorite/get-video-list { fid }
    → 分页拉取全部/增量视频列表
    → 对每条视频：
        a. bilibiliVideoId(bvid, page) → 确定性 material_id
        b. MaterialVideoService.repo.upsertAll([material])
        c. MaterialCategoryService.repo.linkMaterials([materialId], [categoryId])
        d. SyncPlatformInfoService.repo.upsertSyncItem(platformInfoId, materialId, platformItemId)
    → 成功：sync_state='idle', fail_count=0, 更新 sync_cursor/last_synced_at/item_count
    → 失败：sync_state='failed', fail_count++, last_error=错误信息
    → 返回 ExecuteResult(result = "{ synced: 42, new: 5 }")

前端轮询 WorkflowRun 状态 → completed → 刷新分类列表
```

#### `MaterialCategorySyncUserConfigUpdateRoute`（新增）

修改当前用户的同步策略（cron、freshness_window、enabled）。

```
POST /api/v1/MaterialCategorySyncUserConfigUpdateRoute
minRole: TENANT

请求：{
    "user_config_id": "...",
    "cron_expr"?: "0 */6 * * *",
    "freshness_window_sec"?: 3600,
    "enabled"?: true | false
}
响应：{ success: true, user_config: { ... } }

错误：
  - "订阅不存在"
  - "权限不足"（非本人订阅且非 ROOT）
  - "cron 表达式无效"
```

**实现要点：**

```kotlin
override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
    val p = param.loadJsonModel<Param>().getOrThrow()
    val userId = context.userId!!
    val isRoot = context.identity is AuthIdentity.RootUser

    val config = SyncUserConfigService.repo.findById(p.userConfigId)
        ?: return buildJsonObject { put("error", "订阅不存在") }.toValidJson()
    if (config.userId != userId && !isRoot)
        return buildJsonObject { put("error", "权限不足") }.toValidJson()

    // 验证 cron 表达式（如提供）
    if (p.cronExpr != null && p.cronExpr.isNotEmpty()) {
        if (!CronExpressionValidator.isValid(p.cronExpr))
            return buildJsonObject { put("error", "cron 表达式无效") }.toValidJson()
    }

    // Partial update：只更新请求中提供的字段
    SyncUserConfigService.repo.update(
        id = config.id,
        cronExpr = p.cronExpr,
        freshnessWindowSec = p.freshnessWindowSec,
        enabled = p.enabled,
    )

    val updated = SyncUserConfigService.repo.findById(config.id)!!
    return buildJsonObject {
        put("success", true)
        put("user_config", json.encodeToJsonElement(updated))
    }.toValidJson()
}
```

#### `MaterialCategoryAuditLogListRoute`（新增）

查询分类操作审计日志。

```
POST /api/v1/MaterialCategoryAuditLogListRoute
minRole: TENANT

请求：{
    "category_id"?: "...",     // 按分类过滤
    "user_id"?: "...",         // 按操作者过滤
    "limit"?: 50,              // 默认 50，最大 200
    "offset"?: 0
}
响应：{
    items: [{
        id, category_id, category_name, user_id, username,
        action, detail, created_at
    }],
    total: 123
}
```

**实现要点：**

```sql
SELECT al.*, c.name AS category_name, u.username
FROM material_category_audit_log al
JOIN material_category c ON c.id = al.category_id
JOIN auth_user u ON u.id = al.user_id
WHERE (:categoryId IS NULL OR al.category_id = :categoryId)
  AND (:userId IS NULL OR al.user_id = :userId)
ORDER BY al.created_at DESC
LIMIT :limit OFFSET :offset;
```

- 所有 TENANT+ 用户可查看全部审计日志（审计日志不受分类权限限制）
- GUEST 不可访问审计日志
- `detail` 字段在响应中反序列化为 JSON 对象

---

## 6. 同步机制设计

### 6.1 Executor 分发架构

同步由 `MaterialCategorySyncTriggerRoute` 触发（§5.2），创建 WorkflowRun 后由 WorkerEngine 领取执行。

**Executor 注册（`FredicaApi.jvm.kt`）：**

```kotlin
// 在 initWorkerEngine() 中注册同步 Executor
val syncExecutors = listOf(
    SyncBilibiliFavoriteExecutor(),
    SyncBilibiliUploaderExecutor(),
    SyncBilibiliSeasonExecutor(),
    SyncBilibiliSeriesExecutor(),
    SyncBilibiliVideoPagesExecutor(),
)
workerEngine.registerExecutors(existingExecutors + syncExecutors)
```

**taskType 命名规则：**

| sealed 子类 | taskType | WorkflowRun template |
|-------------|----------|---------------------|
| `BilibiliFavorite` | `SYNC_BILIBILI_FAVORITE` | `sync_bilibili_favorite` |
| `BilibiliUploader` | `SYNC_BILIBILI_UPLOADER` | `sync_bilibili_uploader` |
| `BilibiliSeason` | `SYNC_BILIBILI_SEASON` | `sync_bilibili_season` |
| `BilibiliSeries` | `SYNC_BILIBILI_SERIES` | `sync_bilibili_series` |
| `BilibiliVideoPages` | `SYNC_BILIBILI_VIDEO_PAGES` | `sync_bilibili_video_pages` |

**通用同步协议 — 所有 Executor 共享的基类：**

```kotlin
abstract class SyncCategoryExecutor : TaskExecutor {
    @Serializable
    data class SyncPayload(
        @SerialName("platform_info_id") val platformInfoId: String,
        @SerialName("category_id") val categoryId: String,
        @SerialName("sync_type") val syncType: String,
        @SerialName("sync_cursor") val syncCursor: String,
    )

    @Serializable
    data class SyncResult(
        val synced: Int,    // 本次处理的总条目数
        val added: Int,     // 新增素材数
        val updated: Int,   // 更新素材数（已存在但信息变更）
    )

    override suspend fun execute(task: Task): ExecuteResult {
        val payload = task.payload.loadJsonModel<SyncPayload>().getOrThrow()
        val platformInfo = SyncPlatformInfoService.repo.findById(payload.platformInfoId)
            ?: return ExecuteResult(error = "平台数据源不存在", errorType = "PLATFORM_INFO_NOT_FOUND")

        // 状态机校验：只有 syncing 状态才允许执行（由 TriggerRoute / SyncScheduler 负责转换）
        if (platformInfo.syncState != "syncing") {
            return ExecuteResult(error = "平台数据源状态异常: ${platformInfo.syncState}", errorType = "INVALID_STATE")
        }

        return try {
            val result = doSync(task, payload, platformInfo)
            // 同步成功：更新 platform_info 记录 + 状态机回到 idle
            SyncPlatformInfoService.repo.updateAfterSyncSuccess(
                platformInfoId = payload.platformInfoId,
                cursor = result.newCursor,
                itemCount = result.totalItemCount,
                lastSyncedAt = epochSec(),
            )
            ExecuteResult(result = AppUtil.dumpJsonStr(result.stats).getOrThrow())
        } catch (e: Exception) {
            // 同步失败：状态机转入 failed，记录错误信息，fail_count++
            SyncPlatformInfoService.repo.updateAfterSyncFailure(
                platformInfoId = payload.platformInfoId,
                error = e.message ?: "未知错误",
            )
            ExecuteResult(
                error = "同步失败: ${e.message}",
                errorType = "SYNC_ERROR",
            )
        }
    }

    abstract suspend fun doSync(
        task: Task,
        payload: SyncPayload,
        platformInfo: SyncPlatformInfoRecord,
    ): DoSyncResult

    data class DoSyncResult(
        val stats: SyncResult,
        val newCursor: String,
        val totalItemCount: Int,
    )
}
```

**`SyncPlatformInfoService.repo` 的成功/失败更新方法：**

```kotlin
fun updateAfterSyncSuccess(platformInfoId: String, cursor: String, itemCount: Int, lastSyncedAt: Long) {
    // sync_state → idle, fail_count → 0, last_error → ''
    update(platformInfoId) {
        it.syncCursor = cursor
        it.itemCount = itemCount
        it.lastSyncedAt = lastSyncedAt
        it.syncState = "idle"
        it.failCount = 0
        it.lastError = ""
        it.updatedAt = epochSec()
    }
}

fun updateAfterSyncFailure(platformInfoId: String, error: String) {
    // sync_state → failed, fail_count++, last_error = error
    val info = findById(platformInfoId) ?: return
    update(platformInfoId) {
        it.syncState = "failed"
        it.failCount = info.failCount + 1
        it.lastError = error
        it.updatedAt = epochSec()
    }
}
```

**通用 upsert 辅助方法（基类提供）：**

```kotlin
protected suspend fun upsertMaterialsAndLink(
    materials: List<MaterialVideo>,
    categoryId: String,
    platformInfoId: String,
    onProgress: (Int) -> Unit,
) {
    materials.chunked(50).forEachIndexed { batchIdx, batch ->
        // a. upsert material 表
        MaterialVideoService.repo.upsertAll(batch)
        // b. link to category
        MaterialCategoryService.repo.linkMaterials(
            batch.map { it.id }, listOf(categoryId)
        )
        // c. 写 sync_item（FK 指向 platform_info，同步结果是平台级共享的）
        SyncPlatformInfoService.repo.upsertSyncItems(
            platformInfoId = platformInfoId,
            items = batch.map { SyncItemRecord(materialId = it.id, platformItemId = extractPlatformId(it)) },
        )
        onProgress((batchIdx + 1) * batch.size)
    }
}
```

**时序图 — Executor 执行完整流程：**

```
WorkerEngine.poll()
  → 领取 task (type = "SYNC_BILIBILI_FAVORITE", status → "claimed" → "running")
  → SyncBilibiliFavoriteExecutor.execute(task)
    │
    ├─ 1. 解析 payload → SyncPayload { platformInfoId, categoryId, syncCursor }
    ├─ 2. 查询 platform_info → SyncPlatformInfoRecord
    ├─ 3. 校验 syncState == "syncing"
    ├─ 4. doSync()
    │     ├─ 反序列化 platformInfo.platformConfig → SyncPlatformIdentity 子类
    │     ├─ 调用 Python API（分页拉取）
    │     ├─ 构造 MaterialVideo 列表
    │     ├─ upsertMaterialsAndLink() → 批量写 DB
    │     │     ├─ MaterialVideoService.repo.upsertAll(batch)
    │     │     ├─ MaterialCategoryService.repo.linkMaterials(ids, [categoryId])
    │     │     └─ SyncPlatformInfoService.repo.upsertSyncItems(platformInfoId, items)
    │     └─ 返回 DoSyncResult { stats, newCursor, totalItemCount }
    │
    ├─ 5a. 成功 → updateAfterSyncSuccess(platformInfoId, cursor, itemCount, lastSyncedAt)
    │       sync_state → idle, fail_count → 0
    ├─ 5b. 失败 → updateAfterSyncFailure(platformInfoId, error)
    │       sync_state → failed, fail_count++
    └─ 6. 返回 ExecuteResult(result = "{ synced: 42, added: 5, updated: 2 }")

WorkerEngine → task.status = "completed" → WorkflowRunService.recalculate()
```

### 6.2 Bilibili 收藏夹同步（BilibiliFavorite）

**数据源**：Python `/bilibili/favorite/get-video-list`（已有）+ `/bilibili/favorite/get-page`（已有）

**Executor 实现：**

```kotlin
class SyncBilibiliFavoriteExecutor : SyncCategoryExecutor() {
    override val taskType = "SYNC_BILIBILI_FAVORITE"

    override suspend fun doSync(task: Task, payload: SyncPayload, platformInfo: SyncPlatformInfoRecord): DoSyncResult {
        // 从 platform_config JSON 反序列化为 SyncPlatformIdentity.BilibiliFavorite
        val identity = platformInfo.platformConfig.loadJsonModel<SyncPlatformIdentity>().getOrThrow()
            as SyncPlatformIdentity.BilibiliFavorite
        val cursor = payload.syncCursor  // fav_time（epoch sec），空字符串表示全量

        // 1. 首次同步：调用 get-video-list 获取收藏夹元信息 + 首页
        //    后续同步：直接分页拉取
        val allMedias = mutableListOf<BilibiliMediaItem>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val resp = FredicaApi.PyUtil.post(
                "/bilibili/favorite/get-page",
                buildJsonObject {
                    put("fid", identity.mediaId.toString())
                    put("page", page)
                }.toString()
            )
            val data = resp.loadJsonModel<FavoritePageResponse>().getOrThrow()
            val medias = data.medias

            // 增量模式：遇到 fav_time <= cursor 的条目时停止
            if (cursor.isNotEmpty()) {
                val cursorTime = cursor.toLong()
                val newMedias = medias.filter { it.favTime > cursorTime }
                allMedias.addAll(newMedias)
                if (newMedias.size < medias.size) break  // 已到达上次同步点
            } else {
                allMedias.addAll(medias)
            }

            hasMore = data.hasMore
            page++
            TaskService.repo.updateProgress(task.id, (page * 10).coerceAtMost(90))
        }

        // 2. 转换为 MaterialVideo + upsert
        val materials = allMedias.map { media ->
            MaterialVideo(
                id = bilibiliVideoId(media.bvid, media.page),
                type = MaterialType.VIDEO,
                sourceType = "bilibili_favorite",
                sourceId = media.bvid,
                title = media.title,
                coverUrl = media.cover,
                description = media.intro,
                duration = media.duration,
                extra = buildJsonObject {
                    put("upper_name", media.upper.name)
                    put("upper_mid", media.upper.mid)
                    put("fav_time", media.favTime)
                    put("bvid", media.bvid)
                }.toString(),
                createdAt = epochSec(),
                updatedAt = epochSec(),
            )
        }

        upsertMaterialsAndLink(materials, payload.categoryId, payload.platformInfoId) { count ->
            TaskService.repo.updateProgress(task.id, 90 + (count * 10 / materials.size.coerceAtLeast(1)))
        }

        // 3. 更新 cursor 为最新 fav_time
        val newCursor = allMedias.maxOfOrNull { it.favTime }?.toString() ?: cursor
        val totalCount = SyncPlatformInfoService.repo.countSyncItems(payload.platformInfoId)

        // 4. 首次同步完成后，用收藏夹真实标题更新 display_name
        if (cursor.isEmpty()) {
            val info = fetchFavoriteInfo(identity.mediaId)
            if (info != null) {
                SyncPlatformInfoService.repo.updateDisplayName(payload.platformInfoId, info.title)
                MaterialCategoryService.repo.updateName(payload.categoryId, "[B站收藏夹] ${info.title}")
            }
        }

        return DoSyncResult(
            stats = SyncResult(synced = allMedias.size, added = materials.size, updated = 0),
            newCursor = newCursor,
            totalItemCount = totalCount,
        )
    }
}
```

**增量同步策略：**
- `sync_cursor` 存储上次同步的最大 `fav_time`（收藏时间，epoch sec）
- 收藏夹 API 按 `fav_time` 降序返回，遇到 `fav_time <= cursor` 即停止
- 首次同步（cursor 为空）拉取全部
- 收藏夹内容可能被删除（取消收藏），但同步不处理删除（只增不减），避免用户已处理的素材被意外移除

**确定性 ID**：复用现有 `bilibiliVideoId(bvid, page)` 函数，保证同一视频不会重复创建。

### 6.3 Bilibili UP 主投稿同步（BilibiliUploader）

**数据源**：需新增 Python 路由 `/bilibili/uploader/get-video-list`

**Python 路由实现：**

```python
# desktop_assets/common/fredica-pyutil/fredica_pyutil_server/routes/bilibili_uploader.py

@_router.post("/get-video-list")
async def get_video_list(body: _UploaderBody):
    """获取 UP 主投稿视频列表（分页）"""
    user = bilibili_api.user.User(uid=int(body.mid))
    result = await user.get_videos(pn=body.page, ps=body.page_size, order=bilibili_api.user.VideoOrder.PUBDATE)
    return {
        "mid": body.mid,
        "page": body.page,
        "videos": result.get("list", {}).get("vlist", []),
        "total": result.get("page", {}).get("count", 0),
        "has_more": body.page * body.page_size < result.get("page", {}).get("count", 0),
    }
```

**Kotlin 路由（Python 代理）：**

```kotlin
object BilibiliUploaderGetVideoListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Post
    override val minRole = AuthRole.GUEST
    override suspend fun handler(param: String, context: RouteContext): ValidJsonString {
        val p = param.loadJsonModel<Param>().getOrThrow()
        val body = buildJsonObject { put("mid", p.mid); put("page", p.page); put("page_size", p.pageSize) }.toValidJson()
        return ValidJsonString(FredicaApi.PyUtil.post("/bilibili/uploader/get-video-list", body.str))
    }
}
```

**同步流程：**
- `sync_cursor` 记录最后一条的 `pubdate`（发布时间，epoch sec）
- UP 主投稿按 `pubdate` 降序返回，增量逻辑与收藏夹一致
- **首次同步上限**：UP 主投稿量可能很大（数千条），首次同步设置上限 500 条（约 17 页 × 30 条/页），避免单次任务耗时过长
- 首次同步完成后，用 UP 主昵称更新 `display_name`：`[B站UP主] {nickname}`

**UP 主投稿 API 返回字段映射：**

| bilibili API 字段 | MaterialVideo 字段 | 说明 |
|-------------------|-------------------|------|
| `bvid` | `sourceId` + `id`（via bilibiliVideoId） | BV 号 |
| `title` | `title` | 视频标题 |
| `pic` | `coverUrl` | 封面 URL |
| `description` | `description` | 简介 |
| `length` | `duration`（需解析 "MM:SS" 格式） | 时长 |
| `created` | `extra.pubdate` | 发布时间 |
| `author` | `extra.upper_name` | UP 主昵称 |

### 6.4 Bilibili 合集同步（BilibiliSeason）

**数据源**：需新增 Python 路由 `/bilibili/season/get-video-list`

**Python 路由实现：**

```python
@_router.post("/get-video-list")
async def get_season_video_list(body: _SeasonBody):
    """获取合集（season）视频列表（分页）"""
    user = bilibili_api.user.User(uid=int(body.mid))
    result = await user.get_channel_videos_season(sid=int(body.season_id), pn=body.page, ps=body.page_size)
    archives = result.get("archives", [])
    meta = result.get("meta", {})
    return {
        "season_id": body.season_id,
        "mid": body.mid,
        "page": body.page,
        "videos": archives,
        "total": meta.get("total", 0),
        "season_name": meta.get("name", ""),
        "has_more": body.page * body.page_size < meta.get("total", 0),
    }
```

**同步流程：**
- 合集通常是有限集合（几十到几百条），**全量同步**即可，无需增量游标
- 每次同步拉取全部视频列表，与已有 `material_category_sync_item` 做 diff
- `sync_cursor` 不使用（保持空字符串），每次全量拉取
- 首次同步后用合集标题更新 `display_name`：`[B站合集] {season_name}`

**全量同步 vs 增量同步的选择依据：**

| 数据源 | 典型规模 | 是否有序 | 同步策略 |
|--------|---------|---------|---------|
| 收藏夹 | 数百~数千 | 按 fav_time 降序 | 增量（cursor = fav_time） |
| UP 主投稿 | 数十~数千 | 按 pubdate 降序 | 增量（cursor = pubdate） |
| 合集 | 数十~数百 | 固定顺序 | 全量 |
| 列表 | 数十~数百 | 固定顺序 | 全量 |
| 多 P 视频 | 数个~数十 | 固定顺序 | 全量 |

### 6.5 Bilibili 列表同步（BilibiliSeries）

**数据源**：需新增 Python 路由 `/bilibili/series/get-video-list`

**Python 路由实现：**

```python
@_router.post("/get-video-list")
async def get_series_video_list(body: _SeriesBody):
    """获取列表（series）视频列表（分页）"""
    user = bilibili_api.user.User(uid=int(body.mid))
    result = await user.get_channel_videos_series(sid=int(body.series_id), pn=body.page, ps=body.page_size)
    archives = result.get("archives", [])
    meta = result.get("meta", {})
    return {
        "series_id": body.series_id,
        "mid": body.mid,
        "page": body.page,
        "videos": archives,
        "total": meta.get("total", 0),
        "series_name": meta.get("name", ""),
        "has_more": body.page * body.page_size < meta.get("total", 0),
    }
```

**同步流程**：与合集（§6.4）完全一致，全量同步。旧版列表（series）和新版合集（season）的 bilibili API 不同但同步逻辑一致。

### 6.6 Bilibili 多 P 视频同步（BilibiliVideoPages）

**数据源**：Python `/bilibili/video/get-pages/{bvid}`（已有）

**同步流程：**
- 调用已有 Python 路由 `POST /bilibili/video/get-pages/{bvid}` 获取分 P 列表
- 无分页，一次返回全部分 P 信息
- 每个分 P 生成独立 material：`bilibiliVideoId(bvid, pageIndex)`（pageIndex 从 1 开始）
- 全量同步，分 P 数量通常有限（几个到几十个）

**分 P API 返回字段映射：**

| bilibili API 字段 | MaterialVideo 字段 | 说明 |
|-------------------|-------------------|------|
| `page` | `id`（via bilibiliVideoId(bvid, page)） | 分 P 序号（1-based） |
| `part` | `title` | 分 P 标题 |
| `duration` | `duration` | 分 P 时长（秒） |
| `cid` | `extra.cid` | 分 P 的 cid |

**注意**：多 P 视频的封面 URL 需要从视频主信息获取（所有分 P 共享同一封面），Executor 需额外调用一次 `Video(bvid).get_info()` 获取封面和视频标题。

### 6.7 自动同步设计

#### 6.7.1 现状分析

当前系统无 cron/定时调度机制。WorkerEngine 是纯轮询式任务调度器（1s busy / 5s idle backoff），只处理 DB 中已存在的 pending 任务，不主动创建任务。

#### 6.7.2 设计方案：Cron 驱动 + Freshness 窗口 + 多用户合并

在 `FredicaApi.jvm.kt` 的 `init()` 中启动一个协程定时器，定期扫描 `material_category_sync_user_config` 表中 `enabled = 1` 且 `cron_expr` 非空的订阅配置，按 `platform_info_id` 分组后合并调度。

**架构选择 — 为什么不用 WorkerEngine：**

WorkerEngine 的职责是"执行已创建的任务"，不应承担"决定何时创建任务"的职责。定时同步的本质是"定期检查并创建同步任务"，属于调度层，应独立于执行层。

**核心调度逻辑：**

对于每个 `platform_info`，收集所有订阅者的 `user_config`，按以下规则决定是否触发同步：

1. **Cron 合并**：取所有订阅者中最早到达的 cron 触发时间。即：只要有任何一个订阅者的 cron 表达式在当前检查窗口内匹配，就触发同步
2. **Freshness 窗口**：触发前检查 `platform_info.last_synced_at`。如果任何一个订阅者的 `freshness_window_sec > 0` 且 `now - last_synced_at < freshness_window_sec`，该订阅者视为"已满足"，不计入触发投票。但只要仍有至少一个订阅者的 cron 匹配且未被 freshness 跳过，就触发同步
3. **状态机前置**：触发前检查 `platform_info.sync_state`：
   - `idle` → 可触发，转为 `syncing`
   - `syncing` → 已有任务在执行，跳过
   - `failed` → 检查退避：`base_interval * min(2^fail_count, 32)`，未到退避时间则跳过

**实现：`SyncScheduler` 单例**

```kotlin
// shared/src/jvmMain/kotlin/com/github/project_fredica/worker/SyncScheduler.kt

object SyncScheduler {
    private val logger = createLogger("SyncScheduler")
    private var job: Job? = null

    /** 启动后首次检查延迟：5 分钟（等待系统稳定） */
    const val INITIAL_DELAY_MS = 5 * 60 * 1000L
    /** 检查周期：每 5 分钟扫描一次 */
    const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    /** 失败退避基础间隔：30 分钟 */
    const val BACKOFF_BASE_SEC = 30 * 60L

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    checkAndTriggerSyncs()
                } catch (e: Exception) {
                    logger.warn("自动同步检查失败: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun checkAndTriggerSyncs() {
        val now = epochSec()

        // 1. 查询所有 enabled 且有 cron_expr 的 user_config，按 platform_info_id 分组
        val configs = SyncUserConfigService.repo.listEnabledWithCron()
        val grouped = configs.groupBy { it.platformInfoId }

        for ((platformInfoId, userConfigs) in grouped) {
            val platformInfo = SyncPlatformInfoService.repo.findById(platformInfoId) ?: continue

            // 2. 状态机检查
            when (platformInfo.syncState) {
                "syncing" -> continue  // 已有任务在执行
                "failed" -> {
                    // 退避检查：base * min(2^fail_count, 32)
                    val backoffSec = BACKOFF_BASE_SEC * minOf(1L shl platformInfo.failCount, 32L)
                    val lastAttempt = platformInfo.updatedAt  // 最近一次状态变更时间
                    if (now - lastAttempt < backoffSec) continue
                    // 退避已过，允许重试
                }
                "idle" -> { /* 可触发 */ }
            }

            // 3. Cron 匹配 + Freshness 过滤
            val shouldTrigger = userConfigs.any { config ->
                // 检查 cron 是否在当前检查窗口内匹配
                val cronMatches = cronMatchesWindow(config.cronExpr, now, CHECK_INTERVAL_MS / 1000)
                if (!cronMatches) return@any false

                // Freshness 窗口检查
                if (config.freshnessWindowSec > 0 && platformInfo.lastSyncedAt != null) {
                    val fresh = (now - platformInfo.lastSyncedAt!!) < config.freshnessWindowSec
                    if (fresh) return@any false  // 此订阅者视为已满足
                }

                true  // cron 匹配且未被 freshness 跳过
            }

            if (!shouldTrigger) continue

            // 4. 状态机转换：idle/failed → syncing
            SyncPlatformInfoService.repo.updateSyncState(platformInfoId, "syncing")

            // 5. 创建同步 WorkflowRun（复用 TriggerRoute 的逻辑）
            logger.info("自动同步触发: ${platformInfo.syncType} (${platformInfo.displayName})")
            createSyncWorkflow(platformInfo)
        }
    }

    /**
     * 判断 cron 表达式在 [now - windowSec, now] 窗口内是否有匹配时刻。
     * 使用简易 cron 解析器（5 字段：分 时 日 月 周）。
     */
    private fun cronMatchesWindow(cronExpr: String, nowSec: Long, windowSec: Long): Boolean {
        // 实现：将 cron 表达式解析为 CronExpression，
        // 检查 [now - windowSec, now] 区间内是否存在匹配的分钟
        val cron = CronExpression.parse(cronExpr) ?: return false
        return cron.hasMatchInWindow(nowSec - windowSec, nowSec)
    }
}
```

**在 `FredicaApi.jvm.kt` 中启动：**

```kotlin
// init() 末尾
SyncScheduler.start(applicationScope)
```

#### 6.7.3 Cron 表达式解析

使用轻量级 cron 解析器（`CronExpression`），支持标准 5 字段格式：

```
┌───────────── 分钟 (0-59)
│ ┌───────────── 小时 (0-23)
│ │ ┌───────────── 日 (1-31)
│ │ │ ┌───────────── 月 (1-12)
│ │ │ │ ┌───────────── 周几 (0-6, 0=周日)
│ │ │ │ │
* * * * *
```

**常用预设（前端提供选择）：**

| 预设 | cron 表达式 | 含义 |
|------|-----------|------|
| 每 6 小时 | `0 */6 * * *` | 每天 0:00, 6:00, 12:00, 18:00 |
| 每 12 小时 | `0 */12 * * *` | 每天 0:00, 12:00 |
| 每天一次 | `0 3 * * *` | 每天凌晨 3:00 |
| 每周一次 | `0 3 * * 1` | 每周一凌晨 3:00 |
| 手动 | （空字符串） | 不自动同步 |

#### 6.7.4 多用户合并调度示例

```
platform_info: Bilibili 收藏夹 #12345 (last_synced_at = 10:00)

user_config A: cron = "0 */6 * * *", freshness_window = 3600s (1h)
user_config B: cron = "0 */12 * * *", freshness_window = 7200s (2h)
user_config C: cron = "0 3 * * *", freshness_window = 0 (不使用)

── 场景 1：当前时间 12:05，SyncScheduler 检查 ──
  A: cron 匹配 12:00 ✓, freshness: 12:05 - 10:00 = 2h05m > 1h → 不跳过 → 投票触发
  B: cron 匹配 12:00 ✓, freshness: 2h05m < 2h? 否 → 不跳过 → 投票触发
  C: cron 不匹配 → 不投票
  结果：A 和 B 投票触发 → 执行同步

── 场景 2：当前时间 12:05，但 last_synced_at = 11:30 ──
  A: cron 匹配 ✓, freshness: 12:05 - 11:30 = 35m < 1h → 跳过
  B: cron 匹配 ✓, freshness: 35m < 2h → 跳过
  结果：所有匹配者都被 freshness 跳过 → 不触发

── 场景 3：platform_info.sync_state = "failed", fail_count = 2 ──
  退避时间 = 30min * min(2^2, 32) = 30min * 4 = 2h
  如果距上次失败不足 2h → 跳过（不论 cron 是否匹配）
  如果已过 2h → 允许重试
```

#### 6.7.5 错误处理与重试

自动同步失败时的处理策略：

| 场景 | 处理 |
|------|------|
| Python 服务不可用 | WorkerEngine 的 maxRetries（默认 2）自动重试 |
| Bilibili API 限流 | Executor 内 catch → `updateAfterSyncFailure()` → `sync_state='failed'`, `fail_count++` |
| 网络超时 | 同上，maxRetries 自动重试后仍失败则进入 failed |
| 平台数据源被删除 | Executor 查询 platform_info 返回 null → `PLATFORM_INFO_NOT_FOUND`，任务标记 failed |
| 连续失败 | 指数退避：30min × min(2^fail_count, 32)，最大退避 16 小时 |
| 手动重试 | 用户可随时通过 `MaterialCategorySyncTriggerRoute` 手动触发，不受退避限制 |

**退避时间表：**

| fail_count | 退避时间 |
|-----------|---------|
| 1 | 1 小时 |
| 2 | 2 小时 |
| 3 | 4 小时 |
| 4 | 8 小时 |
| 5+ | 16 小时（上限） |

#### 6.7.6 同步状态可观测性

前端需要展示同步状态，数据来源：

| 信息 | 来源 |
|------|------|
| 上次同步时间 | `platform_info.last_synced_at` |
| 已同步条目数 | `platform_info.item_count` |
| 是否正在同步 | `platform_info.sync_state == 'syncing'` |
| 同步进度 | 活跃 WorkflowRun 的 task.progress |
| 上次同步结果 | 最近一次 completed/failed 的 WorkflowRun 的 task.result/error |
| 失败信息 | `platform_info.last_error`（sync_state='failed' 时显示） |
| 连续失败次数 | `platform_info.fail_count` |
| 下次自动同步时间 | 前端根据订阅者 cron 表达式计算最近触发时间 |
| 我的订阅状态 | `user_config.enabled` + `user_config.cron_expr` |
| 其他订阅者 | `MaterialCategorySyncListRoute` 返回的 `subscribers[]` 数组 |

---

## 7. 前端 UI 设计

### 7.1 分类体系概览

重构后分类分为三种类型，写权限各不相同：

| 类型 | 来源 | 可添加素材 | 可编辑/删除 | 权限控制 |
|------|------|-----------|------------|---------|
| **我的分类** | 用户手动创建 | ✅ owner 可添加 | ✅ owner 可编辑/删除 | owner 可设置 `allow_others_view/add/delete` |
| **他人分类** | 其他用户创建 | 取决于 `allow_others_add` | 取决于 `allow_others_delete` | 由 owner 的 ACL 设置决定 |
| **同步分类** | `MaterialCategorySyncCreateRoute` 订阅后自动创建 | ❌ 仅同步引擎写入 | ✅ 订阅者可取消订阅/暂停 | 始终 `allow_others_view=true` |

**核心规则：**
- 只有"我的分类"和设置了 `allow_others_add=true` 的他人分类允许用户手动添加素材
- 同步分类的内容由同步引擎管理，任何订阅者均可触发手动同步
- 未设置 `allow_others_view=true` 的他人分类对其他用户不可见

### 7.2 素材库分类面板（重构 `MaterialCategoryPanel`）

```
┌─────────────────────────────────────────────┐
│ 分类                                    [+] │
│                                             │
│ ┌─ 我的分类 ──────────────────────────────┐ │
│ │ [全部 (42)]  [学习 (12)]  [工作 (8)]    │ │
│ │ [待看 (22)]                        🔒   │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ ┌─ 公开分类 ──────────────────────────────┐ │
│ │ [Alice的推荐 (15)]              👤Alice │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ ┌─ 同步信源 ──────────────────────────────┐ │
│ │ [🔄 收藏夹:默认 (156)]    最近同步 2h前 │ │
│ │ [🔄 UP:影视飓风 (89)]     最近同步 1d前 │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

#### 7.2.1 分组逻辑

前端从 `MaterialCategoryListRoute`（分页查询，`filter: "all"`）获取分类列表后，按以下规则分组：

```typescript
// 分组函数（纯函数，在 useMemo 中调用）
function groupCategories(categories: MaterialCategory[]) {
    const mine: MaterialCategory[] = [];
    const publicOthers: MaterialCategory[] = [];
    const synced: MaterialCategory[] = [];

    for (const cat of categories) {
        if (cat.sync) {
            synced.push(cat);
        } else if (cat.is_mine) {
            mine.push(cat);
        } else {
            publicOthers.push(cat);
        }
    }
    return { mine, publicOthers, synced };
}
```

**分组优先级：** `sync` 字段非 null → 同步信源组（无论 `is_mine`）。这保证同步分类不会出现在"我的分类"中，避免用户误操作。

#### 7.2.2 各分组交互行为

| 分组 | 点击 pill | 右键/长按菜单 | 额外 UI |
|------|----------|--------------|---------|
| 我的分类 | 筛选素材列表 | 重命名 / 权限设置 / 删除 | 🔒 图标表示 `allow_others_view=false` |
| 他人分类 | 筛选素材列表 | 无菜单（或仅"添加素材"当 `allow_others_add=true`） | 👤 显示 owner 用户名 |
| 同步信源 | 筛选素材列表 | 立即同步 / 更新订阅设置 / 取消订阅（仅订阅者） | 同步状态标签（见 §7.6） |

**"全部"虚拟 pill：** 仅出现在"我的分类"分组中，点击后清除分类筛选，显示用户可见的全部素材。计数 = 用户可见的去重素材总数。

#### 7.2.3 与 `material-library._index.tsx` 的集成

当前 `material-library._index.tsx` 管理分类状态的模式：

```typescript
// 当前：categories 是扁平数组，effectiveCategoryId 驱动筛选
const [categories, setCategories] = useState<MaterialCategory[] | null>(null);
const effectiveCategoryId = searchParams.get("category") ?? null;
```

重构后保持相同模式，但 `MaterialCategoryPanel` 内部按分组渲染。`effectiveCategoryId` 不区分分类类型——点击任何分组的 pill 都设置同一个 `category` search param，素材列表的 API 调用不变。

**新增 props：**

```typescript
interface MaterialCategoryPanelProps {
    categories: MaterialCategory[] | null;
    effectiveCategoryId: string | null;
    // ... 现有 props 保持不变 ...
    onSyncTrigger?: (platformInfoId: string) => Promise<void>;   // 触发同步
    onSyncSubscriptionUpdate?: (platformInfoId: string, updates: Partial<SyncUserConfigUpdate>) => Promise<void>;  // 更新订阅设置（启用/禁用/cron）
    onSyncUnsubscribe?: (platformInfoId: string) => Promise<void>;  // 取消订阅
    onSyncPlatformDelete?: (platformInfoId: string) => Promise<void>;  // 删除平台数据源（仅最后一个订阅者或 ROOT）
    onCategoryUpdate?: (id: string, updates: { name?: string; allow_others_view?: boolean; allow_others_add?: boolean; allow_others_delete?: boolean }) => Promise<void>;
}

interface SyncUserConfigUpdate {
    enabled: boolean;
    cron_expr: string;
    freshness_window_sec: number;
}
```

**权限说明：**
- `onSyncTrigger`：任何订阅者均可触发手动同步
- `onSyncSubscriptionUpdate`：修改自己的订阅设置（cron、启用/禁用）
- `onSyncUnsubscribe`：取消自己的订阅（不影响其他订阅者）
- `onSyncPlatformDelete`：仅当无其他订阅者时可删除平台数据源，或 ROOT 强制删除
```

#### 7.2.4 分类编辑交互（内联编辑）

"我的分类"中的 pill 右键菜单触发内联编辑：

```
重命名流程：
  右键 → "重命名" → pill 变为 input（预填当前名称）→ Enter/blur 提交
  → POST MaterialCategorySimpleUpdateRoute { id, name: newName }
    （如果是同步分类，改为 POST MaterialCategorySyncUpdateRoute { id, name: newName }）
  → 成功：刷新分类列表
  → 失败（同名）：toast 提示 "同名分类已存在"

切换可见性流程：
  右键 → "权限设置" → 弹出权限面板（三个开关）
  → 允许他人查看 (allow_others_view): ✅/❌
  → 允许他人添加素材 (allow_others_add): ✅/❌
  → 允许他人删除素材 (allow_others_delete): ✅/❌
  → 任一开关变更时：
    POST MaterialCategorySimpleUpdateRoute { id, allow_others_view, allow_others_add, allow_others_delete }
  → 成功：刷新分类列表，🔒 图标更新
  → 注意：关闭 allow_others_view 时自动关闭 allow_others_add 和 allow_others_delete
  → 注意：同步分类不显示此菜单项（同步分类强制 public，不可修改 visibility）

删除流程：
  右键 → "删除" → 确认弹窗（"删除后分类关联将被移除，素材本身不会被删除"）
  → 简易分类：POST MaterialCategorySimpleDeleteRoute { id }
  → 同步分类：POST MaterialCategorySyncDeleteRoute { id }（确认弹窗额外提示"同步元数据将被清除"）
  → 成功：刷新分类列表；如果当前筛选的就是被删分类，清除 category search param
```

**右键菜单实现：** 使用 `@radix-ui/react-context-menu` 或简单的 `useState` + absolute positioned div。考虑到项目当前未使用 Radix，推荐用 `useState<{ x: number; y: number; categoryId: string } | null>` + `onContextMenu` 实现轻量级右键菜单。

### 7.3 分类选择弹窗（重构 `CategoryPickerModal`）

#### 7.3.1 当前实现分析

`CategoryPickerModal` 是导入素材时选择目标分类的弹窗，被两处使用：
- `BilibiliVideoList.tsx`：收藏夹浏览页中，单个/批量导入视频时弹出
- `add-resource.bilibili.multi-part.tsx`：多 P 视频导入时弹出

当前实现的 API 调用链：

```
CategoryPickerModal mount
  → POST MaterialCategoryListRoute { filter: "all" }  // 无 filter，获取全部分类
  → 渲染 checkbox 列表

用户点击"新建分类"
  → POST MaterialCategorySimpleCreateRoute { name } // 无 owner_id
  → 追加到列表并自动勾选

用户点击"确认"
  → onConfirm(selectedCategoryIds)            // 回调给父组件
```

当前问题：
1. 调用 `MaterialCategoryListRoute` 获取**全部**分类，无用户过滤
2. 创建新分类时无 `owner_id`，全局共享
3. 没有区分分类类型（普通 / 公开 / 同步）

#### 7.3.2 重构方案

**API 调用变更：**

```
CategoryPickerModal mount
  → POST MaterialCategoryListRoute { filter: "mine" }  // 仅获取自己的非同步分类
  → 渲染 checkbox 列表

用户点击"新建分类"
  → POST MaterialCategorySimpleCreateRoute { name }     // owner_id 由后端注入
  → 追加到列表并自动勾选（返回的 is_mine=true 保证一致性）

用户点击"确认"
  → onConfirm(selectedCategoryIds)                      // 不变
```

**过滤保证：** `filter: "mine"` 在后端排除了同步分类和他人分类，前端无需额外过滤。即使后端返回了意外数据，前端也可以用 `categories.filter(c => c.is_mine && !c.sync)` 做防御性过滤。

**创建新分类时自动关联 owner：**
- `MaterialCategorySimpleCreateRoute` 从 `RouteAuthContext` 获取当前用户 ID 作为 `owner_id`
- 前端无需传 `owner_id`，后端自动注入
- 创建成功后返回完整的 `MaterialCategory` 对象（含 `is_mine: true`），前端直接追加到列表

**UI 不变：** checkbox 列表 + 新建输入框的交互模式保持不变，仅数据源过滤。

#### 7.3.3 编辑模式的特殊处理

编辑模式（`isEditMode=true`）下，`existingCategoryIds` 可能包含同步分类的 ID（素材同时属于用户分类和同步分类）。但 `filter: "mine"` 不返回同步分类，所以这些 ID 不会出现在 checkbox 列表中。

这是正确行为：用户编辑分类时只能操作自己的分类，同步分类的关联由同步引擎管理。`MaterialSetCategoriesRoute` 的后端实现（§7.4.2）保证只替换用户自己的分类关联，不影响同步分类关联。

### 7.4 视频导入流程（重构 `BilibiliVideoList` + `MaterialImportRoute`）

#### 7.4.1 当前实现分析

`BilibiliVideoList` 是收藏夹详情页（`favorite.fid.$fid.tsx`）的核心组件，管理：
- `libraryMap`：已导入素材的 DB ID → `{ id, categoryIds }` 映射
- 导入流程：选择视频 → `CategoryPickerModal` → `MaterialImportRoute`
- 编辑流程：已导入视频 → `CategoryPickerModal`（edit mode）→ `MaterialSetCategoriesRoute`
- 删除流程：`CategoryPickerModal` → `MaterialDeleteRoute`

`MaterialImportRoute` 要求 `categoryIds.isNotEmpty()`，即导入时必须选择至少一个分类。

当前完整调用链：

```
用户勾选视频 → 点击"导入"
  → CategoryPickerModal 弹出
  → 用户选择分类 → 点击"确认"
  → POST MaterialImportRoute {
      source_type: "bilibili",
      videos: [{ bvid, title, cover, page, duration, upper, fav_time }],
      category_ids: ["cat-1", "cat-2"],
      source_fid: "12345"
    }
  → 后端：
    1. 对每个 video 生成确定性 ID：bilibili_bvid__{bvid}__P{page}
    2. INSERT OR IGNORE material（幂等）
    3. INSERT OR IGNORE material_category_rel（关联分类）
  → 前端：更新 libraryMap，视频行显示"已入库"标记
```

#### 7.4.2 重构要点

**导入目标分类的权限校验（后端）：**

```kotlin
// MaterialImportRoute.handler 中新增校验
val userId = context.userId!!
val categoryIds = request.categoryIds

// 批量查询分类，验证权限
val categories = MaterialCategoryService.repo.findByIds(categoryIds)
for (cat in categories) {
    if (cat.ownerId != userId) {
        return respond(error = "不能导入到他人的分类")
    }
}
// 检查是否包含同步分类
val syncCategoryIds = SyncPlatformInfoService.repo.findByCategoryIds(categoryIds).map { it.categoryId }.toSet()
if (syncCategoryIds.isNotEmpty()) {
    return respond(error = "不能手动导入到同步分类")
}
```

**编辑分类的权限校验（`MaterialSetCategoriesRoute` 重构）：**

当前实现是全量替换 `material_category_rel`：`DELETE WHERE material_id = ? → INSERT 新关联`。

重构后需要**保留同步分类关联**：

```kotlin
// MaterialSetCategoriesRoute.handler 重构
val userId = context.userId!!
val materialId = request.materialId
val newCategoryIds = request.categoryIds

// 1. 验证新分类全部属于当前用户
val newCategories = MaterialCategoryService.repo.findByIds(newCategoryIds)
for (cat in newCategories) {
    if (cat.ownerId != userId) return respond(error = "不能关联到他人的分类")
}

// 2. 查询当前素材的所有分类关联
val currentRels = MaterialCategoryRelDb.findByMaterialId(materialId)

// 3. 区分：哪些是同步分类关联（保留），哪些是用户分类关联（替换）
val syncCategoryIds = SyncPlatformInfoService.repo.findByCategoryIds(
    currentRels.map { it.categoryId }
).map { it.categoryId }.toSet()

// 4. 删除用户分类关联（保留同步分类关联）
MaterialCategoryRelDb.deleteByMaterialIdExcluding(materialId, syncCategoryIds)

// 5. 插入新的用户分类关联
for (catId in newCategoryIds) {
    MaterialCategoryRelDb.insertOrIgnore(materialId, catId)
}
```

**`source_fid` 字段的演进：**
- 当前 `MaterialImportRoute` 接收 `source_fid`（收藏夹 ID），存入 `extra` JSON
- 重构后，如果用户从同步分类的浏览页手动导入，`source_fid` 仍然有用（记录来源）
- 但同步引擎自动导入时，来源信息由 `material_category_sync_item` 表管理

#### 7.4.3 `libraryMap` 的分类显示适配

当前 `libraryMap` 中每个条目包含 `categoryIds: string[]`，用于在 `CategoryPickerModal` 编辑模式中预勾选。

重构后，`categoryIds` 可能包含同步分类的 ID。但 `CategoryPickerModal` 使用 `filter: "mine"` 只获取用户自己的分类，所以同步分类 ID 不会出现在 checkbox 列表中——这些 ID 被静默忽略，用户只看到自己的分类勾选状态。

**无需前端额外处理**：后端 `MaterialSetCategoriesRoute` 的"保留同步关联"逻辑保证了数据一致性。

### 7.5 收藏夹"入库"为同步分类

#### 7.5.1 当前问题

当前收藏夹浏览页（`favorite.fid.$fid.tsx`）的数据是**临时查询结果**：
- 用户输入 fid → 调用 Bilibili API → 展示视频列表 → 手动逐个/批量导入
- 收藏夹本身不持久化，刷新页面后需要重新输入 fid
- 没有"订阅"概念，无法自动同步新增视频

#### 7.5.2 重构方案：双模式

每个 add-resource 子页面提供两种操作模式：

**快速模式（创建同步分类）：**

```
用户输入 fid / uid / sid / bvid
  → 点击"创建为同步分类"
  → POST MaterialCategorySyncCreateRoute {
      sync_type: "bilibili_favorite",
      platform_config: { media_id: 12345 }
    }
  → 后端：查找或创建 platform_info（去重）+ 创建 user_config 订阅
  → 自动创建 material_category（allow_others_view=true）+ material_category_sync_platform_info
  → 返回 { platform_info_id, category_id, display_name }
  → 前端立即触发首次同步：
    POST MaterialCategorySyncTriggerRoute { platform_info_id }
  → navigate("/material-library?category={category_id}")
  → 素材库页面显示同步进度（§7.6）
```

**快速模式的 UI 布局：**

```
┌─────────────────────────────────────────────────────┐
│ 收藏夹 ID 或链接                                    │
│ ┌─────────────────────────────────────────────────┐ │
│ │ https://space.bilibili.com/fav/12345            │ │
│ └─────────────────────────────────────────────────┘ │
│                                                     │
│ [创建为同步分类]  [浏览内容 →]                      │
│                                                     │
│ 💡 创建同步分类后，系统将自动同步收藏夹中的所有视频 │
│    并在素材库中持续更新。                            │
└─────────────────────────────────────────────────────┘
```

**手动模式（浏览 + 选择性导入）：**
- 点击"浏览内容"按钮，跳转到 `favorite.fid.$fid` 子路由
- 保留现有的浏览 + 勾选 + 导入流程
- 导入时通过 `CategoryPickerModal` 选择目标分类（仅"我的分类"）
- 适用于只想导入部分素材、不需要持续同步的场景

#### 7.5.3 页面 ↔ sealed 子类型映射

| 前端路由 | 当前状态 | 对应 sealed 子类型 | 改造要点 |
|----------|----------|-------------------|----------|
| `favorite` | ✅ 完整 | `BilibiliFavorite` | 新增"创建为同步分类"按钮；保留手动导入 |
| `uploader` | ⚠️ 桩 | `BilibiliUploader` | 实现后端 API；替换 console.log 桩；新增同步按钮 |
| `collection` | ⚠️ 桩 | `BilibiliSeason` / `BilibiliSeries` | 实现后端 API；自动检测 season/series；新增同步按钮 |
| `multi-part` | ✅ 完整 | `BilibiliVideoPages` | 新增"创建为同步分类"按钮；保留手动导入 |

**各页面的 `sync_type` + `platform_config` 构造：**

```typescript
// favorite 页面
const syncPayload = { sync_type: "bilibili_favorite", platform_config: { media_id: parsedFid } };

// uploader 页面
const syncPayload = { sync_type: "bilibili_uploader", platform_config: { mid: parsedUid } };

// collection 页面（需要自动检测 season vs series）
// 用户输入 URL 或 ID → 解析出 season_id 或 series_id
// Bilibili 合集 URL: https://space.bilibili.com/{mid}/channel/collectiondetail?sid={season_id}
// Bilibili 列表 URL: https://space.bilibili.com/{mid}/channel/seriesdetail?sid={series_id}
const syncPayload = isSeasonUrl
    ? { sync_type: "bilibili_season", platform_config: { season_id: parsedSid, mid: parsedMid } }
    : { sync_type: "bilibili_series", platform_config: { series_id: parsedSid, mid: parsedMid } };

// multi-part 页面
const syncPayload = { sync_type: "bilibili_video_pages", platform_config: { bvid: parsedBvid } };
```

#### 7.5.4 `add-resource.bilibili.tsx` 布局适配

当前 3 个 tab 标记为 `todo: true`（显示"即将推出"）。重构后：
- 移除所有 `todo` 标记，启用全部 4 个 tab
- 每个 tab 页面顶部新增"创建为同步分类"快捷操作区

#### 7.5.5 collection 页面的 season/series 自动检测

Bilibili 的"合集"（season）和"列表"（series）使用不同的 API，但用户可能不清楚区别。前端需要自动检测：

```typescript
// URL 解析规则
function parseBilibiliCollectionUrl(input: string): {
    type: "season" | "series";
    id: number;
    mid: number;
} | null {
    // 合集 URL: /channel/collectiondetail?sid=xxx
    const seasonMatch = input.match(/collectiondetail\?sid=(\d+)/);
    if (seasonMatch) {
        const midMatch = input.match(/space\.bilibili\.com\/(\d+)/);
        return { type: "season", id: +seasonMatch[1], mid: midMatch ? +midMatch[1] : 0 };
    }
    // 列表 URL: /channel/seriesdetail?sid=xxx
    const seriesMatch = input.match(/seriesdetail\?sid=(\d+)/);
    if (seriesMatch) {
        const midMatch = input.match(/space\.bilibili\.com\/(\d+)/);
        return { type: "series", id: +seriesMatch[1], mid: midMatch ? +midMatch[1] : 0 };
    }
    return null;
}
```

如果用户直接输入数字 ID（无 URL），需要额外的 `mid` 输入框，因为 season/series API 都需要 `mid` 参数。

### 7.6 同步源管理面板

#### 7.6.1 同步信源条目 UI

在分类面板的"同步信源"分组中，每个条目显示：

```
┌─────────────────────────────────────────────┐
│ 🔄 收藏夹:默认收藏夹          最近同步 2h前 │
│    156 个素材                    [已启用 ✓] │
└─────────────────────────────────────────────┘
```

**条目信息来源（全部来自 `MaterialCategoryListRoute` 返回的 `sync` 字段）：**

| UI 元素 | 数据字段 | 说明 |
|---------|---------|------|
| 平台图标 | `sync.sync_type` 前缀 | `bilibili_*` → B站图标 |
| 类型标签 | `sync.sync_type` | 映射为中文：`bilibili_favorite` → "收藏夹" |
| 显示名 | `sync.display_name` | 用户可编辑，默认为数据源名称 |
| 素材计数 | `material_count`（分类级别） | 与普通分类相同的 COUNT 逻辑 |
| 同步时间 | `sync.last_synced_at` | 格式化为相对时间（"2h前"、"1d前"） |
| 同步状态 | `sync.sync_state` | `idle` / `syncing` / `failed`（见 §5 状态机） |
| 我的订阅 | `sync.my_subscription.enabled` | 绿色勾 / 灰色暂停图标（当前用户的订阅状态） |
| 订阅者数 | `sync.subscriber_count` | 显示"N 人订阅" |

**`sync_type` → 中文标签映射：**

```typescript
const SYNC_TYPE_LABELS: Record<string, string> = {
    bilibili_favorite: "收藏夹",
    bilibili_uploader: "UP主",
    bilibili_season: "合集",
    bilibili_series: "列表",
    bilibili_video_pages: "分P",
};
```

#### 7.6.2 展开详情面板

点击同步信源条目展开详情（使用 `useState<string | null>` 控制展开的 platformInfoId）：

```
┌─────────────────────────────────────────────┐
│ 🔄 收藏夹:默认收藏夹                       │
│                                             │
│ 平台：Bilibili                              │
│ 类型：收藏夹 (bilibili_favorite)            │
│ 参数：media_id = 12345                      │
│ 已同步：156 个素材                          │
│ 上次同步：2026-04-17 14:30                  │
│ 同步状态：idle / syncing / failed           │
│ 我的订阅：已启用 (cron: 0 */6 * * *)       │
│ 订阅者：3 人                                │
│                                             │
│ [立即同步]  [更新订阅设置]  [取消订阅]      │
│ （仅无其他订阅者或 ROOT 时显示 [删除数据源]）│
└─────────────────────────────────────────────┘
```

**参数显示逻辑：** 根据 `sync_type` 解析 `platform_config` 并格式化：

```typescript
function formatPlatformConfig(syncType: string, config: Record<string, unknown>): string {
    switch (syncType) {
        case "bilibili_favorite": return `media_id = ${config.media_id}`;
        case "bilibili_uploader": return `mid = ${config.mid}`;
        case "bilibili_season":   return `season_id = ${config.season_id}, mid = ${config.mid}`;
        case "bilibili_series":   return `series_id = ${config.series_id}, mid = ${config.mid}`;
        case "bilibili_video_pages": return `bvid = ${config.bvid}`;
        default: return JSON.stringify(config);
    }
}
```

#### 7.6.3 操作按钮的 API 调用链

**立即同步：**

```
点击"立即同步"
  → 按钮变为 loading 状态
  → POST MaterialCategorySyncTriggerRoute { platform_info_id }
  → 成功：返回 { workflow_run_id }
  → 前端进入同步进度轮询（见 §7.6.4）
  → 失败（SYNC_ALREADY_RUNNING）：toast "同步任务正在运行中"
  → 失败（SYNC_STATE_FAILED）：toast "数据源处于失败状态，请先重置"
```

**更新订阅设置：**

```
点击"更新订阅设置" → 弹出设置面板
  → 启用/禁用开关
  → cron 表达式输入（默认 "0 */6 * * *"）
  → 新鲜度窗口（秒）
  → POST MaterialCategorySyncUserConfigUpdateRoute { platform_info_id, enabled, cron_expr, freshness_window_sec }
  → 成功：刷新分类列表
```

**取消订阅：**

```
点击"取消订阅"
  → 确认弹窗："取消订阅后将不再参与此数据源的同步调度。已同步的素材不会被删除。确认取消？"
  → POST MaterialCategorySyncUnsubscribeRoute { platform_info_id }
  → 成功：刷新分类列表（如果是最后一个订阅者，同步分类消失）
```

**删除平台数据源（仅当无其他订阅者或 ROOT）：**

```
点击"删除数据源"
  → 确认弹窗："删除后将移除平台数据源及所有订阅关系。已同步的素材不会被删除。确认删除？"
  → POST MaterialCategorySyncPlatformDeleteRoute { platform_info_id }
  → 成功：刷新分类列表（同步分类消失）
  → 失败（HAS_OTHER_SUBSCRIBERS）：toast "还有其他订阅者，无法删除"
  → 失败（SYNC_RUNNING）：toast "请等待当前同步完成后再删除"
```

#### 7.6.4 同步进度轮询

触发同步后，前端需要展示进度。复用现有的 `WorkerTaskListRoute` 轮询模式（与 `WebenSourceAnalysisModal` 相同）：

```typescript
// 同步进度 hook
function useSyncProgress(workflowRunId: string | null) {
    const [progress, setProgress] = useState<SyncProgress | null>(null);
    const { appFetch } = useAppFetch();

    useEffect(() => {
        if (!workflowRunId) return;
        const timer = setInterval(async () => {
            const resp = await appFetch("/api/v1/MaterialWorkflowStatusRoute", {
                method: "GET",
                params: { workflow_run_id: workflowRunId },
            });
            if (!resp.ok) return;
            const data = await resp.json();
            setProgress(data);
            // 终态时停止轮询
            if (data.workflow_run.status === "completed" || data.workflow_run.status === "failed") {
                clearInterval(timer);
            }
        }, 2000);
        return () => clearInterval(timer);
    }, [workflowRunId]);

    return progress;
}
```

**进度显示：** 在同步信源条目中，同步运行时显示进度条替代"最近同步"时间：

```
┌─────────────────────────────────────────────┐
│ 🔄 收藏夹:默认收藏夹                       │
│    ████████░░░░░░░░ 同步中 52%              │
└─────────────────────────────────────────────┘
```

#### 7.6.5 权限控制

同步源管理按钮基于订阅关系显示。判断逻辑：

```typescript
// sync 条目的权限判断
// MaterialCategoryListRoute 返回的 sync 字段包含 my_subscription 和 subscriber_count
const isSubscribed = sync.my_subscription != null;
const isLastSubscriber = sync.subscriber_count === 1 && isSubscribed;
const isRoot = currentUser.role === "root";
```

**方案：** 在 `SyncPlatformInfoSummary` 中包含 `my_subscription: SyncUserConfigSummary | null` 字段和 `subscriber_count: Int`。前端据此决定：
- `my_subscription != null`：显示"立即同步"、"更新订阅设置"、"取消订阅"按钮
- `isLastSubscriber || isRoot`：额外显示"删除数据源"按钮
- `my_subscription == null`：仅显示"订阅此数据源"按钮（允许其他用户订阅已有的平台数据源）

---

## 8. 迁移策略

旧数据直接重构，不保留向后兼容。

### 8.1 数据库迁移

迁移在 `MaterialCategoryDb.initialize()` 中执行，遵循项目已有的 PRAGMA 检测 + 表重建模式（参考 `WebenConcept.kt`）。

#### 8.1.1 迁移检测

```kotlin
// MaterialCategoryDb.initialize() 内部
val hasOwnerIdColumn = conn.prepareStatement("PRAGMA table_info(material_category)").use { ps ->
    ps.executeQuery().use { rs ->
        var found = false
        while (rs.next()) { if (rs.getString("name") == "owner_id") { found = true; break } }
        found
    }
}
```

仅当 `hasOwnerIdColumn == false` 时执行以下迁移步骤。

#### 8.1.2 迁移 SQL（完整）

```sql
-- Step 1: 查找默认 owner（第一个 ROOT 用户）
-- 注意：表名是 `user`（非 auth_user），见 UserDb.kt:37
-- 如果无 ROOT 用户（理论上不可能，InstanceInit 必创建），使用空字符串兜底
SELECT id FROM user WHERE role = 'root' ORDER BY created_at ASC LIMIT 1;
-- → 记为 :defaultOwnerId（Kotlin 代码中查询并绑定）

-- Step 2: 重建 material_category 表（添加 owner_id + ACL 权限列，变更 UNIQUE 约束）
-- SQLite 不支持 ALTER CONSTRAINT，必须 CREATE → INSERT → DROP → RENAME
CREATE TABLE material_category_v2 (
    id                    TEXT PRIMARY KEY,
    owner_id              TEXT NOT NULL,
    name                  TEXT NOT NULL,
    description           TEXT NOT NULL DEFAULT '',
    allow_others_view     INTEGER NOT NULL DEFAULT 0,
    allow_others_add      INTEGER NOT NULL DEFAULT 0,
    allow_others_delete   INTEGER NOT NULL DEFAULT 0,
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL,
    UNIQUE(owner_id, name)
);

INSERT INTO material_category_v2 (id, owner_id, name, description, allow_others_view, allow_others_add, allow_others_delete, created_at, updated_at)
SELECT id, :defaultOwnerId, name, description, 0, 0, 0, created_at, updated_at
FROM material_category;

DROP TABLE material_category;
ALTER TABLE material_category_v2 RENAME TO material_category;

-- Step 3: 创建 material_category_sync_platform_info 表
CREATE TABLE IF NOT EXISTS material_category_sync_platform_info (
    id              TEXT PRIMARY KEY,
    category_id     TEXT NOT NULL,
    sync_type       TEXT NOT NULL,
    platform_id     TEXT NOT NULL,
    platform_config TEXT NOT NULL DEFAULT '{}',
    display_name    TEXT NOT NULL DEFAULT '',
    last_synced_at  INTEGER,
    sync_cursor     TEXT NOT NULL DEFAULT '',
    item_count      INTEGER NOT NULL DEFAULT 0,
    sync_state      TEXT NOT NULL DEFAULT 'idle',
    last_error      TEXT,
    fail_count      INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE(sync_type, platform_id)
);

-- Step 4: 创建 material_category_sync_user_config 表
CREATE TABLE IF NOT EXISTS material_category_sync_user_config (
    id                    TEXT PRIMARY KEY,
    platform_info_id      TEXT NOT NULL,
    user_id               TEXT NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1,
    cron_expr             TEXT NOT NULL DEFAULT '0 */6 * * *',
    freshness_window_sec  INTEGER NOT NULL DEFAULT 3600,
    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL,
    UNIQUE(platform_info_id, user_id)
);

-- Step 5: 创建 material_category_sync_item 表
CREATE TABLE IF NOT EXISTS material_category_sync_item (
    platform_info_id TEXT NOT NULL,
    material_id      TEXT NOT NULL,
    platform_id      TEXT NOT NULL DEFAULT '',
    synced_at        INTEGER NOT NULL,
    PRIMARY KEY (platform_info_id, material_id)
);
```

#### 8.1.3 Kotlin 迁移实现

```kotlin
// MaterialCategoryDb.initialize() 中，在 CREATE TABLE IF NOT EXISTS 之后
if (!hasOwnerIdColumn) {
    logger.info("Migrating material_category: adding owner_id + ACL columns, rebuilding UNIQUE constraint")

    // Step 1: 查找默认 owner
    val defaultOwnerId = conn.prepareStatement(
        "SELECT id FROM user WHERE role = 'root' ORDER BY created_at ASC LIMIT 1"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString("id") else null }
    }

    if (defaultOwnerId == null) {
        logger.warn("No ROOT user found during migration — existing categories will have empty owner_id")
    }
    val ownerId = defaultOwnerId ?: ""

    // Step 2: 表重建（事务内执行）
    stmt.execute("""
        CREATE TABLE material_category_v2 (
            id                    TEXT PRIMARY KEY,
            owner_id              TEXT NOT NULL,
            name                  TEXT NOT NULL,
            description           TEXT NOT NULL DEFAULT '',
            allow_others_view     INTEGER NOT NULL DEFAULT 0,
            allow_others_add      INTEGER NOT NULL DEFAULT 0,
            allow_others_delete   INTEGER NOT NULL DEFAULT 0,
            created_at            INTEGER NOT NULL,
            updated_at            INTEGER NOT NULL,
            UNIQUE(owner_id, name)
        )
    """.trimIndent())
    conn.prepareStatement("""
        INSERT INTO material_category_v2 (id, owner_id, name, description,
            allow_others_view, allow_others_add, allow_others_delete, created_at, updated_at)
        SELECT id, ?, name, description, 0, 0, 0, created_at, updated_at
        FROM material_category
    """.trimIndent()).use { ps ->
        ps.setString(1, ownerId)
        ps.executeUpdate()
    }
    stmt.execute("DROP TABLE material_category")
    stmt.execute("ALTER TABLE material_category_v2 RENAME TO material_category")

    // Step 3-5: 新表（CREATE IF NOT EXISTS，幂等）
    // material_category_sync_platform_info、material_category_sync_user_config、
    // material_category_sync_item 的 CREATE 语句已在 initialize() 主流程中执行，此处无需重复

    // 验证迁移结果
    val migratedCount = conn.prepareStatement(
        "SELECT COUNT(*) FROM material_category WHERE owner_id = ?"
    ).use { ps ->
        ps.setString(1, ownerId)
        ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
    }
    logger.info("Migration complete: $migratedCount categories assigned to owner $ownerId")
}
```

#### 8.1.4 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| 无 ROOT 用户 | `owner_id` 设为空字符串，`logger.warn` 告警。首次 ROOT 登录后可通过管理面板认领 |
| 旧表无数据 | INSERT SELECT 结果为 0 行，不影响迁移流程 |
| 迁移中断（断电） | SQLite WAL 模式保证原子性；下次启动 PRAGMA 检测仍为旧 schema，重新执行 |
| 已迁移的 DB 再次启动 | `hasOwnerIdColumn == true`，跳过全部迁移逻辑 |
| `material_category_rel` 表 | 无需迁移，外键关系通过 `category_id` 保持不变 |
| 旧 `visibility` 列不存在 | 新建表直接使用 `allow_others_view/add/delete` 三列，默认值 0（等同旧 `private`） |

### 8.2 代码迁移

按依赖顺序分步执行，每步可独立编译验证。

#### 8.2.1 序列化模块注册（`jsons.kt`）

当前全局 JSON 实例无 `SerializersModule`，`SyncPlatformIdentity` 是项目中**首个需要多态序列化的 sealed interface**。

```kotlin
// jsons.kt — 修改全局 JSON 实例
val AppUtil.GlobalVars.json by lazy {
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(SyncPlatformIdentity::class) {
                subclass(SyncPlatformIdentity.BilibiliFavorite::class)
                subclass(SyncPlatformIdentity.BilibiliUploader::class)
                subclass(SyncPlatformIdentity.BilibiliSeason::class)
                subclass(SyncPlatformIdentity.BilibiliSeries::class)
                subclass(SyncPlatformIdentity.BilibiliVideoPages::class)
            }
        }
    }
}
```

> **注意**：`jsonPretty` 实例也需要同步添加相同的 `serializersModule`，否则美化输出时反序列化失败。可提取为共享变量：
>
> ```kotlin
> private val syncPlatformModule = SerializersModule {
>     polymorphic(SyncPlatformIdentity::class) { ... }
> }
> ```

序列化后的 JSON 格式（`platform_config` 列存储内容）：

```json
// BilibiliFavorite
{"type": "bilibili_favorite", "media_id": 12345}

// BilibiliSeason
{"type": "bilibili_season", "season_id": 678, "mid": 999}
```

`type` 字段由 kotlinx.serialization 的 `classDiscriminator`（默认 `"type"`）自动添加，与 `sync_type` 列的值一致。

#### 8.2.2 DB 层（`material_category/db/`）

**`MaterialCategoryDb.kt`** 变更：

| 变更 | 说明 |
|------|------|
| `initialize()` | 添加 §8.1.3 的迁移逻辑 + 三张新表的 CREATE TABLE |
| `create(userId, name, desc, allowView, allowAdd, allowDelete)` | 新增 `owner_id` + ACL 参数，INSERT 时写入 |
| `listAll(userId)` → `listForUser(userId)` | 返回 `owner_id = userId` 的全部 + `allow_others_view = 1` 的他人分类 |
| `listMine(userId)` | 新增，仅返回 `owner_id = userId` |
| `deleteById(categoryId, userId)` | 新增 `userId` 参数，WHERE 条件加 `owner_id = ?`（ROOT 跳过此检查） |
| `update(categoryId, userId, name?, desc?, allowView?, allowAdd?, allowDelete?)` | 新增方法，仅 owner 或 ROOT 可修改。关闭 `allow_others_view` 时自动关闭 `allow_others_add` 和 `allow_others_delete` |

新增 **`SyncPlatformInfoDb`** 类（`material_category/db/SyncPlatformInfoDb.kt`）：

| 方法 | 说明 |
|------|------|
| `initialize()` | CREATE TABLE IF NOT EXISTS `material_category_sync_platform_info` + `material_category_sync_item` |
| `create(record: SyncPlatformInfoRecord)` | INSERT，失败时检查 UNIQUE(sync_type, platform_id) 约束返回具体错误 |
| `findById(platformInfoId)` | 查询单条平台数据源 |
| `findByCategoryId(categoryId)` | 查询分类关联的平台数据源 |
| `findByCategoryIds(categoryIds)` | 批量查询多个分类关联的平台数据源 |
| `updateAfterSyncSuccess(id, cursor, lastSyncedAt, itemCount)` | 同步成功后更新游标，重置 `sync_state = 'idle'`、`fail_count = 0`、`last_error = null` |
| `updateAfterSyncFailure(id, error)` | 同步失败后 `fail_count += 1`、`sync_state = 'failed'`、`last_error = error` |
| `updateSyncState(id, state)` | 更新 `sync_state`（如 `'syncing'`） |
| `delete(platformInfoId)` | 删除平台数据源 + 级联删除 sync_item + 级联删除 user_config（事务） |
| `upsertItems(platformInfoId, items)` | 批量 INSERT OR IGNORE sync_item |
| `subscriberCount(platformInfoId)` | 查询 `material_category_sync_user_config` 中的订阅者数量 |

新增 **`SyncUserConfigDb`** 类（`material_category/db/SyncUserConfigDb.kt`）：

| 方法 | 说明 |
|------|------|
| `initialize()` | CREATE TABLE IF NOT EXISTS `material_category_sync_user_config` |
| `create(config: SyncUserConfigRecord)` | INSERT，UNIQUE(platform_info_id, user_id) 约束 |
| `findByPlatformInfoAndUser(platformInfoId, userId)` | 查询用户对某平台数据源的订阅配置 |
| `listByUser(userId)` | 列出用户的所有订阅配置 |
| `listByPlatformInfo(platformInfoId)` | 列出某平台数据源的所有订阅者配置 |
| `update(id, enabled?, cronExpr?, freshnessWindowSec?)` | 更新订阅设置 |
| `delete(id)` | 删除单条订阅配置 |
| `deleteByPlatformInfoAndUser(platformInfoId, userId)` | 按平台数据源 + 用户删除 |

#### 8.2.3 Service 层

```
MaterialCategoryService（重构）
├── repo: MaterialCategoryRepo（接口不变，实现加 userId + ACL 参数）
└── initialize(repo)

SyncPlatformInfoService（新增）
├── repo: SyncPlatformInfoRepo
├── initialize(repo)
└── 业务方法：createWithCategory(), delete(), triggerSync(), ...

SyncUserConfigService（新增）
├── repo: SyncUserConfigRepo
├── initialize(repo)
└── 业务方法：subscribe(), unsubscribe(), updateConfig(), ...
```

`SyncPlatformInfoService.createWithCategory()` 是关键方法——创建平台数据源时自动创建关联的 `material_category`（`allow_others_view=1`），在同一事务中完成。

`SyncUserConfigService.subscribe()` 在订阅时检查 `platform_info` 是否已存在：
- 已存在 → 直接创建 `user_config` 记录
- 不存在 → 先创建 `platform_info`（含关联 category），再创建 `user_config`

#### 8.2.4 路由层

现有路由采用 handler2 委托模式（见 §12.3.2）。Route 对象留在 `api/routes/`，业务逻辑迁移到 `material_category/route_ext/`：

```kotlin
// api/routes/MaterialCategoryListRoute.kt — 瘦壳
object MaterialCategoryListRoute : FredicaApi.Route(...) {
    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}

// material_category/route_ext/MaterialCategoryListRouteExt.kt — 业务逻辑
@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategoryListRoute.handler2(param: String): ValidJsonString {
    val userId = RouteAuthContext.currentUserId()  // nullable for GUEST
    // ...
}
```

所有路由的权限检查模式（在 handler2 中）：

```kotlin
// 写操作（create/update/delete）— 需要 Authenticated 身份
val user = RouteAuthContext.currentUser()
    ?: return buildJsonObject { put("error", "需要登录") }.toValidJson()

// 删除操作 — 需要 owner 或 ROOT
val category = repo.findById(categoryId)
if (category.ownerId != user.userId && user.role != AuthRole.ROOT) {
    return buildJsonObject { put("error", "无权操作") }.toValidJson()
}
```

#### 8.2.5 FredicaApi.jvm.kt 初始化顺序

```kotlin
// 在 configureDatabases() 中，SyncPlatformInfoDb 和 SyncUserConfigDb 在 MaterialCategoryDb 之后初始化
MaterialCategoryDb(database).also {
    it.initialize()
    MaterialCategoryService.initialize(it)
}
SyncPlatformInfoDb(database).also {
    it.initialize()
    SyncPlatformInfoService.initialize(it)
}
SyncUserConfigDb(database).also {
    it.initialize()
    SyncUserConfigService.initialize(it)
}
```

#### 8.2.6 前端类型变更

```typescript
// types/materialCategory.ts
interface MaterialCategory {
    id: string;
    owner_id: string;
    name: string;
    description: string;
    allow_others_view: boolean;
    allow_others_add: boolean;
    allow_others_delete: boolean;
    material_count: number;
    is_mine: boolean;
    sync?: SyncPlatformInfoSummary;
    created_at: number;
    updated_at: number;
}

interface SyncPlatformInfoSummary {
    id: string;                              // platform_info_id
    sync_type: string;
    platform_config: Record<string, unknown>; // 已解析的平台配置（含 type 鉴别器）
    display_name: string;
    last_synced_at: number | null;
    item_count: number;
    sync_state: "idle" | "syncing" | "failed";
    last_error: string | null;
    fail_count: number;
    subscriber_count: number;
    my_subscription: SyncUserConfigSummary | null;  // 当前用户的订阅配置，null 表示未订阅
}

interface SyncUserConfigSummary {
    id: string;
    enabled: boolean;
    cron_expr: string;
    freshness_window_sec: number;
}
```

前端组件按 `is_mine` + `sync` 字段分三组渲染（见 §7.2）。编辑/删除按钮仅在 `is_mine === true` 时显示。权限面板（§7.2.4）控制 `allow_others_view/add/delete` 三个开关。

---

## 9. Kotlin 模型变更

**文件位置**：模型按类拆分到 `material_category/model/` 目录（见 §12.2），每个模型一个文件。`SyncPlatformIdentity` sealed interface 已在 §3.3.2 定义，位于 `material_category/model/SyncPlatformIdentity.kt`。

### 9.1 `MaterialCategory`（重构）

```kotlin
@Serializable
data class MaterialCategory(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val description: String,
    val visibility: String = "private",  // "private" | "public"
    @SerialName("allow_others_view") val allowOthersView: Boolean = false,
    @SerialName("allow_others_add") val allowOthersAdd: Boolean = false,
    @SerialName("allow_others_delete") val allowOthersDelete: Boolean = false,
    @SerialName("material_count") val materialCount: Int = 0,
    @SerialName("is_mine") val isMine: Boolean = false,
    val sync: SyncPlatformInfoSummary? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)
```

`materialCount` 和 `isMine` 不存储在 DB 中，由查询时计算：

```kotlin
// MaterialCategoryDb.listForUser() 中
val isMine = rs.getString("owner_id") == userId
val allowOthersView = rs.getInt("allow_others_view") == 1
val materialCount = countRelStmt.use { ps ->
    ps.setString(1, categoryId)
    ps.executeQuery().use { it.next(); it.getInt(1) }
}
```

### 9.2 `SyncPlatformInfoRecord`（新增）

DB 行模型，对应 `material_category_sync_platform_info` 表的完整记录：

```kotlin
@Serializable
data class SyncPlatformInfoRecord(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("sync_type") val syncType: String,
    @SerialName("platform_id") val platformId: String,
    @SerialName("platform_config") val platformConfig: String,  // JSON 字符串，反序列化为 SyncPlatformIdentity 子类
    @SerialName("display_name") val displayName: String,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    @SerialName("sync_cursor") val syncCursor: String = "",
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sync_state") val syncState: String = "idle",   // "idle" | "syncing" | "failed"
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("fail_count") val failCount: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) {
    /** 反序列化 platform_config 为 sealed interface 子类 */
    fun parseConfig(): SyncPlatformIdentity =
        platformConfig.loadJsonModel<SyncPlatformIdentity>().getOrThrow()
}
```

**`parseConfig()` 依赖链**：`loadJsonModel<T>()` 内部调用全局 `AppUtil.GlobalVars.json`，该实例必须已注册 `SyncPlatformIdentity` 的 `SerializersModule`（见 §8.2.1），否则运行时抛 `SerializationException: Serializer for class 'SyncPlatformIdentity' is not found`。

**`platformConfig` 存储格式**：DB 中存储的是带 `type` 鉴别器的 JSON 字符串：

```json
{"type":"bilibili_favorite","media_id":12345}
{"type":"bilibili_season","season_id":678,"mid":999}
```

### 9.2b `SyncUserConfigRecord`（新增）

DB 行模型，对应 `material_category_sync_user_config` 表的完整记录：

```kotlin
@Serializable
data class SyncUserConfigRecord(
    val id: String,
    @SerialName("platform_info_id") val platformInfoId: String,
    @SerialName("user_id") val userId: String,
    val enabled: Boolean = true,
    @SerialName("cron_expr") val cronExpr: String = "0 */6 * * *",
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int = 3600,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

### 9.3 `SyncPlatformInfoSummary`（新增）

列表接口中嵌入 `MaterialCategory` 的摘要信息（已反序列化的 sealed 子类，前端可直接读取字段）：

```kotlin
@Serializable
data class SyncPlatformInfoSummary(
    val id: String,
    @SerialName("sync_type") val syncType: String,
    @SerialName("platform_config") val platformConfig: SyncPlatformIdentity,  // 已反序列化的 sealed 子类
    @SerialName("display_name") val displayName: String,
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sync_state") val syncState: String = "idle",
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("fail_count") val failCount: Int = 0,
    @SerialName("subscriber_count") val subscriberCount: Int = 0,
    @SerialName("my_subscription") val mySubscription: SyncUserConfigSummary? = null,
)

@Serializable
data class SyncUserConfigSummary(
    val id: String,
    val enabled: Boolean = true,
    @SerialName("cron_expr") val cronExpr: String = "0 */6 * * *",
    @SerialName("freshness_window_sec") val freshnessWindowSec: Int = 3600,
)
```

**`SyncPlatformInfoRecord` → `SyncPlatformInfoSummary` 转换**（在 `MaterialCategoryListRoute` handler 中）：

```kotlin
fun SyncPlatformInfoRecord.toSummary(
    subscriberCount: Int,
    myConfig: SyncUserConfigRecord?,
) = SyncPlatformInfoSummary(
    id = id,
    syncType = syncType,
    platformConfig = parseConfig(),  // JSON 字符串 → sealed 子类
    displayName = displayName,
    lastSyncedAt = lastSyncedAt,
    itemCount = itemCount,
    syncState = syncState,
    lastError = lastError,
    failCount = failCount,
    subscriberCount = subscriberCount,
    mySubscription = myConfig?.let {
        SyncUserConfigSummary(
            id = it.id,
            enabled = it.enabled,
            cronExpr = it.cronExpr,
            freshnessWindowSec = it.freshnessWindowSec,
        )
    },
)
```

**序列化输出**：`SyncPlatformInfoSummary` 的 `platformConfig` 字段类型是 `SyncPlatformIdentity`（sealed interface），序列化时 kotlinx.serialization 自动添加 `type` 鉴别器。前端收到的 JSON：

```json
{
    "id": "pi-abc",
    "sync_type": "bilibili_favorite",
    "platform_config": { "type": "bilibili_favorite", "media_id": 12345 },
    "display_name": "我的收藏夹",
    "last_synced_at": 1700000000,
    "item_count": 42,
    "sync_state": "idle",
    "last_error": null,
    "fail_count": 0,
    "subscriber_count": 2,
    "my_subscription": {
        "id": "uc-xyz",
        "enabled": true,
        "cron_expr": "0 */6 * * *",
        "freshness_window_sec": 3600
    }
}
```

> `SyncPlatformIdentity` sealed interface 定义见 §3.3.2。

---

## 10. 实施计划

> 原 Phase A1（分类用户化）和 Phase A2（同步源基础设施）合并为 Phase A1，因为 add-resource 页面同时依赖两者。
> Phase A0（包结构重组）在功能开发前执行，纯重构无行为变更。

### Phase A0：包结构重组（见 §12）

纯重构，无行为变更，独立 commit。

1. 创建 `material_category/` 目录结构（`db/`、`model/`、`service/`、`route_ext/`）
2. 拆分 `db/MaterialCategory.kt` → `model/MaterialCategory.kt` + `db/MaterialCategoryRepo.kt` + `service/MaterialCategoryService.kt`
3. 迁移 `db/MaterialCategoryDb.kt` → `material_category/db/MaterialCategoryDb.kt`
4. 提取路由 handler → `route_ext/` handler2 扩展函数
5. 更新所有 import（`FredicaApi.jvm.kt`、Route 文件）
6. 编译验证 + 运行现有测试
7. 删除旧文件

### Phase A1：分类用户化 + 同步源基础设施

**后端 — 分类用户化：**
1. 迁移 `material_category` 表结构（添加 `owner_id` + ACL 列 `allow_others_view`/`allow_others_add`/`allow_others_delete`，重建唯一约束）
2. 重构 `MaterialCategoryDb`：所有方法加 `userId` 参数，`listForUser` 按 ACL 过滤
3. 重构 `MaterialCategoryRepo` 接口
4. 重构现有路由：注入用户身份，按 ACL 权限过滤；`MaterialCategoryCreateRoute` → `MaterialCategorySimpleCreateRoute`；`MaterialCategoryDeleteRoute` → `MaterialCategorySimpleDeleteRoute`
5. 新增 `MaterialCategorySimpleUpdateRoute` + `MaterialCategorySyncUpdateRoute`
6. 新增 `MaterialCategorySyncDeleteRoute`（级联删除同步元数据）

**后端 — 同步源基础设施：**
6. 新增 `material_category_sync_platform_info` 和 `material_category_sync_user_config` 和 `material_category_sync_item` 表
7. 新增 `SyncPlatformInfoDb` + `SyncPlatformInfoRepo` + `SyncPlatformInfoService`
8. 新增 `SyncUserConfigDb` + `SyncUserConfigRepo` + `SyncUserConfigService`
9. 注册 `SyncPlatformIdentity` sealed interface 的 kotlinx.serialization 多态模块
10. 新增同步源路由：`MaterialCategorySyncSubscribeRoute`（订阅/创建）、`MaterialCategorySyncUnsubscribeRoute`（退订）、`MaterialCategorySyncTriggerRoute`（手动触发）、`MaterialCategorySyncUserConfigUpdateRoute`（更新订阅设置）、`MaterialCategorySyncPlatformDeleteRoute`（删除平台源，仅 owner/ROOT）

**前端 — 分类面板重构：**
11. `MaterialCategory` 类型新增 `owner_id`、ACL 字段（`allow_others_view/add/delete`）、`is_mine`、`sync?` 字段
12. `MaterialCategoryPanel` 三组渲染（我的分类 / 可见分类 / 同步信源）
13. `CategoryPickerModal` 只显示 `is_mine = true` 的分类

**前端 — add-resource 页面整合（见 §7.5）：**
14. `add-resource.bilibili.tsx`：移除 3 个 tab 的 `todo` 标记，启用全部 tab
15. 各子页面新增"创建为同步分类"按钮（快速模式）
16. `favorite` / `multi-part`：适配新分类 API（已有完整实现，仅需对接）

**测试：**
17. 分类 CRUD + ACL 权限隔离测试
18. 同步源 CRUD 测试（platform_info + user_config）

### Phase A2：Bilibili 收藏夹同步 + 后端补全

**同步执行器：**
1. 新增 `MaterialCategorySyncTriggerRoute`（手动触发同步，已在 Phase A1 注册路由壳）
2. 新增 `SyncBilibiliFavoriteExecutor`（WorkerEngine executor，`when (config) { is BilibiliFavorite -> ... }`）
3. 复用 `BilibiliFavoriteGetVideoListRoute` 的 Python 后端
4. 增量同步逻辑（sync_cursor = `fav_time`）
5. 同步状态机集成：成功调 `SyncPlatformInfoDb.updateAfterSyncSuccess()`，失败调 `updateAfterSyncFailure()`（指数退避）
6. Cron 调度集成：`SyncUserConfigDb` 中的 `cron_expr` + `freshness_window_sec` 驱动定时同步

**add-resource 桩页面后端补全：**
7. Python 新增 `/bilibili/uploader/get-video-list` 路由
8. Python 新增 `/bilibili/season/get-video-list` 和 `/bilibili/series/get-video-list` 路由
9. Kotlin 新增 `BilibiliUploaderGetVideoListRoute`
10. Kotlin 新增 `BilibiliSeasonGetVideoListRoute` / `BilibiliSeriesGetVideoListRoute`
11. 前端 `uploader` 页面：替换 `console.log` 桩为真实 API 调用
12. 前端 `collection` 页面：实现 season/series 自动检测 + 真实 API 调用

**前端：**
13. 同步状态展示（sync_state 状态机：idle/syncing/failed + fail_count + last_error）
14. 手动触发按钮 + 订阅管理（cron_expr / freshness_window_sec 编辑）

### Phase A3：其他 Bilibili 同步类型

1. `SyncBilibiliUploaderExecutor`：UP 主投稿同步（增量，`pubdate` 游标）
2. `SyncBilibiliSeasonExecutor`：合集同步（全量，有限集合）
3. `SyncBilibiliSeriesExecutor`：列表同步（全量，有限集合）
4. `SyncBilibiliVideoPagesExecutor`：多 P 视频分 P 同步（全量，无分页）
5. 前端：各同步类型的创建表单中集成同步触发

### Phase A4：完善与优化（未来）

- `BilibiliCollectedFavorites`：收藏的合集同步（需登录凭据）
- 素材 `owner_id` 字段（素材级别的用户隔离）
- 分类排序/置顶
- YouTube 等其他平台同步类型

---

## 11. 测试计划

**文件位置**：`shared/src/jvmTest/kotlin/com/github/project_fredica/material_category/`（见 §12.5）

**通用 Setup**（所有 DB 测试共享）：

```kotlin
private lateinit var db: Database
private lateinit var tmpFile: File

@BeforeTest
fun setup() {
    tmpFile = File.createTempFile("test_category_", ".db").also { it.deleteOnExit() }
    db = Database.connect(url = "jdbc:sqlite:${tmpFile.absolutePath}", driver = "org.sqlite.JDBC")
}

@AfterTest
fun teardown() {
    tmpFile.delete()
}
```

### 11.1 `MaterialCategoryDbTest`（重构）

| ID | 测试 | 预期 |
|----|------|------|
| MC1 | 用户 A 创建分类 | owner_id = A，allow_others_view = false |
| MC2 | 用户 A 和 B 创建同名分类 "学习" | 各自成功，返回不同 id |
| MC3 | 同一用户创建同名分类 | 抛异常（UNIQUE(owner_id, name) 约束） |
| MC4 | `listForUser(userId=A)` | 返回 A 的全部 + B 的 allow_others_view=true 分类，不含 B 的 allow_others_view=false |
| MC5 | `listMine(userId=A)` | 仅返回 owner_id = A 的分类 |
| MC6 | `deleteById(categoryId, userId=B)` 非 owner | 返回 false / 抛权限错误 |
| MC7 | `deleteById(categoryId, userId=A)` owner | 成功，级联删除 `material_category_rel` 中的关联 |
| MC8 | `update(categoryId, userId=A, allowOthersView=true)` | allow_others_view 更新为 true，updated_at 变化 |
| MC9 | `update(categoryId, userId=B)` 非 owner | 失败 |
| MC10 | `listForUser` 返回的 `isMine` 字段 | A 的分类 isMine=true，B 的可见分类 isMine=false |
| MC11 | `listForUser` 返回的 `materialCount` | 正确统计 `material_category_rel` 中的关联数 |
| MC12 | 关闭 `allow_others_view` 时自动关闭 `allow_others_add` 和 `allow_others_delete` | 三个 ACL 字段联动 |

### 11.2 `MaterialCategoryMigrationTest`（新增）

验证 §8.1 的迁移逻辑。测试方法：先用旧 schema 建表并插入数据，再调用 `initialize()` 触发迁移。

```kotlin
@Test
fun migration_adds_owner_id_and_rebuilds_unique_constraint() {
    // 1. 创建旧 schema（无 owner_id、UNIQUE(name)）
    db.useConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE material_category (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(name)
                )
            """.trimIndent())
            stmt.execute("INSERT INTO material_category VALUES ('cat-1','学习','',1000,1000)")
            stmt.execute("INSERT INTO material_category VALUES ('cat-2','工作','',1000,1000)")
        }
    }
    // 2. 创建 user 表并插入 ROOT 用户（迁移需要查找默认 owner）
    db.useConnection { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE user (id TEXT PRIMARY KEY, role TEXT, created_at INTEGER)")
            stmt.execute("INSERT INTO user VALUES ('root-1','root',900)")
        }
    }
    // 3. 调用 initialize() 触发迁移
    val categoryDb = MaterialCategoryDb(db)
    categoryDb.initialize()
    // 4. 验证
    db.useConnection { conn ->
        conn.prepareStatement("SELECT owner_id, allow_others_view, allow_others_add, allow_others_delete FROM material_category WHERE id = 'cat-1'").use { ps ->
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                assertEquals("root-1", rs.getString("owner_id"))
                assertEquals(0, rs.getInt("allow_others_view"))
                assertEquals(0, rs.getInt("allow_others_add"))
                assertEquals(0, rs.getInt("allow_others_delete"))
            }
        }
    }
}
```

| ID | 测试 | 预期 |
|----|------|------|
| MG1 | 旧 schema + ROOT 用户存在 | 迁移后 owner_id = ROOT 用户 id，ACL 列全为 0（false） |
| MG2 | 旧 schema + 无 ROOT 用户 | 迁移后 owner_id = ""，logger.warn 输出 |
| MG3 | 旧 schema + 空表 | 迁移成功，无数据丢失 |
| MG4 | 新 schema（已迁移） | `initialize()` 跳过迁移，数据不变 |
| MG5 | 迁移后 UNIQUE(owner_id, name) 生效 | 不同 owner 可创建同名分类 |
| MG6 | 迁移后 `material_category_rel` 数据完整 | 外键关系通过 category_id 保持 |

### 11.3 `SyncPlatformInfoDbTest` + `SyncUserConfigDbTest`（新增）

#### 11.3a `SyncPlatformInfoDbTest`

| ID | 测试 | 预期 |
|----|------|------|
| SP1 | 创建平台源（BilibiliFavorite） | sync_type = "bilibili_favorite"，platform_config 含 media_id，platform_id 正确 |
| SP2 | 重复创建同一平台源（相同 sync_type + platform_id） | 抛异常（UNIQUE 约束） |
| SP3 | 不同 sync_type 相同 platform_id | 各自成功（UNIQUE 是 sync_type + platform_id 组合） |
| SP4 | 删除平台源 | 级联删除 `material_category_sync_user_config` + `material_category_sync_item` 中的关联记录 |
| SP5 | `updateAfterSyncSuccess` | sync_cursor、last_synced_at、item_count 更新，sync_state = "idle"，fail_count = 0 |
| SP6 | `updateAfterSyncFailure` | sync_state = "failed"，fail_count 递增，last_error 记录 |
| SP7 | `subscriberCount(platformInfoId)` | 返回关联的 user_config 数量 |
| SP8 | `findByCategoryId(categoryId)` | 返回关联的平台源记录 |
| SP9 | `findByPlatformKey(syncType, platformId)` | 精确匹配返回记录 |

#### 11.3b `SyncUserConfigDbTest`

| ID | 测试 | 预期 |
|----|------|------|
| UC1 | 用户订阅平台源 | 创建 user_config 记录，enabled = true，默认 cron_expr |
| UC2 | 同一用户重复订阅 | 抛异常（UNIQUE(platform_info_id, user_id) 约束） |
| UC3 | 不同用户订阅同一平台源 | 各自成功 |
| UC4 | 退订（删除 user_config） | 记录删除，platform_info 不受影响 |
| UC5 | 更新订阅设置（cron_expr、freshness_window_sec） | 字段更新，updated_at 变化 |
| UC6 | `findByPlatformInfoAndUser(platformInfoId, userId)` | 精确匹配返回记录 |
| UC7 | `listByPlatformInfo(platformInfoId)` | 返回该平台源的所有订阅者配置 |
| UC8 | `listByUser(userId)` | 返回该用户的所有订阅配置 |

**序列化测试 Setup**（SP1 等依赖）：

```kotlin
// 确保全局 JSON 实例已注册 SyncPlatformIdentity 的 SerializersModule
// 测试中直接使用 loadJsonModel<SyncPlatformIdentity>()
@Test
fun sp1_parseConfig_bilibili_favorite() {
    val json = """{"type":"bilibili_favorite","media_id":12345}"""
    val config = json.loadJsonModel<SyncPlatformIdentity>().getOrThrow()
    assertIs<SyncPlatformIdentity.BilibiliFavorite>(config)
    assertEquals(12345L, config.mediaId)
}
```

### 11.4 路由权限测试

**Setup**：使用 `withContext(RouteAuthContext(identity))` 注入身份（参考 `TenantInviteRegisterRouteTest.kt` 模式）。

```kotlin
private fun tenantIdentity(userId: String) = AuthIdentity.Authenticated.TenantUser(
    userId = userId, username = "test-$userId", displayName = "Test User",
    sessionId = "sess-$userId", permissions = emptySet(),
)
private val rootIdentity = AuthIdentity.Authenticated.RootUser(
    userId = "root-1", username = "root", displayName = "Root",
    sessionId = "sess-root", permissions = emptySet(),
)
private val guestIdentity = AuthIdentity.Guest
```

| ID | 测试 | 预期 |
|----|------|------|
| **MaterialCategorySimpleCreateRoute** | | |
| RP1 | TENANT 创建自己的分类 | 成功，owner_id = 当前用户 |
| RP2 | GUEST 创建分类 | `{ error: "..." }`（minRole = TENANT） |
| RP3 | 创建时 name 为空 | `{ error: "分类名称不能为空" }` |
| RP4 | 创建时 name 与自己已有分类重复 | `{ error: "分类名称已存在" }` |
| **MaterialCategorySimpleDeleteRoute** | | |
| RP5 | TENANT 删除自己的简易分类 | 成功 |
| RP6 | TENANT 删除他人简易分类 | `{ error: "无权操作" }` |
| RP7 | ROOT 删除他人简易分类 | 成功 |
| RP8 | 删除同步分类 | `{ error: "同步分类请使用 MaterialCategorySyncDeleteRoute" }` |
| **MaterialCategorySyncDeleteRoute** | | |
| RP8b | TENANT 删除自己的同步分类 | 成功，级联删除 sync_item + sync_user_config + sync_platform_info + rel |
| RP8c | TENANT 删除他人同步分类 | `{ error: "无权操作" }` |
| RP8d | ROOT 删除他人同步分类 | 成功 |
| RP8e | 删除简易分类 | `{ error: "简易分类请使用 MaterialCategorySimpleDeleteRoute" }` |
| **MaterialCategoryListRoute** | | |
| RP9 | TENANT 列出分类 | 返回自己的全部 + 他人 allow_others_view=true，每条含 is_mine 字段 |
| RP10 | GUEST 列出分类 | 仅返回 allow_others_view=true 分类，is_mine 全为 false |
| RP11 | 带同步源的分类 | 返回中含 sync 摘要字段（SyncPlatformInfoSummary） |
| **MaterialSetCategoriesRoute** | | |
| RP12 | TENANT 向自己的分类添加素材 | 成功 |
| RP13 | TENANT 向他人 allow_others_add=false 分类添加素材 | 失败 |
| RP14 | TENANT 向他人 allow_others_view=true 但 allow_others_add=false 分类添加素材 | 失败（可见不等于可写） |
| RP14b | TENANT 向他人 allow_others_add=true 分类添加素材 | 成功 |
| **MaterialCategorySimpleUpdateRoute** | | |
| RP15 | TENANT 修改自己简易分类的名称 | 成功 |
| RP16 | TENANT 修改他人简易分类 | `{ error: "无权操作" }` |
| RP17 | ROOT 修改他人简易分类 | 成功 |
| RP18 | 修改名称与自己已有分类重复 | `{ error: "分类名称已存在" }` |
| RP18b | 修改同步分类 | `{ error: "同步分类请使用 MaterialCategorySyncUpdateRoute" }` |
| **MaterialCategorySyncUpdateRoute** | | |
| RP19 | TENANT 修改自己同步分类的名称 | 成功 |
| RP20 | TENANT 修改他人同步分类 | `{ error: "无权操作" }` |
| RP21 | ROOT 修改他人同步分类 | 成功 |
| RP22 | 尝试修改 visibility | `{ error: "同步分类不支持修改可见性" }` |
| RP23 | 修改简易分类 | `{ error: "简易分类请使用 MaterialCategorySimpleUpdateRoute" }` |

### 11.5 同步源路由测试

| ID | 测试 | 预期 |
|----|------|------|
| **MaterialCategorySyncSubscribeRoute** | | |
| SY1 | 订阅 BilibiliFavorite 同步源（首次，平台源不存在） | 自动创建 platform_info + user_config + 关联分类，返回 category_id + platform_info_id + user_config_id |
| SY2 | 订阅已存在的平台源（其他用户已创建） | 仅创建 user_config，复用已有 platform_info |
| SY3 | 重复订阅同一平台源 | `{ error: "已订阅该信源" }` |
| SY4 | 请求参数无效（缺少 sync_type） | `{ error: "请求参数无效" }` |
| **MaterialCategorySyncUnsubscribeRoute** | | |
| SY5 | 退订同步源 | 删除 user_config，platform_info 保留（其他订阅者仍在） |
| SY6 | 退订后无订阅者 | platform_info 保留（不自动清理，由 owner 手动删除） |
| SY7 | 非订阅者退订 | `{ error: "未订阅该信源" }` |
| **MaterialCategorySyncPlatformDeleteRoute** | | |
| SY8 | owner 删除平台源 | 成功，级联删除所有 user_config + sync_item |
| SY9 | 非 owner 删除 | `{ error: "无权操作" }` |
| **MaterialCategorySyncTriggerRoute** | | |
| SY10 | 手动触发同步 | 返回 `{ workflow_run_id: "..." }`，sync_state → "syncing" |
| SY11 | 平台源处于 syncing 状态时触发 | `{ error: "同步正在进行中" }` |
| **MaterialCategorySyncUserConfigUpdateRoute** | | |
| SY12 | 更新 cron_expr | 字段更新成功 |
| SY13 | 更新 freshness_window_sec | 字段更新成功 |
| SY14 | 更新他人的订阅配置 | `{ error: "无权操作" }` |

### 11.6 `SyncPlatformIdentitySerializationTest`（新增）

独立测试 `SyncPlatformIdentity` sealed interface 的多态序列化/反序列化，不依赖 DB。

| ID | 测试 | 预期 |
|----|------|------|
| SE1 | BilibiliFavorite 序列化 | `{"type":"bilibili_favorite","media_id":12345}` |
| SE2 | BilibiliFavorite 反序列化 | `BilibiliFavorite(mediaId=12345)` |
| SE3 | BilibiliUploader 往返 | 序列化 → 反序列化 → 字段一致 |
| SE4 | BilibiliSeason 往返 | 序列化 → 反序列化 → seasonId + mid 一致 |
| SE5 | BilibiliSeries 往返 | 序列化 → 反序列化 → seriesId + mid 一致 |
| SE6 | BilibiliVideoPages 往返 | 序列化 → 反序列化 → bvid 一致 |
| SE7 | 未知 type 反序列化 | 抛 `SerializationException`（不静默忽略） |
| SE8 | `SyncPlatformInfoSummary` 含 sealed 子类序列化 | JSON 中 platform_config 含 type 鉴别器 |

```kotlin
@Test
fun se1_bilibili_favorite_serialization() {
    val config: SyncPlatformIdentity = SyncPlatformIdentity.BilibiliFavorite(mediaId = 12345)
    val json = AppUtil.dumpJsonStr(config).getOrThrow().value
    val parsed = json.loadJsonModel<SyncPlatformIdentity>().getOrThrow()
    assertIs<SyncPlatformIdentity.BilibiliFavorite>(parsed)
    assertEquals(12345L, parsed.mediaId)
}

@Test
fun se7_unknown_type_throws() {
    val json = """{"type":"youtube_playlist","playlist_id":"PLxxx"}"""
    val result = json.loadJsonModel<SyncPlatformIdentity>()
    assertTrue(result.isFailure)
}
```

### 11.7 前端测试

**文件位置**：`fredica-webui/tests/`

| ID | 测试 | 预期 |
|----|------|------|
| FE1 | 分类列表三组渲染 | "我的分类"、"可见分类"、"同步信源" 各组正确分类 |
| FE2 | is_mine=false 时隐藏编辑/删除按钮 | 按钮不渲染 |
| FE3 | 同步源分类显示同步状态 | 显示 last_synced_at、item_count、enabled 状态 |
| FE4 | 创建分类表单提交 | 调用 MaterialCategorySimpleCreateRoute，成功后刷新列表 |
| FE5 | 删除分类确认弹窗 | 简易分类调用 MaterialCategorySimpleDeleteRoute，同步分类调用 MaterialCategorySyncDeleteRoute |

---

## 12. 包结构重组

> 参考 `asr/` 包的领域组织模式，将 material_category 相关代码从扁平的 `db/` + `api/routes/` 迁移到独立的 `material_category/` 领域包中。

### 12.1 ASR 包参考结构

```
shared/src/commonMain/kotlin/.../asr/
├── db/                          # Repo 接口 + DB 实现
│   ├── BilibiliSubtitleBodyCacheRepo.kt
│   └── BilibiliSubtitleMetaCacheRepo.kt
├── model/                       # 数据模型（一类一文件）
│   ├── AsrConfig.kt
│   ├── BilibiliSubtitleMeta.kt
│   └── ... (27 files)
├── service/                     # Service Holder + 业务逻辑
│   ├── MaterialSubtitleService.kt
│   └── ... (7 files)
├── route_ext/                   # handler2 扩展函数（路由业务逻辑）
│   ├── AsrConfigRouteExt.kt
│   └── ... (7 files)
├── executor/                    # WorkerEngine Executor
│   └── AsrSpawnChunksExecutor.kt
├── srt/                         # 领域工具函数
│   └── ... (6 files)
└── material_workflow_ext/       # 跨领域扩展
    └── MaterialWorkflowServiceExt.kt
```

**核心模式**：
- Route 对象（`object XxxRoute : FredicaApi.Route`）留在 `api/routes/`，保持 `all_routes.kt` 集中注册
- 业务逻辑通过 `handler2` 扩展函数放在领域包的 `route_ext/` 中
- Route 的 `handler()` 变为一行委托：`override suspend fun handler(...) = handler2(...)`

### 12.2 目标结构

```
shared/src/commonMain/kotlin/.../material_category/
├── db/
│   ├── MaterialCategoryRepo.kt          # Repo 接口（从 MaterialCategory.kt 拆出）
│   ├── MaterialCategoryDb.kt            # DB 实现（从 db/MaterialCategoryDb.kt 迁入）
│   ├── SyncPlatformInfoRepo.kt          # 平台源 Repo 接口（新增）
│   ├── SyncPlatformInfoDb.kt            # 平台源 DB 实现（新增）
│   ├── SyncUserConfigRepo.kt            # 用户订阅 Repo 接口（新增）
│   └── SyncUserConfigDb.kt              # 用户订阅 DB 实现（新增）
├── model/
│   ├── MaterialCategory.kt              # 数据模型（从 db/MaterialCategory.kt 拆出）
│   ├── SyncPlatformIdentity.kt          # sealed interface + 子类（新增）
│   ├── SyncPlatformInfoRecord.kt        # 平台源 DB 行模型（新增）
│   ├── SyncUserConfigRecord.kt          # 用户订阅 DB 行模型（新增）
│   └── SyncPlatformInfoSummary.kt       # 列表摘要模型 + SyncUserConfigSummary（新增）
├── service/
│   ├── MaterialCategoryService.kt       # Service Holder（从 db/MaterialCategory.kt 拆出）
│   ├── SyncPlatformInfoService.kt       # 平台源 Service（新增）
│   └── SyncUserConfigService.kt         # 用户订阅 Service（新增）
├── route_ext/
│   ├── MaterialCategorySimpleCreateRouteExt.kt
│   ├── MaterialCategorySimpleDeleteRouteExt.kt
│   ├── MaterialCategoryListRouteExt.kt
│   ├── MaterialCategorySimpleUpdateRouteExt.kt
│   ├── MaterialCategorySyncUpdateRouteExt.kt
│   ├── MaterialCategorySyncDeleteRouteExt.kt
│   ├── MaterialSetCategoriesRouteExt.kt
│   ├── MaterialCategorySyncSubscribeRouteExt.kt             # 新增
│   ├── MaterialCategorySyncUnsubscribeRouteExt.kt           # 新增
│   ├── MaterialCategorySyncPlatformDeleteRouteExt.kt        # 新增
│   ├── MaterialCategorySyncTriggerRouteExt.kt               # 新增
│   └── MaterialCategorySyncUserConfigUpdateRouteExt.kt      # 新增
└── executor/
    └── SyncBilibiliFavoriteExecutor.kt  # Phase A2 新增

shared/src/commonMain/kotlin/.../api/routes/
├── MaterialCategorySimpleCreateRoute.kt    # 重命名，handler 委托到 route_ext
├── MaterialCategorySimpleDeleteRoute.kt    # 重命名，handler 委托到 route_ext
├── MaterialCategoryListRoute.kt            # 保留，handler 委托到 route_ext
├── MaterialCategorySimpleUpdateRoute.kt    # 新增，handler 委托到 route_ext
├── MaterialCategorySyncUpdateRoute.kt      # 新增，handler 委托到 route_ext
├── MaterialCategorySyncDeleteRoute.kt      # 新增，handler 委托到 route_ext
├── MaterialSetCategoriesRoute.kt           # 保留，handler 委托到 route_ext
├── MaterialCategorySyncSubscribeRoute.kt               # 新增
├── MaterialCategorySyncUnsubscribeRoute.kt             # 新增
├── MaterialCategorySyncPlatformDeleteRoute.kt          # 新增
├── MaterialCategorySyncTriggerRoute.kt                 # 新增
└── MaterialCategorySyncUserConfigUpdateRoute.kt        # 新增
```

### 12.3 文件迁移映射

#### 12.3.1 现有文件拆分

**`db/MaterialCategory.kt`**（当前 ~40 行：model + repo interface + service holder）拆为 3 个文件：

| 原内容 | 目标文件 | 说明 |
|--------|---------|------|
| `data class MaterialCategory` | `material_category/model/MaterialCategory.kt` | 添加 §9.1 的新字段 |
| `interface MaterialCategoryRepo` | `material_category/db/MaterialCategoryRepo.kt` | 添加 `userId` 参数 |
| `object MaterialCategoryService` | `material_category/service/MaterialCategoryService.kt` | Holder 模式不变 |

**`db/MaterialCategoryDb.kt`** 整体迁移：

| 原文件 | 目标文件 | 说明 |
|--------|---------|------|
| `db/MaterialCategoryDb.kt` | `material_category/db/MaterialCategoryDb.kt` | 添加 §8.1 迁移逻辑 + userId 参数 |

#### 12.3.2 路由 handler 提取

4 个现有路由从 inline handler 转为 handler2 委托模式（重命名后）：

**转换前**（以 `MaterialCategorySimpleCreateRoute` 为例）：

```kotlin
// api/routes/MaterialCategorySimpleCreateRoute.kt
object MaterialCategorySimpleCreateRoute : FredicaApi.Route(
    name = "MaterialCategorySimpleCreateRoute",
    path = "/api/v1/MaterialCategorySimpleCreateRoute",
    method = FredicaApi.HttpMethod.POST,
    minRole = AuthRole.TENANT,
) {
    override suspend fun handler(param: String): ValidJsonString {
        // 30+ 行业务逻辑
    }
}
```

**转换后**：

```kotlin
// api/routes/MaterialCategorySimpleCreateRoute.kt — 瘦壳
object MaterialCategorySimpleCreateRoute : FredicaApi.Route(
    name = "MaterialCategorySimpleCreateRoute",
    path = "/api/v1/MaterialCategorySimpleCreateRoute",
    method = FredicaApi.HttpMethod.POST,
    minRole = AuthRole.TENANT,
) {
    override suspend fun handler(param: String): ValidJsonString = handler2(param)
}
```

```kotlin
// material_category/route_ext/MaterialCategorySimpleCreateRouteExt.kt — 业务逻辑
package com.github.project_fredica.material_category.route_ext

import com.github.project_fredica.api.routes.MaterialCategorySimpleCreateRoute

@Suppress("UnusedReceiverParameter")
suspend fun MaterialCategorySimpleCreateRoute.handler2(param: String): ValidJsonString {
    val user = RouteAuthContext.currentUser()
        ?: return buildJsonObject { put("error", "需要登录") }.toValidJson()
    // 业务逻辑...
}
```

**4 个路由的迁移对照**：

| Route 对象 | handler2 文件 |
|-----------|--------------|
| `MaterialCategorySimpleCreateRoute` | `route_ext/MaterialCategorySimpleCreateRouteExt.kt` |
| `MaterialCategorySimpleDeleteRoute` | `route_ext/MaterialCategorySimpleDeleteRouteExt.kt` |
| `MaterialCategoryListRoute` | `route_ext/MaterialCategoryListRouteExt.kt` |
| `MaterialSetCategoriesRoute` | `route_ext/MaterialSetCategoriesRouteExt.kt` |

#### 12.3.3 新增文件

| 文件 | 所属 Phase | 说明 |
|------|-----------|------|
| `model/SyncPlatformIdentity.kt` | A1 | sealed interface + 5 个子类（§3.3.2） |
| `model/SyncPlatformInfoRecord.kt` | A1 | 平台源 DB 行模型（§9.2） |
| `model/SyncUserConfigRecord.kt` | A1 | 用户订阅 DB 行模型（§9.2b） |
| `model/SyncPlatformInfoSummary.kt` | A1 | 列表摘要 + SyncUserConfigSummary（§9.3） |
| `db/SyncPlatformInfoRepo.kt` | A1 | 平台源 Repo 接口 |
| `db/SyncPlatformInfoDb.kt` | A1 | 平台源 DB 实现 |
| `db/SyncUserConfigRepo.kt` | A1 | 用户订阅 Repo 接口 |
| `db/SyncUserConfigDb.kt` | A1 | 用户订阅 DB 实现 |
| `service/SyncPlatformInfoService.kt` | A1 | 平台源 Service Holder |
| `service/SyncUserConfigService.kt` | A1 | 用户订阅 Service Holder |
| `route_ext/MaterialCategorySimpleUpdateRouteExt.kt` | A1 | 更新简易分类 handler |
| `route_ext/MaterialCategorySyncUpdateRouteExt.kt` | A1 | 更新同步分类 handler |
| `route_ext/MaterialCategorySyncDeleteRouteExt.kt` | A1 | 删除同步分类 handler（级联清理） |
| `route_ext/MaterialCategorySyncSubscribeRouteExt.kt` | A1 | 订阅路由 handler |
| `route_ext/MaterialCategorySyncUnsubscribeRouteExt.kt` | A1 | 退订路由 handler |
| `route_ext/MaterialCategorySyncPlatformDeleteRouteExt.kt` | A1 | 删除平台源路由 handler |
| `route_ext/MaterialCategorySyncTriggerRouteExt.kt` | A1 | 手动触发同步路由 handler |
| `route_ext/MaterialCategorySyncUserConfigUpdateRouteExt.kt` | A1 | 更新订阅设置路由 handler |
| `executor/SyncBilibiliFavoriteExecutor.kt` | A2 | 收藏夹同步执行器 |
| `executor/SyncBilibiliUploaderExecutor.kt` | A3 | UP 主同步执行器 |
| `executor/SyncBilibiliSeasonExecutor.kt` | A3 | 合集同步执行器 |
| `executor/SyncBilibiliSeriesExecutor.kt` | A3 | 列表同步执行器 |
| `executor/SyncBilibiliVideoPagesExecutor.kt` | A3 | 多 P 同步执行器 |

### 12.4 包名与 import 变更

迁移后的包名：

```
com.github.project_fredica.material_category.model
com.github.project_fredica.material_category.db
com.github.project_fredica.material_category.service
com.github.project_fredica.material_category.route_ext
com.github.project_fredica.material_category.executor
```

**需要更新 import 的位置**：

| 引用方 | 原 import | 新 import |
|--------|----------|----------|
| `api/routes/MaterialCategory*Route.kt` | `...db.MaterialCategory` | `...material_category.model.MaterialCategory` |
| `api/routes/MaterialCategory*Route.kt` | `...db.MaterialCategoryService` | `...material_category.service.MaterialCategoryService` |
| `api/routes/all_routes.kt` | 无变化 | 无变化（Route 对象仍在 `api/routes/`） |
| `FredicaApi.jvm.kt` | `...db.MaterialCategoryDb` | `...material_category.db.MaterialCategoryDb` |
| `FredicaApi.jvm.kt` | `...db.MaterialCategoryService` | `...material_category.service.MaterialCategoryService` |
| `jsons.kt` | — | 新增 `...material_category.model.SyncPlatformIdentity` |
| 前端 | 无变化 | 无变化（前端不感知 Kotlin 包结构） |

### 12.5 测试文件位置

迁移后测试文件从 `jvmTest/.../db/` 移至 `jvmTest/.../material_category/`：

```
shared/src/jvmTest/kotlin/.../material_category/
├── MaterialCategoryDbTest.kt                 # §11.1
├── MaterialCategoryMigrationTest.kt          # §11.2
├── SyncPlatformInfoDbTest.kt                 # §11.3a
├── SyncUserConfigDbTest.kt                   # §11.3b
├── MaterialCategoryRouteTest.kt              # §11.4（路由权限测试）
├── MaterialCategorySyncRouteTest.kt            # §11.5（同步源路由测试）
└── SyncPlatformIdentitySerializationTest.kt  # §11.6
```

### 12.6 迁移执行顺序

包结构重组应在 Phase A1 的**第一步**执行，先搬迁现有代码再添加新功能：

1. **创建 `material_category/` 目录结构**：`db/`、`model/`、`service/`、`route_ext/`
2. **拆分 `db/MaterialCategory.kt`** → 3 个文件（model、repo、service），更新包名
3. **迁移 `db/MaterialCategoryDb.kt`** → `material_category/db/`，更新包名
4. **提取 4 个路由 handler** → `route_ext/` 的 handler2 扩展函数，Route 对象改为委托
5. **更新所有 import**：`FredicaApi.jvm.kt`、Route 文件、测试文件
6. **编译验证**：`./gradlew :shared:build`
7. **运行现有测试**：确保无回归
8. **删除旧文件**：`db/MaterialCategory.kt`、`db/MaterialCategoryDb.kt`（已迁移）

> 步骤 1-5 是纯重构（无行为变更），可作为独立 commit：`refactor(material_category): 迁移到领域包结构`。
> 步骤 6-8 验证后，再开始 Phase A1 的功能开发（添加 owner_id、同步源等）。
