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
    def setupParameters = (env.SETUP_PARAMETERS ?: "true") == "true"
    if (!setupParameters) {
        echo "skip setup parameters"
        rememberParameterValues(script)
        return
    }

    def actualParameters = [
            booleanParam(name: 'WEBHOOK_ENABLED', defaultValue: true, description: 'Отправлять вебхук для CI/CD'),
            booleanParam(name: 'RESTORE_LIBRARY_CACHE', defaultValue: true, description: 'Восстанавливать кэш Library'),
            booleanParam(name: 'SAVE_LIBRARY_CACHE', defaultValue: true, description: 'Сохранять кэш Library'),
            booleanParam(name: 'CLEAR_WORKSPACE_BEFORE', defaultValue: false, description: 'Очищать рабочую папку перед сборкой'),
            booleanParam(name: 'SETUP_PARAMETERS', defaultValue: true, description: 'Установить параметры в настройках задачи'),
            booleanParam(name: 'SETUP_PARAMETERS_ONLY', defaultValue: false, description: 'Отменить сборку после установки параметров'),
    ]

    if (script.hasProperty('additionalParameters') && script.additionalParameters) {
        // Параметр скрипта с библиотечным именем заменяет библиотечный: так Jenkinsfile задаёт своё
        // умолчание (например, выключенный кэш Library), и оно переживает перерегистрацию параметров
        // при появлении нового.
        def overridden = script.additionalParameters.collect { it.toMap().get("name") }
        actualParameters = actualParameters.findAll { !(it.toMap().get("name") in overridden) }
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
    } else {
        echo "parameters up-to-date"
    }

    rememberParameterValues(script)

    def setupParametersOnly = (env.SETUP_PARAMETERS_ONLY ?: "false") == "true"
    if (setupParametersOnly) {
        // Помечаем сборку как отменённую
        env.CANCELED = 'true'
        currentBuild.result = 'CANCELED'
        error("Parameters updated")
    }
}

// Значения параметров из options.rememberedParameters, выбранные в этой сборке, становятся умолчаниями
// задачи, и форма следующего запуска открывается с прошлым выбором. Какие параметры запоминать, решает
// Jenkinsfile: разовые действия (очистка workspace, кэш Library) туда не кладут, иначе одна сборка
// с галкой превратит её в режим для всех следующих. Вызывается и при SETUP_PARAMETERS=false: тот
// управляет набором параметров, а здесь меняются только умолчания уже заведённых.
def rememberParameterValues(def script) {
    def names = script.options?.rememberedParameters
    if (!names) return
    def remembered = rememberParameterDefaults(env.JOB_NAME, currentBuild.number, names.collect { it.toString() })
    if (remembered) {
        echo "remembered parameter values: ${remembered.join(', ')}"
    }
}

// Переписывает умолчания задачи значениями сборки buildNumber для параметров из names и возвращает
// имена изменившихся. Параметр, чей тип не умеет copyWithDefaultValue, остаётся как был. Заменяется
// только свойство параметров, остальные свойства задачи не трогаются.
@NonCPS
def rememberParameterDefaults(String jobName, int buildNumber, List<String> names) {
    def job = Jenkins.instance.getItemByFullName(jobName)
    def property = job?.getProperty(ParametersDefinitionProperty)
    def values = job?.getBuildByNumber(buildNumber)?.getAction(ParametersAction)
    if (property == null || values == null) return []

    def remembered = []
    def definitions = property.parameterDefinitions.collect { definition ->
        def value = definition.name in names ? values.getParameter(definition.name) : null
        if (value == null || value.value == definition.defaultParameterValue?.value) return definition
        def copy = definition.copyWithDefaultValue(value)
        if (copy.is(definition)) return definition
        remembered << definition.name
        return copy
    }
    if (remembered) {
        def change = new hudson.BulkChange(job)
        try {
            job.removeProperty(ParametersDefinitionProperty)
            job.addProperty(new ParametersDefinitionProperty(definitions))
            change.commit()
        } finally {
            change.abort()
        }
    }
    return remembered
}

def prepareWorkspaceWithLibraryCache(def script) {
    def restoreLibraryCache = (env.RESTORE_LIBRARY_CACHE ?: "true") == "true"

    if (!restoreLibraryCache) {
        echo "Restore library cache is disabled"
        return
    }

    migrateLibraryCache(script)

    try {
        def localCachePathZip = getLibraryCachePath("zip")
        def libraryCacheFormat = getCacheFormat(script)
        if (libraryCacheFormat == "zip" && fileExists(localCachePathZip)) {
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
        } else {
            echo "Unsupported cache format $format"
        }

    } catch (Exception e) {
        echo "Failed to restore library cache: ${e.getMessage()}"
        // Продолжаем выполнение, даже если восстановление кэша не удалось
    }
}

def getCacheFormat(def script) {
    return "zip"
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

    def libraryCacheFormat = getCacheFormat(script)
    def localCachePath = getLibraryCachePath(libraryCacheFormat)

    echo "cache format: ${libraryCacheFormat}"

    try {
        if (libraryCacheFormat == "zip") {
            zip zipFile: localCachePath, dir: 'Library', overwrite: true, archive: false
        } else {
            throw new Exception("format ${libraryCacheFormat} not supported for Library cache")
        }
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

// script.scm приходит в двух несовместимых формах. У job'а типа Pipeline script from SCM
// это hudson.plugins.git.GitSCM, у которого свойства arguments нет вовсе: обращение к нему
// бросает MissingPropertyException, а не возвращает null, поэтому безопасной навигации мало.
// Когда SCM собирает сама библиотека через scmGit, свойство есть и его надо прочитать.
//
// Проверкой hasProperty('arguments') это не решается: на Map с живым ключом arguments она
// возвращает null, и рабочий случай молча уехал бы в фолбэк.
def getGitBranch(def script) {
    def branch = null
    try {
        branch = script?.scm?.arguments?.branches?.get(0)?.name
    } catch (Exception ignored) {
    }
    return branch ?: script?.options?.gitBranch ?: script?.env?.GIT_BRANCH
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
            def gitBranch = getGitBranch(script)

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

    def gitBranch = getGitBranch(script)
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