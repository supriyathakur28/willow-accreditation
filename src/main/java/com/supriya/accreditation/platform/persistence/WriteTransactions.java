package com.supriya.accreditation.platform.persistence;

import com.supriya.accreditation.platform.web.ServiceUnavailableException;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * Runs work in a write-model transaction. A deadlock rolls back the whole transaction, so the whole unit is
 * retried (up to twice). Any other transient failure, such as a 3 s lock wait, becomes a 503: the caller retries.
 * Business errors such as a duplicate key pass through untouched.
 */
@Singleton
public class WriteTransactions {

    private static final Logger log = LoggerFactory.getLogger(WriteTransactions.class);
    static final int DEADLOCK_RETRIES = 2;

    private final TransactionOperations<Connection> transactions;

    public WriteTransactions(@Named("write") TransactionOperations<Connection> transactions) {
        this.transactions = transactions;
    }

    public <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.executeWrite(status -> work.get());
            } catch (RuntimeException e) {
                if (TransientErrors.isDeadlock(e) && attempt < DEADLOCK_RETRIES) {
                    log.info("Deadlock, retrying the transaction (attempt {})", attempt + 2);
                    continue;
                }
                if (TransientErrors.isTransient(e)) {
                    throw new ServiceUnavailableException("Write transaction failed transiently", e);
                }
                throw e;
            }
        }
    }
}
