import { render, screen, act, fireEvent } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import WebenSourcesIndexPage from "~/routes/weben.sources._index";

const mockApiFetch = vi.fn();
const mockSetSearchParams = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useSearchParams: () => [new URLSearchParams(), mockSetSearchParams],
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

const MOCK_SOURCES = [
    {
        source: {
            id: "src-001",
            material_id: "mat-001",
            url: "https://www.bilibili.com/video/BV1xx",
            title: "STM32 定时器深度解析",
            source_type: "bilibili_video",
            bvid: "BV1xx",
            duration_sec: 1800,
            quality_score: 0.9,
            analysis_status: "completed",
            workflow_run_id: "wf-001",
            progress: 100,
            created_at: 1743000000,
        },
        concept_count: 8,
    },
    {
        source: {
            id: "src-002",
            material_id: "mat-002",
            url: "https://www.bilibili.com/video/BV2yy",
            title: "GPIO 入门教程",
            source_type: "bilibili_video",
            bvid: "BV2yy",
            duration_sec: 900,
            quality_score: 0.8,
            analysis_status: "analyzing",
            workflow_run_id: "wf-002",
            progress: 45,
            created_at: 1742900000,
        },
        concept_count: 0,
    },
];

const makeOkResp = (data: unknown) => ({
    resp: new Response("{}", { status: 200 }),
    data,
});

const makePageResult = (items: unknown[]) => ({ items, total: items.length, offset: 0, limit: 20 });
const flush = () => act(async () => {});

beforeEach(() => { vi.clearAllMocks(); });
afterEach(() => { vi.restoreAllMocks(); });

describe("S1 – initial render shows loading", () => {
    it("shows loading state before API resolves", () => {
        mockApiFetch.mockReturnValue(new Promise(() => {}));
        render(<WebenSourcesIndexPage />);
        expect(document.querySelector(".animate-pulse") || screen.queryByText(/加载/)).toBeTruthy();
    });
});

describe("S2 – success: renders source cards", () => {
    it("displays source titles", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult(MOCK_SOURCES)));
        render(<WebenSourcesIndexPage />);
        await flush();
        expect(screen.getByText("STM32 定时器深度解析")).toBeTruthy();
        expect(screen.getByText("GPIO 入门教程")).toBeTruthy();
    });

    it("displays concept count for completed source", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult(MOCK_SOURCES)));
        render(<WebenSourcesIndexPage />);
        await flush();
        expect(screen.getByText(/8.*概念|概念.*8/)).toBeTruthy();
        expect(screen.queryByText(/闪卡/)).toBeNull();
    });

    it("displays status badges", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult(MOCK_SOURCES)));
        render(<WebenSourcesIndexPage />);
        await flush();
        expect(screen.getAllByText("已完成").length).toBeGreaterThan(0);
        expect(screen.getAllByText("分析中").length).toBeGreaterThan(0);
    });
});

describe("S3 – empty state when no sources", () => {
    it("shows empty prompt when items list is empty", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult([])));
        render(<WebenSourcesIndexPage />);
        await flush();
        expect(screen.getByText(/暂无|还没有|未分析/)).toBeTruthy();
    });
});

describe("S4 – network error shows error state", () => {
    it("shows error text on fetch rejection", async () => {
        mockApiFetch.mockRejectedValueOnce(new Error("Network error"));
        render(<WebenSourcesIndexPage />);
        await flush();
        expect(screen.getByText(/加载失败，请刷新重试/)).toBeTruthy();
    });
});

describe("S5 – status filter triggers re-fetch", () => {
    it("calls setSearchParams with 'completed' when filter select changes", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult(MOCK_SOURCES)));
        render(<WebenSourcesIndexPage />);
        await flush();

        const select = document.querySelector("select") as HTMLSelectElement;
        expect(select).toBeTruthy();
        await act(async () => { fireEvent.change(select, { target: { value: "completed" } }); });

        expect(mockSetSearchParams).toHaveBeenCalledWith({ status: "completed" });
    });
});

describe("S6 – source card links to detail page", () => {
    it("completed source card has link to /weben/sources/{id}", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(makePageResult(MOCK_SOURCES)));
        render(<WebenSourcesIndexPage />);
        await flush();

        const links = document.querySelectorAll<HTMLAnchorElement>('a[href*="src-001"]');
        expect(links.length).toBeGreaterThan(0);
        expect(links[0].href).toContain("/weben/sources/src-001");
    });
});
