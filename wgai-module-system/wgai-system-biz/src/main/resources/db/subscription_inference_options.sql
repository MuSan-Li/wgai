-- MySQL: execute once before deploying the updated backend.
-- Existing subscriptions default to 5 waiting frames, single-image inference.
ALTER TABLE tab_ai_subscription_new
    ADD COLUMN inference_queue_capacity INT NOT NULL DEFAULT 5 COMMENT 'Waiting frames per subscription; discard oldest when full',
    ADD COLUMN inference_batch_enabled INT NOT NULL DEFAULT 0 COMMENT '0 single image, 1 enable batching when supported by model',
    ADD COLUMN inference_batch_size INT NOT NULL DEFAULT 4 COMMENT 'Maximum real images for dynamic-batch models',
    ADD COLUMN inference_batch_wait_ms INT NOT NULL DEFAULT 30 COMMENT 'Maximum batch collection wait in milliseconds';

-- Inspect the deployed dictionary. 20 must mean ONNX, 100 must mean OM.
-- Other existing values retain the legacy OpenCV mapping; do not blindly change dictionary values.
SELECT d.dict_code, i.item_text, i.item_value
FROM sys_dict d JOIN sys_dict_item i ON i.dict_id = d.id
WHERE d.dict_code IN ('model_type', 'jm_type');
