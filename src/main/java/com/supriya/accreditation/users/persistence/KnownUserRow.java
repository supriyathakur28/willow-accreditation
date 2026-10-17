package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** A user the Users service has registered. */
@MappedEntity("known_users")
public record KnownUserRow(
        @Id String userId,
        Instant registeredAt) {
}
