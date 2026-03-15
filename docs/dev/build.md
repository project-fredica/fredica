---
title: 构建指南
order: 50
---

# 构建指南

## 模块构建命令

### 运行桌面应用（开发模式）

```shell
./gradlew :composeApp:run
```

启动 Compose Desktop 应用，内嵌 WebView 加载前端页面。这是日常开发的主要入口。
应用启动时会同步启动 Ktor API 服务器（`:7631`）和 Python 辅助服务（`:7632`）。

### 构建共享模块

```shell
./gradlew :shared:build
```

构建 `shared` 模块，会重新生成 JSON Schema 相关的代码。修改了 API 数据模型后需要重新执行此命令。

```shell
# 附加 --info 查看详细构建日志
./gradlew :shared:build --info
```

### 构建 WebUI 前端

WebUI 是独立的 Node 工程，位于 `fredica-webui/` 目录，有自己的 `package.json`。

```shell
cd fredica-webui

# 首次或依赖变更后安装依赖
npm install

# 启动开发服务器（热更新，默认 :7630）
npm run dev

# 构建生产产物（输出到 fredica-webui/build/）
npm run build
```

### 构建文档站（VitePress）

文档站使用根目录的 `package.json`（与 WebUI 前端工程**相互独立**）。

```shell
# 在项目根目录执行

# 安装 VitePress 等文档依赖
npm install

# 本地预览文档
npm run docs:dev

# 构建文档产物
npm run docs:build
```

### 构建桌面发行包

```shell
# Windows 安装包（.msi 或 .exe）
./gradlew :composeApp:packageMsi
./gradlew :composeApp:packageExe

# macOS DMG
./gradlew :composeApp:packageDmg

# Linux DEB / RPM
./gradlew :composeApp:packageDeb
./gradlew :composeApp:packageRpm
```

::: tip
打包命令需要在目标平台上运行。例如，构建 `.msi` 需要在 Windows 上执行。
:::

### 构建独立 Server JAR（实验性）

```shell
./gradlew :server:buildFatJar
```

生成包含所有依赖的可独立运行 JAR 文件。

---

## 运行测试

详见 [测试指南](./testing)。

---

## 关键依赖版本

| 依赖 | 版本 |
|------|------|
| Kotlin | `2.3.0` |
| Compose Multiplatform | `1.9.3` |
| Ktor | `3.3.3` |
| Ktorm | `4.1.1` |
| Kotlinx Serialization | `1.9.0` |
| Kotlinx Coroutines | `1.10.2` |
| Kotlinx DateTime | `0.7.1` |
| React | `19.2.3` |
| React Router | `7.11.0` |
| Tailwind CSS | `4.1.13` |
| Vite / TypeScript | 最新稳定版 |

完整依赖版本定义在 `gradle/libs.versions.toml`。

---

## 项目结构速览

```
fredica/
├── composeApp/              # Compose Multiplatform 桌面应用
│   └── src/
│       ├── commonMain/      # 跨平台 UI 代码
│       ├── jvmMain/         # JVM 平台特定代码
│       └── androidMain/     # Android 特定代码
├── shared/                  # 跨平台核心业务逻辑
│   └── src/
│       ├── commonMain/      # 共享代码（API、DB 模型、Worker 引擎、工具）
│       ├── jvmMain/         # JVM 实现（Ktor 启动、Python 客户端、JVM Executor）
│       └── jvmTest/         # JVM 单元测试
├── fredica-webui/           # React 前端（独立 Node 工程）
│   ├── app/routes/          # 页面路由文件
│   └── package.json         # WebUI 专属 Node 依赖
├── desktop_assets/          # 随安装包分发的平台资产
│   ├── common/
│   │   └── fredica-pyutil/  # Python 辅助服务源码（FastAPI + faster-whisper）
│   └── windows/lfs/         # Windows LFS 大文件（Python 嵌入式运行时等）
├── server/                  # 独立服务器模块（实验性）
├── docs/                    # VitePress 文档站（独立 Node 工程）
├── build.gradle.kts         # 根构建脚本
├── settings.gradle.kts      # 模块注册
├── gradle/
│   └── libs.versions.toml   # 统一版本管理
└── package.json             # 根 Node 脚本（仅文档站 docs:dev / docs:build）
```

---

## CI/CD <Badge type="warning" text="尚待开发" />

::: warning 尚待开发
持续集成和自动发布流程尚未配置。以下为规划内容。
:::

计划使用 GitHub Actions 实现：
- PR 提交时自动运行 Kotlin 单元测试（`./gradlew :shared:jvmTest`）
- Tag 推送时自动构建各平台安装包并发布到 GitHub Releases
- 文档推送时自动部署到 GitHub Pages
