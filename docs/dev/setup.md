---
title: 开发环境搭建
order: 1
---

# 开发环境搭建

## 前置依赖

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 11 或更高 | 推荐使用 JDK 17 或 21 |
| Node.js | 18 或更高 | 前端开发依赖 |
| Git | 任意版本 | |

::: tip 推荐工具
后端开发推荐使用 **IntelliJ IDEA**（社区版即可）。前端开发推荐使用 **VS Code** 或 **WebStorm**。
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
2. 自动打开内置浏览器，加载 Web UI（`http://localhost:7630`）

## 分模块开发

### 仅启动后端 API 服务器

```shell
./gradlew :composeApp:run
```

> 目前 API 服务器集成在 `composeApp` 模块中，通过桌面应用进程启动。

### 独立开发前端

```shell
# 安装前端依赖
npm install

# 启动前端开发服务器（热更新）
npm run dev
# 或
cd fredica-webui && npm run dev
```

前端开发服务器默认运行在 `http://localhost:7630`。

开发前端时，需要同时运行后端（桌面应用），并在 Web UI 连接配置页面填写：

```
Schema:  http
Domain:  localhost
Port:    7631
Token:   (从桌面应用设置中获取)
```

### 前端构建产物说明

前端构建后的静态文件嵌入到 JAR 包中，由 Ktor 提供服务。开发模式下两者分离运行，生产模式下合并在同一进程。

## 构建共享模块

`shared` 模块包含跨平台的核心业务逻辑，修改后需要重新构建以更新生成的代码（如 JSON Schema）：

```shell
./gradlew :shared:build
```

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
1. 后端是否正在运行（`http://localhost:7631/api/v1/ping` 是否返回 `pong`）
2. Web UI 的连接配置（Schema / Domain / Port / Token）是否正确
3. 浏览器控制台是否有 CORS 或 401 错误
:::
