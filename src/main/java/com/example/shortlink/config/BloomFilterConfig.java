package com.example.shortlink.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.shortlink.entity.ShortLink;
import com.example.shortlink.mapper.ShortLinkMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Guava BloomFilter 配置 —— 缓存穿透保护。
 *
 * <p>在所有缓存层之前，Bloom Filter 用极小的内存代价（约 1.7 MB / 100 万条）
 * 判断一个短码是否"一定不存在"。如果 Bloom 返回 false，无需查询任何后端
 * 存储，直接返回 404。
 *
 * <h3>为什么选 Guava 而非 Redisson RBloomFilter？</h3>
 * <ul>
 *   <li>Guava BloomFilter 在 JVM 堆内，延迟 < 100ns，没有网络开销</li>
 *   <li>Redisson RBloomFilter 每次判断要走 Redis 网络 IO，和 L2 缓存延迟一致，
 *       失去了"快拦截"的意义</li>
 *   <li>启动时从 MySQL 全量加载只需一次，后续新增通过 {@code put()} 增量同步</li>
 * </ul>
 *
 * <h3>内存估算</h3>
 * <pre>
 *   m = -n·ln(p) / (ln2)²
 *   100 万条 × 0.1% FPR → ~1.7 MB
 *   1000 万条 × 0.1% FPR → ~17 MB
 * </pre>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BloomFilterConfig {

    private final ShortLinkMapper shortLinkMapper;

    @Value("${shortlink.bloom.expected-insertions:1000000}")
    private long expectedInsertions;

    @Value("${shortlink.bloom.false-positive-rate:0.001}")
    private double fpp;

    /**
     * 创建 BloomFilter Bean。
     *
     * <p>Guava BloomFilter.put() 内部使用 AtomicLongArray，线程安全。
     * 生产环境中可通过 JMX 监控 {@link BloomFilter#approximateElementCount()}。
     */
    @Bean
    public BloomFilter<String> bloomFilter() {
        BloomFilter<String> filter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                fpp
        );
        log.info("BloomFilter Bean 创建完成: expectedInsertions={}, fpp={}", expectedInsertions, fpp);
        return filter;
    }

    /**
     * 启动时从 MySQL 全量加载已有短码到 BloomFilter。
     *
     * <p>使用 {@link ApplicationRunner} 确保在数据库连接就绪之后执行。
     * 对于千万级数据量，建议改为分页流式加载以避免 OOM。
     */
    @Bean
    public ApplicationRunner bloomFilterLoader(BloomFilter<String> bloomFilter) {
        return (ApplicationArguments args) -> {
            log.info("开始加载 BloomFilter...");

            long count = shortLinkMapper.selectCount(null);
            if (count == 0) {
                log.info("MySQL 中无数据，BloomFilter 为空");
                return;
            }

            // 分页加载，避免一次性拉取过多数据
            int pageSize = 5000;
            long pages = (count + pageSize - 1) / pageSize;
            long loaded = 0;

            for (int page = 1; page <= pages; page++) {
                Page<ShortLink> p = shortLinkMapper.selectPage(
                        new Page<>(page, pageSize), null);
                List<ShortLink> batch = p.getRecords();
                for (ShortLink sl : batch) {
                    if (sl.getShortCode() != null && !sl.getShortCode().isEmpty()) {
                        bloomFilter.put(sl.getShortCode());
                        loaded++;
                    }
                }
            }

            log.info("BloomFilter 加载完成: {} 条短码已加入过滤器（MySQL 总记录: {}）",
                    loaded, count);
        };
    }
}
