package com.supriya.accreditation.platform.persistence;

import com.supriya.accreditation.platform.web.ServiceUnavailableException;
import com.supriya.accreditation.support.IntegrationTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransactionRollbackException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a write transaction reacts to each kind of database failure. */
@MicronautTest(startApplication = false, transactional = false)
class WriteTransactionsIntegrationTest extends IntegrationTest {

    @Inject WriteTransactions transactions;

    @Test
    void retriesTheWholeTransactionAfterADeadlock() {
        AtomicInteger attempts = new AtomicInteger();

        String result = transactions.inTransaction(() -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new RuntimeException(new SQLTransactionRollbackException("Deadlock found", "40001", 1213));
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void givesUpAfterTwoRetriesWith503() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException(new SQLTransactionRollbackException("Deadlock found", "40001", 1213));
        })).isInstanceOf(ServiceUnavailableException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryALockWaitTimeout() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException(new SQLTransactionRollbackException("Lock wait timeout", "40001", 1205));
        })).isInstanceOf(ServiceUnavailableException.class);
        assertThat(attempts).hasValue(1); // the caller already waited 3 s; it retries, not us
    }

    @Test
    void passesBusinessErrorsThrough() {
        RuntimeException duplicate = new RuntimeException(
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062));

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
            throw duplicate;
        })).isSameAs(duplicate);
    }
}
