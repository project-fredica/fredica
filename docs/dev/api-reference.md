---
title: API 参考
order: 3
---

# API 参考

Fredica 后端提供 RESTful HTTP API，默认监听 `http://localhost:7631`。

## 认证

除图片代理接口外，所有请求都需要在 HTTP Header 中携带 Bearer Token：

```http
Authorization: Bearer <your-token>
```

Token 可在桌面应用设置页面中查看。

## 基础信息

- **基础路径**：`/api/v1/`
- **请求格式**：`Content-Type: application/json`
- **响应格式**：`application/json`（图片代理接口除外）

---

## 健康检查

### `GET /api/v1/ping`

检查服务是否正常运行。无需认证。

**响应示例：**

```
pong
```

---

## 素材管理

### `POST /api/v1/MaterialListRoute`

获取素材库中的所有视频（含分类信息）。

**请求体：**

```json
{}
```

**响应示例：**

```json
[
  {
    "id": "bilibili_bvid__BV1NK4y1V7M5__P1",
    "source_type": "bilibili",
    "source_id": "BV1NK4y1V7M5",
    "title": "视频标题",
    "cover_url": "https://...",
    "duration": 1234,
    "pipeline_status": "pending",
    "extra": "{\"upper_name\":\"UP主名\",\"cnt_play\":10000}",
    "created_at": 1700000000,
    "updated_at": 1700000000
  }
]
```

---

### `POST /api/v1/MaterialImportRoute`

将视频元数据导入素材库（相同 ID 的视频不会重复导入）。

**请求体：**

```json
{
  "source_type": "bilibili",
  "source_fid": "收藏夹ID（字符串）",
  "videos": [
    {
      "bvid": "BV1NK4y1V7M5",
      "title": "视频标题",
      "cover": "https://封面图URL",
      "duration": 1234,
      "upper_name": "UP主名",
      "cnt_play": 10000,
      "cnt_collect": 500,
      "desc": "视频简介"
    }
  ],
  "category_ids": ["分类ID1", "分类ID2"]
}
```

> `category_ids` 为可选字段，导入时可直接关联到已有分类。

**响应示例：**

```json
{
  "inserted": 5,
  "total": 5
}
```

---

### `POST /api/v1/MaterialDeleteRoute`

删除一个或多个素材视频。

**请求体：**

```json
{
  "ids": ["bilibili_bvid__BV1NK4y1V7M5__P1"]
}
```

**响应示例：**

```json
{
  "deleted": 1
}
```

---

## 素材分类

### `POST /api/v1/MaterialCategoryListRoute`

获取所有分类及其关联的视频数量。

**请求体：**

```json
{}
```

**响应示例：**

```json
[
  {
    "id": "uuid-1234",
    "name": "学习资料",
    "description": "",
    "video_count": 12,
    "created_at": 1700000000,
    "updated_at": 1700000000
  }
]
```

---

### `POST /api/v1/MaterialCategoryCreateRoute`

创建新分类（同名分类不会重复创建）。

**请求体：**

```json
{
  "name": "学习资料",
  "description": "各类教程视频"
}
```

**响应：** 返回创建的 `MaterialCategory` 对象。

---

### `POST /api/v1/MaterialCategoryDeleteRoute`

删除分类（同时删除与所有视频的关联，但不删除视频）。

**请求体：**

```json
{
  "id": "uuid-1234"
}
```

**响应示例：**

```json
{
  "deleted": true
}
```

---

### `POST /api/v1/MaterialSetCategoriesRoute`

替换指定视频的全部分类关联（先清空再重新写入）。

**请求体：**

```json
{
  "video_id": "bilibili_bvid__BV1NK4y1V7M5__P1",
  "category_ids": ["uuid-cat1", "uuid-cat2"]
}
```

**响应示例：**

```json
{
  "updated": true
}
```

---

## B 站收藏夹

### `POST /api/v1/BilibiliFavoriteGetVideoListRoute`

获取收藏夹中的全部视频（自动翻页聚合）。

**请求体：**

```json
{
  "fid": "123456789"
}
```

**响应：** 返回 B 站 API 格式的视频列表 JSON。

---

### `POST /api/v1/BilibiliFavoriteGetPageRoute`

获取收藏夹指定页的视频列表。

**请求体：**

```json
{
  "fid": "123456789",
  "page": 1
}
```

**响应：** 返回 B 站 API 格式的分页视频列表 JSON。

---

## 工具接口

### `GET /api/v1/ImageProxyRoute`

图片反向代理，缓存外部图片到本地。**无需认证。**

**请求参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `url` | string | 要代理的图片 URL（需 URL 编码） |

**请求示例：**

```
GET /api/v1/ImageProxyRoute?url=https%3A%2F%2Fi0.hdslb.com%2Fbfs%2Farchive%2Fxxx.jpg
```

**响应：** 图片二进制数据，携带适当的 `Content-Type` 和长期缓存头。

---

## 错误响应

| HTTP 状态码 | 说明 |
|-----------|------|
| `401 Unauthorized` | Token 缺失或无效 |
| `400 Bad Request` | 请求体格式错误 |
| `500 Internal Server Error` | 服务器内部错误 |
