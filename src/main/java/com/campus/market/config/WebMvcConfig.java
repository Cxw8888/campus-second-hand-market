package com.campus.market.config;

import com.campus.market.config.properties.StorageProperties;
import com.campus.market.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置：注册认证拦截器 + 本地存储静态资源映射。
 *
 * <p>拦截范围 {@code /api/v1/**}，排除三类中的"完全公开"与"可选认证"路径中
 * 无需 Token 解析的接口（登录、注册、验证码、支付回调等），
 * 其余 /api/v1/** 一律走强制认证（未携带/无效 Token → HTTP 401）。</p>
 *
 * <p>CORS 见 {@link CorsConfig}。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final StorageProperties storageProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                // 完全公开 + 可选认证路径：不强制 Token（拦截器内部仍会按语义处理）
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/auth/email-code",
                        "/api/v1/auth/reset-password",
                        "/api/v1/order/pay/callback"
                );
    }

    /**
     * 本地存储（storage.type=local）静态资源映射。
     *
     * <p>{@code LocalStorageImpl} 返回的 URL 前缀为 {@code app.storage.local.url-prefix}
     * （默认 {@code /static/uploads}），此处把该前缀映射到磁盘目录 {@code app.storage.local.base-path}，
     * 使上传后的图片可直接通过 URL 访问；该前缀已在 {@code PathConstants.PUBLIC_PATHS} 中列为完全公开路径。</p>
     *
     * <p>storage.type=minio 时不注册该映射（对象存储由 MinIO 自行提供访问地址）。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!"local".equalsIgnoreCase(storageProperties.getType())) {
            log.info("storage.type={} 非 local, 跳过本地静态资源映射", storageProperties.getType());
            return;
        }
        String urlPrefix = storageProperties.getLocal().getUrlPrefix();
        Path basePath = Paths.get(storageProperties.getLocal().getBasePath()).toAbsolutePath().normalize();
        String location = basePath.toUri().toString();
        registry.addResourceHandler(normalizePattern(urlPrefix))
                .addResourceLocations(location);
        log.info("本地存储静态资源映射: {} -> {}", urlPrefix + "/**", location);
    }

    /**
     * 规范化资源路径模式：补全首尾斜杠，避免映射失效。
     */
    private String normalizePattern(String urlPrefix) {
        String prefix = urlPrefix == null || urlPrefix.isBlank() ? "/static/uploads" : urlPrefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/**";
    }
}
