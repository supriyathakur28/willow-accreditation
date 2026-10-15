package com.supriya.accreditation.domain;

/** The outcome of {@link AccreditationDecider#decide}: a value, never an exception. */
public sealed interface Decision {

    /** The command is legal: persist the event and apply it. */
    record Accepted(AccreditationEvent event) implements Decision {
    }

    /** The accreditation is already in the requested state: nothing to do, no event. */
    record NoOp(Accreditation current) implements Decision {
    }

    /** The command is not allowed against the current state. */
    record Rejected(Reason reason, Accreditation current) implements Decision {
    }

    enum Reason {
        TRANSITION_NOT_ALLOWED,
        VERSION_MISMATCH,
        NOT_ELIGIBLE_FOR_EXPIRY
    }
}
