-- Shared nutrition estimates keyed by the canonical dish digest. Only the digest and the
-- metrics are stored: dish text never leaves the request. See docs/DATA_CONTRACT.md.
CREATE TABLE nutrition_estimates (
    input_hash CHAR(64) PRIMARY KEY,
    basis VARCHAR(16) NOT NULL CHECK (basis = 'PER_100G'),
    energy_kcal NUMERIC(10, 2),
    carbs_g NUMERIC(10, 2),
    protein_g NUMERIC(10, 2),
    fat_g NUMERIC(10, 2),
    fiber_g NUMERIC(10, 2),
    sugars_g NUMERIC(10, 2),
    salt_mg NUMERIC(12, 2),
    tips VARCHAR(600) NOT NULL CHECK (length(trim(tips)) > 0),
    provider VARCHAR(40) NOT NULL,
    provider_version VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX nutrition_estimates_created_idx ON nutrition_estimates(created_at);
