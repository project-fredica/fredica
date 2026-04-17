/**
 * MaterialVideoPlayer.tsx
 *
 * 视频播放器组件，支持三种模式：
 *   - "inline"     : 嵌入子页面（Mode A），生命周期随路由
 *   - "floating"   : App 级悬浮单例（Mode B），由 FloatingPlayerCtx 驱动
 *   - "standalone" : 独立标签页（Mode C），全屏播放
 *
 * 状态机：CHECKING → PAUSED / NEEDS_ENCODE → ENCODING → CHECKING
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, Zap, CheckCircle, Play, Pause, Volume2, VolumeX, ExternalLink } from "lucide-react";
import { useAppConfig } from "~/context/appConfig";
import { buildAuthHeaders, DEFAULT_SERVER_PORT } from "~/util/app_fetch";
import { useVideoPlayerState, type PendingSeek } from "~/hooks/useVideoPlayerState";
import { useVideoPlayerChannel } from "~/util/videoPlayerChannel";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import { openInternalUrl } from "~/util/bridge";

// ── 公共类型 ──────────────────────────────────────────────────────────────────

export type VideoPlayerMode = "inline" | "floating" | "standalone";

export interface MaterialVideoPlayerProps {
    materialId: string;
    mode: VideoPlayerMode;
    /** 素材来源类型（"bilibili" 等），用于决定启动下载+转码还是纯转码 */
    sourceType?: string;
    /** standalone 模式时由外部传入初始跳转位置（字幕联动） */
    initialSeek?: number;
    /** floating 模式：外部触发的跳转请求（每次赋新对象引用即触发） */
    seekRequest?: PendingSeek | null;
    /** floating 模式：seekRequest 被消费后回调调用方清空 */
    onSeekRequestApplied?: () => void;
    /** 自定义容器高度 class（inline 默认 aspect-video） */
    className?: string;
}

// ── 播放器 ────────────────────────────────────────────────────────────────────

export function MaterialVideoPlayer({ materialId, mode, sourceType, initialSeek, seekRequest, onSeekRequestApplied, className }: MaterialVideoPlayerProps) {
    const { appConfig } = useAppConfig();
    const instanceId = useRef(crypto.randomUUID()).current;
    const videoRef = useRef<HTMLVideoElement | null>(null);
    // lastTimeRef：普通数据 ref（非 DOM ref），持续跟踪最新播放/seek 位置。
    // 动机：useEffect cleanup 属于 passive effect，在 React commitMutationEffects 之后
    //       异步执行；此时 videoRef（DOM ref）已被 React 清为 null，无法读取 currentTime。
    //       lastTimeRef 不附加到 DOM 元素，React 不会在 unmount 时清空它，
    //       因此 cleanup 里始终能读到正确的时间戳。
    const lastTimeRef = useRef(0);

    // retryCountRef / retryTimerRef：网络断连自动重试机制。
    // 频繁 seek 时浏览器可能与 Ktor 之间的 HTTP 连接断开（Ktor 侧 ClosedWriteChannelException），
    // 浏览器若无法自动重建连接，video 元素触发 error 事件，但状态机停留在 paused/playing——
    // 此时画面卡住，必须手动刷新页面。onError 处理器通过 video.load() 重建 HTTP 连接并恢复位置。
    const retryCountRef = useRef(0);
    const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    // 构建服务器 base URL
    const serverBase = (() => {
        const s = appConfig.webserver_schema ?? "http";
        const d = appConfig.webserver_domain ?? "localhost";
        const p = appConfig.webserver_port ?? DEFAULT_SERVER_PORT;
        return `${s}://${d}:${p}`;
    })();

    // ── 状态机 ──────────────────────────────────────────────────────────────

    const {
        state,
        fileMtime,
        workflowRunId,
        pendingSeek,
        startEncode,
        setPendingSeek,
        onPlaybackStarted,
        onPlaybackPaused,
        onForcePause,
        saveProgress,
    } = useVideoPlayerState(materialId, sourceType);

    // 折叠悬浮窗或路由离开时，video 元素随 unmount 被移除；
    // React 在 unmount 期间不会通过合成事件系统派发 pause 事件（fiber 已标记销毁），
    // 故 handlePause → onPlaybackPaused 不会执行，需在此处兜底写入 localStorage。
    // 注意：不能用 videoRef.current（passive cleanup 时已为 null），改读 lastTimeRef。
    useEffect(() => {
        return () => {
            if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
            if (lastTimeRef.current > 0) saveProgress(lastTimeRef.current);
        };
    }, [saveProgress]);

    // standalone 模式的初始 seek（字幕联动打开新标签时携带的时间戳）
    useEffect(() => {
        if (initialSeek != null && initialSeek > 0) {
            setPendingSeek({ seconds: initialSeek, autoPlay: true });
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId]);

    // floating 模式：外部传入的跳转请求（FloatingVideoPlayerSingleton 驱动）
    useEffect(() => {
        if (seekRequest != null) {
            setPendingSeek(seekRequest);
            onSeekRequestApplied?.();
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [seekRequest]);

    // ── 播放状态（本地）────────────────────────────────────────────────────

    const playbackStateRef = useRef<"playing" | "paused" | "not-ready">("not-ready");
    const isVideoReady = state === "paused" || state === "playing";
    if (!isVideoReady) playbackStateRef.current = "not-ready";

    // ── Channel ──────────────────────────────────────────────────────────

    const { broadcastPlaying, broadcastPaused, broadcastSeekPassive } = useVideoPlayerChannel({
        materialId,
        instanceId,
        mode,
        getPlaybackState: () => playbackStateRef.current,
        getCurrentTime: () => videoRef.current?.currentTime ?? 0,
        onForcePause: () => {
            onForcePause();
            videoRef.current?.pause();
        },
        onSeekAndPlay: useCallback((seconds: number) => {
            if (videoRef.current) {
                videoRef.current.currentTime = seconds;
                videoRef.current.play().catch(() => {});
            }
            setPendingSeek(null);
        }, [setPendingSeek]),
        onSeekPassive: useCallback((seconds: number) => {
            if (videoRef.current) {
                videoRef.current.currentTime = seconds;
            }
        }, []),
        onPeerDestroyed: useCallback(() => {}, []),
    });

    // ── pendingSeek 应用（状态机就绪后自动 seek / play）────────────────────

    useEffect(() => {
        if ((state !== "paused" && state !== "playing") || pendingSeek == null || !videoRef.current) return;
        const video = videoRef.current;
        video.currentTime = pendingSeek.seconds;
        lastTimeRef.current = pendingSeek.seconds; // seek 后立即更新，不等 timeupdate（可能晚于 unmount）
        if (pendingSeek.autoPlay) {
            video.play().catch(() => {});
        }
        setPendingSeek(null);
    }, [state, pendingSeek, setPendingSeek]);

    // ── 视频事件回调 ──────────────────────────────────────────────────────

    const handlePlay = useCallback(() => {
        retryCountRef.current = 0; // 成功播放，重置重试计数
        playbackStateRef.current = "playing";
        onPlaybackStarted(videoRef.current?.currentTime ?? 0);
        broadcastPlaying(videoRef.current?.currentTime ?? 0);
    }, [onPlaybackStarted, broadcastPlaying]);

    const handlePause = useCallback(() => {
        playbackStateRef.current = "paused";
        onPlaybackPaused(videoRef.current?.currentTime ?? 0);
        broadcastPaused();
    }, [onPlaybackPaused, broadcastPaused]);

    const handleTimeUpdate = useCallback(() => {
        const t = videoRef.current?.currentTime ?? 0;
        lastTimeRef.current = t; // 播放中持续更新，供 unmount cleanup 读取
        broadcastSeekPassive(t);
    }, [broadcastSeekPassive]);

    // video onError：捕获网络错误（Range 请求失败、服务器断开后浏览器无法自动重连等）。
    // 触发时通过 video.load() 重新建立 HTTP 连接，并在 canplay 后恢复到 lastTimeRef 位置。
    // 最多重试 3 次（500ms / 1000ms / 1500ms 递增退避），连续失败后放弃，避免无限循环。
    const handleVideoError = useCallback(() => {
        if (retryCountRef.current >= 3) return;
        retryCountRef.current += 1;
        const delay = 500 * retryCountRef.current;
        if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
        retryTimerRef.current = setTimeout(() => {
            retryTimerRef.current = null;
            const video = videoRef.current;
            if (!video) return;
            const wasPlaying = playbackStateRef.current === "playing";
            const resumeAt = lastTimeRef.current; // load() 会重置 currentTime 为 0，需手动恢复
            video.load(); // 重新加载 src，重建 HTTP 连接（触发新的 Range 请求）
            // load() 后元素进入 HAVE_NOTHING 状态；等 canplay 事件后再 seek + 恢复播放
            const onCanPlay = () => {
                video.removeEventListener("canplay", onCanPlay);
                if (resumeAt > 0) video.currentTime = resumeAt;
                if (wasPlaying) video.play().catch(() => {});
            };
            video.addEventListener("canplay", onCanPlay);
        }, delay);
    }, []);

    // ── 视频 src ──────────────────────────────────────────────────────────

    const videoSrc = `${serverBase}/api/v1/MaterialVideoStreamRoute?material_id=${encodeURIComponent(materialId)}`;

    // ── 渲染 ──────────────────────────────────────────────────────────────

    const containerCls = className ?? (mode === "standalone" ? "w-full h-full" : "w-full aspect-video");

    return (
        <div className={`relative bg-black rounded-lg overflow-hidden ${containerCls}`}>

            {/* ── CHECKING ──────────────────────────────────────────────── */}
            {state === "checking" && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-white/60">
                    <Loader2 className="w-8 h-8 animate-spin" />
                    <span className="text-sm">检查视频就绪状态…</span>
                </div>
            )}

            {/* ── NEEDS_ENCODE ──────────────────────────────────────────── */}
            {state === "needs_encode" && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 p-6 text-white">
                    <Zap className="w-10 h-10 text-yellow-400" />
                    <div className="text-center">
                        <p className="text-sm font-semibold">视频尚未转码</p>
                        <p className="text-xs text-white/60 mt-1">需要先将视频转码为 MP4 才能在线播放</p>
                    </div>
                    <button
                        onClick={startEncode}
                        className="flex items-center gap-2 px-4 py-2 bg-violet-600 hover:bg-violet-500 text-white text-sm font-medium rounded-lg transition-colors"
                    >
                        <Zap className="w-4 h-4" />
                        开始转码 MP4
                    </button>
                </div>
            )}

            {/* ── ENCODING ──────────────────────────────────────────────── */}
            {state === "encoding" && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-4 text-white">
                    <Loader2 className="w-8 h-8 animate-spin text-violet-400" />
                    <p className="text-sm font-semibold">转码进行中…</p>
                    {workflowRunId && (
                        <div className="w-full max-w-sm">
                            <WorkflowInfoPanel workflowRunId={workflowRunId} active defaultExpanded />
                        </div>
                    )}
                </div>
            )}

            {/* ── VIDEO（PAUSED / PLAYING）──────────────────────────────── */}
            {isVideoReady && (
                <video
                    key={fileMtime ?? 0}
                    ref={videoRef}
                    src={videoSrc}
                    controls
                    className="w-full h-full object-contain"
                    crossOrigin="use-credentials"
                    onPlay={handlePlay}
                    onPause={handlePause}
                    onTimeUpdate={handleTimeUpdate}
                    onError={handleVideoError}
                    style={{ display: isVideoReady ? "block" : "none" }}
                />
            )}

            {/* ── standalone 模式工具栏省略（使用原生 controls）──────── */}
            {/* inline 模式：右上角新标签按钮 */}
            {mode === "inline" && isVideoReady && (
                <button
                    onClick={() => openInternalUrl(`/material/${materialId}/video-standalone`)}
                    className="absolute top-2 right-2 p-1.5 bg-black/50 hover:bg-black/70 text-white rounded-md transition-colors"
                    title="在新标签页中打开"
                >
                    <ExternalLink className="w-3.5 h-3.5" />
                </button>
            )}
        </div>
    );
}
