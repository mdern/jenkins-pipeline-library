#!/usr/bin/groovy

def call(body) {

  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config

  //Global definitions
  JOB_NAME = config.customJobName ? config.customJobName : "${env.JOB_NAME}".toLowerCase().replace('/', '-').replace('%2F', '-').replace('%', '-')
  String label = "jenkinsbuildpod.${JOB_NAME}.${env.BUILD_NUMBER}"
  String serviceAccount = config.serviceAccount ? config.serviceAccount : 'jenkins' // Try to guess at SA to use
  def imagePullSecrets = config.imagePullSecrets ? config.imagePullSecrets : []
  def customContainers = config.customContainers ? []
  def customVolumes = config.customVolumes ? []
  def customEnvVars = config.customEnvVars ? []
  String git_credentials = config.gitCredentials ? 'git'

  //Image versions for build containers
  String buildImage = config.buildImage ? config.buildImage : 'docker:17.12.0-ce-dind'
  String jnlpImage = config.jnlpImage ? config.jnlpImage : 'jenkins/jnlp-slave:3.19-1-alpine'

  ////// Local configuration variables //////

  //This should always be defined... we'll not bother to check and just error if not
  def deploymentMap = config.deploymentMap

  def dockerRepository = config.dockerRepository ? 'docker-cse.wds.io'
  String slackChannel = config.slackChannel
  ////// Local configuration variables //////

  // Send status to Gitlab early so we know the build started
  if (env.gitlabSourceBranch) {
    updateGitlabCommitStatus name: 'build', state: 'pending'
  }
  notifySlack {channel = slackChannel}

  //Build instantiation
  try {
    podTemplate(
      cloud: 'kubernetes',
      serviceAccount: serviceAccount,
      label: label,
      imagePullSecrets: imagePullSecrets,  
      containers: [
        [
          name: 'jnlp',
          image: "${jnlpImage}",
          args: '${computer.jnlpmac} ${computer.name}',
          workingDir: '/home/jenkins/'
        ],
        [
          name: 'builder',
          image: "${buildImage}",
          command: 'sh -c',
          args: 'cat',
          ttyEnabled: true
        ],
        customContainers
      ].findAll(),
      volumes: [
        secretVolume(secretName: 'jenkins-docker-cfg', mountPath: '/home/jenkins/.docker'),
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
        customVolumes
      ].findAll(),
      envVars: [
        podEnvVar(key: 'DOCKER_API_VERSION', value: '1.23'),
        podEnvVar(key: 'DOCKER_HOST', value: 'unix:///var/run/docker.sock'),
        podEnvVar(key: 'DOCKER_CONFIG', value: '/home/jenkins/.docker'),
        customEnvVars
      ].findAll()
    ) {

      node(label) {

        //Checkout using gitlab merge information
        String sourceBranch = env.gitlabSourceBranch ? env.gitlabSourceBranch : env.SOURCE_BRANCH
        String mergeBranch = env.gitlabTargetBranch ? env.gitlabTargetBranch : env.gitlabSourceBranch
          
        checkout changelog: true, poll: true, scm: [
          $class: 'GitSCM',
          branches: [[name: "origin/${sourceBranch}"]],
          doGenerateSubmoduleConfigurations: false,
          extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeTarget: "origin/${mergeBranch}"]]],
          submoduleCfg: [],
          userRemoteConfigs: [[name: 'origin', url: scm.getUserRemoteConfigs()[0].getUrl(), credentialsId: git_credentials]]
        ]

        // Send status to Gitlab early so we know the build started
        if (env.gitlabSourceBranch) {
          updateGitlabCommitStatus name: 'build', state: 'pending'
        }

        def newVersion = sh( script: 'git rev-parse --short HEAD', returnStdout: true).toString().trim() + "_${env.BUILD_NUMBER}"
        def clusterImageName = "${dockerRepository}/${JOB_NAME}:${newVersion}".toLowerCase()

        //Build step definition

        // Wrap build in gitlab status updates for each stage
        gitlabBuilds(builds: ["build", "push"]) {
          body()

          // Check if our deployment branch matches the source branch and deploy, or not
          // Could make this more DRY via a function, but this is to give a good simple example of deployment
          if (deploymentMap.development.branches.contains(env.gitlabSourceBranch)) {
            stage('Rollout to Development') {
              gitlabCommitStatus("deploy") {
                helmDeployment {
                  namespace = deploymentMap.development.namespace
                  resourceRequestCPU = '200m'
                  resourceRequestMemory = '512Mi'
                  resourceLimitCPU = '200m'
                  resourceLimitMemory = '512Mi'
                  replicaCount = 1
                  internalPort = 8000
                  healthUri = '/images/select2.png'
                  healthPort = 8000
                  imageName = clusterImageName
                  imagePullSecret = 'docker-registry'
                  hostNames = deploymentMap.development.domains
                  ingressAnnotations = ["kubernetes.io/ingress.class": "nginx", "kubernetes.io/tls-acme": "true"]
                  customValues = [
                    "persistence": [
                      "enabled": "true",
                      "accessMode": "ReadWriteOnce",
                      "storageClass": "standard",
                      "size": "8Gi"
                    ],
                    "cron_url": deploymentMap.development.domains[0],
                    "db_config": [
                      "db_name": "infinitewp",
                      "db_user": "infinitewp_admin",
                      "db_password": "-,W3&NK(%asq4YZQcy^",
                      "db_host": "prd-cse-marketingprd-shared.cam8ougivgnz.us-east-1.rds.amazonaws.com",
                    ]
                  ]
                }//helmDeployment
              }//gitlabCommitStatus("deploy")
            }//development
          } else if (deploymentMap.production.branches.contains(env.gitlabSourceBranch)) {
            stage('Rollout to Production') {
              gitlabCommitStatus("deploy") {
                helmDeployment {
                  namespace = deploymentMap.production.namespace
                  resourceRequestCPU = '500m'
                  resourceRequestMemory = '2048Mi'
                  resourceLimitCPU = '500m'
                  resourceLimitMemory = '2048Mi'
                  replicaCount = 1
                  internalPort = 8000
                  healthUri = '/images/select2.png'
                  healthPort = 8000
                  imageName = clusterImageName
                  imagePullSecret = 'docker-registry'
                  hostNames = deploymentMap.production.domains
                  ingressAnnotations = ["kubernetes.io/ingress.class": "nginx", "kubernetes.io/tls-acme": "true"]
                  customValues = [
                    "persistence": [
                      "enabled": "true",
                      "accessMode": "ReadWriteOnce",
                      "storageClass": "standard",
                      "size": "20Gi"
                    ],
                    "cron_url": deploymentMap.production.domains[0],
                    "db_config": [
                      "db_name": "infinitewp",
                      "db_user": "infinitewp_admin",
                      "db_password": "-,W3&NK(%asq4YZQcy^",
                      "db_host": "prd-cse-marketingprd-shared.cam8ougivgnz.us-east-1.rds.amazonaws.com",
                    ]
                  ]
                }//helmDeployment
              }//gitlabCommitStatus("deploy")
            }//production 
          }//if production
        }//gitlabbuildsteps
      }//node
    }//podtemplate

  //Try/Catch block which catches timeouts, aborted builds and failures or successes
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
    if (interruptEx.causes.size() == 0) {
      currentBuild.result = 'ABORTED'
    } else {
      currentBuild.result = 'UNSTABLE'
    }
    throw interruptEx
  } catch (hudson.AbortException abortEx) {
    currentBuild.result = 'ABORTED'
    throw abortEx
  } catch (e) {
    currentBuild.result = 'FAILURE'
    throw e
  } finally {
    def buildResult = currentBuild.result ?: 'SUCCESS'

    //Send slack notification of final build result
    notifySlack { 
      buildStatus = buildResult
      channel = slackChannel
    }
    //Send gitlab final notification of build result
    if (env.gitlabMergeRequestId) {
      def thumbs
      if (buildResult == "SUCCESS") {
        thumbs = "👍"
      } else {
        thumbs = "👎"
      }
      if (env.gitlabSourceBranch) {
        addGitLabMRComment comment: """Build result ${buildResult} ${thumbs}: [${env.RUN_DISPLAY_URL}]"""
      }
    }
  }//try
}
