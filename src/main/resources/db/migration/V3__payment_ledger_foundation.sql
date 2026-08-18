-- ViralGround payment/escrow append-only foundation.
-- V1 creates the pre-existing schema. V3 enriches legacy rows and creates balanced ledger/webhook tables.

ALTER TABLE escrow_transactions ADD COLUMN operation_id VARCHAR(64);
ALTER TABLE escrow_transactions ADD COLUMN idempotency_key VARCHAR(160);
ALTER TABLE escrow_transactions ADD COLUMN currency VARCHAR(3) DEFAULT 'KRW';
ALTER TABLE escrow_transactions ADD COLUMN provider VARCHAR(50);
ALTER TABLE escrow_transactions ADD COLUMN provider_tx_id VARCHAR(200);
ALTER TABLE escrow_transactions ADD COLUMN actor_member_id INTEGER;
ALTER TABLE escrow_transactions ADD COLUMN actor_type VARCHAR(30);
ALTER TABLE escrow_transactions ADD COLUMN reason VARCHAR(500);
ALTER TABLE escrow_transactions ADD COLUMN balance_after INTEGER;

-- Existing rows predate provider-verifiable transactions. Preserve them explicitly as LEGACY,
-- never misrepresent them as commercial provider confirmations.
UPDATE escrow_transactions
SET operation_id = 'legacy-' || id,
    idempotency_key = 'legacy:escrow-transaction:' || id,
    currency = COALESCE(currency, 'KRW'),
    provider = 'legacy',
    provider_tx_id = 'LEGACY-' || id,
    actor_type = 'SYSTEM',
    reason = COALESCE(NULLIF(memo, ''), 'migration of pre-ledger transaction');

WITH running_balance AS (
    SELECT id,
           SUM(CASE WHEN type = 'DEPOSIT' THEN amount ELSE -amount END)
             OVER (PARTITION BY campaign_id ORDER BY created_at ASC, id ASC) AS balance
    FROM escrow_transactions
)
UPDATE escrow_transactions t
SET balance_after = GREATEST(r.balance, 0)
FROM running_balance r
WHERE r.id = t.id;

ALTER TABLE escrow_transactions ALTER COLUMN operation_id SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN currency SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN provider SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN provider_tx_id SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN actor_type SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN reason SET NOT NULL;
ALTER TABLE escrow_transactions ALTER COLUMN balance_after SET NOT NULL;

ALTER TABLE escrow_transactions ADD CONSTRAINT ck_escrow_amount_positive CHECK (amount > 0);
ALTER TABLE escrow_transactions ADD CONSTRAINT ck_escrow_balance_nonnegative CHECK (balance_after >= 0);
ALTER TABLE escrow_transactions ADD CONSTRAINT uq_escrow_operation UNIQUE (operation_id);
ALTER TABLE escrow_transactions ADD CONSTRAINT uq_escrow_idempotency UNIQUE (idempotency_key);
ALTER TABLE escrow_transactions ADD CONSTRAINT uq_escrow_provider_tx UNIQUE (provider, provider_tx_id);
CREATE UNIQUE INDEX uq_escrow_single_deposit ON escrow_transactions (campaign_id) WHERE type = 'DEPOSIT';
CREATE UNIQUE INDEX uq_escrow_single_release_per_application
    ON escrow_transactions (campaign_id, application_id) WHERE type = 'RELEASE';
CREATE UNIQUE INDEX uq_escrow_single_refund ON escrow_transactions (campaign_id) WHERE type = 'REFUND';
CREATE INDEX idx_escrow_created_at ON escrow_transactions (created_at);

CREATE TABLE payment_ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    escrow_transaction_id INTEGER NOT NULL REFERENCES escrow_transactions(id) ON DELETE RESTRICT,
    operation_id VARCHAR(64) NOT NULL,
    campaign_id INTEGER NOT NULL,
    application_id INTEGER,
    account VARCHAR(40) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_ledger_operation_direction UNIQUE (operation_id, direction)
);

CREATE INDEX idx_payment_ledger_campaign ON payment_ledger_entries (campaign_id);
CREATE INDEX idx_payment_ledger_transaction ON payment_ledger_entries (escrow_transaction_id);

-- Backfill two balanced rows for every legacy transaction.
INSERT INTO payment_ledger_entries
    (escrow_transaction_id, operation_id, campaign_id, application_id,
     account, direction, amount, currency, created_at)
SELECT id, operation_id, campaign_id, application_id,
       CASE type
           WHEN 'DEPOSIT' THEN 'GATEWAY_CLEARING'
           ELSE 'ESCROW_AVAILABLE'
       END,
       'DEBIT', amount, currency, created_at
FROM escrow_transactions;

INSERT INTO payment_ledger_entries
    (escrow_transaction_id, operation_id, campaign_id, application_id,
     account, direction, amount, currency, created_at)
SELECT id, operation_id, campaign_id, application_id,
       CASE type
           WHEN 'DEPOSIT' THEN 'ESCROW_AVAILABLE'
           WHEN 'RELEASE' THEN 'CREATOR_PAYOUT'
           ELSE 'CUSTOMER_REFUND'
       END,
       'CREDIT', amount, currency, created_at
FROM escrow_transactions;

-- Commit-time invariant: every operation must have exactly one debit and one credit of equal value.
CREATE OR REPLACE FUNCTION validate_payment_ledger_balance() RETURNS trigger AS $$
DECLARE
    entry_count INTEGER;
    debit_total BIGINT;
    credit_total BIGINT;
BEGIN
    SELECT COUNT(*),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO entry_count, debit_total, credit_total
      FROM payment_ledger_entries
     WHERE operation_id = NEW.operation_id;
    IF entry_count <> 2 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'unbalanced payment ledger operation %', NEW.operation_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER payment_ledger_balance_guard
AFTER INSERT ON payment_ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_payment_ledger_balance();

CREATE OR REPLACE FUNCTION reject_payment_record_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment records are append-only; table=%, operation=%', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER escrow_transactions_immutable
BEFORE UPDATE OR DELETE ON escrow_transactions
FOR EACH ROW EXECUTE FUNCTION reject_payment_record_mutation();

CREATE TRIGGER payment_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON payment_ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_payment_record_mutation();

CREATE TABLE payment_webhook_events (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    provider_object_id VARCHAR(200),
    payload_sha256 VARCHAR(64) NOT NULL,
    provider_occurred_at TIMESTAMP,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_webhook_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_payment_webhook_received_at ON payment_webhook_events (received_at);

CREATE TRIGGER payment_webhook_events_immutable
BEFORE UPDATE OR DELETE ON payment_webhook_events
FOR EACH ROW EXECUTE FUNCTION reject_payment_record_mutation();

-- Production DB role hardening (execute under the deployment-specific application role after its name is chosen):
-- REVOKE UPDATE, DELETE ON escrow_transactions, payment_ledger_entries FROM <viralground_app_role>;
-- GRANT SELECT, INSERT ON escrow_transactions, payment_ledger_entries TO <viralground_app_role>;
