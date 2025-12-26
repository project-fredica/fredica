import { useState } from "react";
import Sidebar from "./Sidebar";
import { Menu } from "lucide-react";
interface SidebarLayoutProps {
    children: React.ReactNode;
}


export const SidebarLayout: React.FC<SidebarLayoutProps> = ({ children }) => {
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);

    return (
        <div className="flex min-h-screen bg-white text-slate-900">
            <Sidebar isOpen={isSidebarOpen} onClose={() => setIsSidebarOpen(false)} />

            <main className="flex-1 flex flex-col min-w-0">
                {/* Header */}
                <header className="h-16 flex items-center justify-between px-4 lg:px-8 bg-white/80 backdrop-blur-md sticky top-0 z-30">
                    <div className="flex items-center gap-4">
                        <button
                            onClick={() => setIsSidebarOpen(true)}
                            className="p-2 hover:bg-gray-100 rounded-lg lg:hidden"
                        >
                            <Menu className="w-6 h-6 text-gray-600" />
                        </button>
                        {/* <div className="flex items-center gap-2 lg:hidden">
                            <div className="w-7 h-7 flex flex-col justify-center items-center gap-[1px]">
                                <div className="w-1 h-5 bg-black rounded-full rotate-[-20deg] translate-x-1"></div>
                                <div className="w-1 h-5 bg-black rounded-full rotate-[-20deg] -translate-x-1"></div>
                            </div>
                        </div> */}
                    </div>
                </header>

                {children}
            </main>
        </div>
    )
}