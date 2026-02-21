import { useState } from "react";
import { useNavigate } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { ExternalLink, Download, Play, Video, Folder, User, List, ArrowLeft } from "lucide-react";
import type { Route } from "./+types/add-resource.bilibili";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "从Bilibili添加资源 - Fredica" },
        { name: "description", content: "分类和预处理B站视频内容" },
    ];
}

type VideoSourceType = 'favorite' | 'collection' | 'multi-part' | 'uploader';

interface VideoItem {
    id: string;
    title: string;
    author: string;
    duration: string;
    thumbnail: string;
    bvid: string;
}

const mockVideoData: VideoItem[] = [
    {
        id: "1",
        title: "【STM32教程】第1讲 - GPIO基础",
        author: "嵌入式技术分享",
        duration: "15:32",
        thumbnail: "https://picsum.photos/seed/video1/320/180",
        bvid: "BV1xx411c7mD"
    },
    {
        id: "2",
        title: "【STM32教程】第2讲 - 中断系统详解",
        author: "嵌入式技术分享",
        duration: "22:45",
        thumbnail: "https://picsum.photos/seed/video2/320/180",
        bvid: "BV1yy4y1q7Kv"
    },
    {
        id: "3",
        title: "【STM32教程】第3讲 - 定时器应用",
        author: "嵌入式技术分享",
        duration: "18:20",
        thumbnail: "https://picsum.photos/seed/video3/320/180",
        bvid: "BV1Sv411q7RF"
    },
    {
        id: "4",
        title: "【STM32教程】第4讲 - 串口通信",
        author: "嵌入式技术分享",
        duration: "20:15",
        thumbnail: "https://picsum.photos/seed/video4/320/180",
        bvid: "BV1Lx411v7dj"
    },
];

export default function Component({
    params,
}: Route.ComponentProps) {
    const navigate = useNavigate();
    const [sourceType, setSourceType] = useState<VideoSourceType>('favorite');
    const [urlInput, setUrlInput] = useState('');
    const [selectedVideos, setSelectedVideos] = useState<Set<string>>(new Set());

    const handleVideoSelect = (videoId: string) => {
        const newSelected = new Set(selectedVideos);
        if (newSelected.has(videoId)) {
            newSelected.delete(videoId);
        } else {
            newSelected.add(videoId);
        }
        setSelectedVideos(newSelected);
    };

    const handleSelectAll = () => {
        if (selectedVideos.size === mockVideoData.length) {
            setSelectedVideos(new Set());
        } else {
            setSelectedVideos(new Set(mockVideoData.map(v => v.id)));
        }
    };

    const handleOpenVideo = (bvid: string) => {
        window.open(`https://www.bilibili.com/video/${bvid}`, '_blank');
    };

    const handleDownload = (video: VideoItem) => {
        console.log('下载视频:', video.title);
        alert(`开始下载: ${video.title}`);
    };

    const handleAnalyze = (video: VideoItem) => {
        console.log('启动内容分析:', video.title);
        alert(`启动内容分析: ${video.title}`);
    };

    const handleBatchDownload = () => {
        console.log('批量下载:', Array.from(selectedVideos));
        alert(`批量下载 ${selectedVideos.size} 个视频`);
    };

    const handleBatchAnalyze = () => {
        console.log('批量分析:', Array.from(selectedVideos));
        alert(`批量分析 ${selectedVideos.size} 个视频`);
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        console.log('提交:', { sourceType, urlInput });
        alert(`获取${getSourceTypeName(sourceType)}内容: ${urlInput}`);
    };

    const getSourceTypeName = (type: VideoSourceType): string => {
        const names = {
            'favorite': '收藏夹',
            'collection': '视频合集',
            'multi-part': '视频分P',
            'uploader': 'UP主视频'
        };
        return names[type];
    };

    const getSourceTypeIcon = (type: VideoSourceType) => {
        const icons = {
            'favorite': Folder,
            'collection': List,
            'multi-part': Video,
            'uploader': User
        };
        return icons[type];
    };

    const getPlaceholder = (type: VideoSourceType): string => {
        const placeholders = {
            'favorite': '请输入收藏夹链接或ID',
            'collection': '请输入视频合集链接',
            'multi-part': '请输入分P视频链接',
            'uploader': '请输入UP主空间链接或UID'
        };
        return placeholders[type];
    };

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
                        {(['favorite', 'collection', 'multi-part', 'uploader'] as VideoSourceType[]).map((type) => {
                            const Icon = getSourceTypeIcon(type);
                            const isSelected = sourceType === type;
                            return (
                                <button
                                    key={type}
                                    onClick={() => setSourceType(type)}
                                    className={`flex items-center justify-center gap-2 sm:gap-3 p-3 sm:p-4 rounded-lg border-2 transition-all ${isSelected
                                        ? 'border-blue-500 bg-blue-50 text-blue-700'
                                        : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300 hover:bg-gray-50'
                                        }`}
                                >
                                    <Icon className="w-4 h-4 sm:w-5 sm:h-5 flex-shrink-0" />
                                    <span className="font-medium text-sm sm:text-base">{getSourceTypeName(type)}</span>
                                </button>
                            );
                        })}
                    </div>
                </div>

                {/* 参数输入表单 */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入{getSourceTypeName(sourceType)}信息</h2>
                    <form onSubmit={handleSubmit} className="space-y-4">
                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                {getSourceTypeName(sourceType)}链接或ID
                            </label>
                            <input
                                type="text"
                                value={urlInput}
                                onChange={(e) => setUrlInput(e.target.value)}
                                placeholder={getPlaceholder(sourceType)}
                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                            />
                        </div>

                        {/* 额外参数 */}
                        {sourceType === 'uploader' && (
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-2">
                                        排序方式
                                    </label>
                                    <select className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                                        <option>最新发布</option>
                                        <option>最多播放</option>
                                        <option>最多收藏</option>
                                    </select>
                                </div>
                                <div>
                                    <label className="block text-sm font-medium text-gray-700 mb-2">
                                        获取数量
                                    </label>
                                    <input
                                        type="number"
                                        defaultValue={30}
                                        min={1}
                                        max={100}
                                        className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                    />
                                </div>
                            </div>
                        )}

                        {sourceType === 'multi-part' && (
                            <div>
                                <label className="flex items-center gap-2 text-sm font-medium text-gray-700">
                                    <input type="checkbox" className="rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
                                    获取所有分P（不勾选则只获取第一个）
                                </label>
                            </div>
                        )}

                        <button
                            type="submit"
                            className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors"
                        >
                            获取视频列表
                        </button>
                    </form>
                </div>

                {/* 视频列表 */}
                <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
                        <div>
                            <h2 className="text-base sm:text-lg font-semibold text-gray-900">视频列表</h2>
                            <p className="text-xs sm:text-sm text-gray-500 mt-1">
                                共 {mockVideoData.length} 个视频
                                {selectedVideos.size > 0 && ` · 已选择 ${selectedVideos.size} 个`}
                            </p>
                        </div>
                        <div className="flex flex-wrap gap-2">
                            <button
                                onClick={handleSelectAll}
                                className="px-3 sm:px-4 py-2 text-xs sm:text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
                            >
                                {selectedVideos.size === mockVideoData.length ? '取消全选' : '全选'}
                            </button>
                            {selectedVideos.size > 0 && (
                                <>
                                    <button
                                        onClick={handleBatchDownload}
                                        className="px-3 sm:px-4 py-2 text-xs sm:text-sm font-medium text-white bg-green-600 rounded-lg hover:bg-green-700 transition-colors whitespace-nowrap"
                                    >
                                        <span className="hidden sm:inline">批量下载 ({selectedVideos.size})</span>
                                        <span className="sm:hidden">下载 ({selectedVideos.size})</span>
                                    </button>
                                    <button
                                        onClick={handleBatchAnalyze}
                                        className="px-3 sm:px-4 py-2 text-xs sm:text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 transition-colors whitespace-nowrap"
                                    >
                                        <span className="hidden sm:inline">批量分析 ({selectedVideos.size})</span>
                                        <span className="sm:hidden">分析 ({selectedVideos.size})</span>
                                    </button>
                                </>
                            )}
                        </div>
                    </div>

                    <div className="space-y-3 sm:space-y-4">
                        {mockVideoData.map((video) => (
                            <div
                                key={video.id}
                                className={`rounded-lg border transition-all ${selectedVideos.has(video.id)
                                    ? 'border-blue-500 bg-blue-50'
                                    : 'border-gray-200 bg-white hover:border-gray-300'
                                    }`}
                            >
                                {/* 桌面端布局 */}
                                <div className="hidden md:flex gap-4 p-4">
                                    {/* 复选框 */}
                                    <div className="flex items-start pt-1">
                                        <input
                                            type="checkbox"
                                            checked={selectedVideos.has(video.id)}
                                            onChange={() => handleVideoSelect(video.id)}
                                            className="w-5 h-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                        />
                                    </div>

                                    {/* 缩略图 */}
                                    <div className="flex-shrink-0">
                                        <img
                                            src={video.thumbnail}
                                            alt={video.title}
                                            className="w-40 h-24 object-cover rounded-lg"
                                        />
                                        <div className="text-xs text-center text-gray-500 mt-1 font-medium">
                                            {video.duration}
                                        </div>
                                    </div>

                                    {/* 视频信息 */}
                                    <div className="flex-1 min-w-0">
                                        <h3 className="text-base font-semibold text-gray-900 mb-1 truncate">
                                            {video.title}
                                        </h3>
                                        <p className="text-sm text-gray-600 mb-2">
                                            UP主: {video.author}
                                        </p>
                                        <p className="text-xs text-gray-500">
                                            BV号: {video.bvid}
                                        </p>
                                    </div>

                                    {/* 操作按钮 */}
                                    <div className="flex flex-col gap-2 justify-center">
                                        <button
                                            onClick={() => handleOpenVideo(video.bvid)}
                                            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors whitespace-nowrap"
                                        >
                                            <ExternalLink className="w-4 h-4" />
                                            跳转打开
                                        </button>
                                        <button
                                            onClick={() => handleDownload(video)}
                                            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors whitespace-nowrap"
                                        >
                                            <Download className="w-4 h-4" />
                                            下载
                                        </button>
                                        <button
                                            onClick={() => handleAnalyze(video)}
                                            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors whitespace-nowrap"
                                        >
                                            <Play className="w-4 h-4" />
                                            内容分析
                                        </button>
                                    </div>
                                </div>

                                {/* 移动端布局 */}
                                <div className="md:hidden p-3">
                                    {/* 头部：复选框 + 缩略图 + 时长 */}
                                    <div className="flex gap-3 mb-3">
                                        <div className="flex items-start pt-1">
                                            <input
                                                type="checkbox"
                                                checked={selectedVideos.has(video.id)}
                                                onChange={() => handleVideoSelect(video.id)}
                                                className="w-5 h-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                            />
                                        </div>
                                        <div className="relative flex-shrink-0">
                                            <img
                                                src={video.thumbnail}
                                                alt={video.title}
                                                className="w-32 h-20 object-cover rounded-lg"
                                            />
                                            <div className="absolute bottom-1 right-1 bg-black/70 text-white text-xs px-1.5 py-0.5 rounded">
                                                {video.duration}
                                            </div>
                                        </div>
                                    </div>

                                    {/* 视频信息 */}
                                    <div className="mb-3">
                                        <h3 className="text-sm font-semibold text-gray-900 mb-1 line-clamp-2">
                                            {video.title}
                                        </h3>
                                        <p className="text-xs text-gray-600 mb-1">
                                            UP主: {video.author}
                                        </p>
                                        <p className="text-xs text-gray-500">
                                            BV: {video.bvid}
                                        </p>
                                    </div>

                                    {/* 操作按钮 - 横向排列 */}
                                    <div className="grid grid-cols-3 gap-2">
                                        <button
                                            onClick={() => handleOpenVideo(video.bvid)}
                                            className="flex flex-col items-center justify-center gap-1 px-2 py-2 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                                        >
                                            <ExternalLink className="w-4 h-4" />
                                            <span>打开</span>
                                        </button>
                                        <button
                                            onClick={() => handleDownload(video)}
                                            className="flex flex-col items-center justify-center gap-1 px-2 py-2 text-xs font-medium text-green-700 bg-green-50 rounded-lg hover:bg-green-100 transition-colors"
                                        >
                                            <Download className="w-4 h-4" />
                                            <span>下载</span>
                                        </button>
                                        <button
                                            onClick={() => handleAnalyze(video)}
                                            className="flex flex-col items-center justify-center gap-1 px-2 py-2 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
                                        >
                                            <Play className="w-4 h-4" />
                                            <span>分析</span>
                                        </button>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </SidebarLayout>
    );
}
