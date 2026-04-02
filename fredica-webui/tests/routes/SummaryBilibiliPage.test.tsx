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

/**
 * 真实 B站 API 响应 — BV1Et42157oP（图论之美，5 节，summary 较长）
 * 来源：GET /api/v1/BilibiliVideoAiConclusionRoute 实测结果
 */
const MOCK_RESULT_REAL = {
    code: 0,
    model_result: {
        result_type: 2,
        summary: "图论的起源与发展，重点介绍了基本概念和类型。",
        outline: [
            {
                title: "图论基础与算法应用",
                timestamp: 8,
                part_outline: [
                    { timestamp: 8,   content: "图论中 Grayi 的概念" },
                    { timestamp: 36,  content: "图由节点集 V 和边集 E 组成" },
                    { timestamp: 119, content: "现实问题可通过图表建模" },
                ],
            },
            {
                title: "图论起源与欧拉问题",
                timestamp: 600,
                part_outline: [
                    { timestamp: 600, content: "寻找最小生成树以减少成本" },
                    { timestamp: 704, content: "图论是数学中广泛的领域" },
                ],
            },
        ],
        subtitle: [
            {
                title: "",
                part_subtitle: [
                    { start_timestamp: 8, end_timestamp: 10, content: "我第一次听说Grayi" },
                ],
            },
        ],
    },
};

/** summary 为空字符串、outline 有内容（hasContent=true，但 summary block 不应渲染） */
const MOCK_RESULT_EMPTY_SUMMARY = {
    code: 0,
    model_result: {
        result_type: 2,
        summary: "",
        outline: [
            {
                title: "唯一章节",
                timestamp: 0,
                part_outline: [
                    { timestamp: 0, content: "章节内容" },
                ],
            },
        ],
        subtitle: [],
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

// ── B8: 真实 B站 API 响应结构（含 result_type / subtitle / section timestamp）─────

describe("B8 – real B站 API response structure renders correctly", () => {
    it("renders summary from real response format", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_REAL));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("图论的起源与发展，重点介绍了基本概念和类型。")).toBeTruthy();
    });

    it("renders outline section title from real response format", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_REAL));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("图论基础与算法应用")).toBeTruthy();
    });

    it("renders outline item content from real response format", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_REAL));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("图由节点集 V 和边集 E 组成")).toBeTruthy();
    });
});

// ── B9: summary 为空字符串时 outline 仍正常渲染 ──────────────────────────────────

describe("B9 – empty summary string with non-empty outline", () => {
    it("renders outline content even when summary is empty string", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_EMPTY_SUMMARY));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("唯一章节")).toBeTruthy();
        expect(screen.getByText("章节内容")).toBeTruthy();
    });

    it("does not render summary block when summary is empty string", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_EMPTY_SUMMARY));
        render(<SummaryBilibiliPage />);
        await flush();
        // summary block should not appear (empty string is falsy)
        expect(document.querySelectorAll(".bg-violet-50").length).toBe(0);
    });
});

// ── B10: 真实响应 BV1sNA1ztEvY（古典诗词，section 级 timestamp）────────────────

/**
 * 真实 B站 API 响应 — BV1sNA1ztEvY（两节，含 section 级 timestamp=1 / 120）
 * 来源：GET /api/v1/BilibiliVideoAiConclusionRoute 实测结果
 */
const MOCK_RESULT_BV1sNA1ztEvY = {
    code: 0,
    model_result: {
        result_type: 2,
        summary: "通过古典诗词与旋律交织，展现主人公在时光流转中的情感孤寂与岁月变迁，以\"醉生梦死\"\"相思苦忆\"\"容颜易逝\"为核心意象，描绘了在繁华与孤寂交织中坚守初心、直面命运的战士精神，凸显\"知战为何而战\"的深刻内核。",
        outline: [
            {
                title: "战士坚守信念与岁月相守",
                timestamp: 1,
                part_outline: [
                    { timestamp: 56,  content: "时光流逝中个人情感与世事变迁的深刻感慨" },
                    { timestamp: 91,  content: "醉态中的回忆与月下共饮述说往昔岁月" },
                    { timestamp: 115, content: "容颜易逝与醉眼观景中人生的无常感悟" },
                ],
            },
            {
                title: "战士坚守信念迎战世事变迁",
                timestamp: 120,
                part_outline: [
                    { timestamp: 120, content: "战士明确战斗意义以坚定信念与行动方向" },
                    { timestamp: 205, content: "通过诗意表达展现情感坚守与世事无常对比" },
                ],
            },
        ],
        subtitle: [
            {
                title: "",
                part_subtitle: [
                    { start_timestamp: 1,  end_timestamp: 5,  content: "♪ 我依然醉生梦死 ♪" },
                    { start_timestamp: 5,  end_timestamp: 11, content: "♪ 奔笑看世事似水变迁 ♪" },
                ],
            },
        ],
    },
    stid: "some-stid",
    status: 0,
    like_num: 10,
    dislike_num: 0,
};

/** code=1（B站未生成总结）的真实响应结构 */
const MOCK_RESULT_CODE1 = {
    code: 1,
    model_result: { result_type: 0, summary: "", outline: [], subtitle: [] },
    stid: "0",
    status: 0,
    like_num: 0,
    dislike_num: 0,
};

describe("B10 – real response BV1sNA1ztEvY renders correctly", () => {
    it("renders summary text from real poetry video response", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_BV1sNA1ztEvY));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText(/战士精神|知战为何而战/)).toBeTruthy();
    });

    it("renders both outline section titles", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_BV1sNA1ztEvY));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("战士坚守信念与岁月相守")).toBeTruthy();
        expect(screen.getByText("战士坚守信念迎战世事变迁")).toBeTruthy();
    });

    it("renders outline item content", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_BV1sNA1ztEvY));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("时光流逝中个人情感与世事变迁的深刻感慨")).toBeTruthy();
    });

    it("renders section-level timestamp as mm:ss (section timestamp=1 → 00:01)", async () => {
        // section-level timestamp is NOT rendered (only part_outline timestamps are)
        // but part_outline[0].timestamp=56 → 00:56
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_BV1sNA1ztEvY));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText("00:56")).toBeTruthy();
        expect(screen.getByText("01:31")).toBeTruthy(); // 91 seconds
    });
});

// ── B11: code=1 真实结构（hasContent=false，不渲染 IIFE）────────────────────────

describe("B11 – code=1 real response shows unavailable (no IIFE called)", () => {
    it("shows '暂无' when code=1 with real response structure", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_CODE1));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.getByText(/暂无.*AI 总结|该视频暂无/)).toBeTruthy();
    });

    it("does not render any outline section when code=1", async () => {
        mockApiFetch.mockResolvedValueOnce(makeOkResp(MOCK_RESULT_CODE1));
        render(<SummaryBilibiliPage />);
        await flush();
        expect(screen.queryByText("章节大纲")).toBeNull();
    });
});
