package com.example.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.shortlink.entity.ShortLink;
import com.example.shortlink.mapper.ShortLinkMapper;
import com.example.shortlink.security.BlacklistValidator;
import com.example.shortlink.security.RedisDistributedLock;
import com.example.shortlink.service.ShortLinkService;
import com.example.shortlink.util.Base62Encoder;
import com.example.shortlink.util.SnowflakeIdGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.hash.BloomFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 短链接服务实现 —— 安全加固版。
 *
 * <h3>请求 → 响应完整链路</h3>
 * <pre>
 *  客户端
 *    │
 *    ├─ 限流拦截器（漏桶，10 req/s / IP）
 *    │    ├─ 放行 → Controller
 *    │    └─ 拒绝 → 429 Too Many Requests
 *    │
 *    └─ Controller → Service.shorten(url)
 *         │
 *         ├─ 1. 黑名单检查               ← 域名在黑名单 → 拒绝
 *         ├─ 2. 分布式锁（按 URL 粒度）   ← 防止并发重复创建
 *         ├─ 3. 查重（MySQL）
 *         ├─ 4. 雪花 ID → Base62
 *         ├─ 5. MySQL INSERT
 *         ├─ 6. Redis SET
 *         ├─ 7. BloomFilter PUT
 *         └─ 8. 释放锁 → 返回短码
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkServiceImpl implements ShortLinkService {

    /** 冲突重试上限（62^7 空间下几乎不会触发，纯兜底） */
    private static final int MAX_COLLISION_RETRIES = 5;

    /** Redis key 前缀 */
    private static final String REDIS_KEY_PREFIX = "shortlink:";

    // ============ 依赖注入 ============

    private final ShortLinkMapper shortLinkMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, String> localCache;         // L1: Caffeine
    private final BloomFilter<String> bloomFilter;          // 缓存穿透保护
    private final BlacklistValidator blacklistValidator;    // 域名黑名单
    private final RedisDistributedLock distributedLock;     // 分布式锁

    @Value("${shortlink.cache.redis-ttl:604800}")
    private long redisTtlSeconds;

    // ==================== 写入：生成短链接 ====================

    @Override
    @Transactional
    public ShortLink shorten(String originalUrl) {
        // ── 1. 黑名单域名过滤 ──
        if (blacklistValidator.isBlacklisted(originalUrl)) {
            throw new IllegalArgumentException("该链接的目标域名在黑名单中，无法生成短链接");
        }

        // ── 2. 分布式锁：按 URL 粒度串行化（防止并发重复创建） ──
        String lockKey = "url:" + Integer.toUnsignedString(originalUrl.hashCode());
        RedisDistributedLock.LockToken lockToken = distributedLock.tryLock(lockKey);
        if (lockToken == null) {
            throw new RuntimeException("系统繁忙，请稍后重试");
        }

        try {
            // ── 3. 查重（在锁内保证幂等性） ──
            ShortLink existing = shortLinkMapper.selectOne(
                    new LambdaQueryWrapper<ShortLink>()
                            .eq(ShortLink::getOriginalUrl, originalUrl)
            );
            if (existing != null) {
                log.info("URL 已存在，复用短码: {} -> {}", originalUrl, existing.getShortCode());
                writeToRedis(existing.getShortCode(), existing.getOriginalUrl());
                return existing;
            }

            // ── 4. 雪花 ID → Base62 → INSERT（含 DuplicateKeyException 重试） ──
            ShortLink entity = new ShortLink();
            entity.setOriginalUrl(originalUrl);
            entity.setCreateTime(LocalDateTime.now());
            entity.setVisitCount(0L);

            String shortCode = null;
            int insertRetries = 3;
            for (int attempt = 0; attempt < insertRetries; attempt++) {
                shortCode = generateUniqueShortCode();
                entity.setShortCode(shortCode);
                try {
                    shortLinkMapper.insert(entity);
                    break;
                } catch (DuplicateKeyException e) {
                    if (attempt == insertRetries - 1) {
                        throw new RuntimeException("短码写入冲突，请重试", e);
                    }
                    log.warn("短码 INSERT 唯一键冲突（极低概率），重试 {}/{}: {}",
                            attempt + 1, insertRetries, shortCode);
                }
            }

            // ── 5. 写入 Redis（缓存层） ──
            writeToRedis(shortCode, originalUrl);

            // ── 6. 加入 Bloom Filter ──
            bloomFilter.put(shortCode);

            log.info("生成短链接: {} -> {}", originalUrl, shortCode);
            return entity;

        } finally {
            // ── 8. 释放锁（finally 保证异常时也释放） ──
            distributedLock.unlock(lockToken);
        }
    }

    // ==================== 读取：查询短链接（三层缓存） ====================

    @Override
    public ShortLink getByShortCode(String shortCode) {
        String originalUrl = resolveUrl(shortCode);
        if (originalUrl == null) {
            return null;
        }

        ShortLink entity = new ShortLink();
        entity.setShortCode(shortCode);
        entity.setOriginalUrl(originalUrl);
        return entity;
    }

    private String resolveUrl(String shortCode) {
        // ── 0: Bloom Filter 缓存穿透保护 ──
        if (!bloomFilter.mightContain(shortCode)) {
            log.debug("Bloom 拦截（一定不存在）: {}", shortCode);
            return null;
        }

        // ── L1: Caffeine 本地缓存 ──
        String url = localCache.getIfPresent(shortCode);
        if (url != null) {
            log.debug("L1 命中: {} → {}", shortCode, url);
            return url;
        }

        // ── L2: Redis 分布式缓存 ──
        url = readFromRedis(shortCode);
        if (url != null) {
            log.debug("L2 命中: {} → {}", shortCode, url);
            localCache.put(shortCode, url);
            return url;
        }

        // ── L3: MySQL 主存储 ──
        ShortLink entity = shortLinkMapper.selectOne(
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getShortCode, shortCode)
        );
        if (entity != null) {
            url = entity.getOriginalUrl();
            log.debug("L3 命中: {} → {}", shortCode, url);

            writeToRedis(shortCode, url);
            localCache.put(shortCode, url);
            return url;
        }

        log.debug("三层均未命中: {}", shortCode);
        return null;
    }

    // ==================== Redis 读写封装 ====================

    private String readFromRedis(String shortCode) {
        try {
            return redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + shortCode);
        } catch (Exception e) {
            log.warn("Redis 读取异常（降级跳过）: {}", e.getMessage());
            return null;
        }
    }

    private void writeToRedis(String shortCode, String originalUrl) {
        try {
            redisTemplate.opsForValue().set(
                    REDIS_KEY_PREFIX + shortCode,
                    originalUrl,
                    Duration.ofSeconds(redisTtlSeconds)
            );
        } catch (Exception e) {
            log.warn("Redis 写入异常（降级跳过）: {}", e.getMessage());
        }
    }

    // ==================== 短码生成 ====================

    private String generateUniqueShortCode() {
        for (int retry = 0; retry < MAX_COLLISION_RETRIES; retry++) {
            long id = snowflakeIdGenerator.nextId();
            String shortCode = Base62Encoder.encode(id);

            ShortLink collision = shortLinkMapper.selectOne(
                    new LambdaQueryWrapper<ShortLink>()
                            .eq(ShortLink::getShortCode, shortCode)
            );

            if (collision == null) {
                return shortCode;
            }

            log.warn("短码碰撞（极低概率事件，retry={}）: {} -> {}", retry, id, shortCode);
        }

        throw new IllegalStateException(
                "短码生成失败：连续 " + MAX_COLLISION_RETRIES + " 次碰撞"
        );
    }
}
