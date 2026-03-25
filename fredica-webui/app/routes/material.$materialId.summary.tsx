import { BrainCircuit, FileText } from "lucide-react";
import { NavLink, Outlet } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";

const SUMMARY_TABS = [
    { id: "overview", to: ".", label: "概览", icon: FileText, end: true },
    { id: "weben", to: "weben", label: "Weben 知识提取", icon: BrainCircuit, end: false },
] as const;

export default function SummaryLayoutPage() {
    useWorkspaceContext();

    return (
        <div className="max-w-5xl mx-auto p-4 sm:p-6 space-y-4">
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                <div className="px-4 sm:px-5 pt-4 sm:pt-5 pb-3 border-b border-gray-100">
                    <h2 className="text-base font-semibold text-gray-900">内容总结</h2>
                    <p className="text-sm text-gray-500 mt-1">在此查看总结概览，并逐步扩展到 PromptBuilder 驱动的分析工作台。</p>
                </div>

                <div className="border-b border-gray-100 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                    <nav className="flex min-w-max px-3 sm:px-4" aria-label="Summary sub navigation">
                        {SUMMARY_TABS.map(tab => {
                            const Icon = tab.icon;
                            return (
                                <NavLink
                                    key={tab.id}
                                    to={tab.to}
                                    end={tab.end}
                                    className={({ isActive }) => `flex items-center gap-1.5 px-3 py-2.5 text-sm font-medium border-b-2 whitespace-nowrap transition-colors ${
                                        isActive
                                            ? "border-violet-500 text-violet-700"
                                            : "border-transparent text-gray-500 hover:text-gray-800 hover:border-gray-200"
                                    }`}
                                >
                                    <Icon className="w-4 h-4" />
                                    <span>{tab.label}</span>
                                </NavLink>
                            );
                        })}
                    </nav>
                </div>

                <div className="min-h-[320px] bg-gray-50/50">
                    <Outlet />
                </div>
            </div>
        </div>
    );
}
