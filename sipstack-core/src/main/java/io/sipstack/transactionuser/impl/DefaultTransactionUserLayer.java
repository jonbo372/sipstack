package io.sipstack.transactionuser.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.pkts.packet.sip.SipMessage;
import io.pkts.packet.sip.SipRequest;
import io.pkts.packet.sip.SipResponse;
import io.sipstack.transaction.Transaction;
import io.sipstack.transaction.TransactionUser;
import io.sipstack.transaction.Transactions;
import io.sipstack.transactionuser.Dialog;
import io.sipstack.transactionuser.TransactionUserEvent;
import io.sipstack.transactionuser.TransactionUserLayer;

/**
 * @author jonas@jonasborjesson.com
 */
public class DefaultTransactionUserLayer implements TransactionUserLayer, TransactionUser {

    private Transactions transactions;
    private Map<String, Dialogs> dialogs = new ConcurrentHashMap<>();
    private final Consumer<TransactionUserEvent> defaultConsumer;

    public DefaultTransactionUserLayer(final Consumer<TransactionUserEvent> defaultConsumer) {
        this.defaultConsumer = defaultConsumer;
    }

    public void start(final Transactions transactionLayer) {
        this.transactions = transactionLayer;
    }

    @Override
    public Dialog findOrCreateDialog(final SipMessage message) {
        return findOrCreateDialogs(message).getDialog(message);
    }

    private Dialogs findOrCreateDialogs(final SipMessage message) {
        return dialogs.computeIfAbsent(getDialogKey(message), k -> new Dialogs(transactions, message, defaultConsumer));
    }

    /**
     * Gets the dialog key. Currently just uses call id.
     * @param message SIP message
     * @return Dialog key.
     */
    private static String getDialogKey(final SipMessage message) {
        return message.getCallIDHeader().getCallId().toString();
    }

    @Override
    public void onRequest(Transaction transaction, SipRequest request) {
        final Dialogs dialogs = findOrCreateDialogs(request);
        dialogs.receive(transaction, request);
    }

    @Override
    public void onResponse(Transaction transaction, SipResponse response) {
        final Dialogs dialog = findOrCreateDialogs(response);
        dialog.receive(transaction, response);
    }

    @Override
    public void onTransactionTerminated(Transaction transaction) {

    }

    @Override
    public void onIOException(Transaction transaction, SipMessage msg) {

    }
}
