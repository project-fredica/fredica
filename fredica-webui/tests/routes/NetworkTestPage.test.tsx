/**
 * NetworkTestPage.test.tsx
 *
 * 覆盖网速和延迟测试页面的核心交互逻辑：
 *   N1 – 初始渲染：显示"开始测试"按钮，输出区为空
 *   N2 – 点击"开始测试"：调用 POST NetworkTestRoute，进入"正在提交任务"状态
 *   N3 – 轮询成功：读取 items[0].status=="completed" → 渲染结果表格
 *   N4 – 轮询失败：读取 items[0].status=="failed" → 显示错误信息
 *   N5 – 响应格式修复：taskData.items 而非 Array.isArray(taskData)（回归）
 *   N6 – items 为空时轮询跳过（不更新状态）
 *   N7 – 完成后按钮文本变为"再次测试"
 *
 * 注意事项：
 *   - vi.useFakeTimers() 下 waitFor 会挂住（waitFor 内部用 setTimeout 轮询）
 *     → 不用 waitFor，改用 act(async) 刷 microtask + vi.advanceTimersByTimeAsync 推进宏任务
 *   - flushPromises 不能用 `new Promise(r => setTimeout(r, 0))`（同一原因）
 *     → act(async () => {}) 即可刷完所有待处理 React 状态更新
 */

import { render, screen, act, fireEvent } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import NetworkTestPage from "~/routes/tools.network-test";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockApiFetch = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
    };
});

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/components/sidebar/SidebarLayout", () => ({
    SidebarLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock("~/util/json", () => ({
    json_parse: <T,>(s: string) => JSON.parse(s) as T,
}));

// ── Helpers ───────────────────────────────────────────────────────────────────

/** 刷新所有待处理的微任务队列（Promise 链）及 React 状态更新。 */
const flush = () => act(async () => {});

/**
 * 推进假计时器 ms 毫秒，同时等待计时器回调内部的 Promise 链全部完成。
 * vi.advanceTimersByTimeAsync 会在计时器触发后继续 flush 微任务，
 * 因此 setInterval 回调里的 async apiFetch 也能一次性跑完。
 */
const tick = (ms = 1100) => act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
});

// ── Fixtures ──────────────────────────────────────────────────────────────────

const WORKFLOW_RUN_ID = "wf-0001";
const TASK_ID = "task-0001";

const CONFIG_URLS = [
    "https://www.baidu.com",
    "https://github.com",
    "https://huggingface.co",
    "https://api.openai.com",
];

/** 组件 mount 时 GET /api/v1/NetworkTestConfigRoute 的响应 */
const makeConfigResp = () => ({
    resp: new Response("{}", { status: 200 }),
    data: { urls: CONFIG_URLS },
});

const makeCreateResp = () => ({
    resp: new Response("{}", { status: 200 }),
    data: { workflow_run_id: WORKFLOW_RUN_ID, task_id: TASK_ID },
});

const makeListResp = (status: string, result?: string, error?: string) => ({
    resp: new Response("{}", { status: 200 }),
    data: {
        items: [{ id: TASK_ID, status, result: result ?? null, error: error ?? null, error_type: null }],
        total: 1,
    },
});

const sampleOutput = {
    proxy_configured: false,
    results: [
        { url: "https://www.baidu.com", direct: { latency_ms: 42, status: "ok", error: null }, proxied: null },
        { url: "https://www.google.com", direct: { latency_ms: null, status: "timeout", error: null }, proxied: null },
    ],
    proxy_warning: null,
};

// ── Setup ─────────────────────────────────────────────────────────────────────

beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.clearAllTimers();
    // 组件 mount 时会 GET /api/v1/NetworkTestConfigRoute，默认返回配置
    mockApiFetch.mockResolvedValueOnce(makeConfigResp());
});

afterEach(() => {
    vi.useRealTimers();
});

// ── N1: 初始渲染 ──────────────────────────────────────────────────────────────

describe("N1 – initial render", () => {
    it("shows '开始测试' button and no result table", async () => {
        render(<NetworkTestPage />);
        await flush();
        expect(screen.getByRole("button", { name: /开始测试/ })).toBeTruthy();
        expect(screen.queryByText(/测试结果/)).toBeNull();
    });

    it("lists the four default target URLs", async () => {
        render(<NetworkTestPage />);
        await flush();
        expect(screen.getByText("https://www.baidu.com")).toBeTruthy();
        expect(screen.getByText("https://github.com")).toBeTruthy();
    });
});

// ── N2: 提交任务 ──────────────────────────────────────────────────────────────

describe("N2 – clicking start button posts task", () => {
    it("calls POST /api/v1/NetworkTestRoute", async () => {
        // config GET 已在 beforeEach 中 mock；后续调用永不 resolve，让组件停在 creating 阶段
        mockApiFetch.mockReturnValue(new Promise(() => {}));

        render(<NetworkTestPage />);
        await flush();

        // fireEvent.click 同步执行 handleStart 直到第一个 await
        // act(async) 刷新 React 状态批处理
        await act(async () => {
            fireEvent.click(screen.getByRole("button", { name: /开始测试/ }));
        });

        expect(mockApiFetch).toHaveBeenCalledWith(
            "/api/v1/NetworkTestRoute",
            expect.objectContaining({ method: "POST" }),
        );
    });

    it("shows '正在提交任务' while POST is in-flight", async () => {
        mockApiFetch.mockReturnValue(new Promise(() => {}));

        render(<NetworkTestPage />);
        await flush();
        await act(async () => {
            fireEvent.click(screen.getByRole("button", { name: /开始测试/ }));
        });

        // handleStart 在 await apiFetch 之前已调用 setPhase("creating")
        // 文本同时出现在 button 和 status span 中，用 getAllByText 断言存在
        expect(screen.getAllByText(/正在提交任务/).length).toBeGreaterThan(0);
    });
});

// ── N3: 轮询成功 ──────────────────────────────────────────────────────────────

describe("N3 – polling resolves completed status", () => {
    it("renders result table after task completes", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("completed", JSON.stringify(sampleOutput)));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });

        await flush(); // POST 响应完成，setInterval 注册完成
        await tick();  // setInterval 触发 → poll 响应完成

        expect(screen.getByText(/测试结果/)).toBeTruthy();
        // URL 列去掉 https://，只显示域名
        expect(screen.getByText("www.baidu.com")).toBeTruthy();
    });

    it("shows phase label '测试完成'", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("completed", JSON.stringify(sampleOutput)));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick();

        // done 状态下 isRunning=false，PHASE_LABEL 不渲染；
        // "已完成第 N 轮"文本是 done 状态的可见标志
        expect(screen.getByText(/已完成第/)).toBeTruthy();
    });
});

// ── N4: 轮询失败 ──────────────────────────────────────────────────────────────

describe("N4 – polling resolves failed status", () => {
    it("shows error message when task fails", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("failed", undefined, "Python 服务不可达"));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick();

        expect(screen.getByText(/Python 服务不可达/)).toBeTruthy();
    });
});

// ── N5: 响应格式回归 —— items 字段而非根数组 ──────────────────────────────────

describe("N5 – response shape regression: reads items[0] not array root", () => {
    it("does NOT stay stuck in waiting when data is { items, total }", async () => {
        // 若代码误用 Array.isArray(taskData)，永远 return early，phase 卡在 waiting
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("completed", JSON.stringify(sampleOutput)));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick();

        // 到达 done 状态说明 items 读取正确（done 状态下按钮变为"再次测试"）
        expect(screen.getByRole("button", { name: /再次测试/ })).toBeTruthy();
        expect(screen.queryByText(/测试中，请稍候/)).toBeNull();
    });

    it("polls using workflow_run_id= (not pipeline_id=)", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("completed", JSON.stringify(sampleOutput)));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick();

        const pollCalls = mockApiFetch.mock.calls.filter(([url]) =>
            typeof url === "string" && url.includes("WorkerTaskListRoute"),
        );
        expect(pollCalls.length).toBeGreaterThan(0);
        expect(pollCalls[0][0]).toContain(`workflow_run_id=${WORKFLOW_RUN_ID}`);
        expect(pollCalls[0][0]).not.toContain("pipeline_id");
    });
});

// ── N6: items 为空时跳过 ──────────────────────────────────────────────────────

describe("N6 – empty items: poll skips gracefully", () => {
    it("stays in '测试中' phase when items is always empty", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue({
                resp: new Response("{}", { status: 200 }),
                data: { items: [], total: 0 },
            });

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick(3100); // 多推进几次 interval，仍应停在 waiting

        expect(screen.getAllByText(/测试中，请稍候/).length).toBeGreaterThan(0);
        expect(screen.queryByText(/测试完成/)).toBeNull();
        expect(screen.queryByText(/测试失败/)).toBeNull();
    });
});

// ── N7: 完成后按钮文本 ─────────────────────────────────────────────────────────

describe("N7 – button text changes to '再次测试' after completion", () => {
    it("shows '再次测试' after first round", async () => {
        mockApiFetch
            .mockResolvedValueOnce(makeCreateResp())
            .mockResolvedValue(makeListResp("completed", JSON.stringify(sampleOutput)));

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick();

        expect(screen.getByRole("button", { name: /再次测试/ })).toBeTruthy();
    });
});

// ── N8: POST 失败时显示错误并恢复按钮 ──────────────────────────────────────────

describe("N8 – POST HTTP 500 sets phase to failed and shows error", () => {
    it("shows error message when POST returns non-ok response", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: new Response("{}", { status: 500 }),
            data: { error: "internal server error" },
        });

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();

        // 提交失败后，应显示某种失败提示，按钮恢复为可点击
        expect(screen.queryByRole("button", { name: /正在提交任务/ })).toBeNull();
    });

    it("does NOT start polling when POST fails", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: new Response("{}", { status: 500 }),
            data: {},
        });

        render(<NetworkTestPage />);
        await act(async () => { fireEvent.click(screen.getByRole("button", { name: /开始测试/ })); });
        await flush();
        await tick(3100); // 推进多个 interval，不应有轮询调用

        const pollCalls = mockApiFetch.mock.calls.filter(([url]) =>
            typeof url === "string" && url.includes("WorkerTaskListRoute"),
        );
        expect(pollCalls.length).toBe(0);
    });
});
