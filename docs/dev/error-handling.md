---
title: 错误处理指南
order: 6
---

# 错误处理指南

本文档描述 Fredica 各层（Python / Kotlin / 前端）的错误处理约定，目标是：

- 异常不被静默吞掉
- 严重程度与日志级别匹配
- 错误信息能沿调用链传递到前端，通过 Toast 通知用户

---

## 1. Python 层（FastAPI 服务）

### 1.1 日志级别选择

| 场景 | 级别 |
|------|------|
| 预期失败（网络超时、镜像不支持该 variant） | `logger.warning` |
| 不可预期的异常（文件读取失败、内部逻辑错误） | `logger.exception`（自动附加 traceback） |
| 正常流程的关键节点 | `logger.debug` / `logger.info` |

```python
# 预期失败 — warn
except Exception as e:
    logger.warning(f"[torch] fetch failed: {e}")
    return {"variants": [], "error": str(e)}

# 不可预期 — exception（含 traceback）
except Exception:
    logger.exception(f"[torch] check_download: failed to read dist-info for variant={variant}")
    return TorchCheckResult(variant=variant, already_ok=False, ...)
```

### 1.2 错误信息传给前端

路由层的 `except` 块统一返回带 `"error"` 字段的 JSON，**不要直接 raise**：

```python
@_router.get("/mirror-versions/")
async def mirror_versions(request: Request):
    try:
        result = await loop.run_in_executor(None, fetch_mirror_supported_variants, mirror_key, proxy)
        return JSONResponse(content=result)   # result 本身含 "error" 字段
    except Exception as e:
        logger.exception("[torch] mirror-versions failed")
        return JSONResponse(status_code=500, content={"error": str(e)})
```

### 1.3 工具函数的返回约定

工具函数（非路由）**不抛异常**，统一返回含 `"error"` 字段的结构。
**必须用 `TypedDict` 标注返回类型，并在 docstring 中说明每个字段的含义**：

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
        logger.warning(f"[torch] mirror check failed: {e}")
        return MirrorCheckResult(available=False, url=url, error=str(e))
```

---

## 2. Kotlin 层

Kotlin 层分两类入口，错误处理模式略有不同。

### 2.1 JsBridge Handler

#### 参数解析失败

参数解析失败属于调用方问题，用 `logger.warn` 记录，并通过 callback 返回 `{"error": "..."}` 给前端：

```kotlin
val params = try {
    AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
} catch (e: Throwable) {
    logger.warn("[XxxHandler] failed to parse params: ${e.message}")
    null
}
if (params == null) {
    callback(buildValidJson { kv("error", "invalid params") }.str)
    return
}
```

#### Python 调用失败

Python 服务不可达属于运行时异常，用 `logger.warn` 记录（Python 服务本身会打 exception），并将错误透传给前端：

```kotlin
val result = try {
    FredicaApi.PyUtil.get(path)
} catch (e: Throwable) {
    logger.warn("[XxxHandler] Python call failed: ${e.message}")
    buildValidJson { kv("error", e.message ?: "unknown error") }.str
}
callback(result)
```

#### 入参日志

每个 handler 在发起 Python 调用前打一条 `logger.warn`，记录关键入参和路径，便于追踪：

```kotlin
logger.warn("[GetTorchMirrorVersionsJsMessageHandler] mirrorKey=$mirrorKey useProxy=$useProxy path=$path")
```

### 2.2 Route API（Ktor 路由）

Ktor 路由层用 `try/catch` 包裹业务逻辑，异常时返回带 `error` 字段的 JSON 响应：

```kotlin
get("/some-route") {
    try {
        val result = doSomething()
        call.respond(result)
    } catch (e: Throwable) {
        logger.warn("[SomeRoute] failed: ${e.message}")
        call.respond(HttpStatusCode.InternalServerError, buildValidJson { kv("error", e.message ?: "unknown") })
    }
}
```

> 注意：Kotlin 的 `logger.warn` 只有单参重载，Throwable 需手动提取 `.message`。

---

## 3. 前端层（React）

### 3.1 catch 块：区分有无响应信息

- **没有 HTTP 响应**（网络异常、运行时错误、bridge 调用失败）→ `print_error`
- **有 HTTP 响应**（`resp.ok === false`）→ `reportHttpError`

```ts
import { print_error, reportHttpError } from "~/util/error_handler";

// catch 块（无响应信息）
} catch (e) {
    print_error({ reason: "加载配置异常", err: e });
}

// HTTP 响应错误
const { resp } = await apiFetch("/api/v1/SomeRoute", { method: "POST", ... });
if (!resp.ok) { reportHttpError("提交失败", resp); return; }
```

`reportHttpError` 会自动将 HTTP 状态码附加到错误描述中（`"提交失败 (HTTP 500)"`）。

### 3.2 BridgeUnavailableError 静默处理

`BridgeUnavailableError` 表示当前运行在浏览器开发环境（非桌面 WebView），属于预期情况，**静默返回**，不上报：

```ts
try { raw = await callBridge("xxx", "{}"); }
catch (e) { if (e instanceof BridgeUnavailableError) return; throw e; }
```

### 3.3 后端 error 字段检查

bridge 调用成功后，检查返回 JSON 中的 `error` 字段，非空则上报：

```ts
const res = JSON.parse(raw) as { variants?: string[]; error?: string };
if (res.error) {
    print_error({ reason: `获取镜像版本列表失败: ${res.error}`, variables: { mirror_key } });
    return;
}
```

### 3.4 useEffect 中的异步错误

`useEffect` 内的异步调用用 `.catch()` 捕获，区分 `BridgeUnavailableError` 和其他异常：

```ts
useEffect(() => {
    callBridge("get_torch_mirror_versions", JSON.stringify(params))
        .then(raw => {
            const res = JSON.parse(raw);
            if (res.error) { print_error({ reason: `失败: ${res.error}` }); return; }
            setData(res.variants);
        })
        .catch(e => {
            if (!(e instanceof BridgeUnavailableError)) {
                print_error({ reason: "获取数据异常", err: e });
            }
        });
}, [dep]);
```

---

## 4. 调用链路总览

```
前端 useEffect / 事件处理
  └─ callBridge("xxx", params)
  │    └─ Kotlin XxxJsMessageHandler.handle2()
  │         ├─ logger.warn（入参日志）
  │         ├─ FredicaApi.PyUtil.get(path)
  │         │    └─ catch → logger.warn + callback({"error": "..."})
  │         └─ callback(result)
  └─ .then(raw => JSON.parse → 检查 res.error → print_error)
  └─ .catch(e => BridgeUnavailableError ? 静默 : print_error)

前端 apiFetch / fetch
  └─ Ktor Route Handler
       ├─ 业务逻辑
       └─ catch → logger.warn + respond({"error": "..."})
  └─ if (!resp.ok) → reportHttpError
  └─ .catch(e) → print_error

Python FastAPI 路由（由 Kotlin PyUtil 调用）
  └─ fetch_xxx() 工具函数
       ├─ 预期失败 → logger.warning + return TypedDict(error=str(e))
       └─ 意外异常 → logger.exception + return TypedDict(error=str(e))
  └─ 路由层 except → logger.exception + JSONResponse({"error": ...})
```

---

## 5. 反模式（避免）

```python
# ❌ 静默吞掉
except Exception:
    pass

# ❌ 只打日志不传给前端
except Exception as e:
    logger.warning(e)
    return {"variants": []}   # 前端不知道出错了

# ❌ 路由层直接 raise（FastAPI 会返回 500，前端拿不到有意义的 error 字段）
except Exception as e:
    raise

# ❌ 工具函数返回裸 dict，无类型标注，字段含义不明
def fetch_data(variant: str) -> dict:
    return {"ok": True, "data": [...]}
```

```ts
// ❌ 裸 catch 静默
} catch { /* ignore */ }

// ❌ 只打 console.error，用户看不到
} catch (e) { console.error(e); }

// ❌ 不检查 res.error
const res = JSON.parse(raw);
setData(res.variants);  // 如果 res.error 非空，variants 是 []，用户不知道为什么

// ❌ 有 resp 却用 print_error（丢失状态码信息）
if (!resp.ok) { print_error({ reason: "失败" }); }  // 应用 reportHttpError
```
