package com.github.project_fredica.prompt

// =============================================================================
// PromptScriptRuntime (jvmMain)
// =============================================================================
//
// GraalJS 沙箱执行引擎。
//
// 架构：
//   - Engine 单例（lazy 初始化），供所有执行上下文共享，减少 JIT 重热开销。
//   - Context 每次执行新建、执行完毕后关闭，防止状态泄漏。
//   - 并发控制：Preview ≤ 3，Run ≤ 2（Semaphore）。
//   - 超时控制：watchdog 协程在指定时间后调用 context.close(true) 中断执行。
//   - 主机函数通过 ProxyExecutable 注入，不赋予脚本任何主机类访问权限。
//
// 脚本约定：
//   - 必须定义 `async function main()` 或 `function main()`。
//   - main() 返回字符串（Prompt 文本）。
//   - 可调用注入函数：getVar(key)、getSchemaHint(key)。
//   - 可使用 console.log/warn/error 写入日志。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable

object PromptScriptRuntime {
    private val logger = createLogger { "PromptScriptRuntime" }

    /** 共享 Engine：复用 JIT 编译缓存，减少首次执行开销。 */
    private val engine: Engine by lazy {
        Engine.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build()
    }

    /** Preview 模式最大并发数。 */
    val previewSemaphore = Semaphore(3)

    /** Run 模式最大并发数。 */
    val runSemaphore = Semaphore(2)

    enum class Mode { PREVIEW, RUN }

    // ── 主执行入口 ─────────────────────────────────────────────────────────────

    /**
     * 在 GraalJS 沙箱中执行脚本，返回执行结果。
     *
     * [PromptRuntimeContextProvider] 由内部创建，通过读取 GraalJS bindings 中的 `__materialId` 获取素材 ID。
     * 该变量由前端编辑器在脚本头部自动注入（不属于模板内容）。
     *
     * @param scriptCode 完整脚本（含前端注入的头部，必须含 main() 函数）。
     * @param mode       执行模式，影响超时阈值。
     */
    suspend fun execute(
        scriptCode: String,
        mode: Mode,
    ): PromptSandboxResult {
        // 5 分钟超时（字幕加载等 IO 操作耗时较长）
        val timeoutMs = 300_000L
        val logs = mutableListOf<PromptSandboxLog>()

        logger.debug("[PromptScriptRuntime] 开始执行: mode=$mode scriptLength=${scriptCode.length}")
        return withContext(Dispatchers.IO) {
            val context = buildGraalContext()
            // materialId 由前端编辑器在脚本头部注入，脚本通过路径字符串显式传递给 getVar。
            val provider = PromptRuntimeContextProvider()
            // 硬超时 watchdog：关闭 Context 会使 eval() 抛出 PolyglotException(isCancelled=true)
            val watchdog = launch {
                delay(timeoutMs)
                logger.warn(
                    "[PromptScriptRuntime] 执行超时（${timeoutMs}ms），中断 GraalJS 上下文",
                    isHappensFrequently = false, err = null,
                )
                runCatching { context.close(true) }
            }
            try {
                val result = executeInContext(context, scriptCode, provider, logs)
                logger.debug(
                    "[PromptScriptRuntime] 执行完毕: errorType=${result.errorType} logs=${result.logs.size}",
                )
                result
            } catch (e: CancellationException) {
                // 协程取消信号必须透传，不能吞掉；否则 withContext 无法正常取消
                logger.debug("[PromptScriptRuntime] execute 协程已取消")
                throw e
            } catch (e: Throwable) {
                // executeInContext 内部已处理 PolyglotException；到这里属于意外异常
                logger.error("[PromptScriptRuntime] execute 意外异常", e)
                PromptSandboxResult(
                    promptText = null,
                    error = e.message ?: "未知错误",
                    errorType = "exception",
                    logs = logs,
                )
            } finally {
                watchdog.cancel()
                runCatching { context.close() }
            }
        }
    }

    // ── 私有实现 ───────────────────────────────────────────────────────────────

    private fun buildGraalContext(): Context = Context.newBuilder("js")
        .engine(engine)
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(IOAccess.NONE)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        // 注意：engine.WarnInterpreterOnly 是 Engine 级选项，已在 engine 单例中配置，
        // 不能在共享了 engine 的 Context 上重复设置（会抛 IllegalArgumentException）。
        .option("js.strict", "true")
        .build()

    private fun executeInContext(
        context: Context,
        scriptCode: String,
        provider: PromptRuntimeContextProvider,
        logs: MutableList<PromptSandboxLog>,
    ): PromptSandboxResult {
        injectBindings(context, provider, logs)
        return try {
            context.eval("js", buildWrappedScript(scriptCode))
            // GraalJS 不会自动刷新 microtask queue；每次 eval() 触发一轮刷新。
            // 对于 async main()，每个 await 产生一个 microtask tick，最多循环 50 次。
            val bindings = context.getBindings("js")
            var flushCount = 0
            while (flushCount++ < 50 && bindings.getMember("__done")?.asBoolean() != true) {
                context.eval("js", "0")
            }
            extractResult(bindings, logs)
        } catch (e: PolyglotException) {
            // isCancelled=true 是看门狗超时触发的预期行为，不记录 stacktrace；
            // 其他 GraalJS 脚本错误属于预期失败（用户脚本问题），记录完整堆栈以助调试。
            logger.warn(
                "[PromptScriptRuntime] GraalJS 异常: ${e.message} isCancelled=${e.isCancelled}",
                isHappensFrequently = false, err = if (e.isCancelled) null else e,
            )
            PromptSandboxResult(
                promptText = null,
                error = if (e.isCancelled) "执行超时，已中断" else (e.message ?: "脚本执行失败"),
                errorType = if (e.isCancelled) "timeout" else "script_error",
                logs = logs,
            )
        }
    }

    private fun injectBindings(
        context: Context,
        provider: PromptRuntimeContextProvider,
        logs: MutableList<PromptSandboxLog>,
    ) {
        val b = context.getBindings("js")
        // console 日志收集器
        b.putMember("__logFn", ProxyExecutable { args: Array<out Value> ->
            val level = args.getOrNull(0)?.asString() ?: "log"
            val msg = args.getOrNull(1)?.asString() ?: ""
            logs.add(PromptSandboxLog(level = level, args = msg, ts = System.currentTimeMillis()))
            null
        })
        // JS main() -> GraalJS ProxyExecutable -> PromptRuntimeContextProvider -> resolver
        b.putMember("getVar", ProxyExecutable { args: Array<out Value> ->
            val key = args.getOrNull(0)?.asString() ?: ""
            logger.debug("[PromptScriptRuntime] JS 调用 getVar key=$key")
            runBlocking(Dispatchers.IO) {
                provider.getVar(key).also {
                    logger.debug("[PromptScriptRuntime] JS getVar 返回 key=$key isBlank=${it.isBlank()} length=${it.length}")
                }
            }
        })
        b.putMember("getSchemaHint", ProxyExecutable { args: Array<out Value> ->
            val key = args.getOrNull(0)?.asString() ?: ""
            logger.debug("[PromptScriptRuntime] JS 调用 getSchemaHint key=$key")
            runBlocking(Dispatchers.IO) {
                provider.getSchemaHint(key).also {
                    logger.debug(
                        "[PromptScriptRuntime] JS getSchemaHint 返回 key=$key isBlank=${it.isBlank()} length=${it.length}",
                    )
                }
            }
        })
    }

    private fun buildWrappedScript(scriptCode: String): String = """
        var console = {
            log:   function() { __logFn('log',   Array.prototype.slice.call(arguments).join(' ')); },
            warn:  function() { __logFn('warn',  Array.prototype.slice.call(arguments).join(' ')); },
            error: function() { __logFn('error', Array.prototype.slice.call(arguments).join(' ')); }
        };
        var __result = undefined;
        var __error  = undefined;
        var __done   = false;

        $scriptCode

        if (typeof main !== 'function') {
            __error = '脚本必须定义 main() 函数';
            __done  = true;
        } else {
            try {
                var __ret = main();
                if (__ret !== null && __ret !== undefined && typeof __ret.then === 'function') {
                    __ret.then(
                        function(v) { __result = (v == null ? '' : String(v)); __done = true; },
                        function(e) { __error  = String(e);                    __done = true; }
                    );
                } else {
                    __result = (__ret == null ? '' : String(__ret));
                    __done   = true;
                }
            } catch(e) {
                __error = String(e);
                __done  = true;
            }
        }
    """.trimIndent()

    private fun extractResult(bindings: Value, logs: MutableList<PromptSandboxLog>): PromptSandboxResult {
        val done = bindings.getMember("__done")?.asBoolean() ?: false
        val error = bindings.getMember("__error")
        val result = bindings.getMember("__result")
        return when {
            !done -> PromptSandboxResult(
                promptText = null, error = "脚本执行未完成", errorType = "incomplete", logs = logs,
            )

            error != null && !error.isNull -> PromptSandboxResult(
                promptText = null, error = error.asString(), errorType = "script_error", logs = logs,
            )

            else -> PromptSandboxResult(
                promptText = result?.takeIf { !it.isNull }?.asString(),
                error = null, errorType = null, logs = logs,
            )
        }
    }
}
