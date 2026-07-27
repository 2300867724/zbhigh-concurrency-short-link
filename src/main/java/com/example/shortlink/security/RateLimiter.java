package com.example.shortlink.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 漏桶算法（Leaky Bucket）接口限流器 —— 基于 Redis Lua 原子操作。
 *
 * <h3>漏桶原理</h3>
 * <pre>
 *         请求（水滴）
 *           ↓  ↓  ↓
 *       ┌───────────┐
 *       │  漏桶      │  容量 = 10
 *       │  ~~~      │
 *       └────┬──────┘
 *            ↓  恒速泄漏（10 滴/秒）
 *       被处理的请求
 *
 *   水满 → 溢出 → 返回 429 Too Many Requests
 * </pre>
 *
 * <p>与令牌桶的区别：漏桶强制平滑输出速率，不允许突发流量；
 * 令牌桶允许积累令牌应对突发。这里用漏桶更严格地保护后端。
 *
 * <h3>Redis 数据结构</h3>
 * Key: {@code rate_limit:{IP}} → Hash { water, last_time }，TTL 120s
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /** Lua 脚本：原子的漏桶检查 */
    private static final DefaultRedisScript<Long> LEAKY_BUCKET_SCRIPT;

    static {
        LEAKY_BUCKET_SCRIPT = new DefaultRedisScript<>();
        LEAKY_BUCKET_SCRIPT.setResultType(Long.class);
        LEAKY_BUCKET_SCRIPT.setScriptText("""
            local key       = KEYS[1]
            local capacity  = tonumber(ARGV[1])   -- 桶容量
            local leak_rate = tonumber(ARGV[2])   -- 泄漏速率（滴/秒）
            local now       = tonumber(ARGV[3])   -- 当前时间戳（秒，含小数）

            local water     = tonumber(redis.call('HGET', key, 'water')) or 0
            local last_time = tonumber(redis.call('HGET', key, 'last_time')) or now

            -- 计算已经泄漏的水量
            local elapsed = math.max(0, now - last_time)
            water = math.max(0, water - elapsed * leak_rate)

            if water < capacity then
                water = water + 1
                redis.call('HSET', key, 'water', water, 'last_time', now)
                redis.call('EXPIRE', key, 120)
                return 1   -- 放行
            else
                redis.call('HSET', key, 'last_time', now)
                redis.call('EXPIRE', key, 120)
                return 0   -- 拒绝
            end
            """);
    }

    // ==================== 配置 ====================

    /** 桶容量（允许的突发请求数） */
    private static final long CAPACITY = 10;

    /** 泄漏速率（每秒处理请求数） */
    private static final double LEAK_RATE = 10.0;

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "rate_limit:";

    // ==================== 公共方法 ====================

    /**
     * 检查指定 IP 的本次请求是否被允许。
     *
     * @param ip 客户端 IP
     * @return true = 放行，false = 触发限流
     */
    public boolean allow(String ip) {
        try {
            double now = System.currentTimeMillis() / 1000.0;
            Long result = redisTemplate.execute(
                    LEAKY_BUCKET_SCRIPT,
                    List.of(KEY_PREFIX + ip),
                    String.valueOf(CAPACITY),
                    String.valueOf(LEAK_RATE),
                    String.valueOf(now)
            );
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                log.warn("触发限流: ip={}", ip);
            }
            return allowed;
        } catch (Exception e) {
            // Redis 异常时降级放行，避免影响正常服务
            log.error("限流器 Redis 异常（降级放行）: {}", e.getMessage());
            return true;
        }
    }
}
