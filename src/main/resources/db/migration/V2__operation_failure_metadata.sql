ALTER TABLE operations ADD COLUMN retryable_failure BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE operations ADD CONSTRAINT retryable_failure_status
    CHECK (NOT retryable_failure OR status = 'FAILED');
