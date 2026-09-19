-- V3: refresh-token families, for reuse detection (2026-09-19).
--
-- Every refresh token belongs to a family: the chain of tokens that one login
-- produces as it is rotated. When a token that was already rotated away is
-- presented again, after a short grace period for tabs racing each other, the
-- whole family is revoked, because two parties holding the same token means one
-- of them stole it. revoked_at dates the rotation, which is what the grace period
-- is measured from.
--
-- Existing rows each become a family of one. They are all short-lived, and
-- nothing is lost by not reconstructing chains that were never recorded.

ALTER TABLE public.refresh_tokens ADD COLUMN family_id uuid;
UPDATE public.refresh_tokens SET family_id = gen_random_uuid() WHERE family_id IS NULL;
ALTER TABLE public.refresh_tokens ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE public.refresh_tokens ADD COLUMN revoked_at timestamp without time zone;

CREATE INDEX idx_refresh_tokens_family_id ON public.refresh_tokens USING btree (family_id);
