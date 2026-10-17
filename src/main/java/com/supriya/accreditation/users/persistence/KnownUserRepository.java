package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;

/** The local copy of the users the Users service knows, in the write model, where POST checks it. */
@Repository("write")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface KnownUserRepository extends GenericRepository<KnownUserRow, String> {

    /** Redelivered user events are a no-op: the first registration is kept. */
    @Query("""
            INSERT INTO known_users (user_id, registered_at) VALUES (:userId, :registeredAt)
            ON DUPLICATE KEY UPDATE user_id = user_id""")
    void insertIfAbsent(
            String userId,
            Instant registeredAt);

    boolean existsById(String userId);
}
