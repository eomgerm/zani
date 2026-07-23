def webhookTokenFile = new File('/run/secrets/GITLAB_WEBHOOK_TOKEN')
if (!webhookTokenFile.isFile()) {
    throw new IllegalStateException('GitLab webhook token file is missing')
}

def webhookToken = webhookTokenFile.getText('UTF-8').trim()
if (webhookToken.isEmpty()) {
    throw new IllegalStateException('GitLab webhook token is empty')
}

pipelineJob('zani-dev-dispatch') {
    description('Receives authenticated GitLab dev push webhooks and queues only the application component jobs affected by the commit range.')
    triggers {
        gitlab {
            triggerOnPush(true)
            triggerOnMergeRequest(false)
            branchFilterType('NameBasedFilter')
            includeBranchesSpec('dev')
            excludeBranchesSpec('')
            secretToken(webhookToken)
        }
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git')
                        credentials('gitlab-zani-read')
                    }
                    branch('*/dev')
                }
            }
            scriptPath('Jenkinsfile.dispatcher')
            lightweight(true)
        }
    }
    disabled(false)
}

pipelineJob('zani-backend-dev') {
    description('Verifies and deploys the backend SHA selected by zani-dev-dispatch. This job has no public webhook trigger.')
    parameters {
        stringParam('GIT_SHA', '', 'Dispatcher-validated 40-character commit SHA from dev')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git')
                        credentials('gitlab-zani-read')
                    }
                    branch('*/dev')
                }
            }
            scriptPath('Jenkinsfile')
            lightweight(true)
        }
    }
    disabled(false)
}

pipelineJob('zani-frontend-dev') {
    description('Verifies and deploys the frontend SHA selected by zani-dev-dispatch. This job has no public webhook trigger.')
    parameters {
        stringParam('GIT_SHA', '', 'Dispatcher-validated 40-character commit SHA from dev')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git')
                        credentials('gitlab-zani-read')
                    }
                    branch('*/dev')
                }
            }
            scriptPath('Jenkinsfile.frontend')
            lightweight(true)
        }
    }
    disabled(false)
}
