package io.sipstack.core;

import static io.pkts.packet.sip.impl.PreConditions.ensureNotNull;

import io.sipstack.net.InboundOutboundHandlerAdapter;
import io.sipstack.net.NetworkLayer;
import io.sipstack.transaction.TransactionUser;
import io.sipstack.transaction.Transactions;
import io.sipstack.transaction.impl.DefaultTransactionLayer;
import io.sipstack.transport.TransportLayer;

/**
 * @author jonas@jonasborjesson.com
 */
public class DefaultSipStack implements SipStack {

    private final TransportLayer transportLayer;

    private final DefaultTransactionLayer transactionLayer;

    private final TransactionUser tu;

    DefaultSipStack(final TransportLayer transportLayer, final DefaultTransactionLayer transactionLayer, final TransactionUser tu) {

        // not that great since we cannot guarantee immutability since
        // we are leaking the SipStack to an external entity before
        // the stack is fully initialized. Got to re-structure some of
        // this to avoid this silliness.
        this.transportLayer = transportLayer;
        this.transactionLayer = transactionLayer;
        this.tu = tu;
    }

    @Override
    public InboundOutboundHandlerAdapter handler() {
        return transportLayer;
    }

    @Override
    public void useNetworkLayer(final NetworkLayer network) {
        ensureNotNull(network, "The network layer cannot be null");
        transportLayer.useNetworkLayer(network);
    }

}