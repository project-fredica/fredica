/**
 * useVideoPlayerState.ts
 *
 * 单实例播放器状态机（见设计文档 §6.3）。
 *
 * 状态：CHECKING → PAUSED / NEEDS_ENCODE → ENCODING → PAUSED
 *       PAUSED ↔ PLAYING
 *
 * 职责：
 * - 挂载时查询 MaterialVideoCheckRoute，决定初始状态
 * - 管理转码任务启动：
 *     - bilibili 素材 → MaterialBilibiliDownloadTranscodeRoute (download+transcode DAG)
 *     - 其他素材      → MaterialVideoTranscodeMp4Route (transcode only)
 * - 管理 pendingSeek（字幕联动、localStorage 恢复）
 * - localStorage 进度持久化（key: fredica-video-progress-{materialId}）
 * - 每 5s 定时保存进度（兜底）
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { useAppConfig } from "~/context/appConfig";
import { buildAuthHeaders } from "~/util/app_fetch";
import { print_error, reportHttpError } from "~/util/error_handler";
import { json_parse } from "~/util/json";
import { MATERIAL_BILIBILI_DOWNLOAD_TRANSCODE_API_PATH, fetchActiveWorkflows, findActiveEncodeWorkflowRunId } from "~/util/materialWorkflowApi";
import { isBilibiliVideo } from "~/components/material-library/materialTypes";

// ── 状态定义 ──────────────────────────────────────────────────────────────────

export type VideoPlayerInternalState =
    | "checking"
    | "needs_encode"
    | "encoding"
    | "paused"
    | "playing";

export interface VideoCheckResult {
    ready: boolean;
    file_mtime: number | null;
    file_size: number | null;
}

export interface PendingSeek {
    seconds: number;
    autoPlay: boolean;
}

export interface VideoPlayerStateResult {
    state: VideoPlayerInternalState;
    /** 视频就绪时的文件 mtime（用于 React key，触发 DOM 重建）*/
    fileMtime: number | null;
    workflowRunId: string | null;
    pendingSeek: PendingSeek | null;

    /** 用户点击"开始转码" */
    startEncode: () => Promise<void>;
    /** 外部（字幕联动 / FloatingPlayerCtx）设置 seekTo 后更新 pendingSeek */
    setPendingSeek: (seek: PendingSeek | null) => void;
    /** 播放状态改变时通知状态机 */
    onPlaybackStarted: (currentTime: number) => void;
    onPlaybackPaused: (currentTime: number) => void;
    /** channel 收到 playing(other) 时强制暂停 */
    onForcePause: () => void;
    /** 保存当前进度到 localStorage */
    saveProgress: (currentTime: number) => void;
    /** 从 localStorage 读取进度 */
    loadSavedProgress: () => number | null;
}

// ── localStorage 进度工具 ─────────────────────────────────────────────────────

const PROGRESS_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 天

function progressKey(materialId: string) {
    return `fredica-video-progress-${materialId}`;
}

function saveProgressToStorage(materialId: string, currentTime: number) {
    try {
        localStorage.setItem(
            progressKey(materialId),
            JSON.stringify({ currentTime, savedAt: Date.now() })
        );
    } catch { /* ignore quota errors */ }
}

function loadProgressFromStorage(materialId: string): number | null {
    try {
        const raw = localStorage.getItem(progressKey(materialId));
        if (!raw) return null;
        const { currentTime, savedAt } = json_parse<{ currentTime: number; savedAt: number }>(raw)!;
        if (Date.now() - savedAt > PROGRESS_TTL_MS) return null;
        return currentTime;
    } catch {
        return null;
    }
}

// ── Hook ──────────────────────────────────────────────────────────────────────

/**
 * @param materialId    素材 ID
 * @param materialType  素材类型（"video" / "audio" 等）
 * @param sourceType    素材来源类型（"bilibili" / "bilibili_favorite" 等）。
 *                      bilibili 视频启动下载+转码双任务流水线，其他素材直接启动转码任务。
 */
export function useVideoPlayerState(materialId: string, materialType?: string, sourceType?: string): VideoPlayerStateResult {
    const { appConfig } = useAppConfig();
    const [state, setState] = useState<VideoPlayerInternalState>("checking");
    const [fileMtime, setFileMtime] = useState<number | null>(null);
    const [workflowRunId, setWorkflowRunId] = useState<string | null>(null);
    const [pendingSeek, setPendingSeek] = useState<PendingSeek | null>(null);

    const progressTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const currentTimeRef = useRef<number>(0);

    const serverBase = (() => {
        const schema = appConfig.webserver_schema ?? "http";
        const domain = appConfig.webserver_domain ?? "localhost";
        const port = appConfig.webserver_port ?? "7631";
        return `${schema}://${domain}:${port}`;
    })();
    const authHeaders = buildAuthHeaders(appConfig);

    // ── 查询 MP4 就绪状态 ────────────────────────────────────────────────────

    const checkReady = useCallback(async (): Promise<VideoCheckResult | null> => {
        try {
            const resp = await fetch(
                `${serverBase}/api/v1/MaterialVideoCheckRoute?material_id=${encodeURIComponent(materialId)}`,
                { headers: authHeaders }
            );
            if (!resp.ok) return null;
            return await resp.json() as VideoCheckResult;
        } catch {
            return null;
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId, serverBase]);

    // ── 挂载 / materialId 变更时轮询 ─────────────────────────────────────────

    useEffect(() => {
        setState("checking");
        setFileMtime(null);
        setWorkflowRunId(null);
        setPendingSeek(null);

        let cancelled = false;
        let pollTimer: ReturnType<typeof setTimeout> | null = null;

        async function poll() {
            const result = await checkReady();
            if (cancelled) return;

            if (result?.ready) {
                setFileMtime(result.file_mtime);
                // 恢复进度：localStorage 记录（无显式 pendingSeek 时）
                const saved = loadProgressFromStorage(materialId);
                if (saved !== null) {
                    setPendingSeek(prev => prev ?? { seconds: saved, autoPlay: false });
                }
                setState("paused");
            } else if (result && !result.ready) {
                // 视频未就绪：先查活跃工作流，判断是恢复 encoding 还是进入 needs_encode。
                // 场景：用户刷新页面时，下载/转码任务仍在后台运行，
                //       此时应直接进入 encoding 状态而非再次展示"开始转码"按钮。
                const active = await fetchActiveWorkflows(serverBase, materialId, authHeaders);
                if (cancelled) return;
                const runId = findActiveEncodeWorkflowRunId(active);
                if (runId !== null) {
                    setWorkflowRunId(runId);
                    setState("encoding");
                } else {
                    setState("needs_encode");
                }
            } else {
                // 网络错误，3s 后重试
                pollTimer = setTimeout(poll, 3000);
            }
        }

        poll();
        return () => {
            cancelled = true;
            if (pollTimer) clearTimeout(pollTimer);
        };
    }, [materialId, checkReady]);

    // ── encoding 期间每 3s 轮询，就绪后直接进入 paused ───────────────────────
    //
    // 注意：不能 setState("checking") 后依赖 mount effect 重跑，
    // 因为 mount effect 只在 materialId / checkReady 变化时重跑，state 改变不触发。

    useEffect(() => {
        if (state !== "encoding") return;
        let cancelled = false;
        const timer = setInterval(async () => {
            const result = await checkReady();
            if (cancelled) return;
            if (result?.ready) {
                clearInterval(timer);
                setFileMtime(result.file_mtime);
                const saved = loadProgressFromStorage(materialId);
                if (saved !== null) {
                    setPendingSeek(prev => prev ?? { seconds: saved, autoPlay: false });
                }
                setState("paused");
            }
        }, 3000);
        return () => { cancelled = true; clearInterval(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [state, checkReady, materialId]);

    // ── 每 5s 自动保存进度（兜底）────────────────────────────────────────────

    useEffect(() => {
        if (state !== "playing") {
            if (progressTimerRef.current) {
                clearInterval(progressTimerRef.current);
                progressTimerRef.current = null;
            }
            return;
        }
        progressTimerRef.current = setInterval(() => {
            saveProgressToStorage(materialId, currentTimeRef.current);
        }, 5000);
        return () => {
            if (progressTimerRef.current) {
                clearInterval(progressTimerRef.current);
                progressTimerRef.current = null;
            }
        };
    }, [state, materialId]);

    // ── 外部 API ──────────────────────────────────────────────────────────────

    const startEncode = useCallback(async () => {
        try {
            // bilibili 素材：下载 + 转码双任务流水线（下载任务携带 check_skip，已有文件自动跳过）
            // 其他素材：直接转码（本地文件已就绪，仅需格式转换）
            const isBilibili = materialType != null && sourceType != null
                && isBilibiliVideo({ type: materialType, source_type: sourceType });
            const url = isBilibili
                ? `${serverBase}${MATERIAL_BILIBILI_DOWNLOAD_TRANSCODE_API_PATH}`
                : `${serverBase}/api/v1/MaterialVideoTranscodeMp4Route`;
            const body = JSON.stringify({ material_id: materialId });

            const resp = await fetch(url, {
                method: "POST",
                headers: { ...authHeaders, "Content-Type": "application/json" },
                body,
            });

            if (!resp.ok) {
                reportHttpError("启动转码失败", resp);
                return;
            }

            const json = await resp.json() as { workflow_run_id?: string; error?: string };

            if (json.error === "TASK_ALREADY_ACTIVE") {
                // 查找已有 workflow_run_id（下载或转码任务均可）
                const listResp = await fetch(
                    `${serverBase}/api/v1/WorkerTaskListRoute?material_id=${encodeURIComponent(materialId)}`,
                    { headers: authHeaders }
                );
                if (listResp.ok) {
                    const data = await listResp.json() as { items: { type: string; workflow_run_id: string; status: string }[] };
                    const active = data.items.find(t =>
                        ["DOWNLOAD_BILIBILI_VIDEO", "TRANSCODE_MP4"].includes(t.type) &&
                        ["pending", "claimed", "running"].includes(t.status));
                    if (active) setWorkflowRunId(active.workflow_run_id);
                }
            } else if (json.error) {
                print_error({ reason: `启动转码失败: ${json.error}` });
                return;
            } else if (json.workflow_run_id) {
                setWorkflowRunId(json.workflow_run_id);
            }
            setState("encoding");
        } catch (e) {
            print_error({ reason: "启动转码请求异常", err: e });
        }
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [materialId, serverBase, materialType, sourceType]);

    const onPlaybackStarted = useCallback((currentTime: number) => {
        currentTimeRef.current = currentTime;
        setState("playing");
    }, []);

    const onPlaybackPaused = useCallback((currentTime: number) => {
        currentTimeRef.current = currentTime;
        saveProgressToStorage(materialId, currentTime);
        setState("paused");
    }, [materialId]);

    const onForcePause = useCallback(() => {
        setState(prev => {
            if (prev === "playing") {
                saveProgressToStorage(materialId, currentTimeRef.current);
                return "paused";
            }
            return prev;
        });
    }, [materialId]);

    const saveProgress = useCallback((currentTime: number) => {
        currentTimeRef.current = currentTime;
        saveProgressToStorage(materialId, currentTime);
    }, [materialId]);

    const loadSavedProgress = useCallback((): number | null => {
        return loadProgressFromStorage(materialId);
    }, [materialId]);

    return {
        state,
        fileMtime,
        workflowRunId,
        pendingSeek,
        startEncode,
        setPendingSeek,
        onPlaybackStarted,
        onPlaybackPaused,
        onForcePause,
        saveProgress,
        loadSavedProgress,
    };
}
