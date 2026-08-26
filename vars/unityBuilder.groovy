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
    if (options.webgl) {
        buildOptions.webgl = options.webgl
    }
    if (options.hideUnityLogo != null) {
        buildOptions.hideUnityLogo = options.hideUnityLogo.toString().toBoolean()
    }

    def buildNumber = options.buildNumber ?: env?.BUILD_NUMBER
    if (buildNumber) {
        buildOptions.buildNumber = buildNumber as int
    }
    if (options.version) {
        buildOptions.version = options.version
    }

    dir('Assets/Editor') {
        writeFile file: 'JenkinsBuilder.cs', text: libraryResource('JenkinsBuilder.cs')
    }
    def ciEnv = [
            "BUILD_NUMBER": env?.BUILD_NUMBER,
            "JOB_NAME"    : env?.JOB_NAME,
            "BUILD_TAG"   : env?.BUILD_TAG
    ]
            .findAll { it.value != null }
            .collect { "${it.key}=${it.value}" }
    writeFile file: '.ci.env', text: String.join("\n", ciEnv)

    if (extraScriptingDefines) {
        def cscRspContent = extraScriptingDefines.collect { "-define:${it}" }
        writeFile file: "${projectDir}/Assets/csc.rsp", text: String.join("\n", cscRspContent)
    }

    additionalParameters += ' -ciOptionsFile ci_build_options.json'

    withAndroidCredentials(options.android) { androidOptions, secretsFromCredentials ->
        if (androidOptions) {
            buildOptions.android = androidOptions
        }

        writeJSON file: 'ci_build_options.json', json: buildOptions
        echo 'ci_build_options.json'
        echo writeJSON(json: maskSecrets(buildOptions), returnText: true)

        unity.execute(projectDir: projectDir, methodToExecute: 'JenkinsBuilder.Build', buildTarget: buildTarget, noGraphics: serverMode, additionalParameters: additionalParameters)

        if (secretsFromCredentials) {
            // the options file holds the resolved passwords, so it must not outlive the build
            file.deleteFile('ci_build_options.json')
        }
    }

    env.OUTPUT_PATH = outputPath
    return [
            outputPath: outputPath
    ]
}

// Android secrets can come as plain values (as before) or as Jenkins credential ids.
// Credentials win when both are given, and a file credential lives only inside the block,
// which is why the caller runs the whole build inside it.
def withAndroidCredentials(def androidOptions, Closure body) {
    def bindings = []
    if (androidOptions?.keystoreCredentialsId) {
        bindings.add([$class: 'FileBinding', credentialsId: androidOptions.keystoreCredentialsId, variable: 'CI_ANDROID_KEYSTORE_FILE'])
    }
    if (androidOptions?.keystorePassCredentialsId) {
        bindings.add([$class: 'StringBinding', credentialsId: androidOptions.keystorePassCredentialsId, variable: 'CI_ANDROID_KEYSTORE_PASS'])
    }
    if (androidOptions?.keyaliasPassCredentialsId) {
        bindings.add([$class: 'StringBinding', credentialsId: androidOptions.keyaliasPassCredentialsId, variable: 'CI_ANDROID_KEYALIAS_PASS'])
    }

    if (bindings.isEmpty()) {
        body.call(resolveAndroidOptions(androidOptions, null, null, null), false)
        return
    }

    withCredentials(bindings) {
        body.call(resolveAndroidOptions(
                androidOptions,
                env.CI_ANDROID_KEYSTORE_FILE,
                env.CI_ANDROID_KEYSTORE_PASS,
                env.CI_ANDROID_KEYALIAS_PASS), true)
    }
}

def resolveAndroidOptions(def androidOptions, def keystoreFile, def keystorePass, def keyaliasPass) {
    if (androidOptions == null) return null
    def resolved = new LinkedHashMap(androidOptions)
    // credential ids describe where the secrets live; Unity has no use for them
    resolved.remove('keystoreCredentialsId')
    resolved.remove('keystorePassCredentialsId')
    resolved.remove('keyaliasPassCredentialsId')
    if (keystoreFile) resolved.keystoreName = keystoreFile
    if (keystorePass) resolved.keystorePass = keystorePass
    if (keyaliasPass) resolved.keyaliasPass = keyaliasPass
    return resolved
}

def maskSecrets(def buildOptions) {
    def masked = new LinkedHashMap(buildOptions)
    def android = masked.android
    if (android != null) {
        def androidCopy = new LinkedHashMap(android)
        if (androidCopy.keystorePass) androidCopy.keystorePass = '***'
        if (androidCopy.keyaliasPass) androidCopy.keyaliasPass = '***'
        masked.android = androidCopy
    }
    return masked
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
        case "standalonelinux64":
            return ["linux-mono"]
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
    def keepArtifacts = options.keepArtifacts ?: env?.KEEP_ARTIFACTS ?: false
    def keepBuildDir = options.keepBuildDir ?: env?.KEEP_BUILD_DIR ?: false
    switch (buildTarget.toLowerCase()) {
        case 'standalonewindows64':
        case 'standalonelinux64':
        case 'webgl':
        case 'ios':
            def archiveFileName = "${buildTag}.zip"
            log.info("create artifact ${archiveFileName} from directory ${outputPath}")
            zip zipFile: archiveFileName, dir: outputPath, overwrite: true, archive: false, exclude: '*_DoNotShip/**, *_ButDontShipItWithYourGame/**'
            archiveArtifacts artifacts: archiveFileName  // Явно архивируем
            if (!keepArtifacts) {
                log.info("delete uploaded artifact ${outputPath}")
                if (!file.deleteFile(archiveFileName)) {
                    log.warn("delete failed: ${archiveFileName}")
                }
            }
            break
        case 'android':
            def filePath = getLocationPathName(script)
            archiveArtifacts artifacts: filePath
            if (!keepArtifacts) {
                log.info("delete uploaded artifact ${filePath}")
                if (!file.deleteFile(filePath)) {
                    log.warn("delete failed: ${filePath}")
                }
            }
            break
        default:
            break
    }

    if (!keepBuildDir && outputPath?.trim()) {
        log.info("delete build dir ${outputPath}")
        dir(outputPath) {
            deleteDir()
        }
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
            def buildAppBundle = options.android?.buildAppBundle ?: options.buildAppBundle
            def ext = buildAppBundle ? '.aab' : '.apk'
            def buildTag = options.buildTag ?: env?.BUILD_TAG
            return "${buildOutputPath}/${buildTag}${ext}"
        case 'ios':
            return buildOutputPath
        default:
            error("buildTarget ${buildTarget} not supported")
            break
    }
}