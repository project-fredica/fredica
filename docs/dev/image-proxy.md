---
title: 图片代理与缓存
order: 210
---

# 图片代理与缓存

所有来自外部平台（如 B 站 CDN）的封面图都通过 Fredica 内置的图片代理服务访问：

1. 前端请求 `/api/v1/ImageProxyRoute?url={encodedUrl}`
2. 代理服务以 URL 的 SHA-256 为缓存键，检查本地缓存（`.data/cache/images/`）
3. 缓存命中直接返回；未命中则从外部下载并写入缓存
4. 响应头携带 `Cache-Control: public, max-age=31536000, immutable`

> 图片代理接口 `requiresAuth = false`，无需 Token，可直接用于 `<img src={...}>` 属性。

::: code-group

```kotlin [ImageProxyRoute.kt]
// shared/src/commonMain/.../routes/ImageProxyRoute.kt
<!--@include: ../../shared/src/commonMain/kotlin/com/github/project_fredica/api/routes/ImageProxyRoute.kt-->
```

```ts [useImageProxyUrl（app_fetch.ts）]
// fredica-webui/app/util/app_fetch.ts — useImageProxyUrl
export function useImageProxyUrl(): (imageUrl: string) => string {
    const { appConfig } = useAppConfig();
    const host = getAppHost(
        appConfig.webserver_domain,
        appConfig.webserver_port,
    );
    return useCallback(
        (imageUrl: string) =>
            `${host}/api/v1/ImageProxyRoute?url=${encodeURIComponent(imageUrl)}`,
        [host],
    );
}
```

:::
