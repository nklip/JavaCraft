-- Preserve V1 history and legacy SQL clients. Legacy buyers have no truthful contact
-- profile: leave those fields NULL rather than fabricate personal data. The API fails
-- closed when asked to return an incomplete legacy profile until explicitly repaired.
ALTER TABLE ordering.buyers
    ALTER COLUMN name TYPE VARCHAR(401),
    ADD COLUMN first_name VARCHAR(200),
    ADD COLUMN surname VARCHAR(200),
    ADD COLUMN email VARCHAR(254),
    ADD COLUMN phone VARCHAR(16),
    ADD COLUMN address_line1 VARCHAR(200),
    ADD COLUMN address_city VARCHAR(100),
    ADD COLUMN address_postal_code VARCHAR(20),
    ADD COLUMN address_country_code VARCHAR(2),
    ADD COLUMN creation_key VARCHAR(128),
    ADD CONSTRAINT buyers_creation_key_check CHECK (
        creation_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    ADD CONSTRAINT buyers_region_creation_key_unique UNIQUE (region, creation_key),
    ADD CONSTRAINT buyers_api_profile_check CHECK (
        creation_key IS NULL OR (
            first_name IS NOT NULL AND btrim(first_name) <> '' AND
            surname IS NOT NULL AND btrim(surname) <> '' AND
            email IS NOT NULL AND btrim(email) <> '' AND
            phone IS NOT NULL AND phone ~ '^\+[0-9]{7,15}$' AND
            address_line1 IS NOT NULL AND btrim(address_line1) <> '' AND
            address_city IS NOT NULL AND btrim(address_city) <> '' AND
            address_postal_code IS NOT NULL AND btrim(address_postal_code) <> '' AND
            address_country_code IS NOT NULL AND address_country_code ~ '^[A-Z]{2}$'
        )
    );
COMMENT ON COLUMN ordering.buyers.creation_key IS
    'Permanent region-scoped retry identity; NULL is reserved for pre-API legacy rows.';

-- Historical items did not capture unit cost. NULL means unknown, never zero profit;
-- new API acceptance always writes the immutable catalog unit cost explicitly.
ALTER TABLE ordering.order_items
    ADD COLUMN unit_cost NUMERIC(19, 2),
    ADD CONSTRAINT order_items_unit_cost_check CHECK (
        unit_cost >= 0 AND unit_cost <> 'NaN'::numeric
    );
COMMENT ON COLUMN ordering.order_items.unit_cost IS
    'Immutable acceptance-time cost; legacy NULL costs require reconciliation before profit crediting.';
