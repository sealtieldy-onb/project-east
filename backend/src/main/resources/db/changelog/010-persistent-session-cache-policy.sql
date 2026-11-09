--liquibase formatted sql

--changeset east:010-persistent-session-cache-policy
-- Spring Session JDBC tables. Schema creation remains owned by Liquibase.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

-- Promote installations still using the former seven-day default. Preserve non-default values.
-- Expired payloads are not extended by a policy change.
UPDATE mail_message mm
SET body_expires_at = mm.body_cached_at + INTERVAL '60 days'
FROM connected_mailbox cm
WHERE cm.id = mm.mailbox_id
  AND cm.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
  AND mm.body_ciphertext IS NOT NULL
  AND mm.body_cached_at IS NOT NULL
  AND mm.body_expires_at > CURRENT_TIMESTAMP;

-- Promote still-live legacy snippets by the remaining 53 days of their former seven-day window.
-- Body cache markers can be expired or refreshed independently of when the snippet was cached.
UPDATE mail_thread mt
SET snippet_expires_at = mt.snippet_expires_at + INTERVAL '53 days'
FROM connected_mailbox cm
WHERE cm.id = mt.mailbox_id
  AND cm.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
  AND mt.snippet_ciphertext IS NOT NULL
  AND mt.snippet_expires_at > CURRENT_TIMESTAMP;

UPDATE mail_attachment ma
SET expires_at = ma.fetched_at + INTERVAL '60 days'
FROM mail_message mm
JOIN connected_mailbox cm ON cm.id = mm.mailbox_id
WHERE mm.id = ma.message_id
  AND cm.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
  AND ma.content_ciphertext IS NOT NULL
  AND ma.fetched_at IS NOT NULL
  AND ma.expires_at > CURRENT_TIMESTAMP
  AND (mm.body_expires_at IS NULL
       OR mm.body_expires_at > CURRENT_TIMESTAMP
       OR ma.fetched_at > mm.body_expires_at);

WITH source_expiry AS (
    SELECT ais.insight_id, MIN(mm.body_expires_at) AS expires_at
    FROM assistant_insight_source ais
    JOIN assistant_insight ai ON ai.id = ais.insight_id
    JOIN mail_message mm ON mm.id = ais.message_id
    WHERE ai.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
    GROUP BY ais.insight_id
    HAVING COUNT(*) = COUNT(mm.body_expires_at)
)
UPDATE assistant_insight ai
SET expires_at = source_expiry.expires_at
FROM source_expiry
WHERE ai.id = source_expiry.insight_id
  AND ai.expires_at > CURRENT_TIMESTAMP
  AND source_expiry.expires_at > CURRENT_TIMESTAMP;

WITH source_rows AS (
    SELECT srs.summary_run_id, mm.body_expires_at
    FROM summary_run_source srs
    JOIN mail_message mm ON mm.id = srs.message_id
    UNION ALL
    SELECT ais.summary_run_id, mm.body_expires_at
    FROM assistant_insight_source ais
    JOIN mail_message mm ON mm.id = ais.message_id
), source_expiry AS (
    SELECT sr.id AS summary_run_id, MIN(source_rows.body_expires_at) AS expires_at
    FROM summary_run sr
    JOIN source_rows ON source_rows.summary_run_id = sr.id
    WHERE sr.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
    GROUP BY sr.id
    HAVING COUNT(*) = COUNT(source_rows.body_expires_at)
)
UPDATE summary_run sr
SET expires_at = source_expiry.expires_at
FROM source_expiry
WHERE sr.id = source_expiry.summary_run_id
  AND sr.narrative_ciphertext IS NOT NULL
  AND sr.expires_at > CURRENT_TIMESTAMP
  AND source_expiry.expires_at > CURRENT_TIMESTAMP;

UPDATE summary_run sr
SET expires_at = sr.created_at + INTERVAL '60 days'
WHERE sr.app_user_id IN (SELECT id FROM app_user WHERE body_cache_ttl_days = 7)
  AND sr.narrative_ciphertext IS NOT NULL
  AND sr.run_purpose IN ('FIRST_LINK', 'MANUAL', 'DAILY', 'CATCH_UP')
  AND sr.expires_at > CURRENT_TIMESTAMP
  AND NOT EXISTS (SELECT 1 FROM summary_run_source srs WHERE srs.summary_run_id = sr.id)
  AND NOT EXISTS (
      SELECT 1
      FROM assistant_insight ai
      JOIN assistant_insight_source ais ON ais.insight_id = ai.id
      WHERE ai.summary_run_id = sr.id
  );

UPDATE app_user
SET body_cache_ttl_days = 60, updated_at = CURRENT_TIMESTAMP
WHERE body_cache_ttl_days = 7;

ALTER TABLE app_user
    ALTER COLUMN body_cache_ttl_days SET DEFAULT 60;
