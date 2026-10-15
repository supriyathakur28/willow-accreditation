package com.supriya.accreditation.command.api;

import io.micronaut.serde.annotation.Serdeable;

import java.util.UUID;

@Serdeable
public record AccreditationIdResponse(UUID accreditationId) {
}
