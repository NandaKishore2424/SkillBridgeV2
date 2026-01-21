-- Migration: Add trainer profile columns
-- Created: 2026-01-21
-- Purpose: Add phone, linkedin_url, and years_of_experience columns to trainers table
--          These fields exist in Trainer entity but were missing from database

-- Add phone column
ALTER TABLE trainers
ADD COLUMN IF NOT EXISTS phone VARCHAR(20);

-- Add linkedin_url column
ALTER TABLE trainers
ADD COLUMN IF NOT EXISTS linkedin_url VARCHAR(255);

-- Add years_of_experience column
ALTER TABLE trainers
ADD COLUMN IF NOT EXISTS years_of_experience INTEGER;

-- Add comments for documentation
COMMENT ON COLUMN trainers.phone IS 'Trainer contact phone number';
COMMENT ON COLUMN trainers.linkedin_url IS 'Link to trainer LinkedIn profile';
COMMENT ON COLUMN trainers.years_of_experience IS 'Number of years of teaching/training experience';
