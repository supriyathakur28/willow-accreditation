package com.supriya.accreditation.platform;

import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-ordered UUIDs (version 7): 48 bits of Unix milliseconds, a 12-bit counter for IDs created in the
 * same millisecond, then random bits. Time-ordered keys append to the end of InnoDB's primary-key index
 * instead of splitting pages, and are not guessable in the API.
 * <p>
 * Lock-free (no {@code synchronized}), so it never pins a virtual thread.
 */
@Singleton
public class UuidV7Generator {

    private static final int COUNTER_BITS = 12;
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;
    private static final long RANDOM_62_BITS = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Last issued (millis << 12 | counter); only ever moves forward, so IDs from one instance are strictly increasing. */
    private final AtomicLong lastTimestampAndCounter = new AtomicLong();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public UUID next() {
        long nowShifted = clock.millis() << COUNTER_BITS;
        // Same millisecond (or clock moved back): increment the counter; a full counter rolls into the next millisecond.
        long timestampAndCounter = lastTimestampAndCounter.updateAndGet(last -> Math.max(nowShifted, last + 1));

        long millis = timestampAndCounter >>> COUNTER_BITS;
        long counter = timestampAndCounter & ((1L << COUNTER_BITS) - 1);

        long mostSignificant = (millis << 16) | VERSION_7 | counter;
        long leastSignificant = (random.nextLong() & RANDOM_62_BITS) | VARIANT_RFC;
        return new UUID(mostSignificant, leastSignificant);
    }
}
