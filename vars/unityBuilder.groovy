def build(def script) {
    def options = script.options
    def env = script?.env
    def autoDetectUnityVersion = (options.autoDetectUnityVersion ?: env?.AUTO_DETECT_UNITY_VERSION ?: true).toBoolean()
    def unityHubPath = getUnityHubPath(script)
    def projectDir = options.projectDir ?: env?.PROJECT_DIR ?: '.'
    def scenes = options.scenes
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET
    def serverMode = (options.standalone?.serverMode ?: env?.SERVER_MODE ?: false).toBoolean()
    def additionalParameters = options.additionalParameters ?: ''
    def extraScriptingDefines = options.extraScriptingDefines
    def preBuildMethod = options.preBuildMethod ?: env?.PRE_BUILD_METHOD
    def postBuildMethod = options.postBuildMethod ?: env?.POST_BUILD_METHOD
    def outputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH

    extraScriptingDefines = (extraScriptingDefines ?: []) + ["CI_BUILD"]

    def locationPathName = getLocationPathName(script)

    String unityVersion
    String unityRevision

    if (autoDetectUnityVersion) {
        (unityVersion, unityRevision) = getProjectUnityVersionAndRevision(projectDir)
        log.info("required unityVersion: ${unityVersion} (${unityRevision})")
    } else {
        unityVersion = options.unityVersion ?: env?.UNITY_VERSION
        unityRevision = options.unityRevision ?: env?.UNITY_REVISION
    }

    unityHub.init(unityHubPath)
    def unityPath = unityHub.getUnityPath(unityVersion, unityRevision, true)
    unityHub.installUnityModules(unityVersion, getRequiredUnityModules(buildTarget))
    unity.init(unityPath)

    def buildOptions = [:]

    buildOptions.locationPathName = locationPathName
    buildOptions.buildTarget = buildTarget

    if (scenes) {
        buildOptions.scenes = scenes
    }
    if (extraScriptingDefines) {
        buildOptions.extraScriptingDefines = extraScriptingDefines
    }
    if (preBuildMethod) {
        buildOptions.preBuildMethod = preBuildMethod
    }
    if (postBuildMethod) {
        buildOptions.postBuildMethod = postBuildMethod
    }
    if (serverMode) {
        buildOptions.enableHeadlessMode = true
        buildOptions.buildSubTarget = 'Server'
    }
    if (options.android) {
        buildOptions.android = options.android
    }
    if (options.webgl) {
        buildOptions.webgl = options.webgl
    }

    dir('Assets/Editor') {
        writeFile file: 'JenkinsBuilder.cs', text: libraryResource('JenkinsBuilder.cs')
    }
    writeJSON file: 'ci_build_options.json', json: buildOptions
    echo 'ci_build_options.json'
    echo writeJSON(json: buildOptions, returnText: true)

    def ciEnv = [
            "BUILD_NUMBER": env?.BUILD_NUMBER,
            "JOB_NAME"    : env?.JOB_NAME,
            "BUILD_TAG"   : env?.BUILD_TAG
    ]
            .findAll { it.value != null }
            .collect { "${it.key}=${it.value}" }
    writeFile file: '.ci.env', text: String.join("\n", ciEnv)

    if (extraScriptingDefines) {
        def cscRspContent = extraScriptingDefines.collect {"-define:${it}"}
        writeFile file: "${projectDir}/Assets/csc.rsp", text: String.join("\n", cscRspContent)
    }

    additionalParameters += ' -ciOptionsFile ci_build_options.json'
    unity.execute(projectDir: projectDir, methodToExecute: 'JenkinsBuilder.Build', buildTarget: buildTarget, noGraphics: serverMode, additionalParameters: additionalParameters)

    env.OUTPUT_PATH = outputPath
    return [
            outputPath: outputPath
    ]
}

def getRequiredUnityModules(String buildTarget) {
    switch (buildTarget?.toLowerCase()) {
        case "standalonewindows64":
            return ['windows-mono']
        case "webgl":
            return ['webgl']
        case "android":
            return ['android']
        case "ios":
            return ['ios']
    }
    return []
}

def getUnityHubPath(def script) {
    def path = script.options?.unityHubPath ?: script.env?.UNITY_HUB_PATH
    if (path) {
        if (fileExists(path)) return path
        error("Unity hub not found at defined path: ${path}")
    }

    if (isUnix()) {
        def uname = sh script: 'uname', returnStdout: true
        if (uname.startsWith("Darwin")) {
            path = '/Applications/Unity Hub.app'
        }
    } else {
        path = 'C:\\Program Files\\Unity Hub\\Unity Hub.exe'
    }
    if (fileExists(path)) return path
    error("Unity hub not found")
}

def processArtifacts(def script) {
    def options = script.options
    def env = script?.env
    def buildTag = options.buildTag ?: env?.BUILD_TAG
    def outputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET
    switch (buildTarget.toLowerCase()) {
        case 'standalonewindows64':
        case 'standalonelinux64':
        case 'webgl':
            def archiveFileName = "${buildTag}.zip"
            zip zipFile: archiveFileName, dir: outputPath, overwrite: true, archive: true
            break
        case 'android':
            def filePath = getLocationPathName(options)
            archiveArtifacts artifacts: filePath
            break
        default:
            break
    }


}

def getProjectUnityVersionAndRevision(String projectDir) {
    final def expectedLineStart = 'm_EditorVersionWithRevision: '
    def projectVersionPath = "${projectDir}/ProjectSettings/ProjectVersion.txt"
    if (fileExists(projectVersionPath)) {
        String text = readFile(projectVersionPath)
        for (final def line in text.readLines()) {
            if (line.startsWith(expectedLineStart)) {
                def (unityVersion, unityRevision) = line.substring(expectedLineStart.size()).split(' ')
                return [unityVersion, unityRevision.substring(1, unityRevision.size() - 2)]
            }
        }
    }
    return ['', '']
}

def getLocationPathName(def script) {
    def options = script.options
    def env = script?.env
    def buildOutputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET

    switch (buildTarget.toLowerCase()) {
        case 'standalonewindows64':
        case 'standalonelinux64':
            def executableName = options.executableName ?: env?.EXECUTABLE_NAME ?: 'app'
            def ext = buildTarget == 'StandaloneWindows64' ? '.exe' : ''
            return "${buildOutputPath}/${executableName}${ext}"
        case 'webgl':
            return buildOutputPath
        case 'android':
            def ext = options.buildAppBundle ? '.aab' : '.apk'
            def buildTag = options.buildTag ?: env?.BUILD_TAG
            return "${buildOutputPath}/${buildTag}${ext}"
        default:
            error("buildTarget ${buildTarget} not supported")
            break
    }
}