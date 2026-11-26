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
