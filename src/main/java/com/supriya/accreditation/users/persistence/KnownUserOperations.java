package com.supriya.accreditation.users.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.Optional;

/** Shared by the write-side and read-side copies; each sub-interface binds it to one datasource. */
public interface KnownUserOperations extends GenericRepository<KnownUserRow, String> {

    /** Redelivered user events are a no-op: the first registration is kept. */
    @Query("""
            INSERT INTO known_users (user_id, registered_at) VALUES (:userId, :registeredAt)
            ON DUPLICATE KEY UPDATE user_id = user_id""")
    void insertIfAbsent(String userId, Instant registeredAt);

    boolean existsById(String userId);

    Optional<KnownUserRow> findById(String userId);
}
