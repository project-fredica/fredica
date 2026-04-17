import { useState } from "react";
import type { LlmResponseMeta } from "~/util/llm";
import { useAppConfig } from "~/context/appConfig";
import { buildAuthHeaders } from "~/util/app_fetch";

interface Props {
    meta: LlmResponseMeta | null;
    onRefresh?: () => void;
}

export function LlmCacheSourceBadge({ meta, onRefresh }: Props) {
    const { appConfig } = useAppConfig();
    const { webserver_domain, webserver_port, webserver_schema } = appConfig;
    const [invalidating, setInvalidating] = useState(false);

    if (!meta || meta.source !== "CACHE") return null;

    const handleInvalidate = async () => {
        if (!meta.keyHash || invalidating) return;
        setInvalidating(true);
        try {
            const s = webserver_schema ?? "http";
            const d = webserver_domain ?? "localhost";
            const p = webserver_port ?? "7631";
            const resp = await fetch(`${s}://${d}:${p}/api/v1/LlmCacheInvalidateRoute`, {
                method: "POST",
                headers: {
                    ...buildAuthHeaders(appConfig),
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({ key_hash: meta.keyHash }),
            });
            if (resp.ok && onRefresh) {
                onRefresh();
            }
        } finally {
            setInvalidating(false);
        }
    };

    return (
        <div className="flex items-center gap-2 text-sm">
            <span className="px-2 py-1 bg-blue-100 text-blue-700 rounded">
                缓存命中
            </span>
            <button
                onClick={handleInvalidate}
                disabled={invalidating}
                className="px-2 py-1 text-blue-600 hover:text-blue-800 disabled:opacity-50"
            >
                {invalidating ? "刷新中..." : "刷新"}
            </button>
        </div>
    );
}
