package com.supriya.accreditation.command.persistence;

import com.supriya.accreditation.platform.UuidBinaryConverter;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;

import java.time.Instant;
import java.util.UUID;

@MappedEntity("accreditation_documents")
public record DocumentRow(
        @Id @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID accreditationId,
        String fileName,
        String mimeType,
        int sizeBytes,
        String sha256,
        byte[] content,
        Instant createdAt) {
}
