import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { callBridge, BridgeUnavailableError } from "~/util/bridge";
import type { Route } from "./+types/app-desktop-home";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "Fredica 桌面端" },
        { name: "description", content: "Fredica 桌面端 App 内部主页。" },
    ];
}

export default function Component({
    params,
}: Route.ComponentProps) {
    const navigate = useNavigate();
    // null = 检测中，true = 已失效，false = 正常或未配置
    const [credExpired, setCredExpired] = useState<boolean | null>(null);

    useEffect(() => {
        // App 启动时通过 kmpJsBridge 检测 B 站账号登录态，
        // Python 服务启动后可能需要数秒才就绪，故失败时每隔 5s 最多重试 3 次。
        //
        // 使用 bridge 而非 HTTP API，原因：
        //   1. 凭据检测涉及用户敏感信息，不应通过 HTTP 路由暴露
        //   2. bridge 不需要 auth_token，避免了 WebView 启动时的 401 竞态

        const MAX_ATTEMPTS = 3;      // 最大尝试次数（含首次）
        const RETRY_DELAY_MS = 5000; // 重试间隔（ms）

        let cancelled = false;
        let attempt = 0;
        let retryTimer: ReturnType<typeof setTimeout> | null = null;

        const doCheck = async () => {
            attempt += 1;
            console.debug(`[app-desktop-home] B 站登录态检测 第 ${attempt}/${MAX_ATTEMPTS} 次`);
            try {
                // 传入空对象：使用 AppConfig 中已保存的凭据（首页检测不需要表单值）
                const raw = await callBridge("check_bilibili_credential");
                if (cancelled) return;
                const r = JSON.parse(raw) as { configured: boolean; valid: boolean; message: string };
                console.debug("[app-desktop-home] B 站登录态检测结果:", r);

                // message 以"检测失败"开头 → Python 服务尚未就绪，重试
                if (r.message?.startsWith("检测失败") && attempt < MAX_ATTEMPTS) {
                    console.debug(`[app-desktop-home] Python 服务未就绪，${RETRY_DELAY_MS / 1000}s 后重试…`);
                    retryTimer = setTimeout(doCheck, RETRY_DELAY_MS);
                    return;
                }

                // 已配置但无效 → 显示失效警告；未配置则不提示
                setCredExpired(r.configured && !r.valid);
            } catch (e) {
                if (cancelled) return;
                if (e instanceof BridgeUnavailableError) {
                    // 浏览器开发模式：bridge 不可用，跳过检测
                    console.debug("[app-desktop-home] kmpJsBridge 不可用，跳过登录态检测");
                    setCredExpired(false);
                    return;
                }
                console.debug(`[app-desktop-home] B 站登录态检测失败（第 ${attempt} 次）:`, e);
                if (attempt < MAX_ATTEMPTS) {
                    console.debug(`[app-desktop-home] ${RETRY_DELAY_MS / 1000}s 后重试…`);
                    retryTimer = setTimeout(doCheck, RETRY_DELAY_MS);
                } else {
                    console.debug("[app-desktop-home] 已达最大重试次数，放弃检测，不影响主功能");
                }
            }
        };

        doCheck();

        return () => {
            cancelled = true;
            if (retryTimer !== null) clearTimeout(retryTimer);
        };
    }, []); // bridge 在页面加载时已注入，不存在竞态，空依赖即可

    const handleOpenBrowser = async () => {
        try {
            await callBridge("open_browser", JSON.stringify({ url: "http://localhost:7630", addServerInfoParam: true }));
        } catch (e) {
            console.error("调用 open_browser bridge 失败:", e);
            alert("kmpJsBridge 不可用，请在桌面端环境中使用");
        }
    };

    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            alignItems: 'center',
            gap: '16px',
            height: '100vh',
            width: '100vw',
        }}>
            <button
                onClick={handleOpenBrowser}
                style={{
                    padding: '16px 32px',
                    fontSize: '18px',
                    fontWeight: 600,
                    color: '#fff',
                    backgroundColor: '#2563eb',
                    border: 'none',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
                    transition: 'all 0.3s ease',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#1d4ed8';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 6px 12px rgba(0, 0, 0, 0.15)';
                }}
                onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#2563eb';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 4px 6px rgba(0, 0, 0, 0.1)';
                }}
            >
                在浏览器中打开
            </button>
            <button
                onClick={() => navigate('/app-desktop-setting')}
                style={{
                    padding: '12px 28px',
                    fontSize: '16px',
                    fontWeight: 600,
                    color: '#374151',
                    backgroundColor: '#f9fafb',
                    border: '1px solid #d1d5db',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.08)',
                    transition: 'all 0.3s ease',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#f3f4f6';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 4px 8px rgba(0, 0, 0, 0.1)';
                }}
                onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#f9fafb';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 1px 3px rgba(0, 0, 0, 0.08)';
                }}
            >
                打开桌面服务器设置
            </button>
            {credExpired && (
                <p style={{ fontSize: '12px', color: '#b45309', margin: 0 }}>
                    ⚠&nbsp;&nbsp;设置的 B 站账号已失效
                </p>
            )}
        </div>
    )
}