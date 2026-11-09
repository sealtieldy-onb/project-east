--liquibase formatted sql

--changeset east:011-inline-image-content-id
ALTER TABLE mail_attachment
    ADD COLUMN content_id TEXT;

ALTER TABLE mail_attachment
    ADD CONSTRAINT mail_attachment_content_id_length CHECK (
        content_id IS NULL OR char_length(content_id) BETWEEN 1 AND 998
    );
