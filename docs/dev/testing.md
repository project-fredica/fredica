---
title: 测试指南
order: 130
---

# 测试指南

## 前端测试（fredica-webui）

测试文件位于 `fredica-webui/tests/`，使用 [Vitest](https://vitest.dev/) + jsdom + @testing-library/react。

```
fredica-webui/tests/
├── mocks/
│   └── broadcastChannel.ts   # MockBroadcastChannel（内存路由，替换 JSDOM 缺失的原生实现）
├── setup.ts                  # 全局初始化（注入 MockBroadcastChannel）
├── util/
│   └── videoPlayerChannel.test.ts   # BroadcastChannel hook 测试（C1–C9，8 cases）
├── hooks/
│   └── useVideoPlayerState.test.ts  # 播放器状态机测试（V1–V14，14 cases）
└── context/
    └── floatingPlayer.test.tsx      # 悬浮播放器 Context 测试（F1–F4，4 cases）
```

### 运行命令

```shell
cd fredica-webui

# 首次运行前安装依赖
npm install -D

# 运行类型检查
npx tsc --noEmit

# 一次性跑完所有测试（CI 模式）
npm test

# 监视模式（文件改动自动重跑）
npm run test:watch

# 跑单个测试文件
npx vitest run tests/util/videoPlayerChannel.test.ts
npx vitest run tests/hooks/useVideoPlayerState.test.ts
npx vitest run tests/context/floatingPlayer.test.tsx
```

**预期输出（全部通过）**：

```
 ✓ tests/context/floatingPlayer.test.tsx    (4 tests)
 ✓ tests/util/videoPlayerChannel.test.ts   (8 tests)
 ✓ tests/hooks/useVideoPlayerState.test.ts (14 tests)

 Test Files  3 passed (3)
       Tests  26 passed (26)
```

### 关键 Mock 策略

**`MockBroadcastChannel`**（`tests/mocks/broadcastChannel.ts`）

JSDOM 不原生支持 `BroadcastChannel`，用内存 Map 路由代替：
- 同 channel name 的实例共享一个 `Set`，`postMessage` 发给同名其他实例
- `static reset()` 在每个测试的 `beforeEach`/`afterEach` 中清除，防止跨测试污染
- 在 `tests/setup.ts` 中全局替换 `globalThis.BroadcastChannel`

**`fetch` mock**

各测试用 `vi.stubGlobal("fetch", ...)` 替换，`afterEach` 中 `vi.unstubAllGlobals()` 恢复：

```typescript
vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({ ready: true, file_mtime: 100, file_size: 500 }),
}));
```

**`useAppConfig` mock**

`useAppConfig()` 返回的是 `{ appConfig, setAppConfig, isStorageLoaded }`，配置字段在 `appConfig` 内部。mock 时需保持这个两层结构，业务代码中也必须先解构 `appConfig` 再取字段（详见 `appConfig.tsx` JSDoc）：

```typescript
vi.mock("~/context/appConfig", () => ({
    useAppConfig: () => ({
        // ⚠️ 注意：配置字段嵌套在 appConfig 内，不是顶层
        appConfig: {
            webserver_schema: "http",
            webserver_domain: "localhost",
            webserver_port: "7631",
            webserver_auth_token: "test-token",
        },
    }),
}));
```

**fake timers**（C9、V7）

使用 `vi.useFakeTimers()` / `vi.advanceTimersByTime(ms)` 测试节流和轮询，测试结束后 `vi.useRealTimers()` 恢复。

---

## Kotlin / Gradle 测试

shared 模块和 composeApp 模块都需要运行 jvmTest 测试。

### 运行命令

```shell
# 运行 shared 模块全部测试（--rerun-tasks 跳过 UP-TO-DATE 缓存）（可选 --info 参数带详细日志）
./gradlew :shared:jvmTest --rerun-tasks

# 运行单个测试类
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.TaskDbTest"

# 运行 composeApp 模块全部测试（--rerun-tasks 跳过 UP-TO-DATE 缓存）（可选 --info 参数带详细日志）
./gradlew :composeApp:jvmTest --rerun-tasks
```

### 关键约定

**SQLite 测试隔离：必须用临时文件，不能用 `:memory:`**

ktorm 连接池每次 `useConnection {}` 开新连接，内存库各连接相互独立，建表和插入数据会在不同连接上执行。

```kotlin
val tmpFile = File.createTempFile("test_", ".db").also { it.deleteOnExit() }
val db = Database.connect(
    url = "jdbc:sqlite:${tmpFile.absolutePath}",
    driver = "org.sqlite.JDBC",
)
```

**WorkerEngine 测试隔离：`@AfterTest` 必须取消 CoroutineScope**

`WorkerEngine` 是全局单例，每次 `start()` 都会新增轮询协程。不取消会导致旧协程抢占下一个测试的任务。

```kotlin
private val activeScopes = mutableListOf<CoroutineScope>()

@AfterTest
fun tearDown() {
    activeScopes.forEach { it.cancel() }
    activeScopes.clear()
}
```

**条件跳过：依赖外部服务时自动跳过，不影响 CI**

```kotlin
// 无 token 时跳过
assumeTrue(System.getenv("LLM_TEST_API_KEY") != null)
```

---

## Python 服务测试（fredica-pyutil）

测试文件位于 `desktop_assets/common/fredica-pyutil/tests/`，使用 pytest。

### 运行命令

使用打包的 embedded Python（`desktop_assets/windows/lfs/python-314-embed/python.exe`）：

```shell
cd desktop_assets/common/fredica-pyutil

# 只跑解析函数单元测试（无网络，速度快）
../../windows/lfs/python-314-embed/python.exe -m pytest tests/ -v -m "not network"

# 只跑真实网络请求测试
../../windows/lfs/python-314-embed/python.exe -m pytest tests/ -v -m "network" -s

# 全跑
../../windows/lfs/python-314-embed/python.exe -m pytest tests/ -v -s
```

### mark 约定

| mark                   | 含义                  |
|------------------------|---------------------|
| 无 mark                 | 纯逻辑单元测试，不需要网络，速度快   |
| `@pytest.mark.network` | 发真实 HTTP 请求，依赖网络可达性 |

mark 在 `pytest.ini` 中已注册，离线环境用 `-m "not network"` 只跑单元测试。
