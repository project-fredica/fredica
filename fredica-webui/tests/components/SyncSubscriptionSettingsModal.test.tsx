import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { SyncSubscriptionSettingsModal } from "~/components/material-library/SyncSubscriptionSettingsModal";
import type { MaterialCategory } from "~/components/material-library/materialTypes";

const mockApiFetch = vi.fn();

vi.mock("~/util/app_fetch", () => ({
    useAppFetch: () => ({ apiFetch: mockApiFetch }),
}));

vi.mock("~/util/error_handler", () => ({
    reportHttpError: vi.fn(),
    print_error: vi.fn(),
}));

const baseSyncCategory: MaterialCategory = {
    id: "cat-1",
    owner_id: "owner-1",
    name: "B站收藏夹同步",
    description: "",
    allow_others_view: false,
    allow_others_add: false,
    allow_others_delete: false,
    material_count: 10,
    is_mine: true,
    sync: {
        id: "pi-1",
        sync_type: "bilibili_favorite",
        platform_config: { media_id: 12345 },
        display_name: "我的收藏夹",
        last_synced_at: 1700000000,
        item_count: 10,
        sync_state: "idle",
        last_error: null,
        fail_count: 0,
        subscriber_count: 1,
        my_subscription: {
            id: "uc-1",
            enabled: true,
            cron_expr: "0 */6 * * *",
            freshness_window_sec: 3600,
        },
        owner_id: "owner-1",
        last_workflow_run_id: null,
    },
    created_at: 1700000000,
    updated_at: 1700000000,
};

const onClose = vi.fn();
const onUpdated = vi.fn();

beforeEach(() => {
    mockApiFetch.mockReset();
    onClose.mockReset();
    onUpdated.mockReset();
});

describe("SyncSubscriptionSettingsModal", () => {
    // FE7: Settings view renders with current values
    it("renders settings form with current subscription values", () => {
        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        expect(screen.getByText("订阅设置")).toBeTruthy();

        expect(screen.getByText("每 6 小时")).toBeTruthy();

        const freshnessInput = screen.getByDisplayValue("3600") as HTMLInputElement;
        expect(freshnessInput).toBeTruthy();

        const checkbox = screen.getByRole("checkbox") as HTMLInputElement;
        expect(checkbox.checked).toBe(true);
    });

    // FE7: Save calls UserConfigUpdateRoute with correct payload
    it("saves settings via API with correct payload", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { success: true },
        });

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        // Open the CronExpressionInput dropdown and select "每 12 小时"
        fireEvent.click(screen.getByText("每 6 小时"));
        fireEvent.click(screen.getByText("每 12 小时"));

        fireEvent.click(screen.getByText("保存"));

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledOnce();
        });

        const [path, init] = mockApiFetch.mock.calls[0];
        expect(path).toBe("/api/v1/MaterialCategorySyncUserConfigUpdateRoute");
        const body = JSON.parse(init.body);
        expect(body.user_config_id).toBe("uc-1");
        expect(body.cron_expr).toBe("0 */12 * * *");
        expect(body.freshness_window_sec).toBe(3600);
        expect(body.enabled).toBe(true);
    });

    // FE7: Save success calls onUpdated and onClose
    it("calls onUpdated and onClose on successful save", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { success: true },
        });

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("保存"));

        await waitFor(() => {
            expect(onUpdated).toHaveBeenCalledOnce();
            expect(onClose).toHaveBeenCalledOnce();
        });
    });

    // FE7: Save shows error on API failure
    it("shows error on save failure", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { error: "更新失败" },
        });

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("保存"));

        await waitFor(() => {
            expect(screen.getByText("更新失败")).toBeTruthy();
        });
        expect(onClose).not.toHaveBeenCalled();
    });

    // FE7: Loading state during save
    it("shows loading state during save", async () => {
        let resolvePromise: (v: unknown) => void;
        mockApiFetch.mockReturnValueOnce(
            new Promise((resolve) => { resolvePromise = resolve; })
        );

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("保存"));

        await waitFor(() => {
            const saveBtn = screen.getByText("保存").closest("button") as HTMLButtonElement;
            expect(saveBtn.disabled).toBe(true);
        });

        resolvePromise!({ resp: { ok: true }, data: { success: true } });
    });

    // FE7: Toggle enabled checkbox
    it("toggles enabled state", () => {
        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        const checkbox = screen.getByRole("checkbox") as HTMLInputElement;
        expect(checkbox.checked).toBe(true);
        expect(screen.getByText("已启用")).toBeTruthy();

        fireEvent.click(checkbox);
        expect(checkbox.checked).toBe(false);
        expect(screen.getByText("已暂停")).toBeTruthy();
    });

    // FE7b: Unsubscribe flow
    it("shows unsubscribe confirmation and calls API", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { success: true },
        });

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={false}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("取消订阅"));

        const confirmHeading = screen.getAllByText("确认取消订阅");
        expect(confirmHeading.length).toBe(2);
        expect(screen.getByText(/确定要取消订阅/)).toBeTruthy();

        const confirmBtn = confirmHeading.find(el => el.tagName === "BUTTON") as HTMLElement;
        fireEvent.click(confirmBtn);

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledOnce();
        });

        const [path, init] = mockApiFetch.mock.calls[0];
        expect(path).toBe("/api/v1/MaterialCategorySyncUnsubscribeRoute");
        expect(JSON.parse(init.body)).toEqual({ user_config_id: "uc-1" });

        await waitFor(() => {
            expect(onUpdated).toHaveBeenCalledOnce();
            expect(onClose).toHaveBeenCalledOnce();
        });
    });

    // FE7b: Unsubscribe confirmation can go back
    it("returns to settings from unsubscribe confirmation", () => {
        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("取消订阅"));
        const confirmHeading = screen.getAllByText("确认取消订阅");
        expect(confirmHeading.length).toBe(2);

        fireEvent.click(screen.getByText("返回"));
        expect(screen.getByText("订阅设置")).toBeTruthy();
    });

    // FE7c: Delete data source (owner only) — button visible
    it("shows delete button only for owner", () => {
        const { unmount } = render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );
        expect(screen.getByText("删除数据源")).toBeTruthy();
        unmount();

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={false}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );
        expect(screen.queryByText("删除数据源")).toBeNull();
    });

    // FE7c: Delete data source flow
    it("shows delete confirmation and calls API", async () => {
        mockApiFetch.mockResolvedValueOnce({
            resp: { ok: true },
            data: { success: true },
        });

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("删除数据源"));

        expect(screen.getByText("确认删除数据源")).toBeTruthy();
        expect(screen.getByText(/此操作不可撤销/)).toBeTruthy();

        fireEvent.click(screen.getByText("确认删除"));

        await waitFor(() => {
            expect(mockApiFetch).toHaveBeenCalledOnce();
        });

        const [path, init] = mockApiFetch.mock.calls[0];
        expect(path).toBe("/api/v1/MaterialCategorySyncDeleteRoute");
        expect(JSON.parse(init.body)).toEqual({ id: "cat-1" });

        await waitFor(() => {
            expect(onUpdated).toHaveBeenCalledOnce();
            expect(onClose).toHaveBeenCalledOnce();
        });
    });

    // FE7c: Delete confirmation can go back
    it("returns to settings from delete confirmation", () => {
        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("删除数据源"));
        expect(screen.getByText("确认删除数据源")).toBeTruthy();

        fireEvent.click(screen.getByText("返回"));
        expect(screen.getByText("订阅设置")).toBeTruthy();
    });

    // No subscription: shows message, no save button
    it("shows no-subscription message when my_subscription is null", () => {
        const catNoSub: MaterialCategory = {
            ...baseSyncCategory,
            sync: {
                ...baseSyncCategory.sync!,
                my_subscription: null,
            },
        };

        render(
            <SyncSubscriptionSettingsModal
                category={catNoSub}
                isOwner={false}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        expect(screen.getByText("你尚未订阅此数据源。")).toBeTruthy();
        expect(screen.queryByText("保存")).toBeNull();
        expect(screen.queryByText("取消订阅")).toBeNull();
    });

    // Close button calls onClose
    it("close button calls onClose", () => {
        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        const closeButtons = screen.getAllByRole("button");
        const xButton = closeButtons.find(b => b.querySelector("svg"));
        if (xButton) fireEvent.click(xButton);

        expect(onClose).toHaveBeenCalled();
    });

    // Network error on save
    it("shows network error on save failure", async () => {
        mockApiFetch.mockRejectedValueOnce(new Error("Network error"));

        render(
            <SyncSubscriptionSettingsModal
                category={baseSyncCategory}
                isOwner={true}
                onClose={onClose}
                onUpdated={onUpdated}
            />
        );

        fireEvent.click(screen.getByText("保存"));

        await waitFor(() => {
            expect(screen.getByText("网络错误")).toBeTruthy();
        });
        expect(onClose).not.toHaveBeenCalled();
    });
});
