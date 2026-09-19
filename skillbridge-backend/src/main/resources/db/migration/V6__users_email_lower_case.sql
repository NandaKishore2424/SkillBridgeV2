-- V6: users.email is always lower case (2026-09-19).
--
-- Login lower-cases what is typed, and the unique constraint compares exactly,
-- so a mixed-case row would be an account nobody can log into, and a second
-- account for the same person. The application normalises (EmailAddress,
-- User's @PrePersist); this is the guard for anything that bypasses it, such
-- as a hand-written INSERT. Every existing row was already lower case.

ALTER TABLE public.users ADD CONSTRAINT users_email_lower_case CHECK (email = lower(email));
