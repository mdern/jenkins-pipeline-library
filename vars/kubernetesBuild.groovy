#!/usr/bin/groovy
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(Map config = [:], body) {

  //Global definitions
  JOB_NAME = config.customJobName ?: "${env.JOB_NAME}".toLowerCase().replace('/', '-').replace('%2F', '-').replace('%', '-')
  String label = "jenkinsbuildpod.${JOB_NAME}.${env.BUILD_NUMBER}"
  String serviceAccount = config.serviceAccount ?: 'jenkins' // Try to guess at SA to use
  def imagePullSecrets = config.imagePullSecrets ?: []
  def customContainers = config.customContainers ?: []
  def customVolumes = config.customVolumes ?: []
  def customEnvVars = config.customEnvVars ?: []
  String git_credentials = config.gitCredentials ?: 'git'

  //Image versions for build containers
  String buildImage = config.buildImage ?: 'docker:17.12.0-ce-dind'
  String jnlpImage = config.jnlpImage ?: 'jenkins/jnlp-slave:3.19-1-alpine'

  ////// Local configuration variables //////

  //This should always be defined... we'll not bother to check and just error if not
  def deploymentMap = config.deploymentMap

  def dockerRepository = config.dockerRepository ?: 'docker-cse.wds.io'
  String slackChannel = config.slackChannel
  ////// Local configuration variables //////

  // Send status to Gitlab early so we know the build started
  if (env.gitlabSourceBranch) updateGitlabCommitStatus name: 'Checkout', state: 'pending'
  if (slackChannel) notifySlack {channel = slackChannel}

  //Build instantiation
  try {
    podTemplate(
      cloud: 'kubernetes',
      serviceAccount: serviceAccount,
      label: label,
      imagePullSecrets: imagePullSecrets,  
      containers: customContainers + [
        [
          name: 'jnlp',
          image: "${jnlpImage}",
          args: '${computer.jnlpmac} ${computer.name}'
        ],
        [
          name: 'builder',
          image: "${buildImage}",
          command: 'sh -c',
          args: 'cat',
          ttyEnabled: true
        ]
      ],
      volumes: customVolumes + [
        secretVolume(secretName: 'jenkins-docker-cfg', mountPath: '/home/jenkins/.docker'),
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
      ],
      envVars: customEnvVars + [
        podEnvVar(key: 'DOCKER_API_VERSION', value: '1.23'),
        podEnvVar(key: 'DOCKER_HOST', value: 'unix:///var/run/docker.sock'),
        podEnvVar(key: 'DOCKER_CONFIG', value: '/home/jenkins/.docker')
      ]
    ) {

      node(label) {

        stage("Checkout Code") {
          //Checkout using gitlab merge information
          String sourceBranch = env.gitlabSourceBranch 
          String mergeBranch = env.gitlabTargetBranch ?: env.gitlabSourceBranch

          gitlabCommitStatus("Checkout") {

            checkout changelog: true, poll: true, scm: [
              $class: 'GitSCM',
              branches: [[name: "origin/${sourceBranch}"]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'PreBuildMerge', options: [fastForwardMode: 'FF', mergeRemote: 'origin', mergeTarget: "${mergeBranch}"]]],
              submoduleCfg: [],
              userRemoteConfigs: [[name: 'origin', url: scm.getUserRemoteConfigs()[0].getUrl(), credentialsId: git_credentials]]
            ]
          }
        }

        // Wrap build in gitlab status updates for each stage
        gitlabBuilds(builds: config.buildStages ?: ["Build"]) {
          body()
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
    stage("Slack Build Result") {
      if(slackChannel) {
        notifySlack { 
          buildStatus = buildResult
          channel = slackChannel
        }
      } else {
        Utils.markStageSkippedForConditional(STAGE_NAME)
      }
    }
    //Send gitlab final notification of build result
    stage("Gitlab Build Result") {
      if(env.gitlabMergeRequestId) {
        def thumbs
        if (buildResult == "SUCCESS") {
          thumbs = "👍"
        } else {
          thumbs = "👎"
        }

        if (env.gitlabSourceBranch) addGitLabMRComment comment: """Build result ${buildResult} ${thumbs}: [${env.RUN_DISPLAY_URL}]"""
      } else {
        Utils.markStageSkippedForConditional(STAGE_NAME)
      }
    }//stage(gitlab)
  }//try
}
