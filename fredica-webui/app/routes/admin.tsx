import { NavLink, Outlet } from "react-router";
import { RequireAuth } from "~/components/auth/RequireAuth";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";

function AdminTabNav() {
    const tabClass = (isActive: boolean) =>
        `px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            isActive
                ? "border-slate-800 text-slate-800"
                : "border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300"
        }`;

    return (
        <nav className="flex border-b border-slate-200">
            <NavLink
                to="/admin/users"
                className={({ isActive }) => tabClass(isActive)}
            >
                用户管理
            </NavLink>
            <NavLink
                to="/admin/invites"
                className={({ isActive }) => tabClass(isActive)}
            >
                邀请链接
            </NavLink>
        </nav>
    );
}

export default function AdminLayout() {
    return (
        <RequireAuth minRole="root">
            <SidebarLayout>
                <div className="flex flex-col h-full">
                    <AdminTabNav />
                    <div className="flex-1 overflow-y-auto">
                        <Outlet />
                    </div>
                </div>
            </SidebarLayout>
        </RequireAuth>
    );
}
