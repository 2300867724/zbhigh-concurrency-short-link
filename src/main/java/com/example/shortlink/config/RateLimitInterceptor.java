package com.example.shortlink.config;

import com.example.shortlink.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 限流拦截器 —— 对每个请求执行漏桶检查。
 *
 * <p>在请求进入 Controller 之前，根据客户端 IP 判断是否触发限流。
 * 拦截规则：同一 IP 每秒最多 10 次（漏桶容量=10，泄漏速率=10/s）。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) throws Exception {
        String ip = extractIp(request);

        if (!rateLimiter.allow(ip)) {
            response.setStatus(HttpStatus.TOOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"请求过于频繁，请稍后再试\"}"
            );
            return false;
        }

        return true;
    }

    /**
     * 提取客户端真实 IP（考虑反向代理头）。
     */
    private String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }
}
