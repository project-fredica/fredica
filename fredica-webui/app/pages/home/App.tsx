
import React, { useState } from 'react';
import {
  Menu, Upload, Link as LinkIcon, Clipboard, Mic,
  ArrowUp, Clock, ChevronDown, User
} from 'lucide-react';
import Sidebar from '../../components/sidebar/Sidebar';
import ActionCard from './components/ActionCard';
import SpaceCard from './components/SpaceCard';

const App: React.FC = () => {

  const [userName, setUserName] = useState("")
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
            <div className="flex items-center gap-2 lg:hidden">
              <div className="w-7 h-7 flex flex-col justify-center items-center gap-[1px]">
                <div className="w-1 h-5 bg-black rounded-full rotate-[-20deg] translate-x-1"></div>
                <div className="w-1 h-5 bg-black rounded-full rotate-[-20deg] -translate-x-1"></div>
              </div>
            </div>
          </div>
        </header>

        {/* Content Container */}
        <div className="flex-1 max-w-5xl mx-auto w-full px-4 sm:px-6 lg:px-12 py-8 lg:py-16 space-y-12">

          {/* Hero Section */}
          <section className="text-center space-y-8">
            <h1 className="text-3xl sm:text-4xl font-bold tracking-tight text-gray-900">
              准备好学习了吗{userName ? ", " + userName : ""}?
            </h1>

            {/* Quick Actions Grid */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <ActionCard
                icon={<Upload className="w-6 h-6" />}
                title="上传"
                description="文件、音频、视频"
              />
              {/* <ActionCard
                icon={<LinkIcon className="w-6 h-6" />}
                title="链接"
                description="YouTube, BiliBili, 网站"
              /> */}
              {/* <ActionCard
                icon={<Clipboard className="w-6 h-6" />}
                title="粘贴"
                description="复制的文本"
              /> */}
              {/* <ActionCard
                icon={<Mic className="w-6 h-6" />}
                title="记录"
                description="录制讲座"
              /> */}
            </div>

            {/* Search Bar */}
            {/* <div className="relative max-w-2xl mx-auto group">
              <input
                type="text"
                placeholder="学习任何东西"
                className="w-full h-14 pl-6 pr-14 bg-white border border-gray-100 rounded-full shadow-lg shadow-gray-200/50 focus:outline-none focus:ring-2 focus:ring-gray-200 transition-all text-lg placeholder:text-gray-300"
              />
              <button className="absolute right-2 top-2 w-10 h-10 bg-gray-600 hover:bg-black text-white rounded-full flex items-center justify-center transition-colors">
                <ArrowUp className="w-6 h-6" />
              </button>
            </div> */}
          </section>
           
              {/* <button className="flex items-center gap-1.5 text-sm font-medium text-gray-400 hover:text-gray-700 transition-colors">
                <Clock className="w-4 h-4" />
                最新的
                <ChevronDown className="w-4 h-4" />
              </button> */}
          {/* Spaces Section
          <section className="space-y-6">
            <div className="flex items-center gap-4 border-b border-gray-50 pb-4">
              <h2 className="text-xl font-bold text-gray-900">空间</h2>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-2 gap-6">
              <SpaceCard isEmpty title="" count={0} />
            </div>
          </section>  */}

        </div>
      </main>
    </div>
  );
};

export default App;
