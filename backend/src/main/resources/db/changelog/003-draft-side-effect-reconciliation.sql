--liquibase formatted sql

--changeset east:003-draft-side-effect-reconciliation
ALTER TABLE mail_draft
    DROP CONSTRAINT mail_draft_draft_state_check,
    DROP CONSTRAINT mail_draft_saved_state;

ALTER TABLE mail_draft
    ADD COLUMN side_effect_operation TEXT,
    ADD COLUMN side_effect_started_at TIMESTAMPTZ;

ALTER TABLE mail_draft
    ADD CONSTRAINT mail_draft_draft_state_check
        CHECK (draft_state IN ('LOCAL', 'SAVED_TO_GMAIL', 'SAVE_PENDING', 'SEND_PENDING')),
    ADD CONSTRAINT mail_draft_side_effect_state CHECK (
        (draft_state = 'LOCAL'
            AND gmail_draft_id IS NULL
            AND saved_at IS NULL
            AND payload_nonce IS NOT NULL
            AND payload_ciphertext IS NOT NULL
            AND side_effect_operation IS NULL
            AND side_effect_started_at IS NULL)
        OR (draft_state = 'SAVED_TO_GMAIL'
            AND gmail_draft_id IS NOT NULL
            AND saved_at IS NOT NULL
            AND payload_nonce IS NULL
            AND payload_ciphertext IS NULL
            AND side_effect_operation IS NULL
            AND side_effect_started_at IS NULL)
        OR (draft_state = 'SAVE_PENDING'
            AND gmail_draft_id IS NULL
            AND saved_at IS NULL
            AND payload_nonce IS NOT NULL
            AND payload_ciphertext IS NOT NULL
            AND side_effect_operation IS NOT NULL
            AND side_effect_operation = 'SAVE_TO_GMAIL'
            AND side_effect_started_at IS NOT NULL)
        OR (draft_state = 'SEND_PENDING'
            AND side_effect_operation IS NOT NULL
            AND side_effect_operation = 'SEND'
            AND side_effect_started_at IS NOT NULL
            AND ((gmail_draft_id IS NULL
                    AND saved_at IS NULL
                    AND payload_nonce IS NOT NULL
                    AND payload_ciphertext IS NOT NULL)
                OR (gmail_draft_id IS NOT NULL
                    AND saved_at IS NOT NULL
                    AND payload_nonce IS NULL
                    AND payload_ciphertext IS NULL)))
    );
