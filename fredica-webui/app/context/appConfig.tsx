import { createContext, useContext, useLayoutEffect, useState } from "react";

type WebserverSchema = "http" | "https";

interface AppConfig {
    webserver_domain: string | null;
    webserver_port: string | null;
    webserver_schema: WebserverSchema | null;
    webserver_auth_token: string | null;
}

interface AppConfigContextType {
    appConfig: AppConfig;
    setAppConfig: (config: Partial<AppConfig>) => void;
    isStorageLoaded: boolean;
}

const STORAGE_KEY = "fredica_app_config";

const defaultConfig: AppConfig = { webserver_domain: null, webserver_port: null, webserver_schema: null, webserver_auth_token: null };

const AppConfigContext = createContext<AppConfigContextType>({
    appConfig: defaultConfig,
    setAppConfig: () => { },
    isStorageLoaded: false,
});

export function AppConfigProvider({ children }: { children: React.ReactNode }) {
    // 始终以 defaultConfig 初始化，避免 SSR hydration 不一致
    const [appConfig, setAppConfigState] = useState<AppConfig>(defaultConfig);
    const [isStorageLoaded, setIsStorageLoaded] = useState(false);

    // useLayoutEffect 在首次绘制前同步执行，不会产生 UI 闪烁
    useLayoutEffect(() => {
        try {
            const stored = localStorage.getItem(STORAGE_KEY);
            if (stored) {
                // webserver_auth_token 不从 localStorage 恢复
                setAppConfigState(JSON.parse(stored) as AppConfig);
            }
        } catch {
            console.debug(`failed load local storage key`, STORAGE_KEY);
        }
        setIsStorageLoaded(true);
    }, []);

    const setAppConfig = (config: Partial<AppConfig>) => {
        setAppConfigState(prev => {
            const next = { ...prev, ...config };
            try {
                localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
            } catch { }
            return next;
        });
    };

    return (
        <AppConfigContext.Provider value={{ appConfig, setAppConfig, isStorageLoaded }}>
            {children}
        </AppConfigContext.Provider>
    );
}

export function useAppConfig() {
    return useContext(AppConfigContext);
}
