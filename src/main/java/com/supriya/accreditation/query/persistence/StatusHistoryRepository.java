package com.supriya.accreditation.query.persistence;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;
import java.util.UUID;

@Repository("read")
@JdbcRepository(dialect = Dialect.MYSQL)
public interface StatusHistoryRepository extends GenericRepository<StatusHistoryRow, UUID> {

    StatusHistoryRow save(StatusHistoryRow row);

    List<StatusHistoryRow> findByAccreditationIdOrderByVersion(UUID accreditationId);
}
