package com.example.shortlink.controller;

import com.example.shortlink.entity.ShortLink;
import com.example.shortlink.service.ShortLinkService;
import com.example.shortlink.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * 短链接 REST 控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;
    private final StatsService statsService;

    // ==================== API 接口 ====================

    /**
     * 生成短链接
     * POST /api/shorten
     * Body: { "url": "https://example.com/very/long/url" }
     */
    @PostMapping("/api/shorten")
    public ResponseEntity<Map<String, Object>> shorten(@RequestBody ShortenRequest request,
                                                        HttpServletRequest httpRequest) {
        String url = request.getUrl().trim();

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "URL 必须以 http:// 或 https:// 开头"));
        }

        // URL 长度校验
        if (url.length() > 2048) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "URL 长度不能超过 2048 字符"));
        }

        try {
            ShortLink shortLink = shortLinkService.shorten(url);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "shortCode", shortLink.getShortCode(),
                    "shortUrl", "http://localhost:8080/" + shortLink.getShortCode(),
                    "originalUrl", shortLink.getOriginalUrl()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("请求参数不合法: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("生成短链接失败: {}", url, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "服务器内部错误"));
        }
    }

    /**
     * 短链接重定向
     * GET /{短码} → 302 重定向到原始链接
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                          HttpServletRequest request) {
        ShortLink shortLink = shortLinkService.getByShortCode(shortCode);
        if (shortLink == null) {
            return ResponseEntity.notFound().build();
        }

        // 异步记录访问统计（Redis 操作极快，同步执行）
        statsService.recordAccess(shortCode, extractIp(request));

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(shortLink.getOriginalUrl()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    // ==================== 工具方法 ====================

    /**
     * 从请求中提取客户端真实 IP。
     * 优先从代理头（X-Forwarded-For, X-Real-IP）获取，兜底用 remoteAddr。
     */
    private String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能包含多级代理 IP，取第一个
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    // ==================== 请求体 ====================

    @Data
    public static class ShortenRequest {
        @NotBlank(message = "URL 不能为空")
        private String url;
    }
}
