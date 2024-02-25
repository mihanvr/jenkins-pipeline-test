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

    if (/*currentBuild.result == 'SUCCESS' && */webhookUrl) {
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