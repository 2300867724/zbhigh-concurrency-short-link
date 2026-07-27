-- MySQL 初始化脚本（生产 / Docker 部署用）
CREATE TABLE IF NOT EXISTS t_short_link (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    short_code   VARCHAR(32)     NOT NULL DEFAULT ''        COMMENT '短码',
    original_url VARCHAR(2048)   NOT NULL                   COMMENT '原始长链接',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    visit_count  BIGINT UNSIGNED NOT NULL DEFAULT 0         COMMENT '访问次数',
    UNIQUE KEY uk_short_code (short_code),
    INDEX idx_original_url (original_url(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='短链接表';

-- ==================== 访问统计表 ====================
CREATE TABLE IF NOT EXISTS t_access_stats (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    short_code   VARCHAR(32)     NOT NULL                COMMENT '短码',
    stats_date   DATE            NOT NULL                COMMENT '统计日期',
    pv           BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '页面浏览量',
    uv           BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '独立访客数',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_code_date (short_code, stats_date),
    INDEX idx_date (stats_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='短链接每日统计';

-- ==================== 每日全局统计表 ====================
CREATE TABLE IF NOT EXISTS t_daily_stats (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    stats_date   DATE            NOT NULL UNIQUE          COMMENT '统计日期',
    dau          BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '日活跃用户数',
    total_pv     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '当日总PV',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='每日全局统计';
