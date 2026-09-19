-- V5: CSV import idempotency and liveness (2026-09-19).
--
-- file_sha256: the same file uploaded twice by the same college, for the same
-- kind of account, returns the first upload instead of importing again. The
-- unique index is what makes that race-safe: two admins, or a double click,
-- cannot both insert. FAILED uploads are outside it, so a file whose upload
-- was interrupted can be sent again. Rows from before V5 have no hash and are
-- outside it too.
--
-- last_progress_at: the importer's heartbeat, touched as rows are written. An
-- upload still PROCESSING whose heartbeat has stopped belonged to a process
-- that died; StaleUploadSweeper marks it FAILED so the screen does not say
-- "processing" forever.

ALTER TABLE public.bulk_uploads ADD COLUMN file_sha256 character varying(64);
ALTER TABLE public.bulk_uploads ADD COLUMN last_progress_at timestamp without time zone;

CREATE UNIQUE INDEX uk_bulk_uploads_same_file ON public.bulk_uploads
    USING btree (college_id, entity_type, file_sha256)
    WHERE file_sha256 IS NOT NULL AND status <> 'FAILED';

CREATE INDEX idx_bulk_uploads_processing ON public.bulk_uploads
    USING btree (last_progress_at) WHERE status = 'PROCESSING';

CREATE INDEX idx_bulk_upload_results_upload_status ON public.bulk_upload_results
    USING btree (bulk_upload_id, status, row_number);

-- The composite above starts with bulk_upload_id, so it serves every lookup the
-- single-column index did, including the foreign key's cascade.
DROP INDEX public.idx_bulk_upload_results_upload_id;
