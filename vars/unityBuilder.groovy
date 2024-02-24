def build(def options) {
    def autoDetectUnityVersion = (options.autoDetectUnityVersion ?: false).toBoolean()
    def unityHubPath = options.unityHubPath
    def projectDir = options.projectDir
    def scenes = options.scenes
    def buildTarget = options.buildTarget
    def serverMode = (options.standalone?.serverMode ?: false).toBoolean()
    def additionalParameters = options.additionalParameters ?: ''
    def extraScriptingDefines = options.extraScriptingDefines
    def preBuildMethod = options.preBuildMethod
    def postBuildMethod = options.postBuildMethod

    def outputPath = options.buildOutputPath
    def locationPathName = getLocationPathName(options)

    String unityVersion
    String unityRevision

    if (autoDetectUnityVersion) {
        (unityVersion, unityRevision) = getProjectUnityVersionAndRevision(projectDir)
        log.info("required unityVersion: ${unityVersion} (${unityRevision})")
    } else {
        unityVersion = options.unityVersion
        unityRevision = options.unityRevision
    }

    unityHub.init(unityHubPath)
    def unityPath = unityHub.getUnityPath(unityVersion, unityRevision, false)
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
    if (options.android){
        buildOptions.android = options.android
    }
    if (options.webgl){
        buildOptions.webgl = options.webgl
    }

    dir('Assets/Editor') {
        writeFile file: 'JenkinsBuilder.cs', text: libraryResource('JenkinsBuilder.cs')
    }
    writeJSON file: 'ci_build_options.json', json: buildOptions
    echo 'ci_build_options.json'
    echo writeJSON(json: buildOptions, returnText: true)

    additionalParameters += ' -ciOptionsFile ci_build_options.json'
    unity.execute(projectDir: projectDir, methodToExecute: 'JenkinsBuilder.Build', buildTarget: buildTarget, noGraphics: serverMode, additionalParameters: additionalParameters)

    return [
            outputPath: outputPath
    ]
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

def getLocationPathName(def options) {
    def buildTarget = options.buildTarget
    def buildOutputPath = options.buildOutputPath
    String locationPathName
    switch (buildTarget.toLowerCase()) {
        case 'standalonewindows64':
        case 'standalonelinux64':
            def executableName = options.executableName ?: 'app'
            def ext = buildTarget == 'StandaloneWindows64' ? '.exe' : ''
            return "${buildOutputPath}/${executableName}${ext}"
        case 'webgl':
            return buildOutputPath
        case 'android':
            def ext = options.buildAppBundle ? '.aab' : '.apk'
            locationPathName = "${buildOutputPath}${ext}"
            return locationPathName
        default:
            error("buildTarget ${buildTarget} not supported")
            break
    }
}