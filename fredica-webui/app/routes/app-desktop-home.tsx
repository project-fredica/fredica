import { useState } from "react";
import { useNavigate } from "react-router";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import type { Route } from "./+types/app-desktop-home";

export function meta({ }: Route.MetaArgs) {
    return [
        { title: "Fredica 桌面端" },
        { name: "description", content: "Fredica 桌面端 App 内部主页。" },
    ];
}

export default function Component({
    params,
}: Route.ComponentProps) {
    const navigate = useNavigate();

    const handleOpenBrowser = () => {
        console.debug('按钮被点击了');
        console.debug('window is :', window);
        const kmpJsBridge = typeof window !== 'undefined' ? (window as any).kmpJsBridge : undefined
        console.debug('window.kmpJsBridge is :', kmpJsBridge);
        try {
            if (kmpJsBridge) {
                console.debug('调用 kmpJsBridge.callNative');
                kmpJsBridge.callNative('open_browser', JSON.stringify({
                    url: `http://localhost:7630`,
                    addServerInfoParam: true
                }), null);
            } else {
                console.warn('kmpJsBridge 不存在');
                alert('kmpJsBridge 不可用，请在桌面端环境中使用');
            }
        } catch (err) {
            console.error('调用jsBridge失败:', err);
            alert('调用jsBridge失败: ' + err);
        }
    };

    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            alignItems: 'center',
            gap: '16px',
            height: '100vh',
            width: '100vw',
        }}>
            <button
                onClick={handleOpenBrowser}
                style={{
                    padding: '16px 32px',
                    fontSize: '18px',
                    fontWeight: 600,
                    color: '#fff',
                    backgroundColor: '#2563eb',
                    border: 'none',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)',
                    transition: 'all 0.3s ease',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#1d4ed8';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 6px 12px rgba(0, 0, 0, 0.15)';
                }}
                onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#2563eb';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 4px 6px rgba(0, 0, 0, 0.1)';
                }}
            >
                在浏览器中打开
            </button>
            {/* <button
                onClick={() => navigate('/app-user-setting')}
                style={{
                    padding: '12px 28px',
                    fontSize: '16px',
                    fontWeight: 600,
                    color: '#374151',
                    backgroundColor: '#f9fafb',
                    border: '1px solid #d1d5db',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.08)',
                    transition: 'all 0.3s ease',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#f3f4f6';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 4px 8px rgba(0, 0, 0, 0.1)';
                }}
                onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#f9fafb';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 1px 3px rgba(0, 0, 0, 0.08)';
                }}
            >
                打开用户设置
            </button> */}
            <button
                onClick={() => navigate('/app-desktop-setting')}
                style={{
                    padding: '12px 28px',
                    fontSize: '16px',
                    fontWeight: 600,
                    color: '#374151',
                    backgroundColor: '#f9fafb',
                    border: '1px solid #d1d5db',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.08)',
                    transition: 'all 0.3s ease',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = '#f3f4f6';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.boxShadow = '0 4px 8px rgba(0, 0, 0, 0.1)';
                }}
                onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = '#f9fafb';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 1px 3px rgba(0, 0, 0, 0.08)';
                }}
            >
                打开桌面服务器设置
            </button>
        </div>
    )
}