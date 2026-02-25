---
title: 功能规格
order: 2
---

# 功能规格

本文档列出 Fredica 所有功能模块的详细规格说明。

## 功能完成状态总览

| 功能模块 | 功能点 | 状态 |
|--------|--------|------|
| **素材管理** | 素材库列表 | ✅ 已完成 |
| | 素材分类（CRUD） | ✅ 已完成 |
| | 素材删除 | ✅ 已完成 |
| | 视频-分类多对多关联 | ✅ 已完成 |
| **B 站导入** | 收藏夹导入 | ✅ 已完成 |
| | 合集导入 | ⚠️ UI 已有，后端待开发 |
| | 多 P 视频导入 | ⚠️ UI 已有，后端待开发 |
| | UP 主视频导入 | ⚠️ UI 已有，后端待开发 |
| **本地导入** | 本地文件夹扫描 | ❌ 尚待开发 |
| **其他平台** | YouTube 导入 | ❌ 尚待开发 |
| **图片代理** | 封面图本地缓存代理 | ✅ 已完成 |
| **视频下载** | B 站视频本地下载 | ❌ 尚待开发 |
| **AI 处理** | 语音识别（ASR） | ❌ 尚待开发 |
| | 文字识别（OCR） | ❌ 尚待开发 |
| | 人声分离 | ❌ 尚待开发 |
| | 音频降噪 | ❌ 尚待开发 |
| **文稿生成** | AI 脚本草稿生成 | ❌ 尚待开发 |
| | 文稿编辑器 | ❌ 尚待开发 |
| **视频制作** | 自动剪辑 | ❌ 尚待开发 |
| | DaVinci Resolve 导出 | ❌ 尚待开发 |
| **辅助功能** | 字幕翻译 | ❌ 尚待开发 |
| | 文本转语音 | ❌ 尚待开发 |
| | 模板预设 | ❌ 尚待开发 |
| **协作** | 多人协作编辑 | ❌ 尚待开发 |
| | 项目文件共享 | ❌ 尚待开发 |
| **设置** | 桌面应用设置 | ✅ 已完成 |
| | 代理配置 | ✅ 已完成（UI 层） |
| **认证** | Bearer Token 认证 | ✅ 已完成 |

## 素材管理规格

### 素材视频数据模型

```typescript
interface MaterialVideo {
  id: string              // "bilibili_bvid__BV1NK4y1V7M5__P1"
  source_type: string     // "bilibili" | "youtube" | "local"
  source_id: string       // 平台原始 ID（如 B 站 BV 号）
  title?: string
  cover_url?: string
  description?: string
  duration?: number       // 秒
  pipeline_status?: string // "pending" | "downloaded" | "transcribed" | "analyzed"
  local_video_path?: string
  local_audio_path?: string
  transcript_path?: string
  extra?: string          // JSON 字符串，存储平台特有元数据
  created_at?: number     // Unix 时间戳（秒）
  updated_at?: number
}
```

### 素材分类数据模型

```typescript
interface MaterialCategory {
  id: string
  name: string          // 唯一
  description?: string
  created_at?: number
  updated_at?: number
  video_count?: number  // 关联视频数量（查询时附带）
}
```

### 处理流水线状态机

```
pending ──→ downloaded ──→ transcribed ──→ analyzed
```

- `pending`：仅有元数据，视频未下载
- `downloaded`：视频文件已保存到 `local_video_path`
- `transcribed`：字幕文件已生成到 `transcript_path`
- `analyzed`：AI 分析完成，内容已结构化

## B 站导入规格

### 收藏夹导入流程

```
输入收藏夹 ID
    ↓
调用 B 站 API 获取视频列表（支持分页）
    ↓
用户选择要导入的视频
    ↓
POST /api/v1/MaterialImportRoute
    ↓
写入 material_video 表（去重：同 ID 不重复插入）
```

### B 站视频 ID 规则

Fredica 使用确定性 ID 标识 B 站视频，格式为：

```
bilibili_bvid__{BV号}__{分P编号}
```

例如：`bilibili_bvid__BV1NK4y1V7M5__P1`

多 P 视频的每个分P独立存储为一条素材记录。

## 图片代理规格

所有从外部平台获取的封面图都通过本地代理访问，避免防盗链问题并实现持久缓存。

- 请求路径：`GET /api/v1/ImageProxyRoute?url={encodeURIComponent(imageUrl)}`
- 缓存策略：`Cache-Control: public, max-age=31536000, immutable`（永久缓存，内容不变）
- 缓存 Key：URL 的 SHA-256 哈希值
- 缓存存储：`.data/cache/images/`
- 无需认证

## 认证规格

除图片代理接口外，所有 API 请求都需要携带认证头：

```
Authorization: Bearer {token}
```

Token 在桌面应用设置中生成和管理。
