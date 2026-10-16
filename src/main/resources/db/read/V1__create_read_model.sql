-- Client read model: written only by the event consumers, read by GET /user/{userId}/accreditation.

CREATE TABLE accreditation_views (
    user_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accreditation_id   BINARY(16)  NOT NULL,
    accreditation_type VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    version            BIGINT      NOT NULL,                        -- events older than this are ignored
    submitted_at       DATETIME(6) NOT NULL,                        -- set on insert only
    updated_at         DATETIME(6) NOT NULL,

    -- Clustered by user: a user's accreditations sit together, so the GET is one short range scan
    PRIMARY KEY (user_id, accreditation_id),
    UNIQUE KEY uq_accreditation_views_accreditation_id (accreditation_id),
    CONSTRAINT chk_accreditation_views_type   CHECK (accreditation_type IN ('BY_INCOME', 'BY_NET_WORTH')),
    CONSTRAINT chk_accreditation_views_status CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Append-only audit trail, one row per event
CREATE TABLE accreditation_status_history (
    accreditation_id BINARY(16)   NOT NULL,
    version          BIGINT       NOT NULL,
    event_id         BINARY(16)   NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    from_status      VARCHAR(20)  NULL,                             -- NULL for the submission
    to_status        VARCHAR(20)  NOT NULL,
    performed_by     VARCHAR(128) NOT NULL,
    occurred_at      DATETIME(6)  NOT NULL,

    -- History endpoint reads one accreditation in version order; one event per version
    PRIMARY KEY (accreditation_id, version),
    -- Redelivered events are recognised by their ID and ignored
    UNIQUE KEY uq_accreditation_status_history_event_id (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
