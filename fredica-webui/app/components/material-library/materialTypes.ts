export interface MaterialVideo {
    id: string;
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
    name: string;
    description: string;
    /** Count of all materials (any type) in this category. */
    material_count: number;
    created_at: number;
    updated_at: number;
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

export const SOURCE_BADGE: Record<string, { label: string; className: string }> = {
    bilibili: { label: 'B站', className: 'bg-pink-100 text-pink-700' },
    youtube:  { label: 'YouTube', className: 'bg-red-100 text-red-700' },
    local:    { label: '本地', className: 'bg-gray-100 text-gray-600' },
};

export const WORKER_TASK_STATUS: Record<string, { label: string; className: string }> = {
    pending:   { label: '排队中', className: 'bg-yellow-100 text-yellow-700' },
    claimed:   { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    running:   { label: '执行中', className: 'bg-blue-100 text-blue-700' },
    completed: { label: '已完成', className: 'bg-green-100 text-green-700' },
    failed:    { label: '失败',   className: 'bg-red-100 text-red-700' },
    cancelled: { label: '已取消', className: 'bg-gray-100 text-gray-400' },
};

export const TASK_TYPE_LABELS: Record<string, string> = {
    DOWNLOAD_BILIBILI_VIDEO: '下载视频',
    TRANSCODE_MP4:           '转码 MP4',
};

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
