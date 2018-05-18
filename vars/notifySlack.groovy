#!/usr/bin/groovy

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def buildStatus = config.buildStatus ?: 'STARTED'
    def enableBlueOcean = config.enableBlueOcean ?: true

    def buildurl

    if (enableBlueOcean) {
      buildurl = "${env.RUN_DISPLAY_URL}"
    } else {
      buildurl = "${env.BUILD_URL}"
    }

    def color
    def emoji

    if (buildStatus == 'STARTED') {
      color = '#D4DADF'
      emoji = "🔮"
    } else if (buildStatus == 'SUCCESS') {
      color = '#BDFFC3'
      emoji = "🎉"
    } else if (buildStatus == 'UNSTABLE' || buildStatus == 'ABORTED') {
      color = '#FFFE89'
      emoji = "⛅"
    } else {
      color = '#FF9FA1'
      emoji = "🔥"
    }

    def msg = "${buildStatus} ${emoji}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${buildurl}"

    slackSend(color: color, message: msg, channel: config.channel)

}
