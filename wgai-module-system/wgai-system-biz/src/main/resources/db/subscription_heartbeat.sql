-- Execute once before deploying the updated backend. Existing subscriptions remain disabled.
ALTER TABLE tab_ai_subscription_new
  ADD COLUMN heartbeat_enabled INT NOT NULL DEFAULT 0 COMMENT 'Independent keepalive POST: 0 disabled, 1 enabled',
  ADD COLUMN heartbeat_server_code VARCHAR(100) NULL COMMENT 'English keepalive server code',
  ADD COLUMN heartbeat_server_name VARCHAR(100) NULL COMMENT 'Keepalive server name',
  ADD COLUMN heartbeat_url VARCHAR(2000) NULL COMMENT 'Keepalive POST URL',
  ADD COLUMN heartbeat_interval_seconds INT NOT NULL DEFAULT 30 COMMENT 'Keepalive interval seconds, 5-3600';
