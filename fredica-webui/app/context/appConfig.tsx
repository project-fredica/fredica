import { createContext, useContext, useLayoutEffect, useState } from "react";
import { callBridge } from "~/util/bridge";
import { json_parse } from "~/util/json";

type WebserverSchema = "http" | "https";

/** 后端 Web 服务器连接配置，所有字段均可为 null（未填写时使用默认值）。 */
interface AppConfig {
    /** 服务器域名，null 时 useAppFetch 默认使用 "localhost"。 */
    webserver_domain: string | null;
    /** 服务器端口号（字符串形式），null 时使用 DEFAULT_SERVER_PORT。 */
    webserver_port: string | null;
    /** 协议，null 时默认使用 "http"。 */
    webserver_schema: WebserverSchema | null;
    /** Bearer Token 鉴权令牌，持久化到 localStorage。 */
    webserver_auth_token: string | null;
}

/** React Context 暴露的类型，通过 `useAppConfig()` 获取。 */
interface AppConfigContextType {
    /** 当前生效的配置对象（初始值为 defaultConfig，localStorage 加载完成后更新）。 */
    appConfig: AppConfig;
    /** 更新配置的部分字段，内部会 merge 到当前配置并写入 localStorage。 */
    setAppConfig: (config: Partial<AppConfig>) => void;
    /**
     * localStorage 是否已完成初始化加载。
     * 在首次渲染时为 false，`useLayoutEffect` 执行完毕后变为 true。
     * 依赖配置才能正确渲染的组件可等待此标志后再渲染，避免闪烁。
     */
    isStorageLoaded: boolean;
}

/** localStorage 中存储配置的键名。 */
const STORAGE_KEY = "fredica_app_config";

const defaultConfig: AppConfig = { webserver_domain: null, webserver_port: null, webserver_schema: null, webserver_auth_token: null };

const AppConfigContext = createContext<AppConfigContextType>({
    appConfig: defaultConfig,
    setAppConfig: () => { },
    isStorageLoaded: false,
});

/**
 * 应用配置 Context Provider。
 *
 * - 始终以 `defaultConfig` 初始化，避免 SSR hydration 不一致。
 * - 使用 `useLayoutEffect` 在首次绘制前同步读取 localStorage，不产生 UI 闪烁。
 *
 * 在 `root.tsx` 中包裹整个应用：
 * ```tsx
 * <AppConfigProvider>
 *   <App />
 * </AppConfigProvider>
 * ```
 */
export function AppConfigProvider({ children }: { children: React.ReactNode }) {
    // 始终以 defaultConfig 初始化，避免 SSR hydration 不一致
    const [appConfig, setAppConfigState] = useState<AppConfig>(defaultConfig);
    const [isStorageLoaded, setIsStorageLoaded] = useState(false);

    // useLayoutEffect 在首次绘制前同步执行，不会产生 UI 闪烁
    useLayoutEffect(() => {
        try {
            const stored = localStorage.getItem(STORAGE_KEY);
            if (stored) {
                const parsed = json_parse<AppConfig>(stored);
                setAppConfigState(parsed);
                // 从 localStorage 恢复时也写入 Cookie，供 <video src> 使用
                if (parsed.webserver_auth_token) {
                    document.cookie = `fredica_token=${parsed.webserver_auth_token}; path=/; SameSite=Strict`;
                }
            }
        } catch {
            console.debug(`failed load local storage key`, STORAGE_KEY);
        }
        setIsStorageLoaded(true);

        // WebView 环境：通过 bridge 获取服务器连接信息（含 auth token）
        callBridge("get_server_info").then(raw => {
            const info = json_parse<Partial<AppConfig>>(raw);
            setAppConfigState(prev => ({ ...prev, ...info }));
            // 写入 fredica_token Cookie，供 <video src> 使用
            if (info.webserver_auth_token) {
                document.cookie = `fredica_token=${info.webserver_auth_token}; path=/; SameSite=Strict`;
            }
        }).catch(() => {
            // 浏览器环境或 bridge 未就绪时忽略
        });
    }, []);

    const setAppConfig = (config: Partial<AppConfig>) => {
        setAppConfigState(prev => {
            const next = { ...prev, ...config };
            try {
                localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
                console.debug('[appConfig] saved to localStorage:', next);
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

/**
 * 获取应用配置 Context，需在 `AppConfigProvider` 内使用。
 *
 * 返回 `{ appConfig, setAppConfig, isStorageLoaded }`，**不是**配置字段本身。
 * 必须先解构出 `appConfig`，再从中取字段：
 *
 * ```ts
 * // ✅ 正确：先取 appConfig，再取字段
 * const { appConfig } = useAppConfig();
 * const url = `${appConfig.webserver_schema}://${appConfig.webserver_domain}:${appConfig.webserver_port}`;
 *
 * // ✅ 需要 setAppConfig 或 isStorageLoaded 时一起解构
 * const { appConfig, setAppConfig, isStorageLoaded } = useAppConfig();
 *
 * // ❌ 错误：直接解构配置字段（会得到 undefined）
 * const { webserver_domain } = useAppConfig();
 * ```
 */
export function useAppConfig() {
    return useContext(AppConfigContext);
}
