package com.supriya.accreditation.query;

import com.supriya.accreditation.events.AccreditationEventMessage;
import com.supriya.accreditation.platform.messaging.PoisonRecordException;
import io.micronaut.json.JsonMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.io.IOException;

/** Parsing is the consumers' first line of defence: anything unusable is poison, never a retry. */
final class EventMessages {

    private EventMessages() {
    }

    static AccreditationEventMessage parse(JsonMapper json, ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new PoisonRecordException("Empty record");
        }
        AccreditationEventMessage message;
        try {
            message = json.readValue(record.value(), AccreditationEventMessage.class);
        } catch (IOException | RuntimeException e) {
            throw new PoisonRecordException("Unparseable event: " + e.getMessage(), e);
        }
        if (message.eventId() == null || message.accreditationId() == null || message.userId() == null
                || message.accreditationType() == null || message.status() == null || message.eventType() == null
                || message.submittedAt() == null || message.occurredAt() == null || message.performedBy() == null
                || message.version() < 1) {
            throw new PoisonRecordException("Event is missing required fields");
        }
        if (message.schemaVersion() != 1) {
            throw new PoisonRecordException("Unsupported schema_version " + message.schemaVersion());
        }
        try {
            message.toDomain(); // rejects an unknown event_type
        } catch (IllegalArgumentException e) {
            throw new PoisonRecordException(e.getMessage(), e);
        }
        return message;
    }
}
