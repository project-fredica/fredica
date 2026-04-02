import { describe, expect, it } from "vitest";
import { validateWebenResult, normalizeWebenSource } from "~/util/materialWebenGuards";

describe("validateWebenResult", () => {
    it("allows free-form concept types", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "链表", types: ["数据结构"], description: "一种线性数据结构" },
            ],
        });

        expect(result.blockingErrors).toEqual([]);
        expect(result.warnings).toEqual([]);
        expect(result.sanitizedResult.concepts).toEqual([
            { name: "链表", types: ["数据结构"], description: "一种线性数据结构" },
        ]);
    });

    it("blocks concepts missing required fields", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "GPIO", types: [], description: "通用输入输出" },
            ],
        });

        expect(result.sanitizedResult.concepts).toEqual([]);
        expect(result.blockingErrors.map(item => item.message)).toEqual([
            "概念 #1 缺少名称、类型或描述",
        ]);
        expect(result.warnings.map(item => item.message)).toContain("当前结果没有可导入的有效概念。");
    });

    it("keeps valid entries for import payloads", () => {
        const result = validateWebenResult({
            concepts: [
                { name: "GPIO", types: ["术语"], description: "通用输入输出", aliases: ["General Purpose IO"] },
                { name: "链表", types: ["数据结构"], description: "一种线性数据结构" },
            ],
        });

        expect(result.blockingErrors).toEqual([]);
        expect(result.warnings).toEqual([]);
        expect(result.sanitizedResult).toEqual({
            concepts: [
                { name: "GPIO", types: ["术语"], description: "通用输入输出", aliases: ["General Purpose IO"] },
                { name: "链表", types: ["数据结构"], description: "一种线性数据结构" },
            ],
        });
    });
});

describe("normalizeWebenSource", () => {
    const BASE = {
        id: "abc-123",
        url: "https://www.bilibili.com/video/BV1xx411c7mD",
        title: "测试视频",
        source_type: "bilibili_video",
        quality_score: 0.8,
        analysis_status: "completed",
        progress: 0,
        created_at: 1700000000,
    };

    it("normalizes empty string material_id to null", () => {
        const result = normalizeWebenSource({ ...BASE, material_id: "" });
        expect(result?.material_id).toBeNull();
    });

    it("normalizes whitespace-only material_id to null", () => {
        const result = normalizeWebenSource({ ...BASE, material_id: "   " });
        expect(result?.material_id).toBeNull();
    });

    it("keeps a valid material_id UUID", () => {
        const uuid = "d1e2f3a4-b5c6-7890-abcd-ef1234567890";
        const result = normalizeWebenSource({ ...BASE, material_id: uuid });
        expect(result?.material_id).toBe(uuid);
    });

    it("keeps null material_id as null", () => {
        const result = normalizeWebenSource({ ...BASE, material_id: null });
        expect(result?.material_id).toBeNull();
    });

    it("returns null when id is missing", () => {
        expect(normalizeWebenSource({ ...BASE, id: "" })).toBeNull();
        expect(normalizeWebenSource(null)).toBeNull();
        expect(normalizeWebenSource(undefined)).toBeNull();
    });

    it("normalizes missing nullable fields to null", () => {
        const result = normalizeWebenSource({ id: "x", url: "u", title: "t", source_type: "s", quality_score: 0.5, analysis_status: "pending", progress: 0, created_at: 0 });
        expect(result?.material_id).toBeNull();
        expect(result?.bvid).toBeNull();
        expect(result?.duration_sec).toBeNull();
        expect(result?.workflow_run_id).toBeNull();
    });
});
