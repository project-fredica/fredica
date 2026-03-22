/**
 * videoPlayerChannel.test.ts
 *
 * useVideoPlayerChannel hook 单元测试。
 * 覆盖设计文档 §11.3 的 C1–C9 测试用例。
 */

import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useVideoPlayerChannel } from "~/util/videoPlayerChannel";
import { MockBroadcastChannel } from "../mocks/broadcastChannel";

// ── 辅助：睡眠 n ms ───────────────────────────────────────────────────────────
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// ── 每次测试前后清理 channel 状态 ─────────────────────────────────────────────
beforeEach(() => MockBroadcastChannel.reset());
afterEach(() => MockBroadcastChannel.reset());

// ── 渲染 hook 的默认选项工厂 ─────────────────────────────────────────────────
function makeOptions(overrides?: Partial<Parameters<typeof useVideoPlayerChannel>[0]>) {
    const onForcePause = vi.fn();
    const onSeekAndPlay = vi.fn();
    const onSeekPassive = vi.fn();
    const onPeerDestroyed = vi.fn();
    return {
        options: {
            materialId: "mat-1",
            instanceId: crypto.randomUUID(),
            mode: "standalone" as const,
            getPlaybackState: vi.fn(() => "paused" as const),
            getCurrentTime: vi.fn(() => 0),
            onForcePause,
            onSeekAndPlay,
            onSeekPassive,
            onPeerDestroyed,
            ...overrides,
        },
        onForcePause,
        onSeekAndPlay,
        onSeekPassive,
        onPeerDestroyed,
    };
}

// ── C1: 收到 playing(instanceId=自身) → onForcePause 不调用 ──────────────────

describe("C1 – playing from self is ignored", () => {
    it("does not call onForcePause when own instanceId", () => {
        const { options, onForcePause } = makeOptions();
        const { result } = renderHook(() => useVideoPlayerChannel(options));

        // 另一个 channel 实例模拟"自身发的消息"
        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        act(() => {
            peer.postMessage({
                type: "playing",
                materialId: "mat-1",
                instanceId: options.instanceId, // 自身 ID
                tabId: "tab-1",
                currentTime: 5,
            });
        });
        peer.close();
        expect(onForcePause).not.toHaveBeenCalled();
    });
});

// ── C2: 收到 playing(instanceId=他人) → onForcePause 调用一次 ────────────────

describe("C2 – playing from other triggers force pause", () => {
    it("calls onForcePause once when other instanceId", () => {
        const { options, onForcePause } = makeOptions({
            getPlaybackState: vi.fn(() => "playing" as const),
        });
        renderHook(() => useVideoPlayerChannel(options));

        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        act(() => {
            peer.postMessage({
                type: "playing",
                materialId: "mat-1",
                instanceId: "other-instance-id",
                tabId: "tab-other",
                currentTime: 10,
            });
        });
        peer.close();
        expect(onForcePause).toHaveBeenCalledTimes(1);
    });
});

// ── C3: seek-and-play(tabId 不匹配) → onSeekAndPlay 不调用 ──────────────────

describe("C3 – seek-and-play with wrong tabId is ignored", () => {
    it("does not call onSeekAndPlay when tabId mismatch", () => {
        const { options, onSeekAndPlay } = makeOptions({ mode: "standalone" });
        renderHook(() => useVideoPlayerChannel(options));

        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        act(() => {
            peer.postMessage({
                type: "seek-and-play",
                materialId: "mat-1",
                seconds: 30,
                seekId: crypto.randomUUID(),
                tabId: "wrong-tab-id",
            });
        });
        peer.close();
        expect(onSeekAndPlay).not.toHaveBeenCalled();
    });
});

// ── C4: seek-and-play(tabId 匹配) → onSeekAndPlay 被调用 ────────────────────

describe("C4 – seek-and-play with matching tabId triggers seek", () => {
    it("calls onSeekAndPlay with correct seconds", () => {
        // 强制设置 sessionStorage tabId
        sessionStorage.setItem("fredica-tab-id", "tab-test-c4");
        const { options, onSeekAndPlay } = makeOptions({ mode: "standalone" });
        renderHook(() => useVideoPlayerChannel(options));

        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        act(() => {
            peer.postMessage({
                type: "seek-and-play",
                materialId: "mat-1",
                seconds: 42,
                seekId: crypto.randomUUID(),
                tabId: "tab-test-c4",
            });
        });
        peer.close();
        expect(onSeekAndPlay).toHaveBeenCalledWith(42);
        sessionStorage.removeItem("fredica-tab-id");
    });
});

// ── C5: 同一 seekId 发送两次 → onSeekAndPlay 只调用一次 ─────────────────────

describe("C5 – duplicate seekId is deduplicated", () => {
    it("calls onSeekAndPlay only once for same seekId", () => {
        sessionStorage.setItem("fredica-tab-id", "tab-test-c5");
        const { options, onSeekAndPlay } = makeOptions({ mode: "standalone" });
        renderHook(() => useVideoPlayerChannel(options));

        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        const seekId = crypto.randomUUID();
        act(() => {
            peer.postMessage({ type: "seek-and-play", materialId: "mat-1", seconds: 10, seekId, tabId: "tab-test-c5" });
            peer.postMessage({ type: "seek-and-play", materialId: "mat-1", seconds: 20, seekId, tabId: "tab-test-c5" });
        });
        peer.close();
        expect(onSeekAndPlay).toHaveBeenCalledTimes(1);
        expect(onSeekAndPlay).toHaveBeenCalledWith(10);
        sessionStorage.removeItem("fredica-tab-id");
    });
});

// ── C6: 第 21 个不同 seekId 后重发第 1 个 → 再次调用（环形缓冲溢出）──────────

describe("C6 – ring buffer evicts old seekId after 20 entries", () => {
    it("accepts seekId after ring buffer overflow", () => {
        sessionStorage.setItem("fredica-tab-id", "tab-test-c6");
        const { options, onSeekAndPlay } = makeOptions({ mode: "standalone" });
        renderHook(() => useVideoPlayerChannel(options));

        const peer = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        const firstSeekId = crypto.randomUUID();

        act(() => {
            // 第 1 个
            peer.postMessage({ type: "seek-and-play", materialId: "mat-1", seconds: 1, seekId: firstSeekId, tabId: "tab-test-c6" });
            // 再送 20 个不同 id，把环形缓冲填满并溢出
            for (let i = 0; i < 20; i++) {
                peer.postMessage({ type: "seek-and-play", materialId: "mat-1", seconds: i + 2, seekId: crypto.randomUUID(), tabId: "tab-test-c6" });
            }
            // 重发第 1 个（此时已被淘汰）
            peer.postMessage({ type: "seek-and-play", materialId: "mat-1", seconds: 99, seekId: firstSeekId, tabId: "tab-test-c6" });
        });
        peer.close();
        // 第 1 次 + 20 个不同 + 第 21 次重发（溢出后接受）= 22 次
        expect(onSeekAndPlay).toHaveBeenCalledTimes(22);
        sessionStorage.removeItem("fredica-tab-id");
    });
});

// ── C8: 组件 unmount → 自动广播 destroyed，channel 关闭 ─────────────────────

describe("C8 – unmount broadcasts destroyed and closes channel", () => {
    it("sends destroyed when hook unmounts", () => {
        const { options } = makeOptions();
        const received: unknown[] = [];
        const watcher = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        watcher.onmessage = (e) => received.push(e.data);

        const { unmount } = renderHook(() => useVideoPlayerChannel(options));
        act(() => { unmount(); });

        watcher.close();
        const destroyed = received.find((m: any) => m.type === "destroyed");
        expect(destroyed).toBeTruthy();
    });
});

// ── C9: broadcastSeekPassive 100ms 内连续调用 → channel 只收到 1 条 ─────────

describe("C9 – broadcastSeekPassive throttles to 100ms", async () => {
    it("sends at most 1 message within 100ms window", async () => {
        vi.useFakeTimers();
        const { options } = makeOptions();
        const received: unknown[] = [];
        const watcher = new MockBroadcastChannel(`fredica-video-player-mat-1`);
        watcher.onmessage = (e) => {
            if ((e.data as any).type === "seek-passive") received.push(e.data);
        };

        const { result } = renderHook(() => useVideoPlayerChannel(options));

        act(() => {
            result.current.broadcastSeekPassive(1);
            result.current.broadcastSeekPassive(2);
            result.current.broadcastSeekPassive(3);
            result.current.broadcastSeekPassive(4);
            result.current.broadcastSeekPassive(5);
        });

        act(() => { vi.advanceTimersByTime(150); });
        watcher.close();

        expect(received).toHaveLength(1);
        expect((received[0] as any).seconds).toBe(5); // 最后一次的值
        vi.useRealTimers();
    });
});
