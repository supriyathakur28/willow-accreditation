package com.supriya.accreditation.command.persistence;

import com.supriya.accreditation.domain.Accreditation;
import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.domain.AccreditationType;
import com.supriya.accreditation.platform.UuidBinaryConverter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code accreditations}. The generated column {@code user_id_if_pending} is never written. */
@MappedEntity("accreditations")
public record AccreditationRow(
        @Id @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID id,
        String userId,
        AccreditationType accreditationType,
        AccreditationStatus status,
        long version,
        String createdBy,
        @Nullable String idempotencyKey,
        @Nullable String requestSha256,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {

    public Accreditation toDomain() {
        return new Accreditation(id, userId, accreditationType, status, version, createdAt, updatedAt, updatedBy);
    }

    /** The same row moved to the state the decider produced; creation columns are kept. */
    public AccreditationRow withState(final Accreditation next) {
        return new AccreditationRow(id, userId, accreditationType, next.status(), next.version(),
                createdBy, idempotencyKey, requestSha256, createdAt, next.updatedAt(), next.updatedBy());
    }
}
