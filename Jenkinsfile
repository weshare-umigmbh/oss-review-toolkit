pipeline {
    agent any

    parameters {
        string(
            name: 'VCS_URL',
            description: 'VCS clone URL of the project',
            defaultValue: 'https://github.com/vdurmont/semver4j.git'
        )

        string(
            name: 'VCS_BRANCH',
            description: 'VCS branch of the project',
            defaultValue: 'master'
        )
    }

    stages {
        stage('Clone project') {
            agent any

            steps {
                // See https://jenkins.io/doc/pipeline/steps/git/.
                checkout([$class: 'GitSCM',
                    userRemoteConfigs: [[url: params.VCS_URL]],
                    branches: [[name: "*/${params.VCS_BRANCH}"]],
                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'project']]
                ])
            }
        }

        stage('Build ORT distribution') {
            steps {
                sh 'docker/build.sh'
            }
        }

        stage('Run ORT analyzer') {
            steps {
                // Remove any previous results.
                //sh 'rm -fr ${WORKSPACE}/project/ort'

                // Run the analyzer.
                sh "docker/run.sh '-v ${WORKSPACE}/project:/app/project' '--info analyze -i project -o /app/project/ort/analyze'"
            }

            post {
                always {
                    archiveArtifacts(
                        artifacts: 'project/ort/analyze',
                        fingerprint: true
                    )
                }
            }
        }
    }
}
