-- Fix JSONB column type mismatch
-- Change data column from JSONB to TEXT to allow string storage

ALTER TABLE bulk_upload_results 
ALTER COLUMN data TYPE TEXT USING data::TEXT;

-- Update comment
COMMENT ON COLUMN bulk_upload_results.data IS 'JSON string of the uploaded row for audit purposes (stored as TEXT)';
