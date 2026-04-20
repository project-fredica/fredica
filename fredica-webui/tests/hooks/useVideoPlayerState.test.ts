/**
 * useVideoPlayerState.test.ts
 *
 * useVideoPlayerState hook 单元测试。
 * 覆盖设计文档 §11.3 的 V1–V14 测试用例。
 */

import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useVideoPlayerState } from "~/hooks/useVideoPlayerState";

// ── 模拟 useAppConfig ────────────────────────────────────────────────────────

vi.mock("~/context/appConfig", () => ({
    useAppConfig: () => ({
        appConfig: {
            webserver_schema: "http",
            webserver_domain: "localhost",
            webserver_port: "7631",
            webserver_auth_token: "test-token",
        },
    }),
}));

// ── 辅助类型 & fetch 工厂 ─────────────────────────────────────────────────────

type CheckResult = { ready: boolean; file_mtime: number | null; file_size: number | null };

/** 返回空活跃工作流列表的 mock 响应（视频未就绪且无正在运行的任务）*/
const EMPTY_ACTIVE_WORKFLOWS = { workflow_runs: [] };

function mockCheckReady(result: CheckResult) {
    return vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(result),
    });
}

/** 返回一个挂起的 fetch mock，和 resolve 函数，用于控制 fetch 何时完成 */
function deferredCheckFetch(result: CheckResult) {
    let resolve!: () => void;
    const promise = new Promise<void>(r => { resolve = r; });
    const fetchMock = vi.fn().mockReturnValue(
        promise.then(() => ({
            ok: true,
            json: () => Promise.resolve(result),
        }))
    );
    return { fetchMock, resolve };
}

// ── beforeEach / afterEach ────────────────────────────────────────────────────

beforeEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    localStorage.clear();
});

afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

// ── V1: mount → CHECKING ─────────────────────────────────────────────────────

describe("V1 – mount starts in CHECKING state", () => {
    it("initial state is checking before fetch resolves", () => {
        // fetch never resolves → stays in checking
        vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
        const { result } = renderHook(() => useVideoPlayerState("mat-v1"));
        expect(result.current.state).toBe("checking");
    });
});

// ── V2: CHECKING + check ok, no pendingSeek → PAUSED ────────────────────────

describe("V2 – check ok with no pending seek → PAUSED", () => {
    it("transitions to paused, pendingSeek is null, fileMtime is set", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 12345, file_size: 1000 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v2"));
        await act(async () => {});
        expect(result.current.state).toBe("paused");
        expect(result.current.pendingSeek).toBeNull();
        expect(result.current.fileMtime).toBe(12345);
    });
});

// ── V3: CHECKING + check ok + pre-set pendingSeek{autoPlay:true} → preserved ─

describe("V3 – check ok with pre-set pendingSeek{autoPlay:true} → preserved after PAUSED", () => {
    it("pendingSeek is preserved when set before check resolves", async () => {
        const { fetchMock, resolve } = deferredCheckFetch({ ready: true, file_mtime: 200, file_size: 500 });
        vi.stubGlobal("fetch", fetchMock);

        const { result } = renderHook(() => useVideoPlayerState("mat-v3"));
        expect(result.current.state).toBe("checking");

        // Set pendingSeek before fetch resolves
        act(() => {
            result.current.setPendingSeek({ seconds: 30, autoPlay: true });
        });
        expect(result.current.pendingSeek).toEqual({ seconds: 30, autoPlay: true });

        // Now let the check resolve
        await act(async () => { resolve(); });

        expect(result.current.state).toBe("paused");
        // prev was already set → ?? keeps it
        expect(result.current.pendingSeek).toEqual({ seconds: 30, autoPlay: true });
    });
});

// ── V4: CHECKING + check ok + pre-set pendingSeek{autoPlay:false} → preserved ─

describe("V4 – check ok with pre-set pendingSeek{autoPlay:false} → preserved after PAUSED", () => {
    it("pendingSeek{autoPlay:false} is preserved when set before check resolves", async () => {
        const { fetchMock, resolve } = deferredCheckFetch({ ready: true, file_mtime: 200, file_size: 500 });
        vi.stubGlobal("fetch", fetchMock);

        const { result } = renderHook(() => useVideoPlayerState("mat-v4"));
        act(() => {
            result.current.setPendingSeek({ seconds: 30, autoPlay: false });
        });

        await act(async () => { resolve(); });

        expect(result.current.state).toBe("paused");
        expect(result.current.pendingSeek).toEqual({ seconds: 30, autoPlay: false });
    });
});

// ── V5: CHECKING + check fail + no active workflow → NEEDS_ENCODE ─────────────

describe("V5 – check returns not ready and no active workflow → NEEDS_ENCODE", () => {
    it("transitions to needs_encode when video is not ready and no active workflow exists", async () => {
        const mockF = vi.fn()
            // 1. MaterialVideoCheckRoute → not ready
            .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }) })
            // 2. MaterialWorkflowStatusRoute?mode=active → no active workflows
            .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(EMPTY_ACTIVE_WORKFLOWS) });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v5"));
        await act(async () => {});
        expect(result.current.state).toBe("needs_encode");
    });
});

// ── V6: startEncode() → ENCODING + TranscodeMp4Route called ─────────────────

describe("V6 – startEncode transitions to ENCODING and calls transcode API", () => {
    it("calls MaterialVideoTranscodeMp4Route and sets state to encoding", async () => {
        const mockF = vi.fn()
            // 1. MaterialVideoCheckRoute → not ready
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }),
            })
            // 2. MaterialWorkflowStatusRoute?mode=active → no active workflows
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve(EMPTY_ACTIVE_WORKFLOWS),
            })
            // 3. startEncode POST → workflow_run_id
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ workflow_run_id: "wfr-test-123" }),
            });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v6"));
        await act(async () => {}); // → needs_encode
        expect(result.current.state).toBe("needs_encode");

        await act(async () => { await result.current.startEncode(); });

        expect(result.current.state).toBe("encoding");
        expect(result.current.workflowRunId).toBe("wfr-test-123");

        // calls[2] は startEncode の POST
        const encodeCall = mockF.mock.calls[2];
        expect((encodeCall[0] as string)).toContain("MaterialVideoTranscodeMp4Route");
        expect((encodeCall[1] as RequestInit).method).toBe("POST");
    });
});

// ── V7: ENCODING + polling detects ready → PAUSED ────────────────────────────

describe("V7 – encoding poll detects ready video → PAUSED (not CHECKING)", () => {
    it("transitions directly from encoding to paused once video becomes ready", async () => {
        vi.useFakeTimers();

        const mockF = vi.fn()
            // 1. Mount check → not ready
            .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }) })
            // 2. Active workflow query → no active workflows
            .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(EMPTY_ACTIVE_WORKFLOWS) })
            // 3. startEncode response
            .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ workflow_run_id: "wfr-v7" }) })
            // 4+. Encoding poll → ready
            .mockResolvedValue({ ok: true, json: () => Promise.resolve({ ready: true, file_mtime: 999, file_size: 500 }) });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v7"));
        await act(async () => {}); // → needs_encode

        await act(async () => { await result.current.startEncode(); }); // → encoding
        expect(result.current.state).toBe("encoding");

        // Trigger encoding poll interval (3000ms)
        await act(async () => {
            vi.advanceTimersByTime(3100);
            await Promise.resolve();
        });
        await act(async () => {}); // flush state updates

        // Bug fix: must be "paused", NOT "checking" (mount effect does not re-run on state change)
        expect(result.current.state).toBe("paused");
        expect(result.current.fileMtime).toBe(999);

        vi.useRealTimers();
    });
});

// ── V6b: bilibili 素材 startEncode 调 MaterialBilibiliDownloadTranscodeRoute ──

describe("V6b – bilibili sourceType calls MaterialBilibiliDownloadTranscodeRoute", () => {
    it("calls MaterialBilibiliDownloadTranscodeRoute", async () => {
        const mockF = vi.fn()
            // 1. Mount check → not ready
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }),
            })
            // 2. Active workflow query → no active workflows
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve(EMPTY_ACTIVE_WORKFLOWS),
            })
            // 3. startEncode POST → workflow_run_id
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ workflow_run_id: "wfr-bilibili-123" }),
            });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v6b", "video", "bilibili"));
        await act(async () => {}); // → needs_encode

        await act(async () => { await result.current.startEncode(); });

        expect(result.current.state).toBe("encoding");
        expect(result.current.workflowRunId).toBe("wfr-bilibili-123");

        // calls[2] は startEncode の POST
        const encodeCall = mockF.mock.calls[2];
        expect((encodeCall[0] as string)).toContain("MaterialBilibiliDownloadTranscodeRoute");
        const body = JSON.parse((encodeCall[1] as RequestInit).body as string);
        expect(body.material_id).toBe("mat-v6b");
    });
});

// ── V8: PAUSED + onPlaybackStarted → PLAYING ─────────────────────────────────

describe("V8 – onPlaybackStarted transitions to PLAYING", () => {
    it("transitions from paused to playing", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v8"));
        await act(async () => {}); // → paused

        act(() => { result.current.onPlaybackStarted(10); });
        expect(result.current.state).toBe("playing");
    });
});

// ── V9: PLAYING + onPlaybackPaused → PAUSED ──────────────────────────────────

describe("V9 – onPlaybackPaused transitions to PAUSED", () => {
    it("transitions from playing to paused", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v9"));
        await act(async () => {});
        act(() => { result.current.onPlaybackStarted(10); });  // → playing
        act(() => { result.current.onPlaybackPaused(15); });   // → paused
        expect(result.current.state).toBe("paused");
    });
});

// ── V10: PLAYING + onForcePause → PAUSED ─────────────────────────────────────

describe("V10 – onForcePause while playing → PAUSED", () => {
    it("force pauses when in playing state", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v10"));
        await act(async () => {});
        act(() => { result.current.onPlaybackStarted(5); });  // → playing
        act(() => { result.current.onForcePause(); });        // → paused
        expect(result.current.state).toBe("paused");
    });
});

// ── V11: PAUSED + onForcePause → no-op ───────────────────────────────────────

describe("V11 – onForcePause while paused is a no-op", () => {
    it("remains paused when already paused", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v11"));
        await act(async () => {}); // → paused
        act(() => { result.current.onForcePause(); }); // no-op
        expect(result.current.state).toBe("paused");
    });
});

// ── V12: pause event → writes localStorage ───────────────────────────────────

describe("V12 – pause event writes progress to localStorage", () => {
    it("saves currentTime to localStorage on onPlaybackPaused", async () => {
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v12"));
        await act(async () => {});
        act(() => { result.current.onPlaybackStarted(0); });
        act(() => { result.current.onPlaybackPaused(42); });

        const raw = localStorage.getItem("fredica-video-progress-mat-v12");
        expect(raw).not.toBeNull();
        const parsed = JSON.parse(raw!) as { currentTime: number; savedAt: number };
        expect(parsed.currentTime).toBe(42);
        expect(parsed.savedAt).toBeGreaterThan(0);
    });
});

// ── V13: check ok + localStorage within 30d → pendingSeek restored ───────────

describe("V13 – check ok with fresh localStorage record → pendingSeek restored", () => {
    it("sets pendingSeek from localStorage when record is within 30-day TTL", async () => {
        const ONE_HOUR = 60 * 60 * 1000;
        localStorage.setItem(
            "fredica-video-progress-mat-v13",
            JSON.stringify({ currentTime: 77, savedAt: Date.now() - ONE_HOUR })
        );
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v13"));
        await act(async () => {});

        expect(result.current.state).toBe("paused");
        expect(result.current.pendingSeek).toEqual({ seconds: 77, autoPlay: false });
    });
});

// ── V14: check ok + localStorage >30d → pendingSeek null ─────────────────────

describe("V14 – check ok with expired localStorage record → pendingSeek null", () => {
    it("ignores expired localStorage record (> 30 days)", async () => {
        const THIRTY_ONE_DAYS = 31 * 24 * 60 * 60 * 1000;
        localStorage.setItem(
            "fredica-video-progress-mat-v14",
            JSON.stringify({ currentTime: 99, savedAt: Date.now() - THIRTY_ONE_DAYS })
        );
        vi.stubGlobal("fetch", mockCheckReady({ ready: true, file_mtime: 100, file_size: 500 }));
        const { result } = renderHook(() => useVideoPlayerState("mat-v14"));
        await act(async () => {});

        expect(result.current.state).toBe("paused");
        expect(result.current.pendingSeek).toBeNull();
    });
});

// ── V15: check not ready + active DOWNLOAD workflow → ENCODING ────────────────

describe("V15 – check not ready but active download/transcode workflow exists → ENCODING", () => {
    it("restores encoding state with workflowRunId on page refresh", async () => {
        const mockF = vi.fn()
            // 1. MaterialVideoCheckRoute → not ready (file not yet produced)
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }),
            })
            // 2. MaterialWorkflowStatusRoute?mode=active → active download workflow
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({
                    workflow_runs: [{
                        workflow_run: {
                            id: "wf-resume-abc",
                            status: "running",
                            template: "bilibili_download_transcode",
                            total_tasks: 2,
                            done_tasks: 0,
                            created_at: 1700000000,
                        },
                        tasks: [
                            { id: "t-dl", type: "DOWNLOAD_BILIBILI_VIDEO", status: "running", progress: 45, workflow_run_id: "wf-resume-abc" },
                            { id: "t-tc", type: "TRANSCODE_MP4",           status: "pending",  progress: 0,  workflow_run_id: "wf-resume-abc" },
                        ],
                    }],
                }),
            });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v15", "video", "bilibili"));
        await act(async () => {});

        // Should restore encoding state, not needs_encode
        expect(result.current.state).toBe("encoding");
        expect(result.current.workflowRunId).toBe("wf-resume-abc");
    });
});

// ── V16: check not ready + active workflow but no encode task → NEEDS_ENCODE ──

describe("V16 – check not ready, no active encode task → NEEDS_ENCODE", () => {
    it("falls back to needs_encode when active workflow has no download/transcode task", async () => {
        const mockF = vi.fn()
            // 1. MaterialVideoCheckRoute → not ready
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({ ready: false, file_mtime: null, file_size: null }),
            })
            // 2. MaterialWorkflowStatusRoute?mode=active → running workflow, but unrelated task type
            .mockResolvedValueOnce({
                ok: true,
                json: () => Promise.resolve({
                    workflow_runs: [{
                        workflow_run: { id: "wf-other", status: "running", template: "other", total_tasks: 1, done_tasks: 0, created_at: 1700000000 },
                        tasks: [
                            { id: "t-other", type: "FETCH_SUBTITLE", status: "running", progress: 10, workflow_run_id: "wf-other" },
                        ],
                    }],
                }),
            });
        vi.stubGlobal("fetch", mockF);

        const { result } = renderHook(() => useVideoPlayerState("mat-v16"));
        await act(async () => {});

        expect(result.current.state).toBe("needs_encode");
    });
});
