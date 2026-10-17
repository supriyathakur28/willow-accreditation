package com.supriya.accreditation.command;

import com.supriya.accreditation.domain.Accreditation;
import com.supriya.accreditation.domain.Decision;

public sealed interface FinaliseResult permits FinaliseResult.Applied, FinaliseResult.Rejected,
        FinaliseResult.NotFound {

    /** The outcome is now the accreditation's status: either changed now, or it already was (a no-op). */
    record Applied(Accreditation accreditation) implements FinaliseResult {
    }

    record Rejected(Decision.Reason reason, Accreditation current) implements FinaliseResult {
    }

    record NotFound() implements FinaliseResult {
    }
}
