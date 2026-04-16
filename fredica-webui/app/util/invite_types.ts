// TypeScript types matching backend InviteModels.kt

export interface GuestInviteLink {
    id: string;
    path_id: string;
    label: string;
    status: string;
    created_by: string;
    created_at: string;
    updated_at: string;
    visit_count: number;
}

export interface GuestInviteVisit {
    id: string;
    link_id: string;
    ip_address: string;
    user_agent: string;
    visited_at: string;
}

export interface TenantInviteLink {
    id: string;
    path_id: string;
    label: string;
    status: string;
    max_uses: number;
    expires_at: string;
    created_by: string;
    created_at: string;
    updated_at: string;
    used_count: number;
}

export interface TenantInviteRegistration {
    id: string;
    link_id: string;
    user_id: string;
    ip_address: string;
    user_agent: string;
    registered_at: string;
    username?: string;
    display_name?: string;
}
