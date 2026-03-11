---
title: 测试指南
order: 5
---

# 测试指南

## 运行测试

所有单元测试位于 `shared/src/jvmTest/`，通过 Gradle 在 JVM 上运行。

```shell
# 运行全部测试
./gradlew :shared:jvmTest

# 强制重新运行（跳过 UP-TO-DATE 缓存）
./gradlew :shared:jvmTest --rerun-tasks

# 运行单个测试类
./gradlew :shared:jvmTest --tests "com.github.project_fredica.db.TaskDbTest"

# 带详细日志
./gradlew :shared:jvmTest --rerun-tasks --info
```

## 测试位置

所有单元测试位于 `shared/src/jvmTest/kotlin/`，按包结构组织：

```
shared/src/jvmTest/kotlin/.../
├── db/          # DB 层测试（Task、WorkflowRun、Weben、对账等）
├── worker/      # WorkerEngine、DagEngine、Executor 测试
├── llm/         # LLM SSE 客户端测试
└── python/      # Python 服务通信测试
```

## 关键约定

### SQLite 测试隔离

::: warning 必须使用临时文件，不能用 `:memory:`
ktorm 连接池每次 `useConnection {}` 开新连接，内存库各连接相互独立，会导致建表和插入数据在不同连接上，测试间数据无法共享。

```kotlin
// 正确做法
val tmpFile = File.createTempFile("test_", ".db").also { it.deleteOnExit() }
val db = Database.connect(
    url = "jdbc:sqlite:${tmpFile.absolutePath}",
    driver = "org.sqlite.JDBC",
)
```
:::

### WorkerEngine 测试隔离

::: warning @AfterTest 必须取消 CoroutineScope
`WorkerEngine` 是全局单例，每次 `start()` 都会新增轮询协程。若不在 `@AfterTest` 中取消，旧协程会抢占下一个测试的任务，导致测试间相互干扰。

```kotlin
private val activeScopes = mutableListOf<CoroutineScope>()

@AfterTest
fun tearDown() {
    activeScopes.forEach { it.cancel() }
    activeScopes.clear()
}
```
:::

### 条件跳过测试

部分测试依赖外部服务（Python 服务、LLM API Token），无外部依赖时会自动跳过，不影响 CI：

```kotlin
// LlmSseClientTest：无 token 时跳过
assumeTrue(System.getenv("LLM_TEST_API_KEY") != null)
```
