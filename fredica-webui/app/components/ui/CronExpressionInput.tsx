import { useState, useCallback } from "react";
import { ChevronDown } from "lucide-react";

const PRESETS: { label: string; value: string }[] = [
    { label: "每 30 分钟", value: "*/30 * * * *" },
    { label: "每 1 小时", value: "0 * * * *" },
    { label: "每 6 小时", value: "0 */6 * * *" },
    { label: "每 12 小时", value: "0 */12 * * *" },
    { label: "每天 8:00", value: "0 8 * * *" },
];

function findPresetLabel(value: string): string | null {
    return PRESETS.find(p => p.value === value)?.label ?? null;
}

export function CronExpressionInput({
    value,
    onChange,
    placeholder,
}: {
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
}) {
    const presetLabel = findPresetLabel(value);
    const isCustom = value !== "" && !presetLabel;
    const [showCustom, setShowCustom] = useState(isCustom);
    const [dropdownOpen, setDropdownOpen] = useState(false);

    const handlePresetSelect = useCallback(
        (preset: string) => {
            onChange(preset);
            setShowCustom(false);
            setDropdownOpen(false);
        },
        [onChange],
    );

    const handleCustomToggle = useCallback(() => {
        setShowCustom(true);
        setDropdownOpen(false);
    }, []);

    const displayText = value === "" ? "不自动同步" : (presetLabel ?? value);

    return (
        <div className="relative">
            <button
                type="button"
                onClick={() => setDropdownOpen(prev => !prev)}
                className="w-full flex items-center justify-between px-3 py-2 text-sm border border-gray-300 rounded-lg bg-white hover:border-gray-400 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-colors"
            >
                <span className={value === "" ? "text-gray-400" : "text-gray-900"}>
                    {displayText}
                </span>
                <ChevronDown className="w-4 h-4 text-gray-400 shrink-0" />
            </button>

            {dropdownOpen && (
                <div className="absolute z-10 mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg py-1">
                    <button
                        type="button"
                        onClick={() => handlePresetSelect("")}
                        className={`w-full text-left px-3 py-2 text-sm hover:bg-gray-50 transition-colors ${value === "" ? "text-blue-600 font-medium" : "text-gray-700"}`}
                    >
                        不自动同步
                    </button>
                    {PRESETS.map(preset => (
                        <button
                            key={preset.value}
                            type="button"
                            onClick={() => handlePresetSelect(preset.value)}
                            className={`w-full text-left px-3 py-2 text-sm hover:bg-gray-50 transition-colors ${value === preset.value ? "text-blue-600 font-medium" : "text-gray-700"}`}
                        >
                            <span>{preset.label}</span>
                            <span className="ml-2 text-xs text-gray-400">{preset.value}</span>
                        </button>
                    ))}
                    <div className="border-t border-gray-100 mt-1 pt-1">
                        <button
                            type="button"
                            onClick={handleCustomToggle}
                            className={`w-full text-left px-3 py-2 text-sm hover:bg-gray-50 transition-colors ${isCustom ? "text-blue-600 font-medium" : "text-gray-700"}`}
                        >
                            自定义...
                        </button>
                    </div>
                </div>
            )}

            {showCustom && (
                <input
                    type="text"
                    value={value}
                    onChange={e => onChange(e.target.value)}
                    placeholder={placeholder ?? "例如：0 */6 * * *"}
                    className="mt-2 w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none font-mono"
                />
            )}
        </div>
    );
}
