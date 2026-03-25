import type { PromptWorkbenchTabItem, PromptWorkbenchTab } from "./promptBuilderTypes";

export function PromptWorkbenchTabs({
    tabs,
    activeTab,
    onChange,
}: {
    tabs: PromptWorkbenchTabItem[];
    activeTab: PromptWorkbenchTab;
    onChange: (tab: PromptWorkbenchTab) => void;
}) {
    return (
        <div className="border-b border-gray-200 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <div className="flex min-w-max px-3" role="tablist" aria-label="Prompt workbench tabs">
                {tabs.map(tab => (
                    <button
                        key={tab.id}
                        type="button"
                        role="tab"
                        aria-selected={activeTab === tab.id}
                        aria-controls={`prompt-panel-${tab.id}`}
                        id={`prompt-tab-${tab.id}`}
                        disabled={tab.disabled}
                        onClick={() => onChange(tab.id)}
                        className={`px-3 py-2.5 text-sm font-medium border-b-2 whitespace-nowrap transition-colors ${
                            activeTab === tab.id
                                ? "border-violet-500 text-violet-700"
                                : "border-transparent text-gray-500 hover:text-gray-800 hover:border-gray-200"
                        } ${tab.disabled ? "opacity-40 cursor-not-allowed hover:text-gray-500 hover:border-transparent" : ""}`}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>
        </div>
    );
}
