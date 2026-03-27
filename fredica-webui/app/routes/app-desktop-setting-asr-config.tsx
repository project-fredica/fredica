import { useEffect, useState, useRef } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, Play, Download, CheckCircle, XCircle, Loader, AlertTriangle, Cpu } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting-asr-config";
import { useAppFetch } from "~/util/app_fetch";
import { callBridge, BridgeUnavailableError } from "~/util/bridge";
import { json_parse } from "~/util/json";

export function meta({ }: Route.MetaArgs) {
    return [{ title: "ASR 语音识别配置 - Fredica" }];
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface FasterWhisperConfig {
    model: string;
    compute_type: string;
    device: string;
    models_dir: string;
    compat_json: string;
}

interface ModelSupport {
    supported: boolean;
    vram_mb: number;
    error: string;
}

interface ComputeTypeResult {
    supported: boolean;
    error: string;
}

interface CompatResult {
    local_models: string[];
    compute_types: Record<string, ComputeTypeResult>;
    model_support: Record<string, ModelSupport>;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const WHISPER_MODELS = ["tiny", "base", "small", "medium", "large-v2", "large-v3"];
const COMPUTE_TYPES = ["float16", "int8_float16", "int8", "float32", "auto"];
const DEVICES = ["auto", "cuda", "cpu"];

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AsrConfigPage() {
    const navigate = useNavigate();
    const { apiFetch } = useAppFetch();

    const [cfg, setCfg] = useState<FasterWhisperConfig>({
        model: "",
        compute_type: "auto",
        device: "auto",
        models_dir: "",
        compat_json: "{}",
    });
    const [saving, setSaving] = useState(false);
    const [saveOk, setSaveOk] = useState(false);

    // Compat result parsed from cfg.compat_json
    const [compat, setCompat] = useState<CompatResult | null>(null);

    // Evaluate compat task state
    const [evalRunning, setEvalRunning] = useState(false);
    const [evalLog, setEvalLog] = useState<string[]>([]);
    const evalLogRef = useRef<HTMLDivElement>(null);

    // Download model task state
    const [dlRunning, setDlRunning] = useState(false);
    const [dlProgress, setDlProgress] = useState(0);
    const [dlLog, setDlLog] = useState<string[]>([]);

    // Load config on mount
    useEffect(() => {
        apiFetch<FasterWhisperConfig>("/api/v1/FasterWhisperConfigInfoRoute", { method: "GET" }, { silent: true })
            .then(res => {
                if (res.data) setCfg(res.data);
            })
            .catch(() => {});
    }, []);

    // Parse compat JSON whenever cfg changes
    useEffect(() => {
        const parsed = json_parse<CompatResult>(cfg.compat_json);
        if (parsed && typeof parsed === "object" && "model_support" in parsed) {
            setCompat(parsed);
        } else {
            setCompat(null);
        }
    }, [cfg.compat_json]);

    // Auto-scroll eval log
    useEffect(() => {
        if (evalLogRef.current) {
            evalLogRef.current.scrollTop = evalLogRef.current.scrollHeight;
        }
    }, [evalLog]);

    const saveConfig = async () => {
        setSaving(true);
        setSaveOk(false);
        try {
            await apiFetch("/api/v1/AppConfigSaveRoute", {
                method: "POST",
                body: JSON.stringify({
                    faster_whisper_model: cfg.model,
                    faster_whisper_compute_type: cfg.compute_type,
                    faster_whisper_device: cfg.device,
                    faster_whisper_models_dir: cfg.models_dir,
                }),
            });
            setSaveOk(true);
            setTimeout(() => setSaveOk(false), 2000);
        } catch {
        } finally {
            setSaving(false);
        }
    };

    const runEvalCompat = async () => {
        if (evalRunning) return;
        setEvalRunning(true);
        setEvalLog(["开始兼容性评估…"]);
        try {
            const raw = await callBridge("run_faster_whisper_compat_eval", "{}");
            const res = json_parse<any>(raw);
            if (res?.error) {
                setEvalLog(prev => [...prev, `错误: ${res.error}`]);
                setEvalRunning(false);
                return;
            }
            const taskId = res?.task_id;
            if (!taskId) {
                setEvalLog(prev => [...prev, "创建任务失败"]);
                return;
            }
            setEvalLog(prev => [...prev, `任务已创建 taskId=${taskId}，等待执行…`]);

            // Poll task status
            const poll = setInterval(async () => {
                try {
                    const r = await apiFetch(
                        `/api/v1/WorkerTaskListRoute?id=${encodeURIComponent(taskId)}`,
                        { method: "GET" }, { silent: true },
                    );
                    const items = (r.data as any)?.items as any[];
                    const task = items?.[0];
                    if (!task) return;

                    if (task.status === "completed") {
                        clearInterval(poll);
                        setEvalLog(prev => [...prev, "评估完成，正在刷新配置…"]);
                        const cfgRes = await apiFetch<FasterWhisperConfig>("/api/v1/FasterWhisperConfigInfoRoute", { method: "GET" }, { silent: true });
                        if (cfgRes.data) setCfg(cfgRes.data);
                        setEvalLog(prev => [...prev, "✓ 兼容性数据已更新"]);
                        setEvalRunning(false);
                    } else if (task.status === "failed") {
                        clearInterval(poll);
                        setEvalLog(prev => [...prev, `✗ 评估失败: ${task.error ?? "未知错误"}`]);
                        setEvalRunning(false);
                    } else if (task.status === "cancelled") {
                        clearInterval(poll);
                        setEvalLog(prev => [...prev, "已取消"]);
                        setEvalRunning(false);
                    } else {
                        setEvalLog(prev => {
                            const last = prev[prev.length - 1];
                            const msg = `运行中… progress=${task.progress}%`;
                            return last.startsWith("运行中") ? [...prev.slice(0, -1), msg] : [...prev, msg];
                        });
                    }
                } catch {}
            }, 2000);
        } catch (e: any) {
            if (e instanceof BridgeUnavailableError) { setEvalLog(prev => [...prev, "仅支持在桌面应用内运行"]); setEvalRunning(false); return; }
            setEvalLog(prev => [...prev, `错误: ${e?.message ?? e}`]);
            setEvalRunning(false);
        }
    };

    const downloadModel = async () => {
        if (dlRunning || !cfg.model) return;
        setDlRunning(true);
        setDlProgress(0);
        setDlLog([`开始下载模型 ${cfg.model}…`]);
        try {
            const raw = await callBridge("run_faster_whisper_model_download", JSON.stringify({ model_name: cfg.model }));
            const res = json_parse<any>(raw);
            if (res?.error) {
                setDlLog(prev => [...prev, `错误: ${res.error}`]);
                setDlRunning(false);
                return;
            }
            const taskId = res?.task_id;
            if (!taskId) {
                setDlLog(prev => [...prev, "创建任务失败"]);
                return;
            }
            setDlLog(prev => [...prev, `任务已创建 taskId=${taskId}`]);

            const poll = setInterval(async () => {
                try {
                    const r = await apiFetch(
                        `/api/v1/WorkerTaskListRoute?id=${encodeURIComponent(taskId)}`,
                        { method: "GET" }, { silent: true },
                    );
                    const items = (r.data as any)?.items as any[];
                    const task = items?.[0];
                    if (!task) return;
                    setDlProgress(task.progress ?? 0);

                    if (task.status === "completed") {
                        clearInterval(poll);
                        setDlProgress(100);
                        setDlLog(prev => [...prev, "✓ 下载完成"]);
                        setDlRunning(false);
                    } else if (task.status === "failed") {
                        clearInterval(poll);
                        setDlLog(prev => [...prev, `✗ 下载失败: ${task.error ?? "未知错误"}`]);
                        setDlRunning(false);
                    } else if (task.status === "cancelled") {
                        clearInterval(poll);
                        setDlLog(prev => [...prev, "已取消"]);
                        setDlRunning(false);
                    }
                } catch {}
            }, 1500);
        } catch (e: any) {
            if (e instanceof BridgeUnavailableError) { setDlLog(prev => [...prev, "仅支持在桌面应用内运行"]); setDlRunning(false); return; }
            setDlLog(prev => [...prev, `错误: ${e?.message ?? e}`]);
            setDlRunning(false);
        }
    };

    return (
        <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", padding: "24px" }}>
            <div style={{ maxWidth: "680px", margin: "0 auto" }}>

                {/* Header */}
                <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "28px" }}>
                    <button
                        onClick={() => navigate(-1)}
                        style={{ background: "none", border: "none", cursor: "pointer", padding: "4px", color: "#6b7280", display: "flex" }}
                    >
                        <ArrowLeft size={20} />
                    </button>
                    <div>
                        <h1 style={{ margin: 0, fontSize: "20px", fontWeight: 700, color: "#111827" }}>本地语音识别（ASR）</h1>
                        <p style={{ margin: "2px 0 0", fontSize: "13px", color: "#9ca3af" }}>配置 faster-whisper 模型参数</p>
                    </div>
                </div>

                {/* Model selection */}
                <Section title="模型配置">
                    <Field label="faster-whisper 模型" desc="选择用于语音识别的模型，模型越大精度越高但需要更多显存。">
                        <select
                            value={cfg.model}
                            onChange={e => setCfg(c => ({ ...c, model: e.target.value }))}
                            style={selectStyle}
                        >
                            <option value="">— 未选择 —</option>
                            {WHISPER_MODELS.map(m => (
                                <option key={m} value={m}>
                                    {m}{compat?.local_models?.includes(m) ? " ✓ 已下载" : ""}
                                    {compat?.model_support?.[m]?.supported === false ? " (不支持)" : ""}
                                </option>
                            ))}
                        </select>
                    </Field>
                    <Field label="计算精度" desc="float16 最快（需 CUDA），int8 兼容性最好，auto 自动选择。">
                        <select
                            value={cfg.compute_type}
                            onChange={e => setCfg(c => ({ ...c, compute_type: e.target.value }))}
                            style={selectStyle}
                        >
                            {COMPUTE_TYPES.map(ct => (
                                <option key={ct} value={ct}>
                                    {ct}{compat?.compute_types?.[ct]?.supported === false ? " (不支持)" : ""}
                                </option>
                            ))}
                        </select>
                    </Field>
                    <Field label="运行设备" desc="auto 自动检测，cuda 强制 GPU，cpu 强制 CPU。">
                        <select
                            value={cfg.device}
                            onChange={e => setCfg(c => ({ ...c, device: e.target.value }))}
                            style={selectStyle}
                        >
                            {DEVICES.map(d => <option key={d} value={d}>{d}</option>)}
                        </select>
                    </Field>
                    <Field label="模型缓存目录" desc="留空使用 HuggingFace 默认缓存目录（~/.cache/huggingface/hub）。">
                        <input
                            type="text"
                            value={cfg.models_dir}
                            onChange={e => setCfg(c => ({ ...c, models_dir: e.target.value }))}
                            placeholder="留空使用默认路径"
                            style={inputStyle}
                        />
                    </Field>

                    <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "8px" }}>
                        <button onClick={saveConfig} disabled={saving} style={primaryBtnStyle}>
                            {saving ? <Loader size={14} className="animate-spin" /> : saveOk ? <CheckCircle size={14} /> : null}
                            {saveOk ? "已保存" : "保存配置"}
                        </button>
                    </div>
                </Section>

                {/* Compatibility evaluation */}
                <Section title="GPU 兼容性评估">
                    <p style={{ margin: "0 0 12px", fontSize: "13px", color: "#6b7280" }}>
                        评估本机 GPU 对各 compute_type 和模型的支持情况，结果将自动填入上方推荐配置。
                    </p>

                    {compat && (
                        <CompatTable compat={compat} />
                    )}

                    <button onClick={runEvalCompat} disabled={evalRunning} style={{ ...primaryBtnStyle, marginTop: "12px" }}>
                        {evalRunning ? <Loader size={14} /> : <Cpu size={14} />}
                        {evalRunning ? "评估中…" : "运行兼容性评估"}
                    </button>

                    {evalLog.length > 0 && (
                        <div ref={evalLogRef} style={logBoxStyle}>
                            {evalLog.map((line, i) => <div key={i} style={{ fontSize: "12px", color: "#d1d5db" }}>{line}</div>)}
                        </div>
                    )}
                </Section>

                {/* Download model */}
                <Section title="下载模型">
                    <p style={{ margin: "0 0 12px", fontSize: "13px", color: "#6b7280" }}>
                        将选定的模型下载到本地缓存目录。已下载的模型会自动跳过。
                    </p>
                    {!cfg.model && (
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", color: "#d97706", marginBottom: "12px" }}>
                            <AlertTriangle size={14} />
                            请先在上方选择模型
                        </div>
                    )}
                    {dlProgress > 0 && dlProgress < 100 && (
                        <div style={{ marginBottom: "12px" }}>
                            <div style={{ display: "flex", justifyContent: "space-between", fontSize: "12px", color: "#6b7280", marginBottom: "4px" }}>
                                <span>下载进度</span><span>{dlProgress}%</span>
                            </div>
                            <div style={{ height: "6px", backgroundColor: "#e5e7eb", borderRadius: "3px", overflow: "hidden" }}>
                                <div style={{ height: "100%", width: `${dlProgress}%`, backgroundColor: "#3b82f6", transition: "width 0.3s" }} />
                            </div>
                        </div>
                    )}
                    <button onClick={downloadModel} disabled={dlRunning || !cfg.model} style={primaryBtnStyle}>
                        {dlRunning ? <Loader size={14} /> : <Download size={14} />}
                        {dlRunning ? "下载中…" : `下载 ${cfg.model || "（未选择）"}`}
                    </button>
                    {dlLog.length > 0 && (
                        <div style={logBoxStyle}>
                            {dlLog.map((line, i) => <div key={i} style={{ fontSize: "12px", color: "#d1d5db" }}>{line}</div>)}
                        </div>
                    )}
                </Section>

            </div>
        </div>
    );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function Section({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "20px", marginBottom: "20px" }}>
            <h2 style={{ margin: "0 0 16px", fontSize: "15px", fontWeight: 600, color: "#111827" }}>{title}</h2>
            {children}
        </div>
    );
}

function Field({ label, desc, children }: { label: string; desc: string; children: React.ReactNode }) {
    return (
        <div style={{ marginBottom: "16px" }}>
            <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>{label}</label>
            <p style={{ margin: "0 0 6px", fontSize: "12px", color: "#9ca3af" }}>{desc}</p>
            {children}
        </div>
    );
}

function CompatTable({ compat }: { compat: CompatResult }) {
    const models = ["tiny", "base", "small", "medium", "large-v2", "large-v3"];
    return (
        <div style={{ overflowX: "auto", marginBottom: "8px" }}>
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "12px" }}>
                <thead>
                    <tr style={{ borderBottom: "1px solid #e5e7eb" }}>
                        <th style={thStyle}>模型</th>
                        <th style={thStyle}>支持</th>
                        <th style={thStyle}>显存</th>
                        <th style={thStyle}>本地</th>
                    </tr>
                </thead>
                <tbody>
                    {models.map(m => {
                        const s = compat.model_support?.[m];
                        const isLocal = compat.local_models?.includes(m);
                        return (
                            <tr key={m} style={{ borderBottom: "1px solid #f3f4f6" }}>
                                <td style={tdStyle}>{m}</td>
                                <td style={tdStyle}>
                                    {s == null ? <span style={{ color: "#9ca3af" }}>—</span>
                                        : s.supported
                                            ? <CheckCircle size={13} color="#22c55e" />
                                            : <XCircle size={13} color="#ef4444" />}
                                </td>
                                <td style={tdStyle}>{s?.vram_mb ? `${s.vram_mb} MB` : "—"}</td>
                                <td style={tdStyle}>{isLocal ? <CheckCircle size={13} color="#22c55e" /> : "—"}</td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const selectStyle: React.CSSProperties = {
    width: "100%", padding: "8px 10px", fontSize: "13px",
    border: "1px solid #d1d5db", borderRadius: "6px", backgroundColor: "#fff", color: "#111827",
};

const inputStyle: React.CSSProperties = {
    width: "100%", padding: "8px 10px", fontSize: "13px",
    border: "1px solid #d1d5db", borderRadius: "6px", backgroundColor: "#fff", color: "#111827",
    boxSizing: "border-box",
};

const primaryBtnStyle: React.CSSProperties = {
    display: "inline-flex", alignItems: "center", gap: "6px",
    padding: "8px 16px", fontSize: "13px", fontWeight: 500,
    color: "#fff", backgroundColor: "#3b82f6", border: "none",
    borderRadius: "8px", cursor: "pointer",
};

const logBoxStyle: React.CSSProperties = {
    marginTop: "10px", padding: "10px 12px",
    backgroundColor: "#1f2937", borderRadius: "8px",
    maxHeight: "140px", overflowY: "auto",
};

const thStyle: React.CSSProperties = {
    padding: "6px 8px", textAlign: "left", fontWeight: 600,
    color: "#6b7280", fontSize: "11px", textTransform: "uppercase",
};

const tdStyle: React.CSSProperties = {
    padding: "6px 8px", color: "#374151",
};
