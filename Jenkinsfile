pipeline {
    agent none

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
            agent {
                dockerfile {
                    dir 'docker/build'
                    args '-v $HOME/.gradle:/root/.gradle'
                }
            }

            steps {
                sh './gradlew --no-daemon :cli:distTar'
            }
        }

        stage('Build Docker image') {
            agent any

            steps {
                sh './gradlew --no-daemon :cli:dockerBuildImage'
            }
        }

        stage('Run ORT analyzer') {
            agent any

            steps {
                // Remove any previous results.
                sh 'rm -fr ${WORKSPACE}/project/ort'

                // Run the analyzer.
                sh "docker run --rm -v ${WORKSPACE}/project:/app/project ort --info analyze -i project -o /app/project/ort/analyze"
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
