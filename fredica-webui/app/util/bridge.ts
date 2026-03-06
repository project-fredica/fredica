export function getBridge() {
    return typeof window !== "undefined" ? window.kmpJsBridge : undefined;
}

/**
 * 打开外部链接。
 * - 在 WebView 环境（kmpJsBridge 存在）：调用 open_browser bridge，由原生系统浏览器打开。
 * - 在普通浏览器环境：直接 window.open。
 */
export function openExternalUrl(url: string) {
    const bridge = getBridge();
    if (bridge) {
        bridge.callNative("open_browser", JSON.stringify({ url, addServerInfoParam: false }), () => { });
    } else {
        window.open(url, "_blank", "noopener,noreferrer");
    }
}
