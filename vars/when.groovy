import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

// This was brought local from: https://github.com/comquent/imperative-when
// Author: comquent.de

def call(boolean condition, body) {
    def config = [:]
    body.resolveStrategy = Closure.OWNER_FIRST
    body.delegate = config

    if (condition) {
        body()
    } else {
        Utils.markStageSkippedForConditional(STAGE_NAME)
    }
}

