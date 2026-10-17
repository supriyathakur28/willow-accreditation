package com.supriya.accreditation.query.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.domain.AccreditationType;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/**
 * The brief's GET shape: accreditation ID → type and status. Always included, because Micronaut Serde omits empty
 * collections by default and the contract has an empty map for a user with no accreditations.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserAccreditationsResponse(
        String userId,
        Map<String, Entry> accreditationStatuses) {

    @Serdeable
    public record Entry(
            AccreditationType accreditationType,
            AccreditationStatus status) {
    }
}
