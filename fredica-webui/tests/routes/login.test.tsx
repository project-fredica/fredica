import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter } from "react-router";

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn();
vi.mock("react-router", async (importOriginal) => {
    const actual = await importOriginal<typeof import("react-router")>();
    return { ...actual, useNavigate: () => mockNavigate };
});

vi.mock("~/context/appConfig", () => ({ useAppConfig: vi.fn() }));
import { useAppConfig } from "~/context/appConfig";
const mockUseAppConfig = vi.mocked(useAppConfig);

function makeConfig(overrides: Record<string, unknown> = {}) {
    return {
        webserver_domain: "localhost", webserver_port: "7631",
        webserver_schema: "http" as const, webserver_auth_token: null,
        session_token: null, user_role: null, user_display_name: null, user_permissions: null,
        ...overrides,
    };
}

const mockSetAppConfig = vi.fn();

beforeEach(() => {
    vi.clearAllMocks();
    mockUseAppConfig.mockReturnValue({
        appConfig: makeConfig(),
        setAppConfig: mockSetAppConfig,
        isStorageLoaded: true,
    });
    // Default fetch: server initialized
    global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ initialized: true }),
    } as Response);
});

// ── Import component after mocks ──────────────────────────────────────────────
import LoginPage from "~/routes/login";

function renderLogin() {
    return render(<MemoryRouter><LoginPage /></MemoryRouter>);
}

describe("LoginPage", () => {
    it("renders login form", () => {
        renderLogin();
        expect(screen.getByPlaceholderText("用户名")).toBeTruthy();
        expect(screen.getByPlaceholderText("密码")).toBeTruthy();
    });

    it("navigates to /setup when instance not initialized", async () => {
        global.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ initialized: false }),
        } as Response);
        renderLogin();
        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/setup", { replace: true }));
    });

    it("navigates to / when existing session is valid", async () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ session_token: "existing-token" }),
            setAppConfig: mockSetAppConfig,
            isStorageLoaded: true,
        });
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ ok: true, json: async () => ({ initialized: true }) } as Response)
            .mockResolvedValueOnce({ ok: true } as Response);
        renderLogin();
        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/", { replace: true }));
    });

    it("shows error on failed password login", async () => {
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ ok: true, json: async () => ({ initialized: true }) } as Response)
            .mockResolvedValueOnce({ ok: false, json: async () => ({ error: "用户名或密码错误" }) } as Response);
        renderLogin();
        await userEvent.type(screen.getByPlaceholderText("用户名"), "admin");
        await userEvent.type(screen.getByPlaceholderText("密码"), "wrongpass");
        await userEvent.click(screen.getByRole("button", { name: "登录" }));
        await waitFor(() => expect(screen.getByText("用户名或密码错误")).toBeTruthy());
    });

    it("saves session and navigates on successful password login", async () => {
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ ok: true, json: async () => ({ initialized: true }) } as Response)
            .mockResolvedValueOnce({
                ok: true,
                json: async () => ({
                    token: "session-tok",
                    user: { display_name: "Admin", role: "root", permissions: "" },
                }),
            } as Response);
        renderLogin();
        await userEvent.type(screen.getByPlaceholderText("用户名"), "admin");
        await userEvent.type(screen.getByPlaceholderText("密码"), "correctpass");
        await userEvent.click(screen.getByRole("button", { name: "登录" }));
        await waitFor(() => {
            expect(mockSetAppConfig).toHaveBeenCalledWith(expect.objectContaining({ session_token: "session-tok" }));
            expect(mockNavigate).toHaveBeenCalledWith("/", { replace: true });
        });
    });

    it("shows error on invalid guest token", async () => {
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ ok: true, json: async () => ({ initialized: true }) } as Response)
            .mockResolvedValueOnce({ ok: false } as Response);
        renderLogin();
        await userEvent.type(screen.getByPlaceholderText("访问令牌"), "bad-token");
        await userEvent.click(screen.getByRole("button", { name: "以游客身份访问" }));
        await waitFor(() => expect(screen.getByText("令牌无效")).toBeTruthy());
    });

    it("saves guest token and navigates on valid guest login", async () => {
        global.fetch = vi.fn()
            .mockResolvedValueOnce({ ok: true, json: async () => ({ initialized: true }) } as Response)
            .mockResolvedValueOnce({ ok: true } as Response);
        renderLogin();
        await userEvent.type(screen.getByPlaceholderText("访问令牌"), "valid-guest-token");
        await userEvent.click(screen.getByRole("button", { name: "以游客身份访问" }));
        await waitFor(() => {
            expect(mockSetAppConfig).toHaveBeenCalledWith(expect.objectContaining({ webserver_auth_token: "valid-guest-token" }));
            expect(mockNavigate).toHaveBeenCalledWith("/", { replace: true });
        });
    });
});
