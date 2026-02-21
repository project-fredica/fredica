import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, Save } from "lucide-react";
import type { Route } from "./+types/app-desktop-setting";

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
    {
        title: "第三方服务",
        items: [
            {
                type: "text",
                key: "rsshub_url",
                label: "RSSHub 地址",
                description: "自托管或公共 RSSHub 实例地址，用于生成 RSS 订阅源。",
                defaultValue: "",
                placeholder: "https://rsshub.app",
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

function getKmpJsBridge(): any | undefined {
    return typeof window !== "undefined" ? (window as any).kmpJsBridge : undefined;
}

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();
    const [values, setValues] = useState<Record<string, string | number | boolean>>(buildInitialValues);
    const [saved, setSaved] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);

    useEffect(() => {
        const bridge = getKmpJsBridge();
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
                    setLoadError("解析配置失败：" + e);
                }
            });
        } catch (e) {
            setLoadError("调用 kmpJsBridge 失败：" + e);
        }
    }, []);

    const handleChange = (key: string, value: string | number | boolean) => {
        setValues((prev) => ({ ...prev, [key]: value }));
        setSaved(false);
    };

    const handleSave = () => {
        const bridge = getKmpJsBridge();
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
                        }}>
                            <h2 style={{ margin: 0, fontSize: "14px", fontWeight: 600, color: "#6b7280", textTransform: "uppercase", letterSpacing: "0.05em" }}>
                                {section.title}
                            </h2>
                        </div>
                        <div>
                            {section.items.map((item, idx) => (
                                <div key={item.key} style={{
                                    display: "flex",
                                    alignItems: "center",
                                    justifyContent: "space-between",
                                    gap: "16px",
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
                                        {item.type === "text" && (
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

                <p style={{ textAlign: "center", fontSize: "12px", color: "#9ca3af", marginTop: "8px" }}>
                    部分设置需要重启应用后生效
                </p>
            </div>
        </div>
    );
}
