import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, RefreshCw, Download, CheckCircle, Loader, AlertTriangle, ChevronDown, Search } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting.torch-config";
import { useAppFetch } from "~/util/app_fetch";
import { callBridge, callBridgeOrNull, BridgeUnavailableError } from "~/util/bridge";
import { WorkflowInfoPanel } from "~/components/ui/WorkflowInfoPanel";
import { print_error } from "~/util/error_handler";

export function meta({}: Route.MetaArgs) {
    return [{ title: "PyTorch 环境配置 - Fredica" }];
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface TorchVariantOption {
    variant: string;
    label: string;
    index_url: string;
    packages: string[];
    is_recommended: boolean;
}

interface TorchRecommendation {
    recommended_variant: string;
    reason: string;
    driver_version: string;
    options: TorchVariantOption[];
}

interface TorchCheckItem {
    variant: string;
    downloaded: boolean;
    version: string | null;
}

interface TorchInfo {
    torch_variant: string;
    torch_recommendation_json: string;
    torch_download_use_proxy: boolean;
    torch_download_proxy_url: string;
    torch_download_index_url: string;
    torch_custom_packages: string;
    torch_custom_index_url: string;
    torch_custom_variant_id: string;
}

interface MirrorCheckResult {
    key: string;
    label: string;
    available: boolean | null;
    url: string;
    error: string;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const BUILTIN_VARIANTS = ["cu128", "cu126", "cu124", "cu121", "cu118", "rocm6.3", "rocm6.2", "cpu"];

const VARIANT_LABELS: Record<string, string> = {
    cu128: "CUDA 12.8",
    cu126: "CUDA 12.6",
    cu124: "CUDA 12.4",
    cu121: "CUDA 12.1",
    cu118: "CUDA 11.8",
    "rocm6.3": "ROCm 6.3（仅 Linux）",
    "rocm6.2": "ROCm 6.2（仅 Linux）",
    cpu: "CPU only",
};

// mirror key -> index_url builder (for pip command preview)
const MIRROR_URL_FNS: Record<string, (v: string) => string> = {
    official: () => "",
    nju: (v) => `https://mirrors.nju.edu.cn/pytorch/whl/${v}`,
    sjtu: (v) => `https://mirror.sjtu.edu.cn/pytorch-wheels/${v}`,
    aliyun: (v) => `https://mirrors.aliyun.com/pytorch-wheels/${v}`,
    tuna: (v) => v === "cpu" ? "https://pypi.tuna.tsinghua.edu.cn/simple" : "",
    custom: () => "",
};

// mirrors that only support CPU-only torch (no CUDA wheels)
const MIRROR_CPU_ONLY = new Set(["tuna"]);

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function TorchConfigPage() {
    const navigate = useNavigate();
    const { apiFetch } = useAppFetch();

    const [info, setInfo] = useState<TorchInfo>({
        torch_variant: "",
        torch_recommendation_json: "",
        torch_download_use_proxy: false,
        torch_download_proxy_url: "",
        torch_download_index_url: "",
        torch_custom_packages: "",
        torch_custom_index_url: "",
        torch_custom_variant_id: "",
    });

    const [recommendation, setRecommendation] = useState<TorchRecommendation | null>(null);
    const [checkItems, setCheckItems] = useState<TorchCheckItem[]>([]);
    const [selectedVariant, setSelectedVariant] = useState<string>("");
    const [customExpanded, setCustomExpanded] = useState(false);

    // 所有镜像合并后的 variant 列表（页面进入时查询）
    const [allVariants, setAllVariants] = useState<string[]>(BUILTIN_VARIANTS);
    // per-mirror variant 支持情况（来自 all-mirror-variants 响应）
    const [perMirrorVariants, setPerMirrorVariants] = useState<Record<string, string[]>>({});
    // per-mirror torch 版本号（来自 all-mirror-variants 响应）
    const [perMirrorTorchVersions, setPerMirrorTorchVersions] = useState<Record<string, Record<string, string>>>({});

    // proxy
    const [useProxy, setUseProxy] = useState(false);
    const [proxyUrl, setProxyUrl] = useState("");

    // mirror selection
    const [selectedMirrorKey, setSelectedMirrorKey] = useState("official");
    const [customMirrorUrl, setCustomMirrorUrl] = useState("");
    const [mirrorCheckResults, setMirrorCheckResults] = useState<Record<string, MirrorCheckResult>>({});
    const [checkingMirrors, setCheckingMirrors] = useState(false);

    // variants supported by the currently selected mirror (null = not yet fetched)
    const [mirrorSupportedVariants, setMirrorSupportedVariants] = useState<string[] | null>(null);

    // custom variant form
    const [customPackages, setCustomPackages] = useState("");
    const [customIndexUrl, setCustomIndexUrl] = useState("");
    const [customVariantId, setCustomVariantId] = useState("");

    // detect state
    const [detecting, setDetecting] = useState(false);

    // pip command preview
    const [pipCommand, setPipCommand] = useState<string>("");

    // download workflow
    const [downloadWorkflowRunId, setDownloadWorkflowRunId] = useState<string | null>(null);
    const [downloading, setDownloading] = useState(false);
    const [downloadError, setDownloadError] = useState<string | null>(null);
    const [showRestartHint, setShowRestartHint] = useState(false);

    // ── Load on mount ──────────────────────────────────────────────────────────

    useEffect(() => {
        loadInfo();
        loadCheckItems();
        loadAllMirrorVariants();
    }, []);

    // reset mirror check cache when variant changes; also deselect CPU-only mirrors for CUDA variants
    useEffect(() => {
        setMirrorCheckResults({});
        if (selectedVariant && selectedVariant !== "cpu" && MIRROR_CPU_ONLY.has(selectedMirrorKey)) {
            setSelectedMirrorKey("official");
        }
    }, [selectedVariant]);

    // fetch supported variants for the selected mirror whenever it changes
    useEffect(() => {
        if (selectedMirrorKey === "custom" || selectedMirrorKey === "official") {
            setMirrorSupportedVariants(null);
            return;
        }
        setMirrorSupportedVariants(null);
        const params: Record<string, unknown> = { mirror_key: selectedMirrorKey };
        if (useProxy && proxyUrl.trim()) { params.use_proxy = true; params.proxy = proxyUrl.trim(); }
        callBridgeOrNull("get_torch_mirror_versions", JSON.stringify(params))
            .then(raw => {
                if (!raw) { setMirrorSupportedVariants(null); return; }
                try {
                    const res = JSON.parse(raw) as { variants?: string[]; error?: string };
                    if (res.error) {
                        print_error({ reason: `获取镜像版本列表失败: ${res.error}`, variables: { mirror_key: selectedMirrorKey } });
                        setMirrorSupportedVariants(null);
                        return;
                    }
                    const variants = res.variants ?? null;
                    setMirrorSupportedVariants(variants);
                    // 如果当前选中的 variant 不在该镜像支持列表里，自动切换到推荐或第一个可用项
                    if (variants && variants.length > 0 && selectedVariant && !variants.includes(selectedVariant)) {
                        const fallback = (recommendedVariant && variants.includes(recommendedVariant))
                            ? recommendedVariant
                            : variants[0];
                        setSelectedVariant(fallback);
                    }
                    setMirrorSupportedVariants(res.variants ?? null);
                } catch (e) {
                    print_error({ reason: "解析镜像版本列表失败", err: e, variables: { mirror_key: selectedMirrorKey } });
                    setMirrorSupportedVariants(null);
                }
            })
            .catch(e => {
                print_error({ reason: "获取镜像版本列表异常", err: e, variables: { mirror_key: selectedMirrorKey } });
                setMirrorSupportedVariants(null);
            });
    }, [selectedMirrorKey]);

    // pip command preview
    useEffect(() => {
        if (!selectedVariant || selectedVariant === "custom") {
            setPipCommand("");
            return;
        }
        const params: Record<string, unknown> = { variant: selectedVariant };
        if (useProxy && proxyUrl.trim()) {
            params.use_proxy = true;
            params.proxy = proxyUrl.trim();
        }
        const effectiveIndexUrl = selectedMirrorKey === "custom"
            ? customMirrorUrl.trim()
            : (MIRROR_URL_FNS[selectedMirrorKey]?.(selectedVariant) ?? "");
        if (effectiveIndexUrl) {
            params.index_url = effectiveIndexUrl;
            params.index_url_mode = "replace";
        }
        callBridge("get_torch_pip_command", JSON.stringify(params))
            .then(raw => {
                try { setPipCommand(JSON.parse(raw).command ?? ""); } catch { setPipCommand(""); }
            })
            .catch(() => setPipCommand(""));
    }, [selectedVariant, useProxy, proxyUrl, selectedMirrorKey, customMirrorUrl]);

    const loadInfo = async () => {
        try {
            const raw = await callBridgeOrNull("get_torch_info");
            if (!raw) return;
            const d = JSON.parse(raw) as TorchInfo;
            if (d.error) {
                print_error({ reason: `加载 torch 配置失败: ${d.error}`, variables: { raw } });
                return;
            }
            setInfo(d);
            setUseProxy(d.torch_download_use_proxy);
            setProxyUrl(d.torch_download_proxy_url);
            setCustomPackages(d.torch_custom_packages);
            setCustomIndexUrl(d.torch_custom_index_url);
            setCustomVariantId(d.torch_custom_variant_id);
            setSelectedVariant(d.torch_variant || "");
            // restore saved mirror key from index_url
            const savedUrl = d.torch_download_index_url;
            if (savedUrl) {
                const variant = d.torch_variant || "";
                const matched = Object.entries(MIRROR_URL_FNS).find(
                    ([k, fn]) => k !== "official" && k !== "custom" && fn(variant) === savedUrl
                );
                if (matched) setSelectedMirrorKey(matched[0]);
                else { setSelectedMirrorKey("custom"); setCustomMirrorUrl(savedUrl); }
            } else {
                setSelectedMirrorKey("official");
            }
            try {
                if (d.torch_recommendation_json) {
                    setRecommendation(JSON.parse(d.torch_recommendation_json) as TorchRecommendation);
                }
            } catch (e) {
                print_error({ reason: "解析 torch 推荐信息失败", err: e });
            }
        } catch (e) {
            print_error({ reason: "加载 torch 配置异常", err: e });
        }
    };

    const loadCheckItems = async () => {
        try {
            const raw = await callBridgeOrNull("get_torch_check");
            if (!raw) return;
            const d = JSON.parse(raw) as { items: TorchCheckItem[]; error?: string };
            if (d.error) {
                print_error({ reason: `检查 torch 下载状态失败: ${d.error}` });
                return;
            }
            setCheckItems(d.items ?? []);
        } catch (e) {
            print_error({ reason: "检查 torch 下载状态异常", err: e });
        }
    };

    const loadAllMirrorVariants = async () => {
        try {
            const raw = await callBridgeOrNull("get_torch_all_mirror_variants", JSON.stringify({ use_proxy: useProxy, proxy: proxyUrl.trim() }));
            if (!raw) return;
            const res = JSON.parse(raw) as { variants?: string[]; per_mirror?: Record<string, string[]>; per_mirror_torch_versions?: Record<string, Record<string, string>>; error?: string };
            if (res.error) {
                print_error({ reason: `查询镜像版本列表失败: ${res.error}` });
                return;
            }
            if (res.variants && res.variants.length > 0) setAllVariants(res.variants);
            if (res.per_mirror) setPerMirrorVariants(res.per_mirror);
            if (res.per_mirror_torch_versions) setPerMirrorTorchVersions(res.per_mirror_torch_versions);
        } catch (e) {
            print_error({ reason: "查询镜像版本列表异常", err: e });
        }
    };

    const runDetect = async () => {
        if (detecting) return;
        setDetecting(true);
        try {
            const raw = await callBridge("run_torch_detect");
            const res = JSON.parse(raw);
            if (res.error) {
                print_error({ reason: `GPU 检测失败: ${res.error}` });
                return;
            }
            if (res.torch_recommendation_json) {
                try { setRecommendation(JSON.parse(res.torch_recommendation_json)); }
                catch (e) { print_error({ reason: "解析 GPU 检测结果失败", err: e }); }
            }
            await loadCheckItems();
        } catch (e) {
            if (e instanceof BridgeUnavailableError) { setDetecting(false); return; }
            print_error({ reason: "GPU 检测异常", err: e });
        } finally { setDetecting(false); }
    };

    const runMirrorCheck = async () => {
        if (!selectedVariant || selectedVariant === "custom" || checkingMirrors) return;
        setCheckingMirrors(true);
        try {
            const params: Record<string, unknown> = { variant: selectedVariant };
            if (useProxy && proxyUrl.trim()) { params.use_proxy = true; params.proxy = proxyUrl.trim(); }
            const raw = await callBridge("get_torch_mirror_check", JSON.stringify(params));
            const res = JSON.parse(raw) as { results: MirrorCheckResult[]; error?: string };
            if (res.error) {
                print_error({ reason: `探测镜像可用性失败: ${res.error}`, variables: { variant: selectedVariant } });
                return;
            }
            const map: Record<string, MirrorCheckResult> = {};
            for (const r of res.results ?? []) map[r.key] = r;
            setMirrorCheckResults(map);
        } catch (e) {
            if (e instanceof BridgeUnavailableError) { setCheckingMirrors(false); return; }
            print_error({ reason: "探测镜像可用性异常", err: e, variables: { variant: selectedVariant } });
        } finally { setCheckingMirrors(false); }
    };

    const startDownload = async () => {
        if (downloading || !selectedVariant) return;
        if (useProxy && !proxyUrl.trim()) { setDownloadError("请填写代理地址"); return; }
        setDownloading(true);
        setDownloadError(null);
        setDownloadWorkflowRunId(null);
        setShowRestartHint(false);

        try {
            const effectiveIndexUrl = selectedMirrorKey === "custom"
                ? customMirrorUrl.trim()
                : (MIRROR_URL_FNS[selectedMirrorKey]?.(selectedVariant) ?? "");

            const saveRaw = await callBridge("save_torch_config", JSON.stringify({
                    torch_variant: selectedVariant,
                    torch_download_use_proxy: useProxy,
                    torch_download_proxy_url: proxyUrl.trim(),
                    torch_download_index_url: selectedVariant !== "custom" ? effectiveIndexUrl : "",
                    torch_custom_packages: selectedVariant === "custom" ? customPackages : "",
                    torch_custom_index_url: selectedVariant === "custom" ? customIndexUrl : "",
                    torch_custom_variant_id: selectedVariant === "custom" ? customVariantId.trim() : "",
                }));
                const saveRes = JSON.parse(saveRaw);
                if (saveRes.error) { setDownloadError(`保存配置失败: ${saveRes.error}`); setDownloading(false); return; }

            const raw = await callBridge("download_torch", "{}");
            const res = JSON.parse(raw);
            if (res.error) {
                setDownloadError(res.error === "TASK_ALREADY_ACTIVE" ? "下载任务已在进行中" : res.error);
                setDownloading(false);
                return;
            }
            if (res.workflow_run_id) setDownloadWorkflowRunId(res.workflow_run_id);
        } catch (e: unknown) {
            if (e instanceof BridgeUnavailableError) { setDownloadError("仅支持在桌面应用内运行"); setDownloading(false); return; }
            setDownloadError(e instanceof Error ? e.message : String(e));
            setDownloading(false);
        }
    };

    const handleActiveState = (state: { anyActiveTaskId: string | null }) => {
        if (downloadWorkflowRunId && state.anyActiveTaskId === null) {
            setDownloading(false);
            setShowRestartHint(true);
            loadInfo();
            loadCheckItems();
        }
    };

    const isDownloaded = (variant: string) =>
        checkItems.find(c => c.variant === variant)?.downloaded ?? false;

    const recommendedVariant = recommendation?.recommended_variant ?? "";

    const MIRROR_DEFS = [
        { key: "official", label: "官方源（pytorch.org）" },
        { key: "nju",      label: "南京大学镜像" },
        { key: "sjtu",     label: "上海交大镜像" },
        { key: "aliyun",   label: "阿里云镜像" },
        { key: "tuna",     label: "清华 PyPI 镜像（仅 CPU，不含 CUDA wheel）" },
        { key: "custom",   label: "自定义…" },
    ];
    const isCudaVariant = selectedVariant && selectedVariant !== "cpu" && !selectedVariant.startsWith("rocm");

    return (
        <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", padding: "24px" }}>
            <div style={{ maxWidth: "900px", margin: "0 auto" }}>

                {/* Header */}
                <div style={{ display: "flex", alignItems: "center", gap: "12px", marginBottom: "28px" }}>
                    <button onClick={() => navigate(-1)} style={{ background: "none", border: "none", cursor: "pointer", padding: "4px", color: "#6b7280", display: "flex" }}>
                        <ArrowLeft size={20} />
                    </button>
                    <div>
                        <h1 style={{ margin: 0, fontSize: "20px", fontWeight: 700, color: "#111827" }}>PyTorch 环境</h1>
                        <p style={{ margin: "2px 0 0", fontSize: "13px", color: "#9ca3af" }}>为 faster-whisper 配置 GPU 加速的 torch 版本</p>
                    </div>
                </div>

                {/* GPU 检测卡片 */}
                <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px 20px", marginBottom: "20px", display: "flex", alignItems: "center", gap: "16px", flexWrap: "wrap" }}>
                    <div style={{ flex: 1, minWidth: "200px" }}>
                        {recommendation ? (
                            <div style={{ fontSize: "13px", color: "#374151", display: "flex", flexWrap: "wrap", gap: "16px" }}>
                                <span><span style={{ color: "#6b7280" }}>推荐：</span><strong>{VARIANT_LABELS[recommendedVariant] ?? recommendedVariant}</strong></span>
                                {recommendation.driver_version && <span><span style={{ color: "#6b7280" }}>驱动：</span>{recommendation.driver_version}</span>}
                                <span style={{ color: "#6b7280" }}>{recommendation.reason}</span>
                            </div>
                        ) : (
                            <span style={{ fontSize: "13px", color: "#9ca3af" }}>尚未检测 GPU，点击右侧按钮检测。</span>
                        )}
                    </div>
                    <button onClick={runDetect} disabled={detecting} style={secondaryBtnStyle}>
                        {detecting ? <Loader size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                        {detecting ? "检测中…" : "重新检测"}
                    </button>
                </div>

                {/* 两栏布局 */}
                <div style={{ display: "flex", gap: "16px", alignItems: "flex-start" }}>

                    {/* 左栏：版本选择 */}
                    <div style={{ width: "240px", flexShrink: 0, backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px" }}>
                        <p style={{ margin: "0 0 10px", fontSize: "13px", fontWeight: 600, color: "#111827" }}>选择版本</p>
                        <p style={{ margin: "0 0 10px", fontSize: "11px", color: "#9ca3af" }}>
                            当前生效：<strong>{info.torch_variant ? (VARIANT_LABELS[info.torch_variant] ?? info.torch_variant) : "未设置"}</strong>
                        </p>
                        {mirrorSupportedVariants !== null && (
                            <p style={{ margin: "0 0 8px", fontSize: "10px", color: "#6b7280" }}>
                                ✓ / ✗ 表示所选镜像是否提供该版本
                            </p>
                        )}
                        <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                            {allVariants.map(v => {
                                const downloaded = isDownloaded(v);
                                const isRec = v === recommendedVariant;
                                const isSelected = selectedVariant === v;
                                // 优先用 perMirrorVariants（页面进入时已查），fallback 到 mirrorSupportedVariants（手动切换镜像时查）
                                const supportedForMirror = selectedMirrorKey !== "official" && selectedMirrorKey !== "custom"
                                    ? (perMirrorVariants[selectedMirrorKey] ?? mirrorSupportedVariants)
                                    : null;
                                const onMirror = supportedForMirror ? supportedForMirror.includes(v) : null;
                                const isDisabled = onMirror === false;
                                // 当前镜像对该 variant 的 torch 版本号
                                const mirrorTorchVer = perMirrorTorchVersions[selectedMirrorKey]?.[v];
                                return (
                                    <label key={v} style={{ display: "flex", alignItems: "center", gap: "8px", cursor: isDisabled ? "not-allowed" : "pointer", padding: "6px 8px", borderRadius: "6px", backgroundColor: isSelected ? "#eff6ff" : "transparent", border: `1px solid ${isSelected ? "#3b82f6" : "transparent"}`, opacity: isDisabled ? 0.4 : 1 }}>
                                        <input
                                            type="radio" name="variant" value={v} checked={isSelected} disabled={isDisabled}
                                            onChange={() => { if (!isDisabled) { setSelectedVariant(v); setCustomExpanded(false); } }}
                                            style={{ accentColor: "#3b82f6", flexShrink: 0 }}
                                        />
                                        <span style={{ flex: 1, fontSize: "12px", color: isDisabled ? "#9ca3af" : "#111827" }}>
                                            {VARIANT_LABELS[v] ?? v}
                                            {isRec && <span style={{ marginLeft: "4px", fontSize: "10px", color: "#2563eb", fontWeight: 600 }}>推荐</span>}
                                            {mirrorTorchVer && onMirror !== false && <span style={{ marginLeft: "4px", fontSize: "10px", color: "#6b7280" }}>torch {mirrorTorchVer}</span>}
                                        </span>
                                        {onMirror === true && <span style={{ fontSize: "10px", color: "#16a34a" }}>✓</span>}
                                        {onMirror === false && <span style={{ fontSize: "10px", color: "#d1d5db" }}>✗</span>}
                                        {onMirror === null && (downloaded
                                            ? <CheckCircle size={12} color="#22c55e" />
                                            : <span style={{ fontSize: "10px", color: "#d1d5db" }}>—</span>)}
                                    </label>
                                );
                            })}
                            {/* 自定义版本 */}
                            <label style={{ display: "flex", alignItems: "center", gap: "8px", cursor: "pointer", padding: "6px 8px", borderRadius: "6px", backgroundColor: selectedVariant === "custom" ? "#eff6ff" : "transparent", border: `1px solid ${selectedVariant === "custom" ? "#3b82f6" : "transparent"}` }}>
                                <input
                                    type="radio" name="variant" value="custom" checked={selectedVariant === "custom"}
                                    onChange={() => { setSelectedVariant("custom"); setCustomExpanded(true); }}
                                    style={{ accentColor: "#3b82f6", flexShrink: 0 }}
                                />
                                <span style={{ flex: 1, fontSize: "12px", color: "#111827" }}>自定义版本</span>
                                <button type="button" onClick={e => { e.preventDefault(); setCustomExpanded(v => !v); }} style={{ background: "none", border: "none", cursor: "pointer", color: "#6b7280", display: "flex", padding: "0" }}>
                                    <ChevronDown size={12} style={{ transform: customExpanded ? "rotate(180deg)" : "none", transition: "transform 0.2s" }} />
                                </button>
                            </label>
                        </div>

                        {/* 自定义版本展开表单 */}
                        {customExpanded && (
                            <div style={{ marginTop: "10px", padding: "10px", backgroundColor: "#f8fafc", borderRadius: "8px", border: "1px solid #e2e8f0" }}>
                                <div style={{ marginBottom: "8px" }}>
                                    <label style={{ display: "block", fontSize: "11px", fontWeight: 500, color: "#374151", marginBottom: "3px" }}>packages（每行一个）</label>
                                    <textarea value={customPackages} onChange={e => setCustomPackages(e.target.value)}
                                        placeholder={"torch==2.7.0+cu128\ntorchvision==0.22.0+cu128"} rows={3}
                                        style={{ ...inputStyle, resize: "vertical", fontSize: "11px" }} />
                                </div>
                                <div style={{ marginBottom: "8px" }}>
                                    <label style={{ display: "block", fontSize: "11px", fontWeight: 500, color: "#374151", marginBottom: "3px" }}>index-url</label>
                                    <input type="text" value={customIndexUrl} onChange={e => setCustomIndexUrl(e.target.value)}
                                        placeholder="https://download.pytorch.org/whl/cu128" style={{ ...inputStyle, fontSize: "11px" }} />
                                </div>
                                <div>
                                    <label style={{ display: "block", fontSize: "11px", fontWeight: 500, color: "#374151", marginBottom: "3px" }}>variant 标识</label>
                                    <input type="text" value={customVariantId} onChange={e => setCustomVariantId(e.target.value)}
                                        placeholder="cu128-custom" style={{ ...inputStyle, fontSize: "11px" }} />
                                </div>
                            </div>
                        )}
                    </div>

                    {/* 右栏：下载源 + pip 预览 + 代理 */}
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px 20px", marginBottom: "16px" }}>
                            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "12px" }}>
                                <p style={{ margin: 0, fontSize: "13px", fontWeight: 600, color: "#111827" }}>下载源</p>
                                <button onClick={runMirrorCheck} disabled={checkingMirrors || !selectedVariant || selectedVariant === "custom"} style={{ ...secondaryBtnStyle, fontSize: "12px", padding: "5px 10px" }}>
                                    {checkingMirrors ? <Loader size={12} className="animate-spin" /> : <Search size={12} />}
                                    {checkingMirrors ? "探测中…" : "探测镜像可用性"}
                                </button>
                            </div>
                            <div style={{ display: "flex", flexDirection: "column", gap: "6px" }}>
                                {MIRROR_DEFS.map(m => {
                                    const checkResult = mirrorCheckResults[m.key];
                                    const isSelected = selectedMirrorKey === m.key;
                                    const isDisabled = MIRROR_CPU_ONLY.has(m.key) && !!isCudaVariant;
                                    return (
                                        <div key={m.key}>
                                            <label style={{ display: "flex", alignItems: "center", gap: "10px", cursor: isDisabled ? "not-allowed" : "pointer", padding: "7px 10px", borderRadius: "8px", backgroundColor: isDisabled ? "#f3f4f6" : isSelected ? "#eff6ff" : "#f9fafb", border: `1px solid ${isSelected ? "#3b82f6" : "#e5e7eb"}`, opacity: isDisabled ? 0.5 : 1 }}>
                                                <input type="radio" name="mirror" checked={isSelected} disabled={isDisabled}
                                                    onChange={() => !isDisabled && setSelectedMirrorKey(m.key)}
                                                    style={{ accentColor: "#3b82f6", flexShrink: 0 }} />
                                                <span style={{ flex: 1, fontSize: "13px", color: isDisabled ? "#9ca3af" : "#111827" }}>{m.label}</span>
                                                {isDisabled && <span style={{ fontSize: "11px", color: "#9ca3af" }}>不支持 CUDA</span>}
                                                {!isDisabled && checkResult && m.key !== "custom" && (
                                                    checkResult.available === true
                                                        ? <span style={{ fontSize: "11px", color: "#16a34a", fontWeight: 500 }}>✓ 可用</span>
                                                        : checkResult.available === false
                                                            ? <span style={{ fontSize: "11px", color: "#dc2626" }}>✗ 不可用</span>
                                                            : null
                                                )}
                                            </label>
                                            {isSelected && m.key === "custom" && (
                                                <input type="text" value={customMirrorUrl} onChange={e => setCustomMirrorUrl(e.target.value)}
                                                    placeholder="https://your-mirror.example.com/whl/cu128"
                                                    style={{ ...inputStyle, marginTop: "6px" }} />
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        </div>

                        {/* pip 命令预览 */}
                        {pipCommand && (
                            <div style={{ marginBottom: "16px", padding: "10px 12px", backgroundColor: "#f8fafc", border: "1px solid #e2e8f0", borderRadius: "8px" }}>
                                <p style={{ margin: "0 0 6px", fontSize: "11px", color: "#64748b", fontWeight: 500 }}>等效 pip 命令</p>
                                <code style={{ fontSize: "11px", color: "#1e293b", wordBreak: "break-all", whiteSpace: "pre-wrap" }}>{pipCommand}</code>
                            </div>
                        )}

                        {/* 代理设置 */}
                        <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px 20px" }}>
                            <p style={{ margin: "0 0 10px", fontSize: "13px", fontWeight: 600, color: "#111827" }}>代理设置</p>
                            <div style={{ display: "flex", alignItems: "center", gap: "16px", marginBottom: "8px" }}>
                                <label style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", cursor: "pointer" }}>
                                    <input type="radio" checked={!useProxy} onChange={() => setUseProxy(false)} style={{ accentColor: "#3b82f6" }} /> 不使用
                                </label>
                                <label style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", cursor: "pointer" }}>
                                    <input type="radio" checked={useProxy} onChange={() => setUseProxy(true)} style={{ accentColor: "#3b82f6" }} /> 使用代理
                                </label>
                            </div>
                            {useProxy && (
                                <input type="text" value={proxyUrl} onChange={e => setProxyUrl(e.target.value)}
                                    placeholder="http://127.0.0.1:7890" style={inputStyle} />
                            )}
                        </div>
                    </div>
                </div>

                {/* 底部操作区 */}
                <div style={{ marginTop: "16px" }}>
                    {!selectedVariant && (
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", color: "#d97706", marginBottom: "12px" }}>
                            <AlertTriangle size={14} /> 请先在左侧选择版本
                        </div>
                    )}
                    {downloadError && (
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", color: "#ef4444", marginBottom: "12px" }}>
                            <AlertTriangle size={14} /> {downloadError}
                        </div>
                    )}
                    {showRestartHint && (
                        <div style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", color: "#16a34a", marginBottom: "12px" }}>
                            <CheckCircle size={14} /> 下载完成，重启 Python 服务后生效
                        </div>
                    )}
                    <button onClick={startDownload} disabled={downloading || !selectedVariant}
                        style={{ ...primaryBtnStyle, width: "100%", justifyContent: "center", opacity: downloading || !selectedVariant ? 0.5 : 1 }}>
                        {downloading ? <Loader size={14} /> : <Download size={14} />}
                        {downloading ? "下载中…" : selectedVariant ? `下载 ${VARIANT_LABELS[selectedVariant] ?? selectedVariant} 版本的 torch` : "请先选择版本"}
                    </button>
                    {downloadWorkflowRunId && (
                        <div style={{ marginTop: "16px" }}>
                            <WorkflowInfoPanel workflowRunId={downloadWorkflowRunId} active={downloading} defaultExpanded onActiveState={handleActiveState} />
                        </div>
                    )}
                </div>

            </div>
        </div>
    );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

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

const secondaryBtnStyle: React.CSSProperties = {
    display: "inline-flex", alignItems: "center", gap: "6px",
    padding: "7px 14px", fontSize: "13px", fontWeight: 500,
    color: "#374151", backgroundColor: "#f3f4f6", border: "1px solid #e5e7eb",
    borderRadius: "8px", cursor: "pointer",
};
