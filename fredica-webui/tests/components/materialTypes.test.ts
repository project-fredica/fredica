import { describe, expect, it } from "vitest";
import {
    groupCategories,
    formatRelativeTime,
    getSourceBadge,
    isBilibiliVideo,
    type MaterialCategory,
} from "~/components/material-library/materialTypes";

function makeCat(overrides: Partial<MaterialCategory> = {}): MaterialCategory {
    return {
        id: "cat-1",
        owner_id: "u1",
        name: "Test",
        description: "",
        allow_others_view: false,
        allow_others_add: false,
        allow_others_delete: false,
        material_count: 0,
        is_mine: true,
        sync: null,
        created_at: 0,
        updated_at: 0,
        ...overrides,
    };
}

// ── FE9: groupCategories 纯函数 ─────────────────────────────────────────────

describe("FE9 – groupCategories", () => {
    it("splits mixed categories into mine / publicOthers / synced", () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", is_mine: true, sync: null }),
            makeCat({ id: "b", is_mine: false, sync: null }),
            makeCat({
                id: "c", is_mine: true,
                sync: {
                    id: "pi-1", sync_type: "bilibili_favorite", platform_config: {},
                    display_name: "Fav", last_synced_at: null, item_count: 0,
                    sync_state: "idle", last_error: null, fail_count: 0,
                    subscriber_count: 1, my_subscription: null, owner_id: "u1",
                    last_workflow_run_id: null,
                },
            }),
        ];
        const g = groupCategories(cats);
        expect(g.mine.map(c => c.id)).toEqual(["a"]);
        expect(g.publicOthers.map(c => c.id)).toEqual(["b"]);
        expect(g.synced.map(c => c.id)).toEqual(["c"]);
    });

    it("returns empty arrays when input is empty", () => {
        const g = groupCategories([]);
        expect(g.mine).toEqual([]);
        expect(g.publicOthers).toEqual([]);
        expect(g.synced).toEqual([]);
    });
});

// ── FE9b: sync 优先级高于 is_mine ───────────────────────────────────────────

describe("FE9b – sync takes priority over is_mine", () => {
    it("is_mine=true with sync!=null goes to synced, not mine", () => {
        const cat = makeCat({
            id: "x", is_mine: true,
            sync: {
                id: "pi-2", sync_type: "bilibili_uploader", platform_config: {},
                display_name: "UP", last_synced_at: 1000, item_count: 5,
                sync_state: "syncing", last_error: null, fail_count: 0,
                subscriber_count: 2, my_subscription: null, owner_id: "u1",
                last_workflow_run_id: null,
            },
        });
        const g = groupCategories([cat]);
        expect(g.mine).toEqual([]);
        expect(g.synced).toHaveLength(1);
        expect(g.synced[0].id).toBe("x");
    });
});

// ── formatRelativeTime ──────────────────────────────────────────────────────

describe("formatRelativeTime", () => {
    it("returns '刚刚' for timestamps within 60 seconds", () => {
        const now = Math.floor(Date.now() / 1000);
        expect(formatRelativeTime(now - 10)).toBe("刚刚");
    });

    it("returns minutes for timestamps within 1 hour", () => {
        const now = Math.floor(Date.now() / 1000);
        expect(formatRelativeTime(now - 300)).toBe("5分钟前");
    });

    it("returns hours for timestamps within 1 day", () => {
        const now = Math.floor(Date.now() / 1000);
        expect(formatRelativeTime(now - 7200)).toBe("2小时前");
    });

    it("returns days for timestamps within 30 days", () => {
        const now = Math.floor(Date.now() / 1000);
        expect(formatRelativeTime(now - 86400 * 3)).toBe("3天前");
    });

    it("returns formatted date for timestamps older than 30 days", () => {
        const result = formatRelativeTime(1700000000);
        expect(result).toMatch(/\d{4}/);
    });
});

// ── getSourceBadge ─────────────────────────────────────────────────────────

describe("getSourceBadge", () => {
    it("returns 'B站' badge for all bilibili source types", () => {
        const bilibiliTypes = [
            "bilibili",
            "bilibili_favorite",
            "bilibili_uploader",
            "bilibili_season",
            "bilibili_series",
            "bilibili_video_pages",
        ];
        for (const st of bilibiliTypes) {
            const badge = getSourceBadge(st);
            expect(badge, `expected badge for "${st}"`).toBeDefined();
            expect(badge!.label).toBe("B站");
            expect(badge!.className).toContain("pink");
        }
    });

    it("returns same object reference for all bilibili types", () => {
        const a = getSourceBadge("bilibili_favorite");
        const b = getSourceBadge("bilibili_uploader");
        expect(a).toBe(b);
    });

    it("returns YouTube badge for 'youtube'", () => {
        const badge = getSourceBadge("youtube");
        expect(badge).toBeDefined();
        expect(badge!.label).toBe("YouTube");
    });

    it("returns 本地 badge for 'local'", () => {
        const badge = getSourceBadge("local");
        expect(badge).toBeDefined();
        expect(badge!.label).toBe("本地");
    });

    it("returns undefined for unknown source type", () => {
        expect(getSourceBadge("unknown")).toBeUndefined();
        expect(getSourceBadge("")).toBeUndefined();
    });
});

// ── isBilibiliVideo ────────────────────────────────────────────────────────

describe("isBilibiliVideo", () => {
    it("returns true for video + bilibili source types", () => {
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili" })).toBe(true);
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili_favorite" })).toBe(true);
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili_uploader" })).toBe(true);
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili_season" })).toBe(true);
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili_series" })).toBe(true);
        expect(isBilibiliVideo({ type: "video", source_type: "bilibili_video_pages" })).toBe(true);
    });

    it("returns false for non-video type even with bilibili source", () => {
        expect(isBilibiliVideo({ type: "audio", source_type: "bilibili_favorite" })).toBe(false);
        expect(isBilibiliVideo({ type: "article", source_type: "bilibili" })).toBe(false);
    });

    it("returns false for video type with non-bilibili source", () => {
        expect(isBilibiliVideo({ type: "video", source_type: "youtube" })).toBe(false);
        expect(isBilibiliVideo({ type: "video", source_type: "local" })).toBe(false);
    });

    it("returns false when both type and source_type are non-bilibili", () => {
        expect(isBilibiliVideo({ type: "audio", source_type: "youtube" })).toBe(false);
    });
});
