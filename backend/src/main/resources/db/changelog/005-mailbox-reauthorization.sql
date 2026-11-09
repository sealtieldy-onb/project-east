--liquibase formatted sql

--changeset east:005-mailbox-reauthorization
ALTER TABLE connected_mailbox
    ADD COLUMN reauthorization_required BOOLEAN NOT NULL DEFAULT FALSE;
