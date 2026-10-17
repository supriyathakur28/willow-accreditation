package com.supriya.accreditation.users;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/**
 * A record on user.events.v1, owned by the Users service. Only the fields this service needs are read; any others
 * (and any event type) are accepted, because the topic is compacted and the latest record per user may be of any kind.
 */
@Serdeable
record UserEventMessage(
        @Nullable String userId,
        @Nullable Instant registeredAt) {
}
