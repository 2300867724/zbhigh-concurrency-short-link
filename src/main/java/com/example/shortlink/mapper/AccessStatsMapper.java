package com.example.shortlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.shortlink.entity.AccessStats;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问统计 Mapper
 */
@Mapper
public interface AccessStatsMapper extends BaseMapper<AccessStats> {
}
