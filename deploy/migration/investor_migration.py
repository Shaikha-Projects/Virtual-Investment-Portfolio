#!/usr/bin/env python3
"""Investor-only PostgreSQL migration. Standard library + Docker; never reads .env.

Default operations are export (local read-only) and plan (target read-only).
apply requires an explicit approval flag and takes a verified private backup first.
"""
import argparse
import base64
import getpass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request

SOURCE = 'investment-portfolio-postgres_db-1'
TARGET = 'investment-aws-postgres_db-1'
DATABASE = 'investment_portfolio'
PRIVATE = Path('.migration-private')
TABLES = ['users','assets','portfolios','holdings','transactions','investment_orders',
          'wallet_operations','watchlist','price_alert','portfolio_snapshots','price_history','audit_logs']
EXCLUDED = ['auth_outbox','investment_outbox','email_verification_tokens','password_reset_tokens']
ALL_TABLES = sorted(TABLES + EXCLUDED)
FK = {
    'portfolios': {'user_id':'users'},
    'holdings': {'portfolio_id':'portfolios','asset_id':'assets'},
    'transactions': {'portfolio_id':'portfolios','asset_id':'assets'},
    'investment_orders': {'portfolio_id':'portfolios','asset_id':'assets'},
    'wallet_operations': {'portfolio_id':'portfolios'},
    'watchlist': {'user_id':'users','asset_id':'assets'},
    'price_alert': {'portfolio_id':'portfolios','asset_id':'assets'},
    'portfolio_snapshots': {'portfolio_id':'portfolios'},
    'price_history': {'asset_id':'assets'},
    'email_verification_tokens': {'user_id':'users'},
    'password_reset_tokens': {'user_id':'users'},
}
CATALOG = """SELECT jsonb_build_object(
'tables',(SELECT jsonb_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'),
'columns',(SELECT jsonb_agg(to_jsonb(c) ORDER BY table_name,ordinal_position) FROM
 (SELECT table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default,
 is_identity,identity_generation,numeric_precision,numeric_scale,character_maximum_length
 FROM information_schema.columns WHERE table_schema='public') c),
'constraints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY table_name,definition) FROM
 (SELECT conrelid::regclass::text table_name,contype::text kind,pg_get_constraintdef(oid) definition,
 convalidated validated FROM pg_constraint WHERE connamespace='public'::regnamespace) c),
'triggers',(SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
 WHERE c.relnamespace='public'::regnamespace AND NOT t.tgisinternal))"""


class MigrationError(Exception):
    pass


def stamp():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')


def private_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Refuse overwrites and symlinks. Python writes bytes without PowerShell transcoding.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(content)


def docker(arguments, *, data=None, output=None):
    result = subprocess.run(['docker', *arguments], input=data,
                            stdout=output if output is not None else subprocess.PIPE,
                            stderr=subprocess.PIPE)
    if result.returncode:
        error = result.stderr.decode('utf-8', errors='replace')
        code = re.search(r'MIGRATION: ([a-z_]+)', error)
        constraint = re.search(r'(?:unique|foreign key|check) constraint "([a-zA-Z0-9_]+)"', error)
        reason = code.group(1) if code else ('constraint '+constraint.group(1) if constraint else 'Docker/PostgreSQL operation failed')
        # Never print PostgreSQL DETAIL, SQL, rows, credentials or provider identifiers.
        raise MigrationError(reason)
    return result.stdout


def psql(container, sql):
    return docker(['exec','-i',container,'psql','-X','-qAt','-U','postgres','-d',DATABASE,
                   '-v','ON_ERROR_STOP=1','--set=VERBOSITY=terse'], data=sql.encode())


def read_query(container, query):
    return psql(container, 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'+query+';\nROLLBACK;').strip()


def target_guard(container):
    if container != TARGET and not re.fullmatch(r'investment-migration-rehearsal-[a-zA-Z0-9_-]+', container):
        raise MigrationError('Target must be the AWS container or an isolated rehearsal container; local source is forbidden')


def check_catalog(catalog):
    if catalog['tables'] != ALL_TABLES or catalog['triggers']:
        raise MigrationError('Unexpected table/trigger: migration scope must be reviewed')
    discovered = {}
    for c in catalog['constraints']:
        if not c['validated']:
            raise MigrationError('Unvalidated constraint requires review')
        if c['kind'] == 'f':
            match = re.fullmatch(r'FOREIGN KEY \((\w+)\) REFERENCES (?:public\.)?(\w+)\(id\)(?: ON DELETE RESTRICT)?', c['definition'])
            if not match:
                raise MigrationError('Unsupported foreign-key shape')
            discovered.setdefault(c['table_name'], {})[match[1]] = match[2]
    if discovered != FK:
        raise MigrationError('Foreign-key graph changed; refusing to omit related data')


def load_bundle(path):
    path=Path(path)
    raw = path.read_bytes()
    checksum=Path(str(path)+'.sha256').read_text().strip()
    if not re.fullmatch('[a-f0-9]{64}',checksum) or hashlib.sha256(raw).hexdigest()!=checksum:
        raise MigrationError('Bundle checksum mismatch; no target changes made')
    document = json.loads(raw)
    if document.get('format') != 1 or document.get('source_user_id') != 2:
        raise MigrationError('Wrong bundle format or source investor')
    catalog = document['data']['catalog']
    check_catalog(catalog)
    tables = document['data']['tables']
    if set(tables) != set(TABLES):
        raise MigrationError('Bundle table scope is incomplete')
    users = tables['users']
    if len(users) != 1 or users[0]['id'] != 2 or users[0]['role'] != 'USER' or users[0]['status'] != 'ACTIVE':
        raise MigrationError('Source must be exactly the active USER with ID 2')
    if not re.match(r'^\$2[aby]\$\d\d\$', users[0]['password']):
        raise MigrationError('Source password is not an existing BCrypt hash')
    if len(tables['portfolios']) != 1 or tables['portfolios'][0]['user_id'] != 2:
        raise MigrationError('Expected one investor-owned portfolio')
    if any(document['data']['pending_events'].values()):
        raise MigrationError('Unpublished outbox events: let normal publishers drain before export')
    if any(r['status'] == 'PENDING' for r in tables['wallet_operations']):
        raise MigrationError('Resolve pending wallet payments through the normal application before export')
    ids = {t: {r['id'] for r in rows} for t, rows in tables.items()}
    for table, rows in tables.items():
        columns = {c['column_name'] for c in catalog['columns'] if c['table_name'] == table}
        if len(ids[table]) != len(rows) or any(set(r) != columns for r in rows):
            raise MigrationError('Duplicate IDs or incomplete row columns')
        for row in rows:
            for column, parent in FK.get(table, {}).items():
                if row[column] not in ids[parent]:
                    raise MigrationError('Source relationship is outside the investor bundle')
        if table == 'audit_logs' and any(r['user_email'] != users[0]['email_address'] for r in rows):
            raise MigrationError('Audit ownership mismatch')
    profile = document.get('profile')
    if bool(users[0]['profile_picture']) != bool(profile):
        raise MigrationError('Missing profile picture payload')
    if profile:
        safe_profile_path(profile['path'])
        if profile['path'] != users[0]['profile_picture'].replace('\\','/'):
            raise MigrationError('Profile picture integrity mismatch')
        if not profile.get('missing'):
            content = base64.b64decode(profile['base64'], validate=True)
            if hashlib.sha256(content).hexdigest() != profile['sha256']:
                raise MigrationError('Profile picture integrity mismatch')
    return raw, document


def safe_profile_path(value):
    path = PurePosixPath(value.replace('\\','/'))
    if len(path.parts) != 3 or path.parts[:2] != ('uploads','profile-pictures') or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,180}', path.name) or '..' in path.name:
        raise MigrationError('Unsafe profile image path')
    return path


def container_file(container, path):
    # docker cp works even if a container is stopped; archive bytes never reach stdout.
    try:
        archive = docker(['cp', f'{container}:/app/{path}', '-'])
    except MigrationError:
        return None
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        files = tar.getmembers()
        if len(files) != 1 or not files[0].isfile() or files[0].size > 6*1024*1024:
            raise MigrationError('Unexpected profile image archive')
        return tar.extractfile(files[0]).read()


def export_bundle(args):
    catalog = json.loads(read_query(SOURCE, CATALOG))
    check_catalog(catalog)
    filters = {'users':'id=2', 'portfolios':'user_id=2','watchlist':'user_id=2',
               'audit_logs':'user_email=(SELECT email_address FROM public.users WHERE id=2)'}
    for table in ['holdings','transactions','investment_orders','wallet_operations','price_alert','portfolio_snapshots']:
        filters[table] = 'portfolio_id IN (SELECT id FROM public.portfolios WHERE user_id=2)'
    asset_refs = ' UNION '.join(f'SELECT asset_id FROM public.{t} WHERE {filters[t]}'
                              for t in ['holdings','transactions','investment_orders','price_alert','watchlist'])
    filters['assets'] = f'id IN ({asset_refs})'
    filters['price_history'] = f'asset_id IN ({asset_refs})'
    pieces = []
    for table in TABLES:
        pieces.extend([f"'{table}'", f"(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY id),'[]'::jsonb) FROM (SELECT * FROM public.{table} WHERE {filters[table]}) r)"])
    query = f"""SELECT jsonb_build_object('catalog',({CATALOG}),
        'pending_events',jsonb_build_object('auth',(SELECT count(*) FROM public.auth_outbox WHERE NOT published),
        'investment',(SELECT count(*) FROM public.investment_outbox WHERE NOT published)),
        'tables',jsonb_build_object({','.join(pieces)}))"""
    data = read_query(SOURCE, query)  # One repeatable-read, read-only database snapshot.
    parsed = json.loads(data)
    selected = parsed['tables']['users']
    if len(selected) != 1:
        raise MigrationError('Investor 2 not found')
    profile = None
    if selected[0]['profile_picture']:
        path = safe_profile_path(selected[0]['profile_picture'])
        content = container_file(args.auth_container, path)
        if content is None:
            local = Path(__file__).resolve().parents[2].joinpath(*path.parts)
            candidates = [local, Path(__file__).resolve().parents[2].joinpath('auth-service',*path.parts)]
            content = next((p.read_bytes() for p in candidates if p.is_file() and not p.is_symlink()),None)
        if content is not None and (not content or len(content) > 6*1024*1024):
            raise MigrationError('Invalid profile image size')
        profile = ({'path':str(path),'missing':True} if content is None else
                   {'path':str(path),'sha256':hashlib.sha256(content).hexdigest(),
                    'base64':base64.b64encode(content).decode()})
    # Keep PostgreSQL's original JSON bytes for exact decimal precision. Do NOT round-trip financial rows through float.
    raw = (b'{"format":1,"source_user_id":2,"exported_at":'+json.dumps(stamp()).encode()+
           b',"data":'+data+b',"profile":'+json.dumps(profile).encode()+b'}')
    path = PRIVATE / f'investor-2-{stamp()}.json'
    private_write(path, raw)
    checksum_created=False
    try:
        checksum_path=Path(str(path)+'.sha256')
        private_write(checksum_path,(hashlib.sha256(raw).hexdigest()+'\n').encode())
        checksum_created=True
        _, document = load_bundle(path)
    except Exception:
        path.unlink()  # Only the just-created failed export, never existing user files.
        if checksum_created:
            checksum_path.unlink()
        raise
    print('Private bundle:', path)
    print('SHA256:', hashlib.sha256(raw).hexdigest())
    print('Source counts:', json.dumps({t:len(r) for t,r in document['data']['tables'].items()}, sort_keys=True))
    print('Profile image included:', bool(profile and not profile.get('missing')))
    if profile and profile.get('missing'):
        print('WARNING: referenced source profile PNG is missing; its database reference is preserved. Import needs explicit acknowledgement.')
    print('Local database accessed only through read-only transactions.')
    return path


def preflight(container, raw, document):
    target_guard(container)
    catalog = json.loads(read_query(container, CATALOG))
    if catalog != document['data']['catalog']:
        raise MigrationError('Target schema differs from the inspected source; no changes made')
    # A read-only plan needs only natural conflict keys, not the full financial history/hash/image.
    rows=document['data']['tables']
    keys={'email':rows['users'][0]['email_address'],
          'assets':[{k:r[k] for k in ['symbol','asset_type']} for r in rows['assets']],
          'wallet_operations':[{k:r[k] for k in ['reference','provider_invoice_id','provider_payment_id','stripe_session_id']}
                               for r in rows['wallet_operations']]}
    literal = "'"+json.dumps(keys).replace("'", "''")+"'::jsonb"
    query = f"""WITH b AS (SELECT {literal} d) SELECT jsonb_build_object(
        'administrator_ok',(SELECT count(*)=1 FROM public.users WHERE id=1 AND role='ADMIN' AND status='ACTIVE'),
        'email_conflict',EXISTS(SELECT 1 FROM public.users,b WHERE lower(email_address)=lower(d->>'email')),
        'incompatible_assets',(SELECT count(*) FROM b,jsonb_array_elements(d->'assets') r
          WHERE EXISTS(SELECT 1 FROM public.assets a WHERE lower(a.symbol)=lower(r->>'symbol')
            AND (a.symbol<>r->>'symbol' OR a.asset_type<>r->>'asset_type'))),
        'wallet_conflicts',(SELECT count(*) FROM b,jsonb_array_elements(d->'wallet_operations') r
          WHERE EXISTS(SELECT 1 FROM public.wallet_operations w WHERE w.reference=(r->>'reference')::uuid
            OR w.provider_invoice_id=(r->>'provider_invoice_id')::bigint
            OR w.provider_payment_id=r->>'provider_payment_id' OR w.stripe_session_id=r->>'stripe_session_id')))"""
    result = json.loads(read_query(container, query))
    if not result['administrator_ok'] or result['email_conflict'] or result['incompatible_assets'] or result['wallet_conflicts']:
        raise MigrationError('Preflight rejected: administrator guard, existing investor email, incompatible asset or wallet reference collision')
    return result


def make_sql(raw, *, commit=True, fail_after_sequences=False):
    template = Path(__file__).with_name('import_investor.sql').read_text()
    guard = f"""DO $catalog$ BEGIN IF ({CATALOG}) <> (SELECT doc->'data'->'catalog' FROM migration_input)
        THEN RAISE EXCEPTION 'MIGRATION: schema_changed'; END IF; END $catalog$;"""
    template = template.replace('/* CATALOG_CHECK */', guard)
    literal = "'"+raw.decode().replace("'", "''")+"'::jsonb"
    finish = "DO $$ BEGIN RAISE EXCEPTION 'MIGRATION: intentional_rehearsal_failure'; END $$;\n" if fail_after_sequences else ''
    return ("BEGIN; SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='180s'; "
            "SET LOCAL standard_conforming_strings=on;\n"
            "CREATE TEMP TABLE migration_input(doc jsonb) ON COMMIT DROP;\n"
            f"INSERT INTO migration_input VALUES ({literal});\n"+template+'\n'+finish+
            ('COMMIT;' if commit else 'ROLLBACK;'))


def backup(container):
    prefix='rehearsal' if container.startswith('investment-migration-rehearsal-') else 'aws'
    path = PRIVATE / f'{prefix}-before-investor-{stamp()}.dump'
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with os.fdopen(os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600), 'wb') as stream:
        docker(['exec',container,'pg_dump','-U','postgres','-d',DATABASE,'-Fc'], output=stream)
    if path.stat().st_size < 100:
        raise MigrationError('Backup is empty; import refused')
    with path.open('rb') as stream:
        result = subprocess.run(['docker','exec','-i',container,'pg_restore','--list'],
                                stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode or b'TABLE' not in result.stdout:
        raise MigrationError('Backup archive validation failed; import refused')
    print('Verified pre-import backup:', path)
    return path


def install_profile(document, container):
    if container!='investment-aws-auth-service-1' and not re.fullmatch(r'investment-migration-rehearsal-[a-zA-Z0-9_-]+',container):
        raise MigrationError('Profile writes are restricted to AWS Auth or the isolated rehearsal container')
    profile = document.get('profile')
    if not profile:
        return None
    if profile.get('missing'):
        return None  # apply requires a separate acknowledgement; the original DB reference is retained.
    path = safe_profile_path(profile['path'])
    expected = base64.b64decode(profile['base64'], validate=True)
    existing = container_file(container, path)
    if existing is not None:
        if existing != expected:
            raise MigrationError('Target profile filename already exists with different bytes; nothing overwritten')
        return None
    target = '/app/'+str(path)
    # noclobber is atomic against an existing file/symlink. No overwrites, even on retries.
    docker(['exec','-i',container,'bash','-c',
            'set -eu; umask 022; mkdir -p /app/uploads/profile-pictures; set -C; cat > "$1"',
            'migration-profile',target], data=expected)
    if container_file(container, path) != expected:
        raise MigrationError('Profile copy verification failed; database import has not started')
    return target


def apply(args):
    if not args.approve_investor_2:
        raise MigrationError('Explicit --approve-investor-2 is required; AWS has not been changed')
    raw, document = load_bundle(args.bundle)
    if (document.get('profile') or {}).get('missing') and not args.allow_missing_profile:
        raise MigrationError('Source profile file missing; recover/re-export it, or explicitly use --allow-missing-profile to retain the existing dangling reference')
    preflight(args.container, raw, document)
    backup_path = backup(args.container)  # Must finish/validate BEFORE any import or image copy.
    copied = install_profile(document, args.auth_container)
    try:
        result = json.loads(psql(args.container, make_sql(raw)))
    except Exception:
        # A lost connection near COMMIT has an ambiguous outcome. Never automatically retry or delete anything.
        print('Import did not report success. Do not retry blindly. Preserve backup and bundle for reconciliation.', file=sys.stderr)
        if copied:
            print('A new profile file may remain; no existing file was overwritten.', file=sys.stderr)
        raise
    receipt = PRIVATE / f'import-receipt-{stamp()}.json'
    private_write(receipt, json.dumps({'bundle_sha256':hashlib.sha256(raw).hexdigest(),
                                      'backup':str(backup_path),'result':result},indent=2).encode())
    print('Committed investor ID:',result['new_user_id'])
    print('Validated counts:',json.dumps(result['counts'],sort_keys=True))
    print('Existing rows/admin unchanged; exact inserted values/hash/FKs validated in transaction.')
    print('Private receipt:',receipt)


def verify_login(args):
    _, document = load_bundle(args.bundle)
    url=urllib.parse.urlsplit(args.origin)
    if not args.origin.startswith('https://') or not url.hostname or url.path not in ('','/') or url.username or url.password or url.query or url.fragment:
        raise MigrationError('Use the HTTPS public origin, with no API path')
    if not sys.stdin.isatty():
        raise MigrationError('Run password verification in an interactive terminal; no piped passwords')
    origin = args.origin.rstrip('/')
    email = document['data']['tables']['users'][0]['email_address']
    password = getpass.getpass('Existing investor password (not stored): ')
    def request(path, *, data=None, token=None):
        headers = {'Content-Type':'application/json'}
        if token:
            headers['Authorization']='Bearer '+token
        req = urllib.request.Request(origin+path, data=data, headers=headers)
        # Do not forward password/JWT to any redirect target.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*unused):
                return None
        try:
            with urllib.request.build_opener(NoRedirect).open(req,timeout=25) as response:
                return json.load(response)
        except (urllib.error.URLError, ValueError):
            raise MigrationError('HTTPS login/identity verification failed; no response or credential printed') from None
    login = request('/api/auth/auth/login',data=json.dumps({'emailAddress':email,'password':password}).encode())
    password = None
    token = login.get('token')
    if not token:
        raise MigrationError('Login did not return a token')
    identity = request('/api/auth/auth/me',token=token)
    if identity.get('emailAddress') != email or identity.get('role') != 'USER':
        raise MigrationError('Authenticated investor identity mismatch')
    request('/api/investment/portfolio/holdings',token=token)
    print('PASS: existing password login, authenticated USER identity, and holdings HTTP access.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command',required=True)
    exp = sub.add_parser('export',help='Read-only export of local investor 2')
    exp.add_argument('--auth-container',default='investment-portfolio-auth-service-1')
    for name in ['plan','apply']:
        p = sub.add_parser(name,help='Read-only target checks' if name=='plan' else 'Approved import with automatic backup')
        p.add_argument('--bundle',type=Path,required=True)
        p.add_argument('--container',default=TARGET)
        if name=='apply':
            p.add_argument('--approve-investor-2',action='store_true')
            p.add_argument('--allow-missing-profile',action='store_true')
            p.add_argument('--auth-container',default='investment-aws-auth-service-1')
    login = sub.add_parser('verify-login',help='Prompt securely for the existing investor password after approval/import')
    login.add_argument('--bundle',type=Path,required=True)
    login.add_argument('--origin',required=True)
    args = parser.parse_args()
    try:
        if args.command=='export':
            export_bundle(args)
        elif args.command=='plan':
            raw,document=load_bundle(args.bundle)
            preflight(args.container,raw,document)
            print('PASS read-only schema/admin/email/asset/wallet preflight; no target data changed.')
            print('Source counts:',json.dumps({t:len(r) for t,r in document['data']['tables'].items()},sort_keys=True))
        elif args.command=='apply':
            apply(args)
        else:
            verify_login(args)
    except (MigrationError, OSError, ValueError, KeyError) as error:
        # Do not stringify arbitrary exceptions: values can contain credentials/PII.
        print('Migration stopped: '+(str(error) if isinstance(error,MigrationError) else type(error).__name__),file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':
    sys.exit(main())
