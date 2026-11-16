# Local Jenkins Docker Hub delivery

## Inspected setup

- Controller: `jenkins-local`, `http://localhost:8082`, image `jenkins/jenkins:lts`.
- Jenkins home: existing named volume `jenkinsvol1` at `/var/jenkins_home`.
- Controller runs as `jenkins` (UID/GID 1000), Java 21; application builds use the configured Java 17 tool.
- Existing job: `investment-portfolio-pipeline`, inline Declarative Pipeline, main branch of `Shaikha-Projects/Virtual-Investment-Portfolio`.
- Existing tool names: `jdk17`, `Maven 3.9.9`, `NodeJS-22`. Existing build 7 succeeded.
- Docker Desktop client/server: 29.9.3, Linux containers. Controller has neither Docker CLI nor a socket mount.
- Controller has the Pipeline, credentials-binding, GitHub, JUnit and NodeJS plugins already. No new plugin is required.
- Docker Hub credential ID `dockerhub-credentials` was confirmed present. Its value was not displayed.

## Pipeline behavior

The repository-root `Jenkinsfile` preserves the working Maven and npm commands. It explicitly uses the built-in Linux node, not the offline Windows agent.

Immediately after checkout, Registry Preflight checks Docker tooling and Docker Hub login without building or publishing images. Invalid credentials fail here, before the lengthy tests. Credentials remain scoped to this short check and the later push stage; each login uses a separate temporary config removed on exit. All CI tests must still pass before any image is published.

1. Check out the configured SCM and calculate `build-<BUILD_NUMBER>-<12-character-commit>`.
2. Run `mvn -B clean verify` for Authentication, Investment (including Cucumber), and Notification.
3. Run frontend `npm ci`, tests, lint and production build.
4. Build all three existing service Dockerfiles, using the JARs from the successful Maven builds.
5. Bind Jenkins username/password credential `dockerhub-credentials` only for publishing. Username is `shaikhahsn`; the password is a Docker Hub Read & Write access token.
6. Publish versioned tags to all three repositories, then update their `latest` tags:
   - `shaikhahsn/investment-auth`
   - `shaikhahsn/investment-service`
   - `shaikhahsn/investment-notification`
7. Inspect the remote versioned images and archive a delivery manifest with commit, tag and registry digests.

Failures stop subsequent stages. Unstable test stages also skip delivery. The build does not run Compose or deploy/restart application containers. Publishing is restricted to the configured main branch. Concurrent builds of this job are disabled to avoid racing `latest` updates.

Docker login uses `--password-stdin`, shell expansion rather than Groovy secret interpolation, disabled command tracing, and a private temporary Docker config directory cleaned up on exit. Credentials are never committed. The service `.dockerignore` files restrict each context to its Dockerfile and packaged JAR, excluding uploads, `.env`, sources and reports.

Registry pushes are not atomic across repositories: a failed push can leave a partial release. Use the versioned tag and only accept a successful Jenkins build with all three remote digests; do not infer success from one updated `latest` tag.

## Controller image

```powershell
docker build -t investment-jenkins:local -f ci/jenkins/Dockerfile ci/jenkins
```

The Dockerfile pins the exact running Jenkins base digest. It copies Docker CLI 29.9.3 and Buildx from the official Docker CLI image and verifies both binaries during the build. It keeps `USER jenkins`; it does not install plugins, reset Jenkins, or alter the application images. The CLI source digest matches the inspected Linux amd64 environment. Review these pins when deliberately upgrading Docker or Jenkins.

## Controller migration: approval required

These commands change the existing Jenkins container. Do not run them without approval. Check Jenkins is idle first. They do not change application containers or delete any volume. Do not use `docker compose down`, volume pruning, or `docker rm -v`.

Run from the project root in PowerShell. Keep the backup private: it contains Jenkins configuration, credentials and encryption keys. `target/` is ignored by Git. The original container is retained, stopped, for rollback.

```powershell
$cliImage = 'docker:29.9.3-cli@sha256:48678dab48932d2b9189040748f321d4eb09c90193bbf4f4b9b86d72cffd6e25'

# Read the real socket group; do not assume its GID and do not chmod the socket.
$socketGroup = docker run --rm --network none --read-only --cap-drop ALL --user 65534:65534 `
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock,readonly `
  --entrypoint stat $cliImage -c %g /var/run/docker.sock
if ($LASTEXITCODE -ne 0 -or $socketGroup -notmatch '^\d+$') { throw 'Cannot determine Docker socket group' }

$oldController = 'jenkins-local-before-cd-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$backup = Join-Path (Get-Location) ('target/jenkins-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null

docker stop jenkins-local
if ($LASTEXITCODE -ne 0) { throw 'Controller stop failed; do not proceed' }

# Consistent backup while Jenkins is stopped. The source volume is mounted read-only.
# Tools, Maven caches and workspaces remain in the untouched volume, but are omitted from the backup.
docker run --rm --network none --read-only --cap-drop ALL --user 1000:1000 `
  --mount type=volume,source=jenkinsvol1,target=/source,readonly `
  --mount "type=bind,source=$backup,target=/backup" `
  --entrypoint tar $cliImage -czf /backup/jenkins-home.tar.gz `
  --exclude=./workspace --exclude=./caches --exclude=./.m2 --exclude=./tools -C /source .
if ($LASTEXITCODE -ne 0) { docker start jenkins-local; throw 'Backup failed; original controller restarted' }

docker rename jenkins-local $oldController
if ($LASTEXITCODE -ne 0) { docker start jenkins-local; throw 'Rename failed' }

docker run -d --name jenkins-local --network bridge -p 8082:8080 `
  --mount type=volume,source=jenkinsvol1,target=/var/jenkins_home `
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock `
  --group-add "$socketGroup" investment-jenkins:local
if ($LASTEXITCODE -ne 0) { throw "New controller failed; use rollback below with $oldController" }

docker exec jenkins-local id
docker exec jenkins-local docker version
docker exec jenkins-local docker buildx version
```

Both helper containers use the official CLI image, bind mounts or the explicit existing volume only. They do not remove named volumes. Never run the old and replacement Jenkins controllers against `jenkinsvol1` simultaneously.

Wait for `http://localhost:8082/login` to respond. Confirm the existing jobs, credentials, plugins, tool definitions and build history remain. The inspected controller uses default startup/environment settings and a single 8082 port mapping, which the replacement preserves. No inbound agent port is added.

Rollback, if necessary, retains both containers and the volume:

```powershell
docker stop jenkins-local
docker rename jenkins-local ('jenkins-local-cd-stopped-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
docker rename $oldController jenkins-local
docker start jenkins-local
```

The rollback restores the old container, but both use the same current Jenkins home. The backup is available if Jenkins home restoration is separately necessary; do not restore over a running controller.

## Connect the existing job to source control

Commit and push the new Jenkinsfile, controller files and three service `.dockerignore` files to `main` before configuring the job to read them. Do not commit `target/`, backups, `.env`, or credentials.

In the existing `investment-portfolio-pipeline` job, change only the Pipeline definition:

- Definition: **Pipeline script from SCM**.
- SCM: **Git**.
- Repository: `https://github.com/Shaikha-Projects/Virtual-Investment-Portfolio.git`.
- Branch: `*/main`.
- Script Path: `Jenkinsfile`.

Keep the existing job rather than creating a replacement. The checkout uses `scm`, so copying this Jenkinsfile into an inline Pipeline is not sufficient. Existing tool definitions are reused exactly. Docker Hub uses the Jenkins credential ID, not a Git credential or a token in a URL.

Run **Build Now** once. A successful run installs the configured triggers and proves the full delivery path.

## GitHub push triggering

The Jenkinsfile enables the already-installed GitHub push trigger and SCM polling every five minutes. Polling works with localhost because Jenkins makes outbound GitHub connections. It detects new main-branch commits without rebuilding unchanged commits after the initial baseline.

GitHub cannot deliver a webhook directly to `http://localhost:8082`: that address is local to the sender. An immediate webhook requires a reachable HTTPS endpoint forwarding `/github-webhook/` to Jenkins, plus a configured webhook secret. No public tunnel, port-forward, security relaxation, or additional plugin is created automatically. Until that is deliberately arranged, polling is the practical local trigger. No GitHub hook has been created by these files.

## Security boundary

Docker socket access gives Jenkins control over Docker Desktop's Linux engine, including the ability to start privileged containers and access application volumes. Non-root Jenkins plus the socket group avoids root execution and a world-writable socket, but does not sandbox the engine. A read-only socket mount is not an API authorization boundary either. Run only trusted repositories/branches and restrict job configuration permissions. Do not allow untrusted pull requests to execute on this controller. Do not expose an unauthenticated Docker TCP port such as 2375.

An isolated Linux build agent or dedicated secured build daemon is preferable for a shared production Jenkins installation. This setup uses the existing local controller as requested and does not use the offline Windows agent.

## Verification and completion criteria

Preparation checks actually executed:

- Built `investment-jenkins:local` successfully, verifying Docker CLI 29.9.3 and Buildx 0.39.0 in the image build. Final configured user is `jenkins`.
- Parsed the Jenkinsfile using the controller's Groovy 2.4.21 compiler without executing the pipeline. Both multiline shell blocks passed `sh -n`.
- Parsed the three PowerShell command blocks in this document without executing migration commands.
- Built separate local verification images for Authentication, Investment and Notification using the new context restrictions and existing packaged JARs. No images were pushed by these checks.
- Inspected the existing job's build 7 result: `SUCCESS`. This predates the new delivery stages; it is not evidence of completed CD.
- Confirmed the configured credential ID exists by inspecting IDs only. Registry authentication has not yet been tested.

No existing container, Jenkins job definition or trigger has been changed during preparation. The new files have not been committed or pushed. Jenkins Declarative validation and the actual build/push run remain pending controller approval and job setup.

Preparation is not completed CD. Before reporting delivery complete, record:

1. Controller socket/CLI checks and preservation of `jenkinsvol1` and the existing job.
2. A new **successful Jenkins pipeline run**, with all three Maven suites, Cucumber and frontend checks passing.
3. Successful Docker Build and Docker Push stages.
4. Remote versioned tags and `latest` in all three Docker Hub repositories, with digests matching the delivery manifest.
5. No restarts or changes to the application's PostgreSQL, Kafka or microservice containers.

The archived Cucumber HTML report and JUnit reports remain available in Jenkins. Credentials and backups must never be archived as pipeline artifacts. No AWS deployment is part of this change.

References: [Jenkins Docker installation](https://www.jenkins.io/doc/book/installing/docker/), [Jenkins credentials handling](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#handling-credentials), [Docker daemon security](https://docs.docker.com/engine/security/protect-access/), [Docker login](https://docs.docker.com/reference/cli/docker/login/), [Jenkins GitHub trigger](https://plugins.jenkins.io/github/).
