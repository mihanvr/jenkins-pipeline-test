def call(def script) {
    pipeline(script)
}

def pipeline(def script) {
    try {
        notify(script: script, buildStatus: "Queued")
        defaultPipeline(script)
        notify(script: script, buildStatus: "Success", fields: [])
        postWebhook(script)
    } catch (Exception e) {
        try {
            if (e instanceof InterruptedException) {
                notify(script: script, buildStatus: "Canceled")
            } else {
                notify(script: script, content: e.toString(), buildStatus: "Failed")
            }
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
        notify(script: script, buildStatus: "Started")
        this.options = options
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
            unityBuilder.build(this)
        }
        stage("Zip") {
            unityBuilder.processArtifacts(this)
        }

        postWebhook(script)
    }
}

def postWebhook(def script) {
    def changeLog = getChangeLogFromLatestSuccess(script)
    def artifacts = getBuildArtifacts(script)
    def env = script.env
    def options = script.options

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

    def webhookUrl = options?.webhookUrl ?: env?.WEBHOOK_URL

    if (/*currentBuild.result == 'SUCCESS' && */ webhookUrl) {
        def customHeaders = []

        def xApiKey = options?.xApiKey ?: env?.X_API_KEY
        if (xApiKey) {
            customHeaders.add([name: 'X-API-KEY', value: xApiKey])
        } else {
            def webhookCredentials = options?.webhookCredentials ?: env?.WEBHOOK_CREDENTIALS
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
def getBuildArtifacts(def script) {
    def currentBuild = script.currentBuild

    def buildArtifacts = currentBuild.rawBuild.artifacts
    def artifacts = []
    def env = script.env ?: script.script?.env
    for (int i = 0; i < buildArtifacts.size(); i++) {
        def file = buildArtifacts[i]
        artifacts.add([size: "${file.fileSize}", name: "${file.fileName}", href: "${env.BUILD_URL}artifact/${file.fileName}"])
    }
    return artifacts
}

@NonCPS
def getChangeLogFromLatestSuccess(def script) {
    def currentBuild = script.currentBuild
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

def notify(def options) {
    discordNotify(options)
}

def discordNotify(def options) {
    def notifyStages = options.script?.options?.notifyStages
    def script = options.script
    def env = script.env
    def buildStatus = options.buildStatus
    def webhookUrl = options?.discordWebhookUrl ?: env?.DISCORD_WEBHOOK_URL
    if (!webhookUrl) return
    if (notifyStages == null || !notifyStages.contains(buildStatus)) return

    node(null) {
        def buildUrl = env?.BUILD_URL
        def jobUrl = env?.JOB_URL
        def jobName = options.script?.env?.JOB_NAME
        def buildPlatform = options.script?.options?.buildTarget ?: options.script?.env?.BUILD_TARGET
        def content = options.content
        def embedsColor = options.color ?: getDiscordEmbedsColorFromStatus(buildStatus)

        def buildNumber = env?.BUILD_NUMBER

        def embeds = [:]
        def discordContent = [embeds: [embeds]]
        if (content) discordContent.content = content
        if (embedsColor) embeds.color = embedsColor
        def fields = []
        embeds.fields = fields

        fields.add([name: "Build ${buildStatus}", value: "[#${buildNumber}](${buildUrl})", inline: true])
        fields.add([name: "Job", value: "[${jobName}](${jobUrl})", inline: true])
        fields.add([name: "Platform", value: buildPlatform, inline: true])

        def artifacts = getBuildArtifacts(script)
        if (artifacts.size() > 0) {
            def art0 = artifacts[0]
            fields.add([name: "Download", value: "[${art0.name}](${art0.href})", inline: true])
        }

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
    if (buildStatus == 'Started') return 3506169
    if (buildStatus == 'Canceled') return 15258703
    return null
}