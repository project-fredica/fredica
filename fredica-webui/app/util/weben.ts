// ─── Data Models ──────────────────────────────────────────────────────────────

export interface WebenSource {
    id: string;
    material_id: string | null;
    url: string;
    title: string;
    source_type: string;
    bvid: string | null;
    duration_sec: number | null;
    quality_score: number;
    analysis_status: string; // pending | analyzing | completed | failed
    workflow_run_id: string | null;
    progress: number; // 0-100，由后端从任务图动态计算（所有任务进度的平均值）
    created_at: number;
}

export interface WebenConcept {
    id: string;
    material_id: string | null;
    canonical_name: string;
    concept_type: string;
    brief_definition: string | null;
    metadata_json: string;
    confidence: number;
    first_seen_at: number;
    last_seen_at: number;
    created_at: number;
    updated_at: number;
}

export interface WebenConceptAlias {
    id: number;
    concept_id: string;
    alias: string;
    alias_source: string | null;
}

export interface WebenConceptSource {
    id: number;
    concept_id: string;
    source_id: string;
    timestamp_sec: number | null;
    excerpt: string | null;
}

export interface WebenNote {
    id: string;
    concept_id: string;
    content: string;
    created_at: number;
    updated_at: number;
}

// ─── API Response Types ────────────────────────────────────────────────────────

export interface WebenConceptDetailResponse {
    concept: WebenConcept;
    aliases: WebenConceptAlias[];
    sources: WebenConceptSource[];
    notes: WebenNote[];
}

export interface WebenSourceListItem {
    source: WebenSource;
    concept_count: number;
}

export interface WebenSourcePageResult {
    items: WebenSourceListItem[];
    total: number;
    offset: number;
    limit: number;
}

export interface WebenConceptTypeHint {
    key: string;
    label: string;
    color: string;
    source_type: string;
}

const FALLBACK_TYPE_COLOR = 'bg-gray-100 text-gray-600 ring-gray-200';

export function getConceptTypeInfo(type: string, hints?: WebenConceptTypeHint[]): { label: string; color: string } {
    const hit = hints?.find(item => item.key === type);
    if (hit) return { label: hit.label, color: hit.color };
    return { label: type, color: FALLBACK_TYPE_COLOR };
}

export interface WebenExtractionRun {
    id: string;
    source_id: string;
    material_id: string | null;
    prompt_script: string | null;
    prompt_text: string | null;
    llm_model_id: string | null;
    llm_input_json: string | null;
    llm_output_raw: string | null;
    concept_count: number;
    created_at: number;
}

// ─── Analysis Status ───────────────────────────────────────────────────────────

export const ANALYSIS_STATUS: Record<string, { label: string; dot: string; badge: string }> = {
    pending:   { label: '待分析', dot: 'bg-gray-400',              badge: 'bg-gray-100 text-gray-500'   },
    analyzing: { label: '分析中', dot: 'bg-blue-500 animate-pulse',badge: 'bg-blue-100 text-blue-700'   },
    completed: { label: '已完成', dot: 'bg-green-500',             badge: 'bg-green-100 text-green-700' },
    failed:    { label: '失败',   dot: 'bg-red-500',               badge: 'bg-red-100 text-red-700'     },
};

export function getAnalysisStatusInfo(status: string) {
    return ANALYSIS_STATUS[status] ?? { label: status, dot: 'bg-gray-400', badge: 'bg-gray-100 text-gray-500' };
}

// ─── Formatters ────────────────────────────────────────────────────────────────

export function formatDate(ts: number): string {
    return new Date(ts * 1000).toLocaleDateString('zh-CN');
}

export function formatRelativeTime(ts: number): string {
    const diff = Date.now() / 1000 - ts;
    if (diff < 60)     return '刚刚';
    if (diff < 3600)   return `${Math.floor(diff / 60)} 分钟前`;
    if (diff < 86400)  return `${Math.floor(diff / 3600)} 小时前`;
    if (diff < 604800) return `${Math.floor(diff / 86400)} 天前`;
    return formatDate(ts);
}

export function formatDuration(sec: number): string {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    if (h > 0) return `${h}时${m}分`;
    return `${m} 分钟`;
}
