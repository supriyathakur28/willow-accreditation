package com.supriya.accreditation.query;

import com.supriya.accreditation.events.AccreditationEventMessage;
import com.supriya.accreditation.platform.messaging.ConsumerRunner;
import com.supriya.accreditation.platform.messaging.RetryingConsumer;
import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.platform.persistence.DuplicateKeys;
import com.supriya.accreditation.query.persistence.AccreditationViewRepository;
import com.supriya.accreditation.query.persistence.AccreditationViewRow;
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

/**
 * Builds the client read model. Each event carries a full snapshot, so it is applied only if its version is newer
 * than the row's: duplicates and late events change nothing and the view never goes backwards.
 */
@Singleton
@Requires(property = "messaging.consumers.enabled", notEquals = "false")
public class ProjectionConsumer implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);
    static final String GROUP = "accreditation-projection";

    private final ConsumerRunner runner;
    private final AccreditationViewRepository views;
    private final TransactionOperations<Connection> transactions;
    private final JsonMapper json;

    ProjectionConsumer(
            final ConsumerRunner runner,
            final AccreditationViewRepository views,
            @Named("read") final TransactionOperations<Connection> transactions,
            final JsonMapper json) {
        this.runner = runner;
        this.views = views;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public void onApplicationEvent(final StartupEvent event) {
        runner.start(RetryingConsumer.Settings.defaults(GROUP, Topics.ACCREDITATION_EVENTS), this::apply);
    }

    void apply(final ConsumerRecord<String, String> record) {
        final AccreditationEventMessage event = EventMessages.parse(json, record);
        final int updated = transactions.executeWrite(status -> views.applyIfNewer(
                event.accreditationId(), event.status(), event.version(), event.occurredAt()));
        if (updated > 0) {
            return;
        }
        try {
            transactions.executeWrite(status -> views.save(new AccreditationViewRow(event.accreditationId(),
                    event.userId(), event.accreditationType(), event.status(), event.version(), event.submittedAt(),
                    event.occurredAt())));
        } catch (RuntimeException e) {
            if (DuplicateKeys.violatedKey(e).isEmpty()) {
                throw e;
            }
            // The row exists with this version or a newer one: nothing to do
            log.debug("Ignoring {} v{} for {}: already applied", event.eventType(), event.version(), event.accreditationId());
        }
    }
}
