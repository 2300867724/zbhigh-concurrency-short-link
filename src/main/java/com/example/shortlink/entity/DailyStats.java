package com.example.shortlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日全局统计（DAU / 总 PV）
 */
@Data
@TableName("t_daily_stats")
public class DailyStats {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统计日期 */
    private LocalDate statsDate;

    /** 日活跃用户数 */
    private Long dau;

    /** 当日总 PV */
    private Long totalPv;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
