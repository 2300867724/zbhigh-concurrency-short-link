package com.example.shortlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shortlink.entity.DailyStats;
import org.apache.ibatis.annotations.Mapper;

/**
 * 每日全局统计 Mapper
 */
@Mapper
public interface DailyStatsMapper extends BaseMapper<DailyStats> {
}
