package com.soundmatch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try (var ignored = MDC.putCloseable("request_id", requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                log.atInfo().addKeyValue("method", request.getMethod()).addKeyValue("status", response.getStatus())
                        .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000).log("http_request");
            }
        }
    }
}
