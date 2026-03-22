/**
 * videoPlayerChannel.ts
 *
 * BroadcastChannel 封装：跨标签页 / 跨实例视频播放器协调。
 *
 * 核心约束（见设计文档 §6.0）：
 * - channel 按 materialId 隔离，名称：`fredica-video-player-{materialId}`
 * - instanceId：组件挂载时生成的 UUID（React ref），区分"谁发的消息"
 * - tabId：标签页首次加载时生成的 UUID（sessionStorage），用于 seek 命令定向
 * - seek-and-play / seek-passive 只有 tabId 匹配且为 Mode C 的实例响应
 * - materialId 变更时自动关闭旧 channel，开启新 channel
 * - unmount 时自动广播 destroyed + 关闭 channel
 */

import { useCallback, useEffect, useRef } from "react";

// ── tabId：每个 Tab 唯一，sessionStorage 中持久（页面刷新后重新生成）────────────────
export function getOrCreateTabId(): string {
    if (typeof window === "undefined" || typeof sessionStorage === "undefined") return "";
    const KEY = "fredica-tab-id";
    let id = sessionStorage.getItem(KEY);
    if (!id) {
        id = crypto.randomUUID();
        sessionStorage.setItem(KEY, id);
    }
    return id;
}

// ── 消息类型定义 ──────────────────────────────────────────────────────────────

export type VideoPlayerState = "playing" | "paused" | "not-ready";

export type VideoPlayerMessage =
    | { type: "playing"; materialId: string; instanceId: string; tabId: string; currentTime: number }
    | { type: "paused"; materialId: string; instanceId: string; tabId: string }
    | { type: "destroyed"; materialId: string; instanceId: string; tabId: string }
    | { type: "status-request"; materialId: string; requestId: string }
    | { type: "status-reply"; materialId: string; instanceId: string; tabId: string; requestId: string; state: VideoPlayerState; currentTime: number }
    | { type: "seek-and-play"; materialId: string; seconds: number; seekId: string; tabId: string }
    | { type: "seek-passive"; materialId: string; seconds: number; tabId: string }
    | { type: "time-sync"; materialId: string; instanceId: string; tabId: string; currentTime: number };

export interface StatusReply {
    instanceId: string;
    tabId: string;
    state: VideoPlayerState;
    currentTime: number;
}

// ── Hook 接口 ─────────────────────────────────────────────────────────────────

export interface UseVideoPlayerChannelOptions {
    materialId: string;
    instanceId: string;
    /** "inline" = Mode A；"floating" = Mode B；"standalone" = Mode C */
    mode: "inline" | "floating" | "standalone";
    getPlaybackState: () => VideoPlayerState;
    getCurrentTime: () => number;
    onForcePause: () => void;
    onSeekAndPlay: (seconds: number) => void;   // Mode C 专用；Mode B 由 Context 直接驱动
    onSeekPassive: (seconds: number) => void;   // Mode C 专用；RAF 批处理
    onPeerDestroyed: (instanceId: string) => void;
}

export interface UseVideoPlayerChannelResult {
    broadcastPlaying: (currentTime: number) => void;
    broadcastPaused: () => void;
    broadcastDestroyed: () => void;
    broadcastSeekAndPlay: (seconds: number) => void;
    broadcastSeekPassive: (seconds: number) => void;
    broadcastTimeSync: (currentTime: number) => void;
    requestPeerStatus: () => Promise<StatusReply[]>;
}

// ── SeekId 环形缓冲（容量 20）────────────────────────────────────────────────

class SeekIdRingBuffer {
    private buf: string[] = [];
    private readonly capacity: number;
    constructor(capacity = 20) { this.capacity = capacity; }

    has(id: string): boolean { return this.buf.includes(id); }

    add(id: string): void {
        if (this.buf.length >= this.capacity) this.buf.shift();
        this.buf.push(id);
    }
}

// ── Hook 实现 ─────────────────────────────────────────────────────────────────

export function useVideoPlayerChannel(
    options: UseVideoPlayerChannelOptions
): UseVideoPlayerChannelResult {
    const {
        materialId, instanceId, mode,
        getPlaybackState, getCurrentTime,
        onForcePause, onSeekAndPlay, onSeekPassive, onPeerDestroyed,
    } = options;

    const tabId = useRef(getOrCreateTabId()).current;
    const channelRef = useRef<BroadcastChannel | null>(null);
    const seenSeekIds = useRef(new SeekIdRingBuffer(20));

    // seek-passive 发送端节流（100ms trailing）
    const seekPassiveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const seekPassiveLatestRef = useRef<number>(0);

    // seek-passive 接收端 RAF 批处理
    const rafRef = useRef<number | null>(null);
    const pendingSeekRef = useRef<number | null>(null);

    // time-sync 发送端节流（1s）
    const timeSyncTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    // ── channel 订阅逻辑（materialId 变化时重建）─────────────────────────────
    useEffect(() => {
        if (!materialId || typeof BroadcastChannel === "undefined") return;

        const ch = new BroadcastChannel(`fredica-video-player-${materialId}`);
        channelRef.current = ch;

        ch.onmessage = (event: MessageEvent<VideoPlayerMessage>) => {
            const msg = event.data;
            if (!msg || typeof msg !== "object") return;

            switch (msg.type) {
                case "playing": {
                    // BroadcastChannel 本身不回发给发送方，此处显式过滤兜底
                    if (msg.instanceId === instanceId) break;
                    if (getPlaybackState() === "playing") {
                        onForcePause();
                    }
                    break;
                }
                case "status-request": {
                    const state = getPlaybackState();
                    if (state === "not-ready") break;
                    const reply: VideoPlayerMessage = {
                        type: "status-reply",
                        materialId,
                        instanceId,
                        tabId,
                        requestId: msg.requestId,
                        state,
                        currentTime: getCurrentTime(),
                    };
                    ch.postMessage(reply);
                    break;
                }
                case "seek-and-play": {
                    if (mode !== "standalone") break;
                    if (msg.tabId !== tabId) break;
                    if (seenSeekIds.current.has(msg.seekId)) break;
                    seenSeekIds.current.add(msg.seekId);
                    onSeekAndPlay(msg.seconds);
                    break;
                }
                case "seek-passive": {
                    if (mode !== "standalone") break;
                    if (msg.tabId !== tabId) break;
                    // RAF 批处理：同一帧内只应用最后一条
                    pendingSeekRef.current = msg.seconds;
                    if (rafRef.current !== null) break;
                    rafRef.current = requestAnimationFrame(() => {
                        rafRef.current = null;
                        if (pendingSeekRef.current !== null) {
                            onSeekPassive(pendingSeekRef.current);
                            pendingSeekRef.current = null;
                        }
                    });
                    break;
                }
                case "destroyed": {
                    if (msg.instanceId === instanceId) break;
                    onPeerDestroyed(msg.instanceId);
                    break;
                }
            }
        };

        // 加入 channel 后广播 status-request，探测是否有实例在播放
        ch.postMessage({
            type: "status-request",
            materialId,
            requestId: crypto.randomUUID(),
        } satisfies VideoPlayerMessage);

        return () => {
            // materialId 变更或组件 unmount 时广播 destroyed + 关闭
            ch.postMessage({
                type: "destroyed",
                materialId,
                instanceId,
                tabId,
            } satisfies VideoPlayerMessage);
            ch.close();
            channelRef.current = null;
            // 清理 RAF
            if (rafRef.current !== null) {
                cancelAnimationFrame(rafRef.current);
                rafRef.current = null;
            }
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    // ── 发送 API ──────────────────────────────────────────────────────────────

    const broadcastPlaying = useCallback((currentTime: number) => {
        channelRef.current?.postMessage({
            type: "playing",
            materialId,
            instanceId,
            tabId,
            currentTime,
        } satisfies VideoPlayerMessage);
    }, [materialId, instanceId, tabId]);

    const broadcastPaused = useCallback(() => {
        channelRef.current?.postMessage({
            type: "paused",
            materialId,
            instanceId,
            tabId,
        } satisfies VideoPlayerMessage);
    }, [materialId, instanceId, tabId]);

    const broadcastDestroyed = useCallback(() => {
        channelRef.current?.postMessage({
            type: "destroyed",
            materialId,
            instanceId,
            tabId,
        } satisfies VideoPlayerMessage);
    }, [materialId, instanceId, tabId]);

    const broadcastSeekAndPlay = useCallback((seconds: number) => {
        channelRef.current?.postMessage({
            type: "seek-and-play",
            materialId,
            seconds,
            seekId: crypto.randomUUID(),
            tabId,
        } satisfies VideoPlayerMessage);
    }, [materialId, tabId]);

    const broadcastSeekPassive = useCallback((seconds: number) => {
        // 发送端 100ms trailing 节流
        seekPassiveLatestRef.current = seconds;
        if (seekPassiveTimerRef.current !== null) return;
        seekPassiveTimerRef.current = setTimeout(() => {
            seekPassiveTimerRef.current = null;
            channelRef.current?.postMessage({
                type: "seek-passive",
                materialId,
                seconds: seekPassiveLatestRef.current,
                tabId,
            } satisfies VideoPlayerMessage);
        }, 100);
    }, [materialId, tabId]);

    const broadcastTimeSync = useCallback((currentTime: number) => {
        // 发送端 1s 节流
        if (timeSyncTimerRef.current !== null) return;
        timeSyncTimerRef.current = setTimeout(() => {
            timeSyncTimerRef.current = null;
        }, 1000);
        channelRef.current?.postMessage({
            type: "time-sync",
            materialId,
            instanceId,
            tabId,
            currentTime,
        } satisfies VideoPlayerMessage);
    }, [materialId, instanceId, tabId]);

    // ── 探测 API（200ms 超时，收集所有 status-reply）────────────────────────

    const requestPeerStatus = useCallback((): Promise<StatusReply[]> => {
        return new Promise((resolve) => {
            const ch = channelRef.current;
            if (!ch) { resolve([]); return; }

            const requestId = crypto.randomUUID();
            const replies: StatusReply[] = [];

            const listener = (event: MessageEvent<VideoPlayerMessage>) => {
                const msg = event.data;
                if (msg.type === "status-reply" && msg.requestId === requestId) {
                    replies.push({
                        instanceId: msg.instanceId,
                        tabId: msg.tabId,
                        state: msg.state,
                        currentTime: msg.currentTime,
                    });
                }
            };
            ch.addEventListener("message", listener);

            ch.postMessage({
                type: "status-request",
                materialId,
                requestId,
            } satisfies VideoPlayerMessage);

            setTimeout(() => {
                ch.removeEventListener("message", listener);
                resolve(replies);
            }, 200);
        });
    }, [materialId]);

    return {
        broadcastPlaying,
        broadcastPaused,
        broadcastDestroyed,
        broadcastSeekAndPlay,
        broadcastSeekPassive,
        broadcastTimeSync,
        requestPeerStatus,
    };
}
