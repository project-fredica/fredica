import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, RefreshCw, Save } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting";
import { getBridge } from "../util/bridge";

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
    return values;
}

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();
    const [values, setValues] = useState<Record<string, string | number | boolean>>(buildInitialValues);
    const [saved, setSaved] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [deviceInfo, setDeviceInfo] = useState<any>(null);
    const [ffmpegProbe, setFfmpegProbe] = useState<any>(null);
    const [detecting, setDetecting] = useState(false);

    useEffect(() => {
        const bridge = getBridge();
        if (!bridge) {
            setLoadError("kmpJsBridge 不可用，请在桌面端环境中使用。当前显示默认值。");
            return;
        }
        try {
            bridge.callNative("get_app_config", "{}", (result: string) => {
                try {
                    const config = JSON.parse(result) as Record<string, string | number | boolean>;
                    setValues(prev => ({ ...prev, ...config }));
                    setLoadError(null);
                } catch (e) {
                    setLoadError("解析配置失败：" + e + "  ---  result is : " + result);
                }
            });
        } catch (e) {
            setLoadError("调用 kmpJsBridge 失败：" + e);
        }
        try {
            bridge.callNative("get_device_info", "{}", (result: string) => {
                try {
                    const info = JSON.parse(result);
                    if (info.device_info_json) {
                        setDeviceInfo(typeof info.device_info_json === "string"
                            ? JSON.parse(info.device_info_json) : info.device_info_json);
                    }
                    if (info.ffmpeg_probe_json) {
                        setFfmpegProbe(typeof info.ffmpeg_probe_json === "string"
                            ? JSON.parse(info.ffmpeg_probe_json) : info.ffmpeg_probe_json);
                    }
                } catch (_) { /* device info optional */ }
            });
        } catch (_) { /* device info optional */ }
    }, []);

    const handleChange = (key: string, value: string | number | boolean) => {
        setValues((prev) => ({ ...prev, [key]: value }));
        setSaved(false);
    };

    const handleSave = () => {
        const bridge = getBridge();
        if (!bridge) {
            alert("kmpJsBridge 不可用，无法保存设置。");
            return;
        }
        try {
            bridge.callNative("save_app_config", JSON.stringify(values), (result: string) => {
                try {
                    const updated = JSON.parse(result) as Record<string, string | number | boolean>;
                    setValues(prev => ({ ...prev, ...updated }));
                    setSaved(true);
                    setTimeout(() => setSaved(false), 2000);
                } catch (e) {
                    console.error("保存设置后解析响应失败：", e);
                }
            });
        } catch (e) {
            console.error("调用 kmpJsBridge 保存配置失败：", e);
            alert("保存失败：" + e);
        }
    };

    const handleDetect = () => {
        const bridge = getBridge();
        if (!bridge) return;
        setDetecting(true);
        try {
            bridge.callNative("run_ffmpeg_detect", "{}", (result: string) => {
                setDetecting(false);
                try {
                    const info = JSON.parse(result);
                    if (info.error) { console.error("device detect error:", info.error); return; }
                    if (info.device_info_json) {
                        setDeviceInfo(typeof info.device_info_json === "string"
                            ? JSON.parse(info.device_info_json) : info.device_info_json);
                    }
                    if (info.ffmpeg_probe_json) {
                        setFfmpegProbe(typeof info.ffmpeg_probe_json === "string"
                            ? JSON.parse(info.ffmpeg_probe_json) : info.ffmpeg_probe_json);
                    }
                } catch (_) { /* ignore parse errors */ }
            });
        } catch (e) {
            setDetecting(false);
            console.error("detect failed:", e);
        }
    };

    return (
        <div style={{ minHeight: "100vh", backgroundColor: "#f9fafb", fontFamily: "system-ui, sans-serif" }}>
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
                        onClick={() => navigate("/app-desktop-home")}
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

                <p style={{ textAlign: "center", fontSize: "12px", color: "#9ca3af", marginTop: "8px" }}>
                    部分设置需要重启应用后生效
                </p>
            </div>
        </div>
    );
}
