import { render, screen, act, fireEvent } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import type { MaterialVideo, MaterialCategory } from "~/components/material-library/materialTypes";

const mockApiFetch = vi.fn();
let mockSearchParams = new URLSearchParams();
const mockSetSearchParams = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useSearchParams: () => [mockSearchParams, mockSetSearchParams] as const,
        Link: ({ children, to }: { children: React.ReactNode; to: string }) => (
            <a href={String(to)}>{children}</a>
        ),
    };
});

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/util/error_handler", () => ({
    print_error: vi.fn(),
    reportHttpError: vi.fn(),
}));

vi.mock("~/components/sidebar/SidebarLayout", () => ({
    SidebarLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock("~/components/bilibili/BilibiliAiConclusionModal", () => ({
    BilibiliAiConclusionModal: () => null,
}));

vi.mock("~/components/material-library/MaterialActionModal", () => ({
    MaterialActionModal: () => null,
}));

vi.mock("~/components/material-library/SyncSubscriptionSettingsModal", () => ({
    SyncSubscriptionSettingsModal: () => null,
}));

vi.mock("~/components/material-library/MaterialCategoryPanel", () => ({
    MaterialCategoryPanel: (props: {
        effectiveCategoryId: string | null;
        onSelectCategory: (id: string | null) => void;
        categories: MaterialCategory[] | null;
    }) => (
        <div data-testid="category-panel">
            <span data-testid="effective-category">{props.effectiveCategoryId ?? "none"}</span>
            <button data-testid="select-cat-1" onClick={() => props.onSelectCategory("cat-1")}>
                Select cat-1
            </button>
            <button data-testid="select-cat-2" onClick={() => props.onSelectCategory("cat-2")}>
                Select cat-2
            </button>
            <button data-testid="select-all" onClick={() => props.onSelectCategory(null)}>
                Select all
            </button>
        </div>
    ),
}));

vi.mock("~/components/material-library/MaterialVideoRow", () => ({
    MaterialVideoRow: ({ video }: { video: MaterialVideo }) => (
        <div data-testid={`video-row-${video.id}`}>{video.title}</div>
    ),
}));

const makeVideo = (id: string, title: string, categoryIds: string[]): MaterialVideo => ({
    id,
    type: "video",
    source_type: "bilibili",
    source_id: id,
    title,
    cover_url: "",
    description: "",
    duration: 300,
    local_video_path: "",
    local_audio_path: "",
    transcript_path: "",
    extra: "{}",
    created_at: 1000,
    updated_at: 1000,
    category_ids: categoryIds,
});

const makeCategory = (id: string, name: string, isMine: boolean = true): MaterialCategory => ({
    id,
    owner_id: "user-1",
    name,
    description: "",
    allow_others_view: false,
    allow_others_add: false,
    allow_others_delete: false,
    material_count: 0,
    is_mine: isMine,
    sync: null,
    created_at: 1000,
    updated_at: 1000,
});

const VIDEOS: MaterialVideo[] = [
    makeVideo("bilibili_bvid__BV1aaa__P1", "Video A", ["cat-1"]),
    makeVideo("bilibili_bvid__BV1bbb__P1", "Video B", ["cat-2"]),
    makeVideo("bilibili_bvid__BV1ccc__P1", "Video C", ["cat-1", "cat-2"]),
    makeVideo("bilibili_bvid__BV1ddd__P1", "Video D", []),
];

const CATEGORIES: MaterialCategory[] = [
    makeCategory("cat-1", "Category 1"),
    makeCategory("cat-2", "Category 2"),
];

const makeOkResp = (data: unknown) => ({
    resp: new Response("{}", { status: 200 }),
    data,
});

const flush = () => act(async () => {});

function mockAllSuccess(videos = VIDEOS, categories = CATEGORIES) {
    mockApiFetch
        .mockResolvedValueOnce(makeOkResp(videos))
        .mockResolvedValueOnce(makeOkResp({ items: categories, total: categories.length }))
        .mockResolvedValueOnce(makeOkResp({}));
}

async function renderPage() {
    const mod = await import("~/routes/material-library._index");
    const LibraryPage = mod.default;
    render(<LibraryPage />);
    await flush();
}

beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.clearAllMocks();
    mockSearchParams = new URLSearchParams();
    mockSetSearchParams.mockImplementation((updater: (prev: URLSearchParams) => URLSearchParams) => {
        if (typeof updater === "function") {
            mockSearchParams = updater(mockSearchParams);
        }
    });
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

describe("F1 – category selection updates URL searchParams", () => {
    it("calls setSearchParams with category when selecting a category", async () => {
        mockAllSuccess();
        await renderPage();

        fireEvent.click(screen.getByTestId("select-cat-1"));

        expect(mockSetSearchParams).toHaveBeenCalledTimes(1);
        const updater = mockSetSearchParams.mock.calls[0][0];
        const result = updater(new URLSearchParams());
        expect(result.get("category")).toBe("cat-1");
    });
});

describe("F2 – restore category selection from URL", () => {
    it("passes URL category param as effectiveCategoryId", async () => {
        mockSearchParams = new URLSearchParams("category=cat-1");
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("effective-category").textContent).toBe("cat-1");
    });

    it("passes null when no category param in URL", async () => {
        mockSearchParams = new URLSearchParams();
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("effective-category").textContent).toBe("none");
    });
});

describe("F3 – clearing category selection removes URL parameter", () => {
    it("calls setSearchParams that deletes category key", async () => {
        mockSearchParams = new URLSearchParams("category=cat-1");
        mockAllSuccess();
        await renderPage();

        fireEvent.click(screen.getByTestId("select-all"));

        expect(mockSetSearchParams).toHaveBeenCalled();
        const updater = mockSetSearchParams.mock.calls[0][0];
        const result = updater(new URLSearchParams("category=cat-1"));
        expect(result.has("category")).toBe(false);
    });
});

describe("F4 – category filtering correctly filters videos", () => {
    it("shows only videos in selected category", async () => {
        mockSearchParams = new URLSearchParams("category=cat-1");
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("video-row-bilibili_bvid__BV1aaa__P1")).toBeTruthy();
        expect(screen.getByTestId("video-row-bilibili_bvid__BV1ccc__P1")).toBeTruthy();
        expect(screen.queryByTestId("video-row-bilibili_bvid__BV1bbb__P1")).toBeNull();
        expect(screen.queryByTestId("video-row-bilibili_bvid__BV1ddd__P1")).toBeNull();
    });

    it("shows only cat-2 videos when cat-2 selected", async () => {
        mockSearchParams = new URLSearchParams("category=cat-2");
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("video-row-bilibili_bvid__BV1bbb__P1")).toBeTruthy();
        expect(screen.getByTestId("video-row-bilibili_bvid__BV1ccc__P1")).toBeTruthy();
        expect(screen.queryByTestId("video-row-bilibili_bvid__BV1aaa__P1")).toBeNull();
        expect(screen.queryByTestId("video-row-bilibili_bvid__BV1ddd__P1")).toBeNull();
    });

    it("shows empty state when category has no videos", async () => {
        mockSearchParams = new URLSearchParams("category=cat-nonexistent");
        mockAllSuccess();
        await renderPage();

        expect(screen.getByText("该筛选条件下暂无素材")).toBeTruthy();
    });
});

describe("F5 – all view shows all videos including synced", () => {
    it("shows all videos when no category selected", async () => {
        mockSearchParams = new URLSearchParams();
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("video-row-bilibili_bvid__BV1aaa__P1")).toBeTruthy();
        expect(screen.getByTestId("video-row-bilibili_bvid__BV1bbb__P1")).toBeTruthy();
        expect(screen.getByTestId("video-row-bilibili_bvid__BV1ccc__P1")).toBeTruthy();
        expect(screen.getByTestId("video-row-bilibili_bvid__BV1ddd__P1")).toBeTruthy();
    });

    it("shows video count in header", async () => {
        mockSearchParams = new URLSearchParams();
        mockAllSuccess();
        await renderPage();

        expect(screen.getByText(/共 4 个视频/)).toBeTruthy();
    });
});

describe("F6 – invalid category in URL falls back gracefully", () => {
    it("treats nonexistent category as null effectiveCategoryId", async () => {
        mockSearchParams = new URLSearchParams("category=does-not-exist");
        mockAllSuccess();
        await renderPage();

        expect(screen.getByTestId("effective-category").textContent).toBe("none");
    });
});
