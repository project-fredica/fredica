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

const mockCallBridge = vi.fn();
vi.mock("~/util/bridge", () => ({
    callBridge: (...args: unknown[]) => mockCallBridge(...args),
    BridgeUnavailableError: class BridgeUnavailableError extends Error {},
}));

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
    // Default: not yet initialized
    global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ initialized: false }),
    } as Response);
});

import SetupPage from "~/routes/setup";

function renderSetup() {
    return render(<MemoryRouter><SetupPage /></MemoryRouter>);
}

describe("SetupPage", () => {
    it("renders setup form", () => {
        renderSetup();
        expect(screen.getByPlaceholderText("admin")).toBeTruthy();
        expect(screen.getByPlaceholderText("至少 8 位")).toBeTruthy();
        expect(screen.getByPlaceholderText("再次输入密码")).toBeTruthy();
    });

    it("navigates to /login when already initialized", async () => {
        global.fetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ initialized: true }),
        } as Response);
        renderSetup();
        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/login", { replace: true }));
    });

    it("submit button disabled when password too short", async () => {
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("admin"), "admin1");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "short");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "short");
        const btn = screen.getByRole("button", { name: "完成初始化" }) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it("submit button disabled when passwords don't match", async () => {
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("admin"), "admin1");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass123!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "DifferentPass123!");
        const btn = screen.getByRole("button", { name: "完成初始化" }) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it("shows confirm error when passwords don't match", async () => {
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass123!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "WrongPass123!");
        expect(screen.getByText("两次密码不一致")).toBeTruthy();
    });

    it("shows username format error for invalid username", async () => {
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("admin"), "1invalid");
        expect(screen.getByText(/用户名须以字母开头/)).toBeTruthy();
    });

    it("shows error on failed init", async () => {
        mockCallBridge.mockResolvedValueOnce(JSON.stringify({ error: "用户名已存在" }));
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("admin"), "admin1");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass123!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "StrongPass123!");
        await userEvent.click(screen.getByRole("button", { name: "完成初始化" }));
        await waitFor(() => expect(screen.getByText("用户名已存在")).toBeTruthy());
    });

    it("saves session and navigates on successful init", async () => {
        mockCallBridge.mockResolvedValueOnce(JSON.stringify({
            token: "new-session-tok",
            user: { display_name: "admin1", role: "root", permissions: "" },
        }));
        renderSetup();
        await userEvent.type(screen.getByPlaceholderText("admin"), "admin1");
        await userEvent.type(screen.getByPlaceholderText("至少 8 位"), "StrongPass123!");
        await userEvent.type(screen.getByPlaceholderText("再次输入密码"), "StrongPass123!");
        await userEvent.click(screen.getByRole("button", { name: "完成初始化" }));
        await waitFor(() => {
            expect(mockSetAppConfig).toHaveBeenCalledWith(expect.objectContaining({ session_token: "new-session-tok" }));
            expect(mockNavigate).toHaveBeenCalledWith("/app-desktop-home", { replace: true });
        });
    });
});
