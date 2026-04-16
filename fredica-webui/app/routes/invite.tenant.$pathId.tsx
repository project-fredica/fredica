import { useEffect, useState } from "react";
import { useParams, useSearchParams, useNavigate } from "react-router";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT } from "~/util/app_fetch";

type PageStatus = "loading" | "usable" | "unusable" | "network_error";

const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_-]{1,31}$/;

function getHostFromParams(sp: URLSearchParams) {
    const s = sp.get("webserver_schema") ?? "http";
    const d = sp.get("webserver_domain") ?? "localhost";
    const p = sp.get("webserver_port") ?? DEFAULT_SERVER_PORT;
    return { schema: s, domain: d, port: p, host: `${s}://${d}:${p}` };
}

const REASON_LABEL: Record<string, string> = {
    disabled: "该邀请链接已被禁用",
    expired: "该邀请链接已过期",
    full: "该邀请链接已达到使用上限",
};

export default function TenantInviteRegisterPage() {
    const { pathId } = useParams<{ pathId: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const { setAppConfig, isStorageLoaded } = useAppConfig();

    // page state
    const [pageStatus, setPageStatus] = useState<PageStatus>("loading");
    const [linkLabel, setLinkLabel] = useState<string | null>(null);
    const [unusableReason, setUnusableReason] = useState<string | null>(null);
    const [networkError, setNetworkError] = useState<string | null>(null);

    // form state
    const [username, setUsername] = useState("");
    const [displayName, setDisplayName] = useState("");
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [submitError, setSubmitError] = useState<string | null>(null);
    const [submitLoading, setSubmitLoading] = useState(false);

    // host info (resolved once)
    const [hostInfo, setHostInfo] = useState<{ schema: string; domain: string; port: string; host: string } | null>(null);

    // check link usability on mount
    useEffect(() => {
        if (!isStorageLoaded || !pathId) return;

        const info = getHostFromParams(searchParams);
        setHostInfo(info);

        (async () => {
            try {
                const resp = await fetch(
                    `${info.host}/api/v1/TenantInviteLandingRoute?path_id=${encodeURIComponent(pathId)}`,
                );
                const data = await resp.json();
                if (!resp.ok || data.error) {
                    setNetworkError(data.error ?? `HTTP ${resp.status}`);
                    setPageStatus("network_error");
                    return;
                }
                if (data.usable === false) {
                    setUnusableReason(data.reason ?? "unknown");
                    setLinkLabel(data.label ?? null);
                    setPageStatus("unusable");
                    return;
                }
                setLinkLabel(data.label ?? null);
                setPageStatus("usable");
            } catch {
                setNetworkError("无法连接到服务器");
                setPageStatus("network_error");
            }
        })();
    }, [isStorageLoaded, pathId]); // eslint-disable-line react-hooks/exhaustive-deps

    // validation
    const usernameError = username && !USERNAME_RE.test(username)
        ? "用户名须以字母开头，仅含字母/数字/下划线/连字符，2-32 位"
        : null;
    const passwordError = password && (password.length < 8 || password.length > 128)
        ? "密码长度须为 8-128 位"
        : null;
    const confirmError = confirm && password !== confirm ? "两次密码不一致" : null;
    const canSubmit = username && !usernameError && password && !passwordError && confirm && !confirmError;

    const handleRegister = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!canSubmit || !hostInfo || !pathId) return;
        setSubmitLoading(true);
        setSubmitError(null);
        try {
            const resp = await fetch(`${hostInfo.host}/api/v1/TenantInviteRegisterRoute`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    path_id: pathId,
                    username: username.trim(),
                    password,
                    display_name: displayName.trim() || undefined,
                }),
            });
            const data = await resp.json();
            if (!resp.ok || data.error) {
                setSubmitError(data.error ?? `注册失败 (HTTP ${resp.status})`);
                return;
            }
            // 注册成功：写入连接信息 + session
            if (data.token && data.user) {
                const permissions = data.user.permissions
                    ? data.user.permissions.split(",").map((s: string) => s.trim()).filter(Boolean)
                    : [];
                setAppConfig({
                    webserver_schema: hostInfo.schema as "http" | "https",
                    webserver_domain: hostInfo.domain,
                    webserver_port: hostInfo.port,
                    session_token: data.token,
                    user_role: data.user.role as "guest" | "tenant" | "root",
                    user_display_name: data.user.display_name,
                    user_permissions: permissions,
                    webserver_auth_token: null,
                });
                navigate("/", { replace: true });
            }
        } catch {
            setSubmitError("网络错误，请检查服务器连接");
        } finally {
            setSubmitLoading(false);
        }
    };

    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="w-full max-w-sm">
                {pageStatus === "loading" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <p className="text-sm text-slate-500">正在验证邀请链接...</p>
                    </div>
                )}

                {pageStatus === "network_error" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <div className="mb-4 flex justify-center">
                            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-red-50">
                                <svg className="h-6 w-6 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                                </svg>
                            </div>
                        </div>
                        <h1 className="mb-2 text-lg font-semibold text-slate-800">链接无效</h1>
                        <p className="text-sm text-red-500">{networkError}</p>
                    </div>
                )}

                {pageStatus === "unusable" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <div className="mb-4 flex justify-center">
                            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-amber-50">
                                <svg className="h-6 w-6 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                </svg>
                            </div>
                        </div>
                        <h1 className="mb-2 text-lg font-semibold text-slate-800">邀请链接不可用</h1>
                        {linkLabel && <p className="mb-1 text-sm text-slate-500">{linkLabel}</p>}
                        <p className="text-sm text-amber-600">
                            {REASON_LABEL[unusableReason ?? ""] ?? "该邀请链接不可用"}
                        </p>
                    </div>
                )}

                {pageStatus === "usable" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
                        <h1 className="mb-1 text-lg font-semibold text-slate-800">注册账户</h1>
                        {linkLabel && (
                            <p className="mb-4 text-sm text-slate-400">{linkLabel}</p>
                        )}
                        {!linkLabel && <div className="mb-4" />}

                        <form onSubmit={handleRegister} className="flex flex-col gap-3">
                            <div className="flex flex-col gap-1">
                                <label className="text-xs text-slate-500">用户名</label>
                                <input
                                    type="text"
                                    value={username}
                                    onChange={e => setUsername(e.target.value)}
                                    placeholder="字母开头，2-32 位"
                                    className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400"
                                    autoComplete="username"
                                />
                                {usernameError && <p className="text-xs text-red-500">{usernameError}</p>}
                            </div>

                            <div className="flex flex-col gap-1">
                                <label className="text-xs text-slate-500">显示名（可选）</label>
                                <input
                                    type="text"
                                    value={displayName}
                                    onChange={e => setDisplayName(e.target.value)}
                                    placeholder={username || "显示名"}
                                    className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400"
                                />
                            </div>

                            <div className="flex flex-col gap-1">
                                <label className="text-xs text-slate-500">密码</label>
                                <input
                                    type="password"
                                    value={password}
                                    onChange={e => setPassword(e.target.value)}
                                    placeholder="至少 8 位"
                                    className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400"
                                    autoComplete="new-password"
                                />
                                {passwordError && <p className="text-xs text-red-500">{passwordError}</p>}
                            </div>

                            <div className="flex flex-col gap-1">
                                <label className="text-xs text-slate-500">确认密码</label>
                                <input
                                    type="password"
                                    value={confirm}
                                    onChange={e => setConfirm(e.target.value)}
                                    placeholder="再次输入密码"
                                    className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400"
                                    autoComplete="new-password"
                                />
                                {confirmError && <p className="text-xs text-red-500">{confirmError}</p>}
                            </div>

                            {submitError && <p className="text-xs text-red-500">{submitError}</p>}

                            <button
                                type="submit"
                                disabled={!canSubmit || submitLoading}
                                className="mt-1 rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
                            >
                                {submitLoading ? "注册中..." : "注册"}
                            </button>
                        </form>

                        <div className="mt-4 text-center">
                            <button
                                onClick={() => {
                                    const loginParams = new URLSearchParams();
                                    if (hostInfo) {
                                        loginParams.set("webserver_schema", hostInfo.schema);
                                        loginParams.set("webserver_domain", hostInfo.domain);
                                        loginParams.set("webserver_port", hostInfo.port);
                                    }
                                    const authToken = searchParams.get("webserver_auth_token");
                                    if (authToken) loginParams.set("webserver_auth_token", authToken);
                                    const qs = loginParams.toString();
                                    navigate(`/login${qs ? `?${qs}` : ""}`, { replace: true });
                                }}
                                className="text-xs text-slate-400 hover:text-slate-600 cursor-pointer"
                            >
                                已有账户？去登录
                            </button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
