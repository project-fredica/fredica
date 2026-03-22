/**
 * FloatingVideoPlayerSingleton.tsx
 *
 * App 级悬浮播放器 UI（Mode B）。
 * 三态：HIDDEN → MINIMIZED ↔ OPEN。
 * 挂在 SidebarLayout，唯一实例，贯穿整个 App 生命周期。
 *
 * 工具栏：拖拽移动（GripHorizontal）· 缩小（Minus）· 放大（Plus）· 收起/展开 · 关闭
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { X, ChevronUp, ChevronDown, GripHorizontal, Plus, Minus } from "lucide-react";
import { useFloatingPlayerCtx } from "~/context/floatingPlayer";
import { MaterialVideoPlayer } from "~/components/material/MaterialVideoPlayer";
import type { PendingSeek } from "~/hooks/useVideoPlayerState";

type FloatingState = "minimized" | "open";

const MIN_WIDTH = 240;
const RESIZE_STEP = 40;
const STORAGE_KEY = "fredica-floating-player-width";

function loadWidth(): number {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
            const n = parseInt(raw, 10);
            if (!isNaN(n) && n >= MIN_WIDTH) return n;
        }
    } catch { /* ignore */ }
    return 320;
}

interface Pos { bottom: number; right: number; }
const DEFAULT_POS: Pos = { bottom: 16, right: 16 };

export function FloatingVideoPlayerSingleton() {
    const {
        isVisible,
        currentMaterialId,
        closeFloatingPlayer,
        pendingSeek,
        consumePendingSeek,
    } = useFloatingPlayerCtx();

    const [floatingState, setFloatingState] = useState<FloatingState>("minimized");
    // liveSeek：从 context.pendingSeek 摘下后暂存于本地，作为 seekRequest prop 传给播放器。
    // 播放器消费后通过 handleSeekApplied 回调清空，防止折叠再展开时重复 seek。
    const [liveSeek, setLiveSeek] = useState<PendingSeek | null>(null);
    const [width, setWidth] = useState(loadWidth);
    const [pos, setPos] = useState<Pos>(DEFAULT_POS);

    // width 变化时持久化到 localStorage
    useEffect(() => {
        try { localStorage.setItem(STORAGE_KEY, String(width)); } catch { /* ignore */ }
    }, [width]);

    // 首次打开时展开
    useEffect(() => {
        if (isVisible) setFloatingState("open");
    }, [isVisible, currentMaterialId]);

    // consumePendingSeek 存 ref 避免 effect 依赖循环
    const consumeRef = useRef(consumePendingSeek);
    consumeRef.current = consumePendingSeek;

    // context 有新 pendingSeek → 展开 + 传给播放器 + 消费
    useEffect(() => {
        if (pendingSeek != null) {
            setFloatingState("open");
            setLiveSeek(pendingSeek);
            consumeRef.current();
        }
    }, [pendingSeek]);

    // 播放器消费 seekRequest 后回调，清空 liveSeek 防止 re-mount 时重复跳转
    const handleSeekApplied = useCallback(() => {
        setLiveSeek(null);
    }, []);

    // ── 拖拽移动 ─────────────────────────────────────────────────────────────

    // pos ref 用于 handleMoveStart 闭包中读取最新位置
    const posRef = useRef(pos);
    useEffect(() => { posRef.current = pos; }, [pos]);

    // 容器 ref：拖拽时读取实际高度，计算最大 bottom（防止工具栏超出屏幕顶部）
    const containerRef = useRef<HTMLDivElement>(null);

    const dragCleanupRef = useRef<(() => void) | null>(null);
    useEffect(() => () => { dragCleanupRef.current?.(); }, []);

    // 窗口 resize 时重新 clamp pos，防止播放器超出新的视口边界
    useEffect(() => {
        const onResize = () => {
            const containerW = containerRef.current?.offsetWidth  ?? 200;
            const containerH = containerRef.current?.offsetHeight ?? 200;
            const maxRight  = Math.max(0, window.innerWidth  - containerW - 8);
            const maxBottom = Math.max(0, window.innerHeight - containerH - 8);
            setPos(prev => ({
                right:  Math.min(prev.right,  maxRight),
                bottom: Math.min(prev.bottom, maxBottom),
            }));
        };
        window.addEventListener("resize", onResize);
        return () => window.removeEventListener("resize", onResize);
    }, []);

    const handleMoveStart = useCallback((startX: number, startY: number) => {
        const startPos = { ...posRef.current };

        const onMove = (e: MouseEvent | TouchEvent) => {
            const cx = "touches" in e ? e.touches[0].clientX : e.clientX;
            const cy = "touches" in e ? e.touches[0].clientY : e.clientY;
            // 每次 move 都实时读取容器尺寸（展开/收起状态不同，高度会变化）。
            // 用 window.innerWidth/Height - containerW/H 确保工具栏始终完全在屏幕内，
            // 而非用固定常量（固定值无法适应 open/minimized 两种尺寸）。
            const containerW = containerRef.current?.offsetWidth  ?? 200;
            const containerH = containerRef.current?.offsetHeight ?? 200;
            const maxRight  = Math.max(0, window.innerWidth  - containerW - 8);
            const maxBottom = Math.max(0, window.innerHeight - containerH - 8);
            setPos({
                right:  Math.max(0, Math.min(maxRight,  startPos.right  + (startX - cx))),
                bottom: Math.max(0, Math.min(maxBottom, startPos.bottom + (startY - cy))),
            });
        };

        const onEnd = () => {
            dragCleanupRef.current = null;
            document.removeEventListener("mousemove", onMove);
            document.removeEventListener("mouseup", onEnd);
            document.removeEventListener("touchmove", onMove as EventListener);
            document.removeEventListener("touchend", onEnd);
        };

        dragCleanupRef.current = onEnd;
        document.addEventListener("mousemove", onMove);
        document.addEventListener("mouseup", onEnd);
        document.addEventListener("touchmove", onMove as EventListener, { passive: false });
        document.addEventListener("touchend", onEnd);
    }, []);

    // ─────────────────────────────────────────────────────────────────────────

    if (!isVisible || !currentMaterialId) return null;

    return (
        <div
            ref={containerRef}
            className="fixed z-50 flex flex-col items-end gap-2"
            style={{ bottom: pos.bottom, right: pos.right }}
        >
            {/* 工具栏 */}
            <div className="flex items-center gap-0.5 px-1.5 py-1 bg-white border border-gray-200 rounded-full shadow-md text-gray-500">
                {/* 拖拽移动 */}
                <button
                    className="p-1 cursor-grab hover:text-gray-800 transition-colors active:cursor-grabbing touch-none"
                    title="拖拽移动"
                    onMouseDown={(e) => { e.preventDefault(); handleMoveStart(e.clientX, e.clientY); }}
                    onTouchStart={(e) => { e.preventDefault(); handleMoveStart(e.touches[0].clientX, e.touches[0].clientY); }}
                >
                    <GripHorizontal className="w-3.5 h-3.5" />
                </button>

                <div className="w-px h-3 bg-gray-200 mx-0.5" />

                {/* 缩小 */}
                <button
                    onClick={() => setWidth(w => Math.max(MIN_WIDTH, w - RESIZE_STEP))}
                    className="p-1 hover:text-gray-800 transition-colors"
                    title="缩小"
                >
                    <Minus className="w-3 h-3" />
                </button>
                {/* 放大：宽度不超出左侧屏幕边界 */}
                <button
                    onClick={() => setWidth(w => Math.min(window.innerWidth - pos.right - 8, w + RESIZE_STEP))}
                    className="p-1 hover:text-gray-800 transition-colors"
                    title="放大"
                >
                    <Plus className="w-3 h-3" />
                </button>

                <div className="w-px h-3 bg-gray-200 mx-0.5" />

                {/* 收起 / 展开 */}
                <button
                    onClick={() => setFloatingState(s => s === "open" ? "minimized" : "open")}
                    className="p-1 hover:text-gray-800 transition-colors"
                    title={floatingState === "open" ? "收起" : "展开"}
                >
                    {floatingState === "open"
                        ? <ChevronDown className="w-3.5 h-3.5" />
                        : <ChevronUp className="w-3.5 h-3.5" />
                    }
                </button>
                {/* 关闭 */}
                <button
                    onClick={closeFloatingPlayer}
                    className="p-1 hover:text-red-500 transition-colors"
                    title="关闭"
                >
                    <X className="w-3.5 h-3.5" />
                </button>
            </div>

            {/* 播放器卡片（OPEN 时显示） */}
            {floatingState === "open" && (
                <div
                    className="rounded-xl overflow-hidden shadow-xl border border-gray-200 bg-black"
                    style={{ width: `${width}px` }}
                >
                    <MaterialVideoPlayer
                        materialId={currentMaterialId}
                        mode="floating"
                        seekRequest={liveSeek}
                        onSeekRequestApplied={handleSeekApplied}
                        className="w-full aspect-video"
                    />
                </div>
            )}

            {/* 胶囊（MINIMIZED 时显示） */}
            {floatingState === "minimized" && (
                <div
                    className="flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-full shadow-md cursor-pointer hover:shadow-lg transition-shadow"
                    onClick={() => setFloatingState("open")}
                >
                    <div className="w-2 h-2 rounded-full bg-violet-500 animate-pulse" />
                    <span className="text-xs text-gray-600 max-w-[120px] truncate">{currentMaterialId}</span>
                </div>
            )}
        </div>
    );
}
