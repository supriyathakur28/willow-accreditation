package com.supriya.accreditation.query.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supriya.accreditation.domain.AccreditationStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Serdeable
public record AccreditationHistoryResponse(
        UUID accreditationId,
        List<Entry> history) {

    /** One state change; from_status is null for the submission. The latest version is what If-Match takes. */
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(
            long version,
            String eventType,
            @Nullable AccreditationStatus fromStatus,
            AccreditationStatus toStatus,
            String performedBy,
            Instant occurredAt) {
    }
}
