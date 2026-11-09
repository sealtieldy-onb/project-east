--liquibase formatted sql

--changeset east:001-initial-local-schema
CREATE TABLE vault_state (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    salt BYTEA NOT NULL CHECK (octet_length(salt) = 16),
    verification_marker BYTEA NOT NULL CHECK (octet_length(verification_marker) > 28),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    singleton_id SMALLINT NOT NULL DEFAULT 1 UNIQUE CHECK (singleton_id = 1),
    google_subject TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL,
    display_name TEXT,
    body_cache_ttl_days SMALLINT NOT NULL DEFAULT 7 CHECK (body_cache_ttl_days BETWEEN 1 AND 365),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE connected_mailbox (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    google_subject TEXT NOT NULL,
    email TEXT NOT NULL,
    display_name TEXT,
    account_color_index SMALLINT NOT NULL CHECK (account_color_index BETWEEN 0 AND 7),
    granted_scopes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    oauth_refresh_token_nonce BYTEA,
    oauth_refresh_token_ciphertext BYTEA,
    sync_cursor TEXT,
    last_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT connected_mailbox_refresh_token_pair CHECK (
        (oauth_refresh_token_nonce IS NULL AND oauth_refresh_token_ciphertext IS NULL)
        OR (oauth_refresh_token_nonce IS NOT NULL
            AND oauth_refresh_token_ciphertext IS NOT NULL
            AND octet_length(oauth_refresh_token_nonce) = 12
            AND octet_length(oauth_refresh_token_ciphertext) > 16)
    ),
    UNIQUE (app_user_id, google_subject)
);

CREATE TABLE mail_thread (
    id UUID PRIMARY KEY,
    mailbox_id UUID NOT NULL REFERENCES connected_mailbox(id) ON DELETE CASCADE,
    gmail_thread_id TEXT NOT NULL,
    subject TEXT NOT NULL DEFAULT '',
    last_message_at TIMESTAMPTZ,
    message_count INTEGER NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    is_unread BOOLEAN NOT NULL DEFAULT FALSE,
    is_starred BOOLEAN NOT NULL DEFAULT FALSE,
    snippet_nonce BYTEA,
    snippet_ciphertext BYTEA,
    snippet_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mail_thread_snippet_cipher_pair CHECK (
        (snippet_nonce IS NULL AND snippet_ciphertext IS NULL AND snippet_expires_at IS NULL)
        OR (snippet_nonce IS NOT NULL
            AND snippet_ciphertext IS NOT NULL
            AND snippet_expires_at IS NOT NULL
            AND octet_length(snippet_nonce) = 12
            AND octet_length(snippet_ciphertext) > 16
            )
    ),
    UNIQUE (mailbox_id, gmail_thread_id),
    UNIQUE (id, mailbox_id)
);

CREATE TABLE mail_message (
    id UUID PRIMARY KEY,
    mailbox_id UUID NOT NULL REFERENCES connected_mailbox(id) ON DELETE CASCADE,
    thread_id UUID NOT NULL,
    gmail_message_id TEXT NOT NULL,
    sender_email TEXT NOT NULL DEFAULT '',
    sender_name TEXT,
    sent_at TIMESTAMPTZ NOT NULL,
    body_mime_type TEXT,
    body_nonce BYTEA,
    body_ciphertext BYTEA,
    body_cached_at TIMESTAMPTZ,
    body_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mail_message_body_cipher_pair CHECK (
        (body_nonce IS NULL AND body_ciphertext IS NULL AND body_cached_at IS NULL AND body_expires_at IS NULL)
        OR (body_nonce IS NOT NULL
            AND body_ciphertext IS NOT NULL
            AND body_cached_at IS NOT NULL
            AND body_expires_at IS NOT NULL
            AND octet_length(body_nonce) = 12
            AND octet_length(body_ciphertext) > 16
            )
    ),
    CONSTRAINT mail_message_body_expiry CHECK (
        body_expires_at IS NULL OR body_expires_at > body_cached_at
    ),
    FOREIGN KEY (thread_id, mailbox_id) REFERENCES mail_thread(id, mailbox_id) ON DELETE CASCADE,
    UNIQUE (mailbox_id, gmail_message_id),
    UNIQUE (id, mailbox_id)
);

CREATE INDEX mail_message_thread_sent_at_idx ON mail_message(thread_id, sent_at);
CREATE INDEX mail_message_expiry_idx ON mail_message(body_expires_at) WHERE body_expires_at IS NOT NULL;
CREATE INDEX mail_thread_inbox_idx ON mail_thread(mailbox_id, last_message_at DESC);

CREATE TABLE mail_attachment (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES mail_message(id) ON DELETE CASCADE,
    gmail_attachment_id TEXT NOT NULL,
    filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    byte_size BIGINT CHECK (byte_size IS NULL OR byte_size >= 0),
    fetched_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    content_nonce BYTEA,
    content_ciphertext BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mail_attachment_content_cipher_pair CHECK (
        (content_nonce IS NULL AND content_ciphertext IS NULL AND fetched_at IS NULL AND expires_at IS NULL)
        OR (content_nonce IS NOT NULL
            AND content_ciphertext IS NOT NULL
            AND fetched_at IS NOT NULL
            AND expires_at IS NOT NULL
            AND octet_length(content_nonce) = 12
            AND octet_length(content_ciphertext) > 16
            )
    ),
    CONSTRAINT mail_attachment_expiry CHECK (
        expires_at IS NULL OR expires_at > fetched_at
    ),
    UNIQUE (message_id, gmail_attachment_id)
);

CREATE INDEX mail_attachment_expiry_idx ON mail_attachment(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE mail_label (
    id UUID PRIMARY KEY,
    mailbox_id UUID NOT NULL REFERENCES connected_mailbox(id) ON DELETE CASCADE,
    gmail_label_id TEXT NOT NULL,
    name TEXT NOT NULL,
    label_type TEXT NOT NULL CHECK (label_type IN ('SYSTEM', 'USER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (mailbox_id, gmail_label_id),
    UNIQUE (id, mailbox_id)
);

CREATE TABLE mail_message_label (
    message_id UUID NOT NULL,
    label_id UUID NOT NULL,
    mailbox_id UUID NOT NULL REFERENCES connected_mailbox(id) ON DELETE CASCADE,
    CONSTRAINT mail_message_label_message_scope FOREIGN KEY (message_id, mailbox_id)
        REFERENCES mail_message(id, mailbox_id) ON DELETE CASCADE,
    CONSTRAINT mail_message_label_label_scope FOREIGN KEY (label_id, mailbox_id)
        REFERENCES mail_label(id, mailbox_id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, label_id)
);

CREATE TABLE mail_draft (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    mailbox_id UUID NOT NULL REFERENCES connected_mailbox(id) ON DELETE CASCADE,
    source_thread_id UUID,
    draft_kind TEXT NOT NULL CHECK (draft_kind IN ('NEW', 'REPLY', 'REPLY_ALL', 'FORWARD')),
    draft_state TEXT NOT NULL DEFAULT 'LOCAL' CHECK (draft_state IN ('LOCAL', 'SAVED_TO_GMAIL')),
    gmail_draft_id TEXT,
    payload_nonce BYTEA CHECK (payload_nonce IS NULL OR octet_length(payload_nonce) = 12),
    payload_ciphertext BYTEA CHECK (payload_ciphertext IS NULL OR octet_length(payload_ciphertext) > 16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    saved_at TIMESTAMPTZ,
    CONSTRAINT mail_draft_saved_state CHECK (
        (draft_state = 'LOCAL'
            AND gmail_draft_id IS NULL
            AND saved_at IS NULL
            AND payload_nonce IS NOT NULL
            AND payload_ciphertext IS NOT NULL)
        OR (draft_state = 'SAVED_TO_GMAIL'
            AND gmail_draft_id IS NOT NULL
            AND saved_at IS NOT NULL
            AND payload_nonce IS NULL
            AND payload_ciphertext IS NULL)
    ),
    FOREIGN KEY (source_thread_id, mailbox_id)
        REFERENCES mail_thread(id, mailbox_id) ON DELETE CASCADE
);

CREATE TABLE mail_draft_attachment (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL REFERENCES mail_draft(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    content_nonce BYTEA NOT NULL CHECK (octet_length(content_nonce) = 12),
    content_ciphertext BYTEA NOT NULL CHECK (octet_length(content_ciphertext) > 16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE summary_schedule (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    run_at_local TIME NOT NULL DEFAULT TIME '08:00',
    timezone_id TEXT NOT NULL DEFAULT 'UTC',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE summary_run (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    run_status TEXT NOT NULL CHECK (run_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    narrative_nonce BYTEA,
    narrative_ciphertext BYTEA,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT summary_run_window CHECK (period_end > period_start),
    CONSTRAINT summary_run_narrative_cipher_pair CHECK (
        (narrative_nonce IS NULL AND narrative_ciphertext IS NULL AND expires_at IS NULL)
        OR (narrative_nonce IS NOT NULL
            AND narrative_ciphertext IS NOT NULL
            AND expires_at IS NOT NULL
            AND octet_length(narrative_nonce) = 12
            AND octet_length(narrative_ciphertext) > 16
            )
    )
);

CREATE INDEX summary_run_expiry_idx ON summary_run(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE assistant_insight (
    id UUID PRIMARY KEY,
    summary_run_id UUID NOT NULL REFERENCES summary_run(id) ON DELETE CASCADE,
    insight_kind TEXT NOT NULL CHECK (insight_kind IN ('URGENT_ACTION', 'REPLY_RECEIVED', 'TRANSACTION')),
    confidence NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    urgency TEXT CHECK (urgency IS NULL OR urgency IN ('HIGH', 'MEDIUM', 'LOW')),
    due_at TIMESTAMPTZ,
    content_nonce BYTEA NOT NULL CHECK (octet_length(content_nonce) = 12),
    content_ciphertext BYTEA NOT NULL CHECK (octet_length(content_ciphertext) > 16),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX assistant_insight_expiry_idx ON assistant_insight(expires_at);

CREATE TABLE assistant_insight_source (
    insight_id UUID NOT NULL REFERENCES assistant_insight(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES mail_message(id) ON DELETE CASCADE,
    PRIMARY KEY (insight_id, message_id)
);

CREATE TABLE ai_provider_configuration (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    provider_type TEXT NOT NULL CHECK (provider_type IN ('OPENAI', 'OPENAI_COMPATIBLE')),
    base_url TEXT,
    model_id TEXT NOT NULL,
    api_key_nonce BYTEA NOT NULL CHECK (octet_length(api_key_nonce) = 12),
    api_key_ciphertext BYTEA NOT NULL CHECK (octet_length(api_key_ciphertext) > 16),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_provider_endpoint CHECK (
        (provider_type = 'OPENAI' AND base_url IS NULL)
        OR (provider_type = 'OPENAI_COMPATIBLE' AND base_url IS NOT NULL)
    )
);
