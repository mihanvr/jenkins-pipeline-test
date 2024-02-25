def post(options) {
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

    if (currentBuild.result == 'SUCCESS' && webhookUrl) {
        def customHeaders = []

        def xApiKey = options.xApiKey ?: env?.X_API_KEY
        if (xApiKey) {
            customHeaders.add([name: 'X-API-KEY', value: xApiKey])
        } else {
            def webhookCredentials = options.webhookCredentials ?: env?.WEBHOOK_CREDENTIALS
            if (webhookCredentials) {
                echo webhookCredentials
                withCredentials([string(credentialsId: webhookCredentials, variable: 'xApiKeyCred')]) {
                    customHeaders.add([name: 'X-API-KEY', value: "${xApiKeyCred}"])
                }
            }
        }
        httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: webhookUrl, customHeaders: customHeaders, validResponseCodes: '100:599'
    }
}

@NonCPS
def getBuildArtifacts(options) {
    def currentBuild = options.currentBuild ?: options.script?.currentBuild

    def buildArtifacts = currentBuild.rawBuild.artifacts
    def artifacts = []
    def env = options.env ?: options.script?.env
    for (int i = 0; i < buildArtifacts.size(); i++) {
        file = buildArtifacts[i]
        artifacts.add([size: "${file.fileSize}", name: "${file.fileName}", href: "${env.BUILD_URL}artifact/${file.fileName}"])
    }
//    return artifacts
    return []
}

@NonCPS
def getChangeLogFromLatestSuccess(options) {
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
def getChangeLog(passedBuilds) {
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