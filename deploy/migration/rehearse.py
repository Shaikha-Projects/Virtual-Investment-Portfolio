#!/usr/bin/env python3
"""Validate a private bundle against a disposable, network-isolated PostgreSQL instance.

Never connects to AWS. Only SELECT/pg_dump are issued to the local source database.
The temporary database has synthetic existing users/admin and deliberate ID conflicts.
"""
import argparse
import base64
import binascii
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import struct
import time
import zlib

import investor_migration as m


def snapshot(container):
    names=json.loads(m.read_query(container,"SELECT jsonb_agg(sequencename ORDER BY sequencename) FROM pg_sequences WHERE schemaname='public'"))
    sequences=','.join(f"'{name}',(SELECT jsonb_build_object('last_value',last_value,'is_called',is_called,'log_cnt',log_cnt) FROM public.{name})" for name in names)
    query = "SELECT jsonb_build_object('rows',jsonb_build_object(" + ','.join(
        f"'{t}',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY id),'[]'::jsonb) FROM public.{t} r)"
        for t in m.ALL_TABLES) + "),'sequences',jsonb_build_object("+sequences+"))"
    return hashlib.sha256(m.read_query(container,query)).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path,required=True)
    args=parser.parse_args()
    raw,document=m.load_bundle(args.bundle)
    name='investment-migration-rehearsal-'+secrets.token_hex(6)
    results=[]
    def passed(text):
        results.append(text)
        print('PASS:',text,flush=True)
    created=False
    try:
        m.docker(['run','-d','--name',name,'--network','none','--memory','512m',
                  '--mount','type=tmpfs,destination=/var/lib/postgresql,tmpfs-size=268435456',
                  '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB='+m.DATABASE,'postgres:18.6'])
        created=True
        inspection=json.loads(m.docker(['inspect',name]))[0]
        if inspection['HostConfig']['PortBindings'] or any(x['Type']!='tmpfs' for x in inspection['Mounts']):
            raise m.MigrationError('Rehearsal isolation assertion failed')
        for attempt in range(60):
            ready=subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres','-d',m.DATABASE],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if ready.returncode==0:
                break
            time.sleep(1)
        else:
            raise m.MigrationError('Rehearsal database did not become ready')
        schema=m.docker(['exec',m.SOURCE,'pg_dump','-U','postgres','-d',m.DATABASE,
                         '--schema-only','--no-owner','--no-privileges'])
        m.psql(name,schema.decode())
        # No real administrator/personal data is used to seed the target.
        literal="'"+raw.decode().replace("'","''")+"'::jsonb"
        seed=f"""BEGIN;
        INSERT INTO public.users(id,created_at,email_address,first_name,last_name,password,phone_number,role,status,updated_at)
          VALUES(1,'2026-01-01','admin@fixture.invalid','Synthetic','Admin','synthetic-admin-hash','10000000001','ADMIN','ACTIVE','2026-01-01'),
                (2,'2026-01-01','existing@fixture.invalid','Synthetic','Investor','synthetic-existing-hash','10000000002','USER','ACTIVE','2026-01-01');
        INSERT INTO public.assets SELECT (jsonb_populate_record(NULL::public.assets,
          jsonb_set(jsonb_set(r,'{{id}}',to_jsonb((r->>'id')::bigint+100)),
                    '{{current_price}}',to_jsonb((r->>'current_price')::numeric+5)))).*
          FROM jsonb_array_elements(({literal})->'data'->'tables'->'assets') WITH ORDINALITY AS a(r,n)
          WHERE n < jsonb_array_length(({literal})->'data'->'tables'->'assets');
        INSERT INTO public.portfolios(id,cash_balance,created_at,updated_at,user_id,reserved_cash,initial_funding)
          VALUES(1,321,'2026-01-01','2026-01-01',2,0,321);
        ALTER SEQUENCE public.users_id_seq RESTART WITH 50;
        INSERT INTO public.price_history(id,asset_id,price,recorded_at)
          SELECT 900000000,(r->>'asset_id')::bigint+100,(r->>'price')::numeric,(r->>'recorded_at')::timestamp
          FROM jsonb_array_elements(({literal})->'data'->'tables'->'price_history') r
          WHERE (r->>'asset_id')::bigint+100 IN(SELECT id FROM public.assets) LIMIT 1;
        COMMIT;"""
        m.psql(name,seed)
        passed('isolated target seeded with ADMIN 1, occupied USER 2, shuffled asset IDs and an advanced sequence')
        m.preflight(name,raw,document)
        passed('read-only catalog/admin/email/asset/wallet preflight')
        m.psql(name,"CREATE TABLE public.unexpected_migration_fixture(id bigint PRIMARY KEY)")
        try:
            m.preflight(name,raw,document)
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Schema drift was not rejected')
        m.psql(name,"DROP TABLE public.unexpected_migration_fixture")
        passed('unknown schema/table rejected before any import')
        wallet=f"""INSERT INTO public.wallet_operations SELECT (jsonb_populate_record(NULL::public.wallet_operations,
            jsonb_set(jsonb_set(r,'{{id}}','999999999'::jsonb),'{{portfolio_id}}','1'::jsonb))).*
            FROM jsonb_array_elements(({literal})->'data'->'tables'->'wallet_operations') r LIMIT 1"""
        m.psql(name,wallet)
        try:
            m.preflight(name,raw,document)
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Wallet uniqueness conflict was not rejected')
        m.psql(name,"DELETE FROM public.wallet_operations WHERE id=999999999")
        passed('existing wallet UUID/provider collision rejected in read-only preflight')
        def png_chunk(kind,body):
            return struct.pack('!I',len(body))+kind+body+struct.pack('!I',binascii.crc32(kind+body)&0xffffffff)
        png=(b'\x89PNG\r\n\x1a\n'+png_chunk(b'IHDR',struct.pack('!IIBBBBB',1,1,8,2,0,0,0))+
             png_chunk(b'IDAT',zlib.compress(b'\0\0\0\0'))+png_chunk(b'IEND',b''))
        profile={'profile':{'path':'uploads/profile-pictures/11111111-1111-4111-8111-111111111111.png',
                            'base64':base64.b64encode(png).decode(),'sha256':hashlib.sha256(png).hexdigest()}}
        if not m.install_profile(profile,name) or m.install_profile(profile,name) is not None:
            raise m.MigrationError('Profile copy/reuse failed')
        conflicting={'profile':dict(profile['profile'],base64=base64.b64encode(png+b'conflict').decode())}
        try:
            m.install_profile(conflicting,name)
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Conflicting image was not rejected')
        if m.container_file(name,profile['profile']['path'])!=png:
            raise m.MigrationError('Existing image was modified')
        passed('profile bytes copied/reused; different existing image is never overwritten')
        try:
            m.install_profile(profile,'investment-portfolio-auth-service-1')
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Local profile write guard failed')
        try:
            m.apply(argparse.Namespace(approve_investor_2=False))
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Approval guard failed')
        passed('approval required and local Auth profile writes forbidden before any Docker call')
        before=snapshot(name)
        simulated=json.loads(m.psql(name,m.make_sql(raw,commit=False)))
        if snapshot(name)!=before:
            raise m.MigrationError('Rollback rehearsal changed target rows/sequences')
        passed('complete import rehearsal rolls back all rows AND sequence state')
        try:
            m.psql(name,m.make_sql(raw,fail_after_sequences=True))
        except m.MigrationError as error:
            if str(error)!='intentional_rehearsal_failure':
                raise
        else:
            raise m.MigrationError('Expected forced transaction failure')
        if snapshot(name)!=before:
            raise m.MigrationError('Failure rollback changed target rows/sequences')
        passed('forced failure after sequence changes leaves rows and sequences unchanged')
        saved=m.backup(name)
        # Prove backup restoration, not merely that a file exists.
        m.docker(['exec',name,'createdb','-U','postgres','migration_backup_check'])
        with saved.open('rb') as stream:
            restored=subprocess.run(['docker','exec','-i',name,'pg_restore','-U','postgres',
                                     '-d','migration_backup_check','--exit-on-error'],
                                    stdin=stream,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if restored.returncode:
            raise m.MigrationError('Rehearsal backup restoration failed')
        check=m.docker(['exec',name,'psql','-X','-qAt','-U','postgres','-d','migration_backup_check',
                        '-c',"SELECT count(*)=1 FROM users WHERE id=1 AND role='ADMIN' AND password='synthetic-admin-hash'"])
        if check.strip()!=b't':
            raise m.MigrationError('Restored backup verification failed')
        passed('automatic custom-format backup validated AND restored into another disposable database')
        result=json.loads(m.psql(name,m.make_sql(raw)))
        if result['new_user_id']!=50 or not all(result[k] for k in ['administrator_unchanged','all_existing_rows_unchanged','password_hash_preserved','foreign_keys_enforced']):
            raise m.MigrationError('Committed migration validation failed')
        source_counts={t:len(rows) for t,rows in document['data']['tables'].items()}
        for t,count in source_counts.items():
            if count and result['counts'][t]['source']!=count:
                raise m.MigrationError('Committed source count differs')
        passed('committed import remaps investor to ID 50 and preserves ADMIN/all existing rows, exact hash, decimals and FKs')
        if any(x['table']=='assets' and x['reused'] and x['target']!=x['source']+100 for x in result['id_map']):
            raise m.MigrationError('Asset symbol remapping failed')
        passed('shared assets reused at different IDs without replacing existing market values')
        if result['counts']['assets']['inserted']!=1 or result['counts']['price_history']['reused']<1:
            raise m.MigrationError('Missing asset insert or shared history reuse failed')
        passed('missing asset inserted and identical existing price history reused')
        after=snapshot(name)
        try:
            m.psql(name,m.make_sql(raw))
        except m.MigrationError as error:
            if str(error)!='existing_email':
                raise
        else:
            raise m.MigrationError('Repeated import was not rejected')
        if snapshot(name)!=after:
            raise m.MigrationError('Repeated import changed existing data')
        passed('repeated import rejected without duplicate accounts or changed rows/sequences')
        # FK enforcement must remain active: try an invalid child row inside a rolled-back transaction.
        try:
            m.psql(name,"BEGIN; INSERT INTO watchlist(id,created_at,user_id,asset_id) VALUES(999999999,now(),999999999,999999999); ROLLBACK;")
        except m.MigrationError:
            pass
        else:
            raise m.MigrationError('Foreign keys were not enforced')
        passed('database rejects orphan relationships; constraints remain enabled')
        report={'source_counts':source_counts,'checks':results,'new_user_id':result['new_user_id'],
                'missing_source_profile':bool((document.get('profile') or {}).get('missing')),
                'aws_accessed':False,'investor_password_login_tested':False}
        report_path=m.PRIVATE / f'rehearsal-report-{m.stamp()}.json'
        m.private_write(report_path,json.dumps(report,indent=2).encode())
        print('Private rehearsal report:',report_path)
        print('Checks passed:',len(results))
    finally:
        if created:
            m.docker(['rm','-f',name])  # Only this randomly named tmpfs test container; no existing volume.
    return 0


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except m.MigrationError as error:
        print('Rehearsal stopped:',error)
        raise SystemExit(1)
