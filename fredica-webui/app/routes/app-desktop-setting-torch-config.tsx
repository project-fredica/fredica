import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, RefreshCw, Download, CheckCircle, Loader, AlertTriangle, ChevronDown, Search } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting-torch-config";
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

    // 所有镜像合并后的 variant 列表（页面进入时查询；null = 尚未加载完成）
    const [allVariants, setAllVariants] = useState<string[] | null>(null);
    const [allVariantsLoading, setAllVariantsLoading] = useState(true);
    const [refreshingCache, setRefreshingCache] = useState(false);
    // 版本筛选
    const [variantFilter, setVariantFilter] = useState("");
    // 分组折叠状态（key = 组名）
    const [groupExpanded, setGroupExpanded] = useState<Record<string, boolean>>({ cuda_new: true, cuda_old: false, rocm: false, cpu: true });
    // per-mirror variant 支持情况（来自 all-mirror-variants 响应）
    const [perMirrorVariants, setPerMirrorVariants] = useState<Record<string, string[]>>({});
    // per-mirror torch 版本号列表（来自 all-mirror-variants 响应）
    // dict[mirror_key][variant] = string[]（降序，第一个为最高版本）
    const [perMirrorTorchVersions, setPerMirrorTorchVersions] = useState<Record<string, Record<string, string[]>>>({});
    // 用户为每个 variant 选择的 torch 版本（空串 = 使用最高版本）
    const [selectedTorchVersions, setSelectedTorchVersions] = useState<Record<string, string>>({});

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

    // install check
    const [checkingInstall, setCheckingInstall] = useState(false);
    const [installCheckResult, setInstallCheckResult] = useState<{ already_ok: boolean; installed_version: string | null } | null>(null);

    // pip command preview
    const [pipCommand, setPipCommand] = useState<string>("");

    // download workflow
    const [downloadWorkflowRunId, setDownloadWorkflowRunId] = useState<string | null>(null);
    const [downloading, setDownloading] = useState(false);
    const [downloadError, setDownloadError] = useState<string | null>(null);
    const [showRestartHint, setShowRestartHint] = useState(false);

    // 历史下载任务列表
    const [historyIds, setHistoryIds] = useState<string[]>([]);
    const [historyTotal, setHistoryTotal] = useState(0);
    const [historyPage, setHistoryPage] = useState(1);
    const HISTORY_PAGE_SIZE = 5;
    const historyTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
    // 用 ref 跟踪当前页，避免 setInterval 闭包捕获旧值
    const historyPageRef = useRef(1);

    // ── Load on mount ──────────────────────────────────────────────────────────

    useEffect(() => {
        loadInfo();
        loadAllMirrorVariants();
        runDetect();
        // 恢复活跃下载任务（页面刷新后 state 丢失场景）
        callBridgeOrNull("get_active_torch_download").then(raw => {
            if (!raw) return;
            try {
                const res = JSON.parse(raw);
                if (res.workflow_run_id) {
                    setDownloadWorkflowRunId(res.workflow_run_id);
                    setDownloading(true);
                }
            } catch { /* ignore */ }
        }).catch(() => {});
    }, []);

    // 历史任务列表：mount 时加载，每 5s 自动刷新
    useEffect(() => {
        loadHistory(1);
        historyTimerRef.current = setInterval(() => loadHistory(historyPageRef.current), 5000);
        return () => { if (historyTimerRef.current) clearInterval(historyTimerRef.current); };
    }, []);

    // 翻页时同步 ref 并重新加载
    useEffect(() => {
        historyPageRef.current = historyPage;
        loadHistory(historyPage);
    }, [historyPage]);

    // reset mirror check cache when variant changes; also deselect CPU-only mirrors for CUDA variants
    useEffect(() => {
        setMirrorCheckResults({});
        if (selectedVariant && selectedVariant !== "cpu" && MIRROR_CPU_ONLY.has(selectedMirrorKey)) {
            setSelectedMirrorKey("official");
        }
        // 自动展开选中 variant 所在的分组
        if (!selectedVariant) return;
        if (selectedVariant.startsWith("rocm")) {
            setGroupExpanded(prev => ({ ...prev, rocm: true }));
        } else if (selectedVariant === "cpu") {
            setGroupExpanded(prev => ({ ...prev, cpu: true }));
        } else if (selectedVariant.startsWith("cu")) {
            const n = parseInt(selectedVariant.replace("cu", ""));
            if (!isNaN(n) && n >= 118) setGroupExpanded(prev => ({ ...prev, cuda_new: true }));
            else setGroupExpanded(prev => ({ ...prev, cuda_old: true }));
        }
    }, [selectedVariant]);

    // fetch supported variants for the selected mirror whenever it changes
    useEffect(() => {
        setSelectedTorchVersions({});
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
                    if (variants && variants.length > 0 && selectedVariant && !variants.includes(selectedVariant)) {
                        const fallback = (recommendedVariant && variants.includes(recommendedVariant))
                            ? recommendedVariant
                            : variants[0];
                        setSelectedVariant(fallback);
                    }
                } catch (e) {
                    print_error({ reason: "解析镜像版本列表失败", err: e, variables: { mirror_key: selectedMirrorKey } });
                    setMirrorSupportedVariants(null);
                }
            })
            .catch(e => {
                print_error({ reason: "获取镜像版本列表异常", err: e, variables: { mirror_key: selectedMirrorKey } });
                setMirrorSupportedVariants(null);
            });
    }, [selectedMirrorKey, useProxy, proxyUrl]);

    // pip command preview
    useEffect(() => {
        if (!selectedVariant || selectedVariant === "custom") {
            setPipCommand("");
            return;
        }
        const effectiveIndexUrl = selectedMirrorKey === "custom"
            ? customMirrorUrl.trim()
            : (MIRROR_URL_FNS[selectedMirrorKey]?.(selectedVariant) ?? "");
        if (!effectiveIndexUrl) { setPipCommand(""); return; }

        const mirrorBestVer = perMirrorTorchVersions[selectedMirrorKey]?.[selectedVariant]?.[0] ?? "";
        const torchVersion = selectedTorchVersions[selectedVariant] || mirrorBestVer;

        const params: Record<string, unknown> = {
            torch_version: torchVersion,
            index_url: effectiveIndexUrl,
            index_url_mode: "replace",
            variant: selectedVariant,
        };
        if (useProxy && proxyUrl.trim()) {
            params.use_proxy = true;
            params.proxy = proxyUrl.trim();
        }
        callBridge("get_torch_pip_command", JSON.stringify(params))
            .then(raw => {
                try { setPipCommand(JSON.parse(raw).command ?? ""); } catch { setPipCommand(""); }
            })
            .catch(() => setPipCommand(""));
    }, [selectedVariant, useProxy, proxyUrl, selectedMirrorKey, customMirrorUrl, selectedTorchVersions, perMirrorTorchVersions]);

    const loadHistory = async (page: number) => {
        try {
            const params = new URLSearchParams({ task_type: "DOWNLOAD_TORCH", page: String(page), page_size: String(HISTORY_PAGE_SIZE) });
            const { resp, data } = await apiFetch(
                `/api/v1/WorkerTaskWfIdListRoute?${params}`,
            );
            if (!resp.ok) { print_error({ reason: `加载历史下载任务失败: HTTP ${resp.status}` }); return; }
            const payload = data as { ids?: string[]; total?: number } | null;
            if (!payload) return;
            setHistoryIds(payload.ids ?? []);
            setHistoryTotal(payload.total ?? 0);
        } catch (e) {
            print_error({ reason: "加载历史下载任务失败", err: e });
        }
    };

    const loadInfo = async () => {
        try {
            const raw = await callBridgeOrNull("get_torch_info");
            if (!raw) return;
            const d = JSON.parse(raw) as TorchInfo & { error?: string };
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
            loadCheckItems();
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
            const raw = await callBridgeOrNull("get_torch_check", JSON.stringify({}));
            if (!raw) return;
            const d = JSON.parse(raw) as { already_ok?: boolean; installed_version?: string | null; error?: string };
            if (d.error) {
                print_error({ reason: `检查 torch 下载状态失败: ${d.error}` });
                return;
            }
            setCheckItems([{ variant: "", downloaded: d.already_ok ?? false, version: d.installed_version ?? null }]);
        } catch (e) {
            print_error({ reason: "检查 torch 下载状态异常", err: e });
        }
    };

    const loadAllMirrorVariants = async () => {
        setAllVariantsLoading(true);
        try {
            const raw = await callBridgeOrNull("get_torch_all_mirror_variants", JSON.stringify({ use_proxy: useProxy, proxy: proxyUrl.trim() }));
            if (!raw) { setAllVariantsLoading(false); return; }
            const res = JSON.parse(raw) as { variants?: string[]; per_mirror?: Record<string, string[]>; per_mirror_torch_versions?: Record<string, Record<string, string[]>>; error?: string };
            if (res.error) {
                print_error({ reason: `查询镜像版本列表失败: ${res.error}` });
                return;
            }
            if (res.variants && res.variants.length > 0) setAllVariants(res.variants);
            if (res.per_mirror) setPerMirrorVariants(res.per_mirror);
            if (res.per_mirror_torch_versions) setPerMirrorTorchVersions(res.per_mirror_torch_versions);
        } catch (e) {
            print_error({ reason: "查询镜像版本列表异常", err: e });
        } finally {
            setAllVariantsLoading(false);
        }
    };

    const refreshMirrorCache = async () => {
        if (refreshingCache) return;
        setRefreshingCache(true);
        setAllVariantsLoading(true);
        try {
            const raw = await callBridgeOrNull("refresh_torch_mirror_cache", JSON.stringify({ use_proxy: useProxy, proxy: proxyUrl.trim() }));
            if (!raw) { setAllVariantsLoading(false); return; }
            const res = JSON.parse(raw) as { variants?: string[]; per_mirror?: Record<string, string[]>; per_mirror_torch_versions?: Record<string, Record<string, string[]>>; error?: string };
            if (res.error) {
                print_error({ reason: `刷新镜像缓存失败: ${res.error}` });
                return;
            }
            if (res.variants && res.variants.length > 0) setAllVariants(res.variants);
            if (res.per_mirror) setPerMirrorVariants(res.per_mirror);
            if (res.per_mirror_torch_versions) setPerMirrorTorchVersions(res.per_mirror_torch_versions);
        } catch (e) {
            print_error({ reason: "刷新镜像缓存异常", err: e });
        } finally {
            setRefreshingCache(false);
            setAllVariantsLoading(false);
        }
    };

    const runInstallCheck = async () => {
        if (checkingInstall) return;
        setCheckingInstall(true);
        try {
            const mirrorBestVer = perMirrorTorchVersions[selectedMirrorKey]?.[selectedVariant]?.[0] ?? "";
            const expectedVersion = selectedVariant && selectedVariant !== "custom"
                ? (selectedTorchVersions[selectedVariant] || mirrorBestVer)
                : "";
            const params = new URLSearchParams();
            if (expectedVersion) params.set("expected_version", expectedVersion);
            const { resp, data } = await apiFetch(
                `/api/v1/TorchInstallCheckRoute${params.size > 0 ? `?${params}` : ""}`,
            );
            if (!resp.ok) { print_error({ reason: `检测 torch 安装状态失败: HTTP ${resp.status}` }); return; }
            const payload = data as { already_ok?: boolean; installed_version?: string | null; error?: string } | null;
            if (!payload) return;
            if (payload.error) { print_error({ reason: `检测 torch 安装状态失败: ${payload.error}` }); return; }
            setInstallCheckResult({ already_ok: payload.already_ok ?? false, installed_version: payload.installed_version ?? null });
        } catch (e) {
            print_error({ reason: "检测 torch 安装状态异常", err: e });
        } finally {
            setCheckingInstall(false);
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

            const mirrorBestVer = perMirrorTorchVersions[selectedMirrorKey]?.[selectedVariant]?.[0] ?? "";
            const torchVersion = selectedTorchVersions[selectedVariant] || mirrorBestVer;

            const raw = await callBridge("download_torch", JSON.stringify({
                variant: selectedVariant,
                torch_version: selectedVariant !== "custom" ? torchVersion : "",
                index_url: effectiveIndexUrl,
                index_url_mode: "replace",
                use_proxy: useProxy,
                proxy: proxyUrl.trim(),
                custom_packages: selectedVariant === "custom" ? customPackages : "",
                custom_index_url: selectedVariant === "custom" ? customIndexUrl : "",
                custom_variant_id: selectedVariant === "custom" ? customVariantId.trim() : "",
            }));
            const res = JSON.parse(raw);
            if (res.error) {
                if (res.error === "TASK_ALREADY_ACTIVE" && res.workflow_run_id) {
                    setDownloadWorkflowRunId(res.workflow_run_id);
                } else {
                    setDownloadError(res.error === "TASK_ALREADY_ACTIVE" ? "下载任务已在进行中" : res.error);
                    setDownloading(false);
                }
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
            // 下载完成后持久化配置
            callBridgeOrNull("save_torch_config", JSON.stringify({
                torch_variant: selectedVariant,
                torch_download_use_proxy: useProxy,
                torch_download_proxy_url: proxyUrl.trim(),
                torch_download_index_url: selectedVariant !== "custom"
                    ? (selectedMirrorKey === "custom" ? customMirrorUrl.trim() : (MIRROR_URL_FNS[selectedMirrorKey]?.(selectedVariant) ?? ""))
                    : "",
                torch_custom_packages: selectedVariant === "custom" ? customPackages : "",
                torch_custom_index_url: selectedVariant === "custom" ? customIndexUrl : "",
                torch_custom_variant_id: selectedVariant === "custom" ? customVariantId.trim() : "",
            })).catch(() => {});
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
        { key: "tuna",     label: "清华 PyPI 镜像" },
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
                                <span><span style={{ color: "#6b7280" }}>推荐：</span><strong>{recommendedVariant}</strong></span>
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

                {/* torch 安装状态卡片 */}
                <div style={{ backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px 20px", marginBottom: "20px", display: "flex", alignItems: "center", gap: "16px", flexWrap: "wrap" }}>
                    <div style={{ flex: 1, minWidth: "200px" }}>
                        {installCheckResult ? (
                            installCheckResult.already_ok ? (
                                <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "13px", color: "#15803d" }}>
                                    <CheckCircle size={16} />
                                    <span>torch 已安装{installCheckResult.installed_version ? `，版本 ${installCheckResult.installed_version}` : ""}</span>
                                </div>
                            ) : (
                                <div style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "13px", color: "#b45309" }}>
                                    <AlertTriangle size={16} />
                                    <span>未检测到 torch{installCheckResult.installed_version ? `（检测到版本 ${installCheckResult.installed_version}，可能不匹配）` : ""}</span>
                                </div>
                            )
                        ) : (
                            <span style={{ fontSize: "13px", color: "#9ca3af" }}>点击右侧按钮检测 torch 是否已安装。</span>
                        )}
                    </div>
                    <button onClick={runInstallCheck} disabled={checkingInstall} style={secondaryBtnStyle}>
                        {checkingInstall ? <Loader size={14} className="animate-spin" /> : <Search size={14} />}
                        {checkingInstall ? "检测中…" : "检测安装状态"}
                    </button>
                </div>

                {/* 两栏布局 */}
                <div style={{ display: "flex", gap: "16px", alignItems: "flex-start" }}>

                    {/* 左栏：版本选择 */}
                    <div style={{ width: "240px", flexShrink: 0, backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px" }}>
                        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "10px" }}>
                            <p style={{ margin: 0, fontSize: "13px", fontWeight: 600, color: "#111827" }}>选择版本</p>
                            <button onClick={refreshMirrorCache} disabled={refreshingCache || allVariantsLoading} title="刷新镜像缓存"
                                style={{ background: "none", border: "none", cursor: refreshingCache || allVariantsLoading ? "not-allowed" : "pointer", padding: "2px", color: "#6b7280", display: "flex", opacity: refreshingCache || allVariantsLoading ? 0.4 : 1 }}>
                                <RefreshCw size={13} className={refreshingCache ? "animate-spin" : ""} />
                            </button>
                        </div>
                        <p style={{ margin: "0 0 10px", fontSize: "11px", color: "#9ca3af" }}>
                            当前生效：<strong>{info.torch_variant || "未设置"}</strong>
                        </p>
                        {mirrorSupportedVariants !== null && (
                            <p style={{ margin: "0 0 8px", fontSize: "10px", color: "#6b7280" }}>
                                ✓ / ✗ 表示所选镜像是否提供该版本
                            </p>
                        )}
                        {/* 版本筛选 */}
                        <input
                            type="text" value={variantFilter} onChange={e => setVariantFilter(e.target.value)}
                            placeholder="筛选版本…"
                            style={{ ...inputStyle, fontSize: "12px", padding: "5px 8px", marginBottom: "8px" }}
                        />
                        {allVariantsLoading && (
                            <div style={{ margin: "8px 0", padding: "10px 12px", backgroundColor: "#eff6ff", border: "1px solid #bfdbfe", borderRadius: "8px", display: "flex", alignItems: "center", gap: "8px" }}>
                                <Loader size={15} className="animate-spin" style={{ color: "#3b82f6", flexShrink: 0 }} />
                                <span style={{ fontSize: "12px", color: "#1d4ed8", fontWeight: 500 }}>
                                    {refreshingCache ? "正在刷新镜像缓存…" : "查询镜像版本中…"}
                                </span>
                            </div>
                        )}
                        <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                            {allVariants === null && !allVariantsLoading && (
                                <div style={{ padding: "10px 12px", backgroundColor: "#fef3c7", border: "1px solid #fcd34d", borderRadius: "8px", fontSize: "12px", color: "#92400e" }}>
                                    查询失败，请点击右上角刷新按钮重试
                                </div>
                            )}
                            {(() => {
                                const filter = variantFilter.trim().toLowerCase();
                                const variants = allVariants ?? [];

                                // 分组定义
                                const CUDA_NEW_CUTOFF = ["cu118", "cu121", "cu124", "cu126", "cu128", "cu129", "cu130"];
                                const groups: { key: string; label: string; items: string[] }[] = [
                                    {
                                        key: "cuda_new",
                                        label: "CUDA（新版，推荐）",
                                        items: variants.filter(v => v.startsWith("cu") && (
                                            CUDA_NEW_CUTOFF.includes(v) ||
                                            (() => { const n = parseInt(v.replace("cu", "")); return !isNaN(n) && n >= 118; })()
                                        )),
                                    },
                                    {
                                        key: "cuda_old",
                                        label: "CUDA（旧版）",
                                        items: variants.filter(v => v.startsWith("cu") && !(
                                            CUDA_NEW_CUTOFF.includes(v) ||
                                            (() => { const n = parseInt(v.replace("cu", "")); return !isNaN(n) && n >= 118; })()
                                        )),
                                    },
                                    {
                                        key: "rocm",
                                        label: "ROCm（AMD GPU，仅 Linux）",
                                        items: variants.filter(v => v.startsWith("rocm")),
                                    },
                                    {
                                        key: "cpu",
                                        label: "CPU only",
                                        items: variants.filter(v => v === "cpu"),
                                    },
                                ];

                                const renderVariantItem = (v: string) => {
                                    const downloaded = isDownloaded(v);
                                    const isRec = v === recommendedVariant;
                                    const isSelected = selectedVariant === v;
                                    const supportedForMirror = selectedMirrorKey !== "official" && selectedMirrorKey !== "custom"
                                        ? (perMirrorVariants[selectedMirrorKey] ?? mirrorSupportedVariants)
                                        : null;
                                    const onMirror = supportedForMirror ? supportedForMirror.includes(v) : null;
                                    const isDisabled = onMirror === false;
                                    const mirrorTorchVers: string[] = perMirrorTorchVersions[selectedMirrorKey]?.[v] ?? [];
                                    const mirrorTorchVerBest = mirrorTorchVers[0] ?? "";
                                    const selectedTorchVer = selectedTorchVersions[v] ?? "";
                                    return (
                                        <div key={v}>
                                            <label style={{ display: "flex", alignItems: "center", gap: "8px", cursor: isDisabled ? "not-allowed" : "pointer", padding: "5px 8px", borderRadius: "6px", backgroundColor: isSelected ? "#eff6ff" : "transparent", border: `1px solid ${isSelected ? "#3b82f6" : "transparent"}`, opacity: isDisabled ? 0.4 : 1 }}>
                                                <input
                                                    type="radio" name="variant" value={v} checked={isSelected} disabled={isDisabled}
                                                    onChange={() => { if (!isDisabled) { setSelectedVariant(v); setCustomExpanded(false); } }}
                                                    style={{ accentColor: "#3b82f6", flexShrink: 0 }}
                                                />
                                                <span style={{ flex: 1, fontSize: "12px", color: isDisabled ? "#9ca3af" : "#111827" }}>
                                                    {v}
                                                    {isRec && <span style={{ marginLeft: "4px", fontSize: "10px", color: "#2563eb", fontWeight: 600 }}>推荐</span>}
                                                    {mirrorTorchVerBest && onMirror !== false && <span style={{ marginLeft: "4px", fontSize: "10px", color: "#6b7280" }}>{mirrorTorchVerBest}</span>}
                                                </span>
                                                {onMirror === true && <span style={{ fontSize: "10px", color: "#16a34a" }}>✓</span>}
                                                {onMirror === false && <span style={{ fontSize: "10px", color: "#d1d5db" }}>✗</span>}
                                                {onMirror === null && (downloaded ? <CheckCircle size={12} color="#22c55e" /> : null)}
                                            </label>
                                            {isSelected && mirrorTorchVers.length > 1 && (
                                                <div style={{ paddingLeft: "24px", paddingBottom: "4px" }}>
                                                    <select
                                                        value={mirrorTorchVers.includes(selectedTorchVer) ? selectedTorchVer : mirrorTorchVerBest}
                                                        onChange={e => setSelectedTorchVersions(prev => ({ ...prev, [v]: e.target.value }))}
                                                        style={{ fontSize: "11px", padding: "2px 4px", border: "1px solid #d1d5db", borderRadius: "4px", color: "#374151", backgroundColor: "#fff" }}
                                                    >
                                                        {mirrorTorchVers.map(ver => (
                                                            <option key={ver} value={ver}>torch {ver}{ver === mirrorTorchVerBest ? " (最新)" : ""}</option>
                                                        ))}
                                                    </select>
                                                </div>
                                            )}
                                        </div>
                                    );
                                };

                                return groups.map(group => {
                                    const filtered = group.items.filter(v =>
                                        !filter || v.toLowerCase().includes(filter)
                                    );
                                    if (filtered.length === 0) return null;
                                    // 筛选时强制展开
                                    const expanded = filter ? true : (groupExpanded[group.key] ?? false);
                                    // 组内是否有选中项
                                    const hasSelected = filtered.includes(selectedVariant);
                                    // 当前源支持的数量（custom 时为 null，不显示分子）
                                    // official 源用 allVariants 本身（即全部支持）；其他镜像用 perMirrorVariants
                                    const mirrorList = selectedMirrorKey === "custom"
                                        ? null
                                        : selectedMirrorKey === "official"
                                            ? variants
                                            : (perMirrorVariants[selectedMirrorKey] ?? mirrorSupportedVariants);
                                    const mirrorCount = mirrorList
                                        ? group.items.filter(v => mirrorList.includes(v)).length
                                        : null;
                                    const countLabel = mirrorCount !== null
                                        ? `${mirrorCount}/${group.items.length}`
                                        : `${group.items.length}`;
                                    return (
                                        <div key={group.key} style={{ marginBottom: "2px" }}>
                                            <button
                                                type="button"
                                                onClick={() => setGroupExpanded(prev => ({ ...prev, [group.key]: !prev[group.key] }))}
                                                style={{ width: "100%", display: "flex", alignItems: "center", gap: "4px", background: "none", border: "none", cursor: "pointer", padding: "4px 6px", borderRadius: "4px", textAlign: "left", backgroundColor: hasSelected ? "#eff6ff" : "#f3f4f6" }}
                                            >
                                                <ChevronDown size={11} style={{ color: "#6b7280", flexShrink: 0, transform: expanded ? "none" : "rotate(-90deg)", transition: "transform 0.15s" }} />
                                                <span style={{ fontSize: "11px", fontWeight: 600, color: hasSelected ? "#2563eb" : "#6b7280", flex: 1 }}>{group.label}</span>
                                                <span style={{ fontSize: "10px", color: "#9ca3af" }}>{countLabel}</span>
                                            </button>
                                            {expanded && (
                                                <div style={{ paddingLeft: "8px", marginTop: "2px", display: "flex", flexDirection: "column", gap: "1px" }}>
                                                    {filtered.map(renderVariantItem)}
                                                </div>
                                            )}
                                        </div>
                                    );
                                });
                            })()}
                            {/* 自定义版本 */}
                            <label style={{ display: "flex", alignItems: "center", gap: "8px", cursor: "pointer", padding: "6px 8px", borderRadius: "6px", backgroundColor: selectedVariant === "custom" ? "#eff6ff" : "transparent", border: `1px solid ${selectedVariant === "custom" ? "#3b82f6" : "transparent"}`, marginTop: "4px" }}>
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
                            <p style={{ margin: "0 0 6px", fontSize: "13px", fontWeight: 600, color: "#111827" }}>代理设置</p>
                            {selectedMirrorKey === "official"
                                ? <p style={{ margin: "0 0 10px", fontSize: "12px", color: "#d97706" }}>官方源在国内访问较慢，推荐配置代理</p>
                                : <p style={{ margin: "0 0 10px", fontSize: "12px", color: "#6b7280" }}>国内镜像源一般无需代理</p>
                            }
                            <div style={{ display: "flex", alignItems: "center", gap: "16px", marginBottom: "8px" }}>
                                <label style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", cursor: "pointer" }}>
                                    <input type="radio" checked={!useProxy} onChange={() => setUseProxy(false)} style={{ accentColor: "#3b82f6" }} /> 不使用
                                </label>
                                <label style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", cursor: "pointer" }}>
                                    <input type="radio" checked={useProxy} onChange={() => setUseProxy(true)} style={{ accentColor: "#3b82f6" }} /> 使用代理
                                </label>
                            </div>
                            {useProxy && (
                                <div style={{ display: "flex", gap: "6px", alignItems: "center" }}>
                                    <input type="text" value={proxyUrl} onChange={e => setProxyUrl(e.target.value)}
                                        placeholder="http://127.0.0.1:7890"
                                        style={{ ...inputStyle, flex: 1, color: proxyUrl ? undefined : "#9ca3af" }} />
                                    <button
                                        style={{ ...secondaryBtnStyle, fontSize: "12px", padding: "5px 10px", whiteSpace: "nowrap" }}
                                        onClick={async () => {
                                            try {
                                                const raw = await callBridgeOrNull("get_system_proxy", "{}");
                                                if (!raw) return;
                                                const res = JSON.parse(raw) as { proxy_url?: string };
                                                const detected = res.proxy_url ?? "";
                                                if (detected) {
                                                    setProxyUrl(detected);
                                                    setUseProxy(true);
                                                }
                                            } catch { /* BridgeUnavailableError etc, silently ignore */ }
                                        }}
                                    >使用检测到的代理</button>
                                </div>
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
                        {downloading ? "下载中…" : selectedVariant ? `下载 ${selectedVariant} 版本的 torch` : "请先选择版本"}
                    </button>
                    {downloadWorkflowRunId && (
                        <div style={{ marginTop: "16px" }}>
                            <WorkflowInfoPanel workflowRunId={downloadWorkflowRunId} active={downloading} defaultExpanded onActiveState={handleActiveState} />
                        </div>
                    )}
                </div>

                {/* 历史下载任务列表 */}
                {historyTotal > 0 && (
                    <div style={{ marginTop: "24px", backgroundColor: "#fff", border: "1px solid #e5e7eb", borderRadius: "12px", padding: "16px 20px" }}>
                        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "12px" }}>
                            <p style={{ margin: 0, fontSize: "13px", fontWeight: 600, color: "#111827" }}>
                                历史下载任务
                                <span style={{ marginLeft: "6px", fontSize: "12px", color: "#9ca3af", fontWeight: 400 }}>共 {historyTotal} 条</span>
                            </p>
                            <button onClick={() => loadHistory(historyPage)} style={{ background: "none", border: "none", cursor: "pointer", padding: "2px", color: "#6b7280", display: "flex" }} title="刷新">
                                <RefreshCw size={13} />
                            </button>
                        </div>
                        <div style={{ display: "flex", flexDirection: "column", gap: "12px" }}>
                            {historyIds.map(wfId => (
                                <WorkflowInfoPanel key={wfId} workflowRunId={wfId} active={true} defaultExpanded={false} />
                            ))}
                        </div>
                        {/* 分页控件 */}
                        {historyTotal > HISTORY_PAGE_SIZE && (
                            <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: "8px", marginTop: "12px" }}>
                                <button
                                    disabled={historyPage <= 1}
                                    onClick={() => setHistoryPage(p => p - 1)}
                                    style={{ ...secondaryBtnStyle, padding: "4px 10px", opacity: historyPage <= 1 ? 0.4 : 1 }}
                                >上一页</button>
                                <span style={{ fontSize: "12px", color: "#6b7280" }}>
                                    {historyPage} / {Math.ceil(historyTotal / HISTORY_PAGE_SIZE)}
                                </span>
                                <button
                                    disabled={historyPage >= Math.ceil(historyTotal / HISTORY_PAGE_SIZE)}
                                    onClick={() => setHistoryPage(p => p + 1)}
                                    style={{ ...secondaryBtnStyle, padding: "4px 10px", opacity: historyPage >= Math.ceil(historyTotal / HISTORY_PAGE_SIZE) ? 0.4 : 1 }}
                                >下一页</button>
                            </div>
                        )}
                    </div>
                )}

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
