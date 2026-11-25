// Ищет отдельный Unity CLI в окружении агента, включая установки вне PATH.
def findExecutable(def options, def env) {
    String configuredPath = options?.unityCliPath ?: env?.UNITY_CLI_PATH
    if (configuredPath) {
        if (!fileExists(configuredPath)) {
            error("Unity CLI not found at configured path: ${configuredPath}")
        }
        return configuredPath
    }

    boolean unix = isUnix()
    String defaultPath
    if (unix && env?.HOME) {
        defaultPath = "${env.HOME}/.unity/bin/unity"
    } else if (!unix && env?.LOCALAPPDATA) {
        defaultPath = "${env.LOCALAPPDATA}\\Unity\\bin\\unity.exe"
    }
    if (defaultPath && fileExists(defaultPath)) return defaultPath

    if (unix) {
        def status = exec label: 'Find Unity CLI on PATH', returnStatus: true,
                script: 'command -v unity >/dev/null 2>&1'
        return status == 0 ? 'unity' : null
    }

    // Unity Editor в Windows называется Unity.exe. Читаем метаданные, чтобы не запускать его при поиске CLI.
    def pathOnWindows = powershell label: 'Find Unity CLI on PATH', returnStdout: true, encoding: 'UTF-8',
            script: '''
Get-Command unity.exe -CommandType Application -All -ErrorAction SilentlyContinue |
    Where-Object { (Get-Item -LiteralPath $_.Source -ErrorAction SilentlyContinue).VersionInfo.FileDescription -eq 'unity' } |
    Select-Object -First 1 -ExpandProperty Source
'''
    return pathOnWindows.trim() ?: null
}

// Возвращает путь редактора нужной версии после проверки модулей сборки.
def prepareEditor(String cli, String version, String revision, List<String> modules) {
    if (!version || !(version ==~ /[A-Za-z0-9._-]+/) || (revision && !(revision ==~ /[A-Za-z0-9._-]+/))) {
        error("Invalid Unity Editor version or changeset: ${version} (${revision})")
    }

    def editor = findInstalledEditor(cli, version)
    if (!editor) {
        def changeset = revision ? " --changeset ${revision}" : ''
        install(cli, "Install Unity Editor ${version}", "install ${version}${changeset}")
        editor = findInstalledEditor(cli, version)
        if (!editor) {
            error("Unity CLI did not report Editor ${version} after installation")
        }
    }

    String editorPath = editor.location
    if (!editorPath || !fileExists(unity.getExePath(editorPath))) {
        error("Unity CLI reported a missing Editor ${version} at: ${editorPath}")
    }
    log.info("Unity CLI found Editor ${version} at '${editorPath}'")
    installRequiredModules(cli, version, editor.architecture, editorPath, modules)
    return editorPath
}

// Читает структурированный список CLI и ищет точное совпадение версии.
private Map findInstalledEditor(String cli, String version) {
    def output = exec label: "List installed Unity Editors (${cli})", returnStdout: true, encoding: 'UTF-8',
            script: cliCommand(cli, 'editors --installed --json --no-banner --non-interactive')
    def result = readJSON text: output, returnPojo: true
    if (!(result instanceof Map) || result.success != true || !(result.data?.items instanceof List)) {
        def details = result instanceof Map ? result.errors : result
        error("Unity CLI failed to list installed Editors at '${cli}': ${details}")
    }
    def editor = result.data.items.find { it.version == version }
    if (!editor && result.data.partial) {
        error("Unity CLI returned a partial Editor list at '${cli}' while looking for ${version}: ${result.errors}")
    }
    return editor
}

// Проверяет штатную раскладку известных модулей и дочерних инструментов Android.
private List<String> requiredModulePaths(String editorPath, String module) {
    if (module == 'windows-mono' && !isUnix()) {
        return [unityHub.getPlaybackEngineDirectory(editorPath, 'WindowsStandaloneSupport')]
    }
    def path = unityHub.getPlaybackEnginePath(editorPath, module)
    if (!path) return []
    if (module != 'android') return [path]

    String toolRoot = path + (isUnix() ? '/' : '\\')
    return [path, toolRoot + 'SDK', toolRoot + 'NDK', toolRoot + 'OpenJDK']
}

// Доустанавливает модули для выбранной архитектуры Editor и проверяет известные каталоги.
private def installRequiredModules(String cli, String version, String architecture, String editorPath, List<String> modules) {
    def missing = modules.findAll { module ->
        def paths = requiredModulePaths(editorPath, module)
        !paths || paths.any { !fileExists(it) }
    }
    if (!missing) return
    if (!isUnix() && missing.contains('windows-mono')) {
        error("Unity Editor ${version} is missing built-in Windows Build Support at '${editorPath}'")
    }
    def architectureOption = architecture in ['x86_64', 'arm64'] ? " --architecture ${architecture}" : ''
    install(cli, "Install Unity modules for ${version} (${missing.join(', ')})",
            "install-modules --editor-version ${version}${architectureOption} -m ${missing.join(' ')} --child-modules")

    def incomplete = missing.findAll { module ->
        requiredModulePaths(editorPath, module).any { !fileExists(it) }
    }
    if (incomplete) {
        error("Unity CLI did not install required modules for Editor ${version}: ${incomplete.join(', ')}")
    }
}

// Сохраняет вывод установки в логе сборки и завершает её при ненулевом коде CLI.
private def install(String cli, String label, String arguments) {
    def status = exec label: label, returnStatus: true,
            script: cliCommand(cli, "${arguments} --non-interactive --yes")
    if (status != 0) error("${label}: Unity CLI at '${cli}' exited with code ${status}")
}

// Цитирует путь CLI. В bat удваивает %, а @ убирает echo команды перед JSON.
private String cliCommand(String cli, String arguments) {
    return isUnix() ? "'${cli.replace("'", "'\"'\"'")}' ${arguments}" : "@\"${cli.replace('%', '%%')}\" ${arguments}"
}
