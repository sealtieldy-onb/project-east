package com.east.app.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Holds a PostgreSQL session advisory lock for the lifetime of the local East backend. */
@Component
public final class EastSingleInstanceGuard implements SmartLifecycle {

    private static final int EAST_LOCK_NAMESPACE = 0x45415354; // EAST
    private static final int LOCAL_DATABASE_LOCK_KEY = 0x4c4f434c; // LOCL
    private static final String LOCK_SQL = "SELECT pg_try_advisory_lock(?, ?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?, ?)";
    private static final String ALREADY_RUNNING_MESSAGE =
            "East is already running against this PostgreSQL database. Stop the other East process before starting another one.";
    private static final String DATABASE_UNAVAILABLE_MESSAGE =
            "East could not acquire its PostgreSQL single-instance lock. Check that the local database is available, then restart East.";

    private final DataSource dataSource;
    private volatile Connection lockConnection;
    private volatile boolean running;

    public EastSingleInstanceGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        Connection candidate = null;
        boolean lockAcquired = false;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            try (PreparedStatement statement = candidate.prepareStatement(LOCK_SQL)) {
                statement.setInt(1, EAST_LOCK_NAMESPACE);
                statement.setInt(2, LOCAL_DATABASE_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    lockAcquired = result.next() && result.getBoolean(1);
                }
            }
            if (!lockAcquired) {
                throw new IllegalStateException(ALREADY_RUNNING_MESSAGE);
            }

            lockConnection = candidate;
            candidate = null;
            running = true;
        } catch (SQLException exception) {
            releaseCandidate(candidate, lockAcquired);
            throw new IllegalStateException(DATABASE_UNAVAILABLE_MESSAGE);
        } catch (RuntimeException exception) {
            releaseCandidate(candidate, lockAcquired);
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        Connection connection = lockConnection;
        lockConnection = null;
        releaseCandidate(connection, true);
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** Start before the embedded web server lifecycle so a second process never serves requests. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    private static void releaseCandidate(Connection connection, boolean unlock) {
        if (connection == null) {
            return;
        }
        if (unlock) {
            try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
                statement.setInt(1, EAST_LOCK_NAMESPACE);
                statement.setInt(2, LOCAL_DATABASE_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        result.getBoolean(1);
                    }
                }
            } catch (SQLException ignored) {
                // Closing the session releases its advisory lock if explicit unlock is unavailable.
            }
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Do not log database connection details during shutdown.
        }
    }
}
