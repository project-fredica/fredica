import { useState } from "react";
import type React from "react";
import { Eye, EyeOff } from "lucide-react";

/**
 * 带显示/隐藏切换的密码输入框。
 *
 * ## 两种使用模式
 *
 * ### 非受控（默认）
 * 不传 `show` / `onShowChange`，组件内部自行管理显示状态。
 * ```tsx
 * <PasswordInput value={val} onChange={setVal} placeholder="请输入密码" />
 * ```
 *
 * ### 受控
 * 传入 `show` 和 `onShowChange`，由外部控制显示状态。
 * 适用于需要批量展开/收起多个字段的场景（如"尝试刷新"功能）。
 * ```tsx
 * const [show, setShow] = useState(false);
 * <PasswordInput value={val} onChange={setVal} show={show} onShowChange={setShow} />
 * ```
 */
export function PasswordInput({
    value,
    placeholder,
    onChange,
    style,
    show: showProp,
    onShowChange,
}: {
    value: string;
    placeholder?: string;
    onChange: (v: string) => void;
    style?: React.CSSProperties;
    /** 受控显示状态。传入时由外部管理（配合 onShowChange 使用）；不传则内部自管理。 */
    show?: boolean;
    /** show 变化时通知外部（仅受控模式下有效）。 */
    onShowChange?: (v: boolean) => void;
}) {
    const [showInternal, setShowInternal] = useState(false);
    const isControlled = showProp !== undefined;
    const show = isControlled ? showProp : showInternal;

    const toggleShow = () => {
        const next = !show;
        if (isControlled) {
            onShowChange?.(next);
        } else {
            setShowInternal(next);
        }
    };

    return (
        <div style={{ position: "relative", flex: 1 }}>
            <style>{`.pw-input::placeholder { color: #d1d5db; }`}</style>
            <input
                className="pw-input"
                type={show ? "text" : "password"}
                value={value}
                placeholder={placeholder}
                onChange={(e) => onChange(e.target.value)}
                style={{
                    width: "100%",
                    boxSizing: "border-box",
                    // 留出足够空间给右侧按钮（按钮宽度 ≈ 图标16px + 横向padding 10px×2 = 36px）
                    paddingRight: "40px",
                    ...style,
                }}
            />
            <button
                type="button"
                onClick={toggleShow}
                style={{
                    position: "absolute",
                    right: 0,
                    top: 0,
                    bottom: 0,
                    // 宽度固定，整列高度可点击，不再是零内边距的小图标
                    width: "36px",
                    background: "none",
                    border: "none",
                    cursor: "pointer",
                    color: "#9ca3af",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    borderRadius: "0 8px 8px 0",
                }}
            >
                {show ? <EyeOff size={15} /> : <Eye size={15} />}
            </button>
        </div>
    );
}
