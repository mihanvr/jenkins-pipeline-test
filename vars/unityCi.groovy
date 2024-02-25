def call(def options) {
    pipeline(options)
}

def pipeline(def options){
    def nodeLabel = options.nodeLabel ?: options.env?.NODE_LABEL ?: "unity"
    node(nodeLabel) {
        options.env = env
        stage("Checkout") {
            def gitUrl = options.gitUrl ?: env?.GIT_URL
            def gitCredentials = options.gitCredentials ?: env?.GIT_CREDENTIALS
            def gitBranch = options.gitBranch ?: env?.GIT_BRANCH
            def userRemoteConfigs = [url: gitUrl]
            if (gitCredentials) {
                userRemoteConfigs.credentialsId = gitCredentials
            }
            def scmVars = checkout scmGit(
                    branches: [[name: gitBranch]],
                    extensions: [lfs()],
                    userRemoteConfigs: [userRemoteConfigs]
            )
            env.GIT_COMMIT = scmVars.GIT_COMMIT
        }

        stage("Build") {
            unityBuilder.build(options)
        }
        stage("Zip") {
            unityBuilder.processArtifacts(options)
        }

        webhook.post(options)
    }
}