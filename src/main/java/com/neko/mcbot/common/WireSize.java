package com.neko.mcbot.common;

/**
 * 信封的线路尺寸计算与裁剪——闸①的算术部分。
 *
 * <p>为什么单独一个类、而且坚持零依赖（不 import Gson、不 import MC）：
 * 根工程的 JUnit（{@code src/test/java}）跑在普通 JVM 上，拿不到 minecraft 与 Gson 的
 * classpath（{@code path/DigAStar} 同一手法），所以尺寸判定必须能在这里被单测覆盖，
 * 只把"真过一遍 StreamCodec"留给服务器运行期的 SelfTest。
 *
 * <p><b>为什么必须按字节而不是字符判：</b>原版 {@code ByteBufCodecs.STRING_UTF8} 的上限是
 * {@code stringUtf8(32767)}（javap 实测：clinit 里 {@code sipush 32767}），限的是
 * <b>字符串长度（字符数）</b>；而一条中文信封按 UTF-8 是每字符最多 3 字节，
 * 4 字节 emoji 还要更多。所以"字符数没超 32767"完全不等于"线路字节数没超 32KB"——
 * 早先用 {@code json.length()} 当尺寸闸是量错了单位，中文回执可以合法地膨胀到 ~98KB。
 */
public final class WireSize {

    private WireSize() {
    }

    /** 一条信封在线路上的字节上限（含长度前缀）。 */
    public static final int MAX_ENVELOPE_BYTES = 32 * 1024;

    /**
     * 长度前缀占的字节数：原版写字符串用 VarInt 前缀，本上限下最大 3 字节
     * （VarInt 每 7 位一节，2 字节封 16383，3 字节封 2097151，远大于 32768）。
     * 计入它是为了"发出去的一定不会被自己拒收"这条不变式。
     */
    public static final int LENGTH_PREFIX_BYTES = 3;

    /** 信封体允许的最大字节数（留出前缀）。 */
    public static final int MAX_BODY_BYTES = MAX_ENVELOPE_BYTES - LENGTH_PREFIX_BYTES;

    /**
     * 该字符串按 UTF-8 编码后的字节数。
     *
     * <p>孤立代理对按 3 字节计：实际编码器会把它替换成 1 字节的占位符，因此本函数是
     * <b>上界估计</b>而非逐位精确。用在一个"宁可错杀"的入口闸上是安全的方向——
     * 高估只会多拒几字节，低估会放进超包的。
     */
    public static int utf8Bytes(String s) {
        if (s == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;   // 真正的代理对 = 一个补充平面码点
                i++;
            } else {
                bytes += 3;   // BMP 内字符（含孤立代理，见上）
            }
        }
        return bytes;
    }

    /** 整条信封（含前缀）是否落在上限内。 */
    public static boolean fits(String json) {
        return utf8Bytes(json) <= MAX_BODY_BYTES;
    }

    /**
     * 就地裁到不超过 {@code maxBytes} 个 UTF-8 字节，且绝不切坏代理对。
     *
     * <p>为什么不"编码成字节数组、截断、再解码"：那样半截序列会解出一个替换字符，
     * 表面干净实则吞掉了一个"这里断过"的痕迹；按码点累加能在断点处如实保留边界。
     */
    public static String truncateToBytes(String s, int maxBytes) {
        if (s == null || utf8Bytes(s) <= maxBytes) {
            return s;
        }
        int bytes = 0;
        int i = 0;
        final int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int need;
            int step;
            if (c < 0x80) {
                need = 1;
                step = 1;
            } else if (c < 0x800) {
                need = 2;
                step = 1;
            } else if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                need = 4;
                step = 2;
            } else {
                need = 3;
                step = 1;
            }
            if (bytes + need > maxBytes) {
                break;
            }
            bytes += need;
            i += step;
        }
        return s.substring(0, i);
    }
}
