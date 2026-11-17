# Jenkins to EC2: approved, versioned deployment

Versioned EC2 deployment runs only after successful CI/publishing and explicit approval at the Jenkins deployment gate.

## Jenkins prerequisites

Configure these two **Global** Jenkins credentials in Manage Jenkins → Credentials → System → Global (both IDs/types were confirmed before the verification run):

1. **SSH Username with private key**, ID `aws-ec2-ssh`, username `ec2-user`. Enter the existing EC2 PEM through Jenkins's private-key field; do not put it in Git, a shell command or chat. The existing AWS PEM is unencrypted; this implementation uses `ssh -i` in batch mode, not an SSH-agent plugin. Jenkins encrypts its stored credentials and binds a temporary key file only during deployment.
2. **Secret file**, ID `aws-ec2-known-hosts`, containing the verified OpenSSH host-key entry for `ec2-54-80-38-216.compute-1.amazonaws.com`. Use the already trusted local `known_hosts` entry or verify its fingerprint through your trusted EC2 connection/console before saving. Do not blindly trust a new `ssh-keyscan` result. Strict host-key verification stays enabled.

SSH/SCP and SSH credentials binding already exist in the current Jenkins controller; no new plugin/image/container is required. The EC2 SSH security-group rule must permit the Jenkins/Docker Desktop host's current public IP (restrict port 22; do not open it globally). EC2 needs Docker-group access, Git, Bash, Python 3, curl, flock and enough disk space. No AWS access-key credential or AWS CLI is needed. Public Docker Hub images can be pulled without placing the push token on EC2.

## Execution and approval

The existing CI/tests and Docker Hub push stages run first. A new **Deploy to AWS** stage waits for explicit Jenkins approval of that build's exact versioned tag. The 15-minute stage timeout includes approval and deployment: approve promptly, or increase deliberately for a slower connection. Merely committing/pushing the proposed Jenkinsfile cannot silently change AWS: the **Deploy to AWS** button is required. Do not approve until the credentials and SSH rule are ready.

After repository-change approval, push only `Jenkinsfile`, `ci/jenkins/deploy-ec2.sh`, this file and `deploy/aws/.gitignore`. Do not include the unrelated investor migration files, private exports or `.gitignore` changes from migration preparation. No tests need to be repeated during syntax-only preparation; the next normal pipeline will execute its existing full CI gate once.

The approval starts automated SCP/SSH deployment into `/home/ec2-user/Virtual-Investment-Portfolio`:

- The published delivery manifest must match the current build tag and commit. EC2 pulls only the three versioned backend tags and verifies each image's `org.opencontainers.image.revision` label against the exact commit.
- The repository fetches and checks out that exact trusted main-branch commit in **detached HEAD** mode. Tracked local edits cause a safe stop; ignored `.env` and certificates are preserved. A later manual `git pull` requires switching back to a branch; normal CD uses fetch plus an exact commit instead.
- Database initialization SQL changes stop automation for a separate review. No schema migration, database restore, volume reset, `down`, prune, or infrastructure recreation is performed.
- Existing `.env` bytes are never edited. Release images live in a private, ignored `deploy/aws/.cd-state/` image override, with exact previous running images retained under rollback tags. Both `compose.yml` and `tls.yml` are always included. Backend/database/Kafka port publication is rejected, and HTTPS Stripe return URL configuration is checked without printing secrets.
- Existing PostgreSQL/Kafka must be healthy and retain their container IDs/start times. Backends update one at a time to reduce startup pressure on t3.small. App updates use `--no-deps --no-build --pull never`, so only changed app images/configuration are recreated. Nginx is explicitly recreated when its mounted configuration changes.
- The existing frontend CI build now produces production same-origin API routes. Its tested `dist` archive is transferred; only a change under `frontend/` or the frontend Dockerfile/context-ignore causes a new lightweight Nginx runtime image. There is **no Node/npm build on the t3.small**. Unchanged frontend sources keep their existing image. First deployment compares against the EC2 repository's current commit, assuming the currently working frontend corresponds to it.
- Health checks wait for all six containers, verify HTTPS locally using the real domain/certificate, and verify unauthenticated Investment/Notification API responses are 401. This is a deployment health check, not a claim of full logged-in trading/Stripe/AI testing.

Deployment completion records `deploy/aws/.cd-state/current-images.json`. For manual Compose operations after CD, always include this active image override to avoid falling back to the older tag in the untouched `.env`:

```bash
cd /home/ec2-user/Virtual-Investment-Portfolio
dc() {
  docker compose --env-file deploy/aws/.env \
    -f deploy/aws/compose.yml -f deploy/aws/tls.yml \
    -f deploy/aws/.cd-state/current-images.json "$@"
}
dc ps
```

No database passwords, provider secrets, private keys or resolved Compose environments are printed/archived. State files contain only Git/image references and an environment-file checksum. Keep retained rollback images until the release is accepted; disk usage grows with retained releases. Do not prune them during the rollback window.

## Failure and rollback

A failed deployment fails the Jenkins stage and prints its exact rollback command. It does **not** automatically loop/retry a partially deployed release or restore the database. A pending-state guard blocks subsequent deployments until that attempt is reviewed/rolled back. Previous images and source/configuration are captured before the update.

With explicit rollback approval, use the command printed in the Jenkins console, for example:

```bash
bash /home/ec2-user/.investment-deploy/BUILD_TAG/deploy-ec2.sh rollback \
  /home/ec2-user/Virtual-Investment-Portfolio/deploy/aws/.cd-state/RECORDED_RELEASE_DIRECTORY
```

Use the **actual paths** printed by the failed/successful deployment, not the placeholders above. Rollback restores the previous source commit and each previous running app image, including mixed older backend tags if that was the baseline. It includes TLS, waits for health, and leaves PostgreSQL/Kafka/data untouched. It refuses if `.env` changed since the captured state rather than silently replacing credentials. Keep the uploaded script outside the Git checkout so it is available even when Git returns to the older commit.

This implements deployment automation behind the requested approval gate. It is not yet verified live; report CD complete only after an explicitly approved Jenkins deployment actually succeeds.
