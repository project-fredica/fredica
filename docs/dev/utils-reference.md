---
title: 工具函数参考
order: 5
---

# 工具函数参考

> 面向 AI 辅助开发场景，帮助快速定位可复用的工具函数。

---

## 前端工具函数（TypeScript）

所有工具文件统一位于 `fredica-webui/app/util/`，
应用配置 Context 位于 `fredica-webui/app/context/`。

| 文件 | 职责 |
|------|------|
| `util/utils.ts` | Tailwind CSS 类名合并（`cn`）|
| `util/app_fetch.ts` | 应用 API 请求 Hook（`useAppFetch`、`useImageProxyUrl`、`parseJsonBody`）|
| `util/bilibili.ts` | B 站纯格式化函数（时长、计数、日期）；分页窗口算法 |
| `context/appConfig.tsx` | 应用配置 Context（后端连接参数，持久化到 localStorage）|

---

## 共享层工具函数（Kotlin）

所有文件位于 `shared/src/commonMain/kotlin/com/github/project_fredica/apputil/`，
JVM 平台特定实现位于 `shared/src/jvmMain/kotlin/com/github/project_fredica/apputil/`。

| 文件 | 职责 |
|------|------|
| `commonUtil.kt` | `Result.wrap` / `wrapAsync` 异常包装；`Double.toFixed` |
| `jsons.kt` | JSON 序列化/反序列化；构建 JSON 的 DSL（`createJson`）|
| `logger.kt` | 多平台日志门面（`createLogger`）|
| `FileSize.kt` | 字节数转人类可读文件大小 |
| `AppUtil.kt` | 应用路径（`Paths`）、全局 HTTP 客户端（`GlobalVars`）、时间工具 |
| `S3File.kt` | S3 兼容存储文件操作 |
| `Platform.kt` | 运行平台检测 |
| `stringUtil.kt` | 命名格式枚举定义（`CaseFormat`）；`convertCase` expect 声明 |
| `stringUtil.jvm.kt` | `convertCase` JVM actual 实现（委托 Guava）|

**JVM 平台专属（`jvmMain/`）**

| 文件 | 职责 |
|------|------|
| `python/PythonUtil.kt` | Python 辅助服务 HTTP 客户端；`Py314Embed.PyUtilServer.requestText(method, path, body?)` |
