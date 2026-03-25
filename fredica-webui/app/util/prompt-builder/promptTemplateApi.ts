// =============================================================================
// promptTemplateApi.ts —— Prompt 脚本模板 CRUD 前端 API 工具
// =============================================================================
//
// 所有函数遵循 error-handling.md 约定：
//   - HTTP 错误：reportHttpError(reason, resp)
//   - 运行时错误：print_error({ reason, err })
//   - 后端 { error } 字段：调用方检查并上报
// =============================================================================

import { print_error, reportHttpError } from "~/util/error_handler";
import type { ApiFetchFn } from "../materialWebenApi";

// ── 类型定义 ──────────────────────────────────────────────────────────────────

/** 模板列表项（不含 script_code，节省传输量） */
export interface PromptTemplateListItem {
    id: string;
    name: string;
    description: string;
    category: string;
    /** "system" | "user" */
    source_type: string;
    schema_target: string;
    based_on_template_id: string | null;
    created_at: number;
    updated_at: number;
}

/** 完整模板（含 script_code，通过 get 接口获取） */
export interface PromptTemplate extends PromptTemplateListItem {
    script_language: string;
    script_code: string;
}

/** 保存模板请求体 */
export interface PromptTemplateSaveParam {
    id: string;
    name: string;
    description?: string;
    category?: string;
    script_code: string;
    schema_target?: string;
    based_on_template_id?: string | null;
}

// ── 类型守卫 ──────────────────────────────────────────────────────────────────

function isTemplateListItem(v: unknown): v is PromptTemplateListItem {
    if (!v || typeof v !== "object") return false;
    const o = v as Record<string, unknown>;
    return (
        typeof o.id === "string" &&
        typeof o.name === "string" &&
        typeof o.source_type === "string"
    );
}

function isTemplate(v: unknown): v is PromptTemplate {
    return isTemplateListItem(v) && typeof (v as unknown as Record<string, unknown>).script_code === "string";
}

// ── API 函数 ──────────────────────────────────────────────────────────────────

/**
 * 获取模板列表（不含 script_code）。
 * @param category 按业务分类过滤，留空则返回全部
 */
export async function fetchPromptTemplates(
    apiFetch: ApiFetchFn,
    category?: string,
): Promise<PromptTemplateListItem[]> {
    try {
        const query = category ? `?category=${encodeURIComponent(category)}` : "";
        const { resp, data } = await apiFetch(
            `/api/v1/PromptTemplateListRoute${query}`,
            undefined,
            { silent: true },
        );
        if (!resp.ok) {
            reportHttpError("获取模板列表失败", resp);
            return [];
        }
        if (Array.isArray(data) && data.every(isTemplateListItem)) return data;
        print_error({ reason: "模板列表响应格式异常", err: data });
        return [];
    } catch (err) {
        print_error({ reason: "获取模板列表异常", err });
        return [];
    }
}

/**
 * 按 id 获取模板完整内容（含 script_code）。
 * 找不到或出错时返回 null。
 */
export async function fetchPromptTemplateById(
    apiFetch: ApiFetchFn,
    id: string,
): Promise<PromptTemplate | null> {
    try {
        const { resp, data } = await apiFetch(
            `/api/v1/PromptTemplateGetRoute?id=${encodeURIComponent(id)}`,
            undefined,
            { silent: true },
        );
        if (!resp.ok) {
            reportHttpError("获取模板详情失败", resp);
            return null;
        }
        // 后端错误字段检查
        if (data && typeof data === "object" && "error" in data) {
            print_error({ reason: `获取模板失败: ${(data as { error: string }).error}`, err: null });
            return null;
        }
        if (isTemplate(data)) return data;
        print_error({ reason: "模板详情响应格式异常", err: data });
        return null;
    } catch (err) {
        print_error({ reason: "获取模板详情异常", err });
        return null;
    }
}

/**
 * 保存（新建或更新）用户模板。
 * 返回保存后的模板，失败时返回 null。
 */
export async function savePromptTemplate(
    apiFetch: ApiFetchFn,
    param: PromptTemplateSaveParam,
): Promise<PromptTemplate | null> {
    try {
        const { resp, data } = await apiFetch(
            "/api/v1/PromptTemplateSaveRoute",
            {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(param),
            },
            { silent: true },
        );
        if (!resp.ok) {
            reportHttpError("保存模板失败", resp);
            return null;
        }
        if (data && typeof data === "object" && "error" in data) {
            print_error({ reason: `保存模板失败: ${(data as { error: string }).error}`, err: null });
            return null;
        }
        if (isTemplate(data)) return data;
        print_error({ reason: "保存模板响应格式异常", err: data });
        return null;
    } catch (err) {
        print_error({ reason: "保存模板异常", err });
        return null;
    }
}

/**
 * 删除用户模板（系统模板不可删除，后端会拒绝）。
 * 返回是否删除成功。
 */
export async function deletePromptTemplate(
    apiFetch: ApiFetchFn,
    id: string,
): Promise<boolean> {
    try {
        const { resp, data } = await apiFetch(
            "/api/v1/PromptTemplateDeleteRoute",
            {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ id }),
            },
            { silent: true },
        );
        if (!resp.ok) {
            reportHttpError("删除模板失败", resp);
            return false;
        }
        if (data && typeof data === "object" && "error" in data) {
            print_error({ reason: `删除模板失败: ${(data as { error: string }).error}`, err: null });
            return false;
        }
        return !!(data && typeof data === "object" && (data as Record<string, unknown>).ok === true);
    } catch (err) {
        print_error({ reason: "删除模板异常", err });
        return false;
    }
}
