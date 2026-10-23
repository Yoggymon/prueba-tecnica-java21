CREATE TABLE accounts (
    id BIGINT PRIMARY KEY,
    initial_balance NUMERIC(19, 2) NOT NULL CHECK (initial_balance >= 0),
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
);

CREATE TABLE operations (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('WITHDRAWAL', 'TRANSFER')),
    source_id BIGINT NOT NULL CHECK (source_id > 0),
    destination_id BIGINT CHECK (destination_id > 0),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING', 'SUCCESS', 'REJECTED', 'FAILED')),
    reason VARCHAR(64),
    CHECK ((kind = 'WITHDRAWAL' AND destination_id IS NULL)
        OR (kind = 'TRANSFER' AND destination_id IS NOT NULL AND source_id <> destination_id))
);

CREATE TABLE operation_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(id),
    result VARCHAR(16) NOT NULL CHECK (result IN ('SUCCESS', 'REJECTED', 'FAILED')),
    reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX operation_attempts_operation_idx ON operation_attempts(operation_id);
CREATE INDEX operations_source_idx ON operations(source_id);
CREATE INDEX operations_destination_idx ON operations(destination_id);

-- Fixed exercise accounts; no account-management API is required.
INSERT INTO accounts(id, initial_balance, balance) VALUES
    (1, 10000.00, 10000.00),
    (2, 0.00, 0.00);
