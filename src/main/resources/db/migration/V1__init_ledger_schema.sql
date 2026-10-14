-- V1__init_ledger_schema.sql
-- Production-grade double-entry bookkeeping schema with immutability triggers

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_entries_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0)
);

-- Performance Indexes
CREATE INDEX idx_accounts_currency ON accounts(currency);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at);

-- Database-Level Immutability Enforcement
-- Prohibit UPDATE or DELETE on ledger_entries and transactions to ensure an append-only audit trail
CREATE OR REPLACE FUNCTION prevent_immutable_record_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Modifications (UPDATE or DELETE) are strictly prohibited on immutable ledger table %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_immutable_record_modification();

CREATE TRIGGER trg_immutable_transactions
BEFORE UPDATE OR DELETE ON transactions
FOR EACH ROW
EXECUTE FUNCTION prevent_immutable_record_modification();
