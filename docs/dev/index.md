---
title: 开发文档
---

# 开发文档

本文档面向参与 Fredica 开发的工程师，涵盖环境搭建、项目架构、API 参考和构建指南。

## 文档导航

| 文档 | 说明 |
|------|------|
| [开发环境搭建](./setup) | 如何配置本地开发环境并运行项目 |
| [项目架构](./architecture) | 模块划分、技术选型与系统设计 |
| [构建指南](./build) | 各模块构建命令、运行测试与产物说明 |

## 架构计划

| 计划 | 说明 |
|------|------|
| [去中心化任务管理架构](./plans/decentralized-task-management) | 多设备协同处理素材的分布式任务调度方案 |

## 技术栈速览

**后端（Kotlin）**
- Kotlin `2.3.0` + Kotlin Multiplatform
- Ktor Server `3.3.3`（Netty 引擎）
- Ktorm ORM `4.1.1`（SQLite）
- Kotlinx Serialization / Coroutines / DateTime

**Python 辅助服务**
- FastAPI + uvicorn（HTTP 服务）
- faster-whisper（本地语音转写）
- bilibili-api-python（B 站数据）
- 以子进程方式随 composeApp 启动，嵌入于安装包，无需用户安装

**前端（TypeScript）**
- React `19` + React Router `7`（文件系统路由）
- Tailwind CSS `4`
- Vite 构建

**桌面宿主**
- Compose Multiplatform `1.9.3`
- 支持 JVM Desktop（Windows / macOS / Linux）
