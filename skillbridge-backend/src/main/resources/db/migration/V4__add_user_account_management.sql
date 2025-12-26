-- Add account management fields to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status VARCHAR(20) DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS invitation_sent_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS first_login_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_completed BOOLEAN DEFAULT FALSE;

-- Add indexes for querying pending accounts
CREATE INDEX IF NOT EXISTS idx_users_account_status ON users(account_status);
CREATE INDEX IF NOT EXISTS idx_users_must_change_password ON users(must_change_password);

-- Add comments for documentation
COMMENT ON COLUMN users.must_change_password IS 'Forces password change on next login';
COMMENT ON COLUMN users.account_status IS 'PENDING_SETUP, ACTIVE, INCOMPLETE, SUSPENDED';
COMMENT ON COLUMN users.invitation_sent_at IS 'When welcome email was sent';
COMMENT ON COLUMN users.first_login_at IS 'When user first logged in';
COMMENT ON COLUMN users.profile_completed IS 'Whether user completed profile setup';
