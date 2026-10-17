package com.supriya.accreditation.query.api;

import com.supriya.accreditation.query.persistence.AccreditationViewRepository;
import com.supriya.accreditation.query.persistence.AccreditationViewRow;
import com.supriya.accreditation.platform.security.Callers;
import com.supriya.accreditation.platform.security.Roles;
import com.supriya.accreditation.platform.web.ConcurrencyLimit;
import com.supriya.accreditation.platform.web.PathIds;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;

import java.security.Principal;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client endpoint. Reads only the event-built read model, so it is eventually consistent with admin writes
 * (normally within a second) and never touches the write model.
 */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured({Roles.ADMIN, Roles.USER})
@ConcurrencyLimit("client-reads")
public class ClientAccreditationController {

    private final AccreditationViewRepository views;
    private final TransactionOperations<Connection> transactions;

    ClientAccreditationController(
            final AccreditationViewRepository views,
            @Named("read") final TransactionOperations<Connection> transactions) {
        this.views = views;
        this.transactions = transactions;
    }

    /** A user with no accreditations, known or not, gets an empty map: the endpoint does not reveal who exists. */
    @Get("/user/{userId}/accreditation")
    public UserAccreditationsResponse accreditations(
            @PathVariable final String userId,
            @Nullable final Principal principal) {
        final String validUserId = PathIds.userId(userId);
        Callers.requireMayRead(principal, validUserId);
        final List<AccreditationViewRow> rows = transactions.executeRead(
                status -> views.findByUserIdOrderByAccreditationId(validUserId));
        final Map<String, UserAccreditationsResponse.Entry> statuses = new LinkedHashMap<>();
        for (AccreditationViewRow row : rows) {
            statuses.put(row.accreditationId().toString(),
                    new UserAccreditationsResponse.Entry(row.accreditationType(), row.status()));
        }
        return new UserAccreditationsResponse(validUserId, statuses);
    }
}
