import { useCallback, useEffect, useState } from "react";
import { useAppConfig } from "~/context/appConfig";

export const DEFAULT_SERVER_PORT = "7631";

function getAppHost(domain?: string | null, port?: string | null, schema?: string | null) {
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
 * 统一的 API 请求 hook。
 *
 * - 自动在请求头中注入 `Authorization: Bearer <webserver_auth_token>`。
 * - 提供 `appPath` 时执行**声明式**自动请求（挂载时触发，appPath/token/host 变化时重新请求），
 *   并将结果写入 `{ data, loading, error, response }` 状态。
 * - 始终返回 `apiFetch`：带 auth header 和超时的**命令式** fetch 函数，
 *   适合在事件处理器中调用，不影响声明式状态。
 */
export function useAppFetch<J = unknown>(param?: {
    /** 声明式自动请求的路径（相对于 API host）。不传则仅使用命令式 apiFetch。 */
    appPath?: string;
    /** 声明式请求的 fetch init（signal 由内部管理，勿传）。 */
    init?: Omit<RequestInit, "signal">;
    /** 是否自动解析响应体为 JSON（默认 true）。 */
    parseJson?: boolean;
    /** 允许非 2xx 响应不抛错（默认 false）。 */
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
    const url = param?.appPath != null ? `${host}${param.appPath}` : null;

    /** 命令式 fetch：自动注入 auth header，默认 10 s 超时，返回原始 Response。 */
    const apiFetch = useCallback(
        async (appPath: string, init?: Omit<RequestInit, "signal">, timeout = 10_000) => {
            const abort = new AbortController();
            const timer =
                timeout > 0
                    ? setTimeout(
                          () => abort.abort(new DOMException("Request timed out", "TimeoutError")),
                          timeout,
                      )
                    : null;
            try {
                return await fetch(`${host}${appPath}`, {
                    ...init,
                    headers: { ...buildAuthHeaders(authToken), ...init?.headers },
                    signal: abort.signal,
                });
            } finally {
                if (timer != null) clearTimeout(timer);
            }
        },
        [host, authToken],
    );

    // 声明式自动请求
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
        const abort = new AbortController();
        const timer =
            timeout > 0
                ? setTimeout(
                      () => abort.abort(new DOMException("Request timed out", "TimeoutError")),
                      timeout,
                  )
                : null;

        setLoading(true);
        setError(null);
        setResponse(null);
        setData(null);

        (async () => {
            try {
                console.debug(`[useAppFetch] ${new Date().toISOString()} : ${appPath}`);
                const resp = await fetch(url, {
                    ...init,
                    headers: { ...buildAuthHeaders(authToken), ...init?.headers },
                    signal: abort.signal,
                });
                if (timer != null) clearTimeout(timer);
                if (cancelled) return;

                setResponse(resp);
                if (!resp.ok && !allowNot2XX) {
                    throw new Error(`HTTP ${resp.status}`);
                }
                if (parseJson) {
                    setData((await resp.json()) as J);
                }
                setError(null);
            } catch (err) {
                // 正常 cleanup 触发的 AbortError 不写入错误状态
                if (cancelled) return;
                setError(err instanceof Error ? err : new Error(String(err)));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
            if (timer != null) clearTimeout(timer);
            abort.abort();
        };
    }, [url, authToken]);

    return { data, loading, error, response, apiFetch };
}
