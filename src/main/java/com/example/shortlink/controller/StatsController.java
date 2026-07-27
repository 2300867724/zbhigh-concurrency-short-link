package com.example.shortlink.controller;

import com.example.shortlink.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统计数据 API
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 概览：总链接数 / 今日PV / 今日UV / 今日DAU */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        return ResponseEntity.ok(statsService.getOverview());
    }

    /** 趋势：最近 N 天的 PV/UV/DAU（默认 7 天） */
    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(statsService.getTrend(Math.min(days, 30)));
    }

    /** Top N：今日访问量最高的短链接（默认 10） */
    @GetMapping("/top")
    public ResponseEntity<List<Map<String, Object>>> top(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(statsService.getTopLinks(Math.min(limit, 50)));
    }
}
