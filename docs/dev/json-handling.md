---
title: 优雅的 JSON 处理指南
order: 7
---

# 优雅的 JSON 处理指南

本文档介绍 Fredica 各层（Kotlin / Python / 前端）的 JSON 工具与使用约定，目标是：

- 消除字符串拼接 JSON 的注入风险
- 统一序列化 / 反序列化入口，避免散落的 `Json {}` 实例
- 让类型系统尽可能早地捕获结构错误

---

## 1. Kotlin 层（`shared/src/commonMain/.../apputil/jsons.kt`）

### 1.1 构建 JSON 对象 — `buildValidJson`

最常用的构建方式，直接返回 `ValidJsonString`，**字符串值自动转义**，无 JSON 注入风险：

```kotlin
// ❌ 危险：$id 若含引号或反斜杠会破坏 JSON 结构
return ValidJsonString("""{"error":"not_found","id":"$id"}""")

// ✅ 安全
return buildValidJson {
    kv("error", "not_found")
    kv("id", id)
}
```

`kv` 支持 `String?` / `Boolean?` / `Number?` / `JsonObject?` / `JsonArray?` / `JsonElement?` / `ValidJsonString?`，`kNull` 显式写入 JSON null。

嵌套 `@Serializable` 子对象时，先转成 `JsonElement` 再传给 `kv`：

```kotlin
return buildValidJson {
    kv("pipeline", AppUtil.GlobalVars.json.encodeToJsonElement(pipeline))
    kv("tasks",    AppUtil.GlobalVars.json.encodeToJsonElement(tasks))
}
```

需要拿到 `JsonObject`（而非直接序列化为字符串）时，用 `createJson`：

```kotlin
val body: JsonObject = createJson {
    obj {
        kv("page", 1)
        kv("size", 20)
    }
}
```

### 1.2 序列化 — `dumpJsonStr`

优先使用扩展函数，**不要直接调 `AppUtil.GlobalVars.json.encodeToString(...)`**：

```kotlin
// JsonElement → ValidJsonString
val vjs: ValidJsonString = myJsonElement.dumpJsonStr().getOrThrow()

// 任意 @Serializable 对象 → ValidJsonString（通过 AppUtil 接收者限制命名空间）
val vjs: ValidJsonString = AppUtil.dumpJsonStr(myObj).getOrThrow()

// 美化格式（调试 / 日志输出）
val pretty: ValidJsonString = myJsonElement.dumpJsonStr(pretty = true).getOrThrow()
```

两者都返回 `Result<ValidJsonString>`，用 `.getOrThrow()` 或 `.getOrElse { }` 处理。

### 1.3 反序列化 — `loadJson` / `loadJsonModel`

优先使用扩展函数，**不要直接调 `AppUtil.GlobalVars.json.decodeFromString(...)`**：

```kotlin
// 结构未知时 → JsonElement 树
val elem: Result<JsonElement> = jsonStr.loadJson()

// 已知类型（M 必须 @Serializable）→ 数据模型
val result: Result<MyData> = jsonStr.loadJsonModel<MyData>()
```

### 1.4 提取字段 — `asT`

从 `JsonElement` 中类型安全地提取具体类型，包装在 `Result` 中：

```kotlin
val name: Result<String>     = jsonObj["name"].asT<String>()
val data: Result<JsonObject> = jsonObj["data"].asT<JsonObject>()
val arr:  Result<JsonArray>  = jsonObj["items"].asT<JsonArray>()
val opt:  Result<String?>    = jsonObj["opt"].asT<String?>()   // 可空
```

### 1.5 局部修改 — `mapOneKey` / `mapKey`

对 `JsonObject` 中的单个键做不可变变换，返回新对象，原对象不变：

```kotlin
val updated = original.mapOneKey("status") { _ -> JsonPrimitive("done") }
// 返回 null 则删除该键
val removed = original.mapOneKey("tmp") { null }
```

对可变 Map 做原地变换：

```kotlin
val m = original.toMutableMap()
m.mapKey("count") { old -> JsonPrimitive((old as? JsonPrimitive)?.int?.plus(1) ?: 0) }
```

### 1.6 `ValidJsonString` 值类

`ValidJsonString` 是 `@JvmInline value class`，持有"已知合法"的 JSON 字符串，`toString()` 直接返回 JSON 内容，可安全嵌入 HTTP body 或 WebSocket 消息：

```kotlin
val vjs = buildValidJson { kv("ok", true) }
println(vjs)          // {"ok":true}
println(vjs.str)      // {"ok":true}
val elem = vjs.toJsonElement()  // 转回 JsonElement 树
```

### 1.7 全局实例（仅在无扩展函数可用时使用）

```kotlin
import com.github.project_fredica.apputil.json       // 必须显式 import
import com.github.project_fredica.apputil.jsonPretty

// 仅在需要直接操作 Json 实例时使用（如传给第三方库）
AppUtil.GlobalVars.json
AppUtil.GlobalVars.jsonPretty
```

> 注意：`json` / `jsonPretty` 是扩展属性，**必须显式 import**，否则编译器找不到。

---

## 2. Python 层（FastAPI 服务）

### 2.1 工具函数返回 TypedDict

工具函数（非路由）的返回值用 `TypedDict` 标注，并在 docstring 中说明字段含义：

```python
from typing import TypedDict

class MirrorCheckResult(TypedDict):
    """
    镜像可用性探测结果。
    available: 是否探测到目标 variant 的 wheel 文件
    url:       实际请求的 URL（用于调试）
    error:     失败原因，成功时为空字符串
    """
    available: bool
    url: str
    error: str


def check_mirror_availability(variant: str, mirror_key: str, proxy: str = "") -> MirrorCheckResult:
    """探测镜像站是否支持指定 variant，不抛异常，失败时 error 字段非空。"""
    try:
        ...
        return MirrorCheckResult(available=True, url=url, error="")
    except Exception as e:
        return MirrorCheckResult(available=False, url=url, error=str(e))
```

### 2.2 路由层返回 JSONResponse

```python
from starlette.responses import JSONResponse

@_router.get("/mirror-check/")
async def mirror_check(request: Request):
    try:
        result: MirrorCheckResult = check_mirror_availability(variant, mirror_key, proxy)
        return JSONResponse(content=result)
    except Exception as e:
        logger.exception("[torch] mirror-check failed")
        return JSONResponse(status_code=500, content={"error": str(e)})
```

### 2.3 dataclass 序列化

对于更复杂的结构，使用 `@dataclass` + `to_dict()`：

```python
from dataclasses import dataclass, asdict

@dataclass
class TorchRecommendation:
    variant: str
    reason: str

    def to_dict(self) -> dict:
        return asdict(self)

return JSONResponse(content=recommendation.to_dict())
```

---

## 3. 前端层（TypeScript / React）

### 3.1 Bridge 返回值解析

`callBridge` 返回 `string`，用 `JSON.parse` 解析后立即做类型断言：

```ts
const raw = await callBridge("get_torch_info", "{}");
const res = JSON.parse(raw) as { variant: string; error?: string };
if (res.error) { print_error({ reason: res.error }); return; }
```

### 3.2 API 响应解析

`apiFetch` 返回 `Response`，用 `parseJsonBody` 解析：

```ts
import { parseJsonBody } from "~/util/app_fetch";

const { resp } = await apiFetch("/api/v1/SomeRoute", { method: "POST", body: JSON.stringify(body) });
if (!resp.ok) { reportHttpError("操作失败", resp); return; }
const data = await parseJsonBody<{ id: string }>(resp);
```

### 3.3 序列化请求体

直接用 `JSON.stringify`：

```ts
body: JSON.stringify({ variant, download_dir: downloadDir })
```

---

## 4. 速查表

| 场景 | Kotlin | Python | 前端 |
|------|--------|--------|------|
| 构建 JSON 对象 | `buildValidJson { kv(...) }` | `TypedDict` 实例 / `dataclass.to_dict()` | `JSON.stringify({...})` |
| 序列化对象 | `AppUtil.dumpJsonStr(obj)` | `JSONResponse(content=obj)` | `JSON.stringify(obj)` |
| 序列化 JsonElement | `elem.dumpJsonStr()` | — | — |
| 反序列化为树 | `str.loadJson()` | `await request.json()` | `JSON.parse(raw)` |
| 反序列化为模型 | `str.loadJsonModel<T>()` | `TypedDict` / `dataclass` | `JSON.parse(raw) as T` |
| 提取字段 | `jsonObj["key"].asT<String>()` | `data.get("key", "")` | `res.key ?? ""` |
| 局部修改对象 | `obj.mapOneKey("k") { ... }` | dict spread / copy | `{ ...obj, key: newVal }` |
| 直接操作 Json 实例 | `AppUtil.GlobalVars.json`（需显式 import） | — | — |
