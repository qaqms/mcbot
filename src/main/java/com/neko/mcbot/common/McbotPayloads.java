package com.neko.mcbot.common;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 双向各一条信封通道：mcbot:c2s（主人→服务器）、mcbot:s2c（服务器→主人）。
 * 具体语义在 Envelope.kind 里，协议演进不增通道。
 */
public final class McbotPayloads {

    private McbotPayloads() {
    }

    public static final class C2s implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2s> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocationHelper.of("mcbot:c2s"));
        public static final StreamCodec<io.netty.buffer.ByteBuf, C2s> CODEC =
                ByteBufCodecs.STRING_UTF8.map(C2s::new, v -> v.json);

        public final String json;

        public C2s(String json) {
            this.json = json;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final class S2c implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2c> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocationHelper.of("mcbot:s2c"));
        public static final StreamCodec<io.netty.buffer.ByteBuf, S2c> CODEC =
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
