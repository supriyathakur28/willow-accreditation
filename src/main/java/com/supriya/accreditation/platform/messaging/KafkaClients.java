package com.supriya.accreditation.platform.messaging;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Map;

@Factory
public class KafkaClients {

    private final String bootstrapServers;

    KafkaClients(@Value("${kafka.bootstrap.servers}") final String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Shared by the relay and the dead-letter writes. acks=all with idempotence: a send is acknowledged only when
     * it is replicated, and producer retries cannot duplicate or reorder within a partition.
     */
    @Singleton
    @Bean(preDestroy = "close")
    Producer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                // send() blocks at most this long when the broker is unreachable, so the relay fails fast
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000,
                ProducerConfig.LINGER_MS_CONFIG, 5,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    public KafkaConsumer<String, String> newConsumer(final String groupId) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
