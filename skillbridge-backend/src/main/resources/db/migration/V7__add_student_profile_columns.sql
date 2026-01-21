-- Add missing student profile columns to match entity

ALTER TABLE students 
ADD COLUMN IF NOT EXISTS full_name VARCHAR(255) NOT NULL DEFAULT 'Unknown',
ADD COLUMN IF NOT EXISTS phone VARCHAR(20),
ADD COLUMN IF NOT EXISTS bio TEXT,
ADD COLUMN IF NOT EXISTS github_url VARCHAR(255),
ADD COLUMN IF NOT EXISTS portfolio_url VARCHAR(255),
ADD COLUMN IF NOT EXISTS resume_url VARCHAR(255);

-- Remove default after adding column (for existing rows)
ALTER TABLE students 
ALTER COLUMN full_name DROP DEFAULT;

-- Add index on full_name for search performance
CREATE INDEX IF NOT EXISTS idx_students_full_name ON students(full_name);

-- Comments
COMMENT ON COLUMN students.full_name IS 'Student full name';
COMMENT ON COLUMN students.phone IS 'Student contact phone number';
COMMENT ON COLUMN students.bio IS 'Student biography/profile description';
COMMENT ON COLUMN students.github_url IS 'Link to student GitHub profile';
COMMENT ON COLUMN students.portfolio_url IS 'Link to student portfolio website';
COMMENT ON COLUMN students.resume_url IS 'Link to student resume/CV document';
