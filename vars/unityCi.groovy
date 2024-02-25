def call(def script) {
    pipeline(script)
}

def pipeline(def script) {
    script.discordWebhookUrl = "https://discord.com/api/webhooks/1009734622650834994/RKxPLNbHgfO2JFQFY0CR7u2yCYgdzF71R-9JVt7h2L-LOxs83t77X5XY_wlYJqqz7Edl"
    try {
        discordPush(script: script, buildStatus: "Queued")
        defaultPipeline(script)
        discordPush(script: script, buildStatus: "Success", fields: [])
        postWebhook(script)
    } catch (Exception e) {
        try {
            discordPush(script: script, content: e.toString(), buildStatus: "Failed")
        } catch (Exception e2) {
            echo e2.toString()
        }
        throw e
    }
}

def defaultPipeline(def script) {
    def options = script.options
    def nodeLabel = options?.nodeLabel ?: options.env?.NODE_LABEL ?: "unity"
    node(nodeLabel) {
        script.env += env
        def env = script.env
        stage("Checkout") {
            def gitUrl = options?.gitUrl ?: env?.GIT_URL
            def gitCredentials = options?.gitCredentials ?: env?.GIT_CREDENTIALS
            def gitBranch = options?.gitBranch ?: env?.GIT_BRANCH
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
            unityBuilder.build(script)
        }
        stage("Zip") {
            unityBuilder.processArtifacts(script)
        }

        webhook.post(options)
    }
}

def postWebhook(def options) {
    def changeLog = getChangeLogFromLatestSuccess(options)
    def artifacts = getBuildArtifacts(options)

    def jsonBody = [
            result               : currentBuild.result,
            project_name         : currentBuild.projectName,
            git_branch           : env.GIT_BRANCH,
            git_commit           : env.GIT_COMMIT,
            build_duration_millis: currentBuild.duration,
            build_duration       : currentBuild.durationString,
            build_id             : env.BUILD_ID,
            build_number         : env.BUILD_NUMBER,
            build_tag            : env.BUILD_TAG,
            build_url            : env.BUILD_URL,
            build_target         : env.BUILD_TARGET,
            job_base_name        : env.JOB_BASE_NAME,
            job_name             : env.JOB_NAME,
            job_url              : env.JOB_URL,
            node_name            : env.NODE_NAME,
            change_log           : changeLog,
            artifacts            : artifacts
    ]
    def json = writeJSON returnText: true, json: jsonBody
    echo "Post body: ${json}"

    def webhookUrl = options.webhookUrl ?: env?.WEBHOOK_URL

    if (/*currentBuild.result == 'SUCCESS' && */ webhookUrl) {
        def customHeaders = []

        def xApiKey = options.xApiKey ?: env?.X_API_KEY
        if (xApiKey) {
            customHeaders.add([name: 'X-API-KEY', value: xApiKey])
        } else {
            def webhookCredentials = options.webhookCredentials ?: env?.WEBHOOK_CREDENTIALS
            if (webhookCredentials) {
                withCredentials([string(credentialsId: webhookCredentials, variable: 'xApiKeyCred')]) {
                    customHeaders.add([name: 'X-API-KEY', value: "${xApiKeyCred}"])
                }
            }
        }
        httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: webhookUrl, customHeaders: customHeaders, validResponseCodes: '100:599'
    }
}

@NonCPS
def getBuildArtifacts(def options) {
    def currentBuild = options.currentBuild ?: options.script?.currentBuild

    def buildArtifacts = currentBuild.rawBuild.artifacts
    def artifacts = []
    def env = options.env ?: options.script?.env
    for (int i = 0; i < buildArtifacts.size(); i++) {
        def file = buildArtifacts[i]
        artifacts.add([size: "${file.fileSize}", name: "${file.fileName}", href: "${env.BUILD_URL}artifact/${file.fileName}"])
    }
    return artifacts
}

@NonCPS
def getChangeLogFromLatestSuccess(def options) {
    def currentBuild = options.currentBuild ?: options.script?.currentBuild
    def build = currentBuild
    def passedBuilds = []
    while (build != null) {
        passedBuilds.add(build)
        if (build.result == 'SUCCESS') break
        build = build.getPreviousBuild()
    }
    return getChangeLog(passedBuilds)
}

@NonCPS
def getChangeLog(def passedBuilds) {
    def log = ""
    for (int x = 0; x < passedBuilds.size(); x++) {
        def currentBuild = passedBuilds[x]
        def changeLogSets = currentBuild.rawBuild.changeSets
        for (int i = 0; i < changeLogSets.size(); i++) {
            def entries = changeLogSets[i].items
            for (int j = 0; j < entries.length; j++) {
                def entry = entries[j]
                log += "* ${entry.msg} by ${entry.author} \n"
            }
        }
    }
    return log
}

def discordPush(def options) {
    node {
        def script = options.script
        def env = script.env

        def webhookUrl = script?.discordWebhookUrl ?: env?.DISCORD_WEBHOOK_URL
        def buildUrl = env?.BUILD_URL
        def jobName = options.script?.env?.JOB_NAME
        def buildPlatform = options.script?.options?.buildTarget ?: options.script?.env?.BUILD_TARGET
        def content = options.content
        def buildStatus = options.buildStatus
        def embedsColor = options.color ?: getDiscordEmbedsColorFromStatus(buildStatus)

        def embeds = [:]
        def discordContent = [embeds: [embeds]]
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

def getDiscordEmbedsColorFromStatus(def buildStatus) {
    if (buildStatus == 'Failed') return 14225172
    if (buildStatus == 'Queued') return 3506169
    if (buildStatus == 'Success') return 42837
    if (buildStatus == 'Canceled') return 15258703
    return null
}