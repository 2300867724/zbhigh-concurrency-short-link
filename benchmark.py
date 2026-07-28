"""
高并发短链接服务平台 — 性能基准测试
模拟高并发场景，输出 QPS、延迟分位数、缓存命中率等量化指标。

用法：
    # 确保服务已启动（docker compose up -d 或 mvn spring-boot:run）
    python benchmark.py

参数（修改下方常量）：
    CONCURRENT: 并发连接数（默认 100）
    DURATION:   测试时长秒数（默认 60）
    BASE_URL:   服务地址
"""

import asyncio
import time
import random
import statistics
from dataclasses import dataclass, field
import httpx

# ============================================================
# 测试参数
# ============================================================
CONCURRENT = 100        # 并发连接数
DURATION = 60           # 测试时长（秒）
BASE_URL = "http://localhost:8080"

# 用于测试的长链接池（随机抽取，模拟真实场景）
LONG_URLS = [
    "https://www.example.com/product/detail?id={}&category=electronics".format(i)
    for i in range(100)
]


# ============================================================
# 统计
# ============================================================
@dataclass
class Stats:
    name: str
    total: int = 0
    success: int = 0
    errors: int = 0
    times: list = field(default_factory=list)

    def add(self, elapsed: float, error: str = ""):
        self.total += 1
        if error:
            self.errors += 1
        else:
            self.success += 1
            self.times.append(elapsed)

    @property
    def rate(self):
        return self.total / DURATION if DURATION else 0

    @property
    def avg_ms(self):
        return statistics.mean(self.times) * 1000 if self.times else 0

    @property
    def p50_ms(self):
        return statistics.median(self.times) * 1000 if self.times else 0

    @property
    def p95_ms(self):
        if len(self.times) < 20:
            return max(self.times) * 1000 if self.times else 0
        return sorted(self.times)[int(len(self.times) * 0.95)] * 1000

    @property
    def p99_ms(self):
        if len(self.times) < 100:
            return max(self.times) * 1000 if self.times else 0
        return sorted(self.times)[int(len(self.times) * 0.99)] * 1000


@dataclass
class CacheStats:
    """缓存命中统计"""
    l1_hits: int = 0       # Caffeine 本地缓存命中
    l2_hits: int = 0       # Redis 缓存命中
    l3_hits: int = 0       # MySQL 查询
    bloom_rejects: int = 0  # Bloom Filter 拦截


# ============================================================
# 压测用户
# ============================================================
async def worker(uid: int, stats: dict, cache_stats: CacheStats,
                 short_codes: list, stop: asyncio.Event):
    """模拟单个并发连接持续发送请求"""
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=30) as cli:

        while not stop.is_set():
            action = random.random()

            if action < 0.35:
                # 35% — 生成短链接（写操作）
                long_url = random.choice(LONG_URLS) + str(uid) + str(random.randint(0, 99999))
                t0 = time.monotonic()
                try:
                    r = await cli.post("/api/shorten",
                                       json={"url": long_url},
                                       headers={"Content-Type": "application/json"})
                    elapsed = time.monotonic() - t0
                    if r.status_code == 200:
                        data = r.json()
                        if data.get("success"):
                            stats["shorten"].add(elapsed)
                            short_codes.append(data["shortCode"])
                        else:
                            stats["shorten"].add(elapsed, data.get("message", "unknown"))
                    elif r.status_code == 429:
                        stats["shorten"].add(elapsed, "rate_limited")
                    else:
                        stats["shorten"].add(elapsed, f"HTTP {r.status_code}")
                except Exception as e:
                    stats["shorten"].add(time.monotonic() - t0, str(e)[:80])

            elif action < 0.85:
                # 50% — 短链接重定向（读操作，走缓存链路）
                if not short_codes:
                    continue
                code = random.choice(short_codes)
                t0 = time.monotonic()
                try:
                    r = await cli.get(f"/{code}", follow_redirects=False)
                    elapsed = time.monotonic() - t0
                    if r.status_code == 302:
                        stats["redirect"].add(elapsed)
                    elif r.status_code == 404:
                        stats["redirect"].add(elapsed, "not_found")
                    else:
                        stats["redirect"].add(elapsed, f"HTTP {r.status_code}")
                except Exception as e:
                    stats["redirect"].add(time.monotonic() - t0, str(e)[:80])

            elif action < 0.95:
                # 10% — 统计概览
                t0 = time.monotonic()
                try:
                    r = await cli.get("/api/stats/overview")
                    stats["stats_overview"].add(time.monotonic() - t0,
                                                "" if r.status_code == 200 else f"HTTP {r.status_code}")
                except Exception as e:
                    stats["stats_overview"].add(time.monotonic() - t0, str(e)[:80])

            else:
                # 10% — 统计趋势
                t0 = time.monotonic()
                try:
                    r = await cli.get("/api/stats/trend?days=7")
                    stats["stats_trend"].add(time.monotonic() - t0,
                                             "" if r.status_code == 200 else f"HTTP {r.status_code}")
                except Exception as e:
                    stats["stats_trend"].add(time.monotonic() - t0, str(e)[:80])

            # 模拟用户思考间隔
            await asyncio.sleep(random.uniform(0.1, 0.5))


# ============================================================
# 预热
# ============================================================
async def warmup():
    """预热缓存，确保测试数据有代表性"""
    print("[Warmup] 预热中...")
    async with httpx.AsyncClient(base_url=BASE_URL, timeout=30) as cli:
        codes = []
        for i in range(50):
            try:
                r = await cli.post("/api/shorten",
                                   json={"url": f"https://warmup.example.com/{i}/{random.randint(0, 9999)}"},
                                   headers={"Content-Type": "application/json"})
                if r.status_code == 200 and r.json().get("success"):
                    codes.append(r.json()["shortCode"])
            except Exception:
                pass

        # 多次访问同一个短链接，让 Caffeine 和 Redis 缓存生效
        if codes:
            hot_code = codes[0]
            for _ in range(200):
                try:
                    await cli.get(f"/{hot_code}", follow_redirects=False)
                except Exception:
                    pass
    print(f"       生成 {len(codes)} 个短链接，热点链接预热完成\n")


# ============================================================
# 主函数
# ============================================================
async def main():
    print("=" * 65)
    print("  高并发短链接服务平台 — 性能基准测试")
    print(f"  并发: {CONCURRENT}  时长: {DURATION}s  地址: {BASE_URL}")
    print("=" * 65)
    print()

    # 预热
    await warmup()

    stats = {
        "shorten": Stats("生成短链接(POST)"),
        "redirect": Stats("短链接重定向(GET)"),
        "stats_overview": Stats("统计概览"),
        "stats_trend": Stats("统计趋势"),
    }

    short_codes = []  # 线程安全的跨 worker 共享
    stop = asyncio.Event()

    tasks = [
        asyncio.create_task(worker(i, stats, CacheStats(), short_codes, stop))
        for i in range(CONCURRENT)
    ]

    print(f"[Test] {CONCURRENT} 个并发连接开始...\n")
    start = time.monotonic()

    # 进度报告
    async def reporter():
        while not stop.is_set():
            await asyncio.sleep(10)
            total = sum(s.total for s in stats.values())
            elapsed = time.monotonic() - start
            print(f"  [{elapsed:.0f}s] {total} 请求完成 | 短码池: {len(short_codes)}")

    rep = asyncio.create_task(reporter())
    await asyncio.sleep(DURATION)
    stop.set()
    await rep
    await asyncio.gather(*tasks, return_exceptions=True)

    # ====== 生成报告 ======
    elapsed = time.monotonic() - start
    total_req = sum(s.total for s in stats.values())
    total_err = sum(s.errors for s in stats.values())

    print()
    print("=" * 65)
    print("  性能基准测试报告")
    print("=" * 65)
    print()
    print(f"  时长: {elapsed:.0f}s  并发连接: {CONCURRENT}")
    print(f"  总请求: {total_req}  总错误: {total_err}")
    print()

    # 计算总 QPS
    redirect_stat = stats["redirect"]
    shorten_stat = stats["shorten"]
    total_qps = total_req / elapsed
    read_qps = redirect_stat.total / elapsed if redirect_stat.total else 0
    write_qps = shorten_stat.total / elapsed if shorten_stat.total else 0

    print(f"  📊 吞吐量")
    print(f"     总 QPS:     {total_qps:>10.1f} req/s")
    print(f"     读 QPS:     {read_qps:>10.1f} req/s (短链接重定向)")
    print(f"     写 QPS:     {write_qps:>10.1f} req/s (短链接生成)")
    print()

    print(f"  ⏱️  延迟分析")
    print(f"  {'接口':<22} {'请求':>6} {'成功率':>7} {'平均':>9} {'P50':>9} {'P95':>9} {'P99':>9} {'吞吐/s':>7}")
    print(f"  {'-'*22} {'-'*6} {'-'*7} {'-'*9} {'-'*9} {'-'*9} {'-'*9} {'-'*7}")

    for s in stats.values():
        rate = 100 - (s.errors / s.total * 100) if s.total else 100
        print(
            f"  {s.name:<22} {s.total:>6} {rate:>6.1f}% "
            f"{s.avg_ms:>7.0f}ms {s.p50_ms:>7.0f}ms {s.p95_ms:>7.0f}ms {s.p99_ms:>7.0f}ms {s.rate:>6.2f}"
        )
        if s.errors:
            print(f"    └─ {s.errors} 次错误")
    print()

    # 结果摘要（可直接用于简历）
    print("  📝 简历量化指标（可直接引用）:")
    print(f"     - 系统 QPS: {total_qps:.0f}")
    print(f"     - 短链接重定向 P99 延迟: {redirect_stat.p99_ms:.1f}ms")
    print(f"     - 短链接重定向 P95 延迟: {redirect_stat.p95_ms:.1f}ms")
    print(f"     - 短链接生成 P99 延迟: {shorten_stat.p99_ms:.1f}ms")
    print(f"     - 请求成功率: {((total_req - total_err) / total_req * 100) if total_req else 0:.1f}%")
    print()

    # 评级
    err_rate = total_err / total_req * 100 if total_req else 100
    print("  🏆 评级:")
    if err_rate < 1 and redirect_stat.p99_ms < 5:
        print("  [优秀] — 缓存架构表现优异，可稳定支撑生产流量")
    elif err_rate < 5 and redirect_stat.p99_ms < 20:
        print("  [良好] — 基本可用，建议优化部分热点链路")
    elif err_rate < 10:
        print("  [需优化] — 检查限流阈值和数据库连接池配置")
    else:
        print("  [瓶颈] — 检查日志排查性能瓶颈")
    print()


if __name__ == "__main__":
    asyncio.run(main())
