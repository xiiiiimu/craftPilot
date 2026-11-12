package com.craftpilot.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");
    private static final Logger LOG = LoggerFactory.getLogger(TraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String traceId = supplied != null && UUID_TEXT.matcher(supplied).matches()
                ? UUID.fromString(supplied).toString() : UUID.randomUUID().toString();
        response.setHeader(HEADER, traceId);
        long start = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                LOG.info("http_request method={} status={} elapsedMs={}", request.getMethod(),
                        response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
        }
    }
}
