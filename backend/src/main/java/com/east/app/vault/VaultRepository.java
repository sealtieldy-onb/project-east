package com.east.app.vault;

import java.util.List;
import java.util.Optional;
import java.util.Arrays;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VaultRepository {

    private static final String SELECT_STATE = """
            SELECT salt, verification_marker
            FROM vault_state
            WHERE singleton_id = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public VaultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<VaultRecord> find() {
        List<VaultRecord> rows = jdbcTemplate.query(
                SELECT_STATE,
                (resultSet, rowNumber) -> {
                    byte[] salt = resultSet.getBytes("salt");
                    byte[] marker = resultSet.getBytes("verification_marker");
                    try {
                        return new VaultRecord(salt, marker);
                    } finally {
                        if (salt != null) Arrays.fill(salt, (byte) 0);
                        if (marker != null) Arrays.fill(marker, (byte) 0);
                    }
                });
        return rows.stream().findFirst();
    }

    public boolean isInitialized() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM vault_state WHERE singleton_id = 1)",
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public void create(byte[] salt, byte[] encryptedVerificationMarker) throws DuplicateKeyException {
        jdbcTemplate.update(
                "INSERT INTO vault_state (singleton_id, salt, verification_marker) VALUES (1, ?, ?)",
                salt,
                encryptedVerificationMarker);
    }
}
