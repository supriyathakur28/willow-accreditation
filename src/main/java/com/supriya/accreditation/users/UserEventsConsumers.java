package com.supriya.accreditation.users;

import com.supriya.accreditation.platform.messaging.ConsumerRunner;
import com.supriya.accreditation.platform.messaging.PoisonRecordException;
import com.supriya.accreditation.platform.messaging.RetryingConsumer;
import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.platform.web.PathIds;
import com.supriya.accreditation.users.persistence.KnownUserOperations;
import com.supriya.accreditation.users.persistence.ReadKnownUserRepository;
import com.supriya.accreditation.users.persistence.WriteKnownUserRepository;
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

/**
 * Keeps both local copies of known users current: one in the write model (checked on POST) and one in the read
 * model. Two consumer groups, so each copy advances on its own and a failure in one never holds back the other.
 */
@Singleton
@Requires(property = "messaging.consumers.enabled", notEquals = "false")
public class UserEventsConsumers implements ApplicationEventListener<StartupEvent> {

    private static final Logger log = LoggerFactory.getLogger(UserEventsConsumers.class);
    static final String WRITE_COPY_GROUP = "users-write-copy";
    static final String READ_COPY_GROUP = "users-read-copy";

    private final ConsumerRunner runner;
    private final JsonMapper json;
    private final WriteKnownUserRepository writeCopy;
    private final ReadKnownUserRepository readCopy;
    private final TransactionOperations<Connection> writeTransactions;
    private final TransactionOperations<Connection> readTransactions;

    UserEventsConsumers(ConsumerRunner runner, JsonMapper json,
                        WriteKnownUserRepository writeCopy, ReadKnownUserRepository readCopy,
                        @Named("write") TransactionOperations<Connection> writeTransactions,
                        @Named("read") TransactionOperations<Connection> readTransactions) {
        this.runner = runner;
        this.json = json;
        this.writeCopy = writeCopy;
        this.readCopy = readCopy;
        this.writeTransactions = writeTransactions;
        this.readTransactions = readTransactions;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        runner.start(RetryingConsumer.Settings.defaults(WRITE_COPY_GROUP, Topics.USER_EVENTS),
                record -> apply(record, writeCopy, writeTransactions));
        runner.start(RetryingConsumer.Settings.defaults(READ_COPY_GROUP, Topics.USER_EVENTS),
                record -> apply(record, readCopy, readTransactions));
    }

    void apply(ConsumerRecord<String, String> record, KnownUserOperations users,
               TransactionOperations<Connection> transactions) {
        if (record.value() == null) {
            // A tombstone: the user was deleted. Accreditation records are kept for regulatory reasons (README)
            log.info("User {} deleted upstream; keeping their accreditation records", record.key());
            return;
        }
        UserEventMessage message;
        try {
            message = json.readValue(record.value(), UserEventMessage.class);
        } catch (IOException | RuntimeException e) {
            throw new PoisonRecordException("Unparseable user event: " + e.getMessage(), e);
        }
        if (!UserEventMessage.USER_REGISTERED.equals(message.eventType())) {
            log.debug("Ignoring user event {}", message.eventType());
            return;
        }
        if (message.userId() == null || !message.userId().matches(PathIds.USER_ID_PATTERN) || message.registeredAt() == null) {
            throw new PoisonRecordException("UserRegistered without a valid user_id or registered_at");
        }
        transactions.executeWrite(status -> {
            users.insertIfAbsent(message.userId(), message.registeredAt());
            return null;
        });
    }
}
