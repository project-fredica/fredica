
import React from 'react';
import {
  Plus, Search, History, Box, MessageSquare,
  Compass, ChevronDown, ChevronRight, FileText,
  Settings, HelpCircle, MessageCircle, BookOpen,
  LogOut,
  Home
} from 'lucide-react';
import { NavLink, useLocation } from 'react-router';

interface SidebarItemProps {
  uid: string;
  title: string
  Icon: React.ForwardRefExoticComponent<any>,
  routeTo?: string
}

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}


const SideBarItem: React.FC<SidebarItemProps> = ({ uid, title, Icon, routeTo }) => {
  const location = useLocation()

  let isActive = false
  if (routeTo && location.pathname == routeTo) {
    isActive = true
  }
  const parentClassName = `flex flex-row items-center text-sm px-4 py-2 font-medium rounded-lg cursor-pointer ${isActive ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`

  const Content = () => (
    <div id={`sidebar-item-${uid}`} className={parentClassName}>
      {/* <div></div> */}
      <Icon className="w-4 h-4 mr-3" />
      {title}
    </div>
  )

  return (
    routeTo
      ? <NavLink to={routeTo}> <Content />  </NavLink>
      : <Content />
  )
}

const Sidebar: React.FC<SidebarProps> = ({ isOpen, onClose }) => {
  // const sectionClass = "mb-6";

  // const sectionTitleClass = "px-4 text-sm font-semibold text-gray-400 uppercase tracking-wider";
  // const itemClass = (active = false) => `flex items-center px-4 py-2 text-sm font-medium rounded-lg transition-colors cursor-pointer ${active ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
  //   }`;


  const sectionTitleClass = "flex flex-row items-center text-sm px-4 py-2 font-semibold text-gray-400 uppercase tracking-wider"
  // const itemClass = (active = false) => `flex flex-row items-center text-sm px-4 py-2 font-medium rounded-lg cursor-pointer ${active ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`

  return (
    <>
      {/* Mobile Overlay */}
      {isOpen && (
        <div
          id='sidebar-mobile-overlay'
          className="fixed inset-0 bg-black/20 z-40 lg:hidden backdrop-blur-sm"
          onClick={onClose}
        />
      )}

      {/* Sidebar Container */}
      <aside
        id='sidebar'
        className={`
        fixed inset-y-0 left-0 z-50 w-64 bg-white border-r border-gray-100 flex flex-col transform transition-transform duration-300 ease-in-out
        ${isOpen ? 'translate-x-0' : '-translate-x-full'}
        lg:translate-x-0 lg:static lg:inset-0
      `} style={{ "maxHeight": "100vh" }}>

        {/* Scrollable Nav */}
        <div className="flex flex-col overflow-y-auto py-4 gap-4">
          <div className='striky'>
            <SideBarItem uid='source-add' Icon={Plus} title='添加内容' />
            <SideBarItem uid='source-search' Icon={Search} title='搜索' />
            <SideBarItem uid='source-history' Icon={History} title='历史' routeTo='/source-history' />
          </div>

          <div>
            <h3 className={sectionTitleClass}>空间</h3>
            <SideBarItem uid='create-zone' Icon={Plus} title='创造空间' />
            <SideBarItem uid='zone-stm32' Icon={ChevronRight} title='嵌入式' />
            {/* <div className={itemClass()}>
              <Plus className="w-4 h-4 mr-3" />
              创造空间
            </div> */}
            {/* <div className="space-y-1">
              <div className={itemClass(true)}>
                <Box className="w-4 h-4 mr-3" />
                <span className="flex-1">嵌入式</span>
                <ChevronDown className="w-4 h-4 text-gray-400" />
              </div>
              <div className="pl-12 space-y-1">
                <div className="text-xs text-gray-500 py-1 flex items-center gap-2 cursor-pointer hover:text-gray-800">
                  <MessageSquare className="w-3 h-3" />
                  Getting Started with Devel...
                </div>
                <div className="text-xs text-gray-500 py-1 flex items-center gap-2 cursor-pointer hover:text-gray-800">
                  <div className="w-3 h-0.5 bg-gray-400"></div>
                  STM32F407最小系统板常...
                </div>
                <div className="text-xs text-gray-500 py-1 flex items-center gap-2 cursor-pointer hover:text-gray-800">
                  <ChevronRight className="w-3 h-3" />
                  第2讲 开发板入门
                </div>
              </div>
              <div className={itemClass()}>
                <Box className="w-4 h-4 mr-3" />
                Tcsnzh的空间
              </div> 
            </div>*/}
          </div>

          <div >
            {/* <h3 className={sectionTitleClass}>近期活动</h3>
            <SideBarItem uid='aceent-zone' Icon={Box} title='Getting Started with Dev...' /> */}

            {/* <div className="px-4 space-y-3">
              <div className="flex items-center gap-2 text-xs text-gray-500 cursor-pointer hover:text-gray-800">
                <MessageSquare className="w-3 h-3" />
                <span className="truncate flex-1">Getting Started with Dev...</span>
                <Box className="w-3 h-3" />
              </div>
              <div className="flex items-center gap-2 text-xs text-gray-500 cursor-pointer hover:text-gray-800">
                <div className="w-3 h-0.5 bg-gray-400"></div>
                <span className="truncate flex-1">STM32F407最小系统板常...</span>
                <Box className="w-3 h-3" />
              </div>
              <div className="flex items-center gap-2 text-xs text-gray-500 cursor-pointer hover:text-gray-800">
                <ChevronRight className="w-3 h-3" />
                <span className="truncate flex-1">第2讲 开发板入门</span>
                <Box className="w-3 h-3" />
              </div>
            </div> */}
          </div>

          <div >
            <h3 className={sectionTitleClass}>帮助与工具</h3>
            <SideBarItem uid='model-config' Icon={Box} title='模型设置' routeTo='/model-config' />
            <SideBarItem uid='feedback' Icon={HelpCircle} title='反馈意见' />
            <SideBarItem uid='quick-guide' Icon={BookOpen} title='快速指南' />
            <SideBarItem uid='home' Icon={Home} title='主页' routeTo='/' />


          </div>
        </div>

        {/* Footer/User */}
        {/* <div className="p-4 border-t border-gray-50 space-y-4">
          <div className="bg-emerald-50 rounded-lg p-1">
            <div className="text-[10px] text-emerald-600 font-bold text-center py-0.5 uppercase tracking-tighter">Free 计划</div>
          </div>
          <button className="w-full flex items-center justify-between p-2 rounded-xl border border-gray-100 hover:bg-gray-50 transition-colors">
            <div className="flex items-center gap-3 overflow-hidden">
              <img src="https://picsum.photos/seed/user/40/40" className="w-8 h-8 rounded-full border border-gray-200" alt="Avatar" />
              <div className="text-left overflow-hidden">
                <div className="text-sm font-semibold text-gray-900 truncate">TcSnZh _</div>
              </div>
            </div>
            <ChevronDown className="w-4 h-4 text-gray-400" />
          </button>
        </div> */}
      </aside>
    </>
  );
};

export default Sidebar;
