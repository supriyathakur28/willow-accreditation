package com.supriya.accreditation.domain;

import java.time.Instant;
import java.util.OptionalLong;

/** A request to change an existing accreditation. Creation is handled by {@link AccreditationDecider#submit}. */
public sealed interface AccreditationCommand permits AccreditationCommand.Finalise, AccreditationCommand.ExpireIfStale {

    /**
     * An admin's decision ({@code PUT /user/accreditation/{id}}).
     *
     * @param expectedVersion the version the admin reviewed ({@code If-Match}), if sent
     */
    record Finalise(Outcome outcome, String actor, OptionalLong expectedVersion) implements AccreditationCommand {
    }

    /** The expiry job: expire only if the accreditation is still CONFIRMED and was last updated before the cutoff. */
    record ExpireIfStale(Instant cutoff, String actor) implements AccreditationCommand {
    }
}
