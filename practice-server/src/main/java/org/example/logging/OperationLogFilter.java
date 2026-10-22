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
import java.util.concurrent.TimeUnit;

/** Logs request metadata only; credentials, query parameters and bodies are never copied. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OperationLogFilter extends OncePerRequestFilter {
    static final String OUTCOME = OperationLogFilter.class.getName() + ".outcome";
    static final String REASON = OperationLogFilter.class.getName() + ".reason";
    static final String SERVER_ERROR = OperationLogFilter.class.getName() + ".serverError";
    private static final Logger log = LoggerFactory.getLogger(OperationLogFilter.class);

    private final java.util.Map<String, PollWarning> pollWarnings = new java.util.HashMap<>();
    private final java.util.function.LongSupplier nanoTime;

    public OperationLogFilter() { this(System::nanoTime); }

    OperationLogFilter(java.util.function.LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    private static class PollWarning {
        long lastWarning;
        long suppressed;
        PollWarning(long now) { lastWarning = now; }
    }

    private synchronized boolean mergePollWarning(String method, String path, int status) {
        if (!"GET".equals(method) || !("/common/networkTopology".equals(path)
                || "/api/v1/datasets".equals(path))) return false;
        PollWarning state = pollWarnings.get(path);
        if (status != 401) {
            if (status < 400 && state != null) {
                if (state.suppressed > 0) log.info("【请求】轮询访问已恢复 path={} 已合并401告警={}次", path, state.suppressed);
                pollWarnings.remove(path);
            }
            return false;
        }
        long now = nanoTime.getAsLong();
        if (state == null) {
            pollWarnings.put(path, new PollWarning(now));
            return false;
        }
        state.suppressed++;
        if (now - state.lastWarning >= TimeUnit.SECONDS.toNanos(60)) {
            log.warn("【请求】轮询持续未认证 method=GET path={} httpStatus=401 result=FAILED 已合并告警={}次", path, state.suppressed);
            state.suppressed = 0;
            state.lastWarning = now;
        }
        return true;
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
                        ? "FAILED" : businessOutcome != null ? businessOutcome.toString()
                        : status == 202 ? "ACCEPTED" : "SUCCESS";
                String path = request.getRequestURI().replaceAll("[\\r\\n\\t]", "_");
                boolean background = path.startsWith("/actuator/") || ("/api/network/metrics/batch".equals(path)
                        || "/api/network/nodes/public-ip".equals(path)
                        || "/api/network/nodes/heartbeat".equals(path)
                        || "/api/network/probe-decision".equals(path));
                if ("OPTIONS".equals(request.getMethod())) background = true;
                String message = "【请求】请求结束 method={} path={} httpStatus={} result={} durationMs={} requestId={}";
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Object reason = request.getAttribute(REASON);
                if (reason != null) message += " reason=" + reason;
                Object[] args = {request.getMethod(), path, status, outcome,
                        durationMs, traceId};
                if (status >= 500 || Boolean.TRUE.equals(request.getAttribute(SERVER_ERROR))) log.error(message, args);
                else if (!Boolean.TRUE.equals(request.getAttribute(SERVER_ERROR))
                        && mergePollWarning(request.getMethod(), path, status)) { /* Repeated polling warning merged. */ }
                else if ("FAILED".equals(outcome) || "PARTIAL_SUCCESS".equals(outcome)
                        || "NO_ELIGIBLE_RESOURCE".equals(outcome) || "NO_OPERATION".equals(outcome)) log.warn(message, args);
                else if (durationMs >= 1000) log.warn(message, args);
                else if (background || "GET".equals(request.getMethod())
                        || "HEAD".equals(request.getMethod())) log.debug(message, args);
                else log.info(message, args);
            } finally {
                if (previous == null) MDC.remove("traceId");
                else MDC.put("traceId", previous);
            }
        }
    }
}
