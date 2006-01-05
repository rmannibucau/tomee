package org.openejb.core.transaction;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.openejb.core.ThreadContext;

public class TransactionContext {

    public Transaction clientTx;
    public Transaction currentTx;
    public ThreadContext callContext;

    private final TransactionManager transactionManager;

    public TransactionContext(ThreadContext callContext, TransactionManager transactionManager) {
        this.callContext = callContext;
        this.transactionManager = transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }
}

