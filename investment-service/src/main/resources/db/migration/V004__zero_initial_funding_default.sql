-- run manually after v001 through v003; no existing values are changed
BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE public.portfolios
    ALTER COLUMN initial_funding SET DEFAULT 0.00;

COMMIT;

-- check the default separately after the migration
-- select column_default from information_schema.columns
-- where table_schema = 'public' and table_name = 'portfolios'
--   and column_name = 'initial_funding';
