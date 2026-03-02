import { Wifi } from "lucide-react";
import { Link } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

const TOOLS = [
    {
        id: "network-test",
        href: "/tools/network-test",
        Icon: Wifi,
        title: "网速和延迟测试",
        desc: "区分直连与代理两种路径，通过 Worker Engine 异步执行",
    },
];

export default function ToolsIndexPage() {
    return (
        <SidebarLayout>
            <div className="max-w-2xl mx-auto p-4 sm:p-6 space-y-4">
                <div>
                    <h1 className="text-xl font-semibold text-gray-900">小工具</h1>
                    <p className="text-sm text-gray-500 mt-0.5">通过 Worker Engine 执行的实用工具集合</p>
                </div>

                <div className="grid gap-3">
                    {TOOLS.map(({ id, href, Icon, title, desc }) => (
                        <Link
                            key={id}
                            to={href}
                            className="bg-white rounded-xl border border-gray-200 px-5 py-4 flex items-center gap-4 hover:border-blue-200 hover:bg-blue-50/30 transition-colors group"
                        >
                            <div className="w-9 h-9 rounded-lg bg-blue-50 flex items-center justify-center flex-shrink-0 group-hover:bg-blue-100 transition-colors">
                                <Icon className="w-4 h-4 text-blue-600" />
                            </div>
                            <div className="flex-1 min-w-0">
                                <p className="text-sm font-semibold text-gray-900">{title}</p>
                                <p className="text-xs text-gray-500 mt-0.5">{desc}</p>
                            </div>
                            <span className="text-gray-300 group-hover:text-blue-400 transition-colors text-lg leading-none">›</span>
                        </Link>
                    ))}
                </div>
            </div>
        </SidebarLayout>
    );
}
