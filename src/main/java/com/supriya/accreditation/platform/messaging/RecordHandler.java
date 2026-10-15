package com.supriya.accreditation.platform.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Applies one record. Return normally when it is applied (or deliberately ignored); throw
 * {@link PoisonRecordException} when it can never be applied; throw anything else to have it retried.
 */
@FunctionalInterface
public interface RecordHandler {

    void handle(ConsumerRecord<String, String> record) throws Exception;
}
