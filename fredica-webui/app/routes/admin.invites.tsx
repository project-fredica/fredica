import { useState, useEffect, useCallback } from "react";
import {
    Plus, RefreshCw, Trash2, Eye, EyeOff, Copy, Check, X, ExternalLink,
} from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { useAppConfig } from "~/context/appConfig";
import { reportHttpError } from "~/util/error_handler";
import type {
    GuestInviteLink, GuestInviteVisit,
    TenantInviteLink, TenantInviteRegistration,
} from "~/util/invite_types";

// ── Helpers ──────────────────────────────────────────────────────────────────

function fmtDate(iso: string) {
    try { return new Date(iso).toLocaleString("zh-CN"); } catch { return iso; }
}

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

function buildInviteUrl(
    type: "guest" | "tenant",
    pathId: string,
    appConfig: { webserver_schema: string | null; webserver_domain: string | null; webserver_port: string | null },
    serverGuestToken: string | null,
) {
    const schema = appConfig.webserver_schema ?? "http";
    const domain = appConfig.webserver_domain ?? "localhost";
    const port = appConfig.webserver_port ?? "7631";
    const params = new URLSearchParams({
        webserver_schema: schema,
        webserver_domain: domain,
        webserver_port: port,
    });
    // 邀请链接附带游客令牌，方便受邀者自动以游客身份登录
    if (serverGuestToken) {
        params.set("webserver_auth_token", serverGuestToken);
    }
    return `${schema}://${domain}:7630/invite/${type}/${pathId}?${params.toString()}`;
}

function CopyButton({ text }: { text: string }) {
    const [copied, setCopied] = useState(false);
    const handleCopy = async () => {
        try {
            await navigator.clipboard.writeText(text);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch { /* clipboard not available */ }
    };
    return (
        <button
            onClick={handleCopy}
            className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-blue-600 hover:bg-blue-50 cursor-pointer"
            title="复制链接"
        >
            {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
            {copied ? "已复制" : "复制"}
        </button>
    );
}

// ── Confirm Dialog ───────────────────────────────────────────────────────────

function ConfirmDialog({ title, message, warning, onClose, onConfirm, loading, confirmLabel, danger }: {
    title: string; message: string; warning?: string;
    onClose: () => void; onConfirm: () => void; loading: boolean;
    confirmLabel?: string; danger?: boolean;
}) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
                <h2 className="mb-2 text-base font-semibold text-slate-800">{title}</h2>
                <p className="mb-1 text-sm text-slate-600">{message}</p>
                {warning && <p className="mb-4 text-xs text-amber-600">{warning}</p>}
                {!warning && <div className="mb-4" />}
                <div className="flex gap-2 justify-end">
                    <button onClick={onClose} className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer">取消</button>
                    <button
                        onClick={onConfirm} disabled={loading}
                        className={`rounded-lg px-4 py-2 text-sm text-white disabled:opacity-40 cursor-pointer ${danger ? "bg-red-500 hover:bg-red-600" : "bg-slate-800 hover:bg-slate-700"}`}
                    >
                        {loading ? "处理中..." : (confirmLabel ?? "确认")}
                    </button>
                </div>
            </div>
        </div>
    );
}

// ══════════════════════════════════════════════════════════════════════════════
// ── Guest Invite Section ─────────────────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

function GuestCreateDialog({ onClose, onCreated, apiFetch }: {
    onClose: () => void; onCreated: () => void;
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}) {
    const [label, setLabel] = useState("");
    const [pathId, setPathId] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true); setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/GuestInviteLinkCreateRoute", {
                method: "POST",
                body: JSON.stringify({ label: label.trim(), path_id: pathId.trim() }),
            });
            if (!resp.ok) { setError((data as any)?.error ?? `HTTP ${resp.status}`); return; }
            onCreated(); onClose();
        } catch { setError("网络错误"); } finally { setLoading(false); }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
                <h2 className="mb-4 text-base font-semibold text-slate-800">创建游客邀请链接</h2>
                <form onSubmit={handleSubmit} className="flex flex-col gap-3">
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-slate-500">标签（可选）</label>
                        <input type="text" value={label} onChange={e => setLabel(e.target.value)}
                            placeholder="例：给朋友的链接"
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400" />
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-slate-500">自定义路径 ID（可选，留空自动生成）</label>
                        <input type="text" value={pathId} onChange={e => setPathId(e.target.value)}
                            placeholder="my-link"
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm font-mono outline-none focus:border-slate-400" />
                    </div>
                    {error && <p className="text-xs text-red-500">{error}</p>}
                    <div className="mt-1 flex gap-2 justify-end">
                        <button type="button" onClick={onClose} className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer">取消</button>
                        <button type="submit" disabled={loading} className="rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-40 cursor-pointer">
                            {loading ? "创建中..." : "创建"}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}

function VisitDetailModal({ visit, onClose }: { visit: GuestInviteVisit; onClose: () => void }) {
    return (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/30">
            <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-lg">
                <div className="flex items-center justify-between mb-4">
                    <h3 className="text-sm font-semibold text-slate-800">访问详情</h3>
                    <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 cursor-pointer"><X className="w-4 h-4" /></button>
                </div>
                <div className="flex flex-col gap-3 text-sm">
                    <div>
                        <div className="text-xs text-slate-400 mb-1">时间</div>
                        <div className="text-slate-700">{fmtDate(visit.visited_at)}</div>
                    </div>
                    <div>
                        <div className="text-xs text-slate-400 mb-1">IP 地址</div>
                        <div className="font-mono text-slate-700">{visit.ip_address || "—"}</div>
                    </div>
                    <div>
                        <div className="text-xs text-slate-400 mb-1">User-Agent</div>
                        <div className="text-slate-700 break-all text-xs leading-relaxed">{visit.user_agent || "—"}</div>
                    </div>
                </div>
            </div>
        </div>
    );
}

function GuestVisitModal({ link, onClose, apiFetch }: {
    link: GuestInviteLink; onClose: () => void;
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}) {
    const [visits, setVisits] = useState<GuestInviteVisit[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [detailVisit, setDetailVisit] = useState<GuestInviteVisit | null>(null);

    useEffect(() => {
        (async () => {
            try {
                const { resp, data } = await apiFetch(
                    `/api/v1/GuestInviteVisitListRoute?link_id=${link.id}&limit=50&offset=0`,
                    { method: "GET" },
                );
                if (resp.ok && data) {
                    setVisits((data as any).items ?? []);
                    setTotal((data as any).total ?? 0);
                }
            } catch { /* ignore */ } finally { setLoading(false); }
        })();
    }, [link.id]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-lg max-h-[80vh] flex flex-col">
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-base font-semibold text-slate-800">
                        访问记录 — {link.label || link.path_id}
                        <span className="ml-2 text-xs text-slate-400">共 {total} 条</span>
                    </h2>
                    <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 cursor-pointer"><X className="w-4 h-4" /></button>
                </div>
                <div className="flex-1 overflow-y-auto">
                    {loading && <p className="text-sm text-slate-400">加载中...</p>}
                    {!loading && visits.length === 0 && <p className="text-sm text-slate-400">暂无访问记录</p>}
                    {!loading && visits.length > 0 && (
                        <table className="w-full text-xs">
                            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wide">
                                <tr>
                                    <th className="px-3 py-2 text-left font-medium">时间</th>
                                    <th className="px-3 py-2 text-left font-medium">IP</th>
                                    <th className="px-3 py-2 text-left font-medium">User-Agent</th>
                                    <th className="px-3 py-2 text-right font-medium"></th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {visits.map(v => (
                                    <tr key={v.id} className="hover:bg-slate-50">
                                        <td className="px-3 py-2 text-slate-500 whitespace-nowrap">{fmtDate(v.visited_at)}</td>
                                        <td className="px-3 py-2 font-mono text-slate-600">{v.ip_address}</td>
                                        <td className="px-3 py-2 text-slate-500 max-w-[180px] truncate">{v.user_agent}</td>
                                        <td className="px-3 py-2 text-right">
                                            <button onClick={() => setDetailVisit(v)}
                                                className="text-xs text-blue-500 hover:text-blue-700 cursor-pointer">
                                                详情
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
            {detailVisit && <VisitDetailModal visit={detailVisit} onClose={() => setDetailVisit(null)} />}
        </div>
    );
}

function GuestInviteSection({ apiFetch, appConfig, serverGuestToken }: {
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
    appConfig: ReturnType<typeof useAppConfig>["appConfig"];
    serverGuestToken: string | null;
}) {
    const [links, setLinks] = useState<GuestInviteLink[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showCreate, setShowCreate] = useState(false);
    const [visitModal, setVisitModal] = useState<GuestInviteLink | null>(null);
    const [confirmAction, setConfirmAction] = useState<{ link: GuestInviteLink; action: "disable" | "enable" | "delete" } | null>(null);
    const [actionLoading, setActionLoading] = useState(false);

    const load = useCallback(async () => {
        setLoading(true); setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/GuestInviteLinkListRoute", { method: "GET" });
            if (!resp.ok) { setError((data as any)?.error ?? `HTTP ${resp.status}`); return; }
            setLinks(data as unknown as GuestInviteLink[]);
        } catch { setError("网络错误"); } finally { setLoading(false); }
    }, [apiFetch]);

    useEffect(() => { load(); }, [load]);

    const handleAction = async () => {
        if (!confirmAction) return;
        setActionLoading(true);
        try {
            const { link, action } = confirmAction;
            if (action === "delete") {
                const { resp } = await apiFetch("/api/v1/GuestInviteLinkDeleteRoute", {
                    method: "POST", body: JSON.stringify({ id: link.id }),
                });
                if (!resp.ok) { reportHttpError("删除失败", resp); return; }
            } else {
                const newStatus = action === "disable" ? "disabled" : "active";
                const { resp } = await apiFetch("/api/v1/GuestInviteLinkUpdateRoute", {
                    method: "POST", body: JSON.stringify({ id: link.id, status: newStatus }),
                });
                if (!resp.ok) { reportHttpError("操作失败", resp); return; }
            }
            setConfirmAction(null);
            load();
        } catch { /* network */ } finally { setActionLoading(false); }
    };

    return (
        <section>
            <div className="mb-4 flex items-center justify-between">
                <h2 className="text-base font-semibold text-slate-700">游客邀请链接</h2>
                <div className="flex gap-2">
                    <button onClick={load} className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer">
                        <RefreshCw className="w-3.5 h-3.5" /> 刷新
                    </button>
                    <button onClick={() => setShowCreate(true)} className="flex items-center gap-1.5 rounded-lg bg-slate-800 px-3 py-1.5 text-sm text-white hover:bg-slate-700 cursor-pointer">
                        <Plus className="w-3.5 h-3.5" /> 创建
                    </button>
                </div>
            </div>

            {loading && <p className="text-sm text-slate-400">加载中...</p>}
            {error && <p className="text-sm text-red-500">加载失败：{error}</p>}

            {!loading && !error && (
                <div className="rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-sm">
                        <thead className="bg-slate-50 text-xs text-slate-500 uppercase tracking-wide">
                            <tr>
                                <th className="px-4 py-3 text-left font-medium">标签</th>
                                <th className="px-4 py-3 text-left font-medium">路径 ID</th>
                                <th className="px-4 py-3 text-left font-medium">状态</th>
                                <th className="px-4 py-3 text-left font-medium">访问次数</th>
                                <th className="px-4 py-3 text-left font-medium">创建时间</th>
                                <th className="px-4 py-3 text-right font-medium">操作</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                            {links.map(link => (
                                <tr key={link.id} className="hover:bg-slate-50">
                                    <td className="px-4 py-3 text-slate-700">{link.label || <span className="text-slate-300">—</span>}</td>
                                    <td className="px-4 py-3 font-mono text-slate-600 text-xs">{link.path_id}</td>
                                    <td className="px-4 py-3"><StatusBadge status={link.status} /></td>
                                    <td className="px-4 py-3 text-slate-500">
                                        <button onClick={() => setVisitModal(link)} className="underline decoration-dotted hover:text-slate-700 cursor-pointer">
                                            {link.visit_count}
                                        </button>
                                    </td>
                                    <td className="px-4 py-3 text-slate-400 text-xs">{fmtDate(link.created_at)}</td>
                                    <td className="px-4 py-3 text-right">
                                        <div className="inline-flex items-center gap-1">
                                            <CopyButton text={buildInviteUrl("guest", link.path_id, appConfig, serverGuestToken)} />
                                            {link.status === "active" ? (
                                                <button onClick={() => setConfirmAction({ link, action: "disable" })}
                                                    className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-amber-600 hover:bg-amber-50 cursor-pointer">
                                                    <EyeOff className="w-3.5 h-3.5" /> 禁用
                                                </button>
                                            ) : (
                                                <button onClick={() => setConfirmAction({ link, action: "enable" })}
                                                    className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-green-700 hover:bg-green-50 cursor-pointer">
                                                    <Eye className="w-3.5 h-3.5" /> 启用
                                                </button>
                                            )}
                                            <button onClick={() => setConfirmAction({ link, action: "delete" })}
                                                className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-red-600 hover:bg-red-50 cursor-pointer">
                                                <Trash2 className="w-3.5 h-3.5" /> 删除
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            {links.length === 0 && (
                                <tr><td colSpan={6} className="px-4 py-8 text-center text-sm text-slate-400">暂无游客邀请链接</td></tr>
                            )}
                        </tbody>
                    </table>
                </div>
            )}

            {showCreate && <GuestCreateDialog apiFetch={apiFetch} onClose={() => setShowCreate(false)} onCreated={load} />}
            {visitModal && <GuestVisitModal link={visitModal} onClose={() => setVisitModal(null)} apiFetch={apiFetch} />}
            {confirmAction && (
                <ConfirmDialog
                    title={confirmAction.action === "delete" ? "删除链接" : confirmAction.action === "disable" ? "禁用链接" : "启用链接"}
                    message={`确认${confirmAction.action === "delete" ? "删除" : confirmAction.action === "disable" ? "禁用" : "启用"}链接「${confirmAction.link.label || confirmAction.link.path_id}」？`}
                    warning={confirmAction.action === "delete" ? "删除后所有访问记录也将被清除，此操作不可撤销。" : undefined}
                    onClose={() => setConfirmAction(null)}
                    onConfirm={handleAction}
                    loading={actionLoading}
                    confirmLabel={confirmAction.action === "delete" ? "删除" : confirmAction.action === "disable" ? "禁用" : "启用"}
                    danger={confirmAction.action === "delete" || confirmAction.action === "disable"}
                />
            )}
        </section>
    );
}

// ══════════════════════════════════════════════════════════════════════════════
// ── Tenant Invite Section ────────────────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

function TenantCreateDialog({ onClose, onCreated, apiFetch }: {
    onClose: () => void; onCreated: () => void;
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}) {
    const [label, setLabel] = useState("");
    const [pathId, setPathId] = useState("");
    const [maxUses, setMaxUses] = useState("10");
    const [expiresIn, setExpiresIn] = useState("7");
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        const mu = parseInt(maxUses, 10);
        const days = parseInt(expiresIn, 10);
        if (isNaN(mu) || mu < 1) { setError("最大使用次数须为正整数"); return; }
        if (isNaN(days) || days < 1) { setError("有效天数须为正整数"); return; }

        const expiresAt = new Date(Date.now() + days * 86400000).toISOString();
        setLoading(true); setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/TenantInviteLinkCreateRoute", {
                method: "POST",
                body: JSON.stringify({
                    label: label.trim(),
                    path_id: pathId.trim(),
                    max_uses: mu,
                    expires_at: expiresAt,
                }),
            });
            if (!resp.ok) { setError((data as any)?.error ?? `HTTP ${resp.status}`); return; }
            onCreated(); onClose();
        } catch { setError("网络错误"); } finally { setLoading(false); }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
                <h2 className="mb-4 text-base font-semibold text-slate-800">创建租户邀请链接</h2>
                <form onSubmit={handleSubmit} className="flex flex-col gap-3">
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-slate-500">标签（可选）</label>
                        <input type="text" value={label} onChange={e => setLabel(e.target.value)}
                            placeholder="例：新成员邀请"
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400" />
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-slate-500">自定义路径 ID（可选，留空自动生成）</label>
                        <input type="text" value={pathId} onChange={e => setPathId(e.target.value)}
                            placeholder="my-invite"
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm font-mono outline-none focus:border-slate-400" />
                    </div>
                    <div className="flex gap-3">
                        <div className="flex-1 min-w-0 flex flex-col gap-1">
                            <label className="text-xs text-slate-500">最大使用次数</label>
                            <input type="number" value={maxUses} onChange={e => setMaxUses(e.target.value)} min={1}
                                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400" />
                        </div>
                        <div className="flex-1 min-w-0 flex flex-col gap-1">
                            <label className="text-xs text-slate-500">有效天数</label>
                            <input type="number" value={expiresIn} onChange={e => setExpiresIn(e.target.value)} min={1}
                                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400" />
                        </div>
                    </div>
                    {error && <p className="text-xs text-red-500">{error}</p>}
                    <div className="mt-1 flex gap-2 justify-end">
                        <button type="button" onClick={onClose} className="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer">取消</button>
                        <button type="submit" disabled={loading} className="rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-40 cursor-pointer">
                            {loading ? "创建中..." : "创建"}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}

function TenantRegistrationModal({ link, onClose, apiFetch }: {
    link: TenantInviteLink; onClose: () => void;
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}) {
    const [regs, setRegs] = useState<TenantInviteRegistration[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        (async () => {
            try {
                const { resp, data } = await apiFetch(
                    `/api/v1/TenantInviteRegistrationListRoute?link_id=${link.id}`,
                    { method: "GET" },
                );
                if (resp.ok && data) {
                    setRegs((data as any).items ?? []);
                    setTotal((data as any).total ?? 0);
                }
            } catch { /* ignore */ } finally { setLoading(false); }
        })();
    }, [link.id]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
            <div className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-lg max-h-[80vh] flex flex-col">
                <div className="flex items-center justify-between mb-4">
                    <h2 className="text-base font-semibold text-slate-800">
                        注册记录 — {link.label || link.path_id}
                        <span className="ml-2 text-xs text-slate-400">共 {total} 条</span>
                    </h2>
                    <button onClick={onClose} className="p-1 rounded hover:bg-slate-100 cursor-pointer"><X className="w-4 h-4" /></button>
                </div>
                <div className="flex-1 overflow-y-auto">
                    {loading && <p className="text-sm text-slate-400">加载中...</p>}
                    {!loading && regs.length === 0 && <p className="text-sm text-slate-400">暂无注册记录</p>}
                    {!loading && regs.length > 0 && (
                        <table className="w-full text-xs">
                            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wide">
                                <tr>
                                    <th className="px-3 py-2 text-left font-medium">时间</th>
                                    <th className="px-3 py-2 text-left font-medium">用户名</th>
                                    <th className="px-3 py-2 text-left font-medium">显示名</th>
                                    <th className="px-3 py-2 text-left font-medium">IP</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {regs.map(r => (
                                    <tr key={r.id}>
                                        <td className="px-3 py-2 text-slate-400">{fmtDate(r.registered_at)}</td>
                                        <td className="px-3 py-2 font-mono text-slate-600">{r.username ?? "—"}</td>
                                        <td className="px-3 py-2 text-slate-600">{r.display_name ?? "—"}</td>
                                        <td className="px-3 py-2 font-mono text-slate-500">{r.ip_address}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
        </div>
    );
}

function TenantInviteSection({ apiFetch, appConfig, serverGuestToken }: {
    apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
    appConfig: ReturnType<typeof useAppConfig>["appConfig"];
    serverGuestToken: string | null;
}) {
    const [links, setLinks] = useState<TenantInviteLink[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showCreate, setShowCreate] = useState(false);
    const [regModal, setRegModal] = useState<TenantInviteLink | null>(null);
    const [confirmAction, setConfirmAction] = useState<{ link: TenantInviteLink; action: "disable" | "enable" | "delete" } | null>(null);
    const [actionLoading, setActionLoading] = useState(false);

    const load = useCallback(async () => {
        setLoading(true); setError(null);
        try {
            const { resp, data } = await apiFetch("/api/v1/TenantInviteLinkListRoute", { method: "GET" });
            if (!resp.ok) { setError((data as any)?.error ?? `HTTP ${resp.status}`); return; }
            setLinks(data as unknown as TenantInviteLink[]);
        } catch { setError("网络错误"); } finally { setLoading(false); }
    }, [apiFetch]);

    useEffect(() => { load(); }, [load]);

    const handleAction = async () => {
        if (!confirmAction) return;
        setActionLoading(true);
        try {
            const { link, action } = confirmAction;
            if (action === "delete") {
                const { resp, data } = await apiFetch("/api/v1/TenantInviteLinkDeleteRoute", {
                    method: "POST", body: JSON.stringify({ id: link.id }),
                });
                if (!resp.ok) {
                    const msg = (data as any)?.error ?? `HTTP ${resp.status}`;
                    reportHttpError(msg, resp);
                    return;
                }
            } else {
                const newStatus = action === "disable" ? "disabled" : "active";
                const { resp } = await apiFetch("/api/v1/TenantInviteLinkUpdateRoute", {
                    method: "POST", body: JSON.stringify({ id: link.id, status: newStatus }),
                });
                if (!resp.ok) { reportHttpError("操作失败", resp); return; }
            }
            setConfirmAction(null);
            load();
        } catch { /* network */ } finally { setActionLoading(false); }
    };

    const isExpired = (link: TenantInviteLink) => {
        try { return new Date(link.expires_at).getTime() < Date.now(); } catch { return false; }
    };

    return (
        <section>
            <div className="mb-4 flex items-center justify-between">
                <h2 className="text-base font-semibold text-slate-700">租户邀请链接</h2>
                <div className="flex gap-2">
                    <button onClick={load} className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 cursor-pointer">
                        <RefreshCw className="w-3.5 h-3.5" /> 刷新
                    </button>
                    <button onClick={() => setShowCreate(true)} className="flex items-center gap-1.5 rounded-lg bg-slate-800 px-3 py-1.5 text-sm text-white hover:bg-slate-700 cursor-pointer">
                        <Plus className="w-3.5 h-3.5" /> 创建
                    </button>
                </div>
            </div>

            {loading && <p className="text-sm text-slate-400">加载中...</p>}
            {error && <p className="text-sm text-red-500">加载失败：{error}</p>}

            {!loading && !error && (
                <div className="rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-sm">
                        <thead className="bg-slate-50 text-xs text-slate-500 uppercase tracking-wide">
                            <tr>
                                <th className="px-4 py-3 text-left font-medium">标签</th>
                                <th className="px-4 py-3 text-left font-medium">路径 ID</th>
                                <th className="px-4 py-3 text-left font-medium">状态</th>
                                <th className="px-4 py-3 text-left font-medium">使用 / 上限</th>
                                <th className="px-4 py-3 text-left font-medium">过期时间</th>
                                <th className="px-4 py-3 text-right font-medium">操作</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                            {links.map(link => (
                                <tr key={link.id} className="hover:bg-slate-50">
                                    <td className="px-4 py-3 text-slate-700">{link.label || <span className="text-slate-300">—</span>}</td>
                                    <td className="px-4 py-3 font-mono text-slate-600 text-xs">{link.path_id}</td>
                                    <td className="px-4 py-3">
                                        <StatusBadge status={link.status} />
                                        {isExpired(link) && link.status === "active" && (
                                            <span className="ml-1 text-xs text-amber-500">已过期</span>
                                        )}
                                    </td>
                                    <td className="px-4 py-3 text-slate-500">
                                        <button onClick={() => setRegModal(link)} className="underline decoration-dotted hover:text-slate-700 cursor-pointer">
                                            {link.used_count}
                                        </button>
                                        <span className="text-slate-300"> / {link.max_uses}</span>
                                    </td>
                                    <td className="px-4 py-3 text-slate-400 text-xs">{fmtDate(link.expires_at)}</td>
                                    <td className="px-4 py-3 text-right">
                                        <div className="inline-flex items-center gap-1">
                                            <CopyButton text={buildInviteUrl("tenant", link.path_id, appConfig, serverGuestToken)} />
                                            {link.status === "active" ? (
                                                <button onClick={() => setConfirmAction({ link, action: "disable" })}
                                                    className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-amber-600 hover:bg-amber-50 cursor-pointer">
                                                    <EyeOff className="w-3.5 h-3.5" /> 禁用
                                                </button>
                                            ) : (
                                                <button onClick={() => setConfirmAction({ link, action: "enable" })}
                                                    className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-green-700 hover:bg-green-50 cursor-pointer">
                                                    <Eye className="w-3.5 h-3.5" /> 启用
                                                </button>
                                            )}
                                            <button onClick={() => setConfirmAction({ link, action: "delete" })}
                                                className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-red-600 hover:bg-red-50 cursor-pointer">
                                                <Trash2 className="w-3.5 h-3.5" /> 删除
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            {links.length === 0 && (
                                <tr><td colSpan={6} className="px-4 py-8 text-center text-sm text-slate-400">暂无租户邀请链接</td></tr>
                            )}
                        </tbody>
                    </table>
                </div>
            )}

            {showCreate && <TenantCreateDialog apiFetch={apiFetch} onClose={() => setShowCreate(false)} onCreated={load} />}
            {regModal && <TenantRegistrationModal link={regModal} onClose={() => setRegModal(null)} apiFetch={apiFetch} />}
            {confirmAction && (
                <ConfirmDialog
                    title={confirmAction.action === "delete" ? "删除链接" : confirmAction.action === "disable" ? "禁用链接" : "启用链接"}
                    message={`确认${confirmAction.action === "delete" ? "删除" : confirmAction.action === "disable" ? "禁用" : "启用"}链接「${confirmAction.link.label || confirmAction.link.path_id}」？`}
                    warning={confirmAction.action === "delete" ? "如果已有注册记录，将无法删除。" : undefined}
                    onClose={() => setConfirmAction(null)}
                    onConfirm={handleAction}
                    loading={actionLoading}
                    confirmLabel={confirmAction.action === "delete" ? "删除" : confirmAction.action === "disable" ? "禁用" : "启用"}
                    danger={confirmAction.action === "delete" || confirmAction.action === "disable"}
                />
            )}
        </section>
    );
}

// ══════════════════════════════════════════════════════════════════════════════
// ── Page ─────────────────────────────────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

export default function AdminInvitesPage() {
    const { apiFetch } = useAppFetch();
    const { appConfig } = useAppConfig();
    const [serverGuestToken, setServerGuestToken] = useState<string | null>(null);

    // 从后端获取服务器配置的游客令牌（用于生成邀请链接）
    useEffect(() => {
        (async () => {
            try {
                const { resp, data } = await apiFetch("/api/v1/WebserverAuthTokenGetRoute", { method: "GET" });
                if (resp.ok && (data as any)?.webserver_auth_token) {
                    setServerGuestToken((data as any).webserver_auth_token);
                }
            } catch { /* ignore */ }
        })();
    }, [apiFetch]);

    return (
        <div className="p-6 max-w-5xl mx-auto space-y-10">
            <GuestInviteSection apiFetch={apiFetch} appConfig={appConfig} serverGuestToken={serverGuestToken} />
            <TenantInviteSection apiFetch={apiFetch} appConfig={appConfig} serverGuestToken={serverGuestToken} />
        </div>
    );
}
