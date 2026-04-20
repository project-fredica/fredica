import { useEffect, useState, useCallback } from "react";
import type React from "react";
import { useNavigate } from "react-router";
import {
    ArrowLeft, Plus, Trash2, CheckCircle, XCircle, Loader,
    Globe, Shield, RefreshCw, ChevronDown, ChevronRight, UserCircle,
} from "lucide-react";
import type { Route } from "./+types/app-desktop-setting-bilibili-account-pool-config";
import { callBridge, BridgeUnavailableError, openExternalUrl } from "~/util/bridge";
import { json_parse } from "~/util/json";
import { print_error } from "~/util/error_handler";
import { toast } from "react-toastify";
import { useImageProxyUrl } from "~/util/app_fetch";
import { PasswordInput } from "~/components/ui/PasswordInput";

export function meta({}: Route.MetaArgs) {
    return [{ title: "B站账号池配置 - Fredica" }];
}

// ─── Types ───────────────────────────────────────────────────────────────────

const PROXY_USE_APP = "USE_APP";

interface BilibiliAccount {
    id: string;
    label: string;
    is_anonymous: boolean;
    is_default: boolean;
    sessdata: string;
    bili_jct: string;
    buvid3: string;
    buvid4: string;
    dedeuserid: string;
    ac_time_value: string;
    proxy: string;
    impersonate: string;
    rate_limit_sec: number;
}

type CheckStatus = "idle" | "checking" | "valid" | "invalid" | "error";
type IpStatus = "idle" | "checking" | "done" | "error";

interface AccountCheckState {
    credStatus: CheckStatus;
    credMessage: string;
    ipStatus: IpStatus;
    ipAddress: string;
}

interface BilibiliAccountInfo {
    account_id: string;
    mid: string;
    name: string;
    face: string;
    level: number;
    sign: string;
    coins: number;
    fans: number;
    following: number;
    queried_at: number;
}

type FetchInfoStatus = "idle" | "fetching" | "done" | "error";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function createAnonymousAccount(): BilibiliAccount {
    return {
        id: "anonymous",
        label: "匿名",
        is_anonymous: true,
        is_default: false,
        sessdata: "",
        bili_jct: "",
        buvid3: "",
        buvid4: "",
        dedeuserid: "",
        ac_time_value: "",
        proxy: PROXY_USE_APP,
        impersonate: "chrome",
        rate_limit_sec: 0.5,
    };
}

function createNewAccount(): BilibiliAccount {
    return {
        id: crypto.randomUUID(),
        label: "",
        is_anonymous: false,
        is_default: false,
        sessdata: "",
        bili_jct: "",
        buvid3: "",
        buvid4: "",
        dedeuserid: "",
        ac_time_value: "",
        proxy: PROXY_USE_APP,
        impersonate: "chrome",
        rate_limit_sec: 3.0,
    };
}

const PROXY_OPTIONS = [
    { value: PROXY_USE_APP, label: "跟随应用代理" },
    { value: "", label: "直连（不使用代理）" },
    { value: "__custom__", label: "自定义代理地址" },
] as const;

const IMPERSONATE_OPTIONS = [
    { value: "chrome", label: "Chrome（默认）" },
    { value: "edge", label: "Edge" },
    { value: "safari", label: "Safari" },
    { value: "", label: "不设置" },
] as const;

function getProxyMode(proxy: string): string {
    if (proxy === PROXY_USE_APP) return PROXY_USE_APP;
    if (proxy === "") return "";
    return "__custom__";
}

// ─── Component ───────────────────────────────────────────────────────────────

export default function Component({}: Route.ComponentProps) {
    const navigate = useNavigate();
    const buildProxyUrl = useImageProxyUrl();

    const [accounts, setAccounts] = useState<BilibiliAccount[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [isDirty, setIsDirty] = useState(false);
    const [savedFlash, setSavedFlash] = useState(false);

    const [expandedId, setExpandedId] = useState<string | null>(null);
    const [checkStates, setCheckStates] = useState<Record<string, AccountCheckState>>({});
    const [showPasswords, setShowPasswords] = useState<Record<string, boolean>>({});
    const [batchChecking, setBatchChecking] = useState(false);
    const [batchIpChecking, setBatchIpChecking] = useState(false);

    const [accountInfoMap, setAccountInfoMap] = useState<Record<string, BilibiliAccountInfo>>({});
    const [fetchInfoStates, setFetchInfoStates] = useState<Record<string, FetchInfoStatus>>({});
    const [batchFetchingInfo, setBatchFetchingInfo] = useState(false);

    // ── Load ─────────────────────────────────────────────────────────────────

    useEffect(() => {
        (async () => {
            try {
                const result = await callBridge("get_bilibili_account_pool");
                const parsed = json_parse<BilibiliAccount[]>(result);
                if (Array.isArray(parsed) && parsed.length > 0) {
                    setAccounts(parsed);
                } else {
                    setAccounts([createAnonymousAccount()]);
                }
                setLoaded(true);
            } catch (e) {
                setLoadError(e instanceof BridgeUnavailableError
                    ? "kmpJsBridge 不可用，请在桌面端环境中使用。"
                    : "加载配置失败");
                setLoaded(true);
            }
            try {
                const infoRaw = await callBridge("get_bilibili_account_info");
                const infoMap = json_parse<Record<string, BilibiliAccountInfo>>(infoRaw);
                if (infoMap && typeof infoMap === "object" && !infoMap.error) {
                    setAccountInfoMap(infoMap as Record<string, BilibiliAccountInfo>);
                }
            } catch {
                // non-critical
            }
        })();
    }, []);

    // ── Save ─────────────────────────────────────────────────────────────────

    const handleSave = async () => {
        setSaving(true);
        try {
            await callBridge("save_bilibili_account_pool", JSON.stringify(accounts));
            setIsDirty(false);
            setSavedFlash(true);
            setTimeout(() => setSavedFlash(false), 2000);
        } catch (e) {
            print_error({ reason: "保存账号池失败", err: e });
        } finally {
            setSaving(false);
        }
    };

    // ── Account mutations ────────────────────────────────────────────────────

    const updateAccount = useCallback((id: string, patch: Partial<BilibiliAccount>) => {
        setAccounts(prev => prev.map(a => a.id === id ? { ...a, ...patch } : a));
        setIsDirty(true);
    }, []);

    const addAccount = () => {
        const acct = createNewAccount();
        setAccounts(prev => [...prev, acct]);
        setExpandedId(acct.id);
        setIsDirty(true);
    };

    const removeAccount = (id: string) => {
        setAccounts(prev => prev.filter(a => a.id !== id));
        if (expandedId === id) setExpandedId(null);
        setIsDirty(true);
    };

    const setDefault = (id: string) => {
        setAccounts(prev => prev.map(a => ({ ...a, is_default: a.id === id })));
        setIsDirty(true);
    };

    // ── Credential check ─────────────────────────────────────────────────────

    const checkCredential = async (acct: BilibiliAccount) => {
        setCheckStates(prev => ({
            ...prev,
            [acct.id]: { ...prev[acct.id], credStatus: "checking", credMessage: "", ipStatus: prev[acct.id]?.ipStatus ?? "idle", ipAddress: prev[acct.id]?.ipAddress ?? "" },
        }));

        try {
            if (acct.is_anonymous) {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], credStatus: "valid", credMessage: "匿名模式可用" },
                }));
                return;
            }

            const credParams = JSON.stringify({ account_id: acct.id });
            const raw = await callBridge("check_bilibili_credential", credParams);
            const r = json_parse<{ configured: boolean; valid: boolean; message: string }>(raw);

            if (!r.configured) {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], credStatus: "invalid", credMessage: "未配置凭据" },
                }));
                toast.warning("未配置凭据");
            } else if (r.valid) {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], credStatus: "valid", credMessage: "有效" },
                }));
                toast.success("凭据有效");
            } else {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], credStatus: "invalid", credMessage: r.message || "已失效" },
                }));
                toast.error(r.message || "凭据已失效");
            }
        } catch (e) {
            const msg = e instanceof BridgeUnavailableError ? "仅桌面端可用" : "检测失败";
            setCheckStates(prev => ({
                ...prev,
                [acct.id]: { ...prev[acct.id], credStatus: "error", credMessage: msg },
            }));
            toast.error(msg);
        }
    };

    // ── IP check ─────────────────────────────────────────────────────────────

    const checkIp = async (acct: BilibiliAccount) => {
        setCheckStates(prev => ({
            ...prev,
            [acct.id]: { ...prev[acct.id], ipStatus: "checking", ipAddress: "", credStatus: prev[acct.id]?.credStatus ?? "idle", credMessage: prev[acct.id]?.credMessage ?? "" },
        }));

        try {
            const params = JSON.stringify({ account_id: acct.id });
            const raw = await callBridge("check_bilibili_ip", params);
            const r = json_parse<{ ip: string; error?: string }>(raw);
            if (r.error) {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], ipStatus: "error", ipAddress: r.error! },
                }));
                toast.error(`IP 检测失败: ${r.error}`);
            } else {
                setCheckStates(prev => ({
                    ...prev,
                    [acct.id]: { ...prev[acct.id], ipStatus: "done", ipAddress: r.ip },
                }));
                toast.success(`出口 IP: ${r.ip}`);
            }
        } catch (e) {
            const msg = e instanceof BridgeUnavailableError ? "仅桌面端可用" : "检测失败";
            setCheckStates(prev => ({
                ...prev,
                [acct.id]: { ...prev[acct.id], ipStatus: "error", ipAddress: msg },
            }));
            toast.error(msg);
        }
    };

    // ── Batch operations ─────────────────────────────────────────────────────

    const fetchAccountInfo = async (acct: BilibiliAccount) => {
        setFetchInfoStates(prev => ({ ...prev, [acct.id]: "fetching" }));
        try {
            const raw = await callBridge("fetch_bilibili_account_info", JSON.stringify({ account_id: acct.id }));
            const info = json_parse<BilibiliAccountInfo & { error?: string }>(raw);
            if (info.error) {
                setFetchInfoStates(prev => ({ ...prev, [acct.id]: "error" }));
                toast.error(info.error);
            } else {
                setAccountInfoMap(prev => ({ ...prev, [acct.id]: info }));
                setFetchInfoStates(prev => ({ ...prev, [acct.id]: "done" }));
                toast.success(`已获取账号信息: ${info.name || acct.label}`);
            }
        } catch (e) {
            if (!(e instanceof BridgeUnavailableError)) {
                print_error({ reason: "获取账号信息失败", err: e });
            }
            setFetchInfoStates(prev => ({ ...prev, [acct.id]: "error" }));
        }
    };

    const batchFetchAccountInfo = async () => {
        setBatchFetchingInfo(true);
        for (const acct of accounts) {
            if (!acct.is_anonymous) {
                await fetchAccountInfo(acct);
            }
        }
        setBatchFetchingInfo(false);
    };

    const batchCheckCredentials = async () => {
        setBatchChecking(true);
        for (const acct of accounts) {
            await checkCredential(acct);
        }
        setBatchChecking(false);
    };

    const batchCheckIps = async () => {
        setBatchIpChecking(true);
        for (const acct of accounts) {
            if (!acct.is_anonymous) {
                await checkIp(acct);
            }
        }
        setBatchIpChecking(false);
    };

    // ── Render ───────────────────────────────────────────────────────────────

    return (
        <div style={{ maxWidth: "720px", margin: "0 auto", padding: "24px 16px" }}>
            {/* Header */}
            <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "24px" }}>
                <button
                    onClick={() => navigate("/app-desktop-setting")}
                    style={{ background: "none", border: "none", cursor: "pointer", color: "#6b7280", display: "flex", alignItems: "center" }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div style={{ flex: 1 }}>
                    <h1 style={{ margin: 0, fontSize: "18px", fontWeight: 600, color: "#111827" }}>B站账号池</h1>
                    <p style={{ margin: "2px 0 0 0", fontSize: "13px", color: "#9ca3af" }}>
                        管理多个 B 站账号，配置代理和速率限制。同步任务将按优先级依次使用。
                    </p>
                </div>
                <button
                    onClick={handleSave}
                    disabled={!isDirty || saving}
                    style={{
                        display: "flex", alignItems: "center", gap: "6px",
                        padding: "8px 16px", fontSize: "14px", fontWeight: 500,
                        color: isDirty ? "#fff" : "#9ca3af",
                        backgroundColor: isDirty ? "#2563eb" : "#f3f4f6",
                        border: "none", borderRadius: "8px",
                        cursor: isDirty && !saving ? "pointer" : "default",
                        opacity: saving ? 0.6 : 1,
                    }}
                >
                    {saving ? <Loader size={14} className="animate-spin" /> : savedFlash ? <CheckCircle size={14} /> : null}
                    {savedFlash ? "已保存" : "保存"}
                </button>
            </div>

            {loadError && (
                <div style={{ padding: "12px 16px", marginBottom: "16px", backgroundColor: "#fef2f2", border: "1px solid #fecaca", borderRadius: "8px", fontSize: "13px", color: "#dc2626" }}>
                    {loadError}
                </div>
            )}

            {!loaded ? (
                <div style={{ textAlign: "center", padding: "40px", color: "#9ca3af" }}>
                    <Loader size={20} className="animate-spin" style={{ margin: "0 auto 8px" }} />
                    加载中…
                </div>
            ) : (
                <>
                    {/* Batch actions */}
                    <div style={{
                        display: "flex", gap: "8px", marginBottom: "16px", flexWrap: "wrap",
                    }}>
                        <button
                            onClick={batchCheckCredentials}
                            disabled={batchChecking}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px", fontSize: "13px", fontWeight: 500,
                                color: "#6366f1", background: "#eef2ff",
                                border: "1px solid #c7d2fe", borderRadius: "8px",
                                cursor: batchChecking ? "default" : "pointer",
                                opacity: batchChecking ? 0.6 : 1,
                            }}
                        >
                            {batchChecking ? <Loader size={14} className="animate-spin" /> : <Shield size={14} />}
                            批量检测可用性
                        </button>
                        <button
                            onClick={batchCheckIps}
                            disabled={batchIpChecking}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px", fontSize: "13px", fontWeight: 500,
                                color: "#0891b2", background: "#ecfeff",
                                border: "1px solid #a5f3fc", borderRadius: "8px",
                                cursor: batchIpChecking ? "default" : "pointer",
                                opacity: batchIpChecking ? 0.6 : 1,
                            }}
                        >
                            {batchIpChecking ? <Loader size={14} className="animate-spin" /> : <Globe size={14} />}
                            批量检测 IP
                        </button>
                        <button
                            onClick={batchFetchAccountInfo}
                            disabled={batchFetchingInfo}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px", fontSize: "13px", fontWeight: 500,
                                color: "#7c3aed", background: "#f5f3ff",
                                border: "1px solid #ddd6fe", borderRadius: "8px",
                                cursor: batchFetchingInfo ? "default" : "pointer",
                                opacity: batchFetchingInfo ? 0.6 : 1,
                            }}
                        >
                            {batchFetchingInfo ? <Loader size={14} className="animate-spin" /> : <UserCircle size={14} />}
                            批量获取信息
                        </button>
                        <div style={{ flex: 1 }} />
                        <button
                            onClick={addAccount}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px", fontSize: "13px", fontWeight: 500,
                                color: "#059669", background: "#ecfdf5",
                                border: "1px solid #a7f3d0", borderRadius: "8px",
                                cursor: "pointer",
                            }}
                        >
                            <Plus size={14} />
                            添加账号
                        </button>
                    </div>

                    {/* Account list */}
                    <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                        {accounts.map(acct => (
                            <AccountCard
                                key={acct.id}
                                account={acct}
                                isExpanded={expandedId === acct.id}
                                checkState={checkStates[acct.id]}
                                accountInfo={accountInfoMap[acct.id]}
                                fetchInfoStatus={fetchInfoStates[acct.id] ?? "idle"}
                                showPassword={showPasswords[acct.id] ?? false}
                                buildProxyUrl={buildProxyUrl}
                                onToggleExpand={() => setExpandedId(expandedId === acct.id ? null : acct.id)}
                                onUpdate={(patch) => updateAccount(acct.id, patch)}
                                onRemove={() => removeAccount(acct.id)}
                                onSetDefault={() => setDefault(acct.id)}
                                onCheckCredential={() => checkCredential(acct)}
                                onCheckIp={() => checkIp(acct)}
                                onFetchInfo={() => fetchAccountInfo(acct)}
                                onTogglePassword={() => setShowPasswords(prev => ({ ...prev, [acct.id]: !prev[acct.id] }))}
                            />
                        ))}
                    </div>

                    {accounts.length === 0 && (
                        <div style={{ textAlign: "center", padding: "40px", color: "#9ca3af", fontSize: "14px" }}>
                            暂无账号，点击"添加账号"开始配置
                        </div>
                    )}

                    <p style={{ textAlign: "center", fontSize: "12px", color: "#9ca3af", marginTop: "20px" }}>
                        同步任务将按列表顺序依次尝试各账号，遇到风控时自动切换到下一个。
                    </p>
                </>
            )}
        </div>
    );
}

// ─── AccountCard ──────────────────────────────────────────────────────────────

function AccountCard({
    account: acct,
    isExpanded,
    checkState,
    accountInfo,
    fetchInfoStatus,
    showPassword,
    buildProxyUrl,
    onToggleExpand,
    onUpdate,
    onRemove,
    onSetDefault,
    onCheckCredential,
    onCheckIp,
    onFetchInfo,
    onTogglePassword,
}: {
    account: BilibiliAccount;
    isExpanded: boolean;
    checkState?: AccountCheckState;
    accountInfo?: BilibiliAccountInfo;
    fetchInfoStatus: FetchInfoStatus;
    showPassword: boolean;
    buildProxyUrl: (url: string) => string;
    onToggleExpand: () => void;
    onUpdate: (patch: Partial<BilibiliAccount>) => void;
    onRemove: () => void;
    onSetDefault: () => void;
    onCheckCredential: () => void;
    onCheckIp: () => void;
    onFetchInfo: () => void;
    onTogglePassword: () => void;
}) {
    const proxyMode = getProxyMode(acct.proxy);

    const credIcon = !checkState || checkState.credStatus === "idle" ? null
        : checkState.credStatus === "checking" ? <Loader size={13} className="animate-spin" style={{ color: "#6366f1" }} />
        : checkState.credStatus === "valid" ? <CheckCircle size={13} style={{ color: "#16a34a" }} />
        : <XCircle size={13} style={{ color: "#dc2626" }} />;

    const ipText = checkState?.ipStatus === "done" ? checkState.ipAddress
        : checkState?.ipStatus === "error" ? checkState.ipAddress
        : checkState?.ipStatus === "checking" ? "检测中…"
        : null;

    return (
        <div style={{
            backgroundColor: "#fff",
            border: `1px solid ${acct.is_default ? "#bfdbfe" : "#e5e7eb"}`,
            borderRadius: "10px",
            overflow: "hidden",
        }}>
            {/* Summary row */}
            <div
                onClick={onToggleExpand}
                style={{
                    display: "flex", alignItems: "center", gap: "10px",
                    padding: "12px 16px", cursor: "pointer",
                    backgroundColor: acct.is_default ? "#f0f9ff" : undefined,
                }}
            >
                {isExpanded ? <ChevronDown size={16} style={{ color: "#9ca3af", flexShrink: 0 }} /> : <ChevronRight size={16} style={{ color: "#9ca3af", flexShrink: 0 }} />}

                {accountInfo?.face && (
                    <img
                        src={buildProxyUrl(accountInfo.face)}
                        alt=""
                        style={{ width: 24, height: 24, borderRadius: "50%", flexShrink: 0, objectFit: "cover" }}
                    />
                )}

                <span style={{ fontSize: "14px", fontWeight: 500, color: "#111827" }}>
                    {acct.label || (acct.is_anonymous ? "匿名" : "未命名账号")}
                </span>

                {accountInfo?.name && (
                    <span style={{ fontSize: "12px", color: "#6b7280" }}>
                        {accountInfo.name}
                    </span>
                )}

                {acct.is_anonymous && (
                    <span style={{ fontSize: "11px", padding: "1px 6px", borderRadius: "4px", backgroundColor: "#f3f4f6", color: "#6b7280" }}>匿名</span>
                )}
                {acct.is_default && (
                    <span style={{ fontSize: "11px", padding: "1px 6px", borderRadius: "4px", backgroundColor: "#dbeafe", color: "#2563eb" }}>默认</span>
                )}

                {credIcon && <span style={{ display: "flex", alignItems: "center", marginLeft: "auto" }}>{credIcon}</span>}
                {checkState?.credMessage && (
                    <span style={{ fontSize: "12px", color: checkState.credStatus === "valid" ? "#16a34a" : "#dc2626" }}>
                        {checkState.credMessage}
                    </span>
                )}
                {ipText && (
                    <span style={{ fontSize: "12px", color: checkState?.ipStatus === "error" ? "#dc2626" : "#0891b2", marginLeft: credIcon ? "0" : "auto" }}>
                        IP: {ipText}
                    </span>
                )}
            </div>

            {/* Expanded detail */}
            {isExpanded && (
                <div style={{ borderTop: "1px solid #f3f4f6", padding: "16px" }}>
                    {/* Label + default */}
                    <div style={{ display: "flex", gap: "8px", marginBottom: "12px", alignItems: "center" }}>
                        <input
                            type="text"
                            value={acct.label}
                            placeholder="账号名称"
                            onChange={e => onUpdate({ label: e.target.value })}
                            disabled={acct.is_anonymous}
                            style={{
                                flex: 1, padding: "6px 10px", fontSize: "13px",
                                border: "1px solid #d1d5db", borderRadius: "8px",
                                color: "#374151", boxSizing: "border-box",
                            }}
                        />
                        {!acct.is_anonymous && !acct.is_default && (
                            <button
                                onClick={onSetDefault}
                                style={{
                                    padding: "6px 12px", fontSize: "12px", fontWeight: 500,
                                    color: "#2563eb", background: "#eff6ff",
                                    border: "1px solid #bfdbfe", borderRadius: "6px",
                                    cursor: "pointer", whiteSpace: "nowrap",
                                }}
                            >
                                设为默认
                            </button>
                        )}
                    </div>

                    {/* Credential fields (non-anonymous only) */}
                    {!acct.is_anonymous && (
                        <div style={{ marginBottom: "12px" }}>
                            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "8px" }}>
                                <span style={{ fontSize: "13px", fontWeight: 500, color: "#6b7280" }}>登录凭据</span>
                                <button
                                    onClick={onTogglePassword}
                                    style={{ fontSize: "12px", color: "#6366f1", background: "none", border: "none", cursor: "pointer" }}
                                >
                                    {showPassword ? "隐藏全部" : "显示全部"}
                                </button>
                            </div>
                            {([
                                { key: "sessdata" as const, label: "SESSDATA", placeholder: "浏览器 Cookie 中的 SESSDATA" },
                                { key: "bili_jct" as const, label: "bili_jct", placeholder: "浏览器 Cookie 中的 bili_jct" },
                                { key: "buvid3" as const, label: "buvid3", placeholder: "浏览器 Cookie 中的 buvid3" },
                                { key: "buvid4" as const, label: "buvid4", placeholder: "浏览器 Cookie 中的 buvid4（可选）" },
                                { key: "dedeuserid" as const, label: "DedeUserID", placeholder: "浏览器 Cookie 中的 DedeUserID" },
                                { key: "ac_time_value" as const, label: "ac_time_value", placeholder: "浏览器 Cookie 中的 ac_time_value" },
                            ]).map(({ key, label, placeholder }) => (
                                <div key={key} style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "6px" }}>
                                    <span style={{ fontSize: "13px", color: "#374151", minWidth: "120px", fontWeight: 500 }}>{label}</span>
                                    <PasswordInput
                                        value={acct[key]}
                                        placeholder={placeholder}
                                        onChange={v => onUpdate({ [key]: v })}
                                        show={showPassword}
                                        onShowChange={onTogglePassword}
                                        style={{
                                            padding: "5px 10px", fontSize: "13px",
                                            color: "#374151", border: "1px solid #d1d5db",
                                            borderRadius: "8px", fontFamily: "monospace",
                                        }}
                                    />
                                </div>
                            ))}
                        </div>
                    )}

                    {/* Proxy */}
                    <div style={{ marginBottom: "12px" }}>
                        <span style={{ fontSize: "13px", fontWeight: 500, color: "#6b7280", display: "block", marginBottom: "6px" }}>代理设置</span>
                        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
                            <select
                                value={proxyMode}
                                onChange={e => {
                                    const v = e.target.value;
                                    onUpdate({ proxy: v === "__custom__" ? "http://" : v });
                                }}
                                style={{
                                    padding: "6px 10px", fontSize: "13px",
                                    border: "1px solid #d1d5db", borderRadius: "8px",
                                    color: "#374151", backgroundColor: "#fff",
                                }}
                            >
                                {PROXY_OPTIONS.map(opt => (
                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                            </select>
                            {proxyMode === "__custom__" && (
                                <input
                                    type="text"
                                    value={acct.proxy}
                                    placeholder="http://127.0.0.1:7890"
                                    onChange={e => onUpdate({ proxy: e.target.value })}
                                    style={{
                                        flex: 1, padding: "6px 10px", fontSize: "13px",
                                        border: "1px solid #d1d5db", borderRadius: "8px",
                                        color: "#374151", fontFamily: "monospace", boxSizing: "border-box",
                                    }}
                                />
                            )}
                        </div>
                    </div>

                    {/* Rate limit */}
                    <div style={{ marginBottom: "12px" }}>
                        <span style={{ fontSize: "13px", fontWeight: 500, color: "#6b7280", display: "block", marginBottom: "6px" }}>
                            浏览器指纹 (Impersonate)
                        </span>
                        <select
                            value={acct.impersonate}
                            onChange={e => onUpdate({ impersonate: e.target.value })}
                            style={{
                                padding: "6px 10px", fontSize: "13px",
                                border: "1px solid #d1d5db", borderRadius: "8px",
                                color: "#374151", backgroundColor: "#fff",
                            }}
                        >
                            {IMPERSONATE_OPTIONS.map(opt => (
                                <option key={opt.value} value={opt.value}>{opt.label}</option>
                            ))}
                        </select>
                        <span style={{ fontSize: "12px", color: "#9ca3af", marginLeft: "8px" }}>
                            模拟浏览器 TLS 指纹，降低风控概率
                        </span>
                    </div>

                    {/* Request interval */}
                    <div style={{ marginBottom: "16px" }}>
                        <span style={{ fontSize: "13px", fontWeight: 500, color: "#6b7280", display: "block", marginBottom: "6px" }}>
                            请求间隔（秒）
                        </span>
                        <input
                            type="number"
                            value={acct.rate_limit_sec}
                            min={0}
                            step={0.1}
                            onChange={e => onUpdate({ rate_limit_sec: parseFloat(e.target.value) || 0 })}
                            style={{
                                width: "120px", padding: "6px 10px", fontSize: "13px",
                                border: "1px solid #d1d5db", borderRadius: "8px",
                                color: "#374151", boxSizing: "border-box",
                            }}
                        />
                        <span style={{ fontSize: "12px", color: "#9ca3af", marginLeft: "8px" }}>
                            {acct.is_anonymous ? "匿名建议 ≥ 0.5s" : "登录态建议 ≥ 3.0s"}
                        </span>
                    </div>

                    {/* Account info */}
                    {accountInfo && !acct.is_anonymous && (
                        <div style={{
                            marginBottom: "12px", padding: "10px 14px",
                            backgroundColor: "#f9fafb", borderRadius: "8px",
                            display: "flex", gap: "12px", alignItems: "center",
                        }}>
                            {accountInfo.face && (
                                <img
                                    src={buildProxyUrl(accountInfo.face)}
                                    alt=""
                                    style={{ width: 40, height: 40, borderRadius: "50%", flexShrink: 0, objectFit: "cover" }}
                                />
                            )}
                            <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{ fontSize: "14px", fontWeight: 500, color: "#111827" }}>
                                    {accountInfo.name}
                                    <span style={{
                                        marginLeft: "6px", fontSize: "11px", padding: "1px 5px",
                                        borderRadius: "4px", backgroundColor: "#dbeafe", color: "#2563eb",
                                    }}>
                                        Lv.{accountInfo.level}
                                    </span>
                                </div>
                                <div style={{ fontSize: "12px", color: "#6b7280", marginTop: "2px" }}>
                                    UID: {accountInfo.mid}
                                    {" · "}粉丝 {accountInfo.fans}
                                    {" · "}关注 {accountInfo.following}
                                    {" · "}硬币 {accountInfo.coins}
                                </div>
                                {accountInfo.sign && (
                                    <div style={{ fontSize: "12px", color: "#9ca3af", marginTop: "2px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                                        {accountInfo.sign}
                                    </div>
                                )}
                            </div>
                            <span style={{ fontSize: "11px", color: "#9ca3af", flexShrink: 0 }}>
                                {accountInfo.queried_at > 0
                                    ? new Date(accountInfo.queried_at * 1000).toLocaleString()
                                    : ""}
                            </span>
                        </div>
                    )}

                    {/* Actions */}
                    <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", borderTop: "1px solid #f3f4f6", paddingTop: "12px" }}>
                        <button
                            onClick={onCheckCredential}
                            disabled={checkState?.credStatus === "checking"}
                            style={{
                                display: "flex", alignItems: "center", gap: "5px",
                                padding: "6px 12px", fontSize: "12px", fontWeight: 500,
                                color: "#6366f1", background: "#eef2ff",
                                border: "1px solid #c7d2fe", borderRadius: "6px",
                                cursor: checkState?.credStatus === "checking" ? "default" : "pointer",
                                opacity: checkState?.credStatus === "checking" ? 0.6 : 1,
                            }}
                        >
                            {checkState?.credStatus === "checking" ? <Loader size={12} className="animate-spin" /> : <Shield size={12} />}
                            检测可用性
                        </button>
                        {!acct.is_anonymous && (
                            <button
                                onClick={onCheckIp}
                                disabled={checkState?.ipStatus === "checking"}
                                style={{
                                    display: "flex", alignItems: "center", gap: "5px",
                                    padding: "6px 12px", fontSize: "12px", fontWeight: 500,
                                    color: "#0891b2", background: "#ecfeff",
                                    border: "1px solid #a5f3fc", borderRadius: "6px",
                                    cursor: checkState?.ipStatus === "checking" ? "default" : "pointer",
                                    opacity: checkState?.ipStatus === "checking" ? 0.6 : 1,
                                }}
                            >
                                {checkState?.ipStatus === "checking" ? <Loader size={12} className="animate-spin" /> : <Globe size={12} />}
                                检测 IP
                            </button>
                        )}
                        {!acct.is_anonymous && (
                            <button
                                onClick={onFetchInfo}
                                disabled={fetchInfoStatus === "fetching"}
                                style={{
                                    display: "flex", alignItems: "center", gap: "5px",
                                    padding: "6px 12px", fontSize: "12px", fontWeight: 500,
                                    color: "#7c3aed", background: "#f5f3ff",
                                    border: "1px solid #ddd6fe", borderRadius: "6px",
                                    cursor: fetchInfoStatus === "fetching" ? "default" : "pointer",
                                    opacity: fetchInfoStatus === "fetching" ? 0.6 : 1,
                                }}
                            >
                                {fetchInfoStatus === "fetching" ? <Loader size={12} className="animate-spin" /> : <UserCircle size={12} />}
                                获取账号信息
                            </button>
                        )}
                        {!acct.is_anonymous && (
                            <>
                                <div style={{ flex: 1 }} />
                                <button
                                    onClick={() => openExternalUrl("https://nemo2011.github.io/bilibili-api/#/get-credential")}
                                    style={{ fontSize: "12px", color: "#6366f1", background: "none", border: "none", cursor: "pointer", textDecoration: "underline" }}
                                >
                                    获取凭据教程 ↗
                                </button>
                                <button
                                    onClick={onRemove}
                                    style={{
                                        display: "flex", alignItems: "center", gap: "5px",
                                        padding: "6px 12px", fontSize: "12px", fontWeight: 500,
                                        color: "#dc2626", background: "#fef2f2",
                                        border: "1px solid #fecaca", borderRadius: "6px",
                                        cursor: "pointer",
                                    }}
                                >
                                    <Trash2 size={12} />
                                    删除
                                </button>
                            </>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
