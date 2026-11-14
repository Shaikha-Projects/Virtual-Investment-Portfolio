-- manual postgresql migration, applied once after v001.
-- run this file once with psql and stop on errors
-- this file owns its transaction; do not wrap it in another transaction.
-- no portfolio balances, funding defaults, or existing wallet records are updated.

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

LOCK TABLE public.wallet_operations IN ACCESS EXCLUSIVE MODE;

-- reject an existing or partial v002 before replacing any constraints.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_attribute
        WHERE attrelid = 'public.wallet_operations'::regclass
          AND attname IN ('provider_invoice_id', 'provider_payment_id',
                          'provider_invoice_status', 'provider_transaction_status',
                          'payment_url')
          AND NOT attisdropped
    ) OR EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.wallet_operations'::regclass
          AND conname IN ('wallet_operations_provider_invoice_unique',
                          'wallet_operations_provider_payment_unique',
                          'wallet_operations_deposit_status_check',
                          'wallet_operations_myfatoorah_completion_check')
    ) THEN
        RAISE EXCEPTION
            'MyFatoorah migration objects already exist. Inspect the existing or partial migration before proceeding.';
    END IF;

    IF (
        SELECT count(*)
        FROM pg_constraint
        WHERE conrelid = 'public.wallet_operations'::regclass
          AND conname IN ('wallet_operations_status_check',
                          'wallet_operations_outcome_check')
          AND contype = 'c'
          AND convalidated
    ) <> 2 THEN
        RAISE EXCEPTION
            'Expected validated V001 status and outcome constraints are missing. Review the wallet schema before proceeding.';
    END IF;
END
$$;

ALTER TABLE public.wallet_operations
    ADD COLUMN provider_invoice_id bigint,
    ADD COLUMN provider_payment_id varchar(128),
    ADD COLUMN provider_invoice_status varchar(32),
    ADD COLUMN provider_transaction_status varchar(32),
    ADD COLUMN payment_url text,

    ADD CONSTRAINT wallet_operations_provider_invoice_unique
        UNIQUE (provider_invoice_id),
    ADD CONSTRAINT wallet_operations_provider_payment_unique
        UNIQUE (provider_payment_id),

    DROP CONSTRAINT wallet_operations_status_check,
    DROP CONSTRAINT wallet_operations_outcome_check,

    ADD CONSTRAINT wallet_operations_status_check
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED')),

    ADD CONSTRAINT wallet_operations_outcome_check
        CHECK (
            (status = 'COMPLETED'
                AND failure_code IS NULL
                AND balance_after IS NOT NULL
                AND balance_after >= 0
                AND balance_after <> 'NaN'::numeric
                AND completed_at IS NOT NULL)
            OR
            (status IN ('FAILED', 'CANCELLED')
                AND failure_code IS NOT NULL
                AND length(btrim(failure_code)) > 0
                AND balance_after IS NULL
                AND completed_at IS NULL)
            OR
            (status = 'PENDING'
                AND failure_code IS NULL
                AND balance_after IS NULL
                AND completed_at IS NULL)
        ),

    ADD CONSTRAINT wallet_operations_deposit_status_check
        CHECK (status NOT IN ('PENDING', 'CANCELLED')
               OR operation_type = 'DEPOSIT'),

    -- database presence checks complement server-side payment verification.
    -- only the application can verify these ids with myfatoorah before crediting.
    ADD CONSTRAINT wallet_operations_myfatoorah_completion_check
        CHECK (
            NOT (operation_type = 'DEPOSIT'
                 AND payment_method = 'MYFATOORAH_SANDBOX'
                 AND status = 'COMPLETED')
            OR
            (provider_invoice_id IS NOT NULL
                AND provider_invoice_id > 0
                AND provider_payment_id IS NOT NULL
                AND length(btrim(provider_payment_id)) > 0)
        );

COMMIT;

/*
read-only verification queries. run separately before and after the migration.
pause application writers while taking and comparing the before/after results.

1. inspect the columns before migration (no provider columns should exist),
   and after migration (all five provider columns should be nullable).
select column_name, data_type, character_maximum_length, is_nullable
from information_schema.columns
where table_schema = 'public' and table_name = 'wallet_operations'
order by ordinal_position;

2. before migration, review the actual v001 status/outcome definitions.
   after migration, review their replacements and all preserved constraints.
select conname, contype, convalidated, pg_get_constraintdef(oid) as definition
from pg_constraint
where conrelid = 'public.wallet_operations'::regclass
order by conname;

3. compare indexes before/after: existing indexes must remain; the two new
   unique constraints create additional unique indexes allowing multiple nulls.
select indexname, indexdef
from pg_indexes
where schemaname = 'public' and tablename = 'wallet_operations'
order by indexname;

4. save and compare portfolio balances and funding defaults.
select id, user_id, cash_balance, reserved_cash, initial_funding
from public.portfolios order by id;
select column_name, column_default
from information_schema.columns
where table_schema = 'public' and table_name = 'portfolios'
  and column_name = 'initial_funding';

5. save and compare these fingerprints. explicitly select the original wallet
   columns so newly added null columns do not change the wallet fingerprint.
select count(*) as portfolio_count,
       md5(coalesce(string_agg(
           json_build_array(id, user_id, cash_balance, reserved_cash,
                            initial_funding)::text,
           e'\n' order by id), '')) as portfolio_fingerprint
from public.portfolios;

select count(*) as wallet_operation_count,
       md5(coalesce(string_agg(
           json_build_array(id, portfolio_id, reference, idempotency_key,
                            operation_type, amount, currency, payment_method,
                            status, failure_code, balance_after, created_at,
                            completed_at)::text,
           e'\n' order by id), '')) as wallet_history_fingerprint
from public.wallet_operations;

6. after migration: list the provider fields. existing rows should have nulls.
select id, provider_invoice_id, provider_payment_id, provider_invoice_status,
       provider_transaction_status, payment_url
from public.wallet_operations order by id;
*/
