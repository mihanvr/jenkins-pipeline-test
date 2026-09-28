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

// Каталог модуля внутри редактора: по нему видно, стоит ли модуль. На Windows unityPath указывает
// на ...\Editor\Unity.exe, на macOS на .../<версия>/Unity.app. Таблица внутри функции, а не
// присваиванием в теле файла: Jenkins не выполняет верхнеуровневый код vars/*.groovy, и такая
// переменная в шаге не существует (MissingPropertyException).
def getPlaybackEnginePath(String unityPath, String module) {
    def engine = [
            'android': 'AndroidPlayer',
            'webgl'  : 'WebGLSupport',
            'ios'    : 'iOSSupport',
    ][module]
    if (!engine || !unityPath) return null
    if (unityPath.endsWith('.app')) {
        return unityPath.substring(0, unityPath.lastIndexOf('/')) + '/PlaybackEngines/' + engine
    }
    def editorDir = unityPath.substring(0, Math.max(unityPath.lastIndexOf('\\'), unityPath.lastIndexOf('/')))
    return editorDir + '\\Data\\PlaybackEngines\\' + engine
}

// Код выхода Hub не показатель: на уже стоящем модуле он тоже бывает ненулевым, поэтому установка
// проверяется по каталогу модуля. Без проверки недоустановленный модуль всплывал бы посреди сборки
// невнятной ошибкой Unity, а не здесь с его именем. Модули без известного каталога не проверяются.
def installUnityModules(String editorVersion, List<String> modules, String unityPath = null) {
    if (modules.size() == 0) return
    def status = exec label: 'Install required editor modules', returnStatus: true, script: "\"${UnityHubConfiguration.unityHubPath}\" -- --headless install-modules --version ${editorVersion} -m ${String.join(' ', modules)} --cm"
    def missing = modules.findAll { module ->
        def path = getPlaybackEnginePath(unityPath, module)
        path && !fileExists(path)
    }
    if (missing) {
        error("Модули редактора ${editorVersion} не установлены: ${missing.join(', ')} (unity hub install-modules вернул ${status}). " +
                'На Windows Hub ставит в Program Files только из процесса с правами администратора.')
    }
}

private def ensureUnityHubExecutableExists(String unityHubPath) {
    if (!fileExists(unityHubPath)) {
        error("Unity Hub executable not found at specified path! (${unityHubPath})");
    }
}