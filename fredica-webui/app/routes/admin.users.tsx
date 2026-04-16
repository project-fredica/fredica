import { useState, useEffect } from "react";
import { Plus, UserCheck, UserX, RefreshCw, Link as LinkIcon, Check } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { useAppConfig } from "~/context/appConfig";
import { reportHttpError } from "~/util/error_handler";

interface UserRecord {
    id: string;
    username: string;
    display_name: string;
    role: string;
    permissions: string;
    status: string;
    created_at: string;
    last_login_at: string | null;
}

const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_-]{1,31}$/;

// ── Create User Dialog ────────────────────────────────────────────────────────

interface CreateDialogProps {
    onClose: () => void;
    onCreated: () => void;
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}

function CreateUserDialog({ onClose, onCreated, apiFetch }: CreateDialogProps) {
    const [username, setUsername] = useState("");
    const [displayName, setDisplayName] = useState("");
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    const usernameError = username && !USERNAME_RE.test(username)
        ? "用户名须以字母开头，仅含字母/数字/下划线/连字符，2-32 位"
        : null;
    const passwordError = password && (password.length < 8 || password.length > 128)
        ? "密码长度须为 8-128 位"
        : null;
    const confirmError = confirm && password !== confirm ? "两次密码不一致" : null;

    const canSubmit = username && !usernameError && password && !passwordError && !confirmError;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!canSubmit) return;
        setLoading(true);
        setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/UserCreateRoute", {
                method: "POST",
                body: JSON.stringify({
                    username: username.trim(),
                    display_name: displayName.trim() || username.trim(),
                    password,
                }),
            });
            if (!resp.ok) {
                const msg = (data as any)?.error ?? `HTTP ${resp.status}`;
                setError(msg);
                return;
            }
            onCreated();
            onClose();
        } catch {
            setError("网络错误");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
                <h2 className="mb-4 text-base font-semibold text-slate-800">创建用户</h2>
                <form onSubmit={handleSubmit} className="flex flex-col gap-3">
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-slate-500">用户名</label>
                        <input
                            type="text"
                            value={username}
                            onChange={e => setUsername(e.target.value)}
                            placeholder="admin"
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400"
                            autoComplete="off"
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
                        />
                        {confirmError && <p className="text-xs text-red-500">{confirmError}</p>}
                    </div>
                    {error && <p className="text-xs text-red-500">{error}</p>}
                    <div className="mt-1 flex gap-2 justify-end">
                        <button
                            type="button"
                            onClick={onClose}
                            className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer"
                        >
                            取消
                        </button>
                        <button
                            type="submit"
                            disabled={!canSubmit || loading}
                            className="rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
                        >
                            {loading ? "创建中..." : "创建"}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}

// ── Confirm Dialog ────────────────────────────────────────────────────────────

interface ConfirmDialogProps {
    user: UserRecord;
    action: "disable" | "enable";
    onClose: () => void;
    onConfirm: () => void;
    loading: boolean;
}

function ConfirmDialog({ user, action, onClose, onConfirm, loading }: ConfirmDialogProps) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
                <h2 className="mb-2 text-base font-semibold text-slate-800">
                    {action === "disable" ? "禁用用户" : "启用用户"}
                </h2>
                <p className="mb-1 text-sm text-slate-600">
                    确认{action === "disable" ? "禁用" : "启用"}用户{" "}
                    <span className="font-medium text-slate-800">{user.display_name}</span>
                    {" "}（{user.username}）？
                </p>
                {action === "disable" && (
                    <p className="mb-4 text-xs text-amber-600">
                        禁用后该用户的所有登录会话将立即失效。
                    </p>
                )}
                {action === "enable" && <div className="mb-4" />}
                <div className="flex gap-2 justify-end">
                    <button
                        onClick={onClose}
                        className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer"
                    >
                        取消
                    </button>
                    <button
                        onClick={onConfirm}
                        disabled={loading}
                        className={`rounded-lg px-4 py-2 text-sm text-white disabled:opacity-40 cursor-pointer ${
                            action === "disable"
                                ? "bg-red-500 hover:bg-red-600"
                                : "bg-green-600 hover:bg-green-700"
                        }`}
                    >
                        {loading ? "处理中..." : action === "disable" ? "禁用" : "启用"}
                    </button>
                </div>
            </div>
        </div>
    );
}

// ── Status Badge ──────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: string }) {
    if (status === "active") {
        return (
            <span className="inline-flex items-center gap-1 rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700">
                <span className="h-1.5 w-1.5 rounded-full bg-green-500" />
                启用
            </span>
        );
    }
    return (
        <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-500">
            <span className="h-1.5 w-1.5 rounded-full bg-slate-400" />
            禁用
        </span>
    );
}

const ROLE_LABEL: Record<string, string> = { root: "管理员", tenant: "用户", guest: "游客" };

// ── Invite Link ───────────────────────────────────────────────────────────────

function buildInviteLink(appConfig: { webserver_schema: string | null; webserver_domain: string | null; webserver_port: string | null }) {
    const schema = appConfig.webserver_schema ?? "http";
    const domain = appConfig.webserver_domain ?? "localhost";
    const port = appConfig.webserver_port ?? "7631";
    const params = new URLSearchParams({
        webserver_schema: schema,
        webserver_domain: domain,
        webserver_port: port,
    });
    return `${schema}://${domain}:7630/login?${params.toString()}`;
}

function CopyInviteButton({ appConfig }: { appConfig: { webserver_schema: string | null; webserver_domain: string | null; webserver_port: string | null } }) {
    const [copied, setCopied] = useState(false);

    const handleCopy = async () => {
        const link = buildInviteLink(appConfig);
        try {
            await navigator.clipboard.writeText(link);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch { /* clipboard not available */ }
    };

    return (
        <button
            onClick={handleCopy}
            className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs text-blue-600 hover:bg-blue-50 cursor-pointer"
            title="复制邀请链接"
        >
            {copied ? <Check className="w-3.5 h-3.5" /> : <LinkIcon className="w-3.5 h-3.5" />}
            {copied ? "已复制" : "邀请"}
        </button>
    );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

function AdminUsersContent() {
    const { apiFetch } = useAppFetch();
    const { appConfig } = useAppConfig();
    const { data: users, loading, error, refresh } = useUserList(apiFetch);

    const [showCreate, setShowCreate] = useState(false);
    const [confirmTarget, setConfirmTarget] = useState<{ user: UserRecord; action: "disable" | "enable" } | null>(null);
    const [actionLoading, setActionLoading] = useState(false);

    const handleDisableEnable = async () => {
        if (!confirmTarget) return;
        setActionLoading(true);
        try {
            const route = confirmTarget.action === "disable"
                ? "/api/v1/UserDisableRoute"
                : "/api/v1/UserEnableRoute";
            const { resp, data } = await apiFetch(route, {
                method: "POST",
                body: JSON.stringify({ user_id: confirmTarget.user.id }),
            });
            if (!resp.ok) {
                reportHttpError(`操作失败: HTTP ${resp.status}`, resp);
                return;
            }
            setConfirmTarget(null);
            refresh();
        } catch {
            // network error
        } finally {
            setActionLoading(false);
        }
    };

    return (
        <div className="p-6 max-w-4xl mx-auto">
            <div className="mb-6 flex items-center justify-between">
                <h1 className="text-lg font-semibold text-slate-800">用户管理</h1>
                <div className="flex gap-2">
                    <button
                        onClick={refresh}
                        className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer"
                    >
                        <RefreshCw className="w-3.5 h-3.5" />
                        刷新
                    </button>
                    <button
                        onClick={() => setShowCreate(true)}
                        className="flex items-center gap-1.5 rounded-lg bg-slate-800 px-3 py-1.5 text-sm text-white hover:bg-slate-700 cursor-pointer"
                    >
                        <Plus className="w-3.5 h-3.5" />
                        创建用户
                    </button>
                </div>
            </div>

            {loading && <p className="text-sm text-slate-400">加载中...</p>}
            {error && <p className="text-sm text-red-500">加载失败：{error}</p>}

            {!loading && !error && users && (
                <div className="rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-sm">
                        <thead className="bg-slate-50 text-xs text-slate-500 uppercase tracking-wide">
                            <tr>
                                <th className="px-4 py-3 text-left font-medium">用户名</th>
                                <th className="px-4 py-3 text-left font-medium">显示名</th>
                                <th className="px-4 py-3 text-left font-medium">角色</th>
                                <th className="px-4 py-3 text-left font-medium">状态</th>
                                <th className="px-4 py-3 text-left font-medium">最后登录</th>
                                <th className="px-4 py-3 text-right font-medium">操作</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                            {users.map(user => (
                                <tr key={user.id} className="hover:bg-slate-50">
                                    <td className="px-4 py-3 font-mono text-slate-700">{user.username}</td>
                                    <td className="px-4 py-3 text-slate-700">{user.display_name}</td>
                                    <td className="px-4 py-3 text-slate-500">
                                        {ROLE_LABEL[user.role] ?? user.role}
                                    </td>
                                    <td className="px-4 py-3">
                                        <StatusBadge status={user.status} />
                                    </td>
                                    <td className="px-4 py-3 text-slate-400 text-xs">
                                        {user.last_login_at
                                            ? new Date(user.last_login_at).toLocaleString("zh-CN")
                                            : "—"}
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                        <div className="inline-flex items-center gap-1">
                                            {user.status === "active" && (
                                                <CopyInviteButton appConfig={appConfig} />
                                            )}
                                            {user.role !== "root" && (
                                                user.status === "active" ? (
                                                    <button
                                                        onClick={() => setConfirmTarget({ user, action: "disable" })}
                                                        className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs text-red-600 hover:bg-red-50 cursor-pointer"
                                                    >
                                                        <UserX className="w-3.5 h-3.5" />
                                                        禁用
                                                    </button>
                                                ) : (
                                                    <button
                                                        onClick={() => setConfirmTarget({ user, action: "enable" })}
                                                        className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs text-green-700 hover:bg-green-50 cursor-pointer"
                                                    >
                                                        <UserCheck className="w-3.5 h-3.5" />
                                                        启用
                                                    </button>
                                                )
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            {users.length === 0 && (
                                <tr>
                                    <td colSpan={6} className="px-4 py-8 text-center text-sm text-slate-400">
                                        暂无用户
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            )}

            {showCreate && (
                <CreateUserDialog
                    apiFetch={apiFetch}
                    onClose={() => setShowCreate(false)}
                    onCreated={refresh}
                />
            )}

            {confirmTarget && (
                <ConfirmDialog
                    user={confirmTarget.user}
                    action={confirmTarget.action}
                    onClose={() => setConfirmTarget(null)}
                    onConfirm={handleDisableEnable}
                    loading={actionLoading}
                />
            )}
        </div>
    );
}

// ── useUserList ───────────────────────────────────────────────────────────────

function useUserList(apiFetch: ReturnType<typeof useAppFetch>["apiFetch"]) {
    const [users, setUsers] = useState<UserRecord[] | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const load = async () => {
        setLoading(true);
        setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/UserListRoute", { method: "GET" });
            if (!resp.ok) {
                setError((data as any)?.error ?? `HTTP ${resp.status}`);
                return;
            }
            setUsers(data as unknown as UserRecord[]);
        } catch {
            setError("网络错误");
        } finally {
            setLoading(false);
        }
    };

    // load on mount
    useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

    return { data: users, loading, error, refresh: load };
}

// ── Export ────────────────────────────────────────────────────────────────────

export default function AdminUsersPage() {
    return <AdminUsersContent />;
}
