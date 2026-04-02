package com.nium.cardplatform.shared.config;

import com.nium.cardplatform.shared.ratelimiting.RateLimitInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.UUID;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * Injects a unique requestId into MDC for every request.
     * Reads X-Request-Id header if provided (client-side correlation),
     * generates a UUID otherwise. Always echoes it back in the response.
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> mdcFilter() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                String requestId = req.getHeader("X-Request-Id");
                if (requestId == null || requestId.isBlank()) {
                    requestId = UUID.randomUUID().toString();
                }
                MDC.put("requestId", requestId);
                res.setHeader("X-Request-Id", requestId);
                try {
                    chain.doFilter(req, res);
                } finally {
                    MDC.clear();
                }
            }
        });
        reg.addUrlPatterns("/*");
        reg.setOrder(0);
        return reg;
    }
}
