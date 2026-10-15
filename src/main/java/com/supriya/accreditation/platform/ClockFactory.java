package com.supriya.accreditation.platform;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/** Every timestamp comes from this clock, always UTC; tests replace it to control time. */
@Factory
public class ClockFactory {

    @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }
}
