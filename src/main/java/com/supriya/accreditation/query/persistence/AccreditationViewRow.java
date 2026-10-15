package com.supriya.accreditation.query.persistence;

import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.domain.AccreditationType;
import com.supriya.accreditation.platform.UuidBinaryConverter;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;

import java.time.Instant;
import java.util.UUID;

/** Current state of one accreditation, as the client GET sees it. */
@MappedEntity("accreditation_views")
public record AccreditationViewRow(
        @Id @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID accreditationId,
        String userId,
        AccreditationType accreditationType,
        AccreditationStatus status,
        long version,
        Instant submittedAt,
        Instant updatedAt) {
}
