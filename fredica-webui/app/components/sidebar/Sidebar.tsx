
import React, { useEffect, useState } from 'react';
import {
  Plus, Search, History, Box, MessageSquare,
  Compass, ChevronDown, ChevronRight, FileText,
  Settings, HelpCircle, MessageCircle, BookOpen,
  LogOut,
  Home,
  Database,
  Cpu,
  Wrench,
  BrainCircuit,
  AlertCircle,
  Users,
} from 'lucide-react';
import { NavLink, useLocation } from 'react-router';
import { useAppFetch } from '~/util/app_fetch';
import { useAppConfig } from '~/context/appConfig';
import { isSessionUser } from '~/util/auth';

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
  if (routeTo && location.pathname === routeTo) {
    isActive = true
  } else if (routeTo && location.pathname.startsWith(routeTo + '/')) {
    isActive = true
  }
  const parentClassName = `flex flex-row items-center text-sm px-4 py-2 font-medium rounded-lg ${isActive ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`

  const Content = () => (
    <div id={`sidebar-item-${uid}`} className={parentClassName}>
      {/* <div></div> */}
      <Icon className="w-4 h-4 mr-3" />
      {title}
    </div>
  )

  return (
    <div className='cursor-pointer'>
      {routeTo
        ? <NavLink to={routeTo}> <Content />  </NavLink>
        : <Content />}
    </div>
  )
}

const ROLE_LABEL: Record<string, string> = { guest: "游客", tenant: "用户", root: "管理员" };

function UserFooter({ appConfig, setAppConfig, apiFetch }: {
  appConfig: ReturnType<typeof useAppConfig>["appConfig"];
  setAppConfig: ReturnType<typeof useAppConfig>["setAppConfig"];
  apiFetch: ReturnType<typeof useAppFetch>["apiFetch"];
}) {
  const sessionUser = isSessionUser(appConfig);
  const guestUser = !sessionUser && !!appConfig.webserver_auth_token;

  if (!sessionUser && !guestUser) return null;

  const handleLogout = async () => {
    if (sessionUser) {
      try {
        await apiFetch("/api/v1/AuthLogoutRoute", { method: "POST" }, { silent: true });
      } catch { /* ignore */ }
      setAppConfig({ session_token: null, user_role: null, user_display_name: null, user_permissions: null });
    } else {
      setAppConfig({ webserver_auth_token: null, user_role: null });
    }
    window.location.href = "/login";
  };

  return (
    <div className="p-3 border-t border-gray-100 flex items-center gap-2">
      <div className="flex-1 overflow-hidden">
        <div className="text-sm font-medium text-gray-800 truncate">
          {sessionUser ? (appConfig.user_display_name ?? "用户") : "游客"}
        </div>
        {appConfig.user_role && (
          <div className="text-xs text-gray-400">{ROLE_LABEL[appConfig.user_role] ?? appConfig.user_role}</div>
        )}
      </div>
      <button
        onClick={handleLogout}
        title="退出登录"
        className="p-1.5 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100 cursor-pointer"
      >
        <LogOut className="w-4 h-4" />
      </button>
    </div>
  );
}

const Sidebar: React.FC<SidebarProps> = ({ isOpen, onClose }) => {
  const location = useLocation();
  const [pendingRestartCount, setPendingRestartCount] = useState(0);
  const { apiFetch } = useAppFetch();
  const { appConfig, setAppConfig } = useAppConfig();

  // Poll restart log pending_review_count
  useEffect(() => {
    let active = true;
    const fetchCount = async () => {
      try {
        const { data } = await apiFetch(
          '/api/v1/RestartTaskLogListRoute?disposition=pending_review',
          { method: 'GET' },
          { silent: true },
        );
        if (active && data && typeof (data as any).pending_review_count === 'number') {
          setPendingRestartCount((data as any).pending_review_count);
        }
      } catch { /* ignore */ }
    };
    fetchCount();
    const id = setInterval(fetchCount, 30_000);
    return () => { active = false; clearInterval(id); };
  }, [apiFetch]);


  const sectionTitleClass = "flex flex-row items-center text-sm px-4 py-2 font-semibold text-gray-400 uppercase tracking-wider"

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
            <SideBarItem uid='add-resource' Icon={Plus} title='添加素材' routeTo='/add-resource' />
            <SideBarItem uid='library' Icon={Database} title='素材库' routeTo='/material-library' />
            <SideBarItem uid='tasks' Icon={Cpu} title='任务状态' routeTo='/tasks/status' />
            {/* 重启中断日志，有待处置条目时显示红色数量徽章 */}
            <div className='cursor-pointer relative'>
              <NavLink to='/tasks/restart'>
                <div id='sidebar-item-restart-log' className={`flex flex-row items-center text-sm px-4 py-2 font-medium rounded-lg ${location.pathname === '/tasks/restart' ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`}>
                  <AlertCircle className="w-4 h-4 mr-3" />
                  重启中断
                  {pendingRestartCount > 0 && (
                    <span className="ml-auto flex-shrink-0 min-w-[18px] h-[18px] flex items-center justify-center rounded-full bg-red-500 text-white text-[10px] font-bold px-1">
                      {pendingRestartCount}
                    </span>
                  )}
                </div>
              </NavLink>
            </div>
            <SideBarItem uid='weben' Icon={BrainCircuit} title='知识网络' routeTo='/weben' />
            <SideBarItem uid='tools' Icon={Wrench} title='小工具' routeTo='/tools' />
            {appConfig.user_role === 'root' && (
              <SideBarItem uid='admin' Icon={Users} title='管理后台' routeTo='/admin/users' />
            )}
            <SideBarItem uid='home' Icon={Home} title='主页' routeTo='/' />
            {/* <SideBarItem uid='add-project' Icon={Plus} title='新建项目' routeTo='/create-project' /> */}
            {/* <SideBarItem uid='product-dashboard' Icon={Plus} title='管理产品' routeTo='/product-dashboard' /> */}
            {/* <SideBarItem uid='source-search' Icon={Search} title='搜索' />
            <SideBarItem uid='source-history' Icon={History} title='历史' routeTo='/source-history' /> */}
          </div>

          <div>
            {/* <h3 className={sectionTitleClass}>空间</h3> */}
            {/* <SideBarItem uid='create-zone' Icon={Plus} title='创造空间' />
            <SideBarItem uid='zone-stm32' Icon={ChevronRight} title='嵌入式' /> */}
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
            {/** routeTo='/crud/model-config' */}
            {/* <SideBarItem uid='model-config' Icon={Box} title='模型设置' />
            <SideBarItem uid='feedback' Icon={HelpCircle} title='反馈意见' />
            <SideBarItem uid='quick-guide' Icon={BookOpen} title='快速指南' />
            <SideBarItem uid='home' Icon={Home} title='主页' routeTo='/' /> */}
          </div>
        </div>

        {/* Footer/User */}
        <UserFooter appConfig={appConfig} setAppConfig={setAppConfig} apiFetch={apiFetch} />
      </aside>
    </>
  );
};

export default Sidebar;
