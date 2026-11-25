package com.telecom.support.common.web;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache headers for the built SPA (design 01-foundation backend §12 E2.1): {@code index.html} is revalidated on every
 * load ({@code no-cache}); the content-hashed {@code /assets/**} are cached for a year as {@code immutable}. Other static
 * files keep Spring Boot's default {@code /**} handler.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
    }
}
