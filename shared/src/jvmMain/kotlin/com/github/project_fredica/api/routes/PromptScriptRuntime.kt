package com.github.project_fredica.api.routes

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
//   - 可调用注入函数：getVar(key)、getSchemaHint(key)、readRoute(name, param)。
//   - 可使用 console.log/warn/error 写入日志。
// =============================================================================

import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.error
import com.github.project_fredica.apputil.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * @param scriptCode 用户脚本（必须含 main() 函数）。
     * @param provider   上下文数据提供者（按素材 ID 绑定）。
     * @param mode       执行模式，影响超时阈值。
     */
    suspend fun execute(
        scriptCode: String,
        provider: PromptRuntimeContextProvider,
        mode: Mode,
    ): PromptSandboxResult {
        // Preview: 5s; Run: 8s
        val timeoutMs = if (mode == Mode.PREVIEW) 5_000L else 8_000L
        val logs = mutableListOf<PromptSandboxLog>()

        return withContext(Dispatchers.IO) {
            val context = buildGraalContext()
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
                executeInContext(context, scriptCode, provider, logs)
            } catch (e: Throwable) {
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
        .option("engine.WarnInterpreterOnly", "false")
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
            logger.warn(
                "[PromptScriptRuntime] GraalJS 异常: ${e.message} isCancelled=${e.isCancelled}",
                isHappensFrequently = false, err = null,
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
        // 主机 API
        b.putMember("getVar", ProxyExecutable { args: Array<out Value> ->
            provider.getVar(args.getOrNull(0)?.asString() ?: "")
        })
        b.putMember("getSchemaHint", ProxyExecutable { args: Array<out Value> ->
            provider.getSchemaHint(args.getOrNull(0)?.asString() ?: "")
        })
        b.putMember("readRoute", ProxyExecutable { args: Array<out Value> ->
            val name = args.getOrNull(0)?.asString() ?: ""
            val param = args.getOrNull(1)?.asString() ?: "{}"
            provider.readRoute(name, param)
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
