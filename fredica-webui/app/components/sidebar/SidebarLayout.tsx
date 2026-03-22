import { useState } from "react";
import Sidebar from "./Sidebar";
import { Menu } from "lucide-react";
import { FloatingPlayerProvider } from "~/context/floatingPlayer";
import { FloatingVideoPlayerSingleton } from "~/components/material/FloatingVideoPlayerSingleton";
interface SidebarLayoutProps {
    children: React.ReactNode;
}


export const SidebarLayout: React.FC<SidebarLayoutProps> = ({ children }) => {
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);

    return (
        <FloatingPlayerProvider>
            <div className="flex min-h-screen text-slate-900">
                <Sidebar isOpen={isSidebarOpen} onClose={() => setIsSidebarOpen(false)} />

                <main className="flex-1 flex flex-col min-w-0 max-h-screen overflow-y-auto overflow-x-auto">
                    {/* Header */}
                    <header className="absolute px-4 lg:px-8 backdrop-blur-md top-1 z-30">
                        <div className="flex items-center gap-4">
                            <button
                                onClick={() => setIsSidebarOpen(true)}
                                className="p-2 rounded-lg lg:hidden cursor-pointer"
                            >
                                <Menu className="w-6 h-6 text-gray-600" />
                            </button>
                        </div>
                    </header>

                    {children}
                </main>
            </div>

            {/* App 级悬浮播放器（Mode B），固定在右下角 */}
            <FloatingVideoPlayerSingleton />
        </FloatingPlayerProvider>
    );
}