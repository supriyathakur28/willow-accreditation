package com.supriya.accreditation.command.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface OutboxRepository extends GenericRepository<OutboxEventRow, Long> {

    OutboxEventRow save(OutboxEventRow row);

    /** The relay's next batch, oldest first. Uses ix_outbox_events_published_at_id, so no filesort. */
    @Query("SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit")
    List<OutboxEventRow> findUnpublished(int limit);

    @Query("UPDATE outbox_events SET published_at = :publishedAt WHERE id IN (:ids) AND published_at IS NULL")
    int markPublished(
            List<Long> ids,
            Instant publishedAt);

    @Query("SELECT id FROM outbox_events WHERE published_at < :before ORDER BY id LIMIT :limit")
    List<Long> findPublishedBefore(
            Instant before,
            int limit);

    /** The predicate is repeated so a row can never be deleted unless it was published before the cut-off. */
    @Query("DELETE FROM outbox_events WHERE id IN (:ids) AND published_at < :before")
    int deletePublishedBefore(
            List<Long> ids,
            Instant before);

    /** Relay lag: how long the oldest unpublished event has been waiting. */
    @Query("SELECT MIN(created_at) FROM outbox_events WHERE published_at IS NULL")
    Optional<Instant> findOldestUnpublishedCreatedAt();
}
