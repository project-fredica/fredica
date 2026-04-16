import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter } from "react-router";

// ── Mocks ─────────────────────────────────────────────────────────────────────

vi.mock("~/context/appConfig", () => ({ useAppConfig: vi.fn() }));
import { useAppConfig } from "~/context/appConfig";
const mockUseAppConfig = vi.mocked(useAppConfig);

vi.mock("~/util/app_fetch", () => ({ useAppFetch: vi.fn() }));
import { useAppFetch } from "~/util/app_fetch";
const mockUseAppFetch = vi.mocked(useAppFetch);

vi.mock("~/util/error_handler", () => ({
    reportHttpError: vi.fn(),
    print_error: vi.fn(),
}));

function makeConfig(overrides: Record<string, unknown> = {}) {
    return {
        webserver_domain: "localhost", webserver_port: "7631",
        webserver_schema: "http" as const, webserver_auth_token: null,
        session_token: "root-tok", user_role: "root" as const,
        user_display_name: "Root", user_permissions: null,
        ...overrides,
    };
}

const mockApiFetch = vi.fn();

const USERS = [
    { id: "1", username: "root", display_name: "Root", role: "root", permissions: "", status: "active", created_at: "2024-01-01", last_login_at: null },
    { id: "2", username: "alice", display_name: "Alice", role: "tenant", permissions: "", status: "active", created_at: "2024-01-02", last_login_at: null },
    { id: "3", username: "bob", display_name: "Bob", role: "tenant", permissions: "", status: "disabled", created_at: "2024-01-03", last_login_at: null },
];

beforeEach(() => {
    vi.clearAllMocks();
    mockUseAppConfig.mockReturnValue({
        appConfig: makeConfig(),
        setAppConfig: vi.fn(),
        isStorageLoaded: true,
    });
    mockApiFetch.mockResolvedValue({
        resp: { ok: true } as Response,
        data: USERS,
    });
    mockUseAppFetch.mockReturnValue({
        apiFetch: mockApiFetch,
    } as any);
});

import AdminUsersPage from "~/routes/admin.users";

function renderPage() {
    return render(<MemoryRouter><AdminUsersPage /></MemoryRouter>);
}

describe("AdminUsersPage", () => {
    it("renders user list after load", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        expect(screen.getByText("root")).toBeTruthy();
        expect(screen.getByText("bob")).toBeTruthy();
    });

    it("shows 403 for non-root user", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ user_role: "tenant", session_token: "tok" }),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        renderPage();
        expect(screen.getByText("403")).toBeTruthy();
    });

    it("shows active/disabled status badges", async () => {
        renderPage();
        await waitFor(() => expect(screen.getAllByText("启用").length).toBeGreaterThan(0));
        // "禁用" appears as both a status badge (bob) and an action button (alice) — use getAllByText
        expect(screen.getAllByText("禁用").length).toBeGreaterThanOrEqual(1);
    });

    it("root user has no action button", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        // root row should have no disable/enable button — count buttons in action column
        // alice (active) → 禁用 button; bob (disabled) → 启用 button; root → nothing
        const disableBtns = screen.getAllByRole("button", { name: /禁用/ });
        expect(disableBtns.length).toBe(1); // only alice
    });

    it("opens create dialog on button click", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /创建用户/ }));
        expect(screen.getByPlaceholderText("admin")).toBeTruthy();
        expect(screen.getByPlaceholderText("至少 8 位")).toBeTruthy();
    });

    it("create dialog submit button disabled when username invalid", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /创建用户/ }));
        await userEvent.type(screen.getByPlaceholderText("admin"), "1bad");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass1!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "StrongPass1!");
        const btn = screen.getByRole("button", { name: "创建" }) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it("create dialog submit button disabled when passwords don't match", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /创建用户/ }));
        await userEvent.type(screen.getByPlaceholderText("admin"), "newuser");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass1!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "DifferentPass1!");
        const btn = screen.getByRole("button", { name: "创建" }) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it("create user success closes dialog and refreshes", async () => {
        mockApiFetch
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: USERS })
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: { user_id: "4", username: "newuser", display_name: "newuser" } })
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: USERS });

        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /创建用户/ }));
        await userEvent.type(screen.getByPlaceholderText("admin"), "newuser");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass1!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "StrongPass1!");
        await userEvent.click(screen.getByRole("button", { name: "创建" }));
        await waitFor(() => expect(screen.queryByPlaceholderText("admin")).toBeNull());
    });

    it("create user shows error on failure", async () => {
        mockApiFetch
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: USERS })
            .mockResolvedValueOnce({ resp: { ok: false, status: 400 } as Response, data: { error: "用户名已存在" } });

        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /创建用户/ }));
        await userEvent.type(screen.getByPlaceholderText("admin"), "alice");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass1!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "StrongPass1!");
        await userEvent.click(screen.getByRole("button", { name: "创建" }));
        await waitFor(() => expect(screen.getByText("用户名已存在")).toBeTruthy());
    });

    it("opens disable confirm dialog", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getAllByRole("button", { name: /禁用/ })[0]);
        expect(screen.getByText("禁用用户")).toBeTruthy();
        expect(screen.getByText(/禁用后该用户的所有登录会话将立即失效/)).toBeTruthy();
    });

    it("opens enable confirm dialog for disabled user", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("bob")).toBeTruthy());
        await userEvent.click(screen.getByRole("button", { name: /启用/ }));
        expect(screen.getByText("启用用户")).toBeTruthy();
    });

    it("confirm disable calls API and refreshes", async () => {
        mockApiFetch
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: USERS })
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: { success: true } })
            .mockResolvedValueOnce({ resp: { ok: true } as Response, data: USERS });

        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        await userEvent.click(screen.getAllByRole("button", { name: /禁用/ })[0]);
        // click confirm button in dialog
        const confirmBtns = screen.getAllByRole("button", { name: "禁用" });
        // last one is the confirm button in the dialog
        await userEvent.click(confirmBtns[confirmBtns.length - 1]);
        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/UserDisableRoute",
                expect.objectContaining({ method: "POST" }),
            );
        });
    });

    // ── Back button ──────────────────────────────────────────────────────────

    it("renders back button with ArrowLeft icon", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        const backBtn = screen.getByTitle("返回主页");
        expect(backBtn).toBeTruthy();
        expect(backBtn.tagName).toBe("BUTTON");
    });

    // ── Invite button ────────────────────────────────────────────────────────

    it("shows invite button for active users", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        // root (active) + alice (active) = 2 invite buttons; bob (disabled) = 0
        const inviteBtns = screen.getAllByRole("button", { name: /邀请/ });
        expect(inviteBtns.length).toBe(2);
    });

    it("does not show invite button for disabled users", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText("bob")).toBeTruthy());
        // bob is disabled — no invite button in his row
        const inviteBtns = screen.getAllByRole("button", { name: /邀请/ });
        // only root + alice (both active)
        expect(inviteBtns.length).toBe(2);
    });

    it("copies invite link to clipboard on click", async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        Object.assign(navigator, { clipboard: { writeText } });

        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        const inviteBtns = screen.getAllByRole("button", { name: /邀请/ });
        await userEvent.click(inviteBtns[0]);

        expect(writeText).toHaveBeenCalledTimes(1);
        const link = writeText.mock.calls[0][0] as string;
        expect(link).toContain("http://localhost:7630/login?");
        expect(link).toContain("webserver_schema=http");
        expect(link).toContain("webserver_domain=localhost");
        expect(link).toContain("webserver_port=7631");
    });

    it("shows '已复制' feedback after copying invite link", async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        Object.assign(navigator, { clipboard: { writeText } });

        renderPage();
        await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
        const inviteBtns = screen.getAllByRole("button", { name: /邀请/ });
        await userEvent.click(inviteBtns[0]);

        await waitFor(() => expect(screen.getByText("已复制")).toBeTruthy());
    });
});
