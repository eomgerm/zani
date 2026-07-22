def webhookTokenFile = new File('/run/secrets/GITLAB_WEBHOOK_TOKEN')
if (!webhookTokenFile.isFile()) {
    throw new IllegalStateException('GitLab webhook token file is missing')
}

def webhookToken = webhookTokenFile.getText('UTF-8').trim()
if (webhookToken.isEmpty()) {
    throw new IllegalStateException('GitLab webhook token is empty')
}

pipelineJob('zani-backend-dev') {
    description('Receives authenticated GitLab dev push webhooks, verifies the backend, and deploys only the backend container.')
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
            scriptPath('Jenkinsfile')
            lightweight(true)
        }
    }
    disabled(false)
}
