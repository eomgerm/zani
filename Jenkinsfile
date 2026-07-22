pipeline {
    agent { label 'zani-backend' }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
    }

    environment {
        DEPLOY_WRAPPER = '/opt/zani/deploy/deploy-application'
    }

    stages {
        stage('Checkout dev') {
            steps {
                checkout scm
                script {
                    env.GIT_SHA = sh(
                        script: 'git rev-parse HEAD',
                        returnStdout: true
                    ).trim()
                }
            }
        }

        stage('Detect backend changes') {
            steps {
                script {
                    env.BACKEND_RELEVANT = sh(
                        script: '''
                            set -eu

                            previous_sha="${GIT_PREVIOUS_SUCCESSFUL_COMMIT:-${GIT_PREVIOUS_COMMIT:-}}"

                            if [ -z "${previous_sha}" ] || \
                               ! git cat-file -e "${previous_sha}^{commit}" 2>/dev/null; then
                                # The first controlled run must verify and deploy the current dev state.
                                printf 'true'
                            elif git diff --quiet "${previous_sha}" "${GIT_SHA}" -- \
                                backend/ \
                                infrastructure/application/ \
                                Jenkinsfile; then
                                printf 'false'
                            else
                                printf 'true'
                            fi
                        ''',
                        returnStdout: true
                    ).trim()

                    if (env.BACKEND_RELEVANT != 'true' && env.BACKEND_RELEVANT != 'false') {
                        error("Unexpected backend change result: ${env.BACKEND_RELEVANT}")
                    }

                    currentBuild.description = "${env.GIT_SHA} backend=${env.BACKEND_RELEVANT}"
                    echo "Backend-relevant changes: ${env.BACKEND_RELEVANT}"
                }
            }
        }

        stage('Backend verify') {
            when {
                expression {
                    env.BACKEND_RELEVANT == 'true'
                }
            }
            steps {
                sh '''
                    sudo "${DEPLOY_WRAPPER}" verify "${WORKSPACE}" "${GIT_SHA}"
                '''
            }
        }

        stage('Deploy dev') {
            when {
                allOf {
                    expression {
                        env.BACKEND_RELEVANT == 'true'
                    }
                    expression {
                        env.GIT_BRANCH == 'origin/dev' ||
                            env.GIT_BRANCH == 'dev' ||
                            env.BRANCH_NAME == 'dev'
                    }
                }
            }
            steps {
                sh '''
                    sudo "${DEPLOY_WRAPPER}" deploy "${WORKSPACE}" "${GIT_SHA}"
                '''
            }
        }

        stage('Skip non-backend change') {
            when {
                expression {
                    env.BACKEND_RELEVANT == 'false'
                }
            }
            steps {
                echo 'No backend or application deployment files changed; verification and deployment were skipped.'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
