# Match the running controller exactly: no Jenkins upgrade or plugin changes.
ARG JENKINS_BASE=jenkins/jenkins@sha256:74401de6a6f92e1afe1b02d1c8e3d0c33f263bfb5275dd8813f9150355f2367b
ARG DOCKER_CLI_IMAGE=docker:29.9.3-cli@sha256:48678dab48932d2b9189040748f321d4eb09c90193bbf4f4b9b86d72cffd6e25

FROM ${DOCKER_CLI_IMAGE} AS docker_cli
FROM ${JENKINS_BASE}

USER root
COPY --from=docker_cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker_cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/lib/docker/cli-plugins/docker-buildx
RUN docker --version && docker buildx version
USER jenkins
