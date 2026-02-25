---
title: 快速开始
order: 1
---

# 快速开始

本章节介绍如何安装 Fredica、完成首次配置，并完成第一次素材导入。

## 系统要求

| 项目 | 要求 |
|------|------|
| 操作系统 | Windows 10/11、macOS 12+、Linux（主流发行版） |
| Java | JDK 11 或更高版本 |
| 内存 | 建议 4GB 以上 |
| 磁盘 | 建议 10GB 以上可用空间（用于素材缓存） |

## 启动应用

### 桌面应用（推荐）

下载对应平台的安装包后运行，或从源码构建：

```shell
./gradlew :composeApp:run
```

应用启动后会自动打开内置浏览器，显示 Web 操作界面。

### 服务端口说明

Fredica 运行时会占用以下端口：

| 端口 | 服务 | 说明 |
|------|------|------|
| `7630` | Web UI | React 前端界面 |
| `7631` | API 服务器 | Ktor HTTP 后端 |
| `7632` | AI 处理服务 | Python 模型服务 <Badge type="warning" text="尚待开发" /> |

## 首次配置

应用首次启动时，需要在连接配置页面填写以下信息：

```
Schema:  http
Domain:  localhost
Port:    7631
Token:   your-auth-token
```

::: tip
Token 可在桌面应用的设置页面中查看或重置。
:::

## 第一次导入素材

1. 点击左侧导航栏 **添加资源**
2. 选择 **B 站 → 收藏夹**
3. 粘贴收藏夹 ID（从收藏夹页面 URL 中获取）
4. 勾选要导入的视频，点击 **导入到素材库**
5. 在 **素材库** 中查看已导入的视频

::: info 收藏夹 ID 在哪里找？
打开 B 站收藏夹页面，URL 格式为 `https://space.bilibili.com/{uid}/favlist?fid=XXXXXXXX`，其中 `fid=` 后面的数字即为收藏夹 ID。
:::

## 数据存储位置

Fredica 默认将所有数据保存在应用目录下的 `.data/` 文件夹中：

```
.data/
├── db/
│   └── fredica_app.db    # SQLite 数据库（素材信息、分类、配置）
├── cache/
│   └── images/           # 图片代理缓存
└── log/                  # 运行日志
```

可在设置页面中自定义数据目录路径。

## 下一步

- 了解如何管理 [素材收集](./material-collection)
- 查看 [素材处理](./material-processing) 功能的使用方式
