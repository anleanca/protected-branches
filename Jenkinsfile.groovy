def getProjectName() {
    return 'JenkinsPipeline'
}

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
                retry (3) {
                    script {
                        try {
                            if (checkJobBuildRunned(jobName)) {
                                error 'FAIL'
                            }
                        } catch (err) {
                            sleep(time:60,unit:"SECONDS")
                        } finally {
                            error 'FAIL'
                        }
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
                            sh "mvn -B versions:set -DnewVersion=${pom.version}-${BUILD_NUMBER}"
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
                        error 'FAILURE'
//                        try {
                            // -Dmaven.test.failure.ignore=true
                            // org.jacoco:jacoco-maven-plugin:prepare-agent
                            sh "mvn -B clean org.jacoco:jacoco-maven-plugin:prepare-agent test -Pci-env"
                            stash name: "unit_tests"
//                        } catch (err) {
//                            withCredentials([[$class: 'StringBinding', credentialsId: gitHubCredentialsId, variable: 'TOKEN']]) {
//                                sh "githubstatus.py --token ${env.TOKEN} --repo ${githubRepositoryName}  status --status=error --sha ${scmInfo.GIT_COMMIT}"
//                            }
//                            throw err
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
                String payload = """{"state": "error", "description": "Jenkins build"}"""
                withCredentials([[$class: 'StringBinding', credentialsId: gitHubCredentialsId, variable: 'TOKEN']]) {
                    /**/
                    def response = httpRequest url: "https://api.github.com/repos/${githubRepositoryName}/statuses/${scmInfo.GIT_COMMIT}",
                            httpMode: 'POST',
                            acceptType: 'APPLICATION_JSON',
                            contentType: 'APPLICATION_JSON',
                            customHeaders:[[name:"Authorization", value: "token ${env.TOKEN}"]],
                            requestBody: payload
                    println(response)
                }
        }

        success {
            script {
                String payload = """{"state": "success", "description": "Jenkins build"}"""
                withCredentials([[$class: 'StringBinding', credentialsId: gitHubCredentialsId, variable: 'TOKEN']]) {
                    /**/
                    def response = httpRequest url: "https://api.github.com/repos/${githubRepositoryName}/statuses/${scmInfo.GIT_COMMIT}",
                            httpMode: 'POST',
                            acceptType: 'APPLICATION_JSON',
                            contentType: 'APPLICATION_JSON',
                            customHeaders:[[name:"Authorization", value: "token ${env.TOKEN}"]],
                            requestBody: payload
                    println(response)
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
