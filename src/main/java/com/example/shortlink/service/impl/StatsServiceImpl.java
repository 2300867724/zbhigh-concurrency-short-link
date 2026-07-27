package com.example.shortlink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.shortlink.entity.AccessStats;
import com.example.shortlink.entity.DailyStats;
import com.example.shortlink.entity.ShortLink;
import com.example.shortlink.mapper.AccessStatsMapper;
import com.example.shortlink.mapper.DailyStatsMapper;
import com.example.shortlink.mapper.ShortLinkMapper;
import com.example.shortlink.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 访问统计服务实现。
 *
 * <h3>Redis 数据结构设计</h3>
 * <pre>
 * PV:   String        pv:{shortCode}:{date}          → INCR        (每访问 +1)
 * UV:   HyperLogLog   uv:{shortCode}:{date}          → PFADD       (访客 IP 去重)
 * DAU:  Bitmap        dau:{date}                     → SETBIT      (全局日活)
 * 索引: Set           active_links:{date}            → SADD        (当天有访问的短码集合)
 * </pre>
 *
 * <h3>定时任务</h3>
 * 每小时整点将 Redis 统计数据同步到 MySQL 的 {@code t_access_stats} 和
 * {@code t_daily_stats}，Redis 数据保留 48 小时（防重跑时数据丢失）。
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Redis key TTL：统计 key 保留 48 小时（MySQL 同步窗口足够） */
    private static final Duration STATS_TTL = Duration.ofHours(48);

    /** DAU Bitmap 的最大 offset（IP hash 取模范围） */
    private static final int DAU_BITMAP_SIZE = 10_000_000;

    // ============ 依赖 ============

    private final StringRedisTemplate redisTemplate;
    private final ShortLinkMapper shortLinkMapper;
    private final AccessStatsMapper accessStatsMapper;
    private final DailyStatsMapper dailyStatsMapper;

    // ==================== 写：记录访问 ====================

    @Override
    public void recordAccess(String shortCode, String visitorIp) {
        String today = todayStr();

        try {
            // 1. PV 计数（INCR 原子操作）
            redisTemplate.opsForValue().increment(pvKey(shortCode, today));
            redisTemplate.expire(pvKey(shortCode, today), STATS_TTL);

            // 2. UV 去重（HyperLogLog，标准误差 0.81%）
            redisTemplate.opsForHyperLogLog().add(uvKey(shortCode, today), visitorIp);
            redisTemplate.expire(uvKey(shortCode, today), STATS_TTL);

            // 3. DAU 标记（Bitmap，IP hash → offset）
            // 用 & Integer.MAX_VALUE 清符号位，避免 Math.abs(Integer.MIN_VALUE) 返回负数
            int offset = (visitorIp.hashCode() & Integer.MAX_VALUE) % DAU_BITMAP_SIZE;
            redisTemplate.opsForValue().setBit(dauKey(today), offset, true);
            redisTemplate.expire(dauKey(today), STATS_TTL);

            // 4. 加入当天活跃短码集合（供定时任务遍历）
            redisTemplate.opsForSet().add(activeLinksKey(today), shortCode);
            redisTemplate.expire(activeLinksKey(today), STATS_TTL);

        } catch (Exception e) {
            log.warn("统计记录异常（降级跳过）: shortCode={}, ip={}, err={}",
                    shortCode, visitorIp, e.getMessage());
        }
    }

    // ==================== 读：统计查询 ====================

    @Override
    public Map<String, Object> getOverview() {
        String today = todayStr();

        Map<String, Object> overview = new LinkedHashMap<>();

        // 总链接数
        overview.put("totalLinks", shortLinkMapper.selectCount(null));

        // 今日 PV：统计所有活跃短码的 PV 之和
        long todayPv = 0;
        try {
            Set<String> activeCodes = redisTemplate.opsForSet()
                    .members(activeLinksKey(today));
            if (activeCodes != null) {
                for (String code : activeCodes) {
                    String pv = redisTemplate.opsForValue().get(pvKey(code, today));
                    if (pv != null) todayPv += Long.parseLong(pv);
                }
            }
        } catch (Exception e) {
            log.warn("Redis 查询 PV 异常: {}", e.getMessage());
        }
        overview.put("todayPv", todayPv);

        // 今日 UV：合并所有短码的 HyperLogLog（近似值）
        long todayUv = 0;
        try {
            Set<String> activeCodes = redisTemplate.opsForSet()
                    .members(activeLinksKey(today));
            if (activeCodes != null && !activeCodes.isEmpty()) {
                // 取第一个短码的 UV key 做 PFCOUNT
                // 注意：每个短码独立统计 UV，这里简单求和（各短码间 UV 有重叠）
                // 精确全局 UV 需要额外的全局 HyperLogLog
                for (String code : activeCodes) {
                    Long uv = redisTemplate.opsForHyperLogLog().size(uvKey(code, today));
                    if (uv != null) todayUv += uv;
                }
            }
        } catch (Exception e) {
            log.warn("Redis 查询 UV 异常: {}", e.getMessage());
        }
        overview.put("todayUv", todayUv);

        // 今日 DAU
        long todayDau = 0;
        try {
            Long bitCount = bitCount(dauKey(today));
            if (bitCount != null) todayDau = bitCount;
        } catch (Exception e) {
            log.warn("Redis 查询 DAU 异常: {}", e.getMessage());
        }
        overview.put("todayDau", todayDau);

        return overview;
    }

    @Override
    public List<Map<String, Object>> getTrend(int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String dateStr = date.format(DATE_FMT);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());

            if (date.equals(today)) {
                // 今天：从 Redis 实时读取
                point.put("pv", countTodayPv(dateStr));
                point.put("uv", countTodayUv(dateStr));
                point.put("dau", countTodayDau(dateStr));
            } else {
                // 历史：从 MySQL 读取
                DailyStats ds = dailyStatsMapper.selectOne(
                        new LambdaQueryWrapper<DailyStats>()
                                .eq(DailyStats::getStatsDate, date)
                );
                point.put("pv", ds != null ? ds.getTotalPv() : 0);

                // 历史 UV：从 t_access_stats 汇总
                long uv = 0;
                List<AccessStats> list = accessStatsMapper.selectList(
                        new LambdaQueryWrapper<AccessStats>()
                                .eq(AccessStats::getStatsDate, date)
                );
                for (AccessStats as : list) uv += as.getUv();
                point.put("uv", uv);
                point.put("dau", ds != null ? ds.getDau() : 0);
            }

            trend.add(point);
        }

        return trend;
    }

    @Override
    public List<Map<String, Object>> getTopLinks(int limit) {
        String today = todayStr();
        List<Map<String, Object>> top = new ArrayList<>();

        try {
            Set<String> activeCodes = redisTemplate.opsForSet()
                    .members(activeLinksKey(today));
            if (activeCodes == null || activeCodes.isEmpty()) return top;

            // 按 PV 排序
            List<CodePv> pvList = new ArrayList<>();
            for (String code : activeCodes) {
                String pvStr = redisTemplate.opsForValue().get(pvKey(code, today));
                long pv = pvStr != null ? Long.parseLong(pvStr) : 0;
                pvList.add(new CodePv(code, pv));
            }
            pvList.sort((a, b) -> Long.compare(b.pv, a.pv));

            // 截取 top N 并补全 URL
            for (int i = 0; i < Math.min(limit, pvList.size()); i++) {
                CodePv cp = pvList.get(i);
                ShortLink link = shortLinkMapper.selectOne(
                        new LambdaQueryWrapper<ShortLink>()
                                .eq(ShortLink::getShortCode, cp.code)
                );
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("shortCode", cp.code);
                item.put("pv", cp.pv);
                item.put("originalUrl", link != null ? link.getOriginalUrl() : "未知");
                top.add(item);
            }
        } catch (Exception e) {
            log.warn("查询 Top 链接异常: {}", e.getMessage());
        }

        return top;
    }

    // ==================== 定时任务：Redis → MySQL ====================

    /**
     * 每小时整点执行：将 Redis 实时统计数据同步到 MySQL。
     *
     * 首次启动后可能 Redis 中无数据，直接跳过。
     */
    @Scheduled(cron = "0 0 * * * *")
    @Override
    public void syncToDatabase() {
        log.info("===== 统计数据同步开始 =====");
        String today = todayStr();

        try {
            Set<String> activeCodes = redisTemplate.opsForSet()
                    .members(activeLinksKey(today));
            if (activeCodes == null || activeCodes.isEmpty()) {
                log.info("今日无活跃短码，跳过同步");
                return;
            }

            long totalPv = 0;

            for (String code : activeCodes) {
                // 从 Redis 读取 PV
                String pvStr = redisTemplate.opsForValue().get(pvKey(code, today));
                long pv = pvStr != null ? Long.parseLong(pvStr) : 0;

                // 从 Redis 读取 UV
                Long uv = redisTemplate.opsForHyperLogLog().size(uvKey(code, today));
                long uvCount = uv != null ? uv : 0;

                totalPv += pv;

                // Upsert 到 MySQL
                upsertAccessStats(code, LocalDate.now(), pv, uvCount);
            }

            // 全局 DAU
            Long dauCount = bitCount(dauKey(today));
            long dau = dauCount != null ? dauCount : 0;

            // Upsert 每日全局统计
            upsertDailyStats(LocalDate.now(), dau, totalPv);

            log.info("同步完成: activeLinks={}, totalPv={}, dau={}", activeCodes.size(), totalPv, dau);

        } catch (Exception e) {
            log.error("统计数据同步失败", e);
        }
    }

    // ==================== MySQL Upsert ====================

    private void upsertAccessStats(String shortCode, LocalDate date, long pv, long uv) {
        AccessStats existing = accessStatsMapper.selectOne(
                new LambdaQueryWrapper<AccessStats>()
                        .eq(AccessStats::getShortCode, shortCode)
                        .eq(AccessStats::getStatsDate, date)
        );
        if (existing != null) {
            existing.setPv(pv);
            existing.setUv(uv);
            accessStatsMapper.updateById(existing);
        } else {
            AccessStats stats = new AccessStats();
            stats.setShortCode(shortCode);
            stats.setStatsDate(date);
            stats.setPv(pv);
            stats.setUv(uv);
            accessStatsMapper.insert(stats);
        }
    }

    private void upsertDailyStats(LocalDate date, long dau, long totalPv) {
        DailyStats existing = dailyStatsMapper.selectOne(
                new LambdaQueryWrapper<DailyStats>()
                        .eq(DailyStats::getStatsDate, date)
        );
        if (existing != null) {
            existing.setDau(dau);
            existing.setTotalPv(totalPv);
            dailyStatsMapper.updateById(existing);
        } else {
            DailyStats stats = new DailyStats();
            stats.setStatsDate(date);
            stats.setDau(dau);
            stats.setTotalPv(totalPv);
            dailyStatsMapper.insert(stats);
        }
    }

    // ==================== 实时查询辅助 ====================

    private long countTodayPv(String dateStr) {
        long pv = 0;
        try {
            Set<String> codes = redisTemplate.opsForSet().members(activeLinksKey(dateStr));
            if (codes != null) {
                for (String code : codes) {
                    String val = redisTemplate.opsForValue().get(pvKey(code, dateStr));
                    if (val != null) pv += Long.parseLong(val);
                }
            }
        } catch (Exception e) { /* fall through */ }
        return pv;
    }

    private long countTodayUv(String dateStr) {
        long uv = 0;
        try {
            Set<String> codes = redisTemplate.opsForSet().members(activeLinksKey(dateStr));
            if (codes != null) {
                for (String code : codes) {
                    Long size = redisTemplate.opsForHyperLogLog().size(uvKey(code, dateStr));
                    if (size != null) uv += size;
                }
            }
        } catch (Exception e) { /* fall through */ }
        return uv;
    }

    private long countTodayDau(String dateStr) {
        try {
            Long bitCount = bitCount(dauKey(dateStr));
            return bitCount != null ? bitCount : 0;
        } catch (Exception e) { return 0; }
    }

    // ==================== Redis Key 工具方法 ====================

    private String todayStr() {
        return LocalDate.now().format(DATE_FMT);
    }

    private String pvKey(String code, String date) {
        return "pv:" + code + ":" + date;
    }

    private String uvKey(String code, String date) {
        return "uv:" + code + ":" + date;
    }

    private String dauKey(String date) {
        return "dau:" + date;
    }

    private String activeLinksKey(String date) {
        return "active_links:" + date;
    }

    // ==================== Redis BITCOUNT 封装 ====================

    /**
     * Redis BITCOUNT —— ValueOperations 上没有此方法，
     * 需要通过 {@link RedisCallback} 底层连接执行。
     */
    private Long bitCount(String key) {
        try {
            return redisTemplate.execute(
                    (RedisCallback<Long>) connection ->
                            connection.bitCount(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            log.warn("BITCOUNT 异常: {}", e.getMessage());
            return 0L;
        }
    }

    // ==================== 内部辅助类 ====================

    private record CodePv(String code, long pv) {}
}
