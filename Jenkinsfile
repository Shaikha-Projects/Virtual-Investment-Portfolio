pipeline {
    // Never select the offline Windows agent. This job is for trusted main-branch code only.
    agent { label 'built-in' }

    tools {
        jdk 'jdk17'
        maven 'Maven 3.9.9'
        nodejs 'NodeJS-22'
    }

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
    }

    triggers {
        // Polling works locally without exposing Jenkins to the Internet.
        pollSCM('H/5 * * * *')
        githubPush()
    }

    environment {
        DOCKERHUB_NAMESPACE = 'shaikhahsn'
        DOCKER_BUILDKIT = '1'
        AUTH_POSTGRES_TESTS = 'false'
        INVESTMENT_PERFORMANCE_POSTGRES_TEST = 'false'
        INVESTMENT_HISTORY_POSTGRES_TEST = 'false'
        HELP_GEMINI_LIVE_TEST = 'false'
        RUN_MYFATOORAH_LIVE_TEST = 'false'
    }

    stages {
        stage('Checkout GitHub') {
            steps {
                script {
                    def source = checkout scm
                    env.GIT_COMMIT = source.GIT_COMMIT
                    env.GIT_BRANCH = source.GIT_BRANCH
                    env.IMAGE_TAG = "build-${env.BUILD_NUMBER}-${source.GIT_COMMIT.take(12)}"
                }
                // Prevent artifacts from an earlier build being reported as current.
                sh 'rm -f delivery-manifest.txt'
            }
        }

        stage('Authentication Service Tests') {
            steps {
                dir('auth-service') { sh 'mvn -B clean verify' }
            }
            post {
                always {
                    junit testResults: 'auth-service/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Investment Service Tests + Cucumber') {
            steps {
                dir('investment-service') { sh 'mvn -B clean verify' }
                sh 'test -s investment-service/target/cucumber/asset-api.html'
            }
            post {
                always {
                    junit testResults: 'investment-service/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
                    archiveArtifacts artifacts: 'investment-service/target/cucumber/asset-api.*', allowEmptyArchive: true
                }
            }
        }

        stage('Notification Service Tests') {
            steps {
                dir('notification-service') { sh 'mvn -B clean verify' }
            }
            post {
                always {
                    junit testResults: 'notification-service/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
                }
            }
        }

        stage('React Frontend Tests and Build') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm test'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }

        stage('Docker Build') {
            steps {
                sh '''
                    set -eu
                    case "$GIT_BRANCH" in
                        main|origin/main) ;;
                        *) echo 'Publishing is restricted to the configured main branch.'; exit 1 ;;
                    esac
                    docker version
                    docker buildx version
                    docker build --pull \
                        --label "org.opencontainers.image.revision=$GIT_COMMIT" \
                        --label 'org.opencontainers.image.source=https://github.com/Shaikha-Projects/Virtual-Investment-Portfolio' \
                        --tag "$DOCKERHUB_NAMESPACE/investment-auth:$IMAGE_TAG" \
                        --tag "$DOCKERHUB_NAMESPACE/investment-auth:latest" auth-service
                    docker build --pull \
                        --label "org.opencontainers.image.revision=$GIT_COMMIT" \
                        --label 'org.opencontainers.image.source=https://github.com/Shaikha-Projects/Virtual-Investment-Portfolio' \
                        --tag "$DOCKERHUB_NAMESPACE/investment-service:$IMAGE_TAG" \
                        --tag "$DOCKERHUB_NAMESPACE/investment-service:latest" investment-service
                    docker build --pull \
                        --label "org.opencontainers.image.revision=$GIT_COMMIT" \
                        --label 'org.opencontainers.image.source=https://github.com/Shaikha-Projects/Virtual-Investment-Portfolio' \
                        --tag "$DOCKERHUB_NAMESPACE/investment-notification:$IMAGE_TAG" \
                        --tag "$DOCKERHUB_NAMESPACE/investment-notification:latest" notification-service
                '''
            }
        }

        stage('Docker Push') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-credentials',
                        usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    // Single-quoted Groovy string: secrets expand only in the shell, never in Groovy.
                    sh '''
                        set +x
                        set -eu
                        test "$DOCKERHUB_USERNAME" = "$DOCKERHUB_NAMESPACE"
                        export DOCKER_CONFIG
                        DOCKER_CONFIG=$(mktemp -d /tmp/investment-docker-config.XXXXXX)
                        trap 'docker logout >/dev/null 2>&1 || true; rm -rf -- "$DOCKER_CONFIG"' EXIT
                        printf '%s' "$DOCKERHUB_TOKEN" | docker login \
                            --username "$DOCKERHUB_USERNAME" --password-stdin

                        for repository in investment-auth investment-service investment-notification; do
                            docker push "$DOCKERHUB_NAMESPACE/$repository:$IMAGE_TAG"
                        done
                        # Update latest only after all three versioned images have been uploaded.
                        for repository in investment-auth investment-service investment-notification; do
                            docker push "$DOCKERHUB_NAMESPACE/$repository:latest"
                        done
                        printf 'Commit: %s\nBuild tag: %s\n' "$GIT_COMMIT" "$IMAGE_TAG" > delivery-manifest.txt
                        for repository in investment-auth investment-service investment-notification; do
                            docker buildx imagetools inspect "$DOCKERHUB_NAMESPACE/$repository:$IMAGE_TAG" \
                                >> delivery-manifest.txt
                        done
                    '''
                }
                archiveArtifacts artifacts: 'delivery-manifest.txt', fingerprint: true
            }
        }
    }

    post {
        success { echo 'All CI checks passed and all three service images were published to Docker Hub.' }
        failure { echo 'Pipeline failed. Check the failing stage; a failed push may leave a partial release.' }
    }
}
