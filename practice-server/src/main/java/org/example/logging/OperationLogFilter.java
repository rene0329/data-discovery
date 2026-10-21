package org.example.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.HandlerMapping;
import java.util.concurrent.TimeUnit;

/** Logs request metadata only; credentials, query parameters and bodies are never copied. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperationLogFilter extends OncePerRequestFilter {
    static final String OUTCOME = OperationLogFilter.class.getName() + ".outcome";
    private static final Logger log = LoggerFactory.getLogger(OperationLogFilter.class);

    private final ConcurrentHashMap<String, Long> lastReadLogs = new ConcurrentHashMap<>();
    private static final long READ_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    boolean shouldLogRead(String route, long now) {
        // Use route templates rather than resource IDs; bound storage for unknown routes.
        if (!lastReadLogs.containsKey(route) && lastReadLogs.size() >= 1024) return false;
        final boolean[] accepted = {false};
        lastReadLogs.compute(route, (key, previous) -> {
            if (previous == null || now - previous >= READ_INTERVAL_NANOS) {
                accepted[0] = true;
                return now;
            }
            return previous;
        });
        return accepted[0];
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        String previous = MDC.get("traceId");
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-ID", traceId);
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            try {
                int status = failed ? 500 : response.getStatus();
                Object businessOutcome = request.getAttribute(OUTCOME);
                String outcome = failed || status >= 400 || "FAILED".equals(businessOutcome)
                        ? "FAILED" : status == 202 ? "ACCEPTED" : "SUCCESS";
                String path = request.getRequestURI().replaceAll("[\\r\\n\\t]", "_");
                boolean background = path.startsWith("/actuator/") || ("/api/network/metrics/batch".equals(path)
                        || "/api/network/nodes/public-ip".equals(path)
                        || "/api/network/nodes/heartbeat".equals(path)
                        || "/api/network/probe-decision".equals(path));
                if ("OPTIONS".equals(request.getMethod())) background = true;
                String message = "请求结束 method={} path={} httpStatus={} result={} durationMs={} requestId={}";
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                boolean repeatedRead = "GET".equals(request.getMethod()) && !background
                        && durationMs < 1000 && !"FAILED".equals(outcome)
                        && !shouldLogRead(route == null ? path : route.toString(), System.nanoTime());
                Object[] args = {request.getMethod(), path, status, outcome,
                        durationMs, traceId};
                if ("FAILED".equals(outcome)) log.warn(message, args);
                else if (durationMs >= 1000) log.warn(message, args);
                else if (background || repeatedRead) log.debug(message, args);
                else log.info(message, args);
            } finally {
                if (previous == null) MDC.remove("traceId");
                else MDC.put("traceId", previous);
            }
        }
    }
}
