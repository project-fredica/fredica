import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mockApiFetch = vi.fn();

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

import { useMaterialCategorySyncProgress } from "~/hooks/useMaterialCategorySyncProgress";

beforeEach(() => {
    vi.useFakeTimers();
    mockApiFetch.mockReset();
});

afterEach(() => {
    vi.useRealTimers();
});

function makeTasksResponse(tasks: Array<{ id: string; status: string; progress: number }>) {
    return {
        resp: { ok: true },
        data: {
            items: tasks.map(t => ({
                id: t.id,
                type: "SYNC",
                material_id: "m1",
                pipeline_id: "p1",
                status: t.status,
                result: null,
                error: null,
                error_type: null,
                progress: t.progress,
                is_paused: false,
                is_pausable: true,
                created_at: 0,
            })),
        },
    };
}

// ── FE11: 进度 hook 轮询 ────────────────────────────────────────────────────

describe("FE11 – poll with workflowRunId", () => {
    it("polls every 2s and stops on terminal state", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeTasksResponse([
                { id: "t1", status: "running", progress: 50 },
            ]))
            .mockResolvedValueOnce(makeTasksResponse([
                { id: "t1", status: "completed", progress: 100 },
            ]));

        const { result } = renderHook(() =>
            useMaterialCategorySyncProgress("wfr-123")
        );

        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
        expect(result.current).not.toBeNull();
        expect(result.current!.status).toBe("running");
        expect(result.current!.progress).toBe(50);
        expect(mockApiFetch).toHaveBeenCalledTimes(1);

        const firstCallUrl = mockApiFetch.mock.calls[0][0] as string;
        expect(firstCallUrl).toContain("WorkerTaskListRoute");
        expect(firstCallUrl).toContain("workflow_run_id=wfr-123");

        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
        expect(result.current!.status).toBe("completed");
        expect(result.current!.progress).toBe(100);

        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
        expect(mockApiFetch).toHaveBeenCalledTimes(2);
    });

    it("reports failed when any task fails", async () => {
        mockApiFetch.mockResolvedValueOnce(makeTasksResponse([
            { id: "t1", status: "completed", progress: 100 },
            { id: "t2", status: "failed", progress: 0 },
        ]));

        const { result } = renderHook(() =>
            useMaterialCategorySyncProgress("wfr-fail")
        );

        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
        expect(result.current!.status).toBe("failed");
        expect(result.current!.progress).toBe(100);
    });

    it("computes average progress across tasks", async () => {
        mockApiFetch.mockResolvedValueOnce(makeTasksResponse([
            { id: "t1", status: "running", progress: 40 },
            { id: "t2", status: "running", progress: 60 },
        ]));

        const { result } = renderHook(() =>
            useMaterialCategorySyncProgress("wfr-avg")
        );

        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
        expect(result.current!.progress).toBe(50);
    });
});

// ── FE11b: workflowRunId 为 null 时不轮询 ──────────────────────────────────

describe("FE11b – null workflowRunId does not poll", () => {
    it("returns null and makes no API calls", async () => {
        const { result } = renderHook(() =>
            useMaterialCategorySyncProgress(null)
        );

        await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
        expect(result.current).toBeNull();
        expect(mockApiFetch).not.toHaveBeenCalled();
    });

    it("stops polling when workflowRunId changes to null", async () => {
        mockApiFetch.mockResolvedValue(makeTasksResponse([
            { id: "t1", status: "running", progress: 30 },
        ]));

        const { result, rerender } = renderHook(
            ({ id }: { id: string | null }) => useMaterialCategorySyncProgress(id),
            { initialProps: { id: "wfr-x" as string | null } },
        );

        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
        expect(result.current).not.toBeNull();

        rerender({ id: null });
        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
        expect(result.current).toBeNull();

        const callsBefore = mockApiFetch.mock.calls.length;
        await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
        expect(mockApiFetch.mock.calls.length).toBe(callsBefore);
    });
});
