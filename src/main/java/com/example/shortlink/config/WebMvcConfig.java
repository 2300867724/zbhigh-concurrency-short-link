package com.example.shortlink.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 —— 注册限流拦截器。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 限流拦截器应用于所有路径
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                // 静态资源不走限流
                .excludePathPatterns("/css/**", "/js/**", "/h2-console/**");
    }
}
