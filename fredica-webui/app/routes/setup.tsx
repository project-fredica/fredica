import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import zxcvbn from "zxcvbn";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT } from "~/util/app_fetch";
import { callBridge, BridgeUnavailableError } from "~/util/bridge";
import { json_parse } from "~/util/json";

interface InstanceStatusResponse { initialized: boolean; }
interface InstanceInitResponse { success?: boolean; token?: string; user?: { display_name: string; role: string; permissions: string }; error?: string; }

function getHost(appConfig: { webserver_schema: string | null; webserver_domain: string | null; webserver_port: string | null }) {
    const s = appConfig.webserver_schema ?? "http";
    const d = appConfig.webserver_domain ?? "localhost";
    const p = appConfig.webserver_port ?? DEFAULT_SERVER_PORT;
    return `${s}://${d}:${p}`;
}

const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_-]{1,31}$/;

function strengthLabel(score: number): { text: string; color: string } {
    switch (score) {
        case 0: return { text: "非常弱", color: "text-red-500" };
        case 1: return { text: "弱", color: "text-red-400" };
        case 2: return { text: "一般", color: "text-yellow-500" };
        case 3: return { text: "强", color: "text-green-500" };
        case 4: return { text: "非常强", color: "text-green-600" };
        default: return { text: "", color: "" };
    }
}

export default function SetupPage() {
    const navigate = useNavigate();
    const { appConfig, setAppConfig, isStorageLoaded } = useAppConfig();

    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    // 挂载时检查实例状态：已初始化则跳转登录页
    useEffect(() => {
        if (!isStorageLoaded) return;
        const host = getHost(appConfig);
        (async () => {
            try {
                const resp = await fetch(`${host}/api/v1/InstanceStatusRoute`);
                if (resp.ok) {
                    const data: InstanceStatusResponse = await resp.json();
                    if (data.initialized) { navigate("/login", { replace: true }); return; }
                }
            } catch { /* 服务器未连接，留在初始化页 */ }
        })();
    }, [isStorageLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

    // 校验
    const usernameError = username && !USERNAME_RE.test(username)
        ? "用户名须以字母开头，仅含字母/数字/下划线/连字符，长度 2-32"
        : null;
    const passwordTooShort = password && password.length < 8;
    const passwordTooLong = password && password.length > 128;
    const passwordLengthError = passwordTooShort ? "密码至少 8 位" : passwordTooLong ? "密码最多 128 位" : null;
    const confirmError = confirmPassword && password !== confirmPassword ? "两次密码不一致" : null;
    const strengthResult = password.length >= 8 ? zxcvbn(password) : null;
    const strengthScore = strengthResult?.score ?? -1;
    const strengthWeak = password.length >= 8 && strengthScore < 3;

    const canSubmit = !loading
        && USERNAME_RE.test(username)
        && password.length >= 8 && password.length <= 128
        && password === confirmPassword
        && !strengthWeak;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!canSubmit) return;
        setLoading(true);
        setError(null);
        try {
            const raw = await callBridge("instance_init", JSON.stringify({ username: username.trim(), password }));
            const data = json_parse<InstanceInitResponse>(raw);
            if (data.error) {
                setError(data.error);
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
                navigate("/app-desktop-home", { replace: true });
            }
        } catch (e) {
            if (e instanceof BridgeUnavailableError) {
                setError("请让服主在桌面端界面中完成初始化");
            } else {
                setError("初始化失败，请重试");
            }
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="w-full max-w-sm">
                <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
                    <h1 className="mb-2 text-lg font-semibold text-slate-800">初始化实例</h1>
                    <p className="mb-6 text-sm text-slate-500">创建管理员账号以完成首次启动配置</p>
                    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
                        {/* 用户名 */}
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">用户名</label>
                            <input
                                type="text"
                                value={username}
                                onChange={e => setUsername(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="admin"
                                autoComplete="username"
                                required
                            />
                            {usernameError && <p className="text-xs text-red-500">{usernameError}</p>}
                        </div>

                        {/* 密码 */}
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">密码</label>
                            <input
                                type="password"
                                value={password}
                                onChange={e => setPassword(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="至少 8 位"
                                autoComplete="new-password"
                                required
                            />
                            {passwordLengthError && <p className="text-xs text-red-500">{passwordLengthError}</p>}
                            {strengthResult && (
                                <p className={`text-xs ${strengthLabel(strengthScore).color}`}>
                                    密码强度：{strengthLabel(strengthScore).text}
                                    {strengthWeak && " （建议使用更复杂的密码）"}
                                </p>
                            )}
                        </div>

                        {/* 确认密码 */}
                        <div className="flex flex-col gap-1">
                            <label className="text-sm text-slate-600">确认密码</label>
                            <input
                                type="password"
                                value={confirmPassword}
                                onChange={e => setConfirmPassword(e.target.value)}
                                className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                                placeholder="再次输入密码"
                                autoComplete="new-password"
                                required
                            />
                            {confirmError && <p className="text-xs text-red-500">{confirmError}</p>}
                        </div>

                        {error && <p className="text-sm text-red-500">{error}</p>}

                        <button
                            type="submit"
                            disabled={!canSubmit}
                            className="mt-2 rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-50 cursor-pointer disabled:cursor-not-allowed"
                        >
                            {loading ? "初始化中..." : "完成初始化"}
                        </button>
                    </form>
                </div>
            </div>
        </div>
    );
}
