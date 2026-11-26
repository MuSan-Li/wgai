-- MySQL：先执行本脚本，再部署后端。可重复执行。
-- Java方式2支持目标检测画框推流；Python暂保留配置。推理设备沿用模型配置。
SET @mode_column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'recognition_mode');
SET @mode_ddl = IF(@mode_column_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN recognition_mode varchar(2) DEFAULT ''1'' COMMENT ''识别方式：1视频流识别，2视频流推送重组（预配置）''',
  'SELECT 1');
PREPARE mode_stmt FROM @mode_ddl;
EXECUTE mode_stmt;
DEALLOCATE PREPARE mode_stmt;

INSERT INTO sys_dict (id, dict_name, dict_code, description, del_flag, create_by, create_time, type)
SELECT REPLACE(UUID(), '-', ''), '视频识别方式', 'video_recognition_mode', '仅保存配置，当前不生效', 0, 'admin', NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'video_recognition_mode');
SET @mode_dict_id = (SELECT id FROM sys_dict WHERE dict_code = 'video_recognition_mode' LIMIT 1);

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, create_by, create_time)
SELECT REPLACE(UUID(), '-', ''), @mode_dict_id, '视频流识别', '1', '前端按视频时间戳匹配识别结果', 1, 1, 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = @mode_dict_id AND item_value = '1');
INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, create_by, create_time)
SELECT REPLACE(UUID(), '-', ''), @mode_dict_id, '视频流推送重组', '2', '后端合成识别结果并推流，前端仅播放；当前不生效', 2, 1, 'admin', NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = @mode_dict_id AND item_value = '2');

-- inference_video_url
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'inference_video_url');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN inference_video_url varchar(1024) DEFAULT NULL COMMENT ''Python 推理视频流地址，与默认播放地址独立''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;

-- zlm_push_url
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'zlm_push_url');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN zlm_push_url varchar(1024) DEFAULT NULL COMMENT ''ZLM 推流地址''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;

-- inference_fps
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'inference_fps');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN inference_fps int DEFAULT 3 COMMENT ''每秒推理抽帧数，建议3''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;

-- python_script_path
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'python_script_path');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN python_script_path varchar(1024) DEFAULT NULL COMMENT ''共享 Python 脚本文件路径''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;

-- GPU解码开关: 0关闭，1开启；不改变现有识别方式1的运行设置
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'gpu_decode');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN gpu_decode int DEFAULT 0 COMMENT ''GPU解码开关:0关闭,1开启''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;

-- 推理设备沿用模型配置；已经存在的 gpu_inference 列保留但不再读取，无须删列。
SET @config_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_ai_model_bund' AND COLUMN_NAME = 'execution_engine');
SET @config_ddl = IF(@config_exists = 0,
  'ALTER TABLE tab_ai_model_bund ADD COLUMN execution_engine varchar(16) DEFAULT ''java'' COMMENT ''执行方式:java或python''', 'SELECT 1');
PREPARE config_stmt FROM @config_ddl;
EXECUTE config_stmt;
DEALLOCATE PREPARE config_stmt;
