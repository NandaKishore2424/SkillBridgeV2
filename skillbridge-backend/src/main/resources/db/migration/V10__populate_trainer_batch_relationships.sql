-- Populate trainer_batches with existing relationships
-- Link Prof. Rajesh Menon to Full Stack batch

-- Insert trainer-batch relationship for Prof. Rajesh Menon and Full Stack batch
INSERT INTO trainer_batches (trainer_id, batch_id)
SELECT 
    t.id as trainer_id,
    b.id as batch_id
FROM trainers t
JOIN users u ON t.user_id = u.id
CROSS JOIN batches b
WHERE u.email = 'prof.rajesh.menon@sbu.edu'
  AND b.name = 'Full Stack Development Batch - Jan 2024'
ON CONFLICT (trainer_id, batch_id) DO NOTHING;

-- If the batch name is different, try alternative names
INSERT INTO trainer_batches (trainer_id, batch_id)
SELECT 
    t.id as trainer_id,
    b.id as batch_id
FROM trainers t
JOIN users u ON t.user_id = u.id
CROSS JOIN batches b
WHERE u.email = 'prof.rajesh.menon@sbu.edu'
  AND (b.name ILIKE '%full%stack%' OR b.name ILIKE '%fullstack%')
  AND NOT EXISTS (
    SELECT 1 FROM trainer_batches tb 
    WHERE tb.trainer_id = t.id AND tb.batch_id = b.id
  )
LIMIT 1;
