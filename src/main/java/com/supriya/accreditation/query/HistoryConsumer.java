package com.supriya.accreditation.query;

import com.supriya.accreditation.events.AccreditationEventMessage;
import com.supriya.accreditation.platform.messaging.ConsumerRunner;
import com.supriya.accreditation.platform.messaging.PoisonRecordException;
import com.supriya.accreditation.platform.messaging.RetryingConsumer;
import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.platform.persistence.DuplicateKeys;
import com.supriya.accreditation.query.persistence.StatusHistoryRepository;
import com.supriya.accreditation.query.persistence.StatusHistoryRow;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.json.JsonMapper;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Optional;

/** Appends every event to the queryable audit history. The topic itself is the system of record. */
@Singleton
@Requires(property = "messaging.consumers.enabled", notEquals = "false")
public class HistoryConsumer implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(HistoryConsumer.class);
    static final String GROUP = "accreditation-history";
    private static final String EVENT_ID_KEY = "uq_accreditation_status_history_event_id";

    private final ConsumerRunner runner;
    private final StatusHistoryRepository history;
    private final TransactionOperations<Connection> transactions;
    private final JsonMapper json;

    HistoryConsumer(ConsumerRunner runner, StatusHistoryRepository history,
                    @Named("read") TransactionOperations<Connection> transactions, JsonMapper json) {
        this.runner = runner;
        this.history = history;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        runner.start(RetryingConsumer.Settings.defaults(GROUP, Topics.ACCREDITATION_EVENTS), this::append);
    }

    void append(ConsumerRecord<String, String> record) {
        AccreditationEventMessage event = EventMessages.parse(json, record);
        try {
            transactions.executeWrite(status -> history.save(new StatusHistoryRow(event.eventId(),
                    event.accreditationId(), event.version(), event.eventType(), event.previousStatus(),
                    event.status(), event.performedBy(), event.occurredAt())));
        } catch (RuntimeException e) {
            Optional<String> key = DuplicateKeys.violatedKey(e);
            if (key.isEmpty()) {
                throw e;
            }
            if (key.get().equals(EVENT_ID_KEY)) {
                log.debug("Ignoring redelivered event {}", event.eventId());
                return;
            }
            // Same accreditation and version under a different event ID: two writers made the same change. A bug.
            throw new PoisonRecordException("Version " + event.version() + " of " + event.accreditationId()
                    + " is already recorded under a different event", e);
        }
    }
}
