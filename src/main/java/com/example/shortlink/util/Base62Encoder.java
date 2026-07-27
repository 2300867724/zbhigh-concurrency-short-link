package com.example.shortlink.util;

/**
 * Base62 编码工具：将 long 值转换为 7 位短码。
 *
 * <p>字符集：0-9, a-z, A-Z（共 62 个字符）
 * <p>62^7 ≈ 3.5 万亿，与雪花 ID 配合使用时几乎不会碰撞。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * long id = snowflakeIdGenerator.nextId();
 * String code = Base62Encoder.encode(id);   // → "3dK9xR2"
 * }</pre>
 */
public final class Base62Encoder {

    /** Base62 字符集（0-9, a-z, A-Z） */
    private static final String CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** 62^7 = 3,521,614,606,208 —— 7 位 Base62 可表达的数值上限 */
    public static final long MAX_VALUE = 62L * 62 * 62 * 62 * 62 * 62 * 62;

    /**
     * 7 位固定长度的短码长度
     */
    public static final int CODE_LENGTH = 7;

    private Base62Encoder() {
        // 工具类禁止实例化
    }

    /**
     * 将 long 值编码为 7 位 Base62 短码。
     *
     * <p>自动对 value 取模 ({@link #MAX_VALUE}) 以确保输出恰好 7 位；
     * 调用方应确保传入的 value 来自雪花算法，取模后的碰撞概率在
     * 3.5 万亿空间内可忽略不计，且入库前有唯一性校验兜底。
     *
     * @param value 雪花算法生成的 long ID（必须 ≥ 0）
     * @return 固定 7 位 Base62 短码，如 "3dK9xR2"
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value 必须为非负数，当前值: " + value);
        }

        // 取模映射到 7 位空间
        long v = value % MAX_VALUE;

        // 逐位编码（低位 → 高位）
        char[] buf = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            buf[i] = CHARSET.charAt((int) (v % 62));
            v /= 62;
        }

        return new String(buf);
    }

    /**
     * 将 long 值编码为不定长 Base62 字符串（不取模，不补零）。
     *
     * <p>用于调试或需要完整表达 ID 的场景。雪花 ID 通常为 11 位。
     */
    public static String encodeFull(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value 必须为非负数，当前值: " + value);
        }
        if (value == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(CHARSET.charAt((int) (v % 62)));
            v /= 62;
        }
        return sb.reverse().toString();
    }
}
