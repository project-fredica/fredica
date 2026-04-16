import { useEffect, useState } from "react";
import { useParams, useSearchParams, useNavigate } from "react-router";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT } from "~/util/app_fetch";

type Status = "loading" | "success" | "error";

function getHostFromParams(sp: URLSearchParams) {
    const s = sp.get("webserver_schema") ?? "http";
    const d = sp.get("webserver_domain") ?? "localhost";
    const p = sp.get("webserver_port") ?? DEFAULT_SERVER_PORT;
    return { schema: s, domain: d, port: p, host: `${s}://${d}:${p}` };
}

export default function GuestInviteLandingPage() {
    const { pathId } = useParams<{ pathId: string }>();
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const { appConfig, setAppConfig, isStorageLoaded } = useAppConfig();

    const [status, setStatus] = useState<Status>("loading");
    const [label, setLabel] = useState<string | null>(null);
    const [errorMsg, setErrorMsg] = useState<string | null>(null);
    const [countdown, setCountdown] = useState(3);

    useEffect(() => {
        if (!isStorageLoaded || !pathId) return;

        const { schema, domain, port, host } = getHostFromParams(searchParams);

        (async () => {
            try {
                const resp = await fetch(
                    `${host}/api/v1/GuestInviteLandingRoute?path_id=${encodeURIComponent(pathId)}`,
                );
                const data = await resp.json();
                if (!resp.ok || data.error) {
                    setErrorMsg(data.error ?? `HTTP ${resp.status}`);
                    setStatus("error");
                    return;
                }
                // 成功：写入连接信息
                setLabel(data.label ?? null);
                setAppConfig({
                    webserver_schema: schema as "http" | "https",
                    webserver_domain: domain,
                    webserver_port: port,
                    user_role: "guest",
                    session_token: null,
                    user_display_name: null,
                    user_permissions: null,
                });
                setStatus("success");
            } catch {
                setErrorMsg("无法连接到服务器");
                setStatus("error");
            }
        })();
    }, [isStorageLoaded, pathId]); // eslint-disable-line react-hooks/exhaustive-deps

    // 构建跳转到登录页的 URL（携带连接信息 + 游客令牌）
    const buildLoginUrl = () => {
        const loginParams = new URLSearchParams();
        const info = getHostFromParams(searchParams);
        loginParams.set("webserver_schema", info.schema);
        loginParams.set("webserver_domain", info.domain);
        loginParams.set("webserver_port", info.port);
        const authToken = searchParams.get("webserver_auth_token");
        if (authToken) loginParams.set("webserver_auth_token", authToken);
        return `/login?${loginParams.toString()}`;
    };

    // 成功后倒计时跳转
    useEffect(() => {
        if (status !== "success") return;
        if (countdown <= 0) {
            navigate(buildLoginUrl(), { replace: true });
            return;
        }
        const timer = setTimeout(() => setCountdown(c => c - 1), 1000);
        return () => clearTimeout(timer);
    }, [status, countdown, navigate]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="w-full max-w-sm">
                {status === "loading" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <p className="text-sm text-slate-500">正在验证邀请链接...</p>
                    </div>
                )}

                {status === "success" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <div className="mb-4 flex justify-center">
                            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-green-50">
                                <svg className="h-6 w-6 text-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                                </svg>
                            </div>
                        </div>
                        <h1 className="mb-2 text-lg font-semibold text-slate-800">连接成功</h1>
                        {label && (
                            <p className="mb-2 text-sm text-slate-500">{label}</p>
                        )}
                        <p className="text-sm text-slate-400">
                            已配置服务器连接，{countdown} 秒后跳转到登录页...
                        </p>
                        <button
                            onClick={() => navigate(buildLoginUrl(), { replace: true })}
                            className="mt-4 rounded-lg bg-slate-800 px-4 py-2 text-sm text-white hover:bg-slate-700 cursor-pointer"
                        >
                            立即前往登录
                        </button>
                    </div>
                )}

                {status === "error" && (
                    <div className="rounded-2xl border border-slate-200 bg-white p-8 shadow-sm text-center">
                        <div className="mb-4 flex justify-center">
                            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-red-50">
                                <svg className="h-6 w-6 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                                </svg>
                            </div>
                        </div>
                        <h1 className="mb-2 text-lg font-semibold text-slate-800">链接无效</h1>
                        <p className="text-sm text-red-500">{errorMsg}</p>
                    </div>
                )}
            </div>
        </div>
    );
}
