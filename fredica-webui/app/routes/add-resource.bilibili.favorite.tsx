import { useEffect, useState } from "react";
import { Outlet, useLocation, useNavigate } from "react-router";

/** 从链接或纯数字中提取 fid */
function extractFid(input: string): string {
    const match = input.match(/[?&]fid=(\d+)/);
    if (match) return match[1];
    return input.trim();
}

export default function Component() {
    const navigate = useNavigate();
    const { pathname } = useLocation();
    const [urlInput, setUrlInput] = useState('');
    const [validationError, setValidationError] = useState<string | null>(null);

    // 从 URL 路径中同步 fid 到表单，支持浏览器前进/后退
    const fidFromUrl = pathname.match(/\/fid\/([^/]+)/)?.[1];
    useEffect(() => {
        if (fidFromUrl) setUrlInput(decodeURIComponent(fidFromUrl));
    }, [fidFromUrl]);

    /** 校验输入，返回错误文案或 null */
    const validate = (raw: string): string | null => {
        if (!raw) return null;
        const fid = extractFid(raw);
        if (/[?&]/.test(raw) && !/^\d+$/.test(fid))
            return '链接中未找到 fid 参数，请确认链接格式（示例：...?fid=123456）';
        if (!/^\d+$/.test(fid))
            return '请输入纯数字收藏夹 ID，或含 fid 参数的完整链接';
        return null;
    };

    const handleChange = (value: string) => {
        setUrlInput(value);
        setValidationError(validate(value.trim()));
    };

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault();
        const raw = urlInput.trim();
        const error = validate(raw);
        if (error) { setValidationError(error); return; }

        setValidationError(null);
        const fid = extractFid(raw);
        // 剥去已有的 /fid/xxx 后缀再拼接，避免子路由激活时相对路径叠加为 /fid/fid/xxx
        const base = pathname.replace(/\/fid\/[^/]+$/, '');
        navigate(`${base}/fid/${fid}`);
    };

    return (
        <>
            {/* 表单 */}
            <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6">
                <h2 className="text-base sm:text-lg font-semibold text-gray-900 mb-3 sm:mb-4">输入收藏夹信息</h2>
                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            收藏夹链接或ID
                        </label>
                        <input
                            type="text"
                            value={urlInput}
                            onChange={(e) => handleChange(e.target.value)}
                            placeholder="请输入收藏夹链接或ID"
                            className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent ${
                                validationError ? 'border-red-400 bg-red-50' : 'border-gray-300'
                            }`}
                        />
                        {validationError && (
                            <p className="mt-1.5 text-xs text-red-600">{validationError}</p>
                        )}
                    </div>
                    <button
                        type="submit"
                        disabled={!urlInput.trim()}
                        className="w-full md:w-auto px-6 py-2 bg-blue-600 text-white font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        获取视频列表
                    </button>
                </form>
            </div>

            {/* 子路由：收藏夹信息 + 视频列表 */}
            <Outlet />
        </>
    );
}
