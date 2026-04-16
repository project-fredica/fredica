import { Navigate } from "react-router";
import { useAppConfig } from "~/context/appConfig";
import { isLoggedIn, meetsMinRole, type AuthRole } from "~/util/auth";

interface RequireAuthProps {
    children: React.ReactNode;
    minRole?: AuthRole;
}

function ForbiddenPage() {
    return (
        <div className="flex min-h-screen items-center justify-center bg-slate-50">
            <div className="text-center">
                <p className="text-4xl font-bold text-slate-300">403</p>
                <p className="mt-2 text-sm text-slate-500">权限不足</p>
            </div>
        </div>
    );
}

export function RequireAuth({ children, minRole = "guest" }: RequireAuthProps) {
    const { appConfig, isStorageLoaded } = useAppConfig();

    if (!isStorageLoaded) {
        return (
            <div className="flex min-h-screen items-center justify-center text-slate-400 text-sm">
                Loading...
            </div>
        );
    }

    if (!isLoggedIn(appConfig)) {
        return <Navigate to="/login" replace />;
    }

    if (!meetsMinRole(appConfig.user_role, minRole)) {
        return <ForbiddenPage />;
    }

    return <>{children}</>;
}
