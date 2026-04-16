
import { useEffect, useState } from "react";
import type { Route } from "./+types/_index";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { RequireAuth } from "~/components/auth/RequireAuth";
import { useSearchParams } from "react-router";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT, useAppFetch } from "~/util/app_fetch";
import { reportHttpError } from "~/util/error_handler";
import { isBridgeAvailable } from "~/util/bridge";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "Fredica" },
        { name: "description", content: "Fredica: Elegant Precision for Learning" },
    ];
}

type PingStatus = "idle" | "pinging" | "ok" | "error";

async function doPing(schema: string, domain: string, port: string): Promise<{ ok: boolean; error?: string }> {
    const url = `${schema}://${domain}:${port}/api/v1/ping`;
    try {
        const resp = await fetch(url);
        if (resp.ok) return { ok: true };
        return { ok: false, error: `Server returned ${resp.status}` };
    } catch (err) {
        return { ok: false, error: err instanceof Error ? err.message : String(err) };
    }
}

function ServerSettingsPanel() {
    const { appConfig, setAppConfig } = useAppConfig();
    const { apiFetch } = useAppFetch();
    // 桌面端用户即服务主人，等同 root；Web 端检查 user_role
    const isRoot = appConfig.user_role === "root" || isBridgeAvailable();

    const connUrl = `${appConfig.webserver_schema ?? "http"}://${appConfig.webserver_domain ?? "localhost"}:${appConfig.webserver_port ?? DEFAULT_SERVER_PORT}`;

    // Guest token management (ROOT or desktop bridge)
    const [guestToken, setGuestToken] = useState("");
    const [guestTokenLoading, setGuestTokenLoading] = useState(false);
    const [guestTokenSaving, setGuestTokenSaving] = useState(false);
    const [guestTokenMsg, setGuestTokenMsg] = useState<{ type: "ok" | "err"; text: string } | null>(null);

    useEffect(() => {
        if (!isRoot) return;
        let cancelled = false;
        setGuestTokenLoading(true);
        apiFetch("/api/v1/WebserverAuthTokenGetRoute", { method: "GET" }, { silent: true })
            .then(({ data }) => {
                if (!cancelled && data && typeof (data as any).webserver_auth_token === "string") {
                    setGuestToken((data as any).webserver_auth_token);
                }
            })
            .catch(() => {})
            .finally(() => { if (!cancelled) setGuestTokenLoading(false); });
        return () => { cancelled = true; };
    }, [isRoot, apiFetch]);

    const handleSaveGuestToken = async () => {
        setGuestTokenSaving(true);
        setGuestTokenMsg(null);
        try {
            const { resp } = await apiFetch("/api/v1/WebserverAuthTokenUpdateRoute", {
                method: "POST",
                body: JSON.stringify({ webserver_auth_token: guestToken }),
            });
            if (!resp.ok) {
                reportHttpError("保存游客令牌失败", resp);
                setGuestTokenMsg({ type: "err", text: `保存失败: HTTP ${resp.status}` });
                return;
            }
            setGuestTokenMsg({ type: "ok", text: "已保存" });
        } catch {
            setGuestTokenMsg({ type: "err", text: "网络错误" });
        } finally {
            setGuestTokenSaving(false);
        }
    };

    const handleDisconnect = () => {
        setAppConfig({ webserver_domain: null, webserver_port: null, webserver_schema: null });
    };

    return (
        <div className="max-w-lg mx-auto py-10 px-4">
            <h1 className="text-lg font-semibold text-slate-800 mb-6">服务器设置</h1>

            {/* 连接信息 */}
            <div className="rounded-xl border border-slate-200 bg-white p-5 mb-4">
                <h2 className="text-sm font-medium text-slate-600 mb-2">当前连接</h2>
                <p className="text-sm text-slate-800 font-mono mb-3">{connUrl}</p>
                <button
                    onClick={handleDisconnect}
                    className="text-xs px-3 py-1.5 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 cursor-pointer"
                >
                    断开连接
                </button>
            </div>

            {/* 游客令牌管理 (ROOT only) */}
            {isRoot && (
                <div className="rounded-xl border border-slate-200 bg-white p-5">
                    <h2 className="text-sm font-medium text-slate-600 mb-3">游客访问令牌</h2>
                    {guestTokenLoading ? (
                        <p className="text-sm text-slate-400">加载中...</p>
                    ) : (
                        <>
                            <input
                                type="text"
                                value={guestToken}
                                onChange={e => setGuestToken(e.target.value)}
                                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400 font-mono mb-3"
                                placeholder="留空则禁用游客访问"
                            />
                            <div className="flex items-center gap-3">
                                <button
                                    onClick={handleSaveGuestToken}
                                    disabled={guestTokenSaving}
                                    className="text-xs px-3 py-1.5 rounded-lg bg-slate-800 text-white hover:bg-slate-700 disabled:opacity-50 cursor-pointer"
                                >
                                    {guestTokenSaving ? "保存中..." : "保存"}
                                </button>
                                {guestTokenMsg && (
                                    <span className={`text-xs ${guestTokenMsg.type === "ok" ? "text-green-600" : "text-red-500"}`}>
                                        {guestTokenMsg.text}
                                    </span>
                                )}
                            </div>
                        </>
                    )}
                </div>
            )}
        </div>
    );
}

export default function Component({
    params,
}: Route.ComponentProps) {
    const [searchParams, setSearchParams] = useSearchParams();
    const webserver_domain = searchParams.get("webserver_domain");
    const webserver_port = searchParams.get("webserver_port");
    const webserver_schema = searchParams.get("webserver_schema") as "http" | "https" | null;
    const webserver_auth_token = searchParams.get("webserver_auth_token");
    const { appConfig, setAppConfig, isStorageLoaded } = useAppConfig();

    const [formSchema, setFormSchema] = useState<"http" | "https">("http");
    const [formDomain, setFormDomain] = useState("localhost");
    const [formPort, setFormPort] = useState(DEFAULT_SERVER_PORT);
    const [formAuthToken, setFormAuthToken] = useState(appConfig.webserver_auth_token ?? "");

    // URL 参数在 SSR 阶段即可同步获取，可安全用于初始化 pingStatus
    const willAutoPingFromUrl =
        webserver_domain != null || webserver_port != null || webserver_schema != null || webserver_auth_token != null;
    const [pingStatus, setPingStatus] = useState<PingStatus>(willAutoPingFromUrl ? "pinging" : "idle");
    const [pingError, setPingError] = useState<string | null>(null);

    const triggerPing = async (schema: string, domain: string, port: string) => {
        setPingStatus("pinging");
        setPingError(null);
        const result = await doPing(schema, domain, port);
        if (result.ok) {
            setPingStatus("ok");
        } else {
            setPingError(result.error ?? "Unknown error");
            setPingStatus("error");
        }
    };

    // 当 appConfig 变化时（如 URL 参数写入），同步更新表单字段
    useEffect(() => {
        setFormSchema(appConfig.webserver_schema ?? "http");
        setFormDomain(appConfig.webserver_domain ?? "localhost");
        setFormPort(appConfig.webserver_port ?? DEFAULT_SERVER_PORT);
        setFormAuthToken(appConfig.webserver_auth_token ?? "");
    }, [appConfig.webserver_schema, appConfig.webserver_domain, appConfig.webserver_port, appConfig.webserver_auth_token]);

    // URL 参数优先：由外部（如桌面端）传入新配置时更新并 ping
    useEffect(() => {
        if (webserver_domain != null || webserver_port != null || webserver_schema != null || webserver_auth_token != null) {
            console.debug('reset url param ...', { webserver_domain, webserver_port, webserver_schema, webserver_auth_token })
            setAppConfig({ webserver_domain, webserver_port, webserver_schema, ...(webserver_auth_token != null ? { webserver_auth_token } : {}) });
            setSearchParams(new URLSearchParams());
            triggerPing(
                webserver_schema ?? "http",
                webserver_domain ?? "localhost",
                webserver_port ?? DEFAULT_SERVER_PORT,
            );
        }
    }, [webserver_domain, webserver_port, webserver_schema, webserver_auth_token])

    // localStorage 加载完成后自动恢复连接（若有配置且无 URL 参数）
    useEffect(() => {
        if (!isStorageLoaded) return;
        const hasUrlParams = webserver_domain != null || webserver_port != null || webserver_schema != null || webserver_auth_token != null;
        if (!hasUrlParams && appConfig.webserver_domain != null) {
            triggerPing(
                appConfig.webserver_schema ?? "http",
                appConfig.webserver_domain,
                appConfig.webserver_port ?? DEFAULT_SERVER_PORT,
            );
        }
    }, [isStorageLoaded]) // eslint-disable-line react-hooks/exhaustive-deps

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        setAppConfig({
            webserver_domain: formDomain,
            webserver_port: formPort,
            webserver_schema: formSchema,
            ...(formAuthToken ? { webserver_auth_token: formAuthToken } : {}),
        });
        triggerPing(formSchema, formDomain, formPort);
    };

    if (pingStatus === "ok") {
        return (
            <RequireAuth>
                <SidebarLayout>
                    <ServerSettingsPanel />
                </SidebarLayout>
            </RequireAuth>
        );
    }

    // isStorageLoaded 后若有保存的配置且尚未开始 ping，说明即将自动 ping，提前显示 Connecting
    const isAboutToAutoPing = isStorageLoaded && appConfig.webserver_domain != null && pingStatus === "idle";

    if (pingStatus === "pinging" || pingStatus === "idle" && !isStorageLoaded || isAboutToAutoPing) {
        return (
            <div className="flex min-h-screen items-center justify-center text-slate-400 text-sm">
                Connecting...
            </div>
        );
    }

    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
                <h1 className="mb-6 text-lg font-semibold text-slate-800">Connect to Server</h1>
                <form onSubmit={handleSubmit} className="flex flex-col gap-4">
                    <div className="flex flex-col gap-1">
                        <label className="text-sm text-slate-600">Schema</label>
                        <select
                            value={formSchema}
                            onChange={e => setFormSchema(e.target.value as "http" | "https")}
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                        >
                            <option value="http">http</option>
                            <option value="https">https</option>
                        </select>
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-sm text-slate-600">Domain</label>
                        <input
                            type="text"
                            value={formDomain}
                            onChange={e => setFormDomain(e.target.value)}
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                            placeholder="localhost"
                            required
                        />
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-sm text-slate-600">Port</label>
                        <input
                            type="number"
                            value={formPort}
                            onChange={e => setFormPort(e.target.value)}
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                            placeholder={DEFAULT_SERVER_PORT}
                            min={1}
                            max={65535}
                            required
                        />
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-sm text-slate-600">Guest Token</label>
                        <input
                            type="password"
                            value={formAuthToken}
                            onChange={e => setFormAuthToken(e.target.value)}
                            className="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-400"
                            placeholder="可选，用于游客访问"
                        />
                    </div>
                    {pingStatus === "error" && (
                        <p className="text-sm text-red-500">{pingError}</p>
                    )}
                    <button
                        type="submit"
                        className="mt-2 rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 cursor-pointer"
                    >
                        Connect
                    </button>
                </form>
            </div>
        </div>
    );
}
