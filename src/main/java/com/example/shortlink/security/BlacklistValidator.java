package com.example.shortlink.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * 域名黑名单验证器。
 *
 * <p>短链接把用户重定向到任意 URL，攻击者可能利用它来：
 * <ul>
 *   <li>伪装钓鱼页面（如 fake-bank.com）</li>
 *   <li>分发恶意软件</li>
 *   <li>绕过社交平台的域名检测</li>
 * </ul>
 *
 * <p>生成短链接时检查目标域名是否在黑名单中，
 * 命中则拒绝生成，从源头阻断恶意用途。
 *
 * <p>配置在 application.yml：
 * <pre>
 * shortlink.blacklist.domains:
 *   - phishing.example.com
 *   - spam.example.com
 * </pre>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "shortlink.blacklist")
public class BlacklistValidator {

    /** 可从 application.yml 注入的黑名单域名列表 */
    private Set<String> domains = new HashSet<>();

    public void setDomains(Set<String> domains) {
        this.domains = domains != null ? domains : new HashSet<>();
    }

    /**
     * 检查 URL 的目标域名是否在黑名单中。
     *
     * @param url 用户输入的原始 URL
     * @return true = 在黑名单中（应拒绝），false = 可以通过
     */
    public boolean isBlacklisted(String url) {
        if (domains.isEmpty()) {
            return false;
        }

        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            // 去除端口号
            host = host.split(":")[0].toLowerCase();

            // 精确匹配 + 子域名通配（如 *.example.com 匹配 sub.example.com）
            for (String blocked : domains) {
                blocked = blocked.toLowerCase();
                if (host.equals(blocked)) {
                    log.warn("黑名单拦截（精确匹配）: {} ∈ {}", host, blocked);
                    return true;
                }
                if (blocked.startsWith("*.") && host.endsWith(blocked.substring(1))) {
                    log.warn("黑名单拦截（泛域名匹配）: {} ∈ {}", host, blocked);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("URL 解析失败，按不在黑名单处理: {}", url);
            return false;
        }

        return false;
    }

    /** 查看当前黑名单 */
    public Set<String> getBlacklistedDomains() {
        return new HashSet<>(domains);
    }
}
