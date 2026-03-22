/**
 * materialWorkflowApi.ts
 *
 * MaterialWorkflowRoute（启动工作流）和 MaterialWorkflowStatusRoute（查询工作流）
 * 的类型定义与客户端封装。统一管理路由路径，避免硬编码散落在各处。
 */

import { print_error } from "~/util/error_handler";

/** POST 启动工作流路由的相对路径。 */
export const MATERIAL_WORKFLOW_API_PATH = "/api/v1/MaterialWorkflowRoute";

/** GET 查询工作流状态路由的相对路径。 */
export const MATERIAL_WORKFLOW_STATUS_API_PATH = "/api/v1/MaterialWorkflowStatusRoute";

export type WorkflowTemplate = "bilibili_download_transcode";

export interface StartWorkflowResult {
    workflow_run_id?: string;
    download_task_id?: string;
    transcode_task_id?: string;
    error?: string;
}

// ── 活跃工作流查询结果类型 ────────────────────────────────────────────────────

export interface ActiveTaskInfo {
    id: string;
    type: string;
    status: string;
    progress: number;
    workflow_run_id: string;
}

export interface ActiveWorkflowRunEntry {
    workflow_run: {
        id: string;
        status: string;
        template: string;
        total_tasks: number;
        done_tasks: number;
        created_at: number;
    };
    tasks: ActiveTaskInfo[];
}

export interface ActiveWorkflowsResult {
    workflow_runs: ActiveWorkflowRunEntry[];
}

// ── 下载/转码任务类型 & 活跃状态集合（供多处判断复用）────────────────────────

const ENCODE_TASK_TYPES = new Set(["DOWNLOAD_BILIBILI_VIDEO", "TRANSCODE_MP4"]);
const ACTIVE_STATUSES   = new Set(["pending", "claimed", "running"]);

// ── 客户端封装 ────────────────────────────────────────────────────────────────

/**
 * 启动素材工作流（适用于组件/路由层，通过 useAppFetch 提供的 apiFetch 调用）。
 *
 * @example
 * const { resp } = await startMaterialWorkflow(apiFetch, material.id, "bilibili_download_transcode");
 * if (!resp.ok) reportHttpError("启动失败", resp);
 */
export async function startMaterialWorkflow(
    apiFetch: (path: string, init?: RequestInit) => Promise<{ resp: Response }>,
    materialId: string,
    template: WorkflowTemplate,
): Promise<{ resp: Response }> {
    return apiFetch(MATERIAL_WORKFLOW_API_PATH, {
        method: "POST",
        body: JSON.stringify({ material_id: materialId, template }),
    });
}

/**
 * 查询素材当前所有活跃（非终态）工作流及其子任务。
 * 用于页面刷新时检测是否有正在运行的下载/转码任务，以恢复 encoding 显示状态。
 *
 * @returns 活跃工作流列表，失败时返回 null（调用方降级到 needs_encode）
 */
export async function fetchActiveWorkflows(
    serverBase: string,
    materialId: string,
    authHeaders: Record<string, string>,
): Promise<ActiveWorkflowsResult | null> {
    try {
        const resp = await fetch(
            `${serverBase}${MATERIAL_WORKFLOW_STATUS_API_PATH}?material_id=${encodeURIComponent(materialId)}`,
            { headers: authHeaders },
        );
        if (!resp.ok) return null;
        return await resp.json() as ActiveWorkflowsResult;
    } catch (e) {
        print_error({ reason: "查询活跃工作流失败", err: e });
        return null;
    }
}

/**
 * 从 [ActiveWorkflowsResult] 中提取第一个含活跃下载/转码任务的 workflow_run_id。
 *
 * "活跃下载/转码任务"定义：
 *   - type 为 DOWNLOAD_BILIBILI_VIDEO 或 TRANSCODE_MP4
 *   - status 为 pending / claimed / running
 *
 * @returns 找到则返回 workflow_run_id，否则返回 null
 */
export function findActiveEncodeWorkflowRunId(result: ActiveWorkflowsResult | null): string | null {
    if (!result) return null;
    const entry = result.workflow_runs.find(e =>
        e.tasks.some(t => ENCODE_TASK_TYPES.has(t.type) && ACTIVE_STATUSES.has(t.status))
    );
    return entry?.workflow_run.id ?? null;
}

