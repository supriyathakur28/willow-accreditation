package com.supriya.accreditation.users;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** An event from the Users service on user.events.v1. Only UserRegistered matters here; others are ignored. */
@Serdeable
record UserEventMessage(@Nullable String eventId, @Nullable String eventType, int schemaVersion,
                        @Nullable String userId, @Nullable Instant registeredAt) {

    static final String USER_REGISTERED = "UserRegistered";
}
