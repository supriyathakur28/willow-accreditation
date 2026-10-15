package com.supriya.accreditation.platform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:12:44.123Z");

    private final UuidV7Generator generator = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void isVersion7WithTheStandardVariant() {
        UUID id = generator.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheClockMillisecondsInTheFirst48Bits() {
        UUID id = generator.next();

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void idsFromTheSameMillisecondAreStrictlyIncreasing() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.next());
        }

        // Compared as unsigned big-endian bytes, which is how MySQL orders BINARY(16)
        for (int i = 1; i < ids.size(); i++) {
            assertThat(Long.compareUnsigned(ids.get(i).getMostSignificantBits(), ids.get(i - 1).getMostSignificantBits()))
                    .as("id %d sorts after id %d", i, i - 1)
                    .isPositive();
        }
    }

    @Test
    void concurrentCallersOnVirtualThreadsNeverGetTheSameId() throws Exception {
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        seen.add(generator.next());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(seen).hasSize(16 * 2_000);
    }
}
