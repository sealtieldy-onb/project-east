--liquibase formatted sql

--changeset east:009-phase3-report-scope-and-scheduling
ALTER TABLE summary_run
    ADD COLUMN run_purpose TEXT NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN coverage_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN eligible_message_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN selected_message_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN additional_message_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN read_expansion_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN provider_type TEXT,
    ADD COLUMN provider_model_id TEXT,
    ADD COLUMN provider_config_version TEXT,
    ADD COLUMN prompt_version TEXT,
    ADD COLUMN ruleset_version TEXT,
    ADD COLUMN scheduled_report_date DATE,
    ADD CONSTRAINT summary_run_purpose_check
        CHECK (run_purpose IN ('FIRST_LINK', 'MANUAL', 'DAILY', 'CATCH_UP', 'LEGACY')),
    ADD CONSTRAINT summary_run_coverage_status_check
        CHECK (coverage_status IN ('COMPLETE', 'PARTIAL', 'UNKNOWN')),
    ADD CONSTRAINT summary_run_message_counts_check
        CHECK (eligible_message_count >= 0
            AND selected_message_count >= 0
            AND additional_message_count >= 0
            AND selected_message_count <= eligible_message_count
            AND additional_message_count <= eligible_message_count),
    ADD CONSTRAINT summary_run_provider_type_check
        CHECK (provider_type IS NULL OR provider_type IN ('OPENAI', 'OPENAI_COMPATIBLE')),
    ADD CONSTRAINT summary_run_provider_model_id_check
        CHECK (provider_model_id IS NULL OR length(provider_model_id) BETWEEN 1 AND 256),
    ADD CONSTRAINT summary_run_provider_config_version_check
        CHECK (provider_config_version IS NULL OR length(provider_config_version) BETWEEN 1 AND 128),
    ADD CONSTRAINT summary_run_prompt_version_check
        CHECK (prompt_version IS NULL OR length(prompt_version) BETWEEN 1 AND 128),
    ADD CONSTRAINT summary_run_ruleset_version_check
        CHECK (ruleset_version IS NULL OR length(ruleset_version) BETWEEN 1 AND 128),
    ADD CONSTRAINT summary_run_scheduled_date_purpose_check
        CHECK ((run_purpose IN ('DAILY', 'CATCH_UP') AND scheduled_report_date IS NOT NULL)
            OR (run_purpose IN ('FIRST_LINK', 'MANUAL', 'LEGACY') AND scheduled_report_date IS NULL));

CREATE UNIQUE INDEX summary_run_scheduled_report_date_unique
    ON summary_run (app_user_id, scheduled_report_date)
    WHERE scheduled_report_date IS NOT NULL;

CREATE INDEX summary_run_owner_period_idx
    ON summary_run (app_user_id, period_end DESC);

ALTER TABLE summary_schedule
    ADD COLUMN provider_disclosure_version TEXT,
    ADD COLUMN last_attempted_report_date DATE,
    ADD COLUMN last_successful_report_date DATE,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_success_at TIMESTAMPTZ,
    ADD CONSTRAINT summary_schedule_disclosure_version_check
        CHECK (provider_disclosure_version IS NULL
            OR length(provider_disclosure_version) BETWEEN 1 AND 128),
    ADD CONSTRAINT summary_schedule_success_date_check
        CHECK (last_successful_report_date IS NULL
            OR (last_attempted_report_date IS NOT NULL
                AND last_successful_report_date <= last_attempted_report_date)),
    ADD CONSTRAINT summary_schedule_success_time_check
        CHECK (last_success_at IS NULL OR last_attempt_at IS NOT NULL);

CREATE TABLE summary_run_mailbox (
    summary_run_id UUID NOT NULL,
    app_user_id UUID NOT NULL,
    mailbox_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (summary_run_id, app_user_id, mailbox_id),
    UNIQUE (summary_run_id, mailbox_id),
    FOREIGN KEY (summary_run_id, app_user_id)
        REFERENCES summary_run (id, app_user_id) ON DELETE CASCADE,
    FOREIGN KEY (mailbox_id, app_user_id)
        REFERENCES connected_mailbox (id, app_user_id) ON DELETE CASCADE
);

CREATE INDEX summary_run_mailbox_owner_idx
    ON summary_run_mailbox (app_user_id, mailbox_id, summary_run_id);

ALTER TABLE assistant_insight
    ADD COLUMN app_user_id UUID;

UPDATE assistant_insight AS insight
SET app_user_id = report.app_user_id
FROM summary_run AS report
WHERE report.id = insight.summary_run_id;

ALTER TABLE assistant_insight
    ALTER COLUMN app_user_id SET NOT NULL,
    ADD CONSTRAINT assistant_insight_id_run_owner_unique
        UNIQUE (id, summary_run_id, app_user_id),
    ADD CONSTRAINT assistant_insight_run_owner_fk
        FOREIGN KEY (summary_run_id, app_user_id)
        REFERENCES summary_run (id, app_user_id) ON DELETE CASCADE,
    DROP CONSTRAINT assistant_insight_insight_kind_check,
    ADD CONSTRAINT assistant_insight_insight_kind_check
        CHECK (insight_kind IN ('URGENT_ACTION', 'AWAITING_REPLY', 'REPLY_RECEIVED', 'TRANSACTION')),
    ADD COLUMN insight_status TEXT NOT NULL DEFAULT 'OPEN',
    ADD CONSTRAINT assistant_insight_status_check
        CHECK (insight_status IN ('OPEN', 'DISMISSED', 'HANDLED'));

ALTER TABLE assistant_insight_source
    ADD COLUMN summary_run_id UUID,
    ADD COLUMN app_user_id UUID,
    ADD COLUMN mailbox_id UUID;

UPDATE assistant_insight_source AS source
SET summary_run_id = insight.summary_run_id,
    app_user_id = insight.app_user_id,
    mailbox_id = message.mailbox_id
FROM assistant_insight AS insight,
     mail_message AS message
WHERE insight.id = source.insight_id
  AND message.id = source.message_id;

-- Preserve cited evidence as complete run provenance for legacy report rows.
INSERT INTO summary_run_source (summary_run_id, app_user_id, mailbox_id, message_id)
SELECT DISTINCT source.summary_run_id, source.app_user_id, source.mailbox_id, source.message_id
FROM assistant_insight_source AS source
WHERE source.summary_run_id IS NOT NULL
  AND source.app_user_id IS NOT NULL
  AND source.mailbox_id IS NOT NULL
ON CONFLICT (summary_run_id, message_id) DO NOTHING;

INSERT INTO summary_run_mailbox (summary_run_id, app_user_id, mailbox_id)
SELECT DISTINCT source.summary_run_id, source.app_user_id, source.mailbox_id
FROM summary_run_source AS source
ON CONFLICT (summary_run_id, app_user_id, mailbox_id) DO NOTHING;

ALTER TABLE summary_run_source
    ADD CONSTRAINT summary_run_source_run_owner_mailbox_message_unique
        UNIQUE (summary_run_id, app_user_id, mailbox_id, message_id),
    ADD CONSTRAINT summary_run_source_declared_mailbox_fk
        FOREIGN KEY (summary_run_id, app_user_id, mailbox_id)
        REFERENCES summary_run_mailbox (summary_run_id, app_user_id, mailbox_id) ON DELETE CASCADE;

ALTER TABLE assistant_insight_source
    ALTER COLUMN summary_run_id SET NOT NULL,
    ALTER COLUMN app_user_id SET NOT NULL,
    ALTER COLUMN mailbox_id SET NOT NULL,
    ADD CONSTRAINT assistant_insight_source_insight_run_owner_fk
        FOREIGN KEY (insight_id, summary_run_id, app_user_id)
        REFERENCES assistant_insight (id, summary_run_id, app_user_id) ON DELETE CASCADE,
    ADD CONSTRAINT assistant_insight_source_declared_mailbox_fk
        FOREIGN KEY (summary_run_id, app_user_id, mailbox_id)
        REFERENCES summary_run_mailbox (summary_run_id, app_user_id, mailbox_id) ON DELETE CASCADE,
    ADD CONSTRAINT assistant_insight_source_run_message_fk
        FOREIGN KEY (summary_run_id, app_user_id, mailbox_id, message_id)
        REFERENCES summary_run_source (summary_run_id, app_user_id, mailbox_id, message_id) ON DELETE CASCADE;

CREATE INDEX assistant_insight_source_run_mailbox_idx
    ON assistant_insight_source (summary_run_id, app_user_id, mailbox_id, message_id);

UPDATE summary_run AS report
SET selected_message_count = source_counts.source_count,
    eligible_message_count = GREATEST(report.eligible_message_count, source_counts.source_count)
FROM (
    SELECT summary_run_id, COUNT(*)::INTEGER AS source_count
    FROM summary_run_source
    GROUP BY summary_run_id
) AS source_counts
WHERE report.id = source_counts.summary_run_id
  AND report.run_purpose = 'LEGACY';
