import { useState } from "react";
import Sidebar from "./Sidebar";
import { Menu } from "lucide-react";
interface SidebarLayoutProps {
    children: React.ReactNode;
}


export const SidebarLayout: React.FC<SidebarLayoutProps> = ({ children }) => {
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);

    return (
        <div className="flex min-h-screen text-slate-900">
            <Sidebar isOpen={isSidebarOpen} onClose={() => setIsSidebarOpen(false)} />

            <main className="flex-1 flex flex-col min-w-0" style={{ maxHeight: '100vh', maxWidth: '100vw', overflow: "scroll" }}>
                {/* Header */}
                <header className="absolute px-4 lg:px-8 backdrop-blur-md top-1 z-30">
                    <div className="flex items-center gap-4">
                        <button
                            onClick={() => setIsSidebarOpen(true)}
                            className="p-2 rounded-lg lg:hidden cursor-pointer"
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