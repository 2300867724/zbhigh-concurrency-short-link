package com.example.shortlink.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花算法 ID 生成器（单机版）。
 *
 * <h3>ID 结构（64-bit long，最高位恒为 0 保证正数）</h3>
 * <pre>
 * ┌─ 1 bit ─┬────── 41 bits ──────┬── 10 bits ──┬── 12 bits ──┐
 * │  未使用   │ 时间戳 (ms - 纪元)   │  机器标识    │  序列号      │
 * └──────────┴─────────────────────┴─────────────┴─────────────┘
 * </pre>
 *
 * <ul>
 *   <li>41-bit 时间戳：约 69 年可用期（纪元 2024-01-01）</li>
 *   <li>10-bit 机器 ID：0~1023，单机部署使用随机数</li>
 *   <li>12-bit 序列号：每毫秒 0~4095，QPS 上限 ≈ 409 万/秒</li>
 * </ul>
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /** 纪元起点：2024-01-01 00:00:00 UTC（毫秒） */
    private static final long EPOCH = 1704067200000L;

    /** 各部分 bit 数 */
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS  = 12L;

    /** 各部分最大值 */
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;  // 1023
    private static final long MAX_SEQUENCE  = (1L << SEQUENCE_BITS)  - 1;  // 4095

    /** 位移量 */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 22
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                  // 12

    // ==================== 实例字段 ====================

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        // 单机部署：机器 ID 用随机数，避免重启后生成重复 ID
        long randomWorkerId = ThreadLocalRandom.current().nextLong(0, MAX_WORKER_ID + 1);
        this.workerId = randomWorkerId;
        log.info("SnowflakeIdGenerator 初始化完成, workerId={}", workerId);
    }

    /**
     * 供测试或多实例场景手动指定 workerId
     */
    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                "Worker ID 必须在 [0, " + MAX_WORKER_ID + "] 之间，当前值: " + workerId
            );
        }
        this.workerId = workerId;
    }

    // ==================== 公共方法 ====================

    /**
     * 生成全局唯一的雪花 ID（正数 long）。
     *
     * 线程安全（synchronized），同一毫秒内序列号递增；
     * 序列号用完会自旋等待到下一毫秒。
     */
    public synchronized long nextId() {
        long currentTimestamp = currentTimeMillis();

        // 时钟回拨检测
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            log.error("时钟回拨 {} ms，拒绝生成 ID", offset);
            throw new IllegalStateException(
                "Clock moved backwards! Refusing to generate id for " + offset + " ms"
            );
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒：序列号 +1，超过上限则等待下一毫秒
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的一毫秒：序列号归零
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
             | (workerId << WORKER_ID_SHIFT)
             | sequence;
    }

    // ==================== 私有方法 ====================

    /** 自旋等待到下一毫秒 */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /** 当前毫秒时间戳，抽成方法便于测试 mock */
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
