---
title: 前后端通信通道
order: 30
---

# 前后端通信通道

Fredica 前端与后端之间存在两条独立的通信通道：**Route API**（HTTP）和 **kmpJsBridge**（进程内 postMessage）。两者的传输方式、鉴权模型和适用场景均不同，开发时需根据场景选择正确的通道。

## 两种通道对比

| | Route API | kmpJsBridge |
|---|---|---|
| 传输方式 | HTTP（localhost:7631） | 进程内 postMessage |
| 鉴权方式 | Bearer Token | 进程内信任，无需 Token |
| 可用环境 | WebView + 普通浏览器 | 仅 WebView |
| 响应方式 | HTTP 响应体（JSON） | 异步回调字符串（JSON） |
| 典型用途 | 所有业务 CRUD API | 敏感操作（凭据检测）、需绕过跨域的原生能力、Token 初始化前的配置读取 |

两种通道能够区分部署者与外部访问者，是因为 `kmpJsBridge` 仅在 `composeApp` 托管的 WebView 进程内可用——外部浏览器根本无法访问 Bridge，只能走 HTTP 路由。因此只需对 HTTP 路由做 Token 鉴权，即可在不影响部署者体验的前提下，将外部访问者隔离在授权边界之外。

## Route API — HTTP 路由（Bearer Token）

前端通过 `useAppFetch` / `apiFetch` 发起的所有 HTTP 请求，均在 `Authorization` 头携带 Bearer Token：

```
Authorization: Bearer <token>
```

服务端 `checkAuth()`（`FredicaApi.jvm.kt`）验证此 Token，失败时直接响应 401。Token 由用户在设置页配置，持久化到 `localStorage`，WebView 环境下通过 `get_server_info` Bridge 注入。

路由是否需要鉴权由 `FredicaApi.Route.requiresAuth` 控制（默认 `true`）；图片代理等公开接口可设为 `false`。

**Kotlin 路由实现**（`shared/src/commonMain/.../api/routes/`）：

```kotlin
object WebenSourceListRoute : FredicaApi.Route {
    override val mode = FredicaApi.Route.Mode.Get
    override val desc = "来源库列表（可按 material_id 过滤，分页）"

    override suspend fun handler(param: String): ValidJsonString {
        // GET 路由：param 是 Map<String, List<String>> 的 JSON
        val query = param.loadJsonModel<Map<String, List<String>>>().getOrThrow()
        val materialId = query["material_id"]?.firstOrNull()
        val limit = query["limit"]?.firstOrNull()?.toIntOrNull() ?: 20
        val offset = query["offset"]?.firstOrNull()?.toIntOrNull() ?: 0

        val items = WebenSourceService.repo.listPaged(materialId, limit, offset)
        return AppUtil.dumpJsonStr(items).getOrThrow()
    }
}
```

**前端调用**（`fredica-webui/app/`）：

```ts
// useAppFetch 自动附加 Bearer Token 和 host
const appFetch = useAppFetch();

const res = await appFetch("/api/v1/WebenSourceListRoute", {
    method: "GET",
    params: { material_id: materialId, limit: 20, offset: 0 },
});
if (!res.ok) { reportHttpError("加载来源列表失败", res); return; }
const data = await res.json() as PageResult;
```

## kmpJsBridge — JS Bridge（进程内信任）

WebView 内的前端通过 `window.kmpJsBridge.callNative(method, params, callback)` 直接调用 Kotlin 原生方法，**无需 Token**。

安全边界由运行环境保证：Bridge 仅在 `composeApp` 托管的 WebView 内可用，外部浏览器无法访问（`callBridge()` 会抛出 `BridgeUnavailableError`）。

**方法名约定**：Handler 类名去掉 `JsMessageHandler` 后缀，转为 `lower_underscore`。例如 `CheckBilibiliCredentialJsMessageHandler` → 方法名 `check_bilibili_credential`。

**Kotlin Handler 实现**（`composeApp/src/commonMain/.../appwebview/messages/`）：

```kotlin
// 类名决定方法名：CheckBilibiliCredential → "check_bilibili_credential"
class CheckBilibiliCredentialJsMessageHandler : MyJsMessageHandler() {

    override suspend fun handle2(
        message: JsMessage,
        navigator: WebViewNavigator?,
        callback: (String) -> Unit,
    ) {
        val params = message.params.loadJsonModel<CheckParams>().getOrElse { CheckParams() }

        // 敏感操作：凭据检测不通过 HTTP API 暴露，仅允许 Bridge 调用
        try {
            val raw = FredicaApi.PyUtil.post("/bilibili/credential/check", pyBody.str)
            callback(raw)  // 回调字符串会被基类自动转义（\ 和 '）
        } catch (e: Throwable) {
            logger.warn("CheckBilibiliCredential: Python 服务异常", err = e)
            callback(buildValidJson { kv("error", e.message ?: "unknown") }.str)
        }
    }
}
```

基类 `MyJsMessageHandler` 负责：在 `Dispatchers.IO` 上异步执行、统一 try-catch 兜底、对回调字符串中的 `\` 和 `'` 进行转义（kmpJsBridge 将回调包裹在 JS 单引号字面量中，不转义会破坏 JS 语法）。

**前端调用**（`fredica-webui/app/util/bridge.ts`）：

```ts
import { callBridge, callBridgeOrNull } from "~/util/bridge";

// 标准调用：bridge 不可用时抛出 BridgeUnavailableError
const raw = await callBridge(
    "check_bilibili_credential",
    JSON.stringify({ sessdata: "...", bili_jct: "..." }),
);
const result = JSON.parse(raw) as { configured: boolean; valid: boolean; message: string };
if (result.error) { print_error(result.error); return; }

// 静默变体：浏览器开发环境下 bridge 不可用时返回 null，无需 catch
const raw = await callBridgeOrNull("get_server_info");
if (!raw) return;  // 非 WebView 环境，静默跳过
const info = JSON.parse(raw) as ServerInfo;
```

`callBridge` 内置启动期重试（默认最多 5 次，间隔 300ms），处理 WebView 注入后 postMessage 通道尚未就绪的竞态问题。

::: code-group

```ts [bridge.ts]
// fredica-webui/app/util/bridge.ts
<!--@include: ../../fredica-webui/app/util/bridge.ts-->
```

:::
