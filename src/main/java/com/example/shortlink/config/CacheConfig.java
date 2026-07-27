package com.example.shortlink.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置（L1 缓存层）。
 *
 * <p>存储 shortCode → originalUrl 的映射，作为 Redis 之前的第一道防线。
 * 命中时延迟 < 1μs，对 Redis 命中率提升显著。
 */
@Configuration
public class CacheConfig {

    @Value("${shortlink.cache.caffeine-max-size:10000}")
    private int maxSize;

    @Value("${shortlink.cache.caffeine-ttl:1800}")
    private int ttlSeconds;

    /**
     * L1 本地缓存：shortCode → originalUrl
     *
     * <ul>
     *   <li>最大 10000 条，超出后 LRU 淘汰</li>
     *   <li>写入后 30 分钟过期，热点数据持续刷新</li>
     *   <li>开启统计，方便监控命中率</li>
     * </ul>
     */
    @Bean
    public Cache<String, String> shortLinkLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
