/**
 * floatingPlayer.tsx
 *
 * App 级悬浮播放器 Context（Mode B）。
 * Provider 挂在 SidebarLayout，全应用唯一实例。
 */

import { createContext, useCallback, useContext, useState } from "react";
import type { PendingSeek } from "~/hooks/useVideoPlayerState";

// ── Context 类型 ──────────────────────────────────────────────────────────────

export interface FloatingPlayerCtxType {
    openFloatingPlayer: (materialId: string, seekTo?: number) => void;
    closeFloatingPlayer: () => void;
    currentMaterialId: string | null;
    isVisible: boolean;
    /** 当前待执行的 seek（由 openFloatingPlayer 写入，FloatingVideoPlayerSingleton 读取并消费） */
    pendingSeek: PendingSeek | null;
    consumePendingSeek: () => PendingSeek | null;
}

const FloatingPlayerContext = createContext<FloatingPlayerCtxType | null>(null);

export function useFloatingPlayerCtx() {
    const ctx = useContext(FloatingPlayerContext);
    if (!ctx) throw new Error("useFloatingPlayerCtx must be used inside FloatingPlayerProvider");
    return ctx;
}

// ── Provider ──────────────────────────────────────────────────────────────────

export function FloatingPlayerProvider({ children }: { children: React.ReactNode }) {
    const [currentMaterialId, setCurrentMaterialId] = useState<string | null>(null);
    const [isVisible, setIsVisible] = useState(false);
    const [pendingSeek, setPendingSeek] = useState<PendingSeek | null>(null);

    const openFloatingPlayer = useCallback((materialId: string, seekTo?: number) => {
        setCurrentMaterialId(materialId);
        setIsVisible(true);
        if (seekTo != null) {
            setPendingSeek({ seconds: seekTo, autoPlay: true });
        }
    }, []);

    const closeFloatingPlayer = useCallback(() => {
        setIsVisible(false);
        setCurrentMaterialId(null);
        setPendingSeek(null);
    }, []);

    const consumePendingSeek = useCallback((): PendingSeek | null => {
        const s = pendingSeek;
        setPendingSeek(null);
        return s;
    }, [pendingSeek]);

    return (
        <FloatingPlayerContext.Provider
            value={{
                openFloatingPlayer,
                closeFloatingPlayer,
                currentMaterialId,
                isVisible,
                pendingSeek,
                consumePendingSeek,
            }}
        >
            {children}
        </FloatingPlayerContext.Provider>
    );
}
