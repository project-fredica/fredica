import { render, screen, act } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import WebenSourceDetailPage from "~/routes/weben.sources.$id";

const mockApiFetch = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useParams: () => ({ id: "src-001" }),
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

const MOCK_SOURCE_ITEM = {
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
    concept_count: 2,
};

const MOCK_CONCEPTS = [
    {
        id: "con-001",
        canonical_name: "PWM 输出",
        concept_type: "术语",
        brief_definition: "脉冲宽度调制",
        metadata_json: "{}",
        confidence: 0.9,
        first_seen_at: 1743000000,
        last_seen_at: 1743000000,
        created_at: 1743000000,
        updated_at: 1743000000,
    },
    {
        id: "con-002",
        canonical_name: "预分频器",
        concept_type: "术语",
        brief_definition: "定时器预分频寄存器",
        metadata_json: "{}",
        confidence: 0.8,
        first_seen_at: 1743000000,
        last_seen_at: 1743000000,
        created_at: 1743000000,
        updated_at: 1743000000,
    },
];

const makeOkResp = (data: unknown) => ({
    resp: new Response("{}", { status: 200 }),
    data,
});

const flush = () => act(async () => {});

function mockAllSuccess() {
    mockApiFetch
        .mockResolvedValueOnce(makeOkResp(MOCK_SOURCE_ITEM))
        .mockResolvedValueOnce(makeOkResp({ items: MOCK_CONCEPTS, total: 2, offset: 0, limit: 50 }));
}

beforeEach(() => { vi.clearAllMocks(); });
afterEach(() => { vi.restoreAllMocks(); });

describe("D1 – initial render shows skeleton", () => {
    it("shows animate-pulse skeleton before data loads", () => {
        mockApiFetch.mockReturnValue(new Promise(() => {}));
        render(<WebenSourceDetailPage />);
        expect(document.querySelector(".animate-pulse")).toBeTruthy();
    });
});

describe("D2 – success: shows source title and concepts", () => {
    it("renders the source title as heading", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText("STM32 定时器深度解析")).toBeTruthy();
    });

    it("renders concept names", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText("PWM 输出")).toBeTruthy();
        expect(screen.getByText("预分频器")).toBeTruthy();
    });

    it("renders status badge", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText("已完成")).toBeTruthy();
    });
});

describe("D3 – shows source link and concept summary", () => {
    it("shows source url link", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        const sourceLink = document.querySelector<HTMLAnchorElement>('a[href="https://www.bilibili.com/video/BV1xx"]');
        expect(sourceLink).toBeTruthy();
    });

    it("shows extracted concept count", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText("2")).toBeTruthy();
        expect(screen.getByText(/已提取概念数/)).toBeTruthy();
    });
});

describe("D4 – source not found shows error message", () => {
    it("shows not-found message when source data is null", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(null));
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText(/找不到|不存在|未找到/)).toBeTruthy();
    });
});

describe("D5 – empty concepts shows placeholder", () => {
    it("shows empty text when concept list is empty", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeOkResp(MOCK_SOURCE_ITEM))
            .mockResolvedValueOnce(makeOkResp({ items: [], total: 0, offset: 0, limit: 50 }));
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.getByText(/暂无|尚未|未提取/)).toBeTruthy();
    });
});

describe("D6 – removed flashcard UI", () => {
    it("does not render flashcard or review entry points", async () => {
        mockAllSuccess();
        render(<WebenSourceDetailPage />);
        await flush();
        expect(screen.queryByText(/闪卡|复习本来源/)).toBeNull();
    });
});
