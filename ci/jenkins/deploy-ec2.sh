#!/usr/bin/env bash
# Invoked over SSH only after CI, versioned publishing and explicit Jenkins approval.
set -euo pipefail
umask 077
repo=/home/ec2-user/Virtual-Investment-Portfolio
domain=virtualinvestmentportfolio.duckdns.org
cd "$repo"
test -f deploy/aws/.env
test -f deploy/aws/tls.yml
git diff --quiet
git diff --cached --quiet
exec 9>deploy/aws/.cd-deploy.lock
flock -n 9 || { echo 'Another deployment is running.' >&2; exit 1; }

dc() {
    docker compose --env-file "$repo/deploy/aws/.env" \
        -f "$repo/deploy/aws/compose.yml" -f "$repo/deploy/aws/tls.yml" -f "$override" "$@"
}

health() {
    dc ps
    # Local connection verifies the real HTTPS certificate and avoids public DNS/hairpin dependencies.
    curl --fail --silent --show-error --max-time 15 --resolve "$domain:443:127.0.0.1" "https://$domain/healthz"
    local path status
    for path in /api/auth/auth/me /api/investment/assets /api/notification/notifications/subscribe; do
        status=$(curl --silent --show-error --max-time 15 --resolve "$domain:443:127.0.0.1" \
            --output /dev/null --write-out '%{http_code}' "https://$domain$path")
        if [[ "$status" != 401 ]] && ! [[ "$path" == /api/auth/auth/me && "$status" == 403 ]]; then
            echo "Health failure: $path returned $status" >&2; return 1;
        fi
    done
    python3 - "$state/previous.json" <<'PY'
import json, subprocess, sys
old=json.load(open(sys.argv[1]))
for service in ['postgres_db','kafka']:
    now=json.loads(subprocess.check_output(['docker','inspect','investment-aws-'+service+'-1']))[0]
    if now['Id']!=old['infra'][service]['id'] or now['State']['StartedAt']!=old['infra'][service]['started']:
        raise SystemExit('Infrastructure unexpectedly changed: '+service)
for service in ['auth-service','investment-service','notification-service','frontend','postgres_db','kafka']:
    now=json.loads(subprocess.check_output(['docker','inspect','investment-aws-'+service+'-1']))[0]
    if now['State'].get('Health',{}).get('Status')!='healthy':
        raise SystemExit('Service is not healthy: '+service)
print('All six services healthy; PostgreSQL/Kafka containers unchanged.')
PY
}

mode=${1:-}
if [[ "$mode" == rollback ]]; then
    state=$(realpath "${2:?Supply the deployment state directory}")
    [[ "$state" == "$repo/deploy/aws/.cd-state/"* ]] || exit 1
    previous=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$state/previous.json")
    saved_env=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["env_sha256"])' "$state/previous.json")
    test "$(sha256sum deploy/aws/.env | cut -d' ' -f1)" = "$saved_env" || {
        echo '.env changed since deployment; refusing an unsafe automatic configuration rollback.' >&2; exit 1;
    }
    git checkout --detach --no-overwrite-ignore "$previous"
    override="$state/previous-images.json"
    dc config --quiet
    for service in auth-service investment-service notification-service frontend; do
        dc up -d --no-deps --no-build --pull never --wait --wait-timeout 360 "$service"
    done
    health
    cp "$override" "$repo/deploy/aws/.cd-state/current-images.json"
    if [[ -f deploy/aws/.cd-state/pending ]] && [[ "$(cat deploy/aws/.cd-state/pending)" == "$state" ]]; then
        rm deploy/aws/.cd-state/pending
    fi
    echo 'Application images/configuration rolled back; no database restore or volume changes performed.'
    exit 0
fi

[[ "$mode" == deploy ]] || { echo 'Usage: deploy COMMIT TAG FRONTEND_ARCHIVE | rollback STATE_DIR' >&2; exit 1; }
test ! -f deploy/aws/.cd-state/pending || {
    echo 'Previous deployment is incomplete. Review/rollback its recorded state before retrying.' >&2; exit 1;
}
commit=${2:?}
tag=${3:?}
archive=$(realpath "${4:?}")
[[ "$commit" =~ ^[a-f0-9]{40}$ && "$tag" =~ ^build-[0-9]+-[a-f0-9]{12}$ ]] || exit 1
[[ "$tag" == *-"${commit:0:12}" ]] || exit 1
git fetch --quiet origin main
git merge-base --is-ancestor "$commit" origin/main
previous=$(git rev-parse HEAD)
git diff --quiet "$previous" "$commit" -- investment_schema.sql assets_data.sql || {
    echo 'Database initialization SQL changed; a separate schema review is required.' >&2; exit 1;
}
state="$repo/deploy/aws/.cd-state/$tag-$(date -u +%Y%m%dT%H%M%S)"
mkdir -p "$state"
export IMAGE_TAG="$tag"
python3 - "$state" "$previous" "$tag" <<'PY'
import hashlib,json,pathlib,subprocess,sys
state=pathlib.Path(sys.argv[1]); previous=sys.argv[2]; tag=sys.argv[3]
data={'commit':previous,'env_sha256':hashlib.sha256(pathlib.Path('deploy/aws/.env').read_bytes()).hexdigest(),'infra':{}}
old={'services':{}}; new={'services':{}}
for service in ['postgres_db','kafka','auth-service','investment-service','notification-service','frontend']:
    c=json.loads(subprocess.check_output(['docker','inspect','investment-aws-'+service+'-1']))[0]
    if c['State'].get('Health',{}).get('Status')!='healthy':
        raise SystemExit('Existing service is not healthy: '+service)
    if service in ['postgres_db','kafka']:
        data['infra'][service]={'id':c['Id'],'started':c['State']['StartedAt']}
        continue
    # Keep exact running images locally even after the frontend receives a new tag.
    alias='investment-rollback-'+service+':'+state.name
    subprocess.run(['docker','tag',c['Image'],alias],check=True)
    old['services'][service]={'image':alias}
    if service=='frontend': new['services'][service]={'image':c['Config']['Image']}
    else:
        repository={'auth-service':'investment-auth','investment-service':'investment-service',
                    'notification-service':'investment-notification'}[service]
        new['services'][service]={'image':'shaikhahsn/'+repository+':'+tag}
for filename,value in [('previous.json',data),('previous-images.json',old),('new-images.json',new)]:
    (state/filename).write_text(json.dumps(value,indent=2))
PY

rollback_script=$(realpath "$0")
trap 'rc=$?; if ((rc)); then printf "Deployment failed. Review health, then rollback with:\nbash %q rollback %q\n" "$rollback_script" "$state" >&2; fi' EXIT
printf '%s\n' "$state" > "$repo/deploy/aws/.cd-state/pending"
frontend_changed=false
nginx_changed=false
git diff --quiet "$previous" "$commit" -- frontend deploy/aws/frontend.Dockerfile deploy/aws/frontend.Dockerfile.dockerignore || frontend_changed=true
git diff --quiet "$previous" "$commit" -- deploy/aws/nginx deploy/aws/tls.yml || nginx_changed=true
git checkout --detach --no-overwrite-ignore "$commit"
override="$state/new-images.json"
dc config --quiet
dc config --format json | python3 -c '
import json,sys
c=json.load(sys.stdin)
if c.get("name")!="investment-aws" or any(v.get("ports") for k,v in c["services"].items() if k!="frontend"):
    raise SystemExit("Unexpected project name or exposed backend/database/broker port")
e=c["services"]["investment-service"]["environment"]
if not all(e.get(k,"").startswith("https://virtualinvestmentportfolio.duckdns.org/wallet?") for k in ["STRIPE_SUCCESS_URL","STRIPE_CANCEL_URL"]):
    raise SystemExit("Fix PUBLIC_ORIGIN before deployment; .env was not modified")
'
dc pull auth-service investment-service notification-service
for repository in investment-auth investment-service investment-notification; do
    revision=$(docker image inspect "shaikhahsn/$repository:$tag" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
    test "$revision" = "$commit" || { echo "Image commit mismatch: $repository" >&2; exit 1; }
done

if $frontend_changed; then
    # Use CI's tested production dist; do not run Node/npm on the small EC2 instance.
    mkdir "$state/frontend"
    tar -tzf "$archive" > "$state/frontend-files.txt"
    if grep -Eq '(^/|(^|/)\.\.(/|$))' "$state/frontend-files.txt"; then echo 'Unsafe frontend archive' >&2; exit 1; fi
    tar -xzf "$archive" -C "$state/frontend" --no-same-owner --no-same-permissions
    test -s "$state/frontend/index.html"
    docker build --pull -t "investment-frontend:$tag" -f - "$state/frontend" <<'DOCKERFILE'
FROM nginx:stable-alpine
COPY . /usr/share/nginx/html
EXPOSE 80 443
DOCKERFILE
    python3 - "$override" "$tag" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); data=json.loads(p.read_text())
data['services']['frontend']['image']='investment-frontend:'+sys.argv[2]
p.write_text(json.dumps(data,indent=2))
PY
fi

# Compose only recreates changed images/config. --no-deps never recreates DB/Kafka.
for service in auth-service investment-service notification-service; do
    dc up -d --no-deps --no-build --pull never --wait --wait-timeout 360 "$service"
done
if $nginx_changed; then
    dc up -d --no-deps --no-build --pull never --force-recreate --wait --wait-timeout 120 frontend
else
    dc up -d --no-deps --no-build --pull never --wait --wait-timeout 120 frontend
fi
health
test "$(sha256sum deploy/aws/.env | cut -d' ' -f1)" = "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["env_sha256"])' "$state/previous.json")"
printf '%s\n' "$state" > "$repo/deploy/aws/.cd-state/current"
cp "$override" "$repo/deploy/aws/.cd-state/current-images.json"
rm "$repo/deploy/aws/.cd-state/pending"
echo "Deployment verified: $tag ($commit). Frontend rebuilt: $frontend_changed"
echo "Runtime image override: $override"
echo "Rollback: bash $rollback_script rollback $state"
