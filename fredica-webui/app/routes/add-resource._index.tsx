import { useNavigate } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { Tv, Youtube, HardDrive, Server } from "lucide-react";
import type { Route } from "./+types/add-resource._index";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "添加素材 - Fredica" },
        { name: "description", content: "添加素材到Fredica" },
    ];
}

interface SourceOption {
    key: string;
    label: string;
    description: string;
    icon: React.ElementType;
    iconColor: string;
    bgColor: string;
    borderColor: string;
    hoverBorder: string;
    route?: string;
}

const sourceOptions: SourceOption[] = [
    {
        key: "bilibili",
        label: "从 Bilibili 添加素材",
        description: "导入 B 站收藏夹、合集、分 P 视频或 UP 主视频",
        icon: Tv,
        iconColor: "text-pink-500",
        bgColor: "bg-pink-50",
        borderColor: "border-pink-200",
        hoverBorder: "hover:border-pink-400",
        route: "/add-resource/bilibili",
    },
    {
        key: "youtube",
        label: "从 YouTube 添加素材",
        description: "导入 YouTube 播放列表、频道或单个视频",
        icon: Youtube,
        iconColor: "text-red-500",
        bgColor: "bg-red-50",
        borderColor: "border-red-200",
        hoverBorder: "hover:border-red-400",
    },
    {
        key: "browser",
        label: "从浏览器文件选择器添加素材",
        description: "从浏览器文件选择器导入视频、音频或字幕文件",
        icon: HardDrive,
        iconColor: "text-blue-500",
        bgColor: "bg-blue-50",
        borderColor: "border-blue-200",
        hoverBorder: "hover:border-blue-400",
    },
    {
        key: "webdav",
        label: "从 WebDAV 添加素材",
        description: "通过 WebDAV 协议连接远程存储并导入素材",
        icon: Server,
        iconColor: "text-green-500",
        bgColor: "bg-green-50",
        borderColor: "border-green-200",
        hoverBorder: "hover:border-green-400",
    },
];

export default function Component({ }: Route.ComponentProps) {
    const navigate = useNavigate();

    const handleSelect = (option: SourceOption) => {
        if (option.route) {
            navigate(option.route);
        }
    };

    return (
        <SidebarLayout>
            <div className="max-w-3xl mx-auto p-4 sm:p-6 space-y-4 sm:space-y-6">
                <div className="border-b border-gray-200 pb-4">
                    <h1 className="text-2xl font-bold text-gray-900">添加素材</h1>
                    <p className="text-sm text-gray-500 mt-1">选择素材来源以开始导入</p>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    {sourceOptions.map((option) => {
                        const Icon = option.icon;
                        const isAvailable = !!option.route;
                        return (
                            <button
                                key={option.key}
                                onClick={() => handleSelect(option)}
                                disabled={!isAvailable}
                                className={`
                                    flex items-start gap-4 p-5 rounded-xl border-2 text-left transition-all
                                    ${option.borderColor} ${option.bgColor}
                                    ${isAvailable
                                        ? `${option.hoverBorder} hover:shadow-md cursor-pointer`
                                        : "opacity-50 cursor-not-allowed"
                                    }
                                `}
                            >
                                <div className={`flex-shrink-0 p-2 rounded-lg bg-white shadow-sm`}>
                                    <Icon className={`w-6 h-6 ${option.iconColor}`} />
                                </div>
                                <div className="min-w-0">
                                    <p className="font-semibold text-gray-900 text-sm sm:text-base">
                                        {option.label}
                                    </p>
                                    <p className="text-xs sm:text-sm text-gray-500 mt-1">
                                        {option.description}
                                    </p>
                                    {!isAvailable && (
                                        <span className="inline-block mt-2 text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
                                            即将推出
                                        </span>
                                    )}
                                </div>
                            </button>
                        );
                    })}
                </div>
            </div>
        </SidebarLayout>
    );
}
