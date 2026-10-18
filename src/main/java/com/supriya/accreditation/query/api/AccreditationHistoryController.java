package com.supriya.accreditation.query.api;

import com.supriya.accreditation.query.persistence.StatusHistoryRepository;
import com.supriya.accreditation.query.persistence.StatusHistoryRow;
import com.supriya.accreditation.platform.security.Roles;
import com.supriya.accreditation.platform.web.ConcurrencyLimit;
import com.supriya.accreditation.platform.web.PathIds;
import io.micronaut.openapi.annotation.OpenAPIGroup;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.transaction.TransactionOperations;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Named;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/** Admin view of the audit trail of one accreditation, built from its events. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(Roles.ADMIN)
@ConcurrencyLimit("client-reads")
@OpenAPIGroup("admin")
@Tag(
        name = "Audit history",
        description = "Every change to one accreditation. Requires an ADMIN token.")
@SecurityRequirement(name = "bearer")
public class AccreditationHistoryController {

    private final StatusHistoryRepository history;
    private final TransactionOperations<Connection> transactions;

    AccreditationHistoryController(
            final StatusHistoryRepository history,
            @Named("read") final TransactionOperations<Connection> transactions) {
        this.history = history;
        this.transactions = transactions;
    }

    @Get("/user/accreditation/{accreditationId}/history")
    public AccreditationHistoryResponse history(@PathVariable final String accreditationId) {
        final UUID id = PathIds.accreditationId(accreditationId);
        final List<StatusHistoryRow> rows = transactions.executeRead(status -> history.findByAccreditationIdOrderByVersion(id));
        if (rows.isEmpty()) {
            throw PathIds.accreditationNotFound(accreditationId);
        }
        return new AccreditationHistoryResponse(id, rows.stream()
                .map(r -> new AccreditationHistoryResponse.Entry(r.version(), r.eventType(), r.fromStatus(),
                        r.toStatus(), r.performedBy(), r.occurredAt()))
                .toList());
    }
}
