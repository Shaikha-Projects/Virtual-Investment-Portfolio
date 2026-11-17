-- Called only by investor_migration.py, inside one transaction.
-- Source JSON is staged in a temporary table. No persistent helper/schema is created.
LOCK TABLE public.users, public.assets, public.portfolios, public.holdings,
    public.transactions, public.investment_orders, public.wallet_operations,
    public.watchlist, public.price_alert, public.portfolio_snapshots,
    public.price_history, public.audit_logs, public.auth_outbox,
    public.investment_outbox, public.email_verification_tokens,
    public.password_reset_tokens IN SHARE ROW EXCLUSIVE MODE;

/* CATALOG_CHECK */

CREATE TEMP TABLE migration_before (
    table_name text, id bigint, row_data jsonb, PRIMARY KEY (table_name, id)
) ON COMMIT DROP;
CREATE TEMP TABLE migration_map (
    table_name text, source_id bigint, target_id bigint, reused boolean,
    expected jsonb, PRIMARY KEY (table_name, source_id)
) ON COMMIT DROP;
-- Avoid quadratic scans while matching thousands of shared historical records.
-- This index exists only in this session; no AWS application index/schema is changed.
CREATE TEMP TABLE migration_history_lookup ON COMMIT DROP AS
    SELECT asset_id,recorded_at,price,id FROM public.price_history;
CREATE INDEX ON migration_history_lookup(asset_id,recorded_at,price,id);

DO $migration$
DECLARE
    document jsonb := (SELECT doc FROM migration_input);
    data jsonb := document->'data'->'tables';
    t text; r jsonb; transformed jsonb; parent text; field text;
    mapped bigint; chosen bigint; matched integer; reuse boolean;
    sequence_name text; sequence_last bigint; sequence_called boolean;
    sequence_step bigint; next_id bigint;
BEGIN
    IF (SELECT count(*) FROM public.users WHERE id=1 AND role='ADMIN' AND status='ACTIVE') <> 1 THEN
        RAISE EXCEPTION 'MIGRATION: administrator_guard';
    END IF;
    IF EXISTS (SELECT 1 FROM public.users
               WHERE lower(email_address)=lower(data->'users'->0->>'email_address')) THEN
        RAISE EXCEPTION 'MIGRATION: existing_email';
    END IF;

    -- Snapshot every existing public row, including tokens/outboxes that we never import.
    FOR t IN SELECT value FROM jsonb_array_elements_text(document->'data'->'catalog'->'tables') LOOP
        EXECUTE format('INSERT INTO migration_before SELECT %L, id, to_jsonb(x) FROM public.%I x', t, t);
    END LOOP;

    FOREACH t IN ARRAY ARRAY['users','assets','portfolios','holdings','transactions',
        'investment_orders','wallet_operations','watchlist','price_alert',
        'portfolio_snapshots','price_history','audit_logs'] LOOP
        FOR r IN SELECT value FROM jsonb_array_elements(data->t) ORDER BY (value->>'id')::bigint LOOP
            transformed := r;
            reuse := false;
            chosen := NULL;
            FOREACH field IN ARRAY ARRAY['user_id','portfolio_id','asset_id'] LOOP
                IF r ? field THEN
                    parent := CASE field WHEN 'user_id' THEN 'users'
                        WHEN 'portfolio_id' THEN 'portfolios' ELSE 'assets' END;
                    SELECT target_id INTO mapped FROM migration_map
                        WHERE table_name=parent AND source_id=(r->>field)::bigint;
                    IF mapped IS NULL THEN RAISE EXCEPTION 'MIGRATION: missing_parent'; END IF;
                    transformed := jsonb_set(transformed, ARRAY[field], to_jsonb(mapped));
                END IF;
            END LOOP;

            IF t='assets' THEN
                SELECT count(*), min(id) INTO matched, chosen FROM public.assets
                    WHERE lower(symbol)=lower(r->>'symbol');
                IF matched>1 THEN RAISE EXCEPTION 'MIGRATION: ambiguous_asset'; END IF;
                IF matched=1 THEN
                    IF NOT EXISTS (SELECT 1 FROM public.assets WHERE id=chosen
                        AND symbol=r->>'symbol' AND asset_type=r->>'asset_type') THEN
                        RAISE EXCEPTION 'MIGRATION: incompatible_asset';
                    END IF;
                    reuse := true; -- Never replace AWS market prices, status, name or history.
                END IF;
            ELSIF t='price_history' THEN
                SELECT min(id) INTO chosen FROM migration_history_lookup
                    WHERE asset_id=(transformed->>'asset_id')::bigint
                    AND recorded_at=(r->>'recorded_at')::timestamp
                    AND price=(r->>'price')::numeric;
                reuse := chosen IS NOT NULL;
            END IF;

            IF NOT reuse THEN
                -- Allocate above both existing IDs and the sequence's already-reserved range.
                EXECUTE format('SELECT coalesce(max(id),0)+1 FROM public.%I',t) INTO next_id;
                sequence_name := pg_get_serial_sequence(format('public.%I',t),'id');
                IF sequence_name IS NULL THEN RAISE EXCEPTION 'MIGRATION: missing_sequence'; END IF;
                EXECUTE format('SELECT last_value,is_called FROM %s',sequence_name)
                    INTO sequence_last,sequence_called;
                SELECT seqincrement INTO sequence_step FROM pg_sequence WHERE seqrelid=sequence_name::regclass;
                IF sequence_step<>1 THEN RAISE EXCEPTION 'MIGRATION: unsupported_sequence'; END IF;
                chosen := greatest(next_id,sequence_last + CASE WHEN sequence_called THEN 1 ELSE 0 END);
                -- Include IDs allocated earlier in this transaction, without calling nextval.
                transformed := jsonb_set(transformed, '{id}', to_jsonb(chosen));
                EXECUTE format('INSERT INTO public.%I SELECT (jsonb_populate_record(NULL::public.%I,$1)).*',t,t)
                    USING transformed;
                IF t='price_history' THEN
                    INSERT INTO migration_history_lookup VALUES((transformed->>'asset_id')::bigint,
                        (transformed->>'recorded_at')::timestamp,(transformed->>'price')::numeric,chosen);
                END IF;
            END IF;
            INSERT INTO migration_map VALUES(t,(r->>'id')::bigint,chosen,reuse,transformed);
        END LOOP;
    END LOOP;

    -- Validate every inserted field (including the BCrypt hash and exact financial decimals).
    FOR t IN SELECT DISTINCT table_name FROM migration_map LOOP
        EXECUTE format('SELECT count(*) FROM migration_map m WHERE m.table_name=%L AND NOT m.reused
            AND NOT EXISTS (SELECT 1 FROM public.%I x WHERE x.id=m.target_id AND to_jsonb(x)=m.expected)',t,t)
            INTO matched;
        IF matched<>0 THEN RAISE EXCEPTION 'MIGRATION: inserted_row_mismatch'; END IF;
        IF (SELECT count(*) FROM migration_map WHERE table_name=t)<>jsonb_array_length(data->t) THEN
            RAISE EXCEPTION 'MIGRATION: count_mismatch';
        END IF;
    END LOOP;
    FOR t IN SELECT DISTINCT table_name FROM migration_before LOOP
        EXECUTE format('SELECT count(*) FROM migration_before b WHERE b.table_name=%L
            AND NOT EXISTS (SELECT 1 FROM public.%I x WHERE x.id=b.id AND to_jsonb(x)=b.row_data)',t,t)
            INTO matched;
        IF matched<>0 THEN RAISE EXCEPTION 'MIGRATION: existing_row_changed'; END IF;
    END LOOP;

    -- ALTER SEQUENCE RESTART is transactional. setval/nextval are deliberately NOT used.
    FOR t IN SELECT DISTINCT table_name FROM migration_map WHERE NOT reused LOOP
        sequence_name := pg_get_serial_sequence(format('public.%I',t),'id');
        EXECUTE format('SELECT last_value,is_called FROM %s',sequence_name)
            INTO sequence_last,sequence_called;
        EXECUTE format('SELECT coalesce(max(id),0)+1 FROM public.%I',t) INTO next_id;
        next_id := greatest(next_id,sequence_last + CASE WHEN sequence_called THEN 1 ELSE 0 END);
        EXECUTE format('ALTER SEQUENCE %s RESTART WITH %s',sequence_name,next_id);
    END LOOP;
END
$migration$;

SELECT jsonb_build_object(
    'administrator_unchanged',true,'all_existing_rows_unchanged',true,
    'password_hash_preserved',true,'foreign_keys_enforced',true,
    'new_user_id',(SELECT target_id FROM migration_map WHERE table_name='users'),
    'counts',(SELECT jsonb_object_agg(table_name,counts) FROM (
        SELECT table_name,jsonb_build_object('source',count(*),'inserted',count(*) FILTER(WHERE NOT reused),
            'reused',count(*) FILTER(WHERE reused)) counts FROM migration_map GROUP BY table_name) s),
    'id_map',(SELECT jsonb_agg(jsonb_build_object('table',table_name,'source',source_id,'target',target_id,
        'reused',reused) ORDER BY table_name,source_id) FROM migration_map));
