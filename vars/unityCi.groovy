def call(def script) {
    pipeline(script)
}

def pipeline(def script) {
    try {
        notify(script: script, buildStatus: "Queued")
        defaultPipeline(script)
        notify(script: script, buildStatus: "Success", fields: [])
        script.currentBuild.result = 'SUCCESS'
        postWebhook(script)
    } catch (Exception e) {
        try {
            if (e instanceof InterruptedException) {
                script.currentBuild.result = 'CANCELED'
                notify(script: script, buildStatus: "Canceled")
            } else {
                script.currentBuild.result = 'FAILED'
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
        env.BUILD_NODE_NAME = env.NODE_NAME
        notify(script: script, buildStatus: "Started")
        this.options = options
        stage("Checkout") {
            def clearWorkspace = (env.CLEAR_WORKSPACE ?: "false") == "true"
            if (fileExists('.git')) {
                def flagX = clearWorkspace ? "x" : "" // remove files that are ignored by Git (e.g., specified in .gitignore)
                exec label: "Clean", script:"""
                    git clean -fd${flagX}
                    git submodule foreach --recursive git clean -fd
                    """
            } else {
                if (clearWorkspace) {
                    cleanWs()
                }
            }

            def scm
            if (script.scm) {
                scm = script.scm
            } else {
                def gitUrl = options?.gitUrl ?: env?.GIT_URL
                def gitCredentials = options?.gitCredentials ?: env?.GIT_CREDENTIALS
                def gitBranch = options?.gitBranch ?: env?.GIT_BRANCH
                def userRemoteConfigs = [url: gitUrl]
                if (gitCredentials) {
                    userRemoteConfigs.credentialsId = gitCredentials
                }

                // Создаем список extensions с поддержкой LFS и Submodules
                def extensions = [
                        [$class: 'GitLFSPull'], // Поддержка Git LFS
                        [$class             : 'SubmoduleOption',
                         disableSubmodules  : false, // Включаем подмодули
                         parentCredentials  : true, // Используем те же учетные данные для подмодулей
                         recursiveSubmodules: true, // Рекурсивно обновляем подмодули
                         trackingSubmodules : false] // Не отслеживаем ветки подмодулей
                ]

                scm = scmGit(
                        branches: [[name: gitBranch]],
                        extensions: extensions,
                        userRemoteConfigs: [userRemoteConfigs]
                )
            }
            def scmVars = checkout(scm)
            env.GIT_COMMIT = scmVars.GIT_COMMIT
        }

        stage("Build") {
            unityBuilder.build(this)
        }
        stage("Zip") {
            unityBuilder.processArtifacts(this)
        }
    }
}

def postWebhook(def script) {
    def env = script.env
    def options = script.options

    def webhookUrl = options?.webhookUrl ?: env?.WEBHOOK_URL

    if (/*currentBuild.result == 'SUCCESS' && */ webhookUrl) {
        stage("Post Webhook") {
            def changeLog = getChangeLogFromLatestSuccess(script)
            def artifacts = getBuildArtifacts(script)
            def buildTarget = options?.buildTarget ?: env?.BUILD_TARGET
            def gitBranch = script.scm?.arguments?.branches?.get(0)?.name ?: options?.gitBranch ?: env?.GIT_BRANCH

            def jsonBody = [
                    result               : currentBuild.result,
                    project_name         : currentBuild.projectName,
                    git_branch           : gitBranch,
                    git_commit           : env.GIT_COMMIT,
                    build_duration_millis: currentBuild.duration,
                    build_duration       : currentBuild.durationString,
                    build_id             : env.BUILD_ID,
                    build_number         : env.BUILD_NUMBER,
                    build_tag            : env.BUILD_TAG,
                    build_url            : env.BUILD_URL,
                    build_target         : buildTarget,
                    job_base_name        : env.JOB_BASE_NAME,
                    job_name             : env.JOB_NAME,
                    job_url              : env.JOB_URL,
                    node_name            : env.BUILD_NODE_NAME,
                    change_log           : changeLog,
                    artifacts            : artifacts
            ]
            def json = writeJSON returnText: true, json: jsonBody
            echo "Post body: ${json}"

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
                def message = entry.comment.replace("\n", "\\n")
                log += "* ${message} by ${entry.author}\\n"
            }
        }
    }
    return log
}

def notify(def options) {
    discordNotify(options)
}

def discordNotify(def params) {
    def script = params.script
    def notifyStages = script?.options?.notifyStages
    def env = script?.env
    def buildStatus = params.buildStatus
    def options = script?.options
    def webhookUrl = options?.discordWebhookUrl ?: env?.DISCORD_WEBHOOK_URL

    if (notifyStages == null || !notifyStages.contains(buildStatus)) return

    if (!webhookUrl) return
    if (notifyStages == null || !notifyStages.contains(buildStatus)) return

    def buildUrl = env?.BUILD_URL
    def jobUrl = env?.JOB_URL
    def jobName = env?.JOB_NAME
    def buildPlatform = options?.buildTarget ?: env?.BUILD_TARGET
    def content = params.content
    def embedsColor = params.color ?: getDiscordEmbedsColorFromStatus(buildStatus)

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

    String buildCause = script.currentBuild?.buildCauses?.collect { it.shortDescription }?.find { it != null }
    if (buildCause != null) {
        if (buildCause.toLowerCase().startsWith("started by ")) {
            buildCause = buildCause.substring("started by ".length())
        }
    }

    if (buildCause) {
        fields.add([name: "Started By", value: buildCause, inline: true])
    }

    def gitBranch = script.scm?.arguments?.branches?.get(0)?.name ?: options?.gitBranch ?: env?.GIT_BRANCH
    if (gitBranch) {
        fields.add([name: "Branch", value: gitBranch, inline: true])
    }
    def gitCommit = env?.GIT_COMMIT
    if (gitCommit) {
        def shortCommitHash = gitCommit.substring(0, 7)
        fields.add([name: "Commit", value: shortCommitHash, inline: true])
    }
    def buildNodeName = env?.BUILD_NODE_NAME
    if (buildNodeName) {
        fields.add([name: "Node", value: buildNodeName, inline: true])
    }

    def artifacts = getBuildArtifacts(script)
    if (artifacts.size() > 0) {
        def art0 = artifacts[0]
        fields.add([name: "Download", value: "[${art0.name}](${art0.href})", inline: true])
        if (art0.size) {
            fields.add([name: "Build Size", value: "${toPrettySize(art0.size.toString().toInteger())}", inline: true])
        }
    }

    def json = writeJSON(json: discordContent, returnText: true)
    echo json
    try {
        httpRequest contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: webhookUrl, validResponseCodes: '100:599'
    } catch (Exception e) {
        echo e.class.canonicalName
        if (e.message?.contains("timed out")) {
            log.error(e.message)
        } else {
            throw e
        }
    }

}

def toPrettySize(def sizeInBytes) {
    def KB = 1024
    def MB = 1024 * KB

    def wholeMb = (int) (sizeInBytes / MB)
    if (wholeMb > 0) {
        return "${wholeMb} MB"
    }
    def wholeKb = (int) (sizeInBytes / KB)
    if (wholeKb > 0) {
        return "${wholeMb} KB"
    }
    return "${sizeInBytes} B"
}

def getDiscordEmbedsColorFromStatus(def buildStatus) {
    if (buildStatus == 'Failed') return 14225172
    if (buildStatus == 'Queued') return 3506169
    if (buildStatus == 'Success') return 42837
    if (buildStatus == 'Started') return 3506169
    if (buildStatus == 'Canceled') return 15258703
    return null
}