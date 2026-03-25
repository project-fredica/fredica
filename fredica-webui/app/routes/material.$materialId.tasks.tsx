import { useState } from "react";
import { Link } from "react-router";
import { RefreshCw, ListChecks } from "lucide-react";
import { useWorkspaceContext } from "~/routes/material.$materialId";
import { TaskList } from "~/components/ui/WorkflowInfoPanel";

// ─── Page ─────────────────────────────────────────────────────────────────────

const FILTER_OPTIONS = ['全部', '进行中', '已完成', '失败', '已取消'] as const;
type Filter = typeof FILTER_OPTIONS[number];

const FILTER_STATUSES: Record<Filter, string[] | undefined> = {
    '全部':   undefined,
    '进行中': ['running', 'pending', 'claimed'],
    '已完成': ['completed'],
    '失败':   ['failed'],
    '已取消': ['cancelled'],
};

export default function TasksPage() {
    const { material } = useWorkspaceContext();
    const [filter, setFilter] = useState<Filter>('全部');
    const [refreshKey, setRefreshKey] = useState(0);

    return (
        <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">

            {/* Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <ListChecks className="w-4 h-4 text-gray-400" />
                    <h2 className="text-sm font-semibold text-gray-700">任务历史</h2>
                </div>
                <div className="flex items-center gap-2">
                    <Link
                        to={`/tasks/status?material_id=${encodeURIComponent(material.id)}`}
                        className="text-xs text-blue-500 hover:text-blue-700 transition-colors"
                    >
                        前往任务中心查看详情 →
                    </Link>
                    <button
                        onClick={() => setRefreshKey(k => k + 1)}
                        className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors"
                        title="刷新"
                    >
                        <RefreshCw className="w-3.5 h-3.5" />
                    </button>
                </div>
            </div>

            {/* Filter tabs */}
            <div className="flex gap-1 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                {FILTER_OPTIONS.map(f => (
                    <button
                        key={f}
                        onClick={() => setFilter(f)}
                        className={`px-3 py-1.5 text-xs font-medium rounded-lg whitespace-nowrap transition-colors ${
                            filter === f
                                ? 'bg-violet-600 text-white'
                                : 'bg-white border border-gray-200 text-gray-500 hover:bg-gray-50'
                        }`}
                    >
                        {f}
                    </button>
                ))}
            </div>

            {/* Task list */}
            <div key={refreshKey}>
                <TaskList materialId={material.id} active filterStatuses={FILTER_STATUSES[filter]} />
            </div>

        </div>
    );
}
