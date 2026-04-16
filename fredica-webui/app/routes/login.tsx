import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT } from "~/util/app_fetch";

interface InstanceStatusResponse { initialized: boolean; guest_token_configured?: boolean; }
interface LoginResponse { success?: boolean; token?: string; user?: { display_name: string; role: string; permissions: string }; error?: string; }

function getHost(appConfig: { webserver_schema: string | null; webserver_domain: string | null; webserver_port: string | null }) {
    const s = appConfig.webserver_schema ?? "http";
    const d = appConfig.webserver_domain ?? "localhost";
    const p = appConfig.webserver_port ?? DEFAULT_SERVER_PORT;
    return `${s}://${d}:${p}`;
}

export default function LoginPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const { appConfig, setAppConfig, isStorageLoaded } = useAppConfig();

    // 密码登录
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [loginError, setLoginError] = useState<string | null>(null);
    const [loginLoading, setLoginLoading] = useState(false);

    // 游客登录
    const [guestToken, setGuestToken] = useState("");
    const [guestError, setGuestError] = useState<string | null>(null);
    const [guestLoading, setGuestLoading] = useState(false);
    const [guestTokenConfigured, setGuestTokenConfigured] = useState<boolean | null>(null);

    // URL 参数中的连接信息（来自邀请链接跳转）
    const urlSchema = searchParams.get("webserver_schema");
    const urlDomain = searchParams.get("webserver_domain");
    const urlPort = searchParams.get("webserver_port");
    const urlAuthToken = searchParams.get("webserver_auth_token");

    // 从 URL 参数写入连接信息 + 自动填入游客令牌
    useEffect(() => {
        if (!isStorageLoaded) return;
        if (urlSchema || urlDomain || urlPort) {
            setAppConfig({
                ...(urlSchema ? { webserver_schema: urlSchema as "http" | "https" } : {}),
                ...(urlDomain ? { webserver_domain: urlDomain } : {}),
                ...(urlPort ? { webserver_port: urlPort } : {}),
            });
        }
        if (urlAuthToken) {
            setGuestToken(urlAuthToken);
        }
    }, [isStorageLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

    // 挂载时检查实例状态 + 已有 session
    useEffect(() => {
        if (!isStorageLoaded) return;
        const host = getHost(appConfig);

        (async () => {
            try {
                const resp = await fetch(`${host}/api/v1/InstanceStatusRoute`);
                if (resp.ok) {
                    const data: InstanceStatusResponse = await resp.json();
                    if (!data.initialized) { navigate("/setup", { replace: true }); return; }
                    setGuestTokenConfigured(data.guest_token_configured ?? true);
                }
            } catch { /* 服务器未连接，留在登录页 */ }

            // 已有 session_token，验证是否有效
            if (appConfig.session_token) {
                try {
                    const resp = await fetch(`${host}/api/v1/AuthMeRoute`, {
                        headers: { Authorization: `Bearer ${appConfig.session_token}` },
                    });
                    if (resp.ok) { navigate("/", { replace: true }); return; }
                } catch { /* session 无效，留在登录页 */ }
            }
        })();
    }, [isStorageLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

    const handlePasswordLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!username.trim() || !password) return;
        setLoginLoading(true);
        setLoginError(null);
        try {
            const host = getHost(appConfig);
            const resp = await fetch(`${host}/api/v1/AuthLoginRoute`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username: username.trim(), password }),
            });
            const data: LoginResponse = await resp.json();
            if (!resp.ok || data.error) {
                setLoginError(data.error ?? "登录失败");
                return;
            }
            if (data.token && data.user) {
                const permissions = data.user.permissions
                    ? data.user.permissions.split(",").map(s => s.trim()).filter(Boolean)
                    : [];
                setAppConfig({
                    session_token: data.token,
                    user_role: data.user.role as "guest" | "tenant" | "root",
                    user_display_name: data.user.display_name,
                    user_permissions: permissions,
                });
                navigate("/", { replace: true });
            }
        } catch (err) {
            setLoginError("网络错误，请检查服务器连接");
        } finally {
            setLoginLoading(false);
        }
    };

    const handleGuestLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!guestToken.trim()) return;
        setGuestLoading(true);
        setGuestError(null);
        try {
            const host = getHost(appConfig);
            const resp = await fetch(`${host}/api/v1/AuthGuestValidateRoute`, {
                headers: { Authorization: `Bearer ${guestToken.trim()}` },
            });
            if (!resp.ok) { setGuestError("令牌无效"); return; }
            setAppConfig({ webserver_auth_token: guestToken.trim(), session_token: null, user_role: "guest", user_display_name: null, user_permissions: null });
            navigate("/", { replace: true });
        } catch {
            setGuestError("网络错误，请检查服务器连接");
        } finally {
            setGuestLoading(false);
        }
    };

    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="w-full max-w-sm space-y-4">
                {/* 密码登录 */}
                <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
                    <h1 className="mb-6 text-lg font-semibold text-slate-800">登录</h1>
                    <form onSubmit={handlePasswordLogin} className="flex flex-col gap-4">
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">用户名</label>
                            <input
                                type="text"
                                value={username}
                                onChange={e => setUsername(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="用户名"
                                autoComplete="username"
                                required
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">密码</label>
                            <input
                                type="password"
                                value={password}
                                onChange={e => setPassword(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="密码"
                                autoComplete="current-password"
                                required
                            />
                        </div>
                        {loginError && <p className="text-sm text-red-500">{loginError}</p>}
                        <button
                            type="submit"
                            disabled={loginLoading}
                            className="mt-2 rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-50 cursor-pointer"
                        >
                            {loginLoading ? "登录中..." : "登录"}
                        </button>
                    </form>
                </div>

                {/* 游客登录 */}
                <div className={`rounded-2xl border bg-white p-8 shadow-sm ${urlAuthToken ? "border-blue-200" : "border-slate-200"}`}>
                    <h2 className="mb-4 text-sm font-semibold text-slate-600">游客访问</h2>
                    {urlAuthToken && (
                        <p className="mb-3 text-xs text-blue-500">已从邀请链接自动填入访问令牌，点击下方按钮即可以游客身份访问</p>
                    )}
                    {guestTokenConfigured === false ? (
                        <p className="text-sm text-slate-400">未设置默认游客令牌，游客登录不可用</p>
                    ) : (
                    <form onSubmit={handleGuestLogin} className="flex flex-col gap-4">
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">访问令牌</label>
                            <input
                                type="password"
                                value={guestToken}
                                onChange={e => setGuestToken(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="访问令牌"
                                required
                            />
                        </div>
                        {guestError && <p className="text-sm text-red-500">{guestError}</p>}
                        <button
                            type="submit"
                            disabled={guestLoading}
                            className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50 cursor-pointer"
                        >
                            {guestLoading ? "验证中..." : "以游客身份访问"}
                        </button>
                    </form>
                    )}
                </div>
            </div>
        </div>
    );
}
