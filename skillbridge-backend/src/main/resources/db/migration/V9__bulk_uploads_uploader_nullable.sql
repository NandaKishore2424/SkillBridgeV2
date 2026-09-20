-- V9: an upload outlives the account that made it (2026-09-20).
--
-- bulk_uploads.uploaded_by_user_id was NOT NULL while its foreign key said
-- ON DELETE SET NULL: the two cannot both hold, so deleting a user who had ever
-- uploaded a file failed on the constraint instead. Tests worked around it by
-- deleting uploads first.
--
-- The history is the point of the table, so the column gives way rather than the
-- rule: an upload whose uploader is gone keeps its file name, counts and rows,
-- with no uploader. The screen already never showed one.

ALTER TABLE public.bulk_uploads ALTER COLUMN uploaded_by_user_id DROP NOT NULL;
