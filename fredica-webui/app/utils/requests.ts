import { useCallback, useEffect, useState } from "react";
import { useAppConfig } from "~/context/appConfig";

export const DEFAULT_SERVER_PORT = "7631";

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

function buildAuthHeaders(token?: string | null): Record<string, string> {
    if (!token) return {};
    return { Authorization: `Bearer ${token}` };
}

/**
 * 解析响应体为 JSON，并处理 Ktor 偶发的双重 stringify 问题
 * （服务端有时会将已序列化的 JSON 字符串再次序列化为字符串，需递归 parse）。
 */
async function parseJsonBody(resp: Response): Promise<unknown> {
    let res = await resp.json();
    while (typeof res === "string") {
        try {
            res = JSON.parse(res);
        } catch {
            break;
        }
    }
    return res;
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
        () => abort.abort(new DOMException("Request timed out", "TimeoutError")),
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
 * 统一的应用 API 请求 Hook，自动注入 auth header、管理超时、处理双重 JSON stringify。
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
 *     init: { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
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
 * ```tsx
 * const { apiFetch } = useAppFetch();
 *
 * const handleImport = async () => {
 *     try {
 *         const { resp, data } = await apiFetch('/api/v1/MaterialImportRoute', {
 *             method: 'POST',
 *             headers: { 'Content-Type': 'application/json' },
 *             body: JSON.stringify({ source_type: 'bilibili', videos }),
 *         });
 *         if (!resp.ok) { showError(`导入失败: HTTP ${resp.status}`); return; }
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
 *     if (resp.ok) refreshList();
 * };
 * ```
 *
 * ## AI 使用建议
 * - 页面初次加载数据 → 声明式（传 `appPath`），用 `loading` / `error` 驱动 UI。
 * - 按钮点击、表单提交 → 命令式（`apiFetch`），调用方管理自己的 loading 状态。
 * - 操作后需刷新列表 → 操作完成后再调用一次 `apiFetch` 获取列表，或触发页面刷新。
 * - 不需要声明式状态时，**不传 `appPath`** 以避免挂载时触发多余的请求和 re-render。
 * - 声明式模式中，`init` / `parseJson` / `timeout` 不在 deps 内，更改这些参数
 *   不会自动重新请求；若需动态变化，请改用命令式 `apiFetch`。
 */
export function useAppFetch<J = unknown>(param?: {
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
}) {
    const { appConfig } = useAppConfig();
    const [data, setData] = useState<J | null>(null);
    const [loading, setLoading] = useState<boolean>(() => param?.appPath != null);
    const [error, setError] = useState<Error | null>(null);
    const [response, setResponse] = useState<Response | null>(null);

    const authToken = appConfig.webserver_auth_token;
    const host = getAppHost(
        appConfig.webserver_domain,
        appConfig.webserver_port,
        appConfig.webserver_schema,
    );
    // url 为 null 时跳过声明式请求
    const url = param?.appPath != null ? `${host}${param.appPath}` : null;

    // ── 声明式自动请求 ────────────────────────────────────────────────────────
    // deps 只跟踪 url 和 authToken：这两者是"应该重新获取数据"的信号。
    // init / parseJson / timeout 通常是字面量，不加入 deps 以避免每次 render 重新请求。
    // 若确实需要响应这些参数的变化，请改用命令式 apiFetch。
    useEffect(() => {
        if (!url) return;
        const {
            appPath,
            init,
            parseJson = true,
            allowNot2XX = false,
            timeout = 10_000,
        } = param!;

        let cancelled = false;
        const { abort, clearTimer } = makeAbortWithTimeout(timeout);

        setLoading(true);
        setError(null);
        setResponse(null);
        setData(null);

        (async () => {
            try {
                console.debug(`[useAppFetch] ${new Date().toISOString()} : ${appPath}`);
                const resp = await fetchWithAuth(url, init, authToken, abort.signal);
                clearTimer();
                if (cancelled) return;

                setResponse(resp);
                if (!resp.ok && !allowNot2XX) {
                    throw new Error(`HTTP ${resp.status}`);
                }
                if (parseJson) {
                    setData((await parseJsonBody(resp)) as J);
                }
                setError(null);
            } catch (err) {
                // 组件卸载触发的 AbortError 属于正常 cleanup，不写入错误状态
                if (cancelled) return;
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
        async (
            path: string,
            init?: RequestInit,
            options?: { parseJson?: boolean; timeout?: number },
        ): Promise<{ resp: Response; data: unknown }> => {
            const { parseJson = true, timeout = 10_000 } = options ?? {};
            const { abort, clearTimer } = makeAbortWithTimeout(timeout);
            try {
                const resp = await fetchWithAuth(`${host}${path}`, init, authToken, abort.signal);
                clearTimer();
                if (parseJson) {
                    return { resp, data: await parseJsonBody(resp) };
                }
                return { resp, data: null };
            } catch (err) {
                clearTimer();
                throw err;
            }
        },
        [host, authToken],
    );

    return { data, loading, error, response, apiFetch };
}
