/**
 * floatingPlayer.test.ts
 *
 * FloatingPlayerProvider / useFloatingPlayerCtx 单元测试。
 * 覆盖设计文档 §11.3 的 F1–F4 测试用例。
 */

import { renderHook, act } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FloatingPlayerProvider, useFloatingPlayerCtx } from "~/context/floatingPlayer";

// ── wrapper ───────────────────────────────────────────────────────────────────

const wrapper = ({ children }: { children: React.ReactNode }) => (
    <FloatingPlayerProvider>{children}</FloatingPlayerProvider>
);

// ── F1: 初始状态 ──────────────────────────────────────────────────────────────

describe("F1 – initial state", () => {
    it("isVisible=false, currentMaterialId=null", () => {
        const { result } = renderHook(() => useFloatingPlayerCtx(), { wrapper });
        expect(result.current.isVisible).toBe(false);
        expect(result.current.currentMaterialId).toBeNull();
    });
});

// ── F2: openFloatingPlayer('mat-1') → isVisible=true, currentMaterialId='mat-1' ─

describe("F2 – openFloatingPlayer sets visible and materialId", () => {
    it("isVisible=true, currentMaterialId='mat-1' after open", () => {
        const { result } = renderHook(() => useFloatingPlayerCtx(), { wrapper });
        act(() => { result.current.openFloatingPlayer("mat-1"); });
        expect(result.current.isVisible).toBe(true);
        expect(result.current.currentMaterialId).toBe("mat-1");
    });
});

// ── F3: openFloatingPlayer('mat-1', 42) → pendingSeek={seconds:42, autoPlay:true} ─

describe("F3 – openFloatingPlayer with seekTo sets pendingSeek", () => {
    it("pendingSeek = {seconds:42, autoPlay:true}", () => {
        const { result } = renderHook(() => useFloatingPlayerCtx(), { wrapper });
        act(() => { result.current.openFloatingPlayer("mat-1", 42); });
        expect(result.current.pendingSeek).toEqual({ seconds: 42, autoPlay: true });
    });
});

// ── F4: closeFloatingPlayer() → isVisible=false, currentMaterialId=null ──────

describe("F4 – closeFloatingPlayer resets state", () => {
    it("isVisible=false, currentMaterialId=null after close", () => {
        const { result } = renderHook(() => useFloatingPlayerCtx(), { wrapper });
        act(() => { result.current.openFloatingPlayer("mat-1"); });
        act(() => { result.current.closeFloatingPlayer(); });
        expect(result.current.isVisible).toBe(false);
        expect(result.current.currentMaterialId).toBeNull();
    });
});
