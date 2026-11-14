-- manual postgresql migration, applied once after v001 and v002.
-- in pgadmin query tool, select investment_portfolio and execute this entire file.
-- this file owns its transaction. if execution fails, issue rollback before retrying.
-- no existing wallet records, portfolio balances, or funding defaults are updated.

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

LOCK TABLE public.wallet_operations IN ACCESS EXCLUSIVE MODE;

-- reject an existing or partial v003 instead of silently skipping migration objects.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_attribute
        WHERE attrelid = 'public.wallet_operations'::regclass
          AND attname IN ('stripe_session_id', 'stripe_creation_request')
          AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.wallet_operations'::regclass
          AND conname IN ('wallet_operations_stripe_session_unique',
                          'wallet_operations_provider_completion_check')
    ) OR to_regclass('public.wallet_operations_stripe_session_unique') IS NOT NULL THEN
        RAISE EXCEPTION
            'Stripe migration objects already exist. Inspect the existing or partial migration before proceeding.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.wallet_operations'::regclass
          AND conname = 'wallet_operations_myfatoorah_completion_check'
          AND contype = 'c'
          AND convalidated
    ) THEN
        RAISE EXCEPTION
            'Expected validated V002 completion constraint is missing. Review the wallet schema before proceeding.';
    END IF;
END
$$;

ALTER TABLE public.wallet_operations
    ADD COLUMN stripe_session_id varchar(255),
    -- store only the original non-secret creation parameters for identical retries.
    -- never store api keys, authorization headers, client secrets, or card details.
    ADD COLUMN stripe_creation_request jsonb,

    -- postgresql allows multiple nulls while enforcing uniqueness for saved ids.
    ADD CONSTRAINT wallet_operations_stripe_session_unique
        UNIQUE (stripe_session_id),

    DROP CONSTRAINT wallet_operations_myfatoorah_completion_check,
    ADD CONSTRAINT wallet_operations_provider_completion_check
        CHECK (
            (
                NOT (operation_type = 'DEPOSIT'
                     AND payment_method = 'MYFATOORAH_SANDBOX'
                     AND status = 'COMPLETED')
                OR
                (provider_invoice_id IS NOT NULL
                    AND provider_invoice_id > 0
                    AND provider_payment_id IS NOT NULL
                    AND length(btrim(provider_payment_id)) > 0)
            )
            AND
            (
                NOT (operation_type = 'DEPOSIT'
                     AND payment_method = 'STRIPE_SANDBOX'
                     AND status = 'COMPLETED')
                OR
                (stripe_session_id IS NOT NULL
                    AND length(btrim(stripe_session_id)) > 0
                    AND provider_payment_id IS NOT NULL
                    AND length(btrim(provider_payment_id)) > 0)
            )
        );

-- all other v001/v002 constraints, indexes, foreign keys, and data remain intact.
-- provider identifiers alone do not prove payment; verify server-side before crediting.
COMMIT;

/*
optional read-only verification queries; run separately before/after migration.

select column_name, data_type, character_maximum_length, is_nullable
from information_schema.columns
where table_schema = 'public' and table_name = 'wallet_operations'
order by ordinal_position;

select conname, pg_get_constraintdef(oid) as definition
from pg_constraint
where conrelid = 'public.wallet_operations'::regclass
order by conname;

select indexname, indexdef
from pg_indexes
where schemaname = 'public' and tablename = 'wallet_operations'
order by indexname;
*/
