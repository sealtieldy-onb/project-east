--liquibase formatted sql

--changeset east:006-immediate-body-expiry
ALTER TABLE mail_message DROP CONSTRAINT mail_message_body_expiry;

ALTER TABLE mail_message
    ADD CONSTRAINT mail_message_body_expiry CHECK (
        body_expires_at IS NULL
        OR body_expires_at > body_cached_at
        OR (
            body_expires_at = body_cached_at
            AND body_mime_type IN ('east-internal:ON_DEMAND', 'east-internal:SYNC_LIMIT_ON_DEMAND')
        )
    );
