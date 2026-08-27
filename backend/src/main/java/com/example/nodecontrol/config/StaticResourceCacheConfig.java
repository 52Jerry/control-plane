package com.example.nodecontrol.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Duration;

/**
 * 前端静态资源缓存策略：
 * - index.html 不缓存，保证发布后浏览器立即加载新的 hash 资源（否则
 *   会继续用旧 JS，出现导出链接为空等已修复问题）；
 * - /assets/** 文件名带内容 hash，允许长期缓存。
 */
@Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable());
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> indexNoCacheFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                response.setHeader("Cache-Control", "no-cache");
                filterChain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/", "/index.html");
        return registration;
    }
}
