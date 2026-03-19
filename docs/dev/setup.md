---
title: 开发环境搭建
order: 10
---

# 开发环境搭建

## 前置依赖

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17 | **必须使用不含 JCEF 的标准 JDK 17**（推荐 Microsoft OpenJDK 17 或 Eclipse Temurin 17）。**不要使用 JetBrains Runtime（JBR）**：JBR 内置 `jcef.dll` / `libcef.dll`，与 KCEF 下载的 CEF bundle 版本冲突，会导致打包后 GPU 进程崩溃（`error_code=63`）。 |
| Node.js | 18 或更高 | WebUI 前端与文档站均需要 |
| Git | 任意版本 | |

::: tip 推荐工具
后端开发推荐使用 **IntelliJ IDEA**（社区版即可，内置 JDK 管理）。前端开发推荐使用 **VS Code** 或 **WebStorm**。
:::

::: info Python 服务无需手动安装
Python 辅助服务（`fredica-pyutil`）的运行时和依赖已嵌入安装包，随 `composeApp` 启动时自动作为子进程启动，**开发者无需手动安装 Python 或任何 Python 依赖**。
:::

## 克隆项目

```shell
git clone https://github.com/project-fredica/fredica.git
cd fredica
```

## 启动桌面应用（主入口）

Fredica 的主界面是 Compose Desktop 应用，内嵌 WebView 展示前端页面。

```shell
./gradlew :composeApp:run
```

应用启动后会：
1. 在 `7631` 端口启动 Ktor API 服务器
2. 在 `7632` 端口启动 Python 辅助服务（子进程，仅 Windows 完整支持）
3. 打开 Compose Desktop 窗口，内嵌 WebView 加载前端页面

> 开发模式下前端页面由独立的 Vite 开发服务器（`:7630`）提供，需另行启动（见下方《独立开发 WebUI 前端》）。

## 分模块开发

### 独立开发 WebUI 前端

`fredica-webui/` 是一个独立的 Node 工程，需在该目录下单独安装依赖并启动。

```shell
cd fredica-webui

# 首次或依赖变更后安装依赖
npm install

# 启动前端开发服务器（热更新）
npm run dev
```

前端开发服务器默认运行在 `http://localhost:7630`。

开发前端时，需要同时运行后端（桌面应用），并在 Web UI 连接配置页面填写：

```
Schema:  http
Domain:  localhost
Port:    7631
Token:   (从桌面应用设置中获取)
```

::: warning 注意：两个独立的 Node 工程
项目根目录的 `package.json` 仅包含 **VitePress 文档站**（`docs:dev` / `docs:build`）的脚本，与 WebUI 前端**互相独立**。在根目录运行 `npm install` 只安装文档依赖，**不会**安装 WebUI 依赖。
:::

### 本地预览文档站

```shell
# 在项目根目录
npm install
npm run docs:dev
```

### 仅启动后端 API 服务器

目前 API 服务器集成在 `composeApp` 模块中，通过桌面应用进程启动：

```shell
./gradlew :composeApp:run
```

### 前端构建产物说明

前端构建后的静态文件嵌入到 JAR 包中，由 Ktor 提供服务。开发模式下两者分离运行，生产模式下合并在同一进程。

## 构建共享模块

`shared` 模块包含跨平台的核心业务逻辑，修改后需要重新构建以更新生成的代码（如 JSON Schema）：

```shell
./gradlew :shared:build
```

## 运行测试

详细说明见 [测试指南](./testing)。

## 环境变量与配置

开发时无需额外环境变量。以下为本地配置文件说明：

| 文件 | 说明 |
|------|------|
| `local.properties` | 本地构建配置（SDK 路径等），不提交到 Git |

## 常见问题

::: details Gradle 构建卡住或报网络错误
可能是下载 Gradle Wrapper 或依赖时网络超时。配置系统代理或设置 Gradle 镜像源：

在 `~/.gradle/gradle.properties` 中添加：
```properties
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```
:::

::: details 前端页面空白
检查：
1. 后端是否正在运行（`http://localhost:7631/api/v1/ping` 是否返回正常响应）
2. Web UI 的连接配置（Schema / Domain / Port / Token）是否正确
3. 浏览器控制台是否有 CORS 或 401 错误
:::

::: details WebUI npm install 无效 / 找不到依赖
确认是在 `fredica-webui/` 目录下执行，而非项目根目录。根目录的 `npm install` 只安装 VitePress 文档依赖。
:::

::: details 打包后应用启动即崩溃（KCEF GPU 进程 error_code=63）
原因：使用了 JetBrains Runtime（JBR）作为构建 JDK。JBR 内置 `jcef.dll` / `libcef.dll`，打包时会被复制进 `runtime/bin/`，与 KCEF bundle 里的 `libcef.dll` 版本冲突，GPU 子进程启动时加载错误的库导致崩溃。

解决：在 `gradle.properties` 中将 `org.gradle.java.home` 指向**不含 JCEF 的标准 JDK 17**（如 Microsoft OpenJDK 或 Eclipse Temurin），重新打包即可。

```properties
org.gradle.java.home=C\:/path/to/ms-17.0.17
```

验证方法：打包后检查 `<安装目录>/runtime/bin/` 下是否存在 `jcef.dll`，若不存在则说明使用了正确的 JDK。
:::
