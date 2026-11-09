--liquibase formatted sql

--changeset east:004-account-color-capacity
ALTER TABLE connected_mailbox
    DROP CONSTRAINT connected_mailbox_account_color_index_check;

ALTER TABLE connected_mailbox
    ALTER COLUMN account_color_index TYPE INTEGER USING account_color_index::INTEGER;

ALTER TABLE connected_mailbox
    ADD CONSTRAINT connected_mailbox_account_color_index_nonnegative
        CHECK (account_color_index >= 0);
