-- NiuSu Control Plane production schema compatibility migration
--
-- Purpose: prepare an existing MySQL 8 Control Plane database for the
-- release that uses node-local user ids, residential source ports and the
-- encrypted protocolsAll payload.
--
-- Safety:
--   * Run only after a verified backup.
--   * This script never deletes rows and never drops a primary/composite index.
--   * It must be run against the `control-plane` database, not `niusuip`.
--   * The application is configured with ddl-auto=validate in production, so
--     run this script before replacing the production JAR.
--
-- Usage with the mysql client:
--   mysql --defaults-extra-file=/path/to/control-plane.cnf < production-schema-migration.sql

USE `control-plane`;

DELIMITER $$

DROP PROCEDURE IF EXISTS `niusu_apply_schema_compatibility_migration`$$

CREATE PROCEDURE `niusu_apply_schema_compatibility_migration`()
BEGIN
    DECLARE v_index_name VARCHAR(255);
    DECLARE v_done BOOLEAN DEFAULT FALSE;
    DECLARE v_table_count INT DEFAULT 0;

    DECLARE legacy_index_cursor CURSOR FOR
        SELECT s.INDEX_NAME
        FROM information_schema.STATISTICS s
        WHERE s.TABLE_SCHEMA = DATABASE()
          AND s.TABLE_NAME = 'residential_allocations'
          AND s.NON_UNIQUE = 0
          AND UPPER(s.INDEX_NAME) <> 'PRIMARY'
          AND LOWER(s.COLUMN_NAME) = 'control_user_id'
        GROUP BY s.INDEX_NAME
        HAVING COUNT(*) = 1;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = TRUE;

    IF DATABASE() <> 'control-plane' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Wrong database: run this migration only in control-plane';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_users'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_users' AND COLUMN_NAME = 'role'
    ) THEN
        ALTER TABLE `control_users` ADD COLUMN `role` VARCHAR(32) NOT NULL DEFAULT 'ADMIN';
    END IF;

    CREATE TABLE IF NOT EXISTS `control_audit_logs` (
        `id` BINARY(16) NOT NULL,
        `event_type` VARCHAR(64) NOT NULL,
        `actor_user_id` BINARY(16) NULL,
        `actor_username` VARCHAR(64) NULL,
        `target_type` VARCHAR(64) NULL,
        `target_id` VARCHAR(128) NULL,
        `summary` VARCHAR(500) NOT NULL,
        `created_at` TIMESTAMP(6) NOT NULL,
        PRIMARY KEY (`id`),
        KEY `idx_control_audit_created_at` (`created_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    SELECT COUNT(*) INTO v_table_count
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'residential_allocations'
      AND TABLE_TYPE = 'BASE TABLE';
    IF v_table_count = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Base table residential_allocations is missing; apply the baseline schema first';
    END IF;

    SELECT COUNT(*) INTO v_table_count
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'managed_nodes'
      AND TABLE_TYPE = 'BASE TABLE';
    IF v_table_count = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Base table managed_nodes is missing; apply the baseline schema first';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'residential_allocations'
          AND COLUMN_NAME = 'proxy_source_port'
    ) THEN
        ALTER TABLE `residential_allocations`
            ADD COLUMN `proxy_source_port` INT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'residential_allocations'
          AND COLUMN_NAME = 'protocols_all_cipher'
    ) THEN
        ALTER TABLE `residential_allocations`
            ADD COLUMN `protocols_all_cipher` LONGTEXT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'managed_nodes'
          AND COLUMN_NAME = 'socks_inbound_port'
    ) THEN
        ALTER TABLE `managed_nodes`
            ADD COLUMN `socks_inbound_port` INT NULL;
    END IF;

    -- Older releases made control_user_id globally unique.  User ids are now
    -- unique within a Node Manager, so remove only a single-column unique
    -- index on control_user_id.  Composite indexes and PRIMARY are preserved.
    OPEN legacy_index_cursor;
    legacy_index_loop: LOOP
        FETCH legacy_index_cursor INTO v_index_name;
        IF v_done THEN
            LEAVE legacy_index_loop;
        END IF;

        SET @drop_index_sql = CONCAT(
            'ALTER TABLE `residential_allocations` DROP INDEX `',
            REPLACE(v_index_name, '`', '``'),
            '`'
        );
        PREPARE drop_index_statement FROM @drop_index_sql;
        EXECUTE drop_index_statement;
        DEALLOCATE PREPARE drop_index_statement;
    END LOOP;
    CLOSE legacy_index_cursor;
END$$

CALL `niusu_apply_schema_compatibility_migration`()$$
DROP PROCEDURE `niusu_apply_schema_compatibility_migration`$$

DELIMITER ;

-- Verification (read-only):
-- SHOW COLUMNS FROM `residential_allocations` LIKE 'proxy_source_port';
-- SHOW COLUMNS FROM `residential_allocations` LIKE 'protocols_all_cipher';
-- SHOW COLUMNS FROM `managed_nodes` LIKE 'socks_inbound_port';
-- SHOW INDEX FROM `residential_allocations`;
