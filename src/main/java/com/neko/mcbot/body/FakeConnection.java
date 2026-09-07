package com.neko.mcbot.body;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 同伴的假连接：服务器会给一切在册玩家推流式下行包（位置、实体更新、keep-alive），
 * 而同伴的另一端没有客户端。裸 Connection 在无 channel 时会把 send 排队进内部
 * pending list，属于慢性泄漏——因此这里覆写 send 的全部重载，直接丢弃出站流量。
 *
 * placeNewPlayer 对连接有两个硬要求：
 * 1) 收包方向必须是 SERVERBOUND（服务端连接"接收上行包"，与监听器流向校验一致）；
 * 2) 必须有可用 channel（协议管线初始化要往上面挂 handler）——
 *    用一个内存 EmbeddedChannel 并把自己注册为其 handler 即可满足。
 *
 * keep-alive 超时被顺带消灭：包从未发出，也就不存在等待回包。
 * disconnect 默认吞掉（连接层不得终结同伴），生命周期由 SummonService 管理；
 * 主动遣散时先置 allowClose 再 closeQuietly 收掉 EmbeddedChannel。
 */
public final class FakeConnection extends Connection {

    private final EmbeddedChannel channel;
    private volatile boolean allowClose;

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel(this);
    }

    @Override
    public void send(Packet<?> packet) {
        // 另一端无人，全部丢弃
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        // 同上
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // 同上
    }

    @Override
    public void disconnect(DisconnectionDetails reason) {
        if (!allowClose) {
            // 服务器任何"因连接状态踢人"的路径都不适用于同伴
            return;
        }
        super.disconnect(reason);
    }

    /** 对外报回环地址：部分逻辑不容忍 null 远端地址。 */
    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    /** 主动遣散/停服专用：放开断连闸门并关闭内存 channel。 */
    void closeQuietly() {
        this.allowClose = true;
        this.channel.close();
    }
}
