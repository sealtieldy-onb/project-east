--liquibase formatted sql

--changeset east:002-mail-message-label-cascade-index
CREATE INDEX mail_message_label_label_mailbox_idx
    ON mail_message_label (label_id, mailbox_id);
