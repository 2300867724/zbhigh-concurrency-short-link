-- H2 初始化脚本（本地开发用）
CREATE TABLE IF NOT EXISTS t_short_link (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code  VARCHAR(32)  NOT NULL DEFAULT '',
    original_url VARCHAR(2048) NOT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    visit_count BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_short_code UNIQUE (short_code)
);

CREATE INDEX idx_original_url ON t_short_link(original_url(255));

-- ==================== 访问统计表 ====================
CREATE TABLE IF NOT EXISTS t_access_stats (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code  VARCHAR(32)  NOT NULL,
    stats_date  DATE         NOT NULL,
    pv          BIGINT       NOT NULL DEFAULT 0,
    uv          BIGINT       NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stats_code_date UNIQUE (short_code, stats_date)
);

CREATE INDEX idx_stats_date ON t_access_stats(stats_date);

-- ==================== 每日全局统计表 ====================
CREATE TABLE IF NOT EXISTS t_daily_stats (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    stats_date  DATE         NOT NULL UNIQUE,
    dau         BIGINT       NOT NULL DEFAULT 0,
    total_pv    BIGINT       NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
