package com.supriya.accreditation.query.persistence;

import com.supriya.accreditation.domain.AccreditationStatus;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository("read")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface AccreditationViewRepository extends GenericRepository<AccreditationViewRow, UUID> {

    AccreditationViewRow save(AccreditationViewRow row);

    /**
     * Applies an event only if it is newer than what the view holds, so redelivered or late events are ignored.
     * Returns 0 when the row is missing or already newer; the caller then inserts or skips.
     */
    @Query("""
            UPDATE accreditation_views
            SET status = :status, version = :version, updated_at = :updatedAt
            WHERE accreditation_id = :accreditationId AND version < :version""")
    int applyIfNewer(
            UUID accreditationId,
            AccreditationStatus status,
            long version,
            Instant updatedAt);

    Optional<AccreditationViewRow> findById(UUID accreditationId);

    /** One range scan of the (user_id, accreditation_id) primary key. */
    List<AccreditationViewRow> findByUserIdOrderByAccreditationId(String userId);
}
