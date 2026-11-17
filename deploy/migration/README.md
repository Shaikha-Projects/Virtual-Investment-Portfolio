# Investor 2 migration — preparation first, AWS writes only after approval

This tool migrates **only local active USER 2** and its related data. It is not a full-database restore. It never reads `.env`, changes application code, disables constraints, rewrites passwords, sends outbox events or alters the local database. It uses Python 3's standard library and Docker/psql already available in this project.

**Do not run `apply` until the owner explicitly approves the AWS data import.** No AWS access was performed during preparation. Read-only `plan` still needs to be run against the actual AWS database before its compatibility can be confirmed.

## What was discovered and included

The tool inspects `information_schema` and PostgreSQL constraints automatically, checks the entire public table list and foreign-key graph, and refuses unreviewed table/trigger/relationship changes or mismatched source/target schemas.

The private bundle contains full database rows, including all decimal fields/timestamps, for:

- `users`: exactly active USER 2, including its unchanged BCrypt password hash.
- `portfolios`: the investor's portfolio, balances, reserved cash and initial funding.
- `holdings`, `transactions`, `investment_orders`, `wallet_operations`, `portfolio_snapshots`, `price_alert`: selected through the owned portfolio.
- `watchlist`: selected through the user.
- `audit_logs`: selected by that investor's email, a logical relationship without a foreign key. Historical free-text details are preserved verbatim; embedded historical IDs in prose are not rewritten.
- `assets`: only assets referenced by those rows.
- `price_history`: all recorded prices for those referenced assets, rather than just the dashboard's latest 100.
- The referenced profile picture, if its bytes are available in the Auth container or the repository's normal upload directories.

Ephemeral email-verification/password-reset tokens and both outbox tables are **not imported**. The investor is already ACTIVE and can obtain a new AWS JWT through normal login. Replaying old outboxes could resend emails, notifications or business events with obsolete IDs. Export stops if either source outbox has unpublished events; let the existing publishers drain them normally. Pending wallet payments also block export: settle/cancel/recover through the existing application first, since in-flight provider sessions can contain local return URLs. Completed financial history, wallet UUIDs, provider IDs and saved Stripe request JSON are preserved without replaying payments. Use the same Stripe sandbox account if you need access to old provider records.

**Actual source issue found:** the investor's database references a PNG that is absent from both the Auth container and project upload folders. A PostgreSQL dump stores the path, not uploaded image bytes, so `investment_backup.dump` cannot restore that missing file. The bundle explicitly records this and preserves the existing database path. Default import refuses it. Recover the original file and re-export if possible; otherwise the owner must explicitly accept `--allow-missing-profile` to retain the existing dangling reference. No dummy avatar, new image or cleared profile field is substituted.

## Safety and ID handling

- Local extraction uses one repeatable-read **READ ONLY** snapshot, plus read-only catalog checks. Source schedulers may continue their normal work; this tool does not change or stop them.
- Stop using/trading with this investor locally before the final export and cutover. Do not operate the same financial account simultaneously on both copies. A prepared bundle is a point-in-time snapshot; re-export/rehearse if you make later financial changes.
- Real exports/backups/receipts live in `.migration-private/`, excluded by Git, with restrictive POSIX permissions. `*.dump` and `*.backup` are also ignored, protecting the existing root backup. Windows permissions inherit your directory ACL: keep this directory private, on encrypted storage if available, and outside synced/shared folders.
- The bundle includes a SHA-256 sidecar; the importer validates it before any target change. SSH/SCP supplies encrypted transport. A checksum detects corruption, not a malicious replacement of both files; transfer through your trusted SSH connection.
- Active AWS ADMIN 1 must exist. It is never used as a source row or updated. An existing account with the same investor email (case-insensitive) stops the import; no automatic account merge or overwrite occurs.
- Every inserted ID is mapped above the target table's existing IDs and reserved sequence range. Children use the remapped user/portfolio/asset IDs. Source numeric IDs do not need to remain the same.
- Existing assets are matched by exact symbol and type; conflicting type/case/ambiguous matches stop the import. Their AWS price, status, name and other existing fields are not overwritten. Missing referenced assets are inserted. Identical existing `(asset_id, recorded_at, price)` history points are reused; other source history points are appended with new IDs.
- Wallet references/provider/session IDs are not rewritten. Global uniqueness conflicts stop preflight/import; normal constraints remain enabled. This avoids attaching one user's provider operation to another user.
- `apply` creates and archive-validates a full target custom-format `pg_dump` **before** importing rows or copying an available profile picture. Backups are binary-safe on Windows/Linux.
- Import uses one transaction with writer locks and bounded lock/statement timeouts. Reads can continue, but app writes may wait. Run in a short quiet window. It verifies every inserted field, all ownership mappings/counts and every pre-existing row before commit. No `UPDATE`, `DELETE`, `TRUNCATE`, `ON CONFLICT DO UPDATE`, replication-role bypass or persistent migration helper table is used against AWS.
- Sequence correction uses transactional `ALTER SEQUENCE RESTART`, never nontransactional `setval`/`nextval`, and does not move the next ID behind existing/reserved IDs. Temporary mapping/lookup tables and indexes disappear at commit/rollback.
- Available profile bytes are copied with no-clobber behavior. An existing identical file is reused; different bytes at the same path stop the import without overwriting it. Local Auth writes are explicitly prohibited.
- A confirmed SQL error rolls back the transaction, including sequence restarts. A connection loss around COMMIT can have an ambiguous outcome: **do not retry blindly or restore a full dump over AWS**. Keep the bundle, backup and receipt for reconciliation. If a new profile file was copied before a failed import, it may remain; no existing file is overwritten or automatically deleted.
- After commit, pending orders and active alerts resume under the existing AWS market engine and AWS prices. Their statuses/balances can legitimately change through normal business processing. No event is replayed by the migration itself.

## 1. Local preparation — already safe to run, no AWS writes

From the project root in PowerShell:

```powershell
python deploy/migration/investor_migration.py export
$Bundle = (Get-ChildItem .migration-private/investor-2-*.json |
    Where-Object { Test-Path ($_.FullName + '.sha256') } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
python deploy/migration/rehearse.py --bundle $Bundle
git check-ignore investment_backup.dump .migration-private/probe.json
git status --short
```

`export` prints only counts, an export checksum and file path; it does not print email, phone, password/hash, payment identifiers or image names. Financial row JSON goes unchanged from PostgreSQL into the bundle to avoid Python float rounding. The existing full `investment_backup.dump` is not used to restore AWS; doing so could replace the administrator and other existing cloud data.

`rehearse` creates a randomly named **new** PostgreSQL 18.6 container, with no network, no published ports, no application volume mounts and tmpfs storage. It copies only the source schema, seeds synthetic target ADMIN 1/USER 2 with conflicts, and exercises the actual private investor bundle. It tests commit, rollback, exact values, FK/ID remapping, existing-data preservation, backup restoration, repeat refusal, schema drift, wallet collisions, history reuse and profile no-overwrite behavior. It removes only that disposable container on exit. It never connects to AWS or restores over local PostgreSQL.

## 2. After explicit approval: transfer the package over SSH

No Git push is required for this transfer. Do not put the bundle or backups in GitHub. Use the same `$Bundle` variable as above. Supply your actual public EC2 DNS/IP and the path to your existing SSH private key locally; do not send the key or password in chat.

```powershell
$Ec2Host = Read-Host 'EC2 public DNS/IP'
$SshKey = Read-Host 'Local SSH private-key path'
ssh -i $SshKey "ec2-user@$Ec2Host" 'umask 077; mkdir -p ~/investor-migration'
scp -i $SshKey deploy/migration/investor_migration.py deploy/migration/import_investor.sql `
    $Bundle ($Bundle + '.sha256') "ec2-user@${Ec2Host}:investor-migration/"
```

Verify the SSH host fingerprint through your existing trusted setup. Do not turn off host-key checking. The package directory is outside the cloned Git repository. This step transfers private files to EC2 but does not import database rows.

## 3. On EC2: one automated preflight and one approved import

Use the existing Docker-authorized `ec2-user` session. PostgreSQL remains private; the script calls psql inside `investment-aws-postgres_db-1`, with no database password printed or added to the command line.

```bash
cd ~/investor-migration
umask 077
chmod 700 .
chmod 600 investor-2-*.json investor-2-*.json.sha256
python3 --version
docker ps --format '{{.Names}}'
# Select the transferred bundle; leave older bundles in place if any.
BUNDLE=$(find . -maxdepth 1 -type f -name 'investor-2-*.json' | sort | tail -n 1)
test -n "$BUNDLE" || { echo 'Missing migration bundle'; exit 1; }
python3 investor_migration.py plan --bundle "$BUNDLE"
```

Stop if preflight fails. It performs only read-only queries and requires the actual target catalog, ADMIN 1, investor-email absence, compatible assets and absence of wallet-identity collisions. No need to inspect individual tables. Do not resolve conflicts by deleting or overwriting AWS rows.

**Only after import approval**, if the source image was recovered and included:

```bash
python3 investor_migration.py apply --bundle "$BUNDLE" --approve-investor-2
```

For the actual currently missing PNG, only if the owner also approves retaining its existing dangling reference:

```bash
python3 investor_migration.py apply --bundle "$BUNDLE" --approve-investor-2 --allow-missing-profile
```

Run **one** apply command, not both. The script automatically backs up and validates the target, checks it again under locks, imports, verifies and commits. It reports the new investor ID and counts, and writes a private receipt with ID mappings. No manual SQL, sequence reset, database restore or Docker restart is required. Repeated application stops on the existing investor email and does not create duplicates.

## 4. Verify the real login securely

After a successful approved import, run in an interactive terminal:

```bash
read -r -p 'Public HTTPS origin (no trailing slash): ' ORIGIN
python3 investor_migration.py verify-login --bundle "$BUNDLE" --origin "$ORIGIN"
```

It securely prompts for the **existing investor password**, performs the existing `/api/auth/auth/login`, `/api/auth/auth/me`, and `/api/investment/portfolio/holdings` requests over verified HTTPS, checks the authenticated USER identity, and prints only PASS/failure. Passwords and returned JWTs are never printed or saved, and redirects are refused. Do not pipe a password into the script or include it in shell arguments/history. The same check can be completed by signing into the browser and reviewing holdings/history/wallet. The actual password was not supplied during preparation, so real investor HTTP login has **not** been claimed as tested.

Confirm admin login still works, and check the investor's holdings, wallet, transactions, orders, watchlist, alerts and portfolio history in the normal UI. Both asset prices and portfolio market values can differ between independent simulators; preserved quantities, cost bases, cash/reservations, wallet records and historical snapshots are the migration invariants, not matching live market prices after cutover.

Keep the private pre-import backup and receipt securely until the migration is accepted. Do not post exports, hashes, PII, provider identifiers, logs or backups in GitHub. No full-database restore, automatic post-commit deletion, administrator reset or cleanup of existing AWS data is provided.

## Preparation results and remaining gate

The local schema and foreign keys were inspected live. The existing root backup archive was read-validated without restoring/modifying it. The private investor export was validated against a synthetic AWS-like database with deliberate conflicts; see the private `.migration-private/rehearsal-report-*.json` for the exact checks/counts.

Actual AWS schema/state/backup and actual investor password login remain to be verified only after the approved transfer/import procedure. No AWS modifications or application restarts have been performed by this preparation.
