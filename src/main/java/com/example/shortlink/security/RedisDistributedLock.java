package com.example.shortlink.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 分布式锁 —— 基于 SET NX EX 加锁 + Lua 脚本安全释放。
 *
 * <h3>为什么需要分布式锁？</h3>
 * 同一长链接的并发请求可能同时走到"查重 → 未命中 → 生成新短码"路径，
 * 导致生成多条重复记录。分布式锁把整个查重+生成流程串行化：
 *
 * <pre>
 * Thread A ── acquire lock ── check DB ── insert ── release lock
 * Thread B ── wait lock    ── acquire lock ── check DB ── found! return existing
 * </pre>
 *
 * <h3>锁的实现</h3>
 * <ul>
 *   <li><b>加锁</b>：SET lock_key lock_value NX EX timeout → 原子抢占</li>
 *   <li><b>释放</b>：Lua 脚本先 GET 比较 value，匹配才 DEL → 防止误删他人锁</li>
 *   <li><b>锁粒度</b>：按 URL 的 MD5 做 key，不同 URL 互不阻塞</li>
 * </ul>
 *
 * <h3>对比 Redisson</h3>
 * 这里手写实现以便理解原理。生产环境推荐 Redisson 的 RLock（自带看门狗自动续期）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedLock {

    private final StringRedisTemplate redisTemplate;

    /** 锁超时（秒）：避免死锁，持锁线程崩溃后锁自动释放 */
    private static final long LOCK_TIMEOUT = 5;

    /** 获取锁的最大等待时间（秒） */
    private static final long WAIT_TIMEOUT = 3;

    /** 锁 key 前缀 */
    private static final String LOCK_PREFIX = "lock:shorten:";

    /** 释放锁的 Lua 脚本：原子校验 value → DEL */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setResultType(Long.class);
        UNLOCK_SCRIPT.setScriptText("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """);
    }

    // ==================== 公共接口 ====================

    /**
     * 尝试获取分布式锁（自旋等待）。
     *
     * @param lockKey 锁标识（内部会加前缀，按 URL 区分）
     * @return 锁令牌，获取失败返回 null
     */
    public LockToken tryLock(String lockKey) {
        String key = LOCK_PREFIX + lockKey;
        String value = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT * 1000;

        while (System.currentTimeMillis() < deadline) {
            try {
                Boolean success = redisTemplate.opsForValue()
                        .setIfAbsent(key, value, Duration.ofSeconds(LOCK_TIMEOUT));
                if (Boolean.TRUE.equals(success)) {
                    log.debug("获取锁成功: key={}", lockKey);
                    return new LockToken(key, value);
                }
            } catch (Exception e) {
                // Redis 不可用时降级：跳过分布式锁，继续执行
                log.warn("Redis 连接失败，分布式锁降级跳过: {}", e.getMessage());
                return new LockToken(key, null);  // null value 表示"无锁模式"
            }
            // 自旋等待 50ms 后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        log.warn("获取锁超时: key={}", lockKey);
        return null;
    }

    /**
     * 释放分布式锁（仅当 token 匹配时）。
     */
    public void unlock(LockToken token) {
        if (token == null || token.value == null) return;  // null value = Redis 降级模式
        try {
            redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(token.key),
                    token.value
            );
            log.debug("释放锁: key={}", token.key);
        } catch (Exception e) {
            log.error("释放锁异常: key={}", token.key, e);
        }
    }

    /**
     * 锁令牌：持有 key 和唯一 value，释放时校验。
     */
    public record LockToken(String key, String value) {}
}
