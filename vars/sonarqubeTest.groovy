#!/usr/bin/groovy
def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

		// Config variables
   	String serviceAccount = config.serviceAccount ?: 'jenkins'
		String cloudType = config.cloudType ?: 'kubernetes'
		String jnlpImage = config.jnlpImage ?: 'jenkins/jnlp-slave:3.19-1-alpine'
		String sonarqubeImage = config.sonarqubeImage ?: 'zaquestion/sonarqube-scanner:latest'
    String sonarqubeEnvironment = config.sonarqubeEnvironment ?: 'default'
		def allowedQuality = config.allowedQuality ?: ['OK']
    String additionalCmd = config.additionalCmd ?: ''

    // Cleanup gitlab URL to find root for API access... messy but it works
    def gitlabURI = config.gitlabURI ?: "${env.gitlabSourceRepoHttpUrl.split('/').findAll()[0]}//${env.gitlabSourceRepoHttpUrl.split('/').findAll()[1]}"

    // Stash repo for using inside sonarqube
    stash name: "build", useDefaultExcludes: false

    podTemplate(
      cloud: cloudType,
      serviceAccount: serviceAccount,
      label: 'sonarqube-test',
      containers: [
        [
            name: 'jnlp',
            image: jnlpImage,
            args: '${computer.jnlpmac} ${computer.name}'
        ],
        [
          name: 'sonarqube',
          image: sonarqubeImage,
          command: 'sh -c',
          args: 'cat',
          ttyEnabled: true
        ] 
      ]
    ) {
      node('sonarqube-test') {
				stage('Static Code Analysis') {
          container(name: 'sonarqube') {
         
            // Unstash build
            unstash "build"

						if (env.gitlabMergeRequestId) {
              if (config.gitlabCredential) {
                withCredentials([string(credentialsId: "${config.gitlabCredential}", variable: 'GITLAB_TOKEN')]) {
                  withSonarQubeEnv(sonarqubeEnvironment) {
                    sh "/root/sonar_home/bin/sonar-scanner -Dsonar.projectKey=${JOB_NAME} -Dsonar.projectName=${JOB_NAME} -Dsonar.sources=. -Dsonar.gitlab.url=${gitlabURI} -Dsonar.gitlab.user_token=${GITLAB_TOKEN} -Dsonar.gitlab.commit_sha=${env.gitlabMergeRequestLastCommit} -Dsonar.gitlab.ref_name=${env.gitlabSourceBranch} -Dsonar.gitlab.project_id=${env.gitlabMergeRequestTargetProjectId} ${additionalCmd}"
                  }
                }
              } else {
                withSonarQubeEnv(sonarqubeEnvironment) {
                  sh "/root/sonar_home/bin/sonar-scanner -Dsonar.projectKey=${JOB_NAME} -Dsonar.projectName=${JOB_NAME} -Dsonar.sources=. -Dsonar.gitlab.url=${gitlabURI} -Dsonar.gitlab.user_token=${config.gitlabToken} -Dsonar.gitlab.commit_sha=${env.gitlabMergeRequestLastCommit} -Dsonar.gitlab.ref_name=${env.gitlabSourceBranch} -Dsonar.gitlab.project_id=${env.gitlabMergeRequestTargetProjectId} ${additionalCmd}"
                }
              }

							def qualitygate = waitForQualityGate()
							if (! allowedQuality.contains(qualitygate.status)) {
								error "SonarQube quality scan failed: ${qualitygate.status}"
							}
						} else {
							echo "Probably not a merge request. Skipping Static Code Analysis."
						}//if gitlab
					}//container
				}//stage('Static Code Analysis')
      }//node
    }//podtemplate

}//def
