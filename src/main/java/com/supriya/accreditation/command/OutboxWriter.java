package com.supriya.accreditation.command;

import com.supriya.accreditation.command.persistence.OutboxEventRow;
import com.supriya.accreditation.command.persistence.OutboxRepository;
import com.supriya.accreditation.domain.AccreditationEvent;
import com.supriya.accreditation.events.AccreditationEventMessage;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Records an event in the outbox. Must be called inside the transaction that makes the state change. */
@Singleton
class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    OutboxWriter(
            final OutboxRepository outbox,
            final JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    void append(
            final AccreditationEvent event,
            @Nullable final String requestId) {
        String payload;
        try {
            payload = json.writeValueAsString(AccreditationEventMessage.from(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        outbox.save(OutboxEventRow.pending(event.eventId(), event.accreditationId(), event.eventType(), payload,
                requestId, event.occurredAt()));
    }
}
