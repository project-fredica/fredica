import { BrainCircuit, FileText, Tv2 } from "lucide-react";
import { NavLink, Outlet } from "react-router";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { isBilibiliVideo } from "~/components/material-library/materialTypes";

const BASE_TABS = [
    { id: "overview",  to: ".",        label: "概览",       icon: FileText,    end: true  },
    { id: "weben",     to: "weben",    label: "知识提取",   icon: BrainCircuit, end: false },
] as const;

const BILIBILI_TAB = { id: "bilibili", to: "bilibili", label: "B站AI总结", icon: Tv2, end: false } as const;

export default function SummaryLayoutPage() {
    const { material } = useWorkspaceContext();
    const isBilibili = isBilibiliVideo(material);
    const tabs = isBilibili
        ? [BASE_TABS[0], BILIBILI_TAB, BASE_TABS[1]]
        : [...BASE_TABS];

    return (
        <div className="max-w-5xl mx-auto">
            <div className="border-b border-gray-100 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                <nav className="flex min-w-max px-3 sm:px-4" aria-label="Summary sub navigation">
                    {tabs.map(tab => {
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
            <Outlet />
        </div>
    );
}
