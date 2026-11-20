package com.gyeongjung.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RequestLogFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String DEVICE_ID_HEADER = "X-Device-Id";
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String NO_VALUE = "-";
    // 헤더 값이 그대로 로그에 찍히므로 줄바꿈 등으로 로그를 위조하지 못하게 안전한 문자만 허용한다
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int GENERATED_ID_LENGTH = 12;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info(
                    "{} {} {} {}ms device={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    (System.nanoTime() - startNanos) / NANOS_PER_MILLI,
                    safeHeader(request, DEVICE_ID_HEADER));
            MDC.remove(REQUEST_ID_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        return isSafe(header) ? header : generateId();
    }

    private static String safeHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return isSafe(value) ? value : NO_VALUE;
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches();
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_ID_LENGTH);
    }
}
