export type AuthRole = "guest" | "tenant" | "root";

export const ROLE_LEVEL: Record<AuthRole, number> = { guest: 0, tenant: 1, root: 2 };

export function meetsMinRole(
    current: AuthRole | null | undefined,
    required: AuthRole,
): boolean {
    return ROLE_LEVEL[current ?? "guest"] >= ROLE_LEVEL[required];
}

export function isLoggedIn(appConfig: {
    session_token: string | null;
    webserver_auth_token: string | null;
}): boolean {
    return !!(appConfig.session_token || appConfig.webserver_auth_token);
}

export function isSessionUser(appConfig: { session_token: string | null }): boolean {
    return !!appConfig.session_token;
}
