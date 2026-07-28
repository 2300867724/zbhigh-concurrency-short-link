# 🔗 高并发短链接服务平台

[![GitHub stars](https://img.shields.io/github/stars/2300867724/zbhigh-concurrency-short-link?style=flat)](https://github.com/2300867724/zbhigh-concurrency-short-link)
[![License](https://img.shields.io/badge/license-MIT-green)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)

基于 Spring Boot 3 + Redis + Caffeine 的三层缓存架构短链接服务，支持 Bloom Filter 防穿透、漏桶限流、分布式锁、实时统计等高并发场景核心组件。

> 🏠 [GitHub](https://github.com/2300867724/zbhigh-concurrency-short-link) · [Gitee](https://gitee.com/zbbc/ZBvibecoding-4)

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 后端 | Spring Boot 3.2.5 | Web 框架 |
| ORM | MyBatis-Plus 3.5.6 | 数据库操作 |
| 缓存 L1 | Caffeine 3.1.8 | JVM 本地缓存，10K 容量 / 30min TTL |
| 缓存 L2 | Redis 7 (Lettuce) | 分布式缓存，7 天 TTL / allkeys-lru |
| 存储 | MySQL 8.0 / H2 | H2 本地开发，MySQL 生产 |
| 防穿透 | Guava BloomFilter | 1.7MB 内存过滤 100 万条，0.1% 误判率 |
| ID 生成 | 雪花算法 (Snowflake) | 41bit 时间戳 + 10bit 机器ID + 12bit 序列号 |
| 短码编码 | Base62 | 0-9a-zA-Z 字符集，固定 7 位 |
| 统计 | Redis HyperLogLog + Bitmap | 12KB/百万 UV，定时同步 MySQL |
| 安全 | Redis Lua 漏桶 + SET NX EX | 10 req/s/IP + 域名黑名单 + 分布式锁 |
| 前端 | Vue 3 + ECharts 5 | CDN 引入，零构建 |
| 部署 | Docker Compose | MySQL + Redis + App 一键启动 |

---

## 架构设计

```
                                ┌──────────────┐
                                │   客户端      │
                                └──────┬───────┘
                                       │
                              ┌────────▼────────┐
                              │  RateLimiter    │  漏桶限流 10 req/s/IP
                              │  (Interceptor)  │  超限 → 429
                              └────────┬────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
              POST /api/shorten   GET /{短码}       GET /api/stats/*
                    │                  │                  │
                    ▼                  ▼                  ▼
              ┌───────────┐    ┌──────────────────┐  ┌──────────┐
              │ 黑名单检查 │    │ 0.BloomFilter    │  │ StatsAPI │
              │ 分布式锁   │    │   (一定不存在?)   │  │          │
              │ URL 查重   │    │ 1.Caffeine(L1)   │  │ Redis    │
              │ 雪花+Base62│    │ 2.Redis   (L2)   │  │ 实时查询 │
              │ MySQL+Redis│    │ 3.MySQL   (L3)   │  │          │
              │ BloomFilter│    │   命中→回写上层   │  │          │
              └───────────┘    └──────────────────┘  └──────────┘
                    │                  │
                    ▼                  ▼
              返回短码 JSON       302 重定向 + 统计记录
                                        │
                                        ▼
                                 ┌──────────────┐
                                 │ recordAccess │
                                 │ PV: INCR     │
                                 │ UV: PFADD    │
                                 │ DAU: SETBIT  │
                                 └──────────────┘
```

## 功能清单

### 核心功能
- [x] `POST /api/shorten` — 长链接 → 7 位 Base62 短码
- [x] `GET /{短码}` — 302 重定向到原始链接
- [x] Vue 3 前端 — 输入框 + 结果展示 + 一键复制

### 缓存架构
- [x] L1 Caffeine 本地缓存（< 1μs）
- [x] L2 Redis 分布式缓存（< 1ms）
- [x] L3 MySQL 主存储兜底
- [x] Bloom Filter 缓存穿透保护（< 100ns）
- [x] 逐层回写（L2 命中 → 回写 L1，L3 命中 → 回写 L2+L1）
- [x] Redis 异常自动降级

### 短码生成
- [x] 雪花算法全局唯一 ID
- [x] Base62 编码 → 7 位固定短码（62^7 ≈ 3.5 万亿空间）
- [x] 碰撞检测 + 5 次重试

### 安全保护
- [x] 漏桶限流（Redis Lua 原子操作，10 req/s/IP）
- [x] 域名黑名单（精确匹配 + 泛域名 `*.phishing.com`）
- [x] Redis 分布式锁（SET NX EX + Lua 安全释放）
- [x] 接口幂等性（同一 URL 不重复生成）

### 访问统计
- [x] PV 计数（Redis INCR）
- [x] UV 去重（Redis HyperLogLog，12KB/百万 UV）
- [x] DAU 标记（Redis Bitmap）
- [x] 每小时定时同步 Redis → MySQL
- [x] ECharts 折线图 + 柱状图仪表板

---

## 项目结构

```
short-link-service/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
└── src/main/
    ├── java/com/example/shortlink/
    │   ├── ShortLinkApplication.java
    │   ├── config/
    │   │   ├── BloomFilterConfig.java        # 启动加载 MySQL → BloomFilter
    │   │   ├── CacheConfig.java              # Caffeine L1 缓存 Bean
    │   │   ├── MyBatisPlusConfig.java        # MyBatis-Plus 分页插件
    │   │   ├── RateLimitInterceptor.java     # 漏桶限流拦截器
    │   │   └── WebMvcConfig.java             # 注册拦截器
    │   ├── controller/
    │   │   ├── ShortLinkController.java      # 短链接 REST API
    │   │   └── StatsController.java          # 统计数据 API
    │   ├── entity/
    │   │   ├── ShortLink.java                # 短链接实体
    │   │   ├── AccessStats.java              # 每日链接统计
    │   │   └── DailyStats.java               # 每日全局统计
    │   ├── mapper/
    │   │   ├── ShortLinkMapper.java
    │   │   ├── AccessStatsMapper.java
    │   │   └── DailyStatsMapper.java
    │   ├── security/
    │   │   ├── RateLimiter.java              # Redis Lua 漏桶算法
    │   │   ├── BlacklistValidator.java       # 域名黑名单过滤
    │   │   └── RedisDistributedLock.java     # SET NX EX 分布式锁
    │   ├── service/
    │   │   ├── ShortLinkService.java
    │   │   ├── StatsService.java
    │   │   └── impl/
    │   │       ├── ShortLinkServiceImpl.java  # 核心：缓存 + 安全 + 生成
    │   │       └── StatsServiceImpl.java      # 统计：Redis 计数 + 定时同步
    │   └── util/
    │       ├── SnowflakeIdGenerator.java      # 雪花算法
    │       └── Base62Encoder.java             # 7 位 Base62 编码
    └── resources/
        ├── application.yml                    # 配置（H2 + MySQL 双 profile）
        ├── db/
        │   ├── schema-h2.sql                  # H2 建表
        │   └── schema-mysql.sql               # MySQL 建表
        └── static/
            ├── index.html                     # Vue 3 首页
            ├── stats.html                     # ECharts 统计仪表板
            └── css/style.css
```

---

## 快速启动

### 方式一：Docker Compose（推荐，MySQL + Redis + App）

```bash
git clone git@github.com:2300867724/zbhigh-concurrency-short-link.git
cd zbhigh-concurrency-short-link
docker compose up -d
```

访问：
- 首页：http://localhost:8080
- 统计：http://localhost:8080/stats.html
- H2 控制台（开发模式）：http://localhost:8080/h2-console

### 方式二：本地 Maven（H2 + 需要本地 Redis）

```bash
# 确保本地 Redis 在 6379 端口运行
mvn spring-boot:run
```

### 方式三：指定 MySQL Profile

```bash
# 先启动 MySQL
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

---

## API 文档

### 生成短链接

```http
POST /api/shorten
Content-Type: application/json

{
    "url": "https://www.example.com/very/long/path"
}
```

**成功响应 (200):**
```json
{
    "success": true,
    "shortCode": "3dK9xR2",
    "shortUrl": "http://localhost:8080/3dK9xR2",
    "originalUrl": "https://www.example.com/very/long/path"
}
```

**失败响应:**
```json
// 域名在黑名单 (400)
{ "success": false, "message": "该链接的目标域名在黑名单中，无法生成短链接" }

// 请求过于频繁 (429)
{ "success": false, "message": "请求过于频繁，请稍后再试" }

// URL 格式错误 (400)
{ "success": false, "message": "URL 必须以 http:// 或 https:// 开头" }

// 系统繁忙 (500)
{ "success": false, "message": "系统繁忙，请稍后重试" }
```

### 短链接重定向

```http
GET /3dK9xR2
→ 302 Found
→ Location: https://www.example.com/very/long/path
```

### 统计概览

```http
GET /api/stats/overview

{
    "totalLinks": 1234,
    "todayPv": 5678,
    "todayUv": 1234,
    "todayDau": 890
}
```

### 趋势数据

```http
GET /api/stats/trend?days=7

[
    { "date": "2026-07-21", "pv": 123, "uv": 45, "dau": 30 },
    { "date": "2026-07-22", "pv": 234, "uv": 67, "dau": 45 }
]
```

### Top 短链接

```http
GET /api/stats/top?limit=10

[
    { "shortCode": "3dK9xR2", "pv": 1234, "originalUrl": "https://..." },
    { "shortCode": "7fM2wQ8", "pv": 567,  "originalUrl": "https://..." }
]
```

---

## 核心设计决策

### 为什么 Base62 而不是 Base64？

Base64 含 `+` `/` `=`，URL 中需额外编码处理。Base62（0-9a-zA-Z）天然 URL-safe，7 位即可表达 3.5 万亿个短链接。

### 为什么 Guava BloomFilter 而不是 Redisson RBloomFilter？

- Guava 在 JVM 堆内，判断延迟 < 100ns
- Redisson 每次判断走 Redis 网络 IO（~1ms），和 L2 缓存延迟一致，失去了"快拦截"的意义
- 代价：重启后需从 MySQL 重新加载；通过 `ApplicationRunner` 分页加载，100 万条约 2 秒

### 为什么 HyperLogLog 而不是 Redis Set 做 UV？

100 万独立 IP 用 Set 存储约 80 MB，HyperLogLog 只需 12 KB（**省 6000 倍**），标准误差仅 0.81%，对趋势分析完全够用。

### 为什么漏桶而不是令牌桶？

漏桶强制平滑输出，不允许突发流量。短链接生成是 IO 密集型操作，需严格保护后端 DB。令牌桶适合允许突发的场景（如秒杀）。

### 为什么 Snowflake 而不是 UUID？

| | Snowflake | UUID |
|---|---|---|
| 长度 | 8 字节 (long) | 16 字节 |
| 索引友好 | ✅ 单调递增 | ❌ 完全随机 |
| 全局唯一 | ✅ 时间+机器+序列号 | ✅ 概率唯一 |
| 可读性 | Base62 → 7 位 "3dK9xR2" | Base62 → 仍需多字符 |

---

## Redis Key 设计总览

| Key | 类型 | 示例 | TTL | 用途 |
|-----|------|------|-----|------|
| `shortlink:{code}` | String | `shortlink:3dK9xR2` → URL | 7d | 短码→URL 缓存 |
| `pv:{code}:{date}` | String | `pv:3dK9xR2:20260727` → 123 | 48h | 单链接 PV |
| `uv:{code}:{date}` | HyperLogLog | `uv:3dK9xR2:20260727` | 48h | 单链接 UV |
| `dau:{date}` | Bitmap | `dau:20260727` | 48h | 全局日活 |
| `active_links:{date}` | Set | `active_links:20260727` | 48h | 当天活跃短码索引 |
| `rate_limit:{ip}` | Hash | `{water, last_time}` | 120s | 漏桶水位 |
| `lock:shorten:url:{hash}` | String | NX EX 5s | — | 分布式锁 |

---

## License

MIT
