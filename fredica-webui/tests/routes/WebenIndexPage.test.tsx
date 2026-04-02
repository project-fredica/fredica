import { render, screen, act } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import WebenIndexPage from "~/routes/weben._index";

const mockApiFetch = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        Link: ({ children, to }: { children: React.ReactNode; to: string }) => (
            <a href={String(to)}>{children}</a>
        ),
    };
});

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/components/sidebar/SidebarLayout", () => ({
    SidebarLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const MOCK_SOURCES = [
    {
        source: {
            id: "src-001",
            material_id: "mat-001",
            url: "https://bilibili.com/BV1xx",
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
];

const MOCK_CONCEPTS = [
    {
        id: "con-001", canonical_name: "PWM 输出", concept_type: "术语",
        brief_definition: null, metadata_json: "{}", confidence: 0.9,
        first_seen_at: 1743000000, last_seen_at: 1743000000, created_at: 1743000000, updated_at: 1743000000,
    },
    {
        id: "con-002", canonical_name: "预分频器", concept_type: "术语",
        brief_definition: null, metadata_json: "{}", confidence: 0.8,
        first_seen_at: 1743000000, last_seen_at: 1743000000, created_at: 1743000000, updated_at: 1743000000,
    },
];

const makeOkResp = (data: unknown) => ({
    resp: new Response("{}", { status: 200 }),
    data,
});

const makeSourcePageResult = (items: unknown[]) => ({ items, total: items.length, offset: 0, limit: 5 });
const makeConceptPageResult = (items: unknown[]) => ({ items, total: items.length, offset: 0, limit: 10 });
const flush = () => act(async () => {});

function mockAllSuccess() {
    mockApiFetch
        .mockResolvedValueOnce(makeOkResp(makeConceptPageResult(MOCK_CONCEPTS)))
        .mockResolvedValueOnce(makeOkResp(makeSourcePageResult(MOCK_SOURCES)));
}

beforeEach(() => { vi.clearAllMocks(); });
afterEach(() => { vi.restoreAllMocks(); });

describe("W1 – shows stat summary row", () => {
    it("shows concept count and source entry", async () => {
        mockAllSuccess();
        render(<WebenIndexPage />);
        await flush();
        expect(document.body.textContent).toMatch(/2.*个概念/);
        expect(screen.getByText("来源库")).toBeTruthy();
    });
});

describe("W2 – source timeline renders source info", () => {
    it("shows source title in timeline", async () => {
        mockAllSuccess();
        render(<WebenIndexPage />);
        await flush();
        expect(screen.getByText("STM32 定时器深度解析")).toBeTruthy();
    });

    it("shows concept count badge for source", async () => {
        mockAllSuccess();
        render(<WebenIndexPage />);
        await flush();
        expect(screen.getByText(/8.*概念|概念.*8/)).toBeTruthy();
    });
});

describe("W3 – source links to /weben/sources/{id}", () => {
    it("source card anchor href points to /weben/sources/src-001", async () => {
        mockAllSuccess();
        render(<WebenIndexPage />);
        await flush();
        const link = document.querySelector<HTMLAnchorElement>('a[href*="/weben/sources/src-001"]');
        expect(link).toBeTruthy();
    });
});

describe("W4 – recent concepts render", () => {
    it("shows recent concept names", async () => {
        mockAllSuccess();
        render(<WebenIndexPage />);
        await flush();
        expect(screen.getByText("PWM 输出")).toBeTruthy();
        expect(screen.getByText("预分频器")).toBeTruthy();
    });
});

describe("W5 – empty sources shows placeholder", () => {
    it("shows placeholder when source list is empty", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeOkResp(makeConceptPageResult(MOCK_CONCEPTS)))
            .mockResolvedValueOnce(makeOkResp(makeSourcePageResult([])));
        render(<WebenIndexPage />);
        await flush();
        expect(screen.getByText(/暂无|还没有|尚无/)).toBeTruthy();
    });
});
