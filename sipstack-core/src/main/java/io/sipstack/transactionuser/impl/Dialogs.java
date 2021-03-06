package io.sipstack.transactionuser.impl;

import io.pkts.buffer.Buffer;
import io.pkts.buffer.Buffers;
import io.pkts.packet.sip.SipMessage;
import io.pkts.packet.sip.SipRequest;
import io.pkts.packet.sip.SipResponse;
import io.pkts.packet.sip.Transport;
import io.pkts.packet.sip.address.SipURI;
import io.pkts.packet.sip.header.ContactHeader;
import io.pkts.packet.sip.header.ToHeader;
import io.pkts.packet.sip.header.ViaHeader;
import io.pkts.packet.sip.header.impl.AddressParametersHeaderImpl;
import io.sipstack.transaction.Transaction;
import io.sipstack.transaction.TransactionLayer;
import io.sipstack.transactionuser.Dialog;
import io.sipstack.transactionuser.DialogEvent;
import io.sipstack.transactionuser.TransactionEvent;
import io.sipstack.transport.Flow;

import java.util.function.Consumer;

/**
 * Implements https://tools.ietf.org/html/rfc4235#section-3.7.1
 *
 *     +----------+            +----------+
 *     |          | 1xx-notag  |          |
 *     |          |----------->|          |
 *     |  Trying  |            |Proceeding|-----+
 *     |          |---+  +-----|          |     |
 *     |          |   |  |     |          |     |
 *     +----------+   |  |     +----------+     |
 *          |   |     |  |          |           |
 *          |   |     |  |          |           |
 *          +<--C-----C--+          |1xx-tag    |
 *          |   |     |             |           |
 * cancelled|   |     |             V           |
 *  rejected|   |     |1xx-tag +----------+     |
 *          |   |     +------->|          |     |2xx
 *          |   |              |          |     |
 *          +<--C--------------|  Early   |-----C---+ 1xx-tag
 *          |   |   replaced   |          |     |   | w/new tag
 *          |   |              |          |<----C---+ (new FSM
 *          |   |              +----------+     |      instance
 *          |   |   2xx             |           |      created)
 *          |   +----------------+  |           |
 *          |                    |  |2xx        |
 *          |                    |  |           |
 *          V                    V  V           |
 *     +----------+            +----------+     |
 *     |          |            |          |     |
 *     |          |            |          |     |
 *     |Terminated|<-----------| Confirmed|<----+
 *     |          |  error     |          |
 *     |          |  timeout   |          |
 *     +----------+  replaced  +----------+
 *                   local-bye   |      ^
 *                   remote-bye  |      |
 *                               |      |
 *                               +------+
 *                                2xx w. new tag
 *                                 (new FSM instance
 *                                  created)
 * @author ajansson@twilio.com
 */

public class Dialogs {

    private static final String LOCAL_HOST = System.getProperty("localhost", "127.0.0.1");
    private static final String REMOTE_HOST = System.getProperty("remotehost", "127.0.0.1");
    private static final int REMOTE_PORT = Integer.getInteger("remoteport", 5060);

    private enum State {TRYING, PROCEEDING, EARLY, CONFIRMED, TERMINATED}

    private final Consumer<DialogEvent> upstream;
    private final TransactionLayer transactionLayer;
    private final SipRequest request;
    private final Buffer callId;
    private final Buffer localTag;
    private Flow lastFlow;

    // For now support only one dialog
    private final MyDialog dialog = new MyDialog();

    public Dialogs(final Consumer<DialogEvent> upstream, final TransactionLayer transactionLayer,
            final Transaction tx, final SipRequest request, final boolean isUpstream) {
        this.upstream = upstream;
        this.transactionLayer = transactionLayer;
        this.request = request;
        this.callId = request.getCallIDHeader().getCallId();
        this.localTag = getLocalTag(request, isUpstream);
        if (tx != null) {
            this.lastFlow = tx.flow();
        }
        if (isUpstream) {
            dialog.update(request);
        }
    }

    /**
     * Gets the dialog key (call id + local tag)
     * @param message SIP message
     * @return Dialog key.
     */
    public static Buffer getDialogKey(final SipMessage message, final boolean isUpstream) {
        return Buffers.wrap(message.getCallIDHeader().getCallId(), getLocalTag(message, isUpstream));
    }

    public void dispatchUpstream(final TransactionEvent event) {
        dialog.dispatchUpstream(event);
    }

    public Dialog getDialog(final SipMessage message) {
        return dialog;
    }

    private static Buffer getLocalTag(final SipMessage message, final boolean isUpstream) {
        if (isUpstream) {
            if (message.isRequest()) {
                return message.getToHeader().getTag();
            } else {
                return message.getFromHeader().getTag();
            }
        } else {
            if (message.isRequest()) {
                return message.getFromHeader().getTag();
            } else {
                return message.getToHeader().getTag();
            }
        }
    }

    private static Buffer getRemoteTag(final SipMessage message, final boolean isUpstream) {
        if (isUpstream) {
            if (message.isRequest()) {
                return message.getFromHeader().getTag();
            } else {
                return message.getToHeader().getTag();
            }
        } else {
            if (message.isRequest()) {
                return message.getToHeader().getTag();
            } else {
                return message.getFromHeader().getTag();
            }
        }
    }

    public class MyDialog implements Dialog {
        private State state = State.TRYING;
        private Buffer remoteTag;
        private SipURI remoteContact;
        private int cseqNr = 1;

        public MyDialog() {
        }

        @Override
        public void send(final SipRequest.Builder builder) {
            /*
            if (!builder.method().toString().equals("ACK")) {
                cseqNr++;
            }
            builder.withContactHeader(ContactHeader.with().withHost(LOCAL_HOST).withPort(5060).withTransportUDP().build())
                    .cseq(CSeqHeader.withMethod(builder.method()).withCSeq(cseqNr).build());
                    */

            final SipRequest request = builder.build();
            final ViaHeader via = ViaHeader
                    .withHost(LOCAL_HOST)
                    .withPort(5060)
                    .withTransportUDP()
                    .withBranch(ViaHeader.generateBranch())
                    .build();
            request.addHeaderFirst(via);

            if (remoteContact != null) {
                transactionLayer.createFlow(remoteContact.getHost())
                        .withPort(remoteContact.getPort())
                        .withTransport(Transport.udp)
                        .onSuccess(f -> {
                            lastFlow = f;
                            final Transaction t = transactionLayer.newClientTransaction(f, request).start();
                        })
                        .connect();
            } else {
                transactionLayer.createFlow(REMOTE_HOST)
                        .withPort(REMOTE_PORT)
                        .withTransport(Transport.udp)
                        .onSuccess(f -> {
                            lastFlow = f;
                            final Transaction t = transactionLayer.newClientTransaction(f, request).start();
                        })
                        .connect();
            }
        }

        @Override
        public void send(final SipResponse message) {
            message.setHeader(ContactHeader.with().withHost(LOCAL_HOST).withPort(5060).withTransportUDP().build());
            if (remoteContact != null) {
                transactionLayer.createFlow(remoteContact.getHost())
                        .withPort(remoteContact.getPort())
                        .withTransport(Transport.udp)
                        .onSuccess(f -> {
                            lastFlow = f;
                            throw new RuntimeException("This is wrong. Need to save the transaction and reuse it to send the response");
                            // final Transaction t = transactionLayer.newClientTransaction(f, message).start();
                        })
                        .connect();
            } else {
                transactionLayer.createFlow(REMOTE_HOST)
                        .withPort(REMOTE_PORT)
                        .withTransport(Transport.udp)
                        .onSuccess(f -> {
                            lastFlow = f;
                            throw new RuntimeException("This is wrong. Need to save the transaction and reuse it to send the response");
                            // final Transaction t = transactionLayer.send(f, message);
                        })
                        .connect();
            }
        }

        @Override
        public SipRequest.Builder createAck() {
            final ToHeader to = request.getToHeader().clone();
            if (remoteTag != null) {
                // TODO ugly internal class
                to.setParameter(AddressParametersHeaderImpl.TAG, remoteTag);
            }

            final SipRequest.Builder ack = SipRequest.ack(request.getRequestUri());
            ack.withFromHeader(request.getFromHeader()).withToHeader(to).withCallIdHeader(request.getCallIDHeader());
            return ack;
        }

        @Override
        public SipRequest.Builder createBye() {
            final ToHeader to = request.getToHeader().clone();
            if (remoteTag != null) {
                // TODO ugly internal class
                to.setParameter(AddressParametersHeaderImpl.TAG, remoteTag);
            }
            return (SipRequest.Builder)SipRequest.bye(request.getRequestUri())
                    .withFromHeader(request.getFromHeader())
                    .withToHeader(to)
                    .withCallIdHeader(request.getCallIDHeader());
        }

        public void update(final SipMessage message) {
            final Buffer remoteTag = getRemoteTag(message, true);
            if (remoteContact == null && message.getContactHeader() != null) {
                remoteContact = (SipURI) message.getContactHeader().getAddress().getURI();
            }
            if (message.isResponse() && remoteTag != null) {
                this.remoteTag = remoteTag;
            }

        }
        public void dispatchUpstream(final TransactionEvent event) {
            update(event.message());
            upstream.accept(new DefaultDialogEvent(this, event));
        }
    }

}
