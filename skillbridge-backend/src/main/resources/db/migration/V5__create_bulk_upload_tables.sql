-- Track bulk upload operations
CREATE TABLE IF NOT EXISTS bulk_uploads (
    id BIGSERIAL PRIMARY KEY,
    college_id BIGINT NOT NULL,
    uploaded_by_user_id BIGINT NOT NULL,
    entity_type VARCHAR(20) NOT NULL, -- STUDENT, TRAINER
    file_name VARCHAR(255) NOT NULL,
    total_rows INTEGER NOT NULL,
    successful_rows INTEGER NOT NULL,
    failed_rows INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL, -- PROCESSING, COMPLETED, FAILED
    error_report TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT fk_bulk_uploads_college FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE,
    CONSTRAINT fk_bulk_uploads_user FOREIGN KEY (uploaded_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Track individual upload results
CREATE TABLE IF NOT EXISTS bulk_upload_results (
    id BIGSERIAL PRIMARY KEY,
    bulk_upload_id BIGINT NOT NULL,
    row_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL, -- SUCCESS, FAILED, SKIPPED
    entity_id BIGINT, -- student_id or trainer_id if created
    error_message TEXT,
    data JSONB, -- Store the row data for reference
    CONSTRAINT fk_bulk_upload_results_upload FOREIGN KEY (bulk_upload_id) REFERENCES bulk_uploads(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_bulk_uploads_college_id ON bulk_uploads(college_id);
CREATE INDEX IF NOT EXISTS idx_bulk_uploads_status ON bulk_uploads(status);
CREATE INDEX IF NOT EXISTS idx_bulk_uploads_created_at ON bulk_uploads(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bulk_upload_results_upload_id ON bulk_upload_results(bulk_upload_id);
CREATE INDEX IF NOT EXISTS idx_bulk_upload_results_status ON bulk_upload_results(status);

-- Add comments
COMMENT ON TABLE bulk_uploads IS 'Tracks bulk upload operations for students and trainers';
COMMENT ON TABLE bulk_upload_results IS 'Stores individual row results from bulk uploads';
COMMENT ON COLUMN bulk_uploads.entity_type IS 'Type of entity being uploaded: STUDENT or TRAINER';
COMMENT ON COLUMN bulk_uploads.status IS 'Upload status: PROCESSING, COMPLETED, or FAILED';
COMMENT ON COLUMN bulk_upload_results.data IS 'JSON data of the uploaded row for audit purposes';
