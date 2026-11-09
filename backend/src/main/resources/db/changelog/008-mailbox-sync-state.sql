ALTER TABLE connected_mailbox
    ADD COLUMN sync_status TEXT;

UPDATE connected_mailbox
SET sync_status = CASE
    WHEN reauthorization_required THEN 'needs_reauthorization'
    WHEN last_sync_at IS NOT NULL THEN 'ready'
    ELSE 'pending'
END;

ALTER TABLE connected_mailbox
    ALTER COLUMN sync_status SET DEFAULT 'pending',
    ALTER COLUMN sync_status SET NOT NULL,
    ADD CONSTRAINT connected_mailbox_sync_status_check
        CHECK (sync_status IN (
            'pending',
            'syncing',
            'ready',
            'needs_reauthorization',
            'partial_failure',
            'error'
        ));
