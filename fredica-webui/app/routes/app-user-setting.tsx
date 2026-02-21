import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { ArrowLeft, Save } from "lucide-react";

export function meta() {
    return [
        { title: "用户设置 - Fredica" },
        { name: "description", content: "Fredica 用户个人设置，保存于本地浏览器中。" },
    ];
}

const STORAGE_KEY = "fredica_user_prefs";

interface UserPrefs {
    display_name: string;
    language: string;
    theme: string;
    show_welcome: boolean;
}

const defaultPrefs: UserPrefs = {
    display_name: "",
    language: "zh-CN",
    theme: "system",
    show_welcome: true,
};

function loadPrefs(): UserPrefs {
    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored) return { ...defaultPrefs, ...JSON.parse(stored) };
    } catch { }
    return defaultPrefs;
}

function savePrefs(prefs: UserPrefs) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
    } catch { }
}

export default function Component() {
    const navigate = useNavigate();
    const [prefs, setPrefs] = useState<UserPrefs>(defaultPrefs);
    const [saved, setSaved] = useState(false);

    // localStorage 在客户端才可用，避免 SSR 不一致
    useEffect(() => {
        setPrefs(loadPrefs());
    }, []);

    const handleChange = <K extends keyof UserPrefs>(key: K, value: UserPrefs[K]) => {
        setPrefs(prev => ({ ...prev, [key]: value }));
        setSaved(false);
    };

    const handleSave = () => {
        savePrefs(prefs);
        setSaved(true);
        setTimeout(() => setSaved(false), 2000);
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
                        onClick={() => navigate(-1)}
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
                    <h1 style={{ fontSize: "18px", fontWeight: 700, color: "#111827", margin: 0 }}>用户设置</h1>
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
                <div style={{
                    padding: "12px 16px",
                    marginBottom: "20px",
                    backgroundColor: "#eff6ff",
                    border: "1px solid #bfdbfe",
                    borderRadius: "8px",
                    fontSize: "13px",
                    color: "#1e40af",
                }}>
                    这些设置仅保存在您当前浏览器的本地存储（localStorage）中，与服务器无关，更换设备或浏览器后需重新配置。
                </div>

                {/* 个人信息 */}
                <SettingCard title="个人信息">
                    <SettingRow label="显示名称" description="在界面中显示的名称，仅本地可见。">
                        <input
                            type="text"
                            value={prefs.display_name}
                            placeholder="未设置"
                            onChange={e => handleChange("display_name", e.target.value)}
                            style={inputStyle}
                        />
                    </SettingRow>
                </SettingCard>

                {/* 外观与语言 */}
                <SettingCard title="外观与语言">
                    <SettingRow label="界面主题" description="选择界面配色方案，该偏好仅影响本设备。">
                        <select
                            value={prefs.theme}
                            onChange={e => handleChange("theme", e.target.value)}
                            style={selectStyle}
                        >
                            <option value="system">跟随系统</option>
                            <option value="light">浅色</option>
                            <option value="dark">深色</option>
                        </select>
                    </SettingRow>
                    <SettingRow label="界面语言" description="界面显示语言偏好。" last>
                        <select
                            value={prefs.language}
                            onChange={e => handleChange("language", e.target.value)}
                            style={selectStyle}
                        >
                            <option value="zh-CN">简体中文</option>
                            <option value="en">English</option>
                        </select>
                    </SettingRow>
                </SettingCard>

                {/* 行为偏好 */}
                <SettingCard title="行为偏好">
                    <SettingRow label="显示欢迎提示" description="每次打开应用时在首页显示欢迎信息。" last>
                        <ToggleButton
                            value={prefs.show_welcome}
                            onChange={v => handleChange("show_welcome", v)}
                        />
                    </SettingRow>
                </SettingCard>

                <p style={{ textAlign: "center", fontSize: "12px", color: "#9ca3af", marginTop: "8px" }}>
                    设置实时保存至浏览器本地存储，点击"保存设置"立即生效
                </p>
            </div>
        </div>
    );
}

// ---- 子组件 ----

function SettingCard({ title, children }: { title: string; children: React.ReactNode }) {
    return (
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
                    {title}
                </h2>
            </div>
            <div>{children}</div>
        </div>
    );
}

function SettingRow({ label, description, last, children }: {
    label: string;
    description: string;
    last?: boolean;
    children: React.ReactNode;
}) {
    return (
        <div style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: "16px",
            padding: "16px 20px",
            borderBottom: last ? "none" : "1px solid #f3f4f6",
        }}>
            <div style={{ flex: 1, minWidth: 0 }}>
                <p style={{ margin: "0 0 4px 0", fontSize: "15px", fontWeight: 500, color: "#111827" }}>{label}</p>
                <p style={{ margin: 0, fontSize: "13px", color: "#9ca3af" }}>{description}</p>
            </div>
            <div style={{ flexShrink: 0 }}>{children}</div>
        </div>
    );
}

function ToggleButton({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
    return (
        <button
            onClick={() => onChange(!value)}
            style={{
                position: "relative",
                display: "inline-flex",
                width: "44px",
                height: "24px",
                borderRadius: "12px",
                border: "none",
                cursor: "pointer",
                backgroundColor: value ? "#2563eb" : "#d1d5db",
                transition: "background-color 0.2s",
                padding: 0,
            }}
        >
            <span style={{
                position: "absolute",
                top: "3px",
                left: value ? "23px" : "3px",
                width: "18px",
                height: "18px",
                borderRadius: "50%",
                backgroundColor: "#fff",
                boxShadow: "0 1px 3px rgba(0,0,0,0.2)",
                transition: "left 0.2s",
            }} />
        </button>
    );
}

const inputStyle: React.CSSProperties = {
    padding: "6px 10px",
    fontSize: "14px",
    color: "#374151",
    border: "1px solid #d1d5db",
    borderRadius: "8px",
    width: "220px",
};

const selectStyle: React.CSSProperties = {
    padding: "6px 10px",
    fontSize: "14px",
    color: "#374151",
    backgroundColor: "#fff",
    border: "1px solid #d1d5db",
    borderRadius: "8px",
    cursor: "pointer",
    minWidth: "120px",
};
