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
 * 同伴用的假 {@link Connection}：它只需要"挂在一个真实玩家实体上"，不需要真的收发网络字节。
 *
 * <p>三处必需的行为（1.21.11 javap 实测，逐跳可复现；新事实已同步进 STATUS 防漂移表）：
 *
 * <ul>
 *   <li><b>出站一律丢弃</b>：基类 {@code send} 在无 channel 时会把包塞进 {@code pendingActions}
 *       队列等 channel 就绪冲刷，而同伴的另一端没有客户端——这是一条慢性泄漏（旧版的说法）。
 *       故三个 {@code send} 重载全覆写为空操作。
 *       <p>为什么必须三个都覆写：调用方走的是 {@link ChannelFutureListener} 形态的重载集合，
 *       漏一个就有一条路径静静回到基类。
 *   <li><b>keep-alive 根本不会跑</b>（这是同伴能长驻的真正原因，<b>不是</b>丢包、也不是下面的
 *       disconnect 闸门）：驱动链是
 *       {@code ServerConnectionListener.tick()} 遍历它自己的 {@code connections} 列表 →
 *       {@code Connection.tick()} → （监听器实现 {@code TickablePacketListener} 时）
 *       {@code ServerGamePacketListenerImpl.tick()} → {@code keepConnectionAlive()}。
 *       那个 {@code connections} 只收经监听 socket 受理的连接，而同伴的 {@code FakeConnection}
 *       是手工构造 + {@code placeNewPlayer} 直接进场的，从不出现在其中——所以第一环就进不去，
 *       超时判定（{@code now - keepAliveTime >= 15000}）连执行机会也没有。
 *       <p>两个容易记错的细节（javap 为凭）：
 *       ① {@code keepAlivePending}/{@code keepAliveTime}/{@code TIMEOUT_DISCONNECTION_MESSAGE}
 *       都在<b>监听器</b>（{@code ServerCommonPacketListenerImpl}）上，不在 {@code Connection} 上；
 *       ② 真跑到超时时，走的是<b>监听器</b>的 {@code disconnect(Component)}，它做三件事：
 *       {@code connection.send(Disconnect包, thenRun(connection.disconnect(details)))}（thenRun 因丢包
 *       永不完成）、{@code connection.setReadOnly()}（内存 channel 不 null → 真会执行，
 *       置 autoRead=false）、{@code server.executeBlocking(connection::handleDisconnection)}
 *       ——后者开头就早退（{@code channel != null && channel.isOpen()} 则 return），我们的
 *       EmbeddedChannel 一直开着，所以它也什么都不做。换言之：这条路径上唯一真的调到
 *       我们覆写的 {@code disconnect(DisconnectionDetails)} 的机会，藏在那个永不完成的 thenRun 里。
 *   <li><b>方向必须 SERVERBOUND</b>：{@code setupInboundProtocol} 会调 {@code validateListener}
 *       校验监听器流向，不匹配直接抛 {@link IllegalStateException}（构造阶段就炸，不是静默降级）。
 *   <li><b>必须有可用 channel</b>：同一条装配路径在 channel 为 null 时是直接跳过（实测是
 *       ifnull 分支，不是 NPE）——表现为"进了世界但管线没装上"的静默失效，比崩更难查。
 *       这里持有一个 {@link EmbeddedChannel} 并把自身注册为它的 handler，靠 channelActive
 *       把 channel 回填进连接；字段留着还多一层用途：主动遣散时能干净地关掉它。
 * </ul>
 *
 * <p>那 {@code disconnect} 闸门到底在守什么（全 jar 扫调用者，只这几个）：
 * {@code disconnect(DisconnectionDetails)} 的唯一调用者是
 * {@code ServerCommonPacketListenerImpl.method_60674}——它就挂在上面那条永不完成的 {@code thenRun}
 * 里；{@code disconnect(Component)}（基类会包成 Details 再虚分派回本类覆写）的调用者是
 * {@code ServerConnectionListener}、握手/登录/状态三个阶段监听器，以及 {@code channelInactive}。
 * 所以它防的是"登录握手段段/内存 channel 关闭"这类直连 {@code Connection} 的路径，
 * <b>不防</b> {@code PlayerList} 踢人——后者调的是<b>监听器</b>的 {@code disconnect}，
 * 对同伴而言是被"send 丢弃 + handleDisconnection 早退"这两层拦住的。
 * 主动遣散走 {@link #closeQuietly()}：先置 {@code allowClose} 再关内存 channel，
 * 于是 {@code channelInactive → disconnect(Component)} 那条链路此时才被放行。
 * 召唤与遣散由 SummonService 驱动。
 *
 * <p><b>警告：别拿这个类当"防踢"手段。</b>同伴长驻靠的是"不进 {@code ServerConnectionListener}
 * 的 connections 列表"。若以后改成走真 socket（例如接入真监听器或补上那次 {@code add}），
 * keep-alive 会立刻开始跑，而上面那条超时链并不会真的拆掉同伴（send 丢 + handleDisconnection 早退），
 * 反而变成每 15 秒一次的无用循环。到时候请重新用 javap 实测，别照这一段注释推断。
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

    /**
     * 报一个稳定的回环地址。
     *
     * <p>为什么不是 null：本机上查到的调用点其实都容得下 null（{@code ServerPlayer.getIpAddress()}
     * 走 instanceof，{@code PlayerList.canPlayerLogin()} 的 IP 封禁查询有 ifnull 防护），
     * 所以返回非 null 属于**保守选择而不是实测必需**——目的是让日志与玩家名录里同伴的地址有形，
     * 不必在每个读到它的地方补判空。若将来发现某处确实炸了，请把那条调用点补在这里，别只留结论。
     *
     * <p>它绝不代表真实的来源地址：任何按 IP 做的封禁、限流、白名单判定都不能把同伴算进去
     * （所有同伴的地址在这里是同一个），需要区分同伴时请用 UUID，见 CompanionRoster。
     */
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
