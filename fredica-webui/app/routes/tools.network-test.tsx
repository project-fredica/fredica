import { useState, useEffect, useRef } from "react";
import {
    Wifi, Play, Loader, CheckCircle, XCircle, Clock,
    RotateCcw, ArrowRight, ArrowLeft, RefreshCw, AlertTriangle,
} from "lucide-react";
import { Link } from "react-router";
import { useAppFetch } from "~/util/app_fetch";
import { json_parse } from "~/util/json";
import { print_error, reportHttpError } from "~/util/error_handler";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

// ─── Types ────────────────────────────────────────────────────────────────────

interface ConnResult {
    latency_ms: number | null;
    status: "ok" | "timeout" | "error";
    error: string | null;
}

interface NetworkTestUrlResult {
    url: string;
    direct: ConnResult;
    proxied: ConnResult | null;
}

interface NetworkTestOutput {
    proxy_configured: boolean;
    results: NetworkTestUrlResult[];
    /** 检测到代理异常时的提示，null 表示无异常 */
    proxy_warning: string | null;
}

interface NetworkTestTask {
    id: string;
    status: string;
    result: string | null;
    error: string | null;
    error_type: string | null;
}

type TestPhase = "idle" | "creating" | "waiting" | "done" | "failed";

// ─── Constants ────────────────────────────────────────────────────────────────

const POLL_INTERVAL_MS = 1_000;
/** 持续测试：上次完成后等待多久自动发起下一轮（ms） */
const CONTINUOUS_DELAY_MS = 1_500;

const PHASE_LABEL: Record<TestPhase, string> = {
    idle: "",
    creating: "正在提交任务…",
    waiting: "测试中，请稍候…",
    done: "测试完成",
    failed: "测试失败",
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function fmtLatency(ms: number | null): string {
    if (ms === null) return "—";
    if (ms < 1000) return `${ms} ms`;
    return `${(ms / 1000).toFixed(1)} s`;
}

function latencyColor(ms: number | null): string {
    if (ms === null) return "text-gray-400";
    if (ms < 1000) return "text-green-600 font-medium";
    return "text-yellow-600 font-medium";
}

function ConnCell({ r }: { r: ConnResult | null }) {
    if (r === null) {
        return <span className="text-gray-300 text-xs">未配置</span>;
    }
    if (r.status === "ok") {
        return (
            <span className={`flex items-center gap-1.5 tabular-nums ${latencyColor(r.latency_ms)}`}>
                <CheckCircle className={`w-3.5 h-3.5 flex-shrink-0 ${(r.latency_ms ?? 0) < 1000 ? "text-green-500" : "text-yellow-500"}`} />
                {fmtLatency(r.latency_ms)}
            </span>
        );
    }
    if (r.status === "timeout") {
        return (
            <span className="flex items-center gap-1.5 text-red-600" title={r.error ?? undefined}>
                <Clock className="w-3.5 h-3.5 flex-shrink-0" />
                超时
            </span>
        );
    }
    return (
        <span className="flex items-center gap-1.5 text-red-600" title={r.error ?? undefined}>
            <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
            错误
        </span>
    );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function NetworkTestPage() {
    const [phase, setPhase] = useState<TestPhase>("idle");
    const [output, setOutput] = useState<NetworkTestOutput | null>(null);
    const [taskError, setTaskError] = useState<string | null>(null);
    const [taskId, setTaskId] = useState<string | null>(null);
    const [pipelineId, setPipelineId] = useState<string | null>(null);
    const [continuous, setContinuous] = useState(false);
    const [round, setRound] = useState(0);
    const [configUrls, setConfigUrls] = useState<string[]>([]);

    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
    const retriggerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const { apiFetch } = useAppFetch();

    useEffect(() => {
        apiFetch<{ urls: string[] }>("/api/v1/NetworkTestConfigRoute", { method: "GET" })
            .then(({ resp, data }) => {
                if (!resp.ok) { reportHttpError("加载测试目标列表失败", resp); return; }
                if (data?.urls) setConfigUrls(data.urls);
            })
            .catch(e => { print_error({ reason: "加载测试目标列表异常", err: e }); });
    }, []);

    const stopPolling = () => {
        if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
        if (retriggerRef.current) { clearTimeout(retriggerRef.current); retriggerRef.current = null; }
    };
    useEffect(() => () => stopPolling(), []);

    // ── 核心：发起一次测试 ──────────────────────────────────────────────────
    const handleStart = async () => {
        setPhase("creating");
        // 不清除 output，保留上次结果，直到新结果到达后覆盖
        setTaskError(null);
        stopPolling();

        try {
            const { resp, data } = await apiFetch("/api/v1/NetworkTestRoute", {
                method: "POST",
                body: "{}",
            });

            if (!resp.ok) {
                reportHttpError("提交测试任务失败", resp);
                setPhase("failed");
                setTaskError("提交失败，请重试");
                return;
            }

            const newWorkflowRunId = (data as any).workflow_run_id as string;
            const newTaskId = (data as any).task_id as string;
            setPipelineId(newWorkflowRunId);
            setTaskId(newTaskId);
            setPhase("waiting");

            pollRef.current = setInterval(async () => {
                try {
                    const { data: taskData } = await apiFetch<{ items: NetworkTestTask[]; total: number }>(
                        `/api/v1/WorkerTaskListRoute?workflow_run_id=${newWorkflowRunId}`,
                        { method: "GET" },
                    );
                    const items = taskData?.items;
                    if (!items?.length) return;
                    const task = items[0];

                    if (task.status === "completed") {
                        stopPolling();
                        setPhase("done");
                        setRound(r => r + 1);
                        if (task.result) {
                            setOutput(json_parse<NetworkTestOutput>(task.result) ?? null);
                        }
                    } else if (task.status === "failed") {
                        stopPolling();
                        setPhase("failed");
                        setTaskError(task.error ?? "任务执行失败");
                    }
                } catch { /* 单次轮询失败不中止 */ }
            }, POLL_INTERVAL_MS);

        } catch (e: any) {
            setPhase("failed");
            setTaskError(e?.message ?? "提交任务失败");
        }
    };

    // ── 持续测试：完成后自动触发下一轮 ────────────────────────────────────
    useEffect(() => {
        if (phase === "done" && continuous) {
            retriggerRef.current = setTimeout(() => {
                handleStart();
            }, CONTINUOUS_DELAY_MS);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [phase, continuous]);

    const handleReset = () => {
        stopPolling();
        setPhase("idle"); setOutput(null); setTaskError(null);
        setTaskId(null); setPipelineId(null); setRound(0);
    };

    const isRunning = phase === "creating" || phase === "waiting";

    // ── Render ────────────────────────────────────────────────────────────────
    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

                {/* 面包屑 */}
                <div className="flex items-center gap-2 text-sm text-gray-500">
                    <Link to="/tools" className="flex items-center gap-1 hover:text-gray-700 transition-colors">
                        <ArrowLeft className="w-3.5 h-3.5" />
                        小工具
                    </Link>
                    <span>/</span>
                    <span className="text-gray-900 font-medium">网速和延迟测试</span>
                </div>

                {/* 主卡片 */}
                <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">

                    {/* 卡片标题栏 */}
                    <div className="px-5 py-4 border-b border-gray-100 flex flex-wrap items-center gap-3">
                        <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center flex-shrink-0">
                            <Wifi className="w-4 h-4 text-blue-600" />
                        </div>
                        <div className="flex-1 min-w-0">
                            <h1 className="text-sm font-semibold text-gray-900">网速和延迟测试</h1>
                            <p className="text-xs text-gray-500 mt-0.5">
                                区分直连与代理两种路径，通过 Worker Engine 异步执行
                            </p>
                        </div>
                        {/* 代理状态徽章 */}
                        {output && (
                            <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full flex-shrink-0 ${output.proxy_configured
                                ? "bg-blue-100 text-blue-700"
                                : "bg-gray-100 text-gray-500"
                                }`}>
                                {output.proxy_configured ? "代理已配置" : "无代理"}
                            </span>
                        )}
                    </div>

                    <div className="px-5 py-4 space-y-4">

                        {/* 测试目标列表 */}
                        <div>
                            <p className="text-xs font-medium text-gray-400 mb-2 uppercase tracking-wider">测试目标</p>
                            <div className="space-y-1">
                                {configUrls.map(url => (
                                    <div key={url} className="flex items-center gap-2 text-xs text-gray-500 font-mono">
                                        <span className="w-1.5 h-1.5 rounded-full bg-gray-200 flex-shrink-0" />
                                        {url}
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* 操作区：按钮 + 持续测试开关 */}
                        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                            <button
                                onClick={handleStart}
                                disabled={isRunning}
                                className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {isRunning
                                    ? <Loader className="w-4 h-4 animate-spin" />
                                    : <Play className="w-4 h-4" />
                                }
                                {isRunning ? PHASE_LABEL[phase] : (round === 0 ? "开始测试" : "再次测试")}
                            </button>

                            {(phase === "done" || phase === "failed") && !continuous && (
                                <button
                                    onClick={handleReset}
                                    className="flex items-center gap-1.5 px-3 py-2 text-sm text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
                                >
                                    <RotateCcw className="w-3.5 h-3.5" />
                                    重置
                                </button>
                            )}

                            {/* 持续测试开关 */}
                            <div className="ml-auto flex items-center gap-1.5">
                                <RefreshCw className={`w-3 h-3 ${continuous ? "text-blue-500" : "text-gray-400"}`} />
                                <span className="text-xs text-gray-500">持续测试</span>
                                <div className="flex rounded-md border border-gray-200 overflow-hidden text-xs font-medium">
                                    <button
                                        onClick={() => setContinuous(false)}
                                        className={`px-2.5 py-1 transition-colors ${!continuous
                                            ? "bg-gray-100 text-gray-700"
                                            : "bg-white text-gray-400 hover:bg-gray-50"
                                            }`}
                                    >
                                        关
                                    </button>
                                    <button
                                        onClick={() => setContinuous(true)}
                                        className={`px-2.5 py-1 transition-colors border-l border-gray-200 ${continuous
                                            ? "bg-blue-600 text-white"
                                            : "bg-white text-gray-400 hover:bg-gray-50"
                                            }`}
                                    >
                                        开
                                    </button>
                                </div>
                            </div>
                        </div>

                        {/* 轮次计数（持续测试时显示） */}
                        {round > 0 && (
                            <p className="text-xs text-gray-400">
                                已完成第 <span className="font-semibold text-gray-600">{round}</span> 轮
                                {continuous && isRunning && <span className="ml-1 text-blue-500">（下一轮进行中…）</span>}
                                {continuous && phase === "done" && <span className="ml-1 text-blue-400">（即将开始下一轮…）</span>}
                            </p>
                        )}

                        {/* 失败提示 */}
                        {phase === "failed" && taskError && (
                            <div className="rounded-lg bg-red-50 border border-red-100 px-3 py-2 text-xs text-red-700">
                                {taskError}
                            </div>
                        )}

                        {/* ── 结果表格（始终保留，直到新结果覆盖） ──────── */}
                        {output && (
                            <div>
                                <div className="flex items-center gap-2 mb-2">
                                    <p className="text-xs font-medium text-gray-400 uppercase tracking-wider">
                                        测试结果
                                    </p>
                                    {/* 直连/代理图例 */}
                                    <div className="flex items-center gap-3 ml-auto text-xs text-gray-400">
                                        <span className="flex items-center gap-1">
                                            <ArrowRight className="w-3 h-3" /> 直连
                                        </span>
                                        {output.proxy_configured && (
                                            <span className="flex items-center gap-1 text-blue-500">
                                                <ArrowRight className="w-3 h-3" /> 代理
                                            </span>
                                        )}
                                    </div>
                                </div>

                                <div className="rounded-lg border border-gray-100 overflow-x-auto">
                                    <table className="w-full text-xs">
                                        <thead>
                                            <tr className="bg-gray-50 border-b border-gray-100">
                                                <th className="text-left px-3 py-2 font-medium text-gray-500">URL</th>
                                                <th className="text-left px-3 py-2 font-medium text-gray-500 w-36">直连</th>
                                                {output.proxy_configured && (
                                                    <th className="text-left px-3 py-2 font-medium text-blue-500 w-36">代理</th>
                                                )}
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-gray-50">
                                            {output.results.map((r, i) => (
                                                <tr key={i} className="hover:bg-gray-50/50">
                                                    <td className="px-3 py-2.5 font-mono text-gray-600 truncate max-w-0 w-full">
                                                        {r.url.replace(/^https?:\/\//, "")}
                                                    </td>
                                                    <td className="px-3 py-2.5">
                                                        <ConnCell r={r.direct} />
                                                    </td>
                                                    {output.proxy_configured && (
                                                        <td className="px-3 py-2.5">
                                                            <ConnCell r={r.proxied} />
                                                        </td>
                                                    )}
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>

                                {/* 汇总行 */}
                                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                                    <span>
                                        直连：{output.results.filter(r => r.direct.status === "ok").length}
                                        /{output.results.length} 正常
                                    </span>
                                    {output.proxy_configured ? (
                                        <span className="text-blue-600">
                                            代理：{output.results.filter(r => r.proxied?.status === "ok").length}
                                            /{output.results.length} 正常
                                        </span>
                                    ) : (
                                        <span className="text-gray-400">未配置系统代理</span>
                                    )}
                                    {pipelineId && (
                                        <a href="/processing" className="ml-auto text-blue-500 hover:underline">
                                            在处理中心查看 →
                                        </a>
                                    )}
                                </div>

                                {/* 代理异常警告 */}
                                {output.proxy_warning && (
                                    <div className="mt-3 flex gap-2 rounded-lg bg-yellow-50 border border-yellow-200 px-3 py-2.5 text-xs text-yellow-800">
                                        <AlertTriangle className="w-3.5 h-3.5 flex-shrink-0 mt-0.5 text-yellow-500" />
                                        <span><span className="font-semibold">代理异常：</span>{output.proxy_warning}</span>
                                    </div>
                                )}

                                {/* 等待中提示（放在表格下方，避免表格位置跳动） */}
                                {isRunning && (
                                    <div className="mt-4 flex items-center gap-2 text-xs text-blue-600 bg-blue-50 rounded-lg px-3 py-2">
                                        <Loader className="w-3 h-3 animate-spin flex-shrink-0" />
                                        <span>{PHASE_LABEL[phase]}</span>
                                        {taskId && (
                                            <span className="text-blue-400 font-mono ml-auto">
                                                {taskId.slice(0, 8)}…
                                            </span>
                                        )}
                                    </div>
                                )}
                            </div>
                        )}

                        {/* 等待中提示（首次测试时 output 尚无数据，单独显示） */}
                        {isRunning && !output && (
                            <div className="flex items-center gap-2 text-xs text-blue-600 bg-blue-50 rounded-lg px-3 py-2">
                                <Loader className="w-3 h-3 animate-spin flex-shrink-0" />
                                <span>{PHASE_LABEL[phase]}</span>
                                {taskId && (
                                    <span className="text-blue-400 font-mono ml-auto">
                                        {taskId.slice(0, 8)}…
                                    </span>
                                )}
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </SidebarLayout>
    );
}
