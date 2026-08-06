package com.example.nodecontrol.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, idempotent compatibility migration for databases created by an older
 * Control Plane version. Full schema migration tooling is tracked separately;
 * this guard keeps older installations usable while that tooling is pending.
 */
@Component
@ConditionalOnProperty(
        prefix = "control-plane.schema",
        name = "compatibility-migration-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchemaCompatibilityMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaCompatibilityMigration.class);
    private static final String ALLOCATION_TABLE = "residential_allocations";
    private static final String SOURCE_PORT_COLUMN = "proxy_source_port";
    private static final String PROTOCOLS_ALL_COLUMN = "protocols_all_cipher";
    private static final String NODE_TABLE = "managed_nodes";
    private static final String SOCKS_INBOUND_PORT_COLUMN = "socks_inbound_port";
    private static final String CONTROL_USER_TABLE = "control_users";
    private static final String CONTROL_USER_ROLE_COLUMN = "role";
    private static final String AUDIT_TABLE = "control_audit_logs";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public SchemaCompatibilityMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || !productName.toLowerCase(java.util.Locale.ROOT).contains("mysql")) {
                return;
            }

            DatabaseMetaData metadata = connection.getMetaData();
            ensureControlUserRole(metadata);
            ensureAuditTable(metadata);
            if (!tableExists(metadata, ALLOCATION_TABLE)) return;

            dropLegacyGlobalUserIdUniqueIndex(metadata);

            // MySQL does not support ADD COLUMN IF NOT EXISTS. The metadata check
            // avoids a needless ALTER; the duplicate-column guard below handles
            // two instances starting at the same time.
            if (!columnExists(metadata, ALLOCATION_TABLE, SOURCE_PORT_COLUMN)) {
                jdbcTemplate.execute("ALTER TABLE residential_allocations "
                        + "ADD COLUMN proxy_source_port INT NULL "
                        + "AFTER proxy_source_ip");
                log.info("Added missing compatibility column {}.{}", ALLOCATION_TABLE, SOURCE_PORT_COLUMN);
            }
            if (!columnExists(metadata, ALLOCATION_TABLE, PROTOCOLS_ALL_COLUMN)) {
                jdbcTemplate.execute("ALTER TABLE residential_allocations "
                        + "ADD COLUMN protocols_all_cipher LONGTEXT NULL");
                log.info("Added missing compatibility column {}.{}", ALLOCATION_TABLE, PROTOCOLS_ALL_COLUMN);
            }
            if (tableExists(metadata, NODE_TABLE)
                    && !columnExists(metadata, NODE_TABLE, SOCKS_INBOUND_PORT_COLUMN)) {
                jdbcTemplate.execute("ALTER TABLE managed_nodes "
                        + "ADD COLUMN socks_inbound_port INT NULL");
                log.info("Added missing compatibility column {}.{}", NODE_TABLE, SOCKS_INBOUND_PORT_COLUMN);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "无法检查 Control Plane 数据库结构，请确认数据库账号具有读取元数据和 ALTER TABLE 权限",
                    exception);
        } catch (DataAccessException exception) {
            if (isDuplicateColumn(exception)) {
                log.debug("Compatibility column {}.{} was added by another instance", ALLOCATION_TABLE,
                        SOURCE_PORT_COLUMN);
                return;
            }
            if (isMissingIndex(exception)) {
                log.debug("Legacy user-id index was already removed by another instance");
                return;
            }
            throw new IllegalStateException(
                    "无法补齐 Control Plane 数据库兼容字段",
                    exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "无法补齐 Control Plane 数据库字段 residential_allocations.proxy_source_port",
                    exception);
        }
    }

    private void ensureControlUserRole(DatabaseMetaData metadata) {
        try {
            if (tableExists(metadata, CONTROL_USER_TABLE)
                    && !columnExists(metadata, CONTROL_USER_TABLE, CONTROL_USER_ROLE_COLUMN)) {
                jdbcTemplate.execute("ALTER TABLE control_users ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'ADMIN'");
                log.info("Added compatibility column {}.{}", CONTROL_USER_TABLE, CONTROL_USER_ROLE_COLUMN);
            }
        } catch (DataAccessException exception) {
            if (!isDuplicateColumn(exception)) throw exception;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法检查 control_users.role 字段", exception);
        }
    }

    private void ensureAuditTable(DatabaseMetaData metadata) throws SQLException {
        if (tableExists(metadata, AUDIT_TABLE)) return;
        jdbcTemplate.execute("CREATE TABLE control_audit_logs ("
                + "id BINARY(16) NOT NULL,"
                + "event_type VARCHAR(64) NOT NULL,"
                + "actor_user_id BINARY(16) NULL,"
                + "actor_username VARCHAR(64) NULL,"
                + "target_type VARCHAR(64) NULL,"
                + "target_id VARCHAR(128) NULL,"
                + "summary VARCHAR(500) NOT NULL,"
                + "created_at TIMESTAMP(6) NOT NULL,"
                + "PRIMARY KEY (id), INDEX idx_control_audit_created_at (created_at)"
                + ")");
        log.info("Created compatibility table {}", AUDIT_TABLE);
    }

    private boolean tableExists(DatabaseMetaData metadata, String tableName) throws SQLException {
        return exists(metadata.getTables(null, null, tableName, new String[]{"TABLE"}))
                || exists(metadata.getTables(null, null, tableName.toUpperCase(java.util.Locale.ROOT), new String[]{"TABLE"}));
    }

    private boolean columnExists(DatabaseMetaData metadata, String tableName, String columnName) throws SQLException {
        return exists(metadata.getColumns(null, null, tableName, columnName))
                || exists(metadata.getColumns(null, null, tableName.toUpperCase(java.util.Locale.ROOT), columnName.toUpperCase(java.util.Locale.ROOT)));
    }

    private boolean exists(ResultSet resultSet) throws SQLException {
        if (resultSet == null) {
            return false;
        }
        try (resultSet) {
            return resultSet.next();
        }
    }

    private boolean isDuplicateColumn(DataAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1060
                    || "42S21".equals(sqlException.getSQLState()))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean isMissingIndex(DataAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1091) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Older versions declared control_user_id globally unique.  That index is
     * incompatible with node-local user ids, so remove only a single-column
     * unique index on that column.  The migration is metadata-driven because
     * Hibernate generated different index names on different installations.
     */
    private void dropLegacyGlobalUserIdUniqueIndex(DatabaseMetaData metadata) throws SQLException {
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        ResultSet indexMetadata = metadata.getIndexInfo(null, null, ALLOCATION_TABLE, false, false);
        if (indexMetadata == null) {
            return;
        }
        try (ResultSet indexes = indexMetadata) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name == null || column == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                uniqueByIndex.put(name, !indexes.getBoolean("NON_UNIQUE"));
            }
        }
        for (Map.Entry<String, List<String>> entry : columnsByIndex.entrySet()) {
            String indexName = entry.getKey();
            if (!uniqueByIndex.getOrDefault(indexName, false)
                    || "PRIMARY".equalsIgnoreCase(indexName)
                    || entry.getValue().size() != 1
                    || !"control_user_id".equalsIgnoreCase(entry.getValue().getFirst())) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE residential_allocations DROP INDEX `"
                    + indexName.replace("`", "``") + "`");
            log.info("Removed legacy global unique index {} from {}.control_user_id", indexName, ALLOCATION_TABLE);
        }
    }
}
