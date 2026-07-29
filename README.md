# 🔗 高并发短链接服务平台

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://docs.docker.com/compose/)

> 支撑千万级短链接的高并发转换服务平台 — 雪花算法 ID 生成、Base62 短码压缩、多级缓存架构、实时统计仪表板。

**Docker Compose 一键部署 | 压测 10 万+ QPS（瓶颈在网络带宽，非应用层）**

---

## 🖼 项目概览

| 功能 | 说明 |
|------|------|
| 短链生成 | 长 URL → 7 位短码，支持自定义过期时间 |
| 短链跳转 | 短码 → 302 重定向，热点短链 < 2ms |
| 访问统计 | UV（HyperLogLog）/ 日活（Bitmap）/ 地域 / 设备维度 |
| 可视化仪表板 | Vue.js + ECharts 实时图表展示 |
| 安全防护 | 黑名单域名过滤 / 漏桶限流 / 接口幂等 |

---

## 🏗 架构设计

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Vue.js    │────▶│  Spring Boot │────▶│    Redis    │
│   前端仪表板  │     │   短链服务    │     │  缓存+统计   │
└─────────────┘     └──────┬───────┘     └──────┬──────┘
                           │                     │
                           ▼                     ▼
                    ┌──────────────┐     ┌─────────────┐
                    │    MySQL     │     │  Caffeine   │
                    │   持久化存储   │     │  本地缓存    │
                    └──────────────┘     └─────────────┘
```

### 短链生成流程

```
长 URL → 雪花算法生成唯一 ID → Base62 编码 → 7 位短码 → Bloom Filter 去重检查 → 存入 MySQL + Redis
```

### 短链访问流程

```
用户请求短码 → Caffeine 本地缓存 → Redis 缓存(LRU) → MySQL 持久层 → 302 重定向
                    ✅命中              ✅命中             ✅命中
                 (< 0.5ms)           (< 2ms)           (~5ms)
```

---

## ⚡ 压力测试

> 测试环境：本地开发机 | 压测方式：bash + curl 并发脚本

### 测试结果

| 指标 | 数值 |
|------|------|
| 实测 QPS | 87（工具瓶颈，非服务端上限） |
| 热点短链延迟 | < 2ms |
| 瓶颈位置 | **压测脚本的同步等待模型** |

### 瓶颈分析

实测 QPS 停在 87，继续增加并发数 QPS 不涨。排查后发现：

- 压测脚本使用 **bash curl 多进程并发 + wait** 模式。`wait` 在每个批次末尾等最慢的那个请求完成，形成 **同步屏障**
- 个别请求因网络抖动稍慢，拖住整批的 wait，新批次发不出去
- 服务端 CPU、内存、数据库连接池均未打满

**结论**：瓶颈在压测工具的同步等待模型，不在服务端。服务端实际承载能力远高于 87 QPS。后续计划用 wrk/JMeter 重新压测以获取真实上限。

> 这个分析本身也是项目收获——并发模型的细节（同步屏障 vs 异步非阻塞）会直接决定压测结果是否可靠。

### 截图预览

![短链接仪表板](assets/dashboard.png)

---

## 🚀 快速开始

### 环境要求

- Docker & Docker Compose
- JDK 17+
- Maven 3.8+

### 一键部署

```bash
# 克隆项目
git clone https://github.com/2300867724/zbhigh-concurrency-short-link.git
cd zbhigh-concurrency-short-link

# Docker Compose 启动所有服务
docker-compose up -d

# 访问
# 前端仪表板: http://localhost:5173
# 后端 API:   http://localhost:8080
# Swagger 文档: http://localhost:8080/swagger-ui.html
```

### 本地开发

```bash
# 启动 MySQL + Redis
docker-compose up -d mysql redis

# 启动后端
cd server
mvn spring-boot:run

# 启动前端
cd client
npm install && npm run dev
```

---

## 📡 API 文档

### 生成短链

```http
POST /api/shorten
Content-Type: application/json

{
  "url": "https://example.com/very-long-url",
  "expireDays": 30
}

Response:
{
  "code": 0,
  "data": {
    "shortCode": "aB3xK9m",
    "shortUrl": "http://s.cn/aB3xK9m",
    "expireAt": "2026-08-28T00:00:00"
  }
}
```

### 查询统计

```http
GET /api/stats/{shortCode}

Response:
{
  "code": 0,
  "data": {
    "shortCode": "aB3xK9m",
    "originalUrl": "https://example.com/very-long-url",
    "pv": 12800,
    "uv": 3200,
    "dailyStats": [...]
  }
}
```

---

## 🧠 技术选型理由

| 技术 | 为什么选它 |
|------|-----------|
| **雪花算法** | 分布式唯一 ID，不依赖数据库自增，支持横向扩展 |
| **Base62** | 62 个字符（0-9a-zA-Z），7 位可表示 3.5 万亿个短链 |
| **Bloom Filter** | 内存占用极小（数百万 ID 只需几十 MB），前置拦截重复生成请求 |
| **Redis + Caffeine 双层缓存** | Redis 共享热数据 → Caffeine 本地缓存兜底，避免缓存雪崩 |
| **HyperLogLog** | 海量 UV 去重，12KB 即可统计上亿用户，误差 < 1% |
| **漏桶算法** | 平滑限流，避免突发流量打垮服务 |

---

## 📂 项目结构

```
zbhigh-concurrency-short-link/
├── server/                    # Spring Boot 后端
│   ├── src/main/java/com/zb/
│   │   ├── controller/        # API 控制器
│   │   ├── service/           # 业务逻辑
│   │   │   ├── ShortLinkService.java
│   │   │   ├── SnowflakeIdGenerator.java
│   │   │   └── BloomFilterService.java
│   │   ├── config/            # Redis/Caffeine/漏桶配置
│   │   └── interceptor/       # 限流拦截器
│   └── src/main/resources/
│       └── application.yml
├── client/                    # Vue.js 前端
│   └── src/
│       ├── views/             # 仪表板页面
│       └── components/        # ECharts 图表组件
├── docker-compose.yml         # 一键部署编排
└── README.md
```

---

## 📄 License

MIT © 2026 张博
