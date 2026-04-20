export interface MaterialVideo {
    id: string;
    type: string;
    source_type: string;
    source_id: string;
    title: string;
    cover_url: string;
    description: string;
    duration: number;
    local_video_path: string;
    local_audio_path: string;
    transcript_path: string;
    extra: string;
    created_at: number;
    updated_at: number;
    category_ids: string[];
}

export interface MaterialCategory {
    id: string;
    owner_id: string;
    name: string;
    description: string;
    allow_others_view: boolean;
    allow_others_add: boolean;
    allow_others_delete: boolean;
    material_count: number;
    is_mine: boolean;
    sync: MaterialCategorySyncPlatformInfoSummary | null;
    created_at: number;
    updated_at: number;
}

export interface MaterialCategorySyncPlatformInfoSummary {
    id: string;
    sync_type: string;
    platform_config: Record<string, unknown>;
    display_name: string;
    last_synced_at: number | null;
    item_count: number;
    sync_state: string;
    last_error: string | null;
    fail_count: number;
    subscriber_count: number;
    my_subscription: MaterialCategorySyncUserConfigSummary | null;
    owner_id: string;
    last_workflow_run_id: string | null;
}

export interface MaterialCategorySyncUserConfigSummary {
    id: string;
    enabled: boolean;
    cron_expr: string;
    freshness_window_sec: number;
}

export interface GroupedCategories {
    mine: MaterialCategory[];
    publicOthers: MaterialCategory[];
    synced: MaterialCategory[];
}

export const SYNC_TYPE_LABELS: Record<string, string> = {
    bilibili_favorite: 'B站收藏夹',
    bilibili_uploader: 'UP主投稿',
    bilibili_season: 'B站合集',
    bilibili_series: 'B站列表',
    bilibili_video_pages: '视频分P',
};

export const SYNC_STATE_LABELS: Record<string, { label: string; className: string }> = {
    idle:    { label: '已同步', className: 'bg-green-100 text-green-700' },
    syncing: { label: '同步中', className: 'bg-blue-100 text-blue-700' },
    failed:  { label: '同步失败', className: 'bg-red-100 text-red-700' },
};

function extractPlatformId(config: Record<string, unknown>): string {
    for (const key of ['media_id', 'mid', 'season_id', 'series_id', 'bvid']) {
        if (config[key] != null) return String(config[key]);
    }
    return '';
}

export function getSyncDisplayLabel(sync: MaterialCategorySyncPlatformInfoSummary): string {
    const prefix = SYNC_TYPE_LABELS[sync.sync_type] ?? sync.sync_type;
    const pid = extractPlatformId(sync.platform_config);
    if (sync.display_name) return `[${prefix}] ${sync.display_name} ${pid}`.trim();
    return `[${prefix}] ${pid}`;
}

export function groupCategories(categories: MaterialCategory[]): GroupedCategories {
    const mine: MaterialCategory[] = [];
    const publicOthers: MaterialCategory[] = [];
    const synced: MaterialCategory[] = [];
    for (const cat of categories) {
        if (cat.sync) {
            synced.push(cat);
        } else if (cat.is_mine) {
            mine.push(cat);
        } else {
            publicOthers.push(cat);
        }
    }
    return { mine, publicOthers, synced };
}

export function formatRelativeTime(epochSec: number): string {
    const now = Math.floor(Date.now() / 1000);
    const diff = now - epochSec;
    if (diff < 60) return '刚刚';
    if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`;
    if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`;
    if (diff < 2592000) return `${Math.floor(diff / 86400)}天前`;
    return new Date(epochSec * 1000).toLocaleDateString('zh-CN');
}

export interface BilibiliExtra {
    upper_name?: string;
    upper_face_url?: string;
    upper_mid?: number;
    cnt_play?: number;
    cnt_collect?: number;
    cnt_danmaku?: number;
    fav_time?: number;
    source_fid?: string;
    page_count?: number;
    bvid?: string;
}

export interface MaterialTask {
    id: string;
    material_id: string;
    task_type: string;
    status: string;
}

/** Task from the Phase-1 worker engine (task table). */
export interface WorkerTask {
    id: string;
    type: string;
    material_id: string;
    pipeline_id: string;
    status: string;
    result: string | null;
    error: string | null;
    error_type: string | null;
    progress: number;
    is_paused: boolean;
    /** 任务是否支持暂停；false 时禁用暂停按钮（如 FFmpeg 子进程转码）。默认 true。 */
    is_pausable: boolean;
    created_at: number;
}

export const POLL_INTERVAL_MS = 5_000;
export const PAGE_SIZE = 20;
export const MODAL_TASK_POLL_MS = 2_000;

/**
 * 判断素材是否属于"bilibili 视频"。
 *
 * 必须同时检查 type 和 source_type：source_type 以 "bilibili" 开头只说明来源是 bilibili，
 * 不代表素材是视频——未来可能出现 bilibili 音频、专栏等非视频素材，下载/转码策略完全不同。
 */
export type BilibiliVideoMaterial = { type: 'video'; source_type: string };
export function isBilibiliVideo<T extends { type: string; source_type: string }>(
    material: T,
): material is T & BilibiliVideoMaterial {
    return material.type === 'video' &&
        (material.source_type === 'bilibili' || material.source_type.startsWith('bilibili_'));
}

const SOURCE_BADGE_MAP: Record<string, { label: string; className: string }> = {
    youtube: { label: 'YouTube', className: 'bg-red-100 text-red-700' },
    local:   { label: '本地', className: 'bg-gray-100 text-gray-600' },
};

const BILIBILI_BADGE = { label: 'B站', className: 'bg-pink-100 text-pink-700' } as const;

export function getSourceBadge(sourceType: string): { label: string; className: string } | undefined {
    if (sourceType === 'bilibili' || sourceType.startsWith('bilibili_')) return BILIBILI_BADGE;
    return SOURCE_BADGE_MAP[sourceType];
}

export const WORKER_TASK_STATUS: Record<string, { label: string; className: string }> = {
    pending:   { label: '排队中', className: 'bg-yellow-100 text-yellow-700' },
    claimed:   { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    running:   { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    completed: { label: '已完成', className: 'bg-green-100 text-green-700' },
    failed:    { label: '失败',   className: 'bg-red-100 text-red-700' },
    cancelled: { label: '已取消', className: 'bg-gray-100 text-gray-400' },
};

export { TASK_TYPE_LABELS } from "~/components/ui/WorkflowInfoPanel";

export function formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return `${m}:${String(s).padStart(2, '0')}`;
}

export function formatCount(n: number): string {
    if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}亿`;
    if (n >= 10_000) return `${(n / 10_000).toFixed(1)}万`;
    return String(n);
}
