---
title: 测试指南
order: 130
---

# 测试指南

## Kotlin / Gradle 测试（shared 模块）

测试文件位于 `shared/src/jvmTest/kotlin/`，按包结构组织：

```
shared/src/jvmTest/kotlin/.../
├── db/          # Task、WorkflowRun、Weben、对账等
├── worker/      # WorkerEngine、DagEngine、Executor
├── llm/         # LLM SSE 客户端
└── python/      # Python 服务通信
```

### 运行命令

```shell
# 运行全部测试（跳过 UP-TO-DATE 缓存）
./gradlew :shared:jvmTest --rerun-tasks

# 运行单个测试类
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.TaskDbTest"

# 带详细日志
./gradlew :shared:jvmTest --rerun-tasks --info
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
