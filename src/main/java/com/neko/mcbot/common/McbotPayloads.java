package com.neko.mcbot.common;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 双向各一条信封通道：mcbot:c2s（主人→服务器）、mcbot:s2c（服务器→主人）。
 * 具体语义在 Envelope.kind 里，协议演进不增通道。
 *
 * <p>闸①（尺寸）落在 {@link C2s#CODEC} 的入站方向。为什么这里不能直接用
 * {@code ByteBufCodecs.STRING_UTF8}（javap 实测三条，别凭记忆改）：
 * <ul>
 *   <li><b>它的上限是字符数折算出来的字节数</b>：{@code STRING_UTF8 = stringUtf8(32767)}，
 *       而 {@code Utf8String.read} 拿 {@code ByteBufUtil.utf8MaxBytes(32767)} = <b>98301 字节</b>
 *       去卡 VarInt 声明的长度。也就是说"32767"看着像 32KB，实际允许一条 ~96KB 的中文包通过。
 *       我们要的是真 32KB，所以必须自己判。
 *   <li><b>超限是抛 {@code DecoderException}，不是丢包</b>：{@code Utf8String.read} 里有三处
 *       athrow（声明长度 &gt; utf8MaxBytes、&lt; 0、&gt; readableBytes）。
 *   <li><b>抛异常的后果是断线</b>：{@code Connection.exceptionCaught} 只宽容
 *       {@code SkipPacketException}（debug 一行就返回），其余一律置 {@code handlingFault}
 *       并往下走关 channel 的路。
 * </ul>
 * 三者合起来意味着：如果只靠原版上限，"模型偶尔吐出一坨超大参数"这种自家客户端的失手，
 * 代价会是<b>主人整条连接被踢</b>，而大脑那边还在 90 秒后回一条 TIMEOUT 教学——
 * 现象上就是"同伴忽然掉线"，调查方向极易被带偏。故入站改成：先按字节判，超限
 * <b>不读、不抛</b>，交回 {@link C2s#OVERSIZED} 哨兵，由接收处显式丢弃并记日志。
 *
 * <p>线路格式零改动：出站仍是 {@code STRING_UTF8}（VarInt 长度前缀 + UTF-8 字节），
 * 所以与非 mcbot 服务端的兼容性不变；闸①只是把"收多大就不收了"这件事显式写在自己手里。
 */
public final class McbotPayloads {

    private McbotPayloads() {
    }

    public static final class C2s implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2s> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocationHelper.of("mcbot:c2s"));

        /**
         * 闸①哨兵：入站超限时返回它（{@link #json} 为 null）。它只用于"这一包不要了"，
         * 永远不该被发送；因此出站编码遇到 null 直接写空串，避免任何路径拿它去编码时 NPE。
         */
        public static final C2s OVERSIZED = new C2s(null);

        /**
         * 出站保持原版写法；入站先过字节闸。
         *
         * <p>不采“先交给 {@code STRING_UTF8} 解、失败再说”的写法，也不依赖
         * “包体是否已切片”这种实现细节：这里自己读长度前缀（{@code getByte} 预扮，
         * 不吃 readerIndex）、自己卡上限，因此畸形前缀也是“返回哨兵”而不是报错。
         * 参照原版 {@code Utf8String.read} 的写法（{@code buf.toString(idx, len, UTF_8)}
         * + 推 readerIndex）保证解出来的字符串与原版逐字节一致。
         *
         * <p>一条恒等式值得记着：本闸卡在 32765 <b>字节</b>，而 UTF-8 字节数 ≥ 字符数，
         * 所以字符数必 ≤ 32765，恒小于原版的 32767 字符红线——原版那个“字符数超限也报错”
         * 的分支永远触发不了。
         * 换句说：过了这道闸的信封，原版解码器不可能拒。这才是“不抛异常”能成立的原因。
         */
        public static final StreamCodec<ByteBuf, C2s> CODEC = StreamCodec.ofMember(
                (C2s value, ByteBuf buf) ->
                        ByteBufCodecs.STRING_UTF8.encode(buf, value.json == null ? "" : value.json),
                McbotPayloads.C2s::readGuarded);

        public final String json;

        public C2s(String json) {
            this.json = json;
        }

        /** 是否被闸①挡下：接收方看到 true 就丢弃，不进后续闸门与分发。 */
        public boolean oversize() {
            return json == null;
        }

        /**
         * 闸①入站解码：长度前缀自己读、上限自己卡、畸形一律走哨兵而不报错。
         * 对外不暴露——验收请走 {@link #CODEC} 的 encode/decode 往返，那才是管线真的经路。
         */
        private static C2s readGuarded(ByteBuf buf) {
            final int maxVarintSections = 3;   // 3 节可表 2097151，远大于 32KB 上限
            int start = buf.readerIndex();
            int available = buf.readableBytes();
            int len = 0;
            int consumed = 0;
            boolean terminated = false;
            for (int i = 0; i < maxVarintSections; i++) {
                if (i >= available) {
                    return OVERSIZED;           // 前缀都不完整：丢
                }
                byte b = buf.getByte(start + i);
                len |= (b & 0x7F) << (7 * i);
                consumed = i + 1;
                if (!net.minecraft.network.VarInt.hasContinuationBit(b)) {
                    terminated = true;
                    break;
                }
            }
            // 前缀未结束 = 声明长度至少 2^21 字节，必超限；或声明值为负/超余下可读字节 = 畸形
            if (!terminated || len < 0 || len > WireSize.MAX_BODY_BYTES
                    || len > available - consumed) {
                return OVERSIZED;
            }
            buf.readerIndex(start + consumed);
            String json = buf.toString(buf.readerIndex(), len, java.nio.charset.StandardCharsets.UTF_8);
            buf.readerIndex(buf.readerIndex() + len);
            return new C2s(json);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final class S2c implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2c> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocationHelper.of("mcbot:s2c"));
        /**
         * 出站方向不在此设闸：真正的收口在 {@code ServerToolDispatcher#send}，
         * 那里知道 kind/seq，才能把超限回执改写成一条<b>合法</b>的教学回执（见彼处注释）。
         * 客户端一侧另有发送前自检，见 {@code AgentRunner}。
         */
        public static final StreamCodec<ByteBuf, S2c> CODEC =
                ByteBufCodecs.STRING_UTF8.map(S2c::new, v -> v.json);

        public final String json;

        public S2c(String json) {
            this.json = json;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
