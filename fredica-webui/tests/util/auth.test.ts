import { describe, expect, it } from "vitest";
import { meetsMinRole, isLoggedIn, isSessionUser } from "~/util/auth";

describe("meetsMinRole", () => {
    it("guest meets guest", () => expect(meetsMinRole("guest", "guest")).toBe(true));
    it("tenant meets guest", () => expect(meetsMinRole("tenant", "guest")).toBe(true));
    it("tenant meets tenant", () => expect(meetsMinRole("tenant", "tenant")).toBe(true));
    it("root meets all", () => {
        expect(meetsMinRole("root", "guest")).toBe(true);
        expect(meetsMinRole("root", "tenant")).toBe(true);
        expect(meetsMinRole("root", "root")).toBe(true);
    });
    it("guest does not meet tenant", () => expect(meetsMinRole("guest", "tenant")).toBe(false));
    it("guest does not meet root", () => expect(meetsMinRole("guest", "root")).toBe(false));
    it("tenant does not meet root", () => expect(meetsMinRole("tenant", "root")).toBe(false));
    it("null treated as guest", () => {
        expect(meetsMinRole(null, "guest")).toBe(true);
        expect(meetsMinRole(null, "tenant")).toBe(false);
    });
    it("undefined treated as guest", () => {
        expect(meetsMinRole(undefined, "guest")).toBe(true);
        expect(meetsMinRole(undefined, "tenant")).toBe(false);
    });
});

describe("isLoggedIn", () => {
    it("true when session_token present", () =>
        expect(isLoggedIn({ session_token: "tok", webserver_auth_token: null })).toBe(true));
    it("true when webserver_auth_token present", () =>
        expect(isLoggedIn({ session_token: null, webserver_auth_token: "tok" })).toBe(true));
    it("true when both present", () =>
        expect(isLoggedIn({ session_token: "s", webserver_auth_token: "w" })).toBe(true));
    it("false when both null", () =>
        expect(isLoggedIn({ session_token: null, webserver_auth_token: null })).toBe(false));
});

describe("isSessionUser", () => {
    it("true when session_token present", () =>
        expect(isSessionUser({ session_token: "tok" })).toBe(true));
    it("false when session_token null", () =>
        expect(isSessionUser({ session_token: null })).toBe(false));
});
