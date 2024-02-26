class UnityHubConfiguration implements Serializable {
    static String unityHubPath = ''
}

availableModules = [
        'Android Build Support'                   : 'android',
        'Android SDK & NDK Tools'                 : 'android-sdk-ndk-tools',
        'OpenJDK'                                 : 'android-open-jdk',
        'iOS Build Support'                       : 'ios',
        'tvOS Build Support'                      : 'appletv',
        'Linux Build Support'                     : 'linux',
        'Linux Build Support (Mono)'              : 'linux-mono',
        'Linux Build Support (IL2CPP)'            : 'linux-il2cpp',
        'Mac Build Support (Mono)'                : 'mac-mono',
        'Windows Build Support (IL2CPP)'          : 'windows-il2cpp',
        'Universal Windows Platform Build Support': 'universal-windows-platform',
        'UWP Build Support (IL2CPP)'              : 'uwp-il2cpp',
        'UWP Build Support (.NET)'                : 'uwp-.net',
        'WebGL Build Support'                     : 'webgl',
        'Lumin OS (Magic Leap) Build Support'     : 'lumin',
        'Facebook Gameroom'                       : 'facebookgameroom',
        'Facebook Gameroom Build Support'         : 'facebook-games',
        'Vuforia Augmented Reality Support'       : 'vuforia-ar',
]

def setupJobParameters() {
    def params = []
    for (final def keyValue in availableModules) {
        params.add(booleanParam(name: '', defaultValue: false, description: keyValue.key))
    }
    properties([parameters(params)])
}

def init(String unityHubPath) {
    ensureUnityHubExecutableExists(unityHubPath)
    UnityHubConfiguration.unityHubPath = getExePath(unityHubPath)
}

def getAvailableEditors() {
    def v = exec label: 'Get available unity editors', returnStdout: true, script: "\"${UnityHubConfiguration.unityHubPath}\" -- --headless editors -i"
    return v.split('\n')
}

def getExePath(String unityHubPath) {
    if (unityHubPath.endsWith(".app")) {
        return unityHubPath + "/Contents/MacOS/Unity Hub"
    }
    return unityHubPath
}

def getLatestUnityRevision(String editorVersion) {
    def versionWithoutF = editorVersion[-2] == 'f' ? editorVersion.substring(0, editorVersion.size() - 2) : editorVersion
    def response = httpRequest "https://unity.com/releases/editor/whats-new/${versionWithoutF}"
    String content = response.content
    return (content =~ /<div>Changeset:<\/div>\s+<div>(\w+)</).findAll()[0][1]
}

def getInstalledEditorPath(String editorVersion) {
    def availableEditorList = getAvailableEditors()
    for (final String line in availableEditorList) {
        if (!line) continue

        final def installedDelimiter = "installed at "
        def indexOfInstalled = line.indexOf(installedDelimiter)
        if (indexOfInstalled == -1) continue
        if (!line.startsWith(editorVersion)) continue
        return line.substring(indexOfInstalled + installedDelimiter.size())
    }
    return ''
}


def getUnityPath(String editorVersion, String editorVersionRevision = '', boolean autoInstallEditor = true) {
    if (!editorVersion) {
        log.error('unity version required, but not defined')
    }
    def editorVersionPath = getInstalledEditorPath(editorVersion)
    if (editorVersionPath) {
        log.info("required version unity ${editorVersion} found at path '${editorVersionPath}'")
        return editorVersionPath
    }
    if (!autoInstallEditor) {
        log.error("required version of unity editor not installed: ${editorVersion}")
    }
    log.info("need install unity ${editorVersion}")
    if (!editorVersionRevision) {
        log.info("try find latest revision of ${editorVersion}")
        editorVersionRevision = getLatestUnityRevision(editorVersion)
    }
    exec label: 'Install Unity Editor', script: "\"${UnityHubConfiguration.unityHubPath}\" -- --headless install --version ${editorVersion} --changeset ${editorVersionRevision}"
    editorVersionPath = getInstalledEditorPath(editorVersion)
    if (!editorVersionPath) {
        log.error("required version on unity should have been installed, but not found over unity hub cli")
    }
    return editorVersionPath
}

def installUnityModules(String editorVersion, List<String> modules) {
    if (modules.size() == 0) return
    exec label: 'Install required editor modules', script: "\"${UnityHubConfiguration.unityHubPath}\" -- --headless install-modules --version ${editorVersion} -m ${String.join(' ', modules)} --cm"
}

private def ensureUnityHubExecutableExists(String unityHubPath) {
    if (!fileExists(unityHubPath)) {
        error("Unity Hub executable not found at specified path! (${unityHubPath})");
    }
}