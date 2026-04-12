import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, Save, CheckCircle, FolderOpen } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting-faster-whisper-asr-config";
import { useAppFetch } from "~/util/app_fetch";
import { callBridge, callBridgeOrNull, BridgeUnavailableError } from "~/util/bridge";
import { print_error, reportHttpError } from "~/util/error_handler";
import { json_parse } from "~/util/json";
import { ALL_WHISPER_MODELS, WHISPER_MODEL_VRAM_HINT } from "~/util/asrConfig";

export function meta({}: Route.MetaArgs) {
    return [{ title: "ASR 模型配置 - Fredica" }];
}

// ─── Types ───────────────────────────────────────────────────────────────────

/** 完整配置（仅 jsBridge 返回） */
interface AsrConfig {
    allow_download: boolean;
    disallowed_models: string;
    test_audio_path: string;
    test_wave_count: number;
}

/** 公开配置（HTTP route 返回，不含服主测试参数） */
interface AsrConfigPublic {
    allow_download: boolean;
    disallowed_models: string;
}

// ─── Model grouping by VRAM ──────────────────────────────────────────────────

interface ModelGroup {
    label: string;
    vramHint: string;
    models: string[];
}

const MODEL_GROUPS: ModelGroup[] = [
    {
        label: "Tiny / Base",
        vramHint: "~1 GB",
        models: ALL_WHISPER_MODELS.filter(m => {
            const v = WHISPER_MODEL_VRAM_HINT[m];
            return v !== undefined && v <= 1;
        }),
    },
    {
        label: "Small",
        vramHint: "~1-2 GB",
        models: ALL_WHISPER_MODELS.filter(m => {
            const v = WHISPER_MODEL_VRAM_HINT[m];
            return v !== undefined && v > 1 && v <= 2;
        }),
    },
    {
        label: "Distil / Turbo",
        vramHint: "~2-3 GB",
        models: ALL_WHISPER_MODELS.filter(m => {
            const v = WHISPER_MODEL_VRAM_HINT[m];
            return v !== undefined && v > 2 && v <= 3;
        }),
    },
    {
        label: "Medium",
        vramHint: "~5 GB",
        models: ALL_WHISPER_MODELS.filter(m => {
            const v = WHISPER_MODEL_VRAM_HINT[m];
            return v !== undefined && v > 3 && (m.startsWith("medium"));
        }),
    },
    {
        label: "Large",
        vramHint: "~5 GB",
        models: ALL_WHISPER_MODELS.filter(m => {
            const v = WHISPER_MODEL_VRAM_HINT[m];
            return v !== undefined && v > 3 && (m.startsWith("large"));
        }),
    },
];

// ─── Page ────────────────────────────────────────────────────────────────────

export default function AsrConfigPage() {
    const navigate = useNavigate();
    const { apiFetch } = useAppFetch();

    // state
    const [allowDownload, setAllowDownload] = useState(true);
    const [disabledModels, setDisabledModels] = useState<Set<string>>(new Set());
    const [testAudioPath, setTestAudioPath] = useState("");
    const [testWaveCount, setTestWaveCount] = useState(10);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [canSelectFile, setCanSelectFile] = useState(true);

    // ── Load config on mount ─────────────────────────────────────────────────

    useEffect(() => {
        const abort = new AbortController();
        loadConfig(abort.signal);
        return () => abort.abort();
    }, []);

    async function loadConfig(signal?: AbortSignal) {
        setLoading(true);
        try {
            // Try bridge first (desktop WebView)
            const raw = await callBridgeOrNull("get_asr_config");
            if (raw) {
                const cfg = json_parse<AsrConfig>(raw);
                applyConfig(cfg);
                setLoading(false);
                return;
            }
            // Fallback to HTTP API (browser dev mode)
            setCanSelectFile(false); // file selector only works in desktop
            const { resp, data } = await apiFetch<AsrConfig>(
                "/api/v1/AsrConfigGetRoute",
                undefined,
                { signal },
            );
            if (signal?.aborted) return;
            if (!resp.ok) {
                reportHttpError("加载 ASR 配置失败", resp);
                setLoading(false);
                return;
            }
            if (data) applyConfig(data);
        } catch (e) {
            if (e instanceof DOMException && e.name === "AbortError") return;
            print_error({ reason: "加载 ASR 配置异常", err: e });
        } finally {
            setLoading(false);
        }
    }

    function applyConfig(cfg: AsrConfig) {
        setAllowDownload(cfg.allow_download);
        const trimmed = cfg.disallowed_models.trim();
        if (trimmed) {
            setDisabledModels(new Set(trimmed.split(",").map(s => s.trim()).filter(Boolean)));
        } else {
            setDisabledModels(new Set());
        }
        setTestAudioPath(cfg.test_audio_path);
        setTestWaveCount(cfg.test_wave_count);
    }

    // ── Save config ──────────────────────────────────────────────────────────

    async function handleSave() {
        if (saving) return;
        setSaving(true);
        setSaved(false);
        try {
            const param = {
                allow_download: allowDownload,
                disallowed_models: Array.from(disabledModels).join(","),
                test_audio_path: testAudioPath,
                test_wave_count: testWaveCount,
            };
            // ASR 配置只能通过 jsBridge 保存（仅服主桌面端可用）
            const raw = await callBridge("save_asr_config", JSON.stringify(param));
            const res = json_parse<AsrConfig & { error?: string }>(raw);
            if (res?.error) {
                print_error({ reason: `保存 ASR 配置失败: ${res.error}` });
                return;
            }
            setSaved(true);
            setTimeout(() => setSaved(false), 2000);
        } catch (e) {
            if (e instanceof BridgeUnavailableError) {
                print_error({ reason: "保存 ASR 配置仅限桌面端（服主）操作" });
                return;
            }
            print_error({ reason: "保存 ASR 配置异常", err: e });
        } finally {
            setSaving(false);
        }
    }

    // ── Select audio file (desktop only) ─────────────────────────────────────

    async function handleSelectAudioFile() {
        try {
            const raw = await callBridge("select_audio_file");
            const res = json_parse<{ path: string | null }>(raw);
            if (res?.path) setTestAudioPath(res.path);
        } catch (e) {
            if (e instanceof BridgeUnavailableError) {
                setCanSelectFile(false);
                return;
            }
            print_error({ reason: "选择音频文件失败", err: e });
        }
    }

    // ── Toggle model in blacklist ────────────────────────────────────────────

    function toggleModel(model: string) {
        setDisabledModels(prev => {
            const next = new Set(prev);
            if (next.has(model)) next.delete(model);
            else next.add(model);
            return next;
        });
    }

    // ── Render ───────────────────────────────────────────────────────────────

    if (loading) {
        return (
            <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", padding: "24px", display: "flex", alignItems: "center", justifyContent: "center" }}>
                <p style={{ fontSize: "14px", color: "#9ca3af" }}>加载中...</p>
            </div>
        );
    }

    return (
        <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", padding: "24px" }}>
            <div style={{ maxWidth: "720px", margin: "0 auto" }}>

                {/* Header */}
                <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "28px" }}>
                    <button onClick={() => navigate(-1)} style={{ background: "none", border: "none", cursor: "pointer", padding: "4px", color: "#6b7280", display: "flex" }}>
                        <ArrowLeft size={20} />
                    </button>
                    <div>
                        <h1 style={{ margin: 0, fontSize: "20px", fontWeight: 700, color: "#111827" }}>ASR 模型配置</h1>
                        <p style={{ margin: "2px 0 0", fontSize: "13px", color: "#9ca3af" }}>管理 faster-whisper 模型的下载权限与测试参数</p>
                    </div>
                </div>

                {/* Card 1: 权限配置 */}
                <div style={cardStyle}>
                    <p style={{ margin: "0 0 16px", fontSize: "15px", fontWeight: 600, color: "#111827" }}>权限配置</p>

                    {/* 允许下载 toggle */}
                    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "8px" }}>
                        <div>
                            <p style={{ margin: 0, fontSize: "14px", fontWeight: 500, color: "#111827" }}>允许在线下载模型</p>
                            <p style={{ margin: "2px 0 0", fontSize: "12px", color: "#9ca3af" }}>启用后，ASR 任务可自动下载缺失的模型文件。</p>
                        </div>
                        <button
                            onClick={() => setAllowDownload(v => !v)}
                            style={{
                                width: "44px",
                                height: "24px",
                                borderRadius: "12px",
                                border: "none",
                                cursor: "pointer",
                                backgroundColor: allowDownload ? "#3b82f6" : "#d1d5db",
                                position: "relative",
                                flexShrink: 0,
                                transition: "background-color 0.2s",
                            }}
                        >
                            <div style={{
                                width: "18px",
                                height: "18px",
                                borderRadius: "50%",
                                backgroundColor: "#fff",
                                position: "absolute",
                                top: "3px",
                                left: allowDownload ? "23px" : "3px",
                                transition: "left 0.2s",
                                boxShadow: "0 1px 3px rgba(0,0,0,0.15)",
                            }} />
                        </button>
                    </div>

                    <div style={{ borderTop: "1px solid #f3f4f6", margin: "16px 0" }} />

                    {/* 模型黑名单 */}
                    <div>
                        <p style={{ margin: "0 0 4px", fontSize: "14px", fontWeight: 500, color: "#111827" }}>模型黑名单</p>
                        <p style={{ margin: "0 0 12px", fontSize: "12px", color: "#9ca3af" }}>勾选的模型将被禁用，不会出现在模型选择列表中。</p>

                        <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                            {MODEL_GROUPS.map(group => (
                                <div key={group.label} style={{
                                    border: "1px solid #e5e7eb",
                                    borderRadius: "8px",
                                    overflow: "hidden",
                                }}>
                                    <div style={{
                                        padding: "6px 12px",
                                        backgroundColor: "#f9fafb",
                                        borderBottom: "1px solid #e5e7eb",
                                        display: "flex",
                                        alignItems: "center",
                                        justifyContent: "space-between",
                                    }}>
                                        <span style={{ fontSize: "12px", fontWeight: 600, color: "#374151" }}>{group.label}</span>
                                        <span style={{ fontSize: "11px", color: "#9ca3af" }}>{group.vramHint}</span>
                                    </div>
                                    <div style={{
                                        padding: "8px 12px",
                                        display: "flex",
                                        flexWrap: "wrap",
                                        gap: "6px 16px",
                                    }}>
                                        {group.models.map(model => {
                                            const isDisabled = disabledModels.has(model);
                                            return (
                                                <label key={model} style={{
                                                    display: "flex",
                                                    alignItems: "center",
                                                    gap: "6px",
                                                    cursor: "pointer",
                                                    fontSize: "13px",
                                                    color: isDisabled ? "#ef4444" : "#374151",
                                                    padding: "2px 0",
                                                }}>
                                                    <input
                                                        type="checkbox"
                                                        checked={isDisabled}
                                                        onChange={() => toggleModel(model)}
                                                        style={{ accentColor: "#ef4444" }}
                                                    />
                                                    {model}
                                                </label>
                                            );
                                        })}
                                    </div>
                                </div>
                            ))}
                        </div>

                        <p style={{ margin: "8px 0 0", fontSize: "12px", color: "#6b7280" }}>
                            已禁用 {disabledModels.size} / 共 {ALL_WHISPER_MODELS.length} 个模型
                        </p>
                    </div>
                </div>

                {/* Card 2: 模型测试参数 */}
                <div style={cardStyle}>
                    <p style={{ margin: "0 0 16px", fontSize: "15px", fontWeight: 600, color: "#111827" }}>模型测试参数</p>

                    {/* 测试音频文件 */}
                    <div style={{ marginBottom: "16px" }}>
                        <p style={{ margin: "0 0 4px", fontSize: "13px", fontWeight: 500, color: "#374151" }}>测试音频文件</p>
                        <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
                            <input
                                type="text"
                                value={testAudioPath}
                                onChange={e => setTestAudioPath(e.target.value)}
                                placeholder="选择或输入音频文件路径"
                                style={{ ...inputStyle, flex: 1 }}
                            />
                            <button
                                onClick={handleSelectAudioFile}
                                disabled={!canSelectFile}
                                title={canSelectFile ? "选择文件" : "仅桌面端可用"}
                                style={{
                                    ...secondaryBtnStyle,
                                    opacity: canSelectFile ? 1 : 0.4,
                                    cursor: canSelectFile ? "pointer" : "not-allowed",
                                }}
                            >
                                <FolderOpen size={14} />
                                选择文件
                            </button>
                        </div>
                        {!canSelectFile && (
                            <p style={{ margin: "4px 0 0", fontSize: "11px", color: "#9ca3af" }}>文件选择仅桌面端可用，浏览器环境请手动输入路径。</p>
                        )}
                    </div>

                    {/* 测试重复次数 */}
                    <div style={{ marginBottom: "16px" }}>
                        <p style={{ margin: "0 0 4px", fontSize: "13px", fontWeight: 500, color: "#374151" }}>测试重复次数</p>
                        <input
                            type="number"
                            value={testWaveCount}
                            onChange={e => {
                                const v = parseInt(e.target.value);
                                if (!isNaN(v) && v >= 1 && v <= 100) setTestWaveCount(v);
                            }}
                            min={1}
                            max={100}
                            style={{ ...inputStyle, width: "120px" }}
                        />
                        <p style={{ margin: "4px 0 0", fontSize: "11px", color: "#9ca3af" }}>每个模型对测试音频执行的推理次数（1-100）</p>
                    </div>

                    {/* Placeholder test button */}
                    <button
                        disabled
                        style={{
                            ...primaryBtnStyle,
                            opacity: 0.4,
                            cursor: "not-allowed",
                        }}
                    >
                        开始测试
                    </button>
                    <p style={{ margin: "4px 0 0", fontSize: "11px", color: "#9ca3af" }}>模型测试功能将在后续版本中上线</p>
                </div>

                {/* Save button */}
                <div style={{ display: "flex", justifyContent: "center", marginTop: "8px" }}>
                    <button
                        onClick={handleSave}
                        disabled={saving}
                        style={{
                            ...primaryBtnStyle,
                            width: "200px",
                            justifyContent: "center",
                            opacity: saving ? 0.6 : 1,
                        }}
                    >
                        {saved ? (
                            <>
                                <CheckCircle size={14} />
                                已保存
                            </>
                        ) : (
                            <>
                                <Save size={14} />
                                {saving ? "保存中..." : "保存配置"}
                            </>
                        )}
                    </button>
                </div>

            </div>
        </div>
    );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const cardStyle: React.CSSProperties = {
    backgroundColor: "#fff",
    border: "1px solid #e5e7eb",
    borderRadius: "12px",
    padding: "20px",
    marginBottom: "20px",
};

const inputStyle: React.CSSProperties = {
    width: "100%",
    padding: "8px 10px",
    fontSize: "13px",
    border: "1px solid #d1d5db",
    borderRadius: "6px",
    backgroundColor: "#fff",
    color: "#111827",
    boxSizing: "border-box",
};

const primaryBtnStyle: React.CSSProperties = {
    display: "inline-flex",
    alignItems: "center",
    gap: "6px",
    padding: "8px 16px",
    fontSize: "13px",
    fontWeight: 500,
    color: "#fff",
    backgroundColor: "#3b82f6",
    border: "none",
    borderRadius: "8px",
    cursor: "pointer",
};

const secondaryBtnStyle: React.CSSProperties = {
    display: "inline-flex",
    alignItems: "center",
    gap: "6px",
    padding: "7px 14px",
    fontSize: "13px",
    fontWeight: 500,
    color: "#374151",
    backgroundColor: "#f3f4f6",
    border: "1px solid #e5e7eb",
    borderRadius: "8px",
    cursor: "pointer",
};
