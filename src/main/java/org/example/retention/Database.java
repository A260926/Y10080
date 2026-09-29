package org.example.retention;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

public final class Database implements AutoCloseable {
    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            Database database = new Database(connection);
            database.execute("PRAGMA foreign_keys = ON");
            database.execute("PRAGMA busy_timeout = 5000");
            database.execute("PRAGMA journal_mode = WAL");
            database.migrate();
            return database;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot open SQLite database " + file, exception);
        }
    }

    public Connection connection() {
        return connection;
    }

    public <T> T transaction(Supplier<T> action) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = action.get();
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Transaction failed", exception);
        }
    }

    public void transaction(Runnable action) {
        transaction(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private void migrate() throws SQLException {
        execute("""
            CREATE TABLE IF NOT EXISTS app_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS resources (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_accessed_at TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                content_summary TEXT NOT NULL,
                current_version INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                created_at_record TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS resource_versions (
                id TEXT PRIMARY KEY,
                resource_id TEXT NOT NULL REFERENCES resources(id),
                version INTEGER NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                created_at TEXT NOT NULL,
                last_accessed_at TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                content_summary TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                snapshot_json TEXT NOT NULL,
                recorded_at TEXT NOT NULL,
                UNIQUE(resource_id, version)
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS policy_versions (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL UNIQUE,
                note TEXT NOT NULL,
                snapshot_json TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS legal_holds (
                id TEXT PRIMARY KEY,
                resource_id TEXT NOT NULL REFERENCES resources(id),
                event_type TEXT NOT NULL CHECK(event_type IN ('HOLD','RELEASE')),
                effective_from TEXT NOT NULL,
                effective_to TEXT,
                target_hold_id TEXT,
                reason TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS plans (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL,
                frozen_at TEXT NOT NULL,
                policy_version_id TEXT NOT NULL REFERENCES policy_versions(id),
                policy_version_number INTEGER NOT NULL,
                policy_fingerprint TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('FROZEN','APPROVED','COMPLETED')),
                note TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS plan_items (
                id TEXT PRIMARY KEY,
                plan_id TEXT NOT NULL REFERENCES plans(id),
                seq INTEGER NOT NULL,
                resource_id TEXT NOT NULL REFERENCES resources(id),
                resource_version_id TEXT NOT NULL REFERENCES resource_versions(id),
                resource_fingerprint TEXT NOT NULL,
                initial_disposition TEXT NOT NULL CHECK(initial_disposition IN ('DELETABLE','RETAIN_POLICY','RETAIN_HOLD','RETAIN_NO_RULE')),
                status TEXT NOT NULL CHECK(status IN ('PENDING','RETAINED','BLOCKED','DELETED','UNKNOWN')),
                explanation_json TEXT NOT NULL,
                blocked_reason TEXT,
                UNIQUE(plan_id, seq)
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS executions (
                id TEXT PRIMARY KEY,
                plan_id TEXT NOT NULL UNIQUE REFERENCES plans(id),
                generation INTEGER NOT NULL CHECK(generation = 1),
                status TEXT NOT NULL CHECK(status IN ('PENDING','RUNNING','INTERRUPTED','COMPLETED','FAILED')),
                operator TEXT NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE,
                expected_plan_version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS execution_receipts (
                id TEXT PRIMARY KEY,
                execution_id TEXT NOT NULL REFERENCES executions(id),
                plan_item_id TEXT NOT NULL REFERENCES plan_items(id),
                seq INTEGER NOT NULL,
                item_token TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL CHECK(status IN ('PROCESSING','DELETED','BLOCKED','RETAINED','NOT_CONFIRMED')),
                reason TEXT,
                request_token TEXT NOT NULL,
                started_at TEXT NOT NULL,
                completed_at TEXT
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS execution_events (
                id TEXT PRIMARY KEY,
                execution_id TEXT NOT NULL REFERENCES executions(id),
                plan_item_id TEXT,
                seq INTEGER,
                event_type TEXT NOT NULL,
                detail_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS delete_ledger (
                id TEXT PRIMARY KEY,
                resource_id TEXT NOT NULL UNIQUE REFERENCES resources(id),
                plan_item_id TEXT NOT NULL REFERENCES plan_items(id),
                execution_id TEXT NOT NULL REFERENCES executions(id),
                receipt_id TEXT NOT NULL REFERENCES execution_receipts(id),
                resource_fingerprint TEXT NOT NULL,
                deleted_at TEXT NOT NULL
            )
            """);
        execute("""
            CREATE TRIGGER IF NOT EXISTS legal_holds_no_update
            BEFORE UPDATE ON legal_holds
            BEGIN
                SELECT RAISE(ABORT, 'legal holds are append-only');
            END
            """);
        execute("""
            CREATE TRIGGER IF NOT EXISTS legal_holds_no_delete
            BEFORE DELETE ON legal_holds
            BEGIN
                SELECT RAISE(ABORT, 'legal holds are append-only');
            END
            """);
        execute("""
            CREATE INDEX IF NOT EXISTS idx_resource_versions_resource ON resource_versions(resource_id)
            """);
        execute("""
            CREATE INDEX IF NOT EXISTS idx_holds_resource ON legal_holds(resource_id)
            """);
        execute("""
            CREATE INDEX IF NOT EXISTS idx_plan_items_resource ON plan_items(resource_id)
            """);
        execute("""
            CREATE INDEX IF NOT EXISTS idx_receipts_execution ON execution_receipts(execution_id)
            """);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
