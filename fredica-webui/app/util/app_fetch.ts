import { useCallback, useEffect, useRef, useState } from "react";
import { useAppConfig } from "~/context/appConfig";
import { print_error, reportHttpError } from "./error_handler";
import { json_parse, type JsonValue } from "~/util/json";

export const DEFAULT_SERVER_PORT = "7631";

// ─── FredicaFixBugAdvice ──────────────────────────────────────────────────────
// 后端在捕获到特定异常时，会在 JSON 响应中附加 FredicaFixBugAdvice 字段，
// 前端检测到该字段后弹出 toast 提示用户修复建议。
// 目前已知场景：Clash HTTP 代理模式下 LLM 调用失败，建议切换为 PAC 模式。
// 根本原因与诊断过程见后端 LlmProxyDebugTest。

const FIX_ADVICE_MESSAGES: Record<string, string> = {
    OpenClashPAC: "检测到系统代理，若 LLM 调用失败，请让服主将 Clash 代理模式切换为 PAC 模式",
};

function checkFixBugAdvice(data: unknown) {
    if (data == null || typeof data !== "object") return;
    const advice = (data as Record<string, unknown>)["FredicaFixBugAdvice"];
    if (typeof advice !== "string") return;
    const msg = FIX_ADVICE_MESSAGES[advice];
    if (!msg) return;
    import("react-toastify").then((m) =>
        m.toast.warn(`💡 ${msg}`, { autoClose: 8000, position: "top-right" })
    ).catch(() => {});
}

// ─── 401 重定向去重 ──────────────────────────────────────────────────────────
// 多个并发请求同时收到 401 时，只执行一次跳转到 /login
let _redirectingToLogin = false;

// ─── 内部工具函数 ──────────────────────────────────────────────────────────────

function getAppHost(
    domain?: string | null,
    port?: string | null,
    schema?: string | null,
) {
    const s = schema ?? "http";
    const d = domain ?? "localhost";
    const p = port ?? DEFAULT_SERVER_PORT;
    return `${s}://${d}:${p}`;
}

export function buildAuthHeaders(
    tokenOrConfig?: string | null | { session_token: string | null; webserver_auth_token: string | null },
): Record<string, string> {
    if (!tokenOrConfig) return {};
    if (typeof tokenOrConfig === "string") return { Authorization: `Bearer ${tokenOrConfig}` };
    // AppConfig 对象：session_token 优先，fallback webserver_auth_token
    const token = tokenOrConfig.session_token ?? tokenOrConfig.webserver_auth_token;
    if (!token) return {};
    return { Authorization: `Bearer ${token}` };
}
/**
 * 创建一个带超时的 AbortController。
 *
 * - `timeout > 0`：在指定毫秒后自动触发 abort（TimeoutError）。
 * - `timeout <= 0`：不设超时，clearTimer 为空操作。
 *
 * 使用方负责在请求结束后调用 `clearTimer()` 取消计时器，
 * 在组件卸载 / 清理时调用 `abort.abort()` 取消请求。
 */
function makeAbortWithTimeout(timeout: number): {
    abort: AbortController;
    clearTimer: () => void;
} {
    const abort = new AbortController();
    if (timeout <= 0) return { abort, clearTimer: () => {} };
    const timer = setTimeout(
        () =>
            abort.abort(new DOMException("Request timed out", "TimeoutError")),
        timeout,
    );
    return { abort, clearTimer: () => clearTimeout(timer) };
}

/**
 * 在 `init.headers` 之上注入 auth header，再执行 fetch。
 * auth header 优先级低于 `init.headers`，调用方可按需覆盖。
 */
async function fetchWithAuth(
    url: string,
    init: RequestInit | undefined,
    authToken: string | null | undefined,
    signal: AbortSignal,
): Promise<Response> {
    return fetch(url, {
        ...init,
        headers: {
            ...buildAuthHeaders(authToken),
            "Content-Type": "application/json",
            ...init?.headers,
        },
        signal,
    });
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

/**
 * 返回一个将外部图片 URL 转换为本地代理 URL 的函数，用于规避跨站图片加载限制。
 * 代理路由无需 Authorization 头，可直接用于 `<img src={...}>` 属性。
 *
 * @example
 * const buildProxyUrl = useImageProxyUrl();
 * <img src={buildProxyUrl(media.cover)} />
 */
export function useImageProxyUrl(): (imageUrl: string) => string {
    const { appConfig } = useAppConfig();
    const host = getAppHost(
        appConfig.webserver_domain,
        appConfig.webserver_port,
        appConfig.webserver_schema,
    );
    return (imageUrl: string) =>
        `${host}/api/v1/ImageProxyRoute?url=${encodeURIComponent(imageUrl)}`;
}

/**
 * 统一的应用 API 请求 Hook，自动注入 auth header、管理超时。
 *
 * ## 两种使用模式
 *
 * ### 1. 声明式（适合页面初次加载数据）
 * 传入 `appPath` 后自动在挂载时发请求；`appPath` / token / host 任一变化时重新请求。
 * 状态通过 `{ data, loading, error, response }` 暴露，适合直接驱动 UI 的 loading / error 状态。
 *
 * ```tsx
 * const { data, loading, error } = useAppFetch<MaterialCategory[]>({
 *     appPath: '/api/v1/MaterialCategoryListRoute',
 *     init: { method: 'POST', body: '{}' },
 * });
 * if (loading) return <Spinner />;
 * if (error) return <ErrorMsg error={error} />;
 * return <CategoryList items={data ?? []} />;
 * ```
 *
 * ### 2. 命令式（适合按钮点击、表单提交等事件处理器）
 * 不传 `appPath`，只使用返回的 `apiFetch` 函数。调用方自行管理 loading / error 状态。
 * `apiFetch` 返回 `{ resp, data }`，**不会**因 HTTP 非 2xx 抛错，调用方需检查 `resp.ok`。
 * 仅在网络故障、超时、请求被中止时 throw，建议在调用处用 try/catch 处理。
 *
 * 请求体用 `JSON.stringify` 序列化；响应体由内部的 `json_parse` 解析，`data` 类型由泛型参数决定。
 *
 * ```tsx
 * const { apiFetch } = useAppFetch();
 *
 * const handleImport = async () => {
 *     try {
 *         const { resp, data } = await apiFetch('/api/v1/MaterialImportRoute', {
 *             method: 'POST',
 *             body: JSON.stringify({ source_type: 'bilibili', videos }),
 *         });
 *         if (!resp.ok) { reportHttpError(`导入失败: HTTP ${resp.status}`, resp); return; }
 *         console.log('导入成功', data);
 *     } catch (e) {
 *         showError('网络错误');
 *     }
 * };
 * ```
 *
 * ### 3. 混合模式（同一 hook 实例同时使用两种模式）
 * 声明式负责加载初始数据，`apiFetch` 负责后续操作（如增删改）。
 *
 * ```tsx
 * const { data: categories, loading, apiFetch } = useAppFetch<MaterialCategory[]>({
 *     appPath: '/api/v1/MaterialCategoryListRoute',
 *     init: { method: 'POST', body: '{}' },
 * });
 *
 * const handleDelete = async (id: string) => {
 *     const { resp } = await apiFetch('/api/v1/MaterialCategoryDeleteRoute', {
 *         method: 'POST',
 *         body: JSON.stringify({ id }),
 *     });
 *     if (!resp.ok) { reportHttpError(`删除失败: HTTP ${resp.status}`, resp); return; }
 *     refreshList();
 * };
 * ```
 *
 * ### 4. useEffect 内命令式调用（必须传 signal 以支持取消）
 * 在 useEffect 里直接调用 apiFetch 时，**必须**通过 `options.signal` 传入 AbortController 的
 * signal，并在 cleanup 中 abort。否则依赖变化时旧请求无法取消，会导致瞬发多次请求。
 * AbortError 不会触发 toast（apiFetch 内部已过滤）。
 *
 * ```tsx
 * const { apiFetch } = useAppFetch();
 * const abortRef = useRef<AbortController | null>(null);
 *
 * useEffect(() => {
 *     abortRef.current?.abort();
 *     const abort = new AbortController();
 *     abortRef.current = abort;
 *
 *     let cancelled = false;
 *     setLoading(true);
 *     apiFetch('/api/v1/SomeRoute', {
 *         method: 'POST',
 *         body: JSON.stringify({ id }),
 *     }, { signal: abort.signal })
 *         .then(({ data }) => { if (!cancelled) setResult(data); })
 *         .catch((err) => { if (!cancelled && err?.name !== 'AbortError') setError(true); })
 *         .finally(() => { if (!cancelled) setLoading(false); });
 *     return () => { cancelled = true; abort.abort(); };
 * }, [id]);
 * ```
 *
 * ## AI 使用建议
 * - 页面初次加载数据 → 声明式（传 `appPath`），用 `loading` / `error` 驱动 UI。
 * - 按钮点击、表单提交 → 命令式（`apiFetch`），调用方管理自己的 loading 状态。
 * - **useEffect 内调用 apiFetch → 必须传 `signal` 并在 cleanup 中 abort**（见模式 4）。
 * - 操作后需刷新列表 → 操作完成后再调用一次 `apiFetch` 获取列表，或触发页面刷新。
 * - 不需要声明式状态时，**不传 `appPath`** 以避免挂载时触发多余的请求和 re-render。
 * - 声明式模式中，`init` / `parseJson` / `timeout` 不在 deps 内，更改这些参数
 *   不会自动重新请求；若需动态变化，请改用命令式 `apiFetch`。
 */
export function useAppFetch<J = JsonValue>(param?: {
    /** 声明式自动请求的路径（相对于 API host）。不传则仅使用命令式 apiFetch。 */
    appPath?: string;
    /** 声明式请求的 fetch init（signal 由内部管理，勿传）。 */
    init?: Omit<RequestInit, "signal">;
    /** 是否自动解析响应体为 JSON（默认 true）。 */
    parseJson?: boolean;
    /** 允许非 2xx 响应不抛错，data 仍为 null（默认 false）。 */
    allowNot2XX?: boolean;
    /** 请求超时毫秒（默认 10000；0 表示不超时）。 */
    timeout?: number;
    typeCheck?: (data: unknown) => data is J;
}) {
    const { allowNot2XX = false } = param ?? {};

    const { appConfig, isStorageLoaded, setAppConfig } = useAppConfig();
    const isStorageLoadedRef = useRef(isStorageLoaded);
    isStorageLoadedRef.current = isStorageLoaded;
    // session_token 优先，fallback webserver_auth_token
    const effectiveToken = appConfig.session_token ?? appConfig.webserver_auth_token;
    const authTokenRef = useRef(effectiveToken);
    authTokenRef.current = effectiveToken;
    const [data, setData] = useState<J | null>(null);
    const [loading, setLoading] = useState<boolean>(() =>
        param?.appPath != null
    );
    const [error, setError] = useState<Error | null>(null);
    const [response, setResponse] = useState<Response | null>(null);

    const authToken = effectiveToken;
    const host = getAppHost(
        appConfig.webserver_domain,
        appConfig.webserver_port,
        appConfig.webserver_schema,
    );
    // storage 未加载完或 url 为 null 时跳过声明式请求
    const url = param?.appPath != null && isStorageLoaded
        ? `${host}${param.appPath}`
        : null;

    // ── 声明式自动请求 ────────────────────────────────────────────────────────
    // deps 只跟踪 url 和 authToken：这两者是"应该重新获取数据"的信号。
    // init / parseJson / timeout 通常是字面量，不加入 deps 以避免每次 render 重新请求。
    // 若确实需要响应这些参数的变化，请改用命令式 apiFetch。
    useEffect(() => {
        const {
            appPath,
            init,
            parseJson = true,
            timeout = 900_000,
            typeCheck: jsonTypeCheck,
        } = param ?? {};

        if (!url) return;

        let cancelled = false;
        const { abort, clearTimer } = makeAbortWithTimeout(timeout);

        setLoading(true);
        setError(null);
        setResponse(null);
        setData(null);

        (async () => {
            try {
                console.debug(
                    `[useAppFetch] ${new Date().toISOString()} : ${appPath}`,
                );
                const resp = await fetchWithAuth(
                    url,
                    init,
                    authToken,
                    abort.signal,
                );
                clearTimer();
                if (cancelled) return;

                setResponse(resp);
                // 401 全局处理
                if (resp.status === 401 && !_redirectingToLogin) {
                    if (appConfig.session_token) {
                        // Session 用户：清除 + 跳转
                        _redirectingToLogin = true;
                        setAppConfig({ session_token: null, user_role: null, user_display_name: null, user_permissions: null });
                        import("react-toastify").then(m => m.toast.warn("登录已过期，请重新登录")).catch(() => {});
                        window.location.href = "/login";
                        return;
                    }
                    if (appConfig.webserver_auth_token) {
                        // 游客：交互式 toast，不跳转不清除
                        import("~/util/guest_401_toast").then(m => m.showGuest401Toast()).catch(() => {});
                    }
                }
                if (!resp.ok && !allowNot2XX) {
                    reportHttpError(`HTTP 请求失败 : ${appPath}`, resp);
                    try { checkFixBugAdvice(json_parse(await resp.text())); } catch {}
                    throw new Error(`HTTP ${resp.status}`);
                }
                if (parseJson) {
                    const parsed = jsonTypeCheck
                        ? json_parse(await resp.text(), jsonTypeCheck)
                        : json_parse<J>(await resp.text());
                    checkFixBugAdvice(parsed);
                    setData(parsed);
                }
                setError(null);
            } catch (err) {
                // 组件卸载或外部取消触发的 AbortError 属于正常 cleanup，不写入错误状态
                if (cancelled) return;
                if (err instanceof DOMException && err.name === "AbortError") {
                    return;
                }
                print_error({
                    reason: `HTTP 请求失败 : ${appPath}  ---  ${err}`,
                    err,
                    variables: { appPath, param },
                });
                setError(err instanceof Error ? err : new Error(String(err)));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
            clearTimer();
            abort.abort();
        };
    }, [url, authToken]); // eslint-disable-line react-hooks/exhaustive-deps

    // ── 命令式 fetch ──────────────────────────────────────────────────────────
    // useCallback 保证引用稳定，host / authToken 变化时才重建，
    // 可安全放入其他 hook 的 deps 数组。
    const apiFetch = useCallback(
        async <J2 = JsonValue>(
            path: string,
            init?: RequestInit,
            options?: {
                parseJson?: boolean;
                timeout?: number;
                silent?: boolean;
                signal?: AbortSignal;
                typeCheck?: (data: unknown) => data is J2;
            },
        ): Promise<{ resp: Response; data: J2 | null }> => {
            const {
                parseJson = true,
                timeout = 900_000,
                silent = false,
                signal: externalSignal,
                typeCheck,
            } = options ?? {};
            // 等待 storage 加载完成，确保 authToken 已从 localStorage 恢复
            await new Promise<void>((resolve) => {
                if (isStorageLoadedRef.current) {
                    resolve();
                    return;
                }
                const id = setInterval(() => {
                    if (isStorageLoadedRef.current) {
                        clearInterval(id);
                        resolve();
                    }
                }, 10);
            });
            const { abort, clearTimer } = makeAbortWithTimeout(timeout);
            const signal = externalSignal
                ? AbortSignal.any([abort.signal, externalSignal])
                : abort.signal;
            try {
                const resp = await fetchWithAuth(
                    `${host}${path}`,
                    init,
                    authTokenRef.current,
                    signal,
                );
                clearTimer();
                // 401 全局处理
                if (resp.status === 401 && !_redirectingToLogin) {
                    if (authTokenRef.current === appConfig.session_token && appConfig.session_token) {
                        // Session 用户：清除 + 跳转
                        _redirectingToLogin = true;
                        setAppConfig({ session_token: null, user_role: null, user_display_name: null, user_permissions: null });
                        import("react-toastify").then(m => m.toast.warn("登录已过期，请重新登录")).catch(() => {});
                        window.location.href = "/login";
                        throw new Error("HTTP 401");
                    }
                    if (appConfig.webserver_auth_token && !appConfig.session_token) {
                        // 游客：交互式 toast，不跳转不清除
                        import("~/util/guest_401_toast").then(m => m.showGuest401Toast()).catch(() => {});
                    }
                }
                if (!resp.ok && !allowNot2XX) {
                    if (!silent) {
                        reportHttpError(`HTTP 请求失败 : ${path}`, resp);
                    }
                    try { checkFixBugAdvice(json_parse(await resp.text())); } catch {}
                    throw new Error(`HTTP ${resp.status}`);
                }
                if (parseJson) {
                    const parsed = typeCheck
                        ? json_parse(await resp.text(), typeCheck)
                        : json_parse<J2>(await resp.text());
                    checkFixBugAdvice(parsed);
                    return { resp, data: parsed };
                }
                return { resp, data: null };
            } catch (err) {
                clearTimer();
                if (
                    !(err instanceof DOMException &&
                        err.name === "AbortError") && !silent
                ) {
                    print_error({
                        reason: `HTTP 请求失败 : ${path}  ---  ${err}`,
                        err,
                        variables: { param, host, path },
                    });
                }
                throw err;
            }
        },
        [host, authToken, appConfig.session_token],
    );

    return { data, loading, error, response, apiFetch };
}
