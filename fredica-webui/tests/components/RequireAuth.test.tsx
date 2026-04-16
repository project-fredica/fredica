import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { RequireAuth } from "~/components/auth/RequireAuth";

// Mock useAppConfig
vi.mock("~/context/appConfig", () => ({
    useAppConfig: vi.fn(),
}));

import { useAppConfig } from "~/context/appConfig";
const mockUseAppConfig = vi.mocked(useAppConfig);

function makeConfig(overrides: Record<string, unknown> = {}) {
    return {
        webserver_domain: "localhost",
        webserver_port: "7631",
        webserver_schema: "http" as const,
        webserver_auth_token: null,
        session_token: null,
        user_role: null,
        user_display_name: null,
        user_permissions: null,
        ...overrides,
    };
}

function renderWithRouter(ui: React.ReactElement) {
    return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe("RequireAuth", () => {
    it("shows loading when storage not loaded", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig(),
            setAppConfig: vi.fn(),
            isStorageLoaded: false,
        });
        renderWithRouter(<RequireAuth><div>content</div></RequireAuth>);
        expect(screen.getByText("Loading...")).toBeTruthy();
        expect(screen.queryByText("content")).toBeNull();
    });

    it("redirects to /login when not logged in", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig(),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        // Navigate renders nothing visible; just check content is absent
        renderWithRouter(<RequireAuth><div>content</div></RequireAuth>);
        expect(screen.queryByText("content")).toBeNull();
    });

    it("shows 403 when role insufficient", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ session_token: "tok", user_role: "guest" }),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        renderWithRouter(<RequireAuth minRole="tenant"><div>content</div></RequireAuth>);
        expect(screen.getByText("403")).toBeTruthy();
        expect(screen.queryByText("content")).toBeNull();
    });

    it("renders children when role sufficient", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ session_token: "tok", user_role: "tenant" }),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        renderWithRouter(<RequireAuth minRole="tenant"><div>content</div></RequireAuth>);
        expect(screen.getByText("content")).toBeTruthy();
    });

    it("renders children for guest with default minRole", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ webserver_auth_token: "guestok", user_role: "guest" }),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        renderWithRouter(<RequireAuth><div>content</div></RequireAuth>);
        expect(screen.getByText("content")).toBeTruthy();
    });

    it("renders children for root accessing root-only route", () => {
        mockUseAppConfig.mockReturnValue({
            appConfig: makeConfig({ session_token: "tok", user_role: "root" }),
            setAppConfig: vi.fn(),
            isStorageLoaded: true,
        });
        renderWithRouter(<RequireAuth minRole="root"><div>admin</div></RequireAuth>);
        expect(screen.getByText("admin")).toBeTruthy();
    });
});
