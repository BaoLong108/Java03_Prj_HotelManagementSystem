-- Existing galleries keep their image identifiers and full-size pixel data.
ALTER TABLE room_types ADD COLUMN amenities TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE room_type_images ADD COLUMN thumbnail_data BYTEA;
ALTER TABLE room_type_images ADD COLUMN thumbnail_content_type VARCHAR(20);
ALTER TABLE room_type_images ADD CONSTRAINT ck_room_thumbnail_pair CHECK (
    (thumbnail_data IS NULL AND thumbnail_content_type IS NULL) OR
    (thumbnail_data IS NOT NULL AND thumbnail_content_type IN ('image/jpeg','image/png')
     AND octet_length(thumbnail_data) BETWEEN 1 AND 5242880));

-- Only the explicitly invoked local demo-reset command writes this marker.
CREATE TABLE dev_demo_operations (
    operation_key VARCHAR(100) PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
