package com.zkry.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Small idempotent migrations for existing local databases that predate schema.sql changes.
 */
@Component
public class DatabaseSchemaMigration implements SmartInitializingSingleton {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            migrate();
        } catch (Exception ex) {
            throw new IllegalStateException("数据库迁移失败", ex);
        }
    }

    private void migrate() throws Exception {
        List<String> columns = jdbcTemplate.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'trip_plan'
              AND column_name = 'detail_json'
            """,
            String.class
        );
        if (columns.isEmpty()) {
            jdbcTemplate.execute("ALTER TABLE trip_plan ADD COLUMN detail_json LONGTEXT NULL AFTER visibility");
        }
        ensureColumn("trip_plan", "current_snapshot_json", "LONGTEXT NULL AFTER detail_json");
        List<String> versionColumns = jdbcTemplate.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'trip_plan'
              AND column_name = 'version'
            """,
            String.class
        );
        if (versionColumns.isEmpty()) {
            jdbcTemplate.execute("ALTER TABLE trip_plan ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER visibility");
        }
        jdbcTemplate.update("UPDATE trip_plan SET version = 1 WHERE version = 0");
        applyRealtimeAsyncMigration();
        applyTripSnapshotMigration();
        applyTaskIdempotencyMigration();
        applyMemoryMigration();
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS auth_session (
                id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL, user_id BIGINT NOT NULL,
                expires_at DATETIME(6) NOT NULL, create_time DATETIME(6) NOT NULL,
                update_time DATETIME(6) NOT NULL, deleted TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (id), UNIQUE KEY uk_auth_session_token (token_hash),
                KEY idx_auth_session_user_expiry (user_id, expires_at, deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    /**
     * MySQL does not support {@code ADD COLUMN IF NOT EXISTS}. Query metadata before each
     * schema change so a database created by an older release can be upgraded safely.
     */
    private void applyTripSnapshotMigration() {
        ensureMigrationTable();
        Integer applied = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Integer.class, "V20260805__trip_snapshot");
        if (applied != null && applied > 0) return;
        ensureColumn("trip_plan_version", "snapshot_json", "LONGTEXT NULL AFTER result_json");
        ensureColumn("trip_plan_record", "snapshot_json", "LONGTEXT NULL AFTER result_json");
        jdbcTemplate.update("INSERT INTO schema_migration(version, applied_at) VALUES (?, NOW(6))", "V20260805__trip_snapshot");
    }

    private void applyTaskIdempotencyMigration() {
        ensureMigrationTable();
        Integer applied = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Integer.class, "V20260806__task_idempotency");
        if (applied != null && applied > 0) return;
        ensureColumn("trip_task", "plan_id", "VARCHAR(64) NULL AFTER task_type");
        ensureColumn("trip_task", "idempotency_key", "VARCHAR(128) NULL AFTER plan_id");
        ensureIndex("trip_task", "uk_trip_task_idempotency", "CREATE UNIQUE INDEX uk_trip_task_idempotency ON trip_task (owner_id, task_type, idempotency_key)");
        ensureIndex("trip_task", "idx_trip_task_plan_active", "CREATE INDEX idx_trip_task_plan_active ON trip_task (plan_id, task_type, status, deleted)");
        jdbcTemplate.update("INSERT INTO schema_migration(version, applied_at) VALUES (?, NOW(6))", "V20260806__task_idempotency");
    }

    private void applyMemoryMigration() {
        ensureMigrationTable();
        Integer applied = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Integer.class, "V20260824__four_layer_memory");
        if (applied != null && applied > 0) return;
        ensureColumn("trip_task_checkpoint", "schema_version", "INT NOT NULL DEFAULT 1 AFTER payload_hash");
        ensureColumn("trip_task_checkpoint", "retention_class", "VARCHAR(32) NOT NULL DEFAULT 'RECOVERY_CHECKPOINT' AFTER schema_version");
        ensureColumn("trip_task_checkpoint", "expires_at", "DATETIME(6) NULL AFTER retention_class");
        ensureIndex("trip_task_checkpoint", "idx_trip_task_checkpoint_expiry",
            "CREATE INDEX idx_trip_task_checkpoint_expiry ON trip_task_checkpoint (retention_class, expires_at, deleted)");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_memory_fact (
                id BIGINT NOT NULL, user_id BIGINT NOT NULL, memory_type VARCHAR(32) NOT NULL,
                memory_key VARCHAR(100) NOT NULL, memory_value_json LONGTEXT NOT NULL,
                scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL', scope_value VARCHAR(128) NOT NULL DEFAULT '',
                source VARCHAR(16) NOT NULL, confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
                hard_constraint TINYINT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL,
                memory_fingerprint VARCHAR(64) NOT NULL, first_seen_at DATETIME(6) NOT NULL,
                last_observed_at DATETIME(6) NOT NULL, last_confirmed_at DATETIME(6) NULL,
                expires_at DATETIME(6) NULL, superseded_by BIGINT NULL, evidence_refs_json TEXT NULL,
                create_time DATETIME(6) NOT NULL, update_time DATETIME(6) NOT NULL, deleted TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (id), UNIQUE KEY uk_user_memory_fingerprint (user_id, memory_fingerprint),
                KEY idx_user_memory_active (user_id, status, expires_at, deleted),
                KEY idx_user_memory_type (user_id, memory_type, memory_key, status, deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS trip_memory_event (
                id BIGINT NOT NULL, event_id VARCHAR(160) NOT NULL, user_id BIGINT NOT NULL,
                plan_id VARCHAR(64) NOT NULL, plan_version INT NOT NULL, task_id VARCHAR(64) NULL,
                event_type VARCHAR(40) NOT NULL, target_type VARCHAR(40) NOT NULL DEFAULT '', target_ref VARCHAR(255) NOT NULL DEFAULT '',
                city VARCHAR(80) NOT NULL DEFAULT '', day_number INT NULL, before_json LONGTEXT NULL, after_json LONGTEXT NULL,
                reason_code VARCHAR(64) NOT NULL DEFAULT '', reason_text VARCHAR(1000) NOT NULL DEFAULT '',
                source VARCHAR(32) NOT NULL, evidence_refs_json TEXT NULL, occurred_at DATETIME(6) NOT NULL,
                consolidation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', processed_at DATETIME(6) NULL,
                create_time DATETIME(6) NOT NULL, update_time DATETIME(6) NOT NULL, deleted TINYINT NOT NULL DEFAULT 0,
                PRIMARY KEY (id), UNIQUE KEY uk_trip_memory_event_id (event_id),
                KEY idx_trip_memory_event_user (user_id, occurred_at, deleted),
                KEY idx_trip_memory_event_plan (plan_id, plan_version, deleted),
                KEY idx_trip_memory_event_consolidation (user_id, consolidation_status, occurred_at, deleted)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.update("INSERT INTO schema_migration(version, applied_at) VALUES (?, NOW(6))", "V20260824__four_layer_memory");
    }

    private void ensureColumn(String table, String name, String definition) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
            Integer.class, table, name);
        if (count == null || count == 0) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition);
    }

    private void ensureIndex(String table, String name, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
            Integer.class, table, name);
        if (count == null || count == 0) jdbcTemplate.execute(ddl);
    }

    private void applyRealtimeAsyncMigration() throws Exception {
        ensureMigrationTable();
        applyMigration("V20260727__realtime_async", "migrations/V20260727__realtime_async.sql");
    }

    private void applyMigration(String version, String resourcePath) throws Exception {
        ensureMigrationTable();
        Integer alreadyApplied = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version = ?",
            Integer.class,
            version
        );
        if (alreadyApplied != null && alreadyApplied > 0) return;

        ClassPathResource migration = new ClassPathResource(resourcePath);
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        for (String statement : sql.split(";\\s*(?:\\R|$)")) {
            if (!statement.isBlank()) jdbcTemplate.execute(statement.trim());
        }
        jdbcTemplate.update(
            "INSERT INTO schema_migration(version, applied_at) VALUES (?, NOW(6))",
            version
        );
    }

    private void ensureMigrationTable() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migration (
                version VARCHAR(80) NOT NULL PRIMARY KEY,
                applied_at DATETIME(6) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """
        );
    }
}
