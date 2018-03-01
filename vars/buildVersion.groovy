#!/usr/bin/groovy

def call(Map config) {

    // Build image version
    def imageVersion = [config.imageVersionCustom, sh( script: 'git rev-parse --short HEAD', returnStdout: true).toString().trim() + "_${env.BUILD_NUMBER}"].findAll()

    def buildImageName = "${config.dockerRepository}/${JOB_NAME}:${imageVersion[0]}".toLowerCase()

    return buildImageName
}
