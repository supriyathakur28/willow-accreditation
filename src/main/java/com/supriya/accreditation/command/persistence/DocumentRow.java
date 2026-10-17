package com.supriya.accreditation.command.persistence;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

@MappedEntity("accreditation_documents")
public record DocumentRow(
        @Id UUID accreditationId,
        String fileName,
        String mimeType,
        int sizeBytes,
        String sha256,
        byte[] content,
        Instant createdAt) {
}
