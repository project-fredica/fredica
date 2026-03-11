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
    canonical_name: string;
    concept_type: string;
    brief_definition: string | null;
    metadata_json: string;
    confidence: number;
    mastery: number;
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

export interface WebenRelation {
    id: string;
    subject_id: string;
    predicate: string;
    object_id: string;
    confidence: number;
    is_manual: boolean;
    created_at: number;
    updated_at: number;
}

export interface WebenFlashcard {
    id: string;
    concept_id: string;
    source_id: string | null;
    question: string;
    answer: string;
    card_type: string; // qa | cloze
    is_system: boolean;
    ease_factor: number;
    interval_days: number;
    review_count: number;
    next_review_at: number;
    created_at: number;
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
    relations: WebenRelation[];
    flashcard_count: number;
    notes: WebenNote[];
}

export interface WebenReviewQueueResponse {
    flashcards: WebenFlashcard[];
    concept_names: Record<string, string>;
}

// ─── Concept Types ─────────────────────────────────────────────────────────────

export const CONCEPT_TYPES: { key: string; label: string; color: string }[] = [
    { key: '术语',    label: '术语',    color: 'bg-blue-100 text-blue-700 ring-blue-200'     },
    { key: '理论',    label: '理论',    color: 'bg-purple-100 text-purple-700 ring-purple-200'},
    { key: '协议',    label: '协议',    color: 'bg-cyan-100 text-cyan-700 ring-cyan-200'      },
    { key: '算法',    label: '算法',    color: 'bg-green-100 text-green-700 ring-green-200'   },
    { key: '器件芯片',label: '器件芯片',color: 'bg-orange-100 text-orange-700 ring-orange-200'},
    { key: '公式',    label: '公式',    color: 'bg-rose-100 text-rose-700 ring-rose-200'      },
    { key: '设计模式',label: '设计模式',color: 'bg-pink-100 text-pink-700 ring-pink-200'      },
    { key: '工具软件',label: '工具软件',color: 'bg-teal-100 text-teal-700 ring-teal-200'      },
    { key: '硬件经验',label: '硬件经验',color: 'bg-amber-100 text-amber-700 ring-amber-200'   },
    { key: '开发经验',label: '开发经验',color: 'bg-lime-100 text-lime-700 ring-lime-200'      },
    { key: '方法技巧',label: '方法技巧',color: 'bg-sky-100 text-sky-700 ring-sky-200'         },
];

const CONCEPT_TYPE_MAP: Record<string, { label: string; color: string }> = Object.fromEntries(
    CONCEPT_TYPES.map(t => [t.key, { label: t.label, color: t.color }])
);

export function getConceptTypeInfo(type: string): { label: string; color: string } {
    return CONCEPT_TYPE_MAP[type] ?? { label: type, color: 'bg-gray-100 text-gray-600 ring-gray-200' };
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

// ─── Mastery ───────────────────────────────────────────────────────────────────

export function masteryBarColor(mastery: number): string {
    if (mastery >= 0.8) return 'bg-green-500';
    if (mastery >= 0.5) return 'bg-yellow-400';
    if (mastery >= 0.2) return 'bg-orange-400';
    return 'bg-red-400';
}

export function masteryLabel(mastery: number): string {
    if (mastery >= 0.8) return '已掌握';
    if (mastery >= 0.5) return '熟悉';
    if (mastery >= 0.2) return '学习中';
    if (mastery > 0)    return '初学';
    return '未复习';
}

export function masteryTextColor(mastery: number): string {
    if (mastery >= 0.8) return 'text-green-600';
    if (mastery >= 0.5) return 'text-yellow-600';
    if (mastery >= 0.2) return 'text-orange-500';
    return 'text-red-500';
}

// ─── Relation Predicates ───────────────────────────────────────────────────────

export const PREDICATES = ['包含', '依赖', '用于', '对比', '是...的实例', '实现', '扩展'];

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

export function formatReviewInterval(days: number): string {
    if (days < 1)   return `${Math.round(days * 24)} 小时后`;
    if (days < 30)  return `${Math.round(days)} 天后`;
    if (days < 365) return `${Math.round(days / 30)} 个月后`;
    return `${(days / 365).toFixed(1)} 年后`;
}
