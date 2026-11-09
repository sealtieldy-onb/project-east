package com.east.app.vault;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalDataResetService {

    private static final String RESET_APPLICATION_TABLES = """
            TRUNCATE TABLE
                spring_session_attributes,
                spring_session,
                vault_state,
                ai_provider_configuration,
                assistant_insight_source,
                assistant_insight,
                summary_run,
                summary_schedule,
                mail_draft_attachment,
                mail_draft,
                mail_message_label,
                mail_label,
                mail_attachment,
                mail_message,
                mail_thread,
                connected_mailbox,
                app_user
            RESTART IDENTITY CASCADE
            """;

    private final JdbcTemplate jdbcTemplate;

    public LocalDataResetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Deletes only East-owned tables; Liquibase's migration history is preserved. */
    @Transactional
    public void resetApplicationData() {
        jdbcTemplate.execute(RESET_APPLICATION_TABLES);
    }
}
