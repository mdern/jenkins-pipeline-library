#!/usr/bin/groovy
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

// Small method to turn maps or arrays into yaml
def static to_yaml(Map yamldata) {
  DumperOptions options = new DumperOptions();
  options.setAllowReadOnlyProperties(true);
  options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
  options.setIndent(2);
  Yaml yaml = new Yaml(options)
  return yaml.dump(yamldata)
}

//Build map of tls secrets
def tls_string(hostnames) {
  def Map hostmap = [tls: []]
  for (host in hostnames) {
    hostmap["tls"] << [secretName:host + "-tls-secret", hosts: [host]]
  }

  return hostmap
}

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def serviceAccount = config.serviceAccount ?: 'jenkins'
    def service_name = config.serviceName ?: "${env.JOB_NAME}"
    def replicaCount = config.replicaCount ?: '1'
    def requestCPU = config.resourceRequestCPU ?: '0'
    def requestMemory = config.resourceRequestMemory ?: '0'
    def limitCPU = config.resourceLimitCPU ?: '0'
    def limitMemory = config.resourceLimitMemory ?: '0'
    def health_uri = config.healthUri ?: '/'
    def health_port = config.healthPort ?: "${config.port}"
    def external_port = config.externalPort ?: '80'
    def internal_port = config.internalPort ?: '80'
    def imagepull = config.imagePullPolicy ?: 'IfNotPresent'
    def image_name = config.imageName
    def secondary_image_name = config.secondaryImageName ?: ''
    def custom_values = config.customValues ?: ["empty_custom": "true"]
    def ingress_enable = config.ingressEnable ?: 'false'
    def imagepullsecret = config.imagePullSecret ?: 'false'
    def timeout = config.initTimeout ?: '600'
    def requestsephemeralstorage = config.resourceRequestsEphemeralStorage ?: '2Gi'
    def limitephemeralstorage = config.resourceLimitEphemeralStorage ?: '4Gi' 
    def helmVersion = config.helmVersion ?: '0.90.8-2.16.1-1.14.7'

    def values = """
replicaCount: ${replicaCount}
image:
  name: ${image_name}
  secondary_image_name: ${secondary_image_name}
  pullPolicy: ${imagepull}
  pullSecret: ${imagepullsecret}
service:
  name: ${service_name}
  type: ClusterIP
  externalPort: ${external_port}
  internalPort: ${internal_port}
  healthPath: ${health_uri}
  healthPort: ${health_port}
""".stripIndent()

    def ingress = to_yaml([
      ingress: [
        enabled: true,
        hosts: config.hostNames,
        annotations: config.ingressAnnotations,
        tls: tls_string(config.hostNames).tls
      ]
    ])

    def resources = """
resources:
  limits:
    cpu: ${limitCPU}
    memory: ${limitMemory}
    ephemeral-storage: ${limitephemeralstorage}
  requests:
    cpu: ${requestCPU}
    memory: ${requestMemory}
    ephemeral-storage: ${requestsephemeralstorage}
""".stripIndent()

    def helmConfig = values + ingress + resources + to_yaml(custom_values)

    stash name: "helmconfig", includes: "chart/*/**"

    podTemplate(
      cloud: 'kubernetes',
      serviceAccount: "${serviceAccount}",
      label: 'helm-build-job',
      containers: [
        [
            name: 'jnlp',
           
            command: 'sh -c',
            args: 'jenkins-slave ${computer.jnlpmac} ${computer.name}'
        ],
        [
          name: 'helm',
          image: "chatwork/helmfile:${helmVersion}",
          command: 'sh -c',
          args: 'cat',
          ttyEnabled: true
        ]
      ]
    ) {
      node('helm-build-job') {
        container(name: 'helm') {
          
          writeFile file: 'values.yaml', text: helmConfig

          unstash "helmconfig"
          
          if (! config.namespace) {
            throw new Exception("Namespace cannot be empty!")
          }

          sh "echo 'Starting helm upgrade or install'"
          sh "helm version"
          sh "helm upgrade ${config.namespace.take(4)}-${service_name} chart --wait --debug --timeout ${timeout} --install --namespace ${config.namespace} -f values.yaml"

        }
      }
    }

}
