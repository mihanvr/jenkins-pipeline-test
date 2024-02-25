def call(def script) {
    pipeline(script)
}

def pipeline(def script) {
    try {
        discordPush(script: script, webhookUrl: "https://discord.com/api/webhooks/1009734622650834994/RKxPLNbHgfO2JFQFY0CR7u2yCYgdzF71R-9JVt7h2L-LOxs83t77X5XY_wlYJqqz7Edl", buildStatus: "queued", color: 3506169)
        throw new Exception("some error")
    } catch (Exception e) {
        try {
            discordPush(script: script, webhookUrl: "https://discord.com/api/webhooks/1009734622650834994/RKxPLNbHgfO2JFQFY0CR7u2yCYgdzF71R-9JVt7h2L-LOxs83t77X5XY_wlYJqqz7Edl", content: e.toString(), color: 14225172, fields: [[name: "Download", value: "[link](https://jenkins.mi8820.ru)", inline: true]])
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
        def script = options.script
        def env = script.env

        def webhookUrl = options.webhookUrl
        def buildUrl = env?.BUILD_URL
        def jobName = options.script?.env?.JOB_NAME
        def buildPlatform = options.script?.buildTarget ?: options.script?.env?.BUILD_TARGET
        def content = options.content
        def embedsColor = options.color
        def buildStatus = options.buildStatus

        def embeds = [:]
        def discordContent = [embeds: embeds]
        if (content) discordContent.content = content
        if (embedsColor) embeds.color = embedsColor
        def fields = []
        embeds.fields = fields
        fields.add([name: "Build ${buildStatus}", value: "[link](${buildUrl})", inline: true])
        fields.add([name: "Job", value: jobName, inline: true])
        fields.add([name: "Platform", value: buildPlatform, inline: true])

        def json = writeJSON(json: discordContent, returnText: true)
        echo json
        echo "curl -X POST --location \"$webhookUrl\" -H \"Content-Type: application/json\" -d '${json}'"
        sh("curl -X POST --location \"$webhookUrl\" -H \"Content-Type: application/json\" -d '${json}'")
    }
}