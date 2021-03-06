package io.sipstack.netty.codec.sip;

import io.netty.channel.Channel;
import io.pkts.packet.sip.Transport;
import io.pkts.packet.sip.address.SipURI;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Encapsulates a
 * 
 * @author jonas@jonasborjesson.com
 */
public final class UdpConnection extends AbstractConnection {

    public UdpConnection(final Channel channel, final InetSocketAddress remoteAddress, final Optional<SipURI> vipAddress) {
        super(Transport.udp, channel, remoteAddress, vipAddress);
    }

    public UdpConnection(final Channel channel, final InetSocketAddress remoteAddress) {
        super(Transport.udp, channel, remoteAddress, Optional.empty());
    }

    @Override
    public int getDefaultPort() {
        return 5060;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isUDP() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(final Object o) {
        channel().writeAndFlush(o);
    }

    @Override
    public boolean connect() {
        return true;
    }



}
