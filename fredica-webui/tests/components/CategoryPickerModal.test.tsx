import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { CategoryPickerModal } from "~/components/bilibili/CategoryPickerModal";
import type { MaterialCategory } from "~/components/material-library/materialTypes";

const mockApiFetch = vi.fn();

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/util/error_handler", () => ({
    reportHttpError: vi.fn(),
    print_error: vi.fn(),
}));

beforeEach(() => {
    mockApiFetch.mockReset();
});

function makeCat(overrides: Partial<MaterialCategory> = {}): MaterialCategory {
    return {
        id: "cat-1",
        owner_id: "u1",
        name: "Test",
        description: "",
        allow_others_view: false,
        allow_others_add: false,
        allow_others_delete: false,
        material_count: 3,
        is_mine: true,
        sync: null,
        created_at: 0,
        updated_at: 0,
        ...overrides,
    };
}

function setupListResponse(cats: MaterialCategory[]) {
    mockApiFetch.mockResolvedValueOnce({
        resp: { ok: true },
        data: { items: cats },
    });
}

// ── FE8: 仅显示 is_mine 且非同步分类 ────────────────────────────────────────

describe("FE8 – only shows is_mine non-sync categories", () => {
    it("filters out non-mine and sync categories", async () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", name: "我的分类", is_mine: true, sync: null }),
            makeCat({ id: "b", name: "他人分类", is_mine: false, sync: null }),
            makeCat({
                id: "c", name: "同步分类", is_mine: true,
                sync: {
                    id: "pi-1", sync_type: "bilibili_favorite", platform_config: {},
                    display_name: "Fav", last_synced_at: null, item_count: 0,
                    sync_state: "idle", last_error: null, fail_count: 0,
                    subscriber_count: 1, my_subscription: null, owner_id: "u1",
                    last_workflow_run_id: null,
                },
            }),
        ];
        setupListResponse(cats);

        render(
            <CategoryPickerModal
                videoCount={1}
                onConfirm={vi.fn()}
                onCancel={vi.fn()}
            />
        );

        await waitFor(() => {
            expect(screen.getByText("我的分类")).toBeTruthy();
        });
        expect(screen.queryByText("他人分类")).toBeNull();
        expect(screen.queryByText("同步分类")).toBeNull();
    });

    it("sends filter: mine in API request", async () => {
        setupListResponse([]);

        render(
            <CategoryPickerModal
                videoCount={1}
                onConfirm={vi.fn()}
                onCancel={vi.fn()}
            />
        );

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledTimes(1);
        });

        const [url, opts] = mockApiFetch.mock.calls[0];
        expect(url).toContain("MaterialCategoryListRoute");
        const body = JSON.parse(opts.body);
        expect(body.filter).toBe("mine");
    });
});

// ── FE8b: 新建分类后自动勾选 ────────────────────────────────────────────────

describe("FE8b – newly created category is auto-selected", () => {
    it("creates category and auto-checks it", async () => {
        setupListResponse([
            makeCat({ id: "existing", name: "已有分类" }),
        ]);

        const onConfirm = vi.fn();
        render(
            <CategoryPickerModal
                videoCount={1}
                onConfirm={onConfirm}
                onCancel={vi.fn()}
            />
        );

        await waitFor(() => {
            expect(screen.getByText("已有分类")).toBeTruthy();
        });

        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { id: "new-cat", name: "新建的", material_count: 0, is_mine: true, sync: null, owner_id: "u1", description: "", allow_others_view: false, allow_others_add: false, allow_others_delete: false, created_at: 0, updated_at: 0 },
        });

        const input = screen.getByPlaceholderText("新建分类名称…");
        fireEvent.change(input, { target: { value: "新建的" } });
        fireEvent.click(screen.getByText("创建"));

        await waitFor(() => {
            expect(screen.getByText("新建的")).toBeTruthy();
        });

        const newCheckbox = screen.getByText("新建的").closest('label')!.querySelector('input[type="checkbox"]') as HTMLInputElement;
        expect(newCheckbox.checked).toBe(true);
    });
});
