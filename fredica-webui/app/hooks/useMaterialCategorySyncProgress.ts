import { useEffect, useRef, useState } from "react";
import { useAppFetch } from "~/util/app_fetch";
import { MODAL_TASK_POLL_MS } from "~/components/material-library/materialTypes";
import type { WorkerTask } from "~/components/material-library/materialTypes";

export interface SyncProgressState {
    status: string;
    progress: number;
    tasks: WorkerTask[];
}

const TERMINAL_STATUSES = new Set(["completed", "failed", "cancelled"]);

export function useMaterialCategorySyncProgress(workflowRunId: string | null): SyncProgressState | null {
    const [state, setState] = useState<SyncProgressState | null>(null);
    const { apiFetch } = useAppFetch();
    const stoppedRef = useRef(false);

    useEffect(() => {
        stoppedRef.current = false;
        setState(null);
        if (!workflowRunId) return;

        const poll = async () => {
            if (stoppedRef.current) return;
            try {
                const params = new URLSearchParams({ workflow_run_id: workflowRunId });
                const { resp, data } = await apiFetch<{ items: WorkerTask[] }>(
                    `/api/v1/WorkerTaskListRoute?${params.toString()}`,
                    { method: 'GET' },
                    { silent: true },
                );
                if (!resp.ok || stoppedRef.current) return;
                const tasks = data?.items ?? [];
                const allDone = tasks.length > 0 && tasks.every(t => TERMINAL_STATUSES.has(t.status));
                const hasFailed = tasks.some(t => t.status === 'failed');
                const progress = tasks.length > 0
                    ? Math.round(tasks.reduce((sum, t) => sum + (TERMINAL_STATUSES.has(t.status) ? 100 : t.progress), 0) / tasks.length)
                    : 0;
                const status = allDone ? (hasFailed ? 'failed' : 'completed') : 'running';
                setState({ status, progress, tasks });
                if (allDone) stoppedRef.current = true;
            } catch {
                // network error — keep polling
            }
        };

        poll();
        const id = setInterval(poll, MODAL_TASK_POLL_MS);
        return () => { stoppedRef.current = true; clearInterval(id); };
    }, [workflowRunId, apiFetch]);

    return state;
}
