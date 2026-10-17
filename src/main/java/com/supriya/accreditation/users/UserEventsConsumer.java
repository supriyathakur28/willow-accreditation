package com.supriya.accreditation.users;

import com.supriya.accreditation.platform.messaging.ConsumerRunner;
import com.supriya.accreditation.platform.messaging.PoisonRecordException;
import com.supriya.accreditation.platform.messaging.RetryingConsumer;
import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.platform.web.PathIds;
import com.supriya.accreditation.users.persistence.KnownUserRepository;
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

import java.io.IOException;
import java.sql.Connection;
import java.time.Instant;

/**
 * Keeps the write model's copy of known users current, so POST can reject unknown users without a remote call.
 * <p>
 * user.events.v1 is compacted: only the latest record per user is guaranteed to survive, and it may be of any event
 * type. So any record for a user means the user exists, and a tombstone (null value) means the user was deleted.
 * Depending on a particular earlier type, such as the registration, would silently miss users when rebuilding.
 */
@Singleton
@Requires(property = "messaging.consumers.enabled", notEquals = "false")
public class UserEventsConsumer implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(UserEventsConsumer.class);
    static final String GROUP = "known-users";

    private final ConsumerRunner runner;
    private final JsonMapper json;
    private final KnownUserRepository knownUsers;
    private final TransactionOperations<Connection> transactions;

    UserEventsConsumer(
            final ConsumerRunner runner,
            final JsonMapper json,
            final KnownUserRepository knownUsers,
            @Named("write") final TransactionOperations<Connection> transactions) {
        this.runner = runner;
        this.json = json;
        this.knownUsers = knownUsers;
        this.transactions = transactions;
    }

    @Override
    public void onApplicationEvent(final StartupEvent event) {
        runner.start(RetryingConsumer.Settings.defaults(GROUP, Topics.USER_EVENTS), this::apply);
    }

    void apply(final ConsumerRecord<String, String> record) {
        final String userId = record.key();
        if (userId == null || !userId.matches(PathIds.USER_ID_PATTERN)) {
            throw new PoisonRecordException("User record without a valid user_id key");
        }
        if (record.value() == null) {
            // A tombstone: the user was deleted. Accreditation records are kept for regulatory reasons (README)
            log.info("User {} deleted upstream; keeping their accreditation records", userId);
            return;
        }
        UserEventMessage message;
        try {
            message = json.readValue(record.value(), UserEventMessage.class);
        } catch (IOException | RuntimeException e) {
            throw new PoisonRecordException("Unparseable user record: " + e.getMessage(), e);
        }
        if (message.userId() != null && !message.userId().equals(userId)) {
            throw new PoisonRecordException("Record key " + userId + " does not match its user_id " + message.userId());
        }
        // The registration time when the record carries it, otherwise when Kafka received the record
        final Instant registeredAt = message.registeredAt() != null ? message.registeredAt() : Instant.ofEpochMilli(record.timestamp());
        transactions.executeWrite(status -> {
            knownUsers.insertIfAbsent(userId, registeredAt);
            return null;
        });
    }
}
