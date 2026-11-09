package com.east.app.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuthRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public AppUser findOrCreateAnchoredUser(GoogleIdentity identity) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, singleton_id, google_subject, email, display_name)
                VALUES (?, 1, ?, ?, ?)
                ON CONFLICT (singleton_id) DO NOTHING
                """,
                UUID.randomUUID(),
                identity.subject(),
                identity.email(),
                identity.displayName());

        Optional<AppUser> existing = findSingleton();
        if (existing.isEmpty()) {
            throw new IllegalStateException("Unable to initialize the local app user.");
        }

        AppUser appUser = existing.get();
        if (!appUser.googleSubject().equals(identity.subject())) {
            throw new DifferentGoogleIdentityException();
        }

        jdbcTemplate.update("""
                UPDATE app_user
                SET email = ?, display_name = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                identity.email(),
                identity.displayName(),
                appUser.id());
        return findById(appUser.id()).orElseThrow();
    }

    public Optional<AppUser> findById(UUID id) {
        List<AppUser> rows = jdbcTemplate.query(
                """
                SELECT id, google_subject, email, display_name, created_at, updated_at
                FROM app_user
                WHERE id = ?
                """,
                this::mapAppUser,
                id);
        return rows.stream().findFirst();
    }

    public Optional<AppUser> findSingleton() {
        List<AppUser> rows = jdbcTemplate.query(
                """
                SELECT id, google_subject, email, display_name, created_at, updated_at
                FROM app_user
                WHERE singleton_id = 1
                """,
                this::mapAppUser);
        return rows.stream().findFirst();
    }

    private AppUser mapAppUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AppUser(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("google_subject"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    public static final class DifferentGoogleIdentityException extends RuntimeException {
        public DifferentGoogleIdentityException() {
            super("This local installation is already linked to a different Google sign-in identity. Reset local data to change it.");
        }
    }
}
