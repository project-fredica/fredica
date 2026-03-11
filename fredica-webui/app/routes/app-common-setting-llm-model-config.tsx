import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, Plus, Pencil, Trash2, Save, X, ExternalLink, Compass, FlaskConical, Download, Upload, Copy, Eye, EyeOff } from "lucide-react";
import type { Route } from "./+types/app-common-setting-llm-model-config";
import { print_error } from "../util/error_handler";
import { openExternalUrl, callBridge, BridgeUnavailableError } from "../util/bridge";
import { llmChat, type LlmChatParams, type LlmChatConnectionConfig } from "../util/llm";
import { useAppConfig } from "~/context/appConfig";
import { DEFAULT_SERVER_PORT } from "../util/app_fetch";

export function meta({ }: Route.MetaArgs) {
    return [{ title: "LLM 模型配置 - Fredica" }];
}

// ─── 类型定义 ────────────────────────────────────────────────────────────────

type LlmCapability = "VISION" | "JSON_SCHEMA" | "MCP" | "FUNCTION_CALLING" | "LONG_CONTEXT";
type LlmProviderType = "OPENAI_COMPATIBLE";

interface LlmModelConfig {
    id: string;
    name: string;
    provider_type?: LlmProviderType;
    base_url: string;
    api_key: string;
    model: string;
    capabilities?: LlmCapability[] | null;
    context_window?: number;
    max_output_tokens?: number;
    temperature?: number;
    notes?: string;
    sort_order?: number;
    app_model_id?: string;
}

interface LlmDefaultRoles {
    chat_model_id?: string;
    vision_model_id?: string;
    coding_model_id?: string;
    dev_test_model_id?: string;
}

// ─── 常量 ────────────────────────────────────────────────────────────────────

const CAPABILITY_META: Record<LlmCapability, { label: string; color: string; textColor: string; desc: string }> = {
    VISION: { label: "图像识别", color: "#dbeafe", textColor: "#1e40af", desc: "支持图像输入（base64/URL）" },
    JSON_SCHEMA: { label: "结构化输出", color: "#f3e8ff", textColor: "#7e22ce", desc: "支持 response_format: json_schema" },
    FUNCTION_CALLING: { label: "函数调用", color: "#fef9c3", textColor: "#713f12", desc: "支持 OpenAI tools/functions 格式" },
    LONG_CONTEXT: { label: "长上下文", color: "#fce7f3", textColor: "#9d174d", desc: "上下文窗口 ≥ 128K tokens" },
    MCP: { label: "MCP", color: "#fee2e2", textColor: "#991b1b", desc: "支持 Model Context Protocol" },
};

const ALL_CAPABILITIES: LlmCapability[] = ["VISION", "JSON_SCHEMA", "FUNCTION_CALLING", "LONG_CONTEXT", "MCP"];

const EMPTY_MODEL: LlmModelConfig = {
    id: "", name: "", provider_type: "OPENAI_COMPATIBLE",
    base_url: "", api_key: "", model: "",
    capabilities: [], context_window: undefined, max_output_tokens: undefined, temperature: undefined, notes: "", app_model_id: "",
};

const EMPTY_ROLES: LlmDefaultRoles = {
    chat_model_id: "", vision_model_id: "", coding_model_id: "", dev_test_model_id: "",
};

// ─── Bridge 工具 ──────────────────────────────────────────────────────────────

function bridgeCall(method: string, param: string): Promise<any> {
    return callBridge(method, param).then(result => JSON.parse(result));
}

// ─── 主组件 ──────────────────────────────────────────────────────────────────

const placeholderStyle = `
.llm-input::placeholder { color: #c0c4cc; }
`;

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();
    const [models, setModels] = useState<LlmModelConfig[]>([]);
    const [roles, setRoles] = useState<LlmDefaultRoles>(EMPTY_ROLES);
    const [testConfig, setTestConfig] = useState({ api_key: "", base_url: "", model: "" });
    const [editingModel, setEditingModel] = useState<LlmModelConfig | null>(null);
    const [isNew, setIsNew] = useState(false);
    const [showDiscover, setShowDiscover] = useState(false);
    const [testingModel, setTestingModel] = useState<LlmModelConfig | null>(null);
    const [exportJson, setExportJson] = useState<string | null>(null);
    const [rolesSaved, setRolesSaved] = useState(false);
    const [testSaved, setTestSaved] = useState(false);

    useEffect(() => {
        bridgeCall("get_llm_models", "{}").then(v => { if (Array.isArray(v)) setModels(v); }).catch(err => print_error({ reason: "加载模型列表失败", err }));
        bridgeCall("get_llm_default_roles", "{}").then(v => { if (v && typeof v === "object" && !Array.isArray(v)) setRoles(v); }).catch(err => print_error({ reason: "加载默认角色失败", err }));
        bridgeCall("get_app_config", "{}").then(cfg => {
            setTestConfig({
                api_key: cfg.llm_test_api_key ?? "",
                base_url: cfg.llm_test_base_url ?? "",
                model: cfg.llm_test_model ?? "",
            });
        }).catch(err => print_error({ reason: "加载测试配置失败", err }));
    }, []);

    const handleSaveModel = async (m: LlmModelConfig) => {
        try {
            const updated = await bridgeCall("save_llm_model", JSON.stringify(m));
            if (Array.isArray(updated)) setModels(updated);
            setEditingModel(null);
        } catch (err) { print_error({ reason: "保存模型失败", err }); }
    };

    const handleDeleteModel = async (id: string) => {
        if (!confirm("确认删除该模型配置？")) return;
        try {
            const updated = await bridgeCall("delete_llm_model", JSON.stringify({ id }));
            if (Array.isArray(updated)) setModels(updated);
        } catch (err) { print_error({ reason: "删除模型失败", err }); }
    };

    const handleSaveRoles = async () => {
        try {
            await bridgeCall("save_llm_default_roles", JSON.stringify(roles));
            setRolesSaved(true);
            setTimeout(() => setRolesSaved(false), 2000);
        } catch (err) { print_error({ reason: "保存默认角色失败", err }); }
    };

    const handleSaveTestConfig = async () => {
        try {
            await bridgeCall("save_app_config", JSON.stringify({
                llm_test_api_key: testConfig.api_key,
                llm_test_base_url: testConfig.base_url,
                llm_test_model: testConfig.model,
            }));
            setTestSaved(true);
            setTimeout(() => setTestSaved(false), 2000);
        } catch (err) { print_error({ reason: "保存测试配置失败", err }); }
    };

    const handleReorder = async (ids: string[]) => {
        try {
            const updated = await bridgeCall("reorder_llm_models", JSON.stringify({ ids }));
            if (Array.isArray(updated)) setModels(updated);
        } catch (err) { print_error({ reason: "排序保存失败", err }); }
    };

    const handleDuplicate = async (m: LlmModelConfig) => {
        const copy = { ...m, id: crypto.randomUUID(), name: `${m.name} (副本)`, sort_order: (models ?? []).length };
        try {
            const updated = await bridgeCall("save_llm_model", JSON.stringify(copy));
            if (Array.isArray(updated)) setModels(updated);
        } catch (err) { print_error({ reason: "新建重复配置失败", err }); }
    };

    const handleExport = () => {
        setExportJson(JSON.stringify(models ?? [], null, 2));
    };

    const handleImport = (jsonStr: string) => {
        try {
            const parsed = JSON.parse(jsonStr);
            if (!Array.isArray(parsed)) { print_error({ reason: "导入失败：JSON 必须是数组", err: null }); return; }
            // 逐个保存，最后一个完成后刷新列表
            (async () => {
                let updated: LlmModelConfig[] = models ?? [];
                for (const m of parsed) {
                    try {
                        const res = await bridgeCall("save_llm_model", JSON.stringify(m));
                        if (Array.isArray(res)) updated = res;
                    } catch (err) { print_error({ reason: `导入模型 ${m.name ?? m.id} 失败`, err }); }
                }
                setModels(updated);
            })();
        } catch (err) { print_error({ reason: "导入失败：JSON 解析错误", err }); }
    };

    return (
        <>
            <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", fontFamily: "system-ui, sans-serif" }}>
                <style>{placeholderStyle}</style>
                {/* 顶部导航 */}
                <div style={{
                    display: "flex", alignItems: "center", gap: "12px",
                    padding: "16px 24px", backgroundColor: "#fff",
                    borderBottom: "1px solid #e5e7eb", position: "sticky", top: 0, zIndex: 10,
                }}>
                    <button onClick={() => navigate("/app-desktop-setting")} style={{
                        display: "flex", alignItems: "center", gap: "6px",
                        padding: "8px 12px", fontSize: "14px", color: "#374151",
                        backgroundColor: "transparent", border: "1px solid #d1d5db",
                        borderRadius: "8px", cursor: "pointer",
                    }}>
                        <ArrowLeft size={16} /> 返回设置
                    </button>
                    <h1 style={{ fontSize: "18px", fontWeight: 700, color: "#111827", margin: 0 }}>LLM 模型配置</h1>
                </div>

                <div style={{ maxWidth: "760px", margin: "0 auto", padding: "24px 16px" }} className="flex flex-col gap-7">
                    <DefaultRolesSection
                        roles={roles}
                        models={models}
                        onChange={setRoles}
                        onSave={handleSaveRoles}
                        saved={rolesSaved}
                    />

                    <ModelListSection
                        models={models}
                        onAdd={() => { setIsNew(true); setEditingModel({ ...EMPTY_MODEL, id: crypto.randomUUID() }); }}
                        onEdit={(m) => { setIsNew(false); setEditingModel({ ...m }); }}
                        onDelete={handleDeleteModel}
                        onDiscover={() => setShowDiscover(true)}
                        onTest={(m) => setTestingModel(m)}
                        onDuplicate={handleDuplicate}
                        onReorder={handleReorder}
                        onExport={handleExport}
                        onImport={handleImport}
                    />

                    <TestConfigSection
                        config={testConfig}
                        onChange={setTestConfig}
                        onSave={handleSaveTestConfig}
                        saved={testSaved}
                    />
                </div>

                {editingModel && (
                    <ModelEditModal
                        model={editingModel}
                        isNew={isNew}
                        onChange={setEditingModel}
                        onSave={handleSaveModel}
                        onClose={() => setEditingModel(null)}
                        onTest={(m) => setTestingModel(m)}
                    />
                )}
                {showDiscover && <DiscoverModelsModal onClose={() => setShowDiscover(false)} />}
                {testingModel && <ConfigCheckDrawer model={testingModel} onClose={() => setTestingModel(null)} />}
            </div>
            {exportJson !== null && <JsonPreviewModal json={exportJson} onClose={() => setExportJson(null)} />}
        </>
    );
}

// ─── 测试令牌区块 ─────────────────────────────────────────────────────────────

function TestConfigSection({ config, onChange, onSave, saved }: {
    config: { api_key: string; base_url: string; model: string };
    onChange: (c: { api_key: string; base_url: string; model: string }) => void;
    onSave: () => void;
    saved: boolean;
}) {
    const [open, setOpen] = useState(false);
    const [showApiKey, setShowApiKey] = useState(false);
    return (
        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", overflow: "hidden" }}>
            <button onClick={() => setOpen(v => !v)} style={{
                width: "100%", display: "flex", alignItems: "center", justifyContent: "space-between",
                padding: "14px 20px", background: "#f9fafb", border: "none", borderBottom: open ? "1px solid #f3f4f6" : "none",
                cursor: "pointer", fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase" as const, letterSpacing: "0.05em",
            }}>
                测试令牌配置
                <span style={{ fontSize: "12px", color: "#9ca3af" }}>{open ? "▲" : "▼"}</span>
            </button>
            {open && (
                <div style={{ padding: "16px 20px" }}>
                    <p style={{ margin: "0 0 12px 0", fontSize: "12px", color: "#9ca3af" }}>
                        测试令牌仅用于开发阶段验证 SSE 客户端，不参与业务流程。无令牌时 LlmSseClientTest 自动跳过。
                    </p>
                    {[
                        { key: "base_url" as const, label: "Base URL", placeholder: "https://api.openai.com/v1", type: "text" },
                        { key: "model" as const, label: "模型名称", placeholder: "gpt-4o-mini", type: "text" },
                    ].map(({ key, label, placeholder, type }) => (
                        <div key={key} style={{ marginBottom: "10px" }}>
                            <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>{label}</label>
                            <input type={type} value={config[key]} placeholder={placeholder}
                                onChange={e => onChange({ ...config, [key]: e.target.value })}
                                className="llm-input"
                                style={{ width: "100%", padding: "7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                        </div>
                    ))}
                    <div style={{ marginBottom: "10px" }}>
                        <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>API Key</label>
                        <div style={{ position: "relative" }}>
                            <input type={showApiKey ? "text" : "password"} value={config.api_key} placeholder="sk-..."
                                onChange={e => onChange({ ...config, api_key: e.target.value })}
                                className="llm-input"
                                style={{ width: "100%", padding: "7px 36px 7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                            <button type="button" onClick={() => setShowApiKey(v => !v)} style={{
                                position: "absolute", right: "8px", top: "50%", transform: "translateY(-50%)",
                                background: "none", border: "none", cursor: "pointer", color: "#9ca3af", padding: "2px",
                                display: "flex", alignItems: "center",
                            }}>
                                {showApiKey ? <EyeOff size={15} /> : <Eye size={15} />}
                            </button>
                        </div>
                    </div>
                    <button onClick={onSave} style={{
                        display: "flex", alignItems: "center", gap: "6px", padding: "7px 16px",
                        fontSize: "13px", fontWeight: 500, color: "#fff",
                        backgroundColor: saved ? "#16a34a" : "#2563eb", border: "none", borderRadius: "6px", cursor: "pointer",
                    }}>
                        <Save size={13} /> {saved ? "已保存" : "保存测试配置"}
                    </button>
                </div>
            )}
        </div>
    );
}

// ─── 默认角色区块 ─────────────────────────────────────────────────────────────

function DefaultRolesSection({ roles, models, onChange, onSave, saved }: {
    roles: LlmDefaultRoles;
    models?: LlmModelConfig[] | null;
    onChange: (r: LlmDefaultRoles) => void;
    onSave: () => void;
    saved: boolean;
}) {
    const roleFields: Array<{ key: keyof LlmDefaultRoles; label: string }> = [
        { key: "chat_model_id", label: "对话模型" },
        { key: "vision_model_id", label: "画面识别模型" },
        { key: "coding_model_id", label: "编码模型" },
        { key: "dev_test_model_id", label: "开发测试模型" },
    ];
    return (
        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", overflow: "hidden" }}>
            <div style={{ padding: "14px 20px", borderBottom: "1px solid #f3f4f6", backgroundColor: "#f9fafb" }}>
                <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase" as const, letterSpacing: "0.05em" }}>默认角色分配</h2>
            </div>
            <div style={{ padding: "16px 20px" }}>
                <p style={{ margin: "0 0 12px 0", fontSize: "12px", color: "#9ca3af" }}>为不同场景指定默认使用的模型。</p>
                {roleFields.map(({ key, label }) => (
                    <div key={key} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "10px" }}>
                        <label style={{ fontSize: "14px", color: "#374151", fontWeight: 500 }}>{label}</label>
                        <select value={roles[key]} onChange={e => onChange({ ...roles, [key]: e.target.value })}
                            style={{ padding: "6px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", minWidth: "200px", backgroundColor: "#fff" }}>
                            <option value="">— 未指定 —</option>
                            {(models ?? []).map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
                        </select>
                    </div>
                ))}
                <button onClick={onSave} style={{
                    display: "flex", alignItems: "center", gap: "6px", padding: "7px 16px", marginTop: "4px",
                    fontSize: "13px", fontWeight: 500, color: "#fff",
                    backgroundColor: saved ? "#16a34a" : "#2563eb", border: "none", borderRadius: "6px", cursor: "pointer",
                }}>
                    <Save size={13} /> {saved ? "已保存" : "保存默认角色"}
                </button>
            </div>
        </div>
    );
}

// ─── 模型列表区块 ─────────────────────────────────────────────────────────────

function ModelListSection({ models, onAdd, onEdit, onDelete, onDiscover, onTest, onDuplicate, onReorder, onExport, onImport }: {
    models?: LlmModelConfig[] | null;
    onAdd: () => void;
    onEdit: (m: LlmModelConfig) => void;
    onDelete: (id: string) => void;
    onDiscover: () => void;
    onTest: (m: LlmModelConfig) => void;
    onDuplicate: (m: LlmModelConfig) => void;
    onReorder: (ids: string[]) => void;
    onExport: () => void;
    onImport: (json: string) => void;
}) {
    const [dragOverId, setDragOverId] = useState<string | null>(null);
    const dragIdRef = useRef<string | null>(null);
    const importRef = useRef<HTMLInputElement>(null);

    const list = models ?? [];

    const handleDragStart = (id: string) => { dragIdRef.current = id; };
    const handleDragOver = (e: React.DragEvent, id: string) => {
        e.preventDefault();
        setDragOverId(id);
    };
    const handleDrop = (e: React.DragEvent, targetId: string) => {
        e.preventDefault();
        const srcId = dragIdRef.current;
        if (!srcId || srcId === targetId) { setDragOverId(null); return; }
        const ids = list.map(m => m.id);
        const srcIdx = ids.indexOf(srcId);
        const tgtIdx = ids.indexOf(targetId);
        ids.splice(srcIdx, 1);
        ids.splice(tgtIdx, 0, srcId);
        onReorder(ids);
        setDragOverId(null);
        dragIdRef.current = null;
    };
    const handleDragEnd = () => { setDragOverId(null); dragIdRef.current = null; };

    const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = ev => { if (typeof ev.target?.result === "string") onImport(ev.target.result); };
        reader.readAsText(file);
        e.target.value = "";
    };

    return (
        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", overflow: "hidden" }}>
            {/* 头部：标题 + 流式换行按钮组 */}
            <div style={{ padding: "14px 20px", borderBottom: "1px solid #f3f4f6", backgroundColor: "#f9fafb" }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "8px" }}>
                    <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase" as const, letterSpacing: "0.05em" }}>
                        模型列表 ({list.length})
                    </h2>
                    <div style={{ display: "flex", flexWrap: "wrap" as const, gap: "6px" }}>
                        <button onClick={onDiscover} style={{ display: "flex", alignItems: "center", gap: "4px", padding: "5px 10px", fontSize: "12px", fontWeight: 500, color: "#374151", backgroundColor: "#f9fafb", border: "1px solid #d1d5db", borderRadius: "6px", cursor: "pointer" }}>
                            <Compass size={12} /> 发现模型
                        </button>
                        <button onClick={onExport} style={{ display: "flex", alignItems: "center", gap: "4px", padding: "5px 10px", fontSize: "12px", fontWeight: 500, color: "#374151", backgroundColor: "#f9fafb", border: "1px solid #d1d5db", borderRadius: "6px", cursor: "pointer" }}>
                            <Download size={12} /> 导出 JSON
                        </button>
                        <button onClick={() => importRef.current?.click()} style={{ display: "flex", alignItems: "center", gap: "4px", padding: "5px 10px", fontSize: "12px", fontWeight: 500, color: "#374151", backgroundColor: "#f9fafb", border: "1px solid #d1d5db", borderRadius: "6px", cursor: "pointer" }}>
                            <Upload size={12} /> 导入 JSON
                        </button>
                        <input ref={importRef} type="file" accept=".json,application/json" style={{ display: "none" }} onChange={handleImportFile} />
                        <button onClick={onAdd} style={{ display: "flex", alignItems: "center", gap: "4px", padding: "5px 10px", fontSize: "12px", fontWeight: 500, color: "#fff", backgroundColor: "#2563eb", border: "none", borderRadius: "6px", cursor: "pointer" }}>
                            <Plus size={12} /> 添加模型
                        </button>
                    </div>
                </div>
            </div>
            {list.length === 0 ? (
                <div style={{ padding: "32px", textAlign: "center", color: "#9ca3af", fontSize: "14px" }}>
                    暂无模型配置，点击「添加模型」开始配置。
                </div>
            ) : (
                <div>
                    {list.map((m, idx) => (
                        <div key={m.id}
                            draggable
                            onDragStart={() => handleDragStart(m.id)}
                            onDragOver={e => handleDragOver(e, m.id)}
                            onDrop={e => handleDrop(e, m.id)}
                            onDragEnd={handleDragEnd}
                            style={{
                                padding: "14px 20px",
                                borderBottom: idx < list.length - 1 ? "1px solid #f3f4f6" : "none",
                                display: "flex", alignItems: "flex-start", gap: "10px",
                                backgroundColor: dragOverId === m.id ? "#eff6ff" : "transparent",
                                transition: "background-color 0.1s",
                            }}>
                            {/* 拖动手柄 */}
                            <div style={{ cursor: "grab", color: "#d1d5db", paddingTop: "3px", flexShrink: 0, fontSize: "16px", lineHeight: 1 }} title="拖动排序">⠿</div>
                            {/* 模型信息 */}
                            <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "4px" }}>
                                    <span style={{ fontSize: "15px", fontWeight: 600, color: "#111827" }}>{m.name}</span>
                                    <span style={{ fontSize: "11px", color: "#9ca3af", fontFamily: "monospace" }}>{m.model}</span>
                                </div>
                                <div style={{ fontSize: "12px", color: "#6b7280", marginBottom: "6px", wordBreak: "break-all" as const }}>
                                    {m.base_url?.replace(/\/+$/, "")}
                                </div>
                                <div style={{ display: "flex", flexWrap: "wrap" as const, gap: "4px" }}>
                                    {(m.capabilities ?? []).map(cap => {
                                        const meta = CAPABILITY_META[cap];
                                        return (
                                            <span key={cap} style={{ padding: "1px 7px", fontSize: "11px", borderRadius: "4px", backgroundColor: meta.color, color: meta.textColor }}>{meta.label}</span>
                                        );
                                    })}
                                </div>
                            </div>
                            {/* 操作按钮：流式换行 */}
                            <div style={{ display: "flex", flexWrap: "wrap" as const, gap: "5px", flexShrink: 0, justifyContent: "flex-end" }}>
                                <button onClick={() => onTest(m)} style={{ padding: "4px 9px", fontSize: "12px", color: "#7c3aed", backgroundColor: "#f5f3ff", border: "1px solid #ddd6fe", borderRadius: "6px", cursor: "pointer", display: "flex", alignItems: "center", gap: "3px" }}>
                                    <FlaskConical size={11} /> 测试
                                </button>
                                <button onClick={() => onDuplicate(m)} style={{ padding: "4px 9px", fontSize: "12px", color: "#374151", backgroundColor: "#f9fafb", border: "1px solid #e5e7eb", borderRadius: "6px", cursor: "pointer", display: "flex", alignItems: "center", gap: "3px" }}>
                                    <Copy size={11} /> 新建重复
                                </button>
                                <button onClick={() => onEdit(m)} style={{ padding: "4px 9px", fontSize: "12px", color: "#374151", backgroundColor: "#f9fafb", border: "1px solid #e5e7eb", borderRadius: "6px", cursor: "pointer", display: "flex", alignItems: "center", gap: "3px" }}>
                                    <Pencil size={11} /> 编辑
                                </button>
                                <button onClick={() => onDelete(m.id)} style={{ padding: "4px 9px", fontSize: "12px", color: "#dc2626", backgroundColor: "#fef2f2", border: "1px solid #fecaca", borderRadius: "6px", cursor: "pointer", display: "flex", alignItems: "center", gap: "3px" }}>
                                    <Trash2 size={11} /> 删除
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

// ─── 编辑弹窗 ─────────────────────────────────────────────────────────────────

function ModelEditModal({ model, isNew, onChange, onSave, onClose, onTest }: {
    model: LlmModelConfig;
    isNew: boolean;
    onChange: (m: LlmModelConfig) => void;
    onSave: (m: LlmModelConfig) => void;
    onClose: () => void;
    onTest: (m: LlmModelConfig) => void;
}) {
    const toggleCap = (cap: LlmCapability) => {
        const caps = (model.capabilities ?? []).includes(cap)
            ? (model.capabilities ?? []).filter(c => c !== cap)
            : [...(model.capabilities ?? []), cap];
        onChange({ ...model, capabilities: caps });
    };

    const [showApiKey, setShowApiKey] = useState(false);

    return (
        <div style={{
            position: "fixed", inset: 0, backgroundColor: "rgba(0,0,0,0.4)",
            display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100,
        }} onClick={onClose}>
            <div style={{
                backgroundColor: "#fff", borderRadius: "12px", width: "520px", maxWidth: "95vw",
                maxHeight: "90vh", overflowY: "auto", boxShadow: "0 20px 60px rgba(0,0,0,0.2)",
            }} onClick={e => e.stopPropagation()}>
                <div style={{
                    padding: "16px 20px", borderBottom: "1px solid #e5e7eb",
                    display: "flex", alignItems: "center", justifyContent: "space-between",
                }}>
                    <h3 style={{ margin: 0, fontSize: "16px", fontWeight: 700, color: "#111827" }}>
                        {isNew ? "添加模型" : "编辑模型"}
                    </h3>
                    <button onClick={onClose} style={{ background: "none", border: "none", cursor: "pointer", color: "#6b7280" }}>
                        <X size={18} />
                    </button>
                </div>
                <div style={{ padding: "20px" }}>
                    {([
                        { key: "name", label: "名称", placeholder: "GPT-4o Mini（工作用）", type: "text" },
                        { key: "base_url", label: "Base URL", placeholder: "https://api.openai.com/v1", type: "text" },
                        { key: "model", label: "模型名称", placeholder: "gpt-4o-mini", type: "text" },
                    ] as const).map(({ key, label, placeholder, type }) => (
                        <div key={key} style={{ marginBottom: "12px" }}>
                            <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>{label}</label>
                            <input type={type} value={model[key] ?? ""} placeholder={placeholder}
                                onChange={e => onChange({ ...model, [key]: e.target.value })}
                                className="llm-input"
                                style={{ width: "100%", padding: "7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                        </div>
                    ))}
                    <div style={{ marginBottom: "12px" }}>
                        <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>
                            App Model ID
                            <span style={{ marginLeft: "6px", fontSize: "11px", fontWeight: 400, color: "#9ca3af" }}>前端安全调用标识，留空则保存时自动生成</span>
                        </label>
                        <input type="text" value={model.app_model_id ?? ""} placeholder="留空自动生成，如 gpt-4o-mini-abc123"
                            onChange={e => onChange({ ...model, app_model_id: e.target.value })}
                            className="llm-input"
                            style={{ width: "100%", padding: "7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                    </div>
                    <div style={{ marginBottom: "12px" }}>
                        <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>API Key</label>
                        <div style={{ position: "relative" }}>
                            <input type={showApiKey ? "text" : "password"} value={model.api_key ?? ""} placeholder="sk-..."
                                onChange={e => onChange({ ...model, api_key: e.target.value })}
                                className="llm-input"
                                style={{ width: "100%", padding: "7px 36px 7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                            <button type="button" onClick={() => setShowApiKey(v => !v)} style={{
                                position: "absolute", right: "8px", top: "50%", transform: "translateY(-50%)",
                                background: "none", border: "none", cursor: "pointer", color: "#9ca3af", padding: "2px",
                                display: "flex", alignItems: "center",
                            }}>
                                {showApiKey ? <EyeOff size={15} /> : <Eye size={15} />}
                            </button>
                        </div>
                    </div>

                    <div style={{ marginBottom: "12px" }}>
                        <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "8px" }}>能力标签</label>
                        <div style={{ display: "flex", flexDirection: "column" as const, gap: "6px" }}>
                            {ALL_CAPABILITIES.map(cap => {
                                const meta = CAPABILITY_META[cap];
                                const checked = (model.capabilities ?? []).includes(cap);
                                return (
                                    <label key={cap} style={{ display: "flex", alignItems: "center", gap: "8px", cursor: "pointer" }}>
                                        <input type="checkbox" checked={checked} onChange={() => toggleCap(cap)} />
                                        <span style={{
                                            padding: "1px 7px", fontSize: "12px", borderRadius: "4px",
                                            backgroundColor: checked ? meta.color : "#f3f4f6",
                                            color: checked ? meta.textColor : "#9ca3af",
                                        }}>{meta.label}</span>
                                        <span style={{ fontSize: "12px", color: "#6b7280" }}>{meta.desc}</span>
                                    </label>
                                );
                            })}
                        </div>
                    </div>

                    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: "10px", marginBottom: "12px" }}>
                        {([
                            { key: "context_window", label: "上下文窗口", min: 1024 },
                            { key: "max_output_tokens", label: "最大输出 Token", min: 256 },
                        ] as const).map(({ key, label, min }) => (
                            <div key={key}>
                                <label style={{ display: "block", fontSize: "12px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>{label}</label>
                                <input type="number" value={model[key] ?? ""} min={min}
                                    onChange={e => onChange({ ...model, [key]: e.target.value === "" ? undefined : Number(e.target.value) })}
                                    className="llm-input"
                                    style={{ width: "100%", padding: "6px 8px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                            </div>
                        ))}
                        <div>
                            <label style={{ display: "block", fontSize: "12px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>Temperature</label>
                            <input type="number" value={model.temperature ?? ""} min={0} max={2} step={0.1}
                                onChange={e => onChange({ ...model, temperature: e.target.value === "" ? undefined : Number(e.target.value) })}
                                className="llm-input"
                                style={{ width: "100%", padding: "6px 8px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", boxSizing: "border-box" as const }} />
                        </div>
                    </div>

                    <div style={{ marginBottom: "16px" }}>
                        <label style={{ display: "block", fontSize: "13px", fontWeight: 500, color: "#374151", marginBottom: "4px" }}>备注</label>
                        <textarea value={model.notes} rows={2}
                            onChange={e => onChange({ ...model, notes: e.target.value })}
                            style={{ width: "100%", padding: "7px 10px", fontSize: "13px", border: "1px solid #d1d5db", borderRadius: "6px", resize: "vertical" as const, boxSizing: "border-box" as const }} />
                    </div>

                    <div style={{ display: "flex", gap: "8px", justifyContent: "flex-end" }}>
                        <button onClick={onClose} style={{
                            padding: "8px 16px", fontSize: "14px", color: "#374151",
                            backgroundColor: "#f9fafb", border: "1px solid #d1d5db", borderRadius: "8px", cursor: "pointer",
                        }}>取消</button>
                        <button onClick={() => onTest(model)} style={{
                            padding: "8px 16px", fontSize: "14px", fontWeight: 500, color: "#7c3aed",
                            backgroundColor: "#f5f3ff", border: "1px solid #ddd6fe", borderRadius: "8px", cursor: "pointer",
                            display: "flex", alignItems: "center", gap: "6px",
                        }}><FlaskConical size={14} /> 测试</button>
                        <button onClick={() => onSave(model)} style={{
                            padding: "8px 20px", fontSize: "14px", fontWeight: 600, color: "#fff",
                            backgroundColor: "#2563eb", border: "none", borderRadius: "8px", cursor: "pointer",
                        }}>保存</button>
                    </div>
                </div>
            </div>
        </div>
    );
}

// ─── 发现模型弹窗 ─────────────────────────────────────────────────────────────

const DISCOVER_PLATFORMS = [
    {
        category: "国内平台",
        items: [
            { name: "DeepSeek", desc: "DeepSeek-V3 / R1，高性价比推理模型", url: "https://platform.deepseek.com/api_keys" },
            { name: "阿里云百炼", desc: "Qwen 系列，支持长上下文与多模态", url: "https://bailian.console.aliyun.com/" },
            { name: "智谱 AI", desc: "GLM-4 系列，支持函数调用与视觉", url: "https://open.bigmodel.cn/usercenter/apikeys" },
            { name: "月之暗面 Moonshot", desc: "Kimi 长上下文模型（128K）", url: "https://platform.moonshot.cn/console/api-keys" },
            { name: "零一万物", desc: "Yi 系列开源与闭源模型", url: "https://platform.lingyiwanwu.com/apikeys" },
            { name: "MiniMax", desc: "abab 系列，支持语音与文本", url: "https://www.minimaxi.com/user-center/basic-information/interface-key" },
            { name: "百度千帆", desc: "ERNIE 系列，百度生态集成", url: "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application" },
            { name: "腾讯混元", desc: "混元大模型，腾讯云生态", url: "https://console.cloud.tencent.com/hunyuan/api-key" },
        ],
    },
    {
        category: "国际平台",
        items: [
            { name: "OpenAI", desc: "GPT-4o / o1 / o3，业界标杆", url: "https://platform.openai.com/api-keys" },
            { name: "Anthropic", desc: "Claude 3.5 / 4 系列，强推理与长上下文", url: "https://console.anthropic.com/settings/keys" },
            { name: "Google AI Studio", desc: "Gemini 系列，免费额度丰富", url: "https://aistudio.google.com/app/apikey" },
        ],
    },
    {
        category: "聚合 / 中转",
        items: [
            { name: "OpenRouter", desc: "统一接口访问 200+ 模型", url: "https://openrouter.ai/keys" },
            { name: "硅基流动 SiliconFlow", desc: "国内聚合，Qwen / DeepSeek / GLM 等", url: "https://cloud.siliconflow.cn/account/ak" },
        ],
    },
];

function DiscoverModelsModal({ onClose }: { onClose: () => void }) {
    return (
        <div style={{
            position: "fixed", inset: 0, backgroundColor: "rgba(0,0,0,0.45)",
            display: "flex", alignItems: "center", justifyContent: "center", zIndex: 200,
        }} onClick={onClose}>
            <div style={{
                backgroundColor: "#fff", borderRadius: "14px", width: "min(680px, 95vw)",
                maxHeight: "80vh", display: "flex", flexDirection: "column",
                boxShadow: "0 20px 60px rgba(0,0,0,0.2)",
            }} onClick={e => e.stopPropagation()}>
                {/* 头部 */}
                <div style={{
                    padding: "18px 24px", borderBottom: "1px solid #f3f4f6",
                    display: "flex", alignItems: "center", justifyContent: "space-between",
                }}>
                    <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                        <Compass size={18} color="#2563eb" />
                        <span style={{ fontSize: "16px", fontWeight: 700, color: "#111827" }}>发现模型平台</span>
                    </div>
                    <button onClick={onClose} style={{ background: "none", border: "none", cursor: "pointer", color: "#9ca3af", padding: "2px" }}>
                        <X size={18} />
                    </button>
                </div>
                {/* 内容 */}
                <div style={{ overflowY: "auto", padding: "16px 24px 24px" }}>
                    <p style={{ margin: "0 0 16px 0", fontSize: "13px", color: "#6b7280" }}>
                        以下平台均提供 OpenAI 兼容接口，可直接在「添加模型」中填入对应的 Base URL 和 API Key。
                    </p>
                    {DISCOVER_PLATFORMS.map(({ category, items }) => (
                        <div key={category} style={{ marginBottom: "20px" }}>
                            <div style={{
                                fontSize: "11px", fontWeight: 600, color: "#9ca3af",
                                textTransform: "uppercase", letterSpacing: "0.06em", marginBottom: "8px",
                            }}>{category}</div>
                            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" }}>
                                {items.map(({ name, desc, url }) => (
                                    <button key={name} onClick={() => openExternalUrl(url)}
                                        style={{
                                            display: "flex", alignItems: "flex-start", justifyContent: "space-between",
                                            padding: "10px 12px", borderRadius: "8px", border: "1px solid #e5e7eb",
                                            textAlign: "left", backgroundColor: "#fafafa", cursor: "pointer",
                                            transition: "border-color 0.15s, background-color 0.15s",
                                        }}
                                        onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.borderColor = "#2563eb"; (e.currentTarget as HTMLButtonElement).style.backgroundColor = "#eff6ff"; }}
                                        onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.borderColor = "#e5e7eb"; (e.currentTarget as HTMLButtonElement).style.backgroundColor = "#fafafa"; }}
                                    >
                                        <div>
                                            <div style={{ fontSize: "13px", fontWeight: 600, color: "#111827", marginBottom: "2px" }}>{name}</div>
                                            <div style={{ fontSize: "11px", color: "#6b7280", lineHeight: 1.4 }}>{desc}</div>
                                        </div>
                                        <ExternalLink size={13} color="#9ca3af" style={{ flexShrink: 0, marginTop: "2px", marginLeft: "6px" }} />
                                    </button>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}

// ─── 配置检查抽屉 ──────────────────────────────────────────────────────────────

const TEST_MODES: { value: LlmChatParams["mode"]; label: string; desc: string }[] = [
    { value: "direct", label: "前端直连", desc: "前端直接跨域请求 LLM（SSE 流式）" },
    { value: "router", label: "后端代理", desc: "Kotlin Router 转发，走系统代理（SSE 流式）" },
    { value: "bridge", label: "JsBridge", desc: "Kotlin JsBridge 调用，非流式，仅 WebView 可用" },
];

function ConfigCheckDrawer({ model, onClose }: { model: LlmModelConfig; onClose: () => void }) {
    const { appConfig } = useAppConfig();
    const [output, setOutput] = useState("");
    const [loading, setLoading] = useState(false);
    const hasBridge = typeof window !== "undefined" && !!window.kmpJsBridge;
    const defaultMode: LlmChatParams["mode"] = hasBridge ? "router" : "direct";
    const [mode, setMode] = useState<LlmChatParams["mode"]>(defaultMode);
    const hasRun = useRef(false);

    useEffect(() => {
        if (hasRun.current) return;
        hasRun.current = true;
        runTest();
    }, []);

    async function runTest() {
        setLoading(true);
        try {
            const message = "你是什么模型？请简短回答。";
            if (mode === "direct") {
                if (!model.base_url || !model.api_key || !model.model) {
                    setOutput("缺少 base_url / api_key / model，无法发起请求。");
                    return;
                }
            } else {
                if (!model.app_model_id) {
                    setOutput("该模型尚未设置 appModelId，请先保存模型配置。");
                    return;
                }
            }
            setOutput("");
            const connection: LlmChatConnectionConfig = {
                schema: appConfig.webserver_schema,
                domain: appConfig.webserver_domain,
                port: appConfig.webserver_port,
                appAuthToken: appConfig.webserver_auth_token,
            };
            const params: LlmChatParams = mode === "direct"
                ? { mode: "direct", base_url: model.base_url, api_key: model.api_key, model: model.model, message }
                : mode === "router"
                    ? { mode: "router", app_model_id: model.app_model_id!, message, connection }
                    : { mode: "bridge", app_model_id: model.app_model_id!, message };
            try {
                for await (const chunk of llmChat(params)) setOutput(prev => prev + chunk);
            } catch (e: any) {
                setOutput(`请求失败: ${e?.message ?? String(e)}`);
            }
        } finally {
            setLoading(false);
        }
    }

    return (
        <div style={{
            position: "fixed", top: 0, right: 0, bottom: 0, width: "380px",
            backgroundColor: "#fff", boxShadow: "-4px 0 24px rgba(0,0,0,0.12)",
            display: "flex", flexDirection: "column", zIndex: 150,
        }}>
            <div style={{
                padding: "16px 20px", borderBottom: "1px solid #e5e7eb",
                display: "flex", alignItems: "center", justifyContent: "space-between", flexShrink: 0,
            }}>
                <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                    <FlaskConical size={16} color="#7c3aed" />
                    <span style={{ fontSize: "15px", fontWeight: 700, color: "#111827" }}>配置检查</span>
                </div>
                <button onClick={onClose} style={{ background: "none", border: "none", cursor: "pointer", color: "#9ca3af" }}>
                    <X size={18} />
                </button>
            </div>
            <div style={{ padding: "12px 20px", borderBottom: "1px solid #f3f4f6", flexShrink: 0, backgroundColor: "#fafafa" }}>
                <div style={{ fontSize: "13px", fontWeight: 600, color: "#111827" }}>{model.name || model.model}</div>
                <div style={{ fontSize: "11px", color: "#6b7280", marginTop: "2px", wordBreak: "break-all" }}>{model.base_url}</div>
            </div>
            {/* 模式切换 */}
            <div style={{ padding: "10px 20px", borderBottom: "1px solid #f3f4f6", flexShrink: 0 }}>
                <div style={{ fontSize: "11px", color: "#9ca3af", marginBottom: "6px", fontWeight: 500 }}>请求模式</div>
                <div style={{ display: "flex", gap: "6px", flexWrap: "wrap" }}>
                    {TEST_MODES.map(m => (
                        <button key={m.value} onClick={() => setMode(m.value)} title={m.desc}
                            disabled={m.value === "bridge" && !hasBridge}
                            style={{
                                padding: "4px 10px", fontSize: "12px", borderRadius: "6px", cursor: m.value === "bridge" && !hasBridge ? "not-allowed" : "pointer",
                                border: `1px solid ${mode === m.value ? "#7c3aed" : "#e5e7eb"}`,
                                backgroundColor: mode === m.value ? "#f5f3ff" : "#fff",
                                color: mode === m.value ? "#7c3aed" : m.value === "bridge" && !hasBridge ? "#d1d5db" : "#374151",
                                fontWeight: mode === m.value ? 600 : 400,
                            }}>
                            {m.label}
                        </button>
                    ))}
                </div>
                <div style={{ fontSize: "11px", color: "#9ca3af", marginTop: "4px" }}>
                    {TEST_MODES.find(m => m.value === mode)?.desc}
                </div>
            </div>
            <div style={{ flex: 1, padding: "16px 20px", display: "flex", flexDirection: "column", gap: "12px", overflow: "hidden" }}>
                <div style={{ fontSize: "12px", color: "#6b7280" }}>
                    提问：<span style={{ color: "#374151", fontStyle: "italic" }}>你是什么模型？请简短回答。</span>
                </div>
                <textarea readOnly value={loading && !output ? "正在请求..." : output} style={{
                    flex: 1, resize: "none", padding: "10px 12px", fontSize: "13px", lineHeight: 1.6,
                    border: "1px solid #e5e7eb", borderRadius: "8px", backgroundColor: "#f9fafb",
                    color: "#111827", fontFamily: "inherit",
                }} />
                <button onClick={runTest} disabled={loading} style={{
                    padding: "8px 16px", fontSize: "13px", fontWeight: 500,
                    color: loading ? "#9ca3af" : "#7c3aed",
                    backgroundColor: loading ? "#f3f4f6" : "#f5f3ff",
                    border: `1px solid ${loading ? "#e5e7eb" : "#ddd6fe"}`,
                    borderRadius: "8px", cursor: loading ? "not-allowed" : "pointer",
                    display: "flex", alignItems: "center", justifyContent: "center", gap: "6px",
                }}>
                    <FlaskConical size={13} /> {loading ? "请求中..." : "重新测试"}
                </button>
            </div>
        </div>
    );
}

// ─── JSON 预览模态框 ───────────────────────────────────────────────────────────

function JsonPreviewModal({ json, onClose }: { json: string; onClose: () => void }) {
    const [copied, setCopied] = useState(false);

    const handleCopy = () => {
        navigator.clipboard.writeText(json).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        });
    };

    return (
        <div style={{
            position: "fixed", inset: 0, backgroundColor: "rgba(0,0,0,0.45)",
            display: "flex", alignItems: "center", justifyContent: "center", zIndex: 200,
        }} onClick={onClose}>
            <div style={{
                backgroundColor: "#fff", borderRadius: "14px", width: "min(680px, 95vw)",
                height: "80vh", display: "flex", flexDirection: "column",
                boxShadow: "0 20px 60px rgba(0,0,0,0.2)",
            }} onClick={e => e.stopPropagation()}>
                <div style={{
                    padding: "16px 20px", borderBottom: "1px solid #e5e7eb",
                    display: "flex", alignItems: "center", justifyContent: "space-between", flexShrink: 0,
                }}>
                    <span style={{ fontSize: "15px", fontWeight: 700, color: "#111827" }}>导出 JSON</span>
                    <div style={{ display: "flex", gap: "8px", alignItems: "center" }}>
                        <button onClick={handleCopy} style={{
                            display: "flex", alignItems: "center", gap: "5px", padding: "6px 12px",
                            fontSize: "13px", fontWeight: 500,
                            color: copied ? "#166534" : "#374151",
                            backgroundColor: copied ? "#dcfce7" : "#f9fafb",
                            border: `1px solid ${copied ? "#86efac" : "#d1d5db"}`,
                            borderRadius: "6px", cursor: "pointer",
                        }}>
                            <Copy size={13} /> {copied ? "已复制" : "复制"}
                        </button>
                        <button onClick={onClose} style={{ background: "none", border: "none", cursor: "pointer", color: "#9ca3af" }}>
                            <X size={18} />
                        </button>
                    </div>
                </div>
                <textarea readOnly value={json} style={{
                    flex: 1, margin: "16px", resize: "none", padding: "12px",
                    fontSize: "12px", fontFamily: "monospace", lineHeight: 1.6,
                    border: "1px solid #e5e7eb", borderRadius: "8px",
                    backgroundColor: "#f9fafb", color: "#111827",
                }} />
            </div>
        </div>
    );
}
