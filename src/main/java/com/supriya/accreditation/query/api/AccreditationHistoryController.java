package com.supriya.accreditation.query.api;

import com.supriya.accreditation.query.persistence.StatusHistoryRepository;
import com.supriya.accreditation.query.persistence.StatusHistoryRow;
import com.supriya.accreditation.platform.web.PathIds;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/** Admin view of the audit trail of one accreditation, built from its events. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccreditationHistoryController {

    private final StatusHistoryRepository history;
    private final TransactionOperations<Connection> transactions;

    AccreditationHistoryController(StatusHistoryRepository history,
                                   @Named("read") TransactionOperations<Connection> transactions) {
        this.history = history;
        this.transactions = transactions;
    }

    @Get("/user/accreditation/{accreditationId}/history")
    public AccreditationHistoryResponse history(@PathVariable String accreditationId) {
        UUID id = PathIds.accreditationId(accreditationId);
        List<StatusHistoryRow> rows = transactions.executeRead(status -> history.findByAccreditationIdOrderByVersion(id));
        if (rows.isEmpty()) {
            throw PathIds.accreditationNotFound(accreditationId);
        }
        return new AccreditationHistoryResponse(id, rows.stream()
                .map(r -> new AccreditationHistoryResponse.Entry(r.version(), r.eventType(), r.fromStatus(),
                        r.toStatus(), r.performedBy(), r.occurredAt()))
                .toList());
    }
}
