package com.agentflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                // PUT/DELETE 必须在列：开发模式经 Vite 代理（5173→8888）时请求带 Origin 头，
                // 后端按跨域处理，方法不在白名单会被 Spring 以 403「Invalid CORS request」拒绝——
                // 切账户/删记录等操作在代理下曾整批静默失败
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*");
    }
}
