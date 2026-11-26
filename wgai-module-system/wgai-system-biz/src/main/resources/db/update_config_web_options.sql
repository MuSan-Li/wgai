-- MySQL 5.7/8. Select the application database before running.
-- Re-runnable, including after a partially completed earlier migration.
-- First shrink legacy long VARCHAR URLs to TEXT, BEFORE adding any new columns.
-- Then create/convert options as TEXT: avoids the 65535-byte logical row limit.
-- No DROP/TRUNCATE/UPDATE/DELETE; existing values remain unchanged.
-- Converted optional columns allow NULL and no longer have VARCHAR defaults.
-- Existing character sets/collations are retained. No server row-format change.
SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'SELECT ''release_manifest_url not present; skipped'' AS migration_info',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN release_manifest_url TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Platform update URL'''),
 'SELECT ''release_manifest_url already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='release_manifest_url');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'SELECT ''frontend_download_url not present; skipped'' AS migration_info',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN frontend_download_url TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Platform update URL'''),
 'SELECT ''frontend_download_url already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='frontend_download_url');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'SELECT ''backend_download_url not present; skipped'' AS migration_info',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN backend_download_url TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Platform update URL'''),
 'SELECT ''backend_download_url already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='backend_download_url');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'SELECT ''db_schema_url not present; skipped'' AS migration_info',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN db_schema_url TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Platform update URL'''),
 'SELECT ''db_schema_url already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='db_schema_url');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN stop_script TEXT NULL COMMENT ''Local stop script; blank falls back to YML''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN stop_script TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Local stop script; blank falls back to YML'''),
 'SELECT ''stop_script already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='stop_script');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN start_script TEXT NULL COMMENT ''Local start script; blank falls back to YML''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN start_script TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Local start script; blank falls back to YML'''),
 'SELECT ''start_script already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='start_script');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN health_url TEXT NULL COMMENT ''Backend HTTP200 URL; blank falls back to YML''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN health_url TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Backend HTTP200 URL; blank falls back to YML'''),
 'SELECT ''health_url already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='health_url');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN allowed_hosts TEXT NULL COMMENT ''Download hosts; blank falls back to YML''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN allowed_hosts TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Download hosts; blank falls back to YML'''),
 'SELECT ''allowed_hosts already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='allowed_hosts');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN frontend_preserve_paths TEXT NULL COMMENT ''Preserved frontend paths; blank falls back to YML''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN frontend_preserve_paths TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Preserved frontend paths; blank falls back to YML'''),
 'SELECT ''frontend_preserve_paths already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='frontend_preserve_paths');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

SET @update_web_ddl = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE tab_update_config ADD COLUMN java_executable TEXT NULL COMMENT ''Updater Java executable; blank uses YML or current JVM''',
 IF(MAX(DATA_TYPE) IN ('varchar','char'), CONCAT('ALTER TABLE tab_update_config MODIFY COLUMN java_executable TEXT CHARACTER SET ', MAX(CHARACTER_SET_NAME), ' COLLATE ', MAX(COLLATION_NAME), ' NULL COMMENT ''Updater Java executable; blank uses YML or current JVM'''),
 'SELECT ''java_executable already TEXT or not a character column'' AS migration_info'))
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
 AND TABLE_NAME='tab_update_config' AND COLUMN_NAME='java_executable');
PREPARE update_web_stmt FROM @update_web_ddl;
EXECUTE update_web_stmt;
DEALLOCATE PREPARE update_web_stmt;

-- MySQL: preserve time-of-day if an older deployment used DATE for last_update_time.
-- Run before the new backend; tab_update_config is excluded from schema.sql synchronization.
SET @update_time_ddl = (SELECT IF(DATA_TYPE = 'date',
 'ALTER TABLE tab_update_config MODIFY COLUMN last_update_time DATETIME NULL COMMENT ''Last update completion timestamp''',
 'SELECT ''last_update_time already supports time'' AS migration_info')
 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
 AND TABLE_NAME = 'tab_update_config' AND COLUMN_NAME = 'last_update_time');
PREPARE update_time_stmt FROM @update_time_ddl;
EXECUTE update_time_stmt;
DEALLOCATE PREPARE update_time_stmt;
