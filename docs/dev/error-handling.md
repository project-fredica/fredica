---
title: 错误处理指南
order: 110
---

# 错误处理指南

本文档描述 Fredica 各层（Python / Kotlin / 前端）的错误处理约定，目标是：

- 异常不被静默吞掉
- 严重程度与日志级别匹配
- 错误信息能沿调用链传递到前端，通过 Toast 通知用户

---

## 1. 日志级别选择

### Python

| 场景 | 级别 |
|------|------|
| 预期失败（网络超时、镜像不支持该 variant） | `logger.warning` — 消息中附上 `str(e)` |
| 不可预期的异常（文件读取失败、内部逻辑错误） | `logger.exception`（自动附加 traceback，无需手动传 e） |
| 正常流程的关键节点 | `logger.debug` / `logger.info` |

```python
# 预期失败 — warning，手动附上异常信息
except Exception as e:
    logger.warning(f"[torch] fetch failed: {e}")
    return {"variants": [], "error": str(e)}

# 不可预期 — exception（含 traceback，不需要传 e）
except Exception:
    logger.exception("[torch] check_download: failed to read dist-info for variant={variant}")
    return TorchCheckResult(variant=variant, already_ok=False, ...)
```

### Kotlin

| 场景 | 级别 |
|------|------|
| 预期失败（Python 服务未就绪、用户输入无效、外部服务超时） | `logger.warn(msg, isHappensFrequently, err)` — 扩展函数，按频率决定详细程度 |
| 不可预期的异常（DB 操作异常、状态机非法转移、序列化失败） | `logger.error(msg, err)` — 扩展函数，需显式 `import com.github.project_fredica.apputil.error` |
| 正常流程的关键节点 | `logger.debug` / `logger.info` |

Logger API 说明（`logger.kt` / `logger.jvm.kt`）：

| 方法 | 签名 | 说明 |
|------|------|------|
| `logger.warn(msg)` | `(String)` | 单参，无异常信息 |
| `logger.warn(msg, isHappensFrequently, err)` | `(String, Boolean, Throwable?)` | 扩展函数；`false` 输出完整 stacktrace，`true` 只输出简短类型+message（高频场景避免刷屏） |
| `logger.error(msg)` | `(String)` | 单参，无 stacktrace |
| `logger.error(msg, err)` | `(String, Throwable?)` | 扩展函数，`err != null` 时自动附加 stacktrace；需 `import com.github.project_fredica.apputil.error` |
| `logger.exception(msg, err)` | `(String, Throwable)` | 底层实现，一般不直接调用 |

```kotlin
// ✅ warn — 不频繁的预期失败，输出完整 stacktrace
} catch (e: Throwable) {
    logger.warn("[XxxHandler] Python call failed", isHappensFrequently = false, err = e)
}

// ✅ warn — 高频预期失败（如轮询），只输出简短信息避免刷屏
} catch (e: Throwable) {
    logger.warn("[XxxHandler] ping failed", isHappensFrequently = true, err = e)
}

// ✅ error — 不可预期异常，附加完整 stacktrace
import com.github.project_fredica.apputil.error
} catch (e: Throwable) {
    logger.error("[XxxExecutor] unexpected DB failure", e)
}

// ❌ 不传异常 — 异常信息静默丢失
} catch (e: Throwable) {
    logger.warn("[XxxHandler] failed")
}
```

**判断依据：** 预期失败（上游已有日志，本层只记录上下文）用 `warn`；内部不应失败却失败了、需要开发者介入排查的用 `error`。

---

## 2. Python 层（FastAPI 服务）

### 2.1 错误信息传给前端

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

### 2.2 工具函数的返回约定

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

## 3. Kotlin 层

Kotlin 层分两类入口，错误处理模式略有不同。

### 3.1 JsBridge Handler

#### 参数解析失败

参数解析失败属于调用方问题，用 `logger.warn` 记录，并通过 callback 返回 `{"error": "..."}` 给前端：

```kotlin
val params = try {
    AppUtil.GlobalVars.json.parseToJsonElement(message.params) as? JsonObject
} catch (e: Throwable) {
    logger.warn("[XxxHandler] failed to parse params", isHappensFrequently = false, err = e)
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
    logger.warn("[XxxHandler] Python call failed", isHappensFrequently = false, err = e)
    buildValidJson { kv("error", e.message ?: "unknown error") }.str
}
callback(result)
```

#### 入参日志

每个 handler 在发起 Python 调用前打一条 `logger.warn`，记录关键入参和路径，便于追踪：

```kotlin
logger.warn("[GetTorchMirrorVersionsJsMessageHandler] mirrorKey=$mirrorKey useProxy=$useProxy path=$path")
```

### 3.2 Route API（Ktor 路由）

Ktor 路由层用 `try/catch` 包裹业务逻辑，异常时返回带 `error` 字段的 JSON 响应：

```kotlin
get("/some-route") {
    try {
        val result = doSomething()
        call.respond(result)
    } catch (e: Throwable) {
        logger.warn("[SomeRoute] failed", isHappensFrequently = false, err = e)
        call.respond(HttpStatusCode.InternalServerError, buildValidJson { kv("error", e.message ?: "unknown") })
    }
}
```

---

## 4. 前端层（React）

### 4.1 catch 块：区分有无响应信息

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

### 4.2 BridgeUnavailableError 处理

`BridgeUnavailableError` 表示当前运行在浏览器开发环境（非桌面 WebView），属于预期情况。

**`callBridge` 内部统一 debug log**，调用方无需重复记录。根据场景选择两种调用方式：

**情形一：静默跳过（大多数场景）**

用 `callBridgeOrNull`，bridge 不可用时返回 `null`，调用方无需 catch。`params` 默认为 `"{}"`，无参数时可省略：

```ts
// ✅ 无参数时省略 params
const raw = await callBridgeOrNull("get_torch_info");
if (!raw) return;
const res = JSON.parse(raw);

// ✅ 有参数时传入
const raw = await callBridgeOrNull("get_torch_mirror_versions", JSON.stringify({ mirror_key }));
if (!raw) return;
```

**情形二：需要 UI 反馈（如下载、评估等用户主动触发的操作）**

用 `callBridge`，在外层 catch 中处理 `BridgeUnavailableError`：

```ts
// ✅ 外层统一 catch，BridgeUnavailableError 显示 UI 提示
try {
    const raw = await callBridge("download_torch");  // 无参数时省略 params
    // ...
} catch (e) {
    if (e instanceof BridgeUnavailableError) { setError("仅支持在桌面应用内运行"); return; }
    print_error({ reason: "操作失败", err: e });
}
```

**不应在调用处重复 log**：

```ts
// ❌ callBridge 内部已经 debug log 过了，无需再 log
catch (e) {
    if (e instanceof BridgeUnavailableError) { console.debug("bridge unavailable"); return; }
}
```

### 4.3 后端 error 字段检查

bridge 调用成功后，检查返回 JSON 中的 `error` 字段，非空则上报：

```ts
const res = JSON.parse(raw) as { variants?: string[]; error?: string };
if (res.error) {
    print_error({ reason: `获取镜像版本列表失败: ${res.error}`, variables: { mirror_key } });
    return;
}
```

### 4.4 useEffect 中的异步错误

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
            if (e instanceof BridgeUnavailableError) return;  // callBridge 内部已 debug log
            print_error({ reason: "获取数据异常", err: e });
        });
}, [dep]);
```

---

## 5. 调用链路总览

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

## 6. 反模式（避免）

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

---

## 7. Kotlin 协程错误预防

协程内的异常有几种静默丢失的场景，需要特别注意。

### 7.1 `launch` 内的异常不会被外部 try-catch 捕获

`launch` 启动的协程与调用方是并发关系，外层 try-catch 无法捕获协程内部抛出的异常：

```kotlin
// ❌ 外层 try-catch 捕获不到协程内的异常
try {
    scope.launch { riskyOperation() }  // 异常在协程内抛出，外层感知不到
} catch (e: Throwable) {
    // 永远不会执行
}

// ✅ 在协程内部处理
scope.launch {
    try {
        riskyOperation()
    } catch (e: Throwable) {
        logger.warn("[Xxx] operation failed", isHappensFrequently = false, err = e)
    }
}
```

### 7.2 孤立 scope 的 `launch` 异常会静默丢失

用 `CoroutineScope(...)` 创建的孤立 scope，其 `launch` 协程抛出的未捕获异常默认只打印到 stderr，**不会传播给父协程，也不会触发任何回调**：

```kotlin
// ❌ 孤立 scope — 异常静默丢失（只有 stderr 输出，生产环境可能看不到）
CoroutineScope(Dispatchers.IO).launch {
    doSomething()  // 抛异常 → 静默丢失
}

// ✅ 在协程内兜底
CoroutineScope(Dispatchers.IO).launch {
    try {
        doSomething()
    } catch (e: Throwable) {
        logger.warn("[Xxx] failed", isHappensFrequently = false, err = e)
    }
}
```

本项目中 `MyJsMessageHandler.handle()` 已在基类统一兜底，子类 `handle2()` 无需重复处理。`engineScope.launch` 等后台启动点需在协程内加 try-catch。

### 7.3 `async` 的异常处理

`async` 默认（`CoroutineStart.DEFAULT`）不会立即抛出异常，而是在调用 `await()` 时才重新抛出。**不论是否 `await`，块内都应加 try-catch 并记录日志**——否则异常发生时不会打印任何信息，排查困难。

**情形一：需要结果，用 `await` + `runCatching`**

```kotlin
val deferred = scope.async {
    try {
        riskyOperation()
    } catch (e: CancellationException) {
        logger.debug("[Xxx] async cancelled")
        throw e  // 取消信号必须透传
    } catch (e: Throwable) {
        logger.error("[Xxx] async block failed", e)
        throw e  // 重新抛出，让 await() 侧感知
    }
}
val result = runCatching { deferred.await() }.getOrElse {
    logger.error("[Xxx] await failed", it)
    return
}
```

**情形二：fire-and-forget，不 `await`，块内自行处理**

不 `await` 是合法用法，但此时异常不会传播到任何地方，必须在块内捕获：

```kotlin
scope.async {
    try {
        riskyOperation()
    } catch (e: CancellationException) {
        logger.debug("[Xxx] background async cancelled")
        throw e
    } catch (e: Throwable) {
        logger.warn("[Xxx] background async failed", isHappensFrequently = false, err = e)
        // 不重新抛出，静默结束
    }
}
```

> 💡 fire-and-forget 场景通常用 `launch` 更语义清晰；`async` 主要用于需要返回值的场景。

**情形三：`CoroutineStart.UNDISPATCHED` 下异常立即抛出**

使用 `async(start = CoroutineStart.UNDISPATCHED)` 时，块内代码在当前线程同步执行直到第一个挂起点，此前抛出的异常会**立即**传播到调用方，而非延迟到 `await()`。此时行为更接近普通函数调用，需在调用处用 try-catch 处理：

```kotlin
// UNDISPATCHED：第一个挂起点前的异常立即抛出，不等 await()
try {
    val deferred = scope.async(start = CoroutineStart.UNDISPATCHED) {
        riskyOperation()  // 若此处（挂起前）抛出，立即传播到这里
        delay(100)        // 挂起点之后的异常仍延迟到 await()
        moreWork()
    }
    val result = deferred.await()
} catch (e: CancellationException) {
    logger.debug("[Xxx] undispatched async cancelled")
    throw e
} catch (e: Throwable) {
    logger.error("[Xxx] undispatched async failed", e)
}
```

### 7.4 结构化并发中的异常传播

在结构化并发（`coroutineScope { }` 或父子协程）中，子协程的异常会取消整个 scope 并向上传播。若只想隔离单个子任务的失败，用 `supervisorScope` 或 `SupervisorJob`：

```kotlin
// 子协程失败会取消所有兄弟协程
coroutineScope {
    launch { task1() }
    launch { task2() }  // task1 失败 → task2 也被取消
}

// supervisorScope：子协程失败互不影响
supervisorScope {
    launch { task1() }  // 失败不影响 task2
    launch { task2() }
}
```

本项目 `WorkerEngine` 使用 `SupervisorJob` 隔离各任务执行，单个任务失败不会影响引擎整体运行。

### 7.5 `CancellationException` 不应被吞掉

`CancellationException` 是协程取消的信号，catch `Throwable` 时必须重新抛出，否则会阻止协程正常取消：

```kotlin
// ❌ 吞掉 CancellationException — 协程无法被取消
} catch (e: Throwable) {
    logger.warn("failed", isHappensFrequently = false, err = e)
    // 没有 rethrow，取消信号丢失
}

// ✅ 重新抛出 CancellationException，并记录 debug 日志便于追踪取消路径
} catch (e: CancellationException) {
    logger.debug("[Xxx] cancelled")
    throw e  // 必须重新抛出
} catch (e: Throwable) {
    logger.error("failed", e)
}
```
