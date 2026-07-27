package com.example.shortlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链接实体
 */
@Data
@TableName("t_short_link")
public class ShortLink {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 短码（Base62 编码，7位） */
    private String shortCode;

    /** 原始长链接 */
    private String originalUrl;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 访问次数 */
    private Long visitCount;
}
