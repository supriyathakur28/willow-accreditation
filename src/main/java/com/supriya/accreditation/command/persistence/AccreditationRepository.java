package com.supriya.accreditation.command.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface AccreditationRepository extends GenericRepository<AccreditationRow, UUID> {

    AccreditationRow save(AccreditationRow row);

    AccreditationRow update(AccreditationRow row);

    /** {@code SELECT … FOR UPDATE}: must run inside a write transaction; every decision is made on this read. */
    Optional<AccreditationRow> findByIdForUpdate(UUID id);

    Optional<AccreditationRow> findById(UUID id);

    Optional<AccreditationRow> findByCreatedByAndIdempotencyKey(
            String createdBy,
            String idempotencyKey);

    /**
     * One page of the expiry scan, keyset-paged on (updated_at, id) so a page never repeats or skips rows.
     * Plain read: each candidate is locked and re-checked in its own transaction.
     */
    @Query("""
            SELECT * FROM accreditations
            WHERE status = 'CONFIRMED' AND updated_at < :cutoff
              AND (updated_at > :afterUpdatedAt OR (updated_at = :afterUpdatedAt AND id > :afterId))
            ORDER BY updated_at, id
            LIMIT :limit""")
    List<AccreditationRow> findExpiryCandidates(
            Instant cutoff,
            Instant afterUpdatedAt,
            UUID afterId,
            int limit);
}
