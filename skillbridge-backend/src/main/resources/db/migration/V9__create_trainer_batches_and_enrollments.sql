-- Create trainer_batches table for many-to-many relationship between trainers and batches
CREATE TABLE IF NOT EXISTS trainer_batches (
    id BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainers(id) ON DELETE CASCADE,
    batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    UNIQUE(trainer_id, batch_id)
);

CREATE INDEX idx_trainer_batches_trainer_id ON trainer_batches(trainer_id);
CREATE INDEX idx_trainer_batches_batch_id ON trainer_batches(batch_id);

-- Create enrollments table for many-to-many relationship between students and batches
CREATE TABLE IF NOT EXISTS enrollments (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    student_id BIGINT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(batch_id, student_id)
);

CREATE INDEX idx_enrollments_batch_id ON enrollments(batch_id);
CREATE INDEX idx_enrollments_student_id ON enrollments(student_id);
