package com.example.shortlink.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 访问统计服务 —— Redis 实时计数 + MySQL 定时持久化。
 */
public interface StatsService {

    /**
     * 记录一次短链接访问
     * @param shortCode 短码
     * @param visitorIp 访客 IP（用于 UV / DAU 去重）
     */
    void recordAccess(String shortCode, String visitorIp);

    /**
     * 查询概览数据（总链接数 / 今日 PV / 今日 UV / 今日 DAU）
     */
    Map<String, Object> getOverview();

    /**
     * 最近 N 天的 PV/UV 趋势（供折线图使用）
     */
    List<Map<String, Object>> getTrend(int days);

    /**
     * 今日 PV 最高的 Top N 短链接
     */
    List<Map<String, Object>> getTopLinks(int limit);

    /**
     * 定时任务：将 Redis 统计数据同步到 MySQL（每小时执行）
     */
    void syncToDatabase();
}
