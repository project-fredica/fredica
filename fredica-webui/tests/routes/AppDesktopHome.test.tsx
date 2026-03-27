import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AppDesktopHome from "~/routes/app-desktop-home";

const navigateMock = vi.fn();
const mockApiFetch = vi.fn();
const callBridgeMock = vi.fn();
const printErrorMock = vi.fn();

vi.mock("react-router", async () => {
    const actual = await vi.importActual<typeof import("react-router")>("react-router");
    return {
        ...actual,
        useNavigate: () => navigateMock,
    };
});

vi.mock("~/util/app_fetch", async () => {
    const actual = await vi.importActual<typeof import("~/util/app_fetch")>("~/util/app_fetch");
    return {
        ...actual,
        useAppFetch: () => ({ apiFetch: mockApiFetch }),
    };
});

vi.mock("~/util/bridge", async () => {
    const actual = await vi.importActual<typeof import("~/util/bridge")>("~/util/bridge");
    return {
        ...actual,
        callBridge: (...args: unknown[]) => callBridgeMock(...args),
    };
});

vi.mock("~/util/error_handler", () => ({
    print_error: (...args: unknown[]) => printErrorMock(...args),
}));

describe("AppDesktopHome", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        callBridgeMock.mockImplementation(async (method: string) => {
            if (method === "check_bilibili_credential") {
                return JSON.stringify({ configured: true, valid: true, message: "ok" });
            }
            if (method === "open_browser") {
                return "";
            }
            throw new Error(`unexpected bridge method: ${method}`);
        });
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 1,
                        has_any_available_model: true,
                        selected_model_id: null,
                        selected_model_available: false,
                    },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });
    });

    it("shows bilibili expired warning when bridge check returns invalid", async () => {
        callBridgeMock.mockImplementation(async (method: string) => {
            if (method === "check_bilibili_credential") {
                return JSON.stringify({ configured: true, valid: false, message: "ok" });
            }
            if (method === "open_browser") return "";
            throw new Error(`unexpected bridge method: ${method}`);
        });

        render(<AppDesktopHome params={{}} matches={[] as never} loaderData={undefined} /> as never);

        await waitFor(() => {
            expect(screen.getByText(/设置的 B 站账号已失效/)).toBeTruthy();
        });
    });

    it("shows model warning when no available model exists", async () => {
        mockApiFetch.mockImplementation(async (path: string) => {
            if (path.startsWith("/api/v1/LlmModelAvailabilityRoute")) {
                return {
                    resp: new Response("{}", { status: 200 }),
                    data: {
                        available_count: 0,
                        has_any_available_model: false,
                        selected_model_id: null,
                        selected_model_available: false,
                    },
                };
            }
            throw new Error(`unexpected path: ${path}`);
        });

        render(<AppDesktopHome params={{}} matches={[] as never} loaderData={undefined} /> as never);

        await waitFor(() => {
            expect(screen.getByText(/当前未检测到可用模型/)).toBeTruthy();
        });
    });

    it("does not show model warning when model is available", async () => {
        render(<AppDesktopHome params={{}} matches={[] as never} loaderData={undefined} /> as never);

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledWith(
                "/api/v1/LlmModelAvailabilityRoute",
                { method: "GET" },
                { silent: true },
            );
        });
        expect(screen.queryByText(/当前未检测到可用模型/)).toBeNull();
    });

    it("keeps primary actions usable when model check fails", async () => {
        mockApiFetch.mockRejectedValueOnce(new Error("network down"));
        const user = userEvent.setup();

        render(<AppDesktopHome params={{}} matches={[] as never} loaderData={undefined} /> as never);

        await waitFor(() => {
            expect(screen.getByRole("button", { name: "在浏览器中打开" })).toBeTruthy();
        });
        expect(screen.queryByText(/当前未检测到可用模型/)).toBeNull();

        await user.click(screen.getByRole("button", { name: "打开桌面服务器设置" }));
        expect(navigateMock).toHaveBeenCalledWith("/app-desktop-setting");
        expect(printErrorMock).not.toHaveBeenCalled();
    });
});
