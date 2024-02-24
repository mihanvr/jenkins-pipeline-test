def build(def options) {
    def env = options?.env ?: options?.script?.env
    def autoDetectUnityVersion = (options.autoDetectUnityVersion ?: env?.AUTO_DETECT_UNITY_VERSION ?: false).toBoolean()
    def unityHubPath = options.unityHubPath ?: env?.UNITY_HUB_PATH
    def projectDir = options.projectDir ?: env?.PROJECT_DIR ?: '.'
    def scenes = options.scenes
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET
    def serverMode = (options.standalone?.serverMode ?: env?.SERVER_MODE ?: false).toBoolean()
    def additionalParameters = options.additionalParameters ?: ''
    def extraScriptingDefines = options.extraScriptingDefines
    def preBuildMethod = options.preBuildMethod ?: env?.PRE_BUILD_METHOD
    def postBuildMethod = options.postBuildMethod ?: env?.POST_BUILD_METHOD
    def outputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH

    def locationPathName = getLocationPathName(options)

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

    env.OUTPUT_PATH = outputPath
    return [
            outputPath: outputPath
    ]
}

def processArtifacts(def options) {
    def env = options?.env ?: options?.script?.env
    echo "buildTag: ${options['buildTag']}"
    def buildTag = options.buildTag ?: env?.BUILD_TAG
    def outputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET
    switch (buildTarget.toLowerCase()) {
        case 'standalonewindows64':
        case 'standalonelinux64':
        case 'webgl':
            def archiveFileName = "${buildTag}.zip"
            zip zipFile: archiveFileName, dir: outputPath, overwrite: true, archive: true
        case 'android':
            archiveArtifacts artifacts: ''
        default:
            break
    }


}

@NonCPS
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
    def env = options?.env ?: options?.script?.env
    def buildOutputPath = options.buildOutputPath ?: env?.BUILD_OUTPUT_PATH
    def buildTarget = options.buildTarget ?: env?.BUILD_TARGET
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
            return "${buildOutputPath}${ext}"
        default:
            error("buildTarget ${buildTarget} not supported")
            break
    }
}