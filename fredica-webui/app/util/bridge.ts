/**
 * kmpJsBridge 安全封装。
 *
 * 问题根源：WebView 注入 window.kmpJsBridge 对象后，
 * 其内部 postMessage 通道需要额外数百毫秒才就绪。
 * 在此期间调用 callNative 会同步抛出
 * "window.kmpJsBridge.postMessage is not a function"。
 *
 * 解决方案：callBridge() 内置启动期重试，对所有调用方透明，
 * 调用方无需自行添加 try-catch 或重试逻辑。
 */

/** bridge 对象不存在（非 WebView 环境，如普通浏览器）时抛出此错误。 */
export class BridgeUnavailableError extends Error {
    constructor() {
        super("kmpJsBridge 不可用（非 WebView 环境）");
        this.name = "BridgeUnavailableError";
    }
}

/**
 * 安全调用 kmpJsBridge.callNative，返回 Promise<string>。
 *
 * - bridge 不可用（浏览器环境）→ 抛出 BridgeUnavailableError
 * - callNative 同步抛出（postMessage 通道未就绪）→ 自动重试，最多 maxRetries 次
 * - 超过最大重试次数 → 抛出最后一次错误
 *
 * @param method  bridge 方法名
 * @param params  JSON 字符串参数，默认 "{}"
 * @param options maxRetries（默认 5）/ retryDelayMs（默认 300ms）
 */
export async function callBridge(
    method: string,
    params: string = "{}",
    { maxRetries = 5, retryDelayMs = 300 }: { maxRetries?: number; retryDelayMs?: number } = {},
): Promise<string> {
    const bridge = typeof window !== "undefined" ? window.kmpJsBridge : undefined;
    if (!bridge) {
        console.debug(`[callBridge] bridge unavailable (non-WebView env), method=${method}`);
        throw new BridgeUnavailableError();
    }

    let lastError: unknown;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        if (attempt > 0) {
            await new Promise<void>(r => setTimeout(r, retryDelayMs));
        }
        try {
            return await new Promise<string>((resolve, reject) => {
                try {
                    bridge.callNative(method, params, resolve);
                } catch (e) {
                    reject(e); // 同步抛出（bridge 未就绪）→ 触发重试
                }
            });
        } catch (e) {
            lastError = e;
        }
    }
    throw lastError;
}

/**
 * callBridge 的静默变体：bridge 不可用时返回 null，其他错误正常抛出。
 *
 * 适用于浏览器开发环境下可静默跳过的调用（如 useEffect 内的数据加载）。
 * 调用方只需 `if (!raw) return` 即可，无需 catch BridgeUnavailableError。
 */
export async function callBridgeOrNull(
    method: string,
    params: string = "{}",
    options?: { maxRetries?: number; retryDelayMs?: number },
): Promise<string | null> {
    try {
        return await callBridge(method, params, options ?? {});
    } catch (e) {
        if (e instanceof BridgeUnavailableError) return null;
        throw e;
    }
}

/**
 * 打开外部链接。
 * - WebView 环境（kmpJsBridge 存在）：调用 open_browser bridge，由原生系统浏览器打开。
 * - 普通浏览器环境：直接 window.open。
 */
export function openExternalUrl(url: string) {
    if (typeof window === "undefined") return;
    if (window.kmpJsBridge) {
        callBridge("open_browser", JSON.stringify({ url, addServerInfoParam: false })).catch(() => {});
    } else {
        window.open(url, "_blank", "noopener,noreferrer");
    }
}
