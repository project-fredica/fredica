import { useState } from "react";
import { BilibiliVideoList } from "~/components/bilibili/BilibiliVideoList";

export default function Component() {
    const [urlInput, setUrlInput] = useState('');
    const [fetchAll, setFetchAll] = useState(false);

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        console.log('提交视频分P:', { urlInput, fetchAll });
        alert(`获取视频分P内容: ${urlInput}`);
    };

    return (
        <>
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入视频分P信息</h2>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            视频分P链接或ID
                        </label>
                        <input
                            type="text"
                            value={urlInput}
                            onChange={(e) => setUrlInput(e.target.value)}
                            placeholder="请输入分P视频链接"
                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                        />
                    </div>
                    <div>
                        <label className="flex items-center gap-2 text-sm font-medium text-gray-700">
                            <input
                                type="checkbox"
                                checked={fetchAll}
                                onChange={(e) => setFetchAll(e.target.checked)}
                                className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                            />
                            获取所有分P（不勾选则只获取第一个）
                        </label>
                    </div>
                    <button
                        type="submit"
                        className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors"
                    >
                        获取视频列表
                    </button>
                </form>
            </div>
            <BilibiliVideoList />
        </>
    );
}
