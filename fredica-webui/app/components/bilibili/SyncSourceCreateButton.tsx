import { useState, useEffect, useRef } from "react";
import { RefreshCw, Check, AlertCircle } from "lucide-react";
import { useAppFetch } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { SYNC_TYPE_LABELS } from "~/components/material-library/materialTypes";

interface SyncSourceCreateButtonProps {
    syncType: string;
    platformConfig: Record<string, unknown>;
}

type Status = "idle" | "checking" | "loading" | "success" | "exists" | "error";

export function SyncSourceCreateButton({ syncType, platformConfig }: SyncSourceCreateButtonProps) {
    const { apiFetch } = useAppFetch();
    const [status, setStatus] = useState<Status>("checking");
    const [errorMsg, setErrorMsg] = useState("");
    const platformKey = syncType + ":" + JSON.stringify(platformConfig);
    const checkVersionRef = useRef(0);

    useEffect(() => {
        const version = ++checkVersionRef.current;
        setStatus("checking");
        setErrorMsg("");

        let cancelled = false;
        (async () => {
            try {
                const { resp, data } = await apiFetch<{
                    exists?: boolean;
                    subscribed?: boolean;
                    error?: string;
                }>("/api/v1/MaterialCategorySyncCheckRoute", {
                    method: "POST",
                    body: JSON.stringify({
                        sync_type: syncType,
                        platform_config: platformConfig,
                    }),
                });
                if (cancelled || checkVersionRef.current !== version) return;
                if (!resp.ok || data?.error) {
                    setStatus("idle");
                    return;
                }
                if (data?.subscribed) {
                    setStatus("exists");
                } else {
                    setStatus("idle");
                }
            } catch {
                if (cancelled || checkVersionRef.current !== version) return;
                setStatus("idle");
            }
        })();

        return () => { cancelled = true; };
    }, [platformKey]);

    const typeLabel = SYNC_TYPE_LABELS[syncType] ?? syncType;

    const handleCreate = async () => {
        if (status === "loading") return;
        setStatus("loading");
        setErrorMsg("");

        try {
            const { resp, data } = await apiFetch<{
                is_new_platform?: boolean;
                error?: string;
            }>("/api/v1/MaterialCategorySyncCreateRoute", {
                method: "POST",
                body: JSON.stringify({
                    sync_type: syncType,
                    platform_config: platformConfig,
                }),
            });

            if (!resp.ok) {
                reportHttpError("创建同步源失败", resp);
                setStatus("error");
                setErrorMsg(`HTTP ${resp.status}`);
                return;
            }

            if (data?.error) {
                setStatus("error");
                setErrorMsg(data.error);
                return;
            }

            setStatus(data?.is_new_platform === false ? "exists" : "success");
        } catch (err) {
            print_error({ reason: "创建同步源时发生网络错误", err });
            setStatus("error");
            setErrorMsg("网络错误");
        }
    };

    const buttonText = {
        idle: `创建${typeLabel}同步源`,
        checking: "检查中...",
        loading: "创建中...",
        success: "同步源已创建",
        exists: "同步源已存在",
        error: "创建失败，点击重试",
    }[status];

    const buttonClass = {
        idle: "text-emerald-700 bg-emerald-50 border-emerald-200 hover:bg-emerald-100",
        checking: "text-gray-500 bg-gray-50 border-gray-200 cursor-not-allowed",
        loading: "text-gray-500 bg-gray-50 border-gray-200 cursor-not-allowed",
        success: "text-emerald-700 bg-emerald-50 border-emerald-200",
        exists: "text-amber-700 bg-amber-50 border-amber-200",
        error: "text-red-700 bg-red-50 border-red-200 hover:bg-red-100",
    }[status];

    const icon = {
        idle: <RefreshCw className="w-3.5 h-3.5" />,
        checking: <RefreshCw className="w-3.5 h-3.5 animate-spin" />,
        loading: <RefreshCw className="w-3.5 h-3.5 animate-spin" />,
        success: <Check className="w-3.5 h-3.5" />,
        exists: <Check className="w-3.5 h-3.5" />,
        error: <AlertCircle className="w-3.5 h-3.5" />,
    }[status];

    return (
        <div className="flex items-center gap-2">
            <button
                onClick={handleCreate}
                disabled={status === "checking" || status === "loading" || status === "success" || status === "exists"}
                className={`flex items-center gap-1.5 px-4 py-2 text-sm font-medium border rounded-lg transition-colors disabled:cursor-not-allowed ${buttonClass}`}
            >
                {icon}
                {buttonText}
            </button>
            {status === "error" && errorMsg && (
                <span className="text-xs text-red-500">{errorMsg}</span>
            )}
        </div>
    );
}
