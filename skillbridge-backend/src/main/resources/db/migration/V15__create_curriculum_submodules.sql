-- Migration V15: Add sub-modules and scheduling to curriculum structure
-- This creates a 3-level hierarchy: Module -> Sub-module -> Topic

-- ============================================================================
-- 1. Add scheduling fields to syllabus_modules
-- ============================================================================

-- Add date range fields to modules
ALTER TABLE syllabus_modules 
    ADD COLUMN IF NOT EXISTS start_date DATE,
    ADD COLUMN IF NOT EXISTS end_date DATE;

COMMENT ON COLUMN syllabus_modules.start_date IS 'Start date for the module (e.g., Aug 1, 2026)';
COMMENT ON COLUMN syllabus_modules.end_date IS 'End date for the module (e.g., Aug 31, 2026)';

-- ============================================================================
-- 2. Create syllabus_submodules table (NEW)
-- ============================================================================

CREATE TABLE IF NOT EXISTS syllabus_submodules (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL REFERENCES syllabus_modules(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    display_order INTEGER NOT NULL,
    
    -- Scheduling fields
    start_date DATE,
    end_date DATE,
    week_number INTEGER,  -- Optional: Week 1, Week 2-3, etc.
    
    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_module_submodule_order UNIQUE(module_id, display_order)
);

-- Indexes for performance
CREATE INDEX idx_submodules_module ON syllabus_submodules(module_id);
CREATE INDEX idx_submodules_order ON syllabus_submodules(module_id, display_order);

-- Comments
COMMENT ON TABLE syllabus_submodules IS 'Sub-modules within a curriculum module (e.g., OOP Basics under Java module)';
COMMENT ON COLUMN syllabus_submodules.module_id IS 'Parent module ID';
COMMENT ON COLUMN syllabus_submodules.week_number IS 'Optional week number indicator (e.g., 1 for Week 1, 2 for Week 2-3)';
COMMENT ON COLUMN syllabus_submodules.start_date IS 'Optional start date for the sub-module';
COMMENT ON COLUMN syllabus_submodules.end_date IS 'Optional end date for the sub-module';

-- ============================================================================
-- 3. Update syllabus_topics to link to sub-modules
-- ============================================================================

-- NOTE: This will DROP all existing topics data!
-- User confirmed they will manually delete existing data

-- Drop the old foreign key constraint
ALTER TABLE syllabus_topics 
    DROP CONSTRAINT IF EXISTS syllabus_topics_module_id_fkey;

-- Remove old module_id column
ALTER TABLE syllabus_topics 
    DROP COLUMN IF EXISTS module_id;

-- Add new submodule_id column
ALTER TABLE syllabus_topics 
    ADD COLUMN IF NOT EXISTS submodule_id BIGINT NOT NULL REFERENCES syllabus_submodules(id) ON DELETE CASCADE;

-- Create index for the new relationship
CREATE INDEX IF NOT EXISTS idx_topics_submodule ON syllabus_topics(submodule_id);

COMMENT ON COLUMN syllabus_topics.submodule_id IS 'Parent sub-module ID (topics now belong to sub-modules, not directly to modules)';

-- ============================================================================
-- 4. Clean up - Remove timeline tables (Timeline tab is being removed)
-- ============================================================================

-- Drop timeline sessions table (no longer needed with unified curriculum)
DROP TABLE IF EXISTS batch_timeline_sessions CASCADE;

COMMENT ON TABLE syllabus_submodules IS 'Sub-modules replace the timeline concept - scheduling is now integrated into curriculum structure';
