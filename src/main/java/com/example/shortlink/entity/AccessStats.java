package com.example.shortlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链接每日访问统计（MySQL 持久化）
 */
@Data
@TableName("t_access_stats")
public class AccessStats {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 短码 */
    private String shortCode;

    /** 统计日期 */
    private LocalDate statsDate;

    /** 页面浏览量 */
    private Long pv;

    /** 独立访客数 */
    private Long uv;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
