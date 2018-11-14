
def getJDKVersion() {
    return 'jdk1.8'
}

def getMavenConfig() {
    return 'maven-config'
}

def getMavenLocation() {
//    return 'M2_HOME'
    return 'MAVEN_HOME'
}

def getEnvironment() {
    return  'QA\n' +
            'STG\n' +
            'PRD'
}

def getEmailRecipients() {
    return ''
}

def getReportZipFile() {
    return "Reports_Build_${BUILD_NUMBER}.zip"
}

def getS3ReportPath() {
    return "$projectName"
}

def publishHTMLReports(reportName) {
    // Publish HTML reports (HTML Publisher plugin)
    publishHTML([allowMissing         : true,
                 alwaysLinkToLastBuild: true,
                 keepAll              : true,
                 reportDir            : 'target\\view',
                 reportFiles          : 'index.html',
                 reportName           : reportName])
}


String jobName = "bps-reporting-db"
String gitHubCredentialsId = "352dfae7-1f12-40ad-b64c-c69162beecdb"
String githubRepositoryName = "anleanca/protected-branches"

String nexusCredentialsId = "7d0e985d-2969-45b3-a0d5-2b0f74444bc7"
String NEXUS_URL = '192.168.33.20:8081'
String NEXUS_REPOSITORY = "ansible-meetup"

String artifactVersion = ""

def scmInfo = null

def checkJobBuildRunned(jobName) {
    def job = Jenkins.instance.getItem(jobName)

    if (!job) {
        error("cannot find upstream job ${jobName}")
    }

    //println(job.getAllJobs())
    for(branchJob in job.getAllJobs()) {
        println(branchJob)

        def latestBuild = branchJob.getBuilds().get(0)

        //println(getBuildResult(latestBuild))

        def result = latestBuild.getResult()
        println(result)

        if (result == null) {
            return true
        }
    }

    return false
}

def updateGitHubStatus(githubRepoName, commitSha, dataToSend, authData) {
    def response = httpRequest url: "https://api.github.com/repos/${githubRepoName}/statuses/${commitSha}",
            httpMode: 'POST',
            acceptType: 'APPLICATION_JSON',
            contentType: 'APPLICATION_JSON',
            customHeaders: authData,
            requestBody: dataToSend
    println(response)
}

/* Declarative pipeline must be enclosed within a pipeline block */
pipeline {

    agent any

    /**
     * tools to auto-install and put on the PATH
     * some of the supported tools - maven, jdk, gradle
     */
    tools {
        jdk 'jdk1.8'
        maven 'mvn3.6.0'
    }

    /**
     * parameters directive provides a list of parameters which a user should provide when triggering the Pipeline
     * some of the valid parameter types are booleanParam, choice, file, text, password, run, or string
     */
    /**
    parameters {
        choice(choices: "$environment", description: '', name: 'ENVIRONMENT')
        string(defaultValue: "$emailRecipients",
                description: 'List of email recipients',
                name: 'EMAIL_RECIPIENTS')
    }
     /**/

    /**
     * stages contain one or more stage directives
     */
    stages {
        /**
         * the stage directive should contain a steps section, an optional agent section, or other stage-specific directives
         * all of the real work done by a Pipeline will be wrapped in one or more stage directives
         */
        stage('Check DB build') {
            steps {
                script {
                    retry (3) {
                        echo "Retry"
                        if (checkJobBuildRunned(jobName)) {
                            sleep(time:120,unit:"SECONDS")
                            echo "Fail"
                            sh 'echo "Fail!"; exit 1'
                        }
                    }
                }
                script {
                    if (checkJobBuildRunned(jobName)) {
                        error 'FAIL'
                    }
                }
            }
        }


        stage('Prepare') {
            steps {
                script {


                    // GIT submodule recursive checkout
                    scmInfo = checkout scm: [
                            $class: 'GitSCM',
                            branches: scm.branches,
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'SubmoduleOption',
                                          disableSubmodules: false,
                                          parentCredentials: false,
                                          recursiveSubmodules: true,
                                          reference: '',
                                          trackingSubmodules: false]],
                            submoduleCfg: [],
                            userRemoteConfigs: scm.userRemoteConfigs
                    ]
                    // copy managed files to workspace

                }
            }
        }

        stage('Build') {
            steps {
                script {
                    sh "mvn --version" // Runs a Bourne shell script, typically on a Unix node
                }

                script {
                    withMaven(globalMavenSettingsConfig: "$mavenConfig", jdk: "$JDKVersion" /*, maven: "$mavenLocation"*/) {
                        try {
                            def pom = readMavenPom file: 'pom.xml'
//                            artifactVersion = "${pom.version}.${BUILD_NUMBER}".replace("-SNAPSHOT","")+"-SNAPSHOT"

                            artifactVersion =  "${pom.version}".replace("1-SNAPSHOT","")+"${BUILD_NUMBER}-SNAPSHOT"
//                            artifactVersion = "${pom.version}.${BUILD_NUMBER}"
                            sh "mvn -B versions:set -DnewVersion=${artifactVersion} -Pci-env  -f pom.xml"
//                            sh "mvn -B versions:set -DnewVersion=${pom.version}-${BUILD_NUMBER} -Pci-env"
                            sh "mvn -B clean package -Dmaven.test.skip=true -Pci-env"
                            stash name: "artifact"
                        } catch (Exception err) {
                            echo 'Maven clean install failed'
                            currentBuild.result = 'FAILURE'
                        } finally {
                            publishHTMLReports('Reports')
                        }
                    }
                }
            }
        }

        stage('Unit Tests') {
            steps {
                script {
                    withMaven(globalMavenSettingsConfig: "$mavenConfig", jdk: "$JDKVersion" /*, maven: "$mavenLocation"*/) {
                        try {
                            // -Dmaven.test.failure.ignore=true
                            // org.jacoco:jacoco-maven-plugin:prepare-agent
                            sh "mvn -B clean org.jacoco:jacoco-maven-plugin:prepare-agent test -Pci-env"
                            stash name: "unit_tests"
                        } catch (err) {
                            throw err
                        }
                    }
                }
            }
        }

        stage('Quality Analysis') {
            steps {
                /**
                 * makes use of one single agent, and spins off 2 runs of the steps inside each parallel branch
                 */
                parallel(
                    "Integration Test": {
                        echo 'Run integration tests'
                    },
                    "Sonar Scan": {
                        script {
                            unstash 'unit_tests'
                            withMaven(globalMavenSettingsConfig: "$mavenConfig", jdk: "$JDKVersion" /*, maven: "$mavenLocation"*/) {
                                withSonarQubeEnv('sonar') {
                                    branchName = URLDecoder.decode("${env.JOB_NAME}", "UTF-8");
                                    branchName = branchName.replaceAll('/', '-')
                                    echo branchName

                                    sh "mvn sonar:sonar -Dsonar.projectName=${env.JOB_NAME} -Dsonar.projectKey=${branchName}"
                                }
                            }
                        }
                    }
                )
            }
        }

        stage('Artifact upload') {
            when {
                environment name: 'DEPLOY_TO_NEXUS', value: 'true'
            }
            steps {
                script {

                    unstash 'artifact'

                    def pom = readMavenPom file: 'pom.xml'
                    println(pom)
                    def file = "${pom.artifactId}-${pom.version}"
//                    def file = "${pom.artifactId}-${artifactVersion}"
                    def jar = "target/${file}.jar"

                    sh "cp pom.xml ${file}.pom"
/*
                nexusArtifactUploader {
                    nexusVersion('nexus3')
                    protocol('http')
                    nexusUrl('192.168.33.20:8081')
                    groupId("${pom.groupId}")
                    version('2.4')
                    repository('ansible-meetup')
                    credentialsId(nexusCredentialsId)
                    artifact {
                        artifactId("${pom.artifactId}")
                        type('jar')
                        classifier('debug')
                        file('nexus-artifact-uploader.jar')
                    }
                    artifact {
                        artifactId('nexus-artifact-uploader')
                        type('hpi')
                        classifier('debug')
                        file('nexus-artifact-uploader.hpi')
                    }
                }
*/
                    nexusArtifactUploader artifacts: [
                            [artifactId: "${pom.artifactId}", classifier: '', file: "target/${file}.jar", type: 'jar'],
                            [artifactId: "${pom.artifactId}", classifier: '', file: "${file}.pom", type: 'pom']
                    ],
                            credentialsId: nexusCredentialsId,
                            groupId: "${pom.groupId}",
                            nexusUrl: NEXUS_URL,
                            nexusVersion: 'nexus3',
                            protocol: 'http',
                            repository: NEXUS_REPOSITORY,
                            version: "${pom.version}"
                }
            }
        }
    }


    /**
     * post section defines actions which will be run at the end of the Pipeline run or stage
     * post section condition blocks: always, changed, failure, success, unstable, and aborted
     */
    post {
        // Run regardless of the completion status of the Pipeline run
        always {
            //
            echo 'Run always'
        }

        failure {
            script {
                if (scmInfo) {
                    String payload = """{"state": "error", "description": "Jenkins build"}"""
                    withCredentials([[$class: 'StringBinding', credentialsId: gitHubCredentialsId, variable: 'TOKEN']]) {
                        def authData = [[name: "Authorization", value: "token ${env.TOKEN}"]]
                        updateGitHubStatus(githubRepositoryName, scmInfo.GIT_COMMIT, payload, authData)

                        /**
                        def response = httpRequest url: "https://api.github.com/repos/${githubRepositoryName}/statuses/${scmInfo.GIT_COMMIT}",
                                httpMode: 'POST',
                                acceptType: 'APPLICATION_JSON',
                                contentType: 'APPLICATION_JSON',
                                customHeaders: [[name: "Authorization", value: "token ${env.TOKEN}"]],
                                requestBody: payload
                        println(response)
                        /**/
                    }
                }
            }
        }

        success {
            script {
                if (scmInfo) {
                    String payload = """{"state": "success", "description": "Jenkins build"}"""
                    withCredentials([[$class: 'StringBinding', credentialsId: gitHubCredentialsId, variable: 'TOKEN']]) {

                        def authData = [[name: "Authorization", value: "token ${env.TOKEN}"]]
                        updateGitHubStatus(githubRepositoryName, scmInfo.GIT_COMMIT, payload, authData)

                        /**
                        def response = httpRequest url: "https://api.github.com/repos/${githubRepositoryName}/statuses/${scmInfo.GIT_COMMIT}",
                                httpMode: 'POST',
                                acceptType: 'APPLICATION_JSON',
                                contentType: 'APPLICATION_JSON',
                                customHeaders: [[name: "Authorization", value: "token ${env.TOKEN}"]],
                                requestBody: payload
                        println(response)
                        /**/
                    }
                }
            }
        }
    }

    // configure Pipeline-specific options
    options {
        // keep only last 10 builds
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // timeout job after 60 minutes
        timeout(time: 60,
                unit: 'MINUTES')
    }

}
