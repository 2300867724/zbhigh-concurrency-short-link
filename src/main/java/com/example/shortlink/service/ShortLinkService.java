package com.example.shortlink.service;

import com.example.shortlink.entity.ShortLink;

/**
 * 短链接服务接口
 */
public interface ShortLinkService {

    /**
     * 根据长链接创建短链接
     * @param originalUrl 原始长链接
     * @return 生成的短链接实体（含短码）
     */
    ShortLink shorten(String originalUrl);

    /**
     * 根据短码查询原始链接
     * @param shortCode 短码
     * @return 短链接实体，不存在返回 null
     */
    ShortLink getByShortCode(String shortCode);
}
