ALTER TABLE connected_mailbox
    ADD CONSTRAINT connected_mailbox_id_app_user_unique UNIQUE (id, app_user_id);

ALTER TABLE summary_run
    ADD CONSTRAINT summary_run_id_app_user_unique UNIQUE (id, app_user_id);

CREATE TABLE summary_run_source (
    summary_run_id UUID NOT NULL,
    app_user_id UUID NOT NULL,
    mailbox_id UUID NOT NULL,
    message_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (summary_run_id, message_id),
    FOREIGN KEY (summary_run_id, app_user_id)
        REFERENCES summary_run (id, app_user_id) ON DELETE CASCADE,
    FOREIGN KEY (mailbox_id, app_user_id)
        REFERENCES connected_mailbox (id, app_user_id) ON DELETE CASCADE,
    FOREIGN KEY (message_id, mailbox_id)
        REFERENCES mail_message (id, mailbox_id) ON DELETE CASCADE
);

CREATE INDEX summary_run_source_mailbox_idx
    ON summary_run_source (app_user_id, mailbox_id, summary_run_id);
CREATE INDEX summary_run_source_message_idx
    ON summary_run_source (message_id, mailbox_id);
