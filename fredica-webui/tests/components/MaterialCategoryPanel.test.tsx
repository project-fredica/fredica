import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MaterialCategoryPanel } from "~/components/material-library/MaterialCategoryPanel";
import type { MaterialCategory, MaterialVideo } from "~/components/material-library/materialTypes";

const mockSetSearchParams = vi.fn();
const mockNavigate = vi.fn();
vi.mock("react-router", () => ({
    useSearchParams: () => [new URLSearchParams(), mockSetSearchParams],
    useNavigate: () => mockNavigate,
}));

beforeEach(() => {
    vi.restoreAllMocks();
    mockNavigate.mockReset();
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

function makeSyncCat(overrides: Partial<MaterialCategory> = {}): MaterialCategory {
    return makeCat({
        id: "sync-1",
        name: "B站收藏夹",
        sync: {
            id: "pi-1",
            sync_type: "bilibili_favorite",
            platform_config: {},
            display_name: "我的收藏",
            last_synced_at: Math.floor(Date.now() / 1000) - 300,
            item_count: 10,
            sync_state: "idle",
            last_error: null,
            fail_count: 0,
            subscriber_count: 1,
            my_subscription: null,
            owner_id: "u1",
            last_workflow_run_id: null,
        },
        ...overrides,
    });
}

const defaultProps = {
    categories: null as MaterialCategory[] | null,
    categoriesLoading: false,
    videos: null as MaterialVideo[] | null,
    effectiveCategoryId: null as string | null,
    deletingCategoryIds: new Set<string>(),
    newCategoryName: "",
    creatingCategory: false,
    taskFilterMaterialId: null as string | null | undefined,
    onSelectCategory: vi.fn(),
    onDeleteCategory: vi.fn(),
    onNewCategoryNameChange: vi.fn(),
    onCreateCategory: vi.fn(),
    onSyncTrigger: vi.fn(),
    onRenameCategory: vi.fn(),
};

// ── FE1: 分类列表三组渲染 ───────────────────────────────────────────────────

describe("FE1 – three-group rendering", () => {
    it("renders mine, publicOthers, and synced sections", () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", name: "我的A", is_mine: true }),
            makeCat({ id: "b", name: "公开B", is_mine: false }),
            makeSyncCat({ id: "c", name: "同步C" }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} />);

        expect(screen.getByText("我的分类")).toBeTruthy();
        expect(screen.getByText("公开分类")).toBeTruthy();
        expect(screen.getByText("同步信源")).toBeTruthy();
    });
});

// ── FE1b: 空分组渲染行为 ────────────────────────────────────────────────────

describe("FE1b – empty groups rendering", () => {
    it("hides '公开分类' but always shows '我的分类' and '同步信源'", () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", name: "我的A", is_mine: true }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} />);

        expect(screen.getByText("我的分类")).toBeTruthy();
        expect(screen.queryByText("公开分类")).toBeNull();
        expect(screen.getByText("同步信源")).toBeTruthy();
    });

    it("shows placeholder text when no synced categories exist", () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", name: "我的A", is_mine: true }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} />);

        expect(screen.getByText(/暂无同步信源/)).toBeTruthy();
    });

    it("always shows '我的分类' header even with only synced categories", () => {
        const cats: MaterialCategory[] = [
            makeSyncCat({ id: "s1", name: "同步源" }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} />);

        expect(screen.getByText("我的分类")).toBeTruthy();
        expect(screen.getByText("同步信源")).toBeTruthy();
    });
});

// ── FE1c: "全部"按钮始终可见 ────────────────────────────────────────

describe("FE1c – '全部' button always visible", () => {
    it("renders '全部' button that clears category filter on click", () => {
        const onSelect = vi.fn();
        const cats: MaterialCategory[] = [
            makeCat({ id: "a", name: "分类A" }),
        ];
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={cats}
                videos={[{ id: "v1", category_ids: [] } as unknown as MaterialVideo]}
                onSelectCategory={onSelect}
            />
        );

        const allBtn = screen.getByText(/^全部/);
        expect(allBtn).toBeTruthy();
        fireEvent.click(allBtn);
        expect(onSelect).toHaveBeenCalledWith(null);
    });

    it("renders '全部' even when user has no personal categories", () => {
        const cats: MaterialCategory[] = [
            makeSyncCat({ id: "s1", name: "同步源" }),
        ];
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={cats}
                videos={[{ id: "v1", category_ids: [] } as unknown as MaterialVideo]}
            />
        );

        expect(screen.getByText(/^全部/)).toBeTruthy();
    });
});

// ── FE1d: "未分类"按钮 ────────────────────────────────────────────────────

describe("FE1d – '未分类' button", () => {
    it("renders '未分类' button with count of uncategorized videos", () => {
        const onSelect = vi.fn();
        const cats: MaterialCategory[] = [makeCat({ id: "a", name: "分类A" })];
        const videos = [
            { id: "v1", category_ids: [] } as unknown as MaterialVideo,
            { id: "v2", category_ids: ["a"] } as unknown as MaterialVideo,
            { id: "v3", category_ids: [] } as unknown as MaterialVideo,
        ];
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={cats}
                videos={videos}
                onSelectCategory={onSelect}
            />
        );

        const uncatBtn = screen.getByText(/^未分类/);
        expect(uncatBtn).toBeTruthy();
        expect(uncatBtn.textContent).toContain("2");
        fireEvent.click(uncatBtn);
        expect(onSelect).toHaveBeenCalledWith("__uncategorized__");
    });
});

// ── FE2: is_mine=false 时隐藏编辑/删除 ──────────────────────────────────────

describe("FE2 – public categories have no delete button", () => {
    it("public category pill has no X button", () => {
        const cats: MaterialCategory[] = [
            makeCat({ id: "pub", name: "公开分类X", is_mine: false, material_count: 5 }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} />);

        expect(screen.getByText(/公开分类X/)).toBeTruthy();
        const pubSection = screen.getByText("公开分类").parentElement!;
        const deleteButtons = pubSection.querySelectorAll('button[title*="删除"]');
        expect(deleteButtons.length).toBe(0);
    });
});

// ── FE2b: 同步分类 pill 显示同步状态标签 ────────────────────────────────────

describe("FE2b – sync category pill shows sync state label", () => {
    it("shows '同步中' when sync_state is syncing", () => {
        const cat = makeSyncCat({
            id: "s1", name: "同步中分类",
            sync: {
                id: "pi-1", sync_type: "bilibili_favorite", platform_config: {},
                display_name: "Fav", last_synced_at: null, item_count: 5,
                sync_state: "syncing", last_error: null, fail_count: 0,
                subscriber_count: 1, my_subscription: null, owner_id: "u1",
                last_workflow_run_id: null,
            },
        });
        render(<MaterialCategoryPanel {...defaultProps} categories={[cat]} />);
        expect(screen.getByText("同步中")).toBeTruthy();
    });

    it("shows relative time when sync_state is idle and has last_synced_at", () => {
        const cat = makeSyncCat({
            id: "s2", name: "已同步分类",
            sync: {
                id: "pi-2", sync_type: "bilibili_favorite", platform_config: {},
                display_name: "Fav", last_synced_at: Math.floor(Date.now() / 1000) - 120,
                item_count: 5, sync_state: "idle", last_error: null, fail_count: 0,
                subscriber_count: 1, my_subscription: null, owner_id: "u1",
                last_workflow_run_id: null,
            },
        });
        render(<MaterialCategoryPanel {...defaultProps} categories={[cat]} />);
        expect(screen.getByText("2分钟前")).toBeTruthy();
    });
});

// ── FE2c: 我的分类 inline 按钮 ──────────────────────────────────────────────

describe("FE2c – mine category inline buttons", () => {
    it("shows rename (Pencil) button and triggers onRenameCategory via modal", () => {
        const onRename = vi.fn();
        const cats: MaterialCategory[] = [
            makeCat({ id: "m1", name: "我的分类1" }),
        ];
        render(<MaterialCategoryPanel {...defaultProps} categories={cats} onRenameCategory={onRename} />);

        const renameBtn = screen.getByTitle('重命名分类「我的分类1」');
        expect(renameBtn).toBeTruthy();
        fireEvent.click(renameBtn);

        expect(screen.getByText("重命名分类")).toBeTruthy();
        const input = screen.getByDisplayValue("我的分类1");
        fireEvent.change(input, { target: { value: "新名称" } });
        fireEvent.click(screen.getByText("确认"));
        expect(onRename).toHaveBeenCalledWith("m1", "新名称");
    });
});

// ── FE2d: 同步分类 inline 按钮 ──────────────────────────────────────────────

describe("FE2d – sync category inline buttons", () => {
    it("shows inline sync button on sync pill", () => {
        const onSync = vi.fn();
        const cat = makeSyncCat({ id: "sc1", name: "同步源1" });
        render(<MaterialCategoryPanel {...defaultProps} categories={[cat]} onSyncTrigger={onSync} />);

        const syncBtn = screen.getByTitle("立即同步");
        expect(syncBtn).toBeTruthy();
        fireEvent.click(syncBtn);
        expect(onSync).toHaveBeenCalledWith("pi-1");
    });

    it("shows 删除数据源 in expanded panel when user is owner", () => {
        const onDelete = vi.fn();
        const cat = makeSyncCat({
            id: "sc2", name: "同步源2", owner_id: "u1",
            sync: {
                id: "pi-3", sync_type: "bilibili_favorite", platform_config: {},
                display_name: "Fav", last_synced_at: null, item_count: 0,
                sync_state: "idle", last_error: null, fail_count: 0,
                subscriber_count: 1, my_subscription: null, owner_id: "u1",
                last_workflow_run_id: null,
            },
        });
        render(<MaterialCategoryPanel {...defaultProps} categories={[cat]} onDeleteCategory={onDelete} />);

        const pillButton = screen.getByText(/\[B站收藏夹\] Fav/).closest('button')!;
        const expandSpans = pillButton.querySelectorAll('span[role="button"]');
        const expandBtn = expandSpans[expandSpans.length - 1]!;
        fireEvent.click(expandBtn);

        expect(screen.getByText("删除数据源")).toBeTruthy();
    });
});

// ── FE4: 创建分类表单提交 ───────────────────────────────────────────────────

describe("FE4 – create category form", () => {
    it("calls onCreateCategory when Enter is pressed in input", () => {
        const onCreate = vi.fn();
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={[]}
                newCategoryName="新分类"
                onCreateCategory={onCreate}
            />
        );

        const input = screen.getByPlaceholderText("新建分类名称…");
        fireEvent.keyDown(input, { key: "Enter" });
        expect(onCreate).toHaveBeenCalledTimes(1);
    });

    it("calls onCreateCategory when 创建 button is clicked", () => {
        const onCreate = vi.fn();
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={[]}
                newCategoryName="新分类"
                onCreateCategory={onCreate}
            />
        );

        fireEvent.click(screen.getByText("创建"));
        expect(onCreate).toHaveBeenCalledTimes(1);
    });
});

// ── FE5: 删除分类 ───────────────────────────────────────────────────────────

describe("FE5 – delete category", () => {
    it("calls onDeleteCategory when X button is clicked on mine category", () => {
        const onDelete = vi.fn();
        const cats: MaterialCategory[] = [
            makeCat({ id: "del-1", name: "待删除" }),
        ];
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={cats}
                onDeleteCategory={onDelete}
            />
        );

        const deleteBtn = screen.getByTitle('删除分类「待删除」');
        fireEvent.click(deleteBtn);
        expect(onDelete).toHaveBeenCalledWith("del-1");
    });
});

// ── FE6: 手动触发同步按钮 ───────────────────────────────────────────────────

describe("FE6 – manual sync trigger", () => {
    it("calls onSyncTrigger from expanded detail panel", () => {
        const onSync = vi.fn();
        const cat = makeSyncCat({ id: "st1", name: "同步触发" });
        render(
            <MaterialCategoryPanel
                {...defaultProps}
                categories={[cat]}
                onSyncTrigger={onSync}
            />
        );

        const pillButton = screen.getByText(/\[B站收藏夹\] 我的收藏/).closest('button')!;
        const expandSpans = pillButton.querySelectorAll('span[role="button"]');
        const expandBtn = expandSpans[expandSpans.length - 1]!;
        fireEvent.click(expandBtn);

        const syncButtons = screen.getAllByText("立即同步");
        const detailSyncBtn = syncButtons.find(btn => btn.closest('.bg-gray-50'));
        expect(detailSyncBtn).toBeTruthy();
        fireEvent.click(detailSyncBtn!);
        expect(onSync).toHaveBeenCalledWith("pi-1");
    });
});
