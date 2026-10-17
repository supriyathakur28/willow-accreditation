package com.supriya.accreditation.query.persistence;

import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.domain.AccreditationType;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/** Current state of one accreditation, as the client GET sees it. */
@MappedEntity("accreditation_views")
public record AccreditationViewRow(
        @Id UUID accreditationId,
        String userId,
        AccreditationType accreditationType,
        AccreditationStatus status,
        long version,
        Instant submittedAt,
        Instant updatedAt) {
}
