def call(def options) {
    pipeline(options)
}

def pipeline(def options) {
    try {
        discordPush(discordUrl: "https://discord.com/api/webhooks/1009734622650834994/RKxPLNbHgfO2JFQFY0CR7u2yCYgdzF71R-9JVt7h2L-LOxs83t77X5XY_wlYJqqz7Edl", message: [embeds: [[color = 3506169, fields = [[name: "1", value: "2", inline: true]]]]])
    } catch (Exception e) {
        try {
            discordPush(discordUrl: "https://discord.com/api/webhooks/1009734622650834994/RKxPLNbHgfO2JFQFY0CR7u2yCYgdzF71R-9JVt7h2L-LOxs83t77X5XY_wlYJqqz7Edl", message: [embeds: [[color = 14225172, content = e.toString(), fields = [[name: "1", value: "2", inline: true]]]]])
        } catch (Exception e2) {
            echo e2.toString()
        }
        throw e
    }
//    def nodeLabel = options.nodeLabel ?: options.env?.NODE_LABEL ?: "unity"
//    try {
//        node(nodeLabel) {
//            options.env = env
//            stage("Checkout") {
//                def gitUrl = options.gitUrl ?: env?.GIT_URL
//                def gitCredentials = options.gitCredentials ?: env?.GIT_CREDENTIALS
//                def gitBranch = options.gitBranch ?: env?.GIT_BRANCH
//                def userRemoteConfigs = [url: gitUrl]
//                if (gitCredentials) {
//                    userRemoteConfigs.credentialsId = gitCredentials
//                }
//                def scmVars = checkout scmGit(
//                        branches: [[name: gitBranch]],
//                        extensions: [lfs()],
//                        userRemoteConfigs: [userRemoteConfigs]
//                )
//                env.GIT_COMMIT = scmVars.GIT_COMMIT
//            }
//
//            stage("Build") {
//                unityBuilder.build(options)
//            }
//            stage("Zip") {
//                unityBuilder.processArtifacts(options)
//            }
//
//            webhook.post(options)
//        }
//    } catch (Exception e) {
//        throw e
//    }
}

def discordNotify() {
//    def buildStarted = 3506169
//    def buildSuccess = 42837
//    def buildFailed = 14225172
//    def buildCancelled = 15258703

}

def discordPush(def options) {
    node {
        def webhookUrl = options.webhookUrl
        def message = options.message
        def json = writeJSON(json: message, returnText: true)
        sh("curl -X POST --location \"$webhookUrl\" -H \"Content-Type: application/json\" -d '${json}'")
    }
}