-- =====================================================
-- TRANSACTIONS
-- =====================================================
CREATE TABLE transactions (
          id BIGSERIAL PRIMARY KEY,
          external_id VARCHAR(255) NOT NULL UNIQUE,
          type VARCHAR(30) NOT NULL,
          currency VARCHAR(3) NOT NULL,
          total NUMERIC(19, 2) NOT NULL,
          occurred_at TIMESTAMPTZ NOT NULL,
          metadata JSONB NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT chk_transaction_type
              CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),
          CONSTRAINT chk_transaction_currency
              CHECK (currency = UPPER(currency)),
          CONSTRAINT chk_transaction_metadata
              CHECK (jsonb_typeof(metadata) = 'object')
);

-- =====================================================
-- TRANSACTION TAXES
-- =====================================================
CREATE TABLE transaction_taxes (
           id BIGSERIAL PRIMARY KEY,
           transaction_id BIGINT NOT NULL
               REFERENCES transactions(id) ON DELETE CASCADE,
           name VARCHAR(255) NOT NULL,
           rate NUMERIC(10, 6),
           amount NUMERIC(19, 2) NOT NULL,
           CONSTRAINT chk_tax_rate
               CHECK (rate IS NULL OR (rate >= 0 AND rate <= 1))
);

CREATE INDEX idx_transaction_taxes_transaction_id
    ON transaction_taxes(transaction_id);

-- =====================================================
-- TRANSACTION FEES
-- =====================================================
CREATE TABLE transaction_fees (
          id BIGSERIAL PRIMARY KEY,
          transaction_id BIGINT NOT NULL
              REFERENCES transactions(id) ON DELETE CASCADE,
          name VARCHAR(255) NOT NULL,
          amount NUMERIC(19, 2) NOT NULL
);

CREATE INDEX idx_transaction_fees_transaction_id
    ON transaction_fees(transaction_id);

-- =====================================================
-- INVOICES
-- =====================================================
CREATE TABLE invoices (
          id BIGSERIAL PRIMARY KEY,
          currency VARCHAR(3) NOT NULL,
          grand_total NUMERIC(19, 2) NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          CONSTRAINT chk_invoice_currency
              CHECK (currency = UPPER(currency))
);

-- =====================================================
-- INVOICE LINE SNAPSHOTS
-- =====================================================
CREATE TABLE invoice_line_snapshots (
            id BIGSERIAL PRIMARY KEY,
            invoice_id BIGINT NOT NULL
                REFERENCES invoices(id) ON DELETE CASCADE,
            transaction_id BIGINT NOT NULL
                REFERENCES transactions(id) ON DELETE RESTRICT,
            type VARCHAR(30) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            total NUMERIC(19, 2) NOT NULL,
            metadata JSONB NOT NULL,
            tax_lines JSONB NOT NULL,
            fee_lines JSONB NOT NULL,
            CONSTRAINT uq_invoice_transaction
                UNIQUE (invoice_id, transaction_id),
            CONSTRAINT chk_snapshot_metadata
                CHECK (jsonb_typeof(metadata) = 'object'),
            CONSTRAINT chk_snapshot_taxes
                CHECK (jsonb_typeof(tax_lines) = 'array'),
            CONSTRAINT chk_snapshot_fees
                CHECK (jsonb_typeof(fee_lines) = 'array')
);

-- =====================================================
-- INDEXES
-- =====================================================
-- Fast lookup to check whether a transaction is invoiced
CREATE INDEX idx_snapshot_transaction
    ON invoice_line_snapshots(transaction_id);