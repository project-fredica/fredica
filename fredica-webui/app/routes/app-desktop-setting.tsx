import { useEffect, useState } from "react";
import type React from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, RefreshCw, Save, CheckCircle, XCircle, Loader } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting";
import { print_error } from "~/util/error_handler";
import { callBridge, BridgeUnavailableError, openExternalUrl } from "../util/bridge";
import { json_parse } from "~/util/json";
import { PasswordInput } from "~/components/ui/PasswordInput";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "桌面服务器设置 - Fredica" },
        { name: "description", content: "Fredica 桌面端服务器设置" },
    ];
}

interface SettingSection {
    title: string;
    items: SettingItem[];
}

type SettingItem =
    | { type: "text"; key: string; label: string; description: string; defaultValue: string; placeholder?: string }
    | { type: "number"; key: string; label: string; description: string; defaultValue: number; min?: number; max?: number }
    | { type: "select"; key: string; label: string; description: string; defaultValue: string; options: { label: string; value: string }[] }
    | { type: "toggle"; key: string; label: string; description: string; defaultValue: boolean };

const settingSections: SettingSection[] = [
    {
        title: "服务配置",
        items: [
            {
                type: "number",
                key: "server_port",
                label: "本地服务端口",
                description: "Fredica 后端 HTTP 服务监听的端口号，修改后需重启应用生效。",
                defaultValue: 7631,
                min: 1024,
                max: 65535,
            },
            {
                type: "text",
                key: "data_dir",
                label: "数据存储路径",
                description: "资源文件、数据库及缓存的本地存储根目录。",
                defaultValue: "",
                placeholder: "留空则使用默认路径（用户主目录下 .fredica/）",
            },
        ],
    },
    {
        title: "启动行为",
        items: [
            {
                type: "toggle",
                key: "auto_start",
                label: "开机自启动",
                description: "系统登录时自动在后台启动 Fredica 服务。",
                defaultValue: false,
            },
            {
                type: "toggle",
                key: "start_minimized",
                label: "启动时最小化到托盘",
                description: "启动后不显示主窗口，仅在系统托盘运行。",
                defaultValue: false,
            },
            {
                type: "toggle",
                key: "open_browser_on_start",
                label: "启动时自动打开浏览器",
                description: "服务就绪后自动在默认浏览器中打开 Web 界面。",
                defaultValue: true,
            },
        ],
    },
    {
        title: "外观与语言",
        items: [
            {
                type: "select",
                key: "theme",
                label: "主题",
                description: "选择界面配色方案。",
                defaultValue: "system",
                options: [
                    { label: "跟随系统", value: "system" },
                    { label: "浅色", value: "light" },
                    { label: "深色", value: "dark" },
                ],
            },
            {
                type: "select",
                key: "language",
                label: "语言",
                description: "界面显示语言。",
                defaultValue: "zh-CN",
                options: [
                    { label: "简体中文", value: "zh-CN" },
                    { label: "English", value: "en" },
                ],
            },
        ],
    },
    {
        title: "网络代理",
        items: [
            {
                type: "toggle",
                key: "proxy_enabled",
                label: "启用代理",
                description: "为资源下载和 API 请求启用 HTTP 代理。",
                defaultValue: false,
            },
            {
                type: "text",
                key: "proxy_url",
                label: "代理地址",
                description: "HTTP 代理服务器地址，格式：http://host:port",
                defaultValue: "",
                placeholder: "http://127.0.0.1:7890",
            },
        ],
    },
    // {
    //     title: "第三方服务",
    //     items: [
    //         {
    //             type: "text",
    //             key: "rsshub_url",
    //             label: "RSSHub 地址",
    //             description: "自托管或公共 RSSHub 实例地址，用于生成 RSS 订阅源。",
    //             defaultValue: "",
    //             placeholder: "https://rsshub.app",
    //         },
    //     ],
    // },
    {
        title: "硬件加速",
        items: [
            {
                type: "text",
                key: "ffmpeg_path",
                label: "FFmpeg 路径",
                description: "FFmpeg 可执行文件的绝对路径。留空则自动搜索系统路径。",
                defaultValue: "",
                placeholder: "留空则自动发现（PATH、常见安装目录等）",
            },
            {
                type: "select",
                key: "ffmpeg_hw_accel",
                label: "硬件加速方案",
                description: "视频转码时使用的硬件加速器。自动模式使用检测到的最优方案。",
                defaultValue: "auto",
                options: [
                    { label: "自动（推荐）", value: "auto" },
                    { label: "NVIDIA CUDA", value: "cuda" },
                    { label: "AMD AMF", value: "amf" },
                    { label: "Intel QSV", value: "qsv" },
                    { label: "Apple VideoToolbox", value: "videotoolbox" },
                    { label: "CPU (libx264)", value: "cpu" },
                ],
            },
        ],
    },
];

function buildInitialValues(): Record<string, string | number | boolean> {
    const values: Record<string, string | number | boolean> = {};
    for (const section of settingSections) {
        for (const item of section.items) {
            values[item.key] = item.defaultValue;
        }
    }
    // B站账号字段（不在 settingSections 中，单独初始化）
    for (const key of ["bilibili_sessdata", "bilibili_bili_jct", "bilibili_buvid3", "bilibili_buvid4",
                        "bilibili_dedeuserid", "bilibili_ac_time_value", "bilibili_proxy",
                        "webserver_auth_token"]) {
        values[key] = "";
    }
    return values;
}

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();
    const [values, setValues] = useState<Record<string, string | number | boolean>>(buildInitialValues);
    const [saved, setSaved] = useState(false);
    const [isDirty, setIsDirty] = useState(false);
    const [showLeaveModal, setShowLeaveModal] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [deviceInfo, setDeviceInfo] = useState<any>(null);
    const [ffmpegProbe, setFfmpegProbe] = useState<any>(null);
    const [detecting, setDetecting] = useState(false);

    // B站账号有效性检测状态
    type CredStatus = "idle" | "checking" | "valid" | "invalid" | "unconfigured" | "error";
    const [credStatus, setCredStatus] = useState<CredStatus>("idle");
    const [credMessage, setCredMessage] = useState<string>("");

    // B站账号刷新状态
    type RefreshStatus = "idle" | "revealing" | "refreshing" | "refreshed" | "no_refresh" | "error";
    const [refreshStatus, setRefreshStatus] = useState<RefreshStatus>("idle");
    const [refreshMessage, setRefreshMessage] = useState<string>("");
    // 强制展开所有 B站账号密码字段（刷新前让用户看到当前值）
    const [showBilibiliFields, setShowBilibiliFields] = useState<Record<string, boolean>>({});

    /** 通过 kmpJsBridge 检测当前表单中的 B 站账号登录态是否有效 */
    const handleCheckCredential = async () => {
        // 重置刷新状态，避免两个操作的结果同时显示
        setRefreshStatus("idle");
        setRefreshMessage("");
        setCredStatus("checking");
        setCredMessage("");

        // 传入当前表单中的凭据值（未保存的编辑也能被检测）
        const credParams = JSON.stringify({
            sessdata: values["bilibili_sessdata"] || "",
            bili_jct: values["bilibili_bili_jct"] || "",
            buvid3: values["bilibili_buvid3"] || "",
            buvid4: values["bilibili_buvid4"] || "",
            dedeuserid: values["bilibili_dedeuserid"] || "",
            ac_time_value: values["bilibili_ac_time_value"] || "",
            proxy: values["bilibili_proxy"] || "",
        });

        try {
            console.debug("[app-desktop-setting] handleCheckCredential: 调用 check_bilibili_credential");
            const raw = await callBridge("check_bilibili_credential", credParams);
            console.debug("[app-desktop-setting] check_bilibili_credential raw:", raw);
            const r = json_parse<{ configured: boolean; valid: boolean; message: string }>(raw);
            console.debug("[app-desktop-setting] check_bilibili_credential 解析结果:", r);
            if (!r.configured) {
                setCredStatus("unconfigured");
                setCredMessage("未配置账号");
            } else if (r.valid) {
                setCredStatus("valid");
                setCredMessage("账号有效");
            } else {
                setCredStatus("invalid");
                setCredMessage(r.message || "账号已失效");
            }
        } catch (e) {
            const errMsg = e instanceof BridgeUnavailableError ? "此功能仅在桌面端 App 中可用" : "检测失败，请确认服务已启动";
            setCredStatus("error");
            setCredMessage(errMsg);
            print_error({ reason: errMsg, err: e });
        }
    };

    /**
     * 通过 kmpJsBridge 尝试刷新 B 站账号凭据。
     *
     * 流程：
     *   1. 展开所有密码字段（用户看到当前值，感知"即将被更新"）
     *   2. 等待 700ms 间隔（给用户视觉感知时间）
     *   3. 调用 bridge try_refresh_bilibili_credential（传入当前表单值）
     *   4. 刷新成功 → 将新凭据值更新到表单（用户仍需点"保存"才持久化）
     *      无需刷新 → 提示有效；失败 → 通过 print_error 弹 Toast 通知
     *
     * 注意：此功能仅在内嵌 WebView（kmpJsBridge 可用）环境下工作。
     */
    const handleTryRefresh = async () => {
        // 重置检测状态，避免两个操作的结果同时显示
        setCredStatus("idle");
        setCredMessage("");

        // Step 1: 先展开所有密码字段，让用户看到当前凭据值
        setShowBilibiliFields({
            bilibili_sessdata: true,
            bilibili_bili_jct: true,
            bilibili_buvid3: true,
            bilibili_buvid4: true,
            bilibili_dedeuserid: true,
            bilibili_ac_time_value: true,
        });
        setRefreshStatus("revealing");
        setRefreshMessage("");

        // Step 2: 等待 700ms，给用户视觉感知"字段已展开，即将更新"的时间
        await new Promise<void>(resolve => setTimeout(resolve, 700));

        setRefreshStatus("refreshing");
        console.debug("[app-desktop-setting] handleTryRefresh: 调用 try_refresh_bilibili_credential");

        // Step 3: 传入当前表单值，让 Kotlin 侧直接使用（不再回退读 AppConfig）
        const credParams = JSON.stringify({
            sessdata: values["bilibili_sessdata"] || "",
            bili_jct: values["bilibili_bili_jct"] || "",
            buvid3: values["bilibili_buvid3"] || "",
            buvid4: values["bilibili_buvid4"] || "",
            dedeuserid: values["bilibili_dedeuserid"] || "",
            ac_time_value: values["bilibili_ac_time_value"] || "",
            proxy: values["bilibili_proxy"] || "",
        });

        try {
            const raw = await callBridge("try_refresh_bilibili_credential", credParams);
            // bridge 回调：注意 kmpJsBridge 会将字符串包裹在 JS 单引号字面量中，
            // 若 raw 中含有未转义的单引号（如异常描述），会导致 JSON.parse 失败。
            // MyJsMessageHandler.kt 中已统一转义，此处应可正常解析。
            console.debug("[app-desktop-setting] try_refresh_bilibili_credential raw:", raw);
            const r = json_parse<{
                success: boolean;
                refreshed: boolean;
                message: string;
                sessdata?: string;
                bili_jct?: string;
                buvid3?: string;
                buvid4?: string;
                dedeuserid?: string;
                ac_time_value?: string;
                error?: string;
            }>(raw);
            console.debug("[app-desktop-setting] try_refresh_bilibili_credential 解析结果:", r);

            if (r.success && r.refreshed) {
                // Step 4: 将新凭据值写入表单（此时字段仍展开，用户可直接看到变化）
                if (r.sessdata !== undefined) handleChange("bilibili_sessdata", r.sessdata);
                if (r.bili_jct !== undefined) handleChange("bilibili_bili_jct", r.bili_jct);
                if (r.buvid3 !== undefined) handleChange("bilibili_buvid3", r.buvid3);
                if (r.buvid4 !== undefined) handleChange("bilibili_buvid4", r.buvid4 ?? "");
                if (r.dedeuserid !== undefined) handleChange("bilibili_dedeuserid", r.dedeuserid);
                if (r.ac_time_value !== undefined) handleChange("bilibili_ac_time_value", r.ac_time_value ?? "");
                setRefreshStatus("refreshed");
                setRefreshMessage("刷新成功，请检查后点保存");
                console.debug("[app-desktop-setting] handleTryRefresh: 凭据已更新到表单");
            } else if (r.success && !r.refreshed) {
                // check_refresh() 返回 false：账号有效，bilibili-api 判断无需刷新
                setRefreshStatus("no_refresh");
                setRefreshMessage(r.message || "账号有效，无需刷新");
                console.debug("[app-desktop-setting] handleTryRefresh: 无需刷新");
            } else {
                // Python 返回 success=false（含"未配置"/"账号未登录"/"刷新失败"等情况）
                const errMsg = r.message || "刷新失败";
                setRefreshStatus("error");
                setRefreshMessage(errMsg);
                print_error({ reason: `B 站凭据刷新失败：${errMsg}`, variables: { raw, r } });
            }
        } catch (e) {
            const errMsg = e instanceof BridgeUnavailableError
                ? "此功能仅在桌面端 App 中可用"
                : "解析 Bridge 响应失败";
            setRefreshStatus("error");
            setRefreshMessage(errMsg);
            print_error({ reason: errMsg, err: e });
        }
    };

    useEffect(() => {
        (async () => {
            try {
                const result = await callBridge("get_app_config");
                const config = json_parse<Record<string, string | number | boolean>>(result);
                setValues(prev => ({ ...prev, ...config }));
                setLoadError(null);
            } catch (e) {
                setLoadError(e instanceof BridgeUnavailableError
                    ? "kmpJsBridge 不可用，请在桌面端环境中使用。当前显示默认值。"
                    : "解析配置失败：" + e);
            }
            try {
                const result = await callBridge("get_device_info");
                const info = json_parse<any>(result);
                if (info?.device_info_json) {
                    setDeviceInfo(typeof info.device_info_json === "string"
                        ? json_parse<any>(info.device_info_json) : info.device_info_json);
                }
                if (info?.ffmpeg_probe_json) {
                    setFfmpegProbe(typeof info.ffmpeg_probe_json === "string"
                        ? json_parse<any>(info.ffmpeg_probe_json) : info.ffmpeg_probe_json);
                }
            } catch (_) { /* device info optional */ }
        })();
    }, []);

    const handleChange = (key: string, value: string | number | boolean) => {
        setValues((prev) => ({ ...prev, [key]: value }));
        setSaved(false);
        setIsDirty(true);
    };

    const handleSave = async () => {
        try {
            const result = await callBridge("save_app_config", JSON.stringify(values));
            const updated = json_parse<Record<string, string | number | boolean>>(result);
            setValues(prev => ({ ...prev, ...updated }));
            setSaved(true);
            setIsDirty(false);
            setTimeout(() => setSaved(false), 2000);
        } catch (e) {
            print_error({ reason: "保存设置失败", err: e });
        }
    };

    const handleDetect = async () => {
        setDetecting(true);
        try {
            const result = await callBridge("run_ffmpeg_detect");
            const info = json_parse<any>(result);
            if (info?.error) { print_error({ reason: `设备检测失败: ${info.error}` }); return; }
            if (info?.device_info_json) {
                setDeviceInfo(typeof info.device_info_json === "string"
                    ? json_parse<any>(info.device_info_json) : info.device_info_json);
            }
            if (info?.ffmpeg_probe_json) {
                setFfmpegProbe(typeof info.ffmpeg_probe_json === "string"
                    ? json_parse<any>(info.ffmpeg_probe_json) : info.ffmpeg_probe_json);
            }
        } catch (e) {
            print_error({ reason: "设备检测失败", err: e });
        } finally {
            setDetecting(false);
        }
    };

    return (
        <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", fontFamily: "system-ui, sans-serif" }}>
            {/* 离开确认模态框 */}
            {showLeaveModal && (
                <div style={{ position: "fixed", inset: 0, zIndex: 100, display: "flex", alignItems: "center", justifyContent: "center" }}>
                    <div style={{ position: "absolute", inset: 0, backgroundColor: "rgba(0,0,0,0.4)" }} onClick={() => setShowLeaveModal(false)} />
                    <div style={{ position: "relative", backgroundColor: "#fff", borderRadius: "12px", padding: "24px", width: "320px", boxShadow: "0 8px 32px rgba(0,0,0,0.15)" }}>
                        <h3 style={{ margin: "0 0 8px", fontSize: "16px", fontWeight: 600, color: "#111827" }}>还未保存</h3>
                        <p style={{ margin: "0 0 20px", fontSize: "14px", color: "#6b7280" }}>当前设置已修改但尚未保存，是否保存后再返回？</p>
                        <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                            <button
                                onClick={async () => { await handleSave(); navigate("/app-desktop-home"); }}
                                style={{ padding: "9px 16px", fontSize: "14px", fontWeight: 500, color: "#fff", backgroundColor: "#7c3aed", border: "none", borderRadius: "8px", cursor: "pointer" }}
                            >
                                保存设置并返回
                            </button>
                            <button
                                onClick={() => { setShowLeaveModal(false); navigate("/app-desktop-home"); }}
                                style={{ padding: "9px 16px", fontSize: "14px", fontWeight: 500, color: "#374151", backgroundColor: "#f3f4f6", border: "none", borderRadius: "8px", cursor: "pointer" }}
                            >
                                直接返回
                            </button>
                            <button
                                onClick={() => setShowLeaveModal(false)}
                                style={{ padding: "9px 16px", fontSize: "14px", fontWeight: 500, color: "#6b7280", backgroundColor: "transparent", border: "1px solid #d1d5db", borderRadius: "8px", cursor: "pointer" }}
                            >
                                取消返回
                            </button>
                        </div>
                    </div>
                </div>
            )}
            {/* 顶部导航 */}
            <div style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "16px 24px",
                backgroundColor: "#fff",
                borderBottom: "1px solid #e5e7eb",
                position: "sticky",
                top: 0,
                zIndex: 10,
            }}>
                <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
                    <button
                        onClick={() => isDirty ? setShowLeaveModal(true) : navigate("/app-desktop-home")}
                        style={{
                            display: "flex",
                            alignItems: "center",
                            gap: "6px",
                            padding: "8px 12px",
                            fontSize: "14px",
                            color: "#374151",
                            backgroundColor: "transparent",
                            border: "1px solid #d1d5db",
                            borderRadius: "8px",
                            cursor: "pointer",
                        }}
                    >
                        <ArrowLeft size={16} />
                        返回
                    </button>
                    <h1 style={{ fontSize: "18px", fontWeight: 700, color: "#111827", margin: 0 }}>桌面服务器设置</h1>
                </div>
                <button
                    onClick={handleSave}
                    style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "6px",
                        padding: "8px 20px",
                        fontSize: "14px",
                        fontWeight: 600,
                        color: "#fff",
                        backgroundColor: saved ? "#16a34a" : "#2563eb",
                        border: "none",
                        borderRadius: "8px",
                        cursor: "pointer",
                        transition: "background-color 0.2s",
                    }}
                >
                    <Save size={16} />
                    {saved ? "已保存" : "保存设置"}
                </button>
            </div>

            {/* 设置内容 */}
            <div style={{ maxWidth: "720px", margin: "0 auto", padding: "24px 16px" }}>
                {loadError && (
                    <div style={{
                        padding: "12px 16px",
                        marginBottom: "16px",
                        backgroundColor: "#fef2f2",
                        border: "1px solid #fecaca",
                        borderRadius: "8px",
                        fontSize: "13px",
                        color: "#b91c1c",
                    }}>
                        {loadError}
                    </div>
                )}
                {settingSections.map((section) => (
                    <div key={section.title} style={{
                        backgroundColor: "#fff",
                        border: "1px solid #e5e7eb",
                        borderRadius: "12px",
                        marginBottom: "20px",
                        overflow: "hidden",
                    }}>
                        <div style={{
                            padding: "14px 20px",
                            borderBottom: "1px solid #f3f4f6",
                            backgroundColor: "#f9fafb",
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "space-between",
                        }}>
                            <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase", letterSpacing: "0.05em" }}>
                                {section.title}
                            </h2>
                            {section.title === "硬件加速" && (
                                <button
                                    onClick={handleDetect}
                                    disabled={detecting}
                                    style={{
                                        display: "flex", alignItems: "center", gap: "4px",
                                        padding: "4px 10px", fontSize: "12px",
                                        color: detecting ? "#9ca3af" : "#2563eb",
                                        backgroundColor: "transparent",
                                        border: `1px solid ${detecting ? "#d1d5db" : "#bfdbfe"}`,
                                        borderRadius: "6px", cursor: detecting ? "not-allowed" : "pointer",
                                    }}
                                >
                                    <RefreshCw size={12} style={{ animation: detecting ? "spin 1s linear infinite" : "none" }} />
                                    {detecting ? "检测中..." : "刷新检测"}
                                </button>
                            )}
                        </div>
                        {section.title === "硬件加速" && (deviceInfo || ffmpegProbe) && (
                            <div style={{ padding: "16px 20px", borderBottom: "1px solid #f3f4f6", backgroundColor: "#fafafa" }}>
                                {deviceInfo && (() => {
                                    const GPU_CAPS: Array<{
                                        key: string;
                                        label: string;
                                        detail?: string;
                                        activeColor: string;
                                        activeText: string;
                                    }> = [
                                        {
                                            key: "cuda",
                                            label: "CUDA (NVIDIA)",
                                            detail: deviceInfo.gpu?.cuda?.devices?.[0]?.name,
                                            activeColor: "#dcfce7", activeText: "#166534",
                                        },
                                        { key: "rocm", label: "ROCm (AMD)", activeColor: "#fce7f3", activeText: "#9d174d" },
                                        { key: "qsv", label: "Intel QSV", activeColor: "#dbeafe", activeText: "#1e40af" },
                                        { key: "d3d11va", label: "D3D11VA", activeColor: "#f3e8ff", activeText: "#7e22ce" },
                                        { key: "videotoolbox", label: "VideoToolbox", activeColor: "#fee2e2", activeText: "#991b1b" },
                                        { key: "vaapi", label: "VAAPI", activeColor: "#fef9c3", activeText: "#713f12" },
                                    ];
                                    return (
                                        <div style={{ marginBottom: "12px" }}>
                                            <p style={{ margin: "0 0 8px 0", fontSize: "13px", fontWeight: 600, color: "#374151" }}>设备 GPU 能力</p>
                                            <div style={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
                                                {GPU_CAPS.map(({ key, label, detail, activeColor, activeText }) => {
                                                    const available = !!deviceInfo.gpu?.[key]?.available;
                                                    return (
                                                        <span
                                                            key={key}
                                                            style={{
                                                                padding: "2px 8px",
                                                                fontSize: "12px",
                                                                backgroundColor: available ? activeColor : "#f3f4f6",
                                                                color: available ? activeText : "#9ca3af",
                                                                borderRadius: "4px",
                                                            }}
                                                        >
                                                            {available ? "✓" : "✗"} {label}{detail ? ` · ${detail}` : ""}
                                                        </span>
                                                    );
                                                })}
                                            </div>
                                        </div>
                                    );
                                })()}
                                {ffmpegProbe && (
                                    <div>
                                        <p style={{ margin: "0 0 8px 0", fontSize: "13px", fontWeight: 600, color: "#374151" }}>FFmpeg</p>
                                        {ffmpegProbe.found ? (
                                            <div style={{ fontSize: "12px", color: "#6b7280", display: "flex", flexDirection: "column", gap: "3px" }}>
                                                <span>版本：{ffmpegProbe.version}</span>
                                                <span style={{ display: "flex", alignItems: "center", gap: "6px" }}>
                                                    当前最优加速：
                                                    <strong style={{
                                                        color: ffmpegProbe.selected_accel && ffmpegProbe.selected_accel !== "cpu" ? "#059669" : "#d97706",
                                                        fontSize: "13px",
                                                    }}>
                                                        {ffmpegProbe.selected_accel === "cuda" ? "CUDA (GPU)" :
                                                         ffmpegProbe.selected_accel === "amf" ? "AMF (GPU)" :
                                                         ffmpegProbe.selected_accel === "qsv" ? "QSV (GPU)" :
                                                         ffmpegProbe.selected_accel === "videotoolbox" ? "VideoToolbox (GPU)" :
                                                         ffmpegProbe.selected_accel === "cpu" ? "CPU (无 GPU 加速)" :
                                                         ffmpegProbe.selected_accel ?? "未知"}
                                                    </strong>
                                                </span>
                                                <span style={{ color: "#9ca3af" }}>优先级：CUDA &gt; AMF &gt; QSV &gt; VideoToolbox &gt; CPU</span>
                                            </div>
                                        ) : (
                                            <span style={{ fontSize: "12px", color: "#ef4444" }}>未找到 FFmpeg，请安装后点击刷新检测</span>
                                        )}
                                    </div>
                                )}
                            </div>
                        )}
                        <div>
                            {section.items.map((item, idx) => (
                                <div key={item.key} style={{
                                    display: "flex",
                                    flexDirection: item.key === "ffmpeg_path" ? "column" : "row",
                                    alignItems: item.key === "ffmpeg_path" ? "stretch" : "center",
                                    justifyContent: "space-between",
                                    gap: item.key === "ffmpeg_path" ? "10px" : "16px",
                                    padding: "16px 20px",
                                    borderBottom: idx < section.items.length - 1 ? "1px solid #f3f4f6" : "none",
                                }}>
                                    <div style={{ flex: 1, minWidth: 0 }}>
                                        <p style={{ margin: "0 0 4px 0", fontSize: "15px", fontWeight: 500, color: "#111827" }}>
                                            {item.label}
                                        </p>
                                        <p style={{ margin: 0, fontSize: "13px", color: "#9ca3af" }}>
                                            {item.description}
                                        </p>
                                    </div>
                                    <div style={{ flexShrink: 0 }}>
                                        {item.type === "toggle" && (
                                            <button
                                                onClick={() => handleChange(item.key, !values[item.key])}
                                                style={{
                                                    position: "relative",
                                                    display: "inline-flex",
                                                    width: "44px",
                                                    height: "24px",
                                                    borderRadius: "12px",
                                                    border: "none",
                                                    cursor: "pointer",
                                                    backgroundColor: values[item.key] ? "#2563eb" : "#d1d5db",
                                                    transition: "background-color 0.2s",
                                                    padding: 0,
                                                }}
                                            >
                                                <span style={{
                                                    position: "absolute",
                                                    top: "3px",
                                                    left: values[item.key] ? "23px" : "3px",
                                                    width: "18px",
                                                    height: "18px",
                                                    borderRadius: "50%",
                                                    backgroundColor: "#fff",
                                                    boxShadow: "0 1px 3px rgba(0,0,0,0.2)",
                                                    transition: "left 0.2s",
                                                }} />
                                            </button>
                                        )}
                                        {item.type === "select" && (
                                            <select
                                                value={values[item.key] as string}
                                                onChange={(e) => handleChange(item.key, e.target.value)}
                                                style={{
                                                    padding: "6px 10px",
                                                    fontSize: "14px",
                                                    color: "#374151",
                                                    backgroundColor: "#fff",
                                                    border: "1px solid #d1d5db",
                                                    borderRadius: "8px",
                                                    cursor: "pointer",
                                                    minWidth: "120px",
                                                }}
                                            >
                                                {item.options.map((opt) => (
                                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                                ))}
                                            </select>
                                        )}
                                        {item.type === "number" && (
                                            <input
                                                type="number"
                                                value={values[item.key] as number}
                                                min={item.min}
                                                max={item.max}
                                                onChange={(e) => handleChange(item.key, Number(e.target.value))}
                                                style={{
                                                    padding: "6px 10px",
                                                    fontSize: "14px",
                                                    color: "#374151",
                                                    border: "1px solid #d1d5db",
                                                    borderRadius: "8px",
                                                    width: "100px",
                                                    textAlign: "right",
                                                }}
                                            />
                                        )}
                                        {item.type === "text" && item.key === "ffmpeg_path" ? (() => {
                                            const allPaths: string[] = ffmpegProbe?.all_paths ?? [];
                                            const currentVal = values[item.key] as string;
                                            if (allPaths.length === 0) {
                                                return (
                                                    <input
                                                        type="text"
                                                        value={currentVal}
                                                        placeholder={item.placeholder}
                                                        onChange={(e) => handleChange(item.key, e.target.value)}
                                                        style={{
                                                            padding: "6px 10px",
                                                            fontSize: "13px",
                                                            color: "#374151",
                                                            border: "1px solid #d1d5db",
                                                            borderRadius: "6px",
                                                            width: "100%",
                                                            boxSizing: "border-box",
                                                        }}
                                                    />
                                                );
                                            }
                                            return (
                                                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                                                    {allPaths.map(p => {
                                                        const active = currentVal === p;
                                                        return (
                                                            <button
                                                                key={p}
                                                                onClick={() => handleChange(item.key, p)}
                                                                style={{
                                                                    display: "flex",
                                                                    alignItems: "center",
                                                                    gap: "8px",
                                                                    padding: "7px 10px",
                                                                    fontSize: "12px",
                                                                    textAlign: "left",
                                                                    backgroundColor: active ? "#eff6ff" : "#fff",
                                                                    color: active ? "#1d4ed8" : "#374151",
                                                                    border: `1px solid ${active ? "#bfdbfe" : "#e5e7eb"}`,
                                                                    borderRadius: "6px",
                                                                    cursor: "pointer",
                                                                    wordBreak: "break-all",
                                                                }}
                                                            >
                                                                <span style={{
                                                                    flexShrink: 0,
                                                                    width: "12px", height: "12px",
                                                                    borderRadius: "50%",
                                                                    border: `2px solid ${active ? "#2563eb" : "#d1d5db"}`,
                                                                    backgroundColor: active ? "#2563eb" : "transparent",
                                                                }} />
                                                                {p}
                                                            </button>
                                                        );
                                                    })}
                                                </div>
                                            );
                                        })() : item.type === "text" && (
                                            <input
                                                type="text"
                                                value={values[item.key] as string}
                                                placeholder={item.placeholder}
                                                onChange={(e) => handleChange(item.key, e.target.value)}
                                                style={{
                                                    padding: "6px 10px",
                                                    fontSize: "14px",
                                                    color: "#374151",
                                                    border: "1px solid #d1d5db",
                                                    borderRadius: "8px",
                                                    width: "220px",
                                                }}
                                            />
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ))}

                {/* B站账号配置 */}
                <div style={{
                    backgroundColor: "#fff",
                    border: "1px solid #e5e7eb",
                    borderRadius: "12px",
                    marginBottom: "20px",
                    overflow: "hidden",
                }}>
                    <div style={{ padding: "14px 20px", borderBottom: "1px solid #f3f4f6", backgroundColor: "#f9fafb" }}>
                        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px" }}>
                            <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase", letterSpacing: "0.05em" }}>
                                B站账号
                            </h2>
                        </div>
                        <p style={{ margin: "4px 0 0 0", fontSize: "12px", color: "#9ca3af" }}>
                            留空则匿名请求。仅当出现"账号未登录"错误时才需要配置。从浏览器 Cookie 中获取对应字段值。
                        </p>
                    </div>
                    {([
                        { key: "bilibili_sessdata", label: "SESSDATA", placeholder: "浏览器 Cookie 中的 SESSDATA" },
                        { key: "bilibili_bili_jct", label: "bili_jct", placeholder: "浏览器 Cookie 中的 bili_jct" },
                        { key: "bilibili_buvid3", label: "buvid3", placeholder: "浏览器 Cookie 中的 buvid3" },
                        { key: "bilibili_buvid4", label: "buvid4", placeholder: "浏览器 Cookie 中的 buvid4（可选）" },
                        { key: "bilibili_dedeuserid", label: "DedeUserID", placeholder: "浏览器 Cookie 中的 DedeUserID" },
                        { key: "bilibili_ac_time_value", label: "ac_time_value", placeholder: "浏览器 Cookie 中的 ac_time_value" },
                        { key: "bilibili_proxy", label: "bilibili-api 代理", placeholder: "http://127.0.0.1:7890（可选，留空则不使用）", plainText: true },
                    ] as const).map(({ key, label, placeholder, ...rest }, idx, arr) => (
                        <div key={key} style={{
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "space-between",
                            gap: "16px",
                            padding: "12px 20px",
                            borderBottom: idx < arr.length - 1 ? "1px solid #f3f4f6" : "none",
                        }}>
                            <p style={{ margin: 0, fontSize: "14px", fontWeight: 500, color: "#374151", minWidth: "140px" }}>
                                {label}
                            </p>
                            {"plainText" in rest && rest.plainText ? (
                                <input
                                    type="text"
                                    value={(values[key] as string) ?? ""}
                                    placeholder={placeholder}
                                    onChange={(e) => handleChange(key, e.target.value)}
                                    style={{
                                        flex: 1,
                                        padding: "6px 10px",
                                        fontSize: "13px",
                                        color: "#374151",
                                        border: "1px solid #d1d5db",
                                        borderRadius: "8px",
                                        fontFamily: "monospace",
                                        boxSizing: "border-box",
                                    }}
                                />
                            ) : (
                                <PasswordInput
                                    value={(values[key] as string) ?? ""}
                                    placeholder={placeholder}
                                    onChange={(v) => handleChange(key, v)}
                                    show={showBilibiliFields[key] ?? false}
                                    onShowChange={(v) => setShowBilibiliFields(prev => ({ ...prev, [key]: v }))}
                                    style={{
                                        padding: "6px 10px",
                                        fontSize: "13px",
                                        color: "#374151",
                                        border: "1px solid #d1d5db",
                                        borderRadius: "8px",
                                        fontFamily: "monospace",
                                    }}
                                />
                            )}
                        </div>
                    ))}
                    {/* 检测按钮 + 刷新按钮 + 查看教程 + 状态（表单下方） */}
                    <div style={{ padding: "12px 20px", borderTop: "1px solid #f3f4f6", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
                        <button
                            type="button"
                            onClick={handleCheckCredential}
                            disabled={credStatus === "checking"}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px",
                                fontSize: "13px", fontWeight: 500,
                                color: "#6366f1",
                                background: "#eef2ff",
                                border: "1px solid #c7d2fe",
                                borderRadius: "8px",
                                cursor: credStatus === "checking" ? "default" : "pointer",
                                opacity: credStatus === "checking" ? 0.6 : 1,
                                transition: "all 0.15s ease",
                            }}
                        >
                            {credStatus === "checking"
                                ? <><Loader size={13} style={{ animation: "spin 1s linear infinite" }} />检测中…</>
                                : "检测账号有效性"
                            }
                        </button>
                        <button
                            type="button"
                            onClick={handleTryRefresh}
                            disabled={refreshStatus === "revealing" || refreshStatus === "refreshing"}
                            style={{
                                display: "flex", alignItems: "center", gap: "6px",
                                padding: "7px 14px",
                                fontSize: "13px", fontWeight: 500,
                                color: "#059669",
                                background: "#ecfdf5",
                                border: "1px solid #6ee7b7",
                                borderRadius: "8px",
                                cursor: (refreshStatus === "revealing" || refreshStatus === "refreshing") ? "default" : "pointer",
                                opacity: (refreshStatus === "revealing" || refreshStatus === "refreshing") ? 0.6 : 1,
                                transition: "all 0.15s ease",
                            }}
                        >
                            {refreshStatus === "revealing"
                                ? <><Loader size={13} style={{ animation: "spin 1s linear infinite" }} />准备中…</>
                                : refreshStatus === "refreshing"
                                    ? <><Loader size={13} style={{ animation: "spin 1s linear infinite" }} />刷新中…</>
                                    : <><RefreshCw size={13} />尝试刷新</>
                            }
                        </button>
                        {/* 检测结果 */}
                        {credStatus === "valid" && (
                            <span style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", color: "#16a34a" }}>
                                <CheckCircle size={14} />账号有效
                            </span>
                        )}
                        {credStatus === "invalid" && (
                            <span style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", color: "#dc2626" }}>
                                <XCircle size={14} />{credMessage}
                            </span>
                        )}
                        {credStatus === "unconfigured" && (
                            <span style={{ fontSize: "13px", color: "#9ca3af" }}>未配置账号</span>
                        )}
                        {credStatus === "error" && (
                            <span style={{ fontSize: "13px", color: "#b45309" }}>{credMessage}</span>
                        )}
                        {/* 刷新结果 */}
                        {refreshStatus === "refreshed" && (
                            <span style={{ display: "flex", alignItems: "center", gap: "4px", fontSize: "13px", color: "#16a34a" }}>
                                <CheckCircle size={14} />{refreshMessage}
                            </span>
                        )}
                        {refreshStatus === "no_refresh" && (
                            <span style={{ fontSize: "13px", color: "#6b7280" }}>{refreshMessage}</span>
                        )}
                        {refreshStatus === "error" && (
                            <span style={{ fontSize: "13px", color: "#dc2626" }}>{refreshMessage}</span>
                        )}
                        {/* 分隔，查看教程链接靠右 */}
                        <div style={{ flex: 1 }} />
                        <button
                            type="button"
                            onClick={() => openExternalUrl("https://nemo2011.github.io/bilibili-api/#/get-credential")}
                            style={{ fontSize: "12px", color: "#6366f1", background: "none", border: "none", cursor: "pointer", padding: "2px 0", textDecoration: "underline", whiteSpace: "nowrap" }}
                        >
                            查看教程 ↗
                        </button>
                    </div>
                </div>

                {/* LLM 模型配置入口 */}
                <div style={{
                    backgroundColor: "#fff",
                    border: "1px solid #e5e7eb",
                    borderRadius: "12px",
                    marginBottom: "20px",
                    padding: "20px",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: "16px",
                }}>
                    <div>
                        <p style={{ margin: "0 0 4px 0", fontSize: "15px", fontWeight: 600, color: "#111827" }}>LLM 模型配置</p>
                        <p style={{ margin: 0, fontSize: "13px", color: "#9ca3af" }}>管理 AI 模型连接信息、能力标签及默认角色分配。</p>
                    </div>
                    <button
                        onClick={() => navigate("/app-common-setting-llm-model-config")}
                        style={{
                            flexShrink: 0,
                            padding: "8px 16px",
                            fontSize: "14px",
                            fontWeight: 500,
                            color: "#2563eb",
                            backgroundColor: "#eff6ff",
                            border: "1px solid #bfdbfe",
                            borderRadius: "8px",
                            cursor: "pointer",
                        }}
                    >
                        进入配置 →
                    </button>
                </div>


                {/* PyTorch 环境配置入口 */}
                <div style={{
                    backgroundColor: "#fff",
                    border: "1px solid #e5e7eb",
                    borderRadius: "12px",
                    marginBottom: "20px",
                    padding: "20px",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: "16px",
                }}>
                    <div>
                        <p style={{ margin: "0 0 4px 0", fontSize: "15px", fontWeight: 600, color: "#111827" }}>PyTorch 环境</p>
                        <p style={{ margin: 0, fontSize: "13px", color: "#9ca3af" }}>为 faster-whisper 配置 GPU 加速的 torch 版本，检测 GPU 并下载对应 wheel。</p>
                    </div>
                    <button
                        onClick={() => navigate("/app-desktop-setting-torch-config")}
                        style={{
                            flexShrink: 0,
                            padding: "8px 16px",
                            fontSize: "14px",
                            fontWeight: 500,
                            color: "#2563eb",
                            backgroundColor: "#eff6ff",
                            border: "1px solid #bfdbfe",
                            borderRadius: "8px",
                            cursor: "pointer",
                        }}
                    >
                        进入配置 →
                    </button>
                </div>

                {/* ASR 模型配置入口 */}
                <div style={{
                    backgroundColor: "#fff",
                    border: "1px solid #e5e7eb",
                    borderRadius: "12px",
                    marginBottom: "20px",
                    padding: "20px",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: "16px",
                }}>
                    <div>
                        <p style={{ margin: "0 0 4px 0", fontSize: "15px", fontWeight: 600, color: "#111827" }}>ASR 模型配置</p>
                        <p style={{ margin: 0, fontSize: "13px", color: "#9ca3af" }}>管理 faster-whisper 模型的下载权限、黑名单，配置模型测试参数。</p>
                    </div>
                    <button
                        onClick={() => navigate("/app-desktop-setting-faster-whisper-asr-config")}
                        style={{
                            flexShrink: 0,
                            padding: "8px 16px",
                            fontSize: "14px",
                            fontWeight: 500,
                            color: "#2563eb",
                            backgroundColor: "#eff6ff",
                            border: "1px solid #bfdbfe",
                            borderRadius: "8px",
                            cursor: "pointer",
                        }}
                    >
                        进入配置 →
                    </button>
                </div>

                {/* 游客访问令牌 */}
                <div style={{
                    backgroundColor: "#fff",
                    border: "1px solid #e5e7eb",
                    borderRadius: "12px",
                    marginBottom: "20px",
                    overflow: "hidden",
                }}>
                    <div style={{
                        padding: "14px 20px",
                        borderBottom: "1px solid #f3f4f6",
                        backgroundColor: "#f9fafb",
                    }}>
                        <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase", letterSpacing: "0.05em" }}>
                            游客访问令牌
                        </h2>
                        <p style={{ margin: "4px 0 0 0", fontSize: "12px", color: "#9ca3af" }}>
                            设置后，游客可使用此令牌以只读身份访问。留空则禁用游客访问。
                        </p>
                    </div>
                    <div style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "space-between",
                        gap: "16px",
                        padding: "16px 20px",
                    }}>
                        <p style={{ margin: 0, fontSize: "14px", fontWeight: 500, color: "#374151", minWidth: "140px" }}>
                            令牌
                        </p>
                        <input
                            type="text"
                            value={(values["webserver_auth_token"] as string) ?? ""}
                            placeholder="留空则禁用游客访问"
                            onChange={(e) => handleChange("webserver_auth_token", e.target.value)}
                            style={{
                                flex: 1,
                                padding: "6px 10px",
                                fontSize: "13px",
                                color: "#374151",
                                border: "1px solid #d1d5db",
                                borderRadius: "8px",
                                fontFamily: "monospace",
                                boxSizing: "border-box",
                            }}
                        />
                    </div>
                </div>

                <p style={{ textAlign: "center", fontSize: "12px", color: "#9ca3af", marginTop: "8px" }}>
                    部分设置需要重启应用后生效
                </p>
            </div>
        </div>
    );
}
