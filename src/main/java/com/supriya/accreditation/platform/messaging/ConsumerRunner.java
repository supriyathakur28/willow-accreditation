package com.supriya.accreditation.platform.messaging;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Starts each consumer on its own platform thread (KafkaConsumer is single-threaded) and stops them on shutdown. */
@Singleton
public class ConsumerRunner {

    private static final Logger log = LoggerFactory.getLogger(ConsumerRunner.class);

    private final KafkaClients clients;
    private final Producer<String, String> producer;
    private final Clock clock;
    private final List<RetryingConsumer> consumers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    ConsumerRunner(KafkaClients clients, Producer<String, String> producer, Clock clock) {
        this.clients = clients;
        this.producer = producer;
        this.clock = clock;
    }

    public synchronized void start(RetryingConsumer.Settings settings, RecordHandler handler) {
        RetryingConsumer consumer = new RetryingConsumer(settings, clients.newConsumer(settings.groupId()), producer,
                handler, clock);
        Thread thread = Thread.ofPlatform().name("consumer-" + settings.groupId()).start(consumer);
        consumers.add(consumer);
        threads.add(thread);
        log.info("Consumer {} started on {}", settings.groupId(), settings.topic());
    }

    @PreDestroy
    synchronized void stopAll() throws InterruptedException {
        consumers.forEach(RetryingConsumer::stop);
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }
}
