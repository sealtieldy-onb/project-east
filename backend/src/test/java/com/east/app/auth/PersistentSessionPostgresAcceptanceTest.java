package com.east.app.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.EastApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Opt-in proof that Spring Session JDBC restores an authenticated session after an app restart.
 * Requires EAST_RUN_SESSION_POSTGRES_ACCEPTANCE=true and a disposable east_session_test_* database.
 */
class PersistentSessionPostgresAcceptanceTest {

    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THREAD_ID = UUID.fromString("20000000-0000-4000-8000-000000000003");

    @Test
    @EnabledIfEnvironmentVariable(named = "EAST_RUN_SESSION_POSTGRES_ACCEPTANCE", matches = "true")
    void authenticatedSessionSurvivesRestartAndExplicitLogoutInvalidatesIt() throws Exception {
        String databaseName = System.getenv("EAST_SESSION_TEST_DB");
        if (databaseName == null || !databaseName.matches("east_session_test_[a-z0-9_]+")) {
            throw new IllegalStateException("Set EAST_SESSION_TEST_DB to an isolated east_session_test_* database.");
        }
        String port = System.getenv().getOrDefault("EAST_DB_PORT", "15432");
        String databaseUser = System.getenv().getOrDefault("EAST_DB_USER", "east");
        String datasourceUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + databaseName;
        Path testDirectory = Files.createTempDirectory("east-session-acceptance-");
        Path keyFile = testDirectory.resolve("vault.key");
        HttpClient client = HttpClient.newHttpClient();
        String sessionId;

        try {
            try (ConfigurableApplicationContext firstContext = startContext(datasourceUrl, databaseUser, keyFile)) {
                JdbcTemplate jdbc = firstContext.getBean(JdbcTemplate.class);
                seedOwner(jdbc);
                sessionId = saveAuthenticatedSession(firstContext);

                HttpResponse<String> firstResponse = getMe(client, firstContext, sessionId);
                assertEquals(200, firstResponse.statusCode());
                assertTrue(firstResponse.body().contains("\"id\":\"" + USER_ID + "\""));
                assertCsrfCookiePolicy(firstResponse);
                assertCachedSearch(client, firstContext, sessionId);
            }

            try (ConfigurableApplicationContext restartedContext = startContext(datasourceUrl, databaseUser, keyFile)) {
                HttpResponse<String> restartedResponse = getMe(client, restartedContext, sessionId);
                assertEquals(200, restartedResponse.statusCode());
                assertTrue(restartedResponse.body().contains("\"id\":\"" + USER_ID + "\""));
                assertCachedSearch(client, restartedContext, sessionId);

                String csrfToken = cookie(restartedResponse, "EAST-XSRF-TOKEN");
                assertNotNull(csrfToken, "authenticated GET should issue the CSRF cookie");
                HttpResponse<String> logoutResponse = client.send(
                        HttpRequest.newBuilder(endpoint(restartedContext, "/api/v1/auth/logout"))
                                .header("Cookie", "EASTSESSION=" + sessionId + "; EAST-XSRF-TOKEN=" + csrfToken)
                                .header("X-EAST-XSRF-TOKEN", csrfToken)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(204, logoutResponse.statusCode());
                assertSessionCookiePolicy(logoutResponse);

                HttpResponse<String> afterLogout = getMe(client, restartedContext, sessionId);
                assertEquals(401, afterLogout.statusCode());
            }
        } finally {
            try (ConfigurableApplicationContext cleanupContext = startContext(datasourceUrl, databaseUser, keyFile)) {
                cleanupContext.getBean(JdbcTemplate.class).update("DELETE FROM app_user WHERE id = ?", USER_ID);
            } finally {
                Files.deleteIfExists(keyFile);
                Files.deleteIfExists(testDirectory);
            }
        }
    }

    private static ConfigurableApplicationContext startContext(String datasourceUrl, String databaseUser, Path keyFile) {
        return new SpringApplicationBuilder(EastApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + datasourceUrl,
                        "--spring.datasource.username=" + databaseUser,
                        "--spring.datasource.password=" + System.getenv().getOrDefault("EAST_DB_PASSWORD", ""),
                        "--spring.session.timeout=60d",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--east.vault.key-file=" + keyFile,
                        "--server.port=0",
                        "--east.gmail.sync-initial-delay-ms=600000",
                        "--east.reports.daily-initial-delay-ms=600000",
                        "--spring.main.banner-mode=off");
    }

    private static void seedOwner(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", USER_ID);
        jdbc.update(
                "INSERT INTO app_user (id, google_subject, email, display_name) VALUES (?, ?, ?, ?)",
                USER_ID,
                "synthetic-session-owner",
                "synthetic-session@example.invalid",
                "Synthetic session owner");
        jdbc.update("""
                INSERT INTO connected_mailbox (id, app_user_id, google_subject, email, display_name, account_color_index)
                VALUES (?, ?, ?, ?, ?, 0)
                """, MAILBOX_ID, USER_ID, "synthetic-cache-mailbox", "synthetic-cache@example.invalid", "Synthetic mailbox");
        jdbc.update("""
                INSERT INTO mail_thread (id, mailbox_id, gmail_thread_id, subject, last_message_at, message_count)
                VALUES (?, ?, ?, 'Synthetic cache search', CURRENT_TIMESTAMP, 1)
                """, THREAD_ID, MAILBOX_ID, "synthetic-cache-thread");
        jdbc.update("""
                INSERT INTO mail_message (id, mailbox_id, thread_id, gmail_message_id, sender_email, sender_name, sent_at)
                VALUES (?, ?, ?, ?, 'synthetic-sender@example.invalid', 'Synthetic sender', CURRENT_TIMESTAMP)
                """, UUID.fromString("20000000-0000-4000-8000-000000000004"), MAILBOX_ID, THREAD_ID,
                "synthetic-cache-message");
    }

    private static String saveAuthenticatedSession(ConfigurableApplicationContext context) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> repository = (FindByIndexNameSessionRepository<Session>)
                context.getBean(FindByIndexNameSessionRepository.class);
        assertTrue(repository.getClass().getName().contains("JdbcIndexedSessionRepository"));
        Session session = repository.createSession();
        assertEquals(Duration.ofDays(60), session.getMaxInactiveInterval());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                new EastUserPrincipal(USER_ID, "synthetic-session-owner", "synthetic-session@example.invalid", "Synthetic session owner"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        repository.save(session);
        String indexedPrincipal = context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE SESSION_ID = ?", String.class, session.getId());
        assertEquals(USER_ID.toString(), indexedPrincipal);
        return session.getId();
    }

    private static HttpResponse<String> getMe(HttpClient client, ConfigurableApplicationContext context, String sessionId)
            throws Exception {
        return get(client, context, "/api/v1/me", sessionId);
    }

    private static void assertCachedSearch(HttpClient client, ConfigurableApplicationContext context, String sessionId)
            throws Exception {
        HttpResponse<String> plainText = get(
                client, context, "/api/v1/threads?cacheOnly=true&query=cache%20search", sessionId);
        assertEquals(200, plainText.statusCode());
        assertTrue(plainText.body().contains(THREAD_ID.toString()));
        HttpResponse<String> advanced = get(
                client, context, "/api/v1/threads?cacheOnly=true&query=from%3Asynthetic", sessionId);
        assertEquals(200, advanced.statusCode());
        assertTrue(advanced.body().contains("\"items\":[]"));
    }

    private static HttpResponse<String> get(
            HttpClient client, ConfigurableApplicationContext context, String path, String sessionId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(endpoint(context, path))
                        .header("Cookie", "EASTSESSION=" + sessionId)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI endpoint(ConfigurableApplicationContext context, String path) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static void assertCsrfCookiePolicy(HttpResponse<String> response) {
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("EAST-XSRF-TOKEN="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CSRF cookie was not issued"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }

    private static void assertSessionCookiePolicy(HttpResponse<String> response) {
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("EASTSESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("logout should expire the session cookie"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Max-Age=0"));
    }

    private static String cookie(HttpResponse<?> response, String name) {
        String prefix = name + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }
}
