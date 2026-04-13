/**
 * CommonSubtitlePanel.tsx
 *
 * 通用字幕列表组件，支持：
 * - 虚拟滚动（固定行高 52px）
 * - 根据播放进度高亮当前激活字幕行（通过 materialId 订阅 BroadcastChannel，或外部传入 currentTime）
 * - 自动跟随开关（默认开启），激活行变更时自动滚动居中
 * - maxHeight prop：限制列表最大高度（不传则 flex-1 撑满父容器）
 *
 * 高度约定：
 *   组件根元素为 `flex flex-col min-h-0 flex-1`，需要父容器是 flex 列方向且有确定高度。
 *   直接放入 `flex flex-col min-h-0` 容器即可，无需额外包装层。
 */

import { useEffect, useRef, useState } from "react";
import { ChevronsDown } from "lucide-react";
import { usePlaybackTime } from "~/hooks/usePlaybackTime";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface CommonSubtitleItem {
    from: number;
    to: number;
    content: string;
}

export interface CommonSubtitlePanelProps {
    items: CommonSubtitleItem[];
    /** 素材 ID，用于订阅 BroadcastChannel 播放进度（推荐方式） */
    materialId?: string;
    /** 外部直接传入播放时间（秒），会覆盖 materialId 订阅的值 */
    currentTime?: number;
    onSeek?: (seconds: number) => void;
    /** 限制列表区域最大高度，传入时不再依赖 flex 父容器高度 */
    maxHeight?: number | string;
    /** 自动跟随初始状态（默认 true） */
    autoScrollDefault?: boolean;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTime(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = (seconds % 60).toFixed(1);
    return `${String(m).padStart(2, "0")}:${s.padStart(4, "0")}`;
}

// ─── Component ────────────────────────────────────────────────────────────────

const ITEM_HEIGHT = 52;
const OVERSCAN = 5;

export function CommonSubtitlePanel({
    items,
    materialId,
    currentTime: currentTimeOverride,
    onSeek,
    maxHeight,
    autoScrollDefault = true,
}: CommonSubtitlePanelProps) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [scrollTop, setScrollTop] = useState(0);
    const [containerHeight, setContainerHeight] = useState(400);
    const [autoScroll, setAutoScroll] = useState(autoScrollDefault);

    const playbackTime = usePlaybackTime(materialId);
    // 外部 currentTime 覆盖内部订阅值；两者都没有时默认 0
    const currentTime = currentTimeOverride ?? playbackTime;

    // suppressUntilRef：记录"用户主动滚动"后的抑制截止时间戳（Date.now() + 5000ms）。
    // 使用 ref 而非 state，避免每次更新都触发重渲染。
    const suppressUntilRef = useRef(0);

    // 监听容器尺寸变化
    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        setContainerHeight(el.clientHeight);
        const ro = new ResizeObserver(() => setContainerHeight(el.clientHeight));
        ro.observe(el);
        return () => ro.disconnect();
    }, []);

    // 当前激活字幕行索引
    const activeIdx = items.findIndex(item => currentTime >= item.from && currentTime < item.to);

    // 用户滚动（wheel / touchstart）→ 5 秒内抑制自动跟随。
    // 选择 wheel/touchstart 而非 onScroll 的原因：
    //   scrollTo() 代码触发的滚动同样会产生 scroll 事件，无法区分来源；
    //   wheel/touchstart 仅由真实用户输入触发，不会被 scrollTo() 误触发。
    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        const suppress = () => { suppressUntilRef.current = Date.now() + 5000; };
        el.addEventListener("wheel", suppress, { passive: true });
        el.addEventListener("touchstart", suppress, { passive: true });
        return () => {
            el.removeEventListener("wheel", suppress);
            el.removeEventListener("touchstart", suppress);
        };
    }, []);

    // 自动跟随：激活行变化或开启跟随时，若激活行不在可视区则平滑滚动居中。
    // 直接读取 el.scrollTop / el.clientHeight（DOM 实时值），避免 containerHeight state 的过时问题。
    useEffect(() => {
        if (!autoScroll || activeIdx < 0) return;
        if (Date.now() < suppressUntilRef.current) return; // 用户刚主动滚动，5s 内不抢占
        const el = containerRef.current;
        if (!el || el.clientHeight === 0) return;
        const itemTop = activeIdx * ITEM_HEIGHT;
        const itemBottom = itemTop + ITEM_HEIGHT;
        if (itemTop < el.scrollTop || itemBottom > el.scrollTop + el.clientHeight) {
            el.scrollTo({ top: Math.max(0, itemTop - el.clientHeight / 2 + ITEM_HEIGHT / 2), behavior: "smooth" });
        }
    }, [activeIdx, autoScroll]);

    // 虚拟列表计算
    const totalHeight = items.length * ITEM_HEIGHT;
    const startIdx = Math.max(0, Math.floor(scrollTop / ITEM_HEIGHT) - OVERSCAN);
    const endIdx = Math.min(items.length, Math.ceil((scrollTop + containerHeight) / ITEM_HEIGHT) + OVERSCAN);
    const visibleItems = items.slice(startIdx, endIdx);

    return (
        <div className="flex flex-col min-h-0 flex-1">
            {/* 自动跟随开关 */}
            <div className="flex items-center justify-end px-1 pb-1.5 flex-shrink-0">
                <button
                    onClick={() => setAutoScroll(v => !v)}
                    className={`flex items-center gap-1 text-xs px-2 py-1 rounded-full transition-colors ${
                        autoScroll
                            ? "bg-violet-100 text-violet-600"
                            : "bg-gray-100 text-gray-400 hover:text-gray-600"
                    }`}
                    title={autoScroll ? "关闭自动跟随" : "开启自动跟随"}
                >
                    <ChevronsDown className="w-3 h-3" />
                    跟随
                </button>
            </div>

            {/* 虚拟列表 */}
            <div
                ref={containerRef}
                className={maxHeight ? "overflow-y-auto" : "flex-1 overflow-y-auto min-h-0"}
                style={maxHeight ? { maxHeight } : undefined}
                onScroll={(e) => setScrollTop((e.target as HTMLDivElement).scrollTop)}
            >
                <div style={{ height: totalHeight, position: "relative" }}>
                    <div style={{ position: "absolute", top: startIdx * ITEM_HEIGHT, left: 0, right: 0 }}>
                        {visibleItems.map((item, i) => {
                            const idx = startIdx + i;
                            const isActive = idx === activeIdx;
                            return (
                                <div
                                    key={idx}
                                    style={{ height: ITEM_HEIGHT }}
                                    className={`flex gap-3 items-start px-1 py-2 border-b border-gray-50 last:border-0 transition-colors ${
                                        isActive ? "bg-violet-50" : ""
                                    } ${onSeek ? "cursor-pointer hover:bg-violet-50" : ""}`}
                                    onClick={() => onSeek?.(item.from)}
                                >
                                    <span className={`text-xs font-mono whitespace-nowrap pt-0.5 w-20 flex-shrink-0 ${
                                        isActive ? "text-violet-600 font-semibold" : onSeek ? "text-violet-400" : "text-gray-400"
                                    }`}>
                                        {formatTime(item.from)}
                                    </span>
                                    <span className={`text-sm leading-snug line-clamp-2 ${
                                        isActive ? "text-gray-900 font-medium" : "text-gray-700"
                                    }`}>
                                        {item.content}
                                    </span>
                                </div>
                            );
                        })}
                    </div>
                </div>
            </div>
        </div>
    );
}
