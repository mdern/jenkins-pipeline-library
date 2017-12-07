#!/usr/bin/groovy
import io.fabric8.Utils
import io.fabric8.Fabric8Commands
import org.yaml.snakeyaml.Yaml

// Small method to turn maps or arrays into yaml
def to_yaml(yamldata) {
  Yaml yaml = new Yaml()
  return yaml.dump(yamldata)
}

//Build map of tls secrets
def String tls_string(hostnames) {
  def Map hostmap = [tls: []]
  for (host in hostnames) {
    hostmap.tls << [secretName:host + "-tls-secret", hosts: [host]]
  }

  return to_yaml(hostmap)
}

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def flow = new Fabric8Commands()
    def utils = new Utils()

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
    def image_name = config.imageName ?: "${fabric8Registry}${env.KUBERNETES_NAMESPACE}/${env.JOB_NAME}:${config.version}"
    def custom_values = config.customValues ?: ''
    def ingress_enable = config.ingressEnable ?: 'false'

    def values = """
    replicaCount: ${replicaCount}
    image:
      name: ${image_name}
      pullPolicy: ${imagepull}
    service:
      name: ${service_name}
      type: ClusterIP
      externalPort: ${external_port}
      internalPort: ${internal_port}
    """.stripIndent()
    def ingress = """
    ingress:
      enabled: true
      ${to_yaml([hosts: [config.hostNames]])}
      ${to_yaml([annotations: [config.ingressAnnotations]])}
      ${tls_string(config.hostNames)}
    """.stripIndent()
    def resources = """
    resources:
      limits:
        cpu: ${limitCPU}
        memory: ${limitMemory}
      requests:
        cpu: ${requestCPU}
        memory: ${requestMemory}
    """.stripIndent()

    return values + ingress + resources
}
