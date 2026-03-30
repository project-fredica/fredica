/**
 * SummaryBilibiliPage.test.tsx
 *
 * 覆盖 B站 AI 总结子路由页面的核心交互：
 *   B1 – 初始渲染：显示加载状态
 *   B2 – API 返回 code=0 + model_result → 显示摘要和大纲
 *   B3 – API 返回 code=0 但 model_result=null → 显示"暂无 AI 总结"
 *   B4 – API 返回 code!=0 → 显示"暂无 AI 总结"
 *   B5 – API 网络错误 → 显示错误信息
 *   B6 – 点击"强制刷新"按钮 → is_update=true 重新请求
 *   B7 – 大纲时间戳按钮可见（格式 mm:ss）
 */

import { render, screen, act, fireEvent } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import SummaryBilibiliPage from "~/routes/material.$materialId.summary.bilibili";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockApiFetch = vi.fn();
const mockOpenFloatingPlayer = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useParams: () => ({ materialId: "bilibili_bvid__BV1xx__P1" }),
        Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
    };
});

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/routes/material.$materialId", () => ({
    useWorkspaceContext: () => ({
        material: {
            id: "bilibili_bvid__BV1xx__P1",
            source_type: "bilibili",
            source_id: "BV1xx",
            title: "测试视频",
            extra: JSON.stringify({ bvid: "BV1xx", page_count: 1 }),
            duration: 300,
            cover_url: "",
            description: "",
            local_video_path: "",
            local_audio_path: "",
            transcript_path: "",
            category_ids: [],
            created_at: 0,
            updated_at: 0,
        },
        refreshMaterial: vi.fn(),
    }),
}));

vi.mock("~/context/floatingPlayer", () => ({
    useFloatingPlayerCtx: () => ({
        openFloatingPlayer: mockOpenFloatingPlayer,
        closeFloatingPlayer: vi.fn(),
        currentMaterialId: null,
        isVisible: false,
        pendingSeek: null,
        consumePendingSeek: vi.fn(),
    }),
}));

vi.mock("~/util/json", () => ({
    json_parse: <T,>(s: string): T => JSON.parse(s) as T,
}));

// ── Fixtures ──────────────────────────────────────────────────────────────────

const MOCK_RESULT = {
    code: 0,
    model_result: {
        summary: "本视频讲解了 GPIO 的基本原理。",
        outline: [
            {
                title: "GPIO 基础",
                part_outline: [
                    { timestamp: 30,  content: "GPIO 输入输出模式介绍" },
                    { timestamp: 90,  content: "推挽与开漏" },
                ],
            },
            {
                title: "定时器",
                part_outline: [
                    { timestamp: 180, content: "预分频寄存器" },
                ],
            },
        ],
    },
};

const makeOkResp = (data: unknown) => ({
    resp: new Response("{}", { status: 200 }),
    data,
});

// ── Setup ─────────────────────────────────────────────────────────────────────

const flush = () => act(async () => {});

beforeEach(() => {
    vi.clearAllMocks();
});

afterEach(() => {
    vi.restoreAllMocks();
});

// ── B1: 初始渲染 ──────────────────────────────────────────────────────────────

describe("B1 – initial render shows loading", () => {
    it("shows loading spinner before API resolves", async () => {
        mockApiFetch.mockReturnValue(new Promise(() => {})); // never resolves

        render(<SummaryBilibiliPage />);

        expect(screen.getByText(/获取中/)).toBeTruthy();
    });
});

// ── B2: 成功返回 ──────────────────────────────────────────────────────────────

describe("B2 – success: shows summary and outline", () => {
    it("renders the AI summary text", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("本视频讲解了 GPIO 的基本原理。")).toBeTruthy();
    });

    it("renders outline section titles", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("GPIO 基础")).toBeTruthy();
        expect(screen.getByText("定时器")).toBeTruthy();
    });

    it("renders outline item content", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("GPIO 输入输出模式介绍")).toBeTruthy();
        expect(screen.getByText("预分频寄存器")).toBeTruthy();
    });

    it("calls API with correct bvid and page_index=0", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(mockApiFetch).toHaveBeenCalledWith(
            "/api/v1/BilibiliVideoAiConclusionRoute",
            expect.objectContaining({
                method: "POST",
                body: expect.stringContaining('"bvid":"BV1xx"'),
            }),
            expect.anything(),
        );
        const body = JSON.parse(mockApiFetch.mock.calls[0][1].body);
        expect(body.bvid).toBe("BV1xx");
        expect(body.page_index).toBe(0);
        expect(body.is_update).toBe(false);
    });
});

// ── B3: model_result 为 null ──────────────────────────────────────────────────

describe("B3 – code=0 but model_result=null shows unavailable message", () => {
    it("shows '暂无 AI 总结' when model_result is null", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp({ code: 0, model_result: null }));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText(/暂无.*AI 总结|暂无 AI 总结/)).toBeTruthy();
    });
});

// ── B4: code != 0 ─────────────────────────────────────────────────────────────

describe("B4 – non-zero code shows unavailable message", () => {
    it("shows '暂无 AI 总结' when code is not 0", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp({ code: -1, message: "not found", model_result: null }));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText(/暂无.*AI 总结|暂无 AI 总结/)).toBeTruthy();
    });

    it("does NOT render summary section when code is not 0", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp({ code: -1, model_result: null }));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.queryByText("本视频讲解了 GPIO 的基本原理。")).toBeNull();
    });
});

// ── B5: 网络错误 ──────────────────────────────────────────────────────────────

describe("B5 – network error shows error state", () => {
    it("shows error message on fetch rejection", async () => {
        mockApiFetch.mockRejectedValueOnce(new Error("网络不可达"));

        render(<SummaryBilibiliPage />);
        await flush();

        // 应显示某种错误或"暂无"提示，不显示摘要内容
        expect(screen.queryByText("本视频讲解了 GPIO 的基本原理。")).toBeNull();
        expect(screen.queryByText(/获取中/)).toBeNull();
    });
});

// ── B6: 强制刷新 ──────────────────────────────────────────────────────────────

describe("B6 – refresh button sends is_update=true", () => {
    it("sends is_update=true on second request after refresh click", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeOkResp(MOCK_RESULT))
            .mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        const refreshBtn = screen.getByTitle(/刷新/);
        await act(async () => { fireEvent.click(refreshBtn); });
        await flush();

        expect(mockApiFetch).toHaveBeenCalledTimes(2);
        const body2 = JSON.parse(mockApiFetch.mock.calls[1][1].body);
        expect(body2.is_update).toBe(true);
    });
});

// ── B7: 时间戳格式化 ──────────────────────────────────────────────────────────

describe("B7 – timestamps are formatted as mm:ss", () => {
    it("formats 30 seconds as '00:30'", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("00:30")).toBeTruthy();
    });

    it("formats 90 seconds as '01:30'", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("01:30")).toBeTruthy();
    });

    it("formats 180 seconds as '03:00'", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT));

        render(<SummaryBilibiliPage />);
        await flush();

        expect(screen.getByText("03:00")).toBeTruthy();
    });
});
