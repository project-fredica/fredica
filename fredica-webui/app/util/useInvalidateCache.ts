import { useCallback } from "react";
import { useAppConfig } from "~/context/appConfig";
import { buildAuthHeaders } from "./app_fetch";
import type { LlmResponseMeta } from "./llm";

export function useInvalidateCache() {
    const { appConfig } = useAppConfig();
    const { webserver_domain, webserver_port, webserver_schema } = appConfig;

    const invalidate = useCallback(async (keyHash: string) => {
        const s = webserver_schema ?? "http";
        const d = webserver_domain ?? "localhost";
        const p = webserver_port ?? "7631";
        await fetch(`${s}://${d}:${p}/api/v1/LlmCacheInvalidateRoute`, {
            method: "POST",
            headers: {
                ...buildAuthHeaders(appConfig),
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ key_hash: keyHash }),
        });
    }, [webserver_schema, webserver_domain, webserver_port, appConfig]);

    return { invalidate };
}

export function shouldAutoInvalidate(meta: LlmResponseMeta | null): boolean {
    return meta?.source === "CACHE";
}
