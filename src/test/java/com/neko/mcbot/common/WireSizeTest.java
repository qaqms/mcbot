package com.neko.mcbot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸①的算术部分。故意不碰 MC/netty：本测试 sourceset 拿不到 minecraft classpath
 * （与 {@code path/DigAStarTest} 同一约束），所以这里只钉"单位有没有量错"这一类
 * 最容易在真机上变成 96KB 的判定；真正的编解码往返留给服务器运行期的 SelfTest [m5a]。
 */
class WireSizeTest {

    @Test
    void 中文按字节而非字符计量() {
        // "矿" = 3 字节；按字符数会把它算成 1，这正是旧尺寸闸量错单位的根因
        assertEquals(1, "矿".length());
        assertEquals(3, WireSize.utf8Bytes("矿"));

        String cn = "矿".repeat(11000);
        assertEquals(33000, WireSize.utf8Bytes(cn));
        assertTrue(cn.length() < WireSize.MAX_ENVELOPE_BYTES, "按字符数看根本没超限");
        assertFalse(WireSize.fits(cn), "按 UTF-8 字节必须判为超限");
    }

    @Test
    void 代理对算四字节且绝不被裁成半对() {
        String emoji = "🙂";               // U+1F642，Java 里是 2 个 char
        assertEquals(2, emoji.length());
        assertEquals(4, WireSize.utf8Bytes(emoji));

        // 只剩 3 字节空间时，宁可整对不取，也不能留下半个代理（那是解不出来的字符）
        String cut = WireSize.truncateToBytes("ab" + emoji, 3);
        assertEquals("ab", cut);
        assertEquals(2, WireSize.utf8Bytes(cut));
        assertFalse(cut.contains(emoji));
    }

    @Test
    void 边界值卡在不越界的那一侧() {
        String body = "a".repeat(WireSize.MAX_BODY_BYTES);
        assertTrue(WireSize.fits(body), "刚好等于上限必须放行");
        assertFalse(WireSize.fits(body + "a"), "多一字节必须拒");

        // 本闸字节上限严格小于原版 STRING_UTF8 的 32767 字符红线：
        // UTF-8 字节数 ≥ 字符数，所以过闸信封的字符数必 < 32767，原版解码器不可能再拒。
        // 这条恒等式就是"入站不抛异常、因此不踢线"成立的前提，改上限时必须重看。
        assertTrue(WireSize.MAX_BODY_BYTES < 32767);
        assertEquals(WireSize.MAX_ENVELOPE_BYTES - WireSize.LENGTH_PREFIX_BYTES,
                WireSize.MAX_BODY_BYTES);
    }

    @Test
    void 裁剪结果字节数不超且为原串前缀() {
        String cn = "矿石铜矿石".repeat(1000);          // 每字符 3 字节
        String cut = WireSize.truncateToBytes(cn, 100);
        assertTrue(WireSize.utf8Bytes(cut) <= 100);
        assertEquals(99, WireSize.utf8Bytes(cut), "3 字节步进，100 的上限实收 99");
        assertEquals(33, cut.length());
        assertTrue(cn.startsWith(cut));
    }

    @Test
    void 未超限原样返回且空值归零() {
        String s = "短小回执";
        assertEquals(s, WireSize.truncateToBytes(s, 4096));
        assertEquals(0, WireSize.utf8Bytes(null));
        assertEquals(0, WireSize.utf8Bytes(""));
        assertTrue(WireSize.fits(""));
    }
}
