def call(def script) {
    pipeline(script)
}

def pipeline(def script) {
    try {
        notify(script: script, buildStatus: "Queued")
        defaultPipeline(script)
        script.currentBuild.result = 'SUCCESS'
        notify(script: script, buildStatus: "Success", fields: [])
    } catch (Exception e) {
        try {
            if (e instanceof InterruptedException) {
                currentBuild.result = 'ABORTED'
            } else {
                def canceled = (env?.CANCELED ?: "false") == "true"
                currentBuild.result = canceled ? 'ABORTED' : 'FAILURE'
            }
            notify(script: script, content: e.toString(), buildStatus: buildInternalStatus(currentBuild.result))
        } catch (Exception e2) {
            echo e2.toString()
        }
        throw e
    }
}

def defaultPipeline(def script) {
    checkParameters(script)

    def options = script.options
    def nodeLabel = options?.nodeLabel ?: options.env?.NODE_LABEL ?: "unity"
    node(nodeLabel) {
        env.BUILD_NODE_NAME = env.NODE_NAME
        notify(script: script, buildStatus: "Started")
        this.options = options
        stage("Checkout") {
            def clearWorkspace = (env.CLEAR_WORKSPACE_BEFORE ?: "false") == "true"
            if (fileExists('.git')) {
                def flagX = clearWorkspace ? "x" : ""
                // remove files that are ignored by Git (e.g., specified in .gitignore)
                exec label: "Clean", script: """
                    git clean -fd${flagX}
                    git submodule foreach --recursive git clean -fd${flagX}
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

        stage("Prepare Workspace") {
            prepareWorkspaceWithLibraryCache(script)
        }

        stage("Build") {
            unityBuilder.build(this)
        }

        stage("Zip") {
            unityBuilder.processArtifacts(this)
        }

        postWebhook(script)

        stage("Create Library Cache") {
            createLibraryCacheIfEnabled(script)
        }
    }
}

def checkParameters(def script) {
    def actualParameters = [
            booleanParam(name: 'WEBHOOK_ENABLED', defaultValue: true, description: 'Отправлять вебхук для CI/CD'),
            booleanParam(name: 'RESTORE_LIBRARY_CACHE', defaultValue: true, description: 'Восстанавливать кэш Library'),
            booleanParam(name: 'SAVE_LIBRARY_CACHE', defaultValue: true, description: 'Сохранять кэш Library'),
            booleanParam(name: 'CLEAR_WORKSPACE_BEFORE', defaultValue: false, description: 'Очищать рабочую папку перед сборкой'),
            booleanParam(name: 'IGNORE_OUT_OF_DATE_PARAMETERS', defaultValue: false, description: ''),
    ]

    if (script.hasProperty('additionalParameters')) {
        actualParameters.addAll(script.additionalParameters)
    }
    // Проверяем, есть ли все нужные параметры в текущем билде
    def expectedParamNames = actualParameters.collect { it.toMap().get("name") }
    def currentParamNames = []

    // Получаем текущие параметры из properties, если они есть
    def currentJob = Jenkins.instance.getItemByFullName(env.JOB_NAME)
    if (currentJob) {
        def currentProperties = currentJob.getProperty(ParametersDefinitionProperty)
        if (currentProperties) {
            currentParamNames = currentProperties.getParameterDefinitions().collect { it.name }
        }
    }

    def missingParams = expectedParamNames - currentParamNames

    def parametersIsOutOfDate = !missingParams.isEmpty()

    if (parametersIsOutOfDate) {
        echo "parameters out-of-date: ${missingParams.join(', ')}"

        // Объявляем параметры, чтобы они добавились в конфигурацию
        properties([parameters(actualParameters)])

        def ignoreOutOfDateParameters = env.IGNORE_OUT_OF_DATE_PARAMETERS ?: false
        if (!ignoreOutOfDateParameters) {
            // Помечаем сборку как отменённую
            env.CANCELED = 'true'
            currentBuild.result = 'CANCELED'
            error("Parameters updated. Restart job.")
        }
    } else {
        echo "parameters up-to-date"
    }
}

def prepareWorkspaceWithLibraryCache(def script) {
    def restoreLibraryCache = (env.RESTORE_LIBRARY_CACHE ?: "true") == "true"

    if (!restoreLibraryCache) {
        echo "Restore library cache is disabled"
        return
    }

    migrateLibraryCache(script)

    try {
        def localCachePathTarGz = getLibraryCachePath("tar.gz")
        def localCachePathZip = getLibraryCachePath("zip")
        if (fileExists(localCachePathTarGz)) {
            restoreLibraryFromCache(script, localCachePathTarGz, "tar.gz")
        } else if (fileExists(localCachePathZip)) {
            restoreLibraryFromCache(script, localCachePathZip, "zip")
        } else {
            echo "No library cache found from previous builds, starting fresh build"
        }
    } catch (Exception e) {
        echo "No previous cache found or error occurred: ${e.getMessage()}"
        echo "Starting fresh build without cache"
    }
}

def restoreLibraryFromCache(def script, def cachePath, def format) {
    echo "Found library cache from previous build: ${cachePath}"
    try {
        // Удаляем существующую папку Library, если она есть
        if (fileExists('Library')) {
            dir('Library') {
                deleteDir()
            }
        }

        if (format == "zip") {
            // Создаем директорию и распаковываем кэш
            unzip zipFile: cachePath, dir: 'Library', quiet: true
            echo "Library cache restored successfully"
        } else if (format == "tar.gz") {
            sh "tar -xzf ${cachePath} -C Library"
            echo "Library cache restored successfully"
        } else {
            echo "Unsupported cache format $format"
        }

    } catch (Exception e) {
        echo "Failed to restore library cache: ${e.getMessage()}"
        // Продолжаем выполнение, даже если восстановление кэша не удалось
    }
}

def createLibraryCacheIfEnabled(def script) {
    def saveLibraryCache = (env.SAVE_LIBRARY_CACHE ?: "true") == "true"

    if (!saveLibraryCache) {
        echo "Library cache creation is disabled"
        return
    }

    if (!fileExists('Library')) {
        echo "No Library folder found to cache"
        return
    }

    def localCachePath = getLibraryCachePath("tar.gz")

    try {
        // Архивируем папку Library
//        zip zipFile: localCachePath, dir: 'Library', overwrite: true, archive: false
        sh "tar -cf - Library 2>/dev/null | gzip -1 >$localCachePath"
        // Удаляем папку Library из workspace
        dir('Library') {
            deleteDir()
        }
        echo "Library cache created at: ${localCachePath}"
    } catch (Exception e) {
        echo "Failed to create library cache: ${e.getMessage()}"
        // Не прерываем сборку, если создание кэша не удалось
    }
}

def migrateLibraryCache(def script) {
    def env = script.env
    def jobName = env?.JOB_NAME ?: "unknown_job"
    // Очищаем имя джобы от недопустимых символов для имени файла
    def cleanJobName = jobName.replaceAll('[^a-zA-Z0-9_-]', '_')
    def newCacheLibraryPath = getLibraryCachePath("zip")
    def oldLibraryCachePath = "~library_cache_${cleanJobName}.zip"

    if (fileExists(newCacheLibraryPath)) return
    try {
        copyArtifacts(
                projectName: env.JOB_NAME,
                selector: script.lastSuccessful(),
                filter: oldLibraryCachePath,
                target: ".",
                flatten: true
        )
        if (fileExists(oldLibraryCachePath)) {
            echo "Found library cache from previous build: ${oldLibraryCachePath}"
            sh "mv ${oldLibraryCachePath} ${newCacheLibraryPath}"
        }
    } catch (Exception e) {
        echo "Failed migrate library cache: ${e.getMessage()}"
        // Не прерываем сборку, если создание кэша не удалось
    }
}

def getLibraryCachePath(def format) {
    return ".library_cache.${format}"
}

def postWebhook(def script) {
    def env = script.env
    def options = script.options

    def webhookEnabled = (env.WEBHOOK_ENABLED ?: "true") == "true"
    if (!webhookEnabled) return

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
            httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: webhookUrl, customHeaders: customHeaders, validResponseCodes: '100:599', quiet: false
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
        def artifactPath = file.relativePath
        artifacts.add([
                size: "${file.fileSize}",
                name: "${file.fileName}",
                href: "${env.BUILD_URL}artifact/${artifactPath}"
        ])
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

    echo "Current build status: " + buildStatus
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
        httpRequest contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: webhookUrl, validResponseCodes: '100:599', quiet: false
    } catch (Exception e) {
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
        return "${wholeKb} KB"
    }
    return "${sizeInBytes} B"
}

def buildInternalStatus(def buildStatus) {
    if (buildStatus == "FAILURE") return "Failed"
    if (buildStatus == "ABORTED") return "Canceled"
    return buildStatus
}

def getDiscordEmbedsColorFromStatus(def buildStatus) {
    if (buildStatus == 'Failed') return 14225172
    if (buildStatus == 'Queued') return 3506169
    if (buildStatus == 'Success') return 42837
    if (buildStatus == 'Started') return 3506169
    if (buildStatus == 'Canceled') return 15258703
    return null
}