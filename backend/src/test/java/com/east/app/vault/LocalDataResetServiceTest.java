package com.east.app.vault;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LocalDataResetServiceTest {

    @Test
    void resetTruncatesPersistentSessionsAlongsideApplicationData() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

        new LocalDataResetService(jdbc).resetApplicationData();

        assertTrue(jdbc.executedSql.contains("spring_session_attributes"));
        assertTrue(jdbc.executedSql.contains("spring_session"));
        assertTrue(jdbc.executedSql.contains("RESTART IDENTITY CASCADE"));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String executedSql;

        @Override
        public void execute(String sql) {
            executedSql = sql;
        }
    }
}
