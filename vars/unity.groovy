class UnityConfiguration implements Serializable {
    static String unityPath = ''
}

def init(String unityPath) {
    UnityConfiguration.unityPath = getExePath(unityPath)
}

def getExePath(String unityPath) {
    if (unityPath.endsWith(".app")) {
        return unityPath + "/Contents/MacOS/Unity"
    }
    return unityPath
}

def execute(
        def args,
        String projectDir = '',
        String methodToExecute = null,
        String buildTarget = null,
        String logFile = null,
        Boolean noGraphics = true,
        String additionalParameters = null,
        Boolean outputLogOnFailure = true
) {
    projectDir = args.projectDir ?: projectDir
    methodToExecute = args.methodToExecute ?: methodToExecute
    buildTarget = args.buildTarget ?: buildTarget
    logFile = args.logFile ?: logFile
    noGraphics = args.noGraphics != null ? args.noGraphics.toBoolean() : noGraphics
    additionalParameters = args.additionalParameters ?: additionalParameters
    outputLogOnFailure = args.outputLogOnFailure != null ? args.outputLogOnFailure.toBoolean() : outputLogOnFailure

    ensureUnityExecutableExists(UnityConfiguration.unityPath)
    ensureProjectDirectoryExists(projectDir);

    projectDir = projectDir.replace('\\', '/');

    if (!logFile) {
        logFile = '-';
    }

    def buildTargetStr = '';
    if (buildTarget) {
        buildTargetStr = "-buildTarget ${buildTarget}";
    }

    dir('Assets/Editor') {
        def request = libraryResource 'JenkinsBuilder.cs'
        writeFile file: 'JenkinsBuilder.cs', text: request
    }

    def unityParams = "\"${UnityConfiguration.unityPath}\" -batchmode -skipMissingProjectID -skipMissingUPID -projectPath \"${projectDir}\" ${noGraphics ? '-nographics' : ''} ${methodToExecute ? "-executeMethod ${methodToExecute}" : ''} ${buildTargetStr} ${additionalParameters} -logFile \"${logFile}\" -quit"
    int exitCode
    if (isUnix()) {
        exitCode = sh label: 'Execute Unity Method', returnStatus: true, script: unityParams
    } else {
        exitCode = bat label: 'Execute Unity Method', returnStatus: true, script: "CALL ${unityParams}"
    }

    if (exitCode != 0) {
        if (outputLogOnFailure && logFile != '-') {
            logContent = readFile logFile
            log(logContent)
        }

        error('Unity method exited with a non-zero exit code!');
    }
}

def runTests(String projectDir, String testPlatform = '', List<String> testFilters = [], List<String> testCategories = [], String testSettingsFile = '', String testResultFile = '', Boolean noGraphics = true) {
    ensureUnityDirectoryExists(UnityConfiguration.engineRootDirectory);
    ensureProjectDirectoryExists(projectDir);

    projectDir = projectDir.replace('\\', '/');

    def argumentString = "-batchmode -projectPath \"${projectDir}\" ${noGraphics ? '-nographics' : ''} -runTests -silent-crashes";

    if (testPlatformIsValid(testPlatform)) {
        argumentString += " -testPlatform ${testPlatform}"
    }

    if (testFilters.size() > 0) {
        argumentString += ' -testFilter ';
        for (filter in testFilters) {
            argumentString += "${filter};";
        }
        // Remove trailing semicolon
        argumentString = "${argumentString.substring(0, argumentString.length() - 1)}";
    }

    def categoryString = '';
    if (testCategories.size() > 0) {
        argumentString += ' -testCategory ';
        for (category in testCategories) {
            argumentString += "${category};";
        }
        // Remove trailing semicolon
        argumentString = "${argumentString.substring(0, argumentString.length() - 1)}";
    }

    if (!testResultFile) {
        testResultFile = "${env.WORKSPACE}/logs/UnityTestLog-${env.BUILD_NUMBER}.xml";
    }

    argumentString += " -testResults \"${testResultFile}\"";

    def exitCode = bat label: 'Run Tests', returnStatus: true, script: "CALL \"${UnityConfiguration.engineRootDirectory}/Editor/Unity.exe\" ${argumentString}"

    if (exitCode != 0) {
        unstable 'Some tests did not pass!'
    }
}

private def testPlatformIsValid(String platform) {
    if (!platform) {
        return false;
    }

    def possiblePlatforms = ['EditMode', 'PlayMode', 'StandaloneWindows', 'StandaloneWindows64', 'StandaloneLinux64', 'StandaloneOSX', 'iOS', 'Android', 'PS4', 'XboxOne'];
    platform = platform.toLowerCase();

    for (possiblePlat in possiblePlatforms) {
        if (platform == possiblePlat.toLowerCase()) {
            return true;
        }
    }

    log.error("Invalid test platform '${platform}' specified. Valid platforms: ${possiblePlatforms.join(', ')}.")
    return false
}

private def ensureUnityExecutableExists(String unityPath) {
    if (!fileExists(unityPath)) {
        error("Unity executable not found at specified path! (${unityPath})");
    }
}

private def ensureProjectDirectoryExists(String projectDirectory) {
    if (projectDirectory == '' || !fileExists(projectDirectory)) {
        error("Project directory does not exist! (${projectDirectory})")
    }
}