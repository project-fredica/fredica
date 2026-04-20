import { NavLink, Outlet, useNavigate } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { Folder, List, Video, User, ArrowLeft } from "lucide-react";
import type { Route } from "./+types/add-resource.bilibili";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "从Bilibili添加资源 - Fredica" },
        { name: "description", content: "分类和预处理B站视频内容" },
    ];
}

const tabs = [
    { label: '收藏夹', icon: Folder, path: 'favorite' },
    { label: '视频合集', icon: List, path: 'collection' },
    { label: '视频分P', icon: Video, path: 'multi-part' },
    { label: 'UP主视频', icon: User, path: 'uploader' },
] as const;

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();

    return (
        <SidebarLayout>
            <div className="max-w-7xl mx-auto p-3 sm:p-6 space-y-4 sm:space-y-6">
                {/* 页面标题 */}
                <div className="border-b border-gray-200 pb-3 sm:pb-4">
                    <button
                        onClick={() => navigate('/add-resource')}
                        className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-800 transition-colors mb-2"
                    >
                        <ArrowLeft className="w-4 h-4" />
                        返回
                    </button>
                    <h1 className="text-xl sm:text-2xl font-bold text-gray-900">添加B站视频资源</h1>
                    <p className="text-xs sm:text-sm text-gray-500 mt-1">分类和预处理B站视频内容</p>
                </div>

                {/* 视频来源类型选择 */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">选择视频来源类型</h2>
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-2 sm:gap-4">
                        {tabs.map(({ label, icon: Icon, path }) => (
                            <NavLink
                                key={path}
                                to={path}
                                className={({ isActive }) =>
                                    `flex items-center justify-center gap-2 sm:gap-3 p-3 sm:p-4 rounded-lg border-2 transition-all ${isActive
                                        ? 'border-blue-500 bg-blue-50 text-blue-700'
                                        : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300 hover:bg-gray-50'
                                    }`
                                }
                            >
                                <Icon className="w-4 h-4 sm:w-5 sm:h-5 flex-shrink-0" />
                                <span className="font-medium text-sm sm:text-base">{label}</span>
                            </NavLink>
                        ))}
                    </div>
                </div>

                {/* 子路由内容（表单 + 视频列表） */}
                <Outlet />
            </div>
        </SidebarLayout>
    );
}
